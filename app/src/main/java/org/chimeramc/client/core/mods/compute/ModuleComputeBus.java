package org.chimeramc.client.core.mods.compute;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicReference;

import org.chimeramc.client.util.AppExecutors;

/**
 * A single-worker compute bus that keeps heavy module work off the render thread.
 *
 * <p>Modules submit a frame input from the render/tick thread; the worker consumes the <em>latest</em>
 * input off-thread and publishes a result the render thread reads back. This is the in-process
 * equivalent of the "route module communication through a separate daemon thread" pattern: the render
 * thread never blocks, and a slow solve can never stall a frame.
 *
 * <p><b>Bounded by construction.</b> The pending input is a single {@link AtomicReference}, so
 * {@link #submit} overwrites any unconsumed input rather than queueing it — the drop-oldest /
 * keep-latest rule in {@link ComputeSchedule}. A crowded server therefore cannot grow the bus without
 * bound.
 *
 * <p><b>Fail-closed.</b> A throwing solve is caught, recorded as the last error, and the previous
 * result is kept, so one bad module cannot take the worker thread down or publish a half-computed
 * result. {@link #lastError()} surfaces it instead.
 *
 * <p><b>The worker waits, it does not poll.</b> It parks on a condition that {@link #submit}
 * signals, rather than sleeping and re-checking. A poll loop burns a core and, worse, adds up to a
 * full poll interval of latency to every solve: a 4 ms sleep is 4 ms of delay on a 16 ms frame
 * budget, for work that was submitted to be ready this frame. The wait also makes {@link #stop}
 * immediate instead of "within one poll interval".
 *
 * <p>Threads: {@link #submit} and {@link #latestResult} are safe from any thread. The worker is the
 * only consumer of the pending input.
 */
public final class ModuleComputeBus<I, O> {

    /** A solve from an input to an output. */
    public interface Solver<I, O> {
        O solve(I input) throws Exception;
    }

    private final Solver<I, O> solver;
    private final AtomicReference<I> pending = new AtomicReference<>(null);
    private final AtomicReference<O> result = new AtomicReference<>(null);
    private final AtomicReference<Throwable> lastError = new AtomicReference<>(null);
    private final ComputeSchedule schedule = new ComputeSchedule();

    private final ReentrantLock waitLock = new ReentrantLock();
    private final Condition pendingAvailable = waitLock.newCondition();

    private Thread worker;
    private volatile boolean running;

    public ModuleComputeBus(Solver<I, O> solver) {
        this.solver = solver;
    }

    /** Starts the worker thread. Idempotent. */
    public synchronized void start(String threadName) {
        if (running) return;
        running = true;
        worker = AppExecutors.thread(threadName == null ? "module-compute" : threadName, this::loop);
        worker.start();
    }

    /** Stops the worker thread and clears any pending input. */
    public synchronized void stop() {
        running = false;
        pending.set(null);
        // Wake the worker so it observes running == false now rather than on its next poll.
        waitLock.lock();
        try {
            pendingAvailable.signalAll();
        } finally {
            waitLock.unlock();
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    /** Submits an input from the render/tick thread, replacing any unconsumed one. */
    public void submit(I input) {
        pending.set(input);
        schedule.submit(0L);
        waitLock.lock();
        try {
            pendingAvailable.signalAll();
        } finally {
            waitLock.unlock();
        }
    }

    /**
     * How many inputs are waiting to be solved: 0 or 1.
     *
     * <p>The bus is bounded by construction, so this is a two-state reading rather than a length. It
     * exists for the Diagnostics page, where a value that is stuck at 1 means the worker is not
     * keeping up and the inputs are being replaced before they are solved.
     */
    public int queueDepth() {
        return pending.get() == null ? 0 : 1;
    }

    /** The most recent published result, or null when none has been produced. */
    public O latestResult() {
        return result.get();
    }

    /** The last solve failure, or null. Never cleared by a later success, so it stays diagnosable. */
    public Throwable lastError() {
        return lastError.get();
    }

    /** True when the worker is running. */
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            final I input = pending.getAndSet(null);
            if (input == null) {
                waitLock.lock();
                try {
                    // Re-check under the lock: a submit that raced the getAndSet above has already
                    // signalled, and awaiting after that signal would park until the next submit.
                    if (running && pending.get() == null) {
                        pendingAvailable.await();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    waitLock.unlock();
                }
                continue;
            }
            try {
                O output = solver.solve(input);
                if (output != null) result.set(output);
            } catch (Throwable t) {
                // Fail-closed: keep the previous result and record the failure rather than letting it
                // kill the worker thread (which would silently stop every module on the bus).
                lastError.set(t);
            }
        }
    }
}
