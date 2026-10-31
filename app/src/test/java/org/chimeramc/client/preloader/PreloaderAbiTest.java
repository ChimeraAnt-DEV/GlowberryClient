package org.chimeramc.client.preloader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The preloader ABI-version rule (items 442/443).
 *
 * <p>The preloader is a separate library on its own release schedule, so a client and a preloader
 * built from different ABI revisions can be paired by accident. The client compares the version the
 * preloader reports against the one it was built against and refuses the surface on a mismatch,
 * rather than calling into entry points whose meaning may have changed.
 *
 * <p>Pure comparison, so it is unit-tested without a device; the native read itself is not exercised
 * here (there is no preloader in a JVM test) and reads as version 0.
 */
public class PreloaderAbiTest {

    @Test
    public void aMatchingVersionIsAccepted() {
        assertTrue(PreloaderInput.abiMatches(PreloaderInput.EXPECTED_ABI_VERSION));
    }

    @Test
    public void aMismatchedVersionIsRefused() {
        assertFalse(PreloaderInput.abiMatches(PreloaderInput.EXPECTED_ABI_VERSION + 1));
        assertFalse(PreloaderInput.abiMatches(PreloaderInput.EXPECTED_ABI_VERSION - 1));
    }

    @Test
    public void anUnavailableLibraryReadsAsIncompatible() {
        // No library (or no symbol) reads as version 0, which must not match: the client cannot use
        // a surface it could not version-check.
        assertFalse(PreloaderInput.abiMatches(0));
        assertEquals(0, PreloaderInput.abiVersion());
        assertFalse(PreloaderInput.isAbiCompatible());
    }

    @Test
    public void theExpectedVersionIsPositive() {
        // Version 0 is reserved for "unavailable", so the real expected version must never be 0 or
        // a missing library would compare as compatible.
        assertTrue(PreloaderInput.EXPECTED_ABI_VERSION > 0);
    }
}
