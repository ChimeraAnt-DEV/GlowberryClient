package org.chimeramc.client.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Submits work to an executor that a teardown path may already have shut down.
 *
 * <p>A {@code isShutdown()} check followed by {@code execute(...)} is a check-then-act: the pool can
 * be shut down between the two on another thread (a user backs out of the screen while a button
 * press is being handled), and {@link ExecutorService#execute} then throws
 * {@link RejectedExecutionException} on the <em>calling</em> thread - which for every caller in this
 * app is the main thread, so the screen dies. Answering the callback is the other half: several of
 * these operations only settle a dialog or a row from their callback, and letting the submit throw
 * left that control waiting on a result that could never arrive.
 *
 * <p>Every caller here has a well-defined "could not start" outcome - a dropped refresh, a failure
 * message - so the boolean is what the call site acts on rather than an exception it cannot handle.
 */
public final class SafeExecutor {

    private SafeExecutor() {
    }

    /**
     * Submits {@code work}, returning false instead of throwing when the pool is gone.
     *
     * @return true when the work was accepted; false when the executor was shut down
     */
    public static boolean submit(ExecutorService executor, Runnable work) {
        if (executor == null || work == null) return false;
        try {
            executor.execute(work);
            return true;
        } catch (RejectedExecutionException rejected) {
            // The owner tore the pool down under the caller. Nothing was run and nothing will be.
            return false;
        }
    }
}
