package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class ColourType extends BaseType<Integer> {
    private static final String TYPE = "colour";
    private final Label label;
    private final List<String> colourCodes;

    /**
     * A colour representation
     * This is a very specific type to work with the BYD api which is not likely to be used more than once
     * Takes a list of colours and expects the return value to be the index of that colour
     * The index should start at 1 for the first colour
     *
     * @param label An id and display name for this int
     * @param colourCodes The options for which colours this could be
     */
    public ColourType(Label label, String... colourCodes) {
        this.label = label;
        this.colourCodes = Arrays.asList(colourCodes);
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
     * The list of colours this type represents
     *
     * @return The minimum value this int can be (inclusive)
     */
    public List<String> getColourCodes() {
        return colourCodes;
    }

    /**
     * The comparators for this type
     * This should not be used
     *
     * @return null
     */
    public EnumType getComparators() {
        return null;
    }

    /**
     * Check if the value is valid
     * The value should be a position in the colour codes list but with a 1 index
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    public boolean isValidValue(Integer value) {
        if (value == null) return false;

        return value > 0 && value <= getColourCodes().size();
    }

    /**
     * Create a JSON representation of this int to display in the frontend
     * Will contain the min and max values
     *
     * @return JSON representation of this int
     */
    public JSONObject toJson() {
        JSONObject json = getLabel().toJson();

        try {
            json.put("type", TYPE);
            json.put("colourCodes", new JSONArray(getColourCodes()));
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }
}
