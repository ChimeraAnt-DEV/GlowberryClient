package org.chimeramc.client.preloader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The pure half of the BerryAPI bridge: the capability-bit rule and the constants the launcher
 * greys features on.
 *
 * <p>The JNI half cannot run off-device (there is no preloader), so it is not exercised here; what
 * this pins is the decision logic a caller relies on, so a bit test cannot silently pass on an
 * unrelated flag.
 */
public class BerryApiTest {

    @Test
    public void aCapabilityIsSetOnlyWhenEveryBitIsPresent() {
        assertTrue(BerryApi.hasBit(BerryApi.CAP_ATTACHED, BerryApi.CAP_ATTACHED));
        assertTrue(BerryApi.hasBit(
                BerryApi.CAP_LIBRARY_LOADED | BerryApi.CAP_ATTACHED, BerryApi.CAP_ATTACHED));
        assertFalse("a partial mask must not satisfy the flag",
                BerryApi.hasBit(BerryApi.CAP_LIBRARY_LOADED, BerryApi.CAP_ATTACHED));
        assertFalse(BerryApi.hasBit(0, BerryApi.CAP_LIBRARY_LOADED));
    }

    @Test
    public void theCapabilityBitsAreDistinct() {
        assertTrue(BerryApi.CAP_LIBRARY_LOADED != BerryApi.CAP_ATTACHED);
        assertTrue(BerryApi.CAP_ATTACHED != BerryApi.CAP_WORLD_FEED);
        assertTrue(BerryApi.CAP_LIBRARY_LOADED != BerryApi.CAP_WORLD_FEED);
        assertEquals(0, BerryApi.CAP_LIBRARY_LOADED & BerryApi.CAP_ATTACHED);
        assertEquals(0, BerryApi.CAP_ATTACHED & BerryApi.CAP_WORLD_FEED);
    }

    @Test
    public void theWorldFeedBitIsNeverSetByAnUnloadedLibrary() {
        // A missing library reads as 0 capabilities, so the world feed stays clear. This is the
        // fail-closed contract: nothing may present a world/entity feature as working on its own.
        assertFalse(BerryApi.hasBit(0, BerryApi.CAP_WORLD_FEED));
    }

    @Test
    public void theSummaryIsNeverEmptyWithoutTheNativeLibrary() {
        // Off-device the native call throws UnsatisfiedLinkError; summary() must still return a
        // non-empty, human-readable line so the Diagnostics row is never blank.
        String summary = BerryApi.summary();
        assertTrue(summary != null && !summary.isEmpty());
        assertTrue(summary.toLowerCase().contains("berryapi"));
    }
}
