package org.chimeramc.client.core.mods.inbuilt.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The deferred-attach gate (item 183). Every rule here is an ordering a slow window manager can
 * actually produce, and each one used to leave the overlay stuck on screen.
 */
public class OverlayShowGateTest {

    @Test
    public void aTokenIsCurrentUntilSomethingInvalidatesIt() {
        OverlayShowGate gate = new OverlayShowGate();
        long token = gate.begin();
        assertTrue(gate.isCurrent(token));
    }

    @Test
    public void aHideWhileTheAttachIsPendingStopsTheAttach() {
        OverlayShowGate gate = new OverlayShowGate();
        long token = gate.begin();
        gate.invalidate();
        assertFalse(gate.isCurrent(token));
    }

    @Test
    public void aShowAfterAHideGetsItsOwnCurrentToken() {
        OverlayShowGate gate = new OverlayShowGate();
        long first = gate.begin();
        gate.invalidate();
        long second = gate.begin();
        assertFalse(gate.isCurrent(first));
        assertTrue(gate.isCurrent(second));
    }

    @Test
    public void aStaleTokenStaysStaleAcrossLaterShows() {
        OverlayShowGate gate = new OverlayShowGate();
        long stale = gate.begin();
        gate.invalidate();
        gate.begin();
        // The point of a generation counter: re-arming for a new show must not resurrect the
        // token a hide already killed.
        assertFalse(gate.isCurrent(stale));
    }

    @Test
    public void twoShowsWithoutAHideAreBothCurrent() {
        OverlayShowGate gate = new OverlayShowGate();
        long first = gate.begin();
        long second = gate.begin();
        assertTrue(gate.isCurrent(first));
        assertTrue(gate.isCurrent(second));
    }
}
