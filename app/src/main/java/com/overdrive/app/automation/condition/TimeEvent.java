package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes the local time-of-day (minutes since midnight) and day-of-week to the
 * automation state once a minute so time/day conditions can be evaluated. The task
 * re-schedules itself aligned to the top of the next minute (+1s) so it never fires
 * before the minute actually rolls over.
 */
public class TimeEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final AtomicReference<ScheduledFuture<?>> active = new AtomicReference<>();

    private TimeEvent() {}

    public static void scheduleTimeEvent() {
        // Compute the next-minute boundary against LocalDateTime, NOT LocalTime: at 23:59
        // LocalTime.plusMinutes(1) wraps to 00:00 and Duration.between(now, 00:00) on a
        // LocalTime goes BACKWARDS (~ -86340s), which schedule() treats as "run now" and
        // would busy-loop the reschedule chain for the whole final minute of the day.
        // LocalDateTime spans the date boundary so the delay stays correct across midnight.
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRun = now.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES);
        // Run the task 1 second after the minute to ensure it doesn't run before the minute changes.
        // Clamp to >=1s as a final guard against any clock skew yielding a non-positive delay.
        long delay = Math.max(1, Duration.between(now, nextRun).getSeconds() + 1);

        ScheduledFuture<?> next = scheduler.schedule(TimeEvent::sendEvent, delay, TimeUnit.SECONDS);

        ScheduledFuture<?> previous = active.getAndSet(next);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
    }

    private static void sendEvent() {
        try {
            LocalDateTime now = LocalDateTime.now();
            // Store time as minutes since start of day to make comparison easier
            Automations.update(BydEvent.TIME, now.get(ChronoField.MINUTE_OF_DAY));
            Automations.update(BydEvent.DAY, now.getDayOfWeek().name().toLowerCase());
            // Calendar signals for date/monthly automations. dayOfMonth is numeric
            // (IntType condition, 1-31). month is published as a STRING ("1".."12") to
            // match the month-name EnumType condition — an Integer would wrap as IntValue
            // and never compare-equal to the enum's String ids (StringValue), silently
            // never firing.
            Automations.update(BydEvent.DAY_OF_MONTH, now.getDayOfMonth());
            Automations.update(BydEvent.MONTH, String.valueOf(now.getMonthValue()));
            // Solar phase (day/night) from GPS + local date. Only published when we
            // have a location fix — otherwise unseeded (no bogus sunset at lat/lon 0,0).
            publishSunPhase(now);
        } catch (Exception e) {
            logger.error("Failed to run time event", e);
        }
        scheduleTimeEvent();
    }

    /**
     * Publish sunPhase = "day"/"night" using the current GPS fix + local date. The
     * automation engine fires a trigger on the day→night (sunset) or night→day
     * (sunrise) transition. Skipped entirely without a location fix so we never
     * manufacture a phase from a null island (0,0) reading.
     */
    private static void publishSunPhase(LocalDateTime now) {
        try {
            com.overdrive.app.monitor.GpsMonitor gps = com.overdrive.app.monitor.GpsMonitor.getInstance();
            if (gps == null || !gps.hasLocation()) return;
            double lat = gps.getLatitude();
            double lon = gps.getLongitude();
            SolarCalculator.SunTimes st = SolarCalculator.compute(
                    now.toLocalDate(), lat, lon, java.time.ZoneId.systemDefault());
            if (st == null) return;
            String phase;
            if (st.alwaysUp) {
                phase = "day";
            } else if (st.alwaysDown) {
                phase = "night";
            } else {
                int minuteOfDay = now.get(ChronoField.MINUTE_OF_DAY);
                // Daytime = at/after sunrise and before sunset.
                phase = (minuteOfDay >= st.sunriseMinute && minuteOfDay < st.sunsetMinute)
                        ? "day" : "night";
            }
            Automations.update(BydEvent.SUN_PHASE, phase);
        } catch (Throwable t) {
            // Solar calc / GPS glitch — just skip this tick's phase publish.
        }
    }
}
