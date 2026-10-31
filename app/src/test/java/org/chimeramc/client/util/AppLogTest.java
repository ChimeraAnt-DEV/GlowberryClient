package org.chimeramc.client.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The report-formatting rule (item 300). Every failure the app reports goes through this, so the
 * line a user pastes into a bug report has to name the failure even when the message is empty.
 */
public class AppLogTest {

    @Test
    public void aCauseIsNamedByClassAndDetail() {
        assertEquals("open failed: IllegalStateException: no token",
                AppLog.describe("open failed", new IllegalStateException("no token")));
    }

    @Test
    public void aCauseWithNoMessageIsStillNamed() {
        // The common case: the class is the only thing that identifies the failure.
        assertEquals("open failed: NullPointerException",
                AppLog.describe("open failed", new NullPointerException()));
    }

    @Test
    public void anEmptyMessageIsTreatedAsNoMessage() {
        assertEquals("open failed: IllegalArgumentException",
                AppLog.describe("open failed", new IllegalArgumentException("")));
    }

    @Test
    public void noCauseLeavesTheMessageAlone() {
        assertEquals("open failed", AppLog.describe("open failed", null));
    }

    @Test
    public void aRepeatedFailureIsReportedOnlyOnce() {
        AppLog.resetOnce();
        // A per-widget failure attempted many times must produce one line, not one per widget.
        assertTrue(AppLog.firstReport("tint-switch"));
        assertFalse(AppLog.firstReport("tint-switch"));
        assertFalse(AppLog.firstReport("tint-switch"));
    }

    @Test
    public void aDifferentFailureStillGetsItsOwnReport() {
        AppLog.resetOnce();
        assertTrue(AppLog.firstReport("tint-switch"));
        assertTrue(AppLog.firstReport("tint-slider"));
    }
}
