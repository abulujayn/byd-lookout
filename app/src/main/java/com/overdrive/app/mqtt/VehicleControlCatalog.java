package com.overdrive.app.mqtt;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.byd.routing.VehicleCommandRouter.VehicleCommand;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of <b>controllable</b> vehicle entities exposed over MQTT / Home Assistant.
 *
 * Each entry knows two things:
 *   1. how to render itself as a Home Assistant discovery component (with its
 *      command/state topics, value domain, icon, …); and
 *   2. how to turn an inbound MQTT command payload into a
 *      {@link VehicleCommand} (always dispatched <b>SDK-only</b> — never the BYD cloud)
 *      plus an optional optimistic state echo.
 *
 * Read-back state topics reuse the existing per-field telemetry topics where the value
 * is already published ({@code ac_fan}, {@code light_drl}, {@code speed_limit_warning},
 * …). Where no telemetry field exists (e.g. the climate setpoint), the command echoes
 * the commanded value to a synthetic state topic so HA stays in sync.
 *
 * Only Tier-1 (already-implemented, SDK-backed) commands are registered here initially;
 * Tier 2/3 add entries following the same factories.
 */
public final class VehicleControlCatalog {

    private VehicleControlCatalog() {}

    /** Result of turning a payload into an action: the SDK command + optional optimistic echo. */
    public static final class ControlAction {
        public final VehicleCommand command;
        public final String echoKey;    // state-topic suffix to publish optimistically (nullable)
        public final String echoValue;  // value to publish (nullable)
        ControlAction(VehicleCommand c, String k, String v) { command = c; echoKey = k; echoValue = v; }
        public static ControlAction of(VehicleCommand c) { return new ControlAction(c, null, null); }
        public static ControlAction echo(VehicleCommand c, String k, String v) { return new ControlAction(c, k, v); }
    }

    /** Builds a command from (sub-key, payload, current snapshot). Return null to ignore. */
    public interface CommandFn {
        ControlAction build(String sub, String payload, BydVehicleData snap);
    }

    /** Optional capability gate — only advertise the entity when this returns true. */
    public interface AvailableFn {
        boolean available(BydVehicleData snap);
    }

    /** One controllable entity. */
    public static final class ControlEntity {
        public final String key;            // topic key + unique_id suffix
        public final String platform;       // switch/number/select/button/lock/cover/climate
        public final String name;
        public final String icon;
        public final String category;       // "config"/"diagnostic"/null
        public final boolean sensitive;     // windows/sunroof/locks etc.
        public final String stateKey;       // existing telemetry key for state_topic; null = command-only/optimistic
        // select-only: Jinja value_template applied on the state topic to map an
        // enum int (as published by telemetry) onto an option word; null = none.
        public final String stateValueTemplate;
        // platform extras
        public final double min, max, step;
        public final String unit;
        public final List<String> options;
        public final String deviceClass;
        public final String onVal, offVal;
        public final CommandFn cmd;
        public final AvailableFn avail;

        ControlEntity(String key, String platform, String name, String icon, String category,
                      boolean sensitive, String stateKey, double min, double max, double step,
                      String unit, List<String> options, String deviceClass, String onVal, String offVal,
                      CommandFn cmd, AvailableFn avail) {
            this(key, platform, name, icon, category, sensitive, stateKey, min, max, step,
                    unit, options, deviceClass, onVal, offVal, cmd, avail, null);
        }

        ControlEntity(String key, String platform, String name, String icon, String category,
                      boolean sensitive, String stateKey, double min, double max, double step,
                      String unit, List<String> options, String deviceClass, String onVal, String offVal,
                      CommandFn cmd, AvailableFn avail, String stateValueTemplate) {
            this.key = key; this.platform = platform; this.name = name; this.icon = icon;
            this.category = category; this.sensitive = sensitive; this.stateKey = stateKey;
            this.min = min; this.max = max; this.step = step; this.unit = unit; this.options = options;
            this.deviceClass = deviceClass; this.onVal = onVal; this.offVal = offVal;
            this.cmd = cmd; this.avail = avail; this.stateValueTemplate = stateValueTemplate;
        }

        public boolean isAvailable(BydVehicleData snap) {
            try { return avail == null || avail.available(snap); } catch (Exception e) { return true; }
        }

        public ControlAction toAction(String sub, String payload, BydVehicleData snap) {
            try { return cmd.build(sub, payload, snap); } catch (Exception e) { return null; }
        }

        /** Build the Home Assistant discovery component (with topics injected). */
        public JSONObject component(String baseTopic, String node) {
            try {
                JSONObject c = new JSONObject();
                c.put("p", platform);
                c.put("name", name);
                c.put("unique_id", node + "_ctl_" + key);
                if (icon != null) c.put("icon", icon);
                if (category != null) c.put("entity_category", category);
                String cmdBase = baseTopic + "/" + key;

                switch (platform) {
                    case "climate": {
                        c.put("modes", new JSONArray(java.util.Arrays.asList("off", "auto")));
                        c.put("mode_command_topic", cmdBase + "/mode/set");
                        c.put("mode_state_topic", baseTopic + "/ac_on");
                        c.put("mode_state_template", "{% if (value | int(0)) > 0 %}auto{% else %}off{% endif %}");
                        c.put("temperature_command_topic", cmdBase + "/temperature/set");
                        c.put("temperature_state_topic", baseTopic + "/climate_setpoint");
                        c.put("min_temp", min); c.put("max_temp", max); c.put("temp_step", step);
                        JSONArray fans = new JSONArray();
                        for (int i = 0; i <= 7; i++) fans.put(String.valueOf(i));
                        c.put("fan_modes", fans);
                        c.put("fan_mode_command_topic", cmdBase + "/fan_mode/set");
                        c.put("fan_mode_state_topic", baseTopic + "/ac_fan");
                        c.put("current_temperature_topic", baseTopic + "/cabin_temp");
                        break;
                    }
                    case "cover": {
                        if (deviceClass != null) c.put("device_class", deviceClass);
                        c.put("command_topic", cmdBase + "/set");
                        c.put("payload_open", "OPEN");
                        c.put("payload_close", "CLOSE");
                        c.put("payload_stop", "STOP");
                        if (stateKey != null) {
                            c.put("position_topic", baseTopic + "/" + stateKey);
                            c.put("set_position_topic", cmdBase + "/position/set");
                            c.put("position_open", 100);
                            c.put("position_closed", 0);
                        } else {
                            c.put("optimistic", true);
                        }
                        break;
                    }
                    case "lock": {
                        c.put("command_topic", cmdBase + "/set");
                        c.put("payload_lock", "LOCK");
                        c.put("payload_unlock", "UNLOCK");
                        if (stateKey != null) {
                            c.put("state_topic", baseTopic + "/" + stateKey);
                            c.put("state_locked", onVal != null ? onVal : "2");
                            c.put("state_unlocked", offVal != null ? offVal : "1");
                        } else {
                            c.put("optimistic", true);
                        }
                        break;
                    }
                    case "number": {
                        c.put("command_topic", cmdBase + "/set");
                        if (stateKey != null) c.put("state_topic", baseTopic + "/" + stateKey);
                        c.put("min", min); c.put("max", max); c.put("step", step);
                        if (unit != null) c.put("unit_of_measurement", unit);
                        c.put("mode", "slider");
                        break;
                    }
                    case "select": {
                        c.put("command_topic", cmdBase + "/set");
                        if (stateKey != null) {
                            c.put("state_topic", baseTopic + "/" + stateKey);
                            // Telemetry publishes the enum as a raw int; map it onto the
                            // option word so the HA select accepts the state. The `else value`
                            // passthrough leaves an already-word-valued echo untouched.
                            if (stateValueTemplate != null) c.put("value_template", stateValueTemplate);
                        }
                        c.put("options", new JSONArray(options));
                        break;
                    }
                    case "button": {
                        c.put("command_topic", cmdBase + "/set");
                        c.put("payload_press", "PRESS");
                        break;
                    }
                    case "switch":
                    default: {
                        c.put("command_topic", cmdBase + "/set");
                        if (stateKey != null) c.put("state_topic", baseTopic + "/" + stateKey);
                        c.put("payload_on", onVal != null ? onVal : "1");
                        c.put("payload_off", offVal != null ? offVal : "0");
                        c.put("state_on", onVal != null ? onVal : "1");
                        c.put("state_off", offVal != null ? offVal : "0");
                        break;
                    }
                }
                return c;
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ==================== registry ====================

    private static final Map<String, ControlEntity> ENTITIES = new LinkedHashMap<>();

    private static void register(ControlEntity e) { ENTITIES.put(e.key, e); }

    public static Collection<ControlEntity> all() { return ENTITIES.values(); }
    public static ControlEntity get(String key) { return ENTITIES.get(key); }

    // ── factories ───────────────────────────────────────────────────────
    static ControlEntity sw(String key, String name, String icon, String category, String stateKey,
                            String onVal, String offVal, CommandFn cmd) {
        return new ControlEntity(key, "switch", name, icon, category, false, stateKey,
                0, 0, 0, null, null, null, onVal, offVal, cmd, null);
    }
    static ControlEntity number(String key, String name, String icon, String category, String stateKey,
                                double min, double max, double step, String unit, CommandFn cmd) {
        return new ControlEntity(key, "number", name, icon, category, false, stateKey,
                min, max, step, unit, null, null, null, null, cmd, null);
    }
    static ControlEntity select(String key, String name, String icon, String category, String stateKey,
                                List<String> options, CommandFn cmd) {
        return new ControlEntity(key, "select", name, icon, category, false, stateKey,
                0, 0, 0, null, options, null, null, null, cmd, null);
    }
    static ControlEntity select(String key, String name, String icon, String category, String stateKey,
                                List<String> options, String stateValueTemplate, CommandFn cmd) {
        return new ControlEntity(key, "select", name, icon, category, false, stateKey,
                0, 0, 0, null, options, null, null, null, cmd, null, stateValueTemplate);
    }
    static ControlEntity cover(String key, String name, String icon, String deviceClass, boolean sensitive,
                               String stateKey, CommandFn cmd) {
        return new ControlEntity(key, "cover", name, icon, null, sensitive, stateKey,
                0, 0, 0, null, null, deviceClass, null, null, cmd, null);
    }
    static ControlEntity climate(CommandFn cmd) {
        return new ControlEntity("climate", "climate", "Climate", "mdi:air-conditioner", null, false, null,
                17, 33, 1, null, null, null, null, null, cmd, null);
    }

    // ── helpers ─────────────────────────────────────────────────────────
    static int pInt(String s, int dflt) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; } }
    static double pDouble(String s, double dflt) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return dflt; } }
    static boolean truthy(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.equals("1") || t.equalsIgnoreCase("on") || t.equalsIgnoreCase("true") || t.equalsIgnoreCase("ON");
    }
    private static int seatHeat(BydVehicleData s, int idx) {
        return (s != null && s.seatHeat != null && s.seatHeat.length > idx) ? s.seatHeat[idx] : 0;
    }
    private static int seatCool(BydVehicleData s, int idx) {
        return (s != null && s.seatCool != null && s.seatCool.length > idx) ? s.seatCool[idx] : 0;
    }
    private static final List<String> LEVEL_4 = java.util.Arrays.asList("off", "low", "medium", "high");
    private static int levelIndex(String payload) {
        int i = LEVEL_4.indexOf(payload.trim().toLowerCase());
        return i >= 0 ? i : pInt(payload, 0);
    }
    // BYD operation-mode enum values from the SDK docs
    // (doc/android/hardware/bydauto/energy/BYDAutoEnergyDevice.html + constant-values):
    //   ENERGY_OPERATION_ECONOMY = 1, ENERGY_OPERATION_SPORT = 2.
    // These are the ONLY two operation modes the SDK defines — there is no
    // NORMAL and no SNOW here (SNOW is a *road-surface* value on a different
    // axis: ENERGY_ROAD_SURFACE_SNOW = 2). The earlier 0-based list
    // (eco=0/sport=1/normal=2/snow=3) sent setOperationMode(0) for ECO, which is
    // not a valid operation-mode value, so the HAL rejected the write
    // (ENERGY_COMMAND_INVALID) and the mode never changed. Map the words to the
    // real SDK ints; a raw int passes through.
    private static final List<String> DRIVE_MODES = java.util.Arrays.asList("eco", "sport");
    private static int driveModeValue(String payload) {
        String p = payload.trim().toLowerCase();
        if ("eco".equals(p) || "economy".equals(p)) return 1;   // ENERGY_OPERATION_ECONOMY
        if ("sport".equals(p)) return 2;                        // ENERGY_OPERATION_SPORT
        return pInt(payload, 1);
    }

    static {
        // ── Climate (composite) ─────────────────────────────────────────
        register(climate((sub, payload, snap) -> {
            if ("mode".equals(sub)) {
                if ("off".equalsIgnoreCase(payload.trim())) {
                    return ControlAction.echo(new VehicleCommandRouter.ClimateOffCommand(), "ac_on", "0");
                }
                double setpoint = 22;
                return ControlAction.echo(new VehicleCommandRouter.ClimateOnCommand(setpoint), "ac_on", "1");
            }
            if ("temperature".equals(sub)) {
                double t = pDouble(payload, 22);
                return ControlAction.echo(new VehicleCommandRouter.ClimateSetTempCommand(1, t),
                        "climate_setpoint", String.valueOf(t));
            }
            if ("fan_mode".equals(sub)) {
                int f = pInt(payload, 0);
                return ControlAction.echo(new VehicleCommandRouter.ClimateSetFanCommand(f), "ac_fan", String.valueOf(f));
            }
            return null;
        }));

        // ── Windows (all) — cover, command-only (per-window + position: Tier 2) ──
        register(cover("windows_all", "Windows", "mdi:car-door", "window", true, null, (sub, payload, snap) -> {
            int action = "OPEN".equalsIgnoreCase(payload) ? 1 : "STOP".equalsIgnoreCase(payload) ? 3 : 2;
            return ControlAction.of(new VehicleCommandRouter.WindowMoveCommand(0, action, null));
        }));

        // ── Tailgate — cover (open=SDK motor, close/stop) ───────────────
        register(cover("tailgate", "Tailgate", "mdi:car-back", "door", true, null, (sub, payload, snap) -> {
            if ("CLOSE".equalsIgnoreCase(payload)) return ControlAction.of(new VehicleCommandRouter.TrunkCloseCommand());
            if ("STOP".equalsIgnoreCase(payload)) return ControlAction.of(new VehicleCommandRouter.TrunkStopCommand());
            return ControlAction.of(new VehicleCommandRouter.TrunkOpenSdkCommand());
        }));

        // ── Seat heating (driver/passenger) — select off/low/medium/high ─
        register(select("seat_heat_driver", "Driver Seat Heating", "mdi:car-seat-heater", null,
                "seat_heat_driver", LEVEL_4, (sub, payload, snap) -> {
            int lvl = levelIndex(payload);
            VehicleCommand c = new VehicleCommandRouter.SeatHeatCommand(1, lvl,
                    lvl, seatCool(snap, 0), seatHeat(snap, 1), seatCool(snap, 1));
            return ControlAction.echo(c, "seat_heat_driver", LEVEL_4.get(Math.min(3, Math.max(0, lvl))));
        }));
        register(select("seat_heat_passenger", "Passenger Seat Heating", "mdi:car-seat-heater", null,
                "seat_heat_passenger", LEVEL_4, (sub, payload, snap) -> {
            int lvl = levelIndex(payload);
            VehicleCommand c = new VehicleCommandRouter.SeatHeatCommand(2, lvl,
                    seatHeat(snap, 0), seatCool(snap, 0), lvl, seatCool(snap, 1));
            return ControlAction.echo(c, "seat_heat_passenger", LEVEL_4.get(Math.min(3, Math.max(0, lvl))));
        }));

        // ── Seat ventilation (driver/passenger) — select ────────────────
        register(select("seat_vent_driver", "Driver Seat Ventilation", "mdi:car-seat-cooler", null,
                "seat_vent_driver", LEVEL_4, (sub, payload, snap) -> {
            int lvl = levelIndex(payload);
            VehicleCommand c = new VehicleCommandRouter.SeatVentCommand(1, lvl,
                    seatHeat(snap, 0), lvl, seatHeat(snap, 1), seatCool(snap, 1));
            return ControlAction.echo(c, "seat_vent_driver", LEVEL_4.get(Math.min(3, Math.max(0, lvl))));
        }));
        register(select("seat_vent_passenger", "Passenger Seat Ventilation", "mdi:car-seat-cooler", null,
                "seat_vent_passenger", LEVEL_4, (sub, payload, snap) -> {
            int lvl = levelIndex(payload);
            VehicleCommand c = new VehicleCommandRouter.SeatVentCommand(2, lvl,
                    seatHeat(snap, 0), seatCool(snap, 0), seatHeat(snap, 1), lvl);
            return ControlAction.echo(c, "seat_vent_passenger", LEVEL_4.get(Math.min(3, Math.max(0, lvl))));
        }));

        // ── Seat memory recall — buttons ────────────────────────────────
        register(new ControlEntity("seat_memory_driver", "button", "Recall Driver Seat", "mdi:seat-recline-extra",
                null, false, null, 0, 0, 0, null, null, null, null, null,
                (sub, payload, snap) -> ControlAction.of(new VehicleCommandRouter.SeatMemoryCommand(1)), null));

        // ── Daytime running lights — switch (real state) ────────────────
        register(sw("drl", "Daytime Running Lights", "mdi:car-light-dimmed", null, "light_drl", "1", "0",
                (sub, payload, snap) -> ControlAction.of(new VehicleCommandRouter.LightsCommand(truthy(payload)))));

        // ── Ambient lights colour — number (real state) ────────────────
        register(number("ambient_colour", "Ambient Lights Colour", "mdi:format-color-fill", "config",
                "ambient_colour", 1, 31, 1, "", (sub, payload, snap) -> {
                    return ControlAction.of(new VehicleCommandRouter.AmbientColourCommand(pInt(payload, 1)));
                }));

        // ── ADAS speed-limit warning — switch (real state) ──────────────
        register(sw("adas_slw", "Speed Limit Warning", "mdi:speedometer-slow", "config", "speed_limit_warning",
                "1", "0", (sub, payload, snap) ->
                        ControlAction.of(new VehicleCommandRouter.AdasSpeedLimitWarningCommand(truthy(payload)))));

        // ── ADAS child presence detection — switch (real state) ──────────────
        // TODO: stateKey is not a boolean. Is that an issue?
        register(sw("adas_cpd", "Child Presence Detection", "mdi:car-child-seat", "config", "child_presence_detection",
                "1", "0", (sub, payload, snap) ->
                        ControlAction.of(new VehicleCommandRouter.SettingChildPresenceDetectionCommand(truthy(payload) ? 1 : 2))));

        // ── Charge cap (BEV) — switch + number, optimistic state ────────
        register(sw("charge_cap_enabled", "Charge Limit", "mdi:battery-charging-100", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.ChargeCapToggleCommand(truthy(payload)),
                        "charge_cap_enabled", truthy(payload) ? "1" : "0")));
        register(number("charge_cap_percent", "Charge Limit %", "mdi:battery-charging-80", "config",
                "charge_cap_percent", 50, 100, 5, "%", (sub, payload, snap) -> {
            int pct = Math.max(50, Math.min(100, pInt(payload, 80)));
            return ControlAction.echo(new VehicleCommandRouter.ChargeCapPercentCommand(pct),
                    "charge_cap_percent", String.valueOf(pct));
        }));

        // ── Tier 2: sunroof / sunshade (covers) + child lock + wireless charger ──
        register(cover("sunroof", "Sunroof", "mdi:window-shutter-open", "window", true, null, (sub, payload, snap) -> {
            int cmd = "OPEN".equalsIgnoreCase(payload) ? 1 : "STOP".equalsIgnoreCase(payload) ? 3 : 2;
            return ControlAction.of(new VehicleCommandRouter.SunroofCommand(cmd));
        }));
        register(cover("sunshade", "Sunshade", "mdi:blinds", "shade", true, null, (sub, payload, snap) -> {
            int cmd = "OPEN".equalsIgnoreCase(payload) ? 1 : "STOP".equalsIgnoreCase(payload) ? 3 : 2;
            return ControlAction.of(new VehicleCommandRouter.SunshadeCommand(cmd));
        }));
        register(sw("child_lock", "Child Lock", "mdi:car-door-lock", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.ChildLockCommand(truthy(payload)),
                        "child_lock", truthy(payload) ? "1" : "0")));
        register(sw("wireless_charging", "Phone Wireless Charger", "mdi:battery-charging-wireless", null, null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.WirelessChargingCommand(truthy(payload)),
                        "wireless_charging", truthy(payload) ? "1" : "0")));

        // ── Drive / energy modes (BYDAutoEnergyDevice / SettingDevice) ────
        // select entities: HA renders a dropdown, keymap binds one option, the
        // automation UI exposes the same option set. Payloads are readable words
        // mapped to the BYD SDK int enum; pInt() also accepts a raw int.
        // drive_mode: telemetry publishes operationMode under "op_mode" (not
        // "operation_mode") as a raw int; bind the state topic there and map the
        // int→word via value_template so the HA select accepts live telemetry.
        // The echo emits the option word directly (in-domain).
        // drive_mode: op_mode telemetry is the raw SDK operation-mode int
        // (ENERGY_OPERATION_ECONOMY=1, ENERGY_OPERATION_SPORT=2). Echo the word
        // and map int→word on the state topic using the SAME 1/2 values the SDK
        // reports, so the live telemetry and the optimistic echo agree.
        register(select("drive_mode", "Drive Mode", "mdi:car-shift-pattern", null, "op_mode",
                DRIVE_MODES,
                "{% set m = value | int(-1) %}{{ 'eco' if m == 1 else 'sport' if m == 2 else value }}",
                (sub, payload, snap) -> {
                    int m = driveModeValue(payload);
                    return ControlAction.echo(new VehicleCommandRouter.OperationModeCommand(m),
                            "op_mode", m == 2 ? "sport" : "eco");
                }));
        // powertrain_mode: energy_mode telemetry is the raw SDK energy-mode int
        // (ENERGY_MODE_EV=1, ENERGY_MODE_HEV=3; NOTE 0=ENERGY_MODE_STOP, NOT ev).
        // The old code sent setEnergyMode(0) for EV — which is STOP — so EV never
        // engaged. Map the words to the real SDK ints and mirror them on the
        // state topic.
        final List<String> POWERTRAIN = java.util.Arrays.asList("ev", "hev");
        register(select("powertrain_mode", "Powertrain Mode", "mdi:engine", null, "energy_mode",
                POWERTRAIN,
                "{% set m = value | int(-1) %}{{ 'ev' if m == 1 else 'hev' if m == 3 else value }}",
                (sub, payload, snap) -> {
                    int m = "hev".equalsIgnoreCase(payload.trim()) ? 3   // ENERGY_MODE_HEV
                          : "ev".equalsIgnoreCase(payload.trim()) ? 1    // ENERGY_MODE_EV
                          : pInt(payload, 1);
                    return ControlAction.echo(new VehicleCommandRouter.EnergyModeCommand(m),
                            "energy_mode", m == 3 ? "hev" : "ev");
                }));
        // regen_level: SET_DR_ENERGY_FB_STANDARD = 1, SET_DR_ENERGY_FB_LARGE = 2
        // (there is no 0). Old code sent 0/1 → the HAL rejected 0.
        register(select("regen_level", "Energy Recuperation", "mdi:battery-charging-medium", null, null,
                java.util.Arrays.asList("standard", "high"),
                (sub, payload, snap) -> {
                    int lvl = "high".equalsIgnoreCase(payload.trim()) ? 2   // SET_DR_ENERGY_FB_LARGE
                            : "standard".equalsIgnoreCase(payload.trim()) ? 1 // SET_DR_ENERGY_FB_STANDARD
                            : pInt(payload, 1);
                    return ControlAction.of(new VehicleCommandRouter.EnergyFeedbackCommand(lvl));
                }));
        // steering_mode: SET_DR_ST_ASSIS_COMFORT = 1, SET_DR_ST_ASSIS_SPORT = 2
        // (there is no 0). Old code sent 0/1 → the HAL rejected 0.
        register(select("steering_mode", "Steering Assist", "mdi:steering", null, null,
                java.util.Arrays.asList("comfort", "sport"),
                (sub, payload, snap) -> {
                    int m = "sport".equalsIgnoreCase(payload.trim()) ? 2    // SET_DR_ST_ASSIS_SPORT
                          : "comfort".equalsIgnoreCase(payload.trim()) ? 1  // SET_DR_ST_ASSIS_COMFORT
                          : pInt(payload, 1);
                    return ControlAction.of(new VehicleCommandRouter.SteerAssistCommand(m));
                }));

        // ── Tier 3: curated CAN-backed car settings (local carsettings provider) ──
        for (com.overdrive.app.byd.BydCarSettings.CarSetting s : com.overdrive.app.byd.BydCarSettings.registry()) {
            final String key = s.key;
            final String stateKey = "setting_" + s.key;
            final int sMin = s.min, sMax = s.max, sStep = s.step;
            final int[] sOpts = s.options;
            switch (s.kind) {
                case BOOL: {
                    register(sw(stateKey, s.name, s.icon, "config", stateKey, "1", "0",
                            (sub, payload, snap) -> ControlAction.echo(
                                    new VehicleCommandRouter.CarSettingCommand(key, truthy(payload) ? 1 : 0),
                                    stateKey, truthy(payload) ? "1" : "0")));
                    break;
                }
                case INT_RANGE: {
                    register(number(stateKey, s.name, s.icon, "config", stateKey, sMin, sMax, sStep, s.unit,
                            (sub, payload, snap) -> {
                                int v = Math.max(sMin, Math.min(sMax, pInt(payload, sMin)));
                                return ControlAction.echo(new VehicleCommandRouter.CarSettingCommand(key, v),
                                        stateKey, String.valueOf(v));
                            }));
                    break;
                }
                case INT_ENUM: {
                    java.util.List<String> opts = new java.util.ArrayList<>();
                    for (int o : sOpts) opts.add(String.valueOf(o));
                    register(select(stateKey, s.name, s.icon, "config", stateKey, opts,
                            (sub, payload, snap) -> {
                                int v = pInt(payload, sOpts.length > 0 ? sOpts[0] : 0);
                                return ControlAction.echo(new VehicleCommandRouter.CarSettingCommand(key, v),
                                        stateKey, String.valueOf(v));
                            }));
                    break;
                }
            }
        }
    }
}
