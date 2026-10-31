package org.chimeramc.client.core.crash;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import java.util.List;

/**
 * Reads the platform's record of why the previous process ended (item 167).
 *
 * <p>{@code ActivityManager.getHistoricalProcessExitReasons} exists from API 30, so the call lives
 * in a nested {@link Api30} holder reached only behind an {@code SDK_INT} guard. That split is not
 * cosmetic: a static field or a lambda target whose type only exists above {@code minSdk} is
 * resolved while the enclosing class initializes, so referencing {@code ApplicationExitInfo} from a
 * reachable member would throw {@link NoClassDefFoundError} on Android 9 and 10 before any of this
 * ran. The holder keeps the new type unreachable below 30.
 *
 * <p>Below API 30 the caller falls back to the saved log tail ({@link CrashReportStore}), which is
 * what this build did before; the platform reason is strictly better when it is available because a
 * low-memory kill and a user swipe leave traces that are hard to tell apart in a truncated log.
 */
public final class ProcessExitReasons {

    /** The most recent exit worth showing. */
    public static final class LastExit {
        public final ExitReason reason;
        public final int status;
        public final long timestampMs;
        public final String description;

        LastExit(ExitReason reason, int status, long timestampMs, String description) {
            this.reason = reason;
            this.status = status;
            this.timestampMs = timestampMs;
            this.description = description == null ? "" : description;
        }

        /** True when there is a real, reportable exit. */
        public boolean isPresent() {
            return ExitReason.isReportable(reason);
        }
    }

    private ProcessExitReasons() {
    }

    /**
     * The most recent exit of this package, or null when the platform cannot report one (below API
     * 30, no record, or the call is refused).
     *
     * <p>Never throws: a diagnostics read must not be the thing that takes the process down.
     */
    public static LastExit read(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        try {
            return Api30.readLast(context.getApplicationContext());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A one-line summary for the crash screen, or an empty string when there is nothing to say.
     *
     * <p>Empty rather than a placeholder: the caller already shows the saved log, so an extra
     * "unknown" line would be noise on the platforms that cannot answer.
     */
    public static String describe(LastExit exit) {
        if (exit == null || !exit.isPresent()) {
            return "";
        }
        StringBuilder out = new StringBuilder(exit.reason.label());
        if (exit.status != 0) {
            out.append(" (status ").append(exit.status).append(')');
        }
        if (!exit.description.isEmpty()) {
            out.append(": ").append(exit.description);
        }
        return out.toString();
    }

    /**
     * The API 30+ half. Every reference to {@code ApplicationExitInfo} is confined here so the type
     * is never resolved on a device below 30.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private static final class Api30 {
        static LastExit readLast(Context context) {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return null;
            }
            return readFrom(manager, context.getPackageName());
        }

        private static LastExit readFrom(ActivityManager manager, String packageName) {
            List<android.app.ApplicationExitInfo> reasons =
                    manager.getHistoricalProcessExitReasons(packageName, 0, 1);
            if (reasons == null || reasons.isEmpty()) {
                return null;
            }
            android.app.ApplicationExitInfo info = reasons.get(0);
            if (info == null) {
                return null;
            }
            ExitReason reason = ExitReason.fromCode(info.getReason());
            return new LastExit(reason, info.getStatus(),
                    info.getTimestamp(), safeDescription(info));
        }

        private static String safeDescription(android.app.ApplicationExitInfo info) {
            try {
                CharSequence description = info.getDescription();
                return description == null ? "" : description.toString();
            } catch (Throwable t) {
                return "";
            }
        }
    }
}
