package org.chimeramc.client.launcher.ui.splash;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.core.versions.VersionManager;
import org.chimeramc.client.preloader.PreloaderSignatureRulesManager;
import org.chimeramc.client.util.LauncherStorage;
import org.chimeramc.client.util.AppExecutors;
import org.chimeramc.client.util.MainThread;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the launcher's real start-up work and reports how far along it is, so the splash loader
 * advances on actual milestones instead of a decorative timer.
 *
 * <p>The steps are the warm-up the launcher needs anyway and previously fired-and-forgot from the
 * splash:
 *
 * <ol>
 *   <li>Ensure the app storage root is usable.</li>
 *   <li>Refresh the preloader signature rules (the slowest step; network, but it degrades to the
 *       bundled snapshot).</li>
 *   <li>Load the installed/custom version index the home screen reads.</li>
 *   <li>Open the inbuilt-mod preferences the home screen reads for its stat.</li>
 * </ol>
 *
 * <p><b>Each step is best-effort.</b> A failure advances the counter anyway rather than wedging
 * the splash: a storage error or an offline rules refresh must not stop the user reaching the
 * launcher, and the launcher itself already handles a missing version index. The progress number
 * is therefore "steps attempted", which is exactly as honest as a loader can be about work that
 * is allowed to fail.
 *
 * <p>The work runs on a background thread and the listener is called back on the main thread, so
 * the loader can be touched without a lock.
 */
public final class SplashInit {

    /** Called on the main thread as each step completes. */
    public interface Listener {
        /** @param fraction {@code 0..1} of the steps attempted so far. */
        void onProgress(float fraction);

        /** Every step has been attempted; the launcher may proceed. */
        void onComplete();
    }

    /** The number of warm-up steps; the fraction is {@code done/steps}. */
    private static final int STEPS = 4;

    private SplashInit() {
    }

    /**
     * Starts the warm-up. Returns immediately; progress arrives on {@code listener}'s thread.
     *
     * <p>Every step is guarded, so no failure from storage, network or prefs can leave the splash
     * waiting for a callback that never comes.
     */
    public static void run(Context context, Listener listener) {
        final Context appContext = context.getApplicationContext();
        final Handler main = MainThread.handler();
        final AtomicInteger done = new AtomicInteger(0);

        Thread worker = AppExecutors.thread("splash-init", () -> {
            step(() -> LauncherStorage.ensureNoMedia(appContext), main, done, listener);
            step(() -> PreloaderSignatureRulesManager.refreshOnLauncherStart(appContext),
                    main, done, listener);
            step(() -> VersionManager.get(appContext).loadAllVersions(), main, done, listener);
            step(() -> InbuiltModManager.getInstance(appContext), main, done, listener);

            main.post(() -> {
                if (listener != null) listener.onComplete();
            });
        });
        worker.start();
    }

    /** Runs one step, swallowing failure, then reports the new fraction on the main thread. */
    private static void step(Runnable work, Handler main, AtomicInteger done, Listener listener) {
        try {
            work.run();
        } catch (Throwable ignored) {
            // Best-effort: a failed warm-up must still let the launcher open.
        }
        final int finished = done.incrementAndGet();
        final float fraction = finished / (float) STEPS;
        main.post(() -> {
            if (listener != null) listener.onProgress(fraction);
        });
    }
}
