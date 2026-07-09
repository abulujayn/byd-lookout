package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.TimeType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public class Conditions {
    // Create LinkedHashMap to maintain the insertion order of conditions to display in the frontend
    private final Map<String, EventCondition> conditions = new LinkedHashMap<>();

    /**
     * Initialize conditions list with possible events
     */
    public Conditions() {
        addCondition(new EventCondition(
                new Label("power", "automation.power"), "automation.power_description", new EnumType(
                new Label("state", "automation.state"),
                new Label("off", "automation.off"),
                new Label("acc", "automation.acc"),
                new Label("on", "automation.on"))));
        addCondition(new EventCondition(
                new Label("gear", "automation.gear"), "automation.gear_description", new EnumType(
                new Label("gear", "automation.gear"),
                new Label("p", "automation.p_gear"),
                new Label("r", "automation.r_gear"),
                new Label("n", "automation.n_gear"),
                new Label("d", "automation.d_gear"),
                new Label("m", "automation.m_gear"),
                new Label("s", "automation.s_gear"))));
        addCondition(new EventCondition(
                new Label("windowOpenPercent", "automation.window_open_percent"),
                "automation.window_open_percent_description",
                new IntType(new Label("percent", "automation.percent"), 0, 100),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("lf", "automation.area_lf"),
                        new Label("rf", "automation.area_rf"),
                        new Label("lr", "automation.area_lr"),
                        new Label("rr", "automation.area_rr"),
                        new Label("sunroof", "automation.area_sunroof"),
                        new Label("sunshade", "automation.area_sunshade"))));
        addCondition(new EventCondition(
                new Label("windowState", "automation.window_state"),
                "automation.window_state_description",
                new EnumType(new Label("state", "automation.state"), new Label("open", "automation.open"), new Label("closed", "automation.closed")),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("all", "automation.area_all"),
                        new Label("lf", "automation.area_lf"),
                        new Label("rf", "automation.area_rf"),
                        new Label("lr", "automation.area_lr"),
                        new Label("rr", "automation.area_rr"),
                        new Label("sunroof", "automation.area_sunroof"),
                        new Label("sunshade", "automation.area_sunshade"))));
        addCondition(new EventCondition(
                new Label("batteryLevel", "automation.battery_level"),
                "automation.battery_level_description",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        addCondition(new EventCondition(
                new Label("estimatedRange", "automation.estimated_range"),
                "automation.estimated_range_description",
                new IntType(new Label("range", "automation.estimated_range"), 0, 1000)));
        addCondition(new EventCondition(
                new Label("lights", "automation.lights"),
                "automation.lights_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off")),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("lowBeam", "automation.lights_lowbeam"),
                        new Label("highBeam", "automation.lights_highbeam"),
                        new Label("hazard", "automation.lights_hazard"),
                        new Label("drl", "automation.lights_drl"))));
        addCondition(new EventCondition(
                new Label("slw", "automation.slw"),
                "automation.slw_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off"))));
        addCondition(new EventCondition(
                new Label("cpd", "automation.cpd"),
                "automation.cpd_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("on", "automation.on"),
                        new Label("off", "automation.off"),
                        new Label("delay", "automation.cpd_delay"))));
        addCondition(new EventCondition(
                new Label("seatClimate", "automation.seat_climate"),
                "automation.seat_climate_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("off", "automation.off"),
                        new Label("low", "automation.low"),
                        new Label("high", "automation.high")),
                new EnumType(new Label("type", "automation.type"), new Label("heat", "automation.heat"), new Label("cool", "automation.cool")),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("driver", "automation.driver"),
                        new Label("passenger", "automation.passenger"))));
        addCondition(new EventCondition(
                new Label("ac", "automation.ac"),
                "automation.ac_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off"))));
        addCondition(new EventCondition(
                new Label("temperature", "automation.temperature"),
                "automation.temperature_description",
                new IntType(new Label("celsius", "automation.celsius"), 0, 100)));
        addCondition(new EventCondition(
                new Label("speed", "automation.speed"),
                "automation.speed_description",
                new IntType(new Label("speed", "automation.speed"), 0, 200),
                new EnumType(
                        new Label("units", "automation.units"),
                        new Label("kmph", "automation.kmph"),
                        new Label("mph", "automation.mph"))));
        addCondition(new EventCondition(
                new Label("time", "automation.time"),
                "automation.time_description",
                new TimeType(new Label("time", "automation.time"))));
        addCondition(new EventCondition(
                new Label("day", "automation.day"),
                "automation.day_description",
                new EnumType(
                        new Label("day", "automation.day"),
                        new Label("monday", "automation.monday"),
                        new Label("tuesday", "automation.tuesday"),
                        new Label("wednesday", "automation.wednesday"),
                        new Label("thursday", "automation.thursday"),
                        new Label("friday", "automation.friday"),
                        new Label("saturday", "automation.saturday"),
                        new Label("sunday", "automation.sunday"))));
    }

    /**
     * Add a condition to the map
     * Stored as a map to prevent duplicates
     *
     * @param condition The EventCondition to store
     */
    private void addCondition(EventCondition condition) {
        conditions.put(condition.getLabel().getId(), condition);
    }

    /**
     * Get a stored condition
     *
     * @param key The id of the condition
     * @return The requested condition
     */
    public EventCondition getCondition(String key) {
        return conditions.get(key);
    }

    /**
     * A JSON representation of the schema for conditions and triggers
     * All automations require at least 1 trigger
     * The triggers are the same as the conditions but without a value or comparator
     *
     * @return A JSON array representation of the Triggers and actions schema
     */
    public JSONArray toJson() {
        JSONArray json = new JSONArray();
        JSONObject triggers = new Label("triggers", "On Change").toJson();
        JSONObject conditions = new Label("conditions", "If").toJson();

        try {
            triggers.put("description", Messages.get("automation.triggers_description"));
            conditions.put("description", Messages.get("automation.conditions_description"));
            JSONArray triggersList = new JSONArray();
            JSONArray conditionsList = new JSONArray();
            for (EventCondition condition : this.conditions.values()) {
                JSONObject triggerJson = condition.toJson();
                triggerJson.remove("comparator");
                triggerJson.remove("value");
                triggersList.put(triggerJson);

                // Requires a new copy as removing keys will mutate the object
                conditionsList.put(condition.toJson());
            }
            triggers.put("options", triggersList);
            conditions.put("options", conditionsList);
            triggers.put("required", 1);
            // Conditions are not required as an automation may need to be run when the value changes overall
            conditions.put("required", 0);
            json.put(triggers);
            json.put(conditions);
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }
}
