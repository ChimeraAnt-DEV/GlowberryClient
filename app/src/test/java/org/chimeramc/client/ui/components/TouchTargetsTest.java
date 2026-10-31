package org.chimeramc.client.ui.components;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The touch-target arithmetic (items 91, 339).
 *
 * <p>These are the cases that made a {@code TouchDelegate} fail in practice: a target that hangs
 * off the parent's edge (a close button in the corner) and one that is already big enough. Both
 * look correct in a screenshot and behave wrongly on a device, which is why the rule is a class
 * with a test rather than three lines inlined at each call site.
 */
public class TouchTargetsTest {

    @Test
    public void aSmallViewGrowsToTheMinimumSymmetrically() {
        // 27x27 at (80, 40) in a 200x100 parent. The shortfall is 21px, so 10px goes to each side
        // and the odd pixel goes to the far edge - a target must not lose a pixel to rounding.
        int[] b = TouchTargets.delegateBounds(80, 40, 107, 67, 200, 100, 48);
        assertArrayEquals(new int[]{70, 30, 118, 78}, b);
        assertEquals("target width", 48, b[2] - b[0]);
        assertEquals("target height", 48, b[3] - b[1]);
        // An odd shortfall cannot be split evenly, so the centre moves by half a pixel. That is
        // unavoidable in integer coordinates and is why the assertion allows one pixel rather than
        // demanding an exact centre.
        assertTrue("the view's centre stays within a pixel",
                Math.abs((80 + 107) - (b[0] + b[2])) <= 1);
        assertTrue("the view's vertical centre stays within a pixel",
                Math.abs((40 + 67) - (b[1] + b[3])) <= 1);
    }

    @Test
    public void anOddShortfallGivesTheExtraPixelToTheFarEdge() {
        // 47 wide needs 1 more pixel; it cannot be split, so it must not be lost.
        int[] b = TouchTargets.delegateBounds(10, 10, 57, 57, 200, 200, 48);
        assertEquals(48, b[2] - b[0]);
        assertEquals(48, b[3] - b[1]);
    }

    @Test
    public void aViewAlreadyAtTheMinimumIsUnchanged() {
        int[] b = TouchTargets.delegateBounds(10, 10, 58, 58, 200, 200, 48);
        assertArrayEquals(new int[]{10, 10, 58, 58}, b);
        assertTrue(TouchTargets.meetsMinimum(48, 48, 48));
    }

    @Test
    public void aLargerViewIsNotShrunk() {
        int[] b = TouchTargets.delegateBounds(10, 10, 90, 90, 200, 200, 48);
        assertArrayEquals(new int[]{10, 10, 90, 90}, b);
    }

    @Test
    public void aTargetFlushAgainstTheParentEdgeStaysInsideIt() {
        // A 24dp button at the very start of a 100dp row: growing it left would leave the parent.
        int[] b = TouchTargets.delegateBounds(0, 30, 24, 54, 100, 100, 48);
        assertTrue("must not claim space outside the parent", b[0] >= 0);
        assertTrue(b[2] <= 100);
        assertEquals("the lost half is given back on the other side, not dropped",
                48, b[2] - b[0]);
    }

    @Test
    public void aTargetFlushAgainstTheFarEdgeStaysInsideIt() {
        int[] b = TouchTargets.delegateBounds(76, 30, 100, 54, 100, 100, 48);
        assertTrue(b[0] >= 0);
        assertTrue("must not claim space outside the parent", b[2] <= 100);
        assertEquals(48, b[2] - b[0]);
    }

    @Test
    public void aParentSmallerThanTheMinimumClampsRatherThanOverflowing() {
        int[] b = TouchTargets.delegateBounds(2, 2, 14, 14, 20, 20, 48);
        assertArrayEquals("the target cannot exceed its parent", new int[]{0, 0, 20, 20}, b);
    }

    @Test
    public void anUnknownParentSizeSkipsTheClamp() {
        int[] b = TouchTargets.delegateBounds(0, 0, 24, 24, 0, 0, 48);
        assertEquals(48, b[2] - b[0]);
        assertEquals(48, b[3] - b[1]);
    }

    @Test
    public void theMinimumIsMaterialsFortyEight() {
        assertEquals(48, TouchTargets.MIN_TARGET_DP);
        assertFalse(TouchTargets.meetsMinimum(47, 48, TouchTargets.MIN_TARGET_DP));
        assertFalse(TouchTargets.meetsMinimum(48, 47, TouchTargets.MIN_TARGET_DP));
    }
}
