package com.overdrive.app.community.config

import com.overdrive.app.config.UnifiedConfigManager
import org.json.JSONObject

/**
 * Typed accessor for the `community` section of [UnifiedConfigManager] — config for
 * the Community Automations sharing feature (browse/publish shared automations via
 * the open-source `community-edge/` Cloudflare Worker + D1 backend).
 *
 * Mirrors [com.overdrive.app.roadsense.config.RoadSenseConfig]: a feature-owned
 * wrapper over UCM's PUBLIC generic API (`loadConfig()`/`forceReload()` to read,
 * `updateSection()` to merge-write) so it never touches UCM source, and the section
 * is file-backed so both the app UID (web settings writes) and the daemon UID
 * (handler reads) see the same values. Daemon reads that need the very latest
 * app-written value pass `forceReload=true` (cross-UID staleness — see
 * feedback_unified_config_force_reload).
 *
 * ## Identity is SHARED with RoadSense — not minted twice
 * The anonymous rotating device UUID lives in the `roadSense` section
 * ([com.overdrive.app.roadsense.sync.DeviceId], key `deviceId`). Community reuses it
 * via `DeviceId.current(nowMs)` so the two crowdsource features share ONE identity
 * instead of creating a second. This section therefore holds only the community
 * worker URL + the remembered author display name.
 *
 * All fields have safe defaults so a missing/partial section never crashes a read.
 */
object CommunityConfig {

    private const val SECTION = "community"

    /**
     * Project-run SHARED community backend (mirrors RoadSenseConfig.DEFAULT_WORKER_URL,
     * D-026): the out-of-box default so all users' published automations pool into ONE
     * D1 instance and the browse catalog is shared fleet-wide. A fork can override this
     * on the Automations settings page to run its own pool.
     *
     * NOTE: this URL follows the same account/subdomain convention as roadsense-edge
     * but the `community-edge/` Worker must be DEPLOYED before it responds (see
     * community-edge/README.md). Until then the provider fails gracefully (browse shows
     * an empty/"couldn't load" state; publish reports an error) — the feature is inert,
     * never crashy. The field stays editable so a self-host can point elsewhere; a user
     * who blanks it disables community sync entirely.
     */
    const val DEFAULT_WORKER_URL = "https://community-edge.yash321sri.workers.dev"

    // Keys (also the JSON field names the web settings page reads/writes).
    private const val K_WORKER_URL = "workerUrl"     // user-configurable community-edge URL
    private const val K_AUTHOR_NAME = "authorName"   // remembered display name for publishing

    /** Immutable snapshot of the section — read once per use. */
    data class Snapshot(
        val workerUrl: String?,
        val authorName: String?,
    )

    /**
     * Read the current section. [forceReload]=true forces a cross-UID disk re-read
     * (daemon reading app-written values) — the daemon handler uses this so a
     * just-changed URL / author name is picked up without a restart.
     */
    fun snapshot(forceReload: Boolean = false): Snapshot {
        val root = if (forceReload) UnifiedConfigManager.forceReload()
        else UnifiedConfigManager.loadConfig()
        val s = root.optJSONObject(SECTION) ?: JSONObject()
        return Snapshot(
            // Distinguish UNSET (key absent → project-run shared default so browse
            // works out of the box) from EXPLICITLY-BLANKED (key present but empty →
            // null, which disables sync). This is the only opt-out lever Community
            // has (no separate enabled/crowd toggles), so a user who clears the URL
            // on the settings page to opt out MUST actually stop all cloud calls —
            // baseUrl() returns null → provider fails closed. Diverges deliberately
            // from RoadSenseConfig.kt (which coalesces blank→default, but is benign
            // there because RoadSense has separate enable/crowd gates).
            workerUrl = if (s.has(K_WORKER_URL)) s.optString(K_WORKER_URL, "").ifEmpty { null }
                        else DEFAULT_WORKER_URL,
            authorName = s.optString(K_AUTHOR_NAME, "").ifEmpty { null },
        )
    }

    /** Persist the remembered author display name (so a user types it once). */
    fun setAuthorName(name: String): Boolean =
        UnifiedConfigManager.updateSection(SECTION, JSONObject().put(K_AUTHOR_NAME, name))

    /** Persist a user-overridden worker URL (self-host / point elsewhere). */
    fun setWorkerUrl(url: String): Boolean =
        UnifiedConfigManager.updateSection(SECTION, JSONObject().put(K_WORKER_URL, url))
}
