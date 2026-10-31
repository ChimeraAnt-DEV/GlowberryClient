package org.chimeramc.client.ui.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the active-tab contrast floor (item 87).
 *
 * <p>Under Material You the accent is whatever the wallpaper produces, so the failure this guards
 * is a selected tab whose label is the same lightness as the navigation surface. The tests use
 * real opaque colours rather than mocks, because the rule *is* the colour maths.
 */
public class NavAccentTest {

    private static final int SURFACE = 0xFF17191D;   // the launcher's dark navigation surface
    private static final int ON_SURFACE = 0xFFE8EEF6;
    private static final int READABLE_ACCENT = 0xFF7FA6FF;   // a light blue on the dark surface

    @Test
    public void aReadableAccentIsUsed() {
        assertEquals(READABLE_ACCENT, NavAccent.resolve(READABLE_ACCENT, SURFACE, ON_SURFACE));
        assertFalse(NavAccent.fallsBack(READABLE_ACCENT, SURFACE));
    }

    @Test
    public void anAccentIndistinguishableFromTheSurfaceFallsBack() {
        int nearlySurface = 0xFF1A1C21;   // a hair off the surface
        assertEquals(ON_SURFACE, NavAccent.resolve(nearlySurface, SURFACE, ON_SURFACE));
        assertTrue(NavAccent.fallsBack(nearlySurface, SURFACE));
    }

    @Test
    public void noAccentUsesOnSurface() {
        assertEquals(ON_SURFACE, NavAccent.resolve(0, SURFACE, ON_SURFACE));
        assertTrue(NavAccent.fallsBack(0, SURFACE));
    }

    @Test
    public void aTranslucentSurfaceOrAccentIsTrustedRatherThanRejected() {
        // A translucent surface has no meaningful contrast ratio, so the accent is used instead of
        // being dropped on a reading that cannot be compared.
        int translucentSurface = 0x8017191D;
        assertEquals(READABLE_ACCENT,
                NavAccent.resolve(READABLE_ACCENT, translucentSurface, ON_SURFACE));
        assertFalse(NavAccent.fallsBack(READABLE_ACCENT, translucentSurface));
    }

    @Test
    public void theFloorIsThreeToOneNotFourPointFive() {
        // 3:1 is the non-text/large-text floor. A colour just above it must still be used, because
        // falling back too eagerly makes the selected and idle colours nearly identical.
        assertEquals(3.0, NavAccent.MIN_CONTRAST, 0.0001);
        double ratio = org.chimeramc.client.ui.components.UiTokens
                .contrastRatio(READABLE_ACCENT, SURFACE);
        assertTrue("the fixture accent must clear the floor; was " + ratio, ratio >= 3.0);
    }
}
