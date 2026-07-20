package com.overdrive.app.ui.fragment

import android.content.Context
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.overdrive.app.R
import com.overdrive.app.ui.view.ProjectionBoundsView
import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Projection screen (Vehicle category, native car-app only — no web surface).
 *
 * Cast an installed app onto the driver-cluster ("fission") display, see a LIVE mirror of
 * it on the head unit inside a DRAGGABLE + RESIZABLE bounding rectangle, and tap/swipe the
 * mirror to control the otherwise non-touch cluster.
 *
 * ## How the pixels get here
 * The mirror is NOT drawn by this fragment. The daemon (uid 2000) composites the cluster
 * straight onto a head-unit SurfaceControl layer that floats ABOVE this window, sized to
 * the bounding rect the user sets in [bounds] (see ClusterMirrorController). This fragment:
 *   - owns the bounding rect ([ProjectionBoundsView]); on move/resize it converts the box
 *     to absolute screen px and POSTs it so the floating mirror layer tracks it live,
 *   - forwards taps/swipes (normalized to the box) to the daemon in CONTROL mode,
 *   - polls mirror status to drive the box mode + status text.
 *
 * ## Lifecycle / no-leak contract
 * Mirror starts on onResume (while a cast is active) and STOPS on onPause. All daemon calls
 * run on a background executor; UI touches are re-posted to the main thread and DROPPED once
 * the view is destroyed (via [alive]) so a slow in-flight call can never crash after detach
 * or be rejected after executor shutdown. View refs + observer listeners are cleared in
 * onDestroyView. The chosen bounds are persisted so they survive leaving/returning.
 */
class ProjectionFragment : Fragment() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var spinnerApps: Spinner? = null
    private var btnCast: Button? = null
    private var btnStop: Button? = null
    private var btnAdjust: Button? = null
    private var statusText: TextView? = null
    private var stage: View? = null
    private var bounds: ProjectionBoundsView? = null
    private var progress: ProgressBar? = null
    private var scaleGroup: MaterialButtonToggleGroup? = null
    private var autoStartSwitch: MaterialSwitch? = null

    private val appPackages = ArrayList<String>()

    @Volatile private var casting = false
    @Volatile private var mirrorMode = MODE_STOPPED
    @Volatile private var alive = false
    private var adjusting = false

    @Volatile private var scaleMode = SCALE_FIT
    // Suppress the toggle/switch change listeners while we set their state programmatically
    // (restore, conflict-dialog revert) so we don't re-fire the handler / loop the dialog.
    private var suppressScaleListener = false
    private var suppressAutoStartListener = false
    // Cached sibling state so the conflict dialog can decide without a blocking read: whether
    // RoadSense's map auto-project is currently on. Refreshed by fetchAutoStartState().
    @Volatile private var roadSenseAutoProject = false

    // Last absolute-screen rect pushed to the daemon — re-pushed only on change.
    private var lastPaneRect = intArrayOf(-1, -1, -1, -1)

    private var statusPoll: Runnable? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null

    // Latest-wins rect coalescing: a fast drag emits ~30 rects/sec, but the daemon POST can
    // take up to seconds. Instead of one io task per emit (which would back up an unbounded
    // queue and replay stale rects long after the finger lifts), keep only the NEWEST
    // pending rect and a single in-flight worker that always posts the latest value.
    private val pendingRect = java.util.concurrent.atomic.AtomicReference<IntArray?>(null)
    private val rectPosting = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_projection, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Defensive: this is a non-critical feature screen — no setup step here should ever
        // be allowed to crash the whole app. Any unexpected throw is logged + swallowed so
        // the screen degrades (blank/idle) instead of taking the process down.
        try {
            alive = true
            spinnerApps = view.findViewById(R.id.projectionAppSpinner)
            btnCast = view.findViewById(R.id.projectionCastButton)
            btnStop = view.findViewById(R.id.projectionStopButton)
            btnAdjust = view.findViewById(R.id.projectionAdjustButton)
            statusText = view.findViewById(R.id.projectionStatus)
            stage = view.findViewById(R.id.projectionStage)
            bounds = view.findViewById(R.id.projectionBounds)
            progress = view.findViewById(R.id.projectionProgress)
            scaleGroup = view.findViewById(R.id.projectionScaleGroup)
            autoStartSwitch = view.findViewById(R.id.projectionAutoStartSwitch)

            btnCast?.setOnClickListener { onCastClicked() }
            btnStop?.setOnClickListener { onStopClicked() }
            btnAdjust?.setOnClickListener { toggleAdjust() }
            wireBounds()
            wireScaleGroup()
            wireAutoStartSwitch()
            restoreScaleMode()
            restoreSavedBoxWhenLaidOut()

            loadApps()
            refreshAutoStartState()

            val vto = view.viewTreeObserver
            // Re-push the box's screen rect on layout / scroll so the floating daemon layer
            // tracks it (covers scroll, rotation, first layout).
            layoutListener = ViewTreeObserver.OnGlobalLayoutListener { pushPaneRectIfChanged() }
            scrollListener = ViewTreeObserver.OnScrollChangedListener { pushPaneRectIfChanged() }
            vto.addOnGlobalLayoutListener(layoutListener)
            vto.addOnScrollChangedListener(scrollListener)
        } catch (t: Throwable) {
            android.util.Log.e("ProjectionFragment", "onViewCreated failed", t)
        }
    }

    override fun onResume() {
        super.onResume()
        startStatusPolling()
        // Refresh the picker so an app uninstalled since we last loaded drops out of the
        // list (a long-lived fragment would otherwise keep offering a since-removed app).
        loadApps()
        refreshAutoStartState()
        submitIo {
            val active = fetchCasting()
            casting = active
            postMain { updateButtons() }
            if (active) armMirrorWhenLaidOut()
        }
    }

    override fun onPause() {
        super.onPause()
        stopStatusPolling()
        // Leave adjust mode + persist the box so it returns as the user left it.
        persistBox()
        adjusting = false
        submitIo { postMirror("stop", 0, 0, 0, 0) }
    }

    override fun onDestroyView() {
        alive = false
        stopStatusPolling()
        main.removeCallbacksAndMessages(null)
        val vto = view?.viewTreeObserver
        if (vto != null && vto.isAlive) {
            layoutListener?.let { vto.removeOnGlobalLayoutListener(it) }
            scrollListener?.let { vto.removeOnScrollChangedListener(it) }
        }
        layoutListener = null; scrollListener = null
        spinnerApps = null; btnCast = null; btnStop = null; btnAdjust = null
        statusText = null; stage = null; bounds = null; progress = null
        scaleGroup = null; autoStartSwitch = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ── Bounding-rect wiring ─────────────────────────────────────────────────────────

    private fun wireBounds() {
        val b = bounds ?: return
        // Live move/resize → convert the box (view-local px) to absolute screen px + push.
        b.onBoxChanged = { box ->
            val r = boxToScreenRect(box)
            if (r != null) {
                lastPaneRect = r
                if (casting && mirrorMode != MODE_STOPPED) {
                    pushRect(r)
                }
            }
        }
        b.onTap = { nx, ny -> submitIo { postTap(nx, ny) } }
        b.onSwipe = { nx1, ny1, nx2, ny2, ms -> submitIo { postSwipe(nx1, ny1, nx2, ny2, ms) } }
    }

    // ── Scaling mode (Fit / Fill / Zoom) ─────────────────────────────────────────────

    private fun wireScaleGroup() {
        val g = scaleGroup ?: return
        g.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressScaleListener) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.projectionScaleFill -> SCALE_FILL
                R.id.projectionScaleZoom -> SCALE_ZOOM
                else -> SCALE_FIT
            }
            if (mode == scaleMode) return@addOnButtonCheckedListener
            scaleMode = mode
            persistScaleMode(mode)
            // Fit/Zoom keep the box aspect-locked to the panel; Fill frees it so the user
            // can draw any shape to stretch into (else Fill would look identical to Fit).
            bounds?.setAspectLocked(mode != SCALE_FILL)
            // Re-push the current rect carrying the new mode so the live mirror re-scales
            // immediately (a pure mode change with no box move would otherwise do nothing).
            if (casting && mirrorMode != MODE_STOPPED) {
                val r = bounds?.currentBox()?.let { boxToScreenRect(it) }
                if (r != null) { lastPaneRect = r; submitIo { postMirror("rect", r[0], r[1], r[2], r[3], mode) } }
                else submitIo { postMirrorMode(mode) }
            }
        }
    }

    /** Reflect [scaleMode] into the toggle group + box aspect-lock without firing the
     *  listener. Called on restore and after loading the persisted mode. */
    private fun applyScaleModeToUi(mode: Int) {
        val g = scaleGroup ?: return
        val id = when (mode) {
            SCALE_FILL -> R.id.projectionScaleFill
            SCALE_ZOOM -> R.id.projectionScaleZoom
            else -> R.id.projectionScaleFit
        }
        suppressScaleListener = true
        try { g.check(id) } finally { suppressScaleListener = false }
        bounds?.setAspectLocked(mode != SCALE_FILL)
    }

    private fun restoreScaleMode() {
        val sp = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        scaleMode = sp?.getInt(K_MODE, SCALE_FIT) ?: SCALE_FIT
        applyScaleModeToUi(scaleMode)
    }

    private fun persistScaleMode(mode: Int) {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putInt(K_MODE, mode)?.apply()
    }

    // ── Auto-start on ACC-on (mutually exclusive with RoadSense map auto-project) ─────

    private fun wireAutoStartSwitch() {
        val sw = autoStartSwitch ?: return
        sw.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAutoStartListener) return@setOnCheckedChangeListener
            if (isChecked) onAutoStartEnabled() else onAutoStartDisabled()
        }
    }

    private fun setAutoStartChecked(on: Boolean) {
        val sw = autoStartSwitch ?: return
        suppressAutoStartListener = true
        try { sw.isChecked = on } finally { suppressAutoStartListener = false }
    }

    private fun onAutoStartEnabled() {
        // Need a package to auto-cast. Prefer the current spinner selection; if none, refuse.
        val idx = spinnerApps?.selectedItemPosition ?: -1
        if (idx < 0 || idx >= appPackages.size) {
            setAutoStartChecked(false)
            setStatus(getString(R.string.projection_autostart_need_app))
            return
        }
        val pkg = appPackages[idx]
        // Mutual exclusion: if RoadSense map auto-project is on, confirm handing over the
        // cluster (it can only show one thing on power-up).
        if (roadSenseAutoProject) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.projection_autostart_conflict_title)
                .setMessage(R.string.projection_autostart_conflict_body)
                .setNegativeButton(R.string.action_cancel) { _, _ -> setAutoStartChecked(false) }
                .setPositiveButton(R.string.projection_autostart_conflict_confirm) { _, _ ->
                    // Clear the sibling FIRST so there is never a both-on window, then enable us.
                    submitIo {
                        val a = postUnified("navMap", JSONObject().put("autoProjectCluster", false))
                        if (a) roadSenseAutoProject = false
                        val b = postUnified("projection", JSONObject()
                            .put("autoStartOnAcc", true).put("autoStartPackage", pkg))
                        // If the write didn't land, revert the switch so the UI matches the server.
                        if (!(a && b)) postMain { setAutoStartChecked(false) }
                    }
                }
                .setOnCancelListener { setAutoStartChecked(false) }
                .show()
            return
        }
        submitIo {
            val ok = postUnified("projection", JSONObject()
                .put("autoStartOnAcc", true).put("autoStartPackage", pkg))
            if (!ok) postMain { setAutoStartChecked(false) }
        }
    }

    private fun onAutoStartDisabled() {
        submitIo {
            val ok = postUnified("projection", JSONObject().put("autoStartOnAcc", false))
            // Revert to ON if the write failed, so the switch reflects the server.
            if (!ok) postMain { setAutoStartChecked(true) }
        }
    }

    /** Persist the last-cast package as the auto-start candidate (does NOT enable auto-start
     *  — only records which app it would cast if the toggle is on). */
    private fun persistAutoStartPackage(pkg: String) {
        postUnified("projection", JSONObject().put("autoStartPackage", pkg))
    }

    /** Read both auto-start flags from the unified config so the switch reflects the saved
     *  state and the conflict dialog knows the sibling's value. */
    private fun refreshAutoStartState() {
        submitIo {
            var projOn = false
            var mapOn = false
            try {
                val body = httpGet("/api/settings/unified")
                if (body != null) {
                    val cfg = JSONObject(body).optJSONObject("config") ?: JSONObject()
                    cfg.optJSONObject("projection")?.let { projOn = it.optBoolean("autoStartOnAcc", false) }
                    cfg.optJSONObject("navMap")?.let { mapOn = it.optBoolean("autoProjectCluster", false) }
                }
            } catch (_: Throwable) {}
            roadSenseAutoProject = mapOn
            val on = projOn
            postMain { setAutoStartChecked(on) }
        }
    }

    private fun postMirrorMode(mode: Int) {
        httpPostSuccess("/api/vehicle/cluster-mirror",
            JSONObject().put("action", "mode").put("mode", mode))
    }

    private fun postUnified(section: String, data: JSONObject): Boolean =
        httpPostSuccess("/api/settings/unified",
            JSONObject().put("section", section).put("data", data))

    private fun toggleAdjust() {
        adjusting = !adjusting
        applyBoundsMode()
        btnAdjust?.setText(
            if (adjusting) R.string.projection_done_adjusting else R.string.projection_adjust_bounds
        )
        if (adjusting) {
            setStatus(getString(R.string.projection_status_adjust))
        } else {
            // Leaving adjust: persist + push the final rect once.
            persistBox()
            val r = bounds?.currentBox()?.let { boxToScreenRect(it) }
            if (r != null && casting && mirrorMode != MODE_STOPPED) {
                lastPaneRect = r
                pushRect(r)
            }
            renderStatus(mirrorMode)
        }
    }

    /** Box mode follows: ADJUST when the user is adjusting; CONTROL when a mirror is live;
     *  IDLE otherwise. */
    private fun applyBoundsMode() {
        val b = bounds ?: return
        b.mode = when {
            adjusting -> ProjectionBoundsView.Mode.ADJUST
            mirrorMode == MODE_DIRECT || mirrorMode == MODE_STILL -> ProjectionBoundsView.Mode.CONTROL
            else -> ProjectionBoundsView.Mode.IDLE
        }
    }

    // ── App list + cast ─────────────────────────────────────────────────────────────

    private fun loadApps() {
        setBusy(true)
        submitIo {
            val (labels, pkgs) = fetchApps()
            postMain {
                appPackages.clear(); appPackages.addAll(pkgs)
                val ctx = context ?: return@postMain
                val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, labels)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerApps?.adapter = adapter
                setBusy(false); updateButtons()
            }
        }
    }

    private fun onCastClicked() {
        val idx = spinnerApps?.selectedItemPosition ?: -1
        if (idx < 0 || idx >= appPackages.size) return
        val pkg = appPackages[idx]
        setBusy(true)
        submitIo {
            val result = postCast(pkg)
            casting = result.success
            postMain {
                setBusy(false); updateButtons()
                if (!result.success) {
                    // Distinguish "app no longer installed" from a generic failure so the
                    // user knows to pick another (and refresh the picker to drop it).
                    if (result.reason == "not_installed") {
                        setStatus(getString(R.string.projection_app_uninstalled))
                        loadApps()
                    } else {
                        setStatus(getString(R.string.projection_cast_failed))
                    }
                }
            }
            if (result.success) {
                // Remember this as the auto-start candidate (last-cast-wins). Persisted to
                // the daemon-readable unified config so ACC-on auto-start can cast it.
                persistAutoStartPackage(pkg)
                try { Thread.sleep(600) } catch (_: InterruptedException) {}
                armMirrorWhenLaidOut()
            }
        }
    }

    private fun onStopClicked() {
        setBusy(true)
        adjusting = false
        submitIo {
            postStop()
            casting = false; mirrorMode = MODE_STOPPED
            postMain {
                setBusy(false); updateButtons(); applyBoundsMode()
                setStatus(getString(R.string.projection_status_idle))
            }
        }
    }

    // ── Mirror control ───────────────────────────────────────────────────────────────

    /** Start the mirror at the current box (main-thread geometry read), deferring one frame
     *  if the bounds view isn't laid out yet so onResume never loses the layout race. */
    private fun armMirrorWhenLaidOut() {
        postMain {
            val b = bounds ?: return@postMain
            val box = b.currentBox()
            val r = boxToScreenRect(box)
            if (r == null) { b.post { armMirrorWhenLaidOut() }; return@postMain }
            lastPaneRect = r
            val m = scaleMode
            submitIo { postMirror("start", r[0], r[1], r[2], r[3], m) }
        }
    }

    private fun pushPaneRectIfChanged() {
        // Runs from the layout + scroll listeners (fires immediately after first layout) —
        // on the UI thread, so any throw here would crash the app. Guarded.
        try {
            if (!alive) return
            val r = bounds?.currentBox()?.let { boxToScreenRect(it) } ?: return
            if (r[0] == lastPaneRect[0] && r[1] == lastPaneRect[1]
                && r[2] == lastPaneRect[2] && r[3] == lastPaneRect[3]) return
            lastPaneRect = r
            if (casting && mirrorMode != MODE_STOPPED) {
                pushRect(r)
            }
        } catch (t: Throwable) {
            android.util.Log.e("ProjectionFragment", "pushPaneRectIfChanged failed", t)
        }
    }

    /** Convert a box in [bounds] view-local px to absolute screen px [x,y,w,h], or null if
     *  the view isn't laid out. Main thread only (reads live View geometry).
     *
     *  The mirror is a daemon SurfaceControl layer that floats ABOVE the app window (incl.
     *  the system status/nav bars). To keep the bottom NAV BAR (and top status bar) visible
     *  on top of the mirror — "the mirror should sit below the navbar" — we CLAMP the rect
     *  to the safe content area (screen minus system-bar insets). If the box would spill
     *  into a bar, the whole rect is scaled DOWN to fit (aspect preserved, anchored at its
     *  top-left) so the mirror shrinks rather than overpainting the bar. */
    private fun boxToScreenRect(box: RectF): IntArray? {
        val b = bounds ?: return null
        if (b.width <= 0 || b.height <= 0) return null
        if (box.width() < 1f || box.height() < 1f) return null
        val loc = IntArray(2)
        b.getLocationOnScreen(loc)
        var x = loc[0] + box.left.toInt()
        var y = loc[1] + box.top.toInt()
        var w = box.width().toInt()
        var h = box.height().toInt()

        // Safe area = full display minus system-bar insets (top status, bottom nav, sides).
        val dm = b.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        var top = 0; var bottom = screenH; var left = 0; var right = screenW
        try {
            val insets = b.rootWindowInsets
            if (insets != null) {
                @Suppress("DEPRECATION")
                run {
                    top = insets.systemWindowInsetTop
                    left = insets.systemWindowInsetLeft
                    right = screenW - insets.systemWindowInsetRight
                    bottom = screenH - insets.systemWindowInsetBottom
                }
            }
        } catch (_: Throwable) {}

        // Shift fully-outside origins into the safe area first.
        if (x < left) x = left
        if (y < top) y = top
        // Scale down (aspect-preserving) so the rect fits within [left,top,right,bottom],
        // anchored at (x,y). Only shrinks; never enlarges.
        val availW = (right - x).coerceAtLeast(1)
        val availH = (bottom - y).coerceAtLeast(1)
        if (w > availW || h > availH) {
            val sx = availW.toFloat() / w
            val sy = availH.toFloat() / h
            val s = minOf(sx, sy, 1f)
            w = (w * s).toInt().coerceAtLeast(1)
            h = (h * s).toInt().coerceAtLeast(1)
        }
        return intArrayOf(x, y, w, h)
    }

    // ── Status polling ───────────────────────────────────────────────────────────────

    private fun startStatusPolling() {
        stopStatusPolling()
        val r = object : Runnable {
            override fun run() {
                if (!alive) return
                submitIo {
                    val st = fetchStatus()
                    mirrorMode = st.mode
                    postMain {
                        // Lock the bounds box to the REAL cluster panel aspect the daemon
                        // reports (not a hardcoded 8:3), so the mirror is never stretched on
                        // any trim. Only re-apply on change (avoids re-clamping mid-drag).
                        if (st.panelW > 0 && st.panelH > 0) {
                            val ar = st.panelW.toFloat() / st.panelH.toFloat()
                            bounds?.applyAspectRatio(ar)
                        }
                        renderStatus(st.mode)
                    }
                }
                main.postDelayed(this, STATUS_POLL_MS)
            }
        }
        statusPoll = r
        main.postDelayed(r, STATUS_POLL_MS)
    }

    private fun stopStatusPolling() {
        statusPoll?.let { main.removeCallbacks(it) }
        statusPoll = null
    }

    private fun renderStatus(mode: Int) {
        // Don't stomp the "drag to move" hint while the user is actively adjusting.
        if (!adjusting) {
            when (mode) {
                MODE_DIRECT -> setStatus(getString(R.string.projection_status_live))
                MODE_STILL -> setStatus(getString(R.string.projection_status_preview))
                MODE_NO_PROJECTION -> setStatus(getString(R.string.projection_status_none))
                MODE_UNSUPPORTED -> setStatus(getString(R.string.projection_status_unsupported))
                else -> setStatus(getString(R.string.projection_status_idle))
            }
        }
        applyBoundsMode()
        // Adjust is available only when a mirror is actually showing.
        btnAdjust?.isEnabled = mode == MODE_DIRECT || mode == MODE_STILL
        if ((mode == MODE_DIRECT || mode == MODE_STILL)) pushPaneRectIfChanged()
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────────

    private fun updateButtons() {
        btnCast?.isEnabled = !casting && appPackages.isNotEmpty()
        btnStop?.isEnabled = casting
        if (!casting) btnAdjust?.isEnabled = false
    }

    private fun setStatus(text: String) { statusText?.text = text }
    private fun setBusy(busy: Boolean) { progress?.visibility = if (busy) View.VISIBLE else View.GONE }

    private fun postMain(block: () -> Unit) {
        if (!alive) return
        main.post {
            if (!alive) return@post
            // Guard every main-thread callback (status render, aspect apply, button/state
            // updates): a non-critical feature screen must never crash the whole app on an
            // unexpected throw. Log + swallow so it degrades instead.
            try { block() } catch (t: Throwable) {
                android.util.Log.e("ProjectionFragment", "main callback failed", t)
            }
        }
    }

    private fun submitIo(block: () -> Unit) {
        try { if (!io.isShutdown) io.execute(block) } catch (_: RejectedExecutionException) {}
    }

    /** Coalesced rect push: store the newest rect and ensure exactly one worker is draining
     *  it, so a slow daemon can't accumulate a backlog of stale intermediate rects. */
    private fun pushRect(r: IntArray) {
        pendingRect.set(r)
        if (rectPosting.compareAndSet(false, true)) {
            submitIo {
                try {
                    while (true) {
                        val next = pendingRect.getAndSet(null) ?: break
                        postMirror("rect", next[0], next[1], next[2], next[3])
                    }
                } finally {
                    rectPosting.set(false)
                    // A rect that arrived between the last drain and clearing the flag must
                    // not be stranded — re-drain if one appeared.
                    if (pendingRect.get() != null && rectPosting.compareAndSet(false, true)) {
                        submitIo {
                            try {
                                while (true) {
                                    val next = pendingRect.getAndSet(null) ?: break
                                    postMirror("rect", next[0], next[1], next[2], next[3])
                                }
                            } finally { rectPosting.set(false) }
                        }
                    }
                }
            }
        }
    }

    // ── Box persistence (survives leave/return + app restart) ───────────────────────

    private fun restoreSavedBoxWhenLaidOut() {
        val b = bounds ?: return
        b.post {
            val sp = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return@post
            if (!sp.contains(K_L)) return@post   // no saved box → keep the view's default
            b.setNormalizedBox(
                sp.getFloat(K_L, 0f), sp.getFloat(K_T, 0f),
                sp.getFloat(K_W, 1f), sp.getFloat(K_H, 0f)
            )
        }
    }

    private fun persistBox() {
        val n = bounds?.normalizedBox() ?: return
        val sp = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        sp.edit().putFloat(K_L, n[0]).putFloat(K_T, n[1])
            .putFloat(K_W, n[2]).putFloat(K_H, n[3]).apply()
    }

    // ── Daemon HTTP (all off-main-thread) ───────────────────────────────────────────

    private fun fetchApps(): Pair<List<String>, List<String>> {
        val labels = ArrayList<String>(); val pkgs = ArrayList<String>()
        try {
            val body = httpGet("/api/vehicle/cluster-apps") ?: return Pair(labels, pkgs)
            val arr = JSONObject(body).optJSONArray("apps") ?: return Pair(labels, pkgs)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pkg = o.optString("package", "")
                if (pkg.isEmpty()) continue
                pkgs.add(pkg); labels.add(o.optString("label", pkg))
            }
        } catch (_: Throwable) {}
        return Pair(labels, pkgs)
    }

    private fun fetchCasting(): Boolean = try {
        val body = httpGet("/api/vehicle/cluster-mirror-status") ?: return false
        JSONObject(body).optBoolean("casting", false)
    } catch (_: Throwable) { false }

    private class Status(val mode: Int, val panelW: Int, val panelH: Int)

    private fun fetchStatus(): Status = try {
        val body = httpGet("/api/vehicle/cluster-mirror-status") ?: return Status(MODE_STOPPED, 0, 0)
        val j = JSONObject(body)
        Status(j.optInt("mode", MODE_STOPPED), j.optInt("panelW", 0), j.optInt("panelH", 0))
    } catch (_: Throwable) { Status(MODE_STOPPED, 0, 0) }

    private class CastResult(val success: Boolean, val reason: String)

    private fun postCast(pkg: String): CastResult {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open("/api/vehicle/cluster-cast", "POST", 2000, 4000)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val os: OutputStream = conn.outputStream
            os.write(JSONObject().put("package", pkg).toString().toByteArray()); os.flush(); os.close()
            if (conn.responseCode != 200) return CastResult(false, "")
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(resp)
            CastResult(j.optBoolean("success", false), j.optString("reason", ""))
        } catch (_: Throwable) { CastResult(false, "") } finally { conn?.disconnect() }
    }

    private fun postStop() { httpPostSuccess("/api/vehicle/cluster-stop", JSONObject()) }

    private fun postMirror(action: String, x: Int, y: Int, w: Int, h: Int, mode: Int = scaleMode) {
        httpPostSuccess("/api/vehicle/cluster-mirror",
            JSONObject().put("action", action).put("x", x).put("y", y)
                .put("w", w).put("h", h).put("mode", mode))
    }

    private fun postTap(nx: Double, ny: Double) {
        httpPostSuccess("/api/vehicle/cluster-touch",
            JSONObject().put("type", "tap").put("x", nx).put("y", ny))
    }

    private fun postSwipe(nx1: Double, ny1: Double, nx2: Double, ny2: Double, ms: Int) {
        httpPostSuccess("/api/vehicle/cluster-touch", JSONObject().put("type", "swipe")
            .put("x", nx1).put("y", ny1).put("x2", nx2).put("y2", ny2).put("ms", ms))
    }

    private fun httpGet(path: String): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open(path, "GET", 2000, 4000)
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) { null } finally { conn?.disconnect() }
    }

    private fun httpPostSuccess(path: String, body: JSONObject): Boolean {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open(path, "POST", 2000, 4000)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val os: OutputStream = conn.outputStream
            os.write(body.toString().toByteArray()); os.flush(); os.close()
            if (conn.responseCode != 200) return false
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(resp).optBoolean("success", false)
        } catch (_: Throwable) { false } finally { conn?.disconnect() }
    }

    companion object {
        // Mirror modes — MUST match ClusterMirrorController.MODE_*.
        private const val MODE_STOPPED = 0
        private const val MODE_DIRECT = 1
        private const val MODE_STILL = 2
        private const val MODE_NO_PROJECTION = 3
        private const val MODE_UNSUPPORTED = 4

        // Scaling modes — MUST match ClusterMirrorController.SCALE_*.
        private const val SCALE_FIT = 0
        private const val SCALE_FILL = 1
        private const val SCALE_ZOOM = 2

        private const val STATUS_POLL_MS = 1500L

        private const val PREFS = "projection_prefs"
        private const val K_L = "box_l"
        private const val K_T = "box_t"
        private const val K_W = "box_w"
        private const val K_H = "box_h"
        private const val K_MODE = "scale_mode"
    }
}
