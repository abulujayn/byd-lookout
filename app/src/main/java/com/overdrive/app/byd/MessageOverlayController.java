package com.overdrive.app.byd;

import com.overdrive.app.logging.DaemonLogger;

/**
 * Daemon-side bridge that shows an on-screen toast/dialog for the "Show Toast" /
 * "Show Dialog" automation + key-mapping actions.
 *
 * <p>The daemon (UID 2000, {@code app_process}) has no UI surface, so — exactly like the
 * Play Audio / Speak path in {@link AudioPlaybackController} — this shells
 * {@code am start-foreground-service} against the exported app-process
 * {@code MessageOverlayService}, which draws a {@code TYPE_APPLICATION_OVERLAY} window in
 * the real app process. Fire-and-forget (no waitFor) so a slow {@code am} never stalls the
 * HTTP-worker / keymap-fire thread.
 */
public final class MessageOverlayController {

    private static final String TAG = "MessageOverlay";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String PKG = "com.overdrive.app";
    private static final String SERVICE = "com.overdrive.app/.overlay.MessageOverlayService";
    private static final String ACTION_DISMISS = "com.overdrive.app.action.DISMISS_MESSAGE";

    private MessageOverlayController() {}

    /**
     * Show a toast (auto-dismissing pill). {@code duration} = short|long,
     * {@code position} = top|center|bottom, {@code severity} = info|warning|alert.
     */
    public static boolean showToast(String message, String duration, String position, String severity) {
        if (message == null || message.trim().isEmpty()) {
            logger.warn("showToast: empty message");
            return false;
        }
        exec("am start-foreground-service -n " + SERVICE
                + " --es kind toast"
                + " --es message " + q(message)
                + " --es duration " + q(orDefault(duration, "short"))
                + " --es position " + q(orDefault(position, "bottom"))
                + " --es severity " + q(orDefault(severity, "info")));
        logger.info("showToast: dispatched to MessageOverlayService");
        return true;
    }

    /**
     * Show a dialog (title + body + OK button) as a non-focus-stealing overlay.
     * {@code button} defaults to OK; {@code timeoutSec} > 0 auto-dismisses even without OK.
     */
    public static boolean showDialog(String title, String message, String button,
                                     String severity, int timeoutSec) {
        if ((title == null || title.trim().isEmpty())
                && (message == null || message.trim().isEmpty())) {
            logger.warn("showDialog: empty title+message");
            return false;
        }
        exec("am start-foreground-service -n " + SERVICE
                + " --es kind dialog"
                + " --es title " + q(orDefault(title, ""))
                + " --es message " + q(orDefault(message, ""))
                + " --es button " + q(orDefault(button, "OK"))
                + " --es severity " + q(orDefault(severity, "info"))
                + " --ei timeoutSec " + Math.max(0, timeoutSec));
        logger.info("showDialog: dispatched to MessageOverlayService");
        return true;
    }

    /** Dismiss any showing toast/dialog. Idempotent (no-op if nothing is up). */
    public static void dismiss() {
        exec("am broadcast -a " + ACTION_DISMISS + " -p " + PKG);
        exec("am stopservice -n " + SERVICE);
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    /** Shell-quote one `am` extra value the POSIX way. */
    private static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void exec(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        } catch (Throwable t) {
            logger.warn("exec failed [" + cmd + "]: " + t.getMessage());
        }
    }
}
