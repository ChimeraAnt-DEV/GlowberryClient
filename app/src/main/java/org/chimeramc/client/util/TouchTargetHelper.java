package org.chimeramc.client.util;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import org.chimeramc.client.ui.components.TouchTargets;

import java.util.ArrayList;
import java.util.List;

/**
 * Enlarges a small control's touch area without changing how it looks (items 91, 339).
 *
 * <p>A 27dp icon button is the right size to draw and the wrong size to press; Material's minimum
 * is 48dp. The two ways to fix that are to draw the button bigger, which pushes the row it sits in
 * apart, or to keep the visual and enlarge only the region that accepts the touch. This does the
 * second, through the platform's {@link TouchDelegate}.
 *
 * <p>The platform's delegate holds one target per parent, which is not enough here: the log viewer
 * has seven small buttons in one row. {@link MultiTouchDelegate} is therefore a single delegate that
 * routes to any number of targets.
 *
 * <p>It only ever claims the area <em>outside</em> a target's real bounds. Inside them the parent's
 * normal dispatch already reaches the child, and taking over there would be a behaviour change for
 * no gain. It also never claims space outside the parent, because a delegate whose rectangle escapes
 * its parent simply stops receiving events - the button would look enlarged and behave exactly as
 * before.
 */
public final class TouchTargetHelper {

    private TouchTargetHelper() {
    }

    /** Grows {@code target}'s touch area to the Material minimum in both directions. */
    public static void expand(View target) {
        expand(target, TouchTargets.MIN_TARGET_DP);
    }

    /**
     * Grows {@code target}'s touch area to {@code minDp}, measured in the target's own density.
     *
     * <p>Safe to call from a bind path more than once: the delegate keeps one entry per view, so a
     * recycled row re-registers rather than stacking.
     */
    public static void expand(View target, int minDp) {
        if (target == null) return;
        ViewParent parent = target.getParent();
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) parent;
        int minPx = Math.round(minDp * target.getResources().getDisplayMetrics().density);
        MultiTouchDelegate delegate = delegateFor(group);
        delegate.register(target, minPx);
    }

    private static MultiTouchDelegate delegateFor(ViewGroup parent) {
        Object existing = parent.getTag(org.chimeramc.client.R.id.touch_target_delegate);
        if (existing instanceof MultiTouchDelegate) {
            return (MultiTouchDelegate) existing;
        }
        MultiTouchDelegate delegate = new MultiTouchDelegate(parent);
        parent.setTag(org.chimeramc.client.R.id.touch_target_delegate, delegate);
        parent.setTouchDelegate(delegate);
        return delegate;
    }

    /**
     * One delegate for a whole parent, routing to whichever registered target's expanded bounds
     * contain the point.
     */
    private static final class MultiTouchDelegate extends TouchDelegate {

        private final ViewGroup parent;
        private final List<View> targets = new ArrayList<>();
        private final List<Integer> minimums = new ArrayList<>();
        private View active;

        MultiTouchDelegate(ViewGroup parent) {
            // The base class needs a bounds and a view; both are unused because onTouchEvent is
            // fully overridden, but the constructor is not optional.
            super(new Rect(), parent);
            this.parent = parent;
        }

        void register(View target, int minPx) {
            int index = targets.indexOf(target);
            if (index >= 0) {
                minimums.set(index, minPx);
            } else {
                targets.add(target);
                minimums.add(minPx);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                active = hit(event.getX(), event.getY());
            } else if (active != null && !active.isAttachedToWindow()) {
                active = null;
            }

            if (active == null) return false;

            View target = active;
            int[] rect = expandedBounds(target);
            float dx = rect[0] - target.getLeft();
            float dy = rect[1] - target.getTop();
            event.offsetLocation(dx, dy);
            boolean handled = target.dispatchTouchEvent(event);
            event.offsetLocation(-dx, -dy);

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                active = null;
            }
            return handled;
        }

        /**
         * The target under the point, or null. A point inside a target's own bounds is left to the
         * parent's normal dispatch, so this only ever answers for the margin around a small view.
         */
        private View hit(float x, float y) {
            for (int i = 0; i < targets.size(); i++) {
                View t = targets.get(i);
                if (!t.isAttachedToWindow() || t.getVisibility() != View.VISIBLE) continue;
                if (x >= t.getLeft() && x < t.getRight() && y >= t.getTop() && y < t.getBottom()) {
                    continue;  // the real bounds; normal dispatch owns this
                }
                int[] rect = expandedBounds(t);
                if (x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3]) {
                    return t;
                }
            }
            return null;
        }

        private int[] expandedBounds(View t) {
            int minPx = minimums.get(targets.indexOf(t));
            return TouchTargets.delegateBounds(t.getLeft(), t.getTop(), t.getRight(), t.getBottom(),
                    parent.getWidth(), parent.getHeight(), minPx);
        }
    }
}
