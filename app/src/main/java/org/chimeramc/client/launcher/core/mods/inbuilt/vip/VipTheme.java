package org.chimeramc.client.core.mods.inbuilt.vip;

import android.content.Context;

import org.chimeramc.client.ui.components.UiTokens;

/**
 * The Controller/Keyboard pages' colour system (formerly the "VIP Mod Menu" theme).
 *
 * <p>This used to be a separate four-tier violet ramp. It is now an adapter over {@link UiTokens}:
 * those pages share the one blueberry palette with the rest of the Mod Menu and the radial wheel, so
 * the whole app reads as one product. The accessors are kept so the illustration views compile
 * unchanged.
 */
public final class VipTheme {

    private final int accent;
    private final int accentSecondary;
    private final boolean glowEnabled;

    public VipTheme(Context context) {
        this.accent = UiTokens.resolveAccent(context);
        this.accentSecondary = UiTokens.ACCENT_400;
        boolean glow = true;
        try {
            glow = new org.chimeramc.client.util.PersonalizationManager(context)
                    .isEnableGlowEffects();
        } catch (Throwable ignored) {
        }
        this.glowEnabled = glow;
    }

    public int canvas() {
        return UiTokens.BG_WINDOW;
    }

    public int surface() {
        return UiTokens.BG_SURFACE;
    }

    public int surfaceElevated() {
        return UiTokens.BG_SURFACE_HOVER;
    }

    public int surfaceGlass() {
        return UiTokens.BG_TITLEBAR;
    }

    public int hairline() {
        return UiTokens.STROKE_HAIRLINE;
    }

    public int textPrimary() {
        return UiTokens.TEXT_PRIMARY;
    }

    public int textSecondary() {
        return UiTokens.TEXT_SECONDARY;
    }

    public int textTertiary() {
        return UiTokens.TEXT_DISABLED;
    }

    public int statusActive() {
        return UiTokens.SUCCESS;
    }

    public int statusOff() {
        return UiTokens.TEXT_DISABLED;
    }

    public int statusUnavailable() {
        return UiTokens.DANGER;
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

    /** The accent at an explicit alpha, for washes, halos and pill fills. */
    public int accentFill(int alpha) {
        return UiTokens.withAlpha(accent, alpha);
    }

    public int secondaryFill(int alpha) {
        return UiTokens.withAlpha(accentSecondary, alpha);
    }

    /** The accent blended toward the surface, for an enabled card fill. */
    public int enabledCardColor() {
        return UiTokens.accentWash(accent, 0.16f);
    }

    public int disabledCardColor() {
        return UiTokens.BG_SURFACE_HOVER;
    }

    /** Card lift; zero when the user turned glow effects off, matching the rest of the app. */
    public float enabledElevation() {
        return glowEnabled ? 10f : 0f;
    }

    public float disabledElevation() {
        return glowEnabled ? 3f : 0f;
    }

    /** The shared surface colour; the per-group hue is gone. */
    public int groupColor(String groupId) {
        return UiTokens.BG_SURFACE;
    }

    public static int withAlpha(int color, int alpha) {
        return UiTokens.withAlpha(color, alpha);
    }

    public static int blend(int base, int over, float amount) {
        return UiTokens.blend(base, over, amount);
    }
}
