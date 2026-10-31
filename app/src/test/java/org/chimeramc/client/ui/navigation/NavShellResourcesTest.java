package org.chimeramc.client.ui.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * Regression guards for the navigation shell, read from the real resources and source.
 *
 * <p>Each of these pinned a real defect: a rail row whose {@code paddingStart} was the rail width
 * (so the icon and label were clipped off the right edge), a rail header that centred the back
 * button and logo as a pair (leaving the logo off the rail's axis), an icon-only rail width reused
 * at 840dp (where the labels cannot fit), and a compact-bar wordmark declared {@code visible} while
 * the code hides it. A headless build machine cannot see any of them, so they are asserted here.
 */
public class NavShellResourcesTest {

    @Test
    public void aRailRowInsetIsNeverTheRailWidth() throws IOException {
        String dimens = read("app/src/main/res/values/dimens.xml");
        int railWidth = dimen(dimens, "nav_rail_width");
        int tabStart = dimen(dimens, "nav_rail_tab_padding_start");
        assertTrue("a rail row is match_parent inside the rail, so a paddingStart of the rail width "
                        + "pushes the icon and label off the right edge and clips them. start="
                        + tabStart + " width=" + railWidth,
                tabStart < railWidth);

        String styles = read("app/src/main/res/values/styles.xml");
        Matcher m = Pattern.compile(
                "<style name=\"Widget\\.Chimera\\.NavRailTab\"[^>]*>(.*?)</style>", Pattern.DOTALL)
                .matcher(styles);
        assertTrue("NavRailTab style missing", m.find());
        String body = m.group(1);
        assertFalse("the rail tab's paddingStart must not reference the rail width",
                body.contains("@dimen/nav_rail_width"));
        assertTrue("the rail tab must use the rail inset dimen",
                body.contains("@dimen/nav_rail_tab_padding_start"));
    }

    @Test
    public void aRailRowIsATouchTarget() throws IOException {
        String dimens = read("app/src/main/res/values/dimens.xml");
        assertTrue("a rail destination row must be at least 48dp tall",
                dimen(dimens, "nav_rail_tab_height") >= 48);
    }

    @Test
    public void theExpandedRailIsWideEnoughForLabels() throws IOException {
        String base = read("app/src/main/res/values/dimens.xml");
        String expanded = read("app/src/main/res/values-sw840dp/dimens.xml");
        int baseWidth = dimen(base, "nav_rail_width");
        int expandedWidth = dimen(expanded, "nav_rail_width");
        assertTrue("at 840dp the rail names every destination, so the icon-only width cannot fit a "
                        + "label. base=" + baseWidth + " expanded=" + expandedWidth,
                expandedWidth > baseWidth);
        assertTrue("the expanded rail must be wide enough for the longest destination label",
                expandedWidth >= 184);
        // The drawable padding that separates icon from label also has to be able to grow.
        assertTrue("the expanded rail needs its own icon/label gap",
                expanded.contains("nav_rail_tab_drawable_padding"));
    }

    @Test
    public void theRailHeaderStacksInsteadOfCentringAPair() throws IOException {
        String rail = read("app/src/main/res/layout/nav_rail.xml");
        // The header row is the LinearLayout that directly contains nav_back_button.
        Matcher m = Pattern.compile(
                "<LinearLayout(?:(?!</LinearLayout>).)*?nav_back_button.*?</LinearLayout>",
                Pattern.DOTALL).matcher(rail);
        assertTrue("rail header with the back button missing", m.find());
        String header = m.group();
        assertTrue("the rail header must be vertical so each child is centred on the rail's axis, "
                + "not a horizontal pair that centres as a unit",
                header.contains("android:orientation=\"vertical\""));
    }

    @Test
    public void theCompactBarDeclaresTheWordmarkHidden() throws IOException {
        String bar = read("app/src/main/res/layout/nav_bar.xml");
        Matcher m = Pattern.compile(
                "<TextView[^>]*@\\+id/nav_app_name.*?/>", Pattern.DOTALL).matcher(bar);
        assertTrue("nav_app_name missing from nav_bar.xml", m.find());
        assertTrue("the compact bar hides the wordmark in code, so the XML must declare it gone "
                        + "rather than visible (otherwise the first frame flashes it)",
                m.group().contains("android:visibility=\"gone\""));
    }

    @Test
    public void theBackButtonIsATouchTarget() throws IOException {
        for (String layout : new String[]{"nav_bar.xml", "nav_rail.xml"}) {
            String xml = read("app/src/main/res/layout/" + layout);
            Matcher m = Pattern.compile(
                    "<ImageView[^>]*@\\+id/nav_back_button.*?/>", Pattern.DOTALL).matcher(xml);
            assertTrue("nav_back_button missing from " + layout, m.find());
            String view = m.group();
            Matcher w = Pattern.compile("android:layout_width=\"([0-9]+)dp\"").matcher(view);
            Matcher h = Pattern.compile("android:layout_height=\"([0-9]+)dp\"").matcher(view);
            assertTrue("back button has no explicit size in " + layout, w.find() && h.find());
            assertTrue("the back button must be a 48dp touch target in " + layout,
                    Integer.parseInt(w.group(1)) >= 48 && Integer.parseInt(h.group(1)) >= 48);
        }
    }

    @Test
    public void theSharedThemeDeclaresTheContainerColours() throws IOException {
        // values-v29/v31/v35 used to redeclare the whole theme and had dropped these two, so they
        // were dead on every shipped device. They now live in one shared parent.
        String theme = read("app/src/main/res/values/theme.xml");
        assertTrue("the shared theme must declare colorPrimaryContainer",
                theme.contains("name=\"colorPrimaryContainer\""));
        assertTrue("the shared theme must declare colorOnPrimaryContainer",
                theme.contains("name=\"colorOnPrimaryContainer\""));
    }

    @Test
    public void versionQualifiedThemesOnlyAddDeltas() throws IOException {
        // A version-qualified Base.Theme.FullScreen must extend the shared parent, not restate the
        // whole theme (which is how the copies drifted apart).
        for (String qualifier : new String[]{"values-v29", "values-v31", "values-v35"}) {
            String theme = read("app/src/main/res/" + qualifier + "/theme.xml");
            assertTrue(qualifier + " must extend Base.Theme.FullScreen.Common",
                    theme.contains("<style name=\"Base.Theme.FullScreen\" parent=\"Base.Theme.FullScreen.Common\""));
        }
    }

    @Test
    public void noPaddingIsEverSetFromAWidthDimen() throws IOException {
        // Item 82. The original rail bug was a tab whose paddingStart was @dimen/nav_rail_width:
        // a match_parent row then pushes its icon and label off the right edge, which no build
        // machine can see. The general shape of that mistake is a padding attribute fed from a
        // dimen whose name says "width", so that is what this forbids, in every layout and every
        // style - a padding is an inset inside the view, never the view's own size.
        Path layoutDir = resolve("app/src/main/res/layout");
        Path valuesDir = resolve("app/src/main/res/values");
        int scanned = 0;
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> a = Files.list(layoutDir);
             java.util.stream.Stream<Path> b = Files.list(valuesDir)) {
            a.filter(f -> f.toString().endsWith(".xml")).forEach(files::add);
            b.filter(f -> f.toString().endsWith(".xml")).forEach(files::add);
        }
        assertTrue("no resource files found to scan", files.size() > 50);
        Pattern paddingDimen = Pattern.compile(
                "android:padding[A-Za-z]*=\"@dimen/([a-zA-Z0-9_]+)\"");
        for (Path file : files) {
            String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Matcher m = paddingDimen.matcher(xml);
            while (m.find()) {
                scanned++;
                String dimenName = m.group(1);
                assertFalse(file.getFileName() + " sets a padding from @dimen/" + dimenName
                                + ", which names a size; a padding must be its own inset dimen",
                        dimenName.toLowerCase(java.util.Locale.ROOT).contains("width"));
            }
        }
        assertTrue("the scan found no dimen-backed padding at all, so it proved nothing",
                scanned > 0);
    }

    private static int dimen(String xml, String name) {
        Matcher m = Pattern.compile("<dimen name=\"" + Pattern.quote(name) + "\">([0-9]+)dp</dimen>")
                .matcher(xml);
        assertTrue("dimen " + name + " is missing", m.find());
        return Integer.parseInt(m.group(1));
    }

    /** Resolves a repo-relative directory, searching upward from the working directory. */
    private static Path resolve(String relativePath) throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isDirectory(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new IOException("could not locate " + relativePath);
    }

    private static String read(String relativePath) throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relativePath);
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IOException("could not locate " + relativePath + " from " + Paths.get("").toAbsolutePath());
    }
}
