package com.overdrive.app.camera;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of known camera profiles.
 */
public final class CameraProfiles {
    public static final String PROFILE_AUTO = "auto";
    public static final String PROFILE_LEGACY_SEAL_ATTO = "legacy_seal_atto";
    public static final String PROFILE_TANG_2022 = "tang_2022";

    private static final LinkedHashMap<String, CameraProfile> PROFILES = new LinkedHashMap<>();

    static {
        EnumMap<CameraRole, CameraSourceRef> legacyMappings = new EnumMap<>(CameraRole.class);
        legacyMappings.put(CameraRole.PANO_FRONT, CameraSourceRef.panoramicSlice(PanoramicSlice.SLICE_4));
        legacyMappings.put(CameraRole.PANO_RIGHT, CameraSourceRef.panoramicSlice(PanoramicSlice.SLICE_3));
        legacyMappings.put(CameraRole.PANO_REAR, CameraSourceRef.panoramicSlice(PanoramicSlice.SLICE_1));
        legacyMappings.put(CameraRole.PANO_LEFT, CameraSourceRef.panoramicSlice(PanoramicSlice.SLICE_2));

        register(new CameraProfile(
                PROFILE_LEGACY_SEAL_ATTO,
                "Legacy panoramic (Seal / Atto)",
                1,
                5120,
                960,
                0,
                1280,
                960,
                legacyMappings));

        EnumMap<CameraRole, CameraSourceRef> tangMappings = new EnumMap<>(legacyMappings);
        tangMappings.put(CameraRole.WINDSHIELD, CameraSourceRef.direct(0));
        register(new CameraProfile(
                PROFILE_TANG_2022,
                "BYD Tang 2022",
                2,
                5120,
                720,
                0,
                1280,
                720,
                tangMappings));
    }

    private CameraProfiles() {
    }

    private static void register(CameraProfile profile) {
        PROFILES.put(profile.getId(), profile);
    }

    public static CameraProfile get(String id) {
        CameraProfile profile = PROFILES.get(id);
        return profile != null ? profile : getLegacyDefault();
    }

    public static CameraProfile getLegacyDefault() {
        return PROFILES.get(PROFILE_LEGACY_SEAL_ATTO);
    }

    public static CameraProfile infer(String vehicleModel) {
        if (vehicleModel != null) {
            String normalized = vehicleModel.toLowerCase(Locale.US);
            if (normalized.contains("tang")) {
                return get(PROFILE_TANG_2022);
            }
        }
        return getLegacyDefault();
    }

    public static boolean isKnownProfile(String id) {
        return PROFILES.containsKey(id);
    }

    public static JSONArray toJsonArray() {
        JSONArray out = new JSONArray();
        JSONObject autoOption = new JSONObject();
        putSafely(autoOption, "id", PROFILE_AUTO);
        putSafely(autoOption, "label", "Auto detect");
        out.put(autoOption);
        for (Map.Entry<String, CameraProfile> entry : PROFILES.entrySet()) {
            out.put(entry.getValue().toJson());
        }
        return out;
    }

    private static void putSafely(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to write JSON field '" + key + "'", e);
        }
    }
}