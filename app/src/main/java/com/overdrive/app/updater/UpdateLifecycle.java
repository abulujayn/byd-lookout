package com.overdrive.app.updater;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.overdrive.app.launcher.AdbDaemonLauncher;

import java.io.File;

/**
 * Coordinates the post-update launch path so the new app process always wins
 * over zombie daemons / watchdogs left behind by the previous install.
 *
 * The contract:
 *   - Old process (AppUpdater.stopAllDaemons) writes UPDATE_IN_PROGRESS_FILE
 *     and POST_UPDATE_FILE to /data/local/tmp before pm install.
 *   - New process (MainActivity) detects either sentinel + the post_update
 *     intent extra + PREF_JUST_UPDATED. If any is set, it runs hardResetDaemons
 *     before DaemonStartupManager so old daemons can't outlive the install.
 *   - hardResetDaemons clears the sentinels on completion.
 */
public final class UpdateLifecycle {

    private static final String TAG = "UpdateLifecycle";

    /**
     * Build a ps+awk+kill snippet for {@code pattern}, excluding the calling
     * shell's own PID via {@code $$}. Replaces every {@code pkill -9 -f
     * '<pattern>'} site — pkill -f matches the calling shell's argv on the
     * literal pattern and SIGKILLs it before subsequent commands run.
     */
    private static String psAwkKillLine(String pattern) {
        return "MY_PID=$$; ps -A -o PID,ARGS | grep -F '" + pattern + "' | grep -v grep "
            + "| awk '{print $1}' | while read pid; do "
            + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done\n";
    }

    public static final String UPDATE_IN_PROGRESS_FILE = "/data/local/tmp/overdrive_update_in_progress";
    public static final String POST_UPDATE_FILE = "/data/local/tmp/overdrive_post_update";
    /**
     * One-shot marker read by TelegramBotDaemon's notifyTunnel handler so the
     * first post-update tunnel-URL message can include the new version (and a
     * "this is why your URL changed" hint) instead of the generic "URL changed"
     * copy. Contains the version string (e.g. "alpha-v11.4"). Deleted by the
     * daemon after consuming.
     */
    public static final String TELEGRAM_POST_UPDATE_HINT_FILE =
            "/data/local/tmp/overdrive_post_update_pending_telegram";

    public static final String EXTRA_POST_UPDATE = "post_update";

    private UpdateLifecycle() {}

    /** Detects whether this launch came right after a package install. */
    public static boolean isPostUpdateLaunch(Context ctx, Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_POST_UPDATE, false)) return true;
        if (new File(POST_UPDATE_FILE).exists()) return true;
        if (new File(UPDATE_IN_PROGRESS_FILE).exists()) return true;
        try {
            return ctx.getSharedPreferences("app_updater", Context.MODE_PRIVATE)
                    .getBoolean("just_updated", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Hard-kill every known daemon + watchdog, wipe lock/sentinel files, then
     * invoke onComplete on the same thread that the underlying launcher uses.
     * Safe to call when no update is in progress — it's just a sweep.
     */
    public static void hardResetDaemons(Context ctx, Runnable onComplete) {
        Log.i(TAG, "post-update detected — hard-resetting daemons");
        long start = System.currentTimeMillis();
        // We allocate a fresh AdbDaemonLauncher here because hardResetDaemons
        // is static and runs early in MainActivity.runDaemonStartup, before
        // the per-Activity DaemonStartupManager's launcher is necessarily
        // initialized. To avoid leaking the launcher's executor + nested
        // tunnel-poll scheduler thread, we explicitly call
        // closePersistentConnection() inside the completion paths.
        AdbDaemonLauncher launcher = new AdbDaemonLauncher(ctx);

        // Single script-via-tmp-file invocation — `executeShellScript` writes
        // the body to /data/local/tmp/<id>.sh and runs it. The running shell's
        // argv is `sh /data/local/tmp/<id>.sh`, so toybox `pkill -f` cannot
        // match the calling shell on a daemon pattern. This replaces the
        // earlier 3-phase split that was needed to defend against pkill
        // self-suicide on a `sh -c "..."` payload.
        //
        // Order within the script:
        //   1. Plant per-daemon disable sentinels (defense for any watchdog
        //      we might miss with the pkill — gate-1/gate-2 in the watchdog
        //      loop catches them on the next iteration).
        //   2. Remove watchdog scripts so the kernel can't re-exec them.
        //   3. pkill / killall cascade — daemon binaries first, then
        //      ancillary binaries (cloudflared/zrok/sing-box/tailscaled).
        //      No self-match risk now.
        //   4. Wait briefly for processes to settle, THEN remove lock files.
        //      Doing this AFTER the kills (rather than before) prevents the
        //      "phase-2 lockfile resurrection" race: previously phase 1 rm'd
        //      *_daemon.lock, then between phases the still-alive daemon
        //      wrote its PID back into the lock, then phase 2 pkilled it,
        //      leaving an orphaned lock that the new daemon refused to
        //      overwrite.
        //   5. Clear per-daemon disable sentinels so the new MainActivity
        //      doesn't see them and refuse to start. Post-update sentinels
        //      (UPDATE_IN_PROGRESS_FILE, POST_UPDATE_FILE) are KEPT; the
        //      new process consumes them via isPostUpdateLaunch.
        String script =
                "echo \"disabled by post-update reset at $(date)\" > /data/local/tmp/camera_daemon.disabled\n" +
                "chmod 666 /data/local/tmp/camera_daemon.disabled 2>/dev/null\n" +
                "echo \"disabled by post-update reset at $(date)\" > /data/local/tmp/zrok.disabled\n" +
                "chmod 666 /data/local/tmp/zrok.disabled 2>/dev/null\n" +
                "echo \"disabled by post-update reset at $(date)\" > /data/local/tmp/acc_sentry_daemon.disabled\n" +
                "chmod 666 /data/local/tmp/acc_sentry_daemon.disabled 2>/dev/null\n" +
                "echo \"disabled by post-update reset at $(date)\" > /data/local/tmp/telegram_bot_daemon.disabled\n" +
                "chmod 666 /data/local/tmp/telegram_bot_daemon.disabled 2>/dev/null\n" +
                "rm -f /data/local/tmp/cam_watchdog.pid 2>/dev/null\n" +
                "rm -f /data/local/tmp/start_cam_daemon.sh /data/local/tmp/start_acc_sentry.sh /data/local/tmp/start_zrok.sh /data/local/tmp/start_telegram.sh 2>/dev/null\n" +
                // ps+awk+kill cascade — single-source-of-truth process
                // names below. Each pattern walks /proc and SIGKILLs the
                // matching PIDs except the calling shell's own PID.
                psAwkKillLine("start_cam_daemon") +
                psAwkKillLine("start_acc_sentry") +
                psAwkKillLine("start_telegram") +
                psAwkKillLine("byd_cam_daemon") +
                psAwkKillLine("cam_daemon") +
                psAwkKillLine("sentry_daemon") +
                psAwkKillLine("acc_sentry_daemon") +
                psAwkKillLine("telegram_bot_daemon") +
                psAwkKillLine("sentry_proxy") +
                psAwkKillLine("cloudflared") +
                psAwkKillLine("zrok") +
                psAwkKillLine("sing-box") +
                psAwkKillLine("tailscaled") +
                "killall -9 cloudflared 2>/dev/null\n" +
                "killall -9 zrok 2>/dev/null\n" +
                "killall -9 tailscaled 2>/dev/null\n" +
                "killall -9 sing-box 2>/dev/null\n" +
                // Brief settle so SIGKILL'd daemons release their lockfiles
                // before we rm the lock files. Without this delay, a daemon
                // mid-shutdown could still rewrite the lock between our
                // pkill and our rm.
                "sleep 1\n" +
                "rm -f /data/local/tmp/*_daemon.lock 2>/dev/null\n" +
                // Clear per-daemon sentinels so the new MainActivity starts
                // clean. The post-update markers (UPDATE_IN_PROGRESS_FILE /
                // POST_UPDATE_FILE) survive — new process owns them.
                "rm -f /data/local/tmp/*_daemon.disabled 2>/dev/null\n" +
                "rm -f /data/local/tmp/zrok.disabled 2>/dev/null\n" +
                "rm -f " + UPDATE_IN_PROGRESS_FILE + " " + POST_UPDATE_FILE + " 2>/dev/null\n" +
                "echo done\n";

        launcher.executeShellScript(script, new AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) {}
            @Override public void onLaunched() {
                // releasePerInstanceResources() — NOT closePersistentConnection.
                // The latter closes the process-wide shared Dadb that the
                // long-lived daemonStartupManager.adbLauncher is concurrently
                // using; that would surface as spurious onError on its
                // in-flight tasks. We only own this launcher's executor
                // and tunnel-poll scheduler — release just those.
                try { launcher.releasePerInstanceResources(); } catch (Exception ignored) {}
                finishHardReset(onComplete, start, false);
            }
            @Override public void onError(String e) {
                Log.w(TAG, "hard reset error (continuing): " + e);
                try { launcher.releasePerInstanceResources(); } catch (Exception ignored) {}
                finishHardReset(onComplete, start, true);
            }
        });
    }

    private static void finishHardReset(Runnable onComplete, long start, boolean withError) {
        long ms = System.currentTimeMillis() - start;
        Log.i(TAG, "hard reset complete in " + ms + "ms" + (withError ? " (with warning)" : ""));
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        if (onComplete != null) onComplete.run();
    }
}
