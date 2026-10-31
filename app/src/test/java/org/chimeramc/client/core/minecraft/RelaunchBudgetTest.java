package org.chimeramc.client.core.minecraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pins the relaunch budget (item 274): a bounded number of relaunches inside a window, so a bad
 * state cannot turn the launcher into a restart loop.
 *
 * <p>Pure, so no device and no running game are needed. The rules that matter are the boundary
 * (exactly the cap is still allowed), the window (an old burst does not block forever) and the
 * clock-change case (a future timestamp must not count as a recent relaunch).
 */
public class RelaunchBudgetTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void anEmptyHistoryIsAllowed() {
        assertEquals(RelaunchBudget.Decision.ALLOW, RelaunchBudget.decide(null, NOW));
        assertEquals(RelaunchBudget.Decision.ALLOW,
                RelaunchBudget.decide(Collections.<Long>emptyList(), NOW));
    }

    @Test
    public void theLastAllowedRelaunchIsAllowedAndTheNextIsRefused() {
        // decide() is called *before* recording the relaunch it is asked about, so a history of
        // MAX-1 relaunches means this request is the MAXth and must be allowed; a history of MAX
        // means it would be one too many.
        List<Long> beforeLast = Arrays.asList(NOW - 10, NOW - 20);                 // 2 done -> 3rd ok
        assertEquals(RelaunchBudget.MAX_IN_WINDOW - 1, beforeLast.size());
        assertEquals(RelaunchBudget.Decision.ALLOW, RelaunchBudget.decide(beforeLast, NOW));

        List<Long> atCap = Arrays.asList(NOW - 10, NOW - 20, NOW - 30);            // 3 done -> 4th no
        assertEquals(RelaunchBudget.MAX_IN_WINDOW, atCap.size());
        assertEquals(RelaunchBudget.Decision.REFUSED_TOO_MANY,
                RelaunchBudget.decide(atCap, NOW));
    }

    @Test
    public void relaunchesOutsideTheWindowDoNotCount() {
        long old = NOW - RelaunchBudget.WINDOW_MS - 1;
        List<Long> stale = Arrays.asList(old, old, old, old);
        assertEquals(RelaunchBudget.Decision.ALLOW, RelaunchBudget.decide(stale, NOW));
        assertEquals(0, RelaunchBudget.countInWindow(stale, NOW));
    }

    @Test
    public void aFutureTimestampIsIgnoredNotCountedAsRecent() {
        List<Long> future = Arrays.asList(NOW + 60_000, NOW + 60_000, NOW + 60_000);
        assertEquals(RelaunchBudget.Decision.ALLOW, RelaunchBudget.decide(future, NOW));
        assertEquals(0, RelaunchBudget.countInWindow(future, NOW));
    }

    @Test
    public void pruningKeepsOnlyTheWindowAndNeverGrows() {
        List<Long> mixed = new ArrayList<>();
        mixed.add(NOW - RelaunchBudget.WINDOW_MS - 5_000);
        mixed.add(NOW - 1_000);
        mixed.add(NOW - 2_000);
        java.util.Deque<Long> kept = RelaunchBudget.pruned(mixed, NOW);
        assertEquals(2, kept.size());
        assertTrue(kept.contains(NOW - 1_000L));
        assertFalse(kept.contains(NOW - RelaunchBudget.WINDOW_MS - 5_000L));
    }

    @Test
    public void nullEntriesDoNotCrashOrCount() {
        List<Long> withNull = Arrays.asList(null, NOW - 1_000, null);
        assertEquals(1, RelaunchBudget.countInWindow(withNull, NOW));
        assertEquals(RelaunchBudget.Decision.ALLOW, RelaunchBudget.decide(withNull, NOW));
    }
}
