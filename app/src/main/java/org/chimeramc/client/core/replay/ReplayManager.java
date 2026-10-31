package org.chimeramc.client.core.replay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import org.chimeramc.client.util.AppExecutors;
import org.chimeramc.client.util.MainThread;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Replay system's state machine and the one entry point the UI talks to.
 *
 * <p>It owns the permission round-trip, starts and stops {@link ReplayCaptureService}, tracks the
 * elapsed recording time, applies the storage cap after a clip lands, and fans state changes out to
 * whatever Replay UI is open. Every host shares it — the Replay page of the Mod Menu is
 * one feature with one backend, so there is a single recorder state, not one per screen.
 *
 * <p>State lives on the main thread; a listener is invoked on the main thread. The class is a
 * process singleton so a screen that opens mid-recording immediately sees the running state.
 */
public final class ReplayManager {

    private static final String TAG = "ReplayManager";

    /** Request code for the screen-capture consent dialog. */
    public static final int REQUEST_CODE = 0x52A1;

    /** The recording state the UI renders. */
    public enum State {
        IDLE,
        RECORDING,
        SAVING
    }

    /** Receives state changes; every method runs on the main thread. */
    public interface Listener {
        void onReplayStateChanged(State state, long elapsedMs, ReplayQuality.Profile profile);

        /**
         * A capture ended without producing a clip.
         *
         * <p>Distinct from a state change on purpose: a failure drops the state straight back to
         * {@link State#IDLE}, which is byte-for-byte the state the screen is in before the user
         * ever pressed Record. Without this callback the panel could not tell the two apart, so a
         * recording that failed on the first frame looked like the press being ignored, and the
         * "recording could not start" message had no caller at all.
         */
        default void onReplayCaptureFailed() {
        }
    }

    private static volatile Context holderContext;

    /** The singleton, or null before {@link #init} has run. Never throws. */
    public static ReplayManager get() {
        ReplayManager local = instance;
        if (local != null) return local;
        Context context = holderContext;
        if (context == null) return null;
        synchronized (ReplayManager.class) {
            if (instance == null) {
                instance = new ReplayManager(context);
            }
            return instance;
        }
    }

    /** Called once from Application so the singleton can be built before any screen needs it. */
    public static void init(Context context) {
        holderContext = context.getApplicationContext();
        get();
    }

    /** The launcher activity a capture notification should open. */
    public static Class<?> launcherActivityClass() {
        return org.chimeramc.client.ui.activities.MainActivity.class;
    }

    private static volatile ReplayManager instance;

    private final Context appContext;
    private final Handler mainHandler = MainThread.handler();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ReplayClipRepository repository;

    private State state = State.IDLE;
    private long startedAtMs;
    private ReplayQuality.Profile profile;
    private ReplayHighlightTrigger trigger;
    private Activity foregroundActivity;
    private ReplayMetadataOverlay metadataOverlay;

    private ReplayManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.repository = new ReplayClipRepository(appContext);
    }

    public State state() {
        return state;
    }

    public boolean isRecording() {
        return state == State.RECORDING;
    }

    /** Elapsed recording time, or zero when idle. */
    public long elapsedMs() {
        return state == State.RECORDING ? Math.max(0L, System.currentTimeMillis() - startedAtMs) : 0L;
    }

    public ReplayQuality.Profile profile() {
        return profile;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Keeps the activity that should receive the consent dialog result. */
    public void setForegroundActivity(Activity activity) {
        this.foregroundActivity = activity;
    }

    /**
     * Asks for screen-capture consent, then starts the recorder.
     *
     * <p>The consent dialog is a platform activity result, so the caller must forward
     * {@code onActivityResult} to {@link #onActivityResult}. The activity is remembered here so the
     * Replay tab can request consent without holding its own copy.
     */
    public void requestStart(Activity activity) {
        if (state != State.IDLE) return;
        setForegroundActivity(activity);
        try {
            MediaProjectionManager manager = (MediaProjectionManager)
                    activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                return;
            }
            activity.startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to request screen capture", t);
        }
    }

    /**
     * Handles the consent result.
     *
     * @return true when this manager consumed the result
     */
    public static boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) return false;
        ReplayManager manager = get();
        if (resultCode == Activity.RESULT_OK && data != null) {
            Context context = manager.foregroundActivity != null
                    ? manager.foregroundActivity : holderContext;
            ReplayCaptureService.start(context, resultCode, data);
        }
        return true;
    }

    /** Stops a running capture. */
    public void stop() {
        if (state != State.RECORDING) return;
        ReplayCaptureService.stop(appContext);
    }

    // ---------------------------------------------------------------------------------------------
    // Capture callbacks (called by the service)
    // ---------------------------------------------------------------------------------------------

    public void onRecordingStarted(File file, ReplayQuality.Profile profile, long startedAtMs) {
        this.startedAtMs = startedAtMs;
        this.profile = profile;
        this.state = State.RECORDING;
        this.trigger = ReplaySettings.get(appContext).newTrigger();
        attachMetadataOverlay(startedAtMs);
        notifyState();
    }

    /**
     * Shows the burn-in layer while recording.
     *
     * <p>A full-screen capture records whatever is on screen, so the metadata the spec asks to burn
     * in has to be drawn during the capture rather than encoded afterwards. The layer attaches to
     * the activity that requested the recording and is removed when the capture ends.
     */
    private void attachMetadataOverlay(long startedAtMs) {
        ReplaySettings settings = ReplaySettings.get(appContext);
        if (!settings.burnMetadata() && !settings.watermark()) return;
        if (foregroundActivity == null) return;
        try {
            metadataOverlay = new ReplayMetadataOverlay(foregroundActivity);
            metadataOverlay.attach(settings.burnMetadata(), settings.watermark(), startedAtMs);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to attach the replay metadata layer", t);
            metadataOverlay = null;
        }
    }

    private void detachMetadataOverlay() {
        if (metadataOverlay != null) {
            try {
                metadataOverlay.detach();
            } catch (Throwable ignored) {
            }
            metadataOverlay = null;
        }
    }

    public void onRecordingFinished(File file, long durationMs) {
        detachMetadataOverlay();
        this.state = State.SAVING;
        notifyState();
        final ReplayQuality.Profile finishedProfile = profile;
        // The sidecar and the storage prune are file work; keep them off the main thread.
        AppExecutors.run(AppExecutors.Kind.DISK, "replay-finalize", () -> {
            try {
                writeSidecar(file, durationMs, finishedProfile);
                pruneIfNeeded();
            } catch (Throwable t) {
                Log.w(TAG, "Post-recording bookkeeping failed", t);
            } finally {
                mainHandler.post(() -> {
                    state = State.IDLE;
                    profile = null;
                    notifyState();
                });
            }
        });
    }

    public void onRecordingFailed() {
        detachMetadataOverlay();
        state = State.IDLE;
        profile = null;
        notifyState();
        notifyCaptureFailed();
    }

    private void notifyCaptureFailed() {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onReplayCaptureFailed();
                } catch (Throwable t) {
                    Log.w(TAG, "Replay listener threw", t);
                }
            }
        });
    }

    private void writeSidecar(File file, long durationMs, ReplayQuality.Profile profile) {
        ReplaySidecar sidecar = ReplaySidecar.of(System.currentTimeMillis(),
                "", "", "");
        sidecar.set("durationMs", String.valueOf(durationMs));
        sidecar.set("resolution", profile == null ? "" : profile.label());
        ReplayStorage.writeSidecar(file, sidecar.serialize());
    }

    /** Applies the storage cap: prunes the oldest non-favorited clips so the new one fits. */
    private void pruneIfNeeded() {
        ReplaySettings settings = ReplaySettings.get(appContext);
        long cap = settings.storageCapBytes();
        List<ReplayClip> clips = repository.scan();
        long used = ReplayStorage.usageBytes(appContext);
        List<ReplayClip> prunable =
                ReplayStoragePolicy.selectPrunable(clips, used, cap, 0L);
        repository.deleteAll(prunable);
    }

    // ---------------------------------------------------------------------------------------------
    // Highlight triggers (Tier 4)
    // ---------------------------------------------------------------------------------------------

    public void onDeath() {
        fireHighlight(ReplayHighlightTrigger.Event.death());
    }

    public void onKillStreak(int streak) {
        fireHighlight(ReplayHighlightTrigger.Event.killStreak(streak));
    }

    public void onCombo(int hits) {
        fireHighlight(ReplayHighlightTrigger.Event.combo(hits));
    }

    /**
     * Marks a highlight on the most recent clip when a trigger fires.
     *
     * <p>This build has no rolling ring buffer of encoded frames, so a trigger flags the clip the
     * recorder is currently writing (or the newest clip) rather than slicing the last 30 seconds
     * out of a buffer. That limitation is stated in the UI's scope note instead of being implied
     * away; the trigger decision itself is real and unit-tested.
     */
    private void fireHighlight(ReplayHighlightTrigger.Event event) {
        ReplayHighlightTrigger local = trigger;
        if (local == null) return;
        if (!local.shouldFire(event, SystemClock.uptimeMillis())) return;
        mainHandler.post(() -> {
            List<ReplayClip> clips = repository.scan();
            if (clips.isEmpty()) return;
            ReplayClip newest = clips.get(0);
            newest.setHighlight(true);
            repository.updateSidecar(newest);
        });
    }

    private void notifyState() {
        final State snapshotState = state;
        final long elapsed = elapsedMs();
        final ReplayQuality.Profile snapshotProfile = profile;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onReplayStateChanged(snapshotState, elapsed, snapshotProfile);
                } catch (Throwable t) {
                    Log.w(TAG, "Replay listener threw", t);
                }
            }
        });
    }
}
