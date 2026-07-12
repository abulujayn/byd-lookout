/*
 * Overdrive — Community Automations UI controller (browse / publish / import).
 *
 * A sibling module to automations.js, mounted on the same automations.html page as
 * a third "Community" tab (data-tab="community"). It talks ONLY to the local daemon
 * routes /api/community/* (loopback) — never the cloud directly; the daemon proxies
 * to the open-source community-edge Cloudflare backend.
 *
 * ES5 / Chrome 58 floor (BYD DiLink head-unit WebView) — SAME constraints as
 * automations.js: NO optional chaining (?.) / nullish (??) / Array.flat /
 * String.replaceAll / class fields. const/let, arrow fns, template literals,
 * for...of, fetch, Promise are fine. Mutating calls (POST/DELETE) MUST use fetch()
 * (XHR bodies are dropped on this WebView). Untrusted text (author names, titles,
 * descriptions from other users) is rendered via textContent / BYD.core._esc,
 * NEVER raw innerHTML.
 */

window.BYD = window.BYD || {};

// Inline Lucide-style SVGs (no emoji — M3 icon rule). 24x24 viewBox, currentColor.
var C_addIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14"/><path d="M5 12h14"/></svg>';
var C_publishIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12"/><path d="m8 7 4-4 4 4"/><path d="M8 21H5a2 2 0 0 1-2-2v-4"/><path d="M21 15v4a2 2 0 0 1-2 2h-3"/></svg>';
var C_trashIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>';
var C_flagIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z"/><line x1="4" x2="4" y1="22" y2="15"/></svg>';
var C_starFull = '<svg viewBox="0 0 24 24" fill="currentColor" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>';
var C_starEmpty = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>';

// Filter facets — MUST match community-edge worker CATEGORIES + i18n keys community.cat_*.
var C_CATEGORIES = ['all', 'comfort', 'climate', 'lighting', 'security', 'driving', 'energy', 'other'];

BYD.communityAutomations = {
    // Browse list paging state (mirrors events.js: pageSize/currentPage/totalPages).
    pageSize: 12,
    currentPage: 1,
    totalPages: 1,
    total: 0,
    sort: 'updated',   // updated | created | rating | downloads
    order: 'desc',
    category: 'all',
    q: '',
    minRating: 0,      // 0 = any; 1..5 filters on live average
    minDownloads: 0,   // 0 = any; else minimum import count
    _searchTimer: null,
    _loading: false,

    init: function () {
        this.renderControls();
        // Load the catalog immediately on page init (matches every other tab's
        // controller — the browse grid is on its own tab, hidden until selected, so
        // there's no cost to fetching now and it guarantees data is present the first
        // time the tab is shown, including on a reload while the community tab is
        // the restored-active one). A previous "defer until tab switch" optimization
        // regressed reload (app-tabs uses class 'is-active' not 'active', and its
        // change event only fires on user click, never on restore) — so just load.
        this.loadPage(1);
        // Also refresh whenever the community tab becomes active again, so a user
        // switching back sees fresh ratings/downloads without a manual reload.
        var self = this;
        document.addEventListener('ot-tabs:active-changed', function (ev) {
            var id = (ev && ev.detail) ? ev.detail.id : null;
            if (id === 'community') self.loadPage(self.currentPage || 1);
        });
        // Augment each existing automation card (rendered by automations.js) with a
        // Publish button. Two mechanisms, both idempotent via the data-community-augmented
        // guard: (1) _hookLocalRender wraps BYD.automations.render so the Publish button is
        // re-added after EVERY re-render (import / enable-disable / edit / delete all rebuild
        // #automationList and would otherwise drop it); (2) _scheduleAugment polls for the
        // initial async render in case it lands before the hook is installed.
        this._hookLocalRender();
        this._scheduleAugment();
    },

    // Wrap BYD.automations.render so our Publish-button augmentation survives every
    // list rebuild. render() clears + repopulates #automationList, discarding the
    // augmented buttons and their markers, so we re-augment right after it runs.
    _hookLocalRender: function () {
        if (!window.BYD || !BYD.automations || typeof BYD.automations.render !== 'function') return;
        if (BYD.automations._communityRenderHooked) return;
        var self = this;
        var origRender = BYD.automations.render.bind(BYD.automations);
        BYD.automations.render = function () {
            origRender();
            self._augmentLocalCards();
        };
        BYD.automations._communityRenderHooked = true;
    },

    // ── Browse: controls (sort / filter chips / search) ───────────────────────

    renderControls: function () {
        var host = document.getElementById('communityControls');
        if (!host) return;
        var t = this._t;

        // Search box.
        var searchWrap = document.createElement('div');
        searchWrap.className = 'community-search';
        var search = document.createElement('input');
        search.type = 'search';
        search.className = 'community-search-input';
        search.setAttribute('data-i18n-attr', 'placeholder:community.search_placeholder');
        search.placeholder = t('community.search_placeholder');
        var self = this;
        search.addEventListener('input', function () {
            // Debounce so we don't fire a request per keystroke on the head unit.
            if (self._searchTimer) clearTimeout(self._searchTimer);
            var val = search.value || '';
            self._searchTimer = setTimeout(function () {
                self.q = val.trim();
                self.loadPage(1);
            }, 350);
        });
        searchWrap.appendChild(search);
        host.appendChild(searchWrap);

        // Sort select (net-new themed <select>, styled off .form-grid select).
        var sortRow = document.createElement('div');
        sortRow.className = 'community-sort-row';
        var sortLabel = document.createElement('label');
        sortLabel.className = 'community-sort-label';
        sortLabel.setAttribute('data-i18n', 'community.sort_by');
        sortLabel.textContent = t('community.sort_by');
        sortRow.appendChild(sortLabel);

        var sortSel = document.createElement('select');
        sortSel.className = 'community-sort-select';
        var sortOpts = [
            ['updated', 'community.sort_updated'],
            ['rating', 'community.sort_rating'],
            ['downloads', 'community.sort_downloads'],
            ['created', 'community.sort_created']
        ];
        for (var i = 0; i < sortOpts.length; i++) {
            var o = document.createElement('option');
            o.value = sortOpts[i][0];
            o.setAttribute('data-i18n', sortOpts[i][1]);
            o.textContent = t(sortOpts[i][1]);
            sortSel.appendChild(o);
        }
        sortSel.value = this.sort;
        sortSel.addEventListener('change', function () {
            self.sort = sortSel.value;
            self.loadPage(1);
        });
        sortRow.appendChild(sortSel);
        host.appendChild(sortRow);

        // Minimum-rating + minimum-downloads FILTER selects (distinct from sort:
        // sort orders the whole catalog; these trim it). Compact, side by side.
        var filterRow = document.createElement('div');
        filterRow.className = 'community-filter-row';

        // Min rating.
        var ratingWrap = document.createElement('div');
        ratingWrap.className = 'community-minfilter';
        var ratingLbl = document.createElement('label');
        ratingLbl.className = 'community-sort-label';
        ratingLbl.setAttribute('data-i18n', 'community.min_rating');
        ratingLbl.textContent = t('community.min_rating');
        ratingWrap.appendChild(ratingLbl);
        var ratingSel = document.createElement('select');
        ratingSel.className = 'community-sort-select';
        var ratingOpts = [['0', 'community.min_any'], ['3', 'community.min_rating_3'], ['4', 'community.min_rating_4'], ['5', 'community.min_rating_5']];
        for (var ri = 0; ri < ratingOpts.length; ri++) {
            var ro = document.createElement('option');
            ro.value = ratingOpts[ri][0];
            ro.setAttribute('data-i18n', ratingOpts[ri][1]);
            ro.textContent = t(ratingOpts[ri][1]);
            ratingSel.appendChild(ro);
        }
        ratingSel.value = String(this.minRating);
        ratingSel.addEventListener('change', function () {
            self.minRating = parseInt(ratingSel.value, 10) || 0;
            self.loadPage(1);
        });
        ratingWrap.appendChild(ratingSel);
        filterRow.appendChild(ratingWrap);

        // Min downloads.
        var dlWrap = document.createElement('div');
        dlWrap.className = 'community-minfilter';
        var dlLbl = document.createElement('label');
        dlLbl.className = 'community-sort-label';
        dlLbl.setAttribute('data-i18n', 'community.min_downloads');
        dlLbl.textContent = t('community.min_downloads');
        dlWrap.appendChild(dlLbl);
        var dlSel = document.createElement('select');
        dlSel.className = 'community-sort-select';
        var dlOpts = [['0', 'community.min_any'], ['10', 'community.min_dl_10'], ['50', 'community.min_dl_50'], ['100', 'community.min_dl_100']];
        for (var di = 0; di < dlOpts.length; di++) {
            var dopt = document.createElement('option');
            dopt.value = dlOpts[di][0];
            dopt.setAttribute('data-i18n', dlOpts[di][1]);
            dopt.textContent = t(dlOpts[di][1]);
            dlSel.appendChild(dopt);
        }
        dlSel.value = String(this.minDownloads);
        dlSel.addEventListener('change', function () {
            self.minDownloads = parseInt(dlSel.value, 10) || 0;
            self.loadPage(1);
        });
        dlWrap.appendChild(dlSel);
        filterRow.appendChild(dlWrap);
        host.appendChild(filterRow);

        // Category filter chips (reuse the events.js .filter-chip pattern).
        var chipRow = document.createElement('div');
        chipRow.className = 'community-filter-chips';
        for (var c = 0; c < C_CATEGORIES.length; c++) {
            (function (cat) {
                var chip = document.createElement('button');
                chip.className = 'filter-chip' + (cat === self.category ? ' active' : '');
                chip.setAttribute('data-community-cat', cat);
                chip.setAttribute('data-i18n', 'community.cat_' + cat);
                chip.textContent = self._t('community.cat_' + cat);
                chip.addEventListener('click', function () {
                    self.category = cat;
                    var all = chipRow.querySelectorAll('.filter-chip');
                    for (var k = 0; k < all.length; k++) all[k].classList.remove('active');
                    chip.classList.add('active');
                    self.loadPage(1);
                });
                chipRow.appendChild(chip);
            })(C_CATEGORIES[c]);
        }
        host.appendChild(chipRow);
    },

    // ── Browse: load + render a page ──────────────────────────────────────────

    loadPage: function (page) {
        var self = this;
        this.currentPage = page < 1 ? 1 : page;
        this._loading = true;
        this._renderState('loading');

        var params = [];
        params.push('sort=' + encodeURIComponent(this.sort));
        params.push('order=' + encodeURIComponent(this.order));
        params.push('page=' + this.currentPage);
        params.push('pageSize=' + this.pageSize);
        if (this.category && this.category !== 'all') params.push('category=' + encodeURIComponent(this.category));
        if (this.q) params.push('q=' + encodeURIComponent(this.q));
        if (this.minRating > 0) params.push('minRating=' + this.minRating);
        if (this.minDownloads > 0) params.push('minDownloads=' + this.minDownloads);
        var qs = params.join('&');

        fetch('/api/community/list?' + qs, { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                self._loading = false;
                if (!data || data.error || !Array.isArray(data.items)) {
                    self._renderState('error', data && data.error);
                    return;
                }
                self.total = data.total || 0;
                self.totalPages = data.totalPages || 1;
                self.currentPage = data.page || self.currentPage;
                self.renderList(data.items);
            })
            .catch(function () {
                self._loading = false;
                self._renderState('error');
            });
    },

    renderList: function (items) {
        var grid = document.getElementById('communityList');
        if (!grid) return;
        grid.innerHTML = '';

        if (!items.length) {
            this._renderState('empty');
            this.renderPagination();
            return;
        }
        var empty = document.getElementById('communityEmpty');
        if (empty) empty.style.display = 'none';

        for (var i = 0; i < items.length; i++) {
            grid.appendChild(this.createCard(items[i]));
        }
        this.renderPagination();
    },

    createCard: function (item) {
        var self = this;
        var esc = BYD.core._esc.bind(BYD.core);
        var t = this._t;

        var card = document.createElement('div');
        card.className = 'card community-card';
        card.setAttribute('data-community-id', item.id);

        // Header: name + author + category badge.
        var head = document.createElement('div');
        head.className = 'community-card-head';

        var titleWrap = document.createElement('div');
        titleWrap.className = 'community-card-titlewrap';
        var name = document.createElement('div');
        name.className = 'community-card-name';
        name.textContent = item.name || '';
        titleWrap.appendChild(name);
        var author = document.createElement('div');
        author.className = 'community-card-author';
        // "by {author}" — build with textContent parts, never innerHTML.
        author.textContent = t('community.by_author').replace('{name}', item.authorName || '—');
        titleWrap.appendChild(author);
        head.appendChild(titleWrap);

        var badge = document.createElement('span');
        badge.className = 'community-badge community-cat-' + this._catToken(item.category);
        badge.setAttribute('data-i18n', 'community.cat_' + (item.category || 'other'));
        badge.textContent = t('community.cat_' + (item.category || 'other'));
        head.appendChild(badge);
        card.appendChild(head);

        // Description (clamped by CSS).
        if (item.description) {
            var desc = document.createElement('div');
            desc.className = 'community-card-desc';
            desc.textContent = item.description;
            card.appendChild(desc);
        }

        // Meta row: static star display + counts + updated-at.
        var meta = document.createElement('div');
        meta.className = 'community-card-meta';
        meta.appendChild(this._staticStars(item.ratingAvg || 0, item.ratingCount || 0));

        var dl = document.createElement('span');
        dl.className = 'community-meta-chip';
        dl.textContent = t('community.downloads_n').replace('{n}', item.downloadCount || 0);
        meta.appendChild(dl);

        var upd = document.createElement('span');
        upd.className = 'community-meta-chip';
        // Show the CREATION time (immutable) — never updatedMs, which could move on
        // a content change and confuse "when was this shared". Ratings/installs already
        // don't bump the server's updated_ms; created_ms is the honest "shared on" stamp.
        upd.textContent = this._relTime(item.createdMs);
        meta.appendChild(upd);
        card.appendChild(meta);

        // Actions: Add (import) + View/Rate.
        var actions = document.createElement('div');
        actions.className = 'community-card-actions';

        var addBtn = document.createElement('button');
        addBtn.className = 'btn btn-primary community-add-btn';
        addBtn.innerHTML = C_addIcon + '<span data-i18n="community.add">' + esc(t('community.add')) + '</span>';
        addBtn.addEventListener('click', function () { self.importAutomation(item.id, item.name); });
        actions.appendChild(addBtn);

        var viewBtn = document.createElement('button');
        viewBtn.className = 'btn btn-secondary community-view-btn';
        viewBtn.setAttribute('data-i18n', 'community.view_details');
        viewBtn.textContent = t('community.view_details');
        viewBtn.addEventListener('click', function () { self.openDetail(item.id); });
        actions.appendChild(viewBtn);

        card.appendChild(actions);
        return card;
    },

    // ── Pagination (events.js pattern) ─────────────────────────────────────────

    renderPagination: function () {
        var host = document.getElementById('communityPagination');
        if (!host) return;
        var self = this;
        host.innerHTML = '';
        if (this.totalPages <= 1) { host.style.display = 'none'; return; }
        host.style.display = '';

        var prev = document.createElement('button');
        prev.className = 'pagination-btn';
        prev.setAttribute('data-i18n', 'community.prev');
        prev.textContent = this._t('community.prev');
        prev.disabled = this.currentPage <= 1;
        prev.addEventListener('click', function () { if (self.currentPage > 1) self.loadPage(self.currentPage - 1); });
        host.appendChild(prev);

        var info = document.createElement('span');
        info.className = 'pagination-info';
        info.textContent = this._t('community.page_of')
            .replace('{page}', this.currentPage).replace('{total}', this.totalPages);
        host.appendChild(info);

        var next = document.createElement('button');
        next.className = 'pagination-btn';
        next.setAttribute('data-i18n', 'community.next');
        next.textContent = this._t('community.next');
        next.disabled = this.currentPage >= this.totalPages;
        next.addEventListener('click', function () { if (self.currentPage < self.totalPages) self.loadPage(self.currentPage + 1); });
        host.appendChild(next);
    },

    // ── Detail modal: rule-prose preview + capability badge + rate + import ────

    openDetail: function (id) {
        var self = this;
        fetch('/api/community/automation/' + encodeURIComponent(id), { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (!data || data.error || !data.automation) {
                    self._toast(self._t('community.load_failed'), 'error');
                    return;
                }
                self._showDetailModal(data.automation);
            })
            .catch(function () { self._toast(self._t('community.load_failed'), 'error'); });
    },

    _showDetailModal: function (a) {
        var self = this;
        var t = this._t;
        var backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop';
        backdrop.style.display = 'flex';
        var card = document.createElement('div');
        card.className = 'modal-card community-detail-card';
        card.setAttribute('role', 'dialog');

        var h = document.createElement('h3');
        h.className = 'soh-modal-title';
        h.textContent = a.name || '';
        card.appendChild(h);

        var by = document.createElement('div');
        by.className = 'community-detail-author';
        by.textContent = t('community.by_author').replace('{name}', a.authorName || '—');
        card.appendChild(by);

        if (a.description) {
            var d = document.createElement('p');
            d.className = 'community-detail-desc';
            d.textContent = a.description;
            card.appendChild(d);
        }

        // Human-readable rule preview — reuse automations.js buildAutomationText so
        // the prose matches the local editor exactly (falls back to a note if the
        // schema hasn't loaded yet).
        var preview = document.createElement('div');
        preview.className = 'community-rule-preview';
        var proseHtml = '';
        try {
            if (window.BYD && BYD.automations && typeof BYD.automations.buildAutomationText === 'function' && a.rules) {
                proseHtml = BYD.automations.buildAutomationText(a.rules);
            }
        } catch (e) { proseHtml = ''; }
        // buildAutomationText returns pre-escaped label text from the schema (trusted,
        // server-provided i18n labels) — safe to set as HTML. If empty, show a note.
        preview.innerHTML = proseHtml || BYD.core._esc(t('community.no_preview'));
        card.appendChild(preview);

        // Compatibility badge (from the daemon's per-vehicle capability enrichment).
        var compat = document.createElement('div');
        compat.className = 'community-compat ' + (a.compatible ? 'ok' : 'warn');
        if (a.compatible) {
            compat.textContent = t('community.compatible');
        } else {
            var unsupported = (a.unsupportedActions && a.unsupportedActions.length)
                ? a.unsupportedActions.join(', ') : '';
            compat.textContent = t('community.incompatible').replace('{actions}', unsupported);
        }
        card.appendChild(compat);

        // Rating widget — tap a star to rate (one vote/device, server-deduped).
        var rateWrap = document.createElement('div');
        rateWrap.className = 'community-rate-wrap';
        var rateLabel = document.createElement('span');
        rateLabel.className = 'community-rate-label';
        rateLabel.setAttribute('data-i18n', 'community.rate_this');
        rateLabel.textContent = t('community.rate_this');
        rateWrap.appendChild(rateLabel);
        rateWrap.appendChild(this._interactiveStars(a.id, a));
        card.appendChild(rateWrap);

        // Actions: Add (import) / Report / Close.
        var actions = document.createElement('div');
        actions.className = 'soh-modal-actions community-detail-actions';

        // Delete-my-upload: only the publishing device can delete (server device-match).
        // Ownership isn't in the record, so we always offer it and let the server decide
        // — a non-owner gets a friendly "not yours" toast, never a false success.
        var delBtn = document.createElement('button');
        delBtn.className = 'btn btn-danger community-unpublish-btn';
        delBtn.innerHTML = C_trashIcon + '<span data-i18n="community.unpublish">' + BYD.core._esc(t('community.unpublish')) + '</span>';
        delBtn.addEventListener('click', function () { self.unpublish(a.id, a.name, dismiss); });
        actions.appendChild(delBtn);

        var reportBtn = document.createElement('button');
        reportBtn.className = 'btn btn-secondary community-report-btn';
        reportBtn.innerHTML = C_flagIcon + '<span data-i18n="community.report">' + BYD.core._esc(t('community.report')) + '</span>';
        reportBtn.addEventListener('click', function () { self.reportAutomation(a.id); });
        actions.appendChild(reportBtn);

        var closeBtn = document.createElement('button');
        closeBtn.className = 'btn btn-secondary';
        closeBtn.setAttribute('data-i18n', 'common.close');
        closeBtn.textContent = t('common.close');
        closeBtn.addEventListener('click', function () { dismiss(); });
        actions.appendChild(closeBtn);

        var addBtn = document.createElement('button');
        addBtn.className = 'btn btn-primary';
        addBtn.innerHTML = C_addIcon + '<span data-i18n="community.add">' + BYD.core._esc(t('community.add')) + '</span>';
        addBtn.addEventListener('click', function () { dismiss(); self.importAutomation(a.id, a.name); });
        actions.appendChild(addBtn);

        card.appendChild(actions);
        backdrop.appendChild(card);
        backdrop.addEventListener('click', function (ev) { if (ev.target === backdrop) dismiss(); });
        document.body.appendChild(backdrop);

        function dismiss() { try { backdrop.remove(); } catch (e) {} }
    },

    // ── Import (Add) ────────────────────────────────────────────────────────────

    importAutomation: function (id, name) {
        var self = this;
        BYD.utils.confirmDialog({
            title: self._t('community.add_title'),
            body: self._t('community.add_body').replace('{name}', name || ''),
            confirmLabel: self._t('community.add'),
            cancelLabel: self._t('common.cancel')
        }).then(function (ok) {
            if (!ok) return;
            fetch('/api/community/import/' + encodeURIComponent(id), { method: 'POST' })
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    if (data && data.success) {
                        self._toast(self._t('community.added_disabled'), 'success');
                        // Refresh the local list so the newly-imported (disabled)
                        // automation shows on the Automations tab immediately.
                        if (window.BYD && BYD.automations && BYD.automations.loadAutomations) {
                            BYD.automations.loadAutomations();
                        }
                    } else {
                        self._toast((data && data.error) || self._t('community.add_failed'), 'error');
                    }
                })
                .catch(function () { self._toast(self._t('community.add_failed'), 'error'); });
        });
    },

    // ── Publish (augmented onto existing automation cards) ──────────────────────

    _scheduleAugment: function () {
        var self = this;
        var tries = 0;
        var iv = setInterval(function () {
            tries++;
            var done = self._augmentLocalCards();
            if (done || tries > 20) clearInterval(iv);
        }, 250);
    },

    // Add a "Publish" icon button to each local automation card's action row.
    // Returns true once at least one card was found (list has rendered).
    _augmentLocalCards: function () {
        var cards = document.querySelectorAll('#automationList [data-automation-id]');
        if (!cards.length) return false;
        var self = this;
        for (var i = 0; i < cards.length; i++) {
            var cardEl = cards[i];
            if (cardEl.getAttribute('data-community-augmented') === '1') continue;
            var actionRow = cardEl.querySelector('.actions');
            if (!actionRow) continue;
            var id = cardEl.getAttribute('data-automation-id');
            var pubBtn = document.createElement('button');
            pubBtn.className = 'action icon-btn community-publish-btn';
            pubBtn.title = self._t('community.publish');
            pubBtn.innerHTML = C_publishIcon;
            (function (automationId) {
                pubBtn.addEventListener('click', function () { self.openPublish(automationId); });
            })(id);
            // Insert publish as the first action (before trigger/copy/edit/delete).
            actionRow.insertBefore(pubBtn, actionRow.firstChild);
            cardEl.setAttribute('data-community-augmented', '1');
        }
        return true;
    },

    // Publish modal: collect author / name / description / category, then POST the
    // local automation's blob to the daemon (which blocks shell + re-validates).
    openPublish: function (localId) {
        var self = this;
        var t = this._t;
        // The local automation blob (== the rules payload) is already cached by
        // automations.js from /api/automations/list.
        var rules = null;
        if (window.BYD && BYD.automations && BYD.automations.automations) {
            rules = BYD.automations.automations[localId];
        }
        if (!rules) { self._toast(t('community.publish_no_source'), 'error'); return; }

        // Guard: refuse to even open the publish form for a shell automation (the
        // daemon + Worker also block it; this is the friendly first line).
        if (self._hasShell(rules)) {
            BYD.utils.alertDialog({ title: t('community.publish'), body: t('community.publish_shell_blocked') });
            return;
        }

        var backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop';
        backdrop.style.display = 'flex';
        var card = document.createElement('div');
        card.className = 'modal-card community-publish-card';
        card.setAttribute('role', 'dialog');

        var h = document.createElement('h3');
        h.className = 'soh-modal-title';
        h.setAttribute('data-i18n', 'community.publish_title');
        h.textContent = t('community.publish_title');
        card.appendChild(h);

        var form = document.createElement('div');
        form.className = 'community-publish-form';

        var authorInput = this._field(form, 'community.field_author', 'text', 40);  // match worker MAX_AUTHOR_LEN
        // Prefill the remembered author name.
        this._prefillAuthor(authorInput);
        var nameInput = this._field(form, 'community.field_name', 'text', 60);
        var descInput = this._field(form, 'community.field_desc', 'textarea', 500);

        // Category select.
        var catWrap = document.createElement('div');
        catWrap.className = 'community-publish-row';
        var catLabel = document.createElement('label');
        catLabel.className = 'label';
        catLabel.setAttribute('data-i18n', 'community.field_category');
        catLabel.textContent = t('community.field_category');
        catWrap.appendChild(catLabel);
        var catSel = document.createElement('select');
        catSel.className = 'community-sort-select';
        for (var i = 1; i < C_CATEGORIES.length; i++) { // skip 'all'
            var o = document.createElement('option');
            o.value = C_CATEGORIES[i];
            o.setAttribute('data-i18n', 'community.cat_' + C_CATEGORIES[i]);
            o.textContent = t('community.cat_' + C_CATEGORIES[i]);
            catSel.appendChild(o);
        }
        catWrap.appendChild(catSel);
        form.appendChild(catWrap);
        card.appendChild(form);

        var actions = document.createElement('div');
        actions.className = 'soh-modal-actions';
        var cancelBtn = document.createElement('button');
        cancelBtn.className = 'btn btn-secondary';
        cancelBtn.setAttribute('data-i18n', 'common.cancel');
        cancelBtn.textContent = t('common.cancel');
        cancelBtn.addEventListener('click', function () { dismiss(); });
        actions.appendChild(cancelBtn);

        var pubBtn = document.createElement('button');
        pubBtn.className = 'btn btn-primary';
        pubBtn.innerHTML = C_publishIcon + '<span data-i18n="community.publish">' + BYD.core._esc(t('community.publish')) + '</span>';
        pubBtn.addEventListener('click', function () {
            var author = (authorInput.value || '').trim();
            var name = (nameInput.value || '').trim();
            if (!author) { self._toast(t('community.err_author'), 'error'); return; }
            if (!name) { self._toast(t('community.err_name'), 'error'); return; }
            pubBtn.disabled = true;
            var payload = {
                authorName: author,
                name: name,
                description: (descInput.value || '').trim(),
                category: catSel.value,
                rules: rules
            };
            fetch('/api/community/publish', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            }).then(function (r) { return r.json(); })
              .then(function (data) {
                  if (data && (data.id || data.success)) {
                      dismiss();
                      self._toast(t('community.published'), 'success');
                      self.loadPage(1);
                  } else {
                      pubBtn.disabled = false;
                      self._toast((data && data.error) || t('community.publish_failed'), 'error');
                  }
              }).catch(function () {
                  pubBtn.disabled = false;
                  self._toast(t('community.publish_failed'), 'error');
              });
        });
        actions.appendChild(pubBtn);
        card.appendChild(actions);
        backdrop.appendChild(card);
        backdrop.addEventListener('click', function (ev) { if (ev.target === backdrop) dismiss(); });
        document.body.appendChild(backdrop);
        try { nameInput.focus(); } catch (e) {}

        function dismiss() { try { backdrop.remove(); } catch (e) {} }
    },

    // Delete an automation THIS device published (server enforces author_device match).
    unpublish: function (id, name, closeModal) {
        var self = this;
        BYD.utils.confirmDialog({
            title: self._t('community.unpublish_title'),
            body: self._t('community.unpublish_body').replace('{name}', name || ''),
            confirmLabel: self._t('community.unpublish'),
            cancelLabel: self._t('common.cancel'),
            danger: true
        }).then(function (ok) {
            if (!ok) return;
            fetch('/api/community/automation/' + encodeURIComponent(id), { method: 'DELETE' })
                .then(function (r) { return r.json().then(function (d) { return { status: r.status, d: d }; }); })
                .then(function (res) {
                    if (res.d && (res.d.ok || res.d.deleted)) {
                        self._toast(self._t('community.unpublished'), 'success');
                        if (typeof closeModal === 'function') closeModal();
                        self.loadPage(self.currentPage);
                    } else if (res.status === 404) {
                        // Server said not-owner (or already gone) — this device didn't publish it.
                        self._toast(self._t('community.unpublish_not_owner'), 'error');
                    } else {
                        self._toast((res.d && res.d.error) || self._t('errors.generic'), 'error');
                    }
                })
                .catch(function () { self._toast(self._t('errors.generic'), 'error'); });
        });
    },

    reportAutomation: function (id) {
        var self = this;
        BYD.utils.confirmDialog({
            title: self._t('community.report_title'),
            body: self._t('community.report_body'),
            confirmLabel: self._t('community.report'),
            cancelLabel: self._t('common.cancel'),
            danger: true
        }).then(function (ok) {
            if (!ok) return;
            fetch('/api/community/report/' + encodeURIComponent(id), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ reason: 'user-report' })
            }).then(function (r) { return r.json(); })
              .then(function (data) {
                  if (data && data.ok) self._toast(self._t('community.reported'), 'success');
                  else self._toast((data && data.error) || self._t('errors.generic'), 'error');
              }).catch(function () { self._toast(self._t('errors.generic'), 'error'); });
        });
    },

    // ── Star widgets ────────────────────────────────────────────────────────────

    _staticStars: function (avg, count) {
        var wrap = document.createElement('span');
        wrap.className = 'community-stars static';
        var rounded = Math.round(avg);
        for (var i = 1; i <= 5; i++) {
            var s = document.createElement('span');
            s.className = 'community-star';
            s.innerHTML = i <= rounded ? C_starFull : C_starEmpty;
            wrap.appendChild(s);
        }
        var cnt = document.createElement('span');
        cnt.className = 'community-star-count';
        cnt.textContent = count ? ('(' + count + ')') : this._t('community.no_ratings');
        wrap.appendChild(cnt);
        return wrap;
    },

    _interactiveStars: function (id, a) {
        var self = this;
        var wrap = document.createElement('span');
        wrap.className = 'community-stars interactive';
        var current = Math.round(a.ratingAvg || 0);
        function paint(n) {
            var stars = wrap.querySelectorAll('.community-star');
            for (var k = 0; k < stars.length; k++) {
                stars[k].innerHTML = (k < n) ? C_starFull : C_starEmpty;
            }
        }
        for (var i = 1; i <= 5; i++) {
            (function (val) {
                var s = document.createElement('button');
                s.className = 'community-star star-btn';
                s.innerHTML = val <= current ? C_starFull : C_starEmpty;
                s.title = String(val);
                s.addEventListener('click', function () {
                    paint(val);
                    self._submitRating(id, val, wrap);
                });
                wrap.appendChild(s);
            })(i);
        }
        return wrap;
    },

    _submitRating: function (id, stars, wrap) {
        var self = this;
        fetch('/api/community/rate/' + encodeURIComponent(id), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ stars: stars })
        }).then(function (r) { return r.json(); })
          .then(function (data) {
              if (data && data.ok) {
                  self._toast(self._t('community.rating_saved'), 'success');
                  // Reflect the new average in any open count label.
                  var cnt = wrap.parentNode ? wrap.parentNode.querySelector('.community-star-count') : null;
                  if (cnt && data.ratingCount != null) cnt.textContent = '(' + data.ratingCount + ')';
              } else {
                  self._toast((data && data.error) || self._t('errors.generic'), 'error');
              }
          }).catch(function () { self._toast(self._t('errors.generic'), 'error'); });
    },

    // ── State + small helpers ─────────────────────────────────────────────────

    _renderState: function (kind, msg) {
        var grid = document.getElementById('communityList');
        var empty = document.getElementById('communityEmpty');
        if (!grid || !empty) return;
        if (kind === 'loading') {
            if (!grid.children.length) grid.innerHTML = '<div class="community-loading" data-i18n="community.loading">' + BYD.core._esc(this._t('community.loading')) + '</div>';
            empty.style.display = 'none';
            return;
        }
        if (kind === 'empty') {
            grid.innerHTML = '';
            empty.style.display = 'block';
            var emptyMsg = document.getElementById('communityEmptyMsg');
            var filtered = this.q || this.category !== 'all' || this.minRating > 0 || this.minDownloads > 0;
            if (emptyMsg) emptyMsg.textContent = this._t(filtered ? 'community.empty_filtered' : 'community.empty');
            return;
        }
        if (kind === 'error') {
            grid.innerHTML = '';
            empty.style.display = 'block';
            var em = document.getElementById('communityEmptyMsg');
            if (em) em.textContent = msg || this._t('community.load_failed');
        }
    },

    _hasShell: function (rules) {
        if (!rules || !rules.actions) return false;
        for (var i = 0; i < rules.actions.length; i++) {
            var ty = rules.actions[i] && rules.actions[i].type ? String(rules.actions[i].type).toLowerCase() : '';
            if (ty.indexOf('shell') !== -1) return true;
        }
        return false;
    },

    _field: function (form, i18nKey, kind, maxlen) {
        var row = document.createElement('div');
        row.className = 'community-publish-row';
        var label = document.createElement('label');
        label.className = 'label';
        label.setAttribute('data-i18n', i18nKey);
        label.textContent = this._t(i18nKey);
        row.appendChild(label);
        var input = kind === 'textarea' ? document.createElement('textarea') : document.createElement('input');
        if (kind !== 'textarea') input.type = 'text';
        input.className = 'community-publish-input';
        if (maxlen) input.maxLength = maxlen;
        row.appendChild(input);
        form.appendChild(row);
        return input;
    },

    _prefillAuthor: function (input) {
        fetch('/api/community/settings', { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (data) { if (data && data.authorName) input.value = data.authorName; })
            .catch(function () {});
    },

    _catToken: function (cat) {
        return String(cat || 'other').replace(/[^a-zA-Z0-9_-]/g, '_');
    },

    // Relative "updated" time. Uses i18n templates; falls back to a date string.
    _relTime: function (ms) {
        if (!ms) return '';
        var now = Date.now();
        var diff = Math.max(0, now - ms);
        var min = 60 * 1000, hr = 60 * min, day = 24 * hr;
        if (diff < 2 * min) return this._t('community.time_recent');
        if (diff < hr) return this._t('community.time_minutes').replace('{n}', Math.floor(diff / min));
        if (diff < day) return this._t('community.time_hours').replace('{n}', Math.floor(diff / hr));
        if (diff < 30 * day) return this._t('community.time_days').replace('{n}', Math.floor(diff / day));
        try { return new Date(ms).toLocaleDateString(); } catch (e) { return ''; }
    },

    _t: function (key) {
        if (window.BYD && BYD.i18n && BYD.i18n.t) return BYD.i18n.t(key);
        return key;
    },

    _toast: function (msg, type) {
        if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, type === 'error' ? 'error' : 'success');
    }
};

window.CommunityAutomations = BYD.communityAutomations;
