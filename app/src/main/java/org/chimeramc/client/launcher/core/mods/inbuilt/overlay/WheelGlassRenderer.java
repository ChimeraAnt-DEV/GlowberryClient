package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.widget.ImageView;

import org.chimeramc.client.ui.components.UiTokens;

/**
 * Paints the wheel's glass backdrop and applies the blur behind it (R2, R3, R12).
 *
 * <p>All of the heavy API work lives here and nowhere else. Two blur routes are supported because
 * the honest answer differs by API level:
 *
 * <ul>
 *   <li><b>API 31+</b>: the captured backdrop is shown on its own {@link ImageView} layer behind the
 *       wheel and blurred with a single {@code RenderEffect} on that layer. Blurring the backdrop
 *       <em>layer</em> rather than the wheel's own view is the whole point — a {@code RenderEffect}
 *       on the wheel would blur the wheel's own sectors and labels.</li>
 *   <li><b>API 28-30</b>: no {@code RenderEffect} exists, so the captured backdrop is blurred with a
 *       downscale/upscale stack (blit to a small scratch, then stretch back with filtering), which
 *       costs two blits and adds no API surface.</li>
 * </ul>
 *
 * <p>Both routes are driven by the resolved {@link RadialWheelMaterial}, so the tier, the scrim
 * floor and the thermal budget decide what happens; this class only carries out the decision.
 */
public final class WheelGlassRenderer {

    /** The denominator of the stack-blur's downscale. */
    public static final int STACK_BLUR_DIVISOR = 2;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect srcRect = new Rect();
    private final Rect dstRect = new Rect();
    private final Rect scratchDstRect = new Rect();
    private final Canvas scratchCanvas = new Canvas();

    /**
     * The stack-blur's intermediate bitmap, reused across frames.
     *
     * <p>Held rather than allocated per draw because {@link #drawStackBlur} runs inside
     * {@code onDraw}: a per-frame allocation of a bitmap that size is a GC pause on the render path.
     * It is rebuilt only when the capture's dimensions change.
     */
    private Bitmap blurScratch;

    /**
     * Shows the captured backdrop on its own layer and applies the blur (R3).
     *
     * <p>Called whenever the capture or the resolved material changes, never per frame. A material
     * that does not blur (a solid tier, "reduce transparency", a thermal downgrade) hides the layer
     * and clears the effect, so a tier change cannot leave a stale blur on screen.
     */
    public void applyBackdrop(ImageView layer, RadialWheelMaterial.Resolved material,
                              Bitmap capture, float density) {
        if (layer == null) return;
        boolean usable = capture != null && !capture.isRecycled()
                && RadialWheelMaterial.blurRoute(Build.VERSION.SDK_INT, material)
                        == RadialWheelMaterial.BlurRoute.RENDER_EFFECT;
        if (!usable) {
            releaseBackdrop(layer);
            return;
        }
        try {
            layer.setVisibility(View.VISIBLE);
            layer.setImageBitmap(capture);
            Api31.applyBlur(layer, material.blurDp * Math.max(0.5f, density));
        } catch (Throwable ignored) {
            // A layer that cannot take the effect simply keeps the flat fill; the wheel stays
            // readable either way.
        }
    }

    /** Hides the backdrop layer, drops its bitmap and clears any effect (R3, R12). */
    public void releaseBackdrop(ImageView layer) {
        if (layer != null) {
            clearViewEffect(layer);
            try {
                layer.setImageDrawable(null);
                layer.setVisibility(View.GONE);
            } catch (Throwable ignored) {
            }
        }
        recycleScratch();
    }

    /** Clears any render effect on a view. Safe on every API level. */
    public void clearViewEffect(View target) {
        if (target == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            target.setRenderEffect(null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Draws the backdrop's non-blurred layers: the black scrim floor, the tier's dark fill and its
     * white tint (R2).
     *
     * <p>The order is what makes the material read as glass: the scrim guarantees the labels have
     * something to sit on over a bright frame, the fill sets the tier's solidity, and the tint is
     * the light catching the sheet. On API 28-30 the stack-blurred capture is drawn underneath all
     * three; on API 31+ the blurred {@code ImageView} layer is already behind the wheel, so this
     * method only paints the sheet over it.
     *
     * @param cx,cy  the wheel centre
     * @param radius the radius of the backdrop disc
     */
    public void drawBackdrop(Canvas canvas, RadialWheelMaterial.Resolved material, Bitmap capture,
                             float cx, float cy, float radius, float alpha) {
        if (canvas == null || material == null) return;
        float a = Math.max(0f, Math.min(1f, alpha));

        // API 28-30 only: no RenderEffect exists, so the capture is blurred in the canvas.
        if (capture != null && !capture.isRecycled()
                && RadialWheelMaterial.blurRoute(Build.VERSION.SDK_INT, material)
                        == RadialWheelMaterial.BlurRoute.STACK) {
            drawStackBlur(canvas, capture, cx, cy, radius, a);
        }

        int scrimAlpha = (int) (material.scrimAlpha * 255 * a);
        if (scrimAlpha > 0) {
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(UiTokens.withAlpha(0xFF000000, scrimAlpha));
            canvas.drawCircle(cx, cy, radius, fillPaint);
        }

        int fillAlpha = (int) (material.fillAlpha * a);
        if (fillAlpha > 0) {
            fillPaint.setColor(UiTokens.withAlpha(UiTokens.BG_WINDOW, fillAlpha));
            canvas.drawCircle(cx, cy, radius, fillPaint);
        }

        int tintAlpha = (int) (material.tint * 255 * a);
        if (tintAlpha > 0) {
            tintPaint.setColor(UiTokens.withAlpha(0xFFFFFFFF, tintAlpha));
            canvas.drawCircle(cx, cy, radius, tintPaint);
        }
    }

    /**
     * Draws a blurred copy of the captured backdrop into the disc (the API 28-30 route).
     *
     * <p>A real two-pass stack blur: the capture is blitted down into a small scratch, then that
     * scratch is stretched back over the disc with bilinear filtering. An earlier version mapped a
     * <em>sub-rectangle</em> of the capture onto the disc, which showed the top-left quarter of the
     * frame instead of the whole frame — a misread of "downscale" as "crop". The source rect is now
     * the whole bitmap.
     *
     * <p>Every rect is a reused member and the scratch is cached, so the draw path allocates nothing
     * after the first frame.
     */
    private void drawStackBlur(Canvas canvas, Bitmap capture, float cx, float cy, float radius,
                               float alpha) {
        int w = capture.getWidth();
        int h = capture.getHeight();
        if (w <= 0 || h <= 0) return;
        dstRect.set(Math.round(cx - radius), Math.round(cy - radius),
                Math.round(cx + radius), Math.round(cy + radius));
        int save = canvas.save();
        canvas.clipRect(dstRect);
        bitmapPaint.setAlpha((int) (255 * alpha));

        int sw = Math.max(1, w / STACK_BLUR_DIVISOR);
        int sh = Math.max(1, h / STACK_BLUR_DIVISOR);
        Bitmap scratch = scratchFor(sw, sh);
        if (scratch == null) {
            // No scratch available: fall back to the unblurred whole-frame blit, never a crop.
            srcRect.set(0, 0, w, h);
            canvas.drawBitmap(capture, srcRect, dstRect, bitmapPaint);
            canvas.restoreToCount(save);
            return;
        }

        // Pass 1: shrink the whole frame into the scratch.
        scratchDstRect.set(0, 0, sw, sh);
        scratchCanvas.setBitmap(scratch);
        srcRect.set(0, 0, w, h);
        scratchCanvas.drawBitmap(capture, srcRect, scratchDstRect, bitmapPaint);
        scratchCanvas.setBitmap(null);

        // Pass 2: stretch the shrunk copy over the disc — the filtered second blit is the blur.
        srcRect.set(0, 0, sw, sh);
        canvas.drawBitmap(scratch, srcRect, dstRect, bitmapPaint);
        canvas.restoreToCount(save);
    }

    private Bitmap scratchFor(int w, int h) {
        if (w <= 0 || h <= 0) return null;
        if (blurScratch != null && !blurScratch.isRecycled()
                && blurScratch.getWidth() == w && blurScratch.getHeight() == h) {
            return blurScratch;
        }
        recycleScratch();
        try {
            blurScratch = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable ignored) {
            blurScratch = null;
        }
        return blurScratch;
    }

    private void recycleScratch() {
        if (blurScratch != null && !blurScratch.isRecycled()) {
            try {
                blurScratch.recycle();
            } catch (Throwable ignored) {
            }
        }
        blurScratch = null;
    }

    /** Draws the hairline that defines a glass edge. */
    public void drawHairline(Canvas canvas, float cx, float cy, float radius, float alpha,
                             float density) {
        if (canvas == null || alpha <= 0f) return;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1f, density));
        strokePaint.setColor(UiTokens.withAlpha(UiTokens.STROKE_HAIRLINE,
                (int) (0x24 * Math.max(0f, Math.min(1f, alpha)))));
        canvas.drawCircle(cx, cy, radius, strokePaint);
    }

    /**
     * The paint used for an outlined label: a stroke-only copy of the text paint (R10).
     *
     * <p>Returned rather than configured on a shared paint so the caller can draw the halo and the
     * fill from the same text metrics without re-measuring.
     */
    public Paint labelOutlinePaint(Paint textPaint, int color, float density) {
        Paint outline = new Paint(textPaint);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(Math.max(1f, 2f * density));
        outline.setColor(color);
        return outline;
    }

    /**
     * The {@code RenderEffect} blur, isolated in a holder (R12).
     *
     * <p>{@code RenderEffect} and {@code View#setRenderEffect} only exist on API 31, so the types
     * live behind this {@code @TargetApi} nested class and are reached only after the caller's
     * {@code SDK_INT} guard. Referencing them from the outer class's own fields or method signatures
     * would fail class initialisation on API 28 — the documented crash shape for this project.
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    private static final class Api31 {
        static void applyBlur(View view, float radiusPx) {
            float r = Math.max(0f, radiusPx);
            view.setRenderEffect(r > 0f
                    ? android.graphics.RenderEffect.createBlurEffect(r, r,
                            android.graphics.Shader.TileMode.CLAMP)
                    : null);
        }
    }
}
