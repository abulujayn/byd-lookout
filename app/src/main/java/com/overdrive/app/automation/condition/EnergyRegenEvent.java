package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes the energy-recuperation (regen) level — standard / high / max — into the
 * automation state at a FAST cadence so a "when regen → max" automation fires promptly.
 *
 * <p><b>Why a dedicated fast poll.</b> Regen level was previously read only inside the
 * telemetry snapshot ({@link BydEvent#bydEvent}), which is built on the ~5s ACC-on poll
 * (and 90s parked), so a regen-level change lagged the driver by up to ~5s (the reported
 * "2-4s trigger delay"). This poll reads the same live SDK getter
 * ({@code BydDataCollector.getEnergyFeedback}) directly at a fast cadence and publishes
 * on value transitions, exactly like {@link TurnSignalEvent} / {@link DynamicsEvent}.
 *
 * <p><b>Zero cost unless such an automation exists.</b> Same self-gating: the poll
 * reschedules every tick but does NO SDK read unless {@link Automations#isEventReferenced}
 * reports an enabled automation actually uses {@code energyRegen}. An idle feature costs
 * only a parked scheduler thread ticking a cheap map check.
 *
 * <p><b>1s cadence (not 250ms).</b> Regen strength is a user-initiated SETTING changed on
 * the driving-config screen, not a sub-second gesture like the pedals, so 1s feels
 * immediate while staying well below the old 5s snapshot latency and 4× cheaper than the
 * 250ms {@link DynamicsEvent}.
 */
public final class EnergyRegenEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "EnergyRegenEvent");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<ScheduledFuture<?>> active = new AtomicReference<>();

    private static final long POLL_MS = 1000L;

    private EnergyRegenEvent() {}

    public static void scheduleEnergyRegenEvent() {
        ScheduledFuture<?> next = scheduler.schedule(EnergyRegenEvent::poll, POLL_MS, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = active.getAndSet(next);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
    }

    private static void poll() {
        try {
            // Gate on a real listener: only read the SDK when an enabled automation
            // references the regen event. isEventReferenced short-circuits to false when
            // no automation is enabled at all, so this is a cheap no-op in the common case.
            if (Automations.isEventReferenced(BydEvent.ENERGY_REGEN)) {
                int regen = BydDataCollector.getInstance().getEnergyFeedback();
                String word = regenWord(regen);
                if (word != null) Automations.update(BydEvent.ENERGY_REGEN, word);
            }
        } catch (Throwable t) {
            logger.error("Failed to run energy-regen event", t);
        } finally {
            scheduleEnergyRegenEvent();
        }
    }

    /** Map the app-level regen level (0/1/2) to a word, or null if unavailable (-1 → skip,
     *  leaving the event unseeded so no spurious edge). Mirrors the daemon's app-level map. */
    private static String regenWord(int level) {
        switch (level) {
            case 0:  return "standard";
            case 1:  return "high";
            case 2:  return "max";
            default: return null;
        }
    }
}
