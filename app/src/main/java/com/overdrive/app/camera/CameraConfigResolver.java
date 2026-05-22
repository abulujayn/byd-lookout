package com.overdrive.app.camera;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves persisted + inferred camera settings into a concrete runtime config.
 */
public final class CameraConfigResolver {
    private static final DaemonLogger logger = DaemonLogger.getInstance("CameraConfigResolver");

    private CameraConfigResolver() {
    }

    public static ResolvedCameraConfig resolve() {
        return resolve(readVehicleModel());
    }

    public static ResolvedCameraConfig resolve(String vehicleModel) {
        JSONObject camera = getCameraSection();
        String selectedProfileId = camera.optString("cameraProfile", CameraProfiles.PROFILE_AUTO);
        boolean autoProfile = selectedProfileId.isEmpty() || CameraProfiles.PROFILE_AUTO.equalsIgnoreCase(selectedProfileId);
        CameraProfile profile = autoProfile
                ? CameraProfiles.infer(vehicleModel)
                : CameraProfiles.get(selectedProfileId);

        int panoCameraId = optNonNegative(camera, "probedCameraId", profile.getPanoCameraId());
        int panoSurfaceMode = optNonNegative(camera, "probedSurfaceMode", profile.getPanoSurfaceMode());
        int panoWidth = optNonNegative(camera, "probedWidth", profile.getPanoWidth());
        int panoHeight = optNonNegative(camera, "probedHeight", profile.getPanoHeight());
        boolean manual = camera.optBoolean("manualOverride", false);
        boolean validated = camera.optBoolean("probedAndValidated", false);
        boolean fallback = camera.optBoolean("fallbackFromProbe", false);

        EnumMap<CameraRole, CameraSourceRef> roleMappings = profile.getDefaultRoleMappings();
        JSONObject mappingsJson = camera.optJSONObject("roleMappings");
        if (mappingsJson != null) {
            for (CameraRole role : CameraRole.values()) {
                JSONObject item = mappingsJson.optJSONObject(role.getKey());
                CameraSourceRef sourceRef = CameraSourceRef.fromJson(item);
                if (sourceRef != null) {
                    roleMappings.put(role, sourceRef);
                }
            }
        }

        return new ResolvedCameraConfig(
                profile,
                autoProfile ? CameraProfiles.PROFILE_AUTO : profile.getId(),
                autoProfile,
                panoCameraId,
                panoWidth,
                panoHeight,
                panoSurfaceMode,
                manual,
                validated,
                fallback,
                roleMappings);
    }

    public static JSONObject getCameraSection() {
        return UnifiedConfigManager.loadConfig().optJSONObject("camera") != null
                ? UnifiedConfigManager.loadConfig().optJSONObject("camera")
                : new JSONObject();
    }

    public static JSONArray buildPreviewCandidates(ResolvedCameraConfig resolved) {
        JSONArray out = new JSONArray();
        for (int cameraId = 0; cameraId <= 5; cameraId++) {
            JSONObject item = CameraSourceRef.direct(cameraId).toJson();
            putSafely(item, "previewWidth", resolved.getProfile().getDirectPreviewWidth());
            putSafely(item, "previewHeight", resolved.getProfile().getDirectPreviewHeight());
            out.put(item);
        }
        for (PanoramicSlice slice : PanoramicSlice.values()) {
            JSONObject item = CameraSourceRef.panoramicSlice(slice).toJson();
            putSafely(item, "previewWidth", resolved.getPanoWidth() / 4);
            putSafely(item, "previewHeight", resolved.getPanoHeight());
            out.put(item);
        }
        return out;
    }

    public static JSONArray roleOptionsJson() {
        JSONArray out = new JSONArray();
        for (CameraRole role : CameraRole.values()) {
            out.put(role.toJson());
        }
        return out;
    }

    public static boolean saveCameraProfile(String profileId) {
        JSONObject update = new JSONObject();
        if (profileId == null || profileId.isEmpty() || CameraProfiles.PROFILE_AUTO.equalsIgnoreCase(profileId)) {
            putSafely(update, "cameraProfile", CameraProfiles.PROFILE_AUTO);
        } else if (CameraProfiles.isKnownProfile(profileId)) {
            putSafely(update, "cameraProfile", profileId);
            CameraProfile profile = CameraProfiles.get(profileId);
            if (!getCameraSection().has("probedWidth")) putSafely(update, "probedWidth", profile.getPanoWidth());
            if (!getCameraSection().has("probedHeight")) putSafely(update, "probedHeight", profile.getPanoHeight());
        } else {
            logger.warn("Ignoring unknown camera profile: " + profileId);
            return false;
        }
        return UnifiedConfigManager.updateSection("camera", update);
    }

    public static boolean saveRoleMapping(CameraRole role, CameraSourceRef sourceRef) {
        if (role == null || sourceRef == null) return false;
        JSONObject camera = getCameraSection();
        JSONObject mappings = camera.optJSONObject("roleMappings");
        if (mappings == null) mappings = new JSONObject();
        putSafely(mappings, role.getKey(), sourceRef.toJson());

        JSONObject update = new JSONObject();
        putSafely(update, "roleMappings", mappings);
        return UnifiedConfigManager.updateSection("camera", update);
    }

    public static boolean clearRoleMapping(CameraRole role) {
        if (role == null) return false;
        JSONObject camera = getCameraSection();
        JSONObject mappings = camera.optJSONObject("roleMappings");
        if (mappings == null || !mappings.has(role.getKey())) return true;
        mappings.remove(role.getKey());

        JSONObject update = new JSONObject();
        putSafely(update, "roleMappings", mappings);
        return UnifiedConfigManager.updateSection("camera", update);
    }

    public static boolean persistPanoramicProbe(int cameraId, int surfaceMode, int width, int height,
                                                boolean validated, boolean fallback) {
        JSONObject update = new JSONObject();
        putSafely(update, "probedCameraId", cameraId);
        putSafely(update, "probedSurfaceMode", surfaceMode);
        putSafely(update, "probedWidth", width);
        putSafely(update, "probedHeight", height);
        putSafely(update, "probedAndValidated", validated);
        putSafely(update, "fallbackFromProbe", fallback);
        return UnifiedConfigManager.updateSection("camera", update);
    }

    public static JSONObject resolvedSummaryJson(ResolvedCameraConfig resolved) {
        JSONObject out = new JSONObject();
        putSafely(out, "cameraProfile", resolved.getSelectedProfileId());
        putSafely(out, "resolvedCameraProfile", resolved.getProfile().getId());
        putSafely(out, "resolvedCameraProfileLabel", resolved.getProfile().getDisplayName());
        putSafely(out, "panoCameraId", resolved.getPanoCameraId());
        putSafely(out, "panoSurfaceMode", resolved.getPanoSurfaceMode());
        putSafely(out, "panoWidth", resolved.getPanoWidth());
        putSafely(out, "panoHeight", resolved.getPanoHeight());
        putSafely(out, "cameraManualOverride", resolved.isManualPanoOverride());
        putSafely(out, "cameraValidated", resolved.isValidated());
        putSafely(out, "cameraFallbackFromProbe", resolved.isFallbackFromProbe());
        putSafely(out, "cameraProfiles", CameraProfiles.toJsonArray());
        putSafely(out, "cameraRoleOptions", roleOptionsJson());
        putSafely(out, "cameraRoleMappings", resolved.roleMappingsToJson());
        putSafely(out, "cameraPanoramicSlices", resolved.panoramicSlicesToJson());
        putSafely(out, "cameraPreviewCandidates", buildPreviewCandidates(resolved));
        return out;
    }

    private static int optNonNegative(JSONObject obj, String key, int defaultValue) {
        int value = obj.optInt(key, defaultValue);
        return value >= 0 ? value : defaultValue;
    }

    private static void putSafely(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to write JSON field '" + key + "'", e);
        }
    }

    private static String readVehicleModel() {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "ro.product.model", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }
}