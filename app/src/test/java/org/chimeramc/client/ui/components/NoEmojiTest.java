package org.chimeramc.client.ui.components;

import static org.junit.Assert.assertTrue;

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
 * The client ships no emoji (Phase 13).
 *
 * <p>An emoji in the UI is not a style preference here: it renders as a different picture on every
 * device font, it is announced as a word by a screen reader ("waving hand"), and it breaks the flat
 * outline look the icon set exists for. The icons are {@link UiIcons} vectors and the
 * {@code res/drawable} vectors; nothing user-facing is a pictograph.
 *
 * <p>This is the gate. It reads the shipped resource files and the code that builds text, and fails
 * on any pictograph code point. Typographic characters — the em dash, the ellipsis, a bullet, the
 * infinity sign in a numeric range — are deliberately allowed: they are text, they render in the
 * text font, and removing them would make the copy worse rather than cleaner.
 */
public class NoEmojiTest {

    /**
     * The pictograph and dingbat ranges. Deliberately excludes the general punctuation block
     * (en/em dash, ellipsis, quotes), the mathematical operators ({@code ∞}) and the bullet, which
     * are typography rather than emoji.
     */
    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)   // emoji, symbols, pictographs, supplemental
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF) // regional indicators (flags)
                || (cp >= 0x2600 && cp <= 0x27BF)   // miscellaneous symbols and dingbats
                || (cp >= 0x2B00 && cp <= 0x2BFF)   // miscellaneous symbols and arrows
                || (cp >= 0x2190 && cp <= 0x21FF)   // arrows (only used as emoji-ish decoration)
                || (cp >= 0x2460 && cp <= 0x24FF)   // enclosed alphanumerics
                || cp == 0xFE0F                       // variation selector-16 (emoji presentation)
                || cp == 0x20E3;                      // combining enclosing keycap
    }

    @Test
    public void noShippedStringContainsAnEmoji() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : resourceFiles()) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            int line = 0;
            for (String l : text.split("\n", -1)) {
                line++;
                for (int i = 0; i < l.length(); ) {
                    int cp = l.codePointAt(i);
                    if (isEmoji(cp)) {
                        offenders.add(p.getFileName() + ":" + line
                                + " U+" + Integer.toHexString(cp).toUpperCase());
                    }
                    i += Character.charCount(cp);
                }
            }
        }
        assertTrue("no user-facing string may contain an emoji: " + offenders,
                offenders.isEmpty());
    }

    /**
     * The code-built half: a string literal is what a screen renders when it is not a resource.
     * Only literals are read — a comment may use an arrow to describe a pipeline, and that never
     * reaches a device.
     */
    @Test
    public void noStringLiteralInSourceContainsAnEmoji() throws IOException {
        java.util.regex.Pattern literal =
                java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        List<String> offenders = new ArrayList<>();
        for (Path p : sourceFiles()) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            int line = 0;
            for (String l : text.split("\n", -1)) {
                line++;
                String stripped = l.stripLeading();
                if (stripped.startsWith("*") || stripped.startsWith("//")
                        || stripped.startsWith("/*")) {
                    continue;
                }
                java.util.regex.Matcher m = literal.matcher(l);
                while (m.find()) {
                    String value = m.group(1);
                    for (int i = 0; i < value.length(); ) {
                        int cp = value.codePointAt(i);
                        if (isEmoji(cp)) {
                            offenders.add(p.getFileName() + ":" + line
                                    + " U+" + Integer.toHexString(cp).toUpperCase());
                        }
                        i += Character.charCount(cp);
                    }
                }
            }
        }
        assertTrue("no code-built string may contain an emoji: " + offenders, offenders.isEmpty());
    }

    /**
     * The README and the docs are user-facing too, and they had emoji in the badges and the footer
     * long after the app was clean. Scanned here so the same gate covers the whole surface rather
     * than only the shipped resources.
     */
    /**
     * The document predicate: the same pictographs as the app check, minus the plain arrow block.
     *
     * <p>A document legitimately uses {@code →} as prose punctuation ("Settings → Backup") and in
     * a table cell, and that arrow is not emoji presentation; flagging it would force worse copy
     * for no reason. Everything with a pictograph or an emoji-presentation selector is still
     * caught, which is what took the badges' download/read/report glyphs and the footer heart out.
     */
    private static boolean isDocEmoji(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2B00 && cp <= 0x2BFF)
                || cp == 0xFE0F
                || cp == 0x20E3;
    }

    @Test
    public void noUserFacingDocumentContainsAnEmoji() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : documentFiles()) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            int line = 0;
            for (String l : text.split("\n", -1)) {
                line++;
                for (int i = 0; i < l.length(); ) {
                    int cp = l.codePointAt(i);
                    if (isDocEmoji(cp)) {
                        offenders.add(p.getFileName() + ":" + line
                                + " U+" + Integer.toHexString(cp).toUpperCase());
                    }
                    i += Character.charCount(cp);
                }
            }
        }
        assertTrue("no user-facing document may contain an emoji: " + offenders,
                offenders.isEmpty());
    }

    private static List<Path> documentFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        Path readme = resolve("README.md");
        if (Files.exists(readme)) out.add(readme);
        Path docs = resolve("docs");
        if (Files.exists(docs)) {
            try (Stream<Path> walk = Files.walk(docs)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".md"))
                        .forEach(out::add);
            }
        }
        // The automation and the issue templates are user-facing too: the workflows post the release
        // announcements and the templates are the first thing a reporter reads, so an emoji there is
        // as visible as one in the app. They had them long after the app was clean, which is the
        // reason the gate now covers the whole repository surface rather than only the shipped
        // resources.
        Path github = resolve(".github");
        if (Files.exists(github)) {
            try (Stream<Path> walk = Files.walk(github)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".yml")
                                || p.toString().endsWith(".yaml"))
                        .forEach(out::add);
            }
        }
        // The preloader's own docs ship with the submodule and are read by contributors.
        Path preloaderDocs = resolve("app/src/main/cpp/preloader/docs");
        if (Files.exists(preloaderDocs)) {
            try (Stream<Path> walk = Files.walk(preloaderDocs)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".md"))
                        .forEach(out::add);
            }
        }
        return out;
    }

    private static List<Path> sourceFiles() throws IOException {
        Path root = resolve("app/src/main/java");
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt"))
                    .forEach(out::add);
        }
        return out;
    }

    private static List<Path> resourceFiles() throws IOException {
        Path root = resolve("app/src/main/res");
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".xml"))
                    .forEach(out::add);
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
}
