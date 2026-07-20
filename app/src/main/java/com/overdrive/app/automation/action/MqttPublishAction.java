package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.server.Messages;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publish an MQTT message from an automation — the outbound sink that lets a rule notify
 * Home Assistant (or any broker consumer), e.g. "when a person is detected while parked →
 * publish overdrive/alert = person". Fans the message out to every active MQTT connection
 * via {@link com.overdrive.app.mqtt.MqttConnectionManager#publishToAll}.
 *
 * <p>Deliberately NOT an {@link ApiAction}: {@code /api/mqtt} is intentionally OFF the
 * automation HTTP allowlist ({@code HttpServer.AUTOMATION_ALLOWED_PREFIXES}) — a hard
 * security boundary. This calls the connection manager IN-PROCESS instead, mirroring
 * {@link ManualClipAction} / {@link AutomationControlAction} / {@link RadioAction}.
 *
 * <p>Topic and payload both support {@code ${variable}} interpolation against the shared
 * automation state (same convention as {@link ApiAction} bodies), so a rule can publish a
 * counter/flag another action set. A relative topic is scoped under each connection's
 * base topic; an absolute topic ("/…") is used as-is. No live MQTT connection → clean
 * no-op (logged), never throws.
 */
public class MqttPublishAction extends BaseAction {
    private static final String TYPE = "mqttPublish";
    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)\\}");

    private final Label label;
    private final String description;
    private final List<Type> variables = List.of(
            new StringType(new Label("topic", "automation.mqtt_topic"), 128),
            new StringType(new Label("payload", "automation.mqtt_payload"), 256),
            new EnumType(new Label("retain", "automation.mqtt_retain"),
                    new Label("false", "automation.off"),
                    new Label("true", "automation.on")));

    public MqttPublishAction(Label label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getType() { return TYPE; }
    public Label getLabel() { return label; }
    public String getDescription() { return Messages.get(description); }
    public List<Type> getVariables() { return variables; }

    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        String topic = interpolate(str(vars.get("topic")));
        String payload = interpolate(str(vars.get("payload")));
        boolean retain = "true".equals(str(vars.get("retain")));
        if (topic == null || topic.isEmpty()) {
            logger.warn("MqttPublishAction: empty topic, skipping");
            return;
        }
        try {
            com.overdrive.app.mqtt.MqttConnectionManager mgr = CameraDaemon.getMqttConnectionManager();
            if (mgr == null) {
                logger.warn("MqttPublishAction: MQTT manager unavailable — skipping publish to " + topic);
                return;
            }
            int published = mgr.publishToAll(topic, payload == null ? "" : payload, retain);
            logger.info("MqttPublishAction: '" + topic + "' -> " + published + " connection(s)");
        } catch (Throwable t) {
            logger.warn("MqttPublishAction failed: " + t.getMessage());
        }
    }

    /** Replace ${name} with the current value of that automation variable, or leave the
     *  literal placeholder when unset. Best-effort — any error yields the input unchanged. */
    private static String interpolate(String input) {
        if (input == null || input.indexOf("${") < 0) return input;
        try {
            Matcher m = VAR.matcher(input);
            StringBuffer out = new StringBuffer();
            while (m.find()) {
                String name = m.group(1);
                String val = null;
                try {
                    com.overdrive.app.automation.value.Value v = Automations.getStateValue(
                            SetVariableAction.variableEvent(name));
                    if (v != null) val = v.toString();
                } catch (Throwable ignored) { }
                m.appendReplacement(out, Matcher.quoteReplacement(val != null ? val : m.group(0)));
            }
            m.appendTail(out);
            return out.toString();
        } catch (Throwable t) {
            return input;
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString().trim(); }
}
