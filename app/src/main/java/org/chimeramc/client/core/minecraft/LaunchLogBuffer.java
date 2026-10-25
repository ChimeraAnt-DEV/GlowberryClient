package org.chimeramc.client.core.minecraft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * The bounded line buffer behind the launch screen's log view.
 *
 * <p>The view must never grow without limit: a cold launch of a large version can emit hundreds of
 * lines, and an unbounded {@code TextView} becomes a long-layout, high-memory scroll that also makes
 * every subsequent append slower. This class is the ceiling.
 *
 * <p>It is pure (no Android types, no clock) so the cap and the render are unit-testable, and it is
 * the single place both rules live: {@link #add} drops the oldest line past the cap, and
 * {@link #render} always returns the whole retained window rather than a delta. A delta render was
 * the bug this replaced — the buffer capped itself while the view kept appending, so the cap
 * governed the queue and not the screen.
 */
public final class LaunchLogBuffer {

    /** The most lines the launch log keeps. Older lines are dropped as new ones arrive. */
    public static final int MAX_LINES = 48;

    private final Deque<String> lines = new ArrayDeque<>();
    private final int maxLines;

    public LaunchLogBuffer() {
        this(MAX_LINES);
    }

    public LaunchLogBuffer(int maxLines) {
        this.maxLines = Math.max(1, maxLines);
    }

    /** Appends a line, dropping the oldest when the buffer is full. Null is ignored. */
    public void add(String line) {
        if (line == null) return;
        lines.addLast(line);
        while (lines.size() > maxLines) {
            lines.removeFirst();
        }
    }

    /** Whether a line identical to the previous one should be suppressed as noise. */
    public boolean isDuplicateOfLast(String line) {
        String last = lines.peekLast();
        return last != null && last.equals(line);
    }

    /** The number of retained lines. */
    public int size() {
        return lines.size();
    }

    /** Clears every retained line. */
    public void clear() {
        lines.clear();
    }

    /**
     * The whole retained window as one block of text, oldest line first.
     *
     * <p>Whole-window rather than a delta on purpose: the caller assigns this to the view, so the
     * view and the buffer can never disagree about how many lines exist.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = lines.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
            if (it.hasNext()) sb.append('\n');
        }
        return sb.toString();
    }
}
