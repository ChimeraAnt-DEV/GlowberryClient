package org.chimeramc.client.ui.components;

/**
 * The controller cursor's silhouette, as pure geometry (Phase 12).
 *
 * <p>The cursor is a macOS-style pointer arrow: a slender blade with the tip as the hotspot, a
 * straight left edge, and a stepped tail on the right. It replaces the earlier hand-rolled outline
 * whose proportions read as a squashed blob — the tail was as wide as the blade, so at 10dp the
 * shape was a lozenge rather than a pointer, and it was ambiguous which corner was the hotspot.
 *
 * <p>The class is Android-free on purpose: the outline is a coordinate list, so the shape can be
 * asserted (the tip is the hotspot, the blade is narrow, the tail steps inward) without a device.
 * {@link UiDraw#pointerCursor} turns the list into a {@code Path}.
 *
 * <p>Coordinates are unit fractions of the arrow's height. {@code (0, 0)} is the tip and the
 * hotspot: a click lands exactly where the point is, which is the property a cursor is for.
 */
public final class PointerCursorGeometry {

    /** Points in the outline, as {@code x0,y0,x1,y1,...} in unit fractions of the height. */
    private static final float[] UNIT = {
            0.000f, 0.000f,   // tip — the hotspot
            0.000f, 0.824f,   // straight left edge down
            0.235f, 0.647f,   // step in to the tail
            0.382f, 1.000f,   // tail bottom
            0.559f, 0.912f,   // tail top-right
            0.412f, 0.588f,   // back up the inner edge
            0.706f, 0.588f,   // the right step
    };

    /** Number of outline points; the array length is {@code 2 * POINT_COUNT}. */
    public static final int POINT_COUNT = UNIT.length / 2;

    /** The widest the blade gets, as a fraction of the height. A pointer is slender. */
    public static final float MAX_WIDTH_FRACTION = 0.72f;

    private PointerCursorGeometry() {
    }

    /**
     * The outline scaled to {@code height}, as {@code x0,y0,...} with the tip at {@code (x, y)}.
     *
     * <p>Returns a fresh array rather than a shared one: the caller stores it, and a shared array
     * would be mutated by the next draw.
     */
    public static float[] points(float x, float y, float height) {
        float[] out = new float[UNIT.length];
        for (int i = 0; i < UNIT.length; i += 2) {
            out[i] = x + UNIT[i] * height;
            out[i + 1] = y + UNIT[i + 1] * height;
        }
        return out;
    }

    /** The unscaled outline, for a caller that wants to build its own transform. */
    public static float[] unitOutline() {
        return UNIT.clone();
    }

    /** The arrow's width for a given height, in the same units. */
    public static float widthFor(float height) {
        return MAX_WIDTH_FRACTION * height;
    }
}
