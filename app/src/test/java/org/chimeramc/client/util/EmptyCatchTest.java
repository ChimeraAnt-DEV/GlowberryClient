package org.chimeramc.client.util;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The files item 13 named as silently swallowing failures must not regress (items 13, 300).
 *
 * <p>A truly empty catch — {@code catch (Exception ignored) {}} — turns a failure into nothing:
 * the feature stops working and no one, including the in-app log viewer, is told. That is how a
 * broken backup looks identical to a successful one, and how a settings write that never lands
 * looks identical to one that did.
 *
 * <p>This pins only the list the plan called out, deliberately. A blanket ban would flag the
 * legitimate cases (a best-effort {@code setWritable} on a file whose deletes are the real
 * authority, a {@code RejectedExecutionException} when a screen is already gone), and a check that
 * has to be argued with is a check that gets an exception added until it stops meaning anything.
 * The named files are the ones where a swallow hid a user-visible state bug.
 */
public class EmptyCatchTest {

    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    /** A catch whose body is empty: nothing at all between the braces, not even a comment. */
    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");

    /**
     * The item-13 files, resolved by simple name so a package move does not silently skip a file —
     * {@link #theNamedFilesAllExist()} fails if one of them cannot be found.
     */
    private static final List<String> NAMED_FILES = List.of(
            "PersonalizationManager.java",
            "VersionManager.java",
            "MoreButtonsManager.java",
            "ModNotificationManager.java",
            "InstanceBackupManager.java",
            "LogcatOverlay.java",
            "CustomAlertDialog.java",
            "AboutActivity.java");

    @Test
    public void theNamedFilesAllExist() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String name : NAMED_FILES) {
            if (find(name) == null) missing.add(name);
        }
        if (!missing.isEmpty()) {
            fail("The item-13 files could not be found, so the empty-catch check is not looking at"
                    + " them any more; update the list rather than letting it pass vacuously:\n  "
                    + String.join("\n  ", missing));
        }
    }

    @Test
    public void theNamedFilesHaveNoEmptyCatch() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String name : NAMED_FILES) {
            Path file = find(name);
            if (file == null) continue;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String source = String.join("\n", lines);
            Matcher matcher = EMPTY_CATCH.matcher(source);
            while (matcher.find()) {
                int line = source.substring(0, matcher.start()).split("\n", -1).length;
                offenders.add(name + ":" + line);
            }
        }
        if (!offenders.isEmpty()) {
            fail("These catches swallow the failure with no log and no fallback; route them through"
                    + " AppLog.warn/warnOnce so the failure is visible in the in-app log:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    @Test
    public void theScannerRecognisesAnEmptyCatchAndNotALoggedOne() {
        assertTrue(EMPTY_CATCH.matcher("try { x(); } catch (Exception ignored) {}").find());
        assertTrue(EMPTY_CATCH.matcher("catch (Throwable t) {   }").find());
        // A body with any statement, or even just a comment, is not empty.
        assertTrue(!EMPTY_CATCH.matcher(
                "catch (Exception e) { AppLog.warn(TAG, \"x\", e); }").find());
        assertTrue(!EMPTY_CATCH.matcher("catch (Exception e) { /* best effort */ }").find());
    }

    private static Path find(String simpleName) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files.filter(p -> p.getFileName().toString().equals(simpleName))
                    .findFirst()
                    .orElse(null);
        }
    }
}
