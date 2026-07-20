package com.overdrive.app.automation;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reusable, named action sequences ("action groups") that many automations (and key
 * mappings) can invoke by id via {@link com.overdrive.app.automation.action.ActionGroupAction}.
 * Editing a group updates every caller, because groups are resolved at RUN time
 * (call-by-reference), not expanded into each caller.
 *
 * <p>Stored in a SEPARATE file ({@code action_groups.json}) from automations, with the
 * exact same durability discipline (atomic {@code .tmp}+rename, {@code .bak} recovery,
 * {@code SAVE_LOCK}) — kept separate so an action-group parse failure can never corrupt
 * the automations config, and vice versa.
 *
 * <p>Each group is {@code {name, actions:[...]}}, and its actions are validated through
 * the SAME {@link Automation#parseActions}-equivalent gate (an unknown/invalid action
 * rejects the group), so a group can only ever hold real, runnable actions. Cycles
 * (group A → group B → group A) are stopped at run time by the invoking action's guard
 * plus the {@link Automations#runActionList} depth cap.
 */
public final class ActionGroups {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");

    private static final File HOME = new File("/data/local/tmp/.automations");
    private static final File CONFIG = new File(HOME, "action_groups.json");
    private static final File BACKUP = new File(HOME, "action_groups.json.bak");
    private static final File TMP = new File(HOME, "action_groups.json.tmp");
    private static final Object SAVE_LOCK = new Object();

    // id -> group. LinkedHashMap preserves display order.
    private static final Map<String, Group> groups = new LinkedHashMap<>();

    private ActionGroups() {}

    /** One named, reusable action sequence. */
    public static final class Group {
        public final String name;
        public final List<AutomationAction> actions;
        Group(String name, List<AutomationAction> actions) {
            this.name = name;
            this.actions = actions;
        }
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                JSONArray arr = new JSONArray();
                for (AutomationAction a : actions) arr.put(a.toJson());
                o.put("actions", arr);
            } catch (Exception ignored) {}
            return o;
        }
    }

    /** The actions of a group by id, or an empty list if the id is unknown. Never null. */
    public static List<AutomationAction> getActions(String id) {
        if (id == null) return List.of();
        Group g = groups.get(id);
        return g == null ? List.of() : g.actions;
    }

    /** Whether a group with this id exists. */
    public static boolean exists(String id) {
        return id != null && groups.containsKey(id);
    }

    /** All groups as {@code {id: {name, actions}}} for the API / picker. */
    public static JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            for (Map.Entry<String, Group> e : groups.entrySet()) {
                json.put(e.getKey(), e.getValue().toJson());
            }
        } catch (Exception ignored) {}
        return json;
    }

    /** Lightweight {id,name} list for a picker (no action bodies). */
    public static JSONArray listJson() {
        JSONArray arr = new JSONArray();
        try {
            for (Map.Entry<String, Group> e : groups.entrySet()) {
                arr.put(new JSONObject().put("id", e.getKey()).put("name", e.getValue().name));
            }
        } catch (Exception ignored) {}
        return arr;
    }

    /**
     * Create or update a group. Body: {@code {name, actions:[...]}}. Validates the
     * actions through {@link Automation#parseActionsPublic}; a bad action rejects the
     * whole write (returns null). Returns the id (minted for a new group).
     */
    public static String save(String id, JSONObject body) {
        if (body == null) return null;
        String name = body.optString("name", "").trim();
        if (name.isEmpty()) return null;
        List<AutomationAction> actions;
        try {
            JSONArray actionsJson = body.optJSONArray("actions");
            if (actionsJson == null) return null;
            actions = Automation.parseActionsPublic(actionsJson);
            if (actions == null || actions.isEmpty()) return null;
        } catch (Exception e) {
            return null;
        }
        String gid = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        synchronized (SAVE_LOCK) {
            groups.put(gid, new Group(name, actions));
        }
        saveToFile();
        logger.info("Saved action group: " + gid + " (" + name + ", " + actions.size() + " actions)");
        return gid;
    }

    /** Delete a group by id. Returns true if one was removed. */
    public static boolean delete(String id) {
        boolean removed;
        synchronized (SAVE_LOCK) {
            removed = groups.remove(id) != null;
        }
        if (removed) { saveToFile(); logger.info("Deleted action group: " + id); }
        return removed;
    }

    // ── Persistence (mirrors Automations: atomic tmp+rename, .bak recovery) ──

    public static void saveToFile() {
        synchronized (SAVE_LOCK) {
            if (!HOME.exists()) HOME.mkdirs();
            byte[] bytes = toJson().toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(TMP)) {
                fos.write(bytes);
                fos.getFD().sync();
            } catch (IOException e) {
                logger.error("Failed to write action-groups scratch file");
                return;
            }
            if (CONFIG.exists()) copyFile(CONFIG, BACKUP);
            if (!TMP.renameTo(CONFIG)) {
                logger.error("Failed to promote action-groups scratch file");
                return;
            }
        }
    }

    public static void loadFromFile() {
        synchronized (SAVE_LOCK) {
            if (tryLoadFrom(CONFIG)) return;
            if (BACKUP.exists() && tryLoadFrom(BACKUP)) {
                logger.info("Recovered action groups from backup");
            }
        }
    }

    private static boolean tryLoadFrom(File file) {
        if (!file.exists()) return false;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Map<String, Group> loaded = new LinkedHashMap<>();
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONObject g = json.optJSONObject(id);
                if (g == null) continue;
                String name = g.optString("name", "").trim();
                JSONArray actionsJson = g.optJSONArray("actions");
                if (name.isEmpty() || actionsJson == null) continue;
                List<AutomationAction> actions = Automation.parseActionsPublic(actionsJson);
                if (actions == null) continue; // skip a corrupt group, keep the rest
                loaded.put(id, new Group(name, actions));
            }
            groups.clear();
            groups.putAll(loaded);
            return true;
        } catch (Exception e) {
            logger.error("Failed to load action groups from " + file.getName());
            return false;
        }
    }

    private static void copyFile(File from, File to) {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.getFD().sync();
        } catch (IOException e) {
            logger.error("Failed to back up action groups");
        }
    }
}
