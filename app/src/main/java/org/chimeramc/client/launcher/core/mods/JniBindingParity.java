package org.chimeramc.client.core.mods;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The rule that every Java/Kotlin {@code native} method has a matching C++ JNI export, and the
 * reverse.
 *
 * <p>This exists because both directions have silently broken this project before: a Java method with
 * no export throws {@link UnsatisfiedLinkError} at the call site, and a C++ export whose mangled name
 * no longer matches its Java class (the {@code launcher_preloader} rename) resolves nothing while the
 * error is swallowed by a fail-closed wrapper — so the feature is dead with no crash to point at it.
 *
 * <p>The comparison is deliberately <b>pure</b>: it takes the two sets of names as strings, so a unit
 * test can pin the rule with hand-written inputs, and a separate test feeds it the real repository
 * scan. The mangled JNI name for a method is
 * {@code Java_<package with '_'>_<Class>_<method>}; an overloaded method also carries a
 * {@code __<signature>} suffix, so names are compared on the part before {@code __}.
 *
 * <p>The Java allowlist is for methods whose native implementation lives in a <em>prebuilt</em>
 * library with no source in this repository (the {@code libmtbinloader2.so} bindings). Those cannot
 * be checked here and are excluded from the Java-side check; the C++-side check still covers every
 * export in the preloader source.
 *
 * <p>The C++ allowlist is for the reverse case: an export in the preloader that no longer has a Java
 * declaration because the declaring class was removed, where the preloader lives in a separate
 * repository and its removal ships on its own schedule. An entry here is a promise that the export
 * is dead, not a way to silence a real mismatch.
 */
public final class JniBindingParity {

    private JniBindingParity() {
    }

    /** A single mismatch, with enough detail to fix it. */
    public static final class Problem {
        public final String kind;      // "missing-export" or "stale-export"
        public final String symbol;    // the expected/actual mangled name
        public final String detail;

        Problem(String kind, String symbol, String detail) {
            this.kind = kind;
            this.symbol = symbol;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return kind + ": " + symbol + " (" + detail + ")";
        }
    }

    /**
     * The mangled JNI base name for a Java declaration.
     *
     * @param packageName the declaring class's package, e.g. {@code org.chimeramc.client.preloader}
     * @param className   the simple class name
     * @param methodName  the method name (the {@code @JvmName} value for a Kotlin external fun)
     */
    public static String jniSymbol(String packageName, String className, String methodName) {
        return "Java_" + packageName.replace('.', '_') + "_" + className + "_" + methodName;
    }

    /** The overload-independent base of a mangled name: everything before a {@code __} suffix. */
    public static String baseSymbol(String mangled) {
        int cut = mangled.indexOf("__");
        return cut < 0 ? mangled : mangled.substring(0, cut);
    }

    /**
     * Compares the Java declarations against the C++ exports.
     *
     * @param javaSymbols    mangled names expected from the Java/Kotlin side (may include overloads)
     * @param cppSymbols     mangled names actually exported by the C++ source (may include overloads)
     * @param javaAllowlist  Java symbols whose implementation is in a prebuilt library (skipped)
     * @return the problems found; empty when the two sides agree
     */
    public static List<Problem> compare(Collection<String> javaSymbols,
                                        Collection<String> cppSymbols,
                                        Set<String> javaAllowlist) {
        return compare(javaSymbols, cppSymbols, javaAllowlist, null);
    }

    /**
     * Compares the Java declarations against the C++ exports, with an allowlist on each side.
     *
     * @param javaSymbols    mangled names expected from the Java/Kotlin side (may include overloads)
     * @param cppSymbols     mangled names actually exported by the C++ source (may include overloads)
     * @param javaAllowlist  Java symbols whose implementation is in a prebuilt library (skipped)
     * @param cppAllowlist   C++ exports with no Java declaration pending a separate repository's
     *                       change (skipped); see the class docs
     * @return the problems found; empty when the two sides agree
     */
    public static List<Problem> compare(Collection<String> javaSymbols,
                                        Collection<String> cppSymbols,
                                        Set<String> javaAllowlist,
                                        Set<String> cppAllowlist) {
        Set<String> javaBases = new TreeSet<>();
        for (String symbol : javaSymbols) {
            String base = baseSymbol(symbol);
            if (javaAllowlist == null || !javaAllowlist.contains(base)) javaBases.add(base);
        }
        Set<String> cppBases = new TreeSet<>();
        for (String symbol : cppSymbols) {
            String base = baseSymbol(symbol);
            if (cppAllowlist != null && cppAllowlist.contains(base)) continue;
            cppBases.add(base);
        }

        List<Problem> problems = new ArrayList<>();
        for (String java : javaBases) {
            if (!cppBases.contains(java)) {
                problems.add(new Problem("missing-export", java,
                        "declared native but no JNI export in the preloader source"));
            }
        }
        for (String cpp : cppBases) {
            if (!javaBases.contains(cpp)) {
                problems.add(new Problem("stale-export", cpp,
                        "exported but no matching native declaration in Java/Kotlin"));
            }
        }
        return problems;
    }
}
