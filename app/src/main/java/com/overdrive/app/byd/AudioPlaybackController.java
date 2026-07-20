package com.overdrive.app.byd;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;

/**
 * Plays a user-provided audio/video file (MP3 / WAV / MP4 / …) for the "Play
 * Audio" / "Play Video" automation + key-mapping actions.
 *
 * <p><b>Why this delegates to the app process.</b> This class runs inside the
 * {@code app_process} daemon (UID 2000, synthetic {@code PermissionBypassContext}).
 * A {@link android.media.MediaPlayer} created there cannot play: on this firmware
 * {@code prepare()} fails immediately with {@code status=0x80000000} (media-framework
 * UNKNOWN_ERROR) — the media extractor / mediaserver does not service the headless
 * daemon process, so preparation dies before any track exists. This was confirmed on
 * device: {@code ensureAudible} and {@code requestAudioFocus} both SUCCEED, then
 * {@code play: setup failed: Prepare failed.: status=0x80000000} on every attempt.
 * (The daemon <i>can</i> set volume — that's a privileged Binder settings call, not a
 * MediaPlayer track — which is why volume worked while playback never did.)
 *
 * <p>So playback runs in the REAL app process, where a framework MediaPlayer prepares
 * normally. The daemon reaches it with the SAME proven bridge it already uses for the
 * RoadSense IMU / Location sidecars and the Screen Deterrent: a shell
 * {@code am start-foreground-service} / {@code am start} exec against an exported
 * component (the daemon's synthetic context cannot {@code startForegroundService}
 * cross-process — that is a silent no-op).
 *
 * <ul>
 *   <li><b>Audio</b> → {@code MediaPlaybackService} (app-process foreground service).</li>
 *   <li><b>Video</b> (picture on screen) → {@code VideoPlaybackActivity} (app-process
 *       fullscreen player) — no daemon-owned SurfaceControl needed.</li>
 *   <li><b>Stop</b> → stop the service + broadcast a stop the video activity honours.</li>
 * </ul>
 *
 * <p><b>File transport.</b> Library sounds live under {@code /data/local/tmp/.overdrive/audio},
 * which the app UID (SELinux {@code untrusted_app}) cannot read directly — the locale /
 * device-id managers document the same cross-UID wall, and the app already reads daemon
 * files there only via a shell exec. So for a library file we pass its NAME and the app
 * streams the bytes from the daemon's authenticated {@code /api/audio/library/raw}
 * endpoint (the model the recordings player uses). An explicit {@code /storage} path
 * (the advanced escape hatch) is handed to the app as a path — the app CAN read shared
 * external storage directly.
 */
public final class AudioPlaybackController {

    private static final String TAG = "AudioPlayback";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Library dir whose files the app can't read directly (mirror of AudioApiHandler
    // / VehicleControlApiHandler). A path under here → stream by name; anything else
    // (e.g. /storage/emulated/0/Music/x.mp3) → the app opens it directly.
    private static final String AUDIO_LIBRARY_DIR = "/data/local/tmp/.overdrive/audio";

    // Exported app-process components (see AndroidManifest). Our own package — the same
    // literal the Screen Deterrent / sidecars use in their `am` execs.
    private static final String AUDIO_SERVICE =
            "com.overdrive.app/.services.MediaPlaybackService";
    private static final String VIDEO_ACTIVITY =
            "com.overdrive.app/.ui.VideoPlaybackActivity";
    /** Broadcast the audio service + video activity both stop on. */
    private static final String ACTION_STOP = "com.overdrive.app.action.STOP_MEDIA";
    private static final String PKG = "com.overdrive.app";

    private AudioPlaybackController() {}

    /** Audio only, no loop. Kept for callers that don't need loop/video. */
    public static boolean play(String path, String channel) {
        return play(path, channel, false);
    }

    /**
     * Play {@code path} on {@code channel} (audio only), optionally looping. Returns
     * true if the play command was dispatched to the app process (the app reports the
     * real prepare/play result to its own log); false only if the path is empty/invalid.
     * Any current playback is replaced app-side (single-player).
     */
    public static boolean play(String path, String channel, boolean loop) {
        return dispatchPlay(path, channel, loop, false);
    }

    /**
     * Play a video with its PICTURE on the head-unit screen (audio on {@code channel}).
     * Launches the app-process {@code VideoPlaybackActivity}; falls back to nothing
     * daemon-side (the activity self-manages). Returns true if dispatched.
     */
    public static boolean playVideoOnScreen(String path, String channel, boolean loop) {
        return dispatchPlay(path, channel, loop, true);
    }

    /**
     * Speak {@code text} aloud via TextToSpeech on {@code channel}. Like playback, TTS
     * cannot run in the headless daemon (no usable TTS service binding), so this
     * dispatches to the app-process {@link #AUDIO_SERVICE} via the same `am` bridge.
     */
    public static boolean speak(String text, String channel) {
        if (text == null || text.trim().isEmpty()) {
            logger.warn("speak: empty text");
            return false;
        }
        String ch = (channel == null || channel.trim().isEmpty()) ? "voice" : channel.trim();
        exec("am start-foreground-service -n " + AUDIO_SERVICE
                + " --es action speak"
                + " --es text " + q(text)
                + " --es channel " + q(ch));
        logger.info("speak: dispatched to MediaPlaybackService (channel=" + ch + ")");
        return true;
    }

    /** Stop any audio or video started by a play above. Idempotent. */
    public static void stop() {
        // Stop the audio service, and broadcast a stop the video activity honours.
        // Both are no-ops if the target isn't running.
        exec("am stopservice -n " + AUDIO_SERVICE);
        exec("am broadcast -a " + ACTION_STOP + " -p " + PKG);
        logger.info("stop: dispatched service stop + stop broadcast");
    }

    // ── internals ───────────────────────────────────────────────────────────

    /**
     * Validate the file and shell the appropriate app-process launch. The app can't
     * read the library dir, so a library file rides as a name (streamed from the raw
     * endpoint); an external-storage file rides as a path (the app reads it directly).
     */
    /**
     * Start {@link #VIDEO_ACTIVITY} via the daemon's real app Context (the proven
     * AppLauncher mechanism), passing the same extras the shell path would. Returns false
     * if the Context is unavailable or startActivity throws, so the caller falls back to
     * the shell {@code am start}. NEW_TASK is required because the daemon Context is not an
     * Activity; CLEAR_TASK so a repeat play replaces the current clip.
     */
    private static boolean startVideoViaContext(String libName, String filePath, String channel, boolean loop) {
        try {
            android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (ctx == null) return false;
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            i.setClassName(PKG, "com.overdrive.app.ui.VideoPlaybackActivity");
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (libName != null) i.putExtra("libName", libName);
            else if (filePath != null) i.putExtra("filePath", filePath);
            i.putExtra("channel", channel);
            i.putExtra("loop", loop);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            logger.warn("startVideoViaContext failed (" + t.getMessage() + ") — falling back to shell am start");
            return false;
        }
    }

    private static boolean dispatchPlay(String path, String channel, boolean loop, boolean onScreen) {
        if (path == null || path.trim().isEmpty()) {
            logger.warn("play: empty path");
            return false;
        }
        File f = new File(path.trim());
        String ch = (channel == null || channel.trim().isEmpty()) ? "media" : channel.trim();

        // Decide transport: library name (streamed) vs direct file path. Keep the two as
        // discrete values (libName XOR filePath) so both the Intent path and the shell
        // fallback below can use them.
        String libName = null, filePath = null;
        try {
            String canon = f.getCanonicalPath();
            if (canon.startsWith(AUDIO_LIBRARY_DIR)) {
                libName = f.getName();
            } else {
                // Direct path — must exist and be readable when the app opens it. We
                // don't stat here (daemon UID differs from app UID); the app validates.
                filePath = canon;
            }
        } catch (Exception e) {
            logger.warn("play: path resolve failed: " + e.getMessage());
            return false;
        }
        String srcArgs = (libName != null) ? ("--es libName " + q(libName)) : ("--es filePath " + q(filePath));

        if (onScreen) {
            // Launch the fullscreen video player. PRIMARY path: startActivity() via the
            // daemon's real app Context — the SAME proven mechanism AppLauncher uses for
            // "open app". A bare shell `am start` of an ACTIVITY from the parked UID-2000
            // daemon is subject to background-activity-start limits and was the reason
            // "Play Video did nothing" (the audio path works because it starts a foreground
            // SERVICE, which is not BAL-restricted). Starting from the app Context carries
            // the app's identity/standing so the activity actually surfaces. Falls back to
            // the shell `am start` only if the Context isn't available.
            if (startVideoViaContext(libName, filePath, ch, loop)) {
                logger.info("playVideoOnScreen: launched VideoPlaybackActivity via app Context (channel=" + ch + " loop=" + loop + ")");
                return true;
            }
            exec("am start --user 0 -n " + VIDEO_ACTIVITY
                    + " -a android.intent.action.VIEW"
                    + " --activity-new-task --activity-clear-task"
                    + " " + srcArgs
                    + " --es channel " + q(ch)
                    + " --ez loop " + loop);
            logger.info("playVideoOnScreen: dispatched to VideoPlaybackActivity via shell am start (channel=" + ch + " loop=" + loop + ")");
        } else {
            // Foreground audio service (same `am start-foreground-service` bridge as the sidecars).
            exec("am start-foreground-service -n " + AUDIO_SERVICE
                    + " --es action play"
                    + " " + srcArgs
                    + " --es channel " + q(ch)
                    + " --ez loop " + loop);
            logger.info("play: dispatched to MediaPlaybackService (channel=" + ch + " loop=" + loop + ")");
        }
        return true;
    }

    /**
     * Shell-quote one `am` extra value (filenames may contain spaces). Wrap in single
     * quotes and escape embedded single quotes the POSIX way ('\'').
     */
    private static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Fire-and-forget `am` exec, exactly like the RoadSense sidecar launch — no
     * waitFor, so a slow/hung `am` never stalls the HTTP-worker / keymap-fire thread.
     * The OS reaps the short-lived child; a failed launch is harmless.
     */
    private static void exec(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        } catch (Throwable t) {
            logger.warn("exec failed [" + cmd + "]: " + t.getMessage());
        }
    }
}
