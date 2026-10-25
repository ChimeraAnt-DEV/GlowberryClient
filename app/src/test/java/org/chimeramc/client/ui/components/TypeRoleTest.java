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

/**
 * The type scale (U8), enforced against the real resource files.
 *
 * <p>The roles exist twice by necessity — once as {@code UiType} for code that builds a view, once
 * as {@code TextAppearance.Glowberry.*} for a layout that declares one — and a scale that lives in
 * two places is a scale that drifts. This test reads the layout and style XML off disk and asserts
 * the two halves agree, so a size changed in one place and not the other fails here instead of
 * shipping as two screens whose "body text" is a different size.
 *
 * <p>It also pins the property that made the roles worth introducing: no layout may set a raw
 * {@code textSize}. That is the regression this whole change exists to prevent — before it, 122
 * layouts carried 759 hand-written sizes across 17 distinct values, and "the same kind of text" was
 * a different size on almost every screen.
 */
public class TypeRoleTest {

    /** Role name -> the size the role must carry, from the one token set. */
    private static Map<String, Float> expectedSizes() {
        Map<String, Float> out = new LinkedHashMap<>();
        out.put("Display", UiTokens.TEXT_DISPLAY);
        out.put("Title", UiTokens.TEXT_TITLE_ROLE);
        out.put("Body", UiTokens.TEXT_BODY);
        out.put("Label", UiTokens.TEXT_LABEL);
        out.put("Caption", UiTokens.TEXT_CAPTION);
        return out;
    }

    @Test
    public void everyRoleExistsAsAStyleWithTheCodesSize() throws IOException {
        String styles = readResource("app/src/main/res/values/styles.xml");
        for (Map.Entry<String, Float> e : expectedSizes().entrySet()) {
            String size = styleItem(styles, "TextAppearance.Glowberry." + e.getKey(),
                    "android:textSize");
            assertEquals("TextAppearance.Glowberry." + e.getKey() + " size",
                    formatSp(e.getValue()), size);
        }
    }

    @Test
    public void everyRoleInheritsTheOneFamily() throws IOException {
        String styles = readResource("app/src/main/res/values/styles.xml");
        // The base role style carries the family; each role inherits it rather than restating one.
        assertEquals("@font/misans",
                styleItem(styles, "TextAppearance.Glowberry", "android:fontFamily"));
        for (String role : expectedSizes().keySet()) {
            assertFalse("TextAppearance.Glowberry." + role + " must not restate a family; it "
                            + "inherits the base role's, so one family change moves every role",
                    hasItem(styles, "TextAppearance.Glowberry." + role, "android:fontFamily"));
        }
    }

    @Test
    public void theTwoNamedExceptionsAreTheOnlySizesOffTheRoleScale() throws IOException {
        String styles = readResource("app/src/main/res/values/styles.xml");
        // Wordmark is display art, Micro is a badge inside a pill. Both are deliberate, and both
        // are named so they stay countable rather than becoming an accidental third scale.
        assertEquals("34sp", styleItem(styles, "TextAppearance.Glowberry.Wordmark",
                "android:textSize"));
        assertEquals("9sp", styleItem(styles, "TextAppearance.Glowberry.Micro",
                "android:textSize"));
    }

    @Test
    public void noLayoutSetsARawTextSize() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path layout : layoutFiles()) {
            String xml = new String(Files.readAllBytes(layout), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("android:textSize=\"[0-9.]+sp\"").matcher(xml);
            while (m.find()) {
                offenders.add(layout.getFileName() + ": " + m.group());
            }
        }
        assertTrue("a raw textSize is the drift the type roles exist to prevent; use a "
                        + "TextAppearance.Glowberry.* role instead. Offenders: " + offenders,
                offenders.isEmpty());
    }

    @Test
    public void everyLayoutTextAppearanceIsARoleWeDefine() throws IOException {
        String styles = readResource("app/src/main/res/values/styles.xml");
        List<String> unknown = new ArrayList<>();
        for (Path layout : layoutFiles()) {
            String xml = new String(Files.readAllBytes(layout), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(
                    "android:textAppearance=\"@style/(TextAppearance\\.Glowberry\\.[A-Za-z]+)\"")
                    .matcher(xml);
            while (m.find()) {
                String name = m.group(1);
                if (!styles.contains("<style name=\"" + name + "\"")) {
                    unknown.add(layout.getFileName() + " -> " + name);
                }
            }
        }
        assertTrue("a layout references a type role that styles.xml does not define: " + unknown,
                unknown.isEmpty());
    }

    /** The CJK-capable family is a hard requirement, not a preference: the app ships zh/ja/hi. */
    @Test
    public void theRoleFamilyIsTheCjkCapableOne() throws IOException {
        String styles = readResource("app/src/main/res/values/styles.xml");
        String xmlFamily = styleItem(styles, "TextAppearance.Glowberry", "android:fontFamily");
        assertEquals("@font/misans", xmlFamily);

        // The code half must name the same family, or a view built in code would use a Latin-only
        // font while the identical view in XML used the CJK one.
        String src = readResource("app/src/main/java/org/chimeramc/client/ui/components/UiType.java");
        Matcher m = Pattern.compile("int FAMILY = R\\.font\\.([a-z0-9_]+);").matcher(src);
        assertTrue("UiType must declare FAMILY = R.font.<name>", m.find());
        assertEquals("UiType.FAMILY must be the same family the role styles use",
                "@font/misans", "@font/" + m.group(1));
    }

    private static List<Path> layoutFiles() throws IOException {
        Path dir = resolve("app/src/main/res/layout");
        List<Path> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".xml")).sorted().forEach(out::add);
        }
        return out;
    }

    private static String formatSp(float size) {
        return (size == Math.floor(size) ? String.valueOf((int) size) : String.valueOf(size)) + "sp";
    }

    /** The value of one {@code <item name="...">value</item>} inside a named style. */
    private static String styleItem(String styles, String styleName, String itemName) {
        Matcher style = Pattern.compile(
                "<style name=\"" + Pattern.quote(styleName) + "\"[^>]*>(.*?)</style>",
                Pattern.DOTALL).matcher(styles);
        assertTrue("style " + styleName + " is missing from styles.xml", style.find());
        Matcher item = Pattern.compile(
                "<item name=\"" + Pattern.quote(itemName) + "\">([^<]*)</item>")
                .matcher(style.group(1));
        assertTrue("style " + styleName + " has no " + itemName, item.find());
        return item.group(1).trim();
    }

    private static boolean hasItem(String styles, String styleName, String itemName) {
        Matcher style = Pattern.compile(
                "<style name=\"" + Pattern.quote(styleName) + "\"[^>]*>(.*?)</style>",
                Pattern.DOTALL).matcher(styles);
        if (!style.find()) return false;
        return Pattern.compile("<item name=\"" + Pattern.quote(itemName) + "\">")
                .matcher(style.group(1)).find();
    }

    private static Path resolve(String relativePath) throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isDirectory(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not find " + relativePath);
    }

    private static String readResource(String relativePath) throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not find " + relativePath);
    }
}
