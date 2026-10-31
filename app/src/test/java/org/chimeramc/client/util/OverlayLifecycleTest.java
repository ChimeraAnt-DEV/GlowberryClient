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
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Every overlay that posts delayed work must have a way to cancel it (item 183).
 *
 * <p>The in-game overlays schedule work on the main looper — a deferred attach, a refresh tick, a
 * confirm-delete timer. A post that is never removed outlives the view that made it: it keeps the
 * Activity alive through its closure and fires into a detached tree. That failure is invisible
 * until it is not (a leak, or a callback running after the screen is gone), which is exactly why it
 * needs a check rather than care.
 *
 * <p>The check is deliberately a token test rather than a lifetime analysis: a file that calls
 * {@code postDelayed} must also mention one of the removal paths. It cannot prove the removal runs
 * on every exit path, but it does catch the file that schedules and never cleans up at all, which
 * is the shape of the bug this class was written for.
 */
public class OverlayLifecycleTest {

    private static final Path OVERLAY_ROOT = Paths.get(
            "src", "main", "java", "org", "chimeramc", "client", "launcher", "core", "mods",
            "inbuilt", "overlay");

    private static final String POST = "postDelayed(";

    /** The ways an owner cancels what it posted. */
    private static final List<String> CLEANUP = List.of(
            "removeCallbacks(",
            "removeCallbacksAndMessages(",
            "MainThread.Scope",
            ".scope");

    @Test
    public void everyOverlayThatPostsDelayedWorkHasACleanupPath() throws IOException {
        if (!Files.isDirectory(OVERLAY_ROOT)) {
            fail("overlay source root not found from " + OVERLAY_ROOT.toAbsolutePath());
        }
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(OVERLAY_ROOT)) {
            for (Path file : (Iterable<Path>) files
                    .filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (!source.contains(POST)) continue;
                boolean cleaned = CLEANUP.stream().anyMatch(source::contains);
                if (!cleaned) {
                    offenders.add(OVERLAY_ROOT.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        if (!offenders.isEmpty()) {
            fail("These overlays post delayed work but never cancel it; route the posts through a"
                    + " MainThread.Scope (closed on teardown) or remove them in the owner's exit"
                    + " path:\n  " + String.join("\n  ", offenders));
        }
    }

    @Test
    public void theCheckSeesACleanupToken() throws IOException {
        // Guards the check itself: a file that posts and cancels must pass, and the tokens the
        // overlays actually use must be in the list.
        String cancelling = "handler.postDelayed(r, 10); handler.removeCallbacks(r);";
        assertTrue(CLEANUP.stream().anyMatch(cancelling::contains));
        String scoped = "MainThread.Scope scope = MainThread.scope();"
                + " scope.postDelayed(r, 10); scope.close();";
        assertTrue(CLEANUP.stream().anyMatch(scoped::contains));
        String unscheduled = "handler.postDelayed(r, 10);";
        assertTrue(CLEANUP.stream().noneMatch(unscheduled::contains));
    }
}
