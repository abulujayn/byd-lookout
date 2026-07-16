package com.overdrive.app.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared helper for launching an installed Android app and enumerating the
 * launchable apps for a picker UI. Used by BOTH the automation "open app"
 * action and the key-mapping "openApp" action so there is a single launch
 * path and a single app-list source of truth.
 *
 * <p>Launch strategy: prefer {@link PackageManager#getLaunchIntentForPackage}
 * + {@code startActivity} from the daemon's shared app Context (works on this
 * SDK-25 target with no package-visibility {@code <queries>} needed), and fall
 * back to a shell {@code am start} — the same mechanism the daemon already uses
 * to (re)launch our own app and the sidecar services. Runs in the daemon
 * (UID 2000 = shell), so both paths are permitted.
 */
public final class AppLauncher {
    private static final DaemonLogger logger = DaemonLogger.getInstance("AppLauncher");

    private AppLauncher() {}

    /**
     * Launch the given package (full-screen) by resolving its default launcher activity.
     *
     * @param pkg the target application package name
     * @return true if a launch was dispatched (Intent or shell), false otherwise
     */
    public static boolean launch(String pkg) {
        return launch(pkg, false);
    }

    /**
     * Launch the given package, optionally into split-screen (multi-window).
     *
     * <p>Full-screen ({@code split=false}) keeps the original behaviour: a clean
     * {@code getLaunchIntentForPackage} + {@code startActivity}, falling back to
     * {@code monkey}.
     *
     * <p>Split-screen ({@code split=true}) uses {@code am start --windowingMode 3
     * -n <component>} — the field-tested command. Windowing mode 3 is
     * SPLIT_SCREEN_PRIMARY: it docks this app to one half so the app the user
     * launches next lands in the other half. It REQUIRES the explicit launcher
     * {@code <package>/<activity>} component (the flag has no effect on a plain
     * {@code monkey}/category launch), so we resolve the component first via the
     * PackageManager, then via {@code cmd package resolve-activity}. If the
     * component can't be resolved (or the split am-start fails) we fall back to a
     * normal full-screen launch rather than silently doing nothing.
     *
     * @param pkg   the target application package name
     * @param split true to dock into split-screen, false for a normal launch
     * @return true if a launch was dispatched, false otherwise
     */
    public static boolean launch(String pkg, boolean split) {
        if (pkg == null || pkg.trim().isEmpty()) {
            logger.warn("openApp: missing package");
            return false;
        }
        pkg = pkg.trim();

        // Validate the package-name charset before it ever reaches getLaunchIntentForPackage
        // or the `monkey -p <pkg>` shell. This is the last line of defense for the keymap
        // path (which does not go through AppType validation) — a package name is
        // [A-Za-z0-9._], so anything else is rejected rather than passed to the shell.
        if (!isValidPackageName(pkg)) {
            logger.warn("openApp: rejected invalid package name");
            return false;
        }

        if (split) {
            String component = resolveLauncherComponent(pkg);
            if (component != null) {
                // --windowingMode 3 = SPLIT_SCREEN_PRIMARY. The activity comes from
                // the trusted PackageManager/cmd resolver and pkg is [A-Za-z0-9._];
                // shell-quote the component defensively before dispatch.


                String cmdApp1 = "TASK_ID=$(dumpsys activity activities | awk '/TaskRecord|Task\\{/{t=$0} index($0, \"" + pkg + "\") > 0 {print t; exit}' | grep -oE \"#[0-9]+\" | head -n 1 | tr -d '#'); " +
                        "if [ ! -z \"$TASK_ID\" ]; then " +
                        "am start --user 0 --windowingMode 3 -n com.overdrive.app/.AppLauncherGhostActivity; " +
                        "sleep 1; " +
                        "STACK_ID=$(am stack list | awk '/Stack id=/ {split($2, a, \"=\"); id=a[2]} /mWindowingMode=split-screen-primary/ {print id; exit}'); " +
                        "am stack move-task $TASK_ID $STACK_ID true; " +
                        "else " +
                        "am start --user 0 --windowingMode 3 -n $(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " + pkg + " | tail -n 1); " +
                        "fi";


                if (runShell(cmdApp1)) {
                    logger.info("openApp: launched " + pkg + " in split-screen (" + component + ")");
                    return true;
                }
                logger.warn("openApp: split-screen am start failed for " + component + " — falling back to full-screen");
            } else {
                logger.warn("openApp: could not resolve launcher component for " + pkg + " — falling back to full-screen");
            }
            // Fall through to the normal full-screen path below.
        }

        // 1) Intent path via the daemon's app Context (cleanest; no shell).
        Context ctx = CameraDaemon.getAppContext();
        if (ctx != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    // NEW_TASK required because the daemon Context is not an Activity.
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(launch);
                    logger.info("openApp: launched " + pkg + " via Intent");
                    return true;
                }
                logger.info("openApp: no launch intent for " + pkg + " — trying shell");
            } catch (Throwable t) {
                logger.warn("openApp: Intent launch failed for " + pkg + " (" + t.getMessage() + ") — trying shell");
            }
        }

        // 2) Shell fallback: monkey resolves the launcher activity without us
        //    needing the component name; -p restricts it to the target package.
        //    This mirrors the daemon's existing `am start` launch sites.
        if (runShell("monkey -p " + shellQuote(pkg) + " -c android.intent.category.LAUNCHER 1")) {
            logger.info("openApp: launched " + pkg + " via monkey");
            return true;
        }
        logger.warn("openApp: all launch paths failed for " + pkg);
        return false;
    }

    /**
     * List launchable installed apps as a JSON array of {package, label},
     * sorted by label. Used to populate the picker in the automation and
     * key-mapping UIs.
     */
    public static JSONArray listLaunchableApps() {
        List<JSONObject> apps = new ArrayList<>();
        Context ctx = CameraDaemon.getAppContext();
        if (ctx != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
                launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> resolved = pm.queryIntentActivities(launcherIntent, 0);
                for (ResolveInfo ri : resolved) {
                    if (ri == null || ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null) continue;
                    String label = null;
                    try {
                        CharSequence l = ri.loadLabel(pm);
                        if (l != null) label = l.toString();
                    } catch (Throwable ignored) { }
                    if (label == null || label.isEmpty()) label = pkg;
                    apps.add(makeApp(pkg, label));
                }
            } catch (Throwable t) {
                logger.warn("listLaunchableApps: PackageManager query failed (" + t.getMessage() + ")");
            }
        }
        // Shell fallback if the PackageManager path produced nothing (e.g. broken
        // daemon Context) — package names only, no friendly labels.
        if (apps.isEmpty()) {
            for (String pkg : listPackagesViaShell()) {
                apps.add(makeApp(pkg, pkg));
            }
        }

        // De-dup by package FIRST (a package can expose several launcher
        // activities, sometimes with different labels — those would not be
        // adjacent after a label sort, so an adjacent-only pass would miss them),
        // then sort by label. We launch by package, so one entry per package.
        java.util.LinkedHashMap<String, JSONObject> byPkg = new java.util.LinkedHashMap<>();
        for (JSONObject app : apps) {
            String pkg = app.optString("package", "");
            if (pkg.isEmpty() || byPkg.containsKey(pkg)) continue;
            byPkg.put(pkg, app);
        }
        List<JSONObject> unique = new ArrayList<>(byPkg.values());
        Collections.sort(unique, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return a.optString("label", "").compareToIgnoreCase(b.optString("label", ""));
            }
        });
        JSONArray out = new JSONArray();
        for (JSONObject app : unique) out.put(app);
        return out;
    }

    private static JSONObject makeApp(String pkg, String label) {
        JSONObject o = new JSONObject();
        try {
            o.put("package", pkg);
            o.put("label", label);
        } catch (JSONException ignored) { }
        return o;
    }

    /**
     * Shell fallback for enumerating packages when PackageManager is unavailable.
     * {@code pm list packages} lists all installed packages (we can't cheaply
     * filter to launchable-only over shell, so the picker labels are package
     * names in this degraded path).
     */
    private static List<String> listPackagesViaShell() {
        List<String> pkgs = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("sh", "-c", "pm list packages -3")
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n));
            p.waitFor(5, TimeUnit.SECONDS);
            try { is.close(); } catch (Throwable ignored) { }
            for (String line : sb.toString().split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("package:")) {
                    String pkg = line.substring("package:".length()).trim();
                    if (!pkg.isEmpty()) pkgs.add(pkg);
                }
            }
        } catch (Throwable t) {
            logger.warn("listPackagesViaShell failed: " + t.getMessage());
        }
        return pkgs;
    }

    /** Run a short shell command bounded at 5s; true if it exited 0. */
    private static boolean runShell(String cmd) {
        try {
            final Process p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            final InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] b = new byte[4096];
                try { while (is.read(b) != -1) { /* discard */ } } catch (Throwable ignored) { }
            }, "applauncher-drain");
            drain.setDaemon(true);
            drain.start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); }
            drain.join(500);
            try { is.close(); } catch (Throwable ignored) { }
            return done && p.exitValue() == 0;
        } catch (Throwable t) {
            logger.warn("runShell failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Resolve the {@code package/activity} launcher component for a package.
     * Split-screen's {@code am start -n} needs the explicit component. Tries the
     * PackageManager (daemon Context) first, then a shell
     * {@code cmd package resolve-activity} fallback. Returns null if neither
     * yields a component.
     */
    private static String resolveLauncherComponent(String pkg) {
        Context ctx = CameraDaemon.getAppContext();
        if (ctx != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null && launch.getComponent() != null) {
                    return launch.getComponent().flattenToShortString();
                }
            } catch (Throwable t) {
                logger.warn("resolveLauncherComponent: PackageManager failed for " + pkg + " (" + t.getMessage() + ")");
            }
        }
        // Shell fallback: parse `cmd package resolve-activity --brief` for the component.
        try {
            Process p = new ProcessBuilder("sh", "-c",
                    "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " + shellQuote(pkg))
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n));
            p.waitFor(5, TimeUnit.SECONDS);
            try { is.close(); } catch (Throwable ignored) { }
            for (String line : sb.toString().split("\\r?\\n")) {
                line = line.trim();
                // A resolved component line looks like "pkg/.Activity" or "pkg/pkg.Activity".
                if (line.startsWith(pkg + "/")) return line;
            }
        } catch (Throwable t) {
            logger.warn("resolveLauncherComponent: shell resolve failed for " + pkg + " (" + t.getMessage() + ")");
        }
        return null;
    }

    /** Minimal shell quoting for a package name (defensive; pkg names are [A-Za-z0-9._]). */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** True if the string is a plausible Android package name ([A-Za-z0-9._], 1..255). */
    private static boolean isValidPackageName(String s) {
        if (s == null || s.isEmpty() || s.length() > 255) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!ok) return false;
        }
        return true;
    }
}

