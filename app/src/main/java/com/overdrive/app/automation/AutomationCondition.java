package com.overdrive.app.automation;

import com.overdrive.app.automation.action.SetVariableAction;
import com.overdrive.app.automation.condition.BydEvent;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.value.BaseValue;
import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.Value;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class AutomationCondition {
    private final EventData eventData;
    private final String comparator;
    private final Object value;

    /**
     * A condition representation for a specific automation
     * The value can have any type as it is compared from an event with a specific type
     *
     * @param eventData  The variables for an event which would be compared to this condition
     * @param comparator The id of a comparator to use to compare the event and this value
     * @param value      The value to compare to an event
     */
    public AutomationCondition(EventData eventData, String comparator, Object value) {
        this.eventData = eventData;
        this.comparator = comparator;
        this.value = value;
    }

    /**
     * The variables for an event which would be compared to this condition
     *
     * @return The variables for an event which would be compared to this condition
     */
    public EventData getEventData() {
        return eventData;
    }

    /**
     * The id of a comparator to use to compare the event and this value
     * See the getComparators() method of values or types
     *
     * @return The id of a comparator to use to compare the event and this value
     */
    public String getComparator() {
        return comparator;
    }

    /**
     * The value to compare to an event
     *
     * @return The value to compare to an event
     */
    public Object getValue() {
        return value;
    }

    /**
     * Compare this value using the stored comparator
     * Checks for null response from the compare method to ensure the comparator and values are valid
     * A null value means the referenced event has never fired since boot, so its state is unknown.
     * In that case the condition is treated as not met (returns false) rather than dereferencing null,
     * which would throw an NPE on the telemetry/queue threads that call conditionsMet.
     *
     * @param value The value to compare with this condition
     * @return true if the comparison was successful, false otherwise
     */
    public boolean compare(Value value) {
        // An absent (never-fired) event value can never satisfy a condition
        if (value == null) return false;
        // DYNAMIC RIGHT-HAND SIDE (expression engine). When the stored value is a
        // reference token — ${var:NAME} (a user variable) or ${signal:TYPE[:k=v,…]}
        // (another live signal) — resolve it against the CURRENT shared state at compare
        // time and compare the LHS signal against that. This is fully additive: only a
        // String value beginning with the "${" token triggers it, and no pre-existing
        // automation ever stored such a value (numeric conditions stored ints; enum/
        // string conditions stored plain option words), so every existing rule takes the
        // identical constant path below. Fail-safe: an unresolved/absent/non-comparable
        // reference yields "not met" (returns false) rather than throwing on the
        // telemetry/queue threads — matching the null-LHS contract above.
        Object rhs = this.value;
        if (rhs instanceof String) {
            String ref = ((String) rhs).trim();
            if (ref.startsWith("${") && ref.endsWith("}")) {
                Value resolved = resolveDynamic(ref);
                if (resolved == null) return false; // unresolved reference → not met
                // Coerce the resolved value to the LHS's type so a numeric signal can be
                // compared against a variable/signal whose live value is string-backed
                // (a user variable is always a StringValue; "speed > ${var:limit}" where
                // limit holds "60" must compare numerically, not lexically). If the LHS
                // is an IntValue we parse the resolved value's string form to an int;
                // non-numeric → not met (fail-safe). For a string/enum LHS the raw
                // resolved value is compared as-is (lexical eq/neq).
                Object coerced = coerceForLhs(value, resolved);
                if (coerced == null) return false;
                return Boolean.TRUE.equals(value.compare(coerced, comparator));
            }
        }
        // Compare to true as it will be null when not a valid comparison
        return Boolean.TRUE.equals(value.compare(this.value, comparator));
    }

    /**
     * Coerce a resolved dynamic value to be comparable against the LHS value's type.
     * When the LHS is numeric ({@link IntValue}) the resolved value is parsed to an
     * Integer (from its string/Integer form), so a numeric signal compares numerically
     * against a string-backed variable — returns null when it isn't a whole number
     * (fail-safe → not met). For any other LHS type the resolved value is returned
     * unchanged (string/enum eq/neq compares lexically, as today).
     *
     * @param lhs      the live left-hand value (the signal being tested)
     * @param resolved the value the dynamic reference resolved to
     * @return a value comparable against {@code lhs}, or null if not coercible
     */
    private static Object coerceForLhs(Value lhs, Value resolved) {
        if (lhs instanceof IntValue) {
            Object raw = (resolved instanceof BaseValue<?>) ? ((BaseValue<?>) resolved).getValue() : resolved;
            if (raw instanceof Integer) return raw;
            if (raw instanceof Number) return ((Number) raw).intValue();
            try {
                return Integer.valueOf(raw.toString().trim());
            } catch (Exception e) {
                return null; // non-numeric variable/signal vs a numeric condition → not met
            }
        }
        return resolved;
    }

    /**
     * One-shot evaluation of "&lt;live signal&gt; &lt;comparator&gt; &lt;value&gt;" against the CURRENT
     * shared automation state, reusing this class's full compare path — including the
     * dynamic right-hand side ({@code ${var:NAME}} / {@code ${signal:TYPE[:k=v]}}) and the
     * numeric coercion in {@link #coerceForLhs}. Exposed so the inline flow actions
     * (If / Loop / Wait Until) evaluate a scalar test IDENTICALLY to a real condition,
     * rather than each re-implementing a constant-only compare. Fail-safe: an unknown
     * signal, never-fired LHS, or unresolved reference yields {@code false} (not met).
     *
     * @param lhs        the live signal to test (its state is read here)
     * @param comparator eq/neq/gt/lt/gte/lte
     * @param value      a constant (Integer/String) OR a dynamic {@code ${…}} token
     * @return true iff the comparison holds against current state
     */
    public static boolean evaluate(EventData lhs, String comparator, Object value) {
        if (lhs == null || comparator == null) return false;
        Value current = Automations.getStateValue(lhs);
        if (current == null) return false;
        return new AutomationCondition(lhs, comparator, value).compare(current);
    }

    /** True if a stored condition value is a dynamic reference token ${…}. */
    public static boolean isDynamicRef(Object value) {
        if (!(value instanceof String)) return false;
        String s = ((String) value).trim();
        return s.startsWith("${") && s.endsWith("}") && s.length() > 3;
    }

    /**
     * Resolve a dynamic reference token ({@code ${var:NAME}} / {@code ${signal:TYPE[:k=v]}})
     * to its live value as a plain String, or null if it can't be resolved (unknown kind,
     * never-fired signal, malformed token). Exposed for {@link
     * com.overdrive.app.automation.action.SetVariableAction} so "set variable = &lt;live
     * signal&gt;" captures the CURRENT value of any signal into a user variable, reusing the
     * SAME resolver the condition RHS uses (one source of truth for signal addressing).
     *
     * @param token a {@code ${…}} reference (caller should check {@link #isDynamicRef})
     * @return the resolved value as a String, or null when unresolved
     */
    public static String resolveDynamicToString(String token) {
        Value v = resolveDynamic(token);
        if (v == null) return null;
        Object raw = (v instanceof BaseValue<?>) ? ((BaseValue<?>) v).getValue() : v;
        return raw == null ? null : raw.toString();
    }

    /**
     * Resolve a dynamic reference token to the live {@link Value} it points at, or null
     * when it can't be resolved (unknown kind, never-fired signal, malformed token).
     * Reads the SAME shared automation state the LHS is read from
     * ({@link Automations#getStateValue}), so a cross-signal compare sees a consistent
     * snapshot. Two forms:
     *   ${var:NAME}                      → the user variable NAME (see SetVariableAction)
     *   ${signal:TYPE}                   → the live signal of that type (no attributes)
     *   ${signal:TYPE:k1=v1,k2=v2}       → a live signal differentiated by attributes
     *                                      (e.g. windowOpenPercent with area=lf)
     */
    private static Value resolveDynamic(String token) {
        try {
            // Strip the ${ … } wrapper.
            String inner = token.substring(2, token.length() - 1).trim();
            int colon = inner.indexOf(':');
            if (colon < 0) return null;
            String kind = inner.substring(0, colon).trim();
            String rest = inner.substring(colon + 1).trim();
            if (rest.isEmpty()) return null;

            if ("var".equals(kind)) {
                // User variable → its current state value (a StringValue), or null if unset.
                return Automations.getStateValue(SetVariableAction.variableEvent(rest));
            }
            if ("signal".equals(kind)) {
                // signal:TYPE  or  signal:TYPE:attrKey=val,attrKey2=val2
                String type;
                Map<String, String> attrs = new HashMap<>();
                int attrColon = rest.indexOf(':');
                if (attrColon < 0) {
                    type = rest;
                } else {
                    type = rest.substring(0, attrColon).trim();
                    String attrStr = rest.substring(attrColon + 1).trim();
                    for (String pair : attrStr.split(",")) {
                        int eq = pair.indexOf('=');
                        if (eq <= 0) return null; // malformed attribute → fail safe
                        attrs.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                    }
                }
                if (type.isEmpty()) return null;
                EventData key = attrs.isEmpty() ? new EventData(type) : new EventData(type, attrs);
                return Automations.getStateValue(key);
            }
            return null;
        } catch (Throwable t) {
            return null; // any parse error → not met (fail-safe on the fire gate)
        }
    }

    /**
     * Create a JSON object which can be stored and loaded for this condition
     *
     * @return JSON representation of this condition
     */
    public JSONObject toJson() {
        JSONObject json = getEventData().toJson();

        try {
            json.put("comparator", getComparator());
            json.put("value", getValue());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }
}
