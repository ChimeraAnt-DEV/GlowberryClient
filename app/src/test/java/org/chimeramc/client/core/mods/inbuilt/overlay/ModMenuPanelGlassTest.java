package org.chimeramc.client.core.mods.inbuilt.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the Mod Menu panel's glass intensity rule (M6).
 *
 * <p>The scrim opacity is the one part of the glass setting that is pure arithmetic and therefore
 * testable off-device. The rule it encodes is the reason the setting exists: more glass means a
 * lighter scrim, because a heavy scrim behind a "full" blur hides the very frame the blur samples.
 */
public class ModMenuPanelGlassTest {

    @Test
    public void theScrimGetsLighterAsTheGlassGetsThicker() {
        float off = ModMenuOverlay.scrimAlphaFor(ModMenuSettings.GlassIntensity.OFF);
        float subtle = ModMenuOverlay.scrimAlphaFor(ModMenuSettings.GlassIntensity.SUBTLE);
        float full = ModMenuOverlay.scrimAlphaFor(ModMenuSettings.GlassIntensity.FULL);
        assertTrue("solid hides the most", off > subtle);
        assertTrue("full glass hides the least", subtle > full);
    }

    @Test
    public void theScrimIsNeverFullyTransparent() {
        // A fully transparent scrim would drop the menu's text straight onto a bright game frame.
        for (ModMenuSettings.GlassIntensity intensity : ModMenuSettings.GlassIntensity.values()) {
            float alpha = ModMenuOverlay.scrimAlphaFor(intensity);
            assertTrue("scrim must stay visible at " + intensity, alpha > 0.25f);
            assertTrue("scrim must not be opaque at " + intensity, alpha < 1f);
        }
    }

    @Test
    public void anAbsentIntensityDegradesToTheFullGlassScrimRatherThanZero() {
        // A null setting must not read as "no scrim", which would be the least legible outcome.
        assertEquals(ModMenuOverlay.SCRIM_ALPHA_FULL, ModMenuOverlay.scrimAlphaFor(null), 0.0001f);
    }
}
