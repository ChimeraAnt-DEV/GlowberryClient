package org.chimeramc.client.ui.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The adaptive-navigation rule (U5). A phone gets a bottom bar, a medium window an icon rail and a
 * large window an expanded rail, and the boundaries are exactly the Material breakpoints.
 */
public class NavLayoutModeTest {

    @Test
    public void aPhoneGetsABottomBar() {
        assertEquals(NavLayoutMode.BOTTOM_NAV, NavLayoutMode.forWidthDp(360));
        assertEquals(NavLayoutMode.BOTTOM_NAV, NavLayoutMode.forWidthDp(599));
    }

    @Test
    public void aMediumWindowGetsAnIconRail() {
        assertEquals(NavLayoutMode.NAV_RAIL, NavLayoutMode.forWidthDp(600));
        assertEquals(NavLayoutMode.NAV_RAIL, NavLayoutMode.forWidthDp(839));
    }

    @Test
    public void aLargeWindowGetsAnExpandedRail() {
        assertEquals(NavLayoutMode.EXPANDED_RAIL, NavLayoutMode.forWidthDp(840));
        assertEquals(NavLayoutMode.EXPANDED_RAIL, NavLayoutMode.forWidthDp(1280));
    }

    @Test
    public void aBadWidthFallsBackToTheSmallestLayout() {
        // Before layout the width can read as zero; the bottom bar is the layout that needs the
        // least room, so it is the safe default rather than a rail that would be clipped.
        assertEquals(NavLayoutMode.BOTTOM_NAV, NavLayoutMode.forWidthDp(0));
        assertEquals(NavLayoutMode.BOTTOM_NAV, NavLayoutMode.forWidthDp(-1));
    }

    @Test
    public void theWindowWidthCanOnlyMakeTheLayoutSmaller() {
        // A tablet at its full width: both readings agree, so the expanded rail is used.
        assertEquals(NavLayoutMode.EXPANDED_RAIL, NavLayoutMode.forWindow(900, 900));
        // The same tablet in a 500dp split-screen column: the smallest width still says tablet,
        // but the column cannot hold the rail, so the bottom bar wins.
        assertEquals(NavLayoutMode.BOTTOM_NAV, NavLayoutMode.forWindow(900, 500));
        // A 700dp column on a 900dp tablet gets the icon rail, the widest shape that fits.
        assertEquals(NavLayoutMode.NAV_RAIL, NavLayoutMode.forWindow(900, 700));
    }

    @Test
    public void aNarrowWindowNeverUpgradesTheLayout() {
        // A phone window wider than its smallest width (a landscape phone) must not get a rail it
        // would only keep while the rotation lasts.
        assertEquals(NavLayoutMode.BOTTOM_NAV, NavLayoutMode.forWindow(360, 800));
        assertEquals(NavLayoutMode.NAV_RAIL, NavLayoutMode.forWindow(600, 900));
    }

    @Test
    public void aMissingReadingFallsBackToTheOther() {
        // Before layout one of the two can read as zero; the other still decides, and a zero on
        // either side can only pull the layout down, never up.
        assertEquals(NavLayoutMode.BOTTOM_NAV, NavLayoutMode.forWindow(0, 900));
        assertEquals(NavLayoutMode.BOTTOM_NAV, NavLayoutMode.forWindow(900, 0));
        assertEquals(NavLayoutMode.BOTTOM_NAV, NavLayoutMode.forWindow(0, 0));
    }

    @Test
    public void theBoundariesAreExactOnBothReadings() {
        assertEquals(NavLayoutMode.BOTTOM_NAV, NavLayoutMode.forWindow(599, 599));
        assertEquals(NavLayoutMode.NAV_RAIL, NavLayoutMode.forWindow(600, 600));
        assertEquals(NavLayoutMode.NAV_RAIL, NavLayoutMode.forWindow(839, 839));
        assertEquals(NavLayoutMode.EXPANDED_RAIL, NavLayoutMode.forWindow(840, 840));
    }

    @Test
    public void onlyTheBottomBarIsNotARail() {
        assertFalse(NavLayoutMode.BOTTOM_NAV.isRail());
        assertTrue(NavLayoutMode.NAV_RAIL.isRail());
        assertTrue(NavLayoutMode.EXPANDED_RAIL.isRail());
    }

    @Test
    public void onlyTheExpandedRailShowsEveryLabel() {
        assertFalse(NavLayoutMode.BOTTOM_NAV.showsAllLabels());
        assertFalse(NavLayoutMode.NAV_RAIL.showsAllLabels());
        assertTrue(NavLayoutMode.EXPANDED_RAIL.showsAllLabels());
    }

    @Test
    public void forWindowIsTheSmallerClassificationAtEveryBoundary() {
        // 599/600 and 839/840 are the only two places the answer changes, so both readings are
        // swept either side of each (item 96).
        int[][] cases = {
                {599, 599, NavLayoutMode.BOTTOM_NAV.ordinal()},
                {599, 600, NavLayoutMode.BOTTOM_NAV.ordinal()},
                {600, 599, NavLayoutMode.BOTTOM_NAV.ordinal()},
                {600, 600, NavLayoutMode.NAV_RAIL.ordinal()},
                {839, 840, NavLayoutMode.NAV_RAIL.ordinal()},
                {840, 839, NavLayoutMode.NAV_RAIL.ordinal()},
                {840, 840, NavLayoutMode.EXPANDED_RAIL.ordinal()},
        };
        for (int[] c : cases) {
            assertEquals("smallest=" + c[0] + " window=" + c[1],
                    NavLayoutMode.values()[c[2]], NavLayoutMode.forWindow(c[0], c[1]));
        }
    }
}
