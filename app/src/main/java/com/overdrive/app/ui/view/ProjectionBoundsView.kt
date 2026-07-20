package com.overdrive.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Draggable + resizable bounding rectangle for the cluster mirror.
 *
 * The daemon composites the live cluster mirror onto a head-unit SurfaceControl layer that
 * floats ABOVE the app window at [box]'s on-screen rect. Because that layer sits above this
 * view, anything drawn INSIDE the box is occluded by the live mirror — so the resize handles
 * are drawn just OUTSIDE the corners (always visible beside the mirror), and the box change
 * is pushed LIVE so the mirror itself grows / shrinks / moves under the finger as feedback.
 *
 * Two interaction modes:
 *  - [Mode.CONTROL]: taps / swipes inside the box are forwarded (normalized to the box) via
 *    [onTap] / [onSwipe] so the projected app is interactive. No handles drawn.
 *  - [Mode.ADJUST]: drag a corner to resize (aspect-locked to [aspectRatio] so the mirror is
 *    never stretched), or drag the interior to move. [onBoxChanged] fires live (the caller
 *    repositions the daemon layer). Handles + a dim scrim outside the box are drawn.
 *  - [Mode.IDLE]: nothing interactive (no mirror up); a faint outline placeholder only.
 *
 * The box is kept in this view's local coordinate space. The caller converts to absolute
 * screen pixels (add the view's location-on-screen) before handing geometry to the daemon.
 */
class ProjectionBoundsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { IDLE, CONTROL, ADJUST }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            activeCorner = -1
            moving = false
            invalidate()
        }

    /** Width : height the box is locked to when resizing. Defaults to the Seal cluster
     *  (1920×720 = 8:3); the fragment overrides it with the REAL panel aspect the daemon
     *  reports via {@link #setAspectRatio}, so the mirror is never stretched on other trims. */
    var aspectRatio: Float = 8f / 3f

    /** Whether the box keeps [aspectRatio] when resized. FIT/ZOOM lock it (the mirror
     *  fills a panel-shaped pane cleanly); FILL (stretch) UNLOCKS it so the user can draw
     *  any rectangle to stretch into — otherwise a panel-shaped box makes FILL look
     *  identical to FIT. When unlocked, resize sizes width/height independently and neither
     *  clampBox nor setNormalizedBox re-imposes the panel aspect. */
    private var aspectLocked = true

    fun setAspectLocked(locked: Boolean) {
        if (aspectLocked == locked) return
        aspectLocked = locked
        // When (re)locking, snap the current box back to the panel aspect so it's clean;
        // when unlocking, leave the box as-is (the user will reshape it).
        if (locked && boxInitialized && activeCorner == -1 && !moving && width > 0 && height > 0) {
            val w = box.width()
            box.set(box.left, box.top, box.left + w, box.top + w / aspectRatio)
            clampBox()
            invalidate()
            onBoxChanged?.invoke(RectF(box))
        }
    }

    /** Update the aspect ratio to the real cluster panel's. If it actually changed and we're
     *  not mid-drag, re-fit the current box to the new ratio (keeps top-left, preserves
     *  width) so the placeholder + mirror match the panel shape immediately. No-op if the
     *  ratio is unchanged or invalid. */
    fun applyAspectRatio(ar: Float) {
        if (ar <= 0f || ar.isNaN()) return
        if (kotlin.math.abs(ar - aspectRatio) < 0.001f) return
        aspectRatio = ar
        // In FILL (unlocked) mode the box is a free shape — don't re-impose the panel aspect
        // on a live panel-aspect status poll (that would snap the user's box back).
        if (!aspectLocked) return
        if (boxInitialized && activeCorner == -1 && !moving && width > 0 && height > 0) {
            val w = box.width()
            box.set(box.left, box.top, box.left + w, box.top + w / aspectRatio)
            clampBox()
            invalidate()
            onBoxChanged?.invoke(RectF(box))
        }
    }

    /** Fires live while the box moves/resizes in ADJUST mode (box is in view-local px). */
    var onBoxChanged: ((RectF) -> Unit)? = null
    /** CONTROL-mode discrete tap, normalized (0..1) within the box. */
    var onTap: ((nx: Double, ny: Double) -> Unit)? = null
    /** CONTROL-mode swipe, normalized (0..1) within the box, with duration ms. */
    var onSwipe: ((nx1: Double, ny1: Double, nx2: Double, ny2: Double, ms: Int) -> Unit)? = null

    private val box = RectF()
    private var boxInitialized = false

    // ── paints ──
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); color = ACCENT
    }
    private val idleOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
        color = Color.argb(0x66, 0xFF, 0xFF, 0xFF)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = ACCENT
    }
    private val handleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); color = Color.WHITE
    }
    private val scrim = Paint().apply { color = Color.argb(0x80, 0x00, 0x00, 0x00) }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0xB0, 0xFF, 0xFF, 0xFF); textAlign = Paint.Align.CENTER
        textSize = dp(13f)
    }

    private val handleRadius = dp(11f)
    private val handleOffset = dp(9f)     // push handle centers OUTWARD so they clear the mirror
    private val touchSlop = dp(28f)
    private val minBoxW = dp(120f)
    private val minBoxH = dp(60f)   // only used in the unlocked (FILL) resize path

    // ── interaction state ──
    private var activeCorner = -1         // 0=TL 1=TR 2=BL 3=BR, -1=none
    private var moving = false
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastEmit = 0L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        try {
            if (!boxInitialized && w > 0 && h > 0) {
                initDefaultBox(w, h)
                boxInitialized = true
            } else if (w > 0 && h > 0) {
                clampBox()   // keep valid across rotation / relayout
            }
        } catch (t: Throwable) {
            android.util.Log.e("ProjectionBoundsView", "onSizeChanged failed", t)
        }
    }

    /** Default: full-width-ish box centered, aspect-locked, leaving room for handles. */
    private fun initDefaultBox(w: Int, h: Int) {
        val margin = handleOffset + handleRadius + dp(4f)
        var bw = w - 2 * margin
        var bh = bw / aspectRatio
        if (bh > h - 2 * margin) { bh = h - 2 * margin; bw = bh * aspectRatio }
        val left = (w - bw) / 2f
        val top = (h - bh) / 2f
        box.set(left, top, left + bw, top + bh)
    }

    // ── drawing ──
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try { drawInternal(canvas) } catch (t: Throwable) {
            android.util.Log.e("ProjectionBoundsView", "onDraw failed", t)
        }
    }

    private fun drawInternal(canvas: Canvas) {
        if (!boxInitialized) return
        when (mode) {
            Mode.IDLE -> {
                canvas.drawRoundRect(box, dp(6f), dp(6f), idleOutline)
                canvas.drawText(context.getString(
                    com.overdrive.app.R.string.projection_bounds_hint_idle),
                    box.centerX(), box.centerY(), hint)
            }
            Mode.CONTROL -> {
                // Transparent — the live mirror shows through; we only capture touches.
            }
            Mode.ADJUST -> {
                // Dim everything OUTSIDE the box to focus the frame (inside is the live
                // mirror, which is above us anyway). Four rects around the hole.
                canvas.drawRect(0f, 0f, width.toFloat(), box.top, scrim)
                canvas.drawRect(0f, box.bottom, width.toFloat(), height.toFloat(), scrim)
                canvas.drawRect(0f, box.top, box.left, box.bottom, scrim)
                canvas.drawRect(box.right, box.top, width.toFloat(), box.bottom, scrim)
                canvas.drawRect(box, outline)
                // Corner handles, centers pushed diagonally OUTWARD so they're visible
                // beside the mirror layer that occludes the box interior.
                drawHandle(canvas, box.left - handleOffset, box.top - handleOffset)
                drawHandle(canvas, box.right + handleOffset, box.top - handleOffset)
                drawHandle(canvas, box.left - handleOffset, box.bottom + handleOffset)
                drawHandle(canvas, box.right + handleOffset, box.bottom + handleOffset)
            }
        }
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, handleRadius, handleFill)
        canvas.drawCircle(cx, cy, handleRadius, handleRim)
    }

    // ── touch ──
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return try {
            when (mode) {
                Mode.ADJUST -> handleAdjustTouch(event)
                Mode.CONTROL -> handleControlTouch(event)
                Mode.IDLE -> false
            }
        } catch (t: Throwable) {
            android.util.Log.e("ProjectionBoundsView", "onTouchEvent failed", t)
            false
        }
    }

    private fun handleAdjustTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeCorner = cornerAt(event.x, event.y)
                moving = activeCorner == -1 && box.contains(event.x, event.y)
                lastX = event.x; lastY = event.y
                val grab = activeCorner != -1 || moving
                // Hold the gesture: without this the enclosing NestedScrollView intercepts
                // the first vertical MOVE (touch-slop) and CANCELs our drag/resize.
                if (grab) parent?.requestDisallowInterceptTouchEvent(true)
                return grab
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCorner != -1) {
                    resizeToCorner(activeCorner, event.x, event.y)
                    emitBox(); invalidate(); return true
                }
                if (moving) {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    lastX = event.x; lastY = event.y
                    box.offset(dx, dy); clampBox()
                    emitBox(); invalidate(); return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeCorner != -1 || moving) {
                    activeCorner = -1; moving = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onBoxChanged?.invoke(RectF(box))   // final, unthrottled
                    return true
                }
            }
        }
        return false
    }

    private fun handleControlTouch(event: MotionEvent): Boolean {
        if (!box.contains(event.x, event.y) && event.actionMasked == MotionEvent.ACTION_DOWN) {
            return false   // touch outside the mirror — let it pass (e.g. scroll)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downT = System.currentTimeMillis()
                // Hold the gesture so a vertical swipe isn't stolen by the scroller before
                // ACTION_UP (where onSwipe fires).
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val bw = box.width().coerceAtLeast(1f)
                val bh = box.height().coerceAtLeast(1f)
                val nx1 = ((downX - box.left) / bw).toDouble().coerceIn(0.0, 1.0)
                val ny1 = ((downY - box.top) / bh).toDouble().coerceIn(0.0, 1.0)
                val nx2 = ((event.x - box.left) / bw).toDouble().coerceIn(0.0, 1.0)
                val ny2 = ((event.y - box.top) / bh).toDouble().coerceIn(0.0, 1.0)
                val dist = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())
                val dt = (System.currentTimeMillis() - downT).toInt()
                if (dist > touchSlop) onSwipe?.invoke(nx1, ny1, nx2, ny2, dt.coerceIn(50, 1500))
                else onTap?.invoke(nx1, ny1)
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    /** Which corner (if any) the point is grabbing, accounting for the outward offset. */
    private fun cornerAt(x: Float, y: Float): Int {
        val pts = arrayOf(
            floatArrayOf(box.left - handleOffset, box.top - handleOffset),
            floatArrayOf(box.right + handleOffset, box.top - handleOffset),
            floatArrayOf(box.left - handleOffset, box.bottom + handleOffset),
            floatArrayOf(box.right + handleOffset, box.bottom + handleOffset)
        )
        for (i in pts.indices) {
            if (hypot((x - pts[i][0]).toDouble(), (y - pts[i][1]).toDouble()) <= touchSlop) return i
        }
        return -1
    }

    /** Resize keeping [aspectRatio], anchored at the diagonally-opposite corner. */
    private fun resizeToCorner(corner: Int, px: Float, py: Float) {
        val ax: Float; val ay: Float   // anchor (opposite corner)
        when (corner) {
            0 -> { ax = box.right; ay = box.bottom }   // dragging TL
            1 -> { ax = box.left;  ay = box.bottom }   // dragging TR
            2 -> { ax = box.right; ay = box.top }      // dragging BL
            else -> { ax = box.left; ay = box.top }    // dragging BR
        }
        // Candidate size from the pointer. When aspect-locked (FIT/ZOOM), couple w/h by the
        // dominant axis; when unlocked (FILL), size the two axes independently so the user
        // can draw any rectangle.
        var w = abs(px - ax)
        var h = abs(py - ay)
        if (aspectLocked) {
            if (w / h > aspectRatio) h = w / aspectRatio else w = h * aspectRatio
            if (w < minBoxW) { w = minBoxW; h = w / aspectRatio }
        } else {
            if (w < minBoxW) w = minBoxW
            if (h < minBoxH) h = minBoxH
        }
        // Clamp so the box (plus handle offset) stays inside the view. Clamp to the
        // available room on the drag side even when it is BELOW minBoxW — a box that can't
        // fit its minimum on that side must still not overflow the view (overflow would put
        // a handle off-screen / mis-place the mirror). The min-size preference above yields
        // to the hard in-view bound here.
        val maxW = (if (px < ax) ax else width - ax) - (handleOffset + handleRadius)
        if (maxW > 0 && w > maxW) { w = maxW; if (aspectLocked) h = w / aspectRatio }
        val maxH = (if (py < ay) ay else height - ay) - (handleOffset + handleRadius)
        if (maxH > 0 && h > maxH) { h = maxH; if (aspectLocked) w = h * aspectRatio }
        val newLeft = if (px < ax) ax - w else ax
        val newTop = if (py < ay) ay - h else ay
        box.set(newLeft, newTop, newLeft + w, newTop + h)
    }

    private fun clampBox() {
        val margin = handleOffset + handleRadius
        val w = box.width().coerceAtMost(width - 2 * margin)
        // Locked (FIT/ZOOM): height follows the panel aspect. Unlocked (FILL): keep the
        // box's own height (a free shape), only clamped to fit the view.
        val h = if (aspectLocked) w / aspectRatio
                else box.height().coerceAtMost(height - 2 * margin)
        var left = box.left.coerceIn(margin, width - margin - w)
        var top = box.top.coerceIn(margin, height - margin - h)
        if (left.isNaN()) left = margin
        if (top.isNaN()) top = margin
        box.set(left, top, left + w, top + h)
    }

    /** Live emit while dragging, throttled so we don't flood the daemon with rect ops. */
    private fun emitBox() {
        val now = System.currentTimeMillis()
        if (now - lastEmit < EMIT_THROTTLE_MS) return
        lastEmit = now
        onBoxChanged?.invoke(RectF(box))
    }

    /** Current box in view-local px (copy). */
    fun currentBox(): RectF = RectF(box)

    /** Restore a box from a normalized (0..1 of this view) rect; clamped. When aspect-locked
     *  the height is derived from [aspectRatio]; when unlocked (FILL) the stored normalized
     *  height is honored so a free-shaped box round-trips (otherwise it would snap back to
     *  the panel aspect on restore). */
    fun setNormalizedBox(nl: Float, nt: Float, nw: Float, nh: Float) {
        if (width <= 0 || height <= 0) return
        val w = (nw * width)
        val left = nl * width
        val top = nt * height
        val h = if (aspectLocked) w / aspectRatio else (nh * height)
        box.set(left, top, left + w, top + h)
        clampBox()
        boxInitialized = true
        invalidate()
    }

    /** Current box as a normalized (0..1 of this view) rect, for persistence. */
    fun normalizedBox(): FloatArray {
        if (width <= 0 || height <= 0) return floatArrayOf(0f, 0f, 1f, 1f)
        return floatArrayOf(box.left / width, box.top / height,
            box.width() / width, box.height() / height)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private val ACCENT = Color.rgb(0x00, 0xD4, 0xAA)   // matches RoiDrawingView accent
        private const val EMIT_THROTTLE_MS = 33L           // ~30fps live rect updates
    }
}
