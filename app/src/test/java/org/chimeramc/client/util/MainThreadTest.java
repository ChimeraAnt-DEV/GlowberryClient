package org.chimeramc.client.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The lifetime rules behind every main-thread post (items 14, 295).
 *
 * <p>The scheduler is replaced with a queue a test can drive, so what is asserted is the rule the
 * production code depends on: closing a scope removes exactly the work it posted, and refuses new
 * work so a running callback cannot restart the loop it was cancelled with.
 */
public class MainThreadTest {

    /** A scheduler that queues work instead of posting it to a looper. */
    private static final class FakeScheduler implements MainThread.Scheduler {
        final Deque<Runnable> queue = new ArrayDeque<>();
        final Set<Runnable> removed = new LinkedHashSet<>();
        long lastDelayMs = -1;

        @Override
        public void post(Runnable work) {
            queue.addLast(work);
        }

        @Override
        public void postDelayed(Runnable work, long delayMs) {
            lastDelayMs = delayMs;
            queue.addLast(work);
        }

        @Override
        public void remove(Runnable work) {
            removed.add(work);
        }

        /** Runs everything queued, as the looper would. */
        void drain() {
            while (!queue.isEmpty()) {
                queue.pollFirst().run();
            }
        }
    }

    private static FakeScheduler install() {
        FakeScheduler scheduler = new FakeScheduler();
        MainThread.setSchedulerForTest(scheduler);
        return scheduler;
    }

    @Test
    public void aPostedTaskRunsWhenTheQueueDrains() {
        FakeScheduler scheduler = install();
        boolean[] ran = new boolean[1];
        MainThread.post(() -> ran[0] = true);
        assertFalse(ran[0]);
        scheduler.drain();
        assertTrue(ran[0]);
    }

    @Test
    public void aScopedTaskRunsAndLeavesNothingPending() {
        FakeScheduler scheduler = install();
        MainThread.Scope scope = MainThread.scope();
        boolean[] ran = new boolean[1];
        scope.post(() -> ran[0] = true);
        assertEquals(1, scope.pendingCount());
        scheduler.drain();
        assertTrue(ran[0]);
        assertTrue(scope.isEmpty());
    }

    @Test
    public void closingAScopeRemovesItsPendingWork() {
        FakeScheduler scheduler = install();
        MainThread.Scope scope = MainThread.scope();
        Runnable tick = () -> { };
        scope.postDelayed(tick, 500);
        scope.close();
        assertTrue(scope.isEmpty());
        assertTrue(scope.isClosed());
        assertTrue(scheduler.removed.contains(tick));
    }

    @Test
    public void aClosedScopeRefusesNewWork() {
        // The half a bare removeCallbacksAndMessages(null) cannot do: a callback that is already
        // running posts the next tick, and that tick must not survive the teardown.
        FakeScheduler scheduler = install();
        MainThread.Scope scope = MainThread.scope();
        scope.close();
        boolean[] ran = new boolean[1];
        scope.post(() -> ran[0] = true);
        assertEquals(0, scope.pendingCount());
        scheduler.drain();
        assertFalse(ran[0]);
    }

    @Test
    public void closingAScopeTwiceIsHarmless() {
        install();
        MainThread.Scope scope = MainThread.scope();
        scope.post(() -> { });
        scope.close();
        scope.close();
        assertTrue(scope.isEmpty());
    }

    @Test
    public void aTaskCancelledBeforeItRunsDoesNotRun() {
        // The queue cannot un-dispatch a message that is already being dispatched, so the scope's
        // own flag is the second half of the guarantee.
        FakeScheduler scheduler = install();
        MainThread.Scope scope = MainThread.scope();
        boolean[] ran = new boolean[1];
        Runnable work = () -> ran[0] = true;
        scope.post(work);
        scope.remove(work);
        scheduler.drain();
        assertFalse(ran[0]);
    }

    @Test
    public void removingOneTaskLeavesTheOthersPending() {
        FakeScheduler scheduler = install();
        MainThread.Scope scope = MainThread.scope();
        boolean[] ran = new boolean[2];
        Runnable first = () -> ran[0] = true;
        Runnable second = () -> ran[1] = true;
        scope.post(first);
        scope.post(second);
        scope.remove(first);
        assertEquals(1, scope.pendingCount());
        scheduler.drain();
        assertFalse(ran[0]);
        assertTrue(ran[1]);
    }

    @Test
    public void aDelayIsPassedThroughToTheScheduler() {
        FakeScheduler scheduler = install();
        MainThread.Scope scope = MainThread.scope();
        scope.postDelayed(() -> { }, 250);
        assertEquals(250L, scheduler.lastDelayMs);
    }

    @Test
    public void theAlreadyClosedScopeTakesWorkAndDropsIt() {
        FakeScheduler scheduler = install();
        MainThread.Scope scope = MainThread.Scope.closed();
        scope.post(() -> { });
        scope.postDelayed(() -> { }, 10);
        assertTrue(scope.isEmpty());
        assertTrue(scheduler.queue.isEmpty());
    }

    @Test
    public void aNullTaskIsIgnoredRatherThanQueued() {
        FakeScheduler scheduler = install();
        MainThread.post(null);
        MainThread.postDelayed(null, 10);
        MainThread.Scope scope = MainThread.scope();
        scope.post(null);
        scope.remove(null);
        assertTrue(scheduler.queue.isEmpty());
        assertTrue(scope.isEmpty());
    }

    @Test
    public void aThreadNameIsNeverBlank() {
        assertEquals("chimera-worker", AppExecutors.threadName(null));
        assertEquals("chimera-worker", AppExecutors.threadName("   "));
        assertEquals("voice-capture", AppExecutors.threadName("  voice-capture  "));
    }

    @Test
    public void aNamedThreadIsADaemonAndCarriesItsName() {
        Thread thread = AppExecutors.thread("replay-io", () -> { });
        assertEquals("replay-io", thread.getName());
        assertTrue("a worker must not hold the process open", thread.isDaemon());
    }

    @Test
    public void workSubmittedToAPoolRunsWithTheTaskNameOnTheThread() {
        // The pool's thread is reused, so the name is set per task: a report then names the
        // operation that was running rather than whichever task happened to run first.
        String[] observed = new String[1];
        try {
            AppExecutors.io(AppExecutors.Kind.DISK)
                    .submit(() -> observed[0] = Thread.currentThread().getName())
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertTrue("thread was " + observed[0], observed[0].startsWith("disk-"));
    }
}
