package com.overdrive.app.surveillance;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import com.overdrive.app.camera.GlUtil;
import com.overdrive.app.logging.DaemonLogger;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * FoveatedCropper — High-resolution targeted AI crop from the raw camera strip.
 *
 * Implements the "foveated vision" pattern: the low-res 640×480 mosaic acts as
 * peripheral vision to detect WHERE motion is happening, then this cropper
 * extracts a 640×640 window directly from the raw 5120×960 camera strip at
 * full resolution, centered on the motion centroid.
 *
 * SOTA Tier 2 async readback (GLES 3.0 PBO + fence-sync ring):
 *   The previous double-FBO ping-pong reduced the GPU-side stall but
 *   still issued a synchronous {@code glReadPixels} into a Java direct
 *   ByteBuffer — on Adreno 610 that path implicitly joins the OpenCL
 *   command queue when YOLO inference is in flight, even when the
 *   target FBO has already drained.
 *
 *   This implementation pipelines the readback through a small ring of
 *   {@code GL_PIXEL_PACK_BUFFER} objects:
 *     1. Render the new (quadrant, centroid) into the FBO.
 *     2. Issue {@code glReadPixels} with a PBO bound — the driver queues
 *        a DMA into the PBO and returns immediately (no host wait, no
 *        OpenCL barrier).
 *     3. Drop a {@code glFenceSync} immediately after.
 *     4. On a future call, poll the OLDEST in-flight fence with
 *        {@code glClientWaitSync(timeout=0)}. If signaled, map the PBO,
 *        memcpy out, unmap. If not signaled, return null and try again
 *        next call — no blocking.
 *
 *   Result struct carries the quadrant the readback bytes correspond
 *   to (which is N-RING_SIZE+1 frames behind the request) so the caller
 *   publishes to the correct slot.
 */
public class FoveatedCropper {
    private static final DaemonLogger logger = DaemonLogger.getInstance("FoveatedCrop");

    public static final int CROP_SIZE = 640;

    // Source strip dimensions (runtime-resolved per camera profile).
    // Seal = 5120x960, Tang = 5120x720. Per-camera tile is stripWidth/4.
    private final int stripWidth;
    private final int stripHeight;
    private final float[] quadrantStripOffsetX;

    private int program = -1;
    private int aPosition = -1;
    private int aTexCoord = -1;
    private int uCameraTex = -1;
    private int uCropRect = -1;

    // Single render FBO — readbacks pipeline through PBOs, not FBOs, so we
    // no longer need a ping-pong pair. The driver still does internal frame
    // queueing for the FBO target itself.
    private int fboId = -1;
    private int fboTexture = -1;

    // PBO ring — three slots. RING_SIZE = 3 lets the GPU keep at most two
    // readbacks in flight while a third PBO is being mapped on the CPU,
    // without ever forcing a sync. At V2's 10 Hz motion cadence and our
    // 150 ms service throttle, two-in-flight is more than enough headroom.
    private static final int RING_SIZE = 3;
    private final int[] pboIds = new int[RING_SIZE];
    private final long[] fenceSyncs = new long[RING_SIZE];        // 0 = unused
    private final int[] pboQuadrant = new int[RING_SIZE];          // -1 = unused
    private static final int PBO_BYTES = CROP_SIZE * CROP_SIZE * 4;
    // Head = next slot to render+queue into; tail = next slot to attempt
    // readback from. The ring is empty when head == tail and slots in
    // [tail..head) are in flight.
    private int ringHead = 0;
    private int ringTail = 0;

    private byte[] scratchRgba = null;   // bulk-copy RGBA scratch (single JNI hop)
    private byte[] rgbBuffer = null;
    private boolean initialized = false;
    // True iff the live GL context is GLES 3.x — checked once at init via
    // glGetString(GL_VERSION). The PBO + fence-sync path requires GLES 3
    // entry points; on a GLES 2 fallback we can't pipeline the readback
    // and have to fall back to a synchronous glReadPixels into a direct
    // ByteBuffer. Modern Adreno (Adreno 600 family on DiLink 5) is always
    // GLES 3.2; the fallback exists so the code is robust against a hostile
    // EGL config rejection on unknown hardware.
    private boolean gles3Available = false;
    // GLES2 fallback scratch — only allocated if gles3Available is false.
    private ByteBuffer fallbackReadBuffer = null;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";

    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "uniform samplerExternalOES uCameraTex;\n" +
        "uniform vec4 uCropRect;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    vec2 samplePos = vec2(\n" +
        "        uCropRect.x + vTexCoord.x * uCropRect.z,\n" +
        "        uCropRect.y + vTexCoord.y * uCropRect.w\n" +
        "    );\n" +
        "    gl_FragColor = texture2D(uCameraTex, samplePos);\n" +
        "}\n";

    private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };

    private static final float[] TEX_COORDS = {
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    };

    private static final float[] DEFAULT_QUADRANT_STRIP_OFFSET_X = {
        0.75f,  // Q0 (TL → Front)
        0.50f,  // Q1 (TR → Right)
        0.00f,  // Q2 (BL → Rear)
        0.25f   // Q3 (BR → Left)
    };

    public FoveatedCropper() {
        this(5120, 960, DEFAULT_QUADRANT_STRIP_OFFSET_X);
    }

    public FoveatedCropper(int stripWidth, int stripHeight) {
        this(stripWidth, stripHeight, DEFAULT_QUADRANT_STRIP_OFFSET_X);
    }

    public FoveatedCropper(int stripWidth, int stripHeight, float[] quadrantStripOffsetX) {
        this.stripWidth = Math.max(1, stripWidth);
        this.stripHeight = Math.max(1, stripHeight);
        this.quadrantStripOffsetX = (quadrantStripOffsetX != null && quadrantStripOffsetX.length == 4)
            ? quadrantStripOffsetX.clone()
            : DEFAULT_QUADRANT_STRIP_OFFSET_X.clone();
    }

    /** Result of a crop call. {@link #rgb} may be null on the very first
     *  invocation (no previous frame to read back) — caller falls back to mosaic. */
    public static final class Result {
        public final byte[] rgb;
        public final int quadrant;
        public final int width;
        public final int height;
        public Result(byte[] rgb, int quadrant, int w, int h) {
            this.rgb = rgb;
            this.quadrant = quadrant;
            this.width = w;
            this.height = h;
        }
    }

    public void init() {
        try {
            program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) {
                logger.error("Foveated crop shader compilation failed");
                return;
            }
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            uCameraTex = GLES20.glGetUniformLocation(program, "uCameraTex");
            uCropRect = GLES20.glGetUniformLocation(program, "uCropRect");

            // One-shot GL version probe. PBO + fence-sync require GLES 3.
            String glVer = GLES20.glGetString(GLES20.GL_VERSION);
            gles3Available = (glVer != null && glVer.contains("OpenGL ES 3"));
            logger.info("FoveatedCropper GL version: '" + glVer + "' "
                    + "(gles3=" + gles3Available + ")");

            // One render FBO. The PBO ring is what gives us pipelining now.
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            fboTexture = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    CROP_SIZE, CROP_SIZE, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

            int[] fbo = new int[1];
            GLES20.glGenFramebuffers(1, fbo, 0);
            fboId = fbo[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, fboTexture, 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                logger.error("Foveated FBO incomplete: " + status);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                return;
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            if (gles3Available) {
                // PBO ring. STREAM_READ tells the driver these are CPU-readback
                // buffers (vs. STREAM_DRAW for vertex data).
                GLES30.glGenBuffers(RING_SIZE, pboIds, 0);
                for (int i = 0; i < RING_SIZE; i++) {
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[i]);
                    GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, PBO_BYTES, null,
                            GLES30.GL_STREAM_READ);
                    fenceSyncs[i] = 0L;
                    pboQuadrant[i] = -1;
                }
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            } else {
                // GLES2 fallback: synchronous glReadPixels into a direct buffer.
                // No pipelining, but at least the AI-lane GL thread is still
                // separate from the encoder thread — Tier 1's win is preserved.
                fallbackReadBuffer = ByteBuffer.allocateDirect(PBO_BYTES);
                fallbackReadBuffer.order(ByteOrder.nativeOrder());
            }

            scratchRgba = new byte[PBO_BYTES];
            rgbBuffer = new byte[CROP_SIZE * CROP_SIZE * 3];

            vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
            texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);

            ringHead = 0;
            ringTail = 0;

            initialized = true;
            if (gles3Available) {
                logger.info("FoveatedCropper initialized (GLES3 PBO ring x" + RING_SIZE
                        + ", " + CROP_SIZE + "×" + CROP_SIZE + " RGBA, async readback)");
            } else {
                logger.warn("FoveatedCropper initialized (GLES2 fallback — synchronous readback, no PBO pipelining)");
            }
        } catch (Exception e) {
            logger.error("FoveatedCropper init failed: " + e.getMessage());
        }
    }

    /**
     * Submit a crop request and read back the previous one in a single GL pass.
     *
     * Renders the new (quadrant, centroid) into the current FBO, then issues
     * glReadPixels against the OTHER FBO — the one the GPU already finished
     * during the previous call. The readback is non-blocking because the
     * pipeline has drained for that target.
     *
     * Must be called on the GL thread that owns {@code cameraTextureId}.
     *
     * @return Result whose bytes correspond to the PREVIOUS request's quadrant,
     *         or null on the very first call (nothing to read back yet).
     */
    public Result crop(int cameraTextureId, int quadrant, float centroidX, float centroidY) {
        if (!initialized || quadrant < 0 || quadrant >= 4) return null;

        float quadPixelX = (centroidX + 0.5f) * 32.0f;
        float quadPixelY = (centroidY + 0.5f) * 32.0f;

        float camNormX = quadPixelX / 320.0f;
        float camNormY = quadPixelY / 240.0f;

        float stripOffsetX = quadrantStripOffsetX[quadrant];

        float cropWidthNorm = (float) CROP_SIZE / stripWidth;
        float cropHeightNorm = (float) CROP_SIZE / stripHeight;

        float centerX = stripOffsetX + camNormX * 0.25f;
        float centerY = camNormY;

        float cropLeft = centerX - cropWidthNorm / 2.0f;
        float cropTop = centerY - cropHeightNorm / 2.0f;

        float camLeft = stripOffsetX;
        float camRight = stripOffsetX + 0.25f;
        cropLeft = Math.max(camLeft, Math.min(cropLeft, camRight - cropWidthNorm));
        cropTop = Math.max(0.0f, Math.min(cropTop, 1.0f - cropHeightNorm));

        int[] savedViewport = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0);

        // ---- 1. Render the CURRENT request to the FBO ----
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glViewport(0, 0, CROP_SIZE, CROP_SIZE);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(uCameraTex, 0);
        GLES20.glUniform4f(uCropRect, cropLeft, cropTop, cropWidthNorm, cropHeightNorm);

        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);

        // GLES2 fallback path: read synchronously, return immediately.
        // No pipelining, but Tier 1's separate AI-lane thread already
        // contains the cost away from the encoder thread.
        if (!gles3Available) {
            fallbackReadBuffer.clear();
            GLES20.glReadPixels(0, 0, CROP_SIZE, CROP_SIZE,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, fallbackReadBuffer);
            fallbackReadBuffer.rewind();
            fallbackReadBuffer.get(scratchRgba, 0, PBO_BYTES);
            byte[] src = scratchRgba;
            byte[] dst = rgbBuffer;
            final int rowRgbaBytes = CROP_SIZE * 4;
            int dstIdx = 0;
            for (int y = CROP_SIZE - 1; y >= 0; y--) {
                int srcRow = y * rowRgbaBytes;
                for (int x = 0; x < CROP_SIZE; x++) {
                    int s = srcRow + (x << 2);
                    dst[dstIdx++] = src[s];
                    dst[dstIdx++] = src[s + 1];
                    dst[dstIdx++] = src[s + 2];
                }
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
            return new Result(dst, quadrant, CROP_SIZE, CROP_SIZE);
        }

        // ---- 2. Queue an async readback into the next PBO slot ----
        // If the ring is full (head wraps to tail with non-null fence at tail),
        // we either (a) skip the queue this call and only attempt readback,
        // or (b) overwrite the oldest. Skipping is safer — RING_SIZE=3 leaves
        // generous headroom; saturating the ring means the AI lane is
        // calling us faster than we can drain, which only happens if the
        // 150 ms throttle is bypassed. Defensive skip + log.
        int nextHead = (ringHead + 1) % RING_SIZE;
        boolean ringFull = (nextHead == ringTail) && fenceSyncs[ringTail] != 0L;
        if (!ringFull) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[ringHead]);
            // glReadPixels with PBO bound: queues an async DMA of the FBO
            // into the PBO; returns immediately.
            GLES30.glReadPixels(0, 0, CROP_SIZE, CROP_SIZE,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);

            // Drop a fence right after. glClientWaitSync(timeout=0) will
            // tell us when the DMA has landed without blocking.
            fenceSyncs[ringHead] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            pboQuadrant[ringHead] = quadrant;
            ringHead = nextHead;
        } else {
            // Saturated ring — only attempt drain below.
            if (logger != null) logger.debug("PBO ring full, skipping queue (drain only)");
        }

        // ---- 3. Drain: try to harvest the OLDEST in-flight slot ----
        Result result = null;
        if (ringTail != ringHead && fenceSyncs[ringTail] != 0L) {
            // glClientWaitSync with a 0 timeout is a poll; never blocks.
            // Returns one of:
            //   GL_ALREADY_SIGNALED       — fence already done; map immediately
            //   GL_CONDITION_SATISFIED    — done within the (zero) wait window
            //   GL_TIMEOUT_EXPIRED        — not yet done; try later
            //   GL_WAIT_FAILED            — driver error
            int sig = GLES30.glClientWaitSync(fenceSyncs[ringTail], 0, 0);
            boolean signaled = (sig == GLES30.GL_ALREADY_SIGNALED
                             || sig == GLES30.GL_CONDITION_SATISFIED);
            if (signaled) {
                int q = pboQuadrant[ringTail];
                // Map the PBO read-only and bulk-copy into our scratch
                // byte[]. glMapBufferRange returns a Buffer whose backing
                // memory is the GPU's PBO storage — direct DMA buffer.
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[ringTail]);
                Buffer mapped = GLES30.glMapBufferRange(
                        GLES30.GL_PIXEL_PACK_BUFFER, 0, PBO_BYTES,
                        GLES30.GL_MAP_READ_BIT);
                if (mapped instanceof ByteBuffer) {
                    ByteBuffer bb = (ByteBuffer) mapped;
                    bb.order(ByteOrder.nativeOrder());
                    bb.rewind();
                    bb.get(scratchRgba, 0, PBO_BYTES);
                    GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);

                    // Y-flip + RGBA→RGB pack into the result buffer.
                    byte[] src = scratchRgba;
                    byte[] dst = rgbBuffer;
                    final int rowRgbaBytes = CROP_SIZE * 4;
                    int dstIdx = 0;
                    for (int y = CROP_SIZE - 1; y >= 0; y--) {
                        int srcRow = y * rowRgbaBytes;
                        for (int x = 0; x < CROP_SIZE; x++) {
                            int s = srcRow + (x << 2);
                            dst[dstIdx++] = src[s];
                            dst[dstIdx++] = src[s + 1];
                            dst[dstIdx++] = src[s + 2];
                        }
                    }
                    result = new Result(dst, q, CROP_SIZE, CROP_SIZE);
                } else {
                    GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
                    logger.warn("glMapBufferRange returned non-ByteBuffer mapping");
                }

                // Done with this slot — release fence + advance tail.
                GLES30.glDeleteSync(fenceSyncs[ringTail]);
                fenceSyncs[ringTail] = 0L;
                pboQuadrant[ringTail] = -1;
                ringTail = (ringTail + 1) % RING_SIZE;
            }
            // If sig == GL_TIMEOUT_EXPIRED we just leave the fence in
            // place; the next call will poll it again. Caller falls back
            // to mosaic for THIS tick — which is exactly what we want;
            // we have NEVER blocked the AI-lane GL thread.
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);

        return result;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void release() {
        // Drop any in-flight fences first; deleting their PBOs without
        // releasing the fences leaks driver-side sync objects.
        for (int i = 0; i < RING_SIZE; i++) {
            if (fenceSyncs[i] != 0L) {
                try { GLES30.glDeleteSync(fenceSyncs[i]); } catch (Throwable ignored) {}
                fenceSyncs[i] = 0L;
            }
            pboQuadrant[i] = -1;
        }
        if (pboIds[0] != 0) {
            GLES30.glDeleteBuffers(RING_SIZE, pboIds, 0);
            for (int i = 0; i < RING_SIZE; i++) pboIds[i] = 0;
        }
        if (fboId >= 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{fboId}, 0);
            fboId = -1;
        }
        if (fboTexture >= 0) {
            GLES20.glDeleteTextures(1, new int[]{fboTexture}, 0);
            fboTexture = -1;
        }
        if (program > 0) {
            GLES20.glDeleteProgram(program);
            program = -1;
        }
        // Drop CPU-side scratch + readback. ~4.4 MB held otherwise across
        // every surveillance pause; lazy paths re-init on next call.
        scratchRgba = null;
        rgbBuffer = null;
        fallbackReadBuffer = null;
        ringHead = 0;
        ringTail = 0;
        initialized = false;
        logger.info("FoveatedCropper released");
    }
}
