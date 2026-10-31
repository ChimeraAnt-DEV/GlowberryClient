package org.chimeramc.client.ui.navigation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins how the system-bar insets are distributed between the navigation shape and the content.
 *
 * <p>The bug this prevents is a double inset (a gap under the bar) or a missing one (content under
 * the gesture bar), neither of which shows on a build machine. The rules are small enough to state
 * as exact numbers.
 */
public class EdgeToEdgeInsetsTest {

    /** A landscape phone with a status bar, a cutout on one side and a gesture bar. */
    private static final EdgeToEdgeInsets.Insets PHONE =
            new EdgeToEdgeInsets.Insets(60, 28, 12, 24);

    @Test
    public void aTopBarTakesTheTopInsetAndTheHorizontalEdges() {
        EdgeToEdgeInsets.Padding padding = EdgeToEdgeInsets.forNavBar(false, PHONE);
        assertEquals(60, padding.left);
        assertEquals(28, padding.top);
        assertEquals(12, padding.right);
        // The bottom belongs to the content: the bar is not the view that reaches the bottom.
        assertEquals(0, padding.bottom);
    }

    @Test
    public void aRailSpansTheWindowSoItTakesEveryInset() {
        EdgeToEdgeInsets.Padding padding = EdgeToEdgeInsets.forNavBar(true, PHONE);
        assertEquals(60, padding.left);
        assertEquals(28, padding.top);
        assertEquals(12, padding.right);
        // A full-height rail's last item would otherwise sit under the gesture bar.
        assertEquals(24, padding.bottom);
    }

    @Test
    public void theContentNeverRepeatsTheTopInset() {
        // Both shapes already cover the top; adding it again would double the gap under the bar.
        assertEquals(0, EdgeToEdgeInsets.forContent(false, PHONE).top);
        assertEquals(0, EdgeToEdgeInsets.forContent(true, PHONE).top);
    }

    @Test
    public void theContentTakesTheBottomInsetOnlyWhenTheBarDoesNot() {
        assertEquals(24, EdgeToEdgeInsets.forContent(false, PHONE).bottom);
        // The rail already padded its own bottom, so the content must not add it again.
        assertEquals(0, EdgeToEdgeInsets.forContent(true, PHONE).bottom);
    }

    @Test
    public void theContentAlwaysTakesTheHorizontalInsets() {
        // A landscape cutout sits on one side and neither shape covers the horizontal edges.
        assertEquals(60, EdgeToEdgeInsets.forContent(false, PHONE).left);
        assertEquals(12, EdgeToEdgeInsets.forContent(false, PHONE).right);
        assertEquals(60, EdgeToEdgeInsets.forContent(true, PHONE).left);
        assertEquals(12, EdgeToEdgeInsets.forContent(true, PHONE).right);
    }

    @Test
    public void eachVerticalInsetIsTakenByExactlyOneView() {
        // The nav shape and the content are stacked, not nested, so a vertical inset applied to both
        // would leave a doubled gap. Exactly one of them takes the top, and exactly one the bottom.
        for (boolean rail : new boolean[] {false, true}) {
            EdgeToEdgeInsets.Padding nav = EdgeToEdgeInsets.forNavBar(rail, PHONE);
            EdgeToEdgeInsets.Padding content = EdgeToEdgeInsets.forContent(rail, PHONE);
            assertEquals("top", PHONE.top, nav.top + content.top);
            assertEquals("bottom", PHONE.bottom, nav.bottom + content.bottom);
        }
    }

    @Test
    public void theHorizontalInsetsReachBothViews() {
        // These are side-by-side-safe: a landscape cutout can sit over the nav bar's own row or over
        // the content, and neither view covers the other horizontally, so both need the inset.
        for (boolean rail : new boolean[] {false, true}) {
            EdgeToEdgeInsets.Padding nav = EdgeToEdgeInsets.forNavBar(rail, PHONE);
            EdgeToEdgeInsets.Padding content = EdgeToEdgeInsets.forContent(rail, PHONE);
            assertEquals("nav left", PHONE.left, nav.left);
            assertEquals("nav right", PHONE.right, nav.right);
            assertEquals("content left", PHONE.left, content.left);
            assertEquals("content right", PHONE.right, content.right);
        }
    }

    @Test
    public void aWindowWithNoBarsAddsNoPadding() {
        for (boolean rail : new boolean[] {false, true}) {
            EdgeToEdgeInsets.Padding nav =
                    EdgeToEdgeInsets.forNavBar(rail, EdgeToEdgeInsets.Insets.none());
            EdgeToEdgeInsets.Padding content =
                    EdgeToEdgeInsets.forContent(rail, EdgeToEdgeInsets.Insets.none());
            assertEquals(0, nav.left + nav.top + nav.right + nav.bottom);
            assertEquals(0, content.left + content.top + content.right + content.bottom);
        }
    }

    @Test
    public void aCutoutLargerThanTheSystemBarWinsOnEveryEdge() {
        // Item 93: the union used to be an inline Math.max at the call site, untested. A landscape
        // notch reports a cutout on one side that the system bars do not describe, and a tall
        // status bar reports a top inset the cutout does not; whichever is larger on each edge is
        // what actually has to be cleared.
        EdgeToEdgeInsets.Insets insets =
                EdgeToEdgeInsets.from(0, 24, 0, 0, 96, 0, 0, 48);
        assertEquals(96, insets.left);
        assertEquals(24, insets.top);
        assertEquals(0, insets.right);
        assertEquals(48, insets.bottom);
    }

    @Test
    public void anEmptyCutoutLeavesTheSystemBarsAlone() {
        EdgeToEdgeInsets.Insets insets =
                EdgeToEdgeInsets.from(8, 24, 8, 48, 0, 0, 0, 0);
        assertEquals(8, insets.left);
        assertEquals(24, insets.top);
        assertEquals(8, insets.right);
        assertEquals(48, insets.bottom);
    }

    @Test
    public void aNullInsetSetIsTreatedAsNoBarsRatherThanCrashing() {
        // requestApplyInsets can deliver a null on some OEM builds; a launcher must not die there.
        for (boolean rail : new boolean[] {false, true}) {
            assertEquals(0, EdgeToEdgeInsets.forNavBar(rail, null).top);
            assertEquals(0, EdgeToEdgeInsets.forContent(rail, null).top);
        }
    }
}
