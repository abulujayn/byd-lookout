package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes the fast dynamic-driving inputs — accelerator, brake and steering angle —
 * into the automation state at a FAST cadence so an "accelerator past X%" / "hard brake"
 * / "steering past Y°" automation fires promptly.
 *
 * <p><b>Why a dedicated fast poll.</b> These inputs piggy-backed on the telemetry
 * snapshot ({@link BydEvent#bydEvent}), which is built sub-second WHILE DRIVING but only
 * every ~5s while STATIONARY (the RoadSense fast-dynamics poll refreshes its own atomic
 * but never calls {@code build()}). So a pedal/steering trigger lagged the driver by up
 * to ~5s — the reported "trigger delay". This poll reads the same live SDK getters
 * directly on the daemon at a fast cadence and publishes on value transitions, exactly
 * like {@link TurnSignalEvent} does for the indicators.
 *
 * <p><b>Zero cost unless such an automation exists.</b> Same self-gating as
 * {@link TurnSignalEvent}: the poll reschedules every tick but does NO SDK read unless
 * {@link Automations#isEventReferenced} reports an enabled automation actually uses the
 * accelerator, brake or steering event. Each signal is read ONLY when it is individually
 * referenced, so a rule that watches just the accelerator never reads brake/steering. An
 * idle feature costs only a parked scheduler thread ticking cheap map checks.
 */
public final class DynamicsEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "DynamicsEvent");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<ScheduledFuture<?>> active = new AtomicReference<>();

    // 250ms — pedal/steering gestures are sub-second, so this catches them promptly
    // while staying an order of magnitude cheaper than the render loop. Only paid while
    // a dynamics automation exists (see poll()).
    private static final long POLL_MS = 250L;

    private DynamicsEvent() {}

    public static void scheduleDynamicsEvent() {
        ScheduledFuture<?> next = scheduler.schedule(DynamicsEvent::poll, POLL_MS, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = active.getAndSet(next);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
    }

    private static void poll() {
        try {
            // Gate per-signal on a real listener. isEventReferenced short-circuits to
            // false when no automation is enabled at all, so this is a cheap no-op in the
            // common case; and a rule that uses only one of the three never reads the
            // others' SDK getters.
            BydDataCollector collector = BydDataCollector.getInstance();

            // ACC gate: accelerator / brake / steering are all inert with the key removed,
            // so their live SDK getters return 0/dead on a parked car and can never satisfy
            // a threshold trigger — reading them 4×/sec while parked is pure waste. Skip the
            // reads (not the reschedule) when ACC is off; the poller stays armed and resumes
            // reading on the very next 250ms tick once ACC returns, so trigger latency is
            // unchanged. accIsOn defaults true (fails toward polling) until the first edge.
            if (!collector.isAccOn()) {
                return;
            }

            if (Automations.isEventReferenced(BydEvent.ACCELERATOR)) {
                int a = collector.readAccelNow();
                if (a != BydVehicleData.UNAVAILABLE) Automations.update(BydEvent.ACCELERATOR, a);
            }
            if (Automations.isEventReferenced(BydEvent.BRAKE)) {
                int b = collector.readBrakeNow();
                if (b != BydVehicleData.UNAVAILABLE) Automations.update(BydEvent.BRAKE, b);
            }
            if (Automations.isEventReferenced(BydEvent.STEERING_ANGLE)) {
                int s = collector.readSteeringNow();
                if (s != BydVehicleData.UNAVAILABLE) Automations.update(BydEvent.STEERING_ANGLE, s);
            }
        } catch (Throwable t) {
            logger.error("Failed to run dynamics event", t);
        } finally {
            scheduleDynamicsEvent();
        }
    }
}
