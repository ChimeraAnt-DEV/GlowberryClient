package org.chimeramc.client.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pins the guarded-submit contract with real executors, not mocks.
 *
 * <p>Every one of these cases is a bug that shipped: a control whose work was submitted to a pool
 * that a teardown path had already shut down, so the submit threw
 * {@link RejectedExecutionException} on the main thread (killing the screen) or the callback that
 * settled a dialog never arrived.
 */
public class SafeExecutorTest {

    @Test
    public void workRunsWhenThePoolIsAlive() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger ran = new AtomicInteger();
            assertTrue(SafeExecutor.submit(executor, ran::incrementAndGet));
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, ran.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    public void aShutDownPoolReportsFailureInsteadOfThrowing() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        assertTrue(executor.isShutdown());

        AtomicInteger ran = new AtomicInteger();
        // The whole point: this does not throw on the calling thread.
        assertFalse(SafeExecutor.submit(executor, ran::incrementAndGet));
        assertEquals(0, ran.get());
    }

    @Test
    public void aShutDownNowPoolReportsFailureInsteadOfThrowing() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdownNow();
        assertFalse(SafeExecutor.submit(executor, () -> {
        }));
    }

    @Test
    public void nullInputsAreRefusedRatherThanThrowing() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertFalse(SafeExecutor.submit(null, () -> {
            }));
            assertFalse(SafeExecutor.submit(executor, null));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A pool that rejects for its own reason (here: a capacity of zero through a subclass) must be
     * reported, not swallowed as if the work had run. The distinction matters because the caller
     * uses the false return to answer a callback with a failure.
     */
    @Test
    public void aRejectionIsReportedAsNotSubmitted() {
        ExecutorService rejecting = new java.util.concurrent.AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                throw new RejectedExecutionException("at capacity");
            }

            @Override
            public void shutdown() {
            }

            @Override
            public java.util.List<Runnable> shutdownNow() {
                return java.util.Collections.emptyList();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
                return true;
            }
        };

        AtomicInteger ran = new AtomicInteger();
        assertFalse(SafeExecutor.submit(rejecting, ran::incrementAndGet));
        assertEquals(0, ran.get());
    }
}
