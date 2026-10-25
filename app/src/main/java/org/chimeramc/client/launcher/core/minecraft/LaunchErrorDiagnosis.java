package org.chimeramc.client.launcher.core.minecraft;

import java.util.Locale;

import org.chimeramc.client.R;

/**
 * Turns a launch failure into the specific reason it happened.
 *
 * <p>A launch can die in a dozen different ways that all surface as the same sentence — "Failed to
 * launch Minecraft: ..." with a raw {@code dlopen} string appended. The raw string is accurate but
 * useless to the person holding the phone: {@code dlopen failed: library "libminecraftpe.so" not
 * found} does not say that the install is a Google Play build the launcher cannot read, and
 * {@code is 32-bit instead of 64-bit} does not say that a 64-bit build has to be imported instead.
 *
 * <p>This class is the mapping from the failure to that sentence. It is pure — it reads a message
 * string, never a device — so every cause is testable, and it is the single place the copy lives, so
 * the crash screen and the launch dialog cannot describe the same failure two different ways.
 *
 * <p>Classification order matters and is deliberate: the most specific signal wins. "32-bit instead
 * of 64-bit" contains neither "not found" nor a library name in a reliable position, and a generic
 * {@code UnsatisfiedLinkError} must not be reported as a missing game file, so the bitness test runs
 * before the missing-library test and the game-library test runs before the generic one. A
 * "cannot locate symbol" failure also carries "dlopen failed", so it is tested before the generic
 * dlopen branch or it would be mis-reported as a missing file when the file is present but unusable.
 */
public final class LaunchErrorDiagnosis {

    private LaunchErrorDiagnosis() {
    }

    /** The named reasons a launch can fail after the preflight has passed. */
    public enum Cause {
        /**
         * The game's own native library could not be opened. On this platform that is almost always
         * a Google Play install (whose libraries live inside the installed app) or a build whose
         * APK genuinely does not carry the file — the two cases the message names.
         */
        MISSING_GAME_LIBRARY,
        /** A library is the wrong bitness for this process. */
        ABI_MISMATCH,
        /** A library the game needs is absent from the extracted cache. */
        MISSING_DEPENDENCY,
        /** The extracted cache is present but was written by an older extractor. */
        STALE_NATIVE_LIBS,
        /** No Minecraft package could be found to launch at all. */
        GAME_NOT_FOUND,
        /** A mod's native library failed to load, taking the launch with it. */
        MOD_FAILED,
        /** The process ran out of memory while preparing the game. */
        OUT_OF_MEMORY,
        /** Nothing recognisable in the failure. */
        UNKNOWN,
    }

    /**
     * Classifies a failure from its message text.
     *
     * <p>Text rather than a {@link Throwable} because the crash screen only ever has the tombstone
     * text on disk, and because the same rules must serve both paths.
     */
    public static Cause classifyText(String text) {
        if (text == null) return Cause.UNKNOWN;
        String lower = text.toLowerCase(Locale.ROOT);

        // Bitness first: the message names neither a missing file nor a symbol, and a 32-bit build
        // reported as "missing a file" would send the user to re-import the same broken build.
        if (lower.contains("is 32-bit instead of 64-bit")
                || lower.contains("is 64-bit instead of 32-bit")
                || lower.contains("only supports 32-bit")
                || lower.contains("requires a 32-bit process")) {
            return Cause.ABI_MISMATCH;
        }
        if (lower.contains("outofmemoryerror") || lower.contains("out of memory")
                || lower.contains("failed to allocate")) {
            return Cause.OUT_OF_MEMORY;
        }
        if (lower.contains("minecraft not found") || lower.contains("no minecraft package")) {
            return Cause.GAME_NOT_FOUND;
        }
        // A present-but-unusable library: the file opens, but a symbol it references is absent, so
        // the extracted cache does not match the build that is loading it. That is what a stale
        // (or half-replaced) extraction looks like at dlopen time, and it is the case Repair fixes.
        //
        // This is tested before the game-library branch because a symbol failure names the library
        // that *references* the missing symbol, and that library is libminecraftpe.so: matched by
        // the branch below, it would be reported as "cannot open libminecraftpe.so" when the file
        // is in fact present. It is also tested before the generic dlopen branch, whose pattern it
        // contains.
        if (lower.contains("cannot locate symbol")
                || lower.contains("undefined symbol")
                || lower.contains("cannot find symbol")) {
            return Cause.STALE_NATIVE_LIBS;
        }
        // The game's own library is the one whose absence means "this install cannot be launched",
        // which is the Google Play / incomplete-package case. It is tested before the generic
        // library test so it is never downgraded to "a dependency is missing".
        if (lower.contains("libminecraftpe") || lower.contains("minecraftpe.so")) {
            if (lower.contains("dlopen") || lower.contains("not found")
                    || lower.contains("cannot open") || lower.contains("no such file")) {
                return Cause.MISSING_GAME_LIBRARY;
            }
        }
        if (lower.contains("no implementation found")
                || lower.contains("unsatisfiedlinkerror") && lower.contains("mod")) {
            return Cause.MOD_FAILED;
        }
        if (lower.contains("dlopen failed") || lower.contains("cannot open")
                || lower.contains("library not found") || lower.contains(".so not found")) {
            return Cause.MISSING_DEPENDENCY;
        }
        if (lower.contains("unsatisfiedlinkerror")) {
            // An unresolved link with no library named is the game's own library far more often
            // than anything else in this launcher, so it takes the game-library message rather
            // than the vague fallback.
            return Cause.MISSING_GAME_LIBRARY;
        }
        return Cause.UNKNOWN;
    }

    /** Classifies a throwable, following its cause chain to the root. */
    public static Cause classify(Throwable error) {
        if (error == null) return Cause.UNKNOWN;
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof OutOfMemoryError) return Cause.OUT_OF_MEMORY;
        if (root instanceof UnsatisfiedLinkError) {
            Cause byText = classifyText(root.getMessage());
            // A link error whose text said nothing recognisable is still a link error; the game's
            // library is the likeliest subject, and "unknown" would tell the user nothing.
            return byText == Cause.UNKNOWN ? Cause.MISSING_GAME_LIBRARY : byText;
        }
        Cause byText = classifyText(root.getMessage());
        if (byText != Cause.UNKNOWN) return byText;
        return classifyText(error.toString());
    }

    /** The dialog title for a cause. */
    public static int titleRes(Cause cause) {
        if (cause == null) return R.string.launch_error_title_unknown;
        switch (cause) {
            case MISSING_GAME_LIBRARY:
                return R.string.launch_failure_title_play_store;
            case ABI_MISMATCH:
                return R.string.launch_failure_title_abi;
            case MISSING_DEPENDENCY:
                return R.string.launch_error_title_missing_dependency;
            case STALE_NATIVE_LIBS:
                return R.string.launch_error_title_stale_libs;
            case GAME_NOT_FOUND:
                return R.string.minecraft_not_installed_title;
            case MOD_FAILED:
                return R.string.launch_error_title_mod_failed;
            case OUT_OF_MEMORY:
                return R.string.launch_error_title_out_of_memory;
            default:
                return R.string.launch_error_title_unknown;
        }
    }

    /** The dialog message for a cause. */
    public static int messageRes(Cause cause) {
        if (cause == null) return R.string.launch_error_message_unknown;
        switch (cause) {
            case MISSING_GAME_LIBRARY:
                return R.string.launch_error_message_missing_game_library;
            case ABI_MISMATCH:
                return R.string.launch_failure_message_abi;
            case MISSING_DEPENDENCY:
                return R.string.launch_error_message_missing_dependency;
            case STALE_NATIVE_LIBS:
                return R.string.launch_error_message_stale_libs;
            case GAME_NOT_FOUND:
                return R.string.minecraft_not_installed_message;
            case MOD_FAILED:
                return R.string.launch_error_message_mod_failed;
            case OUT_OF_MEMORY:
                return R.string.launch_error_message_out_of_memory;
            default:
                return R.string.launch_error_message_unknown;
        }
    }

    /** A stable, non-translatable key for the cause, for a bug report to quote. */
    public static String key(Cause cause) {
        return cause == null ? "unknown" : cause.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The inverse of {@link #key}: the cause a stored key names, or null when it names nothing.
     *
     * <p>Null rather than {@link Cause#UNKNOWN} on purpose. A stored report that predates the key
     * carries no {@code Cause:} line at all, and returning {@code UNKNOWN} for it would stop the
     * reader from re-deriving the cause from the message text — a report that says
     * {@code dlopen failed: library "libminecraftpe.so" not found} must still be classified as the
     * game-library case when it is read back.
     */
    public static Cause fromKey(String key) {
        if (key == null) return null;
        String trimmed = key.trim();
        if (trimmed.isEmpty()) return null;
        for (Cause candidate : Cause.values()) {
            if (candidate.name().equalsIgnoreCase(trimmed)) return candidate;
        }
        return null;
    }

    /**
     * A one-line cause label for the crash screen, e.g. {@code "MISSING_GAME_LIBRARY"}.
     *
     * <p>Deliberately the enum name rather than a sentence: the crash screen shows the raw cause
     * beside the human copy so a report can quote the exact category.
     */
    public static String label(Cause cause) {
        return cause == null ? Cause.UNKNOWN.name() : cause.name();
    }
}
