package org.chimeramc.client.util;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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
            signalled.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }
}
