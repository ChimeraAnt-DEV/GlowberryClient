package org.chimeramc.client.core.replay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;

import org.chimeramc.client.util.PersonalizationManager;

/**
 * The Replay UI's colour and shape system, shared by every host of the panel.
 *
 * <p>The Replay tab appears both in the one in-game Mod Menu and on the launcher-side Replay
 * screen, and the spec requires the two to share "the same data, controls and polish". One token
 * class is what makes that true: the cards, pills and washes are drawn from the same ramp, so the
 * feature
 * cannot look like two different products depending on how it was opened. It is a four-tier
 * surface ramp (canvas → surface → elevated → hairline), a text ramp and a fixed status palette,
 * plus factories for the rounded / stroked / rippled drawables the panel reuses.
 */
public final class ReplayStyle {

    private static final int CANVAS = 0xFF0B0A12;
    private static final int SURFACE = 0xFF141221;
    private static final int SURFACE_ELEVATED = 0xFF1E1B2E;
    private static final int SURFACE_GLASS = 0xE61A1729;
    private static final int HAIRLINE = 0xFF2E2A44;

    private static final int TEXT_PRIMARY = 0xFFF4F1FF;
    private static final int TEXT_SECONDARY = 0xFFB6B0CC;
    private static final int TEXT_TERTIARY = 0xFF7B7591;

    private static final int STATUS_RECORD = 0xFFFF5C7A;
    private static final int STATUS_READY = 0xFF3DDC97;
    private static final int STATUS_WARN = 0xFFFFB347;

    private static final int DEFAULT_ACCENT = 0xFF7C5CFF;
    private static final int ACCENT_SECONDARY = 0xFF3682E2;

    private final int accent;
    private final int accentSecondary;
    private final boolean glowEnabled;

    public ReplayStyle(Context context) {
        int resolved = DEFAULT_ACCENT;
        boolean glow = true;
        try {
            PersonalizationManager pm = new PersonalizationManager(context);
            resolved = pm.getAccentColor();
            glow = pm.isEnableGlowEffects();
        } catch (Throwable ignored) {
        }
        this.accent = resolved;
        this.accentSecondary = blend(resolved, ACCENT_SECONDARY, 0.5f);
        this.glowEnabled = glow;
    }

    public int canvas() {
        return CANVAS;
    }

    public int surface() {
        return SURFACE;
    }

    public int surfaceElevated() {
        return SURFACE_ELEVATED;
    }

    public int surfaceGlass() {
        return SURFACE_GLASS;
    }

    public int hairline() {
        return HAIRLINE;
    }

    public int textPrimary() {
        return TEXT_PRIMARY;
    }

    public int textSecondary() {
        return TEXT_SECONDARY;
    }

    public int textTertiary() {
        return TEXT_TERTIARY;
    }

    public int statusRecord() {
        return STATUS_RECORD;
    }

    public int statusReady() {
        return STATUS_READY;
    }

    public int statusWarn() {
        return STATUS_WARN;
    }

    public int accent() {
        return accent;
    }

    public int accentSecondary() {
        return accentSecondary;
    }

    public boolean glowEnabled() {
        return glowEnabled;
    }

    public int accentFill(int alpha) {
        return withAlpha(accent, alpha);
    }

    public int secondaryFill(int alpha) {
        return withAlpha(accentSecondary, alpha);
    }

    public float cardElevation() {
        return glowEnabled ? 8f : 0f;
    }

    // --- drawable factories ---------------------------------------------------------------------

    /** A rounded solid fill. */
    public static GradientDrawable rounded(int color, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    /** A rounded fill with a hairline stroke. */
    public static GradientDrawable roundedStroked(int fill, int stroke, float radiusDp,
                                                  Context context) {
        GradientDrawable drawable = rounded(fill, radiusDp, context);
        drawable.setStroke(Math.max(1, Math.round(dp(context, 1f))), stroke);
        return drawable;
    }

    /** A vertical two-stop gradient, rounded. */
    public static GradientDrawable verticalGradient(int top, int bottom, float radiusDp,
                                                    Context context) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom});
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    /** A ripple over a rounded fill, so every interactive surface has press feedback. */
    public static RippleDrawable rippled(GradientDrawable content, int rippleColor,
                                         Context context) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    /** A pill (fully rounded) fill. */
    public static GradientDrawable pill(int color, Context context) {
        return rounded(color, 20f, context);
    }

    public static float dp(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    public static int dpInt(Context context, float value) {
        return Math.round(dp(context, value));
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    public static int blend(int base, int over, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        int r = (int) (Color.red(base) * (1 - a) + Color.red(over) * a);
        int g = (int) (Color.green(base) * (1 - a) + Color.green(over) * a);
        int b = (int) (Color.blue(base) * (1 - a) + Color.blue(over) * a);
        return Color.rgb(r, g, b);
    }
}
