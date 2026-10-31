package org.chimeramc.client.launcher.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.chimeramc.client.core.mods.inbuilt.overlay.CursorController;
import org.chimeramc.client.ui.components.UiDraw;

/**
 * The macOS-style controller cursor for launcher screens (Phase 12).
 *
 * <p>The in-game menus already drive a cursor, so a controller user had a pointer in the game and
 * none on the launcher home — the screen they see first. This overlay is that missing pointer: a
 * full-bleed, non-interactive child of the content root that paints {@link UiDraw#pointerCursor}
 * at the position the shared {@link CursorController} integrates from the stick.
 *
 * <p>It reuses the in-game cursor rather than re-implementing one, so the dead zone, the response
 * curve, the clamping and the sign convention cannot drift between the two. Selection is the same
 * mechanism too: a synthesised tap dispatched to the content root, so a controller "click" lands
 * on exactly the view a finger would have hit and no per-screen wiring is needed.
 *
 * <p>It never consumes a touch ({@code onTouchEvent} returns false), so touch and pad coexist: a
 * real finger hides the pointer and drives the screen as before. With no pad in use the pointer is
 * hidden and the view draws nothing.
 */
public final class LauncherCursorOverlay extends View {

    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final CursorController cursor;

    private boolean visible;
    private long lastMs;

    /**
     * True while {@link #select()} is dispatching its synthesised tap.
     *
     * <p>Without it the overlay's own {@code onTouchEvent} would see that tap, treat it as a real
     * finger and hide the pointer on the first controller click - the pointer would vanish the
     * moment it was used. Only a genuine touch hides the pointer.
     */
    private boolean dispatchingSyntheticTap;

    public LauncherCursorOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        float density = getResources().getDisplayMetrics().density;
        cursor = new CursorController(
                Math.max(1, getResources().getDisplayMetrics().widthPixels),
                Math.max(1, getResources().getDisplayMetrics().heightPixels),
                density);
    }

    /** True while the pointer is drawn. */
    public boolean isCursorVisible() {
        return visible;
    }

    /** The current pointer position, for a caller that wants to hit-test under it. */
    public float cursorX() {
        return cursor.x();
    }

    public float cursorY() {
        return cursor.y();
    }

    /** The shared cursor, so a caller can read its position or recentre it. */
    public CursorController cursor() {
        return cursor;
    }

    /** Shows the pointer at the viewport centre (a pad moved the stick or pressed a button). */
    public void enable() {
        if (getWidth() > 0 && getHeight() > 0) {
            cursor.setViewport(getWidth(), getHeight());
        }
        cursor.centre();
        visible = true;
        lastMs = 0L;
        invalidate();
    }

    /** Hides the pointer (a touch, or the pad going idle). */
    public void disable() {
        if (!visible) return;
        visible = false;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cursor.setViewport(Math.max(1, w), Math.max(1, h));
        if (visible && (oldw == 0 || oldh == 0)) cursor.centre();
    }

    /**
     * Moves the pointer by one frame of stick input, showing it if it was hidden.
     *
     * @param rawX     right-stick X, -1..1
     * @param rawY     right-stick Y, -1..1 (Android's sign already matches screen Y)
     * @param deadZone radial dead zone, 0..1
     * @param curve    response-curve exponent
     * @return true when the pointer is visible and moved
     */
    public boolean move(float rawX, float rawY, float deadZone, float curve) {
        if (!visible) enable();
        long now = SystemClock.uptimeMillis();
        float dt = lastMs == 0L ? 1f / 60f : Math.min(0.1f, (now - lastMs) / 1000f);
        lastMs = now;

        float[] shaped = new float[2];
        boolean outside = CursorController.shape(rawX, rawY, deadZone, curve, shaped);
        if (!outside) {
            // Inside the dead zone the pointer holds still, but the timestamp still advanced so
            // the first frame that does move is not integrated over the whole idle gap.
            return false;
        }
        cursor.setMagnetTarget(false, 0f, 0f);
        cursor.update(shaped[0], shaped[1], dt, false, false, CursorController.DEFAULT_MAX_SPEED_DP);
        invalidate();
        return true;
    }

    /**
     * Selects the view under the pointer by dispatching a synthesised tap to the content root.
     *
     * @return true when there was a root to dispatch to
     */
    public boolean select() {
        if (!visible) return false;
        View root = (View) getParent();
        if (root == null) return false;
        float x = cursor.x();
        float y = cursor.y();
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, x, y, 0);
        dispatchingSyntheticTap = true;
        try {
            // Dispatch to the content root; this overlay is not interactive, so it is transparent
            // to the events and the child under the point receives them.
            root.dispatchTouchEvent(down);
            root.dispatchTouchEvent(up);
        } catch (Throwable ignored) {
            // A screen that throws while handling the tap must not take the launcher down.
        } finally {
            dispatchingSyntheticTap = false;
        }
        down.recycle();
        up.recycle();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!visible) return;
        float density = getResources().getDisplayMetrics().density;
        UiDraw.pointerCursor(canvas, shadow, body, cursor.x(), cursor.y(), density);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // A real touch hides the pointer and is handled by the view below; never consume. The
        // overlay's own synthesised tap is ignored, or the pointer would vanish on first use.
        if (!dispatchingSyntheticTap
                && event.getActionMasked() == MotionEvent.ACTION_DOWN
                && visible) {
            disable();
        }
        return false;
    }

    /** Attaches a pointer to a content root, filling it. Returns null if either is absent. */
    public static LauncherCursorOverlay attach(Context context, ViewGroup root) {
        if (context == null || root == null) return null;
        LauncherCursorOverlay overlay = new LauncherCursorOverlay(context);
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return overlay;
    }
}
