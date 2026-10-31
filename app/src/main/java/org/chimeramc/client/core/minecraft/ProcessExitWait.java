package org.chimeramc.client.core.minecraft;

import java.util.function.LongSupplier;

/**
 * Waits for a process to exit before the launcher starts its replacement (item 275).
 *
 * <p>The restart path kills the old launcher process and then starts {@code MainActivity} after a
 * fixed delay. A fixed delay is a guess: too short and the new process can start while the old one
 * still holds the app's storage and the game's files, which is the race the restart exists to avoid;
 * too long and the user stares at a blank screen. The delay also has no way to report that the old
 * process refused to die.
 *
 * <p>This class turns the guess into a bounded poll. It is pure: the existence probe, the clock and
 * the sleep are all injected, so the timeout and the "exited vs gave up" decision are unit-tested
 * without a device or a second process.
 */
public final class ProcessExitWait {

    /** The default ceiling: long enough for a normal exit, short enough not to look hung. */
    public static final long DEFAULT_TIMEOUT_MS = 3000L;
    /** How often the probe is consulted. */
    public static final long DEFAULT_POLL_MS = 50L;

    /** Whether a process is still alive. */
    public interface Probe {
        boolean exists(int pid);
    }

    /** Blocks for a while. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private ProcessExitWait() {
    }

    /**
     * Polls until the process is gone or the timeout expires.
     *
     * @return true when the process exited within the budget, false when the wait timed out (the
     *     caller should continue anyway rather than hang, but it now knows the old process may still
     *     be alive).
     */
    public static boolean await(int pid, Probe probe, long timeoutMs, long pollMs,
                                LongSupplier clock, Sleeper sleeper) {
        if (pid <= 0 || probe == null) {
            return true;      // nothing to wait for reads as "already gone"
        }
        if (timeoutMs <= 0) {
            return !probe.exists(pid);
        }
        long step = pollMs > 0 ? pollMs : DEFAULT_POLL_MS;
        long start = clock.getAsLong();
        while (true) {
            if (!probe.exists(pid)) {
                return true;
            }
            long elapsed = clock.getAsLong() - start;
            if (elapsed >= timeoutMs) {
                return false;
            }
            long remaining = timeoutMs - elapsed;
            try {
                sleeper.sleep(Math.min(step, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return !probe.exists(pid);
            }
        }
    }

    /** Convenience overload using the system clock and {@link Thread#sleep(long)}. */
    public static boolean await(int pid, Probe probe, long timeoutMs) {
        return await(pid, probe, timeoutMs, DEFAULT_POLL_MS, System::currentTimeMillis,
                Thread::sleep);
    }
}
