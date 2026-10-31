package org.chimeramc.client.ui.navigation;

/**
 * Where the system bars overlap the app, and how much padding each navigation shape needs.
 *
 * <p>The launcher targets SDK 35, and from Android 15 an app at that target is drawn edge-to-edge
 * whether it asks to be or not: {@code statusBarColor} and {@code navigationBarColor} are ignored
 * and the window is laid out behind both bars. Nothing in the project consumed window insets, so the
 * navigation shape was free to be drawn under the status bar (a display cutout or a tall status bar
 * clips the top of the nav bar) and the content was free to be drawn under the gesture bar. That is
 * the "UI looks broken on Android 15" report.
 *
 * <p>The rule is small but easy to get wrong in a way that only shows on a device, so it is a pure
 * class with its own test rather than four lines inline:
 *
 * <ul>
 *   <li>A top bar adds the top inset as top padding — the bar is the top-most thing on screen.
 *   <li>A rail adds the top inset as top padding and the bottom inset as bottom padding, because a
 *       full-height rail spans the window and its last item would otherwise sit under the gesture
 *       bar.
 *   <li>The content view adds the insets the nav shape does not already absorb. A bar has taken the
 *       top inset, so the content adds only left/right/bottom; a rail has taken the top and bottom,
 *       so the content adds only left/right.
 * </ul>
 *
 * <p>Left/right are added by both because a landscape app is exactly the case where a cutout sits on
 * one side, and neither shape covers the horizontal edges.
 */
public final class EdgeToEdgeInsets {

    /** A window's inset values, in pixels. Mirrors {@code WindowInsetsCompat} without depending on it. */
    public static final class Insets {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Insets(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public static Insets none() {
            return new Insets(0, 0, 0, 0);
        }
    }

    /** The padding to apply to the navigation view itself. */
    public static final class Padding {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Padding(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    /**
     * The single reader of a window's insets (item 93).
     *
     * <p>Every screen used to build this object inline from two {@code WindowInsetsCompat} calls,
     * which put an untested {@code Math.max} at each call site. The union lives here instead, so the
     * rule that a cutout larger than the system bar wins on each edge is stated once and pinned by
     * {@link EdgeToEdgeInsetsTest}.
     */
    public static Insets from(int barLeft, int barTop, int barRight, int barBottom,
                              int cutoutLeft, int cutoutTop, int cutoutRight, int cutoutBottom) {
        return new Insets(
                Math.max(barLeft, cutoutLeft),
                Math.max(barTop, cutoutTop),
                Math.max(barRight, cutoutRight),
                Math.max(barBottom, cutoutBottom));
    }

    /** Padding for the navigation view, given its shape and the window insets. */
    public static Padding forNavBar(boolean rail, Insets insets) {
        if (insets == null) {
            return new Padding(0, 0, 0, 0);
        }
        if (rail) {
            // A rail spans the full window height, so it must clear both bars.
            return new Padding(insets.left, insets.top, insets.right, insets.bottom);
        }
        // A top bar is the top-most view: it clears the top and the horizontal edges. The bottom
        // inset belongs to the content, which is what actually reaches the bottom of the screen.
        return new Padding(insets.left, insets.top, insets.right, 0);
    }

    /** Padding for the content view: the insets the navigation shape has not already absorbed. */
    public static Padding forContent(boolean rail, Insets insets) {
        if (insets == null) {
            return new Padding(0, 0, 0, 0);
        }
        // Both shapes cover the top (a bar directly, a rail by padding itself), so the content never
        // needs the top inset; adding it again would double the gap under the bar.
        return new Padding(insets.left, 0, insets.right, rail ? 0 : insets.bottom);
    }

    private EdgeToEdgeInsets() {
    }
}
