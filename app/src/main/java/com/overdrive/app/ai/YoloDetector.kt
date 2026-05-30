package com.overdrive.app.ai

import android.content.Context
import com.overdrive.app.logging.DaemonLogger
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO Detection Result
 */
data class Detection(
    val classId: Int,
    val confidence: Float,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
)

/**
 * YOLO11n TensorFlow Lite Detector — CPU-only (XNNPACK).
 *
 * **Why CPU and not GPU on this hardware.** The Snapdragon 662 / Adreno 610
 * is a unified-memory SoC: the H.265 hardware encoder, the Adreno GPU's
 * compute units, and the CPU all share one DDR4 memory controller. When
 * YOLO ran via the TFLite GPU delegate concurrently with the surveillance
 * recording pipeline, OpenCL kernels saturated Adreno's compute units AND
 * the memory bus simultaneously. The encoder's per-frame input fetch + ref
 * frame access lost bandwidth, which manifested as 200–300 ms eglSwapBuffers
 * stalls on the encoder GL thread — visible in recordings as freeze+skip
 * during event windows where YOLO was busy.
 *
 * Tier 1+2 (separate AI-lane GL thread, PBO async readback) eliminated
 * GL-pipeline contention but did NOT touch the underlying memory-bandwidth
 * contention because YOLO inference itself was still GPU-bound. The only
 * physical bypass is to move inference off the GPU entirely.
 *
 * **Why XNNPACK 4-thread.** TFLite's CPU backend ships XNNPACK by default
 * (since 2.5). On ARM it dispatches to NEON SIMD kernels, and at 4 threads
 * the inference cost on this hardware is ~200–300 ms vs ~50–80 ms via GPU
 * delegate. That is well within {@code AI_COOLDOWN_MS = 500 ms}, so the
 * trigger pathway sees no regression. The 150–200 ms additional latency is
 * invisible to the user-facing trigger contract.
 *
 * **Thread isolation strategy (Android 10/11/12 portable).** The
 * {@code aiExecutor} thread that calls into this detector runs at
 * {@code Process.THREAD_PRIORITY_BACKGROUND} (nice +10). XNNPACK's worker
 * pthreads inherit this nice value at spawn time. The encoder/drainer
 * threads run at {@code THREAD_PRIORITY_FOREGROUND} (nice -2), giving a
 * 12-point CFS priority gradient — the encoder side wins scheduler
 * contention by ~10× weight regardless of which cores either thread lands
 * on. On Android 10 the priority demotion ALSO confines these threads to
 * the {@code background} cpuset (cores 0-3, A53 silver cluster); on
 * Android 11+ EAS scheduling can migrate them under load, but the CFS
 * gradient alone is what's portable and what actually keeps the encoder
 * fed. The aiExecutor ALSO re-applies {@code THREAD_PRIORITY_BACKGROUND}
 * at task entry as a defense against EAS migration that may otherwise
 * reset the thread's priority class on long-lived executors.
 *
 * **Why not NNAPI.** Field-tested on this hardware: ~538 of ~546 ops fall
 * through to XNNPACK on CPU anyway (the NNAPI driver only accelerates a
 * handful of ops). Effective inference time ≈ pure CPU mode minus a small
 * dispatch overhead — no benefit, more code surface, more failure modes.
 *
 * SOTA Implementation properties retained from prior version:
 * - Native C++ ImageProcessor (SIMD-accelerated bilinear resize + normalize)
 * - Pre-allocated buffers (zero GC churn)
 * - Cache-friendly output parsing
 * - Height filter before NMS
 * - Ghost filter (max 50 detections)
 */
class YoloDetector(private val context: Context) {

    private val logger = DaemonLogger.getInstance("YoloDetector")

    private var interpreter: Interpreter? = null

    // Monitor that mutually excludes inference (interp.run) from
    // close() / re-init. Without it, a UI/IPC-thread close() can free
    // the native TFLite interpreter while aiExecutor is mid-detect,
    // causing a SIGSEGV in tensorflowlite_jni. The Java-side null
    // snapshot in the engine guards null-deref but not use-after-free
    // inside the native run.
    private val interpLock = Any()

    // SOTA: Pre-allocate all buffers to avoid GC
    private var inputImageBuffer: TensorImage? = null
    private var outputBuffer: ByteBuffer? = null

    // Reusable shaped input buffer. Re-create only when image dimensions
    // change (rare — quadrant size is fixed at startup). Without this,
    // every detect() allocated a fresh TensorBuffer + ByteBuffer.wrap →
    // ~1 MB short-lived garbage per inference, contradicting the class's
    // "zero GC churn" promise. Class-field allocation + dim guard runs
    // O(1) when dims match.
    private var shapedBufferW: Int = -1
    private var shapedBufferH: Int = -1
    private var shapedBuffer: TensorBuffer? = null
    private var floatOutput: FloatArray? = null

    // Pre-extracted box-coords scratch reused across detect() calls. Sized
    // numBoxes*4 = 33600 floats = 134 KB; allocating it per inference was
    // ~1 MB/s of short-lived heap garbage feeding into the same GC that
    // serves the encoder drainer thread. Detector is called on a single
    // aiExecutor thread; no synchronization needed beyond interpLock.
    private var boxesScratch: FloatArray? = null
    // NMS sort working copy. Replaces sortedByDescending(), which allocated
    // a fresh List + lambda per call.
    private var nmsScratch: Array<Detection?>? = null

    // Model configuration
    private val modelPath = "models/yolo11n.tflite"
    private val inputSize = 640

    // INT8 / FP32 model auto-detection. The Android side stays compatible
    // with both yolo11n.tflite variants (FP32 default, INT8 produced by
    // dev/quantize_yolo_int8.py) — init() inspects the loaded interpreter
    // and routes preprocessing accordingly. There is no per-detect()
    // overhead from this; the routing decision is cached.
    //
    // FP32 path: ImageProcessor does Resize + Normalize(0..1); output is
    //   already float, no dequant needed.
    // INT8 path: ImageProcessor does Resize only (the int8 input tensor's
    //   embedded scale/zero_point handles the [0,255] -> int8 mapping
    //   inside the interpreter); output is int8 and must be dequantized
    //   to float via (raw - zeroPoint) * scale before parseOutput.
    //
    // outputIsQuantized governs the output post-processing path. For
    // YOLOv11n int8 export the Ultralytics pipeline emits a single output
    // tensor with shape [1, 84, 8400] of dtype UINT8 with non-trivial
    // (scale, zero_point) — same shape as FP32 so parseOutput's iteration
    // is unchanged after dequant.
    private var inputIsQuantized = false
    private var outputIsQuantized = false
    private var outputScale = 0f
    private var outputZeroPoint = 0
    private var int8OutputBuffer: ByteArray? = null  // raw output for int8 path

    // SOTA: Native C++ image processor (SIMD-accelerated bilinear resize
    // + optional normalize). Built lazily in init() once we know the
    // input tensor dtype.
    private var imageProcessor: ImageProcessor? = null
    
    // COCO class IDs
    companion object {
        const val CLASS_PERSON = 0
        const val CLASS_BICYCLE = 1
        const val CLASS_CAR = 2
        const val CLASS_MOTORCYCLE = 3
        const val CLASS_AIRPLANE = 4
        const val CLASS_BUS = 5
        const val CLASS_TRAIN = 6
        const val CLASS_TRUCK = 7
        const val CLASS_BOAT = 8
        const val CLASS_BIRD = 14
        const val CLASS_CAT = 15
        const val CLASS_DOG = 16
        const val CLASS_HORSE = 17
        const val CLASS_SHEEP = 18
        const val CLASS_COW = 19
        const val CLASS_ELEPHANT = 20
        const val CLASS_BEAR = 21
        const val CLASS_ZEBRA = 22
        const val CLASS_GIRAFFE = 23

        // No-capture lambda → static singleton; reused across all nms() calls
        // so the comparator instance never allocates per inference.
        private val NMS_COMPARATOR: java.util.Comparator<Detection?> =
            java.util.Comparator { a, b ->
                val ac = a?.confidence ?: Float.NEGATIVE_INFINITY
                val bc = b?.confidence ?: Float.NEGATIVE_INFINITY
                bc.compareTo(ac)
            }
    }
    
    /**
     * Initialize the detector. CPU-only (XNNPACK 4-thread). See class
     * doc for the rationale on why GPU/NNAPI tiers were removed.
     */
    fun init(): Boolean {
        try {
            // Load TFLite's CPU JNI library explicitly — daemon-mode
            // processes don't always run JVM-side static linking
            // automatically. tensorflowlite_gpu_jni is intentionally not
            // loaded; the GPU delegate is no longer a dependency.
            try {
                System.loadLibrary("tensorflowlite_jni")
                logger.info("TFLite native library loaded (CPU-only)")
            } catch (e: UnsatisfiedLinkError) {
                logger.error("Failed to load TFLite native library: ${e.message}")
                return false
            }

            val modelFile = FileUtil.loadMappedFile(context, modelPath)

            // CPU XNNPACK, 4 threads. Worker pthreads inherit nice +10
            // from the calling aiExecutor thread; the 12-point CFS gradient
            // versus the encoder/drainer (nice -2) keeps the encoder fed
            // even when YOLO threads happen to land on the same physical
            // core.
            try {
                val cpuOptions = Interpreter.Options()
                cpuOptions.setNumThreads(4)
                interpreter = Interpreter(modelFile, cpuOptions)
                interpreter!!.allocateTensors()
            } catch (e: Exception) {
                logger.error("Failed to initialize TFLite CPU interpreter: ${e.message}", e)
                return false
            }

            // Auto-detect FP32 vs INT8 model. Probe input tensor 0 + output
            // tensor 0 dtype. yolo11n's standard export uses FLOAT32; the
            // dev/quantize_yolo_int8.py script produces a UINT8/UINT8 variant.
            val interp = interpreter!!
            val inputTensor = interp.getInputTensor(0)
            val outputTensor = interp.getOutputTensor(0)
            val inputDtype = inputTensor.dataType()
            val outputDtype = outputTensor.dataType()
            inputIsQuantized = (inputDtype == DataType.UINT8 || inputDtype == DataType.INT8)
            outputIsQuantized = (outputDtype == DataType.UINT8 || outputDtype == DataType.INT8)
            if (outputIsQuantized) {
                val q = outputTensor.quantizationParams()
                outputScale = q.scale
                outputZeroPoint = q.zeroPoint
            }

            // Build the preprocessing pipeline that matches the model's
            // expected input dtype:
            //   - FP32 model: resize + normalize (0..255 -> 0.0..1.0)
            //   - INT8 model: resize only; the interpreter's embedded
            //     input quantization params handle the uint8 -> int8 mapping
            //     internally with no host-side normalize step.
            inputImageBuffer = TensorImage(DataType.UINT8)
            imageProcessor = if (inputIsQuantized) {
                ImageProcessor.Builder()
                    .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                    .build()
            } else {
                ImageProcessor.Builder()
                    .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                    .add(NormalizeOp(0f, 255f))
                    .build()
            }

            // Pre-allocate output buffer sized to the actual tensor dtype.
            // FP32: 4 bytes/element. INT8/UINT8: 1 byte/element.
            val outputElements = 84 * 8400
            val outputBytes = outputElements * if (outputIsQuantized) 1 else 4
            outputBuffer = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
            if (outputIsQuantized) {
                int8OutputBuffer = ByteArray(outputElements)
            }

            val mode = if (inputIsQuantized && outputIsQuantized) "INT8"
                       else if (!inputIsQuantized && !outputIsQuantized) "FP32"
                       else "MIXED($inputDtype/$outputDtype)"
            logger.info("CPU XNNPACK initialized (4 threads, $mode model, " +
                    (if (outputIsQuantized) "outScale=$outputScale outZp=$outputZeroPoint, " else "") +
                    "encoder-isolated via nice gradient)")

            logger.info("Model loaded successfully ($mode)")
            return true
        } catch (e: Exception) {
            logger.error("Failed to load model: ${e.message}", e)
            return false
        }
    }
    
    /**
     * SOTA Detection with native C++ preprocessing
     * 
     * @param rgbData RGB888 byte array (vertically flipped for OpenGL)
     * @param width Image width
     * @param height Image height
     * @param confThreshold Confidence threshold
     * @param detectPerson Detect person class
     * @param detectCar Detect vehicle classes
     * @param detectAnimal Detect animal classes
     * @param detectBike Detect bicycle/motorcycle
     * @param minRelativeHeight Minimum object height relative to QUADRANT (SOTA: 15% rule)
     *                          This is applied per-quadrant in 2x2 mosaic grid
     */
    fun detect(
        rgbData: ByteArray,
        width: Int,
        height: Int,
        confThreshold: Float = 0.25f,
        detectPerson: Boolean = true,
        detectCar: Boolean = true,
        detectAnimal: Boolean = false,
        detectBike: Boolean = false,
        minRelativeHeight: Float = 0.15f  // SOTA: 15% of QUADRANT height (~5m for person)
    ): List<Detection> {

        // FIX (Bug B): if the caller has disabled every detectable class, skip the
        // entire inference path. This is the belt-and-braces defence behind the
        // engine's aiEnabled gate and ensures any future caller path benefits too.
        if (!detectPerson && !detectCar && !detectAnimal && !detectBike) {
            return emptyList()
        }

        if (width <= 0 || height <= 0) return emptyList()

        // CRITICAL: Color channel handling
        // GpuDownscaler outputs RGB from OpenGL (RGBA_8888 with A dropped)
        // The data is already in RGB format - NO SWAP NEEDED
        // Image is now correctly oriented (vertical flip applied in GpuDownscaler)
        val processedData = rgbData  // Use directly - already RGB from GpuDownscaler

        // Synchronize against close(). Inside the lock we're guaranteed the
        // native interpreter is alive for the duration of run(). Lock cost
        // on the single-thread aiExecutor is uncontended steady-state; the
        // brief contention with close() is fine — close happens rarely
        // (toggle off, daemon shutdown).
        val output: FloatArray
        synchronized(interpLock) {
            val interp = interpreter ?: return emptyList()

            // Reuse the shaped TensorBuffer across calls. Re-allocate only on
            // dimension change (rare). Same for the float output array.
            var sb = shapedBuffer
            if (sb == null || shapedBufferW != width || shapedBufferH != height) {
                sb = TensorBuffer.createFixedSize(intArrayOf(height, width, 3), DataType.UINT8)
                shapedBuffer = sb
                shapedBufferW = width
                shapedBufferH = height
            }
            sb.loadBuffer(ByteBuffer.wrap(processedData))

            inputImageBuffer!!.load(sb)

            // SOTA: Process with native C++ ops. Pipeline differs by model
            // dtype: FP32 path normalizes 0..255 -> 0.0..1.0; INT8 path is
            // resize-only and the interpreter's input quantization handles
            // the uint8 mapping internally.
            val tensorImage = imageProcessor!!.process(inputImageBuffer)

            // Run inference (CPU XNNPACK). interp.run() blocks until the
            // last layer is computed; there's no async/queue model on the
            // CPU backend (unlike the previous GPU delegate).
            outputBuffer!!.rewind()
            interp.run(tensorImage.buffer, outputBuffer)
            outputBuffer!!.rewind()

            var fo = floatOutput
            if (fo == null || fo.size != 84 * 8400) {
                fo = FloatArray(84 * 8400)
                floatOutput = fo
            }

            if (outputIsQuantized) {
                // INT8 output path: bulk-copy the byte tensor to a Java
                // byte[] in one JNI hop, then dequantize to float in
                // Java loop. Dequant: f = (raw - zeroPoint) * scale.
                // Cost: 84*8400 = 705,600 multiplications (~3-5 ms on
                // Cortex-A53), still much cheaper than the FP32 model's
                // larger XNNPACK kernel set inside interp.run().
                val raw = int8OutputBuffer!!
                outputBuffer!!.get(raw, 0, raw.size)
                // For UINT8 outputs, raw value is in [0, 255]; for INT8,
                // ByteBuffer.get returns signed [-128, 127] which is
                // already the correct interpretation. The interpreter's
                // quantization params encode which dtype was used.
                val scale = outputScale
                val zp = outputZeroPoint
                val outDtype = interp.getOutputTensor(0).dataType()
                if (outDtype == DataType.UINT8) {
                    var i = 0
                    while (i < raw.size) {
                        // Unsigned read: raw[i] is a Java signed byte; mask
                        // with 0xFF to get the [0, 255] value the model
                        // produced.
                        fo[i] = ((raw[i].toInt() and 0xFF) - zp) * scale
                        i++
                    }
                } else {
                    var i = 0
                    while (i < raw.size) {
                        fo[i] = (raw[i].toInt() - zp) * scale
                        i++
                    }
                }
            } else {
                // FP32 output path: bulk-copy from direct ByteBuffer to the
                // Java float[] in one JNI call.
                outputBuffer!!.asFloatBuffer().get(fo)
            }
            output = fo
        }

        return parseOutput(
            output, width, height, confThreshold,
            detectPerson, detectCar, detectAnimal, detectBike, minRelativeHeight
        )
    }
    
    /**
     * SOTA: Cache-friendly output parsing
     * 
     * Optimized memory access pattern to minimize cache misses.
     * Processes output in channel-major order to keep memory accesses sequential.
     */
    private fun parseOutput(
        output: FloatArray,
        imgWidth: Int,
        imgHeight: Int,
        confThreshold: Float,
        detectPerson: Boolean,
        detectCar: Boolean,
        detectAnimal: Boolean,
        detectBike: Boolean,
        minRelativeHeight: Float
    ): List<Detection> {

        val numBoxes = 8400
        val numClasses = 80

        val scaleX = imgWidth.toFloat() / inputSize
        val scaleY = imgHeight.toFloat() / inputSize
        val quadrantHeight = imgHeight / 2
        val quadrantWidth = imgWidth / 2

        // Class-membership bitmask. Every COCO class we care about has id < 24,
        // so a single Long bit-tests in O(1) without allocating an IntRange or
        // a List inside the per-detection loop.
        var wantedMask = 0L
        if (detectPerson) wantedMask = wantedMask or (1L shl CLASS_PERSON)
        if (detectCar) {
            wantedMask = wantedMask or (1L shl CLASS_CAR) or (1L shl CLASS_BUS) or
                    (1L shl CLASS_TRUCK) or (1L shl CLASS_TRAIN) or
                    (1L shl CLASS_BOAT) or (1L shl CLASS_AIRPLANE) or
                    (1L shl CLASS_MOTORCYCLE)
        }
        if (detectBike) wantedMask = wantedMask or (1L shl CLASS_BICYCLE)
        if (detectAnimal) {
            // 14..23 inclusive
            for (c in CLASS_BIRD..CLASS_GIRAFFE) wantedMask = wantedMask or (1L shl c)
        }

        // Pre-thresholded distance filter values. minRelativeHeight is the
        // base; cars use 1.33×, bikes 0.7× — compute once, compare in loop.
        val carWidthThreshold = minRelativeHeight * 1.33f
        val bikeHeightThreshold = minRelativeHeight * 0.7f

        // Reuse pre-extracted box-coords scratch (134 KB). Re-allocate only
        // if numBoxes ever changes (which it can't with a fixed YOLO11n
        // model, but the guard costs nothing).
        var boxes = boxesScratch
        if (boxes == null || boxes.size != numBoxes * 4) {
            boxes = FloatArray(numBoxes * 4)
            boxesScratch = boxes
        }
        for (i in 0 until numBoxes) {
            val base = i * 4
            boxes[base] = output[i]                       // cx
            boxes[base + 1] = output[numBoxes + i]        // cy
            boxes[base + 2] = output[2 * numBoxes + i]    // w
            boxes[base + 3] = output[3 * numBoxes + i]    // h
        }

        val detections = ArrayList<Detection>(16)

        for (i in 0 until numBoxes) {
            val base = i * 4
            val cx = boxes[base]
            val cy = boxes[base + 1]
            val w = boxes[base + 2]
            val h = boxes[base + 3]

            var bestConf = 0f
            var bestClass = -1
            for (c in 0 until numClasses) {
                val conf = output[(4 + c) * numBoxes + i]
                if (conf > bestConf) {
                    bestConf = conf
                    bestClass = c
                }
            }

            if (bestConf < confThreshold) continue
            if (bestClass < 0 || bestClass >= 64) continue
            if ((wantedMask and (1L shl bestClass)) == 0L) continue

            // Convert to image coordinates
            val cxPx = cx * inputSize
            val cyPx = cy * inputSize
            val wPx = w * inputSize
            val hPx = h * inputSize

            val objX = ((cxPx - wPx / 2) * scaleX).toInt().coerceIn(0, imgWidth)
            val objY = ((cyPx - hPx / 2) * scaleY).toInt().coerceIn(0, imgHeight)
            val objW = (wPx * scaleX).toInt().coerceIn(0, imgWidth - objX)
            val objH = (hPx * scaleY).toInt().coerceIn(0, imgHeight - objY)

            // Quadrant-relative distance filter (2×2 mosaic). Inlined to a
            // single `when` over bestClass; thresholds were precomputed above.
            val relH = if (quadrantHeight > 0) objH.toFloat() / quadrantHeight else 0f
            val relW = if (quadrantWidth > 0) objW.toFloat() / quadrantWidth else 0f
            val passes = when (bestClass) {
                CLASS_PERSON -> relH >= minRelativeHeight
                CLASS_CAR, CLASS_BUS, CLASS_TRUCK, CLASS_TRAIN -> relW >= carWidthThreshold
                CLASS_BICYCLE, CLASS_MOTORCYCLE -> relH >= bikeHeightThreshold
                else -> relH >= minRelativeHeight
            }
            if (!passes) continue

            detections.add(Detection(bestClass, bestConf, objX, objY, objW, objH))
        }

        // Apply NMS (in-place sort + culling, no per-call lambda allocation).
        val filtered = nms(detections, 0.45f)

        // Ghost filter
        val final = if (filtered.size > 50) {
            logger.warn("Ghost filter: ${filtered.size} > 50, clearing")
            emptyList()
        } else {
            filtered
        }

        // Class-distribution counts. Single pass with bitmask membership tests
        // — replaces four `final.count { ... }` lambda allocations.
        var personCount = 0
        var carCount = 0
        var bikeCount = 0
        var animalCount = 0
        var bestKeptConf = 0f
        var bestKeptClass = -1
        val carMask = (1L shl CLASS_CAR) or (1L shl CLASS_BUS) or
                (1L shl CLASS_TRUCK) or (1L shl CLASS_TRAIN) or
                (1L shl CLASS_BOAT) or (1L shl CLASS_AIRPLANE) or
                (1L shl CLASS_MOTORCYCLE)
        var animalMask = 0L
        for (c in CLASS_BIRD..CLASS_GIRAFFE) animalMask = animalMask or (1L shl c)
        for (idx in final.indices) {
            val det = final[idx]
            val cid = det.classId
            if (cid == CLASS_PERSON) personCount++
            if (cid == CLASS_BICYCLE) bikeCount++
            if (cid in 0..63) {
                val bit = 1L shl cid
                if ((bit and carMask) != 0L) carCount++
                if ((bit and animalMask) != 0L) animalCount++
            }
            if (det.confidence > bestKeptConf) {
                bestKeptConf = det.confidence
                bestKeptClass = cid
            }
        }

        logger.info("Detected ${final.size} objects: person=$personCount car=$carCount bike=$bikeCount animal=$animalCount (max_conf=${"%.3f".format(bestKeptConf)} class=$bestKeptClass)")

        return final
    }
    
    /**
     * Non-Maximum Suppression. In-place sort into the reused scratch array
     * + linear cull. Replaces a `sortedByDescending { ... }` allocation
     * (new ArrayList + lambda capture) on every call.
     */
    private fun nms(detections: ArrayList<Detection>, iouThreshold: Float): List<Detection> {
        val n = detections.size
        if (n <= 1) return detections

        // Borrow / grow the scratch array. Capped via cap-doubling so the
        // worst-case 50-detection ghost-filter limit doesn't make it grow
        // unbounded across rare bursts.
        var scratch = nmsScratch
        if (scratch == null || scratch.size < n) {
            val cap = if (scratch == null) maxOf(64, n) else maxOf(scratch.size * 2, n)
            scratch = arrayOfNulls(cap)
            nmsScratch = scratch
        }
        for (i in 0 until n) scratch[i] = detections[i]

        // Sort descending by confidence on the slice [0, n). Java's
        // Arrays.sort with a Comparator is mergesort/Timsort and operates
        // in-place on the array; the Comparator is a singleton lambda
        // (Kotlin compiles the no-capture lambda to a static instance).
        @Suppress("UNCHECKED_CAST")
        java.util.Arrays.sort(scratch as Array<Detection?>, 0, n, NMS_COMPARATOR)

        val results = ArrayList<Detection>(minOf(n, 16))
        for (i in 0 until n) {
            val det = scratch[i] ?: continue
            var keep = true
            for (j in 0 until results.size) {
                val res = results[j]
                if (det.classId == res.classId && iou(det, res) > iouThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) results.add(det)
            scratch[i] = null  // help GC release Detection refs after this call
        }
        return results
    }

    
    /**
     * Calculate Intersection over Union
     */
    private fun iou(a: Detection, b: Detection): Float {
        val x1 = max(a.x, b.x)
        val y1 = max(a.y, b.y)
        val x2 = min(a.x + a.w, b.x + b.w)
        val y2 = min(a.y + a.h, b.y + b.h)
        
        val interW = max(0, x2 - x1)
        val interH = max(0, y2 - y1)
        val interArea = interW * interH
        
        val area1 = a.w * a.h
        val area2 = b.w * b.h
        val unionArea = area1 + area2 - interArea
        
        return if (unionArea > 0) interArea.toFloat() / unionArea else 0f
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        // Acquiring interpLock blocks until any in-flight detect() releases it.
        // Without this, freeing the native interpreter mid-run would SIGSEGV
        // inside tensorflowlite_jni.
        synchronized(interpLock) {
            interpreter?.close()
            interpreter = null
            // Drop the reused buffers too — they'll be re-allocated on next init().
            shapedBuffer = null
            shapedBufferW = -1
            shapedBufferH = -1
            floatOutput = null
            boxesScratch = null
            nmsScratch = null
        }
    }
}
