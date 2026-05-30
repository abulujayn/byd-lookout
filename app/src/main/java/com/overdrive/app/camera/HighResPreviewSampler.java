package com.overdrive.app.camera;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;

import com.overdrive.app.logging.DaemonLogger;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Full-resolution preview sampler for the camera-mapping dialog.
 *
 * <p><b>Architecture: dedicated thread + shared EGL context.</b> The encoder
 * pipeline's GL thread (camera glHandler) is on the recording hot path —
 * any work posted there blocks {@code eglSwapBuffers} and produces gaps in
 * the recorded MP4. Instead this class runs its own {@link HandlerThread}
 * with an EGL context that <em>shares</em> the camera's OES texture (via
 * {@code eglCreateContext(... shareContext)}). The camera HAL keeps writing
 * to {@code cameraTextureId} on its own schedule; the sampler reads from
 * it concurrently. Same pattern {@code GpuDownscaler} already uses for the
 * AI lane — recording-safe.
 *
 * <p>Two operations:
 * <ul>
 *   <li>{@link #sampleFullMosaicJpeg} — 2x2 mosaic at full encoder resolution.
 *       Seal: 2560×1920. Tang: 2560×1440.</li>
 *   <li>{@link #samplePerQuadrantJpeg} — one camera tile from the raw strip
 *       at full per-camera resolution. Seal: 1280×960. Tang: 1280×720.</li>
 * </ul>
 *
 * <p>Both calls block the calling thread until the GL job completes.
 * Caller is the HTTP worker (dialog request), not the GL thread.
 */
public final class HighResPreviewSampler {
    private static final DaemonLogger logger =
            DaemonLogger.getInstance("HighResPreviewSampler");

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    private static String buildMosaicShader(float[] offsets) {
        return String.format(Locale.US,
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "uniform samplerExternalOES uCameraTex;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    vec2 gridPos = step(0.5, vTexCoord);\n" +
                "    float frontOffset = %.5ff;\n" +
                "    float rightOffset = %.5ff;\n" +
                "    float rearOffset  = %.5ff;\n" +
                "    float leftOffset  = %.5ff;\n" +
                "    float stripOffsetX;\n" +
                "    if (gridPos.x < 0.5) {\n" +
                "        stripOffsetX = gridPos.y < 0.5 ? frontOffset : rearOffset;\n" +
                "    } else {\n" +
                "        stripOffsetX = gridPos.y < 0.5 ? rightOffset : leftOffset;\n" +
                "    }\n" +
                "    float localX = mod(vTexCoord.x, 0.5) * 0.5;\n" +
                "    float localY = mod(vTexCoord.y, 0.5) * 2.0;\n" +
                "    vec2 samplePos = vec2(localX + stripOffsetX, localY);\n" +
                "    gl_FragColor = texture2D(uCameraTex, samplePos);\n" +
                "}\n",
                offsets[0], offsets[1], offsets[2], offsets[3]);
    }

    private static final String QUADRANT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uCameraTex;\n" +
            "uniform float uStripOffsetX;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    vec2 samplePos = vec2(uStripOffsetX + vTexCoord.x * 0.25, vTexCoord.y);\n" +
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

    private final EGLContext sharedContext;
    private final HandlerThread thread;
    private final Handler handler;

    // GL state — owned by the sampler thread.
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private boolean glReady = false;

    private int mosaicProgram = 0;
    private int mosaicAPos = -1, mosaicATex = -1, mosaicUTex = -1;
    private float[] cachedOffsets = null;

    private int quadProgram = 0;
    private int quadAPos = -1, quadATex = -1, quadUTex = -1, quadUOffset = -1;

    private int fbo = -1;
    private int fboTexture = -1;
    private int fboWidth = 0, fboHeight = 0;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    private ByteBuffer readBuffer;
    private byte[] scratchRgba;
    private int[] argbPixels;

    /**
     * @param sharedContext EGL context owned by the camera GL thread. Pass
     *     {@code camera.getEglCore().getContext()} so we can sample the
     *     camera's OES texture from this thread.
     */
    public HighResPreviewSampler(EGLContext sharedContext) {
        this.sharedContext = sharedContext;
        this.thread = new HandlerThread("HighResPreviewSampler");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
        // Initialize EGL on the sampler thread. Posted async — sample calls
        // will block on glReady internally.
        handler.post(this::initGl);
    }

    private void initGl() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                logger.warn("eglGetDisplay returned NO_DISPLAY");
                return;
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                logger.warn("eglInitialize failed");
                return;
            }
            int[] configAttribs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0,
                    configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                logger.warn("eglChooseConfig failed");
                return;
            }
            int[] contextAttribs = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            // Share with the camera's context so we can sample the OES texture.
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0],
                    sharedContext, contextAttribs, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                logger.warn("eglCreateContext (shared) failed");
                return;
            }
            // 1×1 pbuffer — we render to FBOs, not the surface.
            int[] pbufferAttribs = {
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE
            };
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0],
                    pbufferAttribs, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                logger.warn("eglCreatePbufferSurface failed");
                return;
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                logger.warn("eglMakeCurrent failed");
                return;
            }
            vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
            texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);
            glReady = true;
            logger.info("HighResPreviewSampler GL initialized on dedicated thread");
        } catch (Throwable t) {
            logger.warn("initGl failed: " + t.getMessage());
        }
    }

    private void ensureFbo(int width, int height) {
        if (fbo >= 0 && fboWidth == width && fboHeight == height) return;
        releaseFbo();
        try {
            int[] texIds = new int[1];
            GLES20.glGenTextures(1, texIds, 0);
            fboTexture = texIds[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

            int[] fboIds = new int[1];
            GLES20.glGenFramebuffers(1, fboIds, 0);
            fbo = fboIds[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, fboTexture, 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                logger.warn("FBO incomplete at " + width + "x" + height + " status=" + status);
                releaseFbo();
                return;
            }
            int rgbaBytes = width * height * 4;
            readBuffer = ByteBuffer.allocateDirect(rgbaBytes);
            readBuffer.order(ByteOrder.nativeOrder());
            scratchRgba = new byte[rgbaBytes];
            argbPixels = new int[width * height];
            fboWidth = width;
            fboHeight = height;
        } catch (Throwable t) {
            logger.warn("ensureFbo failed: " + t.getMessage());
            releaseFbo();
        }
    }

    private boolean ensureMosaicProgram(float[] offsets) {
        if (mosaicProgram > 0 && cachedOffsets != null
                && cachedOffsets[0] == offsets[0]
                && cachedOffsets[1] == offsets[1]
                && cachedOffsets[2] == offsets[2]
                && cachedOffsets[3] == offsets[3]) return true;
        if (mosaicProgram > 0) {
            try { GLES20.glDeleteProgram(mosaicProgram); } catch (Throwable ignored) {}
            mosaicProgram = 0;
        }
        mosaicProgram = GlUtil.createProgram(VERTEX_SHADER, buildMosaicShader(offsets));
        if (mosaicProgram <= 0) return false;
        mosaicAPos = GLES20.glGetAttribLocation(mosaicProgram, "aPosition");
        mosaicATex = GLES20.glGetAttribLocation(mosaicProgram, "aTexCoord");
        mosaicUTex = GLES20.glGetUniformLocation(mosaicProgram, "uCameraTex");
        cachedOffsets = offsets.clone();
        return true;
    }

    private boolean ensureQuadrantProgram() {
        if (quadProgram > 0) return true;
        quadProgram = GlUtil.createProgram(VERTEX_SHADER, QUADRANT_SHADER);
        if (quadProgram <= 0) return false;
        quadAPos = GLES20.glGetAttribLocation(quadProgram, "aPosition");
        quadATex = GLES20.glGetAttribLocation(quadProgram, "aTexCoord");
        quadUTex = GLES20.glGetUniformLocation(quadProgram, "uCameraTex");
        quadUOffset = GLES20.glGetUniformLocation(quadProgram, "uStripOffsetX");
        return true;
    }

    /**
     * Block the caller, post the render to the sampler thread, return JPEG.
     * Safe to call from any non-GL thread (HTTP workers).
     */
    public byte[] sampleFullMosaicJpeg(int textureId, int stripWidth, int stripHeight,
                                       float[] offsets) {
        if (textureId == 0 || offsets == null || offsets.length != 4) return null;
        int outW = Math.max(1, stripWidth / 2);
        int outH = Math.max(1, stripHeight * 2);
        return runOnSamplerThread(() -> {
            ensureFbo(outW, outH);
            if (fbo < 0 || !ensureMosaicProgram(offsets)) return null;
            return renderAndEncode(textureId, mosaicProgram, mosaicAPos, mosaicATex, mosaicUTex,
                    -1, 0f, outW, outH);
        });
    }

    public byte[] samplePerQuadrantJpeg(int textureId, int stripWidth, int stripHeight,
                                        float stripOffsetX) {
        if (textureId == 0) return null;
        int outW = Math.max(1, stripWidth / 4);
        int outH = Math.max(1, stripHeight);
        return runOnSamplerThread(() -> {
            ensureFbo(outW, outH);
            if (fbo < 0 || !ensureQuadrantProgram()) return null;
            return renderAndEncode(textureId, quadProgram, quadAPos, quadATex, quadUTex,
                    quadUOffset, stripOffsetX, outW, outH);
        });
    }

    private interface GlJob {
        byte[] run();
    }

    private byte[] runOnSamplerThread(GlJob job) {
        if (!glReady) {
            // GL might still be initializing — give it a brief moment.
            for (int i = 0; i < 20 && !glReady; i++) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (!glReady) {
                logger.warn("GL not ready after 1s wait");
                return null;
            }
        }
        final Object lock = new Object();
        final AtomicReference<byte[]> result = new AtomicReference<>();
        handler.post(() -> {
            try {
                result.set(job.run());
            } catch (Throwable t) {
                logger.warn("GL job failed: " + t.getMessage());
            } finally {
                synchronized (lock) { lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            try { lock.wait(1500); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return result.get();
    }

    /** Caller has bound the FBO + program; we render, read, encode. */
    private byte[] renderAndEncode(int textureId, int program,
                                   int aPosLoc, int aTexLoc, int uTexLoc,
                                   int uOffsetLoc, float offsetValue,
                                   int width, int height) {
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(uTexLoc, 0);
            if (uOffsetLoc >= 0) {
                GLES20.glUniform1f(uOffsetLoc, offsetValue);
            }

            GLES20.glEnableVertexAttribArray(aPosLoc);
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(aTexLoc);
            GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(aPosLoc);
            GLES20.glDisableVertexAttribArray(aTexLoc);

            // glReadPixels syncs on this FBO's pending writes.
            readBuffer.clear();
            GLES20.glReadPixels(0, 0, width, height,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuffer);

            int rgbaBytes = width * height * 4;
            readBuffer.rewind();
            readBuffer.get(scratchRgba, 0, rgbaBytes);

            byte[] src = scratchRgba;
            int rowRgbaBytes = width * 4;
            int dstIdx = 0;
            for (int y = height - 1; y >= 0; y--) {
                int srcRow = y * rowRgbaBytes;
                for (int x = 0; x < width; x++) {
                    int s = srcRow + (x << 2);
                    int r = src[s] & 0xFF;
                    int g = src[s + 1] & 0xFF;
                    int b = src[s + 2] & 0xFF;
                    argbPixels[dstIdx++] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }

            Bitmap bmp = Bitmap.createBitmap(argbPixels, width, height,
                    Bitmap.Config.ARGB_8888);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
                return out.toByteArray();
            } finally {
                bmp.recycle();
            }
        } catch (Throwable t) {
            logger.warn("renderAndEncode failed " + width + "x" + height
                    + ": " + t.getMessage());
            return null;
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }
    }

    private void releaseFbo() {
        if (fbo >= 0) {
            try { GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0); } catch (Throwable ignored) {}
            fbo = -1;
        }
        if (fboTexture >= 0) {
            try { GLES20.glDeleteTextures(1, new int[]{fboTexture}, 0); } catch (Throwable ignored) {}
            fboTexture = -1;
        }
        // Drop the readback scratch (~3.6 MB at 1280×720). Lazy ensureFbo
        // re-creates these on the next sample; without this they survive
        // every camera re-init for the daemon's lifetime.
        readBuffer = null;
        scratchRgba = null;
        argbPixels = null;
        fboWidth = 0;
        fboHeight = 0;
    }

    /**
     * Synchronous release. Posts the GL teardown to the sampler thread and
     * blocks the caller until it completes (or 500 ms timeout). Caller is
     * typically the camera GL thread inside {@code releaseGl()} — we want
     * the sampler's EGL context fully destroyed BEFORE the camera's parent
     * EGL context is torn down, otherwise we'd race the shared-resource
     * destruction and possibly hit EGL_BAD_DISPLAY in the sampler's
     * eglDestroyContext.
     */
    public void release() {
        if (thread == null || !thread.isAlive()) return;
        final Object lock = new Object();
        final boolean[] done = {false};
        handler.post(() -> {
            try {
                releaseFbo();
                if (mosaicProgram > 0) {
                    try { GLES20.glDeleteProgram(mosaicProgram); } catch (Throwable ignored) {}
                    mosaicProgram = 0;
                }
                if (quadProgram > 0) {
                    try { GLES20.glDeleteProgram(quadProgram); } catch (Throwable ignored) {}
                    quadProgram = 0;
                }
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface);
                        eglSurface = EGL14.EGL_NO_SURFACE;
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext);
                        eglContext = EGL14.EGL_NO_CONTEXT;
                    }
                    EGL14.eglTerminate(eglDisplay);
                    eglDisplay = EGL14.EGL_NO_DISPLAY;
                }
                glReady = false;
            } finally {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 500;
            while (!done[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try { lock.wait(remaining); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        thread.quitSafely();
    }
}
