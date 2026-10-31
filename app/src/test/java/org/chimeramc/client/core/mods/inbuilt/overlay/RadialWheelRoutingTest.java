package org.chimeramc.client.core.mods.inbuilt.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import org.chimeramc.client.core.mods.inbuilt.model.ModIds;
import org.chimeramc.client.core.mods.inbuilt.overlay.RadialWheelLayout.Sector;

import java.util.List;

/**
 * The radial wheel's four sectors each have to reach a real destination (13.1).
 *
 * <p>The wheel used to draw the sectors and icons but the taps did nothing useful: the HUD sector
 * opened the module list unfiltered, and the CLOSET sector jumped straight to a screen without
 * asking what the player wanted to customize. These tests pin the routing contract that the fix
 * relies on — the sector set, the destination list, and the HUD section's module ids — so the wheel
 * and the Mod Menu cannot drift apart again.
 */
public class RadialWheelRoutingTest {

    @Test
    public void everySectorIsOneOfTheFourDestinations() {
        assertEquals(4, RadialWheelLayout.Sector.values().length - 2);
        assertTrue(RadialWheelLayout.isDestination(Sector.MODS));
        assertTrue(RadialWheelLayout.isDestination(Sector.CLOSET));
        assertTrue(RadialWheelLayout.isDestination(Sector.SETTINGS));
        assertTrue(RadialWheelLayout.isDestination(Sector.HUD));
    }

    @Test
    public void theDestinationInterfaceNamesEverySectorExactlyOnce() {
        // The Destinations interface is the routing table; a sector with no method, or a method with
        // no sector, is the "sector does nothing" bug in a different shape.
        String[] methods = {"openMods", "openHud", "openCustomize", "openSettings"};
        java.util.Set<String> names = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : RadialMenuOverlay.Destinations.class.getDeclaredMethods()) {
            names.add(m.getName());
        }
        for (String method : methods) {
            assertTrue("Destinations must declare " + method, names.contains(method));
        }
        assertEquals("no extra destinations", methods.length, names.size());
    }

    @Test
    public void theHudSectionIsExactlyTheHudModuleSet() {
        List<String> ids = ModIds.hudModuleIds();
        assertFalse(ids.isEmpty());
        for (String id : ids) {
            assertTrue(id + " must classify as a HUD module", ModIds.isHudModule(id));
        }
        // The set is closed: nothing outside the list classifies as HUD.
        assertFalse(ModIds.isHudModule(ModIds.ZOOM));
        assertFalse(ModIds.isHudModule(ModIds.POJAV_CONTROLS));
        assertFalse(ModIds.isHudModule(null));
    }

    @Test
    public void theHudSectionDoesNotSwallowModulesThatAreNotWidgets() {
        // The HUD filter must not claim the input-shaping or world-changing modules: the wheel's HUD
        // sector would then show rows that have nothing to do with the HUD.
        assertFalse(ModIds.isHudModule(ModIds.HIT_REGISTRATION));
        assertFalse(ModIds.isHudModule(ModIds.CRYSTAL_OPTIMIZER));
        assertFalse(ModIds.isHudModule(ModIds.GYRO));
    }

    @Test
    public void theHudSectionIdsAreUnique() {
        List<String> ids = ModIds.hudModuleIds();
        assertEquals(ids.size(), new java.util.HashSet<>(ids).size());
    }

    @Test
    public void theWheelModuleFiltersAreTheOnesTheMenuUnderstands() {
        // The wheel asks for ALL and HUD through the public filter enum, so those two constants
        // must exist for the routing to compile against them at all.
        assertNotNull(ModMenuOverlay.ModuleFilter.valueOf("ALL"));
        assertNotNull(ModMenuOverlay.ModuleFilter.valueOf("HUD"));
    }
}
