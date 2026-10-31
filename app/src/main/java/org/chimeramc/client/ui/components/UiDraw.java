package org.chimeramc.client.ui.components;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Static canvas primitives shared by every GlowberryClient surface. The overlays draw themselves in
 * code (they cannot inflate a themed layout over the game), so the card, pill, hairline, gradient and
 * shadow geometry lives here rather than being re-derived per screen.
 *
 * <p>All sizes are taken in pixels by the caller; a caller converts dp with
 * {@code value * density}. Keeping the helpers pixel-based makes them usable from both a View and a
 * raw {@code Canvas} without a Context.
 */
public final class UiDraw {

    private UiDraw() {
    }

    /** Fills a rounded rectangle. */
    public static void roundedRect(Canvas c, Paint p, float l, float t, float r, float b,
                                   float radius) {
        c.drawRoundRect(new RectF(l, t, r, b), radius, radius, p);
    }

    /** Strokes a rounded rectangle with a 1dp hairline. */
    public static void hairline(Canvas c, Paint p, float l, float t, float r, float b,
                                float radius, float strokeWidth, int color) {
        p.reset();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(strokeWidth);
        p.setColor(color);
        p.setAntiAlias(true);
        c.drawRoundRect(new RectF(l, t, r, b), radius, radius, p);
    }

    /** A left-to-right linear gradient fill inside a rounded rectangle. */
    public static void gradientRect(Canvas c, Paint p, float l, float t, float r, float b,
                                    float radius, int startColor, int endColor) {
        p.reset();
        p.setStyle(Paint.Style.FILL);
        p.setAntiAlias(true);
        p.setShader(new LinearGradient(l, t, r, t, startColor, endColor, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(l, t, r, b), radius, radius, p);
        p.setShader(null);
    }

    /**
     * Draws the controller cursor as a clean pointer arrow with a small soft shadow.
     *
     * <p>Replaces the old 10dp disc + glow + ring: the glow read as a smear and the disc hid what
     * it was hovering over. The arrow is a standard mouse-pointer silhouette (~7dp wide, ~10dp tall)
     * with a 1dp white fill, a 1dp dark outline and a faint offset shadow. The tip is the hotspot,
     * so a click lands exactly where the arrow points.
     *
     * @param shadowFill the paint used for the shadow (reused across draws, reset each call)
     * @param arrowFill  the paint used for the arrow body (reused across draws, reset each call)
     */
    public static void pointerCursor(Canvas canvas, Paint shadowFill, Paint arrowFill,
                                     float x, float y, float density) {
        // The silhouette is the macOS-style pointer from PointerCursorGeometry: a slender blade with
        // the tip as the hotspot and a stepped tail. Coordinates come from the pure class so the
        // shape is unit-testable and a click lands exactly on the point.
        float h = 13f * density;
        float[] pts = PointerCursorGeometry.points(x, y, h);

        Path shape = new Path();
        shape.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) {
            shape.lineTo(pts[i], pts[i + 1]);
        }
        shape.close();

        // A soft offset shadow so the pointer reads on a bright world frame, without a blur layer.
        shadowFill.reset();
        shadowFill.setAntiAlias(true);
        shadowFill.setColor(0x38000000);
        canvas.save();
        canvas.translate(0.9f * density, 1.2f * density);
        canvas.drawPath(shape, shadowFill);
        canvas.restore();

        // Near-white body with a 1dp dark outline, the classic readable pointer.
        arrowFill.reset();
        arrowFill.setAntiAlias(true);
        arrowFill.setStyle(Paint.Style.FILL);
        arrowFill.setColor(0xFFFCFCFD);
        canvas.drawPath(shape, arrowFill);
        arrowFill.setStyle(Paint.Style.STROKE);
        arrowFill.setStrokeWidth(Math.max(1f, 0.8f * density));
        arrowFill.setStrokeJoin(Paint.Join.ROUND);
        arrowFill.setColor(0xB3000000);
        canvas.drawPath(shape, arrowFill);
    }

    /** A 1dp divider line. */
    public static void divider(Canvas c, Paint p, float l, float y, float r, float strokeWidth,
                               int color) {
        p.reset();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(strokeWidth);
        p.setColor(color);
        c.drawLine(l, y, r, y, p);
    }

    /**
     * A soft drop shadow under a rounded surface, so the panel reads as floating above the world.
     * The shadow is drawn as a translucent rounded rect offset down by {@code dy}; the caller paints
     * the actual fill on top.
     */
    public static void dropShadow(Canvas c, Paint p, float l, float t, float r, float b,
                                  float radius, float dy, int shadowColor) {
        p.reset();
        p.setStyle(Paint.Style.FILL);
        p.setAntiAlias(true);
        p.setColor(shadowColor);
        p.setShadowLayer(Math.max(1f, radius * 0.4f), 0f, dy, shadowColor);
        c.drawRoundRect(new RectF(l, t, r, b), radius, radius, p);
        p.clearShadowLayer();
    }

    /** An inner glow along the inside edge of a rounded surface, for a hovered/focused state. */
    public static void innerGlow(Canvas c, Paint p, float l, float t, float r, float b,
                                 float radius, float width, int glowColor) {
        p.reset();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, width));
        p.setColor(glowColor);
        p.setAntiAlias(true);
        c.drawRoundRect(new RectF(l + width / 2f, t + width / 2f, r - width / 2f, b - width / 2f),
                radius, radius, p);
    }

    /** A soft elliptical shadow that grounds an object, tinted (usually by the accent). */
    public static void pedestal(Canvas c, Paint p, float cx, float cy, float rx, float ry,
                                int color) {
        p.reset();
        p.setStyle(Paint.Style.FILL);
        p.setAntiAlias(true);
        p.setColor(color);
        p.setShadowLayer(Math.max(1f, ry), 0f, 0f, color);
        c.drawOval(new RectF(cx - rx, cy - ry, cx + rx, cy + ry), p);
        p.clearShadowLayer();
    }

    /** The "corner fillet" path for a rounded annular sector, used by the radial wheel. */
    public static Path annularSectorPath(float cx, float cy, float innerR, float outerR,
                                         float startDeg, float sweepDeg, float cornerRadius) {
        Path path = new Path();
        float a0 = startDeg;
        float a1 = startDeg + sweepDeg;
        // Outer arc, then a fillet down to the inner arc, then the inner arc back, then a fillet up.
        float r = Math.min(cornerRadius, Math.min(outerR - innerR, Math.abs(sweepDeg)) / 2f);
        // Approximate rounded corners by insetting the arc endpoints; a full fillet is overkill for
        // a UI wedge and the parallel-gap geometry is what matters visually.
        float outerInsetDeg = (float) Math.toDegrees(r / Math.max(1f, outerR));
        float innerInsetDeg = (float) Math.toDegrees(r / Math.max(1f, innerR));
        path.moveTo(polarX(cx, outerR, a0 + outerInsetDeg), polarY(cy, outerR, a0 + outerInsetDeg));
        arcTo(path, cx, cy, outerR, a0 + outerInsetDeg, a1 - outerInsetDeg);
        path.lineTo(polarX(cx, innerR, a1 - innerInsetDeg), polarY(cy, innerR, a1 - innerInsetDeg));
        arcToReverse(path, cx, cy, innerR, a1 - innerInsetDeg, a0 + innerInsetDeg);
        path.close();
        return path;
    }

    private static void arcTo(Path path, float cx, float cy, float radius, float startDeg,
                              float endDeg) {
        RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        path.arcTo(oval, startDeg, endDeg - startDeg);
    }

    private static void arcToReverse(Path path, float cx, float cy, float radius, float startDeg,
                                     float endDeg) {
        RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        path.arcTo(oval, startDeg, endDeg - startDeg);
    }

    private static float polarX(float cx, float radius, float deg) {
        return cx + (float) (Math.cos(Math.toRadians(deg)) * radius);
    }

    private static float polarY(float cy, float radius, float deg) {
        return cy + (float) (Math.sin(Math.toRadians(deg)) * radius);
    }
}
