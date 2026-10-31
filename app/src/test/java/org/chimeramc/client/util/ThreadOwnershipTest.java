package org.chimeramc.client.util;

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
import java.util.stream.Stream;

/**
 * One owner for threads and main-thread posts (items 14, 294, 295).
 *
 * <p>The migration is only worth anything if it stays done. A rule stated in a comment is a
 * suggestion; a rule a build fails on is not. This scans the real source tree, so it caught every
 * site rather than the ones that were easy to remember, and it names the file and line so a
 * reintroduction is a one-line fix rather than a search.
 *
 * <p>Two constructions are banned outright: {@code new Thread(...)}, which cannot be bounded and
 * carries no owner, and {@code new Handler(Looper.getMainLooper())}, which is 58 separate owners of
 * the main looper. Both have a single replacement in {@link AppExecutors} / {@link MainThread}, and
 * that replacement is where a name, a daemon flag and a lifetime come from.
 *
 * <p>The two files that <em>are</em> the replacement are the only exemptions, and the test asserts
 * they still contain the construction so the exemption cannot hide an empty implementation.
 */
public class ThreadOwnershipTest {

    /** The source root, relative to the module directory Gradle runs the test from. */
    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    private static final String THREAD_CONSTRUCTION = "new Thread(";
    private static final String MAIN_HANDLER = "new Handler(Looper.getMainLooper())";

    private static final List<String> OWNERS = List.of(
            "org/chimeramc/client/util/AppExecutors.java",
            "org/chimeramc/client/util/MainThread.java");

    @Test
    public void noBackgroundThreadIsConstructedOutsideTheOwner() throws IOException {
        List<String> offenders = scan(THREAD_CONSTRUCTION, OWNERS);
        if (!offenders.isEmpty()) {
            fail("Use AppExecutors.run(kind, name, work) or AppExecutors.thread(name, work) instead of"
                    + " constructing a thread directly:\n  " + String.join("\n  ", offenders));
        }
    }

    @Test
    public void noMainLooperHandlerIsConstructedOutsideTheOwner() throws IOException {
        List<String> offenders = scan(MAIN_HANDLER, OWNERS);
        if (!offenders.isEmpty()) {
            fail("Use MainThread.handler() or MainThread.post(...) instead of constructing a"
                    + " main-looper Handler directly:\n  " + String.join("\n  ", offenders));
        }
    }

    @Test
    public void theOwnerStillConstructsBoth() throws IOException {
        // Without this, deleting the construction from the owner would make the two bans pass
        // vacuously and the whole migration could be reverted with the suite still green.
        String executors = read(Paths.get(SOURCE_ROOT.toString(),
                "org/chimeramc/client/util/AppExecutors.java"));
        String mainThread = read(Paths.get(SOURCE_ROOT.toString(),
                "org/chimeramc/client/util/MainThread.java"));
        assertTrue("AppExecutors must own the thread construction",
                executors.contains(THREAD_CONSTRUCTION));
        assertTrue("MainThread must own the main-looper Handler construction",
                mainThread.contains(MAIN_HANDLER));
    }

    private static List<String> scan(String needle, List<String> exemptions) throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = SOURCE_ROOT;
        if (!Files.isDirectory(root)) {
            // A different working directory (an IDE run) would otherwise report every file missing.
            fail("source root not found from " + root.toAbsolutePath());
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (exemptions.contains(relative)) continue;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(needle)) {
                        offenders.add(relative + ":" + (i + 1) + " " + lines.get(i).trim());
                    }
                }
            }
        }
        return offenders;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
