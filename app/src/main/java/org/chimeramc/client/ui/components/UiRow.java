package org.chimeramc.client.ui.components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * A module list row: a 44dp surface with a 20dp leading icon, a 13sp/500 name, an optional 11sp
 * description and a trailing toggle. The row rectangle and the toggle's bounds are pure so a test can
 * assert the layout without a device.
 */
public final class UiRow {

    private UiRow() {
    }

    /** The toggle's bounds within a row of the given rectangle. */
    public static RectF toggleBounds(RectF row, float density) {
        float[] b = toggleBoundsArray(row.left, row.top, row.right, row.bottom, density);
        return new RectF(b[0], b[1], b[2], b[3]);
    }

    /**
     * The toggle's rectangle as {@code {left, top, right, bottom}}, pure and device-free.
     *
     * <p>The array form follows {@link UiWindow#boundsArray}: {@code RectF} is not on the unit-test
     * classpath, and it also lets a caller fill a reused {@code RectF} instead of allocating one
     * per draw.
     */
    public static float[] toggleBoundsArray(float left, float top, float right, float bottom,
                                            float density) {
        float tw = UiTokens.TOGGLE_WIDTH * density;
        float th = UiTokens.TOGGLE_HEIGHT * density;
        float rightEdge = right - 12f * density;
        float centerY = (top + bottom) / 2f;
        float topEdge = centerY - th / 2f;
        return new float[]{rightEdge - tw, topEdge, rightEdge, topEdge + th};
    }

    /** Fills {@code out} with the toggle bounds, allocating nothing. */
    public static void toggleBoundsInto(RectF out, RectF row, float density) {
        float[] b = toggleBoundsArray(row.left, row.top, row.right, row.bottom, density);
        out.set(b[0], b[1], b[2], b[3]);
    }

    /** The leading icon's square within a row. */
    public static RectF iconBounds(RectF row, float density) {
        float size = 20f * density;
        float left = row.left + 12f * density;
        float top = row.centerY() - size / 2f;
        return new RectF(left, top, left + size, top + size);
    }

    /** Draws the row background: surface fill, accent wash when enabled, hover when pressed. */
    public static void drawBackground(Canvas c, Paint paint, RectF row, float radius,
                                      int accent, boolean enabled, boolean pressed) {
        paint.reset();
        paint.setAntiAlias(true);
        if (pressed) {
            paint.setColor(UiTokens.BG_SURFACE_HOVER);
        } else if (enabled) {
            paint.setColor(UiTokens.accentWash(accent, 0.14f));
        } else {
            paint.setColor(UiTokens.BG_SURFACE);
        }
        c.drawRoundRect(row, radius, radius, paint);
        UiDraw.hairline(c, paint, row.left, row.top, row.right, row.bottom, radius,
                Math.max(1f, radius / 10f), UiTokens.STROKE_HAIRLINE);
    }
}
