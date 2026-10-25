package org.chimeramc.client.ui.components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The search field: a 32dp-high pill (surface fill) with a leading search icon and a trailing clear
 * icon. Used in the mod-menu title bar. Geometry is pure.
 */
public final class UiSearchField {

    public static final float HEIGHT_DP = 32f;

    private UiSearchField() {
    }

    /** The clear (x) button's bounds within the field. */
    public static RectF clearBounds(RectF field, float density) {
        float[] b = clearBoundsArray(field.left, field.top, field.right, field.bottom, density);
        return new RectF(b[0], b[1], b[2], b[3]);
    }

    /**
     * The clear button's bounds as {@code {left, top, right, bottom}}, pure and device-free.
     *
     * <p>The array form is what the JVM test can exercise: {@code android.graphics.RectF} is not on
     * the unit-test classpath. {@link #clearBounds} delegates here, so there is one definition.
     */
    public static float[] clearBoundsArray(float fieldLeft, float fieldTop, float fieldRight,
                                           float fieldBottom, float density) {
        float size = 20f * density;
        float right = fieldRight - 8f * density;
        float cy = (fieldTop + fieldBottom) / 2f;
        float top = cy - size / 2f;
        return new float[]{right - size, top, right, top + size};
    }

    /** Draws the field background, the leading icon and the trailing clear icon. */
    public static void draw(Canvas c, Paint paint, RectF field, String text, boolean focused,
                            float density) {
        float radius = field.height() / 2f;
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(UiTokens.BG_SURFACE);
        c.drawRoundRect(field, radius, radius, paint);
        int stroke = focused ? UiTokens.ACCENT_300 : UiTokens.STROKE_HAIRLINE;
        UiDraw.hairline(c, paint, field.left, field.top, field.right, field.bottom, radius,
                Math.max(1f, density), stroke);

        // Leading search glyph (a simple ring + handle).
        float iconCx = field.left + 14f * density;
        float iconCy = field.centerY();
        float r = 5f * density;
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, 2f * density));
        paint.setColor(UiTokens.TEXT_SECONDARY);
        c.drawCircle(iconCx, iconCy, r, paint);
        c.drawLine(iconCx + r * 0.7f, iconCy + r * 0.7f, iconCx + r * 1.7f, iconCy + r * 1.7f,
                paint);

        if (text != null && !text.isEmpty()) {
            RectF clear = clearBounds(field, density);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(UiTokens.TEXT_SECONDARY);
            float s = 4f * density;
            c.drawLine(clear.centerX() - s, clear.centerY() - s,
                    clear.centerX() + s, clear.centerY() + s, paint);
            c.drawLine(clear.centerX() + s, clear.centerY() - s,
                    clear.centerX() - s, clear.centerY() + s, paint);
        }
    }
}
