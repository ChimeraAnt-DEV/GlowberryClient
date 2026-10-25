package org.chimeramc.client.ui.components;

import android.content.Context;

import org.chimeramc.client.util.PersonalizationManager;

/**
 * The single source of truth for the GlowberryClient UI's colours, shape and type.
 *
 * <p>This replaces the retired violet/magenta brand palette and the two separate mod-menu themes
 * ({@code ModMenuTheme} and {@code VipTheme}). Every screen — the radial wheel, the one mod menu, the
 * Closet and the Launch tab — reads from here, so a screen cannot drift onto its own colours.
 *
 * <p>The values are the "blueberry" palette: a near-black, very slightly cool window on a blue
 * accent. The user's selectable accent still applies (the mod menus and the wheel draw themselves,
 * so they resolve it here), but <b>blue is the default</b> — {@link #resolveAccent} falls back to
 * {@link #ACCENT_500} whenever no custom accent has been chosen.
 *
 * <p>Android-free apart from {@link Context} for the accent preference; the constants and the pure
 * derivations are unit-tested without a device.
 */
public final class UiTokens {

    private UiTokens() {
    }

    // --- Colours: window and surfaces --------------------------------------------------------

    /** The window panel fill (alpha 0.96). */
    public static final int BG_WINDOW = 0xF5111214;
    public static final int BG_SURFACE = 0xFF17191D;
    public static final int BG_SURFACE_HOVER = 0xFF1E2126;
    public static final int BG_TITLEBAR = 0xFF141418;

    /** 1dp borders and dividers. */
    public static final int STROKE_HAIRLINE = 0x12FFFFFF;  // rgba(255,255,255,0.07)

    // --- Colours: accent ---------------------------------------------------------------------

    /** Toggle ON, selected rail icon, primary button. */
    public static final int ACCENT_500 = 0xFF0F5BE4;
    /** Hover/pressed lighter, gradient end. */
    public static final int ACCENT_400 = 0xFF3682E2;
    /** Icons on dark, focus ring. */
    public static final int ACCENT_300 = 0xFF71ACEF;
    /** Tinted selected-row background. */
    public static final int ACCENT_900 = 0xFF10254F;
    /** Section tint. */
    public static final int NAVY_TINT = 0xFF1D283D;

    // --- Colours: text -----------------------------------------------------------------------

    public static final int TEXT_PRIMARY = 0xFFE8EAED;
    public static final int TEXT_SECONDARY = 0xFF9AA0A6;
    public static final int TEXT_DISABLED = 0xFF5F6368;

    public static final int TOGGLE_OFF_TRACK = 0xFF2A2D33;

    // --- Colours: status (status only) -------------------------------------------------------

    public static final int SUCCESS = 0xFF3DDC84;
    public static final int WARNING = 0xFFFFB020;
    public static final int DANGER = 0xFFFF5C5C;

    // --- Radial wheel (translucent, over the game) -------------------------------------------

    /**
     * The wheel's scrim. It is now the {@link RadialWheelMaterial} scrim floor's dark base rather
     * than the retired violet wash: the material decides the strength (R2), this is only the hue, and
     * a violet scrim under a blue palette was the last place the old brand survived.
     */
    public static final int WHEEL_SCRIM = 0x73070A0F;             // rgba(7,10,15,0.45)
    /** The segment's dark base fill; the material's white tint is laid over it (R2). */
    public static final int WHEEL_SEGMENT_BASE = 0xCC0B1118;      // rgba(11,17,24,0.80)
    /** The hovered/selected segment: one surface step lighter. */
    public static final int WHEEL_SEGMENT_HOVER = 0xE616202D;     // rgba(22,32,45,0.90)
    public static final int WHEEL_SEGMENT_RIM = 0x24FFFFFF;       // rgba(255,255,255,0.14)
    /** The hub disc: the panel surface, so the head sits on the same material as the ring (R7). */
    public static final int WHEEL_HUB_FILL = 0xE60B1118;          // rgba(11,17,24,0.90)
    public static final int CHIP_BG = 0xC7101823;                 // rgba(16,24,35,0.78)
    public static final int CHIP_BORDER = 0x1AFFFFFF;             // rgba(255,255,255,0.10)

    // --- Shape (dp) --------------------------------------------------------------------------

    /**
     * The single radius scale (U10): 8 / 12 / 16 / 28dp. Every surface picks one of the four —
     * a bespoke radius is how two cards that should match end up one pixel apart.
     */
    public static final int RADIUS_SMALL = 8;
    public static final int RADIUS_MEDIUM = 12;
    public static final int RADIUS_LARGE = 16;
    public static final int RADIUS_XLARGE = 28;

    public static final int RADIUS_WINDOW = RADIUS_XLARGE;
    public static final int RADIUS_CARD = RADIUS_LARGE;
    public static final int RADIUS_CHIP = RADIUS_MEDIUM;
    public static final int RADIUS_BUTTON = RADIUS_LARGE;
    /** A full pill: half the height, so the drawable clamps it to a stadium. */
    public static final int RADIUS_TOGGLE = 999;

    /**
     * The single spacing scale (U9): 4 / 8 / 12 / 16 / 24 / 32dp. Any margin or padding in a
     * Glowberry surface is one of these, so vertical rhythm is consistent by construction.
     */
    public static final int SPACE_1 = 4;
    public static final int SPACE_2 = 8;
    public static final int SPACE_3 = 12;
    public static final int SPACE_4 = 16;
    public static final int SPACE_6 = 24;
    public static final int SPACE_8 = 32;

    // --- Elevation ---------------------------------------------------------------------------

    public static final float WINDOW_SHADOW_RADIUS = 24f;
    public static final float WINDOW_SHADOW_ALPHA = 0.35f;

    // --- Type (sp) ---------------------------------------------------------------------------

    public static final float TEXT_TITLE = 16f;
    public static final int TITLE_WEIGHT = 600;
    public static final float TEXT_ROW_TITLE = 13f;
    public static final int ROW_TITLE_WEIGHT = 500;
    public static final float TEXT_CAPTION = 11f;
    public static final int CAPTION_WEIGHT = 400;
    /** The UI's one variable family (U6/U8): weights are axis values, not NORMAL/BOLD. */
    public static final float TEXT_SECTION = 10f;
    public static final int SECTION_WEIGHT = 600;
    public static final float SECTION_LETTER_SPACING = 0.08f;

    /**
     * The five type roles (U8), in descending size: display, title, body, label, caption. A
     * surface picks a role, never a raw sp value, so two screens showing the same kind of text
     * cannot drift apart.
     */
    public static final float TEXT_DISPLAY = 22f;
    public static final int DISPLAY_WEIGHT = 600;
    /** Alias of {@link #TEXT_TITLE} — the role name is what new code should use. */
    public static final float TEXT_TITLE_ROLE = TEXT_TITLE;
    public static final float TEXT_BODY = 14f;
    public static final int BODY_WEIGHT = 400;
    /** Alias of {@link #TEXT_ROW_TITLE}. */
    public static final float TEXT_LABEL = TEXT_ROW_TITLE;

    // --- Motion ------------------------------------------------------------------------------

    public static final long MOTION_DEFAULT_MS = 160L;
    public static final long MOTION_PRESS_MS = 80L;
    public static final float MOTION_OPEN_OVERSHOOT = 0.6f;
    public static final long MOTION_MAX_MS = 240L;

    /**
     * The two motion schemes (U11). Every animated surface reads its duration and easing from
     * here rather than hard-coding a spring, so "Reduce motion" is one switch instead of a hunt
     * through the codebase. {@link #REDUCED} is a short linear fade — still a transition, so a
     * screen does not pop, but with no travel and no overshoot.
     */
    public static final class Motion {
        public final long durationMs;
        public final float damping;
        public final float stiffness;
        public final boolean linear;

        private Motion(long durationMs, float damping, float stiffness, boolean linear) {
            this.durationMs = durationMs;
            this.damping = damping;
            this.stiffness = stiffness;
            this.linear = linear;
        }

        /** Springy, expressive motion (the Material 3 Expressive default). */
        public static final Motion EXPRESSIVE =
                new Motion(MOTION_DEFAULT_MS, 0.78f, 380f, false);
        /** Short, flat motion for Reduce motion / Reduce transparency. */
        public static final Motion REDUCED =
                new Motion(120L, 1f, 0f, true);
    }

    /**
     * The motion scheme to use, honouring the app's Reduce motion switch and the platform's
     * "remove animations" developer/accessibility setting.
     */
    public static Motion motionScheme(boolean reduceMotion, boolean platformAnimationsOff) {
        return (reduceMotion || platformAnimationsOff) ? Motion.REDUCED : Motion.EXPRESSIVE;
    }

    // --- Component geometry (dp) -------------------------------------------------------------

    public static final int TOGGLE_WIDTH = 36;
    public static final int TOGGLE_HEIGHT = 20;
    public static final int TOGGLE_THUMB = 16;
    public static final int TOGGLE_THUMB_INSET = 2;
    public static final int TOGGLE_SLIDE_MS = 140;

    public static final int ROW_HEIGHT = 44;
    public static final int RAIL_WIDTH = 56;
    public static final int RAIL_BUTTON = 40;
    public static final int TITLEBAR_HEIGHT = 48;
    public static final int MIN_TOUCH_TARGET = 48;

    public static final int WINDOW_MAX_WIDTH_DP = 720;
    public static final float WINDOW_WIDTH_FRACTION = 0.66f;
    public static final float WINDOW_HEIGHT_FRACTION = 0.78f;

    /**
     * The accent to draw with: the theme's primary (dynamic colour when the wallpaper provides
     * one, the brand colour otherwise). Falls back to {@link #ACCENT_500} with no context.
     */
    public static int resolveAccent(Context context) {
        if (context == null) return ACCENT_500;
        try {
            int accent = new PersonalizationManager(context).getAccentColor();
            if (accent != 0) return accent;
        } catch (Throwable ignored) {
        }
        return ACCENT_500;
    }

    /** The accent for the pressed/hover state (a lighter tint of the accent). */
    public static int accentHover(int accent) {
        return blend(accent, 0xFFFFFFFF, 0.18f);
    }

    /** The accent blended toward the surface, for a selected/enabled fill. */
    public static int accentWash(int accent, float amount) {
        return blend(BG_SURFACE, accent, amount);
    }

    /** The accent at an explicit alpha. Pure ARGB packing (no android.graphics.Color). */
    public static int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** Linear blend of two colours; {@code amount} is clamped to 0..1. Pure integer maths. */
    public static int blend(int base, int over, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        int br = (base >>> 16) & 0xFF, bg = (base >>> 8) & 0xFF, bb = base & 0xFF;
        int or = (over >>> 16) & 0xFF, og = (over >>> 8) & 0xFF, ob = over & 0xFF;
        int r = (int) (br * (1 - a) + or * a);
        int g = (int) (bg * (1 - a) + og * a);
        int b = (int) (bb * (1 - a) + ob * a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Relative luminance per WCAG, for the contrast-ratio tests. */
    public static double luminance(int color) {
        double r = channel((color >>> 16) & 0xFF);
        double g = channel((color >>> 8) & 0xFF);
        double b = channel(color & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** The WCAG contrast ratio between two opaque colours. */
    public static double contrastRatio(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        double lighter = Math.max(la, lb);
        double darker = Math.min(la, lb);
        return (lighter + 0.05) / (darker + 0.05);
    }
}
