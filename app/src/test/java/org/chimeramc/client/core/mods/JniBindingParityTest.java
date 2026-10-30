package org.chimeramc.client.core.mods;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Pins the JNI parity rule with hand-written inputs, then runs it against the real repository.
 *
 * <p>The second half is the CI check the spec asks for: it walks every Java/Kotlin source for a
 * {@code native} (Java) or {@code external fun} (Kotlin, honouring {@code @JvmName}) declaration and
 * every preloader C++ source for a {@code Java_...} export, mangles both sides, and fails if they
 * disagree in either direction. It has already caught real mismatches in this repository's history
 * (a class moved to a different package while its export kept the old one).
 *
 * <p>Classes whose native implementation lives in a prebuilt library with no source here (the
 * {@code libmtbinloader2.so} bindings) are excluded from the Java side, because there is no C++
 * source to compare against. That exclusion is deliberately narrow and explicit.
 */
public class JniBindingParityTest {

    // --- The pure rule -----------------------------------------------------------------------

    @Test
    public void aDeclarationManglesToItsJniSymbol() {
        assertEquals("Java_org_chimeramc_client_preloader_PreloaderInput_nativeOnTouch",
                JniBindingParity.jniSymbol("org.chimeramc.client.preloader", "PreloaderInput",
                        "nativeOnTouch"));
    }

    @Test
    public void overloadsAreComparedOnTheirBaseName() {
        assertEquals("Java_a_b_C_d", JniBindingParity.baseSymbol("Java_a_b_C_d__Ljava_lang_String_2"));
        assertEquals("Java_a_b_C_d", JniBindingParity.baseSymbol("Java_a_b_C_d"));
    }

    @Test
    public void aDeclaredNativeWithNoExportIsAProblem() {
        List<JniBindingParity.Problem> problems = JniBindingParity.compare(
                Collections.singletonList("Java_a_b_C_missing"),
                Collections.singletonList("Java_a_b_C_present"),
                Collections.emptySet());
        assertEquals(2, problems.size());
        assertTrue(problems.stream().anyMatch(p -> p.kind.equals("missing-export")
                && p.symbol.equals("Java_a_b_C_missing")));
        assertTrue(problems.stream().anyMatch(p -> p.kind.equals("stale-export")
                && p.symbol.equals("Java_a_b_C_present")));
    }

    @Test
    public void matchingSidesHaveNoProblems() {
        List<String> same = Arrays.asList("Java_a_b_C_x", "Java_a_b_C_y");
        assertTrue(JniBindingParity.compare(same, same, Collections.emptySet()).isEmpty());
    }

    @Test
    public void theAllowlistExcludesPrebuiltOnlyDeclarations() {
        Set<String> allow = new HashSet<>(Collections.singletonList("Java_a_b_Prebuilt_nativeInit"));
        List<JniBindingParity.Problem> problems = JniBindingParity.compare(
                Collections.singletonList("Java_a_b_Prebuilt_nativeInit"),
                Collections.emptyList(), allow);
        assertTrue("an allow-listed declaration needs no export", problems.isEmpty());
    }

    // --- The repository scan (the CI check) --------------------------------------------------

    /**
     * Classes whose {@code native} methods are implemented by a prebuilt library, not by the
     * preloader source. Listed by FQN; excluded from the Java side of the comparison only.
     */
    private static final Set<String> PREBUILT_BOUND_CLASSES = new HashSet<>(Arrays.asList(
            "io.bambosan.mbloader.launcherUtils.LibBindings"));

    /**
     * Preloader exports with no Java declaration, pending the removal in the preloader's own
     * repository (which this repo does not have push access to, so the two land separately).
     *
     * <p>{@code nativeSetupRuntime} was declared by the deleted
     * {@code org.levimc.launcher.core.minecraft.MinecraftRuntimePreparer} bridge, which only existed
     * to satisfy the prebuilt {@code libgxcore.so}'s hardcoded JNI lookup. With that library and its
     * declaring class gone the export is dead; it is listed here so the parity check still fails on
     * any <em>new</em> mismatch. Remove the entry and the export together.
     */
    private static final Set<String> PENDING_PRELOADER_REMOVALS = new HashSet<>(Arrays.asList(
            "Java_org_levimc_launcher_core_minecraft_MinecraftRuntimePreparer_nativeSetupRuntime"));

    @Test
    public void everyNativeDeclarationHasAMatchingExportAndViceVersa() throws IOException {
        Path repo = findRepoRoot();
        Set<String> javaSymbols = scanJavaDeclarations(repo);
        Set<String> cppSymbols = scanCppExports(repo);

        // Sanity: the scan must have found the surface, or a broken scan would pass vacuously.
        assertTrue("scan found too few Java native declarations: " + javaSymbols.size(),
                javaSymbols.size() > 60);
        assertTrue("scan found too few C++ exports: " + cppSymbols.size(), cppSymbols.size() > 50);

        List<JniBindingParity.Problem> problems =
                JniBindingParity.compare(javaSymbols, cppSymbols, Collections.emptySet(),
                        PENDING_PRELOADER_REMOVALS);
        assertTrue("JNI binding parity failed:\n" + join(problems), problems.isEmpty());
    }

    @Test
    public void theCppAllowlistHidesOnlyItsOwnExports() {
        Set<String> cppAllow = new HashSet<>(Collections.singletonList("Java_a_b_C_dead"));
        List<JniBindingParity.Problem> problems = JniBindingParity.compare(
                Collections.singletonList("Java_a_b_C_live"),
                Arrays.asList("Java_a_b_C_dead", "Java_a_b_C_live", "Java_a_b_C_other"),
                Collections.emptySet(), cppAllow);
        assertEquals("only the allow-listed export is skipped", 1, problems.size());
        assertEquals("stale-export", problems.get(0).kind);
        assertEquals("Java_a_b_C_other", problems.get(0).symbol);
    }

    @Test
    public void thePrebuiltBoundClassesAreNotAlsoExportedByThePreloader() throws IOException {
        // A class is either prebuilt-bound or preloader-implemented, never both: if a prebuilt-bound
        // class ever gained a preloader export, the scan exclusion would hide a real drift.
        Set<String> cppSymbols = scanCppExports(findRepoRoot());
        for (String cls : PREBUILT_BOUND_CLASSES) {
            String prefix = "Java_" + cls.replace('.', '_') + "_";
            assertFalse("prebuilt-bound class is exported by the preloader: " + cls,
                    cppSymbols.stream().anyMatch(s -> s.startsWith(prefix)));
        }
    }

    private static String join(List<JniBindingParity.Problem> problems) {
        StringBuilder b = new StringBuilder();
        for (JniBindingParity.Problem p : problems) b.append("  ").append(p).append('\n');
        return b.toString();
    }

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE);
    // Anchored to a real declaration line: a comment/JavaDoc line (starting with * or //) is never a
    // type, so prose like "the host class was moved" cannot be mistaken for the class's own name.
    private static final Pattern CLASS = Pattern.compile(
            "^(?!\\s*[*/])\\s*(?:(?:public|private|protected|internal|final|open|abstract|data|sealed"
                    + "|annotation|value|inline)\\s+)*(?:class|object|interface|enum\\s+class|enum)\\s+(\\w+)",
            Pattern.MULTILINE);
    private static final Pattern JAVA_NATIVE = Pattern.compile(
            "^\\s*(?:public|private|protected|static|final|\\s)*\\bnative\\b[^;{]*?\\b(\\w+)\\s*\\(",
            Pattern.MULTILINE);
    private static final Pattern KOTLIN_EXTERNAL = Pattern.compile(
            "^\\s*(?:public|private|internal|protected|\\s)*(?:external\\s+fun|fun\\s+external)\\s+(\\w+)",
            Pattern.MULTILINE);
    private static final Pattern JVM_NAME = Pattern.compile("@JvmName\\(\"([\\w$]+)\"\\)");
    private static final Pattern CPP_EXPORT = Pattern.compile("Java_[A-Za-z0-9_]+");

    private static Set<String> scanJavaDeclarations(Path repo) throws IOException {
        Set<String> symbols = new TreeSet<>();
        Path javaRoot = repo.resolve("app/src/main/java");
        try (Stream<Path> stream = Files.walk(javaRoot)) {
            for (Path file : (Iterable<Path>) stream
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt"))
                    ::iterator) {
                boolean kotlin = file.toString().endsWith(".kt");
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher pkgM = PACKAGE.matcher(text);
                Matcher clsM = CLASS.matcher(text);
                if (!pkgM.find() || !clsM.find()) continue;
                String pkg = pkgM.group(1);
                String cls = clsM.group(1);
                if (PREBUILT_BOUND_CLASSES.contains(pkg + "." + cls)) continue;

                if (!kotlin) {
                    Matcher m = JAVA_NATIVE.matcher(text);
                    while (m.find()) {
                        symbols.add(JniBindingParity.jniSymbol(pkg, cls, m.group(1)));
                    }
                } else {
                    Matcher m = KOTLIN_EXTERNAL.matcher(text);
                    while (m.find()) {
                        // A Kotlin external fun may carry @JvmName, in which case the mangled name is
                        // the @JvmName value, not the fun name. Look back to the fun's start for the
                        // nearest preceding @JvmName.
                        String name = m.group(1);
                        int lineStart = text.lastIndexOf('\n', m.start()) + 1;
                        int lookback = Math.max(0, lineStart - 200);
                        Matcher jvm = JVM_NAME.matcher(text.substring(lookback, m.start()));
                        String jvmName = null;
                        while (jvm.find()) jvmName = jvm.group(1);
                        symbols.add(JniBindingParity.jniSymbol(pkg, cls, jvmName != null ? jvmName : name));
                    }
                }
            }
        }
        return symbols;
    }

    private static Set<String> scanCppExports(Path repo) throws IOException {
        Set<String> symbols = new TreeSet<>();
        Path cppRoot = repo.resolve("app/src/main/cpp/preloader");
        try (Stream<Path> stream = Files.walk(cppRoot)) {
            for (Path file : (Iterable<Path>) stream
                    .filter(p -> p.toString().endsWith(".cpp") || p.toString().endsWith(".cc")
                            || p.toString().endsWith(".h") || p.toString().endsWith(".hpp"))
                    ::iterator) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher m = CPP_EXPORT.matcher(text);
                while (m.find()) {
                    symbols.add(JniBindingParity.baseSymbol(m.group()));
                }
            }
        }
        return symbols;
    }

    /** Walks up from the working directory to the repo root (the dir holding settings.gradle). */
    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.exists(dir.resolve("settings.gradle"))
                    && Files.isDirectory(dir.resolve("app/src/main/cpp/preloader"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repo root not found from " + Paths.get("").toAbsolutePath());
    }
}
