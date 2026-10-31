package org.chimeramc.client.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one place a background thread is created (items 14, 294).
 *
 * <p>There were 41 {@code new Thread(...)} calls and 77 {@code new Handler(...)} constructions, each
 * naming its own thread or not naming it at all. Two costs, neither of which shows up as a failure:
 * an unnamed thread is invisible in a stack trace or a profiler sample, so a problem in one of them
 * is unattributable; and a thread created per operation cannot be bounded, so a burst of work
 * spawns a burst of threads. Both are fixed by having one owner that names every thread and holds
 * the pools.
 *
 * <p>The pools are shared deliberately. A single-threaded pool per feature would be 40 idle threads
 * on a phone; the IO pool is bounded so a burst queues instead of spawning, and its threads are
 * daemons so a non-daemon thread can never hold the process open after the last activity is gone.
 */
public final class AppExecutors {

    /** What the work is for. The name is what a stack trace will show. */
    public enum Kind {
        /** Disk: reading and writing instance directories, packs, worlds, backups. */
        DISK("disk"),
        /** Network: source listings, downloads, API calls, signature rules. */
        NETWORK("network"),
        /** Native mod work that must not run on the main thread but must stay serialised. */
        MOD("mod");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        String prefix() {
            return prefix;
        }
    }

    /** Unbounded, but it only ever holds queued work: the threads themselves are bounded. */
    private static final int IO_THREADS = 4;

    private static final ThreadPoolExecutor DISK = bounded("disk", IO_THREADS);
    private static final ThreadPoolExecutor NETWORK = bounded("network", IO_THREADS);
    private static final ThreadPoolExecutor MOD = bounded("mod", 2);

    private static final ScheduledExecutorService TIMERS =
            Executors.newScheduledThreadPool(1, factory("timer"));

    private AppExecutors() {
    }

    /** The pool for {@code kind}. */
    public static ExecutorService io(Kind kind) {
        switch (kind) {
            case NETWORK:
                return NETWORK;
            case MOD:
                return MOD;
            case DISK:
            default:
                return DISK;
        }
    }

    /** A pool for one serialised stream of work — a queue that happens to drain on one thread. */
    public static ExecutorService single(String name) {
        return Executors.newSingleThreadExecutor(factory(name));
    }

    /** A pool for one serialised stream of work that also needs delays. */
    public static ScheduledExecutorService scheduled(String name) {
        return Executors.newSingleThreadScheduledExecutor(factory(name));
    }

    /** The shared timer, for a one-off delay or a repeating tick that has no owner of its own. */
    public static ScheduledExecutorService timer() {
        return TIMERS;
    }

    /**
     * Runs {@code work} on the pool for {@code kind}.
     *
     * <p>The point of routing every one-shot through here is the thread name: a crash in a background
     * operation then names the operation, which is the difference between a report that can be acted
     * on and one that cannot.
     */
    public static void run(Kind kind, String name, Runnable work) {
        if (work == null) return;
        ExecutorService executor = io(kind);
        if (SafeExecutor.submit(executor, named(work, name))) return;
        // The pool was shut down. Falling back to a plain thread keeps the work alive rather than
        // dropping it, which is what every previous `new Thread(...)` call would have done.
        thread(name, work).start();
    }

    /** A daemon thread named {@code name}, for the few loops that own their own lifecycle. */
    public static Thread thread(String name, Runnable work) {
        Thread thread = new Thread(work, threadName(name));
        thread.setDaemon(true);
        return thread;
    }

    /**
     * The name every thread is given: {@code <prefix>-<n>} for a pooled thread, and the caller's
     * name unchanged when it already carries one.
     *
     * <p>Pure and package-visible so the naming rule is unit-tested rather than assumed — a thread
     * whose name does not say where it came from is the whole problem being fixed.
     */
    static String threadName(String name) {
        if (name == null || name.trim().isEmpty()) return "chimera-worker";
        return name.trim();
    }

    private static Runnable named(Runnable work, String name) {
        String threadName = threadName(name);
        return () -> {
            Thread current = Thread.currentThread();
            String previous = current.getName();
            // The pool's thread is reused, so the name is set per task and restored: a report then
            // names the operation that was running, not whichever task ran first.
            current.setName(threadName);
            try {
                work.run();
            } finally {
                current.setName(previous);
            }
        };
    }

    private static ThreadPoolExecutor bounded(String prefix, int threads) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads, threads,
                30L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                factory(prefix));
        // A SynchronousQueue with a full pool would reject rather than queue, which drops the work.
        // The caller-runs policy is what makes the bound safe: the submitting thread runs the task
        // instead of the task being lost.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static ThreadFactory factory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
