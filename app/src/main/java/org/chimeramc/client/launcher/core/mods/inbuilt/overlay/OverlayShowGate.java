package org.chimeramc.client.core.mods.inbuilt.overlay;

/**
 * Guards a deferred overlay attach against a hide that happened while it was pending.
 *
 * <p>Several overlays attach a few hundred milliseconds after {@code show()} so the window token is
 * settled. The delay is posted on a {@link android.os.Handler} with no handle on the runnable, so a
 * {@code hide()} in that window cannot cancel it: the runnable still runs, adds the view, and the
 * overlay is visible again with nothing tracking it. It then survives until the session ends.
 *
 * <p>The gate is a generation counter rather than a boolean. A boolean would have to be reset by the
 * next {@code show()}, which is exactly the ordering the race makes unpredictable; a token that
 * {@code hide()} invalidates can only ever be stale, never accidentally re-armed.
 *
 * <p>Pure and Android-free, so the ordering rules are unit-tested rather than only visible on a
 * device with a slow window manager.
 */
final class OverlayShowGate {

    private long generation;

    /**
     * Takes a token for a deferred attach.
     *
     * <p>The token is current until {@link #invalidate()} runs. Calling this twice without an
     * invalidate in between returns two current tokens, which is correct: the second show is a
     * fresh intent, not a stale one.
     */
    long begin() {
        return generation;
    }

    /**
     * Whether a deferred attach may still proceed.
     *
     * <p>False once anything invalidated the gate, so the pending runnable does nothing instead of
     * re-adding a view the caller already asked to remove.
     */
    boolean isCurrent(long token) {
        return token == generation;
    }

    /**
     * Invalidates every outstanding token.
     *
     * <p>Called from {@code hide()} and from detach, so a hide always beats a pending show that was
     * posted before it.
     */
    void invalidate() {
        generation++;
    }
}
