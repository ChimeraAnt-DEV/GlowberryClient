package org.chimeramc.client.core.mods.inbuilt.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import org.chimeramc.client.core.mods.inbuilt.overlay.RadialWheelMaterial.BlurBudget;
import org.chimeramc.client.core.mods.inbuilt.overlay.RadialWheelMaterial.Env;
import org.chimeramc.client.core.mods.inbuilt.overlay.RadialWheelMaterial.Resolved;
import org.chimeramc.client.core.mods.inbuilt.overlay.RadialWheelMaterial.Role;
import org.chimeramc.client.core.mods.inbuilt.overlay.RadialWheelMaterial.Tier;
import org.chimeramc.client.ui.components.UiTokens;

/**
 * Pins the radial wheel's glass material and its fallback ladder (R2, R8, R9, R10, R12).
 *
 * <p>These are the decisions that decide whether the wheel looks like glass or like a grey blob on a
 * given phone, and every one of them is a pure function, so none of them needs a device.
 */
public class RadialWheelMaterialTest {

    private static Env cool() {
        return new Env().apiLevel(RadialWheelMaterial.API_RENDER_EFFECT)
                .pixelCopyOk(true)
                .sessionActive(false);
    }

    // --- The three tiers (R2) -----------------------------------------------------------------

    @Test
    public void theThreeTiersHaveTheSpecRadiiAndTints() {
        assertEquals(24f, RadialWheelMaterial.blurDp(Tier.ULTRA_THIN), 1e-6);
        assertEquals(32f, RadialWheelMaterial.blurDp(Tier.REGULAR), 1e-6);
        assertEquals(40f, RadialWheelMaterial.blurDp(Tier.THICK), 1e-6);
        assertEquals(0f, RadialWheelMaterial.blurDp(Tier.SOLID), 1e-6);

        assertEquals(0.08f, RadialWheelMaterial.tint(Tier.ULTRA_THIN), 1e-6);
        assertEquals(0.14f, RadialWheelMaterial.tint(Tier.REGULAR), 1e-6);
        assertEquals(0.26f, RadialWheelMaterial.tint(Tier.THICK), 1e-6);
    }

    @Test
    public void everyTierSitsOnTheScrimFloor() {
        // R2: without a floor the thinnest tier vanishes over a snow biome.
        assertEquals(0.12f, RadialWheelMaterial.SCRIM_FLOOR_ALPHA, 1e-6);
        for (Tier tier : Tier.values()) {
            Resolved r = RadialWheelMaterial.resolve(tier, cool());
            assertEquals(RadialWheelMaterial.SCRIM_FLOOR_ALPHA, r.scrimAlpha, 1e-6);
        }
    }

    @Test
    public void theLayerRolesPickTheRightTier() {
        assertEquals(Tier.REGULAR, RadialWheelMaterial.tierFor(Role.PANEL));
        assertEquals(Tier.ULTRA_THIN, RadialWheelMaterial.tierFor(Role.HEADER));
        assertEquals(Tier.THICK, RadialWheelMaterial.tierFor(Role.SEGMENT));
    }

    @Test
    public void aThickerTierIsMoreOpaqueAndMoreTinted() {
        assertTrue(RadialWheelMaterial.fillAlpha(Tier.ULTRA_THIN)
                < RadialWheelMaterial.fillAlpha(Tier.REGULAR));
        assertTrue(RadialWheelMaterial.fillAlpha(Tier.REGULAR)
                < RadialWheelMaterial.fillAlpha(Tier.THICK));
        assertTrue(RadialWheelMaterial.tint(Tier.ULTRA_THIN)
                < RadialWheelMaterial.tint(Tier.REGULAR));
        assertTrue(RadialWheelMaterial.tint(Tier.REGULAR)
                < RadialWheelMaterial.tint(Tier.THICK));
    }

    // --- API / capability fallback (R12) -------------------------------------------------------

    @Test
    public void belowApi31TheWheelFallsBackToSolid() {
        // RenderEffect/blur-behind arrive at 31; on 28-30 a blur cannot be requested at all.
        Env env = new Env().apiLevel(30).pixelCopyOk(true).sessionActive(false);
        Resolved r = RadialWheelMaterial.resolve(Tier.REGULAR, env);
        assertEquals(Tier.SOLID, r.tier);
        assertFalse(r.blurs());
        assertEquals(0, r.blurFps);
        // The solid tier is still legible: a near-opaque fill, not a hairline.
        assertTrue(r.fillAlpha >= RadialWheelMaterial.SOLID_FILL_ALPHA - 1);
    }

    @Test
    public void aFailedPixelCopyFallsBackToSolid() {
        // R12: no capture, no blur to draw under the fill.
        Env env = cool().pixelCopyOk(false);
        Resolved r = RadialWheelMaterial.resolve(Tier.THICK, env);
        assertEquals(Tier.SOLID, r.tier);
        assertFalse(r.blurs());
    }

    @Test
    public void api31WithACaptureKeepsTheBlur() {
        Resolved r = RadialWheelMaterial.resolve(Tier.REGULAR, cool());
        assertEquals(Tier.REGULAR, r.tier);
        assertTrue(r.blurs());
        assertEquals(32f, r.blurDp, 1e-6);
        assertEquals(RadialWheelMaterial.BLUR_FPS_DEFAULT, r.blurFps);
    }

    // --- Thermal ladder (R8) ------------------------------------------------------------------

    @Test
    public void theBlurBudgetOnlyBitesDuringASession() {
        // With no game running there is nothing to protect, so a hot device still gets full blur.
        assertEquals(BlurBudget.FULL,
                RadialWheelMaterial.budgetFor(4, true, true, false));
        assertEquals(BlurBudget.FULL,
                RadialWheelMaterial.budgetFor(0, false, false, true));
        assertEquals(BlurBudget.REDUCED,
                RadialWheelMaterial.budgetFor(1, false, false, true));
        assertEquals(BlurBudget.NONE,
                RadialWheelMaterial.budgetFor(3, false, false, true));
        assertEquals(BlurBudget.NONE,
                RadialWheelMaterial.budgetFor(0, true, false, true));
        assertEquals(BlurBudget.NONE,
                RadialWheelMaterial.budgetFor(0, false, true, true));
    }

    @Test
    public void theBudgetDegradesTheHeavyTiersFirst() {
        // REDUCED keeps the lightest blur and drops the heaviest to it; NONE drops everything.
        assertEquals(Tier.THICK, RadialWheelMaterial.degrade(Tier.THICK, BlurBudget.FULL));
        assertEquals(Tier.ULTRA_THIN, RadialWheelMaterial.degrade(Tier.THICK, BlurBudget.REDUCED));
        assertEquals(Tier.REGULAR, RadialWheelMaterial.degrade(Tier.REGULAR, BlurBudget.REDUCED));
        assertEquals(Tier.SOLID, RadialWheelMaterial.degrade(Tier.REGULAR, BlurBudget.NONE));
        assertEquals(Tier.SOLID, RadialWheelMaterial.degrade(Tier.SOLID, BlurBudget.FULL));
    }

    @Test
    public void aHotSessionResolvesToSolidEvenForTheThickestTier() {
        Env env = cool().sessionActive(true).thermal(4, false, false);
        Resolved r = RadialWheelMaterial.resolve(Tier.THICK, env);
        assertEquals(Tier.SOLID, r.tier);
        assertFalse(r.blurs());
    }

    // --- Accessibility (R10) -------------------------------------------------------------------

    @Test
    public void reduceTransparencyForcesSolidAndBeatsTheRequest() {
        // The OS setting must win over anything the app asks for; a user-facing override that beats
        // the accessibility setting is a bug.
        Env env = cool().reduceTransparency(true);
        Resolved r = RadialWheelMaterial.resolve(Tier.THICK, env);
        assertEquals(Tier.SOLID, r.tier);
        assertFalse(r.blurs());
    }

    @Test
    public void reduceTransparencyAlsoBeatsAFullBlurBudget() {
        Env env = cool().reduceTransparency(true).thermal(0, false, false);
        Resolved r = RadialWheelMaterial.resolve(Tier.REGULAR, env);
        assertEquals(Tier.SOLID, r.tier);
    }

    @Test
    public void highContrastAndReduceMotionReachTheResolvedValue() {
        Resolved plain = RadialWheelMaterial.resolve(Tier.REGULAR, cool());
        assertFalse(plain.outlinedLabels);
        assertFalse(plain.reducedMotion);

        Resolved a11y = RadialWheelMaterial.resolve(Tier.REGULAR,
                cool().highContrast(true).reduceMotion(true));
        assertTrue(a11y.outlinedLabels);
        assertTrue(a11y.reducedMotion);
    }

    @Test
    public void reduceMotionDoesNotChangeTheMaterialOnItsOwn() {
        // Motion and transparency are separate concerns: reduced motion must not silently solidify
        // the glass.
        Resolved r = RadialWheelMaterial.resolve(Tier.REGULAR, cool().reduceMotion(true));
        assertEquals(Tier.REGULAR, r.tier);
        assertTrue(r.blurs());
    }

    // --- Contrast floor (R9) -------------------------------------------------------------------

    @Test
    public void theContrastFloorIsTheWcagBodyThreshold() {
        assertEquals(4.5f, RadialWheelMaterial.MIN_CONTRAST, 1e-6);
    }

    @Test
    public void aDarkFrameNeedsNoEscalation() {
        // Light text over a near-black frame is already far above the floor.
        assertEquals(0f, RadialWheelMaterial.escalatedScrim(UiTokens.TEXT_PRIMARY, 0xFF0A0A12), 1e-6);
        assertTrue(RadialWheelMaterial.meetsContrast(UiTokens.TEXT_PRIMARY, 0xFF0A0A12));
    }

    @Test
    public void aBrightFrameEscalatesTheScrimUntilTheLabelIsLegible() {
        // The wheel over a snow biome: a light label on a white frame fails, and the scrim must
        // darken until it passes. Escalating the white tint instead would change nothing at all.
        int snow = 0xFFF2F6FA;
        assertFalse(RadialWheelMaterial.meetsContrast(UiTokens.TEXT_PRIMARY, snow));
        float scrim = RadialWheelMaterial.escalatedScrim(UiTokens.TEXT_PRIMARY, snow);
        assertTrue("the escalation must be a real step", scrim > 0f);
        int filled = RadialWheelMaterial.composite(snow, 0xFF000000, scrim);
        assertTrue(RadialWheelMaterial.meetsContrast(UiTokens.TEXT_PRIMARY, filled));
    }

    @Test
    public void escalatingTheWhiteTintOverABrightFrameWouldBeNoOp() {
        // The reason the escalation is black: white over white is white.
        int snow = 0xFFF2F6FA;
        int tinted = RadialWheelMaterial.composite(snow, 0xFFFFFFFF, 0.6f);
        assertFalse(RadialWheelMaterial.meetsContrast(UiTokens.TEXT_PRIMARY, tinted));
    }

    @Test
    public void resolveForBackdropRaisesTheScrimOnlyWhenNeeded() {
        // A dark frame keeps the floor; a bright one raises it past the floor.
        Resolved dark = RadialWheelMaterial.resolveForBackdrop(Tier.REGULAR, cool(),
                UiTokens.TEXT_PRIMARY, 0xFF0A0A12);
        assertEquals(RadialWheelMaterial.SCRIM_FLOOR_ALPHA, dark.scrimAlpha, 1e-6);

        Resolved bright = RadialWheelMaterial.resolveForBackdrop(Tier.REGULAR, cool(),
                UiTokens.TEXT_PRIMARY, 0xFFF2F6FA);
        assertTrue(bright.scrimAlpha > RadialWheelMaterial.SCRIM_FLOOR_ALPHA);
        // The tier itself is untouched: the scrim is the contrast lever, not the material.
        assertEquals(Tier.REGULAR, bright.tier);
    }

    @Test
    public void theEscalationIsMonotonicInHowBadTheFrameIs() {
        // A worse (brighter) frame must never need less escalation than a milder one.
        float mild = RadialWheelMaterial.escalatedScrim(UiTokens.TEXT_PRIMARY, 0xFFBFC8D4);
        float harsh = RadialWheelMaterial.escalatedScrim(UiTokens.TEXT_PRIMARY, 0xFFFDFDFD);
        assertTrue(harsh >= mild);
    }

    // --- Blur refresh (R3) ---------------------------------------------------------------------

    @Test
    public void theRefreshRateIsCappedAtTenFps() {
        assertEquals(10, RadialWheelMaterial.BLUR_FPS_MAX);
        assertEquals(5, RadialWheelMaterial.BLUR_FPS_DEFAULT);
        // A request above the cap is clamped, not honoured.
        Resolved r = RadialWheelMaterial.resolve(Tier.REGULAR, cool().blurFps(120));
        assertEquals(10, r.blurFps);
        // And a negative/zero request disables the refresh entirely.
        assertEquals(0, RadialWheelMaterial.resolve(Tier.REGULAR, cool().blurFps(0)).blurFps);
    }

    @Test
    public void theRefreshIntervalIsInverseToTheRate() {
        assertEquals(200L, RadialWheelMaterial.blurIntervalMs(5));
        assertEquals(100L, RadialWheelMaterial.blurIntervalMs(10));
        assertEquals(-1L, RadialWheelMaterial.blurIntervalMs(0));
        // Never faster than the cap, even if asked.
        assertEquals(100L, RadialWheelMaterial.blurIntervalMs(1000));
    }

    // --- The blur route (R12) ------------------------------------------------------------------

    @Test
    public void api31UsesTheRenderEffectAndBelowUsesTheStackBlur() {
        // R12: RenderEffect is API 31+. The route is decided in one place so the layer and the
        // canvas cannot disagree about which mechanism is in play.
        Resolved blurring = RadialWheelMaterial.resolve(Tier.REGULAR, cool());
        assertEquals(RadialWheelMaterial.BlurRoute.RENDER_EFFECT,
                RadialWheelMaterial.blurRoute(31, blurring));
        assertEquals(RadialWheelMaterial.BlurRoute.RENDER_EFFECT,
                RadialWheelMaterial.blurRoute(35, blurring));
        assertEquals(RadialWheelMaterial.BlurRoute.STACK,
                RadialWheelMaterial.blurRoute(30, blurring));
        assertEquals(RadialWheelMaterial.BlurRoute.STACK,
                RadialWheelMaterial.blurRoute(28, blurring));
    }

    @Test
    public void aSolidMaterialHasNoBlurRouteAtAll() {
        // A solid tier (below API 31, no capture, reduce-transparency, or a thermal drop) must not
        // ask for a blur by either mechanism.
        Resolved solid = RadialWheelMaterial.resolve(Tier.REGULAR, cool().apiLevel(28));
        assertEquals(Tier.SOLID, solid.tier);
        assertEquals(RadialWheelMaterial.BlurRoute.NONE,
                RadialWheelMaterial.blurRoute(35, solid));
        assertEquals(RadialWheelMaterial.BlurRoute.NONE,
                RadialWheelMaterial.blurRoute(28, solid));
        assertEquals(RadialWheelMaterial.BlurRoute.NONE,
                RadialWheelMaterial.blurRoute(31, null));
    }

    // --- Squircle (R1/R13) ---------------------------------------------------------------------

    @Test
    public void theSquircleControlGrowsFromCircularToSquare() {
        assertEquals(0.5523f, RadialWheelMaterial.squircleControl(0f), 1e-6);
        assertEquals(1.0f, RadialWheelMaterial.squircleControl(1f), 1e-6);
        float mid = RadialWheelMaterial.squircleControl(0.5f);
        assertTrue(mid > 0.5523f && mid < 1f);
        // Out-of-range input is clamped rather than extrapolated.
        assertEquals(0.5523f, RadialWheelMaterial.squircleControl(-3f), 1e-6);
        assertEquals(1.0f, RadialWheelMaterial.squircleControl(9f), 1e-6);
    }

    @Test
    public void theCoordinatorsBudgetOverridesTheLocalReading() {
        // R8: the wheel asks the FPS Optimization coordinator rather than judging the device again,
        // so the coordinator's answer must win over the raw sensor values.
        Env env = cool().sessionActive(true).thermal(0, false, false)
                .budget(BlurBudget.NONE);
        Resolved r = RadialWheelMaterial.resolve(Tier.THICK, env);
        assertEquals(Tier.SOLID, r.tier);
        assertFalse(r.blurs());

        // And a coordinator saying FULL beats a locally-hot reading: the coordinator already folded
        // the sensors in, and re-deriving a stricter answer from the same numbers is the drift this
        // override exists to prevent.
        Env full = cool().sessionActive(true).thermal(4, true, true).budget(BlurBudget.FULL);
        assertEquals(Tier.THICK, RadialWheelMaterial.resolve(Tier.THICK, full).tier);
    }

    @Test
    public void withNoOverrideTheLocalReadingIsUsed() {
        Env env = cool().sessionActive(true).thermal(4, false, false);
        assertEquals(Tier.SOLID, RadialWheelMaterial.resolve(Tier.THICK, env).tier);
    }

    @Test
    public void theTiersAreVisiblyDistinct() {
        // A regression that collapsed two tiers into one would make the whole ladder pointless.
        assertNotEquals(RadialWheelMaterial.blurDp(Tier.ULTRA_THIN),
                RadialWheelMaterial.blurDp(Tier.REGULAR));
        assertNotEquals(RadialWheelMaterial.tint(Tier.REGULAR),
                RadialWheelMaterial.tint(Tier.THICK));
    }
}
