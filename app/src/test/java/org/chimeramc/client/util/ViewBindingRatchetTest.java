package org.chimeramc.client.util;

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
 * A ratchet on {@code findViewById} (item 111).
 *
 * <p>View binding is on, yet the tree holds hundreds of {@code findViewById} calls. Converting them
 * all at once would be a change no one could review, so the plan's rule is the right one: convert
 * per file as you touch it, and stop the number going *up*.
 *
 * <p>This is a ratchet, not a target. The ceiling is the count on the day it was written; every
 * conversion lowers it. When it fails, the message says the current count so lowering the constant
 * is a copy, not an investigation.
 *
 * <p>Only {@code src/main} is counted. A test that reads a view from a test tree is not production
 * code and should not move this number.
 */
public class ViewBindingRatchetTest {

    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    /**
     * The number of {@code findViewById} calls allowed. Lower it whenever a conversion lands; never
     * raise it. Raised once, it stops being a ratchet.
     */
    private static final int CEILING = 897;

    @Test
    public void findViewByIdCallsDoNotIncrease() throws IOException {
        List<String> files = new ArrayList<>();
        int count = 0;
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            for (Path file : (Iterable<Path>) paths
                    .filter(p -> p.toString().endsWith(".java")
                            || p.toString().endsWith(".kt"))::iterator) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                int inFile = occurrences(source);
                if (inFile > 0) {
                    files.add(SOURCE_ROOT.relativize(file).toString().replace('\\', '/')
                            + " (" + inFile + ")");
                    count += inFile;
                }
            }
        }
        if (count > CEILING) {
            fail("findViewById calls rose to " + count + " from the ceiling of " + CEILING
                    + ". Use view binding (the binding is already enabled) instead of a new lookup,"
                    + " or convert an existing file in the same change. Files with lookups:\n  "
                    + String.join("\n  ", files));
        }
    }

    /** Counts {@code findViewById(}, ignoring a mention inside a comment or a string. */
    private static int occurrences(String source) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf("findViewById(", index)) >= 0) {
            if (!isCommentedOut(source, index)) {
                count++;
            }
            index += "findViewById(".length();
        }
        return count;
    }

    /** True when the occurrence sits on a line that is a comment. */
    private static boolean isCommentedOut(String source, int index) {
        int lineStart = source.lastIndexOf('\n', index) + 1;
        String line = source.substring(lineStart, index);
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }
}
