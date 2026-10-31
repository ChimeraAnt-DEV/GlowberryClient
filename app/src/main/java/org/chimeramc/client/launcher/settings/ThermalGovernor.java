package org.chimeramc.client.settings;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import org.chimeramc.client.util.ThermalPolicy;

/**
 * Watches the device's thermal status and pauses non-critical background work while the
 * device is under real pressure, so CPU cycles stay with the game's frame loop.
 *
 * What this does and does not do is worth being precise about. It cannot cool a device, and
 * it has no channel into the game's own renderer. It reduces load the launcher itself owns:
 * news polling, changelog checks, DNS warm-up. User-initiated work (an explicit download)
 * is never paused, because a person waiting on a progress bar is not background work.
 *
 * The platform thermal API only exists from API 29, so every reference to it lives in
 * {@link Api29}. That separation is load-bearing, not tidiness: a static field whose type is
 * an API-29 class makes this class fail to initialize on API 28, because the nested interface
 * cannot be resolved while running the static initializer. An {@code SDK_INT} check inside a
 * method cannot prevent that, since class initialization happens before any method body runs.
 */
public final class ThermalGovernor {

    private static volatile Context sAppContext;
    private static volatile int sSeverity = ThermalPolicy.SEVERITY_NONE;

    private ThermalGovernor() {
    }

    public static void init(Context context) {
        if (context == null) return;
        sAppContext = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Api29.startListener(sAppContext);
        }
    }

    /**
     * Current thermal severity, re-read on each call.
     *
     * Re-reading rather than caching a pushed value means the launcher reacts even if it
     * missed a listener callback, which happens when the process is backgrounded during the
     * transition. The call is cheap.
     */
    public static int severity() {
        Context context = sAppContext;
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ThermalPolicy.SEVERITY_NONE;
        }
        try {
            int severity = ThermalPolicy.severityFor(Api29.currentStatus(context));
            sSeverity = severity;
            return severity;
        } catch (Throwable ignored) {
            return sSeverity;
        }
    }

    /** True when speculative work such as DNS warm-up should be skipped. */
    public static boolean shouldPauseSpeculativeWork() {
        return !ThermalPolicy.allowsSpeculativeWork(severity());
    }

    /** True when non-critical background polling should be paused. */
    public static boolean shouldPauseBackgroundWork() {
        return !ThermalPolicy.allowsBackgroundWork(severity());
    }

    /**
     * Everything that touches the API-29 thermal API. Holds a listener so the callback stays
     * wired; the reading itself is pulled on demand by {@link #severity()}.
     *
     * Annotated so the API-level requirement is stated in one place and Lint can verify every
     * call site keeps its {@code SDK_INT} guard.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private static final class Api29 {

        private static final android.os.Handler MAIN =
                org.chimeramc.client.util.MainThread.handler();

        private static final android.os.PowerManager.OnThermalStatusChangedListener LISTENER =
                status -> { };

        private static volatile boolean sRegistered;

        private Api29() {
        }

        static void startListener(Context context) {
            if (sRegistered) return;
            try {
                android.os.PowerManager powerManager =
                        (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (powerManager == null) return;
                powerManager.addThermalStatusListener(MAIN::post, LISTENER);
                sRegistered = true;
            } catch (Throwable ignored) {
                // No thermal reporting available; severity stays cool and nothing is held back.
            }
        }

        static int currentStatus(Context context) {
            android.os.PowerManager powerManager =
                    (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) return ThermalPolicy.STATUS_NONE;
            return powerManager.getCurrentThermalStatus();
        }
    }
}
