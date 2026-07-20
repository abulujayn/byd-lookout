package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseAction implements Action {
    protected static final DaemonLogger logger = DaemonLogger.getInstance("AutomationAction");

    /**
     * A string id for this action
     *
     * @return String representing this action
     */
    public abstract String getType();

    /**
     * The description for this action
     * Should be translated using the language files
     *
     * @return The translated description for this action
     */
    public abstract String getDescription();

    /**
     * The variables for this action
     * Can be an empty list for no variables
     *
     * @return The variables for this action
     */
    public abstract List<Type> getVariables();

    /**
     * Create a JSON object with the fields required for the frontend to display
     * This can be overridden if needed
     *
     * @return JSON representation of this action
     */
    public JSONObject toJson() {
        JSONObject json = getLabel().toJson();

        try {
            json.put("type", getType());
            JSONArray variables = new JSONArray();
            for (Type variable : getVariables()) {
                variables.put(variable.toJson());
            }
            json.put("variables", variables);
            json.put("description", getDescription());
            // Tell the web form this action carries nested child action lists (loop
            // body / if-then), so it renders a nested action editor. Emitted only for
            // control-flow actions; ordinary actions omit it (unchanged schema).
            if (hasChildActions()) {
                json.put("hasChildActions", true);
                // "if" additionally has an else branch; "loop" does not.
                if ("if".equals(getType())) json.put("hasElseActions", true);
            }
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * An automation action with the id of this instance and the variables needed for the trigger
     * This can be overridden if needed
     *
     * @param input The JSON passed from the frontend
     * @return An AutomationAction that can later be used to trigger this action
     */
    public AutomationAction fromJson(JSONObject input) {
        try {
            String type = getLabel().getId();
            Map<String, Object> variables = new HashMap<>();
            JSONObject variablesJson = input.optJSONObject("variables");
            for (Type variable : getVariables()) {
                String key = variable.getLabel().getId();
                Object value = variablesJson.get(key);
                if (variable.isValid(value)) {
                    variables.put(key, value);
                } else {
                    return null;
                }
            }

            return new AutomationAction(type, variables);
        } catch (Exception e) {
            return null;
        }
    }
}
