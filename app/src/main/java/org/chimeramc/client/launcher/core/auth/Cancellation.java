package org.chimeramc.client.core.auth;

import java.util.function.BooleanSupplier;

import org.chimeramc.client.util.SignalGate;

/**
 * The cancellation seam for a blocking sign-in wait (item 15).
 *
 * <p>The device-code flow waits for the user to approve the sign-in in a browser, so the wait is
 * measured in tens of seconds. Polling it on a fixed interval meant a Cancel press was not seen
 * until the current interval elapsed, and the poll had to re-check on every tick just to notice.
 * This splits the two things a wait needs: <b>state</b> ({@link #isCancelled()}) and <b>wake-up</b>
 * ({@link #awaitSignal(long)}). A caller that can signal wakes the wait the instant the user acts;
 * a caller that cannot still gets the interval as a fallback.
 *
 * <p>It is an interface rather than a {@code BooleanSupplier} so the signal has somewhere to live.
 * A bare predicate forces the wait to be a sleep-then-recheck, which is the shape this replaces.
 */
public interface Cancellation {

    /** Whether the operation should abandon its wait. Safe to call from any thread. */
    boolean isCancelled();

    /**
     * Waits up to {@code timeoutMs} for a cancellation signal.
     *
     * <p>Returns at once when the thread is interrupted, restoring the interrupt flag. It does not
     * report why it returned: the caller re-checks {@link #isCancelled()} and its own interrupt
     * state, which is the one source of truth for whether to keep going.
     */
    void awaitSignal(long timeoutMs);

    /**
     * A cancellation that never fires and cannot be signalled.
     *
     * <p>For the one-shot paths that only ever want the interrupt check — the retry backoff, which
     * has no UI cancel button behind it.
     */
    static Cancellation none() {
        return new Cancellation() {
            // Never signalled, so this is a plain timed wait — but through the condition-wait
            // primitive, not Thread.sleep, so every wait in the app has one implementation and one
            // interrupt contract.
            private final SignalGate gate = new SignalGate();

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void awaitSignal(long timeoutMs) {
                if (timeoutMs <= 0L) return;
                gate.await(timeoutMs);
            }
        };
    }

    /**
     * Adapts a plain predicate, keeping the interval fallback and no signal.
     *
     * <p>Kept for callers that have state but no wake-up source; prefer a signalling implementation
     * where one is available, because the predicate can only be observed after the interval.
     */
    static Cancellation of(BooleanSupplier cancelled) {
        if (cancelled == null) return none();
        return new Cancellation() {
            private final SignalGate gate = new SignalGate();

            @Override
            public boolean isCancelled() {
                return cancelled.getAsBoolean();
            }

            @Override
            public void awaitSignal(long timeoutMs) {
                if (timeoutMs <= 0L) return;
                gate.await(timeoutMs);
            }
        };
    }
}
