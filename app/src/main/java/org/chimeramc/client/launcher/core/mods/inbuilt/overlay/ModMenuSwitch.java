package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The Mod Menu's pill switch, as pure geometry and colour (M5).
 *
 * <p>A 52x32dp track with a 24dp thumb inset 4dp, spring-animated on the thumb only. The state
 * machine is here rather than in the view so a JVM test pins the travel, the colours and the
 * disabled appearance without a device.
 *
 * <p>Deliberately no third "pending" visual state: a module either enables or it does not, and if
 * {@code enable()} fails the switch animates back and a toast names the reason. An intermediate
 * state is how a control starts lying about what it did.
 */
public final class ModMenuSwitch {

    private ModMenuSwitch() {
    }

    /** The thumb's travel in px: the track minus the thumb and both insets. */
    public static float travel(float density) {
        return ModMenuMetrics.SWITCH_WIDTH_DP * density
                - ModMenuMetrics.SWITCH_THUMB_DP * density
                - 2f * ModMenuMetrics.SWITCH_THUMB_INSET_DP * density;
    }

    /**
     * The thumb's centre X inside the track, for animation progress 0 (off) .. 1 (on).
     *
     * <p>The progress is clamped so an over-shooting spring cannot push the thumb outside its track.
     */
    public static float thumbCenterX(float density, float progress) {
        float p = Math.max(0f, Math.min(1f, progress));
        float inset = ModMenuMetrics.SWITCH_THUMB_INSET_DP * density;
        float thumb = ModMenuMetrics.SWITCH_THUMB_DP * density;
        return inset + thumb / 2f + travel(density) * p;
    }

    /** The track fill for a state: the accent when on, the muted accent when off. */
    public static int trackColor(boolean on) {
        return on ? ModMenuPalette.ACCENT : ModMenuPalette.mutedTrack();
    }

    /**
     * The track fill for a row that cannot be driven.
     *
     * <p>A failed module gets {@code state.error} at 30% rather than the muted accent, so the row's
     * failure is visible on the control itself and not only on the badge beside it.
     */
    public static int failedTrackColor() {
        return ModMenuPalette.blend(ModMenuPalette.BG_SURFACE_3,
                ModMenuPalette.STATE_ERROR, 0.30f);
    }

    /** The thumb colour: off-white normally, tertiary grey when the row is not interactive. */
    public static int thumbColor(boolean interactive) {
        return interactive ? ModMenuPalette.TEXT_PRIMARY : ModMenuPalette.TEXT_TERTIARY;
    }

    /**
     * The spring parameters for a state change (M5).
     *
     * <p>Turning <b>on</b> is springier than turning <b>off</b>: an enable is a commitment and the
     * overshoot reads as confirmation, while a disable should feel like a plain retreat.
     */
    public static float dampingFor(boolean turningOn) {
        return turningOn ? 0.72f : 0.85f;
    }

    public static float stiffnessFor(boolean turningOn) {
        return turningOn ? 520f : 480f;
    }

    /**
     * Whether a state change should tick the vibrator (M5).
     *
     * <p>Only the state change haptics — never the press-down, which would make the control feel
     * noisy and fire twice per flip.
     */
    public static boolean hapticsOnStateChange() {
        return true;
    }

    /**
     * The control's visual width in dp for a toggle style (M6).
     *
     * <p>{@code PILL_LABEL} reserves room for the "On"/"Off" word beside the pill; the plain switch
     * and the checkbox do not. The touch target is unaffected — it is always 48dp.
     */
    public static int visualWidthDp(ModMenuSettings.ToggleStyle style) {
        if (style == ModMenuSettings.ToggleStyle.PILL_LABEL) {
            return ModMenuMetrics.SWITCH_WIDTH_DP + PILL_LABEL_WIDTH_DP;
        }
        if (style == ModMenuSettings.ToggleStyle.CHECKBOX) return CHECKBOX_SIZE_DP;
        return ModMenuMetrics.SWITCH_WIDTH_DP;
    }

    /** The width the state word beside the pill occupies (M6). */
    public static final int PILL_LABEL_WIDTH_DP = 34;
    /** The checkbox's edge, a square (M6). */
    public static final int CHECKBOX_SIZE_DP = 22;

    /**
     * The TalkBack announcement for a switch (M5).
     *
     * <p>The spec asks for "Module name, On, Switch". The view carries this as its content
     * description and the accessibility node supplies the switch role, so TalkBack composes
     * "FPS Counter, On, Switch". Built here rather than in the view so the wording is unit-tested;
     * a null or blank label degrades to the state alone rather than announcing ", On".
     */
    public static String accessibilityLabel(String name, boolean on) {
        String state = on ? "On" : "Off";
        if (name == null || name.trim().isEmpty()) return state;
        return name.trim() + ", " + state;
    }

    /**
     * Draws the switch.
     *
     * @param interactive false draws the disabled appearance (grey thumb, no accent)
     * @param failed      true draws the error track, so a broken row is visible on the control
     */
    public static void draw(Canvas canvas, Paint paint, float left, float top, float density,
                            boolean on, float progress, boolean interactive, boolean failed) {
        draw(canvas, paint, left, top, density, on, progress, interactive, failed,
                ModMenuSettings.ToggleStyle.SWITCH);
    }

    /**
     * Draws the switch in the player's chosen toggle style (M6).
     *
     * <p>All three styles are the <em>same</em> control with the same state and the same 48dp touch
     * target; only the visual differs. That is why the style lives here as a drawing branch rather
     * than as three separate views — three views is how the checkbox variant ends up with different
     * behaviour from the switch.
     */
    public static void draw(Canvas canvas, Paint paint, float left, float top, float density,
                            boolean on, float progress, boolean interactive, boolean failed,
                            ModMenuSettings.ToggleStyle style) {
        ModMenuSettings.ToggleStyle effective = style == null
                ? ModMenuSettings.ToggleStyle.SWITCH : style;
        if (effective == ModMenuSettings.ToggleStyle.CHECKBOX) {
            drawCheckbox(canvas, paint, left, top, density, on, interactive, failed);
            return;
        }

        float width = ModMenuMetrics.SWITCH_WIDTH_DP * density;
        float height = ModMenuMetrics.SWITCH_HEIGHT_DP * density;
        float radius = height / 2f;
        RectF track = new RectF(left, top, left + width, top + height);

        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        if (failed) {
            paint.setColor(failedTrackColor());
        } else if (!interactive) {
            paint.setColor(ModMenuPalette.BG_SURFACE_3);
        } else {
            paint.setColor(trackColor(on));
        }
        canvas.drawRoundRect(track, radius, radius, paint);

        float thumb = ModMenuMetrics.SWITCH_THUMB_DP * density;
        float cx = left + thumbCenterX(density, progress);
        float cy = top + height / 2f;
        paint.setColor(thumbColor(interactive));
        canvas.drawCircle(cx, cy, thumb / 2f, paint);

        // The thumb's own shadow, so it reads as a raised part rather than a painted dot.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(Color.argb(0x40, 0, 0, 0));
        canvas.drawCircle(cx, cy, thumb / 2f, paint);

        if (effective == ModMenuSettings.ToggleStyle.PILL_LABEL) {
            // The state word beside the pill, so the control reads "On"/"Off" as text too.
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(11f * density);
            paint.setColor(interactive ? ModMenuPalette.TEXT_SECONDARY
                    : ModMenuPalette.TEXT_TERTIARY);
            paint.setTextAlign(Paint.Align.LEFT);
            float textX = left + width + 6f * density;
            float baseline = cy + 4f * density;
            canvas.drawText(on ? "On" : "Off", textX, baseline, paint);
        }
    }

    /**
     * Draws the checkbox variant of the toggle (M6).
     *
     * <p>A square with a check mark, for the users who ask for the plainest possible control. It
     * carries the same state as the pill: the tick appears exactly when the module is enabled.
     */
    private static void drawCheckbox(Canvas canvas, Paint paint, float left, float top,
                                     float density, boolean on, boolean interactive,
                                     boolean failed) {
        float size = CHECKBOX_SIZE_DP * density;
        RectF box = new RectF(left, top, left + size, top + size);
        float radius = 6f * density;

        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        if (failed) {
            paint.setColor(failedTrackColor());
        } else if (on && interactive) {
            paint.setColor(ModMenuPalette.ACCENT);
        } else {
            paint.setColor(interactive ? ModMenuPalette.mutedTrack()
                    : ModMenuPalette.BG_SURFACE_3);
        }
        canvas.drawRoundRect(box, radius, radius, paint);

        // The border, so an unchecked box is visible against the panel.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(on && interactive ? ModMenuPalette.ACCENT
                : ModMenuPalette.BORDER_HAIRLINE);
        canvas.drawRoundRect(box, radius, radius, paint);

        if (on) {
            // A two-segment tick, drawn as two stroked lines. Deliberately not a Path: this runs on
            // every frame of the menu, and building a Path per draw is an allocation on the hot path.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(interactive ? ModMenuPalette.TEXT_PRIMARY
                    : ModMenuPalette.TEXT_TERTIARY);
            float x0 = left + size * 0.24f;
            float y0 = top + size * 0.52f;
            float x1 = left + size * 0.44f;
            float y1 = top + size * 0.72f;
            float x2 = left + size * 0.78f;
            float y2 = top + size * 0.30f;
            canvas.drawLine(x0, y0, x1, y1, paint);
            canvas.drawLine(x1, y1, x2, y2, paint);
        }
    }
}
