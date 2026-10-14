package org.chimeramc.client.core.mods.inbuilt.overlay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Containment for the mod-menu UI: wraps a step so one bad widget cannot kill the game process.
 *
 * <p>The controller settings screens (the CONTROLLER tab and the "Full settings" dialog) build a
 * large tree with {@code Controller3DView}, curve previews, layer rows and spinners. If any one of
 * those throws while the in-game overlay window is attached, an uncaught exception tears down the
 * game. This class is the seatbelt, <b>not</b> the fix: each step is wrapped, the failure is recorded
 * (class, message, first N stack frames) for the diagnostics report, and the caller shows a toast and
 * carries on.
 *
 * <p>Pure and Android-free so the recording rule (bounded, newest-first, truncated frames) is
 * unit-tested without a device.
 */
public final class ModMenuGuard {

    /** How many stack frames are kept per failure, enough to locate the throw site. */
    public static final int MAX_FRAMES = 12;
    /** How many failures are retained, so a loop cannot grow the buffer without bound. */
    public static final int MAX_ENTRIES = 32;

    /** One recorded failure. */
    public static final class Failure {
        public final String label;
        public final String throwableClass;
        public final String message;
        public final List<String> frames;
        public final long timeMs;

        Failure(String label, String throwableClass, String message, List<String> frames,
                long timeMs) {
            this.label = label == null ? "" : label;
            this.throwableClass = throwableClass == null ? "" : throwableClass;
            this.message = message == null ? "" : message;
            this.frames = frames == null ? Collections.emptyList() : frames;
            this.timeMs = timeMs;
        }

        /** A compact multi-line description for the diagnostics report. */
        public String describe() {
            StringBuilder b = new StringBuilder();
            b.append(label).append(": ").append(throwableClass);
            if (!message.isEmpty()) b.append(": ").append(message);
            for (String frame : frames) {
                b.append("\n    at ").append(frame);
            }
            return b.toString();
        }
    }

    private static final Deque<Failure> FAILURES = new ArrayDeque<>();

    private ModMenuGuard() {
    }

    /**
     * Runs {@code body}, catching any {@link Throwable}. Returns true when the body completed
     * without throwing; false when it failed (and the failure was recorded).
     */
    public static boolean run(String label, Runnable body) {
        if (body == null) return true;
        try {
            body.run();
            return true;
        } catch (Throwable t) {
            record(label, t, System.currentTimeMillis());
            return false;
        }
    }

    /** Records a failure with its class, message and first {@link #MAX_FRAMES} stack frames. */
    public static void record(String label, Throwable t, long timeMs) {
        if (t == null) return;
        List<String> frames = new ArrayList<>();
        StackTraceElement[] trace = t.getStackTrace();
        for (int i = 0; i < trace.length && i < MAX_FRAMES; i++) {
            frames.add(trace[i].toString());
        }
        synchronized (FAILURES) {
            FAILURES.addFirst(new Failure(label, t.getClass().getName(), t.getMessage(),
                    frames, timeMs));
            while (FAILURES.size() > MAX_ENTRIES) {
                FAILURES.removeLast();
            }
        }
    }

    /** The recorded failures, newest first. */
    public static List<Failure> failures() {
        synchronized (FAILURES) {
            return new ArrayList<>(FAILURES);
        }
    }

    /** True when at least one menu build step has failed this session. */
    public static boolean hasFailures() {
        synchronized (FAILURES) {
            return !FAILURES.isEmpty();
        }
    }

    /** Clears the buffer (session teardown). */
    public static void clear() {
        synchronized (FAILURES) {
            FAILURES.clear();
        }
    }

    /** The failures formatted for the diagnostics report, or a "none" line. */
    public static String format() {
        List<Failure> list = failures();
        if (list.isEmpty()) return "  none\n";
        StringBuilder sb = new StringBuilder();
        for (Failure f : list) {
            sb.append("  ").append(f.describe()).append('\n');
        }
        return sb.toString();
    }
}
