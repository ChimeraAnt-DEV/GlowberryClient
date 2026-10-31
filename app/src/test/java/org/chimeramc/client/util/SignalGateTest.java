package org.chimeramc.client.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
}
