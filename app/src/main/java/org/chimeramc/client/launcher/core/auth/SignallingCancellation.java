package org.chimeramc.client.core.auth;

import java.util.concurrent.atomic.AtomicBoolean;

import org.chimeramc.client.util.SignalGate;

/**
 * A {@link Cancellation} that wakes a blocked wait the moment it is cancelled (item 15).
 *
 * <p>The state is an {@link AtomicBoolean} and the wake-up is a {@link SignalGate}. The two are
 * separate on purpose: the flag is what a caller checks, the gate is only how it stops waiting. A
 * cancel that arrived before the wait began still reads as cancelled, because the flag is the
 * authority and the gate is level-triggered and remembers it.
 *
 * <p>Deliberately not reusable: a cancelled flag that could be reset would let a stale cancel
 * release a later, unrelated wait. One instance belongs to one operation.
 */
public final class SignallingCancellation implements Cancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final SignalGate gate = new SignalGate();

    /** Marks the operation cancelled and wakes any waiter. Idempotent. */
    public void cancel() {
        cancelled.set(true);
        gate.signal();
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void awaitSignal(long timeoutMs) {
        if (cancelled.get()) return;
        // A plain await is correct here, unlike a clock-paced loop: this wait has no schedule to
        // keep, so a remembered signal from an earlier cancel returning immediately is exactly the
        // behaviour wanted (the flag is already set, so the caller exits on the next check anyway).
        gate.await(timeoutMs);
    }
}
