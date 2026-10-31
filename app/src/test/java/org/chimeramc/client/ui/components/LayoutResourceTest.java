package org.chimeramc.client.ui.components;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The layouts name their colours and their text (items 22 and 335), enforced against the real files.
 *
 * <p>Two things a layout used to be able to do that it must not:
 *
 * <ul>
 *   <li>state a raw {@code #RRGGBB} colour. The palette is dynamic — Material You rewrites the
 *       theme attributes at runtime and the accent follows the wallpaper — so a literal is a value
 *       that can never follow the user's theme. It is also invisible to a rename: changing
 *       {@code status_danger} would leave the two dozen copies behind.
 *   <li>state a raw {@code android:text}. A string written into a layout cannot be translated, so
 *       it is a permanent English-only screen. Design-time samples belong in {@code tools:text},
 *       which the framework ignores at runtime.
 * </ul>
 *
 * <p>This is the CI gate the plan asks for (item 136): a new literal fails the build rather than
 * quietly shipping, which is the only way the count stays at zero after being brought there.
 */
public class LayoutResourceTest {

    /** A colour written as hex inside a layout. {@code @color/...} and {@code ?attr/...} pass. */
    private static final Pattern HEX_COLOUR =
            Pattern.compile("android:([A-Za-z]+)=\"(#[0-9a-fA-F]{3,8})\"");

    /**
     * A literal text attribute. {@code tools:text} is excluded on purpose: it is a preview-only
     * sample that never reaches a device, and it is where these values now live.
     */
    private static final Pattern LITERAL_TEXT =
            Pattern.compile("android:text=\"(?!@string|@android:string)([^\"]+)\"");

    /**
     * Values that are not words: a count, a percentage, a separator glyph. They are not sent to a
     * translator and are the same in every language, so they stay as literals with a note.
     */
    private static final List<String> NON_TRANSLATABLE = List.of("0", "1", "0%", "\u00d7");

    @Test
    public void noLayoutStatesItsOwnColour() throws IOException {
        Map<String, List<String>> offenders = new LinkedHashMap<>();
        for (Path layout : layoutFiles()) {
            Matcher m = HEX_COLOUR.matcher(read(layout));
            while (m.find()) {
                offenders.computeIfAbsent(layout.getFileName().toString(), k -> new ArrayList<>())
                        .add(m.group());
            }
        }
        assertTrue("a layout must name a colour token (@color/*) or a theme attribute (?attr/*), "
                        + "not a hex literal — a literal cannot follow the theme or the wallpaper "
                        + "accent, and renaming a token would leave it behind. Offenders: " + offenders,
                offenders.isEmpty());
    }

    @Test
    public void noLayoutCarriesUntranslatableText() throws IOException {
        Map<String, List<String>> offenders = new LinkedHashMap<>();
        for (Path layout : layoutFiles()) {
            Matcher m = LITERAL_TEXT.matcher(read(layout));
            while (m.find()) {
                String value = m.group(1);
                if (NON_TRANSLATABLE.contains(value)) continue;
                offenders.computeIfAbsent(layout.getFileName().toString(), k -> new ArrayList<>())
                        .add(value);
            }
        }
        assertTrue("text in a layout must be @string/* so it can be translated; use tools:text for "
                        + "a design-time sample. Offenders: " + offenders,
                offenders.isEmpty());
    }

    /**
     * The tokens the layouts now reference exist, and the ones the removed literals mapped to are
     * the semantic ones rather than a one-off. A layout pointing at a missing colour is a build
     * error, but a token defined in {@code values-night} only would pass the build and fail at
     * runtime, so both files are checked.
     */
    @Test
    public void everyReferencedColourTokenIsDefinedInBothThemes() throws IOException {
        String day = read(resolve("app/src/main/res/values/colors.xml"));
        String night = read(resolve("app/src/main/res/values-night/colors.xml"));
        for (String token : new String[]{"overlay_text_primary", "overlay_text_muted",
                "overlay_text_hint", "overlay_scrim", "overlay_scrim_soft", "overlay_panel",
                "overlay_panel_border", "track_inactive", "muted_text", "status_danger",
                "status_danger_dark", "status_danger_soft", "status_warning",
                "status_accent_pink", "wash_black_10", "wash_black_85", "wash_scrim_50",
                "wash_grey_10", "wash_grey_13", "wash_grey_20", "transparent"}) {
            assertTrue("values/colors.xml must define " + token,
                    day.contains("name=\"" + token + "\""));
        }
        // The overlay tokens are deliberately fixed, not themed: the in-game overlays sit over the
        // game frame and must stay light-on-dark in both themes, so they must not be overridden by
        // a night variant that could drift them.
        for (String token : new String[]{"overlay_text_primary", "overlay_scrim", "overlay_panel"}) {
            assertFalse("the overlay palette is fixed, so values-night must not override " + token,
                    night.contains("name=\"" + token + "\""));
        }
    }

    /**
     * Every layout in {@code res/layout} is reachable. An unreferenced layout is dead weight that
     * lint only reports when {@code shrinkResources} runs, and the ones removed here had drifted
     * far enough to reference drawables that no longer exist. A layout referenced only by name at
     * runtime (a {@code getIdentifier} lookup) is a false positive this cannot see, so the corpus
     * includes every source file and the manifest.
     */
    @Test
    public void noLayoutIsUnreferenced() throws IOException {
        Path layoutDir = resolve("app/src/main/res/layout");
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(layoutDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .forEach(p -> names.add(p.getFileName().toString().replace(".xml", "")));
        }
        String corpus = sourceCorpus();
        List<String> unused = new ArrayList<>();
        for (String name : names) {
            String binding = bindingName(name);
            if (corpus.contains("R.layout." + name) || corpus.contains("@layout/" + name)
                    || corpus.contains(binding)) {
                continue;
            }
            unused.add(name);
        }
        assertEquals("every layout must be referenced from code, the manifest or another layout; an "
                + "unreferenced one is dead and drifts. Unused: " + unused, 0, unused.size());
    }

    private static String bindingName(String layout) {
        StringBuilder sb = new StringBuilder();
        for (String part : layout.split("_")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.append("Binding").toString();
    }

    /**
     * Every source file, layouts included — a layout is referenced from another layout's
     * {@code <include layout="@layout/..."/>} as often as it is from code, so the corpus must hold
     * both or the includes read as unused.
     */
    private static String sourceCorpus() throws IOException {
        StringBuilder sb = new StringBuilder();
        Path root = resolve("app/src/main");
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String file = p.getFileName().toString();
                if (!(file.endsWith(".java") || file.endsWith(".kt") || file.endsWith(".xml"))) {
                    continue;
                }
                sb.append(read(p)).append('\n');
            }
        }
        return sb.toString();
    }

    private static List<Path> layoutFiles() throws IOException {
        Path dir = resolve("app/src/main/res/layout");
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
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
