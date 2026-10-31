package org.chimeramc.client.core.mods.inbuilt.overlay;

/**
 * Pure rolling frame-rate counter: a fixed window of frame timestamps reduced to a whole-number
 * rate. Android-free so the arithmetic is unit-testable without a device.
 *
 * <p>Frames arrive as monotonic nanosecond stamps. {@link #fpsAt} counts how many stamps fall
 * within the last second. A duplicate stamp (some stacks deliver one) is ignored so it cannot
 * inflate the rate.
 */
public final class FrameRateCounter {

    /** Frame timestamps retained; enough for a second at any plausible refresh rate. */
    public static final int WINDOW = 240;

    /** Sentinel so a legitimate first frame at t=0 is not mistaken for a duplicate. */
    private static final long NO_STAMP = Long.MIN_VALUE;

    private final long[] stamps = new long[WINDOW];
    private int cursor;
    private int count;
    private long lastStampNanos = NO_STAMP;

    /** Records one frame timestamp. A repeated timestamp is ignored. */
    public void record(long frameTimeNanos) {
        if (frameTimeNanos == lastStampNanos) return;
        lastStampNanos = frameTimeNanos;
        stamps[cursor] = frameTimeNanos;
        cursor = (cursor + 1) % WINDOW;
        if (count < WINDOW) count++;
    }

    /** Frames within the last second of {@code nowNanos}, as a whole number. */
    public int fpsAt(long nowNanos) {
        if (count < 2) return 0;
        long cutoff = nowNanos - 1_000_000_000L;
        int frames = 0;
        // Walk newest-first and stop at the first stamp older than the window.
        for (int i = 1; i <= count; i++) {
            int index = (cursor - i + WINDOW * 2) % WINDOW;
            if (stamps[index] < cutoff) break;
            frames++;
        }
        return frames;
    }

    /** The rate at the most recent stamp; 0 before two frames are known. */
    public int fps() {
        return fpsAt(lastStampNanos);
    }

    /** The most recent stamp, or {@link #NO_STAMP} before any frame. */
    public long lastStampNanos() {
        return lastStampNanos;
    }

    /** The 1% low rate at the most recent stamp; 0 before two frames are known. */
    public int onePercentLowFps() {
        return onePercentLowFpsAt(lastStampNanos);
    }

    /**
     * The 1% low rate at {@code nowNanos}: the rate implied by the slowest single frame interval in
     * the window, i.e. {@code 1e9 / worstInterval}. A single number hides stutter, so the debug
     * screen shows this beside the average — a steady 60 with a 20 fps low reads very differently
     * from a flat 60. Returns 0 when fewer than two frames are known or the worst interval is not
     * positive.
     */
    public int onePercentLowFpsAt(long nowNanos) {
        long worst = worstIntervalNanos(nowNanos);
        if (worst <= 0) return 0;
        return (int) Math.round(1_000_000_000.0 / (double) worst);
    }

    /**
     * The slowest interval between two consecutive in-window frames, in nanoseconds, or 0 when
     * there is not yet a pair. Walks the retained stamps newest-first and stops at the first stamp
     * older than the one-second window, so it measures the same frames {@link #fpsAt} counts.
     */
    public long worstIntervalNanos(long nowNanos) {
        if (count < 2) return 0;
        long cutoff = nowNanos - 1_000_000_000L;
        long worst = 0;
        long previous = Long.MIN_VALUE;
        for (int i = 1; i <= count; i++) {
            int index = (cursor - i + WINDOW * 2) % WINDOW;
            long stamp = stamps[index];
            if (stamp < cutoff) break;
            if (previous != Long.MIN_VALUE) {
                long interval = previous - stamp;
                if (interval > worst) worst = interval;
            }
            previous = stamp;
        }
        return worst;
    }

    public void reset() {
        cursor = 0;
        count = 0;
        lastStampNanos = NO_STAMP;
    }
}
