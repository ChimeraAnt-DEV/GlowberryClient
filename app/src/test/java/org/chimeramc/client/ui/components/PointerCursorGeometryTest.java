package org.chimeramc.client.ui.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The controller cursor silhouette is a pointer, not a blob (Phase 12).
 *
 * <p>These pin the properties that make it read as a macOS-style arrow and be usable: the tip is
 * the hotspot, the outline is a single closed blade (not a ring or a diamond), the blade stays
 * slender, and the tail steps inward rather than doubling the width. The earlier shape failed the
 * last two — its tail was as wide as the blade, so at the drawn size it was a lozenge.
 */
public class PointerCursorGeometryTest {

    @Test
    public void theTipIsTheHotspot() {
        float[] outline = PointerCursorGeometry.unitOutline();
        assertEquals("the first point is the tip's x", 0f, outline[0], 1e-6f);
        assertEquals("the first point is the tip's y", 0f, outline[1], 1e-6f);
    }

    @Test
    public void theOutlineIsAPolygonNotAnOpenPath() {
        assertTrue("a cursor needs at least a blade and a tail",
                PointerCursorGeometry.POINT_COUNT >= 6);
        assertEquals("points are stored as x,y pairs",
                0, PointerCursorGeometry.unitOutline().length % 2);
    }

    @Test
    public void theBladeStaysSlender() {
        float[] outline = PointerCursorGeometry.unitOutline();
        float maxX = 0f;
        float maxY = 0f;
        for (int i = 0; i < outline.length; i += 2) {
            maxX = Math.max(maxX, outline[i]);
            maxY = Math.max(maxY, outline[i + 1]);
        }
        assertTrue("the arrow must be taller than it is wide", maxY > maxX);
        assertTrue("the width must stay a small fraction of the height, or it reads as a blob",
                maxX <= PointerCursorGeometry.MAX_WIDTH_FRACTION + 1e-6f);
    }

    @Test
    public void theLeftEdgeIsStraightDownFromTheTip() {
        // The classic pointer has a straight left edge; a curve there reads as a teardrop.
        float[] outline = PointerCursorGeometry.unitOutline();
        assertEquals("the second point keeps the tip's x", outline[0], outline[2], 1e-6f);
        assertTrue("and it drops well down the blade", outline[3] > 0.5f);
    }

    @Test
    public void theTailStepsInwardRatherThanWidening() {
        float[] outline = PointerCursorGeometry.unitOutline();
        // The point after the left edge steps in (a larger x) before the tail's outer edge.
        float afterLeftEdge = outline[4];
        assertTrue("the tail must step in from the left edge", afterLeftEdge > outline[2]);
    }

    @Test
    public void scalingPutsTheTipWhereAsked() {
        float[] scaled = PointerCursorGeometry.points(100f, 50f, 20f);
        assertEquals(100f, scaled[0], 1e-4f);
        assertEquals(50f, scaled[1], 1e-4f);
        // A scaled copy is a fresh array; mutating it must not change the unit outline.
        scaled[0] = -1f;
        assertEquals(0f, PointerCursorGeometry.unitOutline()[0], 1e-6f);
    }

    @Test
    public void widthTracksHeight() {
        assertEquals(PointerCursorGeometry.MAX_WIDTH_FRACTION * 20f,
                PointerCursorGeometry.widthFor(20f), 1e-6f);
    }
}
