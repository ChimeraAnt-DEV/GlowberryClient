package org.chimeramc.client.core.mods.inbuilt.overlay;

/**
 * The radial wheel's glass material, as pure arithmetic (R2, R8, R9, R10, R12).
 *
 * <p>The wheel floats over a live game, so its surface is glass in three named thicknesses rather
 * than one arbitrary translucency. Every input that changes what can actually be drawn — the API
 * level ({@code RenderEffect} is API 31+), whether a backdrop capture succeeded, the OS
 * "reduce transparency" and "high contrast" settings, the device's thermal state and the FPS
 * optimizer's blur budget — is resolved here into one {@link Resolved} value. The view then paints
 * that value and owns no rules of its own, which is what makes "the wheel went solid on my phone" a
 * testable decision instead of a mystery.
 *
 * <p>Android-free: the API level and the capability flags are passed in, so every branch is unit
 * tested without a device.
 */
public final class RadialWheelMaterial {

    private RadialWheelMaterial() {
    }

    /** The three glass thicknesses (R2), plus the solid fallback when no blur is possible. */
    public enum Tier {
        /** Header bars: the lightest blur, the clearest fill. */
        ULTRA_THIN,
        /** The panel backdrop: the middle thickness. */
        REGULAR,
        /** A raised sheet over the panel: the heaviest blur. */
        THICK,
        /** No blur available: an opaque-ish fill with a hairline, still legible. */
        SOLID
    }

    /** The layer a surface is drawn as, which picks its default tier. */
    public enum Role {
        /** The whole panel backdrop behind the wheel. */
        PANEL,
        /** A header bar inside the panel. */
        HEADER,
        /** A sector of the ring. */
        SEGMENT
    }

    /** Blur radii in dp, one per tier (R2). */
    public static final float ULTRA_THIN_BLUR_DP = 24f;
    public static final float REGULAR_BLUR_DP = 32f;
    public static final float THICK_BLUR_DP = 40f;
    /** The solid tier's nominal radius; it is never blurred. */
    public static final float SOLID_BLUR_DP = 0f;

    /** White tint alpha, one per tier (R2). */
    public static final float ULTRA_THIN_TINT = 0.08f;
    public static final float REGULAR_TINT = 0.14f;
    public static final float THICK_TINT = 0.26f;
    /** The solid tier carries no white tint; its legibility comes from the fill. */
    public static final float SOLID_TINT = 0.0f;

    /**
     * The black scrim floor (R2): every tier sits on at least this much black, so a very bright
     * frame cannot wash the ring's labels out. Without a floor the thinnest tier disappears over a
     * snow biome.
     */
    public static final float SCRIM_FLOOR_ALPHA = 0.12f;

    /** The minimum contrast ratio a label needs against the worst-case frame (R9). */
    public static final float MIN_CONTRAST = 4.5f;

    /** {@code RenderEffect} and the blur-backed capture path arrive at API 31 (R12). */
    public static final int API_RENDER_EFFECT = 31;

    /** The solid fill alpha for a fallback surface: legible over any frame. */
    public static final int SOLID_FILL_ALPHA = 0xE6;
    /** The glass fill alpha at each tier, before the white tint is laid over it. */
    public static final int ULTRA_THIN_FILL_ALPHA = 0x66;
    public static final int REGULAR_FILL_ALPHA = 0x8C;
    public static final int THICK_FILL_ALPHA = 0xB3;

    /** How far the blur budget can be degraded under thermal pressure (R8). */
    public enum BlurBudget {
        /** Full blur, refreshed at the configured rate. */
        FULL,
        /** Cheaper: the heavier tiers collapse to the lightest blur. */
        REDUCED,
        /** No blur at all: every tier draws as SOLID. */
        NONE
    }

    /** The default blur refresh ceiling, in frames per second (R3). */
    public static final int BLUR_FPS_DEFAULT = 5;
    /** The highest refresh a user may pick; past this the capture costs more than it adds (R3). */
    public static final int BLUR_FPS_MAX = 10;

    /**
     * Everything that can change how the wheel is drawn.
     *
     * <p>An explicit holder rather than a long parameter list, so a new signal is one field and the
     * callers that do not care about it are unaffected.
     */
    public static final class Env {
        public int apiLevel = API_RENDER_EFFECT;
        /** Whether a {@code PixelCopy} of the window succeeded (R3/R12). */
        public boolean pixelCopyOk = true;
        /** The OS "reduce transparency" setting (R10). */
        public boolean reduceTransparency;
        /** The OS "high contrast" setting (R10). */
        public boolean highContrast;
        /** Platform thermal severity, 0 (cool) upward. */
        public int thermalSeverity;
        public boolean batterySaver;
        public boolean lowMemory;
        /** Whether a game session is running; the budget only matters then. */
        public boolean sessionActive;
        /** The app's own reduce-motion switch (R10). */
        public boolean reduceMotion;
        /** The configured blur refresh ceiling in fps (R3). */
        public int blurFps = BLUR_FPS_DEFAULT;
        /**
         * The blur budget decided by the launcher's FPS Optimization coordinator (R8).
         *
         * <p>When set, it wins over the local thermal reading: the coordinator is the single place
         * that decides how hard the launcher may work during a session, and the wheel must not
         * re-derive a different answer from the same sensors.
         */
        public BlurBudget budgetOverride;

        public Env apiLevel(int level) {
            this.apiLevel = level;
            return this;
        }

        public Env pixelCopyOk(boolean ok) {
            this.pixelCopyOk = ok;
            return this;
        }

        public Env reduceTransparency(boolean reduce) {
            this.reduceTransparency = reduce;
            return this;
        }

        public Env highContrast(boolean high) {
            this.highContrast = high;
            return this;
        }

        public Env thermal(int severity, boolean batterySaver, boolean lowMemory) {
            this.thermalSeverity = severity;
            this.batterySaver = batterySaver;
            this.lowMemory = lowMemory;
            return this;
        }

        public Env sessionActive(boolean active) {
            this.sessionActive = active;
            return this;
        }

        public Env reduceMotion(boolean reduce) {
            this.reduceMotion = reduce;
            return this;
        }

        public Env blurFps(int fps) {
            this.blurFps = fps;
            return this;
        }

        public Env budget(BlurBudget budget) {
            this.budgetOverride = budget;
            return this;
        }
    }

    /** The material the wheel should draw with, after every fallback has been applied. */
    public static final class Resolved {
        public final Tier tier;
        public final float blurDp;
        /** The white wash laid over the blurred backdrop. */
        public final float tint;
        /** The panel fill alpha (the dark base under the blur and the tint). */
        public final int fillAlpha;
        /** The black scrim floor drawn behind everything. */
        public final float scrimAlpha;
        public final int blurFps;
        public final boolean outlinedLabels;
        public final boolean reducedMotion;

        Resolved(Tier tier, float blurDp, float tint, int fillAlpha, float scrimAlpha, int blurFps,
                 boolean outlinedLabels, boolean reducedMotion) {
            this.tier = tier;
            this.blurDp = blurDp;
            this.tint = tint;
            this.fillAlpha = fillAlpha;
            this.scrimAlpha = scrimAlpha;
            this.blurFps = blurFps;
            this.outlinedLabels = outlinedLabels;
            this.reducedMotion = reducedMotion;
        }

        /** True when a real blur is in play (the caller may skip the capture otherwise). */
        public boolean blurs() {
            return blurDp > 0f;
        }
    }

    /** The default tier for a layer role. */
    public static Tier tierFor(Role role) {
        switch (role) {
            case HEADER:
                return Tier.ULTRA_THIN;
            case SEGMENT:
                return Tier.THICK;
            case PANEL:
            default:
                return Tier.REGULAR;
        }
    }

    /** The blur radius for a tier, in dp. */
    public static float blurDp(Tier tier) {
        switch (tier) {
            case ULTRA_THIN: return ULTRA_THIN_BLUR_DP;
            case REGULAR: return REGULAR_BLUR_DP;
            case THICK: return THICK_BLUR_DP;
            default: return SOLID_BLUR_DP;
        }
    }

    /** The dark panel fill alpha for a tier. */
    public static int fillAlpha(Tier tier) {
        switch (tier) {
            case ULTRA_THIN: return ULTRA_THIN_FILL_ALPHA;
            case REGULAR: return REGULAR_FILL_ALPHA;
            case THICK: return THICK_FILL_ALPHA;
            default: return SOLID_FILL_ALPHA;
        }
    }

    /** The white tint alpha for a tier. */
    public static float tint(Tier tier) {
        switch (tier) {
            case ULTRA_THIN: return ULTRA_THIN_TINT;
            case REGULAR: return REGULAR_TINT;
            case THICK: return THICK_TINT;
            default: return SOLID_TINT;
        }
    }

    /**
     * The blur budget for the current device state (R8).
     *
     * <p>This is the wheel's half of the thermal ladder: it reads the same signals the FPS
     * optimizer coordinates, so the wheel cannot keep paying for a per-open blur capture while the
     * launcher has already decided the host is under pressure. The budget only bites during a
     * session — with no game running there is nothing to protect.
     */
    public static BlurBudget budgetFor(int thermalSeverity, boolean batterySaver, boolean lowMemory,
                                       boolean sessionActive) {
        if (!sessionActive) return BlurBudget.FULL;
        if (thermalSeverity >= 3 || batterySaver || lowMemory) return BlurBudget.NONE;
        if (thermalSeverity >= 1) return BlurBudget.REDUCED;
        return BlurBudget.FULL;
    }

    /** Degrades a tier to fit the budget. */
    public static Tier degrade(Tier tier, BlurBudget budget) {
        if (tier == null || tier == Tier.SOLID) return Tier.SOLID;
        switch (budget) {
            case NONE:
                return Tier.SOLID;
            case REDUCED:
                // The heavy tiers step down to the lightest blur; the lightest stays put.
                return tier == Tier.THICK ? Tier.ULTRA_THIN : tier;
            case FULL:
            default:
                return tier;
        }
    }

    /**
     * Resolves the material to draw with, applying the API-level, capability, accessibility and
     * thermal fallbacks in a fixed order (R8, R10, R12).
     *
     * <p>Order matters and is deliberate: the API/capability checks decide whether a blur can exist
     * at all, the accessibility settings then override the user's preference (an in-app choice must
     * never beat the OS), and the thermal ladder is applied last because it is a performance
     * concession, not a preference.
     */
    public static Resolved resolve(Tier requested, Env env) {
        Env e = env == null ? new Env() : env;
        Tier tier = requested == null ? Tier.REGULAR : requested;

        // R12: no blur without RenderEffect, and none without a successful capture.
        if (e.apiLevel < API_RENDER_EFFECT || !e.pixelCopyOk) {
            tier = Tier.SOLID;
        }
        // R10: the OS accessibility settings win over the in-app choice.
        if (e.reduceTransparency) {
            tier = Tier.SOLID;
        }
        // R8: the coordinator's budget when it supplied one, else the local pressure reading.
        BlurBudget budget = e.budgetOverride != null ? e.budgetOverride
                : budgetFor(e.thermalSeverity, e.batterySaver, e.lowMemory, e.sessionActive);
        tier = degrade(tier, budget);

        int fps = Math.max(0, Math.min(BLUR_FPS_MAX, e.blurFps));
        if (tier == Tier.SOLID) fps = 0;
        return new Resolved(tier, blurDp(tier), tint(tier), fillAlpha(tier), SCRIM_FLOOR_ALPHA,
                fps, e.highContrast, e.reduceMotion);
    }

    /**
     * Resolves the material for a known backdrop, raising the scrim to the contrast floor (R9).
     *
     * <p>Used when the wheel has a backdrop sample to judge: the label colour is the wheel's own
     * primary text, and the scrim only rises as far as that pair needs. With no sample the caller
     * uses {@link #resolve} and the fixed floor.
     */
    public static Resolved resolveForBackdrop(Tier requested, Env env, int textColor,
                                              int backdropColor) {
        Resolved base = resolve(requested, env);
        float escalated = escalatedScrim(textColor, backdropColor);
        if (escalated <= base.scrimAlpha) return base;
        return new Resolved(base.tier, base.blurDp, base.tint, base.fillAlpha, escalated,
                base.blurFps, base.outlinedLabels, base.reducedMotion);
    }

    /** The blur refresh interval in milliseconds for a rate, or {@code -1} when not blurred. */
    public static long blurIntervalMs(int fps) {
        if (fps <= 0) return -1L;
        return Math.max(1000L / BLUR_FPS_MAX, 1000L / fps);
    }

    /**
     * Which blur mechanism is available at an API level (R12).
     *
     * <p>This is the one place the two routes are chosen, so the renderer cannot decide one way for
     * the layer and another way for the canvas. {@code RenderEffect} arrives at API 31; below that
     * the only mechanism is the downscale/upscale stack blur.
     */
    public enum BlurRoute {
        /** Nothing to blur: a solid tier, or no capture. */
        NONE,
        /** API 28-30: the canvas stack blur. */
        STACK,
        /** API 31+: a {@code RenderEffect} on the backdrop layer. */
        RENDER_EFFECT
    }

    /** The blur route for an API level and a resolved material (R12). */
    public static BlurRoute blurRoute(int apiLevel, Resolved material) {
        if (material == null || !material.blurs()) return BlurRoute.NONE;
        return apiLevel >= API_RENDER_EFFECT ? BlurRoute.RENDER_EFFECT : BlurRoute.STACK;
    }

    /**
     * Whether a label at {@code textColor} is legible over {@code backdropColor} (R9).
     */
    public static boolean meetsContrast(int textColor, int backdropColor) {
        return contrastRatio(textColor, backdropColor) >= MIN_CONTRAST;
    }

    /**
     * The extra black scrim a label needs to reach the contrast floor over a frame, or 0 when the
     * frame is already dark enough (R9).
     *
     * <p>The escalation has to be <em>black</em>, not more white tint: the wheel's labels are light,
     * so over a bright frame the only direction that helps is darker. Raising the white tint over a
     * white frame changes nothing at all — which is exactly the defect this method exists to avoid.
     *
     * <p>Auto-escalation is the difference between "the labels are hard to read in the snow" and a
     * support ticket: the scrim rises only as far as it needs to, so the glass stays glass.
     */
    public static float escalatedScrim(int textColor, int backdropColor) {
        if (meetsContrast(textColor, backdropColor)) return 0f;
        // Walk the scrim up in steps until the floor is met; the loop is bounded so a pathological
        // pair cannot spin, and the final value is the last one tried.
        for (int i = 1; i <= 10; i++) {
            float t = i / 10f;
            int filled = composite(backdropColor, 0xFF000000, t);
            if (meetsContrast(textColor, filled)) return t;
        }
        return 1f;
    }

    /** Alpha-composites {@code over} at {@code amount} onto {@code base}. */
    public static int composite(int base, int over, float amount) {
        return org.chimeramc.client.ui.components.UiTokens.blend(base, over, amount);
    }

    /** The WCAG contrast ratio between two colours (delegates to the shared token maths). */
    public static double contrastRatio(int a, int b) {
        return org.chimeramc.client.ui.components.UiTokens.contrastRatio(a, b);
    }

    /**
     * The squircle corner's control-point ratio for a squareness in {@code 0..1} (R1/R13).
     *
     * <p>A circular fillet uses the classic 0.5523 ratio; a squircle pulls the control points out
     * toward the corner. The value is exposed because the path builder is not unit-testable and this
     * is the part of the shape that is.
     */
    public static float squircleControl(float squareness) {
        float s = Math.max(0f, Math.min(1f, squareness));
        return 0.5523f + s * (1f - 0.5523f);
    }
}
