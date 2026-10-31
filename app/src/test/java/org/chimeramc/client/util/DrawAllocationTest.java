package org.chimeramc.client.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import java.util.stream.Stream;

/**
 * The draw path allocates nothing per frame (items 17, 131, 184-187, 207).
 *
 * <p>An overlay shares the game's frame budget, so an allocation inside {@code onDraw} is not just
 * wasteful — it is a GC pause on the thread that is already drawing the world. The reported sites
 * were a mix: some had already been hoisted in earlier passes, and the ones that remained were
 * string building ({@code String.format}, concatenation) and graphics objects constructed in the
 * body rather than as fields.
 *
 * <p>This scans the real source tree, so it finds every {@code onDraw} rather than the ones that
 * were easy to remember, and it names the file and line so a reintroduction is a one-line fix.
 *
 * <p><b>One pattern is allowed: a guarded rebuild.</b> A gradient depends on the view size, so it
 * cannot be a field constant — but it is rebuilt only when the size changes, which is the cache the
 * comment in the code describes. The test recognises that shape (the assigned field is tested for
 * {@code == null} earlier in the same method) rather than carrying a line-number allowlist that
 * would drift the moment anything above it moved. Anything else is a failure.
 */
public class DrawAllocationTest {

    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    /** Graphics objects and string building that must not happen in a draw body. */
    private static final Pattern ALLOCATION = Pattern.compile(
            "new\\s+(?:android\\.graphics\\.)?"
                    + "(Rect|RectF|Path|Paint|LinearGradient|RadialGradient|SweepGradient"
                    + "|DashPathEffect|ComposePathEffect|CornerPathEffect|Matrix)\\s*\\("
                    + "|String\\.format\\s*\\("
                    + "|new\\s+StringBuilder\\s*\\("
                    + "|String\\.valueOf\\s*\\(");

    /** A field assigned from an allocation, so a preceding null test can be looked for. */
    private static final Pattern ASSIGNED_FIELD = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*new\\s");

    @Test
    public void noOnDrawBodyAllocatesUnconditionally() throws IOException {
        if (!Files.isDirectory(SOURCE_ROOT)) {
            fail("source root not found from " + SOURCE_ROOT.toAbsolutePath());
        }
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String relative = SOURCE_ROOT.relativize(file).toString().replace('\\', '/');
                scanFile(relative, Files.readAllLines(file, StandardCharsets.UTF_8), offenders);
            }
        }
        if (!offenders.isEmpty()) {
            fail("Hoist these to fields, or reuse a scratch instance, instead of allocating in onDraw"
                    + " (an overlay shares the game's frame budget):\n  "
                    + String.join("\n  ", offenders));
        }
    }

    static void scanFile(String relative, List<String> lines, List<String> offenders) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).matches(".*\\bvoid\\s+onDraw\\s*\\(.*")) continue;
            int end = methodEnd(lines, i);
            for (int j = i; j <= end; j++) {
                Matcher allocation = ALLOCATION.matcher(lines.get(j));
                if (!allocation.find()) continue;
                if (isGuardedRebuild(lines, i, j)) continue;
                offenders.add(relative + ":" + (j + 1) + " " + lines.get(j).trim());
            }
            i = end;
        }
    }

    /**
     * Whether this allocation is a cache rebuild guarded by a null/size test on the same field.
     *
     * <p>That is the one legitimate case: a gradient or bitmap that depends on the view size. The
     * test looks for the field on the left of the assignment being tested with {@code == null}
     * earlier in the method, which is the shape the cached-rebuild code actually has.
     */
    private static boolean isGuardedRebuild(List<String> lines, int methodStart, int allocationLine) {
        Matcher assigned = ASSIGNED_FIELD.matcher(lines.get(allocationLine));
        if (!assigned.find()) return false;
        String field = assigned.group(1);
        for (int k = methodStart; k < allocationLine; k++) {
            String line = lines.get(k);
            if (line.contains(field) && line.contains("== null") && line.contains("if")) {
                return true;
            }
        }
        return false;
    }

    /** The last line of the method whose opening brace is on {@code startLine}. */
    private static int methodEnd(List<String> lines, int startLine) {
        int depth = 0;
        boolean started = false;
        for (int j = startLine; j < lines.size(); j++) {
            depth += count(lines.get(j), '{') - count(lines.get(j), '}');
            if (lines.get(j).contains("{")) started = true;
            if (started && depth <= 0) return j;
        }
        return lines.size() - 1;
    }

    private static int count(String line, char c) {
        int n = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == c) n++;
        }
        return n;
    }

    @Test
    public void theScannerFlagsAnUnguardedAllocation() {
        // Drives the detector with synthetic input so the ban cannot pass because the scanner
        // stopped matching anything (a renamed method would otherwise look like a clean tree).
        List<String> lines = List.of(
                "protected void onDraw(Canvas canvas) {",
                "    RectF r = new RectF(0, 0, 1, 1);",
                "    canvas.drawRect(r, paint);",
                "}");
        List<String> offenders = new ArrayList<>();
        scanFile("Example.java", lines, offenders);
        assertEquals(1, offenders.size());
        assertTrue(offenders.get(0), offenders.get(0).startsWith("Example.java:2"));
    }

    @Test
    public void theScannerAllowsAHeightGuardedRebuild() {
        // The one legitimate in-body allocation: a gradient that depends on the view size and is
        // rebuilt only when that size changes. If this regressed, the exemption would silently
        // stop working and a real cached gradient would start failing the build.
        List<String> lines = List.of(
                "protected void onDraw(Canvas canvas) {",
                "    float h = getHeight();",
                "    if (bodyGradient == null || gradientHeight != h) {",
                "        bodyGradient = new LinearGradient(0f, 0f, 0f, h, a, b, CLAMP);",
                "        gradientHeight = h;",
                "    }",
                "    paint.setShader(bodyGradient);",
                "}");
        List<String> offenders = new ArrayList<>();
        scanFile("Example.java", lines, offenders);
        assertTrue(offenders.toString(), offenders.isEmpty());
    }

    @Test
    public void theScannerIgnoresStringConcatenationOutsideAFormat() {
        // Concatenation is only a per-draw allocation when it runs every frame; the scanner targets
        // the constructor/format calls, so a cached field assignment must not be flagged.
        List<String> lines = List.of(
                "protected void onDraw(Canvas canvas) {",
                "    if (captionMs != decision.remainingMs) {",
                "        captionMs = decision.remainingMs;",
                "        captionText = captionMs + \"ms\";",
                "    }",
                "    canvas.drawText(captionText, 0f, 0f, paint);",
                "}");
        List<String> offenders = new ArrayList<>();
        scanFile("Example.java", lines, offenders);
        assertTrue(offenders.toString(), offenders.isEmpty());
    }
}
