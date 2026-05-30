package com.overdrive.app.monitor;

import com.overdrive.app.daemon.CameraDaemon;

/**
 * ACC Monitor - State holder for ACC status with direct hardware query.
 * 
 * ACC state detection is handled by AccSentryDaemon which:
 * 1. Uses BYDAutoBodyworkDevice listener for real ACC events
 * 2. Falls back to sys.accanim.status polling
 * 3. Sends IPC commands to SurveillanceEngine on port 19877
 * 
 * On CameraDaemon restart (e.g., after EGL crash), the ACC state is read
 * directly from BYDAutoBodyworkDevice.getPowerLevel() so the daemon can
 * re-enter sentry mode without depending on AccSentryDaemon IPC.
 */
public class AccMonitor {

    // Power levels from BYDAutoBodyworkDevice (same as AccSentryDaemon)
    private static final int POWER_LEVEL_OFF = 0;
    private static final int POWER_LEVEL_ACC = 1;
    private static final int POWER_LEVEL_ON = 2;

    private static volatile boolean inSentryMode = false;
    // Default to false (ACC off) - safer assumption until AccSentryDaemon confirms state
    // This prevents false "acc: true" in status when daemon restarts
    private static volatile boolean accOn = false;

    // Track the last sentinel state we logged (FAKE_OK=4, INVALID=255, or
    // out-of-range value), so we log only on transitions. Without this,
    // a persistently broken HAL would emit ~2880 "powerLevel=INVALID" lines
    // per day. -1 = no sentinel currently observed (last reading was a
    // real 0/1/2/3 or no probe has run yet).
    private static volatile int lastLoggedSentinel = -1;

    // Cached reflection for probeAccState. Without caching, every probe
    // (called every 5s by CameraDaemon.startAccOnDisarmWatchdog while
    // sentry is active = ~17,000 probes/day overnight) re-runs
    // Class.forName + 2× getMethod. The HAL surface is fixed at boot, so
    // resolve once and reuse. Volatile for safe publication; idempotent
    // double-resolve race is acceptable.
    //
    // Mirrors the pattern already used in RecordingModeManager
    // .resolveBodyworkReflection — same target Class+Methods, same
    // resolved/failed semantics.
    private static volatile Class<?> bodyworkDeviceClassCache;
    private static volatile java.lang.reflect.Method bodyworkGetInstanceCache;
    private static volatile java.lang.reflect.Method bodyworkGetPowerLevelCache;
    private static volatile boolean bodyworkReflectionResolved = false;
    private static volatile boolean bodyworkReflectionFailed = false;

    private static void resolveBodyworkReflection() {
        if (bodyworkReflectionResolved || bodyworkReflectionFailed) return;
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            java.lang.reflect.Method getInstance =
                cls.getMethod("getInstance", android.content.Context.class);
            java.lang.reflect.Method getPowerLevel = cls.getMethod("getPowerLevel");
            bodyworkDeviceClassCache = cls;
            bodyworkGetInstanceCache = getInstance;
            bodyworkGetPowerLevelCache = getPowerLevel;
            // MUST be the last write — readers that observe resolved=true rely
            // on volatile happens-before to see the three Class/Method fields
            // already populated. Reordering this above the cache assignments
            // would let a racing reader see resolved=true with null Methods.
            bodyworkReflectionResolved = true;
        } catch (Exception e) {
            // Permanent — class/method genuinely missing on this firmware.
            // Per-call invoke failures (transient binder errors) do NOT
            // come through here; they hit the outer catch in probeAccState.
            bodyworkReflectionFailed = true;
            CameraDaemon.log("AccMonitor: BYDAutoBodyworkDevice reflection unavailable: "
                + e.getMessage());
        }
    }

    public static boolean isAccOn() {
        return accOn;
    }

    public static boolean isInSentryMode() {
        return inSentryMode;
    }

    /**
     * Called by SurveillanceEngine IPC when AccSentryDaemon sends ACC state.
     */
    public static void setAccState(boolean isAccOn) {
        accOn = isAccOn;
        inSentryMode = !isAccOn;
        CameraDaemon.log("ACC state updated via IPC: accOn=" + isAccOn + ", sentryMode=" + inSentryMode);
    }

    /**
     * Reads ACC state directly from BYDAutoBodyworkDevice hardware.
     * No dependency on AccSentryDaemon or file persistence.
     * 
     * @param context Android context for BYD device API
     * @return true if ACC is OFF (sentry mode should be active), false if ACC is ON or unknown
     */
    public static boolean probeAccState(android.content.Context context) {
        resolveBodyworkReflection();
        if (!bodyworkReflectionResolved) {
            // Class genuinely missing on this firmware — safe default.
            // Don't enter sentry on a permanent reflection failure.
            return false;
        }
        try {
            Object device = bodyworkGetInstanceCache.invoke(null, context);

            if (device == null) {
                CameraDaemon.log("AccMonitor: BYDAutoBodyworkDevice.getInstance returned null");
                return false;
            }

            int level = (Integer) bodyworkGetPowerLevelCache.invoke(device);

            // Only trust the four legitimate power levels (0/1/2/3). The HAL
            // can also return FAKE_OK=4 or INVALID=255, both of which mean
            // "this reading is untrustworthy." Treating either as ACC=ON
            // (because both are >= POWER_LEVEL_ON=2) would incorrectly drop
            // sentry mode. On sentinel/unknown, KEEP the prior state — the
            // last IPC from AccSentryDaemon is more reliable than a HAL
            // bluff. Return true (sentry) only if we're confident ACC=OFF.
            if (level < 0 || level > 3) {
                // Log only when entering a new sentinel state; otherwise a
                // persistently broken HAL would flood the log at the probe
                // interval. Reset the sentinel tracker once we observe a
                // real value again (handled in the success branch below).
                if (lastLoggedSentinel != level) {
                    CameraDaemon.log("AccMonitor: hardware probe powerLevel="
                        + (level == 4 ? "FAKE_OK" : (level == 255 ? "INVALID" : "UNKNOWN(" + level + ")"))
                        + " — keeping prior accOn=" + accOn);
                    lastLoggedSentinel = level;
                }
                return !accOn;
            }
            // Real reading — clear the sentinel tracker so the next sentinel
            // (if any) gets logged. Also log the recovery once.
            if (lastLoggedSentinel != -1) {
                CameraDaemon.log("AccMonitor: hardware probe recovered (level=" + level + ")");
                lastLoggedSentinel = -1;
            }

            boolean isAccOn = level >= POWER_LEVEL_ON;
            accOn = isAccOn;
            inSentryMode = !isAccOn;

            String levelStr;
            switch (level) {
                case 0: levelStr = "OFF"; break;
                case 1: levelStr = "ACC"; break;
                case 2: levelStr = "ON"; break;
                case 3: levelStr = "OK"; break;
                default: levelStr = "UNKNOWN(" + level + ")"; break;
            }
            CameraDaemon.log("AccMonitor: hardware probe powerLevel=" + levelStr +
                " → accOn=" + isAccOn + ", sentryMode=" + inSentryMode);

            return !isAccOn;  // true if ACC is OFF
        } catch (Exception e) {
            CameraDaemon.log("AccMonitor: hardware probe failed: " + e.getMessage());
            return false;  // assume ACC ON (safe default — don't enter sentry on error)
        }
    }

    /**
     * No-op start method for backward compatibility with CameraDaemon.
     */
    public void start() {
        CameraDaemon.log("AccMonitor: passive mode (ACC detection by AccSentryDaemon)");
    }

    /**
     * No-op stop method for backward compatibility.
     */
    public void stop() {
        // Nothing to stop
    }
}
