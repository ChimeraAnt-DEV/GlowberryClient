package org.chimeramc.client.util;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A one-shot wake-up used by the daemon loops that would otherwise poll with {@code Thread.sleep}.
 *
 * <p>A sleep-then-recheck loop has two costs that are easy to miss because neither shows up as a
 * failure. It adds up to a full interval of latency to anything the loop was waiting for, and it
 * makes {@code stop()} take that long to take effect — a voice or cosmetics thread that sleeps a
 * second is still holding its socket for up to a second after the user turned the feature off, which
 * is why those joins had to be given a timeout instead of being allowed to complete.
 *
 * <p>This is a {@link Condition}, not a queue: the loops here have nothing to hand over, they just
 * need to be woken. It is deliberately one-shot rather than counting, because a count would have to
 * be reconciled by the caller and the callers only ever need "wake up and look again".
 *
 * <p>Every {@code await} returns whether the wait completed without an interrupt, and an interrupt
 * always restores the thread's interrupt flag — the loops use that as their shutdown signal, and a
 * swallowed flag turns a clean exit into a spin.
 */
public final class SignalGate {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition signalled = lock.newCondition();

    /** Wakes every waiter. Safe to call when nobody is waiting, and from any thread. */
    public void signal() {
        lock.lock();
        try {
            signalled.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until {@link #signal()} or the timeout.
     *
     * @param timeoutMs how long to wait; 0 or less waits until signalled
     * @return true when the wait completed, false when the thread was interrupted
     */
    public boolean await(long timeoutMs) {
        lock.lock();
        try {
            if (timeoutMs <= 0L) {
                signalled.await();
                return true;
            }
            signalled.await(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The reason an {@link #awaitUntil} wait ended.
     *
     * <p>A caller that waits for a deadline <em>and</em> a cancellation has to know which happened.
     * "Did it wake early?" is not recoverable from a bare boolean, and guessing it from the clock is
     * how a cancel gets swallowed and the wait runs to its full interval anyway.
     */
    public enum Wake {
        /** The deadline was reached. */
        DEADLINE,
        /** A signal arrived before the deadline. */
        SIGNALLED,
        /** The thread was interrupted; its interrupt flag has been restored. */
        INTERRUPTED
    }

    /**
     * Waits until the absolute deadline, waking early on a signal.
     *
     * <p>This replaces the {@code Thread.sleep(remaining)} in a timed loop that must also be
     * cancellable. It keeps the exact deadline — a wake-up does not push the schedule out, so the
     * loop cannot drift — and it <b>drains a stale permit before waiting</b>. That drain is the
     * whole reason this is not just {@link #await}: {@link Condition} remembers a signal that
     * arrived while nobody waited, so a loop that slept and was then woken by an unrelated signal
     * would re-enter and spin on the remembered permit. The result says which of the three things
     * happened, so the caller can re-check its own cancellation predicate instead of assuming the
     * deadline was reached.
     *
     * @param deadlineMs absolute deadline on the caller's own clock
     * @param clock      the clock {@code deadlineMs} was measured against
     */
    public Wake awaitUntil(long deadlineMs, LongSupplier clock) {
        lock.lock();
        try {
            signalled.await(0L, TimeUnit.NANOSECONDS);
            while (true) {
                long remaining = deadlineMs - clock.getAsLong();
                if (remaining <= 0L) {
                    return Wake.DEADLINE;
                }
                // The return value is the only reliable "was it signalled" signal: a timeout and a
                // signal are indistinguishable from the clock alone (a paused or coarse clock reads
                // the same either way), so inferring it from the time would report a plain timeout
                // as a wake-up and hide a cancel that never arrived.
                if (signalled.await(remaining, TimeUnit.MILLISECONDS)) {
                    return clock.getAsLong() >= deadlineMs ? Wake.DEADLINE : Wake.SIGNALLED;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Wake.INTERRUPTED;
        } finally {
            lock.unlock();
        }
    }
}