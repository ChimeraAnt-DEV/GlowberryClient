package org.chimeramc.client.core.minecraft;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Pins the bounded process-exit wait (item 275) without a device or a second process.
 *
 * <p>The restart path kills the old launcher process and then starts the replacement. A fixed delay
 * is a guess that can start the new process while the old one still holds the app's storage; the
 * wait polls instead, and these tests pin the two ends: it returns as soon as the process is gone,
 * and it gives up at the timeout rather than hanging forever.
 */
public class ProcessExitWaitTest {

    /** A probe backed by a mutable flag, plus a fake clock the sleeper advances. */
    private static final class Fake {
        final AtomicLong clock = new AtomicLong(1_000L);
        boolean alive = true;
        int probes = 0;

        ProcessExitWait.Probe probe() {
            return pid -> {
                probes++;
                return alive;
            };
        }

        ProcessExitWait.Sleeper sleeper() {
            return millis -> clock.addAndGet(millis);
        }
    }

    @Test
    public void returnsTrueImmediatelyWhenTheProcessIsAlreadyGone() {
        Fake fake = new Fake();
        fake.alive = false;
        boolean exited = ProcessExitWait.await(42, fake.probe(), 3_000L, 50L,
                fake.clock::get, fake.sleeper());
        assertTrue(exited);
    }

    @Test
    public void returnsTrueOnceTheProcessExitsMidWait() {
        Fake fake = new Fake();
        ProcessExitWait.Probe probe = pid -> {
            fake.probes++;
            // Gone after the third probe, simulating a process that takes a moment to die.
            return fake.probes < 3;
        };
        boolean exited = ProcessExitWait.await(42, probe, 3_000L, 50L,
                fake.clock::get, fake.sleeper());
        assertTrue(exited);
        assertTrue("should have polled more than once", fake.probes >= 3);
    }

    @Test
    public void givesUpAtTheTimeoutRatherThanHanging() {
        Fake fake = new Fake();      // alive stays true forever
        boolean exited = ProcessExitWait.await(42, fake.probe(), 500L, 50L,
                fake.clock::get, fake.sleeper());
        assertFalse("a process that never exits must read as a timeout", exited);
        assertTrue("the fake clock must have advanced to the budget", fake.clock.get() >= 1_500L);
    }

    @Test
    public void anInvalidPidReadsAsAlreadyGone() {
        Fake fake = new Fake();
        assertTrue(ProcessExitWait.await(0, fake.probe(), 1_000L, 50L,
                fake.clock::get, fake.sleeper()));
        assertTrue(ProcessExitWait.await(-1, fake.probe(), 1_000L, 50L,
                fake.clock::get, fake.sleeper()));
        assertTrue(ProcessExitWait.await(42, null, 1_000L, 50L,
                fake.clock::get, fake.sleeper()));
    }

    @Test
    public void aZeroTimeoutIsAPureExistenceCheck() {
        Fake fake = new Fake();
        fake.alive = false;
        assertTrue(ProcessExitWait.await(42, fake.probe(), 0L, 50L,
                fake.clock::get, fake.sleeper()));
        fake.alive = true;
        assertFalse(ProcessExitWait.await(42, fake.probe(), 0L, 50L,
                fake.clock::get, fake.sleeper()));
    }

    @Test
    public void anInterruptStopsTheWaitAndRestoresTheFlag() {
        Fake fake = new Fake();
        ProcessExitWait.Sleeper interrupting = millis -> {
            throw new InterruptedException("stop");
        };
        boolean exited = ProcessExitWait.await(42, fake.probe(), 3_000L, 50L,
                fake.clock::get, interrupting);
        assertFalse(exited);
        assertTrue("the interrupt flag must be restored",
                Thread.currentThread().isInterrupted());
        // Clear it so the interrupt does not leak into the next test.
        Thread.interrupted();
    }
}
