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
        } catch (Exception e) {
            logger.error("Failed to run time event", e);
        }
        scheduleTimeEvent();
    }
}
