package com.overdrive.app.storage;

import android.os.StatFs;
import android.util.Log;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StorageManager - SOTA Storage Management for Overdrive
 * 
 * Manages recording and surveillance storage with:
 * - Dedicated directories under /storage/emulated/0/Overdrive/ (internal) or SD card
 * - Storage type selection: INTERNAL or SD_CARD for both recordings and surveillance
 * - Configurable size limits (100MB - 10000MB for SD card)
 * - Automatic cleanup of oldest files when limit is reached
 * - Event-driven cleanup (after each file save)
 * - Periodic background cleanup during long recordings
 * - Thread-safe operations
 * - SD card detection and availability monitoring
 * 
 * SOTA Cleanup Strategy:
 * 1. Pre-recording check - Reserve space before starting
 * 2. Post-file cleanup - Run after each file is closed/saved
 * 3. Periodic cleanup - Background task every 30 seconds during active recording
 * 
 * Storage Selection:
 * - Each storage type (recordings, surveillance) can independently use internal or SD card
 * - SD card paths are auto-discovered via BYD system properties or known mount points
 * - Graceful fallback to internal storage if SD card becomes unavailable
 */
public class StorageManager {
    private static final String TAG = "StorageManager";
    
    // Storage type enum
    public enum StorageType {
        INTERNAL,
        SD_CARD,
        USB
    }
    
    // Hybrid logger - uses DaemonLogger when running as daemon, android.util.Log otherwise
    private static boolean useDaemonLogger = false;
    private static com.overdrive.app.logging.DaemonLogger daemonLogger = null;
    
    /**
     * Enable daemon logging mode (call from daemon process).
     */
    public static void enableDaemonLogging() {
        useDaemonLogger = true;
        daemonLogger = com.overdrive.app.logging.DaemonLogger.getInstance(TAG);
    }
    
    private static void logInfo(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.info(msg);
        } else {
            Log.i(TAG, msg);
        }
    }
    
    private static void logWarn(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.warn(msg);
        } else {
            Log.w(TAG, msg);
        }
    }
    
    private static void logError(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.error(msg);
        } else {
            Log.e(TAG, msg);
        }
    }

    /**
     * Bounded {@link Process#waitFor()} — kills the child if it doesn't exit
     * within {@code timeoutMs}. Returns the exit code on clean exit, or
     * {@code -1} on timeout / interrupt. The vendored {@code sm} binary on
     * BYD ROMs has been observed to hang indefinitely when an SD/USB volume
     * is in a bad state (post-update with the slot empty, or with stale
     * mount table state after a SIGKILL'd vold helper). Without a timeout
     * here, the daemon's startup path blocked forever — see the
     * recovery-first comment in CameraDaemon.main().
     */
    private static int waitForBounded(Process p, long timeoutMs, String label) {
        try {
            if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                return p.exitValue();
            }
            logWarn(label + ": timed out after " + timeoutMs + "ms — killing child");
            p.destroyForcibly();
            // Give the kernel a moment to reap, but bound this too.
            try { p.waitFor(500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { p.destroyForcibly(); } catch (Exception ignored) {}
            return -1;
        }
    }
    
    private static void logDebug(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.debug(msg);
        } else {
            Log.d(TAG, msg);
        }
    }
    
    // Base directories for Overdrive files
    private static final String INTERNAL_BASE_DIR = "/storage/emulated/0/Overdrive";

    // Legacy paths from older app versions. Files here aren't written anymore
    // but they still count toward the user's configured limit and must be
    // reaped — otherwise a 500 MB limit can show 800 MB used in the UI.
    private static final String LEGACY_APP_FILES_DIR = "/storage/emulated/0/Android/data/com.overdrive.app/files";
    private static final String LEGACY_SURVEILLANCE_DIR = LEGACY_APP_FILES_DIR + "/sentry_events";

    // Subdirectories
    public static final String RECORDINGS_SUBDIR = "recordings";
    public static final String SURVEILLANCE_SUBDIR = "surveillance";
    public static final String PROXIMITY_SUBDIR = "proximity";
    public static final String TRIPS_SUBDIR = "trips";
    
    // Config file location
    private static final String CONFIG_FILE = "/data/local/tmp/overdrive_config.json";

    // Persisted UUID of whichever public volume we've previously confirmed as
    // the SD card. Used as the first-class signal in classifyPublicVolume()
    // when the BYD vendor prop (sys.byd.mSdcardUuid) is empty. The vendor
    // prop is only populated WHILE the card is mounted on this firmware, so
    // during the unmount window between ACC OFF and our remount attempt the
    // prop returns "" and the major-number fallback was misclassifying the
    // bridged-SD (major 8, DEVNAME=sd*) as USB. Learning the FAT volume
    // serial from a previous successful cycle bridges the gap. File is
    // tiny (~10 bytes), atomic-write semantics not required because a stale
    // value still resolves to the same physical card.
    private static final String LEARNED_SD_UUID_FILE = "/data/local/tmp/overdrive_sd_uuid";
    
    // Default limits (in bytes)
    private static final long DEFAULT_RECORDINGS_LIMIT_MB = 500;
    private static final long DEFAULT_SURVEILLANCE_LIMIT_MB = 500;
    private static final long DEFAULT_PROXIMITY_LIMIT_MB = 500;
    private static final long DEFAULT_TRIPS_LIMIT_MB = 500;
    private static final long MIN_LIMIT_MB = 100;

    // Hard ceiling fallback used only when StatFs reports 0 (volume unmounted
    // at the moment of the read). Keeps the slider usable while we wait for a
    // refresh. Real cap comes from getEffectiveMaxLimitMb(type) below, which
    // pulls the live filesystem total minus a safety reserve.
    private static final long MAX_LIMIT_MB_FALLBACK = 100000;  // 100GB

    // Per-category share of the volume — recordings, surveillance, trips,
    // proximity all live on the same FS, so giving each one 100% of the disk
    // overcommits by 4x. 40% per category leaves headroom for the OS, the
    // muxer flush queue, and the other Overdrive categories competing for
    // the same pool.
    private static final double PER_CATEGORY_SHARE = 0.40;

    // Reserve a small fraction of the volume so the encoder can never hit
    // ENOSPC mid-file from the user setting "max" on a near-empty disk.
    private static final long VOLUME_HEADROOM_MB = 256;
    
    // Periodic cleanup interval (30 seconds)
    private static final long CLEANUP_INTERVAL_SECONDS = 30;
    
    // Current limits
    private static long recordingsLimitMb = DEFAULT_RECORDINGS_LIMIT_MB;
    private static long surveillanceLimitMb = DEFAULT_SURVEILLANCE_LIMIT_MB;
    private static long proximityLimitMb = DEFAULT_PROXIMITY_LIMIT_MB;
    private long tripsLimitMb = DEFAULT_TRIPS_LIMIT_MB;
    
    // Storage type selection (SOTA: independent selection for recordings and surveillance)
    private StorageType recordingsStorageType = StorageType.INTERNAL;
    private StorageType surveillanceStorageType = StorageType.INTERNAL;
    private StorageType tripsStorageType = StorageType.INTERNAL;
    
    // SD card state
    private String sdCardPath = null;
    private boolean sdCardAvailable = false;

    // USB state — flash drives mounted via OTG. Treated as a separate volume
    // class from SD because of how head-units enumerate them: SD sits behind
    // an mmc driver (Linux major 179), USB behind sd/SCSI (major 8/65/66/...).
    // Without this distinction discoverSdCard() will happily latch onto a USB
    // stick when both are present.
    private String usbPath = null;
    private boolean usbAvailable = false;
    
    // Singleton instance
    private static StorageManager instance;
    
    // Internal storage directories (always available)
    private File internalRecordingsDir;
    private File internalSurveillanceDir;
    private File internalProximityDir;
    private File internalTripsDir;
    
    // SD card directories (may be null if SD card not available)
    private File sdCardRecordingsDir;
    private File sdCardSurveillanceDir;
    private File sdCardProximityDir;
    private File sdCardTripsDir;

    // USB directories (may be null if USB drive not available)
    private File usbRecordingsDir;
    private File usbSurveillanceDir;
    private File usbProximityDir;
    private File usbTripsDir;
    
    // Active directories (based on storage type selection)
    private File recordingsDir;
    private File surveillanceDir;
    private File proximityDir;
    private File tripsDir;
    
    // Background cleanup scheduler
    private ScheduledExecutorService cleanupScheduler;
    private final AtomicBoolean recordingActive = new AtomicBoolean(false);
    private final AtomicBoolean surveillanceActive = new AtomicBoolean(false);

    // Absolute path of the currently-recording trip telemetry file (.jsonl.gz)
    // or null when no trip is active. Path-based instead of a boolean so a
    // limit-change cleanup mid-trip can still reap older trip files; only the
    // in-flight file is protected. Read by ensureSpace before each delete.
    private volatile String activeTripFilePath = null;

    // SOTA: Authoritative "encoder is mid-write" probe.
    //
    // The setRecordingActive / setSurveillanceActive booleans above track the
    // *user-facing* recording state, set by GpuMosaicRecorder.startRecording /
    // stopRecording. They are NOT a reliable signal for "is the disk writer
    // currently flushing packets to the SD card", because there's a real lag:
    //   - User starts recording → recordingActive=true. Encoder hasn't yet
    //     produced its first packet. Cleanup CAN safely run for ~100 ms.
    //   - User stops recording → recordingActive=false. Disk writer is still
    //     draining the muxer queue + finalising the moov atom (~50-200ms).
    //     A cleanup burst here corrupts the still-open file's footer write.
    //
    // The probe below points at HardwareEventRecorderGpu.isWritingToFile() —
    // the volatile flag set under startStopLock that goes true the moment the
    // muxer is constructed and false ONLY after closeEventRecording has
    // released it. Cleanup uses this to gate destructive deletes and avoid
    // contending with the realtime SD-card writes during an active recording.
    //
    // Default probe returns false so a stale binding never blocks cleanup
    // forever. PipelineDaemon installs the real probe after the encoder
    // exists; if the encoder is later released, the probe returns false
    // gracefully (HardwareEventRecorderGpu.isWritingToFile reads a volatile
    // field that's false when the recorder isn't holding a muxer).
    private volatile java.util.function.BooleanSupplier encoderWritingProbe = () -> false;
    /**
     * Set true the first time setEncoderWritingProbe wires a real probe. The
     * periodic cleanup loop early-returns until this flips, so the first
     * 30-second tick after daemon boot can't run un-gated against a default
     * fail-open probe (audit P1).
     */
    private final java.util.concurrent.atomic.AtomicBoolean probeWired =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // Async cleanup executor (single thread to avoid concurrent cleanup)
    private final java.util.concurrent.ExecutorService asyncCleanupExecutor =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(() -> {
                // SOTA: Linux nice +10 (THREAD_PRIORITY_BACKGROUND). The Java
                // MIN_PRIORITY below is advisory; this is what actually keeps
                // file deletes from preempting the disk writer's muxer writes
                // under SD card I/O contention.
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                r.run();
            }, "StorageCleanupAsync");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

    // Deferred-cleanup queue: when a save event fires while encoder is mid-
    // write, instead of running the delete burst we mark the directory as
    // "needs cleanup later". A polling pass on the same asyncCleanupExecutor
    // drains this set the next time encoderWritingProbe returns false. Without
    // this, a back-to-back recording/cleanup pattern would skip cleanup
    // forever and storage would grow past the limit.
    private final java.util.Set<String> deferredCleanupDirs =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final String DEFERRED_RECORDINGS = "recordings";
    private static final String DEFERRED_SURVEILLANCE = "surveillance";
    private static final String DEFERRED_PROXIMITY = "proximity";
    private static final String DEFERRED_TRIPS = "trips";

    // Cleanup lock to prevent concurrent cleanup operations
    private final Object cleanupLock = new Object();
    
    // SD card / USB mount watchdog (keeps the configured external volume
    // mounted during sentry mode). Single scheduler covers both classes —
    // each volume gets its own consecutive-failure counter so quiet-log
    // throttling is independent.
    private ScheduledExecutorService sdCardWatchdog;
    private static final long SD_WATCHDOG_INTERVAL_SECONDS = 15;
    private int sdWatchdogConsecutiveFailures = 0;
    private int usbWatchdogConsecutiveFailures = 0;
    private static final int SD_WATCHDOG_MAX_VERBOSE_FAILURES = 5;  // Log verbosely for first 5 failures
    private static final int SD_WATCHDOG_QUIET_LOG_INTERVAL = 20;   // Then log every 20th attempt (~5 min)

    // Rate-limit for the raw `sm list-volumes` diagnostic dump. The fingerprint
    // line (publicRows=N matchedRows=M) is cheap and stays at logInfo on every
    // failure; the multi-line raw output only re-prints when 5 minutes have
    // elapsed since the last dump. Without this, a multi-hour park with the
    // SD genuinely missing produces ~240 raw dumps/hour which floods cam_daemon.log.
    private long lastSmRawDumpAtMs = 0;
    private static final long SM_RAW_DUMP_INTERVAL_MS = 5 * 60_000;
    
    /**
     * Parse a storage-type string from persisted config. Anything that
     * doesn't match SD_CARD/USB falls back to INTERNAL — that includes
     * legacy configs and accidentally-truncated writes.
     */
    private static StorageType parseStorageType(String s) {
        if ("SD_CARD".equals(s)) return StorageType.SD_CARD;
        if ("USB".equals(s))     return StorageType.USB;
        return StorageType.INTERNAL;
    }

    private StorageManager() {
        discoverVolumes();
        initDirectories();
        loadConfig();

        // If config says SD/USB but it's not available, try to mount it on a
        // background thread. Even with the per-call timeouts in
        // ensureVolumeMounted, the worst-case is `sm list-volumes` (2s) +
        // `sm mount` (8s) + 10×500ms accessibility-poll = up to ~15s. Doing
        // that synchronously here used to wedge daemon startup whenever a
        // configured external volume was missing or in a bad state — which
        // is exactly the post-update scenario users hit (the updater's
        // pkill-9 of vold helpers can leave the volume marked-unmounted in
        // the kernel until the next ACC cycle).
        //
        // The startSdCardWatchdog() loop already retries failed mounts on a
        // schedule, so there's no value in blocking startup on a one-shot
        // attempt. Same logic for USB. updateActiveDirectories() is called
        // here AND inside ensureVolumeMounted on success, so consumers see
        // INTERNAL until/if the mount lands, then transparently switch.
        Runnable mountAttempt = () -> {
            try {
                if (!sdCardAvailable &&
                    (surveillanceStorageType == StorageType.SD_CARD ||
                     recordingsStorageType == StorageType.SD_CARD ||
                     tripsStorageType == StorageType.SD_CARD)) {
                    logInfo("SD card configured but not available - attempting mount (async)...");
                    ensureSdCardMounted(true);
                }
                if (!usbAvailable &&
                    (surveillanceStorageType == StorageType.USB ||
                     recordingsStorageType == StorageType.USB ||
                     tripsStorageType == StorageType.USB)) {
                    logInfo("USB configured but not available - attempting mount (async)...");
                    ensureUsbMounted(true);
                }
            } catch (Exception e) {
                logWarn("Async mount attempt failed: " + e.getMessage());
            }
        };
        new Thread(mountAttempt, "StorageMountInit").start();

        updateActiveDirectories();

        // One-shot startup reap. If the user lowered the limit, switched
        // storage type, or upgraded from a legacy build, the inactive +
        // legacy locations may be holding orphan files that count toward
        // the limit. Reap them once at boot so the UI total agrees with
        // the configured limit before any new event fires the per-save
        // cleanup. Async — don't block daemon startup.
        asyncCleanupExecutor.execute(() -> {
            synchronized (cleanupLock) {
                try {
                    sweepOrphanTempFiles();
                    ensureRecordingsSpace(0);
                    ensureSurveillanceSpace(0);
                    ensureProximitySpace(0);
                    ensureTripsSpace(0);
                } catch (Exception e) {
                    logWarn("Startup reap failed: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Delete orphan {@code .mp4.tmp} and {@code .broken} files left behind by
     * abnormal daemon exits (SIGKILL, OOM, ACC-cycle kill mid-recording).
     *
     * <p>Only the close path renames {@code .mp4.tmp → .mp4}; if the daemon
     * dies before the rename, the {@code .tmp} sits forever — counted by the
     * filesystem but invisible to the events UI. On BYD head-units where
     * the daemon is regularly killed by ACC cycles, these accumulate until
     * the SD card fills.
     *
     * <p>Conservative policy: only delete files older than {@code TEMP_FILE_GRACE_MS}
     * (10 minutes) so we never race a recording in flight. Anything younger
     * is assumed to belong to a live recording on a sibling daemon process.
     *
     * <p>Sweeps surveillance, proximity, and recordings dirs (current + legacy
     * locations).
     */
    private void sweepOrphanTempFiles() {
        final long graceMs = 10 * 60 * 1000L;
        final long cutoff = System.currentTimeMillis() - graceMs;
        int deleted = 0;
        long bytesFreed = 0;

        java.util.HashSet<String> seenPaths = new java.util.HashSet<>();
        for (String category : new String[]{"recordings", "surveillance", "proximity", "trips"}) {
            String[] partials = partialExtensionsForCategory(category);
            if (partials.length == 0) continue;
            for (File dir : getReapableDirs(category)) {
                if (dir == null) continue;
                String path = dir.getAbsolutePath();
                if (!seenPaths.add(path)) continue;
                if (!dir.isDirectory()) continue;
                File[] files = dir.listFiles((d, name) -> {
                    for (String ext : partials) {
                        if (name.endsWith(ext)) return true;
                    }
                    return false;
                });
                if (files == null) continue;
                for (File f : files) {
                    // Don't unlink a still-being-written trip file in case
                    // the recorder uses an atomic ".jsonl.gz.tmp → .jsonl.gz"
                    // rename and the in-flight file is the .tmp.
                    if (activeTripFilePath != null
                            && (activeTripFilePath.equals(f.getAbsolutePath())
                                || activeTripFilePath.equals(f.getAbsolutePath() + ".tmp"))) {
                        continue;
                    }
                    if (f.lastModified() > cutoff) continue;  // grace window
                    long size = f.length();
                    boolean ok = f.delete();
                    if (!ok) ok = deleteFileViaShell(f);
                    if (ok) {
                        deleted++;
                        bytesFreed += size;
                    } else {
                        logWarn("Orphan tmp delete failed: " + f.getAbsolutePath());
                    }
                }
            }
        }
        if (deleted > 0) {
            logInfo("Orphan tmp sweep: deleted " + deleted + " files, "
                    + (bytesFreed / 1024) + " KB freed");
        }
    }
    
    public static synchronized StorageManager getInstance() {
        if (instance == null) {
            instance = new StorageManager();
        }
        return instance;
    }
    
    // ==================== SD Card Discovery ====================
    
    /**
     * SOTA: Mount SD card if unmounted.
     * Uses Android's StorageManager (sm) command to mount public volumes.
     * 
     * @return true if SD card is now mounted, false otherwise
     */
    public boolean ensureSdCardMounted() {
        return ensureSdCardMounted(false);
    }
    
    /**
     * SOTA: Mount SD card, optionally forcing a remount.
     * Uses Android's StorageManager (sm) command to mount public volumes.
     *
     * @param force If true, always attempt to mount even if already mounted
     * @return true if SD card is now mounted, false otherwise
     */
    public boolean ensureSdCardMounted(boolean force) {
        return ensureVolumeMounted("SD", force);
    }

    /**
     * Mount USB drive (or remount if stale). Mirror of ensureSdCardMounted
     * for the USB volume class.
     */
    public boolean ensureUsbMounted() {
        return ensureUsbMounted(false);
    }

    public boolean ensureUsbMounted(boolean force) {
        return ensureVolumeMounted("USB", force);
    }

    /**
     * Generic mount-or-remount for a specific volume class (SD or USB).
     * Walks {@code sm list-volumes all}, classifies each public volume by
     * underlying block-device major number (see classifyPublicVolume), and
     * mounts the first one matching the requested class. Updates the
     * corresponding {@code <class>Path} / {@code <class>Available} fields
     * + initializes per-class directories on success.
     *
     * @param targetClass "SD" or "USB"
     * @param force       attempt even if already mounted (for remount cases)
     */
    private boolean ensureVolumeMounted(String targetClass, boolean force) {
        boolean isSd = "SD".equals(targetClass);
        String currentPath = isSd ? sdCardPath : usbPath;
        boolean currentAvailable = isSd ? sdCardAvailable : usbAvailable;

        // Quick check: if path is already accessible, no work needed.
        // Use the cheap StatFs+canWrite probe — the touch+rm shell exec
        // (isMountWritable) blocks up to 2s under FUSE binder contention
        // from concurrent dir-walks, falsely reporting unmounted and
        // forcing the slow `sm mount` path even though the volume is fine.
        // This was the root of the "trips storage selection silently fails"
        // bug: setTripsStorageType → ensureExternalAvailable → here, and
        // the 2s timeout returned false → setTripsStorageType returned false.
        if (!force && currentAvailable && currentPath != null) {
            if (isPathLikelyMounted(currentPath)) {
                logDebug(targetClass + " already mounted at: " + currentPath);
                return true;
            }
        }

        logDebug("Mounting " + targetClass + "...");

        // Pre-mount discovery via /proc/mounts. On some BYD ROMs the system
        // remounts the SD slot itself a few seconds after ACC OFF, but `sm
        // list-volumes` lags behind the kernel's actual mount table — so the
        // SD is live at /storage/<uuid> but `sm` either omits the row or
        // reports it as `unmounted`. Without this probe, ensureVolumeMounted
        // would proceed to `sm mount <id>` (which fails because the kernel
        // already owns the mount) and fall back to internal storage despite
        // the card being writable the whole time.
        //
        // discoverVolumes()'s /proc/mounts pass already does the right thing,
        // but the mount path didn't share that knowledge. Run a discovery
        // pass FIRST; if it commits the field for our class, we're done.
        try {
            discoverVolumes();
            if (isSd && sdCardAvailable && sdCardPath != null
                    && isPathLikelyMounted(sdCardPath)) {
                logInfo(targetClass + " already mounted via /proc/mounts: " + sdCardPath);
                if (isSd) initSdCardDirectories(); else initUsbDirectories();
                updateActiveDirectories();
                return true;
            }
            if (!isSd && usbAvailable && usbPath != null
                    && isPathLikelyMounted(usbPath)) {
                logInfo(targetClass + " already mounted via /proc/mounts: " + usbPath);
                initUsbDirectories();
                updateActiveDirectories();
                return true;
            }
        } catch (Throwable t) {
            // Discovery is best-effort here — never let it block the
            // sm-driven mount path below.
            logDebug("Pre-mount discovery threw: " + t.getMessage());
        }

        // Raw sm output captured here so we can dump it on failure. Some BYD
        // ROMs at ACC OFF emit no `public:` row for the SD slot at all —
        // distinguishing that from the "row exists but in unmounted state"
        // case is critical for diagnosis. Without the dump, the daemon log
        // shows "SD card mount failed" with no clue WHICH failure mode.
        StringBuilder rawSmOutput = new StringBuilder();
        try {
            Process listProcess = Runtime.getRuntime().exec(new String[]{"sm", "list-volumes", "all"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
            String line;
            String volumeId = null;
            String volumeUuid = null;
            int volMajor = -1, volMinor = -1;
            int publicRowCount = 0;
            int matchedRowCount = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                rawSmOutput.append(line).append('\n');
                logDebug("sm list-volumes: " + line);
                if (!line.startsWith("public:")) continue;
                publicRowCount++;
                String[] parts = line.split("\\s+");
                if (parts.length < 3) continue;

                String[] dev = parts[0].substring("public:".length()).split(",");
                int major, minor;
                try {
                    major = Integer.parseInt(dev[0]);
                    minor = Integer.parseInt(dev[1]);
                } catch (Exception e) {
                    continue;
                }
                String state = parts[1];
                String thisUuid = parts[2];
                String klass = classifyPublicVolume(major, minor, thisUuid);
                if (!targetClass.equals(klass)) continue;  // wrong volume class
                matchedRowCount++;

                if ("mounted".equals(state)) {
                    String mountPath = "/storage/" + thisUuid;
                    // Cheap check (no shell fork). See note at the
                    // already-accessible branch above for why touch+rm is
                    // unsafe under contention.
                    if (isPathLikelyMounted(mountPath)) {
                        if (isSd) {
                            sdCardPath = mountPath;
                            sdCardAvailable = true;
                            learnSdUuid(thisUuid);  // remember for next unmount window
                        } else {
                            usbPath = mountPath;
                            usbAvailable = true;
                        }
                        logInfo(targetClass + " already mounted at: " + mountPath);
                        reader.close();
                        waitForBounded(listProcess, 2_000, "sm list-volumes (already-mounted)");
                        if (isSd) initSdCardDirectories(); else initUsbDirectories();
                        updateActiveDirectories();
                        return true;
                    }
                    logWarn(targetClass + " volume " + parts[0] + " reports mounted but path " +
                        mountPath + " not accessible — will force remount");
                }

                volumeId = parts[0];
                volumeUuid = thisUuid;
                volMajor = major;
                volMinor = minor;
                break;
            }
            reader.close();
            waitForBounded(listProcess, 2_000, "sm list-volumes (ensureVolumeMounted)");

            // Diagnostic: capture the WHY of an `sm list-volumes` miss. On
            // affected BYD models at ACC OFF this often shows publicRows>0
            // but matchedRows==0 (slot present but classifier rejected it
            // because sys.byd.mSdcardUuid is empty during the transition).
            // The mismatch fingerprint tells us whether mitigation B (kernel
            // fallback) or mitigation C (path-based discovery) needs to fire.
            if (volumeId == null) {
                logInfo("sm list-volumes: no " + targetClass + " match (publicRows="
                    + publicRowCount + ", matchedRows=" + matchedRowCount
                    + ") — falling back to kernel-level retry");
                long nowMs = System.currentTimeMillis();
                if (rawSmOutput.length() > 0
                        && (nowMs - lastSmRawDumpAtMs) >= SM_RAW_DUMP_INTERVAL_MS) {
                    logInfo("sm list-volumes raw output:\n" + rawSmOutput.toString().trim());
                    lastSmRawDumpAtMs = nowMs;
                }
            }

            if (volumeId != null) {
                Process mountProcess = Runtime.getRuntime().exec(new String[]{"sm", "mount", volumeId});
                BufferedReader outReader = new BufferedReader(new InputStreamReader(mountProcess.getInputStream()));
                BufferedReader errReader = new BufferedReader(new InputStreamReader(mountProcess.getErrorStream()));
                StringBuilder output = new StringBuilder();
                String outLine;
                while ((outLine = outReader.readLine()) != null) output.append(outLine).append("\n");
                while ((outLine = errReader.readLine()) != null) output.append("ERR: ").append(outLine).append("\n");
                outReader.close();
                errReader.close();

                // 8s ceiling for the actual mount. Healthy SD/USB mounts on
                // BYD finish in <1s; anything past 8s is a stuck vold and
                // we'd rather fall back to internal than wedge the daemon.
                int exitCode = waitForBounded(mountProcess, 8_000, "sm mount " + volumeId);
                logInfo("sm mount " + volumeId + " exit code: " + exitCode +
                    (output.length() > 0 ? ", output: " + output.toString().trim() : ""));

                if (exitCode == 0 && volumeUuid != null) {
                    String mountPath = "/storage/" + volumeUuid;
                    // Lengthened from 10 to 20 iterations (5s → 10s budget).
                    // On affected BYD models the FUSE bridge is published
                    // ~3-6s after `sm mount` returns 0 — the prior 5s budget
                    // raced the publication and falsely concluded the mount
                    // failed. Per-iteration cost is microseconds (StatFs +
                    // canWrite, no shell fork), so the longer poll is free
                    // when the mount is healthy.
                    boolean interrupted = false;
                    for (int i = 0; i < 20 && !interrupted; i++) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            // Restore the flag and break — the watchdog (or
                            // shutdown path) is asking us to stop. Without
                            // this, the prior code swallowed the interrupt
                            // via the outer Exception catch and silently
                            // turned a shutdown request into 10s of polling.
                            Thread.currentThread().interrupt();
                            interrupted = true;
                            break;
                        }
                        if (isPathLikelyMounted(mountPath)) {
                            if (isSd) {
                                sdCardPath = mountPath;
                                sdCardAvailable = true;
                                learnSdUuid(volumeUuid);
                            } else {
                                usbPath = mountPath;
                                usbAvailable = true;
                            }
                            logInfo(targetClass + " mounted successfully at: " + mountPath
                                + " (poll attempt " + (i + 1) + "/20)");
                            if (isSd) initSdCardDirectories(); else initUsbDirectories();
                            updateActiveDirectories();
                            return true;
                        }
                        logDebug("Waiting for " + targetClass + " mount... attempt " + (i+1) + "/20");
                    }
                    logWarn(targetClass + " mount path not accessible after mount: " + mountPath);
                } else {
                    logWarn("sm mount " + volumeId + " failed with exit code: " + exitCode);
                }
            } else {
                logDebug("No public " + targetClass + " volume found in sm output");
            }

        } catch (Exception e) {
            logError("Error mounting " + targetClass + ": " + e.getMessage());
        }

        // TODO: USB has no analogous kernel-level fallback. /sys/class/mmc_host
        // is mmc-only, so an equivalent USB probe would walk /sys/bus/usb/devices.
        // The reported symptom (ACC OFF → external storage invisible) is SD-only
        // so far; revisit if USB-only configs report the same pattern.
        // Kernel-level fallback for the "sm doesn't see the slot at all" case.
        // On affected BYD ROMs, ACC OFF transiently deregisters the volume from
        // vold's VolumeRecord — but the underlying mmcblk* device is still
        // alive and the kernel mount table either still holds it or is about
        // to. Two recovery routes:
        //   (a) The card may already be mounted by the system at /mnt/media_rw/
        //       or /storage/<uuid> with no `sm` row — discoverVolumes() picks
        //       this up via /proc/mounts. Run a fresh discovery and re-check.
        //   (b) The card is physically present (visible under /sys/class/mmc_host)
        //       but vold hasn't surfaced it yet — wait + re-discover up to 5
        //       times (5s total) for vold to catch up. We don't try `sm forget`
        //       or partition rescans here; those are too invasive and can leave
        //       a wedged volume worse off.
        // If neither route catches the card, fall through to internal storage —
        // the caller's existing fallback path handles that gracefully.
        if (isSd && !sdCardAvailable && isSdCardPhysicallyPresent()) {
            logInfo("Kernel fallback: SD slot reports a card present but sm/proc-mounts didn't find it — polling for vold catch-up");
            for (int i = 0; i < 5; i++) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                discoverVolumes();
                if (sdCardAvailable && sdCardPath != null
                        && isPathLikelyMounted(sdCardPath)) {
                    logInfo("Kernel fallback: SD picked up after " + (i + 1) + "s at " + sdCardPath);
                    initSdCardDirectories();
                    updateActiveDirectories();
                    return true;
                }
            }
            logWarn("Kernel fallback: SD slot card present but vold never surfaced it within 5s");
        }

        // Re-run discovery in case mount succeeded but we missed it
        discoverVolumes();
        return isSd ? sdCardAvailable : usbAvailable;
    }

    /**
     * Probe whether the SD slot has a physical card inserted, independent
     * of vold/sm state. Used by the kernel-level mount fallback to distinguish
     * "card pulled" (give up cleanly) from "vold hasn't caught up yet"
     * (worth waiting on). Reads /sys/class/mmc_host/mmcN/mmcN:* — the kernel
     * publishes this entry as soon as the card is electrically detected,
     * regardless of mount state.
     *
     * @return true if at least one mmc_host has a card-present subdirectory
     */
    private boolean isSdCardPhysicallyPresent() {
        try {
            File mmcHostDir = new File("/sys/class/mmc_host");
            if (!mmcHostDir.exists() || !mmcHostDir.isDirectory()) return false;
            File[] hosts = mmcHostDir.listFiles();
            if (hosts == null) return false;
            for (File host : hosts) {
                File[] children = host.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    // mmcN:NNNN entries appear when a card is attached.
                    // Internal eMMC also creates such entries (mmc0:0001 typically),
                    // so the presence check alone isn't SD-specific — but the
                    // caller already gated on classifyPublicVolume not finding
                    // an SD via sm, so any mmc_host child here that ISN'T the
                    // eMMC indicates an inserted external card. We don't need
                    // to disambiguate further: false-positives just trigger a
                    // 5s vold-catchup poll that no-ops and falls through.
                    if (child.getName().matches("mmc\\d+:[0-9a-fA-F]+")) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            logDebug("isSdCardPhysicallyPresent probe failed: " + t.getMessage());
        }
        return false;
    }

    /**
     * Cheap probe: is any USB mass-storage device attached?
     *
     * <p>Used by the single-volume tiebreaker in {@link #discoverVolumes}
     * to distinguish "lone SCSI-bridged SD card" (no USB device → treat as
     * SD) from "real USB stick is the only thing inserted" (USB device
     * present → keep classifying as USB). We walk {@code /sys/bus/usb/devices}
     * for entries with {@code bInterfaceClass=08} (Mass Storage); pure-host
     * USB ports have no children matching that. Returns false on probe error
     * so the tiebreaker stays conservative — better to miss the SD promotion
     * than to misclassify a real USB stick as SD.
     */
    private boolean isUsbDeviceAttached() {
        try {
            File usbDir = new File("/sys/bus/usb/devices");
            if (!usbDir.exists() || !usbDir.isDirectory()) return false;
            File[] devices = usbDir.listFiles();
            if (devices == null) return false;
            for (File dev : devices) {
                // Skip root hubs (usb1, usb2, …) — those are always present
                // even when nothing is plugged in. Real attached devices show
                // up as e.g. 1-1, 1-1.2 (port path notation).
                String name = dev.getName();
                if (name.startsWith("usb") || !name.contains("-")) continue;
                File[] children = dev.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    File classFile = new File(child, "bInterfaceClass");
                    if (!classFile.isFile() || !classFile.canRead()) continue;
                    try (BufferedReader r = new BufferedReader(new FileReader(classFile))) {
                        String cls = r.readLine();
                        // 08 = USB Mass Storage. 06 = Image (cameras), other
                        // classes (HID etc.) won't surface as a public:
                        // volume in sm list-volumes anyway.
                        if (cls != null && "08".equalsIgnoreCase(cls.trim())) return true;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Throwable t) {
            logDebug("isUsbDeviceAttached probe failed: " + t.getMessage());
        }
        return false;
    }
    
    /**
     * Check if SD card is currently mounted (without attempting to mount).
     * Simply checks if the path exists and is writable.
     *
     * @return true if SD card is mounted
     */
    public boolean isSdCardMounted() {
        if (sdCardPath == null) {
            return false;
        }
        return isMountWritable(sdCardPath);
    }

    /**
     * Cheap liveness check for the SD card mount, suitable for the watchdog
     * tick (called every 15s). Avoids forking a `touch+rm` shell — that
     * probe blocks for up to 2s under FUSE binder contention from concurrent
     * dir-walks (recordings/stats, storage/external, etc.) and falsely
     * reports "unmounted", triggering a remount cascade that itself runs
     * more shell forks and amplifies the contention.
     *
     * <p>Layered check, fail-fast:
     * <ol>
     *   <li>Path resolved? Directory exists? — Java {@code File} API, no fork.</li>
     *   <li>{@code StatFs.getTotalBytes()} — single binder call, ~200µs.</li>
     *   <li>{@code File.canWrite()} — Java permission check, no fork.</li>
     * </ol>
     * Three signals all green = mount is live. The expensive write probe is
     * reserved for {@link #isMountWritable} which callers invoke when they
     * are about to actually write.
     */
    public boolean isSdCardLikelyMounted() {
        if (sdCardPath == null) return false;
        File d = new File(sdCardPath);
        if (!d.exists() || !d.isDirectory()) return false;
        try {
            android.os.StatFs s = new android.os.StatFs(sdCardPath);
            if (s.getTotalBytes() <= 0) return false;
        } catch (Throwable t) {
            return false;
        }
        return d.canWrite();
    }

    /**
     * Check if USB drive is currently mounted (without attempting to mount).
     */
    public boolean isUsbMounted() {
        if (usbPath == null) {
            return false;
        }
        return isMountWritable(usbPath);
    }

    /**
     * Ensure storage is ready for use.
     * If SD/USB storage is selected but not mounted, attempts to mount it.
     * If mount fails, falls back to internal storage.
     *
     * @param forSurveillance true if checking for surveillance, false for recordings
     * @return true if storage is ready (either SD/USB mounted or fallback to internal)
     */
    public boolean ensureStorageReady(boolean forSurveillance) {
        StorageType selectedType = forSurveillance ? surveillanceStorageType : recordingsStorageType;

        if (selectedType == StorageType.INTERNAL) {
            // Internal storage is always ready
            return true;
        }

        // CRITICAL: Don't switch storage location while recording is active
        // This prevents files from being split across volumes
        if (!forSurveillance && recordingActive.get()) {
            logDebug("Recording active - not switching storage location");
            return true;
        }
        if (forSurveillance && surveillanceActive.get()) {
            logDebug("Surveillance active - not switching storage location");
            return true;
        }

        if (selectedType == StorageType.SD_CARD) {
            if (!isSdCardMounted()) {
                logInfo("SD card not mounted, attempting to mount for " +
                    (forSurveillance ? "surveillance" : "recordings"));
                if (!ensureSdCardMounted()) {
                    logWarn("Failed to mount SD card, falling back to internal storage");
                    if (forSurveillance) {
                        surveillanceDir = internalSurveillanceDir;
                        proximityDir = internalProximityDir;
                    } else {
                        recordingsDir = internalRecordingsDir;
                    }
                    return true;
                }
            }
            initSdCardDirectories();
            updateActiveDirectories();

            // Pre-reserve space on SD card by cleaning BYD dashcam files if needed
            try {
                ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
                if (cleaner.isEnabled() && cleaner.isSdCardAvailable()) {
                    cleaner.ensureReservedSpace();
                }
            } catch (Exception e) {
                logWarn("Pre-recording CDR cleanup failed: " + e.getMessage());
            }
            return true;
        }

        if (selectedType == StorageType.USB) {
            if (!isUsbMounted()) {
                logInfo("USB not mounted, attempting to mount for " +
                    (forSurveillance ? "surveillance" : "recordings"));
                if (!ensureUsbMounted()) {
                    logWarn("Failed to mount USB, falling back to internal storage");
                    if (forSurveillance) {
                        surveillanceDir = internalSurveillanceDir;
                        proximityDir = internalProximityDir;
                    } else {
                        recordingsDir = internalRecordingsDir;
                    }
                    return true;
                }
            }
            initUsbDirectories();
            updateActiveDirectories();
            return true;
        }

        return true;
    }
    
    /**
     * Backwards-compatible alias for {@link #discoverVolumes()} — public
     * callers (refreshSdCard, watchdog) keep working unchanged.
     */
    public void discoverSdCard() {
        discoverVolumes();
    }

    /**
     * Classify a public volume as SD or USB.
     *
     * Three signals, in order of authority:
     *   1. {@code sys.byd.mSdcardUuid} — vendor-set prop carrying the UUID of
     *      the SD card slot's volume. Present on BYD head-units; the most
     *      reliable signal because the firmware itself decides what the
     *      slot is. We compare against the volume's UUID (parts[2] from sm
     *      list-volumes), so this works even when the kernel exposes the
     *      SD reader through a USB/SCSI bridge (which surfaces the device
     *      under major 8 / DEVNAME=sd*, otherwise indistinguishable from
     *      a real USB stick — see Seal 2026-05 firmware).
     *   2. {@code /sys/dev/block/M:N/uevent} DEVNAME — kernel-level. Reliable
     *      when the SD goes through the standard mmc subsystem (major 179),
     *      misleading when SD is bridged through SCSI (sda*). Used as the
     *      first fallback when the BYD prop didn't match.
     *   3. Linux major-number table — last resort.
     *      - 179         → mmcblk* (SD slot)                → SD
     *      - 8, 65..71,  → sd* (SCSI; USB-OTG flash drives) → USB
     *        128..135
     *
     * @return "SD", "USB", or null if classification failed (treat as
     *         "don't claim it for either" — better than misclassifying).
     */
    private String classifyPublicVolume(int major, int minor, String volumeUuid) {
        // Signal 1 (vendor-authoritative, live-only): does this volume's UUID
        // match the BYD SD-slot UUID prop? Most reliable WHEN populated, but
        // BYD only writes the prop while the card is mounted, so this misses
        // during the unmount window between ACC OFF and our remount attempt.
        if (volumeUuid != null && !volumeUuid.isEmpty()) {
            String sdUuid = getSystemProperty("sys.byd.mSdcardUuid");
            if (sdUuid != null && !sdUuid.isEmpty() && sdUuid.equalsIgnoreCase(volumeUuid)) {
                return "SD";
            }
        }

        // Signal 1b (vendor-authoritative, persistent): UUID we previously
        // confirmed as SD via a successful mount. Survives the unmount
        // window where the BYD vendor prop returns empty. The FAT volume
        // serial in `volumeUuid` is stable across remount cycles for the
        // same physical card, so a match here is conclusive.
        if (volumeUuid != null && !volumeUuid.isEmpty()) {
            String learned = readLearnedSdUuid();
            if (!learned.isEmpty() && learned.equalsIgnoreCase(volumeUuid)) {
                return "SD";
            }
        }

        // Signal 2: DEVNAME from the kernel uevent.
        try {
            File ueventFile = new File("/sys/dev/block/" + major + ":" + minor + "/uevent");
            if (ueventFile.exists() && ueventFile.canRead()) {
                BufferedReader r = new BufferedReader(new FileReader(ueventFile));
                String l;
                String devname = null;
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("DEVNAME=")) {
                        devname = l.substring("DEVNAME=".length()).trim();
                        break;
                    }
                }
                r.close();
                if (devname != null) {
                    if (devname.startsWith("mmcblk")) return "SD";
                    if (devname.startsWith("sd"))     return "USB";
                }
            }
        } catch (Exception e) {
            logDebug("classifyPublicVolume read failed for " + major + ":" + minor + ": " + e.getMessage());
        }

        // Signal 3: major-number fallback.
        if (major == 179) return "SD";
        if (major == 8 || (major >= 65 && major <= 71) || (major >= 128 && major <= 135)) return "USB";
        return null;
    }

    /**
     * Probe whether the given mount point is writable from app/daemon UID.
     * Java's File.canWrite() returns false on FUSE-bridged mounts that are
     * actually writable via shell, so we fall back to a touch+rm probe.
     */
    private boolean isMountWritable(String mountPath) {
        File dir = new File(mountPath);
        if (!dir.exists() || !dir.isDirectory()) return false;
        if (dir.canWrite()) return true;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "sh", "-c", "touch " + mountPath + "/.overdrive_probe && rm " + mountPath + "/.overdrive_probe"
            });
            // 2s ceiling — touch/rm against a healthy FUSE mount returns in
            // single-digit ms; anything slower is a stuck filesystem and
            // should be treated as not-writable so we don't latch onto it.
            return waitForBounded(p, 2_000, "isMountWritable(" + mountPath + ")") == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Discover both SD card and USB drive paths in a single pass so they
     * can never alias each other. Replaces the old SD-only discoverSdCard
     * which would happily latch onto a USB stick when both were inserted
     * (the type-blind methods accepted any writable {@code public:} volume).
     *
     * Strategy:
     *   1. {@code sm list-volumes all} — walk every mounted public volume
     *      and classify by underlying block-device major number.
     *   2. BYD UUID prop ({@code sys.byd.mSdcardUuid}) as a tie-breaker
     *      for SD when sm didn't help.
     *   3. /proc/mounts vfat/exfat as final fallback, with the same
     *      major-number classifier applied to the source device.
     *
     * The legacy /storage/ blind scan and SD_CARD_PATHS catch-all are
     * removed — they were the source of the SD/USB confusion.
     */
    public void discoverVolumes() {
        // Stage detection in local vars — only commit to fields on success.
        // Previously we nulled sdCardPath / sdCardAvailable at the top, which
        // meant any transient failure mid-detect (sm timeout, isMountWritable
        // false-positive under FUSE contention, /proc/mounts read error)
        // permanently wiped known-good state until the next watchdog tick.
        // Combined with B5 in the audit, that's the "finds it but can't
        // mount" failure mode the user reported: sm list-volumes correctly
        // returned the volume id, but isMountWritable's `touch+rm` probe
        // timed out under contention so the field was never assigned, and
        // the daemon ran the rest of the session thinking the SD was gone.
        String foundSdPath = null;
        boolean foundSdAvail = false;
        String foundUsbPath = null;
        boolean foundUsbAvail = false;

        // Track every mounted public volume we observed, even ones the
        // classifier couldn't bucket. Used by the single-volume tiebreaker
        // below to promote an ambiguously-typed lone volume to SD when no
        // physical USB stick is present (the v17-and-earlier "any writable
        // public volume → SD" behaviour, scoped to safe conditions).
        java.util.List<String[]> ambiguousMounts = new java.util.ArrayList<>();

        // Method 1: sm list-volumes all
        try {
            Process listProcess = Runtime.getRuntime().exec(new String[]{"sm", "list-volumes", "all"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
            String line;

            while ((line = reader.readLine()) != null) {
                // Parse lines like: "public:8,97 mounted 3661-3064"
                line = line.trim();
                if (!line.startsWith("public:") || !line.contains("mounted")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 3) continue;

                // parts[0] = "public:8,97" → major=8, minor=97
                String[] dev = parts[0].substring("public:".length()).split(",");
                int major, minor;
                try {
                    major = Integer.parseInt(dev[0]);
                    minor = Integer.parseInt(dev[1]);
                } catch (Exception e) {
                    continue;
                }
                String volumeUuid = parts[2];
                String mountPath = "/storage/" + volumeUuid;
                // Use the cheap layered check — the expensive touch+rm probe
                // here was the source of the false-negative cascade. If a
                // public:* volume is in `mounted` state per `sm` AND the
                // path exists with positive StatFs, trust it.
                if (!isPathLikelyMounted(mountPath)) continue;

                String klass = classifyPublicVolume(major, minor, volumeUuid);
                ambiguousMounts.add(new String[]{mountPath, volumeUuid,
                        klass == null ? "" : klass,
                        String.valueOf(major), String.valueOf(minor)});
                if ("SD".equals(klass) && !foundSdAvail) {
                    foundSdPath = mountPath;
                    foundSdAvail = true;
                    learnSdUuid(volumeUuid);
                    logInfo("Found SD card via sm list-volumes (" + major + ":" + minor + "): " + mountPath);
                } else if ("USB".equals(klass) && !foundUsbAvail) {
                    foundUsbPath = mountPath;
                    foundUsbAvail = true;
                    logInfo("Found USB drive via sm list-volumes (" + major + ":" + minor + "): " + mountPath);
                }
                // Keep iterating — both kinds may be present.
            }
            reader.close();
            waitForBounded(listProcess, 2_000, "sm list-volumes (discoverVolumes)");
        } catch (Exception e) {
            logDebug("Could not check sm list-volumes: " + e.getMessage());
        }

        // Method 2: BYD UUID prop is SD-specific. Only use if Method 1 missed SD.
        if (!foundSdAvail) {
            String sdUuid = getSystemProperty("sys.byd.mSdcardUuid");
            if (sdUuid != null && !sdUuid.isEmpty()) {
                String uuidPath = "/storage/" + sdUuid;
                if (isPathLikelyMounted(uuidPath) && !uuidPath.equals(foundUsbPath)) {
                    foundSdPath = uuidPath;
                    foundSdAvail = true;
                    learnSdUuid(sdUuid);
                    logInfo("Found SD card via BYD UUID: " + uuidPath);
                }
            }
        }

        // Method 3: /proc/mounts for vfat/exfat — classify the source device
        // by its base name (mmcblk* → SD, sd* → USB) before claiming it.
        if (!foundSdAvail || !foundUsbAvail) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader("/proc/mounts"));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.contains("vfat") && !line.contains("exfat")) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length < 2) continue;
                    String source = parts[0];      // e.g., /dev/block/mmcblk1p1 or /dev/block/sda1
                    String mountPoint = parts[1];
                    if (mountPoint.startsWith("/mnt/vendor") || mountPoint.startsWith("/firmware") ||
                        mountPoint.equals("/boot") || mountPoint.startsWith("/cache")) {
                        continue;
                    }
                    if (!isPathLikelyMounted(mountPoint)) continue;

                    // Strip /dev/block/ prefix and trailing partition number.
                    String base = source;
                    int slash = base.lastIndexOf('/');
                    if (slash >= 0) base = base.substring(slash + 1);
                    // base now like "mmcblk1p1" or "sda1"
                    String klass = null;
                    if (base.startsWith("mmcblk")) klass = "SD";
                    else if (base.startsWith("sd")) klass = "USB";

                    if ("SD".equals(klass) && !foundSdAvail && !mountPoint.equals(foundUsbPath)) {
                        foundSdPath = mountPoint;
                        foundSdAvail = true;
                        logInfo("Found SD card via /proc/mounts (" + source + "): " + mountPoint);
                    } else if ("USB".equals(klass) && !foundUsbAvail && !mountPoint.equals(foundSdPath)) {
                        foundUsbPath = mountPoint;
                        foundUsbAvail = true;
                        logInfo("Found USB drive via /proc/mounts (" + source + "): " + mountPoint);
                    }
                }
                reader.close();
            } catch (Exception e) {
                logDebug("Could not parse /proc/mounts: " + e.getMessage());
            }
        }

        // Method 4: single-volume tiebreaker (v17 behaviour, scoped).
        // Affected firmwares route the SD slot through a SCSI/USB bridge so
        // the kernel surfaces it as DEVNAME=sd*, major 8 — indistinguishable
        // from a real USB stick to classifyPublicVolume(). On those vehicles
        // the BYD prop is also empty, so Method 2 can't help, and the v17
        // permissive "any writable public volume → SD" code path was lost in
        // the type-discriminating rewrite. Recover for the unambiguous case:
        //   - SD still not found
        //   - exactly one mounted public volume observed by Method 1
        //   - no physical USB device attached (per /sys/bus/usb/devices)
        // Then the lone volume is, by elimination, the SD slot.
        if (!foundSdAvail && ambiguousMounts.size() == 1
                && !isUsbDeviceAttached()) {
            String[] only = ambiguousMounts.get(0);
            String mountPath = only[0];
            String volumeUuid = only[1];
            // If we already classed this as USB above, demote it — we now
            // know it's the only volume and there's no real USB to call it.
            if (mountPath.equals(foundUsbPath)) {
                foundUsbPath = null;
                foundUsbAvail = false;
            }
            foundSdPath = mountPath;
            foundSdAvail = true;
            learnSdUuid(volumeUuid);
            logInfo("Found SD card via single-volume tiebreaker (no USB attached): "
                    + mountPath + " [" + only[3] + ":" + only[4]
                    + ", classifier=" + (only[2].isEmpty() ? "ambiguous" : only[2]) + "]");
        }

        // Commit results atomically. Volumes that disappeared since the last
        // detection do go from non-null → null here; that's correct behavior
        // (the card was actually pulled). What we avoid is the transient-
        // failure case where Method 1 found the card via sm list-volumes
        // but Method 1's writability probe timed out — without staging, that
        // would have nulled state mid-walk and Method 2/3 wouldn't recover
        // because they branch on `!sdCardAvailable` (now `!foundSdAvail`,
        // which preserved the success).
        sdCardPath = foundSdPath;
        sdCardAvailable = foundSdAvail;
        usbPath = foundUsbPath;
        usbAvailable = foundUsbAvail;

        if (!sdCardAvailable) logDebug("No writable SD card found");
        if (!usbAvailable) logDebug("No writable USB drive found");
    }

    /** Cheap mount-liveness check for any path. Same layered logic as
     * {@link #isSdCardLikelyMounted} but for arbitrary mount points.
     * StatFs + canWrite, no shell fork. */
    private boolean isPathLikelyMounted(String path) {
        if (path == null) return false;
        File d = new File(path);
        if (!d.exists() || !d.isDirectory()) return false;
        try {
            android.os.StatFs s = new android.os.StatFs(path);
            if (s.getTotalBytes() <= 0) return false;
        } catch (Throwable t) {
            return false;
        }
        return d.canWrite();
    }
    
    /**
     * Get Android system property via reflection or shell.
     */
    private String getSystemProperty(String key) {
        try {
            // Try reflection first
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = systemProperties.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, "");
        } catch (Exception e) {
            // Fall back to shell
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                reader.close();
                waitForBounded(p, 1_000, "getprop " + key);
                return line != null ? line.trim() : "";
            } catch (Exception e2) {
                return "";
            }
        }
    }

    /**
     * Read the persisted UUID of the volume previously confirmed as SD. See
     * {@link #LEARNED_SD_UUID_FILE} for why this exists. Returns empty string
     * if no learned value (first boot, or file missing).
     */
    private String readLearnedSdUuid() {
        File f = new File(LEARNED_SD_UUID_FILE);
        if (!f.exists() || !f.canRead()) return "";
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Persist the UUID of a volume just confirmed as SD. Idempotent — re-writes
     * are cheap and harmless. We only record on a successful mount, so this
     * value only ever describes a real, working SD card.
     */
    private void learnSdUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) return;
        if (uuid.equalsIgnoreCase(readLearnedSdUuid())) return;  // unchanged, skip write
        try (FileWriter w = new FileWriter(LEARNED_SD_UUID_FILE, false)) {
            w.write(uuid);
            // 0644 — daemon (UID 2000) writes, app process needs to read on
            // the rare path where it walks classifyPublicVolume itself.
            try { new File(LEARNED_SD_UUID_FILE).setReadable(true, false); } catch (Exception ignored) {}
            logInfo("Learned SD UUID for future classification: " + uuid);
        } catch (Exception e) {
            logDebug("learnSdUuid write failed: " + e.getMessage());
        }
    }


    /**
     * Initialize storage directories.
     * IMPORTANT: Sets world-readable permissions so the UI app can access recordings.
     */
    private void initDirectories() {
        // Initialize internal storage directories (always available)
        File internalBaseDir = new File(INTERNAL_BASE_DIR);
        if (!internalBaseDir.exists()) {
            boolean created = internalBaseDir.mkdirs();
            logInfo("Created internal base directory: " + INTERNAL_BASE_DIR + " (success=" + created + ")");
        }
        internalBaseDir.setReadable(true, false);
        internalBaseDir.setExecutable(true, false);
        
        internalRecordingsDir = new File(internalBaseDir, RECORDINGS_SUBDIR);
        if (!internalRecordingsDir.exists()) {
            boolean created = internalRecordingsDir.mkdirs();
            logInfo("Created internal recordings directory: " + internalRecordingsDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalRecordingsDir.setReadable(true, false);
        internalRecordingsDir.setExecutable(true, false);
        
        internalSurveillanceDir = new File(internalBaseDir, SURVEILLANCE_SUBDIR);
        if (!internalSurveillanceDir.exists()) {
            boolean created = internalSurveillanceDir.mkdirs();
            logInfo("Created internal surveillance directory: " + internalSurveillanceDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalSurveillanceDir.setReadable(true, false);
        internalSurveillanceDir.setExecutable(true, false);
        
        internalProximityDir = new File(internalBaseDir, PROXIMITY_SUBDIR);
        if (!internalProximityDir.exists()) {
            boolean created = internalProximityDir.mkdirs();
            logInfo("Created internal proximity directory: " + internalProximityDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalProximityDir.setReadable(true, false);
        internalProximityDir.setExecutable(true, false);
        
        internalTripsDir = new File(internalBaseDir, TRIPS_SUBDIR);
        if (!internalTripsDir.exists()) {
            boolean created = internalTripsDir.mkdirs();
            logInfo("Created internal trips directory: " + internalTripsDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalTripsDir.setReadable(true, false);
        internalTripsDir.setExecutable(true, false);
        
        // Initialize SD card and USB directories if available
        initSdCardDirectories();
        initUsbDirectories();
    }

    /**
     * Initialize SD card directories if SD card is available.
     */
    private void initSdCardDirectories() {
        if (!sdCardAvailable || sdCardPath == null) {
            sdCardRecordingsDir = null;
            sdCardSurveillanceDir = null;
            sdCardProximityDir = null;
            sdCardTripsDir = null;
            return;
        }
        File[] dirs = initVolumeDirectories(sdCardPath, "SD card");
        if (dirs != null) {
            sdCardRecordingsDir   = dirs[0];
            sdCardSurveillanceDir = dirs[1];
            sdCardProximityDir    = dirs[2];
            sdCardTripsDir        = dirs[3];
        }
    }

    /**
     * Initialize USB directories if USB drive is available.
     */
    private void initUsbDirectories() {
        if (!usbAvailable || usbPath == null) {
            usbRecordingsDir = null;
            usbSurveillanceDir = null;
            usbProximityDir = null;
            usbTripsDir = null;
            return;
        }
        File[] dirs = initVolumeDirectories(usbPath, "USB");
        if (dirs != null) {
            usbRecordingsDir   = dirs[0];
            usbSurveillanceDir = dirs[1];
            usbProximityDir    = dirs[2];
            usbTripsDir        = dirs[3];
        }
    }

    /**
     * Build {@code <volumePath>/Overdrive/{recordings,surveillance,proximity,trips}}
     * with world rwx so the app UID can read them. Returns the four dirs in
     * order, or null if the base couldn't be created.
     */
    private File[] initVolumeDirectories(String volumePath, String label) {
        File base = new File(volumePath, "Overdrive");
        boolean baseCreated = base.mkdirs();
        if (!base.exists()) {
            logError("Failed to create " + label + " base directory: " + base.getAbsolutePath());
            return null;
        }
        if (baseCreated) {
            logInfo("Created " + label + " base directory: " + base.getAbsolutePath());
        }
        base.setReadable(true, false);
        base.setWritable(true, false);
        base.setExecutable(true, false);

        File rec = makeChildDir(base, RECORDINGS_SUBDIR, label + " recordings");
        File surv = makeChildDir(base, SURVEILLANCE_SUBDIR, label + " surveillance");
        File prox = makeChildDir(base, PROXIMITY_SUBDIR, label + " proximity");
        File trips = makeChildDir(base, TRIPS_SUBDIR, label + " trips");

        if (surv != null && surv.exists() && !surv.canWrite()) {
            logError(label + " surveillance directory exists but is not writable: " + surv.getAbsolutePath());
        }
        return new File[]{rec, surv, prox, trips};
    }

    private File makeChildDir(File parent, String name, String label) {
        File dir = new File(parent, name);
        boolean created = dir.mkdirs();
        if (!dir.exists()) {
            logError("Failed to create " + label + " directory: " + dir.getAbsolutePath());
            return dir;
        }
        if (created) {
            logInfo("Created " + label + " directory: " + dir.getAbsolutePath());
        }
        dir.setReadable(true, false);
        dir.setWritable(true, false);
        dir.setExecutable(true, false);
        return dir;
    }
    
    /**
     * Resolve the active directory for one (category, type) pair, falling
     * back to internal when the requested external volume isn't ready.
     * Logs the fallback only when we actually downgraded (else the boot path
     * spams "fell back" lines for users who never selected SD/USB).
     */
    private File resolveActive(StorageType type,
                               File internalDir, File sdDir, File usbDir,
                               String label) {
        if (type == StorageType.SD_CARD) {
            if (sdCardAvailable && sdDir != null) return sdDir;
            logWarn("SD card not available for " + label + ", falling back to internal storage");
            return internalDir;
        }
        if (type == StorageType.USB) {
            if (usbAvailable && usbDir != null) return usbDir;
            logWarn("USB not available for " + label + ", falling back to internal storage");
            return internalDir;
        }
        return internalDir;
    }

    /**
     * Update active directories based on storage type selection.
     * Falls back to internal storage if the selected external volume is not
     * available. Per-category recording-active guard prevents files from
     * being split across volumes when the user changes storage mid-recording.
     */
    private void updateActiveDirectories() {
        // Recordings directory
        if (recordingActive.get()) {
            logDebug("Recording active - skipping recordings directory update");
        } else {
            recordingsDir = resolveActive(recordingsStorageType,
                internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir, "recordings");
            logInfo("Recordings using " + recordingsStorageType + ": " + recordingsDir.getAbsolutePath());
        }

        // Surveillance directory
        if (surveillanceActive.get()) {
            logDebug("Surveillance active - skipping surveillance directory update");
        } else {
            surveillanceDir = resolveActive(surveillanceStorageType,
                internalSurveillanceDir, sdCardSurveillanceDir, usbSurveillanceDir, "surveillance");
            logInfo("Surveillance using " + surveillanceStorageType + ": " + surveillanceDir.getAbsolutePath());
        }

        // Proximity always uses same storage as surveillance
        if (!surveillanceActive.get()) {
            proximityDir = resolveActive(surveillanceStorageType,
                internalProximityDir, sdCardProximityDir, usbProximityDir, "proximity");
        }

        // Trips directory — trip telemetry files are small, no active guard
        tripsDir = resolveActive(tripsStorageType,
            internalTripsDir, sdCardTripsDir, usbTripsDir, "trips");
        logInfo("Trips using " + tripsStorageType + ": " + tripsDir.getAbsolutePath());
    }
    
    /**
     * Load storage limits and storage type from config file.
     */
    private void loadConfig() {
        try {
            File configFile = new File(CONFIG_FILE);
            if (configFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(configFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject config = new JSONObject(sb.toString());
                JSONObject storage = config.optJSONObject("storage");
                if (storage != null) {
                    recordingsLimitMb = storage.optLong("recordingsLimitMb", DEFAULT_RECORDINGS_LIMIT_MB);
                    surveillanceLimitMb = storage.optLong("surveillanceLimitMb", DEFAULT_SURVEILLANCE_LIMIT_MB);
                    proximityLimitMb = storage.optLong("proximityLimitMb", DEFAULT_PROXIMITY_LIMIT_MB);
                    tripsLimitMb = storage.optLong("tripsLimitMb", DEFAULT_TRIPS_LIMIT_MB);
                    
                    // Load storage type selection
                    recordingsStorageType   = parseStorageType(storage.optString("recordingsStorageType", "INTERNAL"));
                    surveillanceStorageType = parseStorageType(storage.optString("surveillanceStorageType", "INTERNAL"));
                    tripsStorageType        = parseStorageType(storage.optString("tripsStorageType", "INTERNAL"));

                    // Clamp to dynamic max — limit may have been persisted against
                    // a different volume (e.g., user swapped a 128GB SD for a 32GB
                    // one), so re-check against the current effective ceiling.
                    recordingsLimitMb   = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(recordingsStorageType),   recordingsLimitMb));
                    surveillanceLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(surveillanceStorageType), surveillanceLimitMb));
                    proximityLimitMb    = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(surveillanceStorageType), proximityLimitMb));
                    tripsLimitMb        = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(tripsStorageType),        tripsLimitMb));
                    
                    logInfo("Loaded storage config: recordings=" + recordingsLimitMb + "MB (" + recordingsStorageType + 
                        "), surveillance=" + surveillanceLimitMb + "MB (" + surveillanceStorageType + 
                        "), trips=" + tripsLimitMb + "MB (" + tripsStorageType + ")");
                }
            }
        } catch (Exception e) {
            logWarn("Could not load storage config: " + e.getMessage());
        }
    }

    /**
     * Save storage limits and storage type to config file.
     *
     * <p>Synchronized: the HTTP layer uses a 32-thread pool, so
     * concurrent setters (setRecordingsLimitMb, setSurveillanceStorageType,
     * etc.) can race the read-modify-write cycle below. Without this lock
     * two writers could each read the file, mutate disjoint fields in their
     * own copy, and the second writer's full-file write would clobber the
     * first writer's changes.
     */
    public synchronized void saveConfig() {
        try {
            File configFile = new File(CONFIG_FILE);
            JSONObject config;
            
            if (configFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(configFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                config = new JSONObject(sb.toString());
            } else {
                config = new JSONObject();
                config.put("version", 1);
            }
            
            JSONObject storage = config.optJSONObject("storage");
            if (storage == null) {
                storage = new JSONObject();
            }
            storage.put("recordingsLimitMb", recordingsLimitMb);
            storage.put("surveillanceLimitMb", surveillanceLimitMb);
            storage.put("proximityLimitMb", proximityLimitMb);
            storage.put("tripsLimitMb", tripsLimitMb);
            storage.put("recordingsStorageType", recordingsStorageType.name());
            storage.put("surveillanceStorageType", surveillanceStorageType.name());
            storage.put("tripsStorageType", tripsStorageType.name());
            config.put("storage", storage);
            config.put("lastModified", System.currentTimeMillis());
            
            FileWriter writer = new FileWriter(configFile);
            writer.write(config.toString(2));
            writer.close();

            configFile.setReadable(true, false);
            configFile.setWritable(true, false);

            // UnifiedConfigManager has its own in-memory cache of this same
            // file. Without this invalidation, the next updateSection() call
            // would merge into a stale cached config (still holding the OLD
            // storage section) and write it back, silently reverting the
            // SD_CARD/USB selection the user just made.
            try {
                com.overdrive.app.config.UnifiedConfigManager.forceReload();
            } catch (Throwable t) {
                logWarn("UnifiedConfigManager.forceReload() failed: " + t.getMessage());
            }

            logInfo("Saved storage config: recordings=" + recordingsLimitMb + "MB (" + recordingsStorageType +
                "), surveillance=" + surveillanceLimitMb + "MB (" + surveillanceStorageType +
                "), trips=" + tripsLimitMb + "MB (" + tripsStorageType + ")");
        } catch (Exception e) {
            logError("Could not save storage config: " + e.getMessage());
        }
    }
    
    // ==================== Directory Getters ====================
    
    public File getRecordingsDir() {
        return recordingsDir;
    }
    
    public File getSurveillanceDir() {
        return surveillanceDir;
    }
    
    public File getProximityDir() {
        return proximityDir;
    }
    
    public File getTripsDir() {
        return tripsDir;
    }
    
    public String getRecordingsPath() {
        return recordingsDir.getAbsolutePath();
    }
    
    public String getSurveillancePath() {
        return surveillanceDir.getAbsolutePath();
    }
    
    public String getProximityPath() {
        return proximityDir.getAbsolutePath();
    }
    
    public String getTripsPath() {
        return tripsDir.getAbsolutePath();
    }
    
    /**
     * Fix permissions on all storage directories and files.
     * Call this from daemon startup to ensure UI app can read recordings.
     * Note: chmod doesn't work on FUSE - rely on MediaScanner broadcast for cross-UID visibility.
     */
    public void fixAllPermissions() {
        // Fix directory permissions synchronously (fast, no I/O contention)
        File baseDir = new File(INTERNAL_BASE_DIR);
        if (baseDir.exists()) {
            baseDir.setReadable(true, false);
            baseDir.setExecutable(true, false);
        }
        fixDirectoryPermissions(recordingsDir);
        fixDirectoryPermissions(surveillanceDir);
        fixDirectoryPermissions(proximityDir);
        fixDirectoryPermissions(tripsDir);
        
        // Make all existing files world-readable (chmod 666).
        // Required for: (1) UI app (different UID) to read files directly,
        // (2) FUSE layer on BYD Android to allow File.listFiles() to see them.
        // This is fast (no shell processes) — just Java File.setReadable() calls.
        makeFilesReadable(recordingsDir);
        makeFilesReadable(surveillanceDir);
        makeFilesReadable(proximityDir);
        makeFilesReadable(tripsDir);
        
        // SOTA: Incremental MediaScanner broadcast — only broadcast files created
        // since the last successful broadcast. Uses a marker file to track the
        // timestamp of the last full scan. On first run (no marker), broadcasts
        // everything once, then subsequent startups only broadcast new files.
        //
        // Additionally, broadcasts are throttled (50ms between each shell exec)
        // to avoid saturating the I/O bus during camera pipeline startup.
        // The old approach spawned 2 shell processes per file × hundreds of files
        // = hundreds of concurrent process forks competing with the GPU pipeline.
        new Thread(() -> {
            long lastScanTimestamp = loadLastBroadcastTimestamp();
            long scanStartTime = System.currentTimeMillis();
            
            int count = 0;
            count += broadcastFilesSince(recordingsDir, lastScanTimestamp);
            count += broadcastFilesSince(surveillanceDir, lastScanTimestamp);
            count += broadcastFilesSince(proximityDir, lastScanTimestamp);
            
            saveLastBroadcastTimestamp(scanStartTime);
            
            if (count > 0) {
                logInfo("MediaScanner broadcast complete: " + count + " new files indexed");
            } else {
                logDebug("MediaScanner: no new files to broadcast since last scan");
            }
        }, "MediaScannerBroadcast").start();
    }
    
    /** Marker file that stores the epoch millis of the last successful broadcast scan. */
    private static final String BROADCAST_MARKER_FILE = "/data/local/tmp/overdrive_last_mediascan";
    
    /** Throttle delay between individual file broadcasts (ms). */
    private static final long BROADCAST_THROTTLE_MS = 50;
    
    /**
     * Load the timestamp of the last successful MediaScanner broadcast.
     * Returns 0 if no marker exists (first run — will broadcast everything).
     */
    private long loadLastBroadcastTimestamp() {
        try {
            File marker = new File(BROADCAST_MARKER_FILE);
            if (marker.exists()) {
                String content = new java.util.Scanner(marker).useDelimiter("\\A").next().trim();
                return Long.parseLong(content);
            }
        } catch (Exception e) {
            logDebug("No broadcast marker found, will do full scan");
        }
        return 0;
    }
    
    /**
     * Save the timestamp of the current broadcast scan.
     */
    private void saveLastBroadcastTimestamp(long timestamp) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(BROADCAST_MARKER_FILE);
            fw.write(String.valueOf(timestamp));
            fw.close();
        } catch (Exception e) {
            logWarn("Failed to save broadcast marker: " + e.getMessage());
        }
    }
    
    /**
     * Broadcast only files modified after the given timestamp.
     * Throttled to avoid I/O contention with the GPU pipeline.
     * @return number of files broadcast
     */
    private int broadcastFilesSince(File dir, long sinceTimestamp) {
        if (dir == null || !dir.exists()) return 0;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null || files.length == 0) return 0;
        
        int count = 0;
        for (File f : files) {
            if (f.lastModified() > sinceTimestamp) {
                broadcastFile(f);
                count++;
                
                // Throttle: yield between broadcasts to avoid saturating I/O
                if (count % 5 == 0) {
                    try { Thread.sleep(BROADCAST_THROTTLE_MS); } catch (InterruptedException e) { break; }
                }
            }
        }
        return count;
    }
    
    // ==================== Limit Getters/Setters ====================
    
    public long getRecordingsLimitMb() {
        return recordingsLimitMb;
    }
    
    public long getSurveillanceLimitMb() {
        return surveillanceLimitMb;
    }
    
    public long getProximityLimitMb() {
        return proximityLimitMb;
    }
    
    public long getTripsLimitMb() {
        return tripsLimitMb;
    }
    
    public void setRecordingsLimitMb(long limitMb) {
        recordingsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(recordingsStorageType), limitMb));
        saveConfig();
    }

    public void setSurveillanceLimitMb(long limitMb) {
        surveillanceLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(surveillanceStorageType), limitMb));
        saveConfig();
    }

    public void setProximityLimitMb(long limitMb) {
        proximityLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(surveillanceStorageType), limitMb));
        saveConfig();
    }

    public void setTripsLimitMb(long limitMb) {
        tripsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(tripsStorageType), limitMb));
        saveConfig();
    }
    
    // ==================== Storage Type Getters/Setters ====================
    
    public StorageType getRecordingsStorageType() {
        return recordingsStorageType;
    }
    
    public StorageType getSurveillanceStorageType() {
        return surveillanceStorageType;
    }
    
    public StorageType getTripsStorageType() {
        return tripsStorageType;
    }
    
    /**
     * Set recordings storage type (INTERNAL or SD_CARD).
     * @param type The storage type to use
     * @return true if successfully changed, false if SD card not available
     */
    public boolean setRecordingsStorageType(StorageType type) {
        if (!ensureExternalAvailable(type, "recordings")) return false;

        recordingsStorageType = type;
        // Re-clamp the persisted limit against the new volume's effective max
        // (e.g., user switches from SD to USB, USB is smaller). Limit may
        // need to shrink before updateActiveDirectories runs cleanup.
        recordingsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(type), recordingsLimitMb));
        updateActiveDirectories();
        saveConfig();
        logInfo("Recordings storage type set to: " + type);

        if (type == StorageType.SD_CARD) {
            autoEnableCdrCleanup();
        }
        return true;
    }
    
    /**
     * Set surveillance storage type (INTERNAL or SD_CARD).
     * @param type The storage type to use
     * @return true if successfully changed, false if SD card not available
     */
    public boolean setSurveillanceStorageType(StorageType type) {
        if (!ensureExternalAvailable(type, "surveillance")) return false;

        surveillanceStorageType = type;
        surveillanceLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(type), surveillanceLimitMb));
        proximityLimitMb    = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(type), proximityLimitMb));
        updateActiveDirectories();
        saveConfig();
        logInfo("Surveillance storage type set to: " + type);

        if (type == StorageType.SD_CARD) {
            autoEnableCdrCleanup();
        }
        return true;
    }
    
    /**
     * Set trips storage type (INTERNAL or SD_CARD).
     * Does NOT call autoEnableCdrCleanup() — trip files are small and don't compete with BYD dashcam space.
     * @param type The storage type to use
     * @return true if successfully changed, false if SD card not available
     */
    public boolean setTripsStorageType(StorageType type) {
        if (!ensureExternalAvailable(type, "trips")) return false;

        tripsStorageType = type;
        tripsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(type), tripsLimitMb));
        updateActiveDirectories();
        saveConfig();
        logInfo("Trips storage type set to: " + type);
        return true;
    }

    /**
     * Helper: ensure the requested external volume is available before we
     * accept a storage-type change. INTERNAL is always OK. SD/USB get a
     * mount attempt; refusing the change is preferable to silently writing
     * to internal under a label that says "SD card".
     */
    private boolean ensureExternalAvailable(StorageType type, String label) {
        if (type == StorageType.SD_CARD) {
            if (sdCardAvailable) return true;
            logInfo("SD card not available, attempting to mount for " + label + "...");
            if (!ensureSdCardMounted(true)) {
                logWarn("Cannot set " + label + " to SD card - mount failed");
                return false;
            }
            return true;
        }
        if (type == StorageType.USB) {
            if (usbAvailable) return true;
            logInfo("USB not available, attempting to mount for " + label + "...");
            if (!ensureUsbMounted(true)) {
                logWarn("Cannot set " + label + " to USB - mount failed");
                return false;
            }
            return true;
        }
        return true;  // INTERNAL
    }
    
    /**
     * SOTA: Auto-enable CDR (BYD dashcam) cleanup when Overdrive uses SD card.
     * This ensures Overdrive always has space by cleaning up old dashcam files.
     */
    private void autoEnableCdrCleanup() {
        try {
            ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
            if (!cleaner.isEnabled() && cleaner.isSdCardAvailable()) {
                // Calculate recommended reserved space based on our limits
                long totalNeeded = 0;
                if (recordingsStorageType == StorageType.SD_CARD) {
                    totalNeeded += recordingsLimitMb;
                }
                if (surveillanceStorageType == StorageType.SD_CARD) {
                    totalNeeded += surveillanceLimitMb;
                }
                // Add 20% buffer
                long reservedMb = Math.max(2048, (long)(totalNeeded * 1.2));
                
                cleaner.setReservedSpaceMb(reservedMb);
                cleaner.setEnabled(true);
                logInfo("Auto-enabled CDR cleanup with " + reservedMb + "MB reserved for Overdrive");
            }
        } catch (Exception e) {
            logWarn("Could not auto-enable CDR cleanup: " + e.getMessage());
        }
    }
    
    // ==================== Volume Info ====================

    public boolean isSdCardAvailable() {
        return sdCardAvailable;
    }

    public String getSdCardPath() {
        return sdCardPath;
    }

    public boolean isUsbAvailable() {
        return usbAvailable;
    }

    public String getUsbPath() {
        return usbPath;
    }

    /**
     * Re-detect both SD and USB. Public alias mostly used by polling
     * watchdogs / API handlers that want to refresh state on demand.
     */
    public void refreshUsb() {
        discoverVolumes();
        initSdCardDirectories();
        initUsbDirectories();
        updateActiveDirectories();
        logInfo("Volume refresh complete. SD=" + sdCardAvailable + ", USB=" + usbAvailable);
    }
    
    // ==================== All Storage Locations (for scanning) ====================
    
    /**
     * Get ALL directories that may contain recordings of a given type.
     * Returns the active (configured) directory first, then any alternate locations
     * where files may exist (e.g., internal when SD card is active, or vice versa).
     * 
     * This is the single source of truth for multi-location scanning.
     * Callers should iterate all returned directories to find all files.
     */
    public List<File> getAllRecordingsDirs() {
        return getAllDirsForType(recordingsDir, internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir);
    }

    public List<File> getAllSurveillanceDirs() {
        return getAllDirsForType(surveillanceDir, internalSurveillanceDir, sdCardSurveillanceDir, usbSurveillanceDir);
    }

    public List<File> getAllProximityDirs() {
        return getAllDirsForType(proximityDir, internalProximityDir, sdCardProximityDir, usbProximityDir);
    }

    public List<File> getAllTripsDirs() {
        return getAllDirsForType(tripsDir, internalTripsDir, sdCardTripsDir, usbTripsDir);
    }

    /**
     * Same as {@link #getAllSurveillanceDirs()} et al, but additionally
     * includes legacy app-files locations from older app versions where
     * stale media may still be living and counting toward the limit.
     *
     * Used by both the size accounting and the cleanup reaper so the two
     * agree about what "the surveillance pool" actually is — otherwise
     * the UI can show 800 MB used against a 500 MB limit while cleanup
     * (which only saw the active dir) thinks everything is fine.
     *
     * Includes the flat legacy base ({@link #LEGACY_APP_FILES_DIR}) when a
     * non-null filename prefix is supplied via {@link #namePrefixForCategory},
     * because the flat base is shared across categories and only files
     * matching the category's prefix should be touched.
     */
    private List<File> getReapableDirs(String category) {
        List<File> dirs;
        String legacyPath = null;
        boolean includeFlatBase = false;
        switch (category) {
            case "recordings":
                dirs = new ArrayList<>(getAllRecordingsDirs());
                legacyPath = LEGACY_APP_FILES_DIR + "/recordings";
                includeFlatBase = true;  // some old installs wrote cam_* into <base>
                break;
            case "surveillance":
                dirs = new ArrayList<>(getAllSurveillanceDirs());
                legacyPath = LEGACY_SURVEILLANCE_DIR;
                break;
            case "proximity":
                dirs = new ArrayList<>(getAllProximityDirs());
                legacyPath = LEGACY_APP_FILES_DIR + "/proximity_events";
                break;
            case "trips":
                dirs = new ArrayList<>(getAllTripsDirs());
                break;
            default:
                return new ArrayList<>();
        }
        if (legacyPath != null) {
            addDirIfMissing(dirs, new File(legacyPath));
        }
        if (includeFlatBase) {
            addDirIfMissing(dirs, new File(LEGACY_APP_FILES_DIR));
        }
        return dirs;
    }

    private static void addDirIfMissing(List<File> dirs, File candidate) {
        if (candidate == null || !candidate.exists() || !candidate.isDirectory()) return;
        String path = candidate.getAbsolutePath();
        for (File d : dirs) {
            if (d != null && d.getAbsolutePath().equals(path)) return;
        }
        dirs.add(candidate);
    }

    /**
     * Filename prefix that identifies media belonging to {@code category}.
     * When non-null, callers that scan multi-category directories (the
     * flat legacy base) should restrict to filenames starting with this
     * prefix so they don't reap a sibling category's files. Returns null
     * for categories whose dirs are all category-dedicated.
     */
    private static String namePrefixForCategory(String category) {
        switch (category) {
            case "recordings":  return "cam";        // cam_*, cam2_*, …
            case "surveillance": return "event_";
            case "proximity":   return "proximity_";
            default: return null;
        }
    }

    /**
     * Auxiliary filename prefixes that don't share a stem with the anchor but
     * still belong to {@code category} and must be reaped to keep the limit
     * honest. Used by the orphan-sidecar pass — these files are matched as
     * sidecars whose anchor stem is parsed by stripping the auxiliary prefix.
     *
     * <p>Surveillance: per-actor thumbnails are written as
     * {@code thumb_event_<base>_a<id>_<rel>.jpg} (SurveillanceEngineGpu:4823).
     * They don't start with {@code event_}, so the standard prefix gate
     * filters them out — without this auxiliary entry they accumulate
     * untracked.
     */
    private static String[] auxiliaryPrefixesForCategory(String category) {
        switch (category) {
            // Per-actor JPGs are named `thumb_<anchorStem>_a<id>_<rel>.jpg`,
            // where anchorStem already includes the `event_` prefix
            // (SurveillanceEngineGpu:4815-4824 derives tmpBase from the
            // segment basename minus ".mp4"). The aux prefix must be just
            // `thumb_` — anything longer would double-count `event_` and
            // miss every actual file.
            case "surveillance": return new String[]{"thumb_"};
            default:             return new String[]{};
        }
    }

    /**
     * Returns true if {@code name} matches the category's primary prefix or
     * any auxiliary prefix. Centralizes the prefix gate so size accounting,
     * the anchor reaper, and the orphan-sidecar pass agree on which files
     * belong to a category.
     */
    private static boolean nameMatchesCategoryPrefix(String name, String primaryPrefix,
                                                     String[] auxPrefixes) {
        if (primaryPrefix != null && name.startsWith(primaryPrefix)) return true;
        for (String aux : auxPrefixes) {
            if (name.startsWith(aux)) return true;
        }
        return false;
    }

    /**
     * Sum primary + sidecar files across the given dirs, deduplicating by
     * filename (so a clip mirrored on internal + SD-card isn't counted twice).
     * Must match what {@link #ensureSpace} actually frees, otherwise the UI
     * reports usage past the limit while cleanup believes it's fine.
     *
     * @param category   Category key — recordings/surveillance/proximity/trips.
     * @param namePrefix If non-null, only files whose name starts with
     *                   this prefix are summed. Used when the dir set
     *                   includes the flat legacy base shared across
     *                   categories.
     */
    private long getDirectoriesTotalSize(String category, List<File> dirs, String namePrefix) {
        String primaryExt = primaryExtensionForCategory(category);
        String[] sidecarExts = sidecarExtensionsForCategory(category);
        String[] partialExts = partialExtensionsForCategory(category);
        String[] auxPrefixes = auxiliaryPrefixesForCategory(category);

        long size = 0;
        Set<String> seen = new HashSet<>();
        for (File dir : dirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) {
                files = listFilesViaShell(dir);
            }
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (namePrefix != null
                        && !nameMatchesCategoryPrefix(name, namePrefix, auxPrefixes)) continue;

                boolean isPrimary = name.endsWith(primaryExt);
                boolean isSidecar = false;
                boolean isPartial = false;
                if (!isPrimary) {
                    for (String ext : sidecarExts) {
                        if (name.endsWith(ext)) { isSidecar = true; break; }
                    }
                }
                if (!isPrimary && !isSidecar) {
                    for (String ext : partialExts) {
                        if (name.endsWith(ext)) { isPartial = true; break; }
                    }
                }
                if (!isPrimary && !isSidecar && !isPartial) continue;
                if (!seen.add(name)) continue;
                size += f.length();
            }
        }
        return size;
    }
    
    /**
     * Build a deduplicated list of directories: active first, then alternates.
     * Skips null entries and directories that match the active one.
     */
    private List<File> getAllDirsForType(File activeDir, File internalDir, File sdCardDir, File usbDir) {
        List<File> dirs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (activeDir != null) {
            dirs.add(activeDir);
            seen.add(activeDir.getAbsolutePath());
        }
        if (internalDir != null && !seen.contains(internalDir.getAbsolutePath())) {
            dirs.add(internalDir);
            seen.add(internalDir.getAbsolutePath());
        }
        if (sdCardDir != null && !seen.contains(sdCardDir.getAbsolutePath())) {
            dirs.add(sdCardDir);
            seen.add(sdCardDir.getAbsolutePath());
        }
        if (usbDir != null && !seen.contains(usbDir.getAbsolutePath())) {
            dirs.add(usbDir);
            seen.add(usbDir.getAbsolutePath());
        }
        return dirs;
    }
    
    /**
     * Get available space on SD card in bytes.
     */
    public long getSdCardFreeSpace() {
        if (sdCardPath == null) return 0;
        try {
            // Verify path exists before using StatFs
            File sdDir = new File(sdCardPath);
            if (!sdDir.exists() || !sdDir.isDirectory()) {
                logDebug("SD card path not accessible: " + sdCardPath);
                return 0;
            }
            StatFs stat = new StatFs(sdCardPath);
            return stat.getAvailableBytes();
        } catch (Exception e) {
            logWarn("Could not get SD card free space: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get total space on SD card in bytes.
     */
    public long getSdCardTotalSpace() {
        if (sdCardPath == null) return 0;
        try {
            // Verify path exists before using StatFs
            File sdDir = new File(sdCardPath);
            if (!sdDir.exists() || !sdDir.isDirectory()) {
                return 0;
            }
            StatFs stat = new StatFs(sdCardPath);
            return stat.getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Get available space on internal storage in bytes.
     */
    public long getInternalFreeSpace() {
        try {
            StatFs stat = new StatFs(INTERNAL_BASE_DIR);
            return stat.getAvailableBytes();
        } catch (Exception e) {
            logWarn("Could not get internal free space: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get total space on internal storage in bytes.
     */
    public long getInternalTotalSpace() {
        try {
            StatFs stat = new StatFs(INTERNAL_BASE_DIR);
            return stat.getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Refresh SD card AND USB detection and update directories.
     * Call this when either volume may have been inserted/removed.
     * (Kept under the historical name for callers that still reference it.)
     */
    public void refreshSdCard() {
        discoverVolumes();
        initSdCardDirectories();
        initUsbDirectories();
        updateActiveDirectories();
        logInfo("Volume refresh complete. SD=" + sdCardAvailable + ", USB=" + usbAvailable);
    }

    /**
     * Get available space on USB drive in bytes.
     */
    public long getUsbFreeSpace() {
        if (usbPath == null) return 0;
        try {
            File d = new File(usbPath);
            if (!d.exists() || !d.isDirectory()) return 0;
            StatFs stat = new StatFs(usbPath);
            return stat.getAvailableBytes();
        } catch (Exception e) {
            logWarn("Could not get USB free space: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Get total space on USB drive in bytes.
     */
    public long getUsbTotalSpace() {
        if (usbPath == null) return 0;
        try {
            File d = new File(usbPath);
            if (!d.exists() || !d.isDirectory()) return 0;
            StatFs stat = new StatFs(usbPath);
            return stat.getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Effective max-limit ceiling in MB for the requested storage type.
     *
     * Pulled live from StatFs each call so card swaps and capacity changes
     * reflect immediately in the slider — but capped per-category at
     * PER_CATEGORY_SHARE of the volume so the four categories sharing a
     * single FS can't overcommit it 4x.
     *
     * When the requested SD/USB volume is unmounted, falls back to the
     * INTERNAL ceiling rather than the absurd MAX_LIMIT_MB_FALLBACK
     * sentinel — the runtime fall-back path lands writes on internal,
     * so capping at internal's true total stops the user from persisting
     * a 100GB limit against a missing 32GB stick. INTERNAL itself
     * returning <=0 (StatFs literally unreadable) keeps the sentinel.
     */
    public long getEffectiveMaxLimitMb(StorageType type) {
        long totalBytes;
        switch (type) {
            case SD_CARD: totalBytes = sdCardAvailable ? getSdCardTotalSpace() : 0; break;
            case USB:     totalBytes = usbAvailable    ? getUsbTotalSpace()    : 0; break;
            case INTERNAL:
            default:      totalBytes = getInternalTotalSpace(); break;
        }
        if (totalBytes <= 0) {
            if (type == StorageType.INTERNAL) return MAX_LIMIT_MB_FALLBACK;
            // Unmounted SD/USB: clamp to internal volume's ceiling so a save
            // while the volume is missing can't persist a value larger than
            // the fallback target can ever hold.
            long internalBytes = getInternalTotalSpace();
            if (internalBytes <= 0) return MAX_LIMIT_MB_FALLBACK;
            long internalUsableMb = (internalBytes / 1024L / 1024L) - VOLUME_HEADROOM_MB;
            if (internalUsableMb <= 0) return MIN_LIMIT_MB;
            return Math.max(MIN_LIMIT_MB, (long)(internalUsableMb * PER_CATEGORY_SHARE));
        }

        long usableMb = (totalBytes / 1024L / 1024L) - VOLUME_HEADROOM_MB;
        if (usableMb <= 0) return MIN_LIMIT_MB;
        long perCategoryMb = (long)(usableMb * PER_CATEGORY_SHARE);
        return Math.max(MIN_LIMIT_MB, perCategoryMb);
    }

    /**
     * Backwards-compatible: returns the dynamic max for the given type.
     * Old callers that passed a {@link StorageType} keep working.
     */
    public long getMaxLimitMb(StorageType type) {
        return getEffectiveMaxLimitMb(type);
    }
    
    // ==================== Storage Stats ====================
    
    /**
     * Get current size of recordings across all locations (active dir, the
     * inactive internal/SD-card mirror, and legacy app-files paths).
     *
     * Must match the dirs the cleanup actually reaps — otherwise the UI can
     * report 800 MB used while the limit is 500 MB and cleanup never fires.
     */
    public long getRecordingsSize() {
        return getDirectoriesTotalSize("recordings", getReapableDirs("recordings"), namePrefixForCategory("recordings"));
    }

    /**
     * Get current size of surveillance across all locations (active dir, the
     * inactive internal/SD-card mirror, and the legacy sentry_events path).
     */
    public long getSurveillanceSize() {
        return getDirectoriesTotalSize("surveillance", getReapableDirs("surveillance"), namePrefixForCategory("surveillance"));
    }

    /**
     * Get current size of proximity across all locations (active dir, the
     * inactive internal/SD-card mirror, and the legacy proximity_events path).
     */
    public long getProximitySize() {
        return getDirectoriesTotalSize("proximity", getReapableDirs("proximity"), namePrefixForCategory("proximity"));
    }
    
    /**
     * Get recordings file count across all locations (active + inactive
     * mirror + legacy). Matches the size accounting so per-file averages
     * line up with reported totals.
     */
    public int getRecordingsCount() {
        return getFileCountAcross("recordings", getReapableDirs("recordings"), namePrefixForCategory("recordings"));
    }

    /**
     * Get surveillance events file count across all locations.
     */
    public int getSurveillanceCount() {
        return getFileCountAcross("surveillance", getReapableDirs("surveillance"), namePrefixForCategory("surveillance"));
    }

    /**
     * Get proximity events file count across all locations.
     */
    public int getProximityCount() {
        return getFileCountAcross("proximity", getReapableDirs("proximity"), namePrefixForCategory("proximity"));
    }

    private int getFileCountAcross(String category, List<File> dirs, String namePrefix) {
        String primaryExt = primaryExtensionForCategory(category);
        int total = 0;
        Set<String> seen = new HashSet<>();
        for (File dir : dirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(primaryExt));
            if (files == null) {
                files = listFilesByExt(dir, primaryExt);
            }
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (namePrefix != null && !name.startsWith(namePrefix)) continue;
                if (seen.add(name)) {
                    total++;
                }
            }
        }
        return total;
    }
    
    /**
     * Get current size of trips directory in bytes.
     */
    public long getTripsSize() {
        return getDirectoriesTotalSize("trips", getReapableDirs("trips"), namePrefixForCategory("trips"));
    }

    /**
     * Get trips file count.
     */
    public int getTripsCount() {
        return getFileCountAcross("trips", getReapableDirs("trips"), namePrefixForCategory("trips"));
    }

    /**
     * SOTA: List files via shell command when direct access fails.
     * This handles the case where UI app owns the directory but daemon needs to list files.
     * Returns every file in the directory regardless of extension.
     */
    private File[] listFilesViaShell(File dir) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ls", dir.getAbsolutePath()});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));

            java.util.List<File> files = new java.util.ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                files.add(new File(dir, line));
            }
            reader.close();
            p.waitFor();

            logDebug("listFilesViaShell: found " + files.size() + " files in " + dir.getName());
            return files.toArray(new File[0]);
        } catch (Exception e) {
            logWarn("listFilesViaShell failed: " + e.getMessage());
            return new File[0];
        }
    }

    /**
     * Same as {@link #listFilesViaShell(File)} but filtered to a specific
     * extension. Used by the cleanup path so the anchor-collection step
     * picks up the right primary file type for each category.
     */
    private File[] listFilesByExt(File dir, String ext) {
        File[] all = listFilesViaShell(dir);
        if (all == null || all.length == 0) return all;
        java.util.List<File> matched = new java.util.ArrayList<>();
        for (File f : all) {
            if (f.getName().endsWith(ext)) matched.add(f);
        }
        return matched.toArray(new File[0]);
    }
    
    // ==================== Cleanup Logic ====================
    
    /**
     * If a recording is in flight, defer the cleanup so it runs on the next
     * encoder-idle periodic tick. Returns true when deferral happened.
     *
     * <p>Three of the public ensure*Space callers can race the encoder:
     * user-initiated limit changes from the HTTP/IPC settings handlers
     * (QualitySettingsApiHandler, SurveillanceIpcServer, TcpCommandServer).
     * Without this gate, lowering the recordings limit while actively
     * recording triggers a delete burst that contends with the disk
     * writer and produces multi-second eglSwap stalls observed in field
     * logs. The post-save cleanup path has its own gate at
     * onRecordingFileSaved; this generalises the same guard to limit-
     * change paths so the cleanup contract is uniform.
     *
     * <p>Hard-overlimit escape: if usage already exceeds the limit by
     * &gt;5%, deferral is skipped — at that point the encoder will
     * backpressure on disk full anyway, so unblocking storage is the
     * lesser evil. Mirrors the periodic-loop policy.
     */
    private boolean deferIfEncoderBusy(String deferredKey, long currentSize, long limitBytes) {
        if (!isEncoderWriting()) return false;
        boolean hardOverLimit = limitBytes > 0 && currentSize > limitBytes * 21 / 20;
        if (hardOverLimit) {
            logWarn("Cleanup forced during recording: " + deferredKey + " at "
                + formatSize(currentSize) + "/" + formatSize(limitBytes) + " (HARD)");
            return false;
        }
        deferredCleanupDirs.add(deferredKey);
        logDebug("Cleanup deferred (encoder busy): " + deferredKey + " — will drain on next idle tick");
        return true;
    }

    /**
     * Ensure recordings storage is within size limit.
     * Deletes oldest files (across active + inactive + legacy locations)
     * until the total falls under the limit.
     *
     * <p>Defers when the encoder is mid-write (see {@link #deferIfEncoderBusy})
     * unless usage is hard-over-limit, in which case cleanup runs anyway to
     * keep the disk writer from hitting ENOSPC.
     *
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available, or true
     *         when deferred (the deferred-cleanup drain will retry).
     */
    public boolean ensureRecordingsSpace(long reserveBytes) {
        // cleanupLock serializes against post-save async cleanups, periodic
        // ticks, and the reset/wipe path. Without it, two concurrent runs
        // can sort overlapping file lists and double-delete or hit
        // ConcurrentModificationException on the dedup set.
        synchronized (cleanupLock) {
            if (deferIfEncoderBusy(DEFERRED_RECORDINGS, getRecordingsSize(),
                    recordingsLimitMb * 1024 * 1024)) {
                return true;
            }
            return ensureSpace("recordings", getReapableDirs("recordings"), recordingsDir,
                namePrefixForCategory("recordings"),
                recordingsLimitMb * 1024 * 1024, reserveBytes);
        }
    }

    /**
     * Ensure surveillance storage is within size limit.
     * Deletes oldest files (across active + inactive + legacy locations)
     * until the total falls under the limit.
     *
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available
     */
    public boolean ensureSurveillanceSpace(long reserveBytes) {
        synchronized (cleanupLock) {
            if (deferIfEncoderBusy(DEFERRED_SURVEILLANCE, getSurveillanceSize(),
                    surveillanceLimitMb * 1024 * 1024)) {
                return true;
            }
            return ensureSpace("surveillance", getReapableDirs("surveillance"), surveillanceDir,
                namePrefixForCategory("surveillance"),
                surveillanceLimitMb * 1024 * 1024, reserveBytes);
        }
    }

    /**
     * Ensure proximity storage is within size limit.
     * Deletes oldest files (across active + inactive + legacy locations)
     * until the total falls under the limit.
     *
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available
     */
    public boolean ensureProximitySpace(long reserveBytes) {
        synchronized (cleanupLock) {
            if (deferIfEncoderBusy(DEFERRED_PROXIMITY, getProximitySize(),
                    proximityLimitMb * 1024 * 1024)) {
                return true;
            }
            return ensureSpace("proximity", getReapableDirs("proximity"), proximityDir,
                namePrefixForCategory("proximity"),
                proximityLimitMb * 1024 * 1024, reserveBytes);
        }
    }

    /**
     * Ensure trips storage is within size limit.
     * Deletes oldest files until the total falls under the limit.
     *
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available
     */
    public boolean ensureTripsSpace(long reserveBytes) {
        synchronized (cleanupLock) {
            if (deferIfEncoderBusy(DEFERRED_TRIPS, getTripsSize(),
                    tripsLimitMb * 1024 * 1024)) {
                return true;
            }
            return ensureSpace("trips", getReapableDirs("trips"), tripsDir,
                namePrefixForCategory("trips"),
                tripsLimitMb * 1024 * 1024, reserveBytes);
        }
    }
    
    /**
     * Primary file extension for a category. Cleanup walks files matching
     * this extension as the "anchor" rows; sidecars are pulled in via
     * {@link #sidecarExtensionsForCategory(String)}.
     *
     * @return non-null lowercase extension including the leading dot.
     */
    private static String primaryExtensionForCategory(String category) {
        switch (category) {
            case "recordings":   return ".mp4";
            case "surveillance": return ".mp4";
            case "proximity":    return ".mp4";
            case "trips":        return ".jsonl.gz";
            default:             return ".mp4";
        }
    }

    /**
     * Partial / orphan extensions that aren't anchors but still consume disk
     * space the user expects to be subject to the limit. These are produced
     * by abnormal exits (SIGKILL between {@code .mp4.tmp} and {@code .mp4}
     * rename, encoder write that left a {@code .broken}, hero-image
     * extraction that left a {@code .jpg.tmp}). Counted by size accounting
     * and {@link #sweepOrphanTempFiles} reaps them with a 10-minute grace
     * window so live writers aren't disturbed.
     *
     * @return possibly empty array of lowercase suffixes (with leading dot
     *         or compound like {@code .mp4.tmp}).
     */
    private static String[] partialExtensionsForCategory(String category) {
        switch (category) {
            case "recordings":   return new String[]{".mp4.tmp", ".broken"};
            case "surveillance": return new String[]{".mp4.tmp", ".broken", ".jpg.tmp", ".json.tmp", ".srt.tmp"};
            case "proximity":    return new String[]{".mp4.tmp", ".broken"};
            case "trips":        return new String[]{".jsonl.gz.tmp"};
            default:             return new String[]{};
        }
    }

    /**
     * Sidecar extensions that share a stem with the primary file and should
     * be reaped together. Used both for size accounting (so the on-disk
     * footprint matches what the user sees in the UI) and for delete-time
     * orphan cleanup.
     *
     * @return possibly empty array of lowercase extensions (each starting
     *         with a dot), in addition to the primary extension.
     */
    private static String[] sidecarExtensionsForCategory(String category) {
        switch (category) {
            case "recordings":
                // cam_*.mp4 has no sidecars in the current build.
                return new String[]{};
            case "surveillance":
                // event_*: timeline JSON, hero JPG, overlay SRT.
                return new String[]{".json", ".jpg", ".srt"};
            case "proximity":
                return new String[]{".json"};
            case "trips":
                return new String[]{};
            default:
                return new String[]{};
        }
    }

    /**
     * Strip the primary extension from a file name, leaving the stem used
     * to match sidecars. Handles compound extensions like ".jsonl.gz".
     */
    private static String stemForName(String fileName, String primaryExt) {
        if (fileName.endsWith(primaryExt)) {
            return fileName.substring(0, fileName.length() - primaryExt.length());
        }
        return fileName;
    }

    /**
     * Generic cleanup method that operates across a set of directories.
     *
     * Pools all primary-extension files from every dir (active, inactive
     * mirror, legacy), sorts globally by mtime, and deletes oldest-first
     * (along with each anchor's sidecars) until the combined total is
     * under the limit. This guarantees the user-configured limit is honored
     * across orphan locations after a storage-type switch or after a legacy
     * install left behind clips.
     *
     * Size accounting includes sidecars so the limit reflects the on-disk
     * footprint the user sees in the UI, not just the anchor file's bytes.
     *
     * SOTA: Uses shell fallback for listing/deleting when directory is owned
     * by a different UID than the daemon.
     *
     * @param category    Category key — recordings/surveillance/proximity/trips.
     * @param dirs        Every directory whose files count toward this limit.
     *                    May contain a mix of active, inactive, and legacy
     *                    paths. Nulls/missing dirs are skipped.
     * @param activeDir   The dir new files will land in. Created if missing
     *                    so the next write doesn't fail.
     * @param limitBytes  Total bytes allowed across all dirs.
     * @param reserveBytes Additional bytes to keep free (subtracted from limit).
     * @return true if cleanup was successful and space is available
     */
    private boolean ensureSpace(String category, List<File> dirs, File activeDir,
                                String namePrefix,
                                long limitBytes, long reserveBytes) {
        if (activeDir != null && (!activeDir.exists() || !activeDir.isDirectory())) {
            activeDir.mkdirs();
        }

        long targetSize = limitBytes - reserveBytes;
        if (targetSize < 0) targetSize = 0;

        final String primaryExt = primaryExtensionForCategory(category);
        final String[] sidecarExts = sidecarExtensionsForCategory(category);
        final String[] auxPrefixes = auxiliaryPrefixesForCategory(category);

        // Collect every reapable anchor file, deduplicated by filename so a
        // clip that exists on both internal and SD card isn't accounted twice.
        // When namePrefix is non-null, restrict to files matching the category
        // (some dirs in the list — typically the flat legacy base — are shared
        // across categories).
        List<File> allFiles = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        long currentSize = 0;
        for (File dir : dirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(primaryExt));
            if (files == null) {
                files = listFilesByExt(dir, primaryExt);
            }
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (namePrefix != null && !name.startsWith(namePrefix)) continue;
                if (!seenNames.add(name)) continue;
                allFiles.add(f);
                currentSize += f.length();
                // Add sidecar bytes to the running total so we measure the
                // same on-disk footprint we'd actually free by deleting the
                // anchor + its sidecars below. Two sidecar shapes:
                //   - same-stem siblings: <stem><sidecarExt>  (json/srt/jpg)
                //   - aux-prefix siblings: <auxPrefix><stem>_*  (per-actor thumbs)
                if (sidecarExts.length > 0 || auxPrefixes.length > 0) {
                    String stem = stemForName(name, primaryExt);
                    for (String ext : sidecarExts) {
                        File sidecar = new File(f.getParentFile(), stem + ext);
                        if (sidecar.isFile()) currentSize += sidecar.length();
                    }
                    if (auxPrefixes.length > 0) {
                        for (File aux : findAuxiliarySiblings(f.getParentFile(), auxPrefixes, stem)) {
                            currentSize += aux.length();
                        }
                    }
                }
            }
        }

        if (currentSize <= targetSize) {
            return true;  // Already within limit
        }

        if (allFiles.isEmpty()) {
            return true;
        }

        // Oldest first (global ordering across all dirs).
        Collections.sort(allFiles, Comparator.comparingLong(File::lastModified));

        int deletedCount = 0;
        long deletedSize = 0;
        boolean reapedFromInactive = false;

        // Path of the in-flight trip telemetry file, if any. Only honored
        // when reaping the trips category; recordings/surveillance use the
        // encoder-writing probe further down.
        final String protectedTripPath = "trips".equals(category) ? activeTripFilePath : null;

        for (File file : allFiles) {
            if (currentSize <= targetSize) break;

            // Don't unlink a still-being-written trip file. The recorder
            // keeps the GZIPOutputStream open across SAMPLE_INTERVAL_MS ticks;
            // a delete here would orphan every byte buffered after this point.
            if (protectedTripPath != null
                    && protectedTripPath.equals(file.getAbsolutePath())) {
                logDebug("Skipping in-flight trip file: " + file.getName());
                continue;
            }

            long fileSize = file.length();
            boolean deleted = file.delete();
            if (!deleted) {
                deleted = deleteFileViaShell(file);
            }

            if (deleted) {
                currentSize -= fileSize;
                deletedCount++;
                deletedSize += fileSize;
                if (activeDir == null
                    || !file.getParentFile().getAbsolutePath().equals(activeDir.getAbsolutePath())) {
                    reapedFromInactive = true;
                }
                logInfo("Deleted old file: " + file.getAbsolutePath() + " (" + formatSize(fileSize) + ")");

                // Sidecars share the anchor's stem and are dead weight once
                // the anchor is gone. Walk the registered set instead of the
                // recordings-only ".mp4 → .json" replace().
                if (sidecarExts.length > 0) {
                    String stem = stemForName(file.getName(), primaryExt);
                    for (String ext : sidecarExts) {
                        File sidecar = new File(file.getParentFile(), stem + ext);
                        if (sidecar.exists()) {
                            long sidecarSize = sidecar.length();
                            boolean sidecarDeleted = sidecar.delete();
                            if (!sidecarDeleted) sidecarDeleted = deleteFileViaShell(sidecar);
                            if (sidecarDeleted) {
                                currentSize -= sidecarSize;
                                deletedSize += sidecarSize;
                            }
                        }
                    }
                }

                // Aux-prefix siblings (per-actor thumbs `thumb_event_<base>_a*`).
                // These don't share a stem suffix with the anchor — they share
                // a prefix construction (auxPrefix + anchorStem + "_…").
                if (auxPrefixes.length > 0) {
                    String stem = stemForName(file.getName(), primaryExt);
                    for (File aux : findAuxiliarySiblings(file.getParentFile(), auxPrefixes, stem)) {
                        long auxSize = aux.length();
                        boolean auxDeleted = aux.delete();
                        if (!auxDeleted) auxDeleted = deleteFileViaShell(aux);
                        if (auxDeleted) {
                            currentSize -= auxSize;
                            deletedSize += auxSize;
                        }
                    }
                }

                // Drop any cached entry the recordings API might still hold.
                try {
                    com.overdrive.app.server.RecordingsApiHandler
                        .invalidateRecordingCache(file.getAbsolutePath());
                } catch (Throwable ignored) {
                    // RecordingsApiHandler may not be loaded in every process.
                }
            } else {
                logWarn("Failed to delete: " + file.getAbsolutePath());
            }
        }

        if (deletedCount > 0) {
            logInfo("Cleanup complete: deleted " + deletedCount + " files (" + formatSize(deletedSize) + ")"
                + (reapedFromInactive ? " — including orphan/legacy locations" : ""));
        }

        // Orphan-sidecar pass. The collection loop above accumulated every
        // anchor stem in `seenNames`; any sidecar (same-stem .json/.jpg/.srt
        // or aux-prefix `thumb_event_<stem>_a*`) whose stem isn't in that set
        // belongs to an anchor that was deleted in a prior cycle (or by an
        // app-side reset that didn't sweep sidecars). Reap them so per-actor
        // thumbnails / SRT / JSON fragments don't accumulate forever.
        // Skipped when the category has no sidecars or aux prefixes.
        if (sidecarExts.length > 0 || auxPrefixes.length > 0) {
            // Convert anchor-name set to stems for sidecar comparison.
            Set<String> liveStems = new HashSet<>();
            for (String anchorName : seenNames) {
                liveStems.add(stemForName(anchorName, primaryExt));
            }
            // Anchors we just deleted are still in seenNames but their
            // sidecars have already been wiped above; safe to leave them in.
            int orphansDeleted = 0;
            long orphansFreed = 0;
            Set<String> seenSidecarPaths = new HashSet<>();
            for (File dir : dirs) {
                if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
                File[] files = dir.listFiles();
                if (files == null) files = listFilesViaShell(dir);
                if (files == null) continue;
                for (File f : files) {
                    if (!f.isFile()) continue;
                    String name = f.getName();
                    // Sidecar candidates may match the primary prefix
                    // (event_xxx.json) OR an aux prefix (thumb_event_xxx_a17.jpg);
                    // both must be considered, hence nameMatchesCategoryPrefix.
                    if (namePrefix != null
                            && !nameMatchesCategoryPrefix(name, namePrefix, auxPrefixes)) continue;

                    String stem = null;
                    // Aux-prefix sibling first, since a thumb_event_xxx_a*.jpg
                    // also matches the .jpg sidecar branch — without this
                    // ordering, we'd parse stem as the full minus-".jpg" name
                    // and incorrectly conclude it's an orphan on every tick.
                    String matchedAux = null;
                    for (String aux : auxPrefixes) {
                        if (name.startsWith(aux)) { matchedAux = aux; break; }
                    }
                    if (matchedAux != null) {
                        // Per-actor thumb shape: "<aux><anchorStem>_a<id>[_<rel>].<ext>".
                        // The anchor stem itself can contain underscores
                        // (event_<date>_<time>), so we can't parse with
                        // indexOf('_', auxLen). Find the LAST "_a<digit>" run
                        // and treat everything between aux and that as stem.
                        int auxLen = matchedAux.length();
                        int actorMarker = lastIndexOfActorMarker(name, auxLen);
                        if (actorMarker > auxLen) {
                            stem = name.substring(auxLen, actorMarker);
                        }
                    } else {
                        // Same-stem sidecar (event_xxx.json/.srt/.jpg).
                        for (String ext : sidecarExts) {
                            if (name.endsWith(ext)) {
                                stem = name.substring(0, name.length() - ext.length());
                                break;
                            }
                        }
                    }
                    if (stem == null) continue;
                    if (!seenSidecarPaths.add(f.getAbsolutePath())) continue;
                    if (liveStems.contains(stem)) continue;  // anchor still around
                    long sz = f.length();
                    boolean ok = f.delete();
                    if (!ok) ok = deleteFileViaShell(f);
                    if (ok) {
                        orphansDeleted++;
                        orphansFreed += sz;
                        currentSize -= sz;
                    }
                }
            }
            if (orphansDeleted > 0) {
                logInfo("Sidecar orphans reaped: " + orphansDeleted + " files (" + formatSize(orphansFreed) + ")");
            }
        }

        // If still over limit and the active dir lives on the SD card, fall
        // back to CDR cleanup to free up underlying SD-card space.
        if (currentSize > targetSize
            && sdCardAvailable
            && activeDir != null
            && sdCardPath != null
            && activeDir.getAbsolutePath().startsWith(sdCardPath)) {
            try {
                ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
                if (cleaner.isEnabled()) {
                    logInfo("Overdrive cleanup insufficient on SD card — triggering CDR cleanup");
                    cleaner.ensureReservedSpace();
                }
            } catch (Exception e) {
                logWarn("CDR fallback cleanup failed: " + e.getMessage());
            }
        }

        return currentSize <= targetSize;
    }
    
    /**
     * Find the last "_a<digit>" actor-id marker in a thumb filename. Returns
     * the offset of the underscore in "_a", or -1 if none found within
     * {@code [from, name.length())}. The anchor stem (e.g.
     * {@code event_<date>_<time>}) can contain underscores, so we cannot
     * use the first underscore after the aux prefix.
     */
    private static int lastIndexOfActorMarker(String name, int from) {
        for (int i = name.length() - 2; i >= from; i--) {
            if (name.charAt(i) != '_') continue;
            if (i + 1 >= name.length() || name.charAt(i + 1) != 'a') continue;
            // Require a digit after "_a" so we don't match arbitrary text.
            if (i + 2 < name.length() && Character.isDigit(name.charAt(i + 2))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Find every aux-prefix sibling of an anchor stem within a directory.
     * For surveillance, an anchor {@code event_<base>.mp4} in {@code dir}
     * has aux siblings matching {@code thumb_event_<base>_*} — per-actor
     * thumbnails written by {@code SurveillanceEngineGpu.dispatchSegmentMetadata}.
     *
     * <p>Returns an empty list when {@code auxPrefixes} is empty, the dir
     * doesn't exist, or no matches are found.
     */
    private List<File> findAuxiliarySiblings(File dir, String[] auxPrefixes, String stem) {
        if (dir == null || auxPrefixes == null || auxPrefixes.length == 0
                || !dir.isDirectory()) {
            return java.util.Collections.emptyList();
        }
        List<File> hits = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) files = listFilesViaShell(dir);
        if (files == null) return hits;
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            for (String aux : auxPrefixes) {
                // Match `<aux><stem>_…` so we don't accidentally swallow
                // an unrelated stem that happens to share a prefix.
                String wanted = aux + stem + "_";
                if (name.startsWith(wanted)) {
                    hits.add(f);
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * SOTA: Delete file via shell command when Java delete fails.
     */
    private boolean deleteFileViaShell(File file) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"rm", file.getAbsolutePath()});
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            logWarn("deleteFileViaShell failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Run cleanup across every category. Holds {@link #cleanupLock} once
     * for the whole sweep so the four passes appear atomic to peers; the
     * lock is reentrant, so the per-call locks in ensureXxxSpace are no-ops
     * inside this scope.
     */
    public void runCleanup() {
        synchronized (cleanupLock) {
            ensureRecordingsSpace(0);
            ensureSurveillanceSpace(0);
            ensureProximitySpace(0);
            ensureTripsSpace(0);
        }
    }
    
    // ==================== Utility ====================
    
    public static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000) {
            return String.format("%.1f GB", bytes / 1_000_000_000.0);
        } else if (bytes >= 1_000_000) {
            return String.format("%.1f MB", bytes / 1_000_000.0);
        } else if (bytes >= 1_000) {
            return String.format("%.1f KB", bytes / 1_000.0);
        }
        return bytes + " B";
    }
    
    public static long getMinLimitMb() {
        return MIN_LIMIT_MB;
    }
    
    /**
     * Static fallback ceiling. Use the instance methods
     * {@link #getEffectiveMaxLimitMb(StorageType)} / {@link #getMaxLimitMb(StorageType)}
     * for the live, volume-aware ceiling. These statics are kept only
     * for legacy callers that don't have an instance handy.
     */
    public static long getMaxLimitMb() {
        return MAX_LIMIT_MB_FALLBACK;
    }

    public static long getMaxLimitMbInternal() {
        StorageManager sm = instance;
        return sm != null ? sm.getEffectiveMaxLimitMb(StorageType.INTERNAL) : MAX_LIMIT_MB_FALLBACK;
    }

    public static long getMaxLimitMbSdCard() {
        StorageManager sm = instance;
        return sm != null ? sm.getEffectiveMaxLimitMb(StorageType.SD_CARD) : MAX_LIMIT_MB_FALLBACK;
    }

    public static long getMaxLimitMbUsb() {
        StorageManager sm = instance;
        return sm != null ? sm.getEffectiveMaxLimitMb(StorageType.USB) : MAX_LIMIT_MB_FALLBACK;
    }
    
    // ==================== Event-Driven Cleanup (SOTA) ====================
    
    /**
     * Called after a recording file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * This is the SOTA approach - cleanup after each file save rather than
     * only at recording start, preventing storage overflow during long sessions.
     * 
     * IMPORTANT: Runs async to avoid blocking the video encoding thread.
     */
    public void onRecordingFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(recordingsDir);

        // FIX: Removed broadcastRecentFiles() — specific file already broadcast by onFileSaved()

        // Gate destructive cleanup on encoder write state. If a recording is
        // mid-flight, deferring this delete burst is what keeps the SD card
        // available for the encoder's disk writer. The deferred queue is
        // drained the next time we observe encoder=idle, so nothing is lost.
        if (isEncoderWriting()) {
            deferredCleanupDirs.add(DEFERRED_RECORDINGS);
            logDebug("Recording file saved during active write — deferring cleanup");
            return;
        }

        asyncCleanupExecutor.execute(() -> {
            synchronized (cleanupLock) {
                try {
                    // Make all files in directory readable
                    makeFilesReadable(recordingsDir);
                    
                    long currentSize = getRecordingsSize();
                    long limitBytes = recordingsLimitMb * 1024 * 1024;
                    
                    if (currentSize > limitBytes) {
                        logInfo("Recording file saved - triggering cleanup (current=" + 
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureRecordingsSpace(0);
                    } else {
                        logDebug("Recording file saved - within limits (" + 
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async recording cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Called after a surveillance event file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * IMPORTANT: Runs async to avoid blocking the video encoding thread.
     */
    public void onSurveillanceFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(surveillanceDir);

        // FIX: Removed broadcastRecentFiles() call that re-scanned ALL files modified
        // in the last 60 seconds. This caused duplicate MediaScanner broadcasts —
        // if two events saved 20 seconds apart, the second save re-broadcast the first.
        // Over days of parking, this list grows to hundreds of files, causing massive
        // CPU spikes on every new event. The specific file is already broadcast by
        // onFileSaved() → broadcastFile(file) before this method is called.

        // Defer destructive cleanup if encoder is mid-write. See onRecordingFileSaved
        // for rationale — SD card I/O contention against the encoder's disk writer
        // is what produces the freeze+skip artifact in the recorded MP4.
        if (isEncoderWriting()) {
            deferredCleanupDirs.add(DEFERRED_SURVEILLANCE);
            logDebug("Surveillance file saved during active write — deferring cleanup");
            return;
        }

        asyncCleanupExecutor.execute(() -> {
            synchronized (cleanupLock) {
                try {
                    // Make all files in directory readable
                    makeFilesReadable(surveillanceDir);
                    
                    long currentSize = getSurveillanceSize();
                    long limitBytes = surveillanceLimitMb * 1024 * 1024;
                    
                    if (currentSize > limitBytes) {
                        logInfo("Surveillance file saved - triggering cleanup (current=" + 
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureSurveillanceSpace(0);
                    } else {
                        logDebug("Surveillance file saved - within limits (" + 
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async surveillance cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Called after a proximity event file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * IMPORTANT: Runs async to avoid blocking the video encoding thread.
     */
    public void onProximityFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(proximityDir);

        // FIX: Removed broadcastRecentFiles() — specific file already broadcast by onFileSaved()

        if (isEncoderWriting()) {
            deferredCleanupDirs.add(DEFERRED_PROXIMITY);
            logDebug("Proximity file saved during active write — deferring cleanup");
            return;
        }

        asyncCleanupExecutor.execute(() -> {
            synchronized (cleanupLock) {
                try {
                    // Make all files in directory readable
                    makeFilesReadable(proximityDir);
                    
                    long currentSize = getProximitySize();
                    long limitBytes = proximityLimitMb * 1024 * 1024;
                    
                    if (currentSize > limitBytes) {
                        logInfo("Proximity file saved - triggering cleanup (current=" + 
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureProximitySpace(0);
                    } else {
                        logDebug("Proximity file saved - within limits (" + 
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async proximity cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Called after a trip telemetry file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * IMPORTANT: Runs async to avoid blocking the telemetry recording thread.
     */
    public void onTripFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(tripsDir);

        if (isEncoderWriting()) {
            deferredCleanupDirs.add(DEFERRED_TRIPS);
            logDebug("Trip file saved during active write — deferring cleanup");
            return;
        }

        asyncCleanupExecutor.execute(() -> {
            synchronized (cleanupLock) {
                try {
                    // Make all files in directory readable
                    makeFilesReadable(tripsDir);
                    
                    long currentSize = getTripsSize();
                    long limitBytes = tripsLimitMb * 1024 * 1024;
                    
                    if (currentSize > limitBytes) {
                        logInfo("Trip file saved - triggering cleanup (current=" + 
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureTripsSpace(0);
                    } else {
                        logDebug("Trip file saved - within limits (" + 
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async trips cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Fix directory permissions so UI app can read files.
     * Note: chmod doesn't work on Android FUSE filesystem, but we keep Java API calls.
     */
    private void fixDirectoryPermissions(File dir) {
        if (dir != null && dir.exists()) {
            dir.setReadable(true, false);
            dir.setExecutable(true, false);
        }
    }
    
    /**
     * Make all .mp4 files in directory readable by all.
     * Note: chmod doesn't work on Android FUSE filesystem - rely on MediaStore instead.
     */
    private void makeFilesReadable(File dir) {
        if (dir == null || !dir.exists()) return;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null) {
            files = listFilesViaShell(dir);
        }
        
        if (files != null) {
            for (File f : files) {
                f.setReadable(true, false);
            }
        }
    }
    
    /**
     * Make a single file readable by all users.
     * Note: chmod doesn't work on Android FUSE - rely on MediaStore for cross-UID access.
     */
    public void makeFileReadable(File file) {
        if (file == null || !file.exists()) return;
        file.setReadable(true, false);
    }
    
    /**
     * Force Android MediaScanner to index a file so it appears in MediaStore
     * and becomes visible to standard apps with READ_EXTERNAL_STORAGE.
     * 
     * CRITICAL: Both methods are required on BYD's Android 10:
     * - `am broadcast MEDIA_SCANNER_SCAN_FILE` refreshes the FUSE permission cache
     *   so that File.listFiles() on SD card paths can see the file. Without this,
     *   the RecordingsApiHandler's scanDirectory() gets incomplete file listings.
     * - `content insert` directly inserts into MediaStore for cross-UID visibility
     *   (needed for the UI app running as a different UID).
     */
    private void broadcastFile(File file) {
        if (file == null || !file.exists()) return;
        
        String path = file.getAbsolutePath();
        
        try {
            // Method 1: FUSE cache refresh via MediaScanner intent
            // Required for File.listFiles() to work on SD card FUSE paths
            Runtime.getRuntime().exec(new String[]{
                "am", "broadcast",
                "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                "-d", "file://" + path
            });
            
            // Method 2: Direct MediaStore insert for cross-UID visibility
            Runtime.getRuntime().exec(new String[]{
                "content", "insert",
                "--uri", "content://media/external/video/media",
                "--bind", "_data:s:" + path
            });
            
            logDebug("Broadcast file to MediaScanner: " + file.getName());
        } catch (Exception e) {
            logWarn("Failed to broadcast file: " + e.getMessage());
        }
    }
    
    /**
     * SOTA: Fix permissions and broadcast a single file after it's saved.
     * Call this immediately after closing a video file.
     * @param file The video file that was just saved
     */
    public void onFileSaved(File file) {
        if (file == null || !file.exists()) {
            logWarn("onFileSaved: file is null or doesn't exist");
            return;
        }
        
        logInfo("Processing saved file: " + file.getName() + " (" + formatSize(file.length()) + ")");

        // 1. Make file readable by all (chmod 666)
        makeFileReadable(file);

        // 2. Broadcast to MediaScanner. This spawns shell processes
        // (am broadcast + content insert) which compete for I/O bandwidth.
        // While the encoder is mid-write, defer to the background cleanup
        // executor so the disk writer keeps priority. (Audit P2.)
        if (isEncoderWriting()) {
            final File f = file;
            asyncCleanupExecutor.execute(() -> {
                try { broadcastFile(f); } catch (Exception e) {
                    logWarn("Deferred broadcastFile error: " + e.getMessage());
                }
            });
        } else {
            broadcastFile(file);
        }

        // 3. Trigger appropriate cleanup based on directory
        String path = file.getAbsolutePath();
        if (path.contains(RECORDINGS_SUBDIR)) {
            onRecordingFileSaved();
        } else if (path.contains(SURVEILLANCE_SUBDIR)) {
            onSurveillanceFileSaved();
        } else if (path.contains(PROXIMITY_SUBDIR)) {
            onProximityFileSaved();
        } else if (path.contains(TRIPS_SUBDIR)) {
            onTripFileSaved();
        }
    }
    
    /**
     * Broadcast all recent files in a directory to MediaScanner.
     * @param dir Directory to scan
     * @param maxAgeMs Only broadcast files modified within this time (ms)
     */
    private void broadcastRecentFiles(File dir, long maxAgeMs) {
        if (dir == null || !dir.exists()) return;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files != null) {
            long now = System.currentTimeMillis();
            for (File f : files) {
                if (now - f.lastModified() < maxAgeMs) {
                    broadcastFile(f);
                }
            }
        }
    }
    
    // ==================== Periodic Background Cleanup ====================
    
    /**
     * Start periodic cleanup for long recording sessions.
     * Runs every 30 seconds while recording is active.
     */
    public void startPeriodicCleanup() {
        if (cleanupScheduler != null && !cleanupScheduler.isShutdown()) {
            return;  // Already running
        }
        
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(() -> {
                // Same low-priority strategy as asyncCleanupExecutor — the
                // periodic tick must never preempt the disk writer.
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                r.run();
            }, "StorageCleanup");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                // Don't run un-gated cleanup before the encoder probe is wired.
                // Daemon-init ordering: startPeriodicCleanup() fires early
                // (before pipeline.init), so the first scheduled tick at
                // T+30s could land before the probe is bound and would treat
                // an active recording as "encoder idle" → run the destructive
                // cleanup right through the recording. (Audit P1.)
                if (!probeWired.get()) {
                    logDebug("Periodic cleanup tick skipped — encoder probe not wired yet");
                    return;
                }
                // SOTA: skip the entire pass while the encoder is writing. The
                // 19-files / 118 MB delete burst observed in field logs while
                // recording was active produced a 2.8 sec mosaic+swap stall on
                // the GL thread (encoder backpressured eglSwap because the disk
                // writer was starved by cleanup I/O). Deferring is safe: the
                // dir is added to deferredCleanupDirs so the next tick (or the
                // next save event after recording finishes) drains it.
                //
                // Edge case: a HARD storage situation (disk literally full, can't
                // even write the next muxer chunk) needs a way out. We honor
                // that by forcing a cleanup pass when current usage exceeds the
                // limit by >5% — at that point eglSwap will fail anyway, so
                // unblocking storage is the lesser evil. Below that threshold,
                // the encoder write wins.
                if (isEncoderWriting()) {
                    // Per-dir over-limit ratio. Old code used MAX(limits)/20 as
                    // the denominator, which let a small dir (e.g., 100 MB
                    // recordings) grow many tens of MB over its OWN limit
                    // before triggering. Per-dir ratio gives every dir an
                    // independent, fair escape (audit Finding "storage drift").
                    long recBytes = getRecordingsSize();
                    long survBytes = getSurveillanceSize();
                    long tripsBytes = getTripsSize();
                    long recLim = recordingsLimitMb * 1024 * 1024;
                    long survLim = surveillanceLimitMb * 1024 * 1024;
                    long tripsLim = tripsLimitMb * 1024 * 1024;
                    boolean recHard  = recLim   > 0 && recBytes   > recLim   * 21 / 20;  // >5% over OWN limit
                    boolean survHard = survLim  > 0 && survBytes  > survLim  * 21 / 20;
                    boolean tripsHard= tripsLim > 0 && tripsBytes > tripsLim * 21 / 20;

                    // Free-disk emergency: if ANY active volume is critically
                    // low, continuing to write is going to fail anyway. Force
                    // cleanup regardless of probe. The min across all
                    // categories' active volumes covers the case where
                    // surveillance is on USB while recordings are on internal —
                    // a starved surveillance volume must still trigger.
                    long minFree = Long.MAX_VALUE;
                    for (StorageType t : new StorageType[]{
                            recordingsStorageType, surveillanceStorageType, tripsStorageType}) {
                        long f;
                        switch (t) {
                            case SD_CARD: f = getSdCardFreeSpace(); break;
                            case USB:     f = getUsbFreeSpace();    break;
                            case INTERNAL:
                            default:      f = getInternalFreeSpace(); break;
                        }
                        if (f > 0 && f < minFree) minFree = f;
                    }
                    long sdFree = (minFree == Long.MAX_VALUE) ? 0 : minFree;
                    boolean diskCritical = sdFree > 0 && sdFree < 200L * 1024 * 1024;  // <200MB free

                    boolean hardOverlimit = recHard || survHard || tripsHard || diskCritical;
                    if (hardOverlimit) {
                        logWarn("Periodic cleanup forced during recording: "
                            + "rec=" + formatSize(recBytes) + "/" + formatSize(recLim) + (recHard ? " HARD" : "")
                            + " surv=" + formatSize(survBytes) + "/" + formatSize(survLim) + (survHard ? " HARD" : "")
                            + " trips=" + formatSize(tripsBytes) + "/" + formatSize(tripsLim) + (tripsHard ? " HARD" : "")
                            + " sdFree=" + formatSize(sdFree) + (diskCritical ? " CRITICAL" : ""));
                    } else {
                        // Mark all dirs that are at risk so we drain them later.
                        if (recBytes > recLim * 0.9) deferredCleanupDirs.add(DEFERRED_RECORDINGS);
                        if (survBytes > survLim * 0.9) deferredCleanupDirs.add(DEFERRED_SURVEILLANCE);
                        if (tripsBytes > tripsLim * 0.9) deferredCleanupDirs.add(DEFERRED_TRIPS);
                        return;
                    }
                }

                // Encoder idle: drain any deferred work first so storage limits
                // re-converge after a long recording.
                drainDeferredCleanupIfDue();

                // Sweep orphan .mp4.tmp / .broken / .jpg.tmp partials. Held by
                // a 10-minute grace window inside the helper, so anything
                // touched by a live writer is safe. Without this in the
                // periodic loop, partials accumulated only got reaped at
                // daemon boot — a long-running daemon hit by an OOM kill
                // mid-recording could leave the disk half-full of tmps that
                // the size-based reaper never frees because it only walks
                // primary-extension files.
                synchronized (cleanupLock) {
                    sweepOrphanTempFiles();
                }

                // Standard periodic pass (catches dirs that grew past the limit
                // while the daemon was offline, or after a manual limit change).
                synchronized (cleanupLock) {
                    long currentSize = getRecordingsSize();
                    long limitBytes = recordingsLimitMb * 1024 * 1024;
                    if (currentSize > limitBytes * 0.9) {  // 90% threshold
                        logInfo("Periodic cleanup: recordings at " +
                            formatSize(currentSize) + "/" + formatSize(limitBytes));
                        ensureRecordingsSpace(50 * 1024 * 1024);  // Reserve 50MB
                    }
                }

                synchronized (cleanupLock) {
                    long currentSize = getSurveillanceSize();
                    long limitBytes = surveillanceLimitMb * 1024 * 1024;
                    if (currentSize > limitBytes * 0.9) {  // 90% threshold
                        logInfo("Periodic cleanup: surveillance at " +
                            formatSize(currentSize) + "/" + formatSize(limitBytes));
                        ensureSurveillanceSpace(50 * 1024 * 1024);  // Reserve 50MB
                    }
                }

                synchronized (cleanupLock) {
                    long currentSize = getTripsSize();
                    long limitBytes = tripsLimitMb * 1024 * 1024;
                    if (currentSize > limitBytes * 0.9) {  // 90% threshold
                        logInfo("Periodic cleanup: trips at " +
                            formatSize(currentSize) + "/" + formatSize(limitBytes));
                        ensureTripsSpace(50 * 1024 * 1024);  // Reserve 50MB
                    }
                }
            } catch (Exception e) {
                logWarn("Periodic cleanup error: " + e.getMessage());
            }
        }, CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        logInfo("Started periodic storage cleanup (interval=" + CLEANUP_INTERVAL_SECONDS + "s)");
    }
    
    /**
     * Drain any cleanup that was deferred because the encoder was mid-write.
     * Called from the periodic tick AND from each onXxxFileSaved path, so a
     * deferred backlog never sits indefinitely. Safe to call when the queue
     * is empty (early-exits on empty set).
     */
    private void drainDeferredCleanupIfDue() {
        if (deferredCleanupDirs.isEmpty()) return;
        if (isEncoderWriting()) return;  // still busy, try later
        // Snapshot+clear so a concurrent add (e.g. a periodic tick that fires
        // while we're draining) doesn't lose the new mark.
        java.util.Set<String> toRun = new java.util.HashSet<>(deferredCleanupDirs);
        deferredCleanupDirs.removeAll(toRun);
        logInfo("Draining deferred cleanup: " + toRun);

        // Per-dir try/catch: a failure on one dir must NOT cause the others
        // to be re-marked. The previous catch-all re-added the entire toRun
        // snapshot on any exception, including dirs that had already been
        // cleaned successfully — wasting the next tick on idempotent re-runs
        // (audit P1).
        if (toRun.contains(DEFERRED_RECORDINGS)) {
            try {
                synchronized (cleanupLock) {
                    if (getRecordingsSize() > recordingsLimitMb * 1024 * 1024) {
                        ensureRecordingsSpace(0);
                    }
                }
            } catch (Exception e) {
                logWarn("Deferred recordings cleanup error: " + e.getMessage());
                deferredCleanupDirs.add(DEFERRED_RECORDINGS);
            }
        }
        if (toRun.contains(DEFERRED_SURVEILLANCE)) {
            try {
                synchronized (cleanupLock) {
                    if (getSurveillanceSize() > surveillanceLimitMb * 1024 * 1024) {
                        ensureSurveillanceSpace(0);
                    }
                }
            } catch (Exception e) {
                logWarn("Deferred surveillance cleanup error: " + e.getMessage());
                deferredCleanupDirs.add(DEFERRED_SURVEILLANCE);
            }
        }
        if (toRun.contains(DEFERRED_PROXIMITY)) {
            try {
                synchronized (cleanupLock) {
                    if (getProximitySize() > proximityLimitMb * 1024 * 1024) {
                        ensureProximitySpace(0);
                    }
                }
            } catch (Exception e) {
                logWarn("Deferred proximity cleanup error: " + e.getMessage());
                deferredCleanupDirs.add(DEFERRED_PROXIMITY);
            }
        }
        if (toRun.contains(DEFERRED_TRIPS)) {
            try {
                synchronized (cleanupLock) {
                    if (getTripsSize() > tripsLimitMb * 1024 * 1024) {
                        ensureTripsSpace(0);
                    }
                }
            } catch (Exception e) {
                logWarn("Deferred trips cleanup error: " + e.getMessage());
                deferredCleanupDirs.add(DEFERRED_TRIPS);
            }
        }
    }

    /**
     * Stop periodic cleanup.
     */
    public void stopPeriodicCleanup() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
            try {
                if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupScheduler.shutdownNow();
            }
            cleanupScheduler = null;
            logInfo("Stopped periodic storage cleanup");
        }
    }

    /**
     * Start SD card / USB mount watchdog for sentry mode.
     * Periodically checks if the configured external volume(s) are still
     * mounted and re-mounts them if the system unmounted them (BYD/Android
     * tends to unmount SD when ACC is off; USB drops on bus glitches).
     *
     * Call this when entering sentry mode with an external volume selected.
     * The single watchdog now covers BOTH SD and USB so a USB-only config
     * doesn't go un-watched and silently fall back to internal forever.
     */
    public void startSdCardWatchdog() {
        // Start watchdog if ANY storage type uses SD or USB (not just surveillance).
        // The watchdog keeps the external volume mounted so recordings, events,
        // and trips remain accessible via the HTTP server even when surveillance
        // is suppressed.
        boolean anyOnSd  = surveillanceStorageType == StorageType.SD_CARD ||
                          recordingsStorageType   == StorageType.SD_CARD ||
                          tripsStorageType        == StorageType.SD_CARD;
        boolean anyOnUsb = surveillanceStorageType == StorageType.USB ||
                          recordingsStorageType   == StorageType.USB ||
                          tripsStorageType        == StorageType.USB;
        if (!anyOnSd && !anyOnUsb) {
            logDebug("Volume watchdog not needed - no storage type uses SD or USB");
            return;
        }

        stopSdCardWatchdog();  // Stop any existing watchdog first

        final boolean watchSd = anyOnSd;
        final boolean watchUsb = anyOnUsb;

        sdCardWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VolumeWatchdog");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);  // Normal priority - mount is critical
            return t;
        });

        sdCardWatchdog.scheduleAtFixedRate(() -> {
            try {
                // Use the cheap layered check (StatFs + canWrite, no shell
                // fork) for the watchdog tick. The expensive `touch+rm`
                // probe in isSdCardMounted() falsely reports unmounted under
                // FUSE binder contention from concurrent dir-walks, kicking
                // off a remount cascade that itself spawns more shell forks
                // and amplifies the contention. The cheap check has zero
                // such side effects.
                //
                // Two-strikes rule: a single negative reading is treated as
                // a transient probe failure. Only after TWO consecutive
                // failures do we fire the remount path. This eliminates
                // the false-positive "card unmounted" log that fires after
                // every UI settings save (when the page reflexively walks
                // the SD via /api/storage/external + /api/recordings/stats).
                if (watchSd && !isSdCardLikelyMounted()) {
                    sdWatchdogConsecutiveFailures++;

                    // First failure: silent, just record and wait for next tick.
                    if (sdWatchdogConsecutiveFailures < 2) {
                        return;
                    }

                    // Only log verbosely for the first few failures, then quiet down
                    boolean shouldLog = sdWatchdogConsecutiveFailures <= SD_WATCHDOG_MAX_VERBOSE_FAILURES ||
                                        sdWatchdogConsecutiveFailures % SD_WATCHDOG_QUIET_LOG_INTERVAL == 0;

                    if (shouldLog) {
                        logWarn("SD card watchdog: card unmounted, attempting remount... (attempt #" +
                            sdWatchdogConsecutiveFailures + ")");
                    }

                    if (ensureSdCardMounted(true)) {
                        logInfo("SD card watchdog: remounted successfully after " +
                            sdWatchdogConsecutiveFailures + " attempts");
                        sdWatchdogConsecutiveFailures = 0;

                        // Restore SD card directories now that card is back
                        initSdCardDirectories();
                        updateActiveDirectories();

                        // Update running sentry engine's output directory
                        try {
                            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                            if (pipeline != null && pipeline.getSentry() != null) {
                                pipeline.getSentry().setEventOutputDir(getSurveillanceDir());
                                logInfo("SD card watchdog: updated sentry output dir to " +
                                    getSurveillanceDir().getAbsolutePath());
                            }
                        } catch (Exception e) {
                            logWarn("SD card watchdog: could not update sentry dir: " + e.getMessage());
                        }
                    } else if (shouldLog) {
                        logError("SD card watchdog: remount FAILED - surveillance may use internal fallback");
                    }
                } else if (watchSd) {
                    // Card is healthy — reset failure counter (was a single
                    // transient probe failure, not a real unmount).
                    if (sdWatchdogConsecutiveFailures > 0) {
                        if (sdWatchdogConsecutiveFailures >= 2) {
                            logInfo("SD card watchdog: card is mounted again");
                        }
                        sdWatchdogConsecutiveFailures = 0;
                    }
                }

                // USB watchdog branch — independent state machine but shares
                // the schedule. Per user spec: USB-only configs must also fall
                // back to internal transparently when the stick disappears
                // mid-recording, but ALSO get a remount attempt when the
                // bus settles. Without this branch a USB-only surveillance
                // config that loses its drive stays on internal forever.
                if (watchUsb && !isUsbMounted()) {
                    usbWatchdogConsecutiveFailures++;
                    boolean shouldLogUsb = usbWatchdogConsecutiveFailures <= SD_WATCHDOG_MAX_VERBOSE_FAILURES ||
                                           usbWatchdogConsecutiveFailures % SD_WATCHDOG_QUIET_LOG_INTERVAL == 0;
                    if (shouldLogUsb) {
                        logWarn("USB watchdog: drive unmounted, attempting remount... (attempt #" +
                            usbWatchdogConsecutiveFailures + ")");
                    }
                    if (ensureUsbMounted(true)) {
                        logInfo("USB watchdog: remounted successfully after " +
                            usbWatchdogConsecutiveFailures + " attempts");
                        usbWatchdogConsecutiveFailures = 0;
                        initUsbDirectories();
                        updateActiveDirectories();
                        try {
                            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                            if (pipeline != null && pipeline.getSentry() != null
                                    && surveillanceStorageType == StorageType.USB) {
                                pipeline.getSentry().setEventOutputDir(getSurveillanceDir());
                                logInfo("USB watchdog: updated sentry output dir to " +
                                    getSurveillanceDir().getAbsolutePath());
                            }
                        } catch (Exception e) {
                            logWarn("USB watchdog: could not update sentry dir: " + e.getMessage());
                        }
                    } else if (shouldLogUsb) {
                        logError("USB watchdog: remount FAILED - surveillance may use internal fallback");
                    }
                } else if (watchUsb) {
                    if (usbWatchdogConsecutiveFailures > 0) {
                        logInfo("USB watchdog: drive is mounted again");
                        usbWatchdogConsecutiveFailures = 0;
                    }
                }
            } catch (Exception e) {
                logWarn("Volume watchdog error: " + e.getMessage());
            }
        }, SD_WATCHDOG_INTERVAL_SECONDS, SD_WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);

        logInfo("Started volume mount watchdog (interval=" + SD_WATCHDOG_INTERVAL_SECONDS +
            "s, sd=" + watchSd + ", usb=" + watchUsb + ")");
    }

    /**
     * Stop SD card mount watchdog (call when exiting sentry mode or ACC comes back on).
     */
    public void stopSdCardWatchdog() {
        if (sdCardWatchdog != null) {
            sdCardWatchdog.shutdown();
            try {
                if (!sdCardWatchdog.awaitTermination(3, TimeUnit.SECONDS)) {
                    sdCardWatchdog.shutdownNow();
                }
            } catch (InterruptedException e) {
                sdCardWatchdog.shutdownNow();
            }
            sdCardWatchdog = null;
            logInfo("Stopped SD card mount watchdog");
        }
    }
    
    /**
     * Set recording active state. Periodic cleanup runs continuously regardless
     * (started at daemon boot via {@link #startPeriodicCleanup()}); this flag
     * is kept for callers that may consult {@link #isRecordingActive()}.
     */
    public void setRecordingActive(boolean active) {
        recordingActive.set(active);
    }

    /**
     * Wires the authoritative "encoder is currently writing" probe used by
     * the cleanup gate. Should point at HardwareEventRecorderGpu.isWritingToFile.
     * Pipeline init wires this once; release-and-reinit cycles can re-wire.
     * Passing null reverts to the default (always false → cleanup never blocked).
     */
    public void setEncoderWritingProbe(java.util.function.BooleanSupplier probe) {
        this.encoderWritingProbe = probe != null ? probe : () -> false;
        if (probe != null) {
            probeWired.set(true);
        }
    }

    /**
     * True when the encoder is actively writing packets to disk. The cleanup
     * paths (post-save, periodic, sidecar) consult this before running
     * destructive deletes; if true, the cleanup is deferred to the deferred
     * queue and drained on the next non-recording pass.
     *
     * Cheap (volatile read) — safe to call from any thread, every iteration.
     */
    private boolean isEncoderWriting() {
        try {
            return encoderWritingProbe.getAsBoolean();
        } catch (Exception e) {
            // A buggy probe must never block cleanup forever — fail open on
            // recoverable exceptions. Errors (OOM, StackOverflow, LinkageError)
            // propagate; "treat the JVM as healthy and run a delete burst" is
            // the wrong default response to a process that's already broken.
            return false;
        }
    }

    /**
     * Set surveillance active state. See {@link #setRecordingActive(boolean)}
     * for periodic-cleanup lifetime semantics.
     */
    public void setSurveillanceActive(boolean active) {
        surveillanceActive.set(active);
    }

    /**
     * Mark a trip telemetry file as in-flight so {@link #ensureSpace} skips
     * it during cleanup. The recorder still writes through a buffered
     * GZIPOutputStream; if cleanup were to delete and unlink the file mid-write
     * on Linux, subsequent writes go to a still-open fd whose bytes are lost
     * once close() runs (the inode is reaped at fd-close, not at unlink).
     *
     * Pass {@code null} on stop. Path-based rather than a boolean so older
     * trip files can still be reaped during an active trip.
     */
    public void setActiveTripFile(File file) {
        activeTripFilePath = (file != null) ? file.getAbsolutePath() : null;
    }
    
    /**
     * Check if recording is active.
     */
    public boolean isRecordingActive() {
        return recordingActive.get();
    }
    
    /**
     * Check if surveillance is active.
     */
    public boolean isSurveillanceActive() {
        return surveillanceActive.get();
    }
    
    /**
     * Wipes every media file (and JSON sidecars) for the given category from
     * all known storage locations — active dir, internal fallback, and SD-card
     * mirror — plus thumbnails for that category.
     *
     * Used by the user-initiated "Reset Data" feature. Holds {@link #cleanupLock}
     * so it cannot race with periodic cleanup or any in-flight delete.
     *
     * @param category one of "recordings", "surveillance", "proximity", "trips"
     * @return number of files deleted, or -1 on unknown category
     */
    public long wipeMediaCategory(String category) {
        if (category == null) return -1;
        List<File> dirs;
        switch (category) {
            case "recordings":  dirs = getAllRecordingsDirs(); break;
            case "surveillance": dirs = getAllSurveillanceDirs(); break;
            case "proximity":   dirs = getAllProximityDirs(); break;
            case "trips":       dirs = getAllTripsDirs(); break;
            default: return -1;
        }

        long deleted = 0;
        synchronized (cleanupLock) {
            for (File dir : dirs) {
                if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
                File[] files = dir.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    if (f.isFile() && f.delete()) deleted++;
                }
            }

            // Best-effort thumbnail cleanup. Thumbnails live alongside the
            // active dir's parent in a "thumbs" subfolder; nuking the whole
            // dir would also kill any other category's thumbs, so we limit
            // to those derived from the just-wiped filenames. Cheaper to
            // just blow away the whole thumbs dir on a media wipe.
            try {
                File baseDir = (dirs.isEmpty() || dirs.get(0).getParentFile() == null)
                    ? null : dirs.get(0).getParentFile();
                if (baseDir != null) {
                    File thumbs = new File(baseDir, "thumbs");
                    if (thumbs.exists() && thumbs.isDirectory()) {
                        File[] thumbFiles = thumbs.listFiles();
                        if (thumbFiles != null) {
                            for (File t : thumbFiles) if (t.isFile()) t.delete();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        logInfo("wipeMediaCategory(" + category + ") deleted " + deleted + " files");
        return deleted;
    }

    /**
     * Shutdown all background threads.
     * Call this when the app is terminating.
     */
    public void shutdown() {
        stopPeriodicCleanup();
        stopSdCardWatchdog();
        
        asyncCleanupExecutor.shutdown();
        try {
            if (!asyncCleanupExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                asyncCleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncCleanupExecutor.shutdownNow();
        }
        
        logInfo("StorageManager shutdown complete");
    }
}
