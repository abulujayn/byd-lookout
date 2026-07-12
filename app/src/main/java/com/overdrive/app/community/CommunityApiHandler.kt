package com.overdrive.app.community

import com.overdrive.app.automation.Automation
import com.overdrive.app.automation.Automations
import com.overdrive.app.byd.BydDataCollector
import com.overdrive.app.community.config.CommunityConfig
import com.overdrive.app.community.sync.CommunitySyncProvider
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.mqtt.VehicleControlCatalog
import com.overdrive.app.roadsense.sync.DeviceId
import com.overdrive.app.server.HttpResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/**
 * HTTP routes for the Community Automations feature (browse / publish / rate /
 * report / import shared automations). Runs in the DAEMON process (UID shell), so
 * it talks to the automation store ([Automations]) in-process and reaches the
 * open-source `community-edge/` Cloudflare backend through [CommunitySyncProvider]
 * (proxy-aware OkHttp). The WebView only ever calls these local /api/community
 * routes on loopback — it never talks to the cloud directly.
 *
 * Endpoints:
 *  - GET    /api/community/list[?sort&order&page&pageSize&q&category] → paginated catalog (proxied)
 *  - GET    /api/community/automation/{id}   → one shared automation + per-vehicle capability enrichment
 *  - POST   /api/community/publish           → publish a local automation ({rules,authorName,name,description,category})
 *  - POST   /api/community/import/{id}       → import a shared automation into the LOCAL store (disabled)
 *  - POST   /api/community/rate/{id}         → rate 1..5 ({stars})
 *  - POST   /api/community/report/{id}       → flag abuse ({reason?})
 *  - DELETE /api/community/automation/{id}   → delete a shared automation I published (device-match)
 *  - GET    /api/community/settings          → { workerUrl, authorName }
 *  - POST   /api/community/settings          → persist { workerUrl?, authorName? }
 *
 * ## Load-bearing safety (the app-side half of the guard stack; see community-edge/README.md)
 *  1. NO-SHELL on both publish AND import — an automation carrying a `shell` action
 *     is refused locally regardless of the shell gate, in addition to the Worker's
 *     server-side rejection. Triple defense (UI + here + Worker).
 *  2. IMPORT-AS-DISABLED — an imported automation is forced `disabled=true` so the
 *     user reviews its (human-readable) rules before it can fire. No silent activation.
 *  3. CAPABILITY CHECK — each action is probed against the current vehicle via
 *     [VehicleControlCatalog.isAvailable]; unsupported ones are surfaced (a badge)
 *     but do NOT block import (fail-open: the probe is advisory, import-disabled is
 *     the real net). Only catalog-backed vehicle controls are probeable; ApiAction /
 *     notification actions have no per-vehicle probe and are treated as available.
 *  4. Publish is SERVER-AUTHORITATIVE — the blob is re-parsed through
 *     [Automation.fromJson] and re-serialized ([Automation.toJson]) so a tampered
 *     client payload is normalized to the canonical, schema-valid form before upload.
 */
object CommunityApiHandler {

    private val logger = DaemonLogger.getInstance("CommunityApiHandler")

    /** Wire format version of the automation blob (mirrors community-edge schema_version). */
    private const val SCHEMA_VERSION = 1

    private const val PREFIX = "/api/community/"

    /** Lazily-built provider; the URL supplier force-reloads UCM so an app-side URL
     *  change is picked up without a daemon restart (cross-UID; user-initiated path). */
    private val provider: CommunitySyncProvider by lazy {
        CommunitySyncProvider({ CommunityConfig.snapshot(forceReload = true).workerUrl })
    }

    @JvmStatic
    fun handle(method: String, path: String, body: String?, out: OutputStream): Boolean {
        val q = path.indexOf('?')
        val pathOnly = if (q >= 0) path.substring(0, q) else path
        val query = if (q >= 0) path.substring(q + 1) else ""

        try {
            when {
                pathOnly == "${PREFIX}list" && method == "GET" -> return listCatalog(query, out)
                pathOnly == "${PREFIX}publish" && method == "POST" -> return publish(body, out)
                pathOnly == "${PREFIX}settings" && method == "GET" -> return getSettings(out)
                pathOnly == "${PREFIX}settings" && method == "POST" -> return saveSettings(body, out)

                pathOnly.startsWith("${PREFIX}automation/") && method == "GET" ->
                    return getOne(idFrom(pathOnly, "${PREFIX}automation/"), out)
                pathOnly.startsWith("${PREFIX}automation/") && method == "DELETE" ->
                    return deleteOwn(idFrom(pathOnly, "${PREFIX}automation/"), out)
                pathOnly.startsWith("${PREFIX}import/") && method == "POST" ->
                    return importAutomation(idFrom(pathOnly, "${PREFIX}import/"), out)
                pathOnly.startsWith("${PREFIX}rate/") && method == "POST" ->
                    return rate(idFrom(pathOnly, "${PREFIX}rate/"), body, out)
                pathOnly.startsWith("${PREFIX}report/") && method == "POST" ->
                    return report(idFrom(pathOnly, "${PREFIX}report/"), body, out)
            }
        } catch (t: Throwable) {
            // Never let an exception escape to HttpServer's socket-closing catch with no response.
            logger.error("community handler error: " + t.message)
            safeError(out, 500, "internal error")
            return true
        }
        return false
    }

    // ── Browse ───────────────────────────────────────────────────────────────

    /** GET list — forward the (already query-string-shaped) whitelisted params to the Worker. */
    private fun listCatalog(query: String, out: OutputStream): Boolean {
        forward(provider.list(query), out)
        return true
    }

    /**
     * GET one — fetch the full record (with rules) and ENRICH it with a per-vehicle
     * capability check so the UI can show a "works on your car" badge and list any
     * unsupported actions. Enrichment is advisory; the record is returned regardless.
     */
    private fun getOne(id: String?, out: OutputStream): Boolean {
        if (id.isNullOrBlank()) { safeError(out, 400, "missing id"); return true }
        val res = provider.get(id)
        if (!res.ok || res.body == null) { forward(res, out); return true }
        val automation = res.body.optJSONObject("automation")
        if (automation != null) {
            val unsupported = unsupportedActions(automation.optJSONObject("rules"))
            automation.put("unsupportedActions", unsupported)
            automation.put("compatible", unsupported.length() == 0)
        }
        HttpResponse.sendJson(out, res.body.toString())
        return true
    }

    // ── Publish ────────────────────────────────────────────────────────────────

    /**
     * POST publish — body { rules, authorName, name, description?, category? }.
     * Server-authoritative: re-parse the rules through [Automation.fromJson] (the
     * canonical validator) and re-serialize, blocking any shell action, before upload.
     */
    private fun publish(body: String?, out: OutputStream): Boolean {
        val json = parseBody(body) ?: run { safeError(out, 400, "invalid JSON body"); return true }
        val rules = json.optJSONObject("rules")
        if (rules == null) { safeError(out, 400, "rules required"); return true }

        // Authoritative validate + canonicalize (drops any junk the client added).
        val parsed = Automation.fromJson(rules)
        if (parsed == null) { safeError(out, 400, "automation does not match the schema"); return true }
        val canonical = parsed.toJson()
        if (containsShellAction(canonical)) {
            safeError(out, 403, "shell automations cannot be shared to the community catalog")
            return true
        }

        val name = json.optString("name", "").trim()
        if (name.isEmpty()) { safeError(out, 400, "name required"); return true }
        val description = json.optString("description", "").trim()
        var category = json.optString("category", "other").trim().lowercase()
        if (category.isEmpty()) category = "other"

        // Author name: prefer the request, else the remembered value; persist it for next time.
        var authorName = json.optString("authorName", "").trim()
        if (authorName.isEmpty()) authorName = CommunityConfig.snapshot(forceReload = true).authorName ?: ""
        if (authorName.isEmpty()) { safeError(out, 400, "authorName required"); return true }
        CommunityConfig.setAuthorName(authorName)

        val deviceId = DeviceId.current(System.currentTimeMillis())
        val res = provider.publish(deviceId, authorName, name, description, category, canonical, SCHEMA_VERSION)
        forward(res, out)
        return true
    }

    // ── Import ───────────────────────────────────────────────────────────────

    /**
     * POST import — fetch the shared automation's authoritative rules from the Worker,
     * refuse a shell action, force it disabled, and save it into the LOCAL automation
     * store (minting a fresh local UUID). Fires the best-effort install counter.
     */
    private fun importAutomation(id: String?, out: OutputStream): Boolean {
        if (id.isNullOrBlank()) { safeError(out, 400, "missing id"); return true }
        val res = provider.get(id)
        if (!res.ok || res.body == null) { forward(res, out); return true }
        val rules = res.body.optJSONObject("automation")?.optJSONObject("rules")
        if (rules == null) { safeError(out, 502, "shared automation had no rules"); return true }

        // Never import a shell automation, regardless of the local shell gate.
        if (containsShellAction(rules)) {
            safeError(out, 403, "this automation contains a shell action and cannot be imported")
            return true
        }

        // Import-as-disabled: the user reviews the rules before enabling.
        rules.put("disabled", true)
        // Reuse the exact create path the automations API uses (validates + saves + mints UUID).
        if (!Automations.updateAutomation(null, rules)) {
            safeError(out, 400, "shared automation does not match this app's schema")
            return true
        }

        // Best-effort popularity counter — ignore the result.
        try { provider.install(id) } catch (_: Throwable) { }

        val resp = JSONObject().put("success", true).put("disabled", true)
        HttpResponse.sendJson(out, resp.toString())
        logger.info("Imported community automation $id into local store (disabled)")
        return true
    }

    // ── Rate / report / delete ─────────────────────────────────────────────────

    private fun rate(id: String?, body: String?, out: OutputStream): Boolean {
        if (id.isNullOrBlank()) { safeError(out, 400, "missing id"); return true }
        val json = parseBody(body) ?: run { safeError(out, 400, "invalid JSON body"); return true }
        val stars = json.optInt("stars", 0)
        if (stars < 1 || stars > 5) { safeError(out, 400, "stars must be 1..5"); return true }
        forward(provider.rate(id, DeviceId.current(System.currentTimeMillis()), stars), out)
        return true
    }

    private fun report(id: String?, body: String?, out: OutputStream): Boolean {
        if (id.isNullOrBlank()) { safeError(out, 400, "missing id"); return true }
        val reason = parseBody(body)?.optString("reason", "")?.trim()
        forward(provider.report(id, DeviceId.current(System.currentTimeMillis()), reason), out)
        return true
    }

    private fun deleteOwn(id: String?, out: OutputStream): Boolean {
        if (id.isNullOrBlank()) { safeError(out, 400, "missing id"); return true }
        forward(provider.delete(id, DeviceId.current(System.currentTimeMillis())), out)
        return true
    }

    // ── Settings ────────────────────────────────────────────────────────────────

    private fun getSettings(out: OutputStream): Boolean {
        val snap = CommunityConfig.snapshot(forceReload = true)
        val resp = JSONObject()
            .put("success", true)
            .put("workerUrl", snap.workerUrl ?: "")
            .put("authorName", snap.authorName ?: "")
        HttpResponse.sendJson(out, resp.toString())
        return true
    }

    private fun saveSettings(body: String?, out: OutputStream): Boolean {
        val json = parseBody(body) ?: run { safeError(out, 400, "invalid JSON body"); return true }
        var ok = true
        if (json.has("authorName")) ok = CommunityConfig.setAuthorName(json.optString("authorName", "").trim()) && ok
        if (json.has("workerUrl")) ok = CommunityConfig.setWorkerUrl(json.optString("workerUrl", "").trim()) && ok
        val resp = JSONObject().put("success", ok)
        if (!ok) resp.put("error", "failed to persist community settings")
        HttpResponse.sendJson(out, resp.toString())
        return true
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Extract + URL-decode the id segment after [prefix]. */
    private fun idFrom(pathOnly: String, prefix: String): String? {
        val raw = pathOnly.substring(prefix.length)
        if (raw.isEmpty() || raw.contains('/')) return if (raw.isEmpty()) null else raw.substringBefore('/')
        return try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (_: Throwable) { raw }
    }

    private fun parseBody(body: String?): JSONObject? {
        if (body.isNullOrBlank()) return null
        return try { JSONObject(body) } catch (_: Throwable) { null }
    }

    /**
     * True if any action in the rules blob has type "shell" (the stored action `type`
     * == the action's label id; the shell action's id is exactly "shell"). Matched
     * case-insensitively / by substring, defensively, mirroring the Worker's check.
     */
    private fun containsShellAction(rules: JSONObject): Boolean {
        val actions = rules.optJSONArray("actions") ?: return false
        for (i in 0 until actions.length()) {
            val type = actions.optJSONObject(i)?.optString("type")?.trim()?.lowercase() ?: continue
            if (type.contains("shell")) return true
        }
        return false
    }

    /**
     * Label ids of actions the CURRENT vehicle does not support, probed via the
     * vehicle-control catalog. Only catalog-backed controls are probeable; other
     * action kinds (ApiAction / notification) have no per-vehicle probe, so they are
     * omitted (treated as available). Fail-open — this is advisory for a UI badge.
     */
    private fun unsupportedActions(rules: JSONObject?): JSONArray {
        val result = JSONArray()
        val actions = rules?.optJSONArray("actions") ?: return result
        val snap = try { BydDataCollector.getInstance().data } catch (_: Throwable) { null }
        for (i in 0 until actions.length()) {
            val type = actions.optJSONObject(i)?.optString("type")?.trim() ?: continue
            val entity = VehicleControlCatalog.get(type) ?: continue // not catalog-probeable
            if (!entity.isAvailable(snap)) result.put(type)
        }
        return result
    }

    /** Forward a provider [CommunitySyncProvider.Result] to the client verbatim. */
    private fun forward(res: CommunitySyncProvider.Result, out: OutputStream) {
        if (res.ok && res.body != null) {
            HttpResponse.sendJson(out, res.body.toString())
            return
        }
        // Map a transport failure (httpCode 0) to 502; otherwise pass the Worker's status.
        val code = if (res.httpCode in 400..599) res.httpCode else 502
        val err = JSONObject().put("success", false).put("error", res.error ?: "community backend error")
        HttpResponse.sendJson(out, code, err.toString())
    }

    /** Send an error without letting sendError's checked IOException escape the router. */
    private fun safeError(out: OutputStream, code: Int, message: String) {
        try {
            HttpResponse.sendJson(out, code, JSONObject().put("success", false).put("error", message).toString())
        } catch (_: Throwable) { }
    }
}
