package org.chimeramc.client.ui.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translations may not fall further behind (items 24, 331, 333).
 *
 * <p>The ten shipped locales each covered about 38% of the default strings, so roughly 1,350 keys
 * fell back to English — and the gap was in the newest surfaces, which is exactly where a new user
 * meets the app. The cause is not the translators: nothing stopped a new string from shipping
 * untranslated, so the backlog only grew.
 *
 * <p>This is the ratchet. The current untranslated set is recorded in
 * {@code app/src/test/resources/translation-baseline.json}; a key that is not in that baseline and
 * has no translation in a locale fails the build. Bringing a key up to date is always allowed, and
 * removing a key from the baseline is what "translating something" looks like.
 *
 * <p>The other half is honesty: a string that is a brand name, a file extension or a wire token is
 * marked {@code translatable="false"} and stops counting. That is item 334, and it is enforced here
 * so the count means "work to do" rather than "work plus things that must never be translated".
 */
public class TranslationCoverageTest {

    private static final Pattern ENTRY = Pattern.compile(
            "<(string|plurals|string-array)\\b([^>]*)>");

    /** Locales whose translation is expected. A new one is a deliberate change to this list. */
    private static final Set<String> LOCALES = new LinkedHashSet<>(List.of(
            "es", "fr", "hi", "id", "ja", "pt", "ru", "tr", "vi", "zh-rCN"));

    @Test
    public void aNewStringMustBeTranslatedOrMarkedNonTranslatable() throws IOException {
        Set<String> baseline = baselineUntranslated();
        Map<String, Set<String>> missing = missingByLocale();

        List<String> regressions = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : missing.entrySet()) {
            for (String key : e.getValue()) {
                if (!baseline.contains(key)) {
                    regressions.add(e.getKey() + "/" + key);
                }
            }
        }
        assertTrue("every locale must translate a new string, or the string must be marked "
                        + "translatable=\"false\" if it is a brand name or a wire token. Untranslated "
                        + "and not in the baseline: " + regressions
                        + "\n\nTranslate it (scripts/apply_translations.py) or add it to "
                        + "app/src/test/resources/translation-baseline.json only if it is genuinely "
                        + "untranslatable.",
                regressions.isEmpty());
    }

    @Test
    public void theBaselineOnlyHoldsKeysThatStillExist() throws IOException {
        Set<String> defaults = defaultKeys();
        List<String> dead = new ArrayList<>();
        for (String key : baselineUntranslated()) {
            if (!defaults.contains(key)) dead.add(key);
        }
        assertEquals("a baseline entry for a string that no longer exists hides real progress and "
                + "makes the file hard to read. Remove: " + dead, 0, dead.size());
    }

    /**
     * The priority batch is applied, not just written down. The launch-failure dialogs and the
     * first-run wizard are what a new user reads first, so they are the one group that must be
     * complete in every locale even while the rest of the backlog is worked through.
     */
    @Test
    public void thePriorityBatchIsTranslatedEverywhere() throws IOException {
        Map<String, Set<String>> missing = missingByLocale();
        List<String> gaps = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : missing.entrySet()) {
            for (String key : e.getValue()) {
                if (key.startsWith("launch_failure_") || key.startsWith("setup_step_")
                        || key.startsWith("error_short_")) {
                    gaps.add(e.getKey() + "/" + key);
                }
            }
        }
        assertTrue("the first-run wizard, the launch-failure titles and the short error reasons must "
                + "be translated in every locale; they are the first thing a user reads. Missing: "
                + gaps, gaps.isEmpty());
    }

    /**
     * Every format specifier survives translation. A dropped {@code %1$s} is not a cosmetic bug: it
     * is a {@code MissingFormatArgumentException} thrown inside {@code String.format} on the screen
     * that was already reporting a failure.
     */
    @Test
    public void aTranslationKeepsEveryFormatSpecifier() throws IOException {
        Map<String, String> defaults = defaultValues();
        Pattern specifier = Pattern.compile("%(\\d+\\$)?[sd]");
        List<String> broken = new ArrayList<>();

        for (String locale : LOCALES) {
            Map<String, String> translated = values(resolve(
                    "app/src/main/res/values-" + locale + "/strings.xml"));
            for (Map.Entry<String, String> e : translated.entrySet()) {
                String original = defaults.get(e.getKey());
                if (original == null) continue;
                List<String> want = sorted(specifier, original);
                List<String> have = sorted(specifier, e.getValue());
                if (!want.equals(have)) {
                    broken.add(locale + "/" + e.getKey() + " (default " + want + ", has " + have + ")");
                }
            }
        }
        assertTrue("a translation must keep every %s/%d placeholder, or String.format throws on the "
                + "screen that failed. Offenders: " + broken, broken.isEmpty());
    }

    private static List<String> sorted(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group());
        java.util.Collections.sort(out);
        return out;
    }

    private static Map<String, Set<String>> missingByLocale() throws IOException {
        Set<String> defaults = translatableKeys();
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String locale : LOCALES) {
            Path f = resolve("app/src/main/res/values-" + locale + "/strings.xml");
            Set<String> have = keys(f);
            Set<String> missing = new LinkedHashSet<>(defaults);
            missing.removeAll(have);
            out.put(locale, missing);
        }
        return out;
    }

    private static Set<String> baselineUntranslated() throws IOException {
        String json = read(resolve("app/src/test/resources/translation-baseline.json"));
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*[,\\]]").matcher(
                json.substring(json.indexOf("\"untranslated\"")));
        while (m.find()) out.add(m.group(1));
        // The locale names and the wrapper keys are not string keys.
        out.removeAll(LOCALES);
        out.remove("untranslated");
        return out;
    }

    private static Set<String> defaultKeys() throws IOException {
        return keys(resolve("app/src/main/res/values/strings.xml"));
    }

    private static Set<String> translatableKeys() throws IOException {
        Map<String, Boolean> all = new LinkedHashMap<>();
        String xml = read(resolve("app/src/main/res/values/strings.xml"));
        Matcher m = ENTRY.matcher(xml);
        while (m.find()) {
            String name = attribute(m.group(2), "name");
            if (name != null) {
                all.put(name, m.group(2).contains("translatable=\"false\""));
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Boolean> e : all.entrySet()) {
            if (!e.getValue()) out.add(e.getKey());
        }
        return out;
    }

    private static Map<String, String> defaultValues() throws IOException {
        return values(resolve("app/src/main/res/values/strings.xml"));
    }

    private static Map<String, String> values(Path f) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        String xml = read(f);
        Matcher m = Pattern.compile("<string\\b([^>]*)>(.*?)</string>", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String name = attribute(m.group(1), "name");
            if (name != null) out.put(name, m.group(2));
        }
        return out;
    }

    private static Set<String> keys(Path f) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = ENTRY.matcher(read(f));
        while (m.find()) {
            String name = attribute(m.group(2), "name");
            if (name != null) out.add(name);
        }
        return out;
    }

    private static String attribute(String attrs, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]+)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
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
