package org.chimeramc.client.core.minecraft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Caps how often a session may relaunch itself in a row (item 274).
 *
 * <p>The relaunch exists to apply a change that needs a fresh load. It is also the one action that
 * can turn a bad state into a loop: if the game dies immediately after every relaunch, an unlimited
 * auto-relaunch would restart it forever, burning battery and never letting the user see the
 * problem. The rule is deliberately simple and conservative - a bounded number of relaunches inside
 * a short window - because the cost of refusing one legitimate relaunch is a single extra tap,
 * while the cost of an unbounded loop is a device that will not stop.
 *
 * <p>Pure: the history is a list of timestamps and the clock is passed in, so the counting rule is
 * unit-tested without a device or a running game.
 */
public final class RelaunchBudget {

    /** How many relaunches are allowed inside {@link #WINDOW_MS}. */
    public static final int MAX_IN_WINDOW = 3;
    /** The window the count applies to. Three relaunches inside ten minutes is already a loop. */
    public static final long WINDOW_MS = 10 * 60 * 1000L;

    private RelaunchBudget() {
    }

    /** Why a relaunch was refused, for the message the user sees. */
    public enum Decision {
        /** The relaunch is allowed. */
        ALLOW,
        /** Too many relaunches inside the window; the caller must stop and tell the user. */
        REFUSED_TOO_MANY,
    }

    /**
     * Decides whether a relaunch at {@code nowMs} is allowed, given the timestamps of the relaunches
     * already recorded.
     *
     * <p>Only timestamps inside the window count, so an old burst does not permanently block a
     * relaunch hours later. A timestamp in the future (a clock change) is ignored rather than
     * treated as a very recent relaunch.
     */
    public static Decision decide(List<Long> history, long nowMs) {
        int recent = 0;
        if (history != null) {
            long floor = nowMs - WINDOW_MS;
            for (Long at : history) {
                if (at == null) continue;
                if (at > nowMs) continue;                 // clock moved backwards; ignore
                if (at >= floor) recent++;
            }
        }
        return recent >= MAX_IN_WINDOW ? Decision.REFUSED_TOO_MANY : Decision.ALLOW;
    }

    /** The timestamps that still count, so a caller can prune what it stores. */
    public static Deque<Long> pruned(List<Long> history, long nowMs) {
        Deque<Long> out = new ArrayDeque<>();
        if (history == null) return out;
        long floor = nowMs - WINDOW_MS;
        for (Long at : history) {
            if (at == null || at > nowMs) continue;
            if (at >= floor) out.addLast(at);
        }
        return out;
    }

    /** How many relaunches in the window; for a diagnostics line. */
    public static int countInWindow(List<Long> history, long nowMs) {
        return pruned(history, nowMs).size();
    }
}
