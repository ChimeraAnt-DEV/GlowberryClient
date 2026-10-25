package org.chimeramc.client.launcher.core.minecraft;

import android.content.Context;

import java.io.File;
import java.util.Locale;

/**
 * The record of a launch that died inside the game activity, so the next launcher screen can name
 * the cause instead of leaving the user with a vanishing app.
 *
 * <p>{@code MinecraftActivity} runs the game in its own process and tears the launcher down to
 * restart it, so a failure there cannot show a dialog: by the time it knows, the Activity that
 * would own the dialog is gone. The failure was written to
 * {@code getExternalFilesDir(null)/last_launch_failure.txt} and then never read by anything, which
 * made the file a dead end — the user watched the app disappear and the cause sat on disk
 * unread. This class is the missing reader: it writes the report with its classified cause,
 * {@link #consumePending} hands it to the launcher once, and {@link #read} lets the Diagnostics
 * screen report it even after the pending copy has been acknowledged.
 *
 * <p>Parsing and serialisation take and return a {@code String}, never a device, so the format and
 * the cause mapping are unit-testable in a plain JVM. The Android-facing methods are thin wrappers
 * over those.
 */
public final class LastLaunchFailure {

    /** Kept as the historical name so a report written by an older build is still found. */
    public static final String FILE_NAME = "last_launch_failure.txt";

    private static final String PREFIX_TIME = "Time: ";
    private static final String PREFIX_MESSAGE = "Message: ";
    private static final String PREFIX_TYPE = "Type: ";
    private static final String PREFIX_CAUSE = "Cause: ";
    private static final String PREFIX_ANNOUNCED = "Announced: ";
    private static final String PREFIX_STACK = "Stack:";

    private LastLaunchFailure() {
    }

    /**
     * One recorded failure.
     *
     * <p>{@code cause} is stored as the stable {@link LaunchErrorDiagnosis.Cause} key rather than
     * re-derived on read, so a rule change cannot silently reclassify a failure the user already
     * saw; {@link #classify} falls back to re-deriving it for a report written before the key
     * existed.
     */
    public static final class Report {
        /** The message line, or the whole text when the report predates the structured format. */
        public final String message;
        /** The throwable's class name, when the report carries one. */
        public final String type;
        /** The stable cause key, e.g. {@code missing_game_library}. */
        public final String causeKey;
        /** The full file text, so a bug report can quote what was actually recorded. */
        public final String rawText;
        /**
         * True when the failure was already shown on the loading screen before the report was
         * written, so the launcher must not show it a second time.
         */
        public final boolean announced;
        /** When the report was last written; the file's own timestamp, never a parsed date string. */
        public final long recordedAtMs;

        Report(String message, String type, String causeKey, String rawText,
               boolean announced, long recordedAtMs) {
            this.message = message == null ? "" : message;
            this.type = type == null ? "" : type;
            this.causeKey = causeKey == null ? "" : causeKey;
            this.rawText = rawText == null ? "" : rawText;
            this.announced = announced;
            this.recordedAtMs = recordedAtMs;
        }

        /** The named cause, re-derived when the report predates the stored key. */
        public LaunchErrorDiagnosis.Cause cause() {
            LaunchErrorDiagnosis.Cause fromKey = LaunchErrorDiagnosis.fromKey(causeKey);
            if (fromKey != null) return fromKey;
            LaunchErrorDiagnosis.Cause fromText = LaunchErrorDiagnosis.classifyText(message);
            if (fromText != LaunchErrorDiagnosis.Cause.UNKNOWN) return fromText;
            return LaunchErrorDiagnosis.classifyText(rawText);
        }

        /** True when this report is worth showing the user: it has content and was not announced. */
        public boolean isShowable() {
            if (announced) return false;
            return !rawText.trim().isEmpty() || !message.trim().isEmpty();
        }
    }

    // ------------------------------------------------------------------ pure format

    /**
     * Serialises a failure for storage.
     *
     * <p>The {@code Cause:} and {@code Announced:} lines are the additions over the original
     * three-line format; a reader that does not know them ignores them, and this reader tolerates
     * their absence so an old file still parses.
     */
    public static String serialize(Throwable throwable, boolean announced, long nowMs) {
        String message = throwable == null ? "unknown" : String.valueOf(throwable.getMessage());
        String type = throwable == null ? "unknown" : throwable.getClass().getName();
        String stack = throwable == null ? "" : throwableToString(throwable);
        LaunchErrorDiagnosis.Cause cause = LaunchErrorDiagnosis.classify(throwable);
        StringBuilder sb = new StringBuilder();
        sb.append(PREFIX_TIME).append(new java.util.Date(nowMs)).append('\n');
        sb.append(PREFIX_MESSAGE).append(message).append('\n');
        sb.append(PREFIX_TYPE).append(type).append('\n');
        sb.append(PREFIX_CAUSE).append(LaunchErrorDiagnosis.key(cause)).append('\n');
        sb.append(PREFIX_ANNOUNCED).append(announced).append('\n');
        sb.append(PREFIX_STACK).append('\n').append(stack);
        return sb.toString();
    }

    /**
     * Reads a stored report.
     *
     * <p>Never throws: a report that cannot be understood reads as an unclassified failure with the
     * whole text kept, because a garbled report is still more useful than silence.
     */
    public static Report parse(String text, long recordedAtMs) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;

        String message = null;
        String type = null;
        String causeKey = null;
        boolean announced = false;
        StringBuilder stack = new StringBuilder();
        boolean inStack = false;

        for (String line : text.split("\n", -1)) {
            if (inStack) {
                stack.append(line).append('\n');
                continue;
            }
            if (line.startsWith(PREFIX_MESSAGE)) {
                message = line.substring(PREFIX_MESSAGE.length());
            } else if (line.startsWith(PREFIX_TYPE)) {
                type = line.substring(PREFIX_TYPE.length());
            } else if (line.startsWith(PREFIX_CAUSE)) {
                causeKey = line.substring(PREFIX_CAUSE.length());
            } else if (line.startsWith(PREFIX_ANNOUNCED)) {
                announced = Boolean.parseBoolean(
                        line.substring(PREFIX_ANNOUNCED.length()).trim());
            } else if (line.startsWith(PREFIX_STACK)) {
                inStack = true;
            }
        }
        // An unstructured report (an older build, or a hand-written file) keeps its text as the
        // message so the cause classifier still has something to read.
        if (message == null) {
            message = trimmed;
        }
        return new Report(message, type, causeKey, text, announced, recordedAtMs);
    }

    private static String throwableToString(Throwable throwable) {
        try {
            java.io.StringWriter writer = new java.io.StringWriter();
            throwable.printStackTrace(new java.io.PrintWriter(writer));
            return writer.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * The raw failure text beneath the cause-specific copy.
     *
     * <p>Only the detail half: the copy itself comes from
     * {@link LaunchErrorDiagnosis#messageRes(LaunchErrorDiagnosis.Cause)}, so the same failure reads
     * identically whether it is reported by the loading screen, the launch dialog or this stored
     * report.
     */
    public static String detailOf(Report report) {
        return report == null ? "" : report.message.trim();
    }

    /** One line for the Diagnostics report, e.g. {@code missing_game_library - dlopen failed ...}. */
    public static String describe(Report report) {
        if (report == null) return "";
        LaunchErrorDiagnosis.Cause cause = report.cause();
        String detail = report.message.trim().replace('\n', ' ');
        if (detail.isEmpty()) return LaunchErrorDiagnosis.key(cause);
        return LaunchErrorDiagnosis.key(cause) + " - " + detail;
    }

    // ------------------------------------------------------------------ storage

    public static File file(Context context) {
        if (context == null) return null;
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        if (dir == null) return null;
        return new File(dir, FILE_NAME);
    }

    /** Writes the report, replacing any previous one. Never throws. */
    public static void write(Context context, Throwable throwable, boolean announced) {
        File target = file(context);
        if (target == null) return;
        try {
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            // Written with a plain File write rather than the shared JsonIOUtils so a launch failure
            // cannot itself depend on another component's IO helper.
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(target, false)) {
                out.write(serialize(throwable, announced, System.currentTimeMillis())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    /** The most recent report, or null when none is recorded. Never throws. */
    public static Report read(Context context) {
        File target = file(context);
        if (target == null || !target.isFile()) return null;
        try {
            String text = new String(java.nio.file.Files.readAllBytes(target.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            return parse(text, target.lastModified());
        } catch (Throwable ignored) {
            // A read that fails (a deleted file, a permission change) must not be reported as a
            // failure the user has to act on; the launcher simply has nothing to show.
            return null;
        }
    }

    /**
     * Reads and clears the report when it still needs to be shown, so a launch failure is announced
     * exactly once.
     *
     * <p>Returns null when there is nothing to show — no report, or one the loading screen already
     * named. The file is left in place in that case so Diagnostics can still report it.
     */
    public static Report consumePending(Context context) {
        Report report = read(context);
        if (report == null) return null;
        if (!report.isShowable()) return null;
        clear(context);
        return report;
    }

    /** Removes the recorded report. Never throws. */
    public static void clear(Context context) {
        File target = file(context);
        if (target == null || !target.isFile()) return;
        try {
            if (!target.delete()) {
                target.deleteOnExit();
            }
        } catch (Throwable ignored) {
        }
    }

    /** The stable key for a report, or {@code none} when there is none. */
    public static String keyOf(Report report) {
        return report == null ? "none" : LaunchErrorDiagnosis.key(report.cause());
    }

    /** Formats a timestamp for a report line; {@code -1} reads as unknown. */
    public static String formatTimestamp(long recordedAtMs) {
        if (recordedAtMs <= 0L) return "unknown";
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(new java.util.Date(recordedAtMs));
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
