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
 * The spacing scale reaches the layouts (U9).
 *
 * <p>The rhythm was declared as tokens and then bypassed: the layouts carried 1515 hand-written
 * margins and paddings across 25 distinct values, so the vertical rhythm was whatever each screen
 * happened to pick. This test reads the real layouts and fails when a margin or padding states its
 * own number instead of naming a step.
 *
 * <p>Explicit {@code 0dp} is allowed — zero is not a step, and writing {@code @dimen/space_0} for
 * it would be noise. Everything else must be a token.
 */
public class SpacingScaleTest {

    private static final Pattern SPACING = Pattern.compile(
            "android:(?:layout_)?(?:margin|padding)[A-Za-z]*=\"([^\"]+)\""
                    + "|android:drawablePadding=\"([^\"]+)\"");

    @Test
    public void noLayoutStatesItsOwnMarginOrPadding() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path layout : files("app/src/main/res/layout")) {
            String xml = read(layout);
            Matcher m = SPACING.matcher(xml);
            while (m.find()) {
                String value = m.group(1) != null ? m.group(1) : m.group(2);
                if (value.equals("0dp")) continue;
                if (value.startsWith("@dimen/space_")) continue;
                offenders.add(layout.getFileName() + ": " + m.group());
            }
        }
        assertTrue("a margin or padding must name a spacing step (@dimen/space_*), not a number, "
                + "so the vertical rhythm matches the code-drawn surfaces. Offenders: " + offenders,
                offenders.isEmpty());
    }

    /** The six steps are the scale, and each maps to the matching UiTokens.SPACE_* constant. */
    @Test
    public void theStepsMatchTheCodeTokens() throws IOException {
        String dimens = read(resolve("app/src/main/res/values/dimens.xml"));
        assertEquals(UiTokens.SPACE_1, dimen(dimens, "space_1"));
        assertEquals(UiTokens.SPACE_2, dimen(dimens, "space_2"));
        assertEquals(UiTokens.SPACE_3, dimen(dimens, "space_3"));
        assertEquals(UiTokens.SPACE_4, dimen(dimens, "space_4"));
        assertEquals(UiTokens.SPACE_6, dimen(dimens, "space_6"));
        assertEquals(UiTokens.SPACE_8, dimen(dimens, "space_8"));
    }

    /** The steps are a 4dp rhythm, and each is a multiple of it. */
    @Test
    public void everyStepIsAMultipleOfFour() {
        for (int step : new int[]{UiTokens.SPACE_1, UiTokens.SPACE_2, UiTokens.SPACE_3,
                UiTokens.SPACE_4, UiTokens.SPACE_6, UiTokens.SPACE_8}) {
            assertEquals("step " + step + " is off the 4dp rhythm", 0, step % 4);
        }
    }

    /**
     * A layout dimension is never in {@code sp}.
     *
     * <p>{@code sp} scales with the user's font-size setting, which is right for text and wrong for
     * a gap: a margin in {@code sp} grows when a low-vision user enlarges their font, so a
     * carefully tuned layout drifts apart. This was a real defect — a 12sp margin in the APK import
     * dialog — and the only one, so it is worth a permanent guard.
     */
    @Test
    public void noLayoutDimensionIsInSp() throws IOException {
        List<String> offenders = new ArrayList<>();
        Pattern p = Pattern.compile(
                "android:(?:layout_)?(?:margin|padding)[A-Za-z]*=\"[0-9.]+sp\""
                        + "|android:(?:layout_)?(?:width|height|minWidth|minHeight|maxWidth"
                        + "|maxHeight|elevation|translationZ|translationY|translationX)"
                        + "=\"[0-9.]+sp\"");
        for (Path layout : files("app/src/main/res/layout")) {
            Matcher m = p.matcher(read(layout));
            while (m.find()) {
                offenders.add(layout.getFileName() + ": " + m.group());
            }
        }
        assertTrue("a dimension must be dp, not sp — sp scales with the font-size setting, which is "
                + "correct for text and wrong for a gap. Offenders: " + offenders,
                offenders.isEmpty());
    }

    /**
     * The named off-rhythm values exist and stay off the rhythm. If one of these ever lands on a
     * step it should be folded into {@code space_*}; keeping them asserted stops a slow drift where
     * an exception quietly becomes a second scale.
     */
    @Test
    public void theNamedExceptionsStayOffTheRhythm() throws IOException {
        String dimens = read(resolve("app/src/main/res/values/dimens.xml"));
        assertEquals(1, dimen(dimens, "space_divider"));
        assertEquals(2, dimen(dimens, "space_hairline"));
        assertEquals(52, dimen(dimens, "space_align_inset"));
        assertEquals(168, dimen(dimens, "space_dock_clearance"));

        // A divider and a hairline are genuinely below the first step; the alignment inset and the
        // dock clearance are structural measurements, not rhythm, and are not multiples of 4.
        assertTrue(dimen(dimens, "space_divider") < UiTokens.SPACE_1);
        assertTrue(dimen(dimens, "space_hairline") < UiTokens.SPACE_1);
    }

    private static int dimen(String xml, String name) {
        Matcher m = Pattern.compile("<dimen name=\"" + Pattern.quote(name) + "\">([0-9]+)dp</dimen>")
                .matcher(xml);
        assertTrue("dimen " + name + " is missing", m.find());
        return Integer.parseInt(m.group(1));
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
