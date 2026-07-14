package com.overdrive.app.recording;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.RecordingsIndex;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.surveillance.GpuMosaicRecorder;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manual-only instant replay coordinator used by physical key mappings.
 *
 * <p>The request path only snapshots the trigger PTS and queues work. Once the
 * configured post-roll has arrived, the encoder remuxes the requested range
 * from its encoded pre-record ring into a second MP4. The primary continuous
 * recording muxer is never stopped or rotated.
 */
public final class ManualClipService {
    private static final DaemonLogger logger =
            DaemonLogger.getInstance("ManualClip");
    private static final long ENCODER_STALE_MS = 3_000L;
    // MAX H.264 can produce ~113 MB for 60 seconds. Require enough room for
    // that replay plus continued writes by the primary dashcam so an optional
    // export cannot consume the recorder's last free blocks.
    private static final long OUTPUT_RESERVE_BYTES = 200L * 1024L * 1024L;
    private static final long HISTORY_TOLERANCE_US = 250_000L;

    public enum Status {
        ACCEPTED,
        BUSY,
        PIPELINE_NOT_READY,
        RESTART_REQUIRED,
        NO_HISTORY,
        INVALID_WINDOW
    }

    public static final class RequestResult {
        public final Status status;
        public final String message;
        public final int availableBeforeSeconds;

        private RequestResult(Status status, String message, int availableBeforeSeconds) {
            this.status = status;
            this.message = message;
            this.availableBeforeSeconds = Math.max(0, availableBeforeSeconds);
        }

        public boolean isAccepted() {
            return status == Status.ACCEPTED;
        }
    }

    private static final ManualClipService INSTANCE = new ManualClipService();

    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ManualClipExport");
        thread.setDaemon(true);
        return thread;
    });

    private ManualClipService() {}

    public static ManualClipService getInstance() {
        return INSTANCE;
    }

    /** Largest enabled manual-clip total window currently persisted in Key Mapping. */
    public static int getConfiguredRetentionSeconds() {
        try {
            return getConfiguredRetentionSeconds(
                    com.overdrive.app.config.UnifiedConfigManager.getKeymap());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Package-visible for validation tests and config-save propagation. */
    static int getConfiguredRetentionSeconds(JSONObject keymap) {
        if (keymap == null || !keymap.optBoolean("enabled", false)) return 0;
        JSONArray bindings = keymap.optJSONArray("bindings");
        if (bindings == null) return 0;

        int max = 0;
        for (int i = 0; i < bindings.length(); i++) {
            JSONObject binding = bindings.optJSONObject(i);
            if (binding == null || !binding.optBoolean("enabled", true)) continue;
            max = Math.max(max, maxRetentionSeconds(binding.optJSONObject("action")));
        }
        return Math.min(ManualClipWindow.MAX_SECONDS, max);
    }

    private static int maxRetentionSeconds(JSONObject action) {
        if (action == null) return 0;
        String kind = action.optString("kind", "");
        if ("manualClip".equals(kind)) {
            // The requested start must remain in the ring while post-roll is
            // collected. Retention is therefore before + after, not just the
            // pre-trigger portion (30/30 needs the full 60-second ring).
            return Math.max(0, action.optInt("beforeSeconds", 0))
                    + Math.max(0, action.optInt("afterSeconds", 0));
        }
        if (!"sequence".equals(kind)) return 0;

        JSONArray steps = action.optJSONArray("steps");
        if (steps == null) return 0;
        int max = 0;
        for (int i = 0; i < steps.length(); i++) {
            max = Math.max(max, maxRetentionSeconds(steps.optJSONObject(i)));
        }
        return max;
    }

    /**
     * Apply a saved binding edit to the live encoder before the next key press.
     * Returns true when the fixed native arena is too small and needs a manual
     * camera-daemon cold restart; this method never performs that restart.
     */
    public boolean onKeymapConfigChanged(JSONObject keymap) {
        int seconds = getConfiguredRetentionSeconds(keymap);
        try {
            HardwareEventRecorderGpu encoder = currentEncoder();
            if (encoder == null) return false;
            encoder.setManualClipRetentionDuration(seconds);
            return encoder.requiresCameraDaemonRestartForManualClip(seconds);
        } catch (Throwable t) {
            logger.warn("Could not apply live replay retention: " + t.getMessage());
            return false;
        }
    }

    /** Read-only status for the Key Mapping page's persistent restart notice. */
    public boolean isCameraDaemonRestartRequired(JSONObject keymap) {
        int seconds = getConfiguredRetentionSeconds(keymap);
        if (seconds <= 0) return false;
        try {
            HardwareEventRecorderGpu encoder = currentEncoder();
            return encoder != null
                    && encoder.requiresCameraDaemonRestartForManualClip(seconds);
        } catch (Throwable t) {
            return false;
        }
    }

    public RequestResult requestClip(int beforeSeconds, int afterSeconds) {
        final ManualClipWindow window;
        try {
            window = ManualClipWindow.create(beforeSeconds, afterSeconds);
        } catch (IllegalArgumentException e) {
            return result(Status.INVALID_WINDOW, e.getMessage(), 0);
        }

        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline == null || !pipeline.isRunning()) {
            return result(Status.PIPELINE_NOT_READY, "Camera pipeline is not running", 0);
        }
        if (isProtectedRecordingMode(pipeline)) {
            return result(Status.BUSY, "Surveillance or proximity recording owns the camera", 0);
        }

        HardwareEventRecorderGpu encoder = currentEncoder();
        if (!isEncoderReady(encoder)) {
            return result(Status.PIPELINE_NOT_READY, "Encoder history is not available", 0);
        }
        if (!encoder.canRetainManualClip(window.getTotalSeconds())) {
            return result(Status.RESTART_REQUIRED,
                    "Camera history buffer is smaller than this replay window; "
                            + "restart the camera daemon after saving the binding", 0);
        }
        if (encoder.isPreRecordFlushInProgress()) {
            return result(Status.BUSY, "Another pre-record export is in progress", 0);
        }

        long triggerPtsUs = encoder.getLatestPreRecordPtsUs();
        long oldestPtsUs = encoder.getOldestPreRecordPtsUs();
        if (triggerPtsUs == Long.MIN_VALUE || oldestPtsUs == Long.MIN_VALUE) {
            return result(Status.NO_HISTORY, "No encoded history is available yet", 0);
        }
        int availableBefore = (int) Math.max(0L,
                (triggerPtsUs - oldestPtsUs) / 1_000_000L);
        long requestedStartPtsUs = triggerPtsUs
                - window.getBeforeSeconds() * 1_000_000L;
        if (window.getBeforeSeconds() > 0
                && oldestPtsUs > requestedStartPtsUs + HISTORY_TOLERANCE_US) {
            return result(Status.NO_HISTORY,
                    "The requested pre-record history is not available yet", availableBefore);
        }
        if (!requestInFlight.compareAndSet(false, true)) {
            return result(Status.BUSY, "An instant replay request is already pending", availableBefore);
        }

        // Reserve at trigger time, before collecting post-roll. Otherwise an
        // automatic event could claim the ring cursor during that wait and the
        // already-accepted manual replay would be lost.
        if (!encoder.tryReserveManualClip()) {
            requestInFlight.set(false);
            return result(Status.BUSY, "Another pre-record export is in progress", availableBefore);
        }

        long startPtsUs = requestedStartPtsUs;
        long endPtsUs = triggerPtsUs + window.getAfterSeconds() * 1_000_000L;
        try {
            exportExecutor.execute(() -> exportWhenReady(
                    encoder, window, startPtsUs, endPtsUs));
        } catch (RuntimeException e) {
            encoder.releaseManualClipReservation();
            requestInFlight.set(false);
            logger.warn("Could not queue instant replay worker: " + e.getMessage());
            return result(Status.BUSY, "Instant replay worker is unavailable", availableBefore);
        }

        logger.info("Instant replay accepted: before=" + window.getBeforeSeconds()
                + "s after=" + window.getAfterSeconds() + "s availableBefore="
                + availableBefore + "s");
        return result(Status.ACCEPTED, "Instant replay queued", availableBefore);
    }

    private void exportWhenReady(HardwareEventRecorderGpu requestedEncoder,
                                 ManualClipWindow window,
                                 long startPtsUs,
                                 long endPtsUs) {
        try {
            if (!awaitPostRoll(requestedEncoder, endPtsUs, window.getAfterSeconds())) {
                logger.warn("Instant replay cancelled: encoder changed or post-roll did not arrive");
                return;
            }

            GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline == null || !pipeline.isRunning()
                    || currentEncoder() != requestedEncoder) {
                logger.warn("Instant replay cancelled: camera ownership changed while pending");
                return;
            }

            AtomicReference<File> outputRef = new AtomicReference<>();
            boolean exported = requestedEncoder.exportManualClip(() -> {
                File output = prepareOutputFile();
                outputRef.set(output);
                return output;
            }, startPtsUs, endPtsUs);
            File output = outputRef.get();
            if (!exported || output == null) {
                logger.warn(output == null
                        ? "Instant replay cancelled: no writable recordings directory"
                        : "Instant replay export failed for " + output.getName());
                return;
            }

            try {
                StorageManager.getInstance().onFileSaved(output);
            } catch (Throwable t) {
                logger.warn("Instant replay storage accounting failed: " + t.getMessage());
            }
            try {
                RecordingsIndex.getInstance().upsert(output);
            } catch (Throwable t) {
                logger.warn("Instant replay index update failed: " + t.getMessage());
            }
            logger.info("Instant replay saved: " + output.getAbsolutePath());
        } catch (Throwable t) {
            logger.warn("Instant replay worker failed: " + t.getMessage());
        } finally {
            requestedEncoder.releaseManualClipReservation();
            requestInFlight.set(false);
        }
    }

    private boolean awaitPostRoll(HardwareEventRecorderGpu encoder,
                                  long endPtsUs,
                                  int afterSeconds) {
        if (afterSeconds <= 0) return true;
        long deadlineMs = System.currentTimeMillis() + afterSeconds * 1_000L + 2_000L;
        while (System.currentTimeMillis() < deadlineMs) {
            if (currentEncoder() != encoder || !isEncoderReady(encoder)) return false;
            if (encoder.getLatestPreRecordPtsUs() >= endPtsUs) return true;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return encoder.getLatestPreRecordPtsUs() >= endPtsUs;
    }

    private static boolean isProtectedRecordingMode(GpuSurveillancePipeline pipeline) {
        if (pipeline.isSurveillanceMode()) return true;
        RecordingModeManager manager = CameraDaemon.getRecordingModeManager();
        return manager != null
                && manager.getCurrentMode() == RecordingModeManager.Mode.PROXIMITY_GUARD;
    }

    private static HardwareEventRecorderGpu currentEncoder() {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline == null) return null;
        GpuMosaicRecorder recorder = pipeline.getRecorder();
        return recorder != null ? recorder.getEncoder() : null;
    }

    private static boolean isEncoderReady(HardwareEventRecorderGpu encoder) {
        if (encoder == null || !encoder.isFormatAvailable() || !encoder.isPreRecordEnabled()) {
            return false;
        }
        long lastFrameMs = encoder.getLastEncodedFrameMs();
        return lastFrameMs > 0
                && System.currentTimeMillis() - lastFrameMs <= ENCODER_STALE_MS;
    }

    private static File prepareOutputFile() {
        try {
            StorageManager storage = StorageManager.getInstance();
            storage.ensureStorageReady(false);
            File dir = storage.getRecordingsDir();
            // Replay is a secondary writer. Only the primary cam_* writer may
            // mutate the global fallback state used by UI and cleanup routing.
            dir = storage.resolveTargetWithEnospcFallback(dir, OUTPUT_RESERVE_BYTES, false);
            if (dir == null) return null;
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return null;
            if (!storage.ensureRecordingsSpaceForRecorder(OUTPUT_RESERVE_BYTES, dir)) return null;

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            for (int suffix = 0; suffix < 1_000; suffix++) {
                // A dedicated prefix prevents a replay remux from ever sharing
                // a final or .tmp path with the continuous recorder's cam_*
                // segment rotation. RecordingsIndex classifies replay_* as a
                // normal dashcam clip.
                String name = "replay_" + timestamp
                        + (suffix == 0 ? "" : "_" + suffix) + ".mp4";
                File candidate = new File(dir, name);
                if (!candidate.exists() && !new File(candidate.getAbsolutePath() + ".tmp").exists()) {
                    return candidate;
                }
            }
        } catch (Throwable t) {
            logger.warn("Instant replay output preparation failed: " + t.getMessage());
        }
        return null;
    }

    private static RequestResult result(Status status, String message, int availableBefore) {
        return new RequestResult(status, message, availableBefore);
    }
}
