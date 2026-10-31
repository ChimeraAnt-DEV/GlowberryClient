package org.chimeramc.client.ui.navigation;

import org.chimeramc.client.ui.components.UiTokens;

/**
 * Picks the colour the navigation uses for its active destination (item 87).
 *
 * <p>The active tab is tinted with the theme accent. Under Material You the accent comes from the
 * user's wallpaper, so it can be any hue and any lightness - including one that sits almost on top
 * of the navigation surface. The label then reads as "selected but unreadable", which is worse than
 * not tinting it at all: the user cannot see which destination they are on.
 *
 * <p>The rule is a contrast floor, not a colour choice. Above the floor the accent is used exactly
 * as before; below it the plain on-surface colour takes over, so the active entry is still
 * distinguishable by weight, shape and the indicator even when its colour cannot carry the state.
 *
 * <p>Pure, so both ends of the floor are unit-tested without a device or a wallpaper.
 */
public final class NavAccent {

    /**
     * The minimum WCAG contrast ratio for the accent to be usable as an active-tab colour.
     *
     * <p>3:1 is the WCAG large-text / non-text-contrast floor. A tab label is small text, so this
     * is deliberately a floor rather than the 4.5:1 body-text target: falling back too eagerly
     * would drop the accent for a large share of wallpapers and make the "selected" colour and the
     * idle colour nearly the same, which is its own readability problem.
     */
    public static final double MIN_CONTRAST = 3.0;

    private NavAccent() {
    }

    /**
     * The active-tab colour: {@code accent} when it clears {@link #MIN_CONTRAST} against
     * {@code surface}, otherwise {@code onSurface}.
     *
     * <p>A zero accent (the "no custom accent" sentinel) or an opaque-black surface reading is
     * treated as "use onSurface": with no accent there is nothing to tint, and the caller's
     * fallback should be the readable colour rather than transparent.
     */
    public static int resolve(int accent, int surface, int onSurface) {
        if (accent == 0) return onSurface;
        // Only opaque colours have a meaningful contrast ratio; a translucent surface cannot be
        // compared against, so the accent is trusted rather than being rejected on a bad reading.
        if (((surface >>> 24) & 0xFF) != 0xFF) return accent;
        if (((accent >>> 24) & 0xFF) != 0xFF) return accent;
        return UiTokens.contrastRatio(accent, surface) >= MIN_CONTRAST ? accent : onSurface;
    }

    /** True when {@link #resolve} would fall back rather than use the accent. */
    public static boolean fallsBack(int accent, int surface) {
        if (accent == 0) return true;
        if (((surface >>> 24) & 0xFF) != 0xFF) return false;
        if (((accent >>> 24) & 0xFF) != 0xFF) return false;
        return UiTokens.contrastRatio(accent, surface) < MIN_CONTRAST;
    }
}
