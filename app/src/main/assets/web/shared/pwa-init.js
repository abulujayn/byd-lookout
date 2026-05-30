/**
 * OverDrive PWA bootstrap.
 *
 * Loaded after auth.js on every dashboard page. Skips entirely on the
 * in-app WebView (loopback host) so notification permission prompts never
 * appear inside the car. On the user's external phone install, registers
 * the service worker, requests notification permission, subscribes to push,
 * and posts the resulting subscription to the head unit.
 *
 * No-op on browsers that don't support PWAs (e.g. older Android browsers).
 */
(function () {
    'use strict';

    if (typeof navigator === 'undefined') return;
    if (!('serviceWorker' in navigator)) return;

    // Dev escape hatch: ?devPwa=1 in the URL forces SW + subscribe to run on
    // localhost. Used by dev/preview-server.py — Chrome treats localhost as
    // a secure context, so the whole flow can be exercised without a real
    // tunnel, real cert, or a deployed APK.
    var devPwa = /[?&]devPwa=1\b/.test(window.location.search);

    var host = window.location.hostname;
    var isLoopback = host === '127.0.0.1' || host === 'localhost' || host === '0.0.0.0';
    if (isLoopback && !devPwa) {
        // WebView or LAN — never install a PWA against an unstable origin.
        return;
    }

    if (window.location.protocol !== 'https:' && !isLoopback) {
        // Service workers require a secure context. https:// is the normal
        // one; localhost is also accepted by Chrome/Firefox/Safari.
        return;
    }

    function log() {
        if (window.console && console.log) {
            console.log.apply(console, ['[pwa]'].concat([].slice.call(arguments)));
        }
    }

    function urlBase64ToUint8Array(b64) {
        // Web Push expects applicationServerKey as Uint8Array of the raw
        // 65-byte uncompressed P-256 point, decoded from base64url.
        var padding = '='.repeat((4 - b64.length % 4) % 4);
        var base64 = (b64 + padding).replace(/-/g, '+').replace(/_/g, '/');
        var raw = atob(base64);
        var arr = new Uint8Array(raw.length);
        for (var i = 0; i < raw.length; ++i) arr[i] = raw.charCodeAt(i);
        return arr;
    }

    function authedFetch(url, opts) {
        opts = opts || {};
        opts.headers = opts.headers || {};
        if (typeof BYDAuth !== 'undefined' && BYDAuth.getToken) {
            var t = BYDAuth.getToken();
            if (t) opts.headers['Authorization'] = 'Bearer ' + t;
        }
        return fetch(url, opts);
    }

    async function getCategoriesAndKey() {
        var r = await authedFetch('/api/notifications/categories');
        if (!r.ok) throw new Error('categories fetch ' + r.status);
        return r.json();
    }

    async function ensureSubscription(reg, vapidPublicKey) {
        var existing = await reg.pushManager.getSubscription();
        if (existing) {
            try {
                var keys = existing.options && existing.options.applicationServerKey;
                // No clean way to compare a Uint8Array vs a base64url; accept the
                // existing subscription as-is, the server will re-key it via subscribe.
                return existing;
            } catch (e) { /* fall through to resubscribe */ }
        }
        return reg.pushManager.subscribe({
            userVisibleOnly: true,
            applicationServerKey: urlBase64ToUint8Array(vapidPublicKey)
        });
    }

    async function postSubscription(sub) {
        var json = sub.toJSON();
        var label = inferLabel();
        var body = {
            endpoint: json.endpoint,
            keys: json.keys,
            label: label
        };
        var r = await authedFetch('/api/push/subscribe', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        if (!r.ok) throw new Error('subscribe POST ' + r.status);
        return r.json();
    }

    // DOMException stringifies as "[object DOMException]" in older WebViews;
    // we want the name + message so toasts read as "NotAllowedError:
    // Registration failed - push service error" rather than a generic blob.
    function errString(e) {
        if (!e) return '';
        if (e.name && e.message) return e.name + ': ' + e.message;
        if (e.name) return e.name;
        return String(e.message || e);
    }

    // Serial chain that serializes all pushManager.subscribe() + POST flows
    // in this page. Both init()'s silent resubscribe and Enable's
    // requestAndSubscribe() enqueue their work here; each caller observes
    // its own fn's return value (no result coupling), but the WORK runs
    // strictly in FIFO order. This prevents:
    //   - two concurrent pushManager.subscribe() calls (Samsung Internet
    //     has been observed to throw InvalidStateError on the second).
    //   - duplicate /api/push/subscribe POSTs from rapid Enable clicks.
    // The chain continues past errors (catch in the chain reassignment)
    // so a failed silent doesn't break a subsequent Enable. Each fn is
    // wrapped with a 30s watchdog so a hung subscribe can't block the
    // chain forever (rare on Brave/Edge with WNS connectivity issues).
    //
    // CAVEAT: do NOT call runSerial() from inside an enqueued fn — the
    // inner task would await its own outer continuation and deadlock
    // until the 30s watchdog fires. No current caller does this.
    var _subscribeChain = Promise.resolve();
    function runSerial(fn) {
        var timerId;
        var deadline = new Promise(function (_resolve, reject) {
            timerId = setTimeout(function () { reject(new Error('subscribe-timeout')); }, 30000);
        });
        // Suppress unhandledrejection if fn settles first — the deadline
        // promise will reject 30s later with no consumer otherwise.
        deadline.catch(function () {});
        function runOnce() {
            return Promise.race([fn(), deadline]).finally(function () {
                if (timerId) clearTimeout(timerId);
            });
        }
        var next = _subscribeChain.then(runOnce, runOnce);
        // Reassign the chain to the post-error continuation so a failed
        // task doesn't poison the chain.
        _subscribeChain = next.catch(function () {});
        return next;
    }

    function inferLabel() {
        // Best-effort device label from User-Agent; user can rename later.
        var ua = navigator.userAgent || '';
        if (/iPhone/i.test(ua)) return 'iPhone';
        if (/iPad/i.test(ua)) return 'iPad';
        if (/Android/i.test(ua)) {
            var m = ua.match(/Android[^;]*;\s*([^)]+)\)/);
            if (m) return m[1].trim();
            return 'Android';
        }
        return 'Browser';
    }

    async function init() {
        try {
            var reg = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
            log('SW registered, scope:', reg.scope);

            // Wait for SW to be active before subscribing.
            await navigator.serviceWorker.ready;

            // Permission flow: only auto-prompt if not blocked. If denied, we
            // stop silently; if default, we wait for an explicit user action
            // (e.g. test button on /notifications.html).
            if (Notification.permission === 'denied') {
                log('notifications denied — skipping subscribe');
                return;
            }
            if (Notification.permission === 'default') {
                // Don't prompt on every page load — only the settings page
                // should trigger this. Here we just register the SW.
                log('notifications permission not yet granted');
                return;
            }

            // Permission granted but no live PushSubscription. This happens
            // when the browser dropped the subscription (Samsung Internet
            // routinely loses subs across app updates, Brave/Edge can drop
            // when the push service connection cycles, Chrome on Android
            // re-issues after FCM token refresh). Self-heal: fetch the
            // VAPID key and resubscribe transparently so the per-device
            // toggle UI on /notifications.html accurately reflects state.
            var existing = await reg.pushManager.getSubscription();
            if (existing) {
                log('push subscription is active');
                return;
            }
            log('permission granted but no active subscription — attempting silent resubscribe');
            try {
                await runSerial(async function () {
                    // Re-check inside the serialized slot — if Enable
                    // already ran first, a sub may now exist.
                    var live = await reg.pushManager.getSubscription();
                    if (live) { log('subscription appeared before silent slot ran'); return; }
                    var meta = await getCategoriesAndKey();
                    if (!meta || !meta.vapidPublicKey) {
                        log('silent resubscribe skipped: backend has no VAPID key yet');
                        return;
                    }
                    var sub = await reg.pushManager.subscribe({
                        userVisibleOnly: true,
                        applicationServerKey: urlBase64ToUint8Array(meta.vapidPublicKey)
                    });
                    await postSubscription(sub);
                    log('silent resubscribe succeeded');
                });
            } catch (e) {
                // Don't escalate — the settings page will show the real
                // error when the user clicks Enable. Common silent-fail
                // causes: backend 503 (no Zrok yet), browser push service
                // unreachable, app not installed yet, watchdog timeout.
                log('silent resubscribe failed:', errString(e));
            }
        } catch (e) {
            log('init failed:', e && e.message ? e.message : e);
        }
    }

    // Expose minimal API for the settings page to drive permission prompts
    // and explicit (re)subscribe flow.
    window.OverdrivePush = {
        async requestAndSubscribe() {
            // Permission prompt happens OUTSIDE the serial chain — it's a
            // user-gesture-bound API and queueing it would lose the
            // gesture token on browsers that enforce it.
            var perm = await Notification.requestPermission();
            if (perm !== 'granted') return { ok: false, reason: 'permission-' + perm };
            var reg = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
            await navigator.serviceWorker.ready;
            // Run the subscribe + POST inside the serial chain so we
            // never race init()'s silent path or another rapid Enable
            // click. ensureSubscription() will return any live sub
            // produced by an earlier slot (silent or prior Enable),
            // turning duplicate clicks into a single registration.
            return runSerial(async function () {
                var meta;
                try {
                    meta = await getCategoriesAndKey();
                } catch (e) {
                    return { ok: false, reason: 'backend-unreachable', error: errString(e) };
                }
                if (!meta || !meta.vapidPublicKey) return { ok: false, reason: 'no-vapid-key' };
                // pushManager.subscribe() is the call that fails on
                // Samsung Internet without GMS, Brave with shields, and
                // Edge when WNS is unreachable. Capture the DOMException
                // name+message so the settings page can surface it
                // instead of a generic "could not enable" toast — these
                // are browser-level rejections we can't route around
                // but can at least diagnose.
                var sub;
                try {
                    sub = await ensureSubscription(reg, meta.vapidPublicKey);
                } catch (e) {
                    return { ok: false, reason: 'subscribe-failed', error: errString(e) };
                }
                try {
                    var resp = await postSubscription(sub);
                    return { ok: true, id: resp.id };
                } catch (e) {
                    return { ok: false, reason: 'register-failed', error: errString(e) };
                }
            });
        },
        async unsubscribe() {
            var reg = await navigator.serviceWorker.getRegistration();
            if (!reg) return { ok: true };
            var sub = await reg.pushManager.getSubscription();
            if (!sub) return { ok: true };
            try {
                await authedFetch('/api/push/unsubscribe', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ endpoint: sub.endpoint })
                });
            } catch (e) { /* ignore — still try to local-unsubscribe */ }
            try { await sub.unsubscribe(); } catch (e) {}
            return { ok: true };
        },
        async sendTest(severity) {
            return authedFetch('/api/push/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ severity: severity || 'info' })
            }).then(function (r) { return r.json(); });
        }
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
