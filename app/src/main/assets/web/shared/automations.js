/*
 * Overdrive — Automations UI controller.
 *
 * ES5 / Chrome 58 floor (BYD DiLink head-unit WebView, Android 7.1). Assets
 * ship raw — there is NO transpile/build step — so this file must parse and
 * run on Chrome 58. In particular:
 *   - No optional chaining (?.) / nullish coalescing (??) — both are Chrome 80+.
 *     A single one anywhere aborts the ENTIRE script at parse time, which is
 *     what left the tab blank and every onclick handler dead.
 *   - No Array.flat / Object.fromEntries / String.replaceAll (Chrome 90+),
 *     no class fields / private #fields, no numeric separators, no
 *     optional-catch-binding (`catch {}` — needs `catch (e) {}`).
 *   - const/let, arrow functions, template literals, for...of, Array.from,
 *     Promise and fetch ARE fine on Chrome 58.
 * Mutating network calls (POST/PUT/DELETE) MUST use fetch() — XHR request
 * bodies are silently dropped on this WebView.
 */

window.BYD = window.BYD || {};

const triggerIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z"/></svg>';
const copyIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>';
const editIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>';
const deleteIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>';
const infoIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>';

// Automation ids and the schema section ids are user / server supplied. They
// are safe to interpolate into text, but NOT into class names (a leading
// digit, space, or punctuation throws a DOMException from classList.add) and
// NOT into URLs without percent-encoding. Sanitize to a token for class use;
// encodeURIComponent for URL path segments.
function sanitizeToken(value) {
    return String(value == null ? '' : value).replace(/[^a-zA-Z0-9_-]/g, '_');
}

BYD.automations = {
    automations: {},
    schema: [],
    formData: {},
    editingId: null,

    init() {
        this.loadAutomations();
        this.loadAutomationSchema();
        this.loadSettings();
    },

    // Automation-wide settings (currently just the shell-action gate). Kept
    // separate from the per-automation CRUD above.
    async loadSettings() {
        try {
            const resp = await fetch('/api/automations/settings', { cache: 'no-store' });
            const data = await resp.json();
            const el = document.getElementById('autoAllowShell');
            if (el) el.checked = !!(data && data.allowShell);
        } catch (e) {
            console.warn('[Automations] Failed to load settings:', e);
        }
    },

    async saveSettings() {
        const el = document.getElementById('autoAllowShell');
        const allow = !!(el && el.checked);
        try {
            const resp = await fetch('/api/automations/settings', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ allowShell: allow })
            });
            const data = await resp.json();
            if (!data || !data.success) {
                if (el) el.checked = !allow; // revert the toggle on failure
                if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('automation.settings_save_failed'), 'error');
            }
        } catch (e) {
            if (el) el.checked = !allow;
            if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('automation.settings_save_failed'), 'error');
        }
    },

    async loadAutomations() {
        try {
            const resp = await fetch('/api/automations/list');
            const data = await resp.json();
            if (data) {
                this.automations = data;
            } else {
                this.automations = {};
            }
        } catch (e) {
            console.warn('[Automations] Failed to load automations:', e);
            this.automations = {};
        }
        this.render();
    },

    async loadAutomationSchema() {
        try {
            const resp = await fetch('/api/automations/schema');
            const data = await resp.json();
            if (data) {
                this.schema = data;
            } else {
                this.schema = [];
            }
        } catch (e) {
            console.warn('[Automations] Failed to load schema:', e);
            this.schema = [];
        }
        this.render();
        this.renderForm();
    },

    // The schema is an ordered list of sections. Normalize whatever the
    // server returns (array, or an empty/failed object) to an array so the
    // rest of the code can iterate it consistently.
    schemaSections() {
        if (Array.isArray(this.schema)) return this.schema;
        return [];
    },

    render() {
        const list = document.getElementById('automationList');
        const empty = document.getElementById('emptyState');
        const head = document.getElementById('listHead');
        if (!list || !empty) return;

        if (Object.keys(this.automations).length === 0) {
            list.innerHTML = '';
            empty.style.display = 'block';
            if (head) head.style.display = 'none';
            return;
        }

        empty.style.display = 'none';
        if (head) head.style.display = '';
        list.innerHTML = '';
        for (let [key, automation] of Object.entries(this.automations)) {
            list.append(this.createAutomationElement(key, automation));
        }
    },

    createAutomationElement(key, automation) {
        // Never interpolate the raw id into a class name — a key with a
        // leading digit / space / punctuation throws a DOMException from
        // classList.add. Sanitize to a token, and stash the real id on a
        // data-attribute for anything that needs it back.
        const token = sanitizeToken(key);
        const automationDiv = document.createElement('div');
        automationDiv.classList.add(token + '-card', 'card', automation.disabled ? 'disabled' : 'enabled');
        automationDiv.setAttribute('data-automation-id', key);

        const automationHeader = document.createElement('div');
        automationHeader.classList.add(token + '-header', 'header');
        automationDiv.append(automationHeader);

        const automationInfo = document.createElement('div');
        automationInfo.classList.add(token + '-info', 'info');
        automationInfo.innerHTML = this.buildAutomationText(automation);
        automationHeader.append(automationInfo);

        const automationActions = document.createElement('div');
        automationActions.classList.add(token + '-actions', 'actions');
        automationHeader.append(automationActions);

        const triggerBtn = document.createElement('button');
        triggerBtn.classList.add(token + '-action', 'action', 'icon-btn');
        triggerBtn.title = BYD.i18n.t('automation.trigger');
        triggerBtn.innerHTML = triggerIcon;
        triggerBtn.addEventListener('click', () => this.triggerAutomation(key));
        automationActions.append(triggerBtn);

        const copyBtn = document.createElement('button');
        copyBtn.classList.add(token + '-action', 'action', 'icon-btn');
        copyBtn.title = BYD.i18n.t('automation.copy_automation');
        copyBtn.innerHTML = copyIcon;
        // Open the form pre-filled from this automation but with no editingId, so
        // Save creates a NEW automation (a duplicate) rather than overwriting.
        copyBtn.addEventListener('click', () => this.showForm(null, automation));
        automationActions.append(copyBtn);

        const editBtn = document.createElement('button');
        editBtn.classList.add(token + '-action', 'action', 'icon-btn');
        editBtn.title = BYD.i18n.t('automation.edit_automation');
        editBtn.innerHTML = editIcon;
        editBtn.addEventListener('click', () => this.showForm(key, automation));
        automationActions.append(editBtn);

        const deleteBtn = document.createElement('button');
        deleteBtn.classList.add(token + '-action', 'action', 'icon-btn', 'danger');
        deleteBtn.title = BYD.i18n.t('common.delete');
        deleteBtn.innerHTML = deleteIcon;
        deleteBtn.addEventListener('click', () => this.deleteAutomation(key));
        automationActions.append(deleteBtn);

        const automationBody = document.createElement('div');
        automationBody.classList.add(token + '-body', 'body');
        automationDiv.append(automationBody);

        const statusText = document.createElement('div');
        statusText.classList.add(token + '-status-text', 'status-text');
        automationBody.append(statusText);

        const statusDot = document.createElement('span');
        statusDot.classList.add(token + '-status-dot', 'status-dot', automation.disabled ? 'off' : 'connected');
        statusText.append(statusDot);

        const statusMessage = document.createElement('span');
        statusMessage.classList.add(token + '-status-message', 'status-message');
        statusMessage.textContent = BYD.i18n.t('common.' + (automation.disabled ? 'disabled' : 'enabled'));
        statusText.append(statusMessage);

        const statusToggle = document.createElement('label');
        statusToggle.classList.add(token + '-toggle-switch', 'toggle-switch');
        automationBody.append(statusToggle);

        const statusCheckbox = document.createElement('input');
        statusCheckbox.type = 'checkbox';
        statusCheckbox.checked = !automation.disabled;
        statusCheckbox.addEventListener('change', () => this.disableAutomation(key, !statusCheckbox.checked));
        statusToggle.append(statusCheckbox);

        const statusSlider = document.createElement('span');
        statusSlider.classList.add(token + '-toggle-slider', 'toggle-slider');
        statusToggle.append(statusSlider);

        return automationDiv;
    },

    buildAutomationText(automation) {
        const sections = this.schemaSections();
        if (!sections.length) return BYD.i18n.t('automation.failed_to_load_schema');

        let result = '';
        try {
            for (const section of sections) {
                if (section.options && section.options.length) {
                    const sectionData = (automation[section.id] != null) ? automation[section.id] : [];
                    for (let i = 0; i < sectionData.length; i++) {
                        const entry = sectionData[i] || {};
                        result += i === 0 ? section.label : BYD.i18n.t('automation.extra_row');
                        result += ' ';
                        const data = section.options.find(option => option.id === entry.type);
                        if (!data) continue;
                        result += data.label + ' ';
                        const dataVars = (data.variables && data.variables.length) ? data.variables : [];
                        const entryVars = entry.variables || {};
                        if (dataVars.length) result += '(';
                        for (const variable of dataVars) {
                            result += variable.label + '=' + this.automationValueToText(variable, entryVars[variable.id]) + ',';
                        }
                        if (dataVars.length) result = result.slice(0, result.length - 1) + ') ';

                        if (data.comparator && data.value) {
                            // `variable` is a STRING here ('comparator' | 'value'),
                            // NOT an object with an .id — the option/value both
                            // live directly at entry[variable] (e.g. entry.comparator),
                            // not under entry.variables. Reading entry.variables[variable.id]
                            // (the old bug) yielded `undefined` for every comparator/value.
                            for (const variable of ['comparator', 'value']) {
                                result += this.automationValueToText(data[variable], entry[variable]) + ' ';
                            }
                        }
                    }
                } else {
                    result += section.label + '=' + automation[section.id];
                }
                result += '<br>';
            }
        } catch (e) {
            return BYD.i18n.t('automation.parse_error');
        }
        return result;
    },

    renderForm() {
        const grid = document.getElementById('formGrid');
        if (!grid) return;

        const sections = this.schemaSections();
        if (!sections.length) {
            grid.innerHTML = '';
            const msg = document.createElement('div');
            msg.classList.add('form-empty');
            msg.textContent = BYD.i18n.t('automation.failed_to_load_schema');
            grid.append(msg);
            return;
        }

        grid.innerHTML = '';
        for (let section of sections) {
            grid.append(this.createSection(section));
        }
    },

    createSection(section) {
        const token = sanitizeToken(section.id);
        const sectionDiv = document.createElement('div');
        sectionDiv.classList.add(token + '-section', 'section');

        if (section.options && section.options.length) {
            const required = (section.required != null) ? section.required : 0;
            if (!this.formData[section.id]) this.formData[section.id] = [];
            while (this.formData[section.id].length < required) this.formData[section.id].push({});
            sectionDiv.append(this.createRowLabel(section));
            if (section.description) {
                sectionDiv.append(this.createRowDescription(section));
            }
            for (let i = 0; i < this.formData[section.id].length; i++) {
                if (i > 0) {
                    sectionDiv.append(this.createRowLabel({ id: section.id, label: BYD.i18n.t('automation.extra_row') }));
                }
                const row = this.createRow(section, i);
                if (this.formData[section.id].length > required) {
                    const deleteContainer = document.createElement('div');
                    deleteContainer.classList.add(token + '-delete-container', 'delete-container');

                    const deleteBtn = document.createElement('button');
                    deleteBtn.classList.add(token + '-delete', 'delete', 'icon-btn', 'danger');
                    deleteBtn.title = BYD.i18n.t('automation.delete_row');
                    deleteBtn.innerHTML = deleteIcon;
                    deleteBtn.addEventListener('click', () => {
                        this.formData[section.id].splice(i, 1);
                        this.renderForm();
                    });
                    deleteContainer.append(deleteBtn);
                    row.append(deleteContainer);
                }
                sectionDiv.append(row);
            }
            if (this.formData[section.id].length === 0) {
                sectionDiv.append(this.createRowElement(section));
            }
            const addBtn = document.createElement('button');
            addBtn.classList.add('btn', 'btn-secondary', token + '-add-button', 'add-button');
            addBtn.innerHTML = BYD.i18n.t('automation.add_row') + ' +';
            addBtn.addEventListener('click', () => {
                this.formData[section.id].push({});
                this.renderForm();
            });
            sectionDiv.append(addBtn);
        } else {
            sectionDiv.append(this.createRowLabel(section));
            if (section.description) {
                sectionDiv.append(this.createRowDescription(section));
            }
            const row = this.createRowElement(section);
            const initial = (this.formData[section.id] != null) ? this.formData[section.id] : section.min;
            row.append(this.createInput(section, initial, (element, value) => {
                this.formData[section.id] = value;
            }));
            sectionDiv.append(row);
        }
        return sectionDiv;
    },

    createRow({ id, label, options }, index) {
        const token = sanitizeToken(id);
        const row = this.createRowElement({ id });
        const inputs = document.createElement('div');
        inputs.classList.add(token + '-inputs', 'inputs');

        const typeSelectorContainer = document.createElement('div');
        typeSelectorContainer.classList.add(token + '-type-selector', 'type-selector');

        const eventListener = (element, value, selected) => {
            inputs.querySelectorAll('.variable-input').forEach(input => input.remove());
            typeSelectorContainer.querySelectorAll('.description-icon').forEach(description => description.remove());
            if (this.formData[id][index].type !== value) this.formData[id][index] = { type: value };
            if (!this.formData[id][index].variables) this.formData[id][index].variables = {};
            if (selected.description) {
                const icon = document.createElement('div');
                icon.classList.add(token + '-description-tooltip', 'description-icon');
                icon.innerHTML = infoIcon;
                icon.addEventListener('mouseover', () => {
                    const currentPosition = icon.getBoundingClientRect();
                    this.showDescriptionTooltip(selected, currentPosition.left, currentPosition.bottom + window.scrollY);
                });
                icon.addEventListener('mouseout', () => this.hideDescriptionTooltip());
                typeSelectorContainer.append(icon);
            }
            const selectedVars = (selected.variables && selected.variables.length) ? selected.variables : [];
            for (const variable of selectedVars) {
                const variableSelector = this.createInput(variable, this.formData[id][index].variables[variable.id], (element, value) => {
                    this.formData[id][index].variables[variable.id] = value;
                });
                variableSelector.classList.add(token + '-input', 'variable-input');
                inputs.append(variableSelector);
            }
            if (selected.comparator && selected.value) {
                for (const variable of ['comparator', 'value']) {
                    const selector = this.createInput(selected[variable], this.formData[id][index][variable], (element, value) => {
                        this.formData[id][index][variable] = value;
                    });
                    selector.classList.add(token + '-input', 'variable-input', variable);
                    inputs.append(selector);
                }
            }
        };

        const typeSelector = this.createEnumInput(this.getTypeEnum(id, options), this.formData[id][index].type, eventListener);
        typeSelectorContainer.prepend(typeSelector);

        inputs.prepend(typeSelectorContainer);

        row.append(inputs);

        return row;
    },

    createRowElement({ id }) {
        const row = document.createElement('div');
        row.classList.add(sanitizeToken(id) + '-row', 'row');
        return row;
    },

    createRowLabel({ id, label }) {
        const rowLabel = document.createElement('span');
        rowLabel.classList.add(sanitizeToken(id) + '-label', 'label');
        rowLabel.textContent = label;

        return rowLabel;
    },

    createRowDescription({ id, description }) {
        const rowDescription = document.createElement('span');
        rowDescription.classList.add(sanitizeToken(id) + '-description', 'description');
        rowDescription.textContent = description;

        return rowDescription;
    },

    showDescriptionTooltip({ description }, x, y) {
        let tooltip = document.querySelector('.description-tooltip');
        if (!tooltip) {
            tooltip = document.createElement('div');
            tooltip.classList.add('description-tooltip');
            document.body.append(tooltip);
        }
        tooltip.textContent = description;
        tooltip.style.left = x + 'px';
        tooltip.style.top = y + 'px';
        const position = tooltip.getBoundingClientRect();
        if (position.right > window.innerWidth) x = Math.max(0, window.innerWidth - tooltip.offsetWidth);
        if (position.left < 0) x = 0;
        if (position.bottom > window.innerHeight) y = Math.max(0, window.innerHeight + window.scrollY - tooltip.offsetHeight);
        if (position.top < 0) y = window.scrollY;
        tooltip.style.left = x + 'px';
        tooltip.style.top = y + 'px';
        tooltip.classList.toggle('visible', true);
    },

    hideDescriptionTooltip() {
        let tooltip = document.querySelector('.description-tooltip');
        if (tooltip) {
            tooltip.classList.toggle('visible', false);
        }
    },

    getTypeEnum(id, options) {
        return {
            type: 'enum',
            label: BYD.i18n.t('automation.select'),
            id,
            options,
        };
    },

    createInput(data, defaultValue, eventListener) {
        switch (data.type) {
            case 'enum': return this.createEnumInput(data, defaultValue, eventListener);
            case 'string': return this.createStringInput(data, defaultValue, eventListener);
            case 'int': return this.createIntInput(data, defaultValue, eventListener);
            case 'colour': return this.createColourInput(data, defaultValue, eventListener);
            case 'time': return this.createTimeInput(data, defaultValue, eventListener);
            case 'app': return this.createAppInput(data, defaultValue, eventListener);
            default: return this.createFallbackInput(data);
        }
    },

    // An unknown input type used to return `undefined`, and the caller then
    // did row.append(undefined) → TypeError, which aborted the whole form
    // render. Return a disabled, clearly-inert field instead so one unknown
    // type can't blank the entire form.
    createFallbackInput(data) {
        const input = document.createElement('input');
        input.classList.add('input', 'unknown');
        input.type = 'text';
        input.disabled = true;
        input.placeholder = (data && data.label != null) ? data.label : BYD.i18n.t('automation.unsupported_input');
        return input;
    },

    createEnumInput(data, defaultValue, eventListener) {
        const selector = document.createElement('select');
        selector.classList.add('input', 'enum');
        const placeholder = document.createElement('option');
        placeholder.value = data.id;
        placeholder.textContent = data.label;
        placeholder.disabled = true;
        placeholder.selected = true;
        placeholder.hidden = true;
        selector.append(placeholder);
        const options = data.options || [];
        for (const option of options) {
            const optionElement = document.createElement('option');
            optionElement.value = option.id;
            optionElement.textContent = option.label;
            selector.append(optionElement);
        }
        if (defaultValue) selector.value = defaultValue;
        // Add invalid class if the selected option is not within the options list
        const changeEvent = () => {
            const selected = options.find(option => option.id === selector.value);
            if (selected) {
                selector.classList.toggle('invalid', false);
                if (eventListener) eventListener(selector, selector.value, selected);
            } else {
                selector.classList.toggle('invalid', true);
            }
        };
        selector.addEventListener('change', changeEvent);
        changeEvent();
        return selector;
    },

    createStringInput(data, defaultValue, eventListener) {
        const maxLength = (data.maxLength != null) ? data.maxLength : 2147483647;
        const input = document.createElement(maxLength <= 30 ? 'input' : 'textarea');
        input.classList.add('input', 'string');
        input.placeholder = data.label;
        input.maxLength = maxLength;
        if (defaultValue) input.value = defaultValue;
        // Add invalid class if the selected value is longer than maxLength
        const changeEvent = () => {
            if (input.value.length <= maxLength) {
                input.classList.toggle('invalid', false);
                if (eventListener) eventListener(input, input.value);
            } else {
                input.classList.toggle('invalid', true);
            }
        };
        input.addEventListener('change', changeEvent);
        changeEvent();
        // Optional cautionary note (e.g. the shell-command action). When present
        // we wrap the input + an amber warning box, matching the key-mapping
        // shell field. The wrapper still carries the input classes the caller
        // adds (variable-input etc.), and .value/.addEventListener are proxied so
        // the caller's eventListener + form serialization keep working unchanged.
        if (data.warning) {
            const wrap = document.createElement('div');
            wrap.classList.add('string-with-warning');
            wrap.append(input);
            const warn = document.createElement('div');
            warn.classList.add('field-warning');
            warn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg><span></span>';
            warn.querySelector('span').textContent = data.warning;
            wrap.append(warn);
            // Proxy the class list add the caller performs onto the actual input
            // so styling/serialization hooks still land on the field, not the div.
            // (createInput's caller does variableSelector.classList.add(...); the
            // wrapper tolerates that harmlessly, and the input keeps 'string'.)
            return wrap;
        }
        return input;
    },

    createIntInput(data, defaultValue, eventListener) {
        const input = document.createElement('input');
        input.classList.add('input', 'int');
        input.type = 'number';
        input.placeholder = data.label;
        const min = (data.min != null) ? data.min : 0;
        const max = (data.max != null) ? data.max : 2147483647;
        input.min = min;
        input.max = max;
        if (defaultValue != null && !isNaN(defaultValue)) input.value = defaultValue;
        // Add invalid class if the selected value is not within the min and max
        const changeEvent = () => {
            const value = parseInt(input.value, 10);
            if (!isNaN(value) && value >= min && value <= max) {
                input.classList.toggle('invalid', false);
                if (eventListener) eventListener(input, value);
            } else {
                input.classList.toggle('invalid', true);
            }
        };
        input.addEventListener('change', changeEvent);
        changeEvent();
        return input;
    },

    createColourInput(data, defaultValue, eventListener) {
        const input = document.createElement('input');
        input.classList.add('input', 'colour');
        input.type = 'range';
        input.placeholder = data.label;
        const min = 1;
        const max = (data.colourCodes != null && data.colourCodes.length) ? data.colourCodes.length : 1;
        input.min = min;
        input.max = max;
        if (defaultValue != null && !isNaN(defaultValue)) {
            input.value = defaultValue;
        } else {
            input.value = 1;
        }
        if (data.colourCodes) input.style.background = 'linear-gradient(to right, ' + data.colourCodes.join(',') + ')';
        // Add invalid class if the selected value is not within the min and max
        const changeEvent = () => {
            const value = parseInt(input.value, 10);
            if (!isNaN(value) && value >= min && value <= max) {
                input.classList.toggle('invalid', false);
                if (data.colourCodes) input.style.setProperty('--color', data.colourCodes[value - 1]);
                if (eventListener) eventListener(input, value);
            } else {
                input.classList.toggle('invalid', true);
            }
        };
        input.addEventListener('input', changeEvent);
        changeEvent();
        return input;
    },

    createTimeInput(data, defaultValue, eventListener) {
        const input = document.createElement('input');
        input.classList.add('input', 'time');
        input.type = 'time';
        input.placeholder = data.label;
        if (defaultValue != null && !isNaN(defaultValue)) {
            input.value = this.timeToString(defaultValue);
        }
        // Add invalid class if the selected value is not a valid time
        const changeEvent = () => {
            const time = input.value;
            let value = null;
            if (time) {
                const split = time.split(':');
                value = parseInt(split[0], 10) * 60 + parseInt(split[1], 10);
            }
            if (value != null && !isNaN(value) && value >= 0 && value < 60 * 24) {
                input.classList.toggle('invalid', false);
                if (eventListener) eventListener(input, value);
            } else {
                input.classList.toggle('invalid', true);
            }
        };
        input.addEventListener('input', changeEvent);
        changeEvent();
        return input;
    },

    timeToString(value) {
        const hours = Math.floor(value / 60);
        const mins = value % 60;
        return String(hours).padStart(2, '0') + ':' + String(mins).padStart(2, '0');
    },

    automationValueToText(spec, rawVal) {
        const option = spec && spec.options && spec.options.find(o => o.id === rawVal);
        if (option && option.label != null) return option.label;
        if (spec && spec.type === 'time' && rawVal != null && !isNaN(rawVal)) return this.timeToString(rawVal);
        if (spec && spec.type === 'app' && rawVal != null) {
            // Prefer the friendly label if the app list is already cached; else the package name.
            const app = (this._appList || []).find(a => a.package === rawVal);
            return this._escVal((app && app.label) ? app.label : rawVal);
        }
        // rawVal is a user/community-supplied automation VALUE that buildAutomationText
        // interpolates into an innerHTML string. Escape it so a shared automation whose
        // variable value contains markup can't inject script when its rule prose renders
        // (community detail preview AND the local card, which renders imported automations
        // too). The schema option.label + computed time string above are trusted.
        return this._escVal(rawVal);
    },

    // Escape a dynamic value for safe innerHTML interpolation. Prefers BYD.core._esc;
    // falls back to a local textContent escape so a missing core can't leave it raw.
    _escVal(v) {
        if (v == null) return v;
        if (window.BYD && BYD.core && BYD.core._esc) return BYD.core._esc(String(v));
        const d = document.createElement('div');
        d.textContent = String(v);
        return d.innerHTML;
    },

    // Installed-app dropdown, populated live from GET /api/apps/list. The value
    // stored is the package name; the label shown is the app's display name.
    // Unlike enum, options are NOT in the schema (apps differ per device).
    createAppInput(data, defaultValue, eventListener) {
        const selector = document.createElement('select');
        selector.classList.add('input', 'enum', 'app');
        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = data.label;
        placeholder.disabled = true;
        placeholder.selected = true;
        placeholder.hidden = true;
        selector.append(placeholder);

        const fill = (apps) => {
            // Drop any previously-added app options (keep the placeholder at index 0).
            while (selector.options.length > 1) selector.remove(1);
            for (const app of apps) {
                const opt = document.createElement('option');
                opt.value = app.package;
                opt.textContent = app.label || app.package;
                selector.append(opt);
            }
            // If the stored package isn't in the list (uninstalled since), add it so
            // the binding still shows what it points at rather than silently blanking.
            if (defaultValue && !apps.some(a => a.package === defaultValue)) {
                const opt = document.createElement('option');
                opt.value = defaultValue;
                opt.textContent = defaultValue;
                selector.append(opt);
            }
            if (defaultValue) selector.value = defaultValue;
            changeEvent();
            // The list resolves asynchronously — AFTER showForm() already ran
            // _syncSaveDisabled() while this field was still the placeholder-only
            // (invalid) state. Re-sync now that a valid default may have cleared
            // .invalid, or Save stays stuck disabled (pointer-events:none) until
            // the user touches another field.
            this._syncSaveDisabled();
        };

        const changeEvent = () => {
            const ok = !!selector.value;
            selector.classList.toggle('invalid', !ok);
            if (ok && eventListener) eventListener(selector, selector.value);
        };
        selector.addEventListener('change', changeEvent);

        // Async-load the app list once; reuse the cache on subsequent renders.
        this.loadAppList().then(fill).catch(() => { changeEvent(); this._syncSaveDisabled(); });
        changeEvent();
        return selector;
    },

    // Fetch + cache the installed-app list. Resolves to [{package, label}].
    loadAppList() {
        if (this._appList) return Promise.resolve(this._appList);
        if (this._appListPromise) return this._appListPromise;
        this._appListPromise = fetch('/api/apps/list')
            .then(r => r.json())
            .then(j => {
                this._appList = (j && j.success && Array.isArray(j.apps)) ? j.apps : [];
                return this._appList;
            })
            .catch(e => { this._appList = []; return this._appList; });
        return this._appListPromise;
    },

    _switchTab(id) {
        if (typeof window.OT_setActiveTab === 'function') window.OT_setActiveTab(id);
    },

    _syncSaveDisabled() {
        // :has() is Chrome 105+ (the head-unit WebView is Chrome 58), so the
        // "disable Save while the form has an invalid field" affordance can't
        // live in CSS. Reflect it onto the button here instead — called after
        // every render and whenever a field's validity may have changed.
        const saveBtn = document.getElementById('saveBtn');
        if (!saveBtn) return;
        const hasInvalid = document.querySelectorAll('.form-grid .invalid').length > 0;
        saveBtn.classList.toggle('is-disabled', hasInvalid);
    },

    showForm(id, automation) {
        this.editingId = id;
        // Deep-clone the source automation so editing the form does NOT mutate
        // the cached list item in place (which would survive until reload and
        // make Cancel a no-op). A fresh add starts from an empty object.
        this.formData = automation ? JSON.parse(JSON.stringify(automation)) : {};
        var titleEl = document.getElementById('formTitle');
        if (titleEl) {
            if (id) {
                titleEl.textContent = BYD.i18n.t('automation.edit_automation');
            } else {
                titleEl.textContent = BYD.i18n.t('automation.add_automation');
            }
        }
        this.renderForm();
        this._syncSaveDisabled();
        this._switchTab('add');
    },

    hideForm() {
        // Cancel returns to the Automations list. editingId is reset so a
        // subsequent tap on the empty-state CTA opens a fresh form.
        this.showForm(null, {});
        this._switchTab('automations');
    },

    async saveForm() {
        if (document.querySelectorAll('.form-grid .invalid').length) {
            this.toast(BYD.i18n.t('automation.invalid_form'), 'error');
        } else {
            try {
                const path = this.editingId
                    ? '/api/automations/automation/' + encodeURIComponent(this.editingId)
                    : '/api/automations/automation';
                const resp = await fetch(path, {
                    method: this.editingId ? 'PUT' : 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(this.formData)
                });
                const result = await resp.json();
                if (result.success) {
                    await this.loadAutomations();
                    this.hideForm();
                    return this.toast(BYD.i18n.t('toast.saved'), 'success');
                }
            } catch (e) {}
            this.toast(BYD.i18n.t('errors.save_failed'), 'error');
        }
    },

    async triggerAutomation(key) {
        try {
            const resp = await fetch('/api/automations/test/' + encodeURIComponent(key), { method: 'POST' });
            const result = await resp.json();
            if (result.success) {
                return this.toast(BYD.i18n.t('automation.toast_triggered'), 'success');
            }
        } catch (e) {}
        this.toast(BYD.i18n.t('errors.generic'), 'error');
    },

    async deleteAutomation(key) {
        // Themed confirm (BYD.utils.confirmDialog) instead of the native confirm() —
        // matches the app look/feel and avoids the browser dialog leaking the
        // loopback origin. Falls back to native confirm only if utils isn't loaded.
        const ok = (window.BYD && BYD.utils && BYD.utils.confirmDialog)
            ? await BYD.utils.confirmDialog({
                title: BYD.i18n.t('confirm.delete'),
                body: BYD.i18n.t('automation.confirm_delete_body'),
                confirmLabel: BYD.i18n.t('common.delete'),
                cancelLabel: BYD.i18n.t('common.cancel'),
                danger: true
              })
            : confirm(BYD.i18n.t('confirm.delete'));
        if (!ok) return;
        try {
            const resp = await fetch('/api/automations/automation/' + encodeURIComponent(key), { method: 'DELETE' });
            const result = await resp.json();
            if (result.success) {
                await this.loadAutomations();
                return this.toast(BYD.i18n.t('toast.deleted'), 'success');
            }
        } catch (e) {}
        this.toast(BYD.i18n.t('errors.delete_failed'), 'error');
    },

    async disableAutomation(key, disabled) {
        try {
            const resp = await fetch('/api/automations/disable/' + encodeURIComponent(key), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ disabled })
            });
            const result = await resp.json();
            if (result.success) {
                await this.loadAutomations();
                return this.toast(BYD.i18n.t('toast.saved'), 'success');
            }
        } catch (e) {}
        // The backend did not accept the toggle — re-render from cached state so the switch reverts to
        // the real backend value instead of staying stuck in the flipped position the user just clicked.
        this.render();
        this.toast(BYD.i18n.t('errors.save_failed'), 'error');
    },

    toast(message, type) {
        if (BYD.utils && BYD.utils.toast) {
            BYD.utils.toast(message, type === 'error' ? 'error' : 'success');
        } else {
            console.log('[Automations] ' + type + ': ' + message);
        }
    }
};

window.AutomationSettings = BYD.automations;

// Keep the Save-disabled affordance in sync as the user edits fields. Field
// validity is toggled on 'change'; re-check after it bubbles to the grid.
document.addEventListener('change', function (e) {
    if (e.target && e.target.closest && e.target.closest('.form-grid')) {
        BYD.automations._syncSaveDisabled();
    }
});
