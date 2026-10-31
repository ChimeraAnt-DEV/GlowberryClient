package org.chimeramc.client.core.crash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the exit-reason mapping (item 167) without a device.
 *
 * <p>The codes are the platform's own, so a transcription error here would report the wrong reason
 * for a crash - and the mapping is the only thing standing between a raw int and a user-facing
 * sentence. The rule that matters most is the fallback: an unknown code must read as unknown, never
 * as a crash, because a false "it crashed" sends the user hunting for a bug that is not there.
 */
public class ExitReasonTest {

    @Test
    public void mapsThePlatformCodesToTheNamedReasons() {
        assertEquals(ExitReason.EXIT_SELF, ExitReason.fromCode(1));
        assertEquals(ExitReason.SIGNALED, ExitReason.fromCode(2));
        assertEquals(ExitReason.LOW_MEMORY, ExitReason.fromCode(3));
        assertEquals(ExitReason.CRASH, ExitReason.fromCode(4));
        assertEquals(ExitReason.CRASH_NATIVE, ExitReason.fromCode(5));
        assertEquals(ExitReason.ANR, ExitReason.fromCode(6));
        assertEquals(ExitReason.INITIALIZATION_FAILURE, ExitReason.fromCode(7));
        assertEquals(ExitReason.USER_REQUESTED, ExitReason.fromCode(10));
        assertEquals(ExitReason.USER_STOPPED, ExitReason.fromCode(11));
        assertEquals(ExitReason.PACKAGE_UPDATED, ExitReason.fromCode(16));
    }

    @Test
    public void anUnknownCodeReadsAsUnknownNotAsACrash() {
        assertEquals(ExitReason.UNKNOWN, ExitReason.fromCode(9999));
        assertEquals(ExitReason.UNKNOWN, ExitReason.fromCode(0));
        assertEquals(ExitReason.UNKNOWN, ExitReason.fromCode(-5));
        assertFalse(ExitReason.fromCode(9999).isCrash());
        assertFalse(ExitReason.UNKNOWN.isCrash());
    }

    @Test
    public void onlyFaultsAreCrashes() {
        assertTrue(ExitReason.CRASH.isCrash());
        assertTrue(ExitReason.CRASH_NATIVE.isCrash());
        assertTrue(ExitReason.ANR.isCrash());
        assertTrue(ExitReason.INITIALIZATION_FAILURE.isCrash());
        assertFalse(ExitReason.EXIT_SELF.isCrash());
        assertFalse(ExitReason.LOW_MEMORY.isCrash());
        assertFalse(ExitReason.USER_REQUESTED.isCrash());
    }

    @Test
    public void unknownIsNotReportableButARealReasonIs() {
        assertFalse(ExitReason.isReportable(ExitReason.UNKNOWN));
        assertFalse(ExitReason.isReportable(null));
        assertTrue(ExitReason.isReportable(ExitReason.LOW_MEMORY));
        assertTrue(ExitReason.isReportable(ExitReason.CRASH));
    }

    @Test
    public void everyNamedReasonHasALabelAndTheCodesAreUnique() {
        java.util.Set<Integer> codes = new java.util.HashSet<>();
        for (ExitReason reason : ExitReason.values()) {
            assertTrue(reason + " has no label", reason.label() != null && !reason.label().isEmpty());
            if (reason != ExitReason.UNKNOWN) {
                assertTrue("duplicate code for " + reason, codes.add(reason.code()));
            }
        }
    }
}
