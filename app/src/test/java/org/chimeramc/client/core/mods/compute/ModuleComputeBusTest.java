package org.chimeramc.client.core.mods.compute;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Pins the off-thread compute bus (no mocks).
 *
 * <p>The bus keeps heavy module work off the render thread. The invariants that matter: the pending
 * input is bounded to one (drop-oldest, keep-latest), a throwing solve is contained and does not
 * publish a half result or kill the worker, and the throttle coalesces a fast render loop.
 */
public class ModuleComputeBusTest {

    @Test
    public void theScheduleDropsSupersededInputs() {
        ComputeSchedule schedule = new ComputeSchedule();
        assertFalse("nothing pending", schedule.shouldRun(0, 0));
        schedule.submit(0);
        assertTrue("a pending input runs", schedule.shouldRun(0, 0));
        // A second submit before consumption still leaves exactly one pending item.
        schedule.submit(1);
        assertTrue(schedule.hasPending());
        schedule.markConsumed(0);
        assertFalse("consumed input does not re-run", schedule.shouldRun(0, 0));
        assertEquals(2, schedule.submittedCount());
    }

    @Test
    public void theScheduleThrottles() {
        ComputeSchedule schedule = new ComputeSchedule();
        schedule.submit(0);
        schedule.markConsumed(100);
        schedule.submit(101);
        assertFalse("inside the interval", schedule.shouldRun(105, 16));
        assertTrue("after the interval", schedule.shouldRun(116, 16));
        // A zero interval disables the throttle.
        ComputeSchedule noThrottle = new ComputeSchedule();
        noThrottle.submit(0);
        noThrottle.markConsumed(0);
        noThrottle.submit(1);
        assertTrue(noThrottle.shouldRun(0, 0));
    }

    @Test
    public void theBusProducesTheLatestResultOffThread() throws Exception {
        ModuleComputeBus<Integer, Integer> bus = new ModuleComputeBus<>(input -> input * 2);
        bus.start("test-compute");
        assertTrue(bus.isRunning());
        try {
            bus.submit(21);
            Integer value = awaitResult(bus);
            assertNotNull("a result was published", value);
            assertEquals(42, value.intValue());
        } finally {
            bus.stop();
        }
        assertFalse(bus.isRunning());
    }

    @Test
    public void aThrowingSolveIsContainedAndKeepsThePreviousResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModuleComputeBus<Integer, String> bus = new ModuleComputeBus<>(input -> {
            if (calls.incrementAndGet() == 1) return "ok:" + input;
            throw new IllegalStateException("boom");
        });
        bus.start("test-compute-fail");
        try {
            bus.submit(1);
            assertEquals("ok:1", awaitResult(bus));

            bus.submit(2);
            // Wait until the failing solve has run.
            for (int i = 0; i < 200 && bus.lastError() == null; i++) {
                Thread.sleep(5);
            }
            assertNotNull("the failure is recorded", bus.lastError());
            assertTrue(bus.lastError() instanceof IllegalStateException);
            // The worker survived and the previous result is intact.
            assertTrue(bus.isRunning());
            assertEquals("ok:1", bus.latestResult());
        } finally {
            bus.stop();
        }
    }

    @Test
    public void submitBeforeStartDoesNotLoseTheInput() throws Exception {
        ModuleComputeBus<Integer, Integer> bus = new ModuleComputeBus<>(input -> input + 1);
        bus.submit(9);
        bus.start("test-compute-late");
        try {
            assertEquals(10, awaitResult(bus).intValue());
        } finally {
            bus.stop();
        }
    }

    @Test
    public void theBusReportsItsDepthAndStopsPromptly() throws Exception {
        ModuleComputeBus<Integer, Integer> bus = new ModuleComputeBus<>(input -> input * 2);
        bus.start("test-compute-depth");
        try {
            // A block worker parks on the condition, so an idle bus reads as empty rather than
            // "always one pending" the way a poll loop that re-reads its input would.
            assertEquals(0, bus.queueDepth());
            bus.submit(4);
            assertEquals(8, awaitResult(bus).intValue());
            assertEquals(0, bus.queueDepth());
        } finally {
            long before = System.nanoTime();
            bus.stop();
            long elapsedMs = (System.nanoTime() - before) / 1_000_000L;
            // The wait is signalled, so a stop does not have to wait out a poll interval.
            assertTrue("stop returned in " + elapsedMs + "ms", elapsedMs < 1000L);
        }
        assertFalse(bus.isRunning());
    }

    private static <O> O awaitResult(ModuleComputeBus<?, O> bus) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            O value = bus.latestResult();
            if (value != null) return value;
            Thread.sleep(5);
        }
        return bus.latestResult();
    }
}
