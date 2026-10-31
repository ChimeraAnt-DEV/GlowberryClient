package org.chimeramc.client.core.auth;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The cancellation seam behind the device-code poll (item 15).
 *
 * <p>The property that matters is the one the old sleep-then-recheck could not give: a cancel that
 * arrives while the operation is waiting must release it at once, not at the end of the interval.
 * That is asserted against a real thread, because a cancel that only takes effect on the next poll
 * still passes any test that checks the flag after the fact.
 */
public class SignallingCancellationTest {

    @Test
    public void aCancelWakesABlockedWaiterImmediately() throws Exception {
        SignallingCancellation cancellation = new SignallingCancellation();
        AtomicBoolean returned = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            // A minute-long wait: only a real signal can end it in this test's lifetime.
            cancellation.awaitSignal(60_000L);
            returned.set(true);
            done.countDown();
        });
        waiter.setDaemon(true);
        waiter.start();

        Thread.sleep(60L);
        long start = System.nanoTime();
        cancellation.cancel();

        assertTrue("a cancel must release the waiter, not wait out the interval",
                done.await(2, TimeUnit.SECONDS));
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(returned.get());
        assertTrue("cancel latency was " + latencyMs + "ms", latencyMs < 1_000L);
    }

    @Test
    public void isCancelledIsTrueBeforeAndAfterTheSignal() {
        SignallingCancellation cancellation = new SignallingCancellation();
        assertFalse(cancellation.isCancelled());
        cancellation.cancel();
        assertTrue(cancellation.isCancelled());
    }

    @Test
    public void aCancelBeforeTheWaitReturnsAtOnce() {
        SignallingCancellation cancellation = new SignallingCancellation();
        cancellation.cancel();
        long start = System.nanoTime();
        cancellation.awaitSignal(60_000L);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        // The flag is the authority, so a wait entered after the cancel must not block.
        assertTrue("entered a cancelled wait and took " + elapsedMs + "ms", elapsedMs < 500L);
    }

    @Test
    public void cancelIsIdempotent() throws Exception {
        SignallingCancellation cancellation = new SignallingCancellation();
        cancellation.cancel();
        cancellation.cancel();
        assertTrue(cancellation.isCancelled());
    }

    @Test
    public void noneNeverCancelsAndStillTimesOut() {
        Cancellation none = Cancellation.none();
        assertFalse(none.isCancelled());
        long start = System.nanoTime();
        none.awaitSignal(30L);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("returned in " + elapsedMs + "ms", elapsedMs >= 15L);
        assertFalse(none.isCancelled());
    }

    @Test
    public void ofReadsThePredicate() {
        AtomicBoolean flag = new AtomicBoolean(false);
        Cancellation cancellation = Cancellation.of(flag::get);
        assertFalse(cancellation.isCancelled());
        flag.set(true);
        assertTrue(cancellation.isCancelled());
    }
}
