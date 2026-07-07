package com.overdrive.app.server;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.mqtt.VehicleControlCatalog;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Daemon-side executor for physical-key mappings.
 *
 * The key interception itself lives in {@code KeepAliveAccessibilityService}
 * (onKeyEvent → {@code KeyMapDispatcher}), which runs in the <b>app</b> process
 * (UID 10xxx). BYD SDK actuation, however, only
 * clears the signature-permission wall from the <b>daemon</b> process (UID 2000),
 * which is where {@link VehicleCommandRouter} and {@link BydDataCollector} live.
 * So the a11y service does not actuate directly — it POSTs the bound action here
 * (via {@code DaemonHttpClient}, JWT-authenticated) and this handler runs it in
 * the daemon, identical to how {@link com.overdrive.app.mqtt.MqttCommandRouter}
 * dispatches Home-Assistant control commands.
 *
 * Endpoints:
 *   GET  /api/keymap/config — { enabled, allowAdvanced, bindings[],
 *                               a11yEnabled, a11yBound, a11yPending }
 *       a11yEnabled = OS bind PRECONDITION (listed + master-on, or in-proc).
 *       a11yBound   = service ACTUALLY bound/live (in-proc isRunning, or a live
 *                     ServiceRecord via dumpsys) — the honest "keys will fire" truth.
 *       a11yPending = precondition met but not yet bound (the transient enable-edge
 *                     window); the client can show "enabling…" and confirm the real
 *                     bind before hiding the nudge instead of trusting a11yEnabled.
 *   POST /api/keymap/config — persist { enabled, allowAdvanced, bindings[] };
 *                             response echoes { success, a11yEnabled, a11yBound,
 *                             a11yPending } (auto-enables the service on the
 *                             enable edge).
 *   POST /api/keymap/fire  — body { kind, ... } → run one bound action
 *
 * Action kinds (mirror the settings UI's curated picker + advanced escape hatch):
 *   { "kind":"catalog", "key":"drl", "sub":null, "payload":"on" }
 *       Resolve against {@link VehicleControlCatalog} and executeSdkOnly — the
 *       full curated set (drl, sunroof, sunshade, windows_all, tailgate, seat_*,
 *       climate, charge_cap_*, child_lock, wireless_charging, adas_*).
 *   { "kind":"vehicle", "action":"lock|unlock|flash|find_car" }
 *       Composite cloud-first commands not registered in the catalog.
 *   { "kind":"shell", "cmd":"..." }         — advanced, gated by allowAdvanced
 *   { "kind":"sequence", "steps":[ {kind:...}, ... ] }
 *       Run several of the above actions in order on one keypress. Best-effort:
 *       a failing step is recorded but does not abort the rest.
 *
 * All actuation runs synchronously on the caller's request thread (the HttpServer
 * worker), never the app's input-dispatch thread — the a11y service fires the
 * POST on a background executor and does not block on the result.
 */
public final class KeymapApiHandler {

    private static final DaemonLogger logger = DaemonLogger.getInstance("KeymapApi");

    // ── Accessibility bind watchdog (daemon-side supervisor) ─────────────────
    // The key-filter rides on KeepAliveAccessibilityService in the APP process,
    // which shares one heavy main looper with the WebView, two MapLibre GL
    // surfaces, four foreground services and the overlays. On a long-lived app
    // process we observed the OS leave that service stuck in AMS "Binding" and
    // never promote it to "Bound" — onServiceConnected never runs, onKeyEvent
    // never fires, so every mapped wheel button falls straight through to the
    // OEM (and because this firmware delivers one press as a burst, the OEM
    // action repeats — the "looping next-track" symptom). A FRESH app process
    // binds cleanly every time; the only reliable recovery is to kill the wedged
    // process so AMS re-binds into a new one (a toggle of the Secure setting does
    // NOT un-wedge it — verified on-device).
    //
    // This daemon runs as UID 2000, a DIFFERENT uid than the app, so it survives
    // `am force-stop com.overdrive.app` (same reason SocCutoffMonitor pkills the
    // daemon separately). That makes it the stable supervisor: it periodically
    // checks the bind and, only when the service is enabled-but-not-bound for a
    // sustained window (a genuine wedge, not the transient async-bind gap after
    // enable), force-restarts the app so it respawns with a clean bind.
    //
    // Zero-cost + non-disruptive by construction:
    //   • Does nothing unless keymap is enabled AND at least one binding exists —
    //     the restart is FOR key mapping, so we never bounce the app for a
    //     feature the user hasn't configured.
    //   • The precondition-missing case (listed-but-master-off / not listed) is
    //     healed with the cheap `settings put` re-assert, never a restart.
    //   • The disruptive force-restart fires ONLY on a confirmed sustained wedge
    //     (keys already dead), is rate-limited so a pathological case can't
    //     restart-loop the app, and respawns HEADLESSLY (starts the keep-alive
    //     service, not the UI) so navigation/UI aren't yanked to the foreground.
    private static final long WATCHDOG_POLL_MS = 60_000L;
    // Backoff poll used once the state is HEALTHY or IDLE (bound-and-fine, or the
    // feature is off / has no bindings). In those states there is nothing to catch
    // quickly, so the priciest per-tick work (a `dumpsys activity services` exec on
    // the enabled+healthy path) doesn't need to run every 60s. Widen to 5 min while
    // healthy/idle; the loop snaps back to WATCHDOG_POLL_MS the instant a tick sees
    // a not-bound (potential-wedge) or precondition-missing state, so wedge
    // detection latency is unchanged in the case that matters. Cuts the steady-state
    // enabled dumpsys rate ~5×, and is a no-op when disabled (idle path never execs).
    private static final long WATCHDOG_HEALTHY_POLL_MS = 300_000L;
    // Delay the first check so we never mistake the app's own startup (or a fresh
    // post-restart bind) for a wedge. The daemon is itself launched ~45-90s after
    // the app, so by our first tick the a11y service has normally long since bound.
    private static final long WATCHDOG_INITIAL_DELAY_MS = 90_000L;
    // Require this many consecutive enabled-but-not-bound polls before the hammer.
    // The transient async-bind window after enable is seconds and closes between
    // 60s polls, so 2 confirms a real wedge without over-reacting to a slow bind.
    private static final int WATCHDOG_WEDGE_CONFIRM_CHECKS = 2;
    // Never force-restart more often than this. Not a retry CAP (the watchdog
    // keeps supervising forever, per the "no retry-cap watchdogs" rule) — just a
    // cooldown so a fresh process that itself takes a while to bind can't trigger
    // a restart-loop. A clean bind normally lands within ~10s of the respawn.
    private static final long WATCHDOG_MIN_RESTART_INTERVAL_MS = 10L * 60_000L;
    private static final String APP_PACKAGE = "com.overdrive.app";

    private static volatile boolean watchdogStarted = false;
    // Only ever read/written by the single watchdog thread → no synchronization.
    private static long lastForceRestartMs = 0L;
    // Set by watchdogTick each call: true when the observed state is fully settled
    // (feature off/idle, or bound-and-healthy) so the loop may back off to the wider
    // poll; false when actively watching (not-bound, precondition-missing, or just
    // force-restarted) so the loop stays on the tight poll. Watchdog-thread-only.
    private static boolean watchdogTickSettled = false;

    /**
     * Start the accessibility bind watchdog. Idempotent; call once at daemon
     * boot. Runs on its own daemon thread (blocking `settings`/`dumpsys` execs
     * must never touch an HTTP worker), and supervises the app-process a11y
     * bind for the life of the daemon.
     */
    public static synchronized void startAccessibilityWatchdog() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(WATCHDOG_INITIAL_DELAY_MS);
            } catch (InterruptedException ie) {
                return;
            }
            int notBound = 0;
            while (true) {
                long nextSleep = WATCHDOG_POLL_MS;
                try {
                    notBound = watchdogTick(notBound);
                    // Back off to the wider interval only when fully settled: bound-
                    // and-healthy or feature-off/idle (watchdogTickSettled tracks which
                    // branch the tick took). Any not-bound (wedge-in-progress),
                    // precondition-missing, or just-restarted state keeps the tight
                    // WATCHDOG_POLL_MS so wedge detection/recovery latency is unchanged.
                    nextSleep = watchdogTickSettled ? WATCHDOG_HEALTHY_POLL_MS : WATCHDOG_POLL_MS;
                } catch (Throwable th) {
                    logger.warn("Keymap a11y watchdog tick failed: " + th.getMessage());
                }
                try {
                    Thread.sleep(nextSleep);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }, "keymap-a11y-watchdog");
        t.setDaemon(true);
        t.start();
        logger.info("Keymap a11y watchdog started (poll=" + (WATCHDOG_POLL_MS / 1000)
                + "s, confirm=" + WATCHDOG_WEDGE_CONFIRM_CHECKS
                + ", restart-cooldown=" + (WATCHDOG_MIN_RESTART_INTERVAL_MS / 1000) + "s)");
    }

    /**
     * One watchdog evaluation. Returns the updated consecutive-not-bound counter.
     * Reads the keymap section via the cache-fresh getKeymap() path (which picks up
     * a cross-UID edit on the next real on-disk change, no forced re-parse), then
     * walks the escalation ladder: off/no-bindings → idle; precondition-missing →
     * cheap re-assert; enabled+not-bound (sustained) → force-restart; enabled+bound
     * → healthy.
     */
    private static int watchdogTick(int notBound) {
        // Read the keymap section WITHOUT forceReload(). getKeymap()->loadConfig()
        // already re-parses on a real on-disk change (isCacheFresh's mtime check
        // catches cross-UID writes) and returns the cheap in-memory cache otherwise.
        // The earlier forceReload() here nulled the PROCESS-WIDE config cache every
        // 60s unconditionally — even when keymap was disabled — forcing the ~44
        // other subsystems that read overdrive_config.json to re-parse from disk on
        // the next access. That defeated the very cache designed to prevent it and
        // is a real, self-inflicted periodic cost. Plain reads are correct and cheap.
        boolean enabled = com.overdrive.app.config.UnifiedConfigManager.isKeymapEnabled();
        int bindingCount;
        try {
            bindingCount = com.overdrive.app.config.UnifiedConfigManager.getKeymapBindings().length();
        } catch (Throwable t) {
            bindingCount = 0;
        }
        if (!enabled || bindingCount == 0) {
            // Feature off or nothing to fire — the a11y bind is irrelevant to key
            // mapping, so don't supervise it (process keep-alive is separately
            // covered by DaemonKeepaliveService). Reset so a later enable starts fresh.
            // Settled/idle: the loop may back off to the wider poll (this path never
            // execs anything, so even the tight cadence was cheap — but no reason to
            // re-read config every 60s when off).
            watchdogTickSettled = true;
            return 0;
        }

        boolean precondition = isAccessibilityServiceEnabled();
        if (!precondition) {
            // Listed-but-master-off, or not listed at all. Cheap, non-disruptive
            // fix: re-assert the Secure setting (settings put). No restart. Stay on
            // the TIGHT poll so we re-verify the assert took hold promptly.
            logger.info("Keymap a11y watchdog: enable precondition missing — re-asserting accessibility settings");
            ensureAccessibilityServiceEnabled();
            watchdogTickSettled = false;
            return 0;
        }

        if (isAccessibilityServiceBound()) {
            watchdogTickSettled = true; // healthy — bound and dispatching; back off
            return 0;
        }

        // Precondition met but the service is NOT bound. Could be the transient
        // async-bind window right after enable/boot, so confirm it's sustained.
        // Actively watching — keep the tight poll.
        watchdogTickSettled = false;
        notBound++;
        logger.warn("Keymap a11y watchdog: service enabled but NOT bound ("
                + notBound + "/" + WATCHDOG_WEDGE_CONFIRM_CHECKS + " consecutive)");
        if (notBound < WATCHDOG_WEDGE_CONFIRM_CHECKS) {
            return notBound;
        }

        long now = System.currentTimeMillis();
        if (lastForceRestartMs != 0L && (now - lastForceRestartMs) < WATCHDOG_MIN_RESTART_INTERVAL_MS) {
            long sinceS = (now - lastForceRestartMs) / 1000;
            logger.warn("Keymap a11y watchdog: wedge persists but within restart cooldown ("
                    + sinceS + "s < " + (WATCHDOG_MIN_RESTART_INTERVAL_MS / 1000)
                    + "s) — deferring restart, will re-check next tick");
            return notBound; // keep counting; fire once the cooldown clears
        }

        logger.warn("Keymap a11y watchdog: CONFIRMED sustained bind wedge — force-restarting "
                + "the app process so AMS re-binds the accessibility service into a fresh process");
        forceRestartAppForA11y();
        lastForceRestartMs = now;
        return 0; // give the fresh process a full poll interval to come up + bind
    }

    /**
     * Kill the wedged app process and respawn it headlessly. `am force-stop`
     * clears the wedged ServiceRecord and puts the package in the stopped state;
     * an explicit component start then clears that flag and spawns a fresh
     * process, on whose startup AMS re-binds the (still-enabled) accessibility
     * service — the clean bind we verified a fresh process always gets.
     *
     * We respawn via the keep-alive foreground service (NOT MainActivity) so the
     * UI isn't pulled to the foreground mid-drive; OverdriveApplication.onCreate
     * runs on ANY process start, so the daemon-startup + a11y-enable path fires
     * regardless of which component brought the process up. The daemon runs as
     * UID 2000, so `am force-stop com.overdrive.app` (UID 10xxx) does not take
     * this supervisor down with it. Bounded exec so a wedged `am` can't hang the
     * watchdog thread.
     */
    private static void forceRestartAppForA11y() {
        String script =
                "am force-stop " + APP_PACKAGE + "; " +
                "sleep 3; " +
                "am start-foreground-service -n " + APP_PACKAGE
                        + "/.services.DaemonKeepaliveService";
        try {
            final Process p = new ProcessBuilder("sh", "-c", script)
                    .redirectErrorStream(true).start();
            final java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[2048];
                try { while (is.read(buf) != -1) { /* discard */ } }
                catch (Throwable ignored) { }
            }, "keymap-a11y-restart-drain");
            drain.setDaemon(true);
            drain.start();
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            drain.join(500);
            try { is.close(); } catch (Throwable ignored) { }
            logger.info("Keymap a11y watchdog: force-restart issued (force-stop + headless keep-alive respawn)");
        } catch (Throwable t) {
            logger.warn("Keymap a11y watchdog: force-restart failed: " + t.getMessage());
        }
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if (cleanPath.equals("/api/keymap/config") && method.equals("GET")) {
            handleGetConfig(out);
            return true;
        }
        if (cleanPath.equals("/api/keymap/config") && method.equals("POST")) {
            handleSaveConfig(out, body);
            return true;
        }
        if (cleanPath.equals("/api/keymap/fire") && method.equals("POST")) {
            handleFire(out, body);
            return true;
        }
        return false;
    }

    /**
     * Return the whole keymap section plus a derived {@code a11yEnabled} flag so
     * the settings page can prompt the user to turn the accessibility service on
     * (key filtering is inert until it is). We read the section fresh — the app
     * or a prior POST may have written it under a different UID.
     */
    private static void handleGetConfig(OutputStream out) throws Exception {
        com.overdrive.app.config.UnifiedConfigManager.forceReload();
        JSONObject section = com.overdrive.app.config.UnifiedConfigManager.getKeymap();
        boolean enabled = section.optBoolean("enabled", false);
        boolean a11yOn = isAccessibilityServiceEnabled();
        // Distinct honest bind signal (see isAccessibilityServiceBound): a11yOn is
        // the OS BIND PRECONDITION (listed + master-on, or in-proc), which AMS may
        // have satisfied without having finished binding the app-process service —
        // so keys can still be briefly dead while a11yEnabled reads true. a11yBound
        // is the stronger truth (an active ServiceRecord / live in-proc instance),
        // and a11yPending marks the transient window between the two. The client
        // can render "enabling…" on a11yPending instead of "ready", and confirm the
        // real bind before hiding the nudge, rather than trusting the precondition.
        boolean a11yBound = isAccessibilityServiceBound();
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("enabled", enabled);
        resp.put("allowAdvanced", section.optBoolean("allowAdvanced", false));
        resp.put("bindings", section.optJSONArray("bindings") != null
                ? section.optJSONArray("bindings") : new org.json.JSONArray());
        resp.put("a11yEnabled", a11yOn);
        resp.put("a11yBound", a11yBound);
        resp.put("a11yPending", a11yOn && !a11yBound);
        HttpResponse.sendJson(out, resp.toString());

        // Load-time self-heal: the reported on-car state is an already-enabled
        // keymap config whose a11y service is off (listed-but-master-off, or not
        // listed) — which the enable-edge POST alone never recovers, since the
        // user isn't re-toggling. If mapping is ON but a11y is NOT enabled, kick
        // ensureAccessibilityServiceEnabled() so it recovers on page load without
        // the non-obvious toggle-off-then-on dance.
        //
        // Runs OFF this request thread: ensureAccessibilityServiceEnabled() blocks
        // on ~2 settings execs (waitFor up to 3s) plus two read-backs (~4s), which
        // must never park the HttpServer worker or add several seconds to page
        // load. We deliberately report a11yEnabled from the PRE-heal read above
        // rather than blocking this GET on the heal (that stall is the whole
        // reason the heal is async). The healed truth is served by any SUBSEQUENT
        // GET of this endpoint once the background heal has landed, so the client
        // must re-fetch /api/keymap/config after the heal cost (~4-5s) to pick up
        // a11yEnabled=true and hide the nudge. That client-side half now EXISTS in
        // key-mapping.js (load()'s a11yRecheckPending-guarded setTimeout(...,5500)
        // re-fetch, which self-terminates once a11yEnabled flips true), so a manual
        // reload is no longer required. The guard below keeps overlapping GETs
        // (including that client re-fetch) from stacking multiple concurrent
        // heals — one in flight is enough.
        if (enabled && !a11yOn) {
            maybeSelfHealAccessibility();
        }
    }

    /**
     * Fire an off-thread accessibility self-heal at most one at a time. The guard
     * prevents a polling client (or several open tabs) from spawning a pile of
     * concurrent {@code settings} exec bursts; a heal already in flight is enough.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean healInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void maybeSelfHealAccessibility() {
        if (!healInFlight.compareAndSet(false, true)) return; // one at a time
        Thread t = new Thread(() -> {
            try {
                logger.info("Keymap a11y: load-time self-heal — mapping enabled but a11y off, re-asserting");
                ensureAccessibilityServiceEnabled();
            } catch (Throwable th) {
                logger.warn("Keymap a11y: load-time self-heal failed: " + th.getMessage());
            } finally {
                healInFlight.set(false);
            }
        }, "keymap-a11y-selfheal");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Persist the keymap section. The page sends the full desired state
     * {enabled, allowAdvanced, bindings:[...]}; we replace the section wholesale
     * (updateSection) so a removed binding actually disappears rather than
     * lingering from a key-merge.
     */
    private static void handleSaveConfig(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        if (body == null || body.isBlank()) {
            HttpResponse.sendJsonError(out, "Empty request body");
            return;
        }
        JSONObject req;
        try {
            req = new JSONObject(body);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Invalid JSON");
            return;
        }

        JSONObject section = new JSONObject();
        section.put("enabled", req.optBoolean("enabled", false));
        section.put("allowAdvanced", req.optBoolean("allowAdvanced", false));
        // Preserve the bindings array as sent (validated client-side; the fire
        // path re-validates action kind and gates advanced anyway).
        section.put("bindings", req.optJSONArray("bindings") != null
                ? req.optJSONArray("bindings") : new org.json.JSONArray());

        boolean ok = com.overdrive.app.config.UnifiedConfigManager.setKeymap(section);
        // Self-heal: when the user turns key mapping ON, make sure the
        // accessibility service (which key filtering rides on) is actually
        // enabled, rather than leaving them to toggle it in system settings.
        // Only on the enable edge, so a disable-save doesn't re-arm it. On that
        // edge ensureAccessibilityServiceEnabled() already computed the post-write
        // listed+master-on truth, so reuse its return as the a11yEnabled we report
        // rather than firing a second read-back pair of `settings get` execs. Off
        // the enable edge (disable, or persist failed) nothing was written, so a
        // single fresh read is the correct source.
        boolean a11yEnabled;
        if (ok && section.optBoolean("enabled", false)) {
            a11yEnabled = ensureAccessibilityServiceEnabled();
        } else {
            a11yEnabled = isAccessibilityServiceEnabled();
        }
        // a11yEnabled is the OS bind PRECONDITION (kept for backward compatibility
        // with the client's enable-edge adopt). On the enable edge it is reported
        // true the instant after `settings put`, but AMS binds the app-process
        // service asynchronously, so onKeyEvent may not fire for a brief window
        // even though a11yEnabled is already true — a transient false-positive if
        // the client treats it as "ready". So ALSO report the honest bind truth:
        // a11yBound (an active ServiceRecord / live in-proc instance) and
        // a11yPending (precondition met but not yet bound = the transient window),
        // letting the client show "enabling…" and confirm the real bind before
        // hiding the nudge instead of trusting the write-echo. Right after the
        // enable write a11yBound is expected false (bind hasn't completed); a
        // subsequent GET/re-fetch flips it true once AMS lands the bind.
        boolean a11yBound = isAccessibilityServiceBound();
        response.put("success", ok);
        response.put("a11yEnabled", a11yEnabled);
        response.put("a11yBound", a11yBound);
        response.put("a11yPending", a11yEnabled && !a11yBound);
        if (!ok) response.put("error", "Failed to persist keymap config");
        HttpResponse.sendJson(out, response.toString());
    }

    /** Fully-qualified component the accessibility service registers under. */
    private static final String A11Y_COMPONENT =
            "com.overdrive.app/com.overdrive.app.services.KeepAliveAccessibilityService";

    /**
     * Ensure our AccessibilityService is enabled in Secure settings — enabling it
     * ourselves rather than nagging the user. Key filtering (and process
     * keep-alive) is inert until it's on, and the app-side auto-enable runs
     * through an ADB shell session that isn't always available; the daemon,
     * however, runs as UID shell (2000) and holds WRITE_SECURE_SETTINGS, so it can
     * flip the Secure setting directly with `settings put`. Append-safe: never
     * clobbers other enabled services; the list write is a no-op when the
     * component is already present. It does NOT early-return on a present
     * component, though — it always (re)asserts the master accessibility_enabled
     * flag, since a listed-but-master-off install (keys dead) must still be healed.
     *
     * <p>Verify-after-enable: logs the enabled-services list and the master flag
     * BEFORE and AFTER the write, so the device log shows the truth (did the write
     * land? is the master flag on?) rather than us guessing. Best-effort — a
     * failure just leaves the settings-page nudge as the fallback.
     *
     * <p>Returns the post-write truth ({@code nowListed && masterOn}) it already
     * computes for the log line, so callers on the enable edge can reuse it as the
     * {@code a11yEnabled} they report instead of firing a second read-back pair of
     * {@code settings get} execs. Returns {@code false} on any failure (fail-safe:
     * matches {@link #isAccessibilityServiceEnabled()} reporting "off" so the nudge
     * stays visible rather than hiding it over a glitch).
     */
    private static boolean ensureAccessibilityServiceEnabled() {
        try {
            String before = readEnabledA11yServices();
            boolean alreadyListed = containsOurService(before);
            // Do NOT return early when already listed: the master flag
            // (accessibility_enabled) may still be 0, in which case the OS will
            // not bind/dispatch to the service (keys stay dead) even though the
            // component lingers in enabled_accessibility_services — the common
            // path on existing installs, where the keep-alive path already listed
            // the component but never asserted the master flag from here. The
            // script's list write is a no-op when the component is present (its
            // grep guard), and it always (re)asserts accessibility_enabled=1 —
            // mirroring ServiceLauncher's always-run second command. So letting
            // it run unconditionally is the self-heal.
            // Append our component to the ':'-separated list, preserving any
            // existing services; then set the master accessibility_enabled flag.
            // Single sh -c so the read-modify-write is one atomic settings call
            // sequence (matches ServiceLauncher's logic, run in-daemon).
            String script =
                "cur=$(settings get secure enabled_accessibility_services 2>/dev/null); " +
                "if echo \"$cur\" | grep -q '" + A11Y_COMPONENT + "'; then :; " +
                "elif [ -z \"$cur\" ] || [ \"$cur\" = 'null' ]; then " +
                "settings put secure enabled_accessibility_services '" + A11Y_COMPONENT + "'; " +
                "else settings put secure enabled_accessibility_services \"$cur:" + A11Y_COMPONENT + "\"; fi; " +
                "settings put secure accessibility_enabled 1";
            Process p = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
            // Re-read to confirm the write actually landed in Secure settings.
            String after = readEnabledA11yServices();
            boolean nowListed = containsOurService(after);
            boolean masterOn = isAccessibilityMasterEnabled();
            logger.info("Keymap a11y: enable attempted. wasListed=" + alreadyListed
                    + " before=" + safeLog(before) + " after=" + safeLog(after)
                    + " listed=" + nowListed + " masterEnabled=" + masterOn);
            if (!nowListed) {
                logger.warn("Keymap a11y: component still NOT in enabled_accessibility_services "
                        + "after settings put — the write did not take (permission or read-back lag)");
            } else if (!masterOn) {
                // Distinct from a failed write: the list is correct but the OS
                // master flag did not stick, so the app-process service will not
                // be bound and onKeyEvent will not fire — keys stay dead.
                logger.warn("Keymap a11y: component IS listed but accessibility_enabled != 1 "
                        + "after settings put — service will not bind (master flag did not take)");
            } else {
                // Listed AND master-on: the OS bind condition is satisfied. Note
                // this still does not prove the app-process service has finished
                // binding — AMS binds asynchronously and onServiceConnected (which
                // installs the key filter) may run shortly AFTER this write.
                // KeepAliveAccessibilityService.isRunning() is always false in the
                // UID-2000 daemon (the instance lives in the app process), so the
                // final bind is observed separately via isAccessibilityServiceBound()
                // (dumpsys ServiceRecord) rather than inferred from these flags.
                logger.info("Keymap a11y: listed + master-on (OS bind condition met; "
                        + "app-process bind completes asynchronously)");
            }
            // The OS bind condition (listed AND master-on) is the same predicate
            // isAccessibilityServiceEnabled() computes via its shell branch, so
            // hand it back for the enable-edge caller to reuse rather than re-read.
            return nowListed && masterOn;
        } catch (Throwable t) {
            logger.warn("Keymap: could not auto-enable accessibility service: " + t.getMessage());
            return false;
        }
    }

    /**
     * Read enabled_accessibility_services via the `settings` CLI. This is the
     * authoritative path in the daemon (UID 2000): the daemon's synthetic
     * ContentResolver is unreliable for Secure reads, whereas `settings get`
     * always works as shell. Returns "" on any failure.
     */
    private static String readEnabledA11yServices() {
        try {
            Process p = new ProcessBuilder("sh", "-c",
                    "settings get secure enabled_accessibility_services 2>/dev/null")
                    .redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
            String out = sb.toString().trim();
            return ("null".equals(out)) ? "" : out;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Read the master accessibility_enabled flag via the `settings` CLI. The OS
     * will not bind/dispatch to ANY accessibility service unless this is 1 — so a
     * component listed in enabled_accessibility_services while this is 0 means the
     * service is inert (key filtering dead). Reliable as UID shell; returns false
     * on any failure (fail-safe: report "off" so the nudge stays visible rather
     * than hiding it over a read glitch).
     */
    private static boolean isAccessibilityMasterEnabled() {
        try {
            Process p = new ProcessBuilder("sh", "-c",
                    "settings get secure accessibility_enabled 2>/dev/null")
                    .redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
            return "1".equals(sb.toString().trim());
        } catch (Throwable t) {
            return false;
        }
    }

    /** True if the flat enabled-services string contains our component. */
    private static boolean containsOurService(String flat) {
        return flat != null
                && flat.contains("com.overdrive.app/")
                && flat.contains("KeepAliveAccessibilityService");
    }

    private static String safeLog(String s) {
        if (s == null || s.isEmpty()) return "<empty>";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /**
     * Whether our AccessibilityService is enabled — reported to the settings page
     * so it can hide the "enable accessibility" nudge.
     *
     * <p>Two independent sources, EITHER of which proves it's on:
     *   1. The live in-process instance — {@link
     *      com.overdrive.app.services.KeepAliveAccessibilityService#isRunning()}.
     *      Ground truth when the daemon and app share the process; a bound service
     *      can't lie. (When the a11y service and this handler run in different
     *      processes this is simply false, and we fall through to #2.)
     *   2. The Secure settings, read via the `settings` CLI (reliable as UID
     *      shell) — NOT the daemon ContentResolver, which returned false-negatives.
     *      This branch requires BOTH conditions the OS uses to bind a service:
     *      the component is listed in enabled_accessibility_services AND the master
     *      accessibility_enabled flag is 1. Listing alone is NOT proof — an install
     *      where the keep-alive path listed the component but never set the master
     *      flag would otherwise be reported enabled while the service is inert and
     *      keys are dead (the false-positive that hid the nudge with broken keys).
     *
     * <p>Caveat: in the UID-2000 daemon, source #1 is structurally always false
     * (the bound instance lives in the app process), so only source #2 fires there.
     * Even a satisfied source #2 proves the OS <i>bind condition</i> is met, not
     * that the app-process service has finished binding (AMS binds asynchronously).
     * This is deliberately the PRECONDITION signal, kept distinct from the stronger
     * "actually bound" truth — see {@link #isAccessibilityServiceBound()}, which the
     * daemon CAN observe from UID 2000 via the service's ServiceRecord. Callers that
     * must not over-report "ready" (e.g. the enable edge, where AMS has not bound
     * yet) should report the bound signal alongside this one rather than treating a
     * met precondition as proof of a live, key-dispatching service.
     */
    private static boolean isAccessibilityServiceEnabled() {
        try {
            // In-proc ground truth: a live bound instance can't lie, and its
            // presence already implies both Secure-settings conditions hold.
            if (com.overdrive.app.services.KeepAliveAccessibilityService.isRunning()) return true;
        } catch (Throwable ignored) { /* class may be absent in a stripped build */ }
        // Shell branch (the only one that fires in the UID-2000 daemon): report
        // enabled only when the service is BOTH listed AND the master flag is on —
        // matching the OS bind condition.
        return containsOurService(readEnabledA11yServices()) && isAccessibilityMasterEnabled();
    }

    /**
     * Whether our AccessibilityService is <b>actually bound and live</b> — the
     * stronger, honest counterpart to {@link #isAccessibilityServiceEnabled()},
     * which only reports the OS bind PRECONDITION (listed + master-on). The
     * precondition can be satisfied while AMS has not yet finished binding the
     * app-process service, so onKeyEvent does not fire and keys are dead even
     * though the precondition reads true — the transient false-positive on the
     * enable edge. This method resolves that ambiguity for the client.
     *
     * <p>Two independent sources, EITHER of which proves a live bind:
     *   1. In-proc {@link
     *      com.overdrive.app.services.KeepAliveAccessibilityService#isRunning()}
     *      — set in onServiceConnected, cleared in onDestroy, so it is exactly the
     *      bound state. Ground truth when this handler shares the app process; in
     *      the UID-2000 daemon the instance lives in another process and this is
     *      always false, so we fall through to #2.
     *   2. {@code dumpsys activity services <component>} showing an active
     *      ServiceRecord (not {@code app=null}). AMS hosts every bound
     *      AccessibilityService as a real bound service, so a live ServiceRecord
     *      for our component means the app-process bind has completed — the same
     *      probe {@code ServiceLauncher.isLocationSidecarRunning} and
     *      {@code SentryDaemon} already use for their services, run in-daemon here.
     *
     * <p>Fail-safe: returns {@code false} on any read glitch or timeout (so the
     * client keeps the nudge / "enabling…" state rather than falsely hiding it),
     * matching {@link #isAccessibilityServiceEnabled()}'s conservative reporting.
     */
    private static boolean isAccessibilityServiceBound() {
        try {
            // In-proc ground truth: onServiceConnected sets the instance, onDestroy
            // clears it, so this is precisely "bound right now".
            if (com.overdrive.app.services.KeepAliveAccessibilityService.isRunning()) return true;
        } catch (Throwable ignored) { /* class may be absent in a stripped build */ }
        // Daemon path (UID 2000): an active ServiceRecord for the component proves
        // AMS has bound it. Mirrors ServiceLauncher.isLocationSidecarRunning's
        // "non-empty && !app=null" test. 2s ceiling so a slow dumpsys can never
        // park the request thread; drained so the child can't wedge on a full pipe.
        try {
            Process p = new ProcessBuilder("sh", "-c",
                    "dumpsys activity services " + A11Y_COMPONENT + " 2>/dev/null")
                    .redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
            String dump = sb.toString();
            // A bound service shows a ServiceRecord referencing our component and a
            // non-null hosting app. "app=null" means the record exists but nothing
            // is bound (not live) — reject it, matching the sidecar probe.
            return dump.contains("ServiceRecord")
                    && dump.contains("KeepAliveAccessibilityService")
                    && !dump.contains("app=null");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void handleFire(OutputStream out, String body) throws Exception {
        try {
            JSONObject req = new JSONObject(body == null ? "" : body);
            // A "sequence" runs several steps in order (one key → N actions).
            // Anything else is a single action. The advanced-shell
            // 403 is signalled by a runStep result with httpStatus=403 so the
            // gate behaves the same whether shell is a lone binding or a step.
            if ("sequence".equals(req.optString("kind", ""))) {
                HttpResponse.sendJson(out, runSequence(req).toString());
            } else {
                JSONObject r = runStep(req);
                int status = r.optInt("httpStatus", 200);
                r.remove("httpStatus");
                if (status != 200) {
                    HttpResponse.sendError(out, status, r.optString("error", "Refused"));
                } else {
                    HttpResponse.sendJson(out, r.toString());
                }
            }
        } catch (Exception e) {
            logger.warn("Keymap fire failed: " + e.getMessage());
            JSONObject response = new JSONObject();
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Run one action step and return a result JSON (never writes to the socket).
     * {@code kind} selects the path; an advanced-shell step with the gate off
     * returns {success:false, httpStatus:403} so the caller can 403 a lone shell
     * binding while a sequence just records the refusal and moves on.
     */
    private static JSONObject runStep(JSONObject req) throws org.json.JSONException {
        String kind = req.optString("kind", "");
        switch (kind) {
            case "catalog": return runCatalog(req);
            case "vehicle": return runVehicle(req);
            case "shell":   return runShell(req);
            default: {
                JSONObject r = new JSONObject();
                r.put("success", false);
                r.put("error", "Unknown keymap action kind: " + kind);
                return r;
            }
        }
    }

    /**
     * Run a sequence of steps in order. Best-effort: a failing step is recorded
     * but does not abort the rest (a key bound to "close windows; lock" should
     * still try to lock even if the window close reports a non-zero HAL result).
     * Overall success = every step succeeded. Steps run on this request thread
     * (the a11y service fires the POST off its own executor and ignores the body),
     * so ordering is deterministic and sequential.
     */
    private static JSONObject runSequence(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        org.json.JSONArray steps = req.optJSONArray("steps");
        if (steps == null || steps.length() == 0) {
            response.put("success", false);
            response.put("error", "Sequence has no steps");
            return response;
        }
        org.json.JSONArray results = new org.json.JSONArray();
        boolean allOk = true;
        int ran = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            JSONObject r = runStep(step);
            r.remove("httpStatus"); // sequence never 403s the whole call; per-step success carries it
            results.put(r);
            ran++;
            if (!r.optBoolean("success", false)) allOk = false;
        }
        // A sequence whose every entry was non-runnable (e.g. steps:[null] from a
        // hand-corrupted binding) actuated nothing, so it is not a success — mirror
        // the empty-steps contract above rather than report success:true for a no-op.
        boolean anyRan = ran > 0;
        logger.info("Keymap sequence: " + steps.length() + " step(s), ran=" + ran + ", allOk=" + allOk);
        response.put("success", allOk && anyRan);
        if (!anyRan) response.put("error", "Sequence had no runnable steps");
        response.put("steps", results);
        return response;
    }

    /**
     * Curated catalog action — same resolution path as the MQTT control router:
     * look up the entity, build a command from (sub, payload) against the current
     * vehicle snapshot, and run it SDK-only (no BYD cloud round-trip; key presses
     * must be instant and offline-safe).
     */
    private static JSONObject runCatalog(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        String key = req.optString("key", null);
        String sub = req.has("sub") && !req.isNull("sub") ? req.optString("sub", null) : null;
        String payload = req.optString("payload", "");

        if (key == null || key.isBlank()) {
            response.put("success", false);
            response.put("error", "Missing catalog key");
            return response;
        }

        VehicleControlCatalog.ControlEntity entity = VehicleControlCatalog.get(key);
        if (entity == null) {
            response.put("success", false);
            response.put("error", "Unknown control entity: " + key);
            return response;
        }

        BydDataCollector collector = BydDataCollector.getInstance();
        BydVehicleData snap = collector.isInitialized() ? collector.getData() : null;

        VehicleControlCatalog.ControlAction action = entity.toAction(sub, payload, snap);
        if (action == null || action.command == null) {
            response.put("success", false);
            response.put("error", "No action for " + key + (sub != null ? "/" + sub : "") + " payload='" + payload + "'");
            return response;
        }

        VehicleCommandRouter.CommandResult r =
                VehicleCommandRouter.getInstance().executeSdkOnly(action.command);
        logger.info("Keymap catalog '" + key + (sub != null ? "/" + sub : "") + "' payload='" + payload
                + "' -> " + action.command.name() + " " + r.outcome + " (" + r.latencyMs + "ms)");

        response.put("success", r.outcome == VehicleCommandRouter.Outcome.SUCCESS);
        response.put("outcome", r.outcome.toString());
        response.put("message", r.displayMessage);
        return response;
    }

    /**
     * Composite vehicle commands not registered in the catalog. These are
     * cloud-first (lock/flash have no local SDK path on this generation), so
     * unlike the catalog path they route through the full {@code execute()}
     * capability matrix rather than SDK-only.
     */
    private static JSONObject runVehicle(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        String action = req.optString("action", "");
        VehicleCommandRouter.VehicleCommand cmd;
        switch (action) {
            case "lock":     cmd = new VehicleCommandRouter.LockCommand(); break;
            case "unlock":   cmd = new VehicleCommandRouter.UnlockCommand(); break;
            case "flash":    cmd = new VehicleCommandRouter.FlashLightsCommand(); break;
            case "find_car": cmd = new VehicleCommandRouter.FindCarCommand(); break;
            default:
                response.put("success", false);
                response.put("error", "Unknown vehicle action: " + action);
                return response;
        }

        VehicleCommandRouter.CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
        logger.info("Keymap vehicle '" + action + "' -> " + cmd.name() + " " + r.outcome + " (" + r.latencyMs + "ms)");

        response.put("success", r.outcome == VehicleCommandRouter.Outcome.SUCCESS);
        response.put("outcome", r.outcome.toString());
        response.put("message", r.displayMessage);
        return response;
    }

    /**
     * Advanced escape hatch — run a shell command. Gated by the keymap
     * section's {@code allowAdvanced} flag; refused with 403 otherwise so a
     * curated-only install can never be coerced into arbitrary exec by a
     * crafted request. Runs in the daemon (UID 2000), same as the other
     * daemon-side exec sites.
     */
    private static JSONObject runShell(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        if (!com.overdrive.app.config.UnifiedConfigManager.isKeymapAdvancedAllowed()) {
            // Signal a 403 to the single-fire caller; runSequence strips this and
            // just treats the step as failed. Never executes when the gate is off.
            response.put("success", false);
            response.put("error", "Advanced keymap actions are disabled.");
            response.put("httpStatus", 403);
            return response;
        }
        String cmd = req.optString("cmd", "");
        if (cmd.isBlank()) {
            response.put("success", false);
            response.put("error", "Missing shell command");
            return response;
        }
        try {
            final Process p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            final java.io.InputStream is = p.getInputStream();
            // Bound the whole exec by the 5s ceiling, not just the reap. A
            // dedicated daemon thread drains (and discards) stdout so the child
            // can never wedge on a full (>~64KB) pipe, but because it runs off
            // this request thread its blocking read() can never outlive the
            // deadline below — a long-lived-stdout command (logcat, top,
            // tail -f, a backgrounded child) is force-killed at 5s instead of
            // parking this HttpServer worker forever. (The earlier idiom drained
            // to EOF *before* waitFor, so destroyForcibly was unreachable for
            // exactly those commands; AccSentryDaemon.execShell has no waitFor
            // timeout and so is not a bounded precedent either.)
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    while (is.read(buf) != -1) { /* discard */ }
                } catch (Throwable ignored) { /* pipe closed on kill */ }
            }, "keymap-shell-drain");
            drain.setDaemon(true);
            drain.start();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            drain.join(500);        // let the reader unwind after the pipe closes
            try { is.close(); } catch (Throwable ignored) { }
            logger.info("Keymap shell fired: " + cmd);
            response.put("success", true);
        } catch (Throwable t) {
            logger.warn("Keymap shell failed: " + t.getMessage());
            response.put("success", false);
            response.put("error", t.getMessage());
        }
        return response;
    }

    // NOTE: a raw-CAN "kind" was intentionally left out. Sending arbitrary bus
    // frames needs (a) a proven reach into the acquisition/diagnostic service
    // from UID 2000 — BYDACQUISITION_SEND_BUFFER is declared but its HAL
    // acceptance for our UID is unverified — and (b) a per-vehicle frame map the
    // user can only obtain by sniffing their own bus. Until both exist, exposing
    // a hex textbox is unusable. Track as future work; the shell hatch covers the
    // advanced cases in the meantime.

    private KeymapApiHandler() {}
}
