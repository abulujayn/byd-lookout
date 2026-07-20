package com.overdrive.app.surveillance;

import android.content.Context;
import android.graphics.Point;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.MotionEvent;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Daemon-side touch relay: forwards a tap / swipe captured on the head-unit mirror pane
 * into the app that is projected on the NON-TOUCH driver-cluster ("fission") display,
 * restoring interactivity to a cast app.
 *
 * <h3>Why the daemon does the injection</h3>
 * {@code INJECT_EVENTS} is a signature|privileged permission granted to the shell uid out
 * of the box; the daemon runs as uid 2000 (shell) and already injects system input
 * ({@code input keyevent} for home/back/recents, wake/sleep). The app process (uid 1000,
 * not platform-signed) cannot inject, so the app POSTs normalized coordinates to the
 * daemon and this class targets the cluster display.
 *
 * <h3>Coordinates are NORMALIZED (0..1)</h3>
 * The app sends the tap position as fractions of the mirror pane (0..1 in each axis) so it
 * never has to know the cluster panel resolution (which is resolved live and varies by
 * trim). We multiply by the live fission panel size here. This keeps the transform correct
 * even if the pane is dragged/resized on the head unit.
 *
 * <h3>Safety — never clobber the head unit</h3>
 * The live fission {@code displayId} is resolved per-call from {@code dumpsys display}
 * (never hardcoded — SurfaceFlinger assigns it per projection-open and it is NOT reliably
 * 1). We HARD-REFUSE to inject unless the id is {@code > 0} (0 == head unit): a stale/absent
 * id must never fall through to display 0 and actuate the infotainment screen.
 *
 * <h3>Two injection strategies</h3>
 * Primary is the fork-free reflected {@code InputManager.injectInputEvent} +
 * {@code MotionEvent.setDisplayId(fissionId)} (no per-tap process spawn). If reflection is
 * unavailable on this build we fall back to the shell {@code input -d <id> tap|swipe}
 * (the {@code -d} display flag exists on API 29; same exec form the keymap replay uses).
 * A tap that lands on a window the projected app marks non-touchable simply no-ops — that
 * is the app's choice, identical to a head-unit tap, not a failure of this relay.
 */
public final class ClusterInputRelay {

    private static final String TAG = "ClusterInputRelay";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Injected-event mode ASYNC = 0 (INJECT_INPUT_EVENT_MODE_ASYNC). Kept as a literal
    // so we don't reflect a constant; the numeric contract is stable on API 29.
    private static final int INJECT_MODE_ASYNC = 0;
    private static final int DEFAULT_SWIPE_MS = 200;
    private static final int MAX_SWIPE_MS = 3000;

    // Cached reflected injectInputEvent handle (resolved once; null = use shell fallback).
    private static volatile Method injectMethod;      // IInputManager#injectInputEvent(InputEvent,int)
    private static volatile Object inputManagerService;
    private static volatile boolean injectResolved = false;
    private static volatile Method setDisplayIdMethod; // MotionEvent#setDisplayId(int)

    private ClusterInputRelay() {}

    /** Relay a single tap at normalized ({@code nx},{@code ny}) in [0,1] of the mirror
     *  pane. Returns true if an injection was dispatched. */
    public static boolean tap(double nx, double ny) {
        int[] panel = resolvePanel();
        if (panel == null) return false;
        int id = panel[0], w = panel[1], h = panel[2];
        float[] crop = activeCrop();
        int x = clampToPanel(paneToPanel(nx, crop[0], crop[2]), w);
        int y = clampToPanel(paneToPanel(ny, crop[1], crop[3]), h);
        if (injectTap(id, x, y)) return true;
        return shellTap(id, x, y);
    }

    /** Relay a swipe/scroll from normalized ({@code nx1},{@code ny1}) to ({@code nx2},
     *  {@code ny2}) over {@code durationMs}. Returns true if dispatched. */
    public static boolean swipe(double nx1, double ny1, double nx2, double ny2, int durationMs) {
        int[] panel = resolvePanel();
        if (panel == null) return false;
        int id = panel[0], w = panel[1], h = panel[2];
        float[] crop = activeCrop();
        int x1 = clampToPanel(paneToPanel(nx1, crop[0], crop[2]), w);
        int y1 = clampToPanel(paneToPanel(ny1, crop[1], crop[3]), h);
        int x2 = clampToPanel(paneToPanel(nx2, crop[0], crop[2]), w);
        int y2 = clampToPanel(paneToPanel(ny2, crop[1], crop[3]), h);
        int dur = durationMs <= 0 ? DEFAULT_SWIPE_MS : Math.min(durationMs, MAX_SWIPE_MS);
        if (injectSwipe(id, x1, y1, x2, y2, dur)) return true;
        return shellSwipe(id, x1, y1, x2, y2, dur);
    }

    /** Active mirror source-crop fractions [x, y, w, h] in 0..1 of the panel. In ZOOM the
     *  pane shows only this sub-window of the cluster; FIT/FILL show the whole panel so the
     *  identity {0,0,1,1} applies (a pane-normalized coord maps straight to the panel). Read
     *  from the live mirror controller; identity if it isn't running. */
    private static float[] activeCrop() {
        try {
            ClusterMirrorController c = ClusterMirrorController.getInstance();
            float[] f = c.currentSrcCropFractions();
            if (f != null && f.length == 4 && f[2] > 0f && f[3] > 0f) return f;
        } catch (Throwable ignored) {}
        return new float[] { 0f, 0f, 1f, 1f };
    }

    /** Map a pane-normalized coordinate (0..1 of what the user sees in the mirror pane)
     *  through the active source-crop window to a panel-normalized coordinate: the pane maps
     *  onto [{@code cropOffset}, {@code cropOffset}+{@code cropSpan}] of the panel. For the
     *  identity crop (FIT/FILL) this is a no-op passthrough. */
    private static double paneToPanel(double paneNorm, float cropOffset, float cropSpan) {
        if (Double.isNaN(paneNorm)) paneNorm = 0;
        if (paneNorm < 0) paneNorm = 0; else if (paneNorm > 1) paneNorm = 1;
        return cropOffset + paneNorm * cropSpan;
    }

    // ── Panel/display resolution + safety gate ──────────────────────────────────────

    /** {@code [fissionDisplayId, panelW, panelH]}, or null if no safe cluster target.
     *  HARD-REFUSES id <= 0 (0 = head unit) so injection can never hit the infotainment. */
    private static int[] resolvePanel() {
        BsNativeLayer.FissionDisplay fd = BsNativeLayer.resolveFissionDisplay();
        if (fd.displayId <= 0) {
            logger.warn("relay refused — no positive fission displayId (id=" + fd.displayId + ")");
            return null;
        }
        Context ctx = resolveContext();
        Point panel = (ctx != null) ? BsNativeLayer.clusterDisplaySize(ctx) : new Point(1920, 720);
        int w = Math.max(1, panel.x), h = Math.max(1, panel.y);
        return new int[] { fd.displayId, w, h };
    }

    private static int clampToPanel(double norm, int size) {
        if (Double.isNaN(norm)) norm = 0;
        int v = (int) Math.round(norm * size);
        if (v < 0) v = 0;
        if (v > size - 1) v = size - 1;
        return v;
    }

    // ── Primary: fork-free reflected injectInputEvent with setDisplayId ─────────────

    private static boolean injectTap(int displayId, int x, int y) {
        long now = SystemClock.uptimeMillis();
        boolean down = injectMotion(MotionEvent.ACTION_DOWN, now, now, x, y, displayId);
        if (!down) return false;
        // Small dwell so the target registers a press, then up at the same point.
        boolean up = injectMotion(MotionEvent.ACTION_UP, now, now + 60, x, y, displayId);
        return up;
    }

    private static boolean injectSwipe(int displayId, int x1, int y1, int x2, int y2, int durMs) {
        long down = SystemClock.uptimeMillis();
        if (!injectMotion(MotionEvent.ACTION_DOWN, down, down, x1, y1, displayId)) return false;
        int steps = Math.max(2, Math.min(durMs / 16, 64));   // ~60fps of moves, capped
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            int mx = Math.round(x1 + (x2 - x1) * t);
            int my = Math.round(y1 + (y2 - y1) * t);
            long ev = down + (long) (durMs * t);
            if (!injectMotion(MotionEvent.ACTION_MOVE, down, ev, mx, my, displayId)) {
                // A move failed mid-swipe — still emit the UP so we don't strand a
                // pressed pointer on the target.
                injectMotion(MotionEvent.ACTION_UP, down, down + durMs, mx, my, displayId);
                return false;
            }
        }
        return injectMotion(MotionEvent.ACTION_UP, down, down + durMs, x2, y2, displayId);
    }

    /** Build a single-pointer MotionEvent tagged with {@code displayId} and inject it.
     *  Returns false (and disables the reflected path) if reflection is unavailable. */
    private static boolean injectMotion(int action, long downTime, long eventTime,
                                        int x, int y, int displayId) {
        if (!ensureInjectResolved()) return false;
        MotionEvent ev = null;
        try {
            ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
            ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            // Route to the cluster display. If setDisplayId is missing the event would go
            // to the default display → refuse rather than tap the head unit.
            if (setDisplayIdMethod == null) return false;
            setDisplayIdMethod.invoke(ev, displayId);
            Object ret = injectMethod.invoke(inputManagerService, ev, INJECT_MODE_ASYNC);
            return !(ret instanceof Boolean) || (Boolean) ret;
        } catch (Throwable t) {
            logger.debug("injectMotion failed: " + t.getMessage());
            // Disable the reflected path for subsequent calls; shell fallback takes over.
            injectMethod = null;
            return false;
        } finally {
            if (ev != null) try { ev.recycle(); } catch (Throwable ignored) {}
        }
    }

    /** Resolve IInputManager + injectInputEvent + MotionEvent.setDisplayId ONCE. */
    private static boolean ensureInjectResolved() {
        if (injectResolved) return injectMethod != null;
        synchronized (ClusterInputRelay.class) {
            if (injectResolved) return injectMethod != null;
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Object binder = sm.getMethod("getService", String.class).invoke(null, Context.INPUT_SERVICE);
                Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
                Object iim = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                Method m = iim.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
                Method sd = MotionEvent.class.getMethod("setDisplayId", int.class);
                inputManagerService = iim;
                injectMethod = m;
                setDisplayIdMethod = sd;
                logger.info("input relay: reflected injectInputEvent resolved");
            } catch (Throwable t) {
                injectMethod = null;
                logger.info("input relay: reflected inject unavailable (" + t.getMessage()
                        + ") — using `input -d` shell fallback");
            }
            injectResolved = true;
        }
        return injectMethod != null;
    }

    // ── Fallback: shell `input -d <id> tap|swipe` ────────────────────────────────────

    private static boolean shellTap(int displayId, int x, int y) {
        return runShell("input -d " + displayId + " tap " + x + " " + y);
    }

    private static boolean shellSwipe(int displayId, int x1, int y1, int x2, int y2, int durMs) {
        return runShell("input -d " + displayId + " swipe " + x1 + " " + y1 + " "
                + x2 + " " + y2 + " " + durMs);
    }

    private static boolean runShell(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            logger.warn("input relay shell failed: " + t.getMessage());
            return false;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static Context resolveContext() {
        try {
            Class<?> cd = Class.forName("com.overdrive.app.daemon.CameraDaemon");
            Object ctx = cd.getMethod("getAppContext").invoke(null);
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable ignored) {}
        return null;
    }
}
