package org.chimeramc.client.util;

import android.content.Context;

import org.chimeramc.client.core.minecraft.LaunchLog;

/**
 * The one place a caught exception is reported.
 *
 * <p>Before this, failures were either swallowed by an empty {@code catch} or printed with
 * {@code printStackTrace()}, which writes to a stream the app cannot read. Both end the same way: the
 * user sees a feature do nothing, and nothing anywhere says why. This routes every report to two
 * places that survive that — logcat, which the in-app viewer already tails by pid, and the launch
 * log file, which is durable and is what the viewer's copy/share actions read.
 *
 * <p>The report is deliberately best-effort. A diagnostic that throws while reporting a failure is
 * worse than the failure, and off-device (a JVM unit test) neither {@code android.util.Log} nor the
 * launch log exists, so every path is guarded.
 */
public final class AppLog {

    /** Application context, so a held reference cannot leak an Activity. Null until installed. */
    private static volatile Context appContext;

    private AppLog() {
    }

    /**
     * Records the application context so reports also reach the durable launch log.
     *
     * <p>Optional: without it, reports still reach logcat, which is what the in-app viewer tails.
     */
    public static void install(Context context) {
        if (context == null) return;
        try {
            appContext = context.getApplicationContext();
        } catch (Throwable ignored) {
            appContext = null;
        }
    }

    /** Reports a recoverable failure. */
    public static void warn(String tag, String message, Throwable cause) {
        log('W', tag, message, cause);
    }

    /** Reports a failure that broke the operation in progress. */
    public static void error(String tag, String message, Throwable cause) {
        log('E', tag, message, cause);
    }

    /**
     * Reports a failure with no exception, for a path that only has a condition to describe.
     *
     * <p>Exists so "this cannot happen" branches still say what happened rather than being left
     * empty; an empty catch is the state this class was written to remove.
     */
    public static void warn(String tag, String message) {
        log('W', tag, message, null);
    }

    /**
     * The one formatting rule: a report reads as {@code message: ExceptionClass: detail}.
     *
     * <p>The class name is included because "null" or an empty message is a common case and the class
     * is then the only thing that identifies the failure. Pure and package-visible so
     * {@code AppLogTest} pins it.
     */
    static String describe(String message, Throwable cause) {
        if (cause == null) return message;
        String detail = cause.getMessage();
        String type = cause.getClass().getSimpleName();
        if (detail == null || detail.isEmpty()) return message + ": " + type;
        return message + ": " + type + ": " + detail;
    }

    /**
     * Reports a failure at most once per process, keyed by {@code key}.
     *
     * <p>For the cases where the same operation is attempted many times and each attempt can fail
     * the same way: tinting every switch in a screen, trimming a log after every append, writing
     * per-instance metadata on each launch. Logging each one turns a single cause into hundreds of
     * identical lines and buries everything else, which is how a real failure gets missed — so the
     * first report is kept and the rest are dropped.
     */
    public static void warnOnce(String tag, String key, String message, Throwable cause) {
        if (!firstReport(key)) return;
        log('W', tag, message, cause);
    }

    /** The keys already reported. Bounded by the number of distinct call sites. */
    private static final java.util.Set<String> reportedOnce =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Whether this key has not been reported yet, recording it if so.
     *
     * <p>Package-visible and pure so the once-only rule is unit-tested rather than assumed.
     */
    static boolean firstReport(String key) {
        return reportedOnce.add(key);
    }

    /** Clears the once-only memory. Tests only: a real process keeps the first report. */
    static void resetOnce() {
        reportedOnce.clear();
    }

    private static void log(char level, String tag, String message, Throwable cause) {
        String line = describe(message, cause);
        try {
            switch (level) {
                case 'E':
                    android.util.Log.e(tag, line, cause);
                    break;
                case 'W':
                    android.util.Log.w(tag, line, cause);
                    break;
                default:
                    android.util.Log.i(tag, line, cause);
                    break;
            }
        } catch (Throwable ignored) {
            // Off-device. The launch-log mirror below is the fallback.
        }
        Context context = appContext;
        if (context == null) return;
        try {
            LaunchLog.appendLogcat(context, level, tag, line);
        } catch (Throwable ignored) {
            // The log file is best-effort; never let reporting a failure cause one.
        }
    }
}
