package com.overdrive.app.automation;

import com.overdrive.app.automation.action.Action;
import com.overdrive.app.automation.action.Actions;
import com.overdrive.app.automation.condition.Conditions;
import com.overdrive.app.automation.condition.EventCondition;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.condition.TimeEvent;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;
import com.overdrive.app.automation.value.Value;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Automations {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final File AUTOMATION_HOME = new File("/data/local/tmp/.automations");
    private static final File AUTOMATION_CONFIG = new File(AUTOMATION_HOME, "config.json");
    // Last-known-good backup + scratch file for the atomic write. loadFromFile falls back to .bak when
    // the live file is truncated/corrupt (e.g. daemon killed mid-write on ACC-off), so a torn write can
    // never silently wipe every configured automation.
    private static final File AUTOMATION_BACKUP = new File(AUTOMATION_HOME, "config.json.bak");
    private static final File AUTOMATION_TMP = new File(AUTOMATION_HOME, "config.json.tmp");
    // Serializes the read-snapshot-write sequence so two concurrent HTTP request threads can't interleave
    // their FileOutputStreams and produce a mangled file.
    private static final Object SAVE_LOCK = new Object();
    private static final Map<EventData, Value> state = new ConcurrentHashMap<>();
    // The schema object graph (~200 Label/EnumType/EventCondition/Action objects) is built lazily on
    // first use rather than at class-load. A daemon with zero configured automations touches Automations
    // on every telemetry snapshot (via BydEvent.isDisabled()) but never needs the schema, so this keeps
    // the "no automation configured => no extra compute" property honest — the graph only materializes
    // when an automation is loaded from disk or the schema API is hit.
    private static volatile Conditions conditions;
    private static volatile Actions actions;
    private static volatile Type delay;
    private static final Object SCHEMA_LOCK = new Object();
    private static final Map<String, Automation> automations = new ConcurrentHashMap<>();
    // O(1) enabled-automation count so isDisabled() (called ~30x per telemetry snapshot on the daemon
    // hot path) is a field read instead of a full stream scan of the map. Maintained under SAVE_LOCK
    // alongside every mutation. volatile for cross-thread visibility from the telemetry thread.
    private static volatile int enabledCount = 0;

    static {
        // Load config from the file at startup
        loadFromFile();

        // Start publishing the current time-of-day / day-of-week into the state so
        // time and day conditions can be evaluated.
        TimeEvent.scheduleTimeEvent();
    }

    /**
     * Lazily build and return the conditions schema.
     */
    private static Conditions conditions() {
        Conditions c = conditions;
        if (c == null) {
            synchronized (SCHEMA_LOCK) {
                c = conditions;
                if (c == null) c = conditions = new Conditions();
            }
        }
        return c;
    }

    /**
     * Lazily build and return the actions schema.
     */
    private static Actions actions() {
        Actions a = actions;
        if (a == null) {
            synchronized (SCHEMA_LOCK) {
                a = actions;
                if (a == null) a = actions = new Actions();
            }
        }
        return a;
    }

    /**
     * Lazily build and return the delay type.
     */
    private static Type delay() {
        Type d = delay;
        if (d == null) {
            synchronized (SCHEMA_LOCK) {
                d = delay;
                if (d == null) d = delay = new IntType(new Label("delay", Messages.get("automation.delay")), 0, 86400);
            }
        }
        return d;
    }

    /**
     * Recompute the cached enabled-automation count. MUST be called under {@link #SAVE_LOCK} after any
     * mutation of the automations map or an automation's disabled flag.
     */
    private static void refreshEnabledCount() {
        int n = 0;
        for (Automation a : automations.values()) if (!a.isDisabled()) n++;
        enabledCount = n;
    }

    private Automations() {}

    /**
     * Get a condition schema with a specific key
     *
     * @param key The key for a condition
     * @return The condition schema for that key
     */
    public static EventCondition getCondition(String key) {
        return conditions().getCondition(key);
    }

    /**
     * Get an action schema with a specific key
     *
     * @param key The key for an action
     * @return The action schema for that key
     */
    public static Action getAction(String key) {
        return actions().getAction(key);
    }

    /**
     * Check whether the delay is an allowed value
     *
     * @param seconds The number of seconds to delay the actions
     * @return true if it is valid, false otherwise
     */
    public static boolean isValidDelay(int seconds) {
        return delay().isValid(seconds);
    }

    /**
     * Whether automations are disabled.
     * Disabled when there are no automations or all of them are disabled. Backed by an O(1) cached
     * count (not a map scan) because this is called ~30x per telemetry snapshot on the daemon hot path.
     *
     * @return Whether the automation feature is enabled
     */
    public static boolean isDisabled() {
        return enabledCount == 0;
    }

    /**
     * Whether an automation with this id currently exists.
     *
     * @param id The id to look up
     * @return true if an automation is stored under this id
     */
    public static boolean exists(String id) {
        return id != null && automations.containsKey(id);
    }

    /**
     * Create or update an automation
     * Will use a UUID for new automations
     *
     * @param id         The id of an existing automation or null if a new automation is needed
     * @param automation The automation to add to the map
     */
    public static void updateAutomation(String id, Automation automation) {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        automations.put(id, automation);
        synchronized (SAVE_LOCK) { refreshEnabledCount(); }
        saveToFile();
        AutomationQueue.checkWorkerState();
        logger.info("Updated automation: " + id);
    }

    /**
     * Create or update an automation from a JSON representation
     *
     * @param id   The id for this automation or null if a new automation is needed
     * @param json The JSON representation of this automation
     * @return true if successfully created/updated, false otherwise
     */
    public static boolean updateAutomation(String id, JSONObject json) {
        Automation automation = Automation.fromJson(json);
        if (automation == null) return false;
        updateAutomation(id, automation);
        return true;
    }

    /**
     * Delete an automation with a specific id
     * Only persists and re-evaluates the worker state when a mapping actually existed, so a delete of
     * an unknown id is a true no-op (no needless file write / worker churn) and the caller can report
     * a 404 instead of a misleading success.
     *
     * @param id The id of the automation to delete
     * @return true if an automation was actually removed, false if no mapping existed for this id
     */
    public static boolean deleteAutomation(String id) {
        if (automations.remove(id) == null) return false;
        synchronized (SAVE_LOCK) { refreshEnabledCount(); }
        saveToFile();
        AutomationQueue.checkWorkerState();
        logger.info("Removed automation: " + id);
        return true;
    }

    /**
     * Disable an automation
     *
     * @param id       The id of the automation to disable
     * @param disabled true if it should be disabled, false otherwise
     * @return true if successfully disabled, false otherwise
     */
    public static boolean disableAutomation(String id, boolean disabled) {
        Automation automation = automations.get(id);
        if (automation == null) return false;
        automation.setDisabled(disabled);
        synchronized (SAVE_LOCK) { refreshEnabledCount(); }
        saveToFile();
        AutomationQueue.checkWorkerState();
        logger.info((disabled ? "Disabled" : "Enabled") + " automation: " + id);
        return true;
    }

    /**
     * The schema containing allowed values and descriptions for an automation
     *
     * @return The JSON schema for an automation
     */
    public static JSONArray schemaJson() {
        JSONArray json = conditions().toJson();

        try {
            JSONObject delayJson = delay().toJson();
            delayJson.put(
                    "description", Messages.get("automation.delay_description"));
            json.put(delayJson);
            json.put(actions().toJson());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * The JSON for all the stored automations
     * Can be stored to load later
     *
     * @return JSON for all the stored automations
     */
    public static JSONObject toJson() {
        JSONObject json = new JSONObject();

        try {
            for (Map.Entry<String, Automation> automation : automations.entrySet()) {
                json.put(automation.getKey(), automation.getValue().toJson());
            }
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * Persist the automations to a file.
     * <p>
     * Durable + concurrency-safe: the whole snapshot-then-write runs under {@link #SAVE_LOCK} so
     * concurrent API-thread saves can't interleave, and the bytes are written to a scratch
     * {@code .tmp} file then atomically {@code renameTo}'d over the live file (rename is atomic on the
     * same filesystem). Before promoting the scratch file, the current good live file is copied to a
     * {@code .bak} last-known-good. A crash therefore leaves at most a stale-but-valid live file or a
     * recoverable {@code .bak}; it can never leave a half-written live file that wipes all automations.
     */
    public static void saveToFile() {
        synchronized (SAVE_LOCK) {
            if (!AUTOMATION_HOME.exists()) AUTOMATION_HOME.mkdirs();
            // Snapshot to bytes under the lock so the persisted content is internally consistent.
            byte[] bytes = toJson().toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(AUTOMATION_TMP)) {
                fos.write(bytes);
                fos.getFD().sync();
            } catch (IOException e) {
                logger.error("Failed to write automations scratch file");
                return;
            }
            // Promote the existing good file to the backup before replacing it, so a failure while the
            // live file is momentarily gone still leaves a recoverable copy.
            if (AUTOMATION_CONFIG.exists()) copyFile(AUTOMATION_CONFIG, AUTOMATION_BACKUP);
            if (!AUTOMATION_TMP.renameTo(AUTOMATION_CONFIG)) {
                logger.error("Failed to promote automations scratch file to live config");
                return;
            }
            logger.info("Saved " + automations.size() + " Automations to " + AUTOMATION_CONFIG);
        }
    }

    /**
     * Copy a file's bytes. Best-effort; failures are logged but not fatal (the backup is a safety net,
     * not the source of truth).
     */
    private static void copyFile(File from, File to) {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.getFD().sync();
        } catch (IOException e) {
            logger.error("Failed to back up automations config");
        }
    }

    /**
     * Load persisted automations from the file, falling back to the last-known-good backup when the
     * live file is missing or corrupt. Runs under {@link #SAVE_LOCK} so it can't observe a live file
     * mid-rename.
     */
    public static void loadFromFile() {
        synchronized (SAVE_LOCK) {
            if (tryLoadFrom(AUTOMATION_CONFIG)) return;
            // Live file missing/corrupt — recover from the backup rather than silently starting empty.
            if (AUTOMATION_BACKUP.exists()) {
                if (tryLoadFrom(AUTOMATION_BACKUP)) {
                    logger.info("Recovered automations from backup after live config was unreadable");
                    return;
                }
                logger.error("Both live and backup automation configs were unreadable");
            }
        }
    }

    /**
     * Attempt to load automations from a specific file.
     *
     * @param file The file to read
     * @return true if the file existed and parsed (automations populated), false if missing/corrupt
     */
    private static boolean tryLoadFrom(File file) {
        if (!file.exists()) return false;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            String content = new String(bytes, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(content);
            // Build into a scratch map first so a parse failure part-way can't leave the live map
            // half-populated on top of what was already loaded.
            Map<String, Automation> loaded = new java.util.HashMap<>();
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Automation automation = Automation.fromJson(json.optJSONObject(key));
                if (automation != null) loaded.put(key, automation);
            }
            // Replace, don't merge: the file is the source of truth. Merging would resurrect an
            // automation deleted since the last load if this is ever wired to a runtime reload
            // (config-restore / OTA); replacement keeps the in-memory map exactly matching the file.
            automations.keySet().retainAll(loaded.keySet());
            automations.putAll(loaded);
            refreshEnabledCount();
            logger.info("Loaded " + loaded.size() + " Automations from " + file);
            return true;
        } catch (Exception e) {
            logger.error("Failed to load automations from " + file);
            return false;
        }
    }

    /**
     * Method to call when an event caused a value in the state to change (the new value has already been
     * committed to the state map by {@link #update}).
     * Will check all automations which contain this event as a trigger.
     * If the previous value is unknown, the event will not be triggered as the value may not have changed.
     * For this reason, events should fire at least once at startup to fill unknown values in the state.
     *
     * @param key      The event key
     * @param oldValue The value of the event before this change
     */
    private static void stateChanged(EventData key, Value oldValue) {
        // Don't trigger events when we don't know the previous value
        if (oldValue != null) {
            for (Map.Entry<String, Automation> automation : automations.entrySet()) {
                if (!automation.getValue().isDisabled() && automation.getValue().isTriggered(key)) {
                    if (automation.getValue().conditionsMet(state)) {
                        logger.info("Adding automation to queue: " + automation.getKey());
                        AutomationQueue.addToQueue(automation.getKey(), automation.getValue().getDelay());
                    } else {
                        logger.info("Removing automation from queue: " + automation.getKey());
                        AutomationQueue.removeFromQueue(automation.getKey());
                    }
                }
            }
        }
    }

    /**
     * Update the value in the state with a new value
     * Uses the Not Equal To comparator to see if the value has changed.
     * The commit is atomic on the state map: only the single thread that actually transitions the stored
     * value proceeds to evaluate automations, so two telemetry callback threads observing the same old
     * value for one logical change cannot both fire (which would double-run a vehicle-control action).
     *
     * @param key   The key for the event
     * @param value The new value of the event
     */
    public static void update(EventData key, Value value) {
        // Do nothing when no automations enabled
        if (isDisabled()) return;
        if (key == null || value == null) return;

        // Atomic compare-and-set: replace only if the current value differs; the winning thread gets the
        // previous value back and is the only one that runs stateChanged for this transition.
        Value[] previous = new Value[1];
        Value committed = state.compute(key, (k, current) -> {
            previous[0] = current;
            if (current == null || Boolean.TRUE.equals(current.compare(value, "neq"))) {
                return value; // transition — store the new value
            }
            return current; // unchanged — leave as-is
        });
        // We transitioned iff the stored value is now the new value AND it differs from what was there.
        if (committed == value && previous[0] != value) {
            stateChanged(key, previous[0]);
        }
    }

    /**
     * Method to call the update method with a primitive value
     *
     * @param key   The key for the event
     * @param value The new value of the event
     */
    public static void update(EventData key, String value) {
        update(key, new StringValue(value));
    }

    /**
     * Method to call the update method with a primitive value
     *
     * @param key   The key for the event
     * @param value The new value of the event
     */
    public static void update(EventData key, Integer value) {
        update(key, new IntValue(value));
    }

    /**
     * Run the actions for a specific automation
     * Can run the actions without checking the conditions for testing the actions
     * A disabled automation never fires from this choke point: both the queue worker
     * (checkConditions=true) and the /test endpoint (checkConditions=false) flow through here, and a
     * disabled automation that was queued with a delay before being disabled must not auto-fire when
     * the delay elapses. The disabled guard is therefore applied regardless of checkConditions.
     * The returned boolean reports only whether the automation EXISTS (so the API can answer 404 for
     * unknown ids); a known automation that was skipped because it is disabled or its conditions are
     * no longer met still returns true — testing a disabled automation is an accepted edge case that
     * reports success without firing. A known automation whose action throws also returns true (it
     * exists): the throw is swallowed here so the API never confuses an action failure with a 404.
     *
     * @param id              The id of the automation to run the actions for
     * @param checkConditions Whether to check that the conditions match before running the actions
     * @return true if an automation with this id exists, false if the id is unknown
     */
    public static boolean triggerActions(String id, boolean checkConditions) {
        Automation automation = automations.get(id);
        if (automation == null) return false;

        // A disabled automation must never run its actions, even if it was queued before being disabled
        if (automation.isDisabled()) {
            logger.info("Skipping disabled automation actions: " + id);
            return true;
        }

        if (!checkConditions || automation.conditionsMet(state)) {
            logger.info("Triggering automation actions: " + id);
            // Guard the action run at the shared choke point that BOTH callers flow through: the
            // autonomous queue worker (AutomationQueue.java:135-139) already wraps its call in
            // catch(Throwable), but the /test endpoint (AutomationApiHandler.testAutomation) does not.
            // A misbehaving or null action element (reachable via the unchecked
            // actions.add(action.fromJson(...)) in Automation.fromJson) would otherwise let an NPE/Error
            // propagate out of the test call, up through handle() to HttpServer's catch(Exception) which
            // only logs and closes the socket in its finally block — leaving the client with no HTTP
            // response at all. Swallowing it here (mirroring the queue worker) means the caller still
            // gets a proper response and the daemon stays up. We return true (the automation EXISTS) so
            // the failure is never misreported as a 404 by callers that map false -> 404.
            try {
                automation.triggerActions();
            } catch (Throwable t) {
                logger.error("Automation action threw while triggering: " + id);
            }
        }
        return true;
    }
}
