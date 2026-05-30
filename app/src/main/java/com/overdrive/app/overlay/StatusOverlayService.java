package com.overdrive.app.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.overdrive.app.R;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Floating status overlay service.
 *
 * Shows a small draggable pill on top of all apps indicating whether
 * configured features are actually running or not.
 *
 * Rules:
 * - Only shows items that are CONFIGURED (recording mode != NONE, trip analytics enabled)
 * - Each item shows a tinted active/inactive icon next to its label
 * - Tapping a not-running item restarts it (it's configured, so it should be running)
 * - Hides entirely if nothing is configured
 * - Gracefully handles missing SYSTEM_ALERT_WINDOW — just stops itself
 */
public class StatusOverlayService extends Service {

    private static final String TAG = "StatusOverlay";
    private static final String CHANNEL_ID = "status_overlay";
    private static final int NOTIFICATION_ID = 9001;
    private static final long POLL_INTERVAL_MS = 3000;
    private static final long POLL_INTERVAL_ACC_OFF_MS = 10000; // Slower polling when ACC is off

    // Persisted overlay position
    private static final String PREFS_NAME = "status_overlay_prefs";
    private static final String PREF_POS_X = "pos_x";
    private static final String PREF_POS_Y = "pos_y";
    private static final int DEFAULT_POS_X = 20;
    private static final int DEFAULT_POS_Y = 100;

    private WindowManager windowManager;
    private View overlayView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Views
    private LinearLayout recContainer;
    private LinearLayout tripContainer;
    private LinearLayout micContainer;
    private ImageView ivRecIcon;
    private ImageView ivTripIcon;
    private ImageView ivMicIcon;
    private TextView tvRecLabel;
    private TextView tvTripLabel;
    private TextView tvMicLabel;

    // State
    private volatile String configuredMode = "NONE";
    private volatile boolean isRecording = false;
    private volatile boolean tripEnabled = false;
    private volatile boolean tripActive = false;
    private volatile boolean daemonReachable = false;
    private volatile String currentGear = "P";
    private volatile boolean accOn = false;
    // User-controlled audio toggle (recording.audioEnabled in unified config)
    private volatile boolean audioEnabledConfig = false;
    // The capture controller. Touched from the polling executor (reconcile)
    // and from the main thread (onDestroy) — must be volatile so the destroy
    // path observes any in-flight assignment from the executor.
    //
    // Note on idempotency: AppAudioCaptureController.stop() is synchronized
    // and short-circuits when not running, so the onDestroy stop() and any
    // racing reconcile stop() are safe to call independently. start() and
    // stop() are both synchronized on the controller so they serialize.
    private volatile com.overdrive.app.audio.AppAudioCaptureController audioController;

    // Edge-trigger fast-poll: when ACC flips OFF→ON, run the poll loop at 1s
    // for 30s so we minimize the audio-capture-start latency at trip start.
    // Without this, ACC turning on can take up to POLL_INTERVAL_ACC_OFF_MS
    // (10s) to detect, and a single mic-claim retry then adds another 5s of
    // back-off — together that's a 13s gap of silent audio at the very
    // moment the user begins driving.
    private volatile boolean previousAccOn = false;
    private volatile long fastPollUntilElapsedMs = 0;
    private static final long FAST_POLL_INTERVAL_MS = 1000;
    private static final long FAST_POLL_WINDOW_MS = 30_000;

    // Track the last AppAudioCaptureController.start() that returned false so
    // the MIC pill can paint RED with a "mic claimed / unavailable" hint.
    // start() back-off lasts ~5s; we hold the hint for up to 30s so the user
    // sees an explanation rather than a silent failure.
    private volatile long lastAudioStartFailureMs = 0;
    private static final long AUDIO_FAILURE_HINT_WINDOW_MS = 30_000;

    // Grace period: don't flicker the overlay on transient poll failures.
    // The daemon may be restarting, the HTTP server may be briefly busy, etc.
    // Only treat the daemon as truly gone after UNREACHABLE_THRESHOLD consecutive failures.
    private volatile int consecutivePollFailures = 0;
    private static final int UNREACHABLE_THRESHOLD = 3; // ~9 seconds at 3s poll interval
    // Track whether we ever had something to show (so we keep the window during blips)
    private volatile boolean hadContentBefore = false;

    // Drag support
    private float initialTouchX, initialTouchY;
    private int initialX, initialY;
    private boolean isDragging = false;
    private static final int DRAG_THRESHOLD = 10;
    private WindowManager.LayoutParams layoutParams;


    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startOverlayForeground();

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Don't create overlay window yet — wait for first poll to confirm
        // there's something to show. This avoids adding an empty overlay window
        // that can interfere with GPU rendering on BYD head units.
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        // Theme refresh — caller flipped the app's day/night setting.
        // Rebuild the overlay against the new uiMode. rebuildOverlay() also
        // re-fires pollStatus() so the pill repaints; return early so the
        // standard start path doesn't double-poll.
        if (intent != null && ACTION_REFRESH_THEME.equals(intent.getAction())) {
            Log.i(TAG, "ACTION_REFRESH_THEME — rebuilding overlay");
            rebuildOverlay();
            return START_STICKY;
        }
        if (!running.get()) {
            startPolling();
        } else {
            // Re-entry while we're already running: MainActivity is asking
            // us to refresh. Cancel any in-flight delayed poll and fire one
            // immediately so a stale "ACC=off, slow poll" loop doesn't keep
            // us hidden for up to POLL_INTERVAL_ACC_OFF_MS.
            handler.removeCallbacksAndMessages(null);
            pollStatus();
        }

        return START_STICKY;
    }

    /**
     * Enter the foreground with an explicit service type so the platform
     * treats us as a long-running special-use service. Without passing the
     * type on Android 14+, the system can terminate the process along with
     * the Activity task, which is what makes the pill disappear on app close.
     */
    private void startOverlayForeground() {
        Notification notification = buildNotification();
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground with type failed, falling back: " + e.getMessage());
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        // Order matters here:
        //  1. Flip running BEFORE we touch the executor or the controller.
        //     Any in-flight reconcile that's already on the executor reads
        //     running.get() (well, indirectly — its outer pollStatus did);
        //     more importantly, the next reschedule sees false and stops.
        //  2. Cancel any pending Handler callbacks so we don't enqueue
        //     another pollStatus after we've torn down.
        //  3. shutdownNow() interrupts the executor — but the executor may
        //     be MID-RECONCILE on the audio controller. AppAudioCaptureController
        //     is independently synchronized, and our onDestroy stop() below
        //     races with reconcile's stop() through that lock; whichever
        //     loses the race gets the early-return path inside stop().
        //  4. Stop the controller from the main thread. We can't ship this
        //     work to the executor because shutdownNow() drained it.
        //     stop() is documented (in AppAudioCaptureController) as fast —
        //     thread joins use a bounded wait inside cleanup().
        running.set(false);
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        com.overdrive.app.audio.AppAudioCaptureController ctrl = audioController;
        audioController = null;
        if (ctrl != null) {
            try { ctrl.stop(); } catch (Exception ignored) {}
        }
        removeOverlay();
        super.onDestroy();
    }

    /**
     * Called when the user swipes the app away from Recents.
     *
     * On many Android builds (including BYD head units running AOSP forks)
     * this triggers the service to be torn down alongside the activity task,
     * which makes the floating overlay disappear. Re-schedule ourselves so
     * the service (and the overlay window) survives the task being cleared.
     *
     * The re-launch uses an AlarmManager one-shot because Android restricts
     * starting foreground services directly from inside onTaskRemoved on
     * newer platform versions.
     */
    /**
     * Re-inflate the overlay when the device configuration changes (light ↔
     * dark, locale, font scale). Without this, the user toggling the app
     * theme leaves the overlay stuck on whatever palette it was created with
     * because the View tree was inflated once and is never re-resolved.
     *
     * We blow the view away and let the next pollStatus()/updateOverlay()
     * tick rebuild it; that path also re-binds icon tints so the active /
     * inactive states pick up the new status color tokens.
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (overlayView == null) return;
        Log.i(TAG, "Configuration changed — rebuilding overlay so theme tokens reapply");
        rebuildOverlay();
    }

    /**
     * Tear down + recreate the overlay so a theme change reaches the
     * resolved drawables and color tokens. Persists current position so
     * the new pill lands where the user last dragged it.
     */
    private void rebuildOverlay() {
        try {
            if (layoutParams != null) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(PREF_POS_X, layoutParams.x)
                        .putInt(PREF_POS_Y, layoutParams.y)
                        .apply();
            }
        } catch (Exception ignored) {}
        removeOverlay();
        handler.removeCallbacksAndMessages(null);
        pollStatus();
    }

    /**
     * Build a context whose resources honor the app's day/night override.
     *
     * Plain Service contexts read uiMode straight from the system config,
     * so AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_NO) doesn't reach
     * the overlay — the pill stays dark on a light-themed system. Mapping
     * the AppCompat mode onto Configuration.UI_MODE_NIGHT_* and creating a
     * configuration-context with that override fixes it.
     */
    private Context themedContext() {
        int mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
        int uiNight;
        if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
            uiNight = android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } else if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) {
            uiNight = android.content.res.Configuration.UI_MODE_NIGHT_NO;
        } else {
            // Follow-system / unspecified — leave the system's value alone.
            return this;
        }
        android.content.res.Configuration cfg = new android.content.res.Configuration(
                getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | uiNight;
        return createConfigurationContext(cfg);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "onTaskRemoved — scheduling overlay service restart");
        try {
            Intent restart = new Intent(getApplicationContext(), StatusOverlayService.class);
            restart.setPackage(getPackageName());
            int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getForegroundService(
                    getApplicationContext(), 1, restart, flags);
            android.app.AlarmManager am =
                    (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null && pi != null) {
                // 1s out so the current task-removal flow unwinds first.
                am.set(android.app.AlarmManager.ELAPSED_REALTIME,
                        android.os.SystemClock.elapsedRealtime() + 1000,
                        pi);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to schedule overlay restart: " + e.getMessage());
        }
        super.onTaskRemoved(rootIntent);
    }

    // ==================== OVERLAY ====================

    private void createOverlay() {
        if (overlayView != null) return; // Already created

        // Inflate against a context whose configuration honors the app's
        // chosen day/night setting. A bare Service runs against the system
        // configuration, so AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_NO)
        // wouldn't reach the overlay — the pill would stay dark on a
        // light-themed system because the Service never saw the override.
        // Wrapping with createConfigurationContext gives us a context whose
        // resources resolve light/dark drawables according to the user's
        // explicit choice.
        overlayView = LayoutInflater.from(themedContext()).inflate(
                R.layout.overlay_status, null);

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        // Restore last user-placed position (falls back to defaults on first run)
        android.content.SharedPreferences prefs =
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        layoutParams.x = prefs.getInt(PREF_POS_X, DEFAULT_POS_X);
        layoutParams.y = prefs.getInt(PREF_POS_Y, DEFAULT_POS_Y);

        bindViews();
        setupDrag();

        try {
            windowManager.addView(overlayView, layoutParams);
            Log.i(TAG, "Overlay window added");
        } catch (Exception e) {
            Log.e(TAG, "Failed to add overlay: " + e.getMessage());
            overlayView = null;
        }
    }

    private void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {}
            overlayView = null;
        }
    }

    private void bindViews() {
        recContainer = overlayView.findViewById(R.id.recContainer);
        tripContainer = overlayView.findViewById(R.id.tripContainer);
        micContainer = overlayView.findViewById(R.id.micContainer);
        ivRecIcon = overlayView.findViewById(R.id.ivRecIcon);
        ivTripIcon = overlayView.findViewById(R.id.ivTripIcon);
        ivMicIcon = overlayView.findViewById(R.id.ivMicIcon);
        tvRecLabel = overlayView.findViewById(R.id.tvRecLabel);
        tvTripLabel = overlayView.findViewById(R.id.tvTripLabel);
        tvMicLabel = overlayView.findViewById(R.id.tvMicLabel);

        // Tap on recording item → restart recording if it should be running but isn't
        recContainer.setOnClickListener(v -> {
            if (!isRecording && shouldRecordingBeActive()) {
                restartRecording();
            }
        });

        // Tap on trip item → restart trip detection if not running
        tripContainer.setOnClickListener(v -> {
            if (tripEnabled && !tripActive) {
                restartTripDetection();
            }
        });
    }

    private void setupDrag() {
        View pill = overlayView.findViewById(R.id.pillContainer);
        pill.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    initialX = layoutParams.x;
                    initialY = layoutParams.y;
                    isDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                        isDragging = true;
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + (int) dx;
                        layoutParams.y = initialY + (int) dy;
                        try {
                            windowManager.updateViewLayout(overlayView, layoutParams);
                        } catch (Exception ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        // Let child click handlers fire
                        return false;
                    }
                    // Persist the new position so it survives overlay recreation,
                    // service restarts, and reboots
                    try {
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putInt(PREF_POS_X, layoutParams.x)
                                .putInt(PREF_POS_Y, layoutParams.y)
                                .apply();
                    } catch (Exception ignored) {}
                    return true;
            }
            return false;
        });
    }

    // ==================== POLLING ====================

    private void startPolling() {
        running.set(true);
        pollStatus();
    }

    private void pollStatus() {
        if (!running.get()) return;

        executor.execute(() -> {
            try {
                JSONObject status = fetchStatus();
                if (status != null) {
                    daemonReachable = true;
                    consecutivePollFailures = 0;
                    parseStatus(status);
                } else {
                    consecutivePollFailures++;
                    if (consecutivePollFailures >= UNREACHABLE_THRESHOLD) {
                        daemonReachable = false;
                    }
                    // else: keep daemonReachable as-is (grace period)
                }
                // Detect ACC OFF→ON edge AFTER parseStatus() — it's the most
                // recent point at which we trust accOn. Arm fast-poll for 30s
                // so the audio controller restart (if it back-offs after a
                // mic claim) and the daemon's first-segment kickoff happen
                // within ~1s rather than ~10s of the user turning the key.
                boolean prevAcc = previousAccOn;
                previousAccOn = accOn;
                if (accOn && !prevAcc) {
                    fastPollUntilElapsedMs =
                        android.os.SystemClock.elapsedRealtime() + FAST_POLL_WINDOW_MS;
                    Log.i(TAG, "ACC OFF→ON edge — fast-polling for "
                            + FAST_POLL_WINDOW_MS + "ms");
                }
                refreshAudioConfig();
                reconcileAudioCapture();
                handler.post(this::updateUI);
            } catch (Exception e) {
                consecutivePollFailures++;
                if (consecutivePollFailures >= UNREACHABLE_THRESHOLD) {
                    daemonReachable = false;
                }
                // Even on poll failure, run reconcile so a stale capture
                // gets torn down when the daemon goes away.
                reconcileAudioCapture();
                handler.post(this::updateUI);
            }

            if (running.get()) {
                // Always reschedule. Detect ACC by VALUE on each poll, not by
                // edge — single-shot SCREEN_ON suspension was racy because
                // the ACC-on signal propagation (AccSentryDaemon → IPC →
                // RecordingModeManager) lags SCREEN_ON, so the first poll
                // after wake saw accOn=false and stranded us. Slow-poll
                // loopback to the in-process daemon HTTP is negligible.
                //
                // Fast-poll window: when armed by an ACC edge, run at 1s for
                // FAST_POLL_WINDOW_MS to catch the daemon-startup race tight.
                long now = android.os.SystemClock.elapsedRealtime();
                long interval;
                if (fastPollUntilElapsedMs > now) {
                    interval = FAST_POLL_INTERVAL_MS;
                } else {
                    interval = accOn ? POLL_INTERVAL_MS : POLL_INTERVAL_ACC_OFF_MS;
                }
                handler.postDelayed(this::pollStatus, interval);
            }
        });
    }

    private JSONObject fetchStatus() {
        HttpURLConnection conn = null;
        try {
            conn = com.overdrive.app.util.DaemonHttpClient.open(
                "/status", "GET", 2000, 2000);
            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                return new JSONObject(sb.toString());
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * Decide whether audio capture should be running right now and start /
     * stop AppAudioCaptureController accordingly.
     *
     * Capture is gated to ACC-on recording modes only:
     *   - audioEnabledConfig must be true
     *   - daemonReachable, accOn must be true
     *   - configuredMode in {CONTINUOUS, DRIVE_MODE, PROXIMITY_GUARD}
     *   - shouldRecordingBeActive() must be true (ie current gear matches)
     *
     * Surveillance mode (ACC OFF, sentry) deliberately does NOT enable
     * audio capture. That's a privacy-significant separation: audio
     * recording in the cabin while parked + driver gone is a much spicier
     * legal posture than audio while driving.
     *
     * Run on the polling executor (IO thread) — start() opens AudioRecord +
     * MediaCodec + a TCP socket which together can take 30-100 ms.
     */
    /**
     * Refresh audioEnabledConfig from UnifiedConfigManager. Read fresh on
     * every poll so the user toggling the recording.html switch reflects
     * within ~3s without a service restart.
     *
     * Defaults to false on read failure — better to silently NOT capture
     * audio than to silently DO capture audio when we can't confirm consent.
     */
    private void refreshAudioConfig() {
        try {
            // forceReload() rather than loadConfig() — the daemon (different
            // UID from the app) may have just rewritten the config file.
            // loadConfig()'s mtime cache is per-process; without the explicit
            // reload, the app-side cached snapshot can stay stale even after
            // the file has been replaced. forceReload drops the cache and
            // re-reads from disk.
            org.json.JSONObject recCfg =
                com.overdrive.app.config.UnifiedConfigManager.forceReload()
                    .optJSONObject("recording");
            audioEnabledConfig = recCfg != null
                && recCfg.optBoolean("audioEnabled", false);
        } catch (Exception e) {
            audioEnabledConfig = false;
        }
    }

    private void reconcileAudioCapture() {
        // "Trip is in progress" semantics — we keep the audio controller
        // alive across P↔D gear changes within a single ACC-on session.
        // Tearing down on each gear change (which the old shouldRecordingBeActive()
        // gate did) caused ~5s of silent audio at every D resume in city
        // traffic, because each restart hits AudioRecord open + a TCP
        // reconnect and the BYD voice asst can grab the mic during the gap.
        //
        // The daemon decides whether incoming AAC frames are muxed into the
        // current segment (it drops them when isWritingToFile == false), so
        // capturing while not-recording costs only the loopback TCP traffic
        // (~8 KB/s of AAC the daemon discards). This is what makes the
        // pre-record buffer feature work — the controller MUST be live so
        // there's audio history available when the daemon decides to record.
        boolean shouldCapture = audioEnabledConfig
            && daemonReachable
            && accOn
            && (configuredMode.equals("CONTINUOUS")
                || configuredMode.equals("DRIVE_MODE")
                || configuredMode.equals("PROXIMITY_GUARD"));

        // Source of truth for "is capture happening" is the controller
        // itself. We used to mirror it in an audioActive boolean, which
        // desynced after fast user toggles (off-poll calls stop, the next
        // on-poll's start hits back-off and returns false, but our flag
        // was already cleared) — losing the back-off-retry signal entirely.
        com.overdrive.app.audio.AppAudioCaptureController ctrl = audioController;
        boolean isCapturing = ctrl != null && ctrl.isRunning();

        if (shouldCapture && !isCapturing) {
            // Tear down any dead-but-not-stopped controller before
            // creating a new one. A worker thread that self-exited (drain
            // socket reset, encoder error, etc.) leaves running=false but
            // the controller's internal `started` CAS gate is still true,
            // so subsequent start() calls would silently reject. Calling
            // stop() resets that gate and releases the half-allocated
            // resources. stop() is idempotent + cheap when state is
            // already clean.
            if (ctrl != null) {
                ctrl.stop();
            }
            ctrl = new com.overdrive.app.audio.AppAudioCaptureController();
            audioController = ctrl;
            boolean ok = ctrl.start();
            if (ok) {
                Log.i(TAG, "Audio capture enabled (mode=" + configuredMode + ")");
                // Clear any stale failure hint so the MIC pill recovers
                // from RED to GREEN as soon as we get back in.
                lastAudioStartFailureMs = 0;
            } else {
                lastAudioStartFailureMs = android.os.SystemClock.elapsedRealtime();
                Log.w(TAG, "Audio capture start failed — will retry on next poll");
            }
        } else if (!shouldCapture && isCapturing) {
            // ctrl is non-null when isCapturing is true.
            ctrl.stop();
            Log.i(TAG, "Audio capture disabled");
        }
        // No third "self-stopped" branch needed: isRunning() is now the
        // source of truth, so the next poll naturally retries via the
        // shouldCapture && !isCapturing branch.
    }

    private void parseStatus(JSONObject status) {
        try {
            // New fields (from updated daemon)
            JSONObject recStatus = status.optJSONObject("recordingStatus");
            if (recStatus != null) {
                configuredMode = recStatus.optString("configuredMode", "NONE");
                isRecording = recStatus.optBoolean("isRecording", false);
                currentGear = recStatus.optString("gear", "P");
                accOn = recStatus.optBoolean("accOn", false);
            } else {
                // Fallback: old daemon without recordingStatus field
                // Use existing "recording" array (non-empty = recording) and "acc" field
                // We can't know the configured mode, so read it from the config file directly
                org.json.JSONArray recArray = status.optJSONArray("recording");
                isRecording = recArray != null && recArray.length() > 0;
                accOn = status.optBoolean("acc", false);
                
                // Read configured mode from UnifiedConfigManager.
                // forceReload() rather than loadConfig() — the daemon
                // (different UID from the app) writes this file; the
                // app-side mtime cache is per-process and can stay stale
                // across UID boundaries (see feedback_unified_config_force_reload).
                try {
                    JSONObject recording =
                        com.overdrive.app.config.UnifiedConfigManager.forceReload()
                            .optJSONObject("recording");
                    if (recording != null) {
                        configuredMode = recording.optString("mode", "NONE");
                    }
                } catch (Exception configErr) {
                    Log.w(TAG, "Config read fallback failed: " + configErr.getMessage());
                }
                
                // Gear: not available from old daemon status, default to non-P 
                // if ACC is on (assume driving since we can't know)
                currentGear = accOn ? "D" : "P";
            }

            JSONObject tripStatus = status.optJSONObject("tripStatus");
            if (tripStatus != null) {
                tripEnabled = tripStatus.optBoolean("enabled", false);
                tripActive = tripStatus.optBoolean("tripActive", false);
            } else {
                // Fallback: read trip config from UnifiedConfigManager.
                // forceReload() rather than loadConfig() — the daemon (different
                // UID) writes this file; the app-side mtime cache is per-process
                // and can stay stale across UID boundaries
                // (see feedback_unified_config_force_reload).
                try {
                    JSONObject tripCfg =
                        com.overdrive.app.config.UnifiedConfigManager.forceReload()
                            .optJSONObject("tripAnalytics");
                    if (tripCfg != null) {
                        tripEnabled = tripCfg.optBoolean("enabled", false);
                    }
                } catch (Exception configErr) {
                    Log.w(TAG, "Trip config read fallback failed: " + configErr.getMessage());
                }
                // Can't determine tripActive without daemon support — assume false
                tripActive = false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Parse error: " + e.getMessage());
        }
    }

    // ==================== UI ====================

    private void updateUI() {
        // User-facing visibility toggles. Stored in the unified config file
        // (/data/local/tmp/overdrive_config.json) rather than SharedPreferences
        // because both the app UID and the shell/daemon UID need to see the
        // same values. Read fresh on every poll so a flip in Settings reflects
        // without a service restart. Defaults to true so existing installs
        // (where the section doesn't exist yet) keep current behavior.
        boolean cameraOverlayEnabled = true;
        boolean tripOverlayEnabled = true;
        try {
            // forceReload() rather than loadConfig() — these toggles flip
            // from the web UI / Settings (daemon UID) and the app-side
            // mtime cache is per-process; without the explicit reload we
            // can keep showing/hiding chips based on a stale snapshot
            // (see feedback_unified_config_force_reload).
            JSONObject statusOverlayCfg =
                com.overdrive.app.config.UnifiedConfigManager.forceReload()
                    .optJSONObject("statusOverlay");
            if (statusOverlayCfg != null) {
                cameraOverlayEnabled = statusOverlayCfg.optBoolean("cameraVisible", true);
                tripOverlayEnabled = statusOverlayCfg.optBoolean("tripVisible", true);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read statusOverlay prefs: " + e.getMessage());
        }

        boolean recConfigured = !"NONE".equals(configuredMode) && !"UNKNOWN".equals(configuredMode);
        boolean anythingToShow = (recConfigured && cameraOverlayEnabled)
                || (tripEnabled && tripOverlayEnabled);

        Log.d(TAG, "updateUI: mode=" + configuredMode + " isRec=" + isRecording 
                + " gear=" + currentGear + " acc=" + accOn 
                + " tripEnabled=" + tripEnabled + " tripActive=" + tripActive
                + " recConfigured=" + recConfigured + " shouldRec=" + (recConfigured && shouldRecordingBeActive())
                + " pollFails=" + consecutivePollFailures);

        // During the grace period (daemon briefly unreachable), keep the overlay
        // visible with last-known state. This prevents the pill from flickering
        // every time the daemon restarts or a single HTTP poll times out.
        if (!daemonReachable) {
            if (hadContentBefore && consecutivePollFailures < UNREACHABLE_THRESHOLD * 2) {
                // Still in grace window — keep overlay as-is, don't touch it.
                // The stale data is better than a disappearing/reappearing pill.
                return;
            }
            // Sustained unreachability — hide (but don't destroy) the overlay.
            Log.d(TAG, "updateUI: daemon unreachable for " + consecutivePollFailures + " polls — hiding overlay");
            if (overlayView != null) overlayView.setVisibility(View.GONE);
            return;
        }

        if (!anythingToShow) {
            // If the user disabled both segments via Settings, fully tear
            // down the overlay window so we don't keep a hidden View
            // attached to WindowManager. A hidden TYPE_APPLICATION_OVERLAY
            // still consumes a surface on BYD head units.
            Log.d(TAG, "updateUI: nothing to show — removing overlay");
            removeOverlay();
            hadContentBefore = false;
            return;
        }

        // Hide overlay when ACC is off — car is parked, no need to show status.
        // We keep polling (at a slower rate) so we can show it again when ACC turns on.
        if (!accOn) {
            if (overlayView != null) overlayView.setVisibility(View.GONE);
            return;
        }
        
        // Determine what's visible before creating the window.
        // Proximity guard should stay visible even when idle/armed (waiting for
        // a radar trigger) — hiding it would make users think the feature is off.
        boolean isProximityMode = "PROXIMITY_GUARD".equals(configuredMode);
        boolean shouldShowRec = recConfigured && cameraOverlayEnabled
                && (isRecording || shouldRecordingBeActive() || isProximityMode);
        boolean shouldShowTrip = tripEnabled && tripOverlayEnabled;
        // Mic visibility piggybacks on REC visibility. Show whenever audio
        // is armed (configured + recording mode set) so the "armed/idle"
        // amber state remains visible during P-gear standby — the user
        // wants to know audio capture is poised to fire even when REC
        // isn't currently recording. When mic is armed but REC is in
        // standby, we keep the overlay visible specifically for the mic.
        boolean shouldShowMic = audioEnabledConfig && recConfigured && cameraOverlayEnabled;

        if (!shouldShowRec && !shouldShowTrip && !shouldShowMic) {
            // Configured but conditions don't require display (e.g., drive mode in P)
            if (overlayView != null) overlayView.setVisibility(View.GONE);
            return;
        }

        // After config-merge gating, double-check: if BOTH user segments are
        // toggled off, fully remove the window rather than leaving an empty
        // shell attached. (anythingToShow guarded the entry, but reaching
        // here with both flags off means a partial config state — be safe.)
        if (!cameraOverlayEnabled && !tripOverlayEnabled) {
            removeOverlay();
            return;
        }
        
        hadContentBefore = true;
        
        // We have something to show — create overlay window if not yet created
        createOverlay();
        if (overlayView == null) return;
        
        overlayView.setVisibility(View.VISIBLE);

        // Recording: show only if configured AND user hasn't toggled the
        // camera segment off in Settings → Status overlay.
        if (recConfigured && cameraOverlayEnabled) {
            recContainer.setVisibility(View.VISIBLE);

            // Determine if recording SHOULD be happening right now given mode + gear + ACC
            boolean shouldBeRecording = shouldRecordingBeActive();
            boolean isProximity = "PROXIMITY_GUARD".equals(configuredMode);

            if (isRecording) {
                // All good — recording as expected.
                // Proximity mode uses an amber tint + "PROX" label so users can
                // tell at a glance that this is radar-triggered recording,
                // not continuous/drive recording.
                if (isProximity) {
                    ivRecIcon.setImageResource(R.drawable.ic_overlay_rec_active);
                    tvRecLabel.setText("PROX");
                    tvRecLabel.setTextColor(getColor(R.color.status_warning));
                } else {
                    ivRecIcon.setImageResource(R.drawable.ic_overlay_rec_active);
                    tvRecLabel.setText("REC");
                    tvRecLabel.setTextColor(getColor(R.color.status_success));
                }
            } else if (shouldBeRecording) {
                // Problem — should be recording but isn't
                if (isProximity) {
                    ivRecIcon.setImageResource(R.drawable.ic_overlay_rec_inactive);
                    tvRecLabel.setText("PROX");
                    tvRecLabel.setTextColor(getColor(R.color.status_danger));
                } else {
                    ivRecIcon.setImageResource(R.drawable.ic_overlay_rec_inactive);
                    tvRecLabel.setText("REC");
                    tvRecLabel.setTextColor(getColor(R.color.status_danger));
                }
            } else if (isProximity) {
                // Proximity guard is armed but not currently recording (no radar trigger).
                // Show an armed/idle indicator instead of hiding — users want to know
                // the car is being watched even when nothing has triggered yet.
                ivRecIcon.setImageResource(R.drawable.ic_overlay_rec_inactive);
                tvRecLabel.setText("PROX");
                tvRecLabel.setTextColor(getColor(R.color.status_warning));
            } else {
                // Not recording, but that's expected (e.g., drive mode in P gear)
                // Hide the recording indicator since conditions don't require it
                recContainer.setVisibility(View.GONE);
            }
        } else {
            recContainer.setVisibility(View.GONE);
        }

        // Mic: show only when audio recording is configured AND a recording
        // mode that consumes audio is configured. Visible together with the
        // REC pill so the user can tell at a glance "video AND audio are
        // being captured" vs "video only". Tri-state color logic uses
        // BOTH the live audio capture state and the daemon's isRecording
        // flag so users distinguish capturing-but-not-muxing from off:
        //   - status_success (green): audio is being captured AND the
        //     daemon is currently muxing it into a segment.
        //   - status_warning (amber): audio is being captured but the
        //     daemon isn't muxing yet — pre-record buffer is filling /
        //     PROXIMITY_GUARD is armed waiting for a trigger / segment
        //     rotation in flight. Privacy-significant: this is the state
        //     where the cabin mic is open but no clip is being saved.
        //   - status_danger (red): we WANT to be capturing but the
        //     controller failed (mic claimed by BT/voice asst, etc).
        //     Held for AUDIO_FAILURE_HINT_WINDOW_MS so the user sees the
        //     reason their clip is silent rather than a flickering RED.
        boolean micVisibleByConfig = audioEnabledConfig && recConfigured && cameraOverlayEnabled;
        if (micVisibleByConfig) {
            micContainer.setVisibility(View.VISIBLE);
            // Read the live controller state directly — the polling thread
            // and the UI thread both see the volatile reference, and
            // isRunning() is itself thread-safe (AtomicBoolean.get).
            com.overdrive.app.audio.AppAudioCaptureController ctrl = audioController;
            boolean isCapturing = ctrl != null && ctrl.isRunning();
            // Mode says we should be capturing right now (see reconcileAudioCapture).
            boolean wantCapture = audioEnabledConfig && daemonReachable && accOn
                && (configuredMode.equals("CONTINUOUS")
                    || configuredMode.equals("DRIVE_MODE")
                    || configuredMode.equals("PROXIMITY_GUARD"));
            long now = android.os.SystemClock.elapsedRealtime();
            boolean recentFailure = lastAudioStartFailureMs > 0
                && (now - lastAudioStartFailureMs) < AUDIO_FAILURE_HINT_WINDOW_MS;
            if (isCapturing && isRecording) {
                ivMicIcon.setImageResource(R.drawable.ic_overlay_mic_active);
                tvMicLabel.setText(R.string.overlay_mic_inactive_label);
                tvMicLabel.setTextColor(getColor(R.color.status_success));
            } else if (wantCapture && !isCapturing && recentFailure) {
                // Mic claimed / capture failure recently. RED so the user
                // knows their clip is silent and the app didn't just
                // forget to record.
                ivMicIcon.setImageResource(R.drawable.ic_overlay_mic_inactive);
                tvMicLabel.setText(R.string.overlay_mic_inactive_label);
                tvMicLabel.setTextColor(getColor(R.color.status_danger));
            } else {
                // Capturing but daemon isn't muxing yet, OR not capturing
                // because mode/conditions don't require it (PROX armed in
                // P, fresh ACC-on, segment rotation). Amber for "armed".
                ivMicIcon.setImageResource(R.drawable.ic_overlay_mic_inactive);
                tvMicLabel.setText(R.string.overlay_mic_inactive_label);
                tvMicLabel.setTextColor(getColor(R.color.status_warning));
            }
        } else {
            micContainer.setVisibility(View.GONE);
        }

        // Trip: show only if enabled in config AND user hasn't toggled the
        // trip segment off in Settings → Status overlay.
        if (tripEnabled && tripOverlayEnabled) {
            tripContainer.setVisibility(View.VISIBLE);
            if (tripActive) {
                ivTripIcon.setImageResource(R.drawable.ic_overlay_trip_active);
                tvTripLabel.setText("TRIP");
                tvTripLabel.setTextColor(getColor(R.color.status_success));
            } else {
                ivTripIcon.setImageResource(R.drawable.ic_overlay_trip_inactive);
                tvTripLabel.setText("TRIP");
                tvTripLabel.setTextColor(getColor(R.color.status_danger));
            }
        } else {
            tripContainer.setVisibility(View.GONE);
        }

        // Show/hide the two separators based on which segments are visible.
        // Layout order: REC | sep1 | MIC | sep2 | TRIP. A separator is
        // visible iff there is at least one visible segment on each side.
        View separatorRecMic = overlayView.findViewById(R.id.separatorRecMic);
        View separator = overlayView.findViewById(R.id.separator);
        boolean recVisible = recContainer.getVisibility() == View.VISIBLE;
        boolean micVisible = micContainer.getVisibility() == View.VISIBLE;
        boolean tripVisible = tripContainer.getVisibility() == View.VISIBLE;
        if (separatorRecMic != null) {
            // sep1 sits between REC and MIC — visible only when both sides
            // have something to show.
            separatorRecMic.setVisibility(
                recVisible && micVisible ? View.VISIBLE : View.GONE);
        }
        if (separator != null) {
            // sep2 sits between (REC|MIC) and TRIP — visible iff trip is
            // visible AND at least one of REC/MIC is visible.
            boolean leftSideVisible = recVisible || micVisible;
            separator.setVisibility(
                leftSideVisible && tripVisible ? View.VISIBLE : View.GONE);
        }
    }

    // ==================== RESTART ACTIONS ====================

    /**
     * Determine if recording SHOULD be active right now based on mode, gear, and ACC state.
     * 
     * Rules (from RecordingModeManager):
     * - CONTINUOUS: should record whenever ACC is ON
     * - DRIVE_MODE: should record in driving gears (D, R, S, M) when ACC is ON
     * - PROXIMITY_GUARD: should be active in all gears except P when ACC is ON
     */
    private boolean shouldRecordingBeActive() {
        if (!accOn) return false;
        
        switch (configuredMode) {
            case "CONTINUOUS":
                return true;
            case "DRIVE_MODE":
                return isDrivingGear(currentGear);
            case "PROXIMITY_GUARD":
                return !"P".equals(currentGear);
            default:
                return false;
        }
    }
    
    /**
     * Check if gear is a driving gear (D, R, S, M, N — not P).
     * N is included because BYD Auto Hold reports N while stopped at traffic lights.
     */
    private static boolean isDrivingGear(String gear) {
        return "D".equals(gear) || "R".equals(gear) || "S".equals(gear) || "M".equals(gear) || "N".equals(gear);
    }

    /**
     * Restart recording by re-sending the configured mode via TCP.
     * This just re-triggers what's already configured — no config change.
     */
    private void restartRecording() {
        executor.execute(() -> {
            java.net.Socket socket = null;
            try {
                socket = new java.net.Socket("127.0.0.1", 19876);
                socket.setSoTimeout(3000);

                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "setRecordingMode");
                cmd.put("mode", configuredMode);

                java.io.OutputStream os = socket.getOutputStream();
                os.write((cmd.toString() + "\n").getBytes());
                os.flush();

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                String response = reader.readLine();
                Log.i(TAG, "Restart recording (" + configuredMode + "): " + response);
            } catch (Exception e) {
                Log.e(TAG, "Restart recording failed: " + e.getMessage());
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    /**
     * Restart trip detection by toggling the config off then on.
     * This re-initializes the TripDetector without changing user settings.
     */
    private void restartTripDetection() {
        executor.execute(() -> {
            try {
                // Toggle off then on to force re-init
                postTripConfig(false);
                Thread.sleep(500);
                postTripConfig(true);
                Log.i(TAG, "Restart trip detection: toggled");
            } catch (Exception e) {
                Log.e(TAG, "Restart trip failed: " + e.getMessage());
            }
        });
    }

    private void postTripConfig(boolean enabled) {
        HttpURLConnection conn = null;
        try {
            conn = com.overdrive.app.util.DaemonHttpClient.open(
                "/api/trips/config", "POST", 2000, 2000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            JSONObject body = new JSONObject();
            body.put("enabled", enabled);
            conn.getOutputStream().write(body.toString().getBytes());
            conn.getOutputStream().flush();
            conn.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== NOTIFICATION ====================

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Status Overlay", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Recording and trip status overlay");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.status_overlay_notif_title))
                .setContentText(getString(R.string.status_overlay_notif_text))
                .setSmallIcon(R.drawable.ic_recording)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ==================== STATIC HELPERS ====================

    public static boolean hasOverlayPermission(Context context) {
        boolean has = Settings.canDrawOverlays(context);
        Log.i(TAG, "hasOverlayPermission: " + has);
        return has;
    }

    public static boolean startIfPermitted(Context context) {
        if (!hasOverlayPermission(context)) {
            Log.w(TAG, "startIfPermitted: NO overlay permission — service not started");
            return false;
        }
        Log.i(TAG, "startIfPermitted: permission OK — starting service");
        Intent intent = new Intent(context, StatusOverlayService.class);
        context.startForegroundService(intent);
        return true;
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, StatusOverlayService.class));
    }

    /**
     * Trigger a re-inflation of the overlay so a freshly-changed theme
     * takes effect immediately. AppCompatDelegate.setDefaultNightMode()
     * fires onConfigurationChanged for foreground Activities but NOT for
     * plain Services, so the overlay would otherwise stay on its old
     * palette until the system config changed for unrelated reasons.
     *
     * No-op when overlay permission is missing or the service isn't
     * running — startForegroundService would just respawn an unwanted
     * pill in that case.
     */
    public static void refreshTheme(Context context) {
        if (!hasOverlayPermission(context)) return;
        Intent intent = new Intent(context, StatusOverlayService.class);
        intent.setAction(ACTION_REFRESH_THEME);
        try {
            context.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "refreshTheme failed: " + e.getMessage());
        }
    }

    public static final String ACTION_REFRESH_THEME =
            "com.overdrive.app.overlay.REFRESH_THEME";
}
