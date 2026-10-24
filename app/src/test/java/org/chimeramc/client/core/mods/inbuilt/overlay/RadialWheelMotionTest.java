package org.chimeramc.client.core.mods.inbuilt.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import org.chimeramc.client.core.mods.inbuilt.overlay.RadialWheelMotion.Haptic;

/**
 * Pins the radial wheel's motion, haptic, label, cancel and segment-target rules (R1, R4-R7, R11).
 *
 * <p>These are the "feel" values that normally live inside a view's animator calls where nothing can
 * check them. Keeping them pure is what makes "the ring twitches", "the haptics are wrong" and "the
 * segments are too thin to hit" testable claims instead of opinions.
 */
public class RadialWheelMotionTest {

    // --- Springs (R4) --------------------------------------------------------------------------

    @Test
    public void theOpenAnimationRunsFromTheSpecStartToRest() {
        assertEquals(0.86f, RadialWheelMotion.OPEN_FROM_SCALE, 1e-6);
        assertEquals(1.0f, RadialWheelMotion.OPEN_TO_SCALE, 1e-6);
        assertEquals(0.78f, RadialWheelMotion.OPEN_DAMPING, 1e-6);
        assertEquals(380f, RadialWheelMotion.OPEN_STIFFNESS, 1e-6);
    }

    @Test
    public void theSelectionSpringMatchesTheSpec() {
        assertEquals(1.06f, RadialWheelMotion.SELECT_SCALE, 1e-6);
        assertEquals(0.7f, RadialWheelMotion.SELECT_DAMPING, 1e-6);
        assertEquals(500f, RadialWheelMotion.SELECT_STIFFNESS, 1e-6);
        assertEquals(1.14f, RadialWheelMotion.SETTLE_SCALE, 1e-6);
        assertEquals(90L, RadialWheelMotion.SETTLE_MS);
    }

    @Test
    public void theOpenScaleIsAMonotonicLerpBetweenItsEndpoints() {
        assertEquals(0.86f, RadialWheelMotion.openScale(0f), 1e-6);
        assertEquals(1.0f, RadialWheelMotion.openScale(1f), 1e-6);
        float mid = RadialWheelMotion.openScale(0.5f);
        assertTrue(mid > 0.86f && mid < 1f);
        // Out-of-range fractions clamp rather than overshoot past the endpoints.
        assertEquals(0.86f, RadialWheelMotion.openScale(-2f), 1e-6);
        assertEquals(1.0f, RadialWheelMotion.openScale(7f), 1e-6);
    }

    @Test
    public void theSettleOvershootIsLargerThanTheSelectionScale() {
        // The commit pulse has to read as more than the hover, or the two blur together.
        assertTrue(RadialWheelMotion.SETTLE_SCALE > RadialWheelMotion.SELECT_SCALE);
        assertTrue(RadialWheelMotion.SELECT_SCALE > RadialWheelMotion.OPEN_TO_SCALE);
    }

    @Test
    public void theSectorScaleIsTheSelectionScaleOnlyWhenSelected() {
        assertEquals(RadialWheelMotion.SELECT_SCALE, RadialWheelMotion.sectorScale(true), 1e-6);
        assertEquals(1f, RadialWheelMotion.sectorScale(false), 1e-6);
    }

    @Test
    public void reducedMotionIsAShortLinearTransition() {
        assertEquals(120L, RadialWheelMotion.REDUCED_MS);
        assertTrue(RadialWheelMotion.REDUCED_MS < RadialWheelMotion.SETTLE_MS * 4);
    }

    // --- Haptics (R5) --------------------------------------------------------------------------

    @Test
    public void openingTheWheelDoesNotBuzz() {
        // R5: "none on open". A buzz on open is the classic over-haptic.
        assertEquals(Haptic.NONE, RadialWheelMotion.hapticFor(true, false, false));
    }

    @Test
    public void crossingASegmentGivesALightTick() {
        assertEquals(Haptic.TICK, RadialWheelMotion.hapticFor(false, true, false));
    }

    @Test
    public void committingOutranksEverything() {
        assertEquals(Haptic.COMMIT, RadialWheelMotion.hapticFor(false, false, true));
        assertEquals(Haptic.COMMIT, RadialWheelMotion.hapticFor(false, true, true));
        assertEquals(Haptic.COMMIT, RadialWheelMotion.hapticFor(true, true, true));
    }

    @Test
    public void noEventMeansNoHaptic() {
        assertEquals(Haptic.NONE, RadialWheelMotion.hapticFor(false, false, false));
    }

    // --- Labels and hub (R6, R7) ---------------------------------------------------------------

    @Test
    public void theLabelAndHubMetricsMatchTheSpec() {
        assertEquals(24f, RadialWheelMotion.ICON_DP, 1e-6);
        assertEquals(11f, RadialWheelMotion.LABEL_SP, 1e-6);
        assertEquals(500, RadialWheelMotion.LABEL_WEIGHT);
        assertEquals(40f, RadialWheelMotion.HUB_HEAD_DP, 1e-6);
        assertEquals(10f, RadialWheelMotion.HUB_LABEL_SP, 1e-6);
        assertEquals(0.60f, RadialWheelMotion.HUB_LABEL_ALPHA, 1e-6);
    }

    @Test
    public void aShortLabelIsUntouched() {
        assertEquals("Mods", RadialWheelMotion.ellipsise("Mods"));
        assertEquals("Settings", RadialWheelMotion.ellipsise("Settings"));
    }

    @Test
    public void aLongLabelIsEllipsisedAtTheCharacterBudget() {
        String out = RadialWheelMotion.ellipsise("Trajectory Prediction");
        assertEquals(RadialWheelMotion.LABEL_MAX_CHARS, out.length());
        assertTrue(out.endsWith("\u2026"));
        assertEquals("Trajecto\u2026", out);
    }

    @Test
    public void aNullOrBlankLabelReadsAsEmpty() {
        assertEquals("", RadialWheelMotion.ellipsise(null));
        assertEquals("", RadialWheelMotion.ellipsise("   "));
    }

    @Test
    public void surroundingWhitespaceIsTrimmedBeforeMeasuring() {
        assertEquals("Mods", RadialWheelMotion.ellipsise("  Mods  "));
    }

    // --- Cancel gesture (R11) ------------------------------------------------------------------

    @Test
    public void theCancelMarginIsAConstantLinearDistance() {
        assertEquals(24f, RadialWheelMotion.CANCEL_MARGIN_DP, 1e-6);
        // 24dp at density 3 = 72px of extra travel past the outer radius.
        float outer = 400f;
        assertFalse(RadialWheelMotion.isCancelArmed(outer, outer, 3f));
        assertFalse(RadialWheelMotion.isCancelArmed(outer + 71f, outer, 3f));
        assertTrue(RadialWheelMotion.isCancelArmed(outer + 73f, outer, 3f));
    }

    @Test
    public void aSmallScreenUsesTheSamePhysicalMargin() {
        // The margin is dp, so a low-density device gets proportionally fewer pixels, not a harder
        // gesture.
        float outer = 200f;
        assertFalse(RadialWheelMotion.isCancelArmed(outer + 20f, outer, 1f));
        assertTrue(RadialWheelMotion.isCancelArmed(outer + 26f, outer, 1f));
    }

    @Test
    public void theArmedStateDimsAndShrinksTheRing() {
        assertEquals(1f, RadialWheelMotion.cancelOpacity(false), 1e-6);
        assertEquals(0.4f, RadialWheelMotion.cancelOpacity(true), 1e-6);
        assertEquals(1f, RadialWheelMotion.cancelScale(false), 1e-6);
        assertEquals(0.94f, RadialWheelMotion.cancelScale(true), 1e-6);
    }

    // --- Segment count and targets (R1) --------------------------------------------------------

    @Test
    public void theSegmentCountIsCappedAtEightAndFlooredAtTwo() {
        assertEquals(8, RadialWheelMotion.MAX_SEGMENTS);
        assertEquals(8, RadialWheelMotion.clampSegments(50));
        assertEquals(2, RadialWheelMotion.clampSegments(0));
        assertEquals(6, RadialWheelMotion.clampSegments(6));
    }

    @Test
    public void theSegmentCapIsSharedWithTheProfileWheel() {
        // Two independent caps is how the two wheels drift apart.
        assertEquals(WheelLayout.MAX_SECTORS, RadialWheelMotion.MAX_SEGMENTS);
    }

    @Test
    public void aFewerSegmentsGiveALongerArc() {
        float outer = 400f;
        assertTrue(RadialWheelMotion.segmentArcPx(outer, 6)
                > RadialWheelMotion.segmentArcPx(outer, 8));
    }

    @Test
    public void theSegmentArcIsTheCircumferenceOverTheCount() {
        float outer = 300f;
        assertEquals(2 * Math.PI * outer / 6, RadialWheelMotion.segmentArcPx(outer, 6), 1e-3);
    }

    @Test
    public void eightSegmentsStillClearTheTouchTargetOnATypicalPhone() {
        // R1: 48dp targets. At a 3x density that is 144px of outer arc per segment.
        float density = 3f;
        float outer = 0.46f * 1080f;   // a 1080p short edge
        assertTrue(RadialWheelMotion.hasTouchTargets(outer, 8, density));
        assertTrue(RadialWheelMotion.hasTouchTargets(outer, 6, density));
    }

    @Test
    public void tooManySegmentsOnASmallScreenFailsTheTargetCheck() {
        // A tiny wheel cannot carry eight 48dp targets; the caller must reduce the count.
        float density = 4f;
        float outer = 0.46f * 480f;
        assertFalse(RadialWheelMotion.hasTouchTargets(outer, 8, density));
        assertTrue(RadialWheelMotion.maxSegmentsForRadius(outer, density)
                < RadialWheelMotion.MAX_SEGMENTS);
    }

    @Test
    public void theMaxSegmentsQueryNeverGoesBelowTheFloor() {
        assertEquals(RadialWheelMotion.MIN_SEGMENTS,
                RadialWheelMotion.maxSegmentsForRadius(1f, 8f));
    }

    @Test
    public void theAnnulusFractionsAreReExportedNotDuplicated() {
        // R1's 30%/46% band has one definition, in the layout, and the motion class points at it.
        assertEquals(RadialWheelLayout.ANNULUS_INNER_FRACTION,
                RadialWheelMotion.ANNULUS_INNER_FRACTION, 1e-6);
        assertEquals(RadialWheelLayout.ANNULUS_OUTER_FRACTION,
                RadialWheelMotion.ANNULUS_OUTER_FRACTION, 1e-6);
    }

    @Test
    public void theMinimumSegmentArcIsTheTouchTarget() {
        assertEquals(48f, RadialWheelMotion.MIN_SEGMENT_ARC_DP, 1e-6);
    }

    // --- The count the wheel actually uses (R1) ------------------------------------------------

    @Test
    public void aWheelWithRoomKeepsEveryEntry() {
        // A 400dp-wide wheel has plenty of room for six 48dp segments.
        assertEquals(6, RadialWheelMotion.segmentsFor(6, 400f, 2f));
        // The cap still applies to an over-full profile.
        assertEquals(RadialWheelMotion.MAX_SEGMENTS, RadialWheelMotion.segmentsFor(12, 400f, 2f));
    }

    @Test
    public void aCrampedWheelDropsSegmentsRatherThanDrawingUntouchableOnes() {
        // At 100px radius a 48dp (96px) target fits six ways but not seven or eight, so an
        // eight-entry profile must be drawn as six — and the result must satisfy the target rule.
        assertEquals(6, RadialWheelMotion.segmentsFor(8, 100f, 2f));
        assertTrue(RadialWheelMotion.hasTouchTargets(100f, 6, 2f));
        assertFalse(RadialWheelMotion.hasTouchTargets(100f, 8, 2f));
    }

    @Test
    public void aSingleEntryIsARingAndIsNeverReduced() {
        // One entry draws a full ring, which has no 48dp-per-segment requirement to satisfy.
        assertEquals(1, RadialWheelMotion.segmentsFor(1, 40f, 3f));
        assertEquals(0, RadialWheelMotion.segmentsFor(0, 40f, 3f));
    }

    @Test
    public void theReducedCountNeverGoesBelowTheFloor() {
        // However cramped the wheel, it never drops below two: below that it is not a wheel.
        assertEquals(RadialWheelMotion.MIN_SEGMENTS, RadialWheelMotion.segmentsFor(8, 1f, 4f));
    }

    @Test
    public void theSegmentCountNeverExceedsTheEntriesAvailable() {
        // A four-entry profile must not be drawn as six sectors with two empty wedges.
        assertEquals(4, RadialWheelMotion.segmentsFor(4, 400f, 2f));
    }
}
