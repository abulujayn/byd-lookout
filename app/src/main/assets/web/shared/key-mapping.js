/*
 * Key Mapping settings controller.
 *
 * ES5-only (Chrome 58 / Android 7.1 head-unit floor) — no let/const/arrow/template
 * literals. Talks to the daemon's KeymapApiHandler:
 *   GET  /api/keymap/config  -> { enabled, allowAdvanced, bindings[], a11yEnabled }
 *   POST /api/keymap/config  -> persist { enabled, allowAdvanced, bindings[] }
 *
 * A binding is:
 *   { keycode:int, pressType:"single|double|long", enabled:true,
 *     label:"<human summary>", action:<fire-payload> }
 * where <fire-payload> is exactly what /api/keymap/fire consumes:
 *   { kind:"catalog", key, sub?, payload }   — curated vehicle action
 *   { kind:"vehicle", action }               — lock/unlock/flash/find_car
 *   { kind:"shell",   cmd }                  — advanced escape hatch
 *
 * The curated action list below MIRRORS the daemon's VehicleControlCatalog /
 * composite command set. It is the client-side menu only; the daemon re-resolves
 * and re-validates every fire, so a stale entry here just fails safe server-side.
 */
window.KM = (function () {
    'use strict';

    // Curated actions the user can bind. `payloads` (when present) becomes the
    // Value dropdown; the chosen option's `v` is sent as the fire payload.
    //   kind:"catalog" -> {kind:'catalog', key, sub, payload:v}
    //   kind:"vehicle" -> {kind:'vehicle', action:key}   (no payload)
    var CURATED = [
        { id: 'lock',            i18n: 'keymap.act_lock',            kind: 'vehicle', key: 'lock' },
        { id: 'unlock',          i18n: 'keymap.act_unlock',          kind: 'vehicle', key: 'unlock' },
        { id: 'flash',           i18n: 'keymap.act_flash',           kind: 'vehicle', key: 'flash' },
        { id: 'find_car',        i18n: 'keymap.act_find_car',        kind: 'vehicle', key: 'find_car' },
        { id: 'windows_all',     i18n: 'keymap.act_windows',         kind: 'catalog', key: 'windows_all',
          payloads: [ { v: 'OPEN', i18n: 'keymap.open' }, { v: 'CLOSE', i18n: 'keymap.close' }, { v: 'STOP', i18n: 'keymap.stop' } ] },
        { id: 'tailgate',        i18n: 'keymap.act_tailgate',        kind: 'catalog', key: 'tailgate',
          payloads: [ { v: 'OPEN', i18n: 'keymap.open' }, { v: 'CLOSE', i18n: 'keymap.close' }, { v: 'STOP', i18n: 'keymap.stop' } ] },
        { id: 'sunroof',         i18n: 'keymap.act_sunroof',         kind: 'catalog', key: 'sunroof',
          payloads: [ { v: 'OPEN', i18n: 'keymap.open' }, { v: 'CLOSE', i18n: 'keymap.close' }, { v: 'STOP', i18n: 'keymap.stop' } ] },
        { id: 'sunshade',        i18n: 'keymap.act_sunshade',        kind: 'catalog', key: 'sunshade',
          payloads: [ { v: 'OPEN', i18n: 'keymap.open' }, { v: 'CLOSE', i18n: 'keymap.close' }, { v: 'STOP', i18n: 'keymap.stop' } ] },
        { id: 'climate',         i18n: 'keymap.act_climate',         kind: 'catalog', key: 'climate', sub: 'mode',
          payloads: [ { v: 'auto', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'drl',             i18n: 'keymap.act_drl',             kind: 'catalog', key: 'drl',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'seat_heat_driver',    i18n: 'keymap.act_seat_heat_driver',    kind: 'catalog', key: 'seat_heat_driver',
          payloads: [ { v: 'off', i18n: 'keymap.off' }, { v: 'low', i18n: 'keymap.low' }, { v: 'high', i18n: 'keymap.high' } ] },
        { id: 'seat_heat_passenger', i18n: 'keymap.act_seat_heat_passenger', kind: 'catalog', key: 'seat_heat_passenger',
          payloads: [ { v: 'off', i18n: 'keymap.off' }, { v: 'low', i18n: 'keymap.low' }, { v: 'high', i18n: 'keymap.high' } ] },
        { id: 'child_lock',      i18n: 'keymap.act_child_lock',      kind: 'catalog', key: 'child_lock',
          payloads: [ { v: '1', i18n: 'keymap.on' }, { v: '0', i18n: 'keymap.off' } ] },
        { id: 'wireless_charging', i18n: 'keymap.act_wireless_charging', kind: 'catalog', key: 'wireless_charging',
          payloads: [ { v: '1', i18n: 'keymap.on' }, { v: '0', i18n: 'keymap.off' } ] },
        { id: 'drive_mode',      i18n: 'keymap.act_drive_mode',       kind: 'catalog', key: 'drive_mode',
          payloads: [ { v: 'eco', i18n: 'keymap.mode_eco' }, { v: 'sport', i18n: 'keymap.mode_sport' } ] },
        { id: 'powertrain_mode', i18n: 'keymap.act_powertrain_mode',  kind: 'catalog', key: 'powertrain_mode',
          payloads: [ { v: 'ev', i18n: 'keymap.mode_ev' }, { v: 'hev', i18n: 'keymap.mode_hev' } ] },
        { id: 'regen_level',     i18n: 'keymap.act_regen',            kind: 'catalog', key: 'regen_level',
          payloads: [ { v: 'standard', i18n: 'keymap.regen_standard' }, { v: 'high', i18n: 'keymap.regen_high' } ] },
        { id: 'steering_mode',   i18n: 'keymap.act_steering',         kind: 'catalog', key: 'steering_mode',
          payloads: [ { v: 'comfort', i18n: 'keymap.steering_comfort' }, { v: 'sport', i18n: 'keymap.steering_sport' } ] }
    ];

    // Known BYD steering-wheel / dash buttons (keycode → label), from the OEM
    // keycode map verified on DiLink firmware.
    //
    // CRITICAL firmware model: on this hardware a LONG PRESS is NOT repeatCount>0
    // of a base keycode — the firmware detects the hold itself and emits a
    // SEPARATE keycode (302 = "Next long", the long-press of 87 = "Next"; likewise
    // 303←88, 306←305, 312←304). So:
    //   • base keys (87/88/289/…) support single + double (timing between presses),
    //   • long-variant keys (302/303/306/312) must fire ON ARRIVAL — the keycode
    //     showing up IS the completed long-press. Binding them as pressType "long"
    //     (which waits for repeatCount>0) would NEVER fire, since they arrive as a
    //     plain repeatCount==0 DOWN. So `fixed: 'single'` = fire immediately; the
    //     label already tells the user it's the long-press button.
    // `pressTypes` limits the press-type selector to what the base keycode really
    // supports (no "long" on a base key, because long is a different keycode here).
    var KNOWN_BUTTONS = [
        { code: 87,  i18n: 'keymap.btn_next',      pressTypes: ['single', 'double'] },
        { code: 88,  i18n: 'keymap.btn_prev',      pressTypes: ['single', 'double'] },
        { code: 289, i18n: 'keymap.btn_mode',      pressTypes: ['single', 'double'] },
        { code: 291, i18n: 'keymap.btn_vol_up',    pressTypes: ['single'] },
        { code: 292, i18n: 'keymap.btn_vol_down',  pressTypes: ['single'] },
        { code: 293, i18n: 'keymap.btn_mute',      pressTypes: ['single', 'double'] },
        { code: 294, i18n: 'keymap.btn_surround',  pressTypes: ['single', 'double'] },
        { code: 304, i18n: 'keymap.btn_voice',     pressTypes: ['single', 'double'] },
        { code: 305, i18n: 'keymap.btn_rotate',    pressTypes: ['single', 'double'] },
        { code: 313, i18n: 'keymap.btn_phone',     pressTypes: ['single', 'double'] },
        { code: 317, i18n: 'keymap.btn_power',     pressTypes: ['single', 'double'] },
        { code: 302, i18n: 'keymap.btn_next_long',   fixed: 'single' },
        { code: 303, i18n: 'keymap.btn_prev_long',   fixed: 'single' },
        { code: 306, i18n: 'keymap.btn_rotate_long', fixed: 'single' },
        { code: 312, i18n: 'keymap.btn_voice_long',  fixed: 'single' }
    ];

    var state = { enabled: false, allowAdvanced: false, bindings: [], a11yEnabled: false };
    var capturing = false;
    var captured = null; // last captured keycode while arming

    function $(id) { return document.getElementById(id); }
    function tr(key, vars) { return (window.BYD && BYD.i18n) ? (BYD.i18n.t(key, vars) || key) : key; }
    function toast(msg, kind) { if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, kind || 'info'); }
    function curatedById(id) {
        for (var i = 0; i < CURATED.length; i++) if (CURATED[i].id === id) return CURATED[i];
        return null;
    }

    // ───────────────────────── Load / paint ─────────────────────────

    // Guards for the a11y self-heal re-fetch below. a11yRecheckPending blocks
    // OVERLAPPING timers; a11yRechecksLeft BOUNDS the total number of sequential
    // rechecks so the page never polls forever. The daemon's self-heal lands
    // within ~5-11s (one or two 5.5s windows) on a provisioned unit, so a small
    // budget covers the legitimate "heal in flight" case. If the service still
    // isn't enabled after that (e.g. the Secure-settings write can't stick on a
    // permission-limited build, or the user hasn't enabled it), we STOP re-fetching
    // and leave the nudge card up — the user acts via "Open accessibility settings".
    // Without this cap, recheckA11y()->scheduleA11yRecheck() re-armed every 5.5s for
    // the life of the page, and each GET spawned a server-side settings-exec heal.
    var a11yRecheckPending = false;
    var a11yRechecksLeft = 0;
    var A11Y_RECHECK_BUDGET = 3;

    function load() {
        fetch('/api/keymap/config', { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (s) {
                state.enabled = !!s.enabled;
                state.allowAdvanced = !!s.allowAdvanced;
                state.bindings = (s.bindings && s.bindings.length) ? s.bindings : [];
                state.a11yEnabled = !!s.a11yEnabled;
                paint();
                // Fresh foreground load — refill the recheck budget so the
                // heal-window poll runs, but stays bounded (see scheduleA11yRecheck).
                a11yRechecksLeft = A11Y_RECHECK_BUDGET;
                scheduleA11yRecheck();
            })
            .catch(function () { toast(tr('keymap.load_failed'), 'error'); });
    }

    // Client-side half of the daemon's load-time self-heal
    // (KeymapApiHandler.handleGetConfig): when mapping is ON but the a11y service
    // is off, the GET makes the daemon kick an async enable that lands ~4-5s later,
    // but it reports a11yEnabled from a PRE-heal read — so a11yEnabled=true only
    // shows up on a SUBSEQUENT GET. Re-check once after the heal window so the
    // nudge auto-hides instead of lingering until a manual reload.
    //
    // CRITICAL: this MUST refresh ONLY the a11y status, never re-assign
    // state.bindings / state.enabled / state.allowAdvanced. This is a BACKGROUND
    // timer that can fire in the middle of the user adding bindings; if it called
    // the full load() (as it used to), it would clobber state.bindings with the
    // server snapshot — dropping any binding added since the timer was armed, or
    // one that a still-in-flight persist() hasn't committed yet. That was the
    // "adding a new binding deletes the older one" bug. So recheckA11y() reads the
    // config but adopts only a11yEnabled, leaving the user's working binding list
    // and toggles untouched.
    function scheduleA11yRecheck() {
        if (state.enabled && !state.a11yEnabled && !a11yRecheckPending && a11yRechecksLeft > 0) {
            a11yRecheckPending = true;
            a11yRechecksLeft--;
            setTimeout(function () { a11yRecheckPending = false; recheckA11y(); }, 5500);
        }
    }

    function recheckA11y() {
        fetch('/api/keymap/config', { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (s) {
                // Adopt ONLY the a11y status — do NOT touch bindings/enabled/allowAdvanced.
                state.a11yEnabled = !!s.a11yEnabled;
                // Refresh just the nudge card, not the whole list (paint() re-renders
                // from the untouched state.bindings, so it's safe, but keep it minimal).
                var card = $('kmA11yCard');
                if (card) card.style.display = (state.enabled && !state.a11yEnabled) ? '' : 'none';
                // If still not enabled, arm one more recheck (self-terminates once it flips).
                scheduleA11yRecheck();
            })
            .catch(function () { /* transient — leave the nudge as-is */ });
    }

    function paint() {
        $('kmEnable').checked = state.enabled;
        $('kmAllowAdvanced').checked = state.allowAdvanced;

        // The "Advanced: shell" action only exists when the Advanced toggle is on.
        // Hiding the OPTION (not just gating on save) makes the relationship
        // obvious: flip the toggle → the shell choice appears/disappears. If it
        // was selected and Advanced is turned off, snap the picker back to the
        // safe curated kind so no stale shell form is left showing.
        var shellOpt = $('kmShellOption');
        if (shellOpt) {
            shellOpt.style.display = state.allowAdvanced ? '' : 'none';
            shellOpt.disabled = !state.allowAdvanced;
        }
        if (!state.allowAdvanced && $('kmActionKind').value === 'shell') {
            $('kmActionKind').value = 'curated';
        }
        onKindChange();

        var badge = $('kmStatusBadge');
        if (state.enabled) {
            badge.textContent = tr('keymap.badge_on');
            badge.className = 'status-badge active';
        } else {
            badge.textContent = tr('keymap.badge_off');
            badge.className = 'status-badge inactive';
        }

        // Show the a11y nudge only when mapping is enabled but the OS service is off.
        $('kmA11yCard').style.display = (state.enabled && !state.a11yEnabled) ? '' : 'none';

        renderList();
    }

    function renderList() {
        var list = $('kmList');
        var empty = $('kmEmpty');
        list.innerHTML = '';
        if (!state.bindings.length) {
            empty.style.display = '';
            return;
        }
        empty.style.display = 'none';
        for (var i = 0; i < state.bindings.length; i++) {
            list.appendChild(bindingRow(state.bindings[i], i));
        }
    }

    function bindingRow(b, index) {
        var row = document.createElement('div');
        row.className = 'km-binding';

        var key = document.createElement('div');
        key.className = 'km-key';
        key.textContent = b.keycode;
        row.appendChild(key);

        var meta = document.createElement('div');
        meta.className = 'km-meta';
        var action = document.createElement('div');
        action.className = 'km-action';
        action.textContent = b.label || describeAction(b.action);
        var sub = document.createElement('div');
        sub.className = 'km-sub';
        sub.textContent = pressLabel(b.pressType);
        meta.appendChild(action);
        meta.appendChild(sub);
        row.appendChild(meta);

        var pill = document.createElement('span');
        pill.className = 'km-press-pill';
        pill.textContent = pressLabel(b.pressType);
        row.appendChild(pill);

        var del = document.createElement('button');
        del.className = 'btn btn-icon btn-danger-ghost';
        del.setAttribute('aria-label', tr('keymap.delete'));
        del.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>';
        del.onclick = function () { removeBinding(index); };
        row.appendChild(del);
        return row;
    }

    function pressLabel(t) {
        if (t === 'double') return tr('keymap.press_double');
        if (t === 'long') return tr('keymap.press_long');
        return tr('keymap.press_single');
    }

    // Human summary of a fire-payload, used when a binding has no stored label.
    function describeAction(a) {
        if (!a) return '';
        if (a.kind === 'vehicle') return tr('keymap.act_' + a.action);
        if (a.kind === 'catalog') {
            var c = curatedById(a.key);
            var name = c ? tr(c.i18n) : a.key;
            return a.payload ? (name + ' — ' + a.payload) : name;
        }
        if (a.kind === 'shell') return tr('keymap.kind_shell') + ': ' + (a.cmd || '');
        if (a.kind === 'sequence') {
            var steps = a.steps || [];
            var parts = [];
            for (var i = 0; i < steps.length; i++) parts.push(describeAction(steps[i]));
            return parts.join(' → ');
        }
        return a.kind || '';
    }

    // ───────────────────────── Config toggles ─────────────────────────

    function saveConfig() {
        state.enabled = $('kmEnable').checked;
        state.allowAdvanced = $('kmAllowAdvanced').checked;
        persist(function (ok) {
            if (ok) { paint(); } else { toast(tr('keymap.save_failed'), 'error'); }
        });
    }

    function persist(cb) {
        fetch('/api/keymap/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                enabled: state.enabled,
                allowAdvanced: state.allowAdvanced,
                bindings: state.bindings
            })
        }).then(function (r) { return r.json(); })
          .then(function (d) {
              // The save response echoes the live a11y state (the daemon may have
              // just auto-enabled the service on the enable edge), so adopt it and
              // let paint() hide the nag without waiting for a full reload.
              if (d && typeof d.a11yEnabled !== 'undefined') state.a11yEnabled = !!d.a11yEnabled;
              if (cb) cb(!!(d && d.success));
          })
          .catch(function () { if (cb) cb(false); });
    }

    // ───────────────────────── Capture ─────────────────────────

    function toggleCapture() {
        capturing = !capturing;
        var box = $('kmCaptureBox');
        var label = $('kmCaptureBtnLabel');
        if (capturing) {
            box.classList.add('armed');
            $('kmCapHint').textContent = tr('keymap.capture_press');
            label.textContent = tr('keymap.capture_stop');
        } else {
            box.classList.remove('armed');
            label.textContent = tr('keymap.capture');
        }
        // Tell the native side to route hardware keys to us while armed. Hardware
        // buttons hit the native AccessibilityService (onKeyEvent), NOT the WebView
        // DOM, so without this the capture box can only ever see keys that happen
        // to reach the DOM. When armed, the dispatcher forwards + consumes the key
        // and calls window.KM.onNativeKey(code) below.
        try {
            if (window.AndroidBridge && AndroidBridge.setKeyCapture) {
                AndroidBridge.setKeyCapture(capturing);
            }
        } catch (e) { /* not in the app WebView — DOM keydown fallback still works */ }
    }

    // Record a captured keycode (shared by the native bridge and the DOM
    // fallback). Reflects into the display + manual field and disarms.
    function recordCapturedKey(code) {
        if (!code) return;
        captured = code;
        $('kmCapKeycode').textContent = code;
        $('kmCapHint').textContent = tr('keymap.captured');
        $('kmManualKeycode').value = code;
        // If the captured code is a known long-variant (302/303/306/312), narrow
        // the press-type selector to fixed:'single' now — those codes fire on
        // arrival, so a bound "long" would be a dead binding.
        refreshPressTypeForManual();
        if (capturing) toggleCapture(); // disarm after one capture (also clears native capture)
    }

    // Called by the native AccessibilityService (via evaluateJavascript) when a
    // hardware key arrives during capture — the reliable path on the head unit.
    function onNativeKey(code) {
        if (!capturing) return;
        recordCapturedKey(code);
    }

    // In-page keydown fallback for phone/desktop PWA (and any key that DOES reach
    // the DOM). On the head unit the native bridge above is the real path.
    function onKeydown(e) {
        if (!capturing) return;
        var code = e.keyCode || e.which;
        if (!code) return;
        e.preventDefault();
        recordCapturedKey(code);
    }

    // ───────────────────────── Add / remove ─────────────────────────

    function onKindChange() {
        formDirty = true;
        var kind = $('kmActionKind').value;
        $('kmCuratedWrap').style.display = kind === 'curated' ? '' : 'none';
        $('kmShellWrap').style.display = kind === 'shell' ? '' : 'none';
    }

    function onCuratedChange() {
        formDirty = true;
        var c = curatedById($('kmCuratedAction').value);
        var row = $('kmPayloadRow');
        var sel = $('kmPayload');
        sel.innerHTML = '';
        if (c && c.payloads && c.payloads.length) {
            row.style.display = '';
            for (var i = 0; i < c.payloads.length; i++) {
                var o = document.createElement('option');
                o.value = c.payloads[i].v;
                o.textContent = tr(c.payloads[i].i18n);
                sel.appendChild(o);
            }
        } else {
            row.style.display = 'none';
        }
    }

    function buildCuratedOptions() {
        var sel = $('kmCuratedAction');
        sel.innerHTML = '';
        for (var i = 0; i < CURATED.length; i++) {
            var o = document.createElement('option');
            o.value = CURATED[i].id;
            o.textContent = tr(CURATED[i].i18n);
            sel.appendChild(o);
        }
        onCuratedChange();
    }

    // Populate the "Button" dropdown from KNOWN_BUTTONS. First option is a
    // placeholder ("Choose a button…"); a final option is the custom/capture
    // escape hatch that reveals the capture box + manual field.
    function buildKnownButtonOptions() {
        var sel = $('kmKnownButton');
        if (!sel) return;
        sel.innerHTML = '';
        var ph = document.createElement('option');
        ph.value = '';
        ph.textContent = tr('keymap.pick_button');
        sel.appendChild(ph);
        for (var i = 0; i < KNOWN_BUTTONS.length; i++) {
            var b = KNOWN_BUTTONS[i];
            var o = document.createElement('option');
            o.value = String(b.code);
            // Show the code so power users can still see it: "Next  (87)"
            o.textContent = tr(b.i18n) + '  (' + b.code + ')';
            sel.appendChild(o);
        }
        var custom = document.createElement('option');
        custom.value = 'custom';
        custom.textContent = tr('keymap.btn_custom');
        sel.appendChild(custom);
    }

    function knownByCode(code) {
        for (var i = 0; i < KNOWN_BUTTONS.length; i++) {
            if (KNOWN_BUTTONS[i].code === code) return KNOWN_BUTTONS[i];
        }
        return null;
    }

    // When a known button is chosen: set the keycode, reveal/hide the capture
    // area, and tailor the press-type selector to what THIS button supports.
    function onKnownButtonChange() {
        var sel = $('kmKnownButton');
        var val = sel ? sel.value : '';
        var customWrap = $('kmCustomKeyWrap');
        // Leaving the custom/capture branch while still armed would hide the
        // capture wrap without disarming — the dispatcher would keep globally
        // consuming (and silently swallowing) the next hardware key, suppressing
        // its OEM action. toggleCapture() no-ops the display side effects here but
        // flips `capturing` false AND calls AndroidBridge.setKeyCapture(false),
        // clearing WebViewFragment.captureArmed. Re-entering custom from a
        // non-armed state is unaffected (arming still happens via the Capture btn).
        if (val !== 'custom' && capturing) toggleCapture();
        if (val === 'custom') {
            // Reveal the capture + manual-entry area (the secondary path). An
            // arbitrary captured/typed key gets the full press-type choice (we
            // can't know the firmware's gesture model for an unknown keycode) —
            // but if the entered/captured code turns out to be a KNOWN
            // long-variant (302/303/306/312), refreshPressTypeForManual() locks
            // it to fixed:'single' so the user can't create a never-firing
            // "long" binding (those codes arrive as a plain repeatCount==0 DOWN).
            if (customWrap) customWrap.style.display = '';
            captured = null;
            $('kmManualKeycode').value = '';
            refreshPressTypeForManual();
            return;
        }
        if (customWrap) customWrap.style.display = 'none';
        if (!val) { captured = null; $('kmManualKeycode').value = ''; buildPressTypeOptions(null); return; }
        var code = parseInt(val, 10);
        captured = code;
        $('kmManualKeycode').value = code;
        buildPressTypeOptions(knownByCode(code));
    }

    // Rebuild the press-type <select> for the chosen button:
    //   • fixed button (long-variant keycode) → single locked option; the keycode
    //     arriving already IS the gesture, so it fires on arrival (pressType single),
    //     and we disable the selector so the user can't pick a never-firing "long".
    //   • base button with pressTypes → only those options (no "long", since long
    //     is a different keycode on this firmware).
    //   • null (custom/capture) → all three, free choice.
    function buildPressTypeOptions(btn) {
        var pt = $('kmPressType');
        if (!pt) return;
        var opts;
        var disabled = false;
        if (btn && btn.fixed) { opts = [btn.fixed]; disabled = true; }
        else if (btn && btn.pressTypes) { opts = btn.pressTypes; }
        else { opts = ['single', 'double', 'long']; }
        var labelKey = { single: 'keymap.press_single', double: 'keymap.press_double', long: 'keymap.press_long' };
        pt.innerHTML = '';
        for (var i = 0; i < opts.length; i++) {
            var o = document.createElement('option');
            o.value = opts[i];
            o.textContent = tr(labelKey[opts[i]] || opts[i]);
            pt.appendChild(o);
        }
        pt.value = opts[0];
        pt.disabled = disabled;
    }

    // Press-type selector for the manual/custom path. Reads the current manual
    // keycode and, if it is a KNOWN button (base or long-variant), restricts the
    // selector to exactly what that keycode supports — locking the four
    // long-variant keycodes (302/303/306/312) to fixed:'single' so the custom
    // path can't mint a dead "long" (or an unperformable double-of-a-hold)
    // binding. A genuinely-arbitrary keycode still gets all three types (the
    // escape hatch for platforms that DO auto-repeat). Called on entering the
    // custom branch, after capturing a key, and on every manual-keycode edit.
    function refreshPressTypeForManual() {
        var code = parseInt($('kmManualKeycode').value, 10);
        buildPressTypeOptions(code ? knownByCode(code) : null);
    }

    // In-progress sequence steps (each a fire-action object). When non-empty,
    // "Add binding" wraps them as a {kind:'sequence'} action; a lone step is
    // stored as that single action (no needless sequence wrapper).
    var seqSteps = [];

    // Has the Add-form's action selection been touched since the last "Add step"
    // (or form reset)? Because buildActionFromForm() never returns null for the
    // default curated pick, "Add binding" cannot tell a genuinely-edited-but-not-
    // added selection from the leftover of the previous "Add step". This flag
    // lets addBinding() fold a pending selection ONLY when the user actually
    // edited it, so the documented "Add step … Add binding" chain doesn't
    // silently duplicate its last action (e.g. lock → flash → flash).
    var formDirty = false;

    // Build one fire-action object from the current Add-form selection, or null
    // (with a toast) if the form is incomplete. Shared by "Add binding" and
    // "Add step" so a step and a standalone binding are built identically.
    function buildActionFromForm() {
        var kind = $('kmActionKind').value;
        if (kind === 'curated') {
            var c = curatedById($('kmCuratedAction').value);
            if (!c) { toast(tr('keymap.need_action'), 'error'); return null; }
            if (c.kind === 'vehicle') return { kind: 'vehicle', action: c.key };
            var payload = ($('kmPayload').value != null) ? $('kmPayload').value : '';
            var a = { kind: 'catalog', key: c.key, payload: payload };
            if (c.sub) a.sub = c.sub;
            return a;
        }
        if (kind === 'shell') {
            if (!state.allowAdvanced) { toast(tr('keymap.advanced_disabled'), 'error'); return null; }
            var cmd = $('kmShellCmd').value.trim();
            if (!cmd) { toast(tr('keymap.need_cmd'), 'error'); return null; }
            return { kind: 'shell', cmd: cmd };
        }
        return null;
    }

    // Append the current form selection to the in-progress sequence and re-render
    // the step chips. Lets the user compose "close windows → lock → sport mode".
    // After a successful push we blank the action inputs so the just-added value
    // isn't silently re-appended by addBinding()'s pending-fold (WYSIWYG: what's
    // in the chips is what fires).
    function addStep() {
        var a = buildActionFromForm();
        if (!a) return;
        seqSteps.push(a);
        clearActionForm();
        renderSteps();
    }

    // Reset only the action inputs (not keycode/pressType, which describe the key
    // being bound — not the step) so the next step starts blank. Because this is
    // a programmatic reset, clear formDirty AFTER the handlers (which set it) run.
    function clearActionForm() {
        $('kmActionKind').value = 'curated';
        onKindChange();
        $('kmCuratedAction').selectedIndex = 0;
        onCuratedChange();
        $('kmShellCmd').value = '';
        formDirty = false;
    }

    function removeStep(i) {
        seqSteps.splice(i, 1);
        renderSteps();
    }

    function renderSteps() {
        var wrap = $('kmSeqSteps');
        if (!wrap) return;
        wrap.innerHTML = '';
        wrap.style.display = seqSteps.length ? '' : 'none';
        for (var i = 0; i < seqSteps.length; i++) {
            (function (idx) {
                var chip = document.createElement('div');
                chip.className = 'km-step';
                var span = document.createElement('span');
                span.textContent = (idx + 1) + '. ' + describeAction(seqSteps[idx]);
                chip.appendChild(span);
                var x = document.createElement('button');
                x.className = 'km-step-x';
                x.setAttribute('aria-label', tr('keymap.delete'));
                x.innerHTML = '&times;';
                x.onclick = function () { removeStep(idx); };
                chip.appendChild(x);
                wrap.appendChild(chip);
            })(i);
        }
    }

    function addBinding() {
        var keycode = parseInt($('kmManualKeycode').value, 10);
        if (!keycode && captured) keycode = captured;
        if (!keycode || keycode <= 0) { toast(tr('keymap.need_keycode'), 'error'); return; }

        var pressType = $('kmPressType').value;
        var action = null;
        var label = '';

        if (seqSteps.length > 0) {
            // One key → N actions. Only fold an actually-edited, un-added form
            // selection in as the final step — addStep() blanks the form and
            // clears formDirty, so a user who clicked "Add step" for every action
            // doesn't get its last one silently duplicated (lock → flash → flash).
            // A user who edits the form but forgets "Add step" still gets it.
            var steps = seqSteps.slice();
            if (formDirty) {
                var pending = buildActionFromForm();
                if (pending) steps.push(pending);
            }
            if (steps.length === 1) {
                action = steps[0];
                label = describeAction(action);
            } else {
                action = { kind: 'sequence', steps: steps };
                label = tr('keymap.sequence_of', { n: steps.length }) || (steps.length + ' steps');
            }
        } else {
            action = buildActionFromForm();
            if (!action) return; // toast already shown
            label = describeAction(action);
        }

        // Reject an exact keycode+pressType duplicate — the dispatcher's
        // "newest wins" would otherwise fire both. Replace instead. Snapshot the
        // list first so a persist failure can roll back to exactly what the
        // server has — otherwise a phantom binding lingers in local state and a
        // later saveConfig()->persist() (from toggling Enable/Advanced) would
        // silently commit it. Mirrors removeBinding's load()-on-failure resync.
        var prev = state.bindings.slice();
        var replaced = false;
        for (var i = 0; i < state.bindings.length; i++) {
            if (state.bindings[i].keycode === keycode && state.bindings[i].pressType === pressType) {
                state.bindings[i] = { keycode: keycode, pressType: pressType, enabled: true, label: label, action: action };
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            state.bindings.push({ keycode: keycode, pressType: pressType, enabled: true, label: label, action: action });
        }

        persist(function (ok) {
            if (!ok) { state.bindings = prev; toast(tr('keymap.save_failed'), 'error'); renderList(); return; }
            toast(replaced ? tr('keymap.updated') : tr('keymap.added'), 'success');
            resetForm();
            paint();
            if (window.OT_setActiveTab) window.OT_setActiveTab('bindings');
        });
    }

    function removeBinding(index) {
        // Do the actual delete once confirmed.
        function doDelete() {
            state.bindings.splice(index, 1);
            persist(function (ok) {
                if (ok) { renderList(); toast(tr('keymap.deleted'), 'success'); }
                else { toast(tr('keymap.save_failed'), 'error'); load(); }
            });
        }
        // Use the app's themed modal (matches every other page). Native
        // window.confirm looks foreign in the WebView; keep it only as a
        // fallback for very-early-init / older shells without BYD.utils.
        if (window.BYD && BYD.utils && BYD.utils.confirmDialog) {
            BYD.utils.confirmDialog({
                title: tr('keymap.delete'),
                body: tr('keymap.confirm_delete'),
                confirmLabel: tr('keymap.delete'),
                cancelLabel: tr('common.cancel'),
                danger: true
            }).then(function (ok) { if (ok) doDelete(); });
        } else if (window.confirm(tr('keymap.confirm_delete'))) {
            doDelete();
        }
    }

    function resetForm() {
        captured = null;
        $('kmManualKeycode').value = '';
        $('kmCapKeycode').textContent = '—';
        $('kmCapHint').textContent = tr('keymap.capture_idle');
        $('kmShellCmd').value = '';
        seqSteps = [];
        formDirty = false;
        renderSteps();
    }

    // Empty-state CTA — jump to the Add tab.
    function goAdd() {
        if (window.OT_setActiveTab) window.OT_setActiveTab('add');
    }

    function openA11ySettings() {
        // Standard OS accessibility settings. In the head-unit WebView this may
        // be intercepted by the native shell to deep-link BYD's a11y screen.
        try { window.location.href = 'intent://settings/accessibility'; } catch (e) {}
        toast(tr('keymap.a11y_hint'), 'info');
    }

    // ───────────────────────── Init ─────────────────────────

    function init() {
        buildCuratedOptions();
        buildKnownButtonOptions();
        onKnownButtonChange(); // set initial custom-wrap visibility (placeholder → hidden)
        onKindChange();
        document.addEventListener('keydown', onKeydown, true);
        // Mark the Add-form touched when the user edits the free-text / payload
        // inputs directly (the two <select>s already flag via their onchange
        // handlers). Lets addBinding() distinguish a real un-added selection from
        // the blanked leftover of "Add step". Wired once; harmless when disabled.
        wireDirty('kmPayload');
        wireDirty('kmShellCmd');
        wireDirty('kmManualKeycode');
        // Typing a keycode directly (custom path) should immediately narrow the
        // press-type selector: entering a known long-variant (302/303/306/312)
        // locks it to 'single', preventing a never-firing "long" binding, while
        // an unknown code keeps all three types. Complements the capture-path and
        // custom-branch calls to refreshPressTypeForManual().
        var manualEl = $('kmManualKeycode');
        if (manualEl) {
            manualEl.addEventListener('input', refreshPressTypeForManual, false);
            manualEl.addEventListener('change', refreshPressTypeForManual, false);
        }
        // buildCuratedOptions()/onKindChange() above set formDirty as a side
        // effect of programmatic setup; the form is pristine at rest, so clear it.
        formDirty = false;
        load();
    }

    // Flag the form dirty on any user edit of the given input.
    function wireDirty(id) {
        var el = $(id);
        if (!el) return;
        var mark = function () { formDirty = true; };
        el.addEventListener('input', mark, false);
        el.addEventListener('change', mark, false);
    }

    return {
        init: init,
        load: load,
        saveConfig: saveConfig,
        toggleCapture: toggleCapture,
        onNativeKey: onNativeKey,
        onKnownButtonChange: onKnownButtonChange,
        onKindChange: onKindChange,
        onCuratedChange: onCuratedChange,
        addStep: addStep,
        addBinding: addBinding,
        goAdd: goAdd,
        openA11ySettings: openA11ySettings
    };
}());
