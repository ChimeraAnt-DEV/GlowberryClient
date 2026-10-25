package org.chimeramc.client.core.mods.inbuilt.overlay;

/**
 * The radial wheel's motion, haptic, label and cancel rules, as pure values (R4-R7, R11).
 *
 * <p>A wheel is judged by how it feels, and "feel" is exactly the part that is normally buried in a
 * view's {@code ValueAnimator} calls where nothing can test it. The spring targets, the haptic
 * vocabulary, the label truncation and the cancel threshold are named here so the view only applies
 * them, and so a change to one of them cannot silently disagree with the rest (the open scale and
 * the selection scale must stay in a fixed relationship or the ring looks like it twitches).
 *
 * <p>Android-free: the haptic "what to play" decision is a plain enum, so the choice is unit tested
 * and the view maps it onto {@code HapticFeedbackConstants}.
 */
public final class RadialWheelMotion {

    private RadialWheelMotion() {
    }

    // --- Spring targets (R4) ------------------------------------------------------------------

    /** The ring's scale at the start of the open animation. */
    public static final float OPEN_FROM_SCALE = 0.86f;
    /** The ring's resting scale once open. */
    public static final float OPEN_TO_SCALE = 1.0f;
    public static final float OPEN_DAMPING = 0.78f;
    public static final float OPEN_STIFFNESS = 380f;

    /** The scale a sector springs to when the pointer selects it. */
    public static final float SELECT_SCALE = 1.06f;
    public static final float SELECT_DAMPING = 0.7f;
    public static final float SELECT_STIFFNESS = 500f;

    /** The brief overshoot applied on commit, and how long it lasts. */
    public static final float SETTLE_SCALE = 1.14f;
    public static final long SETTLE_MS = 90L;

    /** The reduced-motion duration for every wheel transition (R10). */
    public static final long REDUCED_MS = 120L;

    /**
     * The scale the ring should be at during the open animation.
     *
     * <p>A pure lerp so the view's animator only feeds a fraction; the endpoints live here so the
     * open and the close cannot use different numbers.
     */
    public static float openScale(float fraction) {
        float f = Math.max(0f, Math.min(1f, fraction));
        return OPEN_FROM_SCALE + (OPEN_TO_SCALE - OPEN_FROM_SCALE) * f;
    }

    /** The selection scale for a sector: the resting scale unless it is selected. */
    public static float sectorScale(boolean selected) {
        return selected ? SELECT_SCALE : 1f;
    }

    // --- Haptics (R5) -------------------------------------------------------------------------

    /** The haptic vocabulary. The view maps these onto the platform constants. */
    public enum Haptic {
        /** Nothing: opening the wheel must not buzz. */
        NONE,
        /** A light tick when the pointer crosses into a new segment. */
        TICK,
        /** A medium pulse when a selection is committed. */
        COMMIT
    }

    /**
     * The haptic for a wheel event.
     *
     * @param opened          the wheel just opened
     * @param crossedSegment  the pointer moved into a different segment
     * @param committed       a selection was confirmed
     */
    public static Haptic hapticFor(boolean opened, boolean crossedSegment, boolean committed) {
        // Commit outranks everything: it is the only signal that must never be missed.
        if (committed) return Haptic.COMMIT;
        if (crossedSegment) return Haptic.TICK;
        return Haptic.NONE;
    }

    // --- Labels and hub (R6, R7) ---------------------------------------------------------------

    /** The segment icon size in dp (R6). */
    public static final float ICON_DP = 24f;
    /** The segment label size in sp (R6). */
    public static final float LABEL_SP = 11f;
    /** The label weight axis value (R6). */
    public static final int LABEL_WEIGHT = 500;
    /** A label longer than this is ellipsised, because the segment cannot grow to fit it (R6). */
    public static final int LABEL_MAX_CHARS = 9;

    /** The hub's squircle head size in dp (R7). */
    public static final float HUB_HEAD_DP = 40f;
    /** The hub's active-label size in sp (R7). */
    public static final float HUB_LABEL_SP = 10f;
    /** The hub label's white alpha (R7). */
    public static final float HUB_LABEL_ALPHA = 0.60f;

    /**
     * Truncates a label to the segment's budget, appending an ellipsis (R6).
     *
     * <p>Deliberately a character budget rather than a pixel measurement: the label is drawn
     * centred in a wedge, and a measured ellipsis would make two sectors with the same word
     * truncate at different points depending on their screen angle. A null label reads as empty.
     */
    public static String ellipsise(String label) {
        if (label == null) return "";
        String trimmed = label.trim();
        if (trimmed.length() <= LABEL_MAX_CHARS) return trimmed;
        return trimmed.substring(0, LABEL_MAX_CHARS - 1) + "\u2026";
    }

    // --- Cancel gesture (R11) -----------------------------------------------------------------

    /** How far past the outer radius a drag must go before it reads as a cancel. */
    public static final float CANCEL_MARGIN_DP = 24f;
    /** The ring's opacity while the cancel gesture is armed. */
    public static final float CANCEL_OPACITY = 0.4f;
    /** The ring's scale while the cancel gesture is armed. */
    public static final float CANCEL_SCALE = 0.94f;

    /**
     * Whether a drag distance from the wheel centre has armed the cancel gesture (R11).
     *
     * <p>The margin is a constant linear distance, not a fraction of the radius: a fraction would
     * make the gesture harder on a small screen exactly where it is most needed.
     */
    public static boolean isCancelArmed(float distancePx, float outerRadiusPx, float density) {
        float margin = CANCEL_MARGIN_DP * Math.max(0.5f, density);
        return distancePx > outerRadiusPx + margin;
    }

    /** The ring's opacity for a cancel-armed state. */
    public static float cancelOpacity(boolean armed) {
        return armed ? CANCEL_OPACITY : 1f;
    }

    /** The ring's scale for a cancel-armed state. */
    public static float cancelScale(boolean armed) {
        return armed ? CANCEL_SCALE : 1f;
    }

    // --- Segment geometry (R1) -----------------------------------------------------------------

    /**
     * The most segments the wheel will draw; past this the wedges are too thin to read (R1 caps the
     * wheel at 6-8). Shared with the profile wheel's own cap so the two cannot drift.
     */
    public static final int MAX_SEGMENTS = WheelLayout.MAX_SECTORS;
    /** The fewest a wheel may be built with; below this it is a pair of buttons. */
    public static final int MIN_SEGMENTS = 2;
    /** The minimum arc length of a segment's outer edge, in dp (R1's 48dp target). */
    public static final float MIN_SEGMENT_ARC_DP = 48f;
    /** The annulus' inner edge (R1), re-exported from the layout so there is one definition. */
    public static final float ANNULUS_INNER_FRACTION = RadialWheelLayout.ANNULUS_INNER_FRACTION;
    /** The annulus' outer edge (R1), re-exported from the layout so there is one definition. */
    public static final float ANNULUS_OUTER_FRACTION = RadialWheelLayout.ANNULUS_OUTER_FRACTION;

    /** Clamps a requested segment count into the drawable range. */
    public static int clampSegments(int requested) {
        return Math.max(MIN_SEGMENTS, Math.min(MAX_SEGMENTS, requested));
    }

    /** The arc length of one segment's outer edge, in px. */
    public static float segmentArcPx(float outerRadiusPx, int segments) {
        int count = clampSegments(segments);
        return (float) (2.0 * Math.PI * outerRadiusPx / count);
    }

    /**
     * Whether a wheel of this many segments has 48dp targets at this radius (R1).
     *
     * <p>If it does not, the caller must reduce the count rather than draw targets the player
     * cannot hit; that is why this is a query and not an assertion.
     */
    public static boolean hasTouchTargets(float outerRadiusPx, int segments, float density) {
        return segmentArcPx(outerRadiusPx, segments) >= MIN_SEGMENT_ARC_DP * Math.max(0.5f, density);
    }

    /** The largest segment count that still gives every segment a 48dp target at this radius. */
    public static int maxSegmentsForRadius(float outerRadiusPx, float density) {
        for (int n = MAX_SEGMENTS; n > MIN_SEGMENTS; n--) {
            if (hasTouchTargets(outerRadiusPx, n, density)) return n;
        }
        return MIN_SEGMENTS;
    }

    /**
     * The segment count a wheel actually draws and hit-tests for a given entry count (R1).
     *
     * <p>The entry count is capped at {@link #MAX_SEGMENTS}, then reduced further if the screen is
     * too small to give every segment a 48dp target — R1 requires the wheel to drop segments rather
     * than draw targets the player cannot hit. A one-entry wheel is a ring and is never reduced.
     *
     * <p>This is the single rule the draw path and the hit test both read. When they compute the
     * count separately, a tap lands on the wrong sector, and that is a defect a screenshot cannot
     * catch.
     */
    public static int segmentsFor(int entryCount, float outerRadiusPx, float density) {
        if (entryCount <= 1) return Math.max(0, entryCount);
        int requested = clampSegments(entryCount);
        int fits = maxSegmentsForRadius(outerRadiusPx, density);
        return Math.max(MIN_SEGMENTS, Math.min(requested, fits));
    }
}
