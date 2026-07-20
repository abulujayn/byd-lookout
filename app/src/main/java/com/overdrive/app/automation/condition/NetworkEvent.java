package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.NetworkMonitor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes WiFi connection state / SSID (and a one-shot system-boot event) into the
 * automation state so wifi and boot triggers can be evaluated.
 *
 * <p>Design notes:
 * <ul>
 *   <li><b>Low cadence, and only while enabled.</b> The poll runs every
 *       {@link #POLL_SECONDS}s but does nothing (and does not touch the network) when
 *       no automation is enabled — {@link Automations#isDisabled()} is an O(1) field
 *       read, so an idle feature costs only a parked scheduler thread. This keeps the
 *       "no automation configured => no extra compute" property honest and avoids the
 *       daemon-CPU class of problem from always-on shell polling.</li>
 *   <li><b>WiFi state is an edge.</b> {@link Automations#update} only fires evaluation
 *       on a real value transition, so republishing the same SSID every poll is free.</li>
 *   <li><b>Boot fires once per daemon start.</b> {@code boot=on} is published exactly
 *       once per process, eagerly at the first schedule (daemon start ≈ the head unit
 *       coming up), so it can't be missed or delayed. It does NOT gate on device uptime:
 *       the daemon is often launched minutes after the device booted (and again on an OTA
 *       / watchdog restart), so an uptime window silently dropped the event on the common
 *       path. The one-per-process guard is the dedup — it can't re-fire within a run.</li>
 * </ul>
 */
public class NetworkEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final AtomicReference<ScheduledFuture<?>> active = new AtomicReference<>();

    // Poll cadence for wifi state. Long enough to be cheap, short enough that a
    // "when WiFi connects" automation feels responsive.
    private static final int POLL_SECONDS = 20;

    // Guard so boot is published at most once per process.
    private static volatile boolean bootPublished = false;

    private NetworkEvent() {}

    public static void scheduleNetworkEvent() {
        // Publish the one-shot boot event IMMEDIATELY on the first schedule (daemon start),
        // not on the first 20s poll. The daemon starting is what "system boot" means to the
        // user (the head unit came up); waiting 20s — and only inside the poll, which also
        // no-ops while no automation is enabled — meant the boot transition could be missed
        // or delayed. publishBoot() is itself one-shot + self-gates on isDisabled, so an
        // eager call is safe and free when nothing listens. Guard so we only fire it on the
        // very first schedule, not on every reschedule.
        if (!bootScheduled) {
            bootScheduled = true;
            try { publishBoot(); } catch (Throwable ignored) {}
        }
        ScheduledFuture<?> next = scheduler.schedule(NetworkEvent::poll, POLL_SECONDS, TimeUnit.SECONDS);
        ScheduledFuture<?> previous = active.getAndSet(next);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
    }

    // Ensures the eager boot publish happens once (on the first schedule), independent of
    // the per-poll loop below.
    private static volatile boolean bootScheduled = false;

    private static void poll() {
        try {
            // Skip all work (including the network refresh) when nothing is listening.
            if (!Automations.isDisabled()) {
                publishBoot();
                publishWifi();
            }
        } catch (Throwable t) {
            logger.error("Failed to run network event", t);
        } finally {
            // Always reschedule so the poll resumes once an automation is enabled.
            scheduleNetworkEvent();
        }
    }

    /**
     * Publish {@code boot=on} exactly once, and only when the device is still within
     * the post-boot window. Runs on the poll thread (never the telemetry hot path).
     *
     * <p>{@link Automations#stateChanged} deliberately does NOT fire on a
     * {@code null -> X} seed transition (so events don't all fire at daemon startup).
     * A one-shot boot event would therefore be swallowed if it only ever published
     * {@code on}. To make it a genuine transition we seed {@code off} first: the
     * {@code null -> off} seed is (correctly) swallowed, then {@code off -> on} is a
     * real transition that fires any boot-triggered automation exactly once.
     */
    private static void publishBoot() {
        if (bootPublished) return;
        bootPublished = true; // one per process — the daemon (re)starting IS the boot edge
        try {
            // Fire once per daemon process. We do NOT gate on device uptime
            // (SystemClock.elapsedRealtime): the daemon is launched by the app, often well
            // after the head unit powered on (and again after an OTA / watchdog restart), so
            // a 5-min uptime window silently dropped the boot event on exactly the common
            // path — the reported "boot trigger not working". The one-per-process guard
            // above is the correct dedup: each fresh daemon start (≈ the system coming up
            // for the user) fires boot exactly once; it can't re-fire within a process.
            // Seed "off" first (null -> off is swallowed by stateChanged), then transition
            // to "on" so a boot-triggered automation actually fires.
            Automations.update(BydEvent.BOOT, "off");
            Automations.update(BydEvent.BOOT, "on");
        } catch (Throwable ignored) {
            // Boot is best-effort; never disrupt the poll loop.
        }
    }

    private static void publishWifi() {
        // Refresh from the OS, then publish the (possibly changed) state. refresh()
        // is internally cache-bounded and falls back to shell only when the Android
        // APIs are unavailable.
        NetworkMonitor.refresh();
        boolean connected = NetworkMonitor.isWifiConnected();
        Automations.update(BydEvent.WIFI_STATE, connected ? "on" : "off");
        // Publish the SSID (empty string when not on WiFi) so an SSID-match condition
        // sees a stable value. Never publish null — a null would be treated as
        // "unseen" and could NPE downstream comparisons.
        String ssid = NetworkMonitor.getWifiSsid();
        Automations.update(BydEvent.WIFI_SSID, ssid == null ? "" : ssid);
    }
}
