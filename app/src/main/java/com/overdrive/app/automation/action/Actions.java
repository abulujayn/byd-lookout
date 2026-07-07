package com.overdrive.app.automation.action;

import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public class Actions {
    // Create LinkedHashMap to maintain the insertion order of actions to display in the frontend
    private final Map<String, Action> actions = new LinkedHashMap<>();

    /**
     * Initialize actions list with actions that can be selected
     */
    public Actions() {
        addAction(new NotificationAction(new Label("notification", "automation.send_notification"), "automation.send_notification_description"));
        addAction(new VehicleControlAction(
                new Label("adas_slw", "automation.set_slw"), "automation.set_slw_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new VehicleControlAction(
                new Label("drl", "automation.set_drl"), "automation.set_drl_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // The all windows option can't be done to a specific percentage so not including it
        addAction(new ApiAction(
                new Label("windows", "automation.open_windows"),
                "automation.open_windows_description",
                "POST",
                "/api/vehicle/window",
                "{\"area\":${area},\"targetPercent\":${percent}}",
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("1", "automation.area_lf"),
                        new Label("2", "automation.area_rf"),
                        new Label("3", "automation.area_lr"),
                        new Label("4", "automation.area_rr"),
                        new Label("5", "automation.area_sunroof"),
                        new Label("6", "automation.area_sunshade")),
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        addAction(new VehicleControlAction(
                new Label("sunshade", "automation.set_sunshade"),
                "automation.set_sunshade_description",
                new EnumType(
                        new Label("payload", "automation.action"),
                        new Label("close", "automation.close"),
                        new Label("open", "automation.open"),
                        new Label("stop", "automation.stop"))));
        addAction(new VehicleControlAction(
                new Label("sunroof", "automation.set_sunroof"),
                "automation.set_sunroof_description",
                new EnumType(
                        new Label("payload", "automation.action"),
                        new Label("close", "automation.close"),
                        new Label("open", "automation.open"),
                        new Label("stop", "automation.stop"))));
        addAction(new ApiAction(
                new Label("setAc", "automation.set_ac"),
                "automation.set_ac_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"power_${action}\"}",
                new EnumType(new Label("action", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new ApiAction(
                new Label("setAcTemp", "automation.set_ac_temp"),
                "automation.set_ac_temp_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"set_temp\",\"temp\":${temperature}}",
                new IntType(new Label("temperature", "automation.temperature"), 17, 33)));
        addAction(new ApiAction(
                new Label("setAcFan", "automation.set_ac_fan"),
                "automation.set_ac_fan_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"set_fan\",\"fan\":${speed}}",
                new IntType(new Label("speed", "automation.speed"), 1, 7)));
        addAction(new VehicleControlAction(
                new Label("seat", "automation.seat_climate"),
                "automation.set_seat_climate_description",
                new EnumType(new Label("type", "automation.type"), new Label("heat", "automation.heat"), new Label("vent", "automation.cool")),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("driver", "automation.driver"),
                        new Label("passenger", "automation.passenger")),
                // Seat climate is 3-level hardware (off/low/high); the SDK write path collapses any
                // "medium" onto high, so exposing only these three keeps the action honest and
                // symmetric with the seatClimate condition enum.
                new EnumType(
                        new Label("payload", "automation.state"),
                        new Label("off", "automation.off"),
                        new Label("low", "automation.low"),
                        new Label("high", "automation.high"))));
        addAction(new ApiAction(
                new Label("seatPosition", "automation.set_seat_position"),
                "automation.set_seat_position_description",
                "POST",
                "/api/vehicle/seat",
                "{\"action\":\"position\",\"position\":${position}}",
                new EnumType(
                        new Label("position", "automation.action"),
                        new Label("1", "automation.position_1"),
                        new Label("2", "automation.position_2"))));
        // Drive / energy modes — same catalog entities the keymap and MQTT use.
        // The Label id must match the VehicleControlCatalog key so
        // VehicleControlAction.trigger resolves it.
        addAction(new VehicleControlAction(
                new Label("drive_mode", "automation.set_drive_mode"), "automation.set_drive_mode_description",
                // Operation mode has exactly two SDK values: ENERGY_OPERATION_ECONOMY(1)
                // and ENERGY_OPERATION_SPORT(2). "normal"/"snow" are NOT operation modes
                // (snow is a separate road-surface axis), so they are not offered — a
                // binding to them would send an invalid value the HAL rejects.
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("eco", "automation.mode_eco"),
                        new Label("sport", "automation.mode_sport"))));
        addAction(new VehicleControlAction(
                new Label("powertrain_mode", "automation.set_powertrain_mode"), "automation.set_powertrain_mode_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("ev", "automation.mode_ev"),
                        new Label("hev", "automation.mode_hev"))));
        addAction(new VehicleControlAction(
                new Label("regen_level", "automation.set_regen"), "automation.set_regen_description",
                new EnumType(new Label("payload", "automation.level"),
                        new Label("standard", "automation.regen_standard"),
                        new Label("high", "automation.regen_high"))));
        addAction(new VehicleControlAction(
                new Label("steering_mode", "automation.set_steering"), "automation.set_steering_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("comfort", "automation.steering_comfort"),
                        new Label("sport", "automation.steering_sport"))));
        addAction(new ApiAction(
                new Label("surveillance", "automation.set_surveillance"),
                "automation.set_surveillance_description",
                "POST",
                "/api/surveillance/${action}",
                "",
                new EnumType(
                        new Label("action", "automation.action"),
                        new Label("disable", "automation.off"),
                        new Label("enable", "automation.on"))));
        // Recording settings are inline in the handleClient method so can't be accessed. Only doing surveillance for now.
        // addAction(new ApiAction(
        //         new Label("recording", "automation.set_recording"),
        //         "automation.set_recording_description",
        //         "POST",
        //         "/api/recording/mode",
        //         "{\"mode\":\"${mode}\"}",
        //         new EnumType(
        //                 new Label("mode", "automation.mode"),
        //                 new Label("NONE", "automation.none"),
        //                 new Label("CONTINUOUS", "automation.continuous"),
        //                 new Label("DRIVE_MODE", "automation.drive_mode"),
        //                 new Label("PROXIMITY_GUARD", "automation.proximity_guard"))));
        // Shell command — the free-text StringType variable is defined inside
        // ShellAction. Autonomous exec, so it self-gates on the dedicated
        // automation.allowShell flag (toggle on the Automations page) at fire
        // time (off → logged no-op). Registered last so curated actions lead.
        addAction(new ShellAction(
                new Label("shell", "automation.run_shell"), "automation.run_shell_description"));

    }

    /**
     * Add an action to the map
     * Stored as a map to prevent duplicates
     *
     * @param action The Action to store
     */
    private void addAction(Action action) {
        actions.put(action.getLabel().getId(), action);
    }

    /**
     * Get a stored action
     *
     * @param key The id of the action
     * @return The requested action
     */
    public Action getAction(String key) {
        return actions.get(key);
    }

    /**
     * A JSON representation of the schema for actions
     * All automations require at least 1 action so the required key is set to 1
     *
     * @return A JSON representation of the Actions schema
     */
    public JSONObject toJson() {
        JSONObject actions = new Label("actions", "Then").toJson();

        try {
            actions.put(
                    "description", Messages.get("automation.actions_description"));
            JSONArray actionsList = new JSONArray();
            for (Action action : this.actions.values()) {
                actionsList.put(action.toJson());
            }
            actions.put("options", actionsList);
            actions.put("required", 1);
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return actions;
    }
}
