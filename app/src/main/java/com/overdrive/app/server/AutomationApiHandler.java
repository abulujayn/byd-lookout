package com.overdrive.app.server;

import com.overdrive.app.automation.Automations;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * HTTP routes for automations.
 *
 * Endpoints:
 * - GET    /api/automations/list            → List all automations
 * - GET    /api/automations/schema          → Get the automation schema
 * - POST   /api/automations/automation      → Create a new automation
 * - PUT    /api/automations/automation/{id} → Update an existing automation by id
 * - DELETE /api/automations/automation/{id} → Delete an existing automation by id
 * - POST   /api/automations/test/{id}       → Run the actions for an automation by id
 * - POST   /api/automations/disable/{id}    → Disable an existing automation by id
 */
public final class AutomationApiHandler {

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/automations/list") && method.equals("GET")) {
            return getAutomations(out);
        }
        if (path.equals("/api/automations/schema") && method.equals("GET")) {
            return getSchema(out);
        }
        if (path.equals("/api/automations/automation") && method.equals("POST")) {
            return addOrUpdateAutomation(null, body, out);
        }
        if (path.startsWith("/api/automations/automation/") && method.equals("PUT")) {
            String id = path.substring("/api/automations/automation/".length());
            if (isBlankId(id)) return rejectBlankId(out);
            // PUT targets an existing automation by id. Reject an unknown id with 404 rather than
            // silently creating a new automation under a caller-chosen id (creation is POST, which mints
            // a UUID). Keeps the route consistent with DELETE/test/disable, which already 404 on unknown.
            if (!Automations.exists(id)) {
                HttpResponse.sendError(out, 404, "Automation not found.");
                return true;
            }
            return addOrUpdateAutomation(id, body, out);
        }
        if (path.startsWith("/api/automations/automation/") && method.equals("DELETE")) {
            String id = path.substring("/api/automations/automation/".length());
            if (isBlankId(id)) return rejectBlankId(out);
            return deleteAutomation(id, out);
        }
        if (path.startsWith("/api/automations/test/") && method.equals("POST")) {
            String id = path.substring("/api/automations/test/".length());
            if (isBlankId(id)) return rejectBlankId(out);
            return testAutomation(id, out);
        }
        if (path.startsWith("/api/automations/disable/") && method.equals("POST")) {
            String id = path.substring("/api/automations/disable/".length());
            if (isBlankId(id)) return rejectBlankId(out);
            return disableAutomation(id, body, out);
        }
        // Automation-wide settings (not per-automation): currently just the
        // shell-action gate. Kept on the automations surface — it governs
        // automations firing autonomously, so it lives with them, not on the
        // key-mapping page (the flag is a distinct opt-in from keymap advanced).
        if (path.equals("/api/automations/settings") && method.equals("GET")) {
            return getSettings(out);
        }
        if (path.equals("/api/automations/settings") && method.equals("POST")) {
            return saveSettings(body, out);
        }
        return false;
    }

    /** GET automation-wide settings: { allowShell }. Fresh read for cross-UID. */
    private static boolean getSettings(OutputStream out) throws Exception {
        com.overdrive.app.config.UnifiedConfigManager.forceReload();
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("allowShell", com.overdrive.app.config.UnifiedConfigManager.isAutomationShellAllowed());
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /** POST { allowShell:bool } — persist the autonomous-shell gate. */
    private static boolean saveSettings(String body, OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        if (body == null || body.isBlank()) { HttpResponse.sendJsonError(out, "Empty request body"); return true; }
        boolean allow;
        try {
            allow = new JSONObject(body).optBoolean("allowShell", false);
        } catch (Exception e) { HttpResponse.sendJsonError(out, "Invalid JSON"); return true; }
        boolean ok = com.overdrive.app.config.UnifiedConfigManager.setAutomationShellAllowed(allow);
        resp.put("success", ok);
        resp.put("allowShell", allow);
        if (!ok) resp.put("error", "Failed to persist automation settings");
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /**
     * Whether a path-derived automation id is missing or blank.
     * A trailing-slash route (e.g. PUT /api/automations/automation/) yields an empty id; without this
     * guard PUT would silently create a NEW random-UUID automation and DELETE would report success
     * while removing nothing. Only path-derived ids are validated here — the create route (POST with a
     * null id) intentionally leaves the id unset so a UUID is generated.
     *
     * @param id The id extracted from the request path
     * @return true if the id is null or blank and the request must be rejected
     */
    private static boolean isBlankId(String id) {
        return id == null || id.isBlank();
    }

    /**
     * Send a 400 for a request that carried a missing/blank automation id.
     *
     * @param out The output stream to write the response to
     * @return true so the router treats the request as handled
     */
    private static boolean rejectBlankId(OutputStream out) throws Exception {
        HttpResponse.sendError(out, 400, "Missing automation id.");
        return true;
    }

    private static boolean getAutomations(OutputStream out) throws Exception {
        HttpResponse.sendJson(out, Automations.toJson().toString());
        return true;
    }

    private static boolean getSchema(OutputStream out) throws Exception {
        HttpResponse.sendJson(out, Automations.schemaJson().toString());
        return true;
    }

    /**
     * Create (id == null) or update (non-blank id) an automation from the request body.
     * The body is parsed inside a try/catch so malformed JSON returns a 400 rather than propagating a
     * JSONException out of handle() — the server's outer catch only logs and closes the socket, which
     * would leave the client hanging with no HTTP response.
     *
     * @param id   The id of the automation to update, or null to create a new one
     * @param body The raw request body expected to contain the automation JSON
     * @param out  The output stream to write the response to
     * @return true so the router treats the request as handled
     */
    private static boolean addOrUpdateAutomation(String id, String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendError(out, 400, "Missing body.");
            return true;
        }
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (JSONException e) {
            HttpResponse.sendError(out, 400, "Malformed JSON body.");
            return true;
        }
        if (Automations.updateAutomation(id, json)) {
            HttpResponse.sendJsonSuccess(out);
        } else {
            HttpResponse.sendError(out, 400, "Invalid automation provided. Check the automation follows the schema");
        }
        return true;
    }

    /**
     * Delete an automation by id, returning 404 when no automation with that id exists so the client
     * is not told a delete succeeded when nothing was removed.
     *
     * @param id  The id of the automation to delete
     * @param out The output stream to write the response to
     * @return true so the router treats the request as handled
     */
    private static boolean deleteAutomation(String id, OutputStream out) throws Exception {
        if (Automations.deleteAutomation(id)) {
            HttpResponse.sendJsonSuccess(out);
        } else {
            HttpResponse.sendError(out, 404, "Automation not found.");
        }
        return true;
    }

    /**
     * Run an automation's actions without checking its conditions so a user can test them.
     * Returns 404 for an unknown id; a known automation (even a disabled one) reports success, since
     * triggerActions reports only existence and intentionally allows testing without firing when the
     * automation is disabled.
     *
     * @param id  The id of the automation to test
     * @param out The output stream to write the response to
     * @return true so the router treats the request as handled
     */
    private static boolean testAutomation(String id, OutputStream out) throws Exception {
        if (Automations.triggerActions(id, false)) {
            HttpResponse.sendJsonSuccess(out);
        } else {
            HttpResponse.sendError(out, 404, "Automation not found.");
        }
        return true;
    }

    /**
     * Enable or disable an automation by id from the request body's "disabled" flag.
     * The body is parsed inside a try/catch so malformed JSON returns a 400 instead of propagating a
     * JSONException out of handle() (see addOrUpdateAutomation).
     *
     * @param id   The id of the automation to enable/disable
     * @param body The raw request body expected to contain the "disabled" boolean
     * @param out  The output stream to write the response to
     * @return true so the router treats the request as handled
     */
    private static boolean disableAutomation(String id, String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendError(out, 400, "Missing body.");
            return true;
        }
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (JSONException e) {
            HttpResponse.sendError(out, 400, "Malformed JSON body.");
            return true;
        }
        if (Automations.disableAutomation(id, json.optBoolean("disabled", false))) {
            HttpResponse.sendJsonSuccess(out);
        } else {
            HttpResponse.sendError(out, 404, "Automation not found.");
        }
        return true;
    }
}
