package com.overdrive.app.automation;

import com.overdrive.app.automation.action.Action;
import com.overdrive.app.automation.condition.EventCondition;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.value.Value;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Automation {
    private final LinkedHashSet<EventData> triggers;
    private final List<AutomationCondition> conditions;
    private final int delay;
    private final List<AutomationAction> actions;

    // volatile: written from HTTP request threads (setDisabled) and read from the telemetry thread
    // (stateChanged) and the queue worker thread (triggerActions) with no shared lock, so a plain field
    // could let those threads observe a stale enabled/disabled state and fire a just-disabled automation.
    private volatile boolean disabled;

    /**
     * A representation of a single automation
     * Contains all the fields which build up an automation
     * Can be stored and loaded when needed
     *
     * @param triggers   The events which would cause this automation to be checked
     * @param conditions The conditions to check before applying the actions of this automation
     * @param delay      The amount of time to wait before running the actions in seconds
     * @param actions    The actions which will be run in order when this automation is triggered
     * @param disabled   Whether this automation is disabled. This can be mutated to disable and enable later
     */
    public Automation(
            List<EventData> triggers,
            List<AutomationCondition> conditions,
            Integer delay,
            List<AutomationAction> actions,
            boolean disabled) {
        this.triggers = new LinkedHashSet<>(triggers);
        this.conditions = conditions;
        this.delay = Objects.requireNonNullElse(delay, 0);
        this.actions = actions;
        this.disabled = disabled;
    }

    /**
     * The events which would cause this automation to be checked
     *
     * @return The events which would cause this automation to be checked
     */
    public LinkedHashSet<EventData> getTriggers() {
        return triggers;
    }

    /**
     * The conditions to check before applying the actions of this automation
     *
     * @return The conditions to check before applying the actions of this automation
     */
    public List<AutomationCondition> getConditions() {
        return conditions;
    }

    /**
     * The amount of time to wait before running the actions in seconds
     *
     * @return The amount of time to wait before running the actions in seconds
     */
    public int getDelay() {
        return delay;
    }

    /**
     * The actions which will be run in order when this automation is triggered
     *
     * @return The actions which will be run in order when this automation is triggered
     */
    public List<AutomationAction> getActions() {
        return actions;
    }

    /**
     * Whether this automation is currently disabled
     *
     * @return Whether this automation is currently disabled
     */
    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Check if the triggered contains an event
     * Will be looked up from a set so is fast to check
     *
     * @param trigger The event to check
     * @return true if this automation should be checked, false otherwise
     */
    public boolean isTriggered(EventData trigger) {
        return triggers.contains(trigger);
    }

    /**
     * Whether all the conditions specified in this automation are met
     *
     * @param state The current event state
     * @return true if all conditions match the state, false otherwise
     */
    public boolean conditionsMet(Map<EventData, Value> state) {
        return conditions.stream().allMatch(condition -> condition.compare(state.get(condition.getEventData())));
    }

    /**
     * Mutate this automation to disable the actions
     *
     * @param disabled Whether this should be disabled
     */
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    /**
     * Run all the actions for this automation
     * Will not check whether the conditions are met so that should be checked first if needed
     */
    public void triggerActions() {
        for (AutomationAction automationAction : getActions()) {
            // Defense-in-depth: a hand-edited config.json can still yield a null or unknown-type
            // action element; skip it so it can never NPE the queue drainer or the /test path.
            if (automationAction == null) continue;
            Action action = Automations.getAction(automationAction.getType());
            if (action == null) continue;
            action.trigger(automationAction);
        }
    }

    /**
     * Create a JSON object which can be stored and loaded for this automation
     *
     * @return JSON representation of this automation
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();

        try {
            JSONArray triggers = new JSONArray();
            for (EventData trigger : getTriggers()) {
                triggers.put(trigger.toJson());
            }
            json.put("triggers", triggers);
            JSONArray conditions = new JSONArray();
            for (AutomationCondition condition : getConditions()) {
                conditions.put(condition.toJson());
            }
            json.put("conditions", conditions);
            json.put("delay", getDelay());
            JSONArray actions = new JSONArray();
            for (AutomationAction action : getActions()) {
                actions.put(action.toJson());
            }
            json.put("actions", actions);
            json.put("disabled", isDisabled());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * Create an instance of this class from some JSON
     * Will validate that the automation is valid
     *
     * @param input The JSON for this automation
     * @return An automation instance if the JSON is valid, null otherwise
     */
    public static Automation fromJson(JSONObject input) {
        try {
            List<EventData> triggers = new ArrayList<>();
            JSONArray triggersJson = input.getJSONArray("triggers");
            if (triggersJson.length() == 0) return null;
            for (int i = 0; i < triggersJson.length(); i++) {
                JSONObject triggerJson = triggersJson.getJSONObject(i);
                String key = triggerJson.getString("type");
                EventCondition condition = Automations.getCondition(key);
                if (condition == null) return null;
                triggers.add(condition.eventData(triggerJson));
            }

            List<AutomationCondition> conditions = new ArrayList<>();
            JSONArray conditionsJson = input.optJSONArray("conditions");
            if (conditionsJson != null) {
                for (int i = 0; i < conditionsJson.length(); i++) {
                    JSONObject conditionJson = conditionsJson.getJSONObject(i);
                    String key = conditionJson.getString("type");
                    EventCondition condition = Automations.getCondition(key);
                    if (condition == null) return null;
                    conditions.add(condition.automationCondition(conditionJson));
                }
            }

            int delay = input.optInt("delay", 0);
            if (!Automations.isValidDelay(delay)) return null;

            List<AutomationAction> actions = new ArrayList<>();
            JSONArray actionsJson = input.getJSONArray("actions");
            if (actionsJson.length() == 0) return null;
            for (int i = 0; i < actionsJson.length(); i++) {
                JSONObject actionJson = actionsJson.getJSONObject(i);
                String key = actionJson.getString("type");
                Action action = Automations.getAction(key);
                if (action == null) return null;
                AutomationAction automationAction = action.fromJson(actionJson);
                if (automationAction == null) return null;
                actions.add(automationAction);
            }

            boolean disabled = input.optBoolean("disabled", false);

            return new Automation(triggers, conditions, delay, actions, disabled);
        } catch (Exception e) {
            return null;
        }
    }
}
