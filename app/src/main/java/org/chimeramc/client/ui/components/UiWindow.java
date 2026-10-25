package org.chimeramc.client.ui.components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The window chrome shared by the Mod Menu and the Closet: a rounded 16dp near-black panel with a
 * 1dp hairline and a shadow, a 48dp title bar with a hairline divider, and a centred layout rule.
 */
public final class UiWindow {

    private UiWindow() {
    }

    /**
     * The window rectangle for a screen: centred, width {@code min(0.66 * screenW, 720dp)} and height
     * {@code 0.78 * screenH}. Pure so a test can pin it across screen sizes.
     */
    public static RectF bounds(int screenW, int screenH, float density) {
        float[] b = boundsArray(screenW, screenH, density);
        return new RectF(b[0], b[1], b[2], b[3]);
    }

    /** The window rectangle as {@code {left, top, right, bottom}}, pure and device-free. */
    public static float[] boundsArray(int screenW, int screenH, float density) {
        float maxW = UiTokens.WINDOW_MAX_WIDTH_DP * density;
        float w = Math.min(screenW * UiTokens.WINDOW_WIDTH_FRACTION, maxW);
        float h = screenH * UiTokens.WINDOW_HEIGHT_FRACTION;
        float left = (screenW - w) / 2f;
        float top = (screenH - h) / 2f;
        return new float[]{left, top, left + w, top + h};
    }

    /** The title bar rectangle inside a window. */
    public static RectF titleBar(RectF window, float density) {
        float[] b = titleBarArray(window.left, window.top, window.right, window.bottom, density);
        return new RectF(b[0], b[1], b[2], b[3]);
    }

    /**
     * The title bar rectangle as {@code {left, top, right, bottom}}, pure and device-free.
     *
     * <p>The array form is what the JVM test can exercise, since {@code android.graphics.RectF} is
     * not on the unit-test classpath. {@link #titleBar} delegates here, so there is one definition.
     */
    public static float[] titleBarArray(float left, float top, float right, float bottom,
                                        float density) {
        return new float[]{left, top, right, top + UiTokens.TITLEBAR_HEIGHT * density};
    }

    /** The content rectangle below the title bar. */
    public static RectF content(RectF window, float density) {
        float[] b = contentArray(window.left, window.top, window.right, window.bottom, density);
        return new RectF(b[0], b[1], b[2], b[3]);
    }

    /**
     * The content rectangle as {@code {left, top, right, bottom}}, pure and device-free. It starts
     * exactly where {@link #titleBarArray} ends and reaches the window's bottom, so the two tile the
     * window without an overlap or a gap.
     */
    public static float[] contentArray(float left, float top, float right, float bottom,
                                       float density) {
        float barBottom = titleBarArray(left, top, right, bottom, density)[3];
        return new float[]{left, barBottom, right, bottom};
    }

    /** Draws the window background, the title bar and the divider. */
    public static void draw(Canvas c, Paint paint, RectF window, float radius, float density,
                            float shadowRadius, float shadowAlpha) {
        draw(c, paint, window, radius, density, shadowRadius, shadowAlpha,
                UiGlass.BLUR_PANEL);
    }

    /**
     * Draws the window at an explicit glass blur level.
     *
     * <p>The fill alpha follows {@link UiGlass#panelAlpha}: a heavier blur is a clearer pane. The
     * title bar and the content share the one material so a menu cannot render its chrome and its
     * body at different translucencies. {@code userOpacity} (0..1) is the player's own background
     * opacity preference, applied on top so the "clear glass vs solid panel" slider works without a
     * second drawing path.
     */
    public static void draw(Canvas c, Paint paint, RectF window, float radius, float density,
                            float shadowRadius, float shadowAlpha, float blur) {
        draw(c, paint, window, radius, density, shadowRadius, shadowAlpha, blur, 1f);
    }

    /** As {@link #draw(Canvas, Paint, RectF, float, float, float, float, float)} with user opacity. */
    public static void draw(Canvas c, Paint paint, RectF window, float radius, float density,
                            float shadowRadius, float shadowAlpha, float blur, float userOpacity) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(UiTokens.withAlpha(0xFF000000, (int) (shadowAlpha * 255)));
        paint.setShadowLayer(shadowRadius, 0f, 0f,
                UiTokens.withAlpha(0xFF000000, (int) (shadowAlpha * 255)));
        c.drawRoundRect(window, radius, radius, paint);
        paint.clearShadowLayer();

        int fillAlpha = Math.round(UiGlass.panelAlpha(blur, true) * clamp01(userOpacity));
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(UiTokens.withAlpha(UiTokens.BG_WINDOW, fillAlpha));
        c.drawRoundRect(window, radius, radius, paint);

        RectF bar = titleBar(window, density);
        paint.setColor(UiTokens.withAlpha(UiTokens.BG_TITLEBAR, fillAlpha));
        c.drawRoundRect(new RectF(bar.left, bar.top, bar.right, bar.bottom), radius, radius, paint);
        UiDraw.divider(c, paint, window.left, bar.bottom, window.right, Math.max(1f, density),
                UiTokens.STROKE_HAIRLINE);
        UiDraw.hairline(c, paint, window.left, window.top, window.right, window.bottom, radius,
                Math.max(1f, density), UiTokens.STROKE_HAIRLINE);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
