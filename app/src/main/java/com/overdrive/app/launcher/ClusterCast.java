package com.overdrive.app.launcher;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.ClusterProjectionController;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Casts an arbitrary installed app onto the BYD driver-cluster (fission) display
 * for the "Move app to display → cluster" automation / key-mapping action.
 *
 * <p><b>Why this is not a bare {@code am start --display 1}.</b> On this firmware the
 * cluster display does NOT exist until the OEM projection is opened: the fission
 * {@code VirtualDisplay} is created by the projection open-sequence (size-profile →
 * fullscreen-on → Di4 mode) that {@link ClusterProjectionController} owns, and
 * SurfaceFlinger assigns its logical id per-open (it is NOT reliably 1 — on some trims
 * display 1 is the HEAD UNIT). A raw {@code --display 1} therefore either lands on
 * nothing or clobbers the infotainment screen. This mirrors the proven
 * {@code ClusterMapProjector} flow: acquire the projection as a sustained holder, wait
 * for the live fission display id to materialise, then {@code am start --display <id>
 * --windowingMode 1} the target app onto it. The gauge-restore safety net
 * (forceClose on ACC-off / disable / SIGTERM) is entirely inside the controller and is
 * unaffected by this class.
 *
 * <p><b>Difference from the map cast.</b> The map is OUR cooperative Activity (a
 * dedicated cluster alias that self-finishes off a UCM flag and is kept alive by a
 * daemon watchdog). A third-party app is NOT cooperative: the daemon (uid 2000) cannot
 * {@code finish()} a foreign uid-1000 Activity, and a non-touch 1920×720 panel offers
 * no way for the user to dismiss it. So this class deliberately does NOT run a
 * keep-alive watchdog or a self-finish handshake — it launches once (with a short
 * resume-verify + retry, since {@code am start} exits on ACCEPT not RESUMED) and holds
 * the projection open under the {@code "castapp"} token until {@link #stop()} (moving
 * back to the head unit, an explicit stop action, or ACC-off) releases it and the
 * controller restores the gauges. Holding open is what keeps the cast app visible; a
 * swipe-away simply leaves the projection showing whatever the OEM puts there until
 * stop, which is the safest non-cooperative behaviour (never repaints a ghost).
 *
 * <p>Runs the wait+launch off the caller's thread (the HTTP worker), exactly like the
 * map projector, so a slow projection open never blocks the request.
 */
public final class ClusterCast {

    private static final String TAG = "ClusterCast";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Distinct sustained token so this coexists with the map/blind-spot holders. */
    private static final String TOKEN = "castapp";

    // WINDOWING_MODE_FULLSCREEN — fills the whole fission panel. FREEFORM (5) placed a
    // ~270px floating box (the "small projection" symptom the map hit); fullscreen is
    // what the map + blind-spot use on this display. Verified via ClusterMapProjector.
    private static final String WINDOWING_MODE_FULLSCREEN = "1";
    // NEW_TASK only (0x10000000): the daemon Context is not an Activity, and a foreign
    // app owns its own task affinity, so NEW_TASK is sufficient and correct.
    private static final String LAUNCH_FLAGS = "0x10000000";

    // Poll budget for the fission display to appear after the projection opens
    // (materialises ~1-3s after the open opcodes). Mirrors ClusterMapProjector.
    private static final int READY_POLL_MS = 250;
    private static final int READY_TIMEOUT_MS = 8000;
    // Post-launch resume verify+retry (am start exits on ACCEPT, not RESUMED-on-display).
    private static final int LAUNCH_VERIFY_ATTEMPTS = 3;
    private static final int LAUNCH_VERIFY_POLLS_PER_ATTEMPT = 6;
    private static final int LAUNCH_VERIFY_POLL_MS = 300;

    // The package currently cast (for logging + so a second cast replaces the first).
    private static volatile String castPkg;
    private static volatile Thread launchThread;

    private ClusterCast() {}

    /**
     * True while an app is (being) cast onto the cluster. Self-reconciling: if the OEM
     * projection was torn down out from under us by a DIRECT forceClose (cluster-size
     * relayout, blind-spot disable, cluster→head-unit retarget, ACC-off) — which clears
     * ALL sustained holders including our {@code "castapp"} token but does not call our
     * {@link #stop()} — the projection no longer holds our token, so the cast is over.
     * We detect that here (no keep-alive loop needed for a non-cooperative app) and
     * reconcile {@code castPkg} to null, releasing our (already-dropped) token defensively.
     * Once a launch thread has been started (castPkg set + acquired) the projection is
     * held for us; the only time it wouldn't be is exactly this torn-down case.
     */
    /** The package currently (being) cast onto the cluster, or null if none. Read by the
     *  package-removed watcher to tear down a live cast whose app was just uninstalled. */
    public static synchronized String getCastPackage() { return castPkg; }

    public static synchronized boolean isActive() {
        String pkg = castPkg;
        if (pkg == null) return false;
        try {
            // While the launch thread may still be waiting for the display to materialise,
            // the "castapp" hold was already acquired in start() — so a missing hold means
            // a forceClose cleared it (teardown), not a not-yet-acquired state.
            if (!ClusterProjectionController.holdsTokenStatic(TOKEN)) {
                castPkg = null;
                launchThread = null;
                return false;
            }
        } catch (Throwable ignored) { /* fail toward the raw flag */ }
        return true;
    }

    /**
     * Begin casting {@code pkg} onto the cluster. Validates the package, resolves its
     * launcher component (trusted PackageManager / {@code cmd package resolve-activity},
     * reused from {@link AppLauncher}), acquires the OEM projection, waits for the live
     * fission display, then launches + verifies. Returns true if the request was
     * accepted (component resolved + projection acquired) — the actual on-screen resume
     * is confirmed asynchronously on the launch thread. Returns false only on a bad
     * package or an unresolvable component (so the caller can report failure), NOT on
     * the async resume race.
     */
    public static synchronized boolean start(String pkg) {
        if (pkg == null || pkg.trim().isEmpty() || !AppLauncher.isValidPackageName(pkg.trim())) {
            logger.warn("cluster cast: missing/invalid package");
            return false;
        }
        pkg = pkg.trim();
        final String component = AppLauncher.resolveLauncherComponent(pkg);
        if (component == null) {
            logger.warn("cluster cast: could not resolve launcher component for " + pkg);
            return false;
        }
        castPkg = pkg;
        logger.info("cluster cast: start " + pkg + " (" + component + ")");
        // Acquire our hold FIRST so the projection is pinned before we touch the map.
        try {
            ClusterProjectionController.getInstance().acquireSustained(TOKEN);
        } catch (Throwable t) {
            logger.warn("cluster cast: acquireSustained failed: " + t.getMessage());
            // acquireSustained adds the token to the holder set as its FIRST step, then
            // does more work that could throw — so a failure may leave "castapp" stranded
            // (suppressing auto-close, gauges blanked) with castPkg about to be nulled
            // (stop() then early-returns on castPkg==null and can't release it). Release
            // defensively so a partial acquire can't pin the projection open forever.
            try { ClusterProjectionController.getInstance().releaseSustained(TOKEN); }
            catch (Throwable ignored) {}
            castPkg = null;
            return false;
        }
        // Now stop any active nav-map projection. ORDER MATTERS: our "castapp" hold is
        // already in place, so the map's releaseSustained("map") sees a remaining holder
        // and leaves the projection OPEN (no close→reopen churn, no new fission id, no
        // gauge flash). stop() also sets the map's active=false (its keep-alive watchdog
        // exits on the next tick, so it never relaunches the map over the cast) and
        // clears navMap.clusterMapActive (the map Activity self-finishes ~500ms), freeing
        // the foreground for the cast app.
        try {
            if (com.overdrive.app.navmap.ClusterMapProjector.isActive()) {
                logger.info("cluster cast: stopping active map projection to take the cluster");
                com.overdrive.app.navmap.ClusterMapProjector.stop();
            }
        } catch (Throwable t) {
            logger.warn("cluster cast: map stop failed (continuing): " + t.getMessage());
        }
        final String castComponent = component;
        launchThread = new Thread(() -> waitAndLaunch(castComponent), "ClusterCastLaunch");
        launchThread.setDaemon(true);
        launchThread.start();
        return true;
    }

    /**
     * Stop casting from a USER-INITIATED, within-session path (the Projection screen's Stop
     * button, {@code cluster_cast_stop}). Releases the sustained hold so the controller
     * restores the gauges (when no other consumer — map / blind-spot — still wants the
     * projection) AND clears the cast task's stale cluster-display affinity in the background
     * so the app reopens on the infotainment (see {@link AppLauncher#reparentToDisplay0}).
     * Idempotent.
     */
    public static void stop() { stop(CLEAR_AFFINITY); }

    // stop() rehome modes.
    private static final int NO_REHOME     = 0;  // ACC-off / forceClose: touch nothing extra
    private static final int CLEAR_AFFINITY = 1; // background reparent to display 0 (no focus steal)
    private static final int FOREGROUND    = 2;  // explicit "move back to head unit" (brings to front)

    /**
     * Stop casting the ACC-OFF / teardown way: release the hold only, do NO shell/AMS work.
     * The ACC-off sequence has a load-bearing SurfaceFlinger teardown ordering (mirror VD
     * unbound before the fission source closes — see {@link com.overdrive.app.monitor.AccMonitor});
     * injecting an {@code am stack move-task} into that window risks the native crash cascade.
     * The stale cluster affinity is harmless until the next ACC-on reopen, and by then the VD
     * is typically truly removed (AMS auto-reparents) — and a user-initiated
     * {@link #stop()} on the Projection screen clears it explicitly anyway.
     */
    public static void stopForAccOff() { stop(NO_REHOME); }

    /** @param rehomeToHeadUnit true → the explicit "move app back to head unit" foreground
     *   reparent; false → the ACC-off-safe no-rehome path. Retained for the existing
     *   {@code move_display} caller; new call sites should use {@link #stop()} (background
     *   affinity clear) or {@link #stopForAccOff()}. */
    public static void stop(boolean rehomeToHeadUnit) {
        stop(rehomeToHeadUnit ? FOREGROUND : NO_REHOME);
    }

    private static void stop(int rehomeMode) {
        // Capture + clear state UNDER the lock, but do the slow shell reparent OUTSIDE it:
        // reparentToDisplay0 / launchOnDisplay spawn dumpsys + am (up to several seconds), and
        // holding the ClusterCast monitor that long would block isActive()/getCastPackage()/
        // a concurrent start() — including the CastPackageWatcher teardown path.
        final String pkg;
        synchronized (ClusterCast.class) {
            if (castPkg == null) return;
            pkg = castPkg;
            logger.info("cluster cast: stop " + pkg + " (rehomeMode=" + rehomeMode + ")");
            castPkg = null;
            launchThread = null;   // supersede any in-flight verify loop
        }
        // Reparent BEFORE releaseSustained so the fission display is still live — reparenting
        // between two LIVE displays is the well-supported AMS case; doing it after the source
        // is torn down would orphan the task.
        if (rehomeMode == FOREGROUND) {
            // Explicit user "move back to head unit" — bring it to the front on display 0.
            try { AppLauncher.launchOnDisplay(pkg, 0); }
            catch (Throwable t) { logger.warn("cluster cast: re-home to display 0 failed: " + t.getMessage()); }
        } else if (rehomeMode == CLEAR_AFFINITY) {
            // User stopped the cast (not driving-away ACC-off) — silently pull the task's
            // affinity back to display 0 so it reopens on the infotainment, WITHOUT bringing
            // it to the foreground (toTop=false). Best-effort; a parse failure is a no-op.
            try { AppLauncher.reparentToDisplay0(pkg); }
            catch (Throwable t) { logger.warn("cluster cast: clear affinity failed: " + t.getMessage()); }
        }
        try {
            ClusterProjectionController.getInstance().releaseSustained(TOKEN);
        } catch (Throwable t) {
            logger.warn("cluster cast: releaseSustained failed: " + t.getMessage());
        }
    }

    /**
     * True while this launch thread should keep working: we're still the current cast,
     * not superseded, AND the projection still holds our "castapp" token. The token
     * check is the key gate (mirrors ClusterMapProjector.isSustainedProjectionHeld):
     * a DIRECT forceClose (cluster-size relayout, blind-spot disable, cluster→head-unit
     * retarget) clears ALL sustained holders + restores the gauges WITHOUT routing
     * through stop(), leaving castPkg stale-true. Without this gate the launch/verify
     * loop would re-issue `am start` and repaint the cast app over the just-restored
     * gauges (the fission VirtualDisplay outlives the OEM 18→0 close). When we detect the
     * token is gone, reconcile our own flags so isActive() and a later start() are correct.
     */
    private static boolean stillOurs(Thread self) {
        if (castPkg == null || launchThread != self) return false;
        boolean held;
        try {
            held = ClusterProjectionController.holdsTokenStatic(TOKEN);
        } catch (Throwable t) {
            held = true;   // fail toward continuing (never worse than the pre-gate behaviour)
        }
        if (!held) {
            // Torn down by a direct forceClose. Reconcile under the monitor, identity-guarded
            // so a concurrent start() that already replaced launchThread keeps its state.
            synchronized (ClusterCast.class) {
                if (launchThread == self) {
                    logger.info("cluster cast: projection no longer holds our token "
                            + "(closed via a direct forceClose — relayout / bs-disable / retarget); "
                            + "aborting launch without repaint");
                    castPkg = null;
                    launchThread = null;
                }
            }
            return false;
        }
        return true;
    }

    private static void waitAndLaunch(String component) {
        final Thread self = Thread.currentThread();
        int waited = 0;
        int displayId = -1;
        // Wait for a POSITIVE fission displayId (never 0 = built-in head unit). The
        // VirtualDisplay materialises 1-3s after the open opcodes, so an early resolve
        // transiently returns -1.
        while (stillOurs(self) && waited < READY_TIMEOUT_MS) {
            int id = resolveFissionDisplayId();
            if (id > 0) { displayId = id; break; }
            try { Thread.sleep(READY_POLL_MS); } catch (InterruptedException e) { return; }
            waited += READY_POLL_MS;
        }
        if (!stillOurs(self)) return;   // stopped / superseded / projection torn down
        if (displayId <= 0) {
            // No fission display materialised — non-fission trim, or display 1 is the
            // head unit here. Do NOT blind-fall-back to 1 (would clobber infotainment).
            // Abort + release so the controller restores the gauges.
            logger.warn("cluster cast: fission display not resolved (>0) in " + READY_TIMEOUT_MS
                    + "ms — aborting (no clobber of display 0)");
            stop();
            return;
        }
        launchOnDisplay(component, displayId, self);
    }

    /** {@code am start --display N} the app, then verify it RESUMED on that display,
     *  retrying if it lost the resume race (same rationale as ClusterMapProjector). */
    private static void launchOnDisplay(String component, int displayId, Thread self) {
        for (int attempt = 1; stillOurs(self) && attempt <= LAUNCH_VERIFY_ATTEMPTS; attempt++) {
            if (!issueLaunch(component, displayId, attempt, self)) {
                if (!sleepWhileActive(LAUNCH_VERIFY_POLL_MS, self)) return;
                continue;
            }
            for (int poll = 0; stillOurs(self) && poll < LAUNCH_VERIFY_POLLS_PER_ATTEMPT; poll++) {
                if (!sleepWhileActive(LAUNCH_VERIFY_POLL_MS, self)) return;
                if (isResumedOnDisplay(component, displayId)) {
                    logger.info("cluster cast: RESUMED on displayId " + displayId
                            + " (attempt " + attempt + ")");
                    return;   // no keep-alive for a foreign app (see class doc)
                }
            }
            logger.warn("cluster cast: NOT resumed on displayId " + displayId
                    + " after attempt " + attempt + " — re-launching");
        }
        if (stillOurs(self)) {
            logger.warn("cluster cast: failed to confirm resume on displayId " + displayId
                    + " after " + LAUNCH_VERIFY_ATTEMPTS + " attempts (projection stays up; "
                    + "gauges restore on the normal stop / ACC-off path)");
        }
    }

    /** Issue one {@code am start --display N}. Returns true iff it exited 0. */
    private static boolean issueLaunch(String component, int displayId, int attempt, Thread self) {
        try {
            // Final liveness re-check IMMEDIATELY before exec: shrinks the check-then-act
            // window between the loop guard and the launch to microseconds, so a forceClose
            // (relayout / bs-disable / retarget) that landed in between aborts here instead
            // of firing one stray `am start` over the just-restored gauges.
            if (!stillOurs(self)) return false;
            String[] cmd = {
                "am", "start",
                "--user", "0",
                "--display", String.valueOf(displayId),
                "--windowingMode", WINDOWING_MODE_FULLSCREEN,
                "-f", LAUNCH_FLAGS,
                "-n", component
            };
            logger.info("cluster cast: launching " + component + " onto displayId " + displayId
                    + " (attempt " + attempt + ")");
            Process proc = Runtime.getRuntime().exec(cmd);
            boolean done = proc.waitFor(5, TimeUnit.SECONDS);
            if (!done) { proc.destroy(); logger.warn("cluster cast: am start did not exit in 5s"); return false; }
            if (proc.exitValue() != 0) { logger.warn("cluster cast: am start exited " + proc.exitValue()); return false; }
            return true;
        } catch (Throwable t) {
            logger.warn("cluster cast: am start failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Resolve the current fission cluster displayId from {@code dumpsys display} (the
     * id whose display name contains "fission", or -1). Read live — SurfaceFlinger
     * assigns it per open and the daemon's DisplayManager cache doesn't see the foreign
     * uid-1000 display. Mirrors ClusterMapProjector.resolveFissionDisplayId.
     */
    private static int resolveFissionDisplayId() {
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "display").redirectErrorStream(true).start();
            final Process fp = p;
            // Read on a daemon thread so a wedged dumpsys (never reaching EOF) can't block
            // the launch thread forever; we cap the whole read at 3s and destroy on timeout.
            final int[] result = { -1 };
            final Thread reader = new Thread(() -> {
                try {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(fp.getInputStream()));
                    String line;
                    int found = -1;
                    while ((line = r.readLine()) != null) {
                        if (!line.toLowerCase(Locale.US).contains("fission")) continue;
                        int id = extractDisplayIdOnLine(line);
                        if (id >= 0) { found = id; if (id > 0) break; }
                    }
                    result[0] = found;
                } catch (Throwable ignored) { /* result stays -1 */ }
            }, "ClusterCastDisplayScan");
            reader.setDaemon(true);
            reader.start();
            reader.join(3000);
            return result[0];
        } catch (Throwable t) {
            logger.warn("cluster cast: resolveFissionDisplayId failed: " + t.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
        return -1;
    }

    /** Pull the integer after "displayid" (followed by ' ' or '=') on a single line, or -1. */
    private static int extractDisplayIdOnLine(String line) {
        String low = line.toLowerCase(Locale.US);
        int idx = low.indexOf("displayid");
        while (idx >= 0) {
            int i = idx + "displayid".length();
            while (i < low.length() && (low.charAt(i) == '=' || low.charAt(i) == ' ')) i++;
            int start = i;
            while (i < low.length() && Character.isDigit(low.charAt(i))) i++;
            if (i > start) {
                try { return Integer.parseInt(low.substring(start, i)); } catch (Throwable ignored) {}
            }
            idx = low.indexOf("displayid", idx + 1);
        }
        return -1;
    }

    /**
     * True iff {@code component} is the RESUMED activity on {@code displayId} per
     * {@code dumpsys activity activities}. Scans the "Display #N" block for a
     * ResumedActivity line naming the component's package. Conservative: any parse
     * failure returns false (→ retry), never a false positive.
     */
    private static boolean isResumedOnDisplay(String component, int displayId) {
        // Match on the package (component is "pkg/act"); the resumed line names the
        // full component but lower-casing + package containment is robust across builds.
        String pkg = component;
        int slash = component.indexOf('/');
        if (slash > 0) pkg = component.substring(0, slash);
        String needle = pkg.toLowerCase(Locale.US);
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "activity", "activities")
                    .redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            boolean inTargetDisplay = false;
            String displayHeader = "display #" + displayId;
            while ((line = r.readLine()) != null) {
                String low = line.toLowerCase(Locale.US);
                int hdr = low.indexOf("display #");
                if (hdr >= 0) {
                    inTargetDisplay = low.startsWith(displayHeader, hdr)
                            && !Character.isDigit(charAt(low, hdr + displayHeader.length()));
                    continue;
                }
                if (inTargetDisplay && low.contains("resumedactivity") && low.contains(needle)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            logger.debug("cluster cast: isResumedOnDisplay failed: " + t.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
        return false;
    }

    private static char charAt(String s, int i) {
        return (i >= 0 && i < s.length()) ? s.charAt(i) : ' ';
    }

    /** Sleep {@code ms}, returning false if the cast was stopped / superseded / torn down
     *  (via {@link #stillOurs}) meanwhile — so the caller aborts promptly and never
     *  re-issues an {@code am start} after teardown. */
    private static boolean sleepWhileActive(int ms, Thread self) {
        if (!stillOurs(self)) return false;
        try { Thread.sleep(ms); } catch (InterruptedException e) { return false; }
        return stillOurs(self);
    }
}
