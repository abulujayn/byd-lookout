package com.overdrive.app.camera;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Raw quarter-slice from the merged panoramic strip.
 *
 * These are intentionally neutral physical slices, not logical directions.
 * Logical front/right/rear/left is derived by mapping camera roles to slices.
 */
public enum PanoramicSlice {
    SLICE_1("slice1", "Merged slice 1", 0, 0.00f),
    SLICE_2("slice2", "Merged slice 2", 1, 0.25f),
    SLICE_3("slice3", "Merged slice 3", 2, 0.50f),
    SLICE_4("slice4", "Merged slice 4", 3, 0.75f);

    private final String id;
    private final String displayName;
    private final int index;
    private final float stripOffsetX;

    PanoramicSlice(String id, String displayName, int index, float stripOffsetX) {
        this.id = id;
        this.displayName = displayName;
        this.index = index;
        this.stripOffsetX = stripOffsetX;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getIndex() {
        return index;
    }

    public float getStripOffsetX() {
        return stripOffsetX;
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        putSafely(out, "id", id);
        putSafely(out, "label", displayName);
        putSafely(out, "index", index + 1);
        putSafely(out, "offsetX", stripOffsetX);
        return out;
    }

    public static PanoramicSlice fromId(String id) {
        if (id == null) return null;
        for (PanoramicSlice value : values()) {
            if (value.id.equalsIgnoreCase(id)) {
                return value;
            }
        }
        return null;
    }

    public static PanoramicSlice fromLegacyView(CameraVirtualView view) {
        if (view == null) return null;
        switch (view) {
            case REAR:  return SLICE_1;
            case LEFT:  return SLICE_2;
            case RIGHT: return SLICE_3;
            case FRONT:
            default:    return SLICE_4;
        }
    }

    private static void putSafely(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to write JSON field '" + key + "'", e);
        }
    }
}