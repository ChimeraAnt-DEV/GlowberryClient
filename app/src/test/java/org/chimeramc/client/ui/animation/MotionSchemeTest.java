package org.chimeramc.client.ui.animation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.chimeramc.client.ui.components.UiTokens;
import org.junit.Test;

/**
 * The motion scheme is actually wired (U6).
 *
 * <p>{@code UiTokens.Motion} and {@code UiTokens.motionScheme} were declared but had no consumer:
 * the tokens were correct and nothing read them, so "Material 3 Expressive" was a description
 * rather than behaviour. This test pins the wiring itself — that the animation entry points select
 * and then use the scheme — because a dead token cannot fail a test that only checks the token.
 *
 * <p>Pure: it never constructs an Android view, so it runs as a plain JVM test.
 */
public class MotionSchemeTest {

    @Test
    public void expressiveIsTheDefault() {
        DynamicAnim.enableAnimations();
        assertSame(UiTokens.Motion.EXPRESSIVE, DynamicAnim.motionScheme());
        assertTrue(DynamicAnim.areAnimationsEnabled());
    }

    @Test
    public void reduceMotionSelectsTheFlatSchemeAndDisablesMotion() {
        DynamicAnim.applyMotionScheme(true, false);
        assertSame(UiTokens.Motion.REDUCED, DynamicAnim.motionScheme());
        assertFalse("reduced motion must report animations as disabled",
                DynamicAnim.areAnimationsEnabled());
    }

    /**
     * The platform setting has to win. A user who told Android to remove animations must not get
     * springs back because the app's own switch happens to be on.
     */
    @Test
    public void thePlatformSettingWinsOverTheAppSwitch() {
        DynamicAnim.applyMotionScheme(false, true);
        assertSame(UiTokens.Motion.REDUCED, DynamicAnim.motionScheme());
        assertFalse(DynamicAnim.areAnimationsEnabled());

        DynamicAnim.applyMotionScheme(false, false);
        assertSame(UiTokens.Motion.EXPRESSIVE, DynamicAnim.motionScheme());
    }

    @Test
    public void theLegacyEntryPointsKeepTheSchemeInStep() {
        DynamicAnim.disableAnimations();
        assertSame(UiTokens.Motion.REDUCED, DynamicAnim.motionScheme());
        assertFalse(DynamicAnim.areAnimationsEnabled());

        DynamicAnim.enableAnimations();
        assertSame(UiTokens.Motion.EXPRESSIVE, DynamicAnim.motionScheme());
        assertTrue(DynamicAnim.areAnimationsEnabled());
    }

    @Test
    public void aTransitionDurationFollowsTheScheme() {
        DynamicAnim.setGlobalSpeedMultiplier(1f);
        DynamicAnim.applyMotionScheme(false, false);
        assertEquals("expressive keeps the surface's own duration", 260L,
                DynamicAnim.motionDurationMs(260L));

        DynamicAnim.applyMotionScheme(true, false);
        assertEquals("reduced motion collapses to the scheme's short duration",
                UiTokens.Motion.REDUCED.durationMs, DynamicAnim.motionDurationMs(260L));
    }

    @Test
    public void theSpeedMultiplierStillAppliesToExpressiveMotion() {
        DynamicAnim.applyMotionScheme(false, false);
        DynamicAnim.setGlobalSpeedMultiplier(2f);
        assertEquals(130L, DynamicAnim.motionDurationMs(260L));
        DynamicAnim.setGlobalSpeedMultiplier(1f);
    }

    /** Reduced motion must never be longer than expressive motion, or the switch makes it slower. */
    @Test
    public void reducedMotionIsNeverTheLongerTransition() {
        assertTrue(UiTokens.Motion.REDUCED.durationMs <= UiTokens.Motion.EXPRESSIVE.durationMs);
        assertTrue("a reduced spring must not overshoot", UiTokens.Motion.REDUCED.damping >= 1f);
    }
}
