package org.chimeramc.client.ui.components;

/**
 * Touch-target geometry (item 91, 339).
 *
 * <p>A 27dp icon button is the right visual size and the wrong touch size: Material's minimum is
 * 48dp, and anything under it is a target a user misses. The fix is not to inflate the button —
 * that would push the row apart — but to keep the visual and enlarge only the region that accepts
 * the touch, which is what a {@code TouchDelegate} does.
 *
 * <p>The arithmetic of that is pure and is kept here so it can be tested without a device: given
 * the view's bounds and its container's, work out the rectangle to hand the delegate. The rule is
 * simple — grow the view symmetrically to the minimum, then clamp to the container so the delegate
 * never claims space outside its parent (a delegate whose rect escapes the parent simply does not
 * receive the events, so the button would look enlarged and behave as before).
 */
public final class TouchTargets {

    /** Material's minimum interactive size. */
    public static final int MIN_TARGET_DP = 48;

    private TouchTargets() {
    }

    /**
     * The rectangle to pass to a {@code TouchDelegate}, as {@code [left, top, right, bottom]}.
     *
     * <p>Returns the view's own bounds when they already meet the minimum, so the caller can skip
     * installing a delegate at all.
     *
     * @param viewLeft   the view's left edge within its parent
     * @param viewTop    the view's top edge within its parent
     * @param viewRight  the view's right edge within its parent
     * @param viewBottom the view's bottom edge within its parent
     * @param parentWidth  the parent's width, or 0 to skip the horizontal clamp
     * @param parentHeight the parent's height, or 0 to skip the vertical clamp
     * @param minSize    the minimum target edge in pixels
     */
    public static int[] delegateBounds(int viewLeft, int viewTop, int viewRight, int viewBottom,
                                       int parentWidth, int parentHeight, int minSize) {
        int width = viewRight - viewLeft;
        int height = viewBottom - viewTop;
        int growX = Math.max(0, minSize - width);
        int growY = Math.max(0, minSize - height);
        int left = viewLeft - growX / 2;
        int top = viewTop - growY / 2;
        int right = viewRight + (growX - growX / 2);
        int bottom = viewBottom + (growY - growY / 2);

        if (parentWidth > 0) {
            // Clamp rather than shift: a target that hangs off one edge must give the space back on
            // the other, or a button flush against the start of its parent would lose half of it.
            if (left < 0) {
                right = Math.min(parentWidth, right - left);
                left = 0;
            }
            if (right > parentWidth) {
                left = Math.max(0, left - (right - parentWidth));
                right = parentWidth;
            }
        }
        if (parentHeight > 0) {
            if (top < 0) {
                bottom = Math.min(parentHeight, bottom - top);
                top = 0;
            }
            if (bottom > parentHeight) {
                top = Math.max(0, top - (bottom - parentHeight));
                bottom = parentHeight;
            }
        }
        return new int[]{left, top, right, bottom};
    }

    /** True when the view's own bounds already meet the minimum in both directions. */
    public static boolean meetsMinimum(int width, int height, int minSize) {
        return width >= minSize && height >= minSize;
    }
}
