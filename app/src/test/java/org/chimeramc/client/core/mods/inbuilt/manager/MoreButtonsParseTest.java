package org.chimeramc.client.core.mods.inbuilt.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Pins the stored-button parser's resilience.
 *
 * <p>The failure this guards is the one that loses a player's configuration: one malformed record
 * in the stored list. The parser must drop that record and keep every other button, not empty the
 * list. The file reader is injected so no Android context is needed.
 */
public class MoreButtonsParseTest {

    private static MoreButtonsManager.SvgFileReader icons(Map<String, String> files) {
        return fileName -> files.getOrDefault(fileName, "");
    }

    private static String button(String id, String extra) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"key_code\":87,"
                + "\"key_format\":\"glfw\"" + extra + "}";
    }

    @Test
    public void everyWellFormedEntryIsKept() {
        String raw = "[" + button("a", "") + "," + button("b", "") + "]";
        MoreButtonsManager.ParsedButtons parsed =
                MoreButtonsManager.parseButtons(raw, icons(new HashMap<>()));
        assertEquals(2, parsed.buttons.size());
        assertEquals("a", parsed.buttons.get(0).id);
        assertEquals("b", parsed.buttons.get(1).id);
        assertFalse("a glfw-formatted list needs no migration", parsed.needsMigration);
    }

    @Test
    public void oneCorruptEntryDoesNotLoseTheRest() {
        // The middle entry is not an object, and the last one carries a non-numeric key code, which
        // MoreButtonConfig must tolerate. Neither may take the well-formed entries with it.
        String raw = "[" + button("keep1", "")
                + ",42,"
                + button("keep2", "")
                + "]";
        MoreButtonsManager.ParsedButtons parsed =
                MoreButtonsManager.parseButtons(raw, icons(new HashMap<>()));
        assertEquals("both well-formed buttons survive a corrupt neighbour", 2,
                parsed.buttons.size());
        assertEquals("keep1", parsed.buttons.get(0).id);
        assertEquals("keep2", parsed.buttons.get(1).id);
    }

    @Test
    public void anUnparseableListReadsAsEmptyRatherThanThrowing() {
        MoreButtonsManager.ParsedButtons parsed =
                MoreButtonsManager.parseButtons("not json at all", icons(new HashMap<>()));
        assertTrue(parsed.buttons.isEmpty());
    }

    @Test
    public void aNonGlfwEntryIsFlaggedForMigration() {
        String raw = "[{\"id\":\"old\",\"key_code\":29,\"key_format\":\"android\"}]";
        MoreButtonsManager.ParsedButtons parsed =
                MoreButtonsManager.parseButtons(raw, icons(new HashMap<>()));
        assertEquals(1, parsed.buttons.size());
        assertTrue("a legacy android key format is migrated", parsed.needsMigration);
    }

    @Test
    public void anEntryStoredWithAnInlineSvgIsFlaggedForMigration() {
        String raw = "[{\"id\":\"inline\",\"key_code\":87,\"key_format\":\"glfw\","
                + "\"normal_svg\":\"<svg/>\"}]";
        MoreButtonsManager.ParsedButtons parsed =
                MoreButtonsManager.parseButtons(raw, icons(new HashMap<>()));
        assertEquals(1, parsed.buttons.size());
        assertTrue("an inline SVG is moved out to a file", parsed.needsMigration);
    }

    @Test
    public void aStoredSvgFileIsReadBackIntoTheEntry() {
        Map<String, String> files = new HashMap<>();
        files.put("a_normal.svg", "<svg id=\"n\"/>");
        String raw = "[{\"id\":\"a\",\"key_code\":87,\"key_format\":\"glfw\","
                + "\"normal_svg_file\":\"a_normal.svg\"}]";
        MoreButtonsManager.ParsedButtons parsed =
                MoreButtonsManager.parseButtons(raw, icons(files));
        assertEquals(1, parsed.buttons.size());
        assertEquals("<svg id=\"n\"/>", parsed.buttons.get(0).normalSvg);
    }
}
