package org.chimeramc.client.util;

/**
 * Decides whether a periodic overlay refresh should actually touch its view (item 187).
 *
 * <p>The readout overlays run on a timer — 100 ms for CPS, 250 ms for FPS, 33 ms for the combat
 * widgets — and each tick used to call {@code setText} or {@code invalidate} unconditionally. The
 * value they show changes far less often than the timer fires, so most ticks repainted an identical
 * frame: layout, measure and a draw pass spent to produce the same pixels.
 *
 * <p>The rule is deliberately small and pure, so it is unit-testable without a device:
 *
 * <ul>
 *   <li>a <b>changed</b> value is painted immediately — a readout must never lag its own data;
 *   <li>an <b>unchanged</b> value is painted at most once per {@code minIntervalMs}, which keeps a
 *       continuously-animating overlay alive (a progress bar whose signature is stable but whose
 *       pixels must advance) while a static one stops repainting.
 * </ul>
 *
 * <p>Not thread-safe by design: every caller drives it from the overlay's own handler, so a lock
 * would only add cost to a path that runs on the game's frame budget.
 */
public final class OverlayRefreshGate {

    private final long minIntervalMs;
    private String lastSignature;
    private long lastRefreshMs = Long.MIN_VALUE;

    /**
     * @param minIntervalMs the floor between two paints of an unchanged value; 0 paints every tick.
     */
    public OverlayRefreshGate(long minIntervalMs) {
        this.minIntervalMs = Math.max(0L, minIntervalMs);
    }

    /**
     * Records the signature and answers whether this tick should paint.
     *
     * @param signature a value that changes exactly when the pixels must change (the formatted
     *                  text, or the decision the draw body reads); null is treated as "unknown" and
     *                  always paints, so a caller that cannot describe its state is never starved.
     * @param nowMs     a monotonic clock reading; a clock that jumps backwards is treated as due,
     *                  because one wasted paint beats a frozen overlay.
     */
    public boolean shouldRefresh(String signature, long nowMs) {
        if (signature == null) {
            lastSignature = null;
            lastRefreshMs = nowMs;
            return true;
        }
        boolean changed = !signature.equals(lastSignature);
        long elapsed = nowMs - lastRefreshMs;
        // A negative elapsed means the clock jumped backwards (uptime wrap, a test clock). Treat
        // that as due rather than as "not yet": one wasted paint beats a readout stuck on a stale
        // frame, which is the failure a monotonic assumption would produce.
        boolean due = lastRefreshMs == Long.MIN_VALUE || elapsed < 0 || elapsed >= minIntervalMs;
        if (!changed && !due) {
            return false;
        }
        lastSignature = signature;
        lastRefreshMs = nowMs;
        return true;
    }

    /** Forces the next tick to paint, for a show/reconfigure where the view was rebuilt. */
    public void reset() {
        lastSignature = null;
        lastRefreshMs = Long.MIN_VALUE;
    }
}
