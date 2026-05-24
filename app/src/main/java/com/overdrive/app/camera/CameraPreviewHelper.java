package com.overdrive.app.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;

import com.overdrive.app.logging.DaemonLogger;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Best-effort preview capture for direct camera IDs.
 */
public final class CameraPreviewHelper {
    private static final DaemonLogger logger = DaemonLogger.getInstance("CameraPreviewHelper");
    private static final int DEFAULT_TIMEOUT_MS = 1500;

    private CameraPreviewHelper() {
    }

    public static byte[] captureDirectPreviewJpeg(int cameraId, int preferredWidth, int preferredHeight) {
        return captureDirectPreviewJpeg(cameraId, preferredWidth, preferredHeight, 10, DEFAULT_TIMEOUT_MS);
    }

    public static byte[] captureDirectPreviewJpeg(int cameraId, int preferredWidth, int preferredHeight,
                                                  int fps, int timeoutMs) {
        int[][] candidates = new int[][] {
            {preferredWidth, preferredHeight},
            {1280, 960},
            {1280, 720},
            {960, 720},
            {720, 480}
        };
        for (int[] candidate : candidates) {
            if (candidate[0] <= 0 || candidate[1] <= 0) continue;
            byte[] jpeg = captureWithSize(cameraId, candidate[0], candidate[1], fps, timeoutMs);
            if (jpeg != null && jpeg.length > 0) {
                return jpeg;
            }
        }
        return null;
    }

    public static byte[] captureDirectPreviewJpegExact(int cameraId, int width, int height,
                                                       int fps, int timeoutMs) {
        if (width <= 0 || height <= 0) return null;
        return captureWithSize(cameraId, width, height, fps, timeoutMs);
    }

    public static byte[] capturePanoramicSliceJpeg(int cameraId, int panoWidth, int panoHeight,
                                                   PanoramicSlice slice) {
        if (slice == null || panoWidth <= 0 || panoHeight <= 0) return null;

        int[][] candidates = new int[][] {
            {panoWidth, panoHeight},
            {Math.max(1, panoWidth / 2), Math.max(1, panoHeight / 2)},
            {Math.max(1, panoWidth / 4), Math.max(1, panoHeight / 4)}
        };

        for (int[] candidate : candidates) {
            byte[] fullJpeg = captureDirectPreviewJpegExact(
                cameraId,
                candidate[0],
                candidate[1],
                10,
                DEFAULT_TIMEOUT_MS
            );
            if (fullJpeg == null || fullJpeg.length == 0) continue;

            byte[] cropped = cropJpegToSlice(fullJpeg, slice, 82);
            if (cropped != null && cropped.length > 0) {
                return cropped;
            }
        }

        return null;
    }

    private static byte[] captureWithSize(int cameraId, int width, int height, int fps, int timeoutMs) {
        HandlerThread thread = null;
        ImageReader reader = null;
        BinderCameraBackend backend = new BinderCameraBackend();
        AtomicReference<Image> imageRef = new AtomicReference<>();

        try {
            thread = new HandlerThread("DirectPreview-" + cameraId + "-" + width + "x" + height);
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            CountDownLatch latch = new CountDownLatch(1);

            reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(imageReader -> {
                Image image = null;
                try {
                    image = imageReader.acquireLatestImage();
                    if (image != null && imageRef.compareAndSet(null, image)) {
                        latch.countDown();
                        image = null;
                    }
                } catch (Exception e) {
                    logger.debug("Preview image callback failed: " + e.getMessage());
                } finally {
                    if (image != null) {
                        try { image.close(); } catch (Exception ignored) {}
                    }
                }
            }, handler);

            if (!backend.startCamera(cameraId, reader.getSurface(), width, height, fps)) {
                return null;
            }

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                logger.debug("Timed out waiting for preview frame cam=" + cameraId + " size=" + width + "x" + height);
                return null;
            }

            Image image = imageRef.get();
            if (image == null) {
                return null;
            }
            try {
                return yuv420888ToJpeg(image, 80);
            } finally {
                try { image.close(); } catch (Exception ignored) {}
                imageRef.set(null);
            }
        } catch (Exception e) {
            logger.debug("Direct preview capture failed cam=" + cameraId + " size=" + width + "x" + height + ": " + e.getMessage());
            return null;
        } finally {
            try { backend.stopCamera(); } catch (Exception ignored) {}
            try { backend.disconnect(); } catch (Exception ignored) {}
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            Image leaked = imageRef.getAndSet(null);
            if (leaked != null) {
                try { leaked.close(); } catch (Exception ignored) {}
            }
            if (thread != null) {
                try {
                    thread.quitSafely();
                    thread.join(250);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static byte[] yuv420888ToJpeg(Image image, int quality) {
        byte[] nv21 = yuv420888ToNv21(image);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), quality, out);
        return out.toByteArray();
    }

    private static byte[] cropJpegToSlice(byte[] jpegBytes, PanoramicSlice slice, int quality) {
        Bitmap bitmap = null;
        Bitmap cropped = null;
        try {
            bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
            if (bitmap == null) return null;

            int sliceWidth = Math.max(1, bitmap.getWidth() / 4);
            int startX = Math.min(bitmap.getWidth() - sliceWidth, Math.max(0, slice.getIndex() * sliceWidth));
            cropped = Bitmap.createBitmap(bitmap, startX, 0, sliceWidth, bitmap.getHeight());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, quality, out);
            return out.toByteArray();
        } catch (Exception e) {
            logger.debug("Panoramic slice crop failed: " + e.getMessage());
            return null;
        } finally {
            if (cropped != null) {
                try { cropped.recycle(); } catch (Exception ignored) {}
            }
            if (bitmap != null) {
                try { bitmap.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    private static byte[] yuv420888ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        byte[] out = new byte[width * height * 3 / 2];
        int dst = 0;

        ByteBuffer yBuffer = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        for (int row = 0; row < height; row++) {
            int rowOffset = row * yRowStride;
            for (int col = 0; col < width; col++) {
                out[dst++] = yBuffer.get(rowOffset + col * yPixelStride);
            }
        }

        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int vRowStride = planes[2].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vPixelStride = planes[2].getPixelStride();
        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        for (int row = 0; row < chromaHeight; row++) {
            int uRowOffset = row * uRowStride;
            int vRowOffset = row * vRowStride;
            for (int col = 0; col < chromaWidth; col++) {
                out[dst++] = vBuffer.get(vRowOffset + col * vPixelStride);
                out[dst++] = uBuffer.get(uRowOffset + col * uPixelStride);
            }
        }
        return out;
    }
}