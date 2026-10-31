package org.chimeramc.client.ui.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The radius scale reaches the surfaces (U10).
 *
 * <p>{@code UiTokensTest} already pins the token values. What it could not see was whether anything
 * used them: the scale was declared and the layouts and drawables carried their own numbers, so a
 * card's corner depended on which file drew it. This test reads the real layout and drawable XML
 * and fails when a card surface states its own radius instead of naming a role.
 *
 * <p>Pills, tags, badges and the decorative accent bars are deliberately exempt. A pill's radius is
 * its height, a 4dp tag is not a card, and a full-tree rewrite would flatten the chip at 20dp and
 * the accent bar at 1.5dp into the card scale — which is the opposite of what the scale is for.
 */
public class RadiusScaleTest {

    /** A radius at or above this is a stadium (pill), and is not a card corner. */
    @Test
    public void noLayoutStatesItsOwnCardRadius() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path layout : files("app/src/main/res/layout")) {
            String xml = read(layout);
            Matcher m = Pattern.compile("app:cardCornerRadius=\"([0-9.]+)dp\"").matcher(xml);
            while (m.find()) {
                offenders.add(layout.getFileName() + ": " + m.group());
            }
        }
        assertTrue("a card must name a radius role (@dimen/radius_*), not a number, so a card in "
                + "XML and one in code round the same corner. Offenders: " + offenders,
                offenders.isEmpty());
    }

    /**
     * The card-bearing drawables must name a role. A drawable whose radius is a pill height or a
     * tag size is exempt, so this checks the surfaces the token set actually describes.
     */
    @Test
    public void theSharedSurfaceDrawablesNameARadiusRole() throws IOException {
        // Only surfaces that exist and are referenced. The list used to name glass_card_accent,
        // glass_card_elevated and bg_dialog_config, which had no consumer anywhere — a pin on a
        // dead drawable is a pin that keeps dead code alive, so they were removed with it.
        String[] surfaces = {
                "glass_card",
                "bg_instance_card", "bg_modal_card",
                "bg_vip_surface", "bg_launch_dock", "bg_search_field", "bg_edit_text",
                "bg_popup_menu_rounded", "bg_launch_quickdock",
        };
        List<String> offenders = new ArrayList<>();
        for (String name : surfaces) {
            Path p = resolve("app/src/main/res/drawable/" + name + ".xml");
            String xml = read(p);
            if (!xml.contains("@dimen/radius_")) {
                offenders.add(name);
            }
        }
        assertTrue("these card/field surfaces must read their radius from the scale: " + offenders,
                offenders.isEmpty());
    }

    /** Anything above the pill threshold in a shared surface drawable is a pill, not a card. */
    @Test
    public void noSurfaceDrawableUsesAnOffScaleCardRadius() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : files("app/src/main/res/drawable")) {
            String xml = read(p);
            Matcher m = Pattern.compile("android:radius=\"([0-9.]+)dp\"").matcher(xml);
            while (m.find()) {
                offenders.add(p.getFileName() + ": " + m.group(1) + "dp");
            }
        }
        assertTrue("a drawable must name a radius role (@dimen/radius_*), not a number. Pills are "
                + "radius_toggle; tags, badges and small chips use the named small-shape tokens. "
                + "Offenders: " + offenders, offenders.isEmpty());
    }

    /** The three named small-shape tokens are the only exceptions to the 8/12/16/28 surface scale. */
    @Test
    public void theSmallShapeFamilyIsNamedNotNumeric() throws IOException {
        String dimens = read(resolve("app/src/main/res/values/dimens.xml"));
        assertEquals("4dp", dimenAlias(dimens, "radius_tag"));
        assertEquals("6dp", dimenAlias(dimens, "radius_badge"));
        assertEquals("10dp", dimenAlias(dimens, "radius_chip_small"));
    }

    @Test
    public void theDimensRolesMatchTheTokens() throws IOException {
        String dimens = read(resolve("app/src/main/res/values/dimens.xml"));
        assertEquals(String.valueOf(UiTokens.RADIUS_SMALL), dimen(dimens, "radius_small"));
        assertEquals(String.valueOf(UiTokens.RADIUS_MEDIUM), dimen(dimens, "radius_medium"));
        assertEquals(String.valueOf(UiTokens.RADIUS_LARGE), dimen(dimens, "radius_large"));
        assertEquals(String.valueOf(UiTokens.RADIUS_XLARGE), dimen(dimens, "radius_xlarge"));

        // The semantic roles alias the scale rather than restating a value, so one change moves all.
        assertEquals("@dimen/radius_large", dimenAlias(dimens, "radius_card"));
        assertEquals("@dimen/radius_xlarge", dimenAlias(dimens, "radius_window"));
        assertEquals("@dimen/radius_medium", dimenAlias(dimens, "radius_chip"));
    }

    private static String dimen(String xml, String name) {
        Matcher m = Pattern.compile("<dimen name=\"" + Pattern.quote(name) + "\">([0-9]+)dp</dimen>")
                .matcher(xml);
        assertTrue("dimen " + name + " is missing", m.find());
        return m.group(1);
    }

    private static String dimenAlias(String xml, String name) {
        Matcher m = Pattern.compile("<dimen name=\"" + Pattern.quote(name) + "\">([^<]+)</dimen>")
                .matcher(xml);
        assertTrue("dimen " + name + " is missing", m.find());
        return m.group(1).trim();
    }

    private static List<Path> files(String relativeDir) throws IOException {
        Path dir = resolve(relativeDir);
        List<Path> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".xml")).sorted().forEach(out::add);
        }
        return out;
    }

    private static Path resolve(String relativePath) throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relativePath);
            if (Files.exists(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not find " + relativePath);
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
