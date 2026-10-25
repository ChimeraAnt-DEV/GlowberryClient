package org.chimeramc.client.core.mods.inbuilt.overlay;

/**
 * The Mod Menu's dark-navy token set (M1).
 *
 * <p>The menu's colours used to be whatever {@code UiTokens} happened to hold, which meant the
 * in-game surface drifted onto the launcher's blueberry palette. This is the menu's own set: a
 * near-black, very slightly blue window (never {@code #000000}) with an off-white text colour
 * (never {@code #FFFFFF}), because pure black and pure white are what make a dark UI read "OLED
 * harsh" rather than "smooth".
 *
 * <p>Two rules the palette encodes rather than documents:
 * <ul>
 *   <li><b>One accent per screen.</b> {@link #ACCENT} appears on the switch track and the focus
 *       ring and nowhere else — no accent headers, no accent card borders.</li>
 *   <li><b>The wash tokens exist so no call site embeds a raw ARGB literal.</b> Android colour
 *       resources cannot express "the accent at 20% alpha", so the translucent variants are named
 *       here ({@link #accentWash}, {@link #ACCENT_MUTED}, {@link #ACCENT_GLOW}).</li>
 * </ul>
 *
 * <p>Deliberately Android-free: the channel maths is done by hand rather than through
 * {@code android.graphics.Color}, because that class is a stub off-device and every rule here —
 * the blend, the alpha mask, the contrast ratio — has to be unit-testable without one.
 */
public final class ModMenuPalette {

    private ModMenuPalette() {
    }

    /** App background and modal scrim. */
    public static final int BG_BASE = 0xFF070A0F;
    /** Menu panel base. */
    public static final int BG_SURFACE_1 = 0xFF0B1118;
    /** Cards and rows. */
    public static final int BG_SURFACE_2 = 0xFF101823;
    /** Hover / pressed / selected row. */
    public static final int BG_SURFACE_3 = 0xFF16202D;
    /** 0.5dp dividers and glass hairlines. */
    public static final int BORDER_HAIRLINE = 0xFF1E2A38;

    // --- The theme presets' surfaces (M6) ------------------------------------------------------

    /**
     * The True Black preset's surfaces.
     *
     * <p>This is the one place pure black is correct: the preset exists precisely so an OLED panel
     * can switch the pixels off, which is the whole point of choosing it. The default navy set
     * still avoids {@code #000000}, because on an LCD "OLED harsh" is a defect, not a feature.
     */
    public static final int BLACK_BG_BASE = 0xFF000000;
    public static final int BLACK_BG_SURFACE_1 = 0xFF000000;
    public static final int BLACK_BG_SURFACE_2 = 0xFF0A0A0A;
    public static final int BLACK_BG_SURFACE_3 = 0xFF141414;

    /**
     * The light surfaces for the Follow System preset on a light device.
     *
     * <p>Follow System has to resolve to something real on both sides: a "system" preset that only
     * ever produced the dark set would be a lie, and the menu is drawn over a game that may be in
     * any lighting.
     */
    public static final int LIGHT_BG_BASE = 0xFFF2F5FA;
    public static final int LIGHT_BG_SURFACE_1 = 0xFFFFFFFF;
    public static final int LIGHT_BG_SURFACE_2 = 0xFFFFFFFF;
    public static final int LIGHT_BG_SURFACE_3 = 0xFFE7ECF4;
    public static final int LIGHT_TEXT_PRIMARY = 0xFF0F141B;
    public static final int LIGHT_TEXT_SECONDARY = 0xFF47566A;
    public static final int LIGHT_TEXT_TERTIARY = 0xFF6C7A8B;

    /** Module names. */
    public static final int TEXT_PRIMARY = 0xFFE8EEF6;
    /** Descriptions and values. */
    public static final int TEXT_SECONDARY = 0xFF93A3B8;
    /** Section labels and disabled text. */
    public static final int TEXT_TERTIARY = 0xFF5C6B7E;

    /** Active switch track and focus ring. */
    public static final int ACCENT = 0xFF3E6FF0;
    /** Switch track in the off state and tinted chips. */
    public static final int ACCENT_MUTED = 0xFF1A2B4D;
    /** Optional 8dp outer glow on an active switch. */
    public static final int ACCENT_GLOW = 0x403E6FF0;

    /** Loaded / healthy. */
    public static final int STATE_SUCCESS = 0xFF35DC84;
    /** Degraded / unsupported build. */
    public static final int STATE_WARN = 0xFFFFC944;
    /** Failed to load. */
    public static final int STATE_ERROR = 0xFFFF5C5C;

    /** The track fill of an off switch, as a fraction of {@link #ACCENT_MUTED}'s alpha. */
    public static final float ACCENT_MUTED_TRACK_ALPHA = 0.60f;

    /** The scrim painted under the panel, over the game. */
    public static final int PANEL_SCRIM = 0x66070A0F;

    /** The white tint laid over the glass for each intensity step (M4). */
    public static final float TINT_OFF = 0f;
    public static final float TINT_SUBTLE = 0.06f;
    public static final float TINT_FULL = 0.14f;

    /**
     * Blends {@code over} onto {@code under} by {@code fraction}.
     *
     * <p>Used for the accent washes and the muted switch track so no call site has to write a
     * literal alpha. {@code fraction} is clamped, so a bad setting cannot produce an out-of-range
     * channel.
     */
    public static int blend(int under, int over, float fraction) {
        float f = Math.max(0f, Math.min(1f, fraction));
        int r = Math.round(red(under) + (red(over) - red(under)) * f);
        int g = Math.round(green(under) + (green(over) - green(under)) * f);
        int b = Math.round(blue(under) + (blue(over) - blue(under)) * f);
        return argb(alpha(under), r, g, b);
    }

    /** The red channel of an ARGB colour. */
    public static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    /** The green channel of an ARGB colour. */
    public static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    /** The blue channel of an ARGB colour. */
    public static int blue(int color) {
        return color & 0xFF;
    }

    /** The alpha channel of an ARGB colour. */
    public static int alpha(int color) {
        return (color >>> 24) & 0xFF;
    }

    /** Packs four clamped channels into an ARGB colour. */
    public static int argb(int a, int r, int g, int b) {
        return (clampChannel(a) << 24) | (clampChannel(r) << 16)
                | (clampChannel(g) << 8) | clampChannel(b);
    }

    /** The same colour at a new alpha (0..255), keeping its channels. */
    public static int withAlpha(int color, int alpha) {
        return (clampChannel(alpha) << 24) | (color & 0x00FFFFFF);
    }

    /**
     * The panel's fill for a glass intensity (M4).
     *
     * <p>Reuses the wheel's own regular tier — the same fill alpha and the same white tint — so the
     * Mod Menu panel and the radial wheel are the same material rather than two similar ones. A
     * solid intensity returns the opaque surface: with no blur behind it, a translucent panel would
     * just show the game through the menu's own text.
     */
    public static int panelFill(Scheme scheme, ModMenuSettings.GlassIntensity glass) {
        if (scheme == null) return BG_SURFACE_1;
        if (glass == null || glass == ModMenuSettings.GlassIntensity.OFF) return scheme.surface1;
        int base = withAlpha(scheme.surface1, RadialWheelMaterial.REGULAR_FILL_ALPHA);
        return blend(base, 0xFFFFFFFF, RadialWheelMaterial.REGULAR_TINT);
    }

    private static int clampChannel(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /** The accent at {@code alpha} (0..255). */
    public static int accentAlpha(int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (ACCENT & 0x00FFFFFF);
    }

    /** A translucent accent wash over a surface, for a selected row or a tinted chip. */
    public static int accentWash(int surface, float fraction) {
        return blend(surface, ACCENT, fraction);
    }

    /** The off switch's track: the muted accent at {@link #ACCENT_MUTED_TRACK_ALPHA}. */
    public static int mutedTrack() {
        int alpha = Math.round(255 * ACCENT_MUTED_TRACK_ALPHA);
        return (alpha << 24) | (ACCENT_MUTED & 0x00FFFFFF);
    }

    /** The state dot colour for a row's load state (M10). */
    public static int dotFor(ModMenuRowState state) {
        switch (state) {
            case FAILED:
            case CRASHED:
                return STATE_ERROR;
            case UNSUPPORTED:
                return STATE_WARN;
            default:
                return 0;
        }
    }

    /** The tint laid over the glass for a glass-intensity step (M4). */
    public static float tintFor(ModMenuSettings.GlassIntensity intensity) {
        switch (intensity) {
            case OFF:
                return TINT_OFF;
            case SUBTLE:
                return TINT_SUBTLE;
            case FULL:
            default:
                return TINT_FULL;
        }
    }

    /**
     * The surface and text set a theme preset resolves to (M6).
     *
     * <p>Pure so the three presets are testable without a device: the theme was previously stored
     * and shown in the settings tab but never read, so choosing True Black or Follow System changed
     * nothing on screen. Resolving here — rather than branching at each call site — is what makes
     * the preset a single decision.
     */
    public static final class Scheme {
        public final int base;
        public final int surface1;
        public final int surface2;
        public final int surface3;
        public final int textPrimary;
        public final int textSecondary;
        public final int textTertiary;
        /** True when the scheme is light, so a caller can pick light-appropriate strokes. */
        public final boolean light;

        Scheme(int base, int surface1, int surface2, int surface3, int textPrimary,
               int textSecondary, int textTertiary, boolean light) {
            this.base = base;
            this.surface1 = surface1;
            this.surface2 = surface2;
            this.surface3 = surface3;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.textTertiary = textTertiary;
            this.light = light;
        }
    }

    /** The dark-navy scheme, the default (M1). */
    public static final Scheme NAVY_SCHEME = new Scheme(BG_BASE, BG_SURFACE_1, BG_SURFACE_2,
            BG_SURFACE_3, TEXT_PRIMARY, TEXT_SECONDARY, TEXT_TERTIARY, false);

    /** The True Black scheme for OLED panels. */
    public static final Scheme BLACK_SCHEME = new Scheme(BLACK_BG_BASE, BLACK_BG_SURFACE_1,
            BLACK_BG_SURFACE_2, BLACK_BG_SURFACE_3, TEXT_PRIMARY, TEXT_SECONDARY, TEXT_TERTIARY,
            false);

    /** The light scheme. */
    public static final Scheme LIGHT_SCHEME = new Scheme(LIGHT_BG_BASE, LIGHT_BG_SURFACE_1,
            LIGHT_BG_SURFACE_2, LIGHT_BG_SURFACE_3, LIGHT_TEXT_PRIMARY, LIGHT_TEXT_SECONDARY,
            LIGHT_TEXT_TERTIARY, true);

    /**
     * Resolves a theme preset to its scheme (M6).
     *
     * @param systemIsDark whether the platform is currently in dark mode, which only matters for
     *                     {@link ModMenuSettings.Theme#SYSTEM}
     */
    public static Scheme schemeFor(ModMenuSettings.Theme theme, boolean systemIsDark) {
        if (theme == ModMenuSettings.Theme.TRUE_BLACK) return BLACK_SCHEME;
        if (theme == ModMenuSettings.Theme.SYSTEM) {
            return systemIsDark ? NAVY_SCHEME : LIGHT_SCHEME;
        }
        return NAVY_SCHEME;
    }

    /**
     * The WCAG relative luminance of a colour, for the contrast rule.
     *
     * <p>Pinned by the test so the menu's legibility claim is arithmetic, not opinion.
     */
    public static double luminance(int color) {
        double r = channel(red(color));
        double g = channel(green(color));
        double b = channel(blue(color));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int value) {
        double v = value / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
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
