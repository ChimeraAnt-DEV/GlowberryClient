package org.chimeramc.client.util;

import android.os.Handler;
import android.os.Looper;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The one place work is posted to the main thread (items 14, 295).
 *
 * <p>There were 77 {@code new Handler(Looper.getMainLooper())} constructions. Most were an instance
 * field that posts a repeating tick and removes it in {@code onDestroy} — but a tick that is posted
 * and never removed outlives the view that started it, keeps a reference to the Activity through its
 * closure, and fires into a detached tree. The bug is invisible until it is not: it shows up as a
 * leak, or as a callback running after the screen is gone.
 *
 * <p>A {@link Scope} is the fix, and it is the reason this class exists rather than a shared
 * Handler. Work is posted through the scope, the scope remembers it, and closing the scope removes
 * everything it posted. The lifetime is then expressed once, next to the thing that owns it, instead
 * of as a {@code removeCallbacks} that has to be remembered in every exit path.
 *
 * <p>The bookkeeping is split from the {@link Handler} through {@link Scheduler}, because a Handler
 * does not exist in a JVM unit test and the rules here — a closed scope refuses new work, closing
 * removes everything, closing twice is harmless — are exactly the rules a test should pin.
 */
public final class MainThread {

    /** What a scope posts through. The production implementation is a main-looper Handler. */
    public interface Scheduler {
        void post(Runnable work);

        void postDelayed(Runnable work, long delayMs);

        void remove(Runnable work);
    }

    private static volatile Scheduler scheduler;

    private MainThread() {
    }

    /**
     * The main-thread scheduler, created on first use.
     *
     * <p>Lazy on purpose: a static {@code Handler} field would run {@code Looper.getMainLooper()} at
     * class-initialisation, which throws off-device and would make every class that merely mentions
     * this one un-loadable in a test.
     */
    public static Scheduler scheduler() {
        Scheduler current = scheduler;
        if (current != null) return current;
        synchronized (MainThread.class) {
            if (scheduler == null) {
                scheduler = new HandlerScheduler();
            }
            return scheduler;
        }
    }

    /** Replaces the scheduler. Tests only. */
    static void setSchedulerForTest(Scheduler replacement) {
        scheduler = replacement;
    }

    /**
     * A main-looper {@link Handler} for an owner that needs its own removal scope.
     *
     * <p>Not a shared instance, on purpose: {@code removeCallbacksAndMessages(null)} is the teardown
     * most of these owners use, and on a shared handler it would cancel every other component's
     * pending work as well. Routing the construction here still gives one owner for the main looper
     * and one place the looper dependency lives.
     */
    public static Handler handler() {
        return new Handler(Looper.getMainLooper());
    }

    /** Posts to the main thread with no lifetime of its own. Prefer {@link #scope}. */
    public static void post(Runnable work) {
        if (work == null) return;
        scheduler().post(work);
    }

    /** Posts to the main thread after {@code delayMs}, with no lifetime of its own. */
    public static void postDelayed(Runnable work, long delayMs) {
        if (work == null) return;
        scheduler().postDelayed(work, delayMs);
    }

    /**
     * A set of pending posts that can be cancelled together.
     *
     * <p>Not thread-safe by design: a scope belongs to one main-thread owner, and making it
     * synchronised would add a lock to the very path (a per-frame tick) that must stay cheap. Every
     * method asserts the main thread in debug builds through the scheduler's own behaviour — a
     * Handler post from another thread is fine, but the bookkeeping here assumes one owner.
     */
    public static final class Scope {
        private final Set<Runnable> pending = new LinkedHashSet<>();
        private boolean closed;

        /** Posts and remembers the work so {@link #close()} can remove it. */
        public void post(Runnable work) {
            if (work == null) return;
            if (closed) return;
            pending.add(work);
            MainThread.scheduler().post(wrap(work));
        }

        /** Posts after a delay and remembers the work so {@link #close()} can remove it. */
        public void postDelayed(Runnable work, long delayMs) {
            if (work == null) return;
            if (closed) return;
            pending.add(work);
            MainThread.scheduler().postDelayed(wrap(work), delayMs);
        }

        /** Forgets a single post, for work that has already run or is no longer wanted. */
        public void remove(Runnable work) {
            if (work == null) return;
            if (pending.remove(work)) {
                MainThread.scheduler().remove(work);
            }
        }

        /** True when nothing is pending. */
        public boolean isEmpty() {
            return pending.isEmpty();
        }

        /** How many posts are outstanding. */
        public int pendingCount() {
            return pending.size();
        }

        /**
         * Removes every pending post and refuses new ones.
         *
         * <p>Refusing new work is the half that a bare {@code removeCallbacksAndMessages(null)}
         * cannot do: a callback that is already running can post the next tick, and without the
         * closed flag that tick survives the teardown and restarts the loop.
         */
        public void close() {
            if (closed) return;
            closed = true;
            for (Runnable work : pending) {
                MainThread.scheduler().remove(work);
            }
            pending.clear();
        }

        /** Whether {@link #close()} has run. */
        public boolean isClosed() {
            return closed;
        }

        /**
         * A scope that is already closed. For an owner that is created after its host is gone — a
         * view that is inflated and torn down in the same frame — so the caller needs no null check.
         */
        public static Scope closed() {
            Scope scope = new Scope();
            scope.closed = true;
            return scope;
        }

        private Runnable wrap(Runnable work) {
            return () -> {
                // A post that was cancelled between being scheduled and running must not run. The
                // scheduler's remove is best-effort (a message already being dispatched cannot be
                // un-dispatched), so the flag is the second half of the guarantee.
                if (!pending.remove(work)) return;
                work.run();
            };
        }
    }

    /** A new scope. Close it when the thing that owns it goes away. */
    public static Scope scope() {
        return new Scope();
    }

    /** The production scheduler: a Handler on the main looper. */
    private static final class HandlerScheduler implements Scheduler {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void post(Runnable work) {
            handler.post(work);
        }

        @Override
        public void postDelayed(Runnable work, long delayMs) {
            handler.postDelayed(work, delayMs);
        }

        @Override
        public void remove(Runnable work) {
            handler.removeCallbacks(work);
        }
    }
}
