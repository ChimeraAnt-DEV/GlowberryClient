package org.chimeramc.client.ui.components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * A left-rail category button: a 40dp rounded square (radius 10) with a 24dp icon. Selected = filled
 * accent with a white icon; unselected = transparent with a secondary icon; pressed = hover surface.
 */
public final class UiRailButton {

    private UiRailButton() {
    }

    /** The button's rectangle inside a rail column of the given width, at the given slot. */
    public static RectF bounds(float railLeft, float railTop, float density, int slot) {
        float[] b = boundsArray(railLeft, railTop, density, slot);
        return new RectF(b[0], b[1], b[2], b[3]);
    }

    /**
     * The button's rectangle as {@code {left, top, right, bottom}}, pure and device-free.
     *
     * <p>The array form exists so the geometry is testable on the JVM: {@code android.graphics.RectF}
     * is not on the unit-test classpath, which is the same reason {@link UiWindow#boundsArray}
     * exists. {@link #bounds} delegates here, so there is one definition of the layout.
     */
    public static float[] boundsArray(float railLeft, float railTop, float density, int slot) {
        float size = UiTokens.RAIL_BUTTON * density;
        float gap = 8f * density;
        float left = railLeft + (UiTokens.RAIL_WIDTH * density - size) / 2f;
        float top = railTop + slot * (size + gap);
        return new float[]{left, top, left + size, top + size};
    }

    /** Draws the button background for a state. */
    public static void drawBackground(Canvas c, Paint paint, RectF b, float radius, int accent,
                                      boolean selected, boolean pressed) {
        paint.reset();
        paint.setAntiAlias(true);
        if (selected) {
            paint.setColor(accent);
        } else if (pressed) {
            paint.setColor(UiTokens.BG_SURFACE_HOVER);
        } else {
            paint.setColor(0x00000000);
        }
        c.drawRoundRect(b, radius, radius, paint);
    }

    /** The icon tint for a state. */
    public static int iconColor(int accent, boolean selected) {
        return selected ? 0xFFFFFFFF : UiTokens.TEXT_SECONDARY;
    }
}
