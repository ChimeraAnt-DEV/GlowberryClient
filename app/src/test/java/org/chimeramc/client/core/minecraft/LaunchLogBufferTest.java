package org.chimeramc.client.core.minecraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The launch log's ceiling and its whole-window render. */
public class LaunchLogBufferTest {

    @Test
    public void theBufferNeverExceedsItsCap() {
        LaunchLogBuffer buffer = new LaunchLogBuffer(3);
        buffer.add("a");
        buffer.add("b");
        buffer.add("c");
        buffer.add("d");
        assertEquals(3, buffer.size());
        // The oldest line is the one dropped, so the newest are the ones kept.
        assertEquals("b\nc\nd", buffer.render());
    }

    @Test
    public void theDefaultCapIsTheDocumentedCeiling() {
        LaunchLogBuffer buffer = new LaunchLogBuffer();
        for (int i = 0; i < LaunchLogBuffer.MAX_LINES + 25; i++) {
            buffer.add("line " + i);
        }
        assertEquals(LaunchLogBuffer.MAX_LINES, buffer.size());
        assertTrue(buffer.render().endsWith("line " + (LaunchLogBuffer.MAX_LINES + 24)));
        assertFalse(buffer.render().contains("line 0\n"));
    }

    @Test
    public void renderIsTheWholeWindowNotADelta() {
        LaunchLogBuffer buffer = new LaunchLogBuffer(10);
        buffer.add("first");
        assertEquals("first", buffer.render());
        // A second render with nothing added must return the same window, not an empty string: the
        // delta behaviour is what let the view and the buffer disagree about the line count.
        assertEquals("first", buffer.render());
        buffer.add("second");
        assertEquals("first\nsecond", buffer.render());
    }

    @Test
    public void nullLinesAreIgnored() {
        LaunchLogBuffer buffer = new LaunchLogBuffer(4);
        buffer.add(null);
        buffer.add("only");
        assertEquals(1, buffer.size());
        assertEquals("only", buffer.render());
    }

    @Test
    public void duplicateDetectionComparesAgainstTheLastRetainedLine() {
        LaunchLogBuffer buffer = new LaunchLogBuffer(4);
        assertFalse(buffer.isDuplicateOfLast("x"));
        buffer.add("x");
        assertTrue(buffer.isDuplicateOfLast("x"));
        assertFalse(buffer.isDuplicateOfLast("y"));
    }

    @Test
    public void aCapBelowOneIsClampedSoTheBufferStillWorks() {
        LaunchLogBuffer buffer = new LaunchLogBuffer(0);
        buffer.add("a");
        buffer.add("b");
        assertEquals(1, buffer.size());
        assertEquals("b", buffer.render());
    }

    @Test
    public void clearEmptiesTheWindow() {
        LaunchLogBuffer buffer = new LaunchLogBuffer(4);
        buffer.add("a");
        buffer.clear();
        assertEquals(0, buffer.size());
        assertEquals("", buffer.render());
    }
}
