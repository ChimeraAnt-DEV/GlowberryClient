package org.chimeramc.client.core.crash;

/**
 * Why the previous process ended, from the platform's own record (item 167).
 *
 * <p>On API 30 and up {@code ActivityManager.getHistoricalProcessExitReasons} reports a reason code
 * for the last exit, which is strictly better than guessing from a saved log tail: a native crash, a
 * low-memory kill and a user swiping the app away all leave very different traces but can look the
 * same in a truncated logcat. Below API 30 there is no such record, so the caller falls back to the
 * saved log - this enum only names what the platform reported.
 *
 * <p>Pure and Android-free: the codes are plain ints (the platform's {@code ApplicationExitInfo}
 * constants, which are safe to copy - a constant is inlined by javac and creates no class
 * reference), so the mapping is unit-tested without a device. Keeping the numbers here rather than
 * referencing {@code ApplicationExitInfo.REASON_*} directly is also what keeps the min-SDK 28 class
 * initialization rule satisfied: touching that class from a reachable field or method body would
 * crash the process on Android 9.
 */
public enum ExitReason {

    /** The process called exit on itself (a clean shutdown, or a deliberate restart). */
    EXIT_SELF(1, "Exited on its own", false),
    /** The process was killed by a signal. */
    SIGNALED(2, "Killed by a signal", true),
    /** The system reclaimed the process under memory pressure. */
    LOW_MEMORY(3, "Stopped by the system: low memory", false),
    /** An uncaught Java exception took the process down. */
    CRASH(4, "Java crash (uncaught exception)", true),
    /** A native (JNI / C++) fault took the process down. */
    CRASH_NATIVE(5, "Native crash", true),
    /** The app stopped responding to input for long enough that the system killed it. */
    ANR(6, "Application not responding (ANR)", true),
    /** The app failed during initialization. */
    INITIALIZATION_FAILURE(7, "Failed to initialize", true),
    /** A runtime permission change forced a restart. */
    PERMISSION_CHANGE(8, "Restarted after a permission change", false),
    /** The system killed the app for using excessive resources. */
    EXCESSIVE_RESOURCE_USAGE(9, "Stopped by the system: excessive resource use", false),
    /** The user asked for the app to be closed (for example, from Recents). */
    USER_REQUESTED(10, "Closed by the user", false),
    /** The user stopped the app from Settings. */
    USER_STOPPED(11, "Stopped by the user", false),
    /** A process the app depended on died, so the app was taken down with it. */
    DEPENDENCY_DIED(12, "A dependency process died", false),
    /** Any other reason the platform reports. */
    OTHER(13, "Other reason", false),
    /** The system put the app into a cached/frozen state. */
    FREEZER(14, "Frozen by the system", false),
    /** The app's package state changed (an install, update or clear-data). */
    PACKAGE_STATE_CHANGE(15, "Package state changed", false),
    /** The app was updated while it was running. */
    PACKAGE_UPDATED(16, "Updated while running", false),
    /** The platform reported a code this build does not know. */
    UNKNOWN(-1, "Unknown", false);

    private final int code;
    private final String label;
    private final boolean crash;

    ExitReason(int code, String label, boolean crash) {
        this.code = code;
        this.label = label;
        this.crash = crash;
    }

    /** The platform's reason code. */
    public int code() {
        return code;
    }

    /** A plain-language name for the reason. */
    public String label() {
        return label;
    }

    /** True when the platform classified the exit as a fault (crash, native crash, ANR, init). */
    public boolean isCrash() {
        return crash;
    }

    /**
     * Maps a platform reason code to this enum.
     *
     * <p>An unrecognised code (a newer platform, or a value this build does not list) reads as
     * {@link #UNKNOWN}, never as a crash: a wrong "it crashed" is worse than an honest "unknown",
     * because it sends the user looking for a bug that is not there.
     */
    public static ExitReason fromCode(int code) {
        for (ExitReason reason : values()) {
            if (reason != UNKNOWN && reason.code == code) {
                return reason;
            }
        }
        return UNKNOWN;
    }

    /** Whether there is a reason worth reporting; an unknown code is not. */
    public static boolean isReportable(ExitReason reason) {
        return reason != null && reason != UNKNOWN;
    }
}
