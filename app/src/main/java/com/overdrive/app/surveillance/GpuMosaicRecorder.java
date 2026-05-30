package com.overdrive.app.surveillance;

import android.opengl.EGLSurface;
import android.opengl.GLUtils;
import com.overdrive.app.logging.DaemonLogger;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import com.overdrive.app.camera.EGLCore;
import com.overdrive.app.camera.GlUtil;
import com.overdrive.app.telemetry.OverlayBitmapRenderer;
import com.overdrive.app.telemetry.TelemetryDataCollector;
import com.overdrive.app.telemetry.TelemetrySnapshot;

import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * GpuMosaicRecorder - GPU-based 2x2 grid compositor for zero-copy recording.
 * 
 * This class renders a 5120x960 camera strip into a 2560x1920 2x2 grid layout
 * directly to the MediaCodec encoder's input surface. All composition happens
 * on the GPU, achieving 0% CPU usage and 0 GB/s memory bandwidth.
 * 
 * Key features:
 * - Zero-copy GPU path (camera texture → encoder surface)
 * - Branchless fragment shader for optimal GPU performance
 * - Direct rendering to encoder (no intermediate buffers)
 * - 0% CPU usage for video composition
 */
public class GpuMosaicRecorder {
    private static final String TAG = "GpuMosaicRecorder";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // EGL and OpenGL state
    private EGLCore eglCore;
    private EGLSurface encoderSurface;
    private Surface encoderInputSurface;
    
    // PTS comes from the HAL-provided Image.getTimestamp() value, passed in
    // through drawFrame(textureId, timestampNs). The previous EMA-based
    // Time-Base Corrector clamped per-frame deltas to [30 ms, 500 ms] and
    // smoothed at α = 0.1, which was incompatible with rates ≥ ~22 fps —
    // the lower clamp pushed averageDeltaNs toward 33 fps regardless of the
    // real arrival rate, and any 200 ms spike took ~22 frames to recover,
    // producing the rubber-banding/snapback users saw at 15+ fps + high
    // bitrate. Letting the hardware timestamp speak for itself means PTS
    // values exactly mirror real camera cadence.
    
    // OpenGL program and locations
    private int programId;
    private int uCameraTexLocation;
    private int uApaModeLocation;
    private int aPositionLocation;
    private int aTexCoordLocation;
    
    // Vertex data
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    
    // Encoder reference
    private HardwareEventRecorderGpu encoder;
    
    // State
    // volatile + accessed under recordingLock for read-modify-write safety.
    // The double-check around encoder.triggerEventRecording closes the racing
    // start-paths (RecordingModeManager, deferred-listener thread, direct
    // CameraDaemon.startPipeline calls) that previously could land two muxers
    // on disk with timestamps milliseconds apart. The encoder side has its
    // own startStopLock as defense in depth.
    private volatile boolean recording = false;
    private final Object recordingLock = new Object();
    private volatile boolean apaMode = false;  // APA mode: passthrough instead of mosaic split
    private volatile int cameraLayout = 0;  // 0=4-cam, 1=APA passthrough, 2=3-cam
    // Set when setCameraLayout flips on a non-GL thread; consumed inside
    // drawFrame on the encoder GL thread to push the new uniform exactly
    // once. Per GLES2 spec, uniform values are part of the program object,
    // so a write outside drawFrame would be lost (we don't own the GL
    // context elsewhere). The previous code wrote glUniform1f every frame
    // — ~30 driver-side state-dirty calls per second on Adreno 610 for a
    // value that changes maybe twice a session.
    //
    // AtomicBoolean (not volatile boolean) because the consumer uses
    // getAndSet(false): a setCameraLayout that races between the consumer's
    // glUniform1f and its dirty-clear would otherwise be lost (volatile
    // pair has no read-modify-write atomicity), pinning the shader to a
    // stale layout forever. Also reset to true in init() so an EGL
    // reinit's fresh programId picks up the layout on first drawFrame.
    private final java.util.concurrent.atomic.AtomicBoolean apaModeUniformDirty =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    private long lastFrameTime = 0;
    private long frameCount = 0;
    
    // Overlay GL resources
    private int overlayTextureId;
    private int overlayProgramId;
    private int overlayAPositionLoc;
    private int overlayATexCoordLoc;
    private int overlayUTextureLoc;
    private FloatBuffer overlayVertexBuffer;
    private FloatBuffer overlayTexCoordBuffer;

    // Overlay state
    private volatile boolean overlayEnabled = false;
    private OverlayBitmapRenderer overlayRenderer;
    private TelemetryDataCollector telemetryCollector;
    private int overlayFrameCounter = 0;
    private volatile boolean overlayRecordingModeAllowed = false;
    private boolean overlayTextureReady = false;
    private boolean overlayTextureInitialized = false;
    
    // HAL-contention safety valve.
    //
    // The original code skipped a frame any time the *previous* draw exceeded
    // 30 ms three times in a row. That tripped constantly at 25–30 fps + high
    // bitrate (per-frame budget = 33–40 ms; eglSwap fence + AI readback
    // routinely cross 30 ms by themselves), removing 1-in-N frames from the
    // recorded MP4 even when nothing was actually wrong.
    //
    // Under normal operation we now do nothing — eglSwapBuffers is supposed to
    // block when the encoder input queue is full, and acquireLatestImage will
    // drop incoming HAL frames at zero GPU cost when we fall behind.
    //
    // The valve only opens when the BYD native parking-camera app is sharing
    // the HAL with us (set via setHalContentionProbe). In that mode a stalled
    // GL thread fills the gralloc pool and the native AVM loses its preview
    // signal, so we sacrifice one frame to keep the BufferQueue draining.
    // Threshold is derived from the encoder's frame budget × 2, refreshed on
    // every init() so an FPS change scales it correctly.
    private long lastDrawDurationNs = 0;

    // Last PTS handed to the encoder surface. Belt-and-suspenders against
    // the encoder-input-queue lag at HW->NANO domain transition: PTS values
    // queued before the latch fires can have larger timestamps than NANO
    // values that follow, breaking muxer monotonicity. The PTS-source helper
    // in PanoramicCameraGpu already enforces monotonicity within its own
    // sequence, but doesn't see what's still pending in MediaCodec's input
    // queue. Clamping here against the previously-stamped value covers both
    // the cross-domain transition and any future code path that bypasses
    // nextFrameTimestampNs (audit P1).
    private long lastSwappedPtsNs = 0;
    private long maxDrawDurationNs = 100_000_000L;          // refreshed in init()
    private int consecutiveSlowFrames = 0;
    private static final int SLOW_FRAME_SKIP_THRESHOLD = 20; // ~0.7 s at 30 fps
    private int skippedFrames = 0;
    // Default probe — never trips. PanoramicCameraGpu installs the real one
    // pointing at BydCameraCoordinator.isNativeAppActive(); if no one wires
    // it, the safety valve stays inert and eglSwap handles backpressure
    // natively.
    private volatile java.util.function.BooleanSupplier halContentionProbe = () -> false;
    
    // EGL_BAD_SURFACE recovery: track consecutive surface errors to trigger reinit
    private int consecutiveSurfaceErrors = 0;
    private static final int SURFACE_ERROR_REINIT_THRESHOLD = 3;
    private volatile boolean needsReinit = false;

    // Per-stage timing diagnostic for drawFrame.
    //
    // The encoder GL thread's drawFrame() bundles five distinct GPU/CPU
    // operations under one timer in PanoramicCameraGpu.renderLoop's stage
    // log: makeCurrent + clear + mosaic-shader-draw + overlay-upload-and-
    // draw + eglSwapBuffersWithTimestamp. When the bundled "mosaic+swap"
    // worst-frame goes high, we can't tell which sub-stage was responsible.
    //
    // This per-stage timer logs the worst frame in a 30s window (matching
    // the parent stage timer's cadence) and breaks out:
    //   - mc:    makeCurrent
    //   - cls:   clear
    //   - shd:   mosaic shader bind + draw
    //   - ovl:   overlay (texSubImage2D upload + composite draw)
    //   - swap:  eglSwapBuffersWithTimestamp itself (this is what blocks
    //            on encoder input-pool backpressure)
    //
    // Once a stutter event is captured, the dominant sub-stage tells us
    // where to look — overlay upload vs shader draw vs encoder backpressure
    // produce identical "mosaic+swap=207ms" symptoms in the parent log.
    private static final long DRAW_STAGE_LOG_INTERVAL_MS = 30_000L;
    private long drawStageWindowStartMs = 0;
    private long drawStageWorstTotalNs = 0;
    private long drawStageWorstMakeCurrentNs = 0;
    private long drawStageWorstClearNs = 0;
    private long drawStageWorstShaderNs = 0;
    private long drawStageWorstOverlayNs = 0;
    private long drawStageWorstSwapNs = 0;
    private int  drawStageWindowFrames = 0;

    // Per-quadrant strip-X offsets resolved from the camera profile + user role
    // mapping. Default mirrors the legacy Seal layout (TL=Front, TR=Right,
    // BL=Rear, BR=Left). The fragment shader is rebuilt at construction time
    // with these constants baked in — no per-frame uniform write needed.
    private final float[] quadrantStripOffsetX;
    private final String fragmentShader;
    private final int viewportWidth;
    private final int viewportHeight;
    private static final float[] DEFAULT_QUADRANT_STRIP_OFFSET_X = {
        0.75f, 0.50f, 0.00f, 0.25f
    };
    private static final int DEFAULT_VIEWPORT_WIDTH = 2560;
    private static final int DEFAULT_VIEWPORT_HEIGHT = 1920;
    
    // Fullscreen quad vertices (NDC coordinates)
    private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,  // Bottom-left
         1.0f, -1.0f,  // Bottom-right
        -1.0f,  1.0f,  // Top-left
         1.0f,  1.0f   // Top-right
    };
    
    // Texture coordinates (flipped vertically for correct orientation)
    private static final float[] TEX_COORDS = {
        0.0f, 1.0f,  // Bottom-left (flipped to top-left)
        1.0f, 1.0f,  // Bottom-right (flipped to top-right)
        0.0f, 0.0f,  // Top-left (flipped to bottom-left)
        1.0f, 0.0f   // Top-right (flipped to bottom-right)
    };
    
    // Vertex shader - simple passthrough
    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";
    
    // Fragment shader - supports 4-camera mosaic, 3-camera mosaic, and APA passthrough.
    // uApaMode: 0.0 = 4-camera mosaic (Seal: pano_h/pano_l with surfaceMode=0)
    //           1.0 = APA passthrough (single pre-composited image)
    //           2.0 = 3-camera mosaic (Atto 3 default: Rear=0-25%, Side=25-75%, Front=75-100%)
    // 4-cam strip: cam1(Rear)=0.00, cam2(Left)=0.25, cam3(Right)=0.50, cam4(Front)=0.75
    // 3-cam strip: Rear=0.00-0.25, Left+Right=0.25-0.75, Front=0.75-1.00
    /** 4-camera mosaic offsets are baked at construction via
     *  {@link #buildFragmentShader(float[])} so the per-quadrant slice→corner
     *  mapping can be resolved per camera profile (Seal vs Tang) and per
     *  user role mapping. 3-cam and APA branches are layout-independent so
     *  they stay as-is. */
    
    // Overlay vertex shader - simple 2D passthrough
    private static final String OVERLAY_VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";
    
    // Overlay fragment shader - standard sampler2D with alpha (NOT OES)
    private static final String OVERLAY_FRAGMENT_SHADER =
        "precision mediump float;\n" +
        "varying vec2 vTexCoord;\n" +
        "uniform sampler2D uTexture;\n" +
        "void main() {\n" +
        "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
        "}\n";

    public GpuMosaicRecorder() {
        this(null, DEFAULT_VIEWPORT_WIDTH, DEFAULT_VIEWPORT_HEIGHT);
    }

    public GpuMosaicRecorder(float[] quadrantStripOffsetX) {
        this(quadrantStripOffsetX, DEFAULT_VIEWPORT_WIDTH, DEFAULT_VIEWPORT_HEIGHT);
    }

    /**
     * @param quadrantStripOffsetX Per-quadrant strip-X offsets in
     *     {Front, Right, Rear, Left} order. Resolved from
     *     {@code ResolvedCameraConfig.getQuadrantStripOffsetX()}. Pass null
     *     for the legacy Seal default.
     * @param viewportWidth Encoder/mosaic width. Should match the encoder's
     *     configured width — typically {@code panoWidth/2} (2560 on Seal,
     *     2560 on Tang).
     * @param viewportHeight Encoder/mosaic height. Should match the encoder's
     *     configured height — typically {@code panoHeight*2} (1920 on Seal,
     *     1440 on Tang).
     */
    public GpuMosaicRecorder(float[] quadrantStripOffsetX, int viewportWidth, int viewportHeight) {
        this.quadrantStripOffsetX = normalizeOffsets(quadrantStripOffsetX);
        this.fragmentShader = buildFragmentShader(this.quadrantStripOffsetX);
        this.viewportWidth = viewportWidth > 0 ? viewportWidth : DEFAULT_VIEWPORT_WIDTH;
        this.viewportHeight = viewportHeight > 0 ? viewportHeight : DEFAULT_VIEWPORT_HEIGHT;
    }

    /**
     * Initializes the GPU mosaic recorder.
     *
     * @param eglCore EGL context manager
     * @param encoder Hardware encoder that provides the input surface
     */
    public void init(EGLCore eglCore, HardwareEventRecorderGpu encoder) {
        // Release old resources if reinitializing
        if (this.encoderSurface != null && this.eglCore != null) {
            this.eglCore.destroySurface(this.encoderSurface);
            this.encoderSurface = null;
            logger.info("Released old encoder surface for reinitialization");
        }

        this.eglCore = eglCore;
        this.encoder = encoder;

        // Scale the HAL-contention safety valve to the configured fps. Budget
        // is 2 × the per-frame target so a normal eglSwap fence + AI readback
        // never trips it; only sustained backpressure during BYD native AVM
        // contention should cross it.
        int encFps = encoder != null ? encoder.getFps() : 15;
        maxDrawDurationNs = 2L * 1_000_000_000L / Math.max(1, encFps);

        // Reset the swap-time monotonic clamp on every init so a recorder
        // reinit (encoder swap, codec change) doesn't carry over a stale
        // ceiling that would clamp every new frame to a far-future PTS.
        lastSwappedPtsNs = 0;

        // Register callback to sync recording flag when encoder closes file
        encoder.setFileClosedCallback(() -> {
            if (recording) {
                recording = false;
                logger.info("Recording flag reset (encoder closed file)");
            }
            
            // SOTA: Trigger storage cleanup after each file is saved
            try {
                com.overdrive.app.storage.StorageManager storageManager =
                    com.overdrive.app.storage.StorageManager.getInstance();
                
                // Determine if this was a surveillance or manual recording based on output path
                // Surveillance files go to surveillance dir, manual recordings to recordings dir
                if (encoder != null) {
                    String lastPath = encoder.getCurrentOutputPath();
                    if (lastPath != null) {
                        if (lastPath.contains("/surveillance/") || lastPath.contains("event_")) {
                            storageManager.onSurveillanceFileSaved();
                        } else {
                            storageManager.onRecordingFileSaved();
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Storage cleanup after file close failed: " + e.getMessage());
            }
        });
        
        // Get encoder's input surface
        encoderInputSurface = encoder.getInputSurface();
        if (encoderInputSurface == null) {
            throw new RuntimeException("Encoder input surface is null");
        }
        
        // Create EGL surface from encoder surface (with RECORDABLE flag)
        encoderSurface = eglCore.createWindowSurface(encoderInputSurface);
        
        // Compile shaders and create program (fragment shader is profile-baked)
        programId = GlUtil.createProgram(VERTEX_SHADER, fragmentShader);
        if (programId == 0) {
            throw new RuntimeException("Failed to create shader program");
        }
        
        // Get attribute and uniform locations
        aPositionLocation = GLES20.glGetAttribLocation(programId, "aPosition");
        aTexCoordLocation = GLES20.glGetAttribLocation(programId, "aTexCoord");
        uCameraTexLocation = GLES20.glGetUniformLocation(programId, "uCameraTex");
        uApaModeLocation = GLES20.glGetUniformLocation(programId, "uApaMode");
        
        GlUtil.checkGlError("glGetLocation");
        
        // Create vertex buffers
        vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
        texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);
        
        // --- Overlay GL resource initialization ---
        overlayProgramId = GlUtil.createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER);
        if (overlayProgramId == 0) {
            logger.error("Failed to create overlay shader program - overlay disabled");
            overlayEnabled = false;
        } else {
            overlayAPositionLoc = GLES20.glGetAttribLocation(overlayProgramId, "aPosition");
            overlayATexCoordLoc = GLES20.glGetAttribLocation(overlayProgramId, "aTexCoord");
            overlayUTextureLoc = GLES20.glGetUniformLocation(overlayProgramId, "uTexture");
            
            // Generate overlay texture
            int[] texIds = new int[1];
            GLES20.glGenTextures(1, texIds, 0);
            overlayTextureId = texIds[0];
            
            // Overlay quad: top 160px of 1920px frame
            // NDC Y: +1.0 (top) down to +1.0 - 0.1667 = +0.8333
            float[] overlayVertexCoords = {
                -1.0f,  0.8333f,
                 1.0f,  0.8333f,
                -1.0f,  1.0f,
                 1.0f,  1.0f
            };
            // Tex coords flipped Y for correct orientation
            float[] overlayTexCoords = {
                0.0f, 1.0f,
                1.0f, 1.0f,
                0.0f, 0.0f,
                1.0f, 0.0f
            };
            overlayVertexBuffer = GlUtil.createFloatBuffer(overlayVertexCoords);
            overlayTexCoordBuffer = GlUtil.createFloatBuffer(overlayTexCoords);
            
            // Create bitmap renderer
            overlayRenderer = new OverlayBitmapRenderer();
            overlayTextureInitialized = false;
        }
        
        // Set GL clear color once. GL context state, persists across
        // drawFrame calls; the encoder EGL context is exclusive to this
        // recorder. Saves ~one glClearColor call per frame (~30/sec at
        // 30 fps) on the encoder GL thread.
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        // The shader program is fresh; its uApaMode uniform defaults to 0.0f.
        // Force the next drawFrame to re-publish the current cameraLayout so
        // an EGL recovery (release → init) doesn't pin the new programId at
        // layout 0 when the field-level cameraLayout != 0.
        apaModeUniformDirty.set(true);

        logger.info("GpuMosaicRecorder initialized (encoder codec=" +
            (encoder.isHevcCodec() ? "H.265" : "H.264") + ")");
    }
    
    /**
     * Draws a frame from the camera texture to the encoder surface.
     * 
     * This performs the GPU-based 2x2 grid composition and submits the
     * result directly to the encoder. All processing happens in VRAM.
     * 
     * NOTE: In SOTA mode, this ALWAYS renders (encoder is always running).
     * The recording flag only controls whether frames are saved to file.
     * 
     * @param cameraTextureId OpenGL texture ID containing camera frame
     */
    /**
     * Draws a frame from the camera texture to the encoder surface.
     * 
     * This performs the GPU-based 2x2 grid composition and submits the
     * result directly to the encoder. All processing happens in VRAM.
     * 
     * NOTE: In SOTA mode, this ALWAYS renders (encoder is always running).
     * The recording flag only controls whether frames are saved to file.
     * 
     * IMPORTANT: If the encoder is backed up (eglSwapBuffers blocking due to
     * full encoder input buffer), we skip rendering to prevent blocking the GL thread.
     * This keeps the camera HAL's BufferQueue flowing, which prevents the BYD native
     * parking camera app from losing video signal during prolonged recording.
     * 
     * @param cameraTextureId OpenGL texture ID containing camera frame
     * @param frameTimestampNs HAL-provided sensor timestamp in nanoseconds
     *                         (from Image.getTimestamp()). Stamped onto the
     *                         encoder surface via eglPresentationTimeANDROID
     *                         so MediaCodec emits PTS values that match real
     *                         camera cadence — no smoothing, no EMA, no clamps.
     */
    public void drawFrame(int cameraTextureId, long frameTimestampNs) {
        // Check if initialized
        if (eglCore == null || encoderSurface == null) {
            // Not initialized yet - skip silently
            return;
        }

        // HAL-CONTENTION SAFETY VALVE: only trips when the BYD native AVM app
        // is actively sharing the camera. In normal operation we let
        // eglSwapBuffers block (its native backpressure mechanism) and let
        // acquireLatestImage() drop upstream frames at zero GPU cost.
        if (consecutiveSlowFrames >= SLOW_FRAME_SKIP_THRESHOLD
                && halContentionProbe.getAsBoolean()) {
            skippedFrames++;
            consecutiveSlowFrames = 0;
            if (skippedFrames % 10 == 1) {
                logger.warn("HAL contention: skipped " + skippedFrames +
                    " frames to keep BYD native AVM signal alive (last draw=" +
                    (lastDrawDurationNs / 1_000_000) + "ms, threshold=" +
                    (maxDrawDurationNs / 1_000_000) + "ms × " +
                    SLOW_FRAME_SKIP_THRESHOLD + ")");
            }
            return;
        }

        // SOTA: Always render to encoder (for pre-record buffer)
        // The encoder decides whether to write to file or just buffer

        long startTime = System.nanoTime();

        // Per-stage diagnostic timing. Cheap (~50ns nanoTime + Long math
        // per call); only the worst frame in a 30s window is logged.
        long t0 = startTime;

        // Make encoder surface current
        eglCore.makeCurrent(encoderSurface);
        long tAfterMakeCurrentNs = System.nanoTime();

        // Set viewport to encoder resolution (profile-driven: Seal=2560x1920, Tang=2560x1440)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight);

        // Clear. glClearColor was hoisted to init() — it's GL context state
        // that persists; the encoder's EGL context is exclusive to this
        // recorder so no other code path can change it under us.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        long tAfterClearNs = System.nanoTime();

        // Use our shader program
        GLES20.glUseProgram(programId);

        // Bind camera texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(uCameraTexLocation, 0);
        // uApaMode uniform: rewrite only on layout change. Per GLES2 spec
        // uniform values are part of the program object and persist across
        // glUseProgram of the same program. setCameraLayout sets the dirty
        // flag from another thread; we consume it here on the GL thread.
        // Atomic claim of the dirty bit. If a setCameraLayout races between
        // the load and the uniform write, getAndSet(false) ensures the
        // racing setter's dirty=true survives (the racer sets it AFTER our
        // getAndSet returned true, so the next drawFrame will replay).
        // Read cameraLayout AFTER the claim so we see the latest value the
        // racer published before flipping the dirty bit.
        if (apaModeUniformDirty.compareAndSet(true, false)) {
            GLES20.glUniform1f(uApaModeLocation, (float) cameraLayout);
        }

        // Set up vertex attributes
        GLES20.glEnableVertexAttribArray(aPositionLocation);
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(aTexCoordLocation);
        GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        // Draw fullscreen quad (shader does the 2x2 grid mapping)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // Disable vertex arrays
        GLES20.glDisableVertexAttribArray(aPositionLocation);
        GLES20.glDisableVertexAttribArray(aTexCoordLocation);
        long tAfterShaderNs = System.nanoTime();

        // OVERLAY PASS: Composite telemetry overlay if enabled
        if (overlayEnabled && overlayRecordingModeAllowed && overlayRenderer != null) {
            overlayFrameCounter++;
            try {
                // Update bitmap every 3rd frame (~5 FPS at 15 FPS recording)
                if ((overlayFrameCounter == 1 || overlayFrameCounter % 3 == 0) && telemetryCollector != null) {
                    TelemetrySnapshot snapshot = telemetryCollector.getLatestSnapshot();
                    overlayRenderer.renderFrame(snapshot, overlayFrameCounter / 3);
                }
                
                // Upload new bitmap to texture ONLY when the double buffer actually swapped.
                // swapAndGetFront() returns null when no new content is available,
                // avoiding the expensive texImage2D/texSubImage2D call on unchanged frames.
                android.graphics.Bitmap overlayBitmap = overlayRenderer.swapAndGetFront();
                if (overlayBitmap != null && !overlayBitmap.isRecycled()) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
                    if (!overlayTextureInitialized) {
                        // First upload: allocate GPU texture storage with texImage2D
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                        overlayTextureInitialized = true;
                    } else {
                        // Subsequent uploads: reuse existing texture storage with texSubImage2D
                        // This avoids GPU texture reallocation on every update
                        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, overlayBitmap);
                    }
                    overlayTextureReady = true;
                    
                    if (overlayFrameCounter <= 3) {
                        logger.info("Overlay: uploaded frame " + overlayFrameCounter + 
                            " bitmap=" + overlayBitmap.getWidth() + "x" + overlayBitmap.getHeight());
                    }
                }
                
                // Draw overlay quad EVERY frame (reuses last uploaded texture)
                if (overlayTextureReady) {
                    GLES20.glEnable(GLES20.GL_BLEND);
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                    
                    GLES20.glUseProgram(overlayProgramId);
                    
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
                    GLES20.glUniform1i(overlayUTextureLoc, 1);
                    
                    GLES20.glEnableVertexAttribArray(overlayAPositionLoc);
                    GLES20.glVertexAttribPointer(overlayAPositionLoc, 2, GLES20.GL_FLOAT, false, 0, overlayVertexBuffer);
                    GLES20.glEnableVertexAttribArray(overlayATexCoordLoc);
                    GLES20.glVertexAttribPointer(overlayATexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, overlayTexCoordBuffer);
                    
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    
                    GLES20.glDisableVertexAttribArray(overlayAPositionLoc);
                    GLES20.glDisableVertexAttribArray(overlayATexCoordLoc);
                    GLES20.glDisable(GLES20.GL_BLEND);
                }
            } catch (Exception e) {
                // Skip overlay on error, never drop frame
                if (overlayFrameCounter <= 5) {
                    logger.error("Overlay draw error: " + e.getMessage(), e);
                }
            }
        }
        long tAfterOverlayNs = System.nanoTime();

        // Push the HAL sensor timestamp straight through. MediaCodec uses the
        // value stamped on the encoder surface via eglPresentationTimeANDROID
        // to compute PTS, so the recorded MP4 inherits true camera cadence
        // without any software smoothing/EMA layer in between.
        //
        // Swap-time monotonic clamp: if the candidate is not strictly greater
        // than the previously-stamped value, bump by 1 ms. This guards the
        // muxer against any path that produces a non-monotonic PTS — most
        // notably the HW→NANO clock-domain transition where queued frames
        // already inside MediaCodec's input pool carry pre-transition
        // timestamps that can sequence-wise leapfrog post-transition ones.
        long stampedPtsNs = frameTimestampNs;
        if (stampedPtsNs <= lastSwappedPtsNs) {
            stampedPtsNs = lastSwappedPtsNs + 1_000_000L;  // +1 ms
        }
        lastSwappedPtsNs = stampedPtsNs;

        try {
            eglCore.swapBuffersWithTimestamp(encoderSurface, stampedPtsNs);
            consecutiveSurfaceErrors = 0;  // Reset on success
        } catch (RuntimeException e) {
            consecutiveSurfaceErrors++;
            if (consecutiveSurfaceErrors >= SURFACE_ERROR_REINIT_THRESHOLD) {
                logger.error("Encoder surface dead after " + consecutiveSurfaceErrors +
                    " consecutive errors, requesting reinit");
                needsReinit = true;
                encoderSurface = null;  // Prevent further attempts
                return;
            }
            if (consecutiveSurfaceErrors <= 3) {
                logger.warn("swapBuffers failed (" + consecutiveSurfaceErrors + "): " + e.getMessage());
            }
            return;
        }
        long tAfterSwapNs = System.nanoTime();

        // Track draw duration to detect encoder backpressure
        long elapsedNs = tAfterSwapNs - startTime;
        lastDrawDurationNs = elapsedNs;

        if (elapsedNs > maxDrawDurationNs) {
            consecutiveSlowFrames++;
        } else {
            consecutiveSlowFrames = 0;
        }

        // Per-stage diagnostic: track the worst frame in a 30s window so we
        // can localize the dominant cost on bad frames. The PARENT stage
        // timer in PanoramicCameraGpu sees this whole method as
        // "mosaic+swap"; if its worst frame exceeds the per-frame budget,
        // this log will tell us which sub-stage was responsible (overlay
        // upload? swap blocking on encoder backpressure? shader draw?).
        long stageTotalNs   = tAfterSwapNs       - t0;
        long stageMakeNs    = tAfterMakeCurrentNs - t0;
        long stageClearNs   = tAfterClearNs      - tAfterMakeCurrentNs;
        long stageShaderNs  = tAfterShaderNs     - tAfterClearNs;
        long stageOverlayNs = tAfterOverlayNs    - tAfterShaderNs;
        long stageSwapNs    = tAfterSwapNs       - tAfterOverlayNs;
        drawStageWindowFrames++;
        if (stageTotalNs > drawStageWorstTotalNs) {
            drawStageWorstTotalNs       = stageTotalNs;
            drawStageWorstMakeCurrentNs = stageMakeNs;
            drawStageWorstClearNs       = stageClearNs;
            drawStageWorstShaderNs      = stageShaderNs;
            drawStageWorstOverlayNs     = stageOverlayNs;
            drawStageWorstSwapNs        = stageSwapNs;
        }
        long nowMsForStage = System.currentTimeMillis();
        if (drawStageWindowStartMs == 0) {
            drawStageWindowStartMs = nowMsForStage;
        } else if (nowMsForStage - drawStageWindowStartMs >= DRAW_STAGE_LOG_INTERVAL_MS) {
            logger.info(String.format(
                    "DrawStage(worst/30s): total=%dms mc=%dms cls=%dms shd=%dms ovl=%dms swap=%dms (frames=%d)",
                    drawStageWorstTotalNs       / 1_000_000,
                    drawStageWorstMakeCurrentNs / 1_000_000,
                    drawStageWorstClearNs       / 1_000_000,
                    drawStageWorstShaderNs      / 1_000_000,
                    drawStageWorstOverlayNs     / 1_000_000,
                    drawStageWorstSwapNs        / 1_000_000,
                    drawStageWindowFrames));
            drawStageWorstTotalNs = 0;
            drawStageWorstMakeCurrentNs = 0;
            drawStageWorstClearNs = 0;
            drawStageWorstShaderNs = 0;
            drawStageWorstOverlayNs = 0;
            drawStageWorstSwapNs = 0;
            drawStageWindowFrames = 0;
            drawStageWindowStartMs = nowMsForStage;
        }

        // Update stats (only count if actually recording to file)
        if (recording) {
            lastFrameTime = System.currentTimeMillis();
            frameCount++;
        }


    }
    
    /**
     * Starts recording to a file.
     * 
     * @param outputPath Path for the output MP4 file
     */
    public void startRecording(String outputPath) {
        synchronized (recordingLock) {
            if (recording) {
                logger.warn("Already recording");
                return;
            }

            // Start encoder recording (with pre-record buffer flush)
            if (encoder != null && encoder.triggerEventRecording(outputPath, 5000)) {  // Default 5 sec post-record
                recording = true;
                frameCount = 0;

                // SOTA: Notify StorageManager that recording is active (for periodic cleanup)
                try {
                    com.overdrive.app.storage.StorageManager.getInstance().setRecordingActive(true);
                } catch (Exception e) {
                    logger.warn("Could not set recording active state: " + e.getMessage());
                }

                logger.info("Recording started: " + outputPath + " (codec=" +
                    (encoder.isHevcCodec() ? "H.265" : "H.264") + ")");
            } else {
                logger.error("Failed to start encoder recording");
            }
        }
    }

    /**
     * Triggers event recording with pre-record buffer flush.
     * Alias for startRecording for API compatibility.
     */
    public void triggerEventRecording(String outputPath, long postRecordDurationMs) {
        synchronized (recordingLock) {
            if (recording) {
                logger.warn("Already recording");
                return;
            }

            // Start encoder recording (with pre-record buffer flush)
            if (encoder != null && encoder.triggerEventRecording(outputPath, postRecordDurationMs)) {
                recording = true;
                frameCount = 0;
                logger.info("Recording started: " + outputPath);
            } else {
                logger.error("Failed to start encoder recording");
            }
        }
    }
    
    /**
     * Starts recording (generates automatic filename).
     */
    public void startRecording() {
        startRecording(null, "cam");
    }
    
    /**
     * Starts recording with custom output directory and filename prefix.
     * 
     * @param outputDir Custom output directory (null for default recordings dir)
     * @param prefix Filename prefix (e.g., "cam", "proximity", "event")
     */
    public void startRecording(java.io.File outputDir, String prefix) {
        // Two-phase guard for the directory+prefix overload:
        //
        // Phase 1 (this check, intentionally unlocked): cheap volatile read.
        //   If recording is already true, skip mkdirs/ensureSpace/timestamp
        //   generation — work that's irrelevant once another caller has
        //   started. This is a performance optimization, NOT the correctness
        //   guarantee.
        //
        // Phase 2 (inside the inner startRecording(outputPath) call): the
        //   recordingLock-protected re-check is the authoritative one. If two
        //   callers both pass Phase 1, both will compute their own timestamps,
        //   but only the first to acquire recordingLock will start the encoder
        //   and create a file. The second caller is rejected at the inner
        //   guard and discards its timestamp harmlessly — the wasted work is
        //   bounded to a few mkdirs and a SimpleDateFormat call, which is
        //   acceptable to keep this method lock-free during normal operation.
        //
        // The duplicate-files-on-disk symptom this whole structure prevents
        // came from an earlier version where neither phase used a lock; the
        // encoder's startStopLock alone was insufficient because the wrapper's
        // recording flag had its own race window.
        if (recording) {
            logger.warn("Already recording — ignoring redundant start (dir=" +
                (outputDir != null ? outputDir.getName() : "default") + ", prefix=" + prefix + ")");
            return;
        }
        // SOTA: Use StorageManager for recordings directory and auto-cleanup
        try {
            com.overdrive.app.storage.StorageManager storageManager =
                com.overdrive.app.storage.StorageManager.getInstance();
            
            // Use provided directory or default to recordings dir
            java.io.File targetDir = outputDir != null ? outputDir : storageManager.getRecordingsDir();
            
            // Ensure directory exists
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            
            // Reserve ~100MB for new recording
            storageManager.ensureRecordingsSpace(100 * 1024 * 1024);
            
            // Generate filename with timestamp
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            String filename = prefix + "_" + timestamp + ".mp4";
            
            // Use target directory
            String outputPath = new java.io.File(targetDir, filename).getAbsolutePath();
            startRecording(outputPath);
        } catch (Exception e) {
            logger.error("Failed to start recording: " + e.getMessage());
            // Fallback to legacy path
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            String filename = (prefix != null ? prefix : "cam") + "_" + timestamp + ".mp4";
            startRecording("/storage/emulated/0/Android/data/com.overdrive.app/files/" + filename);
        }
    }
    
    /**
     * Stops recording.
     */
    public void stopRecording() {
        if (!recording) {
            return;
        }
        
        recording = false;
        
        // SOTA: Notify StorageManager that recording is inactive
        try {
            com.overdrive.app.storage.StorageManager.getInstance().setRecordingActive(false);
        } catch (Exception e) {
            logger.warn("Could not set recording inactive state: " + e.getMessage());
        }
        
        // Stop encoder recording
        if (encoder != null) {
            encoder.stopRecording();
        }
    }
    
    /**
     * Stops recording with post-record support.
     * 
     * @param immediate If true, stops immediately. If false, uses post-record.
     */
    public void stopEventRecording(boolean immediate, long postRecordDurationMs) {
        if (!recording) {
            return;
        }
        
        recording = false;
        
        // SOTA: Notify StorageManager that recording is inactive
        try {
            com.overdrive.app.storage.StorageManager.getInstance().setRecordingActive(false);
        } catch (Exception e) {
            logger.warn("Could not set recording inactive state: " + e.getMessage());
        }
        
        // Stop encoder recording
        if (encoder != null) {
            encoder.stopEventRecording(immediate, postRecordDurationMs);
        }
        
        logger.info(String.format("Recording stopped. Total frames: %d", frameCount));
    }
    
    /**
     * Checks if currently recording.
     * 
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return recording;
    }
    
    /**
     * Gets the timestamp of the last rendered frame.
     * 
     * @return Timestamp in milliseconds
     */
    public long getLastFrameTime() {
        return lastFrameTime;
    }
    
    /**
     * Gets the total number of frames rendered.
     * 
     * @return Frame count
     */
    public long getFrameCount() {
        return frameCount;
    }
    
    /**
     * Gets the encoder instance.
     * 
     * @return Hardware encoder
     */
    public HardwareEventRecorderGpu getEncoder() {
        return encoder;
    }
    
    public void setOverlayEnabled(boolean enabled) {
        this.overlayEnabled = enabled;
    }

    /**
     * Wires the HAL-contention probe used by the safety valve. The valve only
     * skips frames when this returns true (i.e., the BYD native AVM app is
     * actively sharing the camera and could lose its preview signal). When
     * unset (or set to a probe that always returns false), the valve is inert
     * and eglSwapBuffers handles backpressure natively.
     */
    public void setHalContentionProbe(java.util.function.BooleanSupplier probe) {
        this.halContentionProbe = probe != null ? probe : () -> false;
    }
    
    /**
     * Sets the camera layout mode for the mosaic shader.
     * 0 = 4-camera mosaic (Seal: pano_h/pano_l, surfaceMode=0)
     * 1 = APA passthrough (single pre-composited image, surfaceMode=1 with apa/byd_apa tag)
     * 2 = 3-camera mosaic (Atto 3 default: Rear, Side, Front)
     */
    public void setCameraLayout(int layout) {
        this.apaMode = (layout == 1);
        this.cameraLayout = layout;
        // Defer the actual glUniform1f to the next drawFrame on the
        // encoder GL thread; we don't own that context here.
        this.apaModeUniformDirty.set(true);
        String[] names = {"4-camera mosaic", "APA passthrough", "3-camera mosaic"};
        logger.info("Camera layout: " + (layout < names.length ? names[layout] : "unknown(" + layout + ")"));
    }
    
    public void setApaMode(boolean apa) {
        setCameraLayout(apa ? 1 : 0);
    }
    
    public boolean isOverlayEnabled() {
        return overlayEnabled;
    }

    public void setOverlayRecordingModeAllowed(boolean allowed) {
        this.overlayRecordingModeAllowed = allowed;
    }

    public void setTelemetryCollector(TelemetryDataCollector collector) {
        this.telemetryCollector = collector;
    }
    
    /**
     * Returns true if the encoder surface has died and needs reinitialization.
     * Called by PanoramicCameraGpu to trigger encoder recovery.
     */
    public boolean needsReinit() {
        return needsReinit;
    }
    
    /**
     * Clears the reinit flag after recovery is complete.
     */
    public void clearReinitFlag() {
        needsReinit = false;
        consecutiveSurfaceErrors = 0;
    }
    
    /**
     * SOTA: Releases only the encoder surface without releasing other resources.
     * Called before encoder reinitialization to prevent EGL_BAD_SURFACE errors.
     * The surface will be recreated when init() is called with the new encoder.
     */
    public void releaseEncoderSurface() {
        if (encoderSurface != null && eglCore != null) {
            eglCore.destroySurface(encoderSurface);
            encoderSurface = null;
            encoderInputSurface = null;
            logger.info("Released encoder surface for reinitialization");
        }
    }
    
    /**
     * Releases all resources.
     */
    public void release() {
        recording = false;
        
        if (programId != 0) {
            GlUtil.deleteProgram(programId);
            programId = 0;
        }
        
        // Release overlay resources
        if (overlayProgramId != 0) {
            GLES20.glDeleteProgram(overlayProgramId);
            overlayProgramId = 0;
        }
        if (overlayTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{overlayTextureId}, 0);
            overlayTextureId = 0;
        }
        if (overlayRenderer != null) {
            overlayRenderer.release();
            overlayRenderer = null;
        }
        overlayTextureReady = false;
        overlayTextureInitialized = false;
        
        if (encoderSurface != null) {
            eglCore.destroySurface(encoderSurface);
            encoderSurface = null;
        }
        
        logger.info( "GpuMosaicRecorder released");
    }

    private static float[] normalizeOffsets(float[] quadrantStripOffsetX) {
        if (quadrantStripOffsetX == null || quadrantStripOffsetX.length != 4) {
            return DEFAULT_QUADRANT_STRIP_OFFSET_X.clone();
        }
        return quadrantStripOffsetX.clone();
    }

    /**
     * Build the mosaic fragment shader with the four per-quadrant strip-X
     * offsets baked in as GLSL constants. Order: {Front, Right, Rear, Left}
     * → {TL, TR, BL, BR}. 3-cam (uApaMode > 1.5) and APA (uApaMode > 0.5)
     * branches are layout-independent and stay as-is.
     */
    private static String buildFragmentShader(float[] offsets) {
        return String.format(Locale.US,
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uCameraTex;\n" +
            "uniform float uApaMode;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    vec2 samplePos;\n" +
            "    float frontOffset = %.5ff;\n" +
            "    float rightOffset = %.5ff;\n" +
            "    float rearOffset  = %.5ff;\n" +
            "    float leftOffset  = %.5ff;\n" +
            "    if (uApaMode > 1.5) {\n" +
            "        if (vTexCoord.x < 0.5) {\n" +
            "            float lx = vTexCoord.x * 0.5;\n" +
            "            float ly = mod(vTexCoord.y, 0.5) * 2.0;\n" +
            "            if (vTexCoord.y < 0.5) { samplePos = vec2(lx + 0.75, ly); }\n" +
            "            else { samplePos = vec2(lx, ly); }\n" +
            "        } else {\n" +
            "            float lx = (vTexCoord.x - 0.5);\n" +
            "            samplePos = vec2(0.25 + lx * 0.5, vTexCoord.y);\n" +
            "        }\n" +
            "    } else if (uApaMode > 0.5) {\n" +
            "        samplePos = vTexCoord;\n" +
            "    } else {\n" +
            "        vec2 gridPos = step(0.5, vTexCoord);\n" +
            "        float stripOffsetX;\n" +
            "        if (gridPos.x < 0.5) {\n" +
            "            stripOffsetX = gridPos.y < 0.5 ? frontOffset : rearOffset;\n" +
            "        } else {\n" +
            "            stripOffsetX = gridPos.y < 0.5 ? rightOffset : leftOffset;\n" +
            "        }\n" +
            "        float localX = mod(vTexCoord.x, 0.5) * 0.5;\n" +
            "        float localY = mod(vTexCoord.y, 0.5) * 2.0;\n" +
            "        samplePos = vec2(localX + stripOffsetX, localY);\n" +
            "    }\n" +
            "    gl_FragColor = texture2D(uCameraTex, samplePos);\n" +
            "}\n",
            offsets[0], offsets[1], offsets[2], offsets[3]);
    }
}
