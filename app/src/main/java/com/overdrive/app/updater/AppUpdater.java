package com.overdrive.app.updater;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.overdrive.app.BuildConfig;
import com.overdrive.app.launcher.AdbShellExecutor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Checks GitHub Releases for app updates and handles download + silent install.
 *
 * Release model:
 * - Fixed tags: "alpha", "debug", "prod" (future)
 * - APK is replaced in-place on the same release
 * - Update detection: compare asset updated_at vs last installed timestamp
 * - Debug tag is ignored in release builds
 *
 * API: https://api.github.com/repos/yash-srivastava/Overdrive-release/releases/tags/{channel}
 */
public class AppUpdater {

    private static final String TAG = "AppUpdater";
    private static final String GITHUB_REPO = "yash-srivastava/Overdrive-release";
    private static final String PREFS_NAME = "app_updater";
    private static final String PREF_LAST_UPDATE_TIME = "last_update_timestamp";
    private static final String PREF_JUST_UPDATED = "just_updated";
    private static final String PREF_UPDATED_VERSION = "updated_version";
    // Also persist to filesystem (survives app reinstall, unlike SharedPreferences)
    private static final String UPDATE_TIMESTAMP_FILE = "/data/local/tmp/overdrive_update_timestamp";
    // Version file readable by daemon process (SharedPreferences are per-process)
    public static final String VERSION_FILE = "/data/local/tmp/overdrive_version";
    // Sentinels for the post-update handshake (see UpdateLifecycle).
    private static final String UPDATE_IN_PROGRESS_FILE = UpdateLifecycle.UPDATE_IN_PROGRESS_FILE;

    /**
     * Build a ps+awk+kill snippet that kills processes whose argv contains
     * {@code pattern}, excluding the calling shell's own PID. Replaces
     * every {@code pkill -9 -f '<pattern>'} that used to live in shell
     * payloads — pkill -f matches against /proc/&lt;pid&gt;/cmdline, so the
     * calling sh -c wrapper (whose cmdline contains the literal pattern)
     * self-matches and gets SIGKILLed before subsequent commands run.
     * ps+awk+kill keys on PID instead, with a {@code $$} guard.
     */
    private static String psAwkKillLine(String pattern) {
        return "MY_PID=$$; ps -A -o PID,ARGS | grep -F '" + pattern + "' | grep -v grep "
            + "| awk '{print $1}' | while read pid; do "
            + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done\n";
    }
    private static final String POST_UPDATE_FILE = UpdateLifecycle.POST_UPDATE_FILE;

    private final Context context;
    private volatile boolean cancelled = false;
    /**
     * Single-threaded executor SHARED across every {@link AppUpdater}
     * instance. MainActivity instantiates a fresh AppUpdater for the
     * periodic 6h check, manual checks, and the actual install — and the
     * daemon-process API handler does the same. Per-instance executors
     * leaked one non-daemon thread per check, accumulating across days.
     * One static executor with a daemon thread keeps the process clean
     * across instance churn AND serializes concurrent install attempts
     * naturally (the second one waits behind the first).
     */
    private static final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "AppUpdater");
                t.setDaemon(true);
                return t;
            });
    // Null in the daemon process — Looper.getMainLooper() returns null when no
    // thread has been designated as the main looper (the daemon's main() only
    // does Looper.prepare(), not prepareMainLooper()). Callbacks fall back to
    // inline execution; see runCallback().
    private final Handler mainHandler = resolveMainHandler();

    private static Handler resolveMainHandler() {
        Looper looper = Looper.getMainLooper();
        return looper != null ? new Handler(looper) : null;
    }

    private void runCallback(Runnable r) {
        if (mainHandler != null) mainHandler.post(r);
        else r.run();
    }

    /**
     * "Are we in a process that can write {@code /data/local/tmp}?"
     * Used to decide between the direct OkHttp path and the
     * AdbDaemonLauncher-tunnelled shell path.
     *
     * UID 2000 (shell) is the canonical answer — that's what the daemon
     * runs as via app_process. But some vendor-modded BYD ROMs launch the
     * daemon as system (UID 1000) or root (UID 0). A bare `myUid() == 2000`
     * check would silently route those into the slow shell-tunnel path
     * even though they have direct write access. Probe the actual
     * permission once and cache.
     */
    private static volatile Boolean canWriteTmpCached = null;

    private static boolean canWriteLocalTmp() {
        Boolean cached = canWriteTmpCached;
        if (cached != null) return cached;
        File probe = new File("/data/local/tmp/.overdrive_updater_probe");
        boolean ok;
        try {
            try (FileOutputStream fos = new FileOutputStream(probe)) {
                fos.write((byte) 0);
                fos.flush();
            }
            ok = true;
        } catch (Exception e) {
            ok = false;
        } finally {
            try { probe.delete(); } catch (Exception ignored) {}
        }
        canWriteTmpCached = ok;
        return ok;
    }
    private AdbShellExecutor adb; // Lazy — only created when install is triggered
    private com.overdrive.app.launcher.AdbDaemonLauncher adbLauncher; // For daemon management

    private String latestDownloadUrl;
    private String releaseNotes;
    private String remoteVersion;
    private String remoteUpdatedAt;

    /**
     * Build an OkHttpClient that routes through whichever proxy the rest of
     * the app is using — sing-box on 8119 first, Tailscale on 8539 as a
     * fallback. Delegates to {@link com.overdrive.app.mqtt.ProxyHelper} so
     * we share a single 60s probe cache with MQTT, ABRP, BYD Cloud, and
     * the Telegram daemon (no separate timing windows where one consumer
     * thinks the proxy is up and another doesn't).
     *
     * Both timeouts are in seconds. Pass {@code 0} for {@code readTimeout}
     * when streaming a large body — we still want a connect deadline but
     * we don't want OkHttp to abort a healthy slow download as "read
     * timed out". For tiny JSON fetches (release metadata) keep both
     * non-zero.
     */
    private static OkHttpClient buildClient(long connectTimeout, long readTimeout) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout, TimeUnit.SECONDS)
                .readTimeout(readTimeout, TimeUnit.SECONDS)
                .followRedirects(true);

        java.net.Proxy proxy = com.overdrive.app.mqtt.ProxyHelper.getHttpProxy();
        if (proxy != null && proxy.type() != java.net.Proxy.Type.DIRECT) {
            builder.proxy(proxy);
            Log.d(TAG, "AppUpdater HTTP via proxy " + proxy.address());
        }
        return builder.build();
    }

    public interface UpdateCallback {
        void onUpdateAvailable(String currentVersion, String newVersion, String releaseNotes);
        void onNoUpdate(String currentVersion);
        void onError(String error);
    }

    public interface InstallCallback {
        void onProgress(String message);
        void onDownloadProgress(int percent);
        void onSuccess();
        void onError(String error);
    }

    public AppUpdater(Context context) {
        this.context = context;
        // Cleanup runs without ADB — just deletes from app's own external files dir
        cleanupLeftoverApk();
    }

    private AdbShellExecutor getAdb() {
        if (adb == null) {
            adb = new AdbShellExecutor(context);
        }
        return adb;
    }

    private com.overdrive.app.launcher.AdbDaemonLauncher getAdbLauncher() {
        if (adbLauncher == null) {
            adbLauncher = new com.overdrive.app.launcher.AdbDaemonLauncher(context);
        }
        return adbLauncher;
    }

    /**
     * Release per-instance ADB executor + tunnel-poll scheduler that this
     * AppUpdater lazily allocated. AppUpdater is NOT a process singleton
     * — it's `new`'d at every call site (MainActivity manual-check, the
     * 6h periodic check, UpdateApiHandler, SettingsAboutFragment,
     * SurveillanceIpcServer). Without this method, each instance that
     * touched the ADB-tunnel path stranded one non-daemon executor
     * thread + one tunnel-poll scheduler thread for the life of the
     * process. After a week of parking that's ~50+ zombie threads in
     * /proc/self/status.
     *
     * Callers SHOULD invoke close() in a finally block once they're done
     * with the AppUpdater instance. Idempotent.
     *
     * Uses releasePerInstanceResources (NOT closePersistentConnection)
     * so we don't null the process-wide shared Dadb that other launchers
     * are using.
     */
    public void close() {
        try {
            if (adbLauncher != null) {
                adbLauncher.releasePerInstanceResources();
                adbLauncher = null;
            }
        } catch (Exception ignored) {}
        try {
            if (adb != null) {
                adb.shutdown();
                adb = null;
            }
        } catch (Exception ignored) {}
    }

    /**
     * Run a shell command, picking the right executor for the current process.
     *
     * The app process (UID 10xxx) needs to elevate to UID 2000 to write
     * /data/local/tmp and call `pm install`, so it goes through the ADB-shell
     * tunnel ({@link com.overdrive.app.launcher.AdbDaemonLauncher}).
     *
     * The daemon process is ALREADY UID 2000 (it was launched via app_process
     * by the same ADB tunnel at startup), so it can — and must — execute
     * shell commands directly. Routing daemon-side calls through the ADB
     * tunnel fails on every BYD head unit because dadb tries to read the
     * app's adbkey at /data/user/0/com.overdrive.app/files/adbkey, and that
     * directory is mode 0700 owned by the app UID — UID 2000 can't open it
     * (EACCES). The user reported this as
     * "Install failed: Download failed: ERROR: Execution failed:
     *  /data/user/0/com.overdrive.app/files/adbkey: open failed:
     *  EACCES (Permission denied)".
     *
     * Direct exec is also faster (no socket round-trip per command).
     *
     * Callback semantics match {@link com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback}:
     * onLog gets the combined stdout/stderr, then onLaunched fires on success
     * (exit 0) or onError on a non-zero exit / spawn failure.
     */
    private void runShell(String command,
                          com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback callback) {
        // Tunnel through ADB if this process can't write /data/local/tmp
        // directly (the canonical app-process state). See canWriteLocalTmp
        // for why this beats a UID==2000 check.
        if (!canWriteLocalTmp()) {
            getAdbLauncher().executeShellCommand(command, callback);
            return;
        }
        // Daemon path — exec directly. Runs SYNCHRONOUSLY on the caller's
        // thread (don't bounce onto AppUpdater.executor — downloadAndInstall
        // already runs there and is single-threaded, so queueing more work
        // would deadlock against the wait() that follows each call site).
        // The synchronous callback fires before return, so the caller's
        // notify/wait pattern still works (the wait sees the done flag
        // already true and short-circuits).
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            StringBuilder out = new StringBuilder();
            java.io.BufferedReader stdout = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            java.io.BufferedReader stderr = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = stdout.readLine()) != null) {
                out.append(line).append('\n');
            }
            while ((line = stderr.readLine()) != null) {
                out.append(line).append('\n');
            }
            int exit = p.waitFor();
            String combined = out.toString().trim();
            if (!combined.isEmpty()) callback.onLog(combined);
            if (exit == 0) {
                callback.onLaunched();
            } else {
                callback.onError("Exit " + exit + ": " + combined);
            }
        } catch (Exception e) {
            callback.onError("Execution failed: " + e.getMessage());
        }
    }

    /**
     * Execute a multi-command shell payload via a temp script file. Use this
     * for any payload containing `pkill -f '<pattern>'` whose pattern also
     * appears as a literal substring in earlier commands of the same payload
     * — `sh -c "<payload>"` puts the whole payload in argv[2], and toybox
     * `pkill -f` would match the calling shell itself, SIGKILLing it before
     * later commands run.
     *
     * Routes through ADB or direct exec depending on the canonical
     * /data/local/tmp writability check used by runShell.
     */
    private void runShellScript(String scriptBody,
                                com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback callback) {
        if (!canWriteLocalTmp()) {
            getAdbLauncher().executeShellScript(scriptBody, callback);
            return;
        }
        // Direct path — write the script to a tmp file ourselves and exec it.
        // Same self-match defense as the ADB path: the running shell's argv
        // is just `sh <path>`, no daemon pattern visible to pkill.
        String scriptPath = "/data/local/tmp/.appupdater_script_" + System.nanoTime() + ".sh";
        try {
            java.io.File scriptFile = new java.io.File(scriptPath);
            try (java.io.FileWriter fw = new java.io.FileWriter(scriptFile)) {
                fw.write(scriptBody);
            }
            scriptFile.setExecutable(true, false);

            Process p = Runtime.getRuntime().exec(new String[]{"sh", scriptPath});
            StringBuilder out = new StringBuilder();
            java.io.BufferedReader stdout = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            java.io.BufferedReader stderr = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = stdout.readLine()) != null) out.append(line).append('\n');
            while ((line = stderr.readLine()) != null) out.append(line).append('\n');
            int exit = p.waitFor();
            String combined = out.toString().trim();
            if (!combined.isEmpty()) callback.onLog(combined);
            if (exit == 0) {
                callback.onLaunched();
            } else {
                callback.onError("Exit " + exit + ": " + combined);
            }
        } catch (Exception e) {
            callback.onError("Script execution failed: " + e.getMessage());
        } finally {
            try { new java.io.File(scriptPath).delete(); } catch (Exception ignored) {}
        }
    }

    /**
     * Currently-active OkHttp call, held so {@link #cancel()} can tear
     * down the socket immediately instead of waiting for the read loop
     * to observe the cancelled flag on its next iteration. Volatile so
     * the cancel thread sees the most recent reference set by the
     * download thread.
     */
    private volatile okhttp3.Call activeCall = null;

    /**
     * Cancel an in-progress download/install.
     */
    public void cancel() {
        cancelled = true;
        // Tear the socket down NOW so a stalled-mid-read transfer can't
        // hold for another 15 seconds before the read loop notices.
        okhttp3.Call call = activeCall;
        if (call != null) {
            try { call.cancel(); } catch (Exception ignored) {}
        }
    }

    private static final String APK_PATH = "/data/local/tmp/overdrive_update.apk";

    private String getApkPath() {
        return APK_PATH;
    }

    private void cleanupLeftoverApk() {
        try {
            // Also age out a stale Telegram post-update hint older than 24h —
            // if Telegram never came back online to consume it, the user has
            // already noticed the URL change through other means and a "you
            // were just updated" message would be confusing days later.
            String cmd = "rm -f " + APK_PATH + "; " +
                    "find " + UpdateLifecycle.TELEGRAM_POST_UPDATE_HINT_FILE +
                    " -mmin +1440 -delete 2>/dev/null; echo done";
            runShell(cmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() { Log.i(TAG, "Cleaned up leftover APK"); }
                @Override public void onError(String e) {}
            });
        } catch (Exception ignored) {}
    }

    /**
     * Check GitHub Releases for a newer APK on the configured channel.
     * Skips check if channel is empty (debug builds).
     */
    public void checkForUpdate(UpdateCallback callback) {
        String channel = BuildConfig.UPDATE_CHANNEL;
        if (channel == null || channel.isEmpty()) {
            runCallback(() -> callback.onNoUpdate(getDisplayVersion(context)));
            return;
        }

        executor.execute(() -> {
            try {
                String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO +
                        "/releases/tags/" + channel;

                OkHttpClient client = buildClient(15, 15);

                Request request = new Request.Builder()
                        .url(apiUrl)
                        .header("Accept", "application/vnd.github.v3+json")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        postError(callback, "GitHub API error: HTTP " + response.code());
                        return;
                    }

                    String body = response.body().string();
                    JSONObject release = new JSONObject(body);

                    releaseNotes = release.optString("body", "Bug fixes and improvements.");

                    // Find the APK asset
                    JSONArray assets = release.optJSONArray("assets");
                    if (assets == null || assets.length() == 0) {
                        postError(callback, "No assets in release");
                        return;
                    }

                    String apkUrl = null;
                    String apkName = null;
                    String updatedAt = null;

                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "");
                            apkName = name;
                            updatedAt = asset.optString("updated_at", "");
                            break;
                        }
                    }

                    if (apkUrl == null || apkUrl.isEmpty()) {
                        postError(callback, "No APK found in release");
                        return;
                    }

                    latestDownloadUrl = apkUrl;
                    remoteUpdatedAt = updatedAt;

                    // Extract version from APK filename
                    remoteVersion = extractVersion(apkName);
                    // Report what AppUpdater previously persisted so the
                    // user-facing "you're on version X" matches what
                    // SettingsAboutFragment shows. BuildConfig.VERSION_NAME
                    // is the gradle stub and unrelated to GitHub releases.
                    String currentVersion = getDisplayVersion(context);

                    // Update detection: compare asset updated_at timestamp only.
                    // Version comparison is unreliable since versionName may not be bumped
                    // when the APK is replaced on the same release tag.
                    String lastInstalledTimestamp = getLastUpdateTimestamp();
                    boolean apkUpdated = !updatedAt.isEmpty() && !updatedAt.equals(lastInstalledTimestamp);

                    // First install or fresh Android Studio install: no stored timestamp
                    // or app was just reinstalled — save current and don't prompt
                    if (lastInstalledTimestamp.isEmpty()) {
                        saveLastUpdateTimestamp(updatedAt);
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putString(PREF_UPDATED_VERSION, remoteVersion).apply();
                        persistVersionToFile(remoteVersion);
                        Log.i(TAG, "First run — saved baseline timestamp: " + updatedAt + ", version: " + remoteVersion);
                        runCallback(() -> callback.onNoUpdate(currentVersion));
                        return;
                    }

                    // Detect fresh install/deploy: if app's install time is more recent than
                    // the stored timestamp, this is a new deploy (Android Studio or manual install).
                    // Only suppress update prompt if the app was installed AFTER the remote APK was updated,
                    // meaning the user already has this version (e.g. via Android Studio sideload).
                    try {
                        long appInstallTime = context.getPackageManager()
                                .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
                        
                        // Parse the REMOTE asset timestamp (not the stored one)
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                        long remoteAssetTime = 0;
                        try { remoteAssetTime = sdf.parse(updatedAt).getTime(); } catch (Exception ignored) {}
                        
                        // Parse the stored timestamp to detect if app was reinstalled since last check
                        long storedTime = 0;
                        try { storedTime = sdf.parse(lastInstalledTimestamp).getTime(); } catch (Exception ignored) {}
                        
                        // Fresh deploy: app was installed AFTER the remote APK was uploaded AND
                        // app was also installed after the last update check (i.e. a sideload happened)
                        if (appInstallTime > remoteAssetTime && appInstallTime > storedTime && apkUpdated) {
                            saveLastUpdateTimestamp(updatedAt);
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putString(PREF_UPDATED_VERSION, remoteVersion).apply();
                            persistVersionToFile(remoteVersion);
                            Log.i(TAG, "Fresh deploy detected (app install " + appInstallTime +
                                    " > remote asset " + remoteAssetTime + ") — updated baseline");
                            runCallback(() -> callback.onNoUpdate(currentVersion));
                            return;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not check install time: " + e.getMessage());
                    }

                    Log.i(TAG, "Channel: " + channel + ", Current: " + currentVersion +
                            ", Remote: " + remoteVersion + ", APK updated: " + updatedAt +
                            ", Last installed: " + lastInstalledTimestamp);

                    if (apkUpdated) {
                        runCallback(() -> callback.onUpdateAvailable(
                                currentVersion, remoteVersion, releaseNotes));
                    } else {
                        runCallback(() -> callback.onNoUpdate(currentVersion));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Update check failed: " + e.getMessage());
                postError(callback, e.getMessage());
            }
        });
    }

    /**
     * Download APK, then stop daemons, then install silently.
     * Download happens first so user can cancel before daemons are killed.
     */
    public void downloadAndInstall(InstallCallback callback) {
        cancelled = false;
        executor.execute(() -> {
            try {
                if (latestDownloadUrl == null) {
                    postInstallError(callback, "No download URL");
                    return;
                }

                // Wipe any leftover APK from a previous attempt up front.
                // The constructor already runs cleanupLeftoverApk, but a
                // single AppUpdater instance can be reused across multiple
                // download attempts (cancel + retry), and a static executor
                // means the previous attempt's partial APK could still be
                // on disk. Re-running cleanup here is the cheap safe call.
                cleanupLeftoverApk();

                // Step 1: Download APK.
                //
                // Two paths:
                //   - Daemon process (UID 2000): direct OkHttp download. Uses
                //     ProxyHelper.getHttpProxy() so we go through sing-box on
                //     8119 (or Tailscale 8539) the same way every other
                //     network call in the project does. Streams the body to
                //     /data/local/tmp/ and emits real progress percent.
                //     Connect timeout caps wedged TCP at 15s; readTimeout is
                //     also 15s (per-read, not whole-download) so a stalled
                //     CDN throws SocketTimeoutException instead of hanging
                //     indefinitely. Healthy slow downloads keep flowing
                //     because each successful read resets the timer.
                //   - App process (UID 10xxx): falls back to the shell tunnel
                //     because the app UID can't write to /data/local/tmp/.
                //     Shell command now carries explicit timeout flags so a
                //     blocked CDN fails cleanly within ~30s instead of the
                //     5-minute Java waiter.
                //
                // The 5-minute wait below is now an outer ceiling — both
                // paths self-cap much earlier on failure.
                postProgress(callback, "Downloading update...");
                runCallback(() -> callback.onDownloadProgress(-1));

                final boolean[] dlDone = {false};
                final String[] dlResult = {null};

                if (canWriteLocalTmp()) {
                    // Direct OkHttp — runs synchronously on the executor thread.
                    try {
                        downloadApkOkHttp(latestDownloadUrl, APK_PATH, callback);
                        dlResult[0] = "OK";
                    } catch (Exception e) {
                        dlResult[0] = "ERROR: " + (e.getMessage() == null ? "download failed" : e.getMessage());
                    }
                    dlDone[0] = true;
                } else {
                    String downloadCmd = buildDownloadCommand(latestDownloadUrl, APK_PATH);
                    runShell(downloadCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String message) {
                            dlResult[0] = message;
                        }
                        @Override public void onLaunched() {
                            dlDone[0] = true;
                            synchronized (dlDone) { dlDone.notify(); }
                        }
                        @Override public void onError(String error) {
                            dlResult[0] = "ERROR: " + error;
                            dlDone[0] = true;
                            synchronized (dlDone) { dlDone.notify(); }
                        }
                    });

                    // Outer ceiling — only relevant for the shell path.
                    // OkHttp path already returned synchronously above.
                    synchronized (dlDone) {
                        if (!dlDone[0]) dlDone.wait(300000);
                    }
                }

                if (cancelled) {
                    runShell("rm -f " + APK_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) {}
                        @Override public void onLaunched() {}
                        @Override public void onError(String e) {}
                    });
                    postInstallError(callback, "Cancelled");
                    return;
                }

                String dlOutput = dlResult[0] != null ? dlResult[0] : "";
                if (dlOutput.startsWith("ERROR") || !dlOutput.contains("OK")) {
                    postInstallError(callback, "Download failed: " + dlOutput);
                    return;
                }

                runCallback(() -> callback.onDownloadProgress(100));

                // Step 2: Verify APK size via shell
                postProgress(callback, "Verifying download...");
                final boolean[] szDone = {false};
                final String[] szResult = {null};
                runShell("stat -c%s " + APK_PATH + " 2>/dev/null || echo 0",
                        new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                    @Override public void onLog(String message) { szResult[0] = message.trim(); }
                    @Override public void onLaunched() {
                        szDone[0] = true;
                        synchronized (szDone) { szDone.notify(); }
                    }
                    @Override public void onError(String error) {
                        szResult[0] = "0";
                        szDone[0] = true;
                        synchronized (szDone) { szDone.notify(); }
                    }
                });
                synchronized (szDone) {
                    if (!szDone[0]) szDone.wait(10000);
                }

                long fileSize = 0;
                try { fileSize = Long.parseLong(szResult[0].trim()); } catch (Exception ignored) {}
                if (fileSize < 1_000_000) {
                    runShell("rm -f " + APK_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) {}
                        @Override public void onLaunched() {}
                        @Override public void onError(String e) {}
                    });
                    postInstallError(callback, "Invalid APK (size: " + fileSize + ")");
                    return;
                }

                // Step 3: Save update info BEFORE we touch any daemon (the daemon
                // process — if we're running inside it — is about to die, and the
                // app process gets killed by `pm install -r`; either way the
                // SharedPreferences write must happen first).
                //
                // Snapshot the prior timestamp so we can roll back on
                // install failure. Without rollback, a failed install
                // permanently advances PREF_LAST_UPDATE_TIME to the failed
                // remote version, and the next checkForUpdate sees
                // "lastInstalled == latest" → reports no update available
                // until GitHub re-uploads the asset. The user is silently
                // stuck on the old build.
                final String priorUpdateTimestamp = getLastUpdateTimestamp();
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_JUST_UPDATED, true)
                        .putString(PREF_UPDATED_VERSION, remoteVersion)
                        .commit();
                persistVersionToFile(remoteVersion);
                saveLastUpdateTimestamp(remoteUpdatedAt);

                // Step 4 & 5: Stop daemons + install + relaunch. The control flow
                // splits here based on which process we're in:
                //
                //   App process (UID 10xxx) — the existing synchronous flow works.
                //   The app talks to UID 2000 over the dadb tunnel; that tunnel
                //   outlives our app process when `pm install -r` replaces it,
                //   and the same tunnel runs `am start` after the install lands.
                //
                //   Daemon process (UID 2000) — we ARE one of the processes the
                //   stop step kills, so we cannot supervise the install ourselves.
                //   Instead, write a self-contained install script to
                //   /data/local/tmp/ and kick it off detached (subshell + closed
                //   stdio so init reparents it), then return. The script runs
                //   on its own; our death is fine.
                if (canWriteLocalTmp()) {
                    postProgress(callback, "Stopping daemons & installing...");
                    runDetachedInstall(callback, priorUpdateTimestamp);
                    return;
                }

                postProgress(callback, "Stopping daemons...");
                stopAllDaemons();
                Thread.sleep(3000);

                postProgress(callback, "Installing...");
                final boolean[] done = {false};
                final String[] result = {null};

                String installCmd = "pm install -r -d " + APK_PATH +
                    "; rm -f " + APK_PATH +
                    "; sleep 2; am start -n com.overdrive.app/.ui.MainActivity" +
                    " --ez " + UpdateLifecycle.EXTRA_POST_UPDATE + " true";

                runShell(installCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                    @Override public void onLog(String message) {
                        Log.i(TAG, "Install: " + message);
                        result[0] = message;
                    }
                    @Override public void onLaunched() {
                        done[0] = true;
                        synchronized (done) { done.notify(); }
                    }
                    @Override public void onError(String error) {
                        result[0] = "ERROR: " + error;
                        done[0] = true;
                        synchronized (done) { done.notify(); }
                    }
                });

                synchronized (done) {
                    if (!done[0]) done.wait(60000);
                }

                // If we reach here, install may have failed (process should be dead on success)
                String output = result[0] != null ? result[0] : "";
                if (!output.toLowerCase().contains("success")) {
                    // Roll back the prefs we set pre-install. Critically,
                    // restore PREF_LAST_UPDATE_TIME — without this, a
                    // failed install silently advances the baseline and
                    // checkForUpdate would report "no update" forever.
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_JUST_UPDATED, false)
                            .remove(PREF_UPDATED_VERSION)
                            .putString(PREF_LAST_UPDATE_TIME, priorUpdateTimestamp)
                            .commit();
                    // Wipe the post-update sentinels — install never landed, so
                    // there's nothing for the next launch to recover from.
                    runShell(
                            "rm -f " + UPDATE_IN_PROGRESS_FILE + " " + POST_UPDATE_FILE,
                            new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                                @Override public void onLog(String m) {}
                                @Override public void onLaunched() {}
                                @Override public void onError(String e) {}
                            });
                    postInstallError(callback, "Install failed: " + output);
                } else {
                    postProgress(callback, "✅ Update installed! Restarting...");
                    runCallback(callback::onSuccess);
                }
            } catch (Exception e) {
                Log.e(TAG, "Install error: " + e.getMessage());
                postInstallError(callback, e.getMessage());
            }
        });
    }

    /**
     * Build a shell command that downloads a URL to a file path.
     * Uses Java's URL class via a shell one-liner (no curl/wget dependency).
     */
    private String buildDownloadCommand(String url, String outputPath) {
        // Shell-tunnel path (app process). Direct download in the daemon
        // process goes through downloadApkOkHttp instead.
        //
        // Deadline enforcement: BYD ships toybox 0.7.x (Android 7.1) where
        // toybox `wget` is the bare single-file applet — it ignores
        // `--timeout` and rejects `--tries` outright. Wrap with toybox's
        // own `timeout` applet so the deadline holds regardless of which
        // wget flavor is installed (busybox-wget, toybox-wget, vendor
        // bundle). 600s caps a 60 MB APK on a 100 kbps SIM. `timeout`
        // sends SIGTERM at the deadline and SIGKILL 5s later, so wget
        // dies cleanly and the `&& echo OK` short-circuits → "Download
        // failed:" surfaces in the UI instead of the 5-minute Java
        // waiter sweeping the symptom under the rug.
        //
        // curl gets the granular flags directly because curl on every
        // toolbox/toybox version ships with full flag parsing.
        return "sh -c 'java_url=\"" + url + "\"; " +
               "output=\"" + outputPath + "\"; " +
               "rm -f \"$output\"; " +
               "if command -v wget >/dev/null 2>&1; then " +
               "  timeout 600 wget -q -O \"$output\" \"$java_url\" && echo OK; " +
               "elif command -v curl >/dev/null 2>&1; then " +
               "  curl -sL --connect-timeout 15 --max-time 600 -o \"$output\" \"$java_url\" && echo OK; " +
               "else " +
               "  echo \"ERROR: No download tool available\"; " +
               "fi'";
    }

    /**
     * Stream an APK download to disk via OkHttp, emitting real percent
     * progress to the install callback. Used by the daemon-process path
     * (UID 2000) where we have direct write access to /data/local/tmp/
     * and can route through ProxyHelper.getHttpProxy() — same proxy the
     * rest of the app uses for outbound HTTP.
     *
     * Timeout policy:
     *   - connectTimeout = 15s  (covers slow handshake / blocked egress)
     *   - readTimeout    = 15s per read, NOT whole download
     *
     * OkHttp's readTimeout fires only when no bytes arrive for that many
     * seconds. A healthy slow link that delivers a packet every few
     * seconds keeps progressing. A wedged CDN socket where bytes stop
     * flowing throws SocketTimeoutException within 15s, which we surface
     * as a clean "Download failed" instead of the 5-minute hang the old
     * shell-wget path produced.
     *
     * Throws on any failure; caller wraps in dlResult[]. Honors
     * {@link #cancelled} between reads so the dialog's Cancel button
     * stops the transfer within one buffer's worth.
     */
    private void downloadApkOkHttp(String url, String outputPath, InstallCallback callback)
            throws Exception {
        // Drop any stale APK from a previous attempt so a partial download
        // can't be confused with a complete one if we fail mid-stream.
        new File(outputPath).delete();

        java.net.Proxy proxy = com.overdrive.app.mqtt.ProxyHelper.getHttpProxy();
        boolean usedProxy = proxy != null && proxy.type() != java.net.Proxy.Type.DIRECT;
        try {
            doStreamingDownload(url, outputPath, proxy, callback);
        } catch (Exception primary) {
            // If we tried via sing-box/Tailscale and failed, the proxy may
            // have died mid-flight (sing-box restart, Tailscale link drop).
            // Invalidate the probe cache so MQTT and friends re-detect on
            // their next call, then retry once direct. If the network
            // truly requires the proxy (CN-firmware on the SIM, GitHub
            // CDN blocked), the direct retry still fails and we surface
            // the original error — better than silently swallowing a
            // recoverable proxy blip.
            if (cancelled) throw primary;
            if (!usedProxy) throw primary;
            Log.w(TAG, "Download via proxy failed (" + primary.getMessage() + "); retrying direct");
            com.overdrive.app.mqtt.ProxyHelper.invalidateCache();
            try {
                doStreamingDownload(url, outputPath, java.net.Proxy.NO_PROXY, callback);
            } catch (Exception retry) {
                // Surface the proxy-attempt error since that's what the user
                // is more likely to recognize (sing-box / tunnel issue), but
                // append the direct-retry detail for diagnostics.
                throw new java.io.IOException(
                        primary.getMessage() + " (direct retry: " + retry.getMessage() + ")");
            }
        }
    }

    /**
     * Single-shot streaming download. Caller decides whether to retry.
     */
    private void doStreamingDownload(String url, String outputPath,
                                     java.net.Proxy proxy, InstallCallback callback)
            throws Exception {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true);
        if (proxy != null && proxy.type() != java.net.Proxy.Type.DIRECT) {
            builder.proxy(proxy);
        }
        OkHttpClient client = builder.build();

        Request req = new Request.Builder().url(url).build();
        okhttp3.Call call = client.newCall(req);
        activeCall = call;
        Response resp;
        try {
            resp = call.execute();
        } catch (Exception e) {
            activeCall = null;
            throw e;
        }
        try {
            if (!resp.isSuccessful()) {
                throw new java.io.IOException("HTTP " + resp.code() + " " + resp.message());
            }
            long total = resp.body() != null ? resp.body().contentLength() : -1;
            int lastReportedPct = -1;
            long bytesRead = 0;

            // Drop any partial bytes from a failed prior proxy attempt before
            // the retry rewrites the file from scratch.
            new File(outputPath).delete();

            try (InputStream in = resp.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(outputPath)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled) {
                        throw new java.io.IOException("Cancelled");
                    }
                    fos.write(buf, 0, n);
                    bytesRead += n;
                    if (total > 0) {
                        int pct = (int) Math.min(99, (bytesRead * 100L) / total);
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct;
                            final int report = pct;
                            runCallback(() -> callback.onDownloadProgress(report));
                        }
                    }
                }
                fos.flush();
            }
            Log.i(TAG, "APK downloaded: " + bytesRead + " bytes → " + outputPath
                    + " (proxy=" + (proxy != null ? proxy.type() : "DIRECT") + ")");
        } finally {
            try { resp.close(); } catch (Exception ignored) {}
            // Release the cancel hook so a follow-up cancel() doesn't try
            // to tear down a finished call. Set to null AFTER close so a
            // racing cancel still hits Call.cancel on the live socket.
            activeCall = null;
        }
    }

    /**
     * Daemon-process install path: write a self-contained install script and
     * fire it off detached (setsid + null fds) so it survives our death.
     *
     * Why a script instead of a single `runShell(...)`: this very process is
     * one of the things `pkill -9 -f byd_cam_daemon` will kill. If the kill
     * runs in our own shell we'd suicide before `pm install` ever fires. The
     * script is launched with setsid into a new session with closed std fds,
     * so the kernel doesn't reap it when the daemon dies.
     *
     * The script: kills watchdogs first (so they can't respawn the daemons
     * we're about to kill), kills daemons (including us), runs `pm install
     * -r -d`, then `am start` to relaunch the app. Same sequence the
     * synchronous app-process flow uses, just packaged so the caller can
     * exit before it runs.
     *
     * The webapp tracks success via /api/update/progress + the app coming
     * back online; the SharedPreferences `PREF_JUST_UPDATED` flag we wrote
     * in step 3 is what the new MainActivity reads to confirm.
     */
    private void runDetachedInstall(InstallCallback callback, String priorUpdateTimestamp) {
        String scriptPath = "/data/local/tmp/overdrive_install.sh";
        String logPath = "/data/local/tmp/overdrive_install.log";

        StringBuilder script = new StringBuilder();
        script.append("#!/system/bin/sh\n");
        script.append("set +e\n");
        script.append("exec >").append(logPath).append(" 2>&1\n");
        script.append("echo \"[install] starting at $(date)\"\n");
        // Step 1: plant sentinels so the new MainActivity recovers correctly.
        script.append("echo 'update at '$(date) > ").append(UPDATE_IN_PROGRESS_FILE).append("\n");
        script.append("echo 'update at '$(date) > ").append(POST_UPDATE_FILE).append("\n");
        // Step 2: plant the camera disable sentinel so any watchdog we miss
        // exits on its next iteration, then kill everything in one syscall
        // per family. Single broad pkill on 'cam_daemon' / 'acc_sentry' takes
        // out watchdog + daemon together — no kill-order race, no sleep
        // window for one to respawn the other. Each `2>/dev/null` so a
        // "no such process" exit doesn't abort the script.
        script.append("echo \"disabled for update at $(date)\" > /data/local/tmp/camera_daemon.disabled\n");
        script.append("chmod 666 /data/local/tmp/camera_daemon.disabled 2>/dev/null\n");
        // Plant zrok + acc-sentry sentinels so their shell watchdogs
        // (start_zrok.sh, start_acc_sentry.sh) bail out on their next
        // iteration if our pkill misses a respawn race. Cleared below
        // alongside camera_daemon.disabled. World-readable so the watchdogs
        // (UID 2000) can stat the file when it was written by the install
        // script (running as the app UID).
        script.append("echo \"disabled for update at $(date)\" > /data/local/tmp/zrok.disabled\n");
        script.append("chmod 666 /data/local/tmp/zrok.disabled 2>/dev/null\n");
        script.append("echo \"disabled for update at $(date)\" > /data/local/tmp/acc_sentry_daemon.disabled\n");
        script.append("chmod 666 /data/local/tmp/acc_sentry_daemon.disabled 2>/dev/null\n");
        script.append("echo \"disabled for update at $(date)\" > /data/local/tmp/telegram_bot_daemon.disabled\n");
        script.append("chmod 666 /data/local/tmp/telegram_bot_daemon.disabled 2>/dev/null\n");
        script.append(psAwkKillLine("cam_daemon"));
        script.append(psAwkKillLine("acc_sentry"));
        script.append(psAwkKillLine("start_telegram"));
        script.append("killall -9 byd_cam_daemon 2>/dev/null\n");
        script.append(psAwkKillLine("sentry_daemon"));
        script.append(psAwkKillLine("telegram_bot_daemon"));
        script.append(psAwkKillLine("sentry_proxy"));
        script.append(psAwkKillLine("cloudflared"));
        script.append("killall -9 cloudflared 2>/dev/null\n");
        script.append(psAwkKillLine("zrok"));
        script.append("killall -9 zrok 2>/dev/null\n");
        script.append(psAwkKillLine("sing-box"));
        script.append("killall -9 sing-box 2>/dev/null\n");
        script.append(psAwkKillLine("tailscaled"));
        script.append("killall -9 tailscaled 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/start_cam_daemon.sh /data/local/tmp/cam_watchdog.pid 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/start_acc_sentry.sh /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/start_zrok.sh 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/start_telegram.sh 2>/dev/null\n");

        // Per-daemon lock files (mirrors DaemonLauncher's killDaemonViaAdb
        // cleanup) so the relaunched MainActivity's daemon supervisor doesn't
        // refuse to start because a stale lock looks alive.
        script.append("rm -f /data/local/tmp/camera_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/telegram_bot_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/*_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/cam_watchdog.pid 2>/dev/null\n");
        // Clear the cam + zrok + acc-sentry disable sentinels — we needed
        // them set above so any surviving watchdog exits, but the new
        // MainActivity must not see them on startup or it'll leave those
        // daemons disabled. POST_UPDATE_FILE / UPDATE_IN_PROGRESS_FILE
        // stay in place; the new process consumes them via UpdateLifecycle.
        script.append("rm -f /data/local/tmp/camera_daemon.disabled 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/zrok.disabled 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/acc_sentry_daemon.disabled 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/telegram_bot_daemon.disabled 2>/dev/null\n");
        script.append("sleep 2\n");
        // Step 4: install. `pm install -r -d` allows downgrades (-d) so a
        // bad release doesn't strand the user, and replaces the existing app
        // (-r). Stdout is captured into PM_OUT so step 4b can include the
        // failure reason in the progress JSON if `pm install` exits non-zero.
        script.append("echo \"[install] running pm install\"\n");
        script.append("PM_OUT=$(pm install -r -d ").append(APK_PATH).append(" 2>&1)\n");
        script.append("INSTALL_RC=$?\n");
        script.append("echo \"$PM_OUT\"\n");
        script.append("rm -f ").append(APK_PATH).append("\n");
        // Step 4b: on `pm install` failure, write phase=error to the progress
        // JSON so the webapp's poller surfaces the failure instead of sitting
        // in reconnect-mode forever waiting for an upgraded daemon that will
        // never appear. Same shape as UpdateApiHandler.writeProgress so the
        // /api/update/progress endpoint and update-flow.js consume it without
        // changes. We do this before `am start` so the relaunched app reports
        // the error in its own UI, and the failure write happens BEFORE the
        // sentinels are cleared (the new MainActivity reads PROGRESS_FILE on
        // start and can roll back PREF_JUST_UPDATED if it sees phase=error).
        // `pm install` Success path skips this block entirely; the daemon
        // restart that follows `am start` re-writes phase=installing/100 and
        // eventually a fresh daemon overwrites with idle.
        script.append("if [ \"$INSTALL_RC\" != \"0\" ]; then\n");
        // Escape special chars in PM_OUT for JSON. toybox lacks `jq`, but we
        // can substitute backslashes, double-quotes, and newlines via
        // parameter expansion (POSIX) — covers the ~99% case of pm install's
        // single-line failure messages like "Failure [INSTALL_PARSE_FAILED…]".
        // Strip ALL control chars first (`tr -d '\000-\037'`) — a stray tab
        // or stack-trace embedded NUL would produce technically-invalid JSON
        // and JSONObject would throw, swallowing the error in the consumer.
        script.append("  PM_ESC=$(printf %s \"$PM_OUT\" | tr -d '\\000-\\037' | ");
        script.append("sed 's/\\\\/\\\\\\\\/g;s/\"/\\\\\"/g')\n");
        script.append("  TS=$(($(date +%s) * 1000))\n");
        // Embed the prior PREF_LAST_UPDATE_TIME so consumeFailedUpdateError
        // can roll it back. Without rollback, a failed install permanently
        // advances the baseline timestamp and the next checkForUpdate
        // reports "no update available" — user is silently stuck on the
        // old version until the GitHub asset is re-uploaded.
        // priorTs comes from a Java-side String; we URL-escape minimally
        // (it's already an ISO timestamp like 2026-05-29T10:00:00Z, no
        // backslashes/quotes/control chars) so direct interpolation is safe.
        script.append("  printf '{\"phase\":\"error\",\"percent\":-1,");
        script.append("\"message\":\"Install failed\",\"error\":\"%s\",");
        script.append("\"priorUpdateTs\":\"")
              .append(priorUpdateTimestamp != null ? priorUpdateTimestamp : "")
              .append("\",\"ts\":%s}' ");
        script.append("\"$PM_ESC\" \"$TS\" > /data/local/tmp/overdrive_update_progress.json\n");
        script.append("  echo \"[install] FAILED rc=$INSTALL_RC\"\n");
        // Clear the in-progress sentinel so the new MainActivity doesn't run a
        // post-update hard-reset for an install that never landed. Keep
        // POST_UPDATE_FILE — its presence on a still-old-version app is the
        // signal MainActivity uses to read PROGRESS_FILE and show the error.
        script.append("  rm -f ").append(UPDATE_IN_PROGRESS_FILE).append("\n");
        script.append("fi\n");
        // Step 5: relaunch. Runs in both success and failure cases so the user
        // gets the app back either way (with the new APK on success, or with
        // the old APK + an error toast on failure).
        script.append("sleep 2\n");
        script.append("am start -n com.overdrive.app/.ui.MainActivity --ez ");
        script.append(UpdateLifecycle.EXTRA_POST_UPDATE).append(" true\n");
        script.append("echo \"[install] done rc=$INSTALL_RC at $(date)\"\n");

        try {
            // Atomic write: stream into a .tmp sibling, fsync, then rename
            // onto the final path. If the daemon is killed mid-write (very
            // small window between the write and the spawn below), the
            // partial .tmp file is harmless on its own — only after the
            // rename completes does the launcher see a valid script. The
            // previous direct-write approach could leave a partially-formed
            // script that fired pkill but never reached the pm install
            // section, leaving the device with daemons dead and no
            // recovery on the same boot.
            String tmpPath = scriptPath + ".tmp";
            java.io.File tmpFile = new java.io.File(tmpPath);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile)) {
                fos.write(script.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            tmpFile.setExecutable(true, false);
            java.io.File finalFile = new java.io.File(scriptPath);
            // Java's File.renameTo is best-effort across filesystems but
            // /data/local/tmp/<a> → /data/local/tmp/<b> is intra-fs so
            // it's atomic on every Android kernel.
            if (!tmpFile.renameTo(finalFile)) {
                // Fallback: explicit delete + rename. Some BYD ROMs reject
                // renameTo if the target exists despite POSIX rename(2)
                // semantics — defensive.
                finalFile.delete();
                if (!tmpFile.renameTo(finalFile)) {
                    throw new java.io.IOException("Could not rename install script into place");
                }
            }

            // Detach: a subshell with `& exit 0` reparents the install script
            // to init (PID 1), so SIGTERM/SIGHUP from the daemon's death don't
            // reach it. We don't rely on `setsid` — toybox builds on BYD ROMs
            // are inconsistent about which applets ship. The script itself
            // does `exec >log 2>&1` to close all stdio, and `</dev/null` here
            // closes stdin so nothing keeps the parent waiting.
            ProcessBuilder pb = new ProcessBuilder(
                    "sh", "-c",
                    "(sh " + scriptPath + " </dev/null >/dev/null 2>&1 &)");
            pb.redirectErrorStream(true);
            pb.start();

            postProgress(callback, "Installing...");
            // DO NOT call onSuccess here. The detached script hasn't run
            // pm install yet — it's still in its 1-second sleep. Calling
            // onSuccess clears installInFlight in the API handler, which
            // lets a second concurrent install request slip through, and
            // it tells the UI "✅ Update installed!" before the install
            // has actually started. The script writes its own terminal
            // progress (phase=error on pm-install failure, daemon process
            // death on success), and the webapp's poller is the source of
            // truth from this point. The original comment ("Just return
            // and let the script + webapp poller take it from here") was
            // correct; the runCallback below contradicted it. Removed.
        } catch (Exception e) {
            Log.e(TAG, "Detached install failed: " + e.getMessage());
            postInstallError(callback, "Detached install failed: " + e.getMessage());
        }
    }

    private void cleanup(String path) {
        try {
            runShell("rm -f " + path, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String e) {}
            });
        } catch (Exception ignored) {}
    }

    private void stopAllDaemons() {
        Log.i(TAG, "Stopping all daemons...");

        com.overdrive.app.launcher.AdbDaemonLauncher launcher = getAdbLauncher();

        // Step 0: Plant the post-update sentinels so the new process knows to
        // run a hard-reset before starting daemons (see UpdateLifecycle). The
        // BootReceiver path is intentionally inert on MY_PACKAGE_REPLACED, so
        // the new MainActivity is the sole daemon orchestrator after install.
        final boolean[] markerDone = {false};
        String markerCmd =
                "echo 'update at $(date)' > " + UPDATE_IN_PROGRESS_FILE + "; " +
                "echo 'update at $(date)' > " + POST_UPDATE_FILE + "; " +
                "echo done";
        runShell(markerCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) {}
            @Override public void onLaunched() {
                markerDone[0] = true;
                synchronized (markerDone) { markerDone.notify(); }
            }
            @Override public void onError(String e) {
                Log.w(TAG, "Sentinel write: " + e);
                markerDone[0] = true;
                synchronized (markerDone) { markerDone.notify(); }
            }
        });
        try {
            synchronized (markerDone) {
                if (!markerDone[0]) markerDone.wait(3000);
            }
        } catch (InterruptedException ignored) {}

        // Step 1: Hard-kill the cam_daemon and acc_sentry families in one
        // sweep — broad pkill -f matches both watchdog and daemon
        // simultaneously so neither survives to respawn the other. Plant the
        // camera disable sentinel first so any straggler watchdog exits on
        // its next iteration.
        //
        // CRITICAL: must use runShellScript (tmpfile-backed) rather than
        // runShell (`sh -c "<body>"`). With runShell, the calling shell's
        // argv contains the literal patterns 'cam_daemon' and 'acc_sentry',
        // so the very first `pkill -9 -f 'cam_daemon'` SIGKILLs the calling
        // shell — every command after the first pkill is silently dropped
        // (acc_sentry kill, lock rms, the echo done). Phase-3 sweep at
        // line 1242 below already used runShellScript for the same reason;
        // step-1 was the last `sh -c` survivor of the self-match family of
        // bugs.
        Log.i(TAG, "Killing daemons and watchdogs...");
        String killWatchdogsCmd =
                "echo 'disabled for update at $(date)' > /data/local/tmp/camera_daemon.disabled\n" +
                psAwkKillLine("cam_daemon") +
                psAwkKillLine("acc_sentry") +
                "rm -f /data/local/tmp/start_cam_daemon.sh /data/local/tmp/cam_watchdog.pid /data/local/tmp/camera_daemon.lock 2>/dev/null\n" +
                "rm -f /data/local/tmp/start_acc_sentry.sh /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null\n" +
                "echo done\n";

        final boolean[] wdDone = {false};
        runShellScript(killWatchdogsCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) {}
            @Override public void onLaunched() {
                Log.i(TAG, "Watchdog scripts killed");
                wdDone[0] = true;
                synchronized (wdDone) { wdDone.notify(); }
            }
            @Override public void onError(String e) {
                Log.w(TAG, "Watchdog kill: " + e);
                wdDone[0] = true;
                synchronized (wdDone) { wdDone.notify(); }
            }
        });
        try {
            synchronized (wdDone) {
                if (!wdDone[0]) wdDone.wait(5000);
            }
        } catch (InterruptedException ignored) {}
        
        // Step 2: Kill the rest of the daemon families. cam_daemon and
        // acc_sentry are already gone from step 1 — this loop covers the
        // standalone daemons (telegram, proxy) and native binaries
        // (cloudflared, zrok, sing-box, tailscaled).
        String[] daemons = {"sentry_daemon",
                "telegram_bot_daemon", "sentry_proxy", "cloudflared", "zrok", "sing-box",
                "tailscaled"};
        
        for (String daemon : daemons) {
            final boolean[] done = {false};
            launcher.killDaemon(daemon, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {
                    Log.i(TAG, "Stopped: " + daemon);
                    done[0] = true;
                    synchronized (done) { done.notify(); }
                }
                @Override public void onError(String e) {
                    Log.w(TAG, "Stop " + daemon + ": " + e);
                    done[0] = true;
                    synchronized (done) { done.notify(); }
                }
            });
            
            try {
                synchronized (done) {
                    if (!done[0]) done.wait(5000);
                }
            } catch (InterruptedException ignored) {}
        }
        
        // Step 3: Final sweep — broad pkill on every family pattern catches
        // any stragglers (orphaned shells, late-respawn races). One syscall
        // per family is enough; no need to re-list watchdog vs daemon
        // separately because the broad pattern covers both.
        // NOTE: we keep UPDATE_IN_PROGRESS_FILE / POST_UPDATE_FILE in place;
        // the new process clears them after its own hard-reset pass.
        Log.i(TAG, "Final sweep for remaining processes...");
        // Single script-via-tmp-file invocation — `executeShellScript` writes
        // the body to a temp file and `sh <path>`s it. Calling shell's argv
        // is just `sh <path>`, so toybox `pkill -f 'cam_daemon'` cannot
        // match the calling shell. This replaces the earlier 2-phase split
        // that was needed to defend against pkill self-suicide.
        //
        // Order: sentinels → rm scripts → pkill cascade → settle → rm locks.
        // Lock-rm is AFTER pkill (not before) to prevent the lockfile
        // resurrection race: a still-alive daemon can rewrite its PID into
        // the lock between rm and pkill.
        //
        // Per-daemon disable sentinels remain set after this returns; the
        // subsequent install script (buildInstallScript) clears them right
        // before `am start`.
        String sweepScript =
                "echo \"disabled by stopAllDaemons sweep at $(date)\" > /data/local/tmp/zrok.disabled\n" +
                "chmod 666 /data/local/tmp/zrok.disabled 2>/dev/null\n" +
                "echo \"disabled by stopAllDaemons sweep at $(date)\" > /data/local/tmp/acc_sentry_daemon.disabled\n" +
                "chmod 666 /data/local/tmp/acc_sentry_daemon.disabled 2>/dev/null\n" +
                "echo \"disabled by stopAllDaemons sweep at $(date)\" > /data/local/tmp/telegram_bot_daemon.disabled\n" +
                "chmod 666 /data/local/tmp/telegram_bot_daemon.disabled 2>/dev/null\n" +
                "rm -f /data/local/tmp/cam_watchdog.pid 2>/dev/null\n" +
                "rm -f /data/local/tmp/start_cam_daemon.sh /data/local/tmp/start_acc_sentry.sh /data/local/tmp/start_zrok.sh /data/local/tmp/start_telegram.sh 2>/dev/null\n" +
                psAwkKillLine("cam_daemon") +
                psAwkKillLine("acc_sentry") +
                psAwkKillLine("sentry_daemon") +
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
                "sleep 1\n" +
                "rm -f /data/local/tmp/*_daemon.lock 2>/dev/null\n" +
                "echo done\n";

        final boolean[] sweepDone = {false};
        runShellScript(sweepScript, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) {}
            @Override public void onLaunched() {
                sweepDone[0] = true;
                synchronized (sweepDone) { sweepDone.notify(); }
            }
            @Override public void onError(String e) {
                Log.w(TAG, "stopAllDaemons sweep error: " + e);
                sweepDone[0] = true;
                synchronized (sweepDone) { sweepDone.notify(); }
            }
        });
        try {
            synchronized (sweepDone) {
                if (!sweepDone[0]) sweepDone.wait(5000);
            }
        } catch (InterruptedException ignored) {}

        Log.i(TAG, "All daemons and watchdogs stopped");
    }

    /**
     * Extract version from APK filename including channel.
     * "overdrive-release-alpha-v6.1.apk" → "alpha-v6.1"
     * "overdrive-release-prod-v2.0.1.apk" → "prod-v2.0.1"
     */
    static String extractVersion(String apkName) {
        if (apkName != null) {
            // Try to match channel-version pattern: alpha-v6.1, prod-v2.0
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(alpha|debug|prod|beta)-v?(\\d+\\.\\d+(?:\\.\\d+)?)")
                    .matcher(apkName);
            if (m.find()) return m.group(1) + "-v" + m.group(2);

            // Fallback: just version number
            m = java.util.regex.Pattern.compile("v?(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(apkName);
            if (m.find()) return "v" + m.group(1);
        }
        return "unknown";
    }

    static boolean isNewerVersion(String local, String remote) {
        try {
            String[] lp = local.split("\\.");
            String[] rp = remote.split("\\.");
            int len = Math.max(lp.length, rp.length);
            for (int i = 0; i < len; i++) {
                int l = i < lp.length ? Integer.parseInt(lp[i].replaceAll("[^0-9]", "")) : 0;
                int r = i < rp.length ? Integer.parseInt(rp[i].replaceAll("[^0-9]", "")) : 0;
                if (r > l) return true;
                if (r < l) return false;
            }
            return false;
        } catch (Exception e) {
            return !local.equals(remote);
        }
    }

    private String getLastUpdateTimestamp() {
        // Try SharedPreferences first (fast)
        String ts = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_LAST_UPDATE_TIME, "");
        if (!ts.isEmpty()) return ts;

        // Fall back to filesystem (survives app reinstall)
        try {
            File f = new File(UPDATE_TIMESTAMP_FILE);
            if (f.exists()) {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                ts = r.readLine();
                r.close();
                if (ts != null && !ts.isEmpty()) {
                    // Sync back to SharedPreferences
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(PREF_LAST_UPDATE_TIME, ts).apply();
                    return ts;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void saveLastUpdateTimestamp(String timestamp) {
        if (timestamp == null) return;
        // Use commit() (synchronous) — process may be killed right after
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREF_LAST_UPDATE_TIME, timestamp).commit();
        // Also save to filesystem via ADB shell (survives reinstall, app can't write /data/local/tmp directly)
        try {
            runShell("echo '" + timestamp + "' > " + UPDATE_TIMESTAMP_FILE,
                    new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String error) {
                    Log.w(TAG, "Failed to save timestamp to file: " + error);
                }
            });
        } catch (Exception ignored) {}
    }

    /**
     * Persist version string to filesystem so the daemon process can read it.
     * SharedPreferences are per-process and may not be accessible from the daemon.
     */
    private void persistVersionToFile(String version) {
        if (version == null || version.isEmpty()) return;
        try {
            runShell("echo '" + version + "' > " + VERSION_FILE,
                    new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String error) {
                    Log.w(TAG, "Failed to save version to file: " + error);
                }
            });
        } catch (Exception ignored) {}
    }

    private void postError(UpdateCallback cb, String msg) {
        runCallback(() -> cb.onError(msg));
    }
    private void postInstallError(InstallCallback cb, String msg) {
        runCallback(() -> cb.onError(msg));
    }
    private void postProgress(InstallCallback cb, String msg) {
        runCallback(() -> cb.onProgress(msg));
    }

    /**
     * Check if app was just updated and return the version string.
     * Clears the flag after reading so it only shows once.
     *
     * Callers MUST call {@link #consumeFailedUpdateError(Context)} first.
     * That method clears PREF_JUST_UPDATED on a failed install (the flag is
     * set before `runDetachedInstall` so it's true on BOTH success and
     * failure paths; the progress JSON is the authoritative outcome
     * signal). After that, this method only returns a non-null version
     * when the install actually succeeded.
     */
    public static String consumeJustUpdatedVersion(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_JUST_UPDATED, false)) {
            String version = prefs.getString(PREF_UPDATED_VERSION, "");
            // Only clear the flag, keep the version for display
            prefs.edit()
                    .putBoolean(PREF_JUST_UPDATED, false)
                    .apply();
            return version;
        }
        return null;
    }

    /**
     * Read and clear a failed-install error written by the daemon-side
     * install script. Returns the error message (e.g. "Failure
     * [INSTALL_PARSE_FAILED_NO_CERTIFICATES]") when the script's
     * `pm install` exited non-zero, or null when there's no failure to
     * report. Called on app launch — together with
     * {@link #consumeJustUpdatedVersion(Context)} — so the user sees a
     * concrete error toast instead of a misleading success message or
     * silence.
     *
     * Also clears PREF_JUST_UPDATED. The flag is set BEFORE the install
     * script runs (at line 471 in downloadAndInstall) so it's "true" on
     * BOTH success and failure paths; we use the progress JSON as the
     * authoritative success/failure signal. If we left the flag set on
     * failure, the immediately-following consumeJustUpdatedVersion call
     * would see PREF_JUST_UPDATED=true with no failure marker (because we
     * deleted it here) and would fire the misleading "Updated to vX" toast
     * alongside the error toast.
     */
    public static String consumeFailedUpdateError(Context context) {
        if (!hasFailedUpdateMarker()) return null;
        String err = null;
        String priorUpdateTs = null;
        try {
            java.io.File f = new java.io.File("/data/local/tmp/overdrive_update_progress.json");
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f)))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject j = new JSONObject(sb.toString());
            err = j.optString("error", null);
            if (err == null || err.isEmpty()) err = j.optString("message", "Install failed");
            priorUpdateTs = j.optString("priorUpdateTs", null);
        } catch (Exception ignored) {}
        // One-shot: clear the just-updated flag AND delete the progress file
        // and post-update sentinel so the next launch is clean. The retry
        // will write a fresh record.
        //
        // Roll back PREF_LAST_UPDATE_TIME so the failed-install retry path
        // sees the correct baseline. Without this, the daemon-process
        // detached install advances the baseline before pm install runs;
        // on failure the baseline is still pointing at the version that
        // didn't land, and checkForUpdate reports "no update available"
        // — user is silently stuck.
        try {
            android.content.SharedPreferences.Editor e =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_JUST_UPDATED, false)
                    .remove(PREF_UPDATED_VERSION);
            // Empty string is the legitimate "no prior install" sentinel
            // (saveLastUpdateTimestamp always writes a value after first
            // success). Restore even if empty so the field reverts to the
            // exact pre-attempt state.
            if (priorUpdateTs != null) {
                e.putString(PREF_LAST_UPDATE_TIME, priorUpdateTs);
            }
            e.apply();
        } catch (Exception ignored) {}
        try { new java.io.File("/data/local/tmp/overdrive_update_progress.json").delete(); } catch (Exception ignored) {}
        try { new java.io.File(POST_UPDATE_FILE).delete(); } catch (Exception ignored) {}
        return err;
    }

    /**
     * True when the progress JSON exists and reports phase=error. Used as a
     * gate so consumeJustUpdatedVersion doesn't toast "Updated to vX" for an
     * install that never landed.
     */
    private static boolean hasFailedUpdateMarker() {
        try {
            java.io.File f = new java.io.File("/data/local/tmp/overdrive_update_progress.json");
            if (!f.exists()) return false;
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f)))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString()).optString("phase", "").equals("error");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fetch the latest published version label for the current channel from
     * GitHub Releases (best-effort, non-blocking). Returns the same label
     * format as the on-device "display version" (e.g. "alpha-v6.1") so the
     * UI can swap one for the other without further parsing.
     *
     * Used by the Settings → About surface so the user sees the truth-source
     * version (what's published) rather than what was last installed locally.
     * Falls back through {@link #onError} when offline / proxy down.
     */
    public interface RemoteVersionCallback {
        void onResult(String version);
        void onError(String error);
    }

    public void fetchLatestReleaseVersion(RemoteVersionCallback callback) {
        String channel = BuildConfig.UPDATE_CHANNEL;
        if (channel == null || channel.isEmpty()) {
            runCallback(() -> callback.onError("No channel configured"));
            return;
        }
        executor.execute(() -> {
            try {
                String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO +
                        "/releases/tags/" + channel;

                OkHttpClient client = buildClient(10, 10);
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .header("Accept", "application/vnd.github.v3+json")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String err = "GitHub API HTTP " + response.code();
                        runCallback(() -> callback.onError(err));
                        return;
                    }
                    JSONObject release = new JSONObject(response.body().string());
                    JSONArray assets = release.optJSONArray("assets");
                    String label = null;
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String name = asset.optString("name", "");
                            if (name.endsWith(".apk")) {
                                label = extractVersion(name);
                                break;
                            }
                        }
                    }
                    if (label == null || label.isEmpty() || "unknown".equals(label)) {
                        // Fallback to the release tag_name when the asset name
                        // doesn't expose a parseable version.
                        label = release.optString("tag_name", "");
                    }
                    final String result = label;
                    runCallback(() -> {
                        if (result == null || result.isEmpty()) {
                            callback.onError("No version in release");
                        } else {
                            callback.onResult(result);
                        }
                    });
                }
            } catch (Exception e) {
                String err = e.getMessage() != null ? e.getMessage() : "Unknown error";
                runCallback(() -> callback.onError(err));
            }
        });
    }

    /**
     * Sentinel returned when neither the SharedPreferences entry nor the
     * version file has been populated yet — i.e. a fresh sideloaded install
     * that has never run an update check. We deliberately do NOT fall back
     * to BuildConfig.VERSION_NAME here because that value is the gradle
     * stub ("11.0") and is unrelated to the GitHub release the user is
     * actually running. "Manually Installed" makes the install path
     * explicit; once the user runs check-for-updates the real channel-
     * versioned string takes over.
     */
    public static final String DISPLAY_VERSION_FALLBACK = "Manually Installed";

    /**
     * Get the display version string (channel + version from APK name).
     * Returns {@link #DISPLAY_VERSION_FALLBACK} when no AppUpdater-tracked
     * version exists yet (fresh sideload before first update check).
     */
    public static String getDisplayVersion(Context context) {
        String stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_UPDATED_VERSION, null);
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        // Try the file too — daemon-side updates may have written it without
        // the app-process SharedPreferences also being populated yet.
        return getDisplayVersionFromFile();
    }

    /**
     * Get the display version without requiring a Context.
     * Reads from the persisted version file (written by the updater after
     * a successful install). Returns {@link #DISPLAY_VERSION_FALLBACK} when
     * the file is missing or empty — used by the daemon process where
     * SharedPreferences may not be accessible.
     */
    public static String getDisplayVersionFromFile() {
        try {
            java.io.File f = new java.io.File(VERSION_FILE);
            if (f.exists()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(f));
                String version = reader.readLine();
                reader.close();
                if (version != null && !version.trim().isEmpty()) {
                    return version.trim();
                }
            }
        } catch (Exception ignored) {}
        return DISPLAY_VERSION_FALLBACK;
    }
}
