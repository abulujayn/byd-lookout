package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.Label;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

public class TimeType extends BaseType<Integer> {
    private static final String TYPE = "time";
    private final Label label;

    /**
     * A time representation
     * Expects the resulting value to be the number of minutes since the start of the day
     *
     * @param label An id and display name for this int
     * @param colourCodes The options for which colours this could be
     */
    public TimeType(Label label, String... colourCodes) {
        this.label = label;
    }

    /**
     * The label that was stored when this int was initialized
     *
     * @return The Label for this int
     */
    public Label getLabel() {
        return label;
    }

    /**
     * The comparators for this type
     *
     * @return The comparators for this type
     */
    public EnumType getComparators() {
        return IntValue.COMPARATORS;
    }

    /**
     * Check if the value is valid
     * The value should not exceed the number of minutes in a day
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    public boolean isValidValue(Integer value) {
        if (value == null) return false;

        return value >= 0 && value < 60 * 24;
    }

    /**
     * Create a JSON representation of this type to display in the frontend
     *
     * @return JSON representation of this type
     */
    public JSONObject toJson() {
        JSONObject json = getLabel().toJson();

        try {
            json.put("type", TYPE);
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }
}
