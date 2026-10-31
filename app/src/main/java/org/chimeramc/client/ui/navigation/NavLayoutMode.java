package org.chimeramc.client.ui.navigation;

/**
 * How the launcher's top-level navigation is presented at a given window width (U5).
 *
 * <p>One bar cannot serve every width: a phone has a short, narrow edge to spare and a wide
 * tablet has room for a rail that names every destination. The rule is a pure function of the
 * window width in dp so it is unit-tested rather than discovered by resizing a device, and so the
 * wrapper orientation, the layout and the label visibility all read the same answer.
 *
 * <p>The thresholds are the Material breakpoints: below 600dp is a compact window, 600-839dp is a
 * medium window, and 840dp and up is an expanded window.
 */
public enum NavLayoutMode {
    /** A horizontal bar docked to the bottom of the screen. Compact windows (phones). */
    BOTTOM_NAV,
    /** A vertical icon-only rail on the leading edge. Medium windows (small tablets, foldables). */
    NAV_RAIL,
    /** A vertical rail that also shows each destination's label. Large windows. */
    EXPANDED_RAIL;

    public static final int MEDIUM_WIDTH_DP = 600;
    public static final int EXPANDED_WIDTH_DP = 840;

    /**
     * The mode for a window [widthDp] dp wide. A non-positive width (before layout, or a bad
     * reading) resolves to {@link #BOTTOM_NAV}, which is the layout that needs the least room.
     */
    public static NavLayoutMode forWidthDp(int widthDp) {
        if (widthDp >= EXPANDED_WIDTH_DP) return EXPANDED_RAIL;
        if (widthDp >= MEDIUM_WIDTH_DP) return NAV_RAIL;
        return BOTTOM_NAV;
    }

    /**
     * The mode for a window described by both its {@code smallestScreenWidthDp} and its current
     * width in dp.
     *
     * <p>Either reading alone is wrong for a window that is not the whole screen. The smallest
     * width is stable across rotation, but on a tablet it stays the tablet's width even while the
     * window is a narrow split-screen column, so the rail would be chosen for a column too narrow
     * to hold its labels. The current width tracks the window, but it flips on rotation, which
     * would swap the layout under the user mid-turn. Taking the **smaller** classification of the
     * two keeps the rail off a window that cannot hold it, while a full-width window still gets
     * the shape its size deserves.
     */
    public static NavLayoutMode forWindow(int smallestWidthDp, int windowWidthDp) {
        NavLayoutMode bySmallest = forWidthDp(smallestWidthDp);
        NavLayoutMode byWindow = forWidthDp(windowWidthDp);
        return bySmallest.ordinal() <= byWindow.ordinal() ? bySmallest : byWindow;
    }

    /** True for a vertical rail, whatever its width class. */
    public boolean isRail() {
        return this != BOTTOM_NAV;
    }

    /** True when the rail (or bar) shows a text label beside every icon. */
    public boolean showsAllLabels() {
        return this == EXPANDED_RAIL;
    }

    /**
     * The width at which a bottom bar can afford to show the wordmark beside its tabs (item 84).
     *
     * <p>Below this the bar is icon-only with a single label and there is no room for the brand as
     * well; above it the extra width is otherwise empty, so the wordmark is what tells a user
     * which app they are in. A rail shows the wordmark whenever it shows labels, which is a
     * different question and stays {@link #showsAllLabels()}.
     */
    public static final int WORDMARK_WIDTH_DP = 480;

    /**
     * Whether the wordmark is shown for this mode at {@code windowWidthDp} (item 84).
     *
     * <p>On a rail it follows the labels: an icon-only rail has no room for a wordmark either. On
     * the bar it is a width decision, because the bar is horizontal and gains room from width
     * rather than from a label column.
     */
    public boolean showsWordmark(int windowWidthDp) {
        if (isRail()) return showsAllLabels();
        return windowWidthDp >= WORDMARK_WIDTH_DP;
    }
}
