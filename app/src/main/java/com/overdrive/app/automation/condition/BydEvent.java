package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.bodywork.BodyworkConstants;
import com.overdrive.app.monitor.GearMonitor;

import java.util.Map;

public class BydEvent {
    // Stored as static variables to prevent the EventData objects being created repeatedly
    public static final EventData POWER = new EventData("power");
    public static final EventData GEAR = new EventData("gear");
    public static final EventData WINDOW_LF_PERCENT = new EventData("windowOpenPercent", Map.of("area", "lf"));
    public static final EventData WINDOW_RF_PERCENT = new EventData("windowOpenPercent", Map.of("area", "rf"));
    public static final EventData WINDOW_LR_PERCENT = new EventData("windowOpenPercent", Map.of("area", "lr"));
    public static final EventData WINDOW_RR_PERCENT = new EventData("windowOpenPercent", Map.of("area", "rr"));
    public static final EventData WINDOW_SUNROOF_PERCENT = new EventData("windowOpenPercent", Map.of("area", "sunroof"));
    public static final EventData WINDOW_SUNSHADE_PERCENT = new EventData("windowOpenPercent", Map.of("area", "sunshade"));
    public static final EventData WINDOW_LF = new EventData("windowState", Map.of("area", "lf"));
    public static final EventData WINDOW_RF = new EventData("windowState", Map.of("area", "rf"));
    public static final EventData WINDOW_LR = new EventData("windowState", Map.of("area", "lr"));
    public static final EventData WINDOW_RR = new EventData("windowState", Map.of("area", "rr"));
    public static final EventData WINDOW_SUNROOF = new EventData("windowState", Map.of("area", "sunroof"));
    public static final EventData WINDOW_SUNSHADE = new EventData("windowState", Map.of("area", "sunshade"));
    public static final EventData WINDOW_ALL = new EventData("windowState", Map.of("area", "all"));
    public static final EventData BATTERY_LEVEL = new EventData("batteryLevel");
    public static final EventData ESTIMATED_RANGE = new EventData("estimatedRange");
    public static final EventData LIGHTS_LOW_BEAM = new EventData("lights", Map.of("area", "lowBeam"));
    public static final EventData LIGHTS_HIGH_BEAM = new EventData("lights", Map.of("area", "highBeam"));
    public static final EventData LIGHTS_HAZARD = new EventData("lights", Map.of("area", "hazard"));
    public static final EventData LIGHTS_DRL = new EventData("lights", Map.of("area", "drl"));
    public static final EventData SLW = new EventData("slw");
    public static final EventData CPD = new EventData("cpd");
    public static final EventData SEAT_HEAT_DRIVER = new EventData("seatClimate", Map.of("type", "heat", "area", "driver"));
    public static final EventData SEAT_HEAT_PASSENGER = new EventData("seatClimate", Map.of("type", "heat", "area", "passenger"));
    public static final EventData SEAT_COOL_DRIVER = new EventData("seatClimate", Map.of("type", "cool", "area", "driver"));
    public static final EventData SEAT_COOL_PASSENGER = new EventData("seatClimate", Map.of("type", "cool", "area", "passenger"));
    public static final EventData AC = new EventData("ac");
    public static final EventData TEMPERATURE = new EventData("temperature");
    public static final EventData SPEED_KMPH = new EventData("speed", Map.of("units", "kmph"));
    public static final EventData SPEED_MPH = new EventData("speed", Map.of("units", "mph"));
    public static final EventData TIME = new EventData("time");
    public static final EventData DAY = new EventData("day");

    private BydEvent() {}

    /**
     * This class is created to make gathering events easier
     * As the BydVehicleData is not updated often, this does not affect app performance
     * If this changes in the future, Automations.update should be called directly when an event is triggered
     * This would allow it to update a single variable instead of updating all variables when a single value changes
     *
     * @param data The current BydVehicleData with the vehicle state
     */
    public static void bydEvent(BydVehicleData data) {
        // Do nothing when no automations enabled
        if (Automations.isDisabled()) return;

        Automations.update(POWER, BodyworkConstants.powerLevelToString(data.powerLevel).toLowerCase());
        Automations.update(GEAR, GearMonitor.gearToString(data.gearMode).toLowerCase());
        // windowOpenPercent is nullable (defaults null; only populated when the bodywork HAL device is
        // present, and may be left null if the HAL reflection threw), and the HAL fills unavailable /
        // unreadable slots with a negative sentinel (-1). Guard the whole block so the telemetry poll
        // loop (build sites are not wrapped in try/catch) never NPEs, index every slot defensively by
        // length, and skip sentinel slots — otherwise a -1 would map to "open" (since -1 != 0) and
        // false-fire a "window open" automation, and would keep WINDOW_ALL from ever reporting "closed".
        int[] win = data.windowOpenPercent;
        if (win != null) {
            if (win.length > 0) updateWindow(WINDOW_LF_PERCENT, WINDOW_LF, win[0]);
            if (win.length > 1) updateWindow(WINDOW_RF_PERCENT, WINDOW_RF, win[1]);
            if (win.length > 2) updateWindow(WINDOW_LR_PERCENT, WINDOW_LR, win[2]);
            if (win.length > 3) updateWindow(WINDOW_RR_PERCENT, WINDOW_RR, win[3]);
            if (win.length > 4) updateWindow(WINDOW_SUNROOF_PERCENT, WINDOW_SUNROOF, win[4]);
            if (win.length > 5) updateWindow(WINDOW_SUNSHADE_PERCENT, WINDOW_SUNSHADE, win[5]);

            // WINDOW_ALL is a convenience shortcut ("are all windows shut?"). Only meaningful once we
            // have at least one real reading: "closed" iff every available (non-negative) slot is 0.
            boolean anyKnown = false, anyOpen = false;
            for (int percent : win) {
                if (percent < 0) continue; // unavailable slot — ignore
                anyKnown = true;
                if (percent != 0) anyOpen = true;
            }
            if (anyKnown) Automations.update(WINDOW_ALL, anyOpen ? "open" : "closed");
        }
        if (!Double.isNaN(data.socPercent)) Automations.update(BATTERY_LEVEL, (int) data.socPercent);
        if (data.elecRangeKm != BydVehicleData.UNAVAILABLE) {
            Automations.update(ESTIMATED_RANGE, data.elecRangeKm);
        } else if (data.bodyworkRangeKm != BydVehicleData.UNAVAILABLE) {
            Automations.update(ESTIMATED_RANGE, data.bodyworkRangeKm);
        }
        Automations.update(LIGHTS_LOW_BEAM, data.lowBeam ? "on" : "off");
        Automations.update(LIGHTS_HIGH_BEAM, data.highBeam ? "on" : "off");
        Automations.update(LIGHTS_HAZARD, data.hazard ? "on" : "off");
        Automations.update(LIGHTS_DRL, data.dayTimeLight ? "on" : "off");
        Automations.update(SLW, data.speedLimitWarning ? "on" : "off");
        Automations.update(CPD, cpdToString(data.childPresenceDetection));
        if (data.seatHeat != null) {
            if (data.seatHeat.length > 0) Automations.update(SEAT_HEAT_DRIVER, seatClimateToString(data.seatHeat[0]));
            if (data.seatHeat.length > 1) Automations.update(SEAT_HEAT_PASSENGER, seatClimateToString(data.seatHeat[1]));
        }
        if (data.seatCool != null) {
            if (data.seatCool.length > 0) Automations.update(SEAT_COOL_DRIVER, seatClimateToString(data.seatCool[0]));
            if (data.seatCool.length > 1) Automations.update(SEAT_COOL_PASSENGER, seatClimateToString(data.seatCool[1]));
        }
        boolean poweredOn = data.powerLevel >= 2;
        Automations.update(AC, (poweredOn && data.acStartState == 1) ? "on" : "off");
        if (!Double.isNaN(data.insideTempC)) Automations.update(TEMPERATURE, (int) data.insideTempC);
        if (!Double.isNaN(data.speedKmh)) {
            Automations.update(SPEED_KMPH, (int) Math.round(data.speedKmh));
            Automations.update(SPEED_MPH, (int) Math.round(data.speedKmh * 0.621371));
        }
    }

    /**
     * Seed the percent and open/closed state events for one window slot, skipping unavailable slots.
     *
     * @param percentKey The event key for the raw open percentage
     * @param stateKey   The event key for the derived open/closed state
     * @param percent    The slot's open percentage, or a negative sentinel if unavailable
     */
    private static void updateWindow(EventData percentKey, EventData stateKey, int percent) {
        if (percent < 0) return; // unavailable/unreadable slot — leave the state unseeded
        Automations.update(percentKey, percent);
        Automations.update(stateKey, percent == 0 ? "closed" : "open");
    }

    private static String seatClimateToString(int level) {
        switch (level) {
            case 0:
                return "off";
            case 1:
                return "low";
            case 2:
                return "high";
            default:
                return "unknown";
        }
    }

    private static String cpdToString(int value) {
        switch (value) {
            case 1:
                return "on";
            case 2:
                return "off";
            case 3:
                return "delay";
            default:
                return "unknown";
        }
    }
}
