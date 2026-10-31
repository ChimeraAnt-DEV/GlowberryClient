package org.chimeramc.client.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one-shot wake-up behind the daemon loops (item 15). The rules that matter: a signal wakes a
 * waiter immediately, a timeout bounds a wait that nothing signals, and an interrupt is reported
 * rather than swallowed so a loop can use it as its shutdown path.
 */
public class SignalGateTest {

    @Test
    public void aSignalWakesAWaitingThreadPromptly() throws Exception {
        SignalGate gate = new SignalGate();
        long[] waitedMs = new long[1];
        Thread waiter = new Thread(() -> {
            long start = System.nanoTime();
            assertTrue(gate.await(0));
            waitedMs[0] = (System.nanoTime() - start) / 1_000_000L;
        });
        waiter.start();
        Thread.sleep(50);
        gate.signal();
        waiter.join(2000);
        assertFalse(waiter.isAlive());
        // A signal must not have to wait out a poll interval.
        assertTrue("waited " + waitedMs[0] + "ms", waitedMs[0] < 1000L);
    }

    @Test
    public void aTimeoutBoundsAWaitNothingSignals() {
        SignalGate gate = new SignalGate();
        long start = System.nanoTime();
        assertTrue(gate.await(60));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("returned in " + elapsedMs + "ms", elapsedMs >= 40L);
        assertTrue("returned in " + elapsedMs + "ms", elapsedMs < 3000L);
    }

    @Test
    public void anInterruptIsReportedAndTheFlagIsRestored() throws Exception {
        SignalGate gate = new SignalGate();
        boolean[] result = new boolean[1];
        boolean[] flagRestored = new boolean[1];
        Thread waiter = new Thread(() -> {
            result[0] = gate.await(0);
            flagRestored[0] = Thread.currentThread().isInterrupted();
        });
        waiter.start();
        Thread.sleep(50);
        waiter.interrupt();
        waiter.join(2000);
        assertFalse("an interrupted wait reports false", result[0]);
        assertTrue("the interrupt flag survives", flagRestored[0]);
    }

    @Test
    public void signallingWithNoWaiterIsHarmless() {
        SignalGate gate = new SignalGate();
        gate.signal();
        gate.signal();
        assertTrue(gate.await(20));
    }

    @Test
    public void awaitUntilReportsTheDeadlineWhenNothingSignals() {
        SignalGate gate = new SignalGate();
        long start = System.nanoTime();
        SignalGate.Wake wake = gate.awaitUntil(elapsedMs() + 30L, SignalGateTest::elapsedMs);
        assertTrue("returned after " + elapsedMs(start) + "ms",
                elapsedMs(start) >= 15L);
        assertEquals(SignalGate.Wake.DEADLINE, wake);
    }

    /** A real monotonic clock, in ms from an arbitrary origin. */
    private static long elapsedMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    @Test
    public void awaitUntilReportsASignalThatArrivesDuringTheWait() throws Exception {
        SignalGate gate = new SignalGate();
        AtomicReference<SignalGate.Wake> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            result.set(gate.awaitUntil(elapsedMs() + 60_000L, SignalGateTest::elapsedMs));
            done.countDown();
        });
        waiter.setDaemon(true);
        waiter.start();

        Thread.sleep(60L);
        gate.signal();

        assertTrue("a signal must cut the wait short", done.await(2, TimeUnit.SECONDS));
        assertEquals(SignalGate.Wake.SIGNALLED, result.get());
    }

    @Test
    public void aStaleSignalDoesNotMakeTheNextWaitSpin() throws Exception {
        SignalGate gate = new SignalGate();
        // Signal with nobody waiting: the permit is remembered by the Condition.
        gate.signal();

        long start = System.nanoTime();
        SignalGate.Wake wake = gate.awaitUntil(elapsedMs() + 50L, SignalGateTest::elapsedMs);
        long waited = elapsedMs(start);

        // The drain means the wait runs to the deadline instead of returning at once on the
        // remembered permit, which is what would turn a paced loop into a spin.
        assertEquals(SignalGate.Wake.DEADLINE, wake);
        assertTrue("the stale signal must be drained (took " + waited + "ms)", waited >= 20L);
    }

    @Test
    public void awaitUntilReportsAnInterruptAndRestoresTheFlag() throws Exception {
        SignalGate gate = new SignalGate();
        AtomicReference<SignalGate.Wake> result = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            result.set(gate.awaitUntil(elapsedMs() + 60_000L, SignalGateTest::elapsedMs));
            interrupted.set(Thread.currentThread().isInterrupted());
            done.countDown();
        });
        waiter.setDaemon(true);
        waiter.start();

        Thread.sleep(60L);
        waiter.interrupt();

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(SignalGate.Wake.INTERRUPTED, result.get());
        assertTrue("the interrupt flag must be restored so the caller's shutdown works", interrupted.get());
    }

    @Test
    public void aSignalDoesNotAdvanceTheDeadline() throws Exception {
        SignalGate gate = new SignalGate();
        long deadline = elapsedMs() + 5_000L;
        AtomicReference<SignalGate.Wake> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            result.set(gate.awaitUntil(deadline, SignalGateTest::elapsedMs));
            done.countDown();
        });
        waiter.setDaemon(true);
        waiter.start();

        Thread.sleep(50L);
        gate.signal();

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(SignalGate.Wake.SIGNALLED, result.get());
        // The wake-up must not push the caller's schedule out, or a paced loop would drift.
        assertTrue("the deadline must not move", elapsedMs() < deadline);
    }
}
