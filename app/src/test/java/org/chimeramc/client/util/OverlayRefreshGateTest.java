package org.chimeramc.client.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The overlay refresh gate (item 187).
 *
 * <p>The overlays redraw on a timer that fires far more often than their value changes, so the gate
 * is what stops a static readout repainting an identical frame. The two rules that matter are
 * pinned here: a changed value paints at once, and an unchanged value paints no more than once per
 * interval. A null signature is the "unknown state" case and must never be starved.
 */
public class OverlayRefreshGateTest {

    @Test
    public void theFirstTickAlwaysPaints() {
        OverlayRefreshGate gate = new OverlayRefreshGate(1000L);
        assertTrue(gate.shouldRefresh("FPS: 60", 0));
    }

    @Test
    public void aChangedValuePaintsImmediatelyEvenInsideTheInterval() {
        OverlayRefreshGate gate = new OverlayRefreshGate(1000L);
        assertTrue(gate.shouldRefresh("FPS: 60", 0));
        // 1 ms later, but the text changed: a readout must never lag its own data.
        assertTrue(gate.shouldRefresh("FPS: 59", 1));
    }

    @Test
    public void anUnchangedValueIsHeldUntilTheIntervalElapses() {
        OverlayRefreshGate gate = new OverlayRefreshGate(250L);
        assertTrue(gate.shouldRefresh("FPS: 60", 0));
        assertFalse(gate.shouldRefresh("FPS: 60", 100));
        assertFalse(gate.shouldRefresh("FPS: 60", 249));
        // Exactly at the interval counts as due.
        assertTrue(gate.shouldRefresh("FPS: 60", 250));
    }

    @Test
    public void aZeroIntervalPaintsEveryTick() {
        OverlayRefreshGate gate = new OverlayRefreshGate(0L);
        assertTrue(gate.shouldRefresh("same", 0));
        assertTrue(gate.shouldRefresh("same", 0));
        assertTrue(gate.shouldRefresh("same", 1));
    }

    @Test
    public void anUnknownSignatureNeverStarves() {
        OverlayRefreshGate gate = new OverlayRefreshGate(1000L);
        assertTrue(gate.shouldRefresh(null, 0));
        assertTrue(gate.shouldRefresh(null, 1));
        // And a known value after an unknown one still tracks normally.
        assertTrue(gate.shouldRefresh("x", 2));
        assertFalse(gate.shouldRefresh("x", 3));
    }

    @Test
    public void aBackwardsClockIsTreatedAsDue() {
        OverlayRefreshGate gate = new OverlayRefreshGate(250L);
        assertTrue(gate.shouldRefresh("same", 10_000));
        // A clock that jumped backwards must not freeze the overlay: one wasted paint beats a
        // readout stuck on a stale frame.
        assertTrue(gate.shouldRefresh("same", 5_000));
    }

    @Test
    public void resetForcesTheNextTickToPaint() {
        OverlayRefreshGate gate = new OverlayRefreshGate(1000L);
        assertTrue(gate.shouldRefresh("x", 0));
        assertFalse(gate.shouldRefresh("x", 10));
        gate.reset();
        assertTrue(gate.shouldRefresh("x", 11));
    }

    @Test
    public void aNegativeIntervalIsClampedToZero() {
        OverlayRefreshGate gate = new OverlayRefreshGate(-5L);
        assertTrue(gate.shouldRefresh("x", 0));
        assertTrue(gate.shouldRefresh("x", 0));
    }
}
