package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class TimeEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final AtomicReference<ScheduledFuture<?>> active = new AtomicReference<>();

    private TimeEvent() {}

    public static void scheduleTimeEvent() {
        LocalTime now = LocalTime.now();
        LocalTime nextRun = now.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES);
        // Run the task 1 second after the minute to ensure it doesn't run before the minute changes
        long delay = Duration.between(now, nextRun).getSeconds() + 1;

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
