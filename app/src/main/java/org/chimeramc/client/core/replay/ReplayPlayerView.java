package org.chimeramc.client.core.replay;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.chimeramc.client.R;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.util.MainThread;

import java.io.File;

/**
 * The embedded clip player: a surface plus transport controls, living inside the Replay overlay.
 *
 * <p>Why a {@link SurfaceView} and {@link MediaPlayer} rather than an external player: the spec
 * requires watching a clip to stay in-game. The old path fired {@code ACTION_VIEW}, which handed
 * the file to whatever video player was installed and backgrounded Minecraft. This view plays the
 * clip in place, so the game is never left.
 *
 * <p>It is deliberately a plain platform player, not ExoPlayer: the launcher already ships no
 * media library, and pulling one in for a single overlay is a large dependency. The trade is that
 * this decodes one clip at a time — which is exactly what the overlay needs, and
 * {@link ReplayPlaybackGate} stops the recorder from competing with it.
 *
 * <p>Controls: play/pause, a scrub bar with a time readout, a 1x/1.5x/2x speed toggle and a close
 * button that returns to the library grid. All of them are inside the same overlay window.
 */
public final class ReplayPlayerView {

    /** Receives transport and lifecycle events; all run on the main thread. */
    public interface Callbacks {
        /** The close button was pressed; the host should tear the player down. */
        void onClose();

        /** Playback is actually running (started or resumed); latch the playback gate. */
        void onPlaybackStarted();

        /** Playback stopped (paused, completed or released); release the playback gate. */
        void onPlaybackEnded();
    }

    private final Activity activity;
    private final ReplayStyle style;
    private final Callbacks callbacks;

    private final FrameLayout root;
    private final SurfaceView surface;
    private TextView title;
    private ImageView playPause;
    private TextView speedButton;
    private TextView cameraButton;
    private SeekBar seekBar;
    private TextView positionText;

    private final Handler mainHandler = MainThread.handler();

    /** The transport state (position, speed, play/pause) shared with the camera path. */
    private final ReplayTimeline timeline = new ReplayTimeline(0L);
    /** The detached spectator camera, used while the free camera is on. */
    private final ReplayCamera camera = new ReplayCamera();
    /** The cinematic camera path being played back, if the clip has one. */
    private ReplayCameraPath cameraPath;
    private boolean freeCamera;
    /** Wall-clock anchor for the reverse/stepped seek tick. */
    private long lastTickMs;

    private MediaPlayer player;
    private ReplayClip clip;
    private boolean prepared;
    /** Whether the gate is currently latched by this view, so callbacks alternate. */
    private boolean gateLatched;
    /** True while the user drags the scrub bar, so the tick does not fight the thumb. */
    private boolean scrubbing;

    // Interactive spectator flight. Shown only in free-camera mode without an authored camera path:
    // with a path, the shot owns the camera. A single-finger drag over the video looks; the fly
    // stick in the corner moves the camera; the flight tick integrates the stick while it is held.
    private SpectatorFlyView flyStick;
    private long lastFlightTickMs;
    private boolean looking;
    private float lastLookX;
    private float lastLookY;

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            long elapsed = lastTickMs == 0L ? 0L : now - lastTickMs;
            lastTickMs = now;
            if (timeline.isReversed()) {
                stepReversed(elapsed);
            }
            updateProgress();
            integrateSpectatorFlight(now);
            if (timeline.isPlaying()) mainHandler.postDelayed(this, 100L);
        }
    };

    public ReplayPlayerView(Activity activity, ReplayStyle style, ReplayClip clip,
                            Callbacks callbacks) {
        this.activity = activity;
        this.style = style;
        this.callbacks = callbacks;

        root = new FrameLayout(activity);
        root.setBackgroundColor(0xFF000000);

        surface = new SurfaceView(activity);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        root.addView(buildTopBar(), topBarParams());
        root.addView(buildControls(), controlsParams());

        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                attachSurface(holder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                attachSurface(holder);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (player != null) {
                    try {
                        player.setDisplay(null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });

        flyStick = new SpectatorFlyView(activity, style);
        flyStick.setVisibility(View.GONE);
        FrameLayout.LayoutParams stickParams = new FrameLayout.LayoutParams(
                ReplayStyle.dpInt(activity, 120), ReplayStyle.dpInt(activity, 120));
        stickParams.gravity = Gravity.BOTTOM | Gravity.END;
        stickParams.setMargins(0, 0, ReplayStyle.dpInt(activity, 24),
                ReplayStyle.dpInt(activity, 88));
        root.addView(flyStick, stickParams);
        flyStick.setOnStickListener((vx, vy) -> {
            if (freeCamera && cameraPath == null) {
                lastFlightTickMs = SystemClock.uptimeMillis();
            }
        });

        // The user may tap the video area to toggle playback, matching every video player. In
        // free-camera spectator mode a single-finger drag looks instead of scrubbing the timeline.
        surface.setOnClickListener(v -> togglePlayPause());
        surface.setOnTouchListener((v, event) -> {
            if (!freeCamera || cameraPath != null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    looking = true;
                    lastLookX = event.getRawX();
                    lastLookY = event.getRawY();
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    if (!looking) return false;
                    float dx = event.getRawX() - lastLookX;
                    float dy = event.getRawY() - lastLookY;
                    lastLookX = event.getRawX();
                    lastLookY = event.getRawY();
                    float[] look = ReplaySpectatorInput.dragToLook(dx, dy);
                    camera.look(look[0], look[1]);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    looking = false;
                    return false;
                default:
                    return false;
            }
        });
    }

    /**
     * Integrates the fly stick's input into the spectator camera while it is held.
     *
     * <p>The stick is only meaningful in interactive spectator mode (free camera, no authored
     * path). Its deflection drives {@link ReplaySpectatorInput#fly}, which temporarily applies the
     * boost before integrating so a held boost actually moves faster rather than just changing the
     * camera's stored speed.
     */
    private void integrateSpectatorFlight(long now) {
        if (flyStick == null || flyStick.getVisibility() != View.VISIBLE || flyStick.isNeutral()) {
            lastFlightTickMs = 0L;
            return;
        }
        if (!freeCamera || cameraPath != null) {
            return;
        }
        long elapsed = lastFlightTickMs == 0L ? 0L : now - lastFlightTickMs;
        lastFlightTickMs = now;
        ReplaySpectatorInput.fly(camera, flyStick.forward(), flyStick.strafe(),
                flyStick.vertical(), flyStick.boost(), elapsed);
    }

    public View getView() {
        return root;
    }

    /** Starts playing {@code clip}, replacing any previous one. */
    public void show(ReplayClip clip) {
        this.clip = clip;
        title.setText(clip == null ? "" : clip.displayName());
        releasePlayer();
        prepared = false;
        timeline.pause();
        timeline.setDurationMs(0L);
        timeline.seekMs(0L);
        timeline.setSpeed(1f);
        updateSpeedLabel();
        updateProgress();
        // A clip may carry an authored cinematic camera path in its sidecar; load it so the free
        // camera can play the shot, otherwise the camera starts from the recorded view.
        cameraPath = loadCameraPath(clip);
        freeCamera = false;
        looking = false;
        updateCameraLabel();
        updateFlyStick();
        if (clip == null) return;
        startPlayer(clip.file());
    }

    /** Stops playback and releases all media resources. Safe to call repeatedly. */
    public void release() {
        mainHandler.removeCallbacks(progressTick);
        releasePlayer();
        prepared = false;
        timeline.pause();
        unlatchGate();
        flyStick = null;
    }

    /** The sidecar's authored camera path for a clip, or null when it has none. */
    private ReplayCameraPath loadCameraPath(ReplayClip clip) {
        if (clip == null) return null;
        try {
            ReplayCameraPath path = clip.cameraPath();
            return path == null || path.isEmpty() ? null : path;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The transport state, exposed so a host (the panel) can drive or read it. */
    public ReplayTimeline timeline() {
        return timeline;
    }

    /** The free camera, exposed for a host that wants to move it from a controller/stick. */
    public ReplayCamera camera() {
        return camera;
    }

    public boolean isFreeCamera() {
        return freeCamera;
    }

    /** Toggles the detached spectator camera on or off. */
    public void toggleFreeCamera() {
        freeCamera = !freeCamera;
        looking = false;
        updateCameraLabel();
        updateFlyStick();
    }

    /** True while the interactive spectator flight is available (free camera, no authored shot). */
    public boolean isSpectatorFlightAvailable() {
        return freeCamera && cameraPath == null;
    }

    /** Fly stick visible only in interactive spectator flight; with a path the shot owns the camera. */
    private void updateFlyStick() {
        if (flyStick == null) return;
        flyStick.setVisibility(isSpectatorFlightAvailable() ? View.VISIBLE : View.GONE);
        if (flyStick.getVisibility() != View.VISIBLE) {
            flyStick.reset();
            lastFlightTickMs = 0L;
        }
    }

    /** Toggles play/pause, for a controller route into the open player. */
    public void togglePlayback() {
        togglePlayPause();
    }

    /** Steps the playback speed, for a controller route into the open player. */
    public void stepSpeed() {
        cycleSpeed();
    }

    /** Seeks by a fraction of the clip, for a controller's scrub route. */
    public void seekByFraction(float fraction) {
        if (player == null || !prepared) return;
        timeline.seekFraction(fraction);
        player.seekTo((int) timeline.positionMs());
        updateProgress();
    }

    // ---------------------------------------------------------------------------------------------

    private void startPlayer(File file) {
        if (file == null || !file.isFile()) {
            Toast.makeText(activity, R.string.replay_playback_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                prepared = true;
                mp.setLooping(false);
                timeline.setDurationMs(mp.getDuration());
                applySpeed();
                attachSurface(surface.getHolder());
                startPlayback();
            });
            player.setOnCompletionListener(mp -> {
                timeline.pause();
                unlatchGate();
                showPlayIcon(true);
                updateProgress();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                timeline.pause();
                unlatchGate();
                Toast.makeText(activity, R.string.replay_playback_hint, Toast.LENGTH_SHORT)
                        .show();
                return true;
            });
            player.prepareAsync();
        } catch (Throwable t) {
            Toast.makeText(activity, R.string.replay_playback_hint, Toast.LENGTH_SHORT).show();
        }
    }

    private void attachSurface(SurfaceHolder holder) {
        if (player == null || holder == null || !prepared) return;
        try {
            player.setDisplay(holder);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Sets the transport glyph from the vector set.
     *
     * @param play true for the play triangle (paused), false for the pause bars (playing)
     */
    private void showPlayIcon(boolean play) {
        if (playPause == null) return;
        playPause.setImageResource(play ? R.drawable.ic_replay_play : R.drawable.ic_replay_pause);
        playPause.setColorFilter(style.textPrimary());
    }

    private void togglePlayPause() {
        if (player == null || !prepared) return;
        if (timeline.isPlaying()) {
            timeline.pause();
            player.pause();
            unlatchGate();
            showPlayIcon(true);
            mainHandler.removeCallbacks(progressTick);
        } else {
            timeline.seekMs(player.getCurrentPosition());
            timeline.play();
            if (timeline.isPlaying()) startPlayback();
        }
    }

    private void startPlayback() {
        if (player == null || !prepared) return;
        if (timeline.isReversed()) {
            // Reverse is a stepped seek; the decoder stays paused and the tick moves the position.
            timeline.play();
            if (!timeline.isPlaying()) return;
            showPlayIcon(false);
            latchGate();
            lastTickMs = SystemClock.uptimeMillis();
            mainHandler.removeCallbacks(progressTick);
            mainHandler.post(progressTick);
            return;
        }
        try {
            player.start();
        } catch (Throwable t) {
            return;
        }
        timeline.play();
        if (!timeline.isPlaying()) return;
        showPlayIcon(false);
        latchGate();
        lastTickMs = SystemClock.uptimeMillis();
        mainHandler.removeCallbacks(progressTick);
        mainHandler.post(progressTick);
    }

    /**
     * Drives a reversed transport, which the platform player cannot do itself, by stepping the
     * position backwards on the tick. The forward path keeps using the decoder's own speed.
     */
    private void stepReversed(long elapsedMs) {
        if (player == null || !prepared) return;
        if (timeline.advance(elapsedMs)) {
            player.seekTo((int) timeline.positionMs());
            player.pause();
            unlatchGate();
            showPlayIcon(true);
            mainHandler.removeCallbacks(progressTick);
            return;
        }
        player.seekTo((int) timeline.positionMs());
    }

    /** Re-seeks the current speed after a start, since some decoders reset the params. */
    private void applySpeed() {
        if (player == null) return;
        float speed = timeline.speed();
        if (speed < 0f) {
            // Reverse is a stepped seek, not a decoder rate; keep the decoder paused between steps.
            try {
                player.setPlaybackParams(player.getPlaybackParams().setSpeed(1f));
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            android.media.PlaybackParams params = player.getPlaybackParams();
            params.setSpeed(speed);
            player.setPlaybackParams(params);
        } catch (Throwable ignored) {
            // Pre-N decoders may not support speed; playback stays at 1x.
        }
    }

    private void cycleSpeed() {
        timeline.cycleSpeed();
        applySpeed();
        updateSpeedLabel();
    }

    private void updateSpeedLabel() {
        float speed = timeline.speed();
        speedButton.setText(speed == 1.0f
                ? activity.getString(R.string.replay_player_speed_1x)
                : activity.getString(R.string.replay_player_speed, speed));
    }

    private void updateCameraLabel() {
        if (cameraButton == null) return;
        cameraButton.setText(freeCamera
                ? R.string.replay_camera_free : R.string.replay_camera_recorded);
    }

    private void updateProgress() {
        if (player == null || !prepared) return;
        int duration = player.getDuration();
        if (duration > 0) timeline.setDurationMs(duration);
        int position = timeline.isReversed() ? (int) timeline.positionMs()
                : player.getCurrentPosition();
        if (!timeline.isReversed()) timeline.seekMs(position);
        // While the free camera plays an authored path, drive the camera from the same timeline so
        // the shot and the clip stay in step.
        if (freeCamera && cameraPath != null) cameraPath.sample(position, camera);
        if (duration > 0) {
            seekBar.setMax(duration);
            if (!scrubbing) seekBar.setProgress(position);
            positionText.setText(ReplayFormat.duration(position) + " / "
                    + ReplayFormat.duration(duration));
        }
    }

    private void releasePlayer() {
        mainHandler.removeCallbacks(progressTick);
        if (player != null) {
            try {
                player.setDisplay(null);
            } catch (Throwable ignored) {
            }
            try {
                player.reset();
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
    }

    private void latchGate() {
        if (gateLatched) return;
        gateLatched = true;
        if (callbacks != null) callbacks.onPlaybackStarted();
    }

    private void unlatchGate() {
        if (!gateLatched) return;
        gateLatched = false;
        if (callbacks != null) callbacks.onPlaybackEnded();
    }

    // ---------------------------------------------------------------------------------------------
    // Control construction
    // ---------------------------------------------------------------------------------------------

    private FrameLayout.LayoutParams topBarParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP;
        return params;
    }

    private FrameLayout.LayoutParams controlsParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        return params;
    }

    private View buildTopBar() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(ReplayStyle.withAlpha(style.canvas(), 0xCC));
        int pad = ReplayStyle.dpInt(activity, 12);
        row.setPadding(pad, ReplayStyle.dpInt(activity, 8), pad, ReplayStyle.dpInt(activity, 8));

        title = new TextView(activity);
        title.setTextColor(style.textPrimary());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(activity);
        close.setText(R.string.replay_player_close);
        close.setTextColor(style.textPrimary());
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        close.setTypeface(null, Typeface.BOLD);
        close.setBackground(ReplayStyle.rippled(
                ReplayStyle.pill(style.surfaceElevated(), activity), style.accentFill(60), activity));
        close.setPadding(ReplayStyle.dpInt(activity, 12), ReplayStyle.dpInt(activity, 6),
                ReplayStyle.dpInt(activity, 12), ReplayStyle.dpInt(activity, 6));
        close.setOnClickListener(v -> {
            if (callbacks != null) callbacks.onClose();
        });
        DynamicAnim.applyPressScale(close);
        row.addView(close);
        return row;
    }

    private View buildControls() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(ReplayStyle.withAlpha(style.canvas(), 0xCC));
        int pad = ReplayStyle.dpInt(activity, 12);
        column.setPadding(pad, ReplayStyle.dpInt(activity, 8), pad, ReplayStyle.dpInt(activity, 8));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        playPause = new ImageView(activity);
        // Vector transport icons, never the ▶/⏸ glyphs: those are emoji and render inconsistently.
        showPlayIcon(false);
        playPause.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        playPause.setBackground(ReplayStyle.rippled(
                ReplayStyle.rounded(style.surfaceElevated(), 10f, activity),
                style.accentFill(60), activity));
        playPause.setPadding(ReplayStyle.dpInt(activity, 12), ReplayStyle.dpInt(activity, 4),
                ReplayStyle.dpInt(activity, 12), ReplayStyle.dpInt(activity, 4));
        playPause.setOnClickListener(v -> togglePlayPause());
        DynamicAnim.applyPressScale(playPause);
        row.addView(playPause);

        seekBar = new SeekBar(activity);
        seekBar.setPadding(ReplayStyle.dpInt(activity, 8), 0, ReplayStyle.dpInt(activity, 8), 0);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && player != null && prepared) {
                    player.seekTo(progress);
                    positionText.setText(ReplayFormat.duration(progress) + " / "
                            + ReplayFormat.duration(player.getDuration()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                scrubbing = false;
            }
        });
        row.addView(seekBar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        positionText = new TextView(activity);
        positionText.setTextColor(style.textSecondary());
        positionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        positionText.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeParams.setMarginStart(ReplayStyle.dpInt(activity, 8));
        row.addView(positionText, timeParams);

        speedButton = new TextView(activity);
        speedButton.setTextColor(style.textPrimary());
        speedButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        speedButton.setTypeface(null, Typeface.BOLD);
        speedButton.setBackground(ReplayStyle.rippled(
                ReplayStyle.pill(style.surfaceElevated(), activity), style.accentFill(60), activity));
        speedButton.setPadding(ReplayStyle.dpInt(activity, 10), ReplayStyle.dpInt(activity, 5),
                ReplayStyle.dpInt(activity, 10), ReplayStyle.dpInt(activity, 5));
        speedButton.setOnClickListener(v -> cycleSpeed());
        DynamicAnim.applyPressScale(speedButton);
        LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        speedParams.setMarginStart(ReplayStyle.dpInt(activity, 8));
        row.addView(speedButton, speedParams);

        // The free-camera toggle: detaches the view from the recorded first-person pose so the clip
        // can be watched like a spectator, and replays the clip's authored camera path when it has
        // one. Its label always names the active mode so the state is never ambiguous.
        cameraButton = new TextView(activity);
        cameraButton.setTextColor(style.textPrimary());
        cameraButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        cameraButton.setTypeface(null, Typeface.BOLD);
        cameraButton.setBackground(ReplayStyle.rippled(
                ReplayStyle.pill(style.surfaceElevated(), activity), style.accentFill(60), activity));
        cameraButton.setPadding(ReplayStyle.dpInt(activity, 10), ReplayStyle.dpInt(activity, 5),
                ReplayStyle.dpInt(activity, 10), ReplayStyle.dpInt(activity, 5));
        cameraButton.setOnClickListener(v -> toggleFreeCamera());
        DynamicAnim.applyPressScale(cameraButton);
        LinearLayout.LayoutParams cameraParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cameraParams.setMarginStart(ReplayStyle.dpInt(activity, 8));
        row.addView(cameraButton, cameraParams);
        updateCameraLabel();

        column.addView(row);
        return column;
    }

    // ---------------------------------------------------------------------------------------------
    // Spectator fly stick
    // ---------------------------------------------------------------------------------------------

    /**
     * The spectator flight control: a virtual joystick with an up/down lift rail beside it.
     *
     * <p>Drag inside the pad to fly forward/back/strafe; drag the rail to ascend/descend. Pushing
     * the thumb to the pad's edge counts as boost, which {@link ReplaySpectatorInput#fly} turns
     * into a speed multiplier while held. The stick reports normalised inputs in -1..1 and is
     * deliberately dumb about the camera — the pure model does the integration.
     */
    private static final class SpectatorFlyView extends View {

        interface StickListener {
            void onStick(float forward, float strafe);
        }

        /** Drag deflection above this fraction of the pad radius counts as boost. */
        static final float BOOST_THRESHOLD = 0.82f;

        private final ReplayStyle style;
        private final Paint padPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint railPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // The boost ring is drawn while the stick is deflected, which is exactly when the player is
        // looking at it, so its Paint and geometry are fields rather than per-frame allocations.
        private final Paint boostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float boostRingRadius;
        private final float padCenterX;
        private final int railWidth;
        private final float padRadius;
        private final float thumbRadius;
        private final float railX;
        private float thumbX;
        private float thumbY;
        private boolean stickActive;
        private float stickDx;
        private float stickDy;
        private boolean railActive;
        private float railY;
        private float railOriginY;
        private StickListener listener;

        SpectatorFlyView(Activity activity, ReplayStyle style) {
            super(activity);
            this.style = style;
            padRadius = ReplayStyle.dpInt(activity, 36);
            thumbRadius = ReplayStyle.dpInt(activity, 16);
            railWidth = ReplayStyle.dpInt(activity, 18);
            railX = ReplayStyle.dpInt(activity, 12);
            padPaint.setColor(style.accentFill(44));
            thumbPaint.setColor(style.accent());
            railPaint.setColor(style.accentFill(50));
            boostPaint.setStyle(Paint.Style.STROKE);
            boostPaint.setStrokeWidth(ReplayStyle.dpInt(activity, 2));
            boostPaint.setColor(style.statusReady());
            boostRingRadius = padRadius + ReplayStyle.dpInt(activity, 3);
            padCenterX = padRadius + railWidth + ReplayStyle.dpInt(activity, 6);
            thumbX = padRadius;
            thumbY = padRadius;
            railY = padRadius;
            railOriginY = padRadius;
        }

        void setOnStickListener(StickListener listener) {
            this.listener = listener;
        }

        void reset() {
            stickActive = false;
            railActive = false;
            stickDx = 0f;
            stickDy = 0f;
            thumbX = padRadius;
            thumbY = padRadius;
            railY = railOriginY;
            invalidate();
        }

        boolean isNeutral() {
            return !stickActive && !railActive;
        }

        float forward() {
            return -stickDy; // drag up = forward
        }

        float strafe() {
            return stickDx;
        }

        float vertical() {
            float lift = (railOriginY - railY) / Math.max(1f, padRadius);
            if (lift > 1f) lift = 1f;
            if (lift < -1f) lift = -1f;
            return lift;
        }

        boolean boost() {
            float r = (float) Math.hypot(stickDx, stickDy);
            return r >= BOOST_THRESHOLD;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = resolveSize((int) (padRadius * 2 + railWidth
                    + ReplayStyle.dpInt(getContext(), 24)), widthMeasureSpec);
            int height = resolveSize((int) (padRadius * 2), heightMeasureSpec);
            setMeasuredDimension(size, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = padCenterX;
            float cy = getHeight() / 2f;

            canvas.drawRoundRect(railX, cy - padRadius, railX + railWidth, cy + padRadius,
                    railWidth / 2f, railWidth / 2f, railPaint);
            float railThumbY = cy + (railY - railOriginY);
            canvas.drawCircle(railX + railWidth / 2f, railThumbY, thumbRadius * 0.6f, thumbPaint);

            canvas.drawCircle(cx, cy, padRadius, padPaint);
            float tX = cx + (thumbX - padRadius);
            float tY = cy + (thumbY - padRadius);
            canvas.drawCircle(tX, tY, thumbRadius, thumbPaint);
            if (boost()) {
                canvas.drawCircle(cx, cy, boostRingRadius, boostPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            float cx = padCenterX;
            float cy = getHeight() / 2f;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (x < railX + railWidth + 8) {
                        railActive = true;
                        railOriginY = padRadius;
                        railY = y - cy + padRadius;
                        if (railY < 0f) railY = 0f;
                        if (railY > padRadius * 2f) railY = padRadius * 2f;
                    } else {
                        stickActive = true;
                        stickDx = 0f;
                        stickDy = 0f;
                        thumbX = padRadius;
                        thumbY = padRadius;
                    }
                    notifyListener();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (stickActive) {
                        float dx = x - cx;
                        float dy = y - cy;
                        float max = padRadius - thumbRadius;
                        float len = (float) Math.hypot(dx, dy);
                        if (len > max) {
                            dx = dx / len * max;
                            dy = dy / len * max;
                        }
                        stickDx = dx / Math.max(1f, padRadius);
                        stickDy = dy / Math.max(1f, padRadius);
                        thumbX = padRadius + dx;
                        thumbY = padRadius + dy;
                    } else if (railActive) {
                        railY = y - cy + padRadius;
                        if (railY < 0f) railY = 0f;
                        if (railY > padRadius * 2f) railY = padRadius * 2f;
                    }
                    notifyListener();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stickActive = false;
                    railActive = false;
                    stickDx = 0f;
                    stickDy = 0f;
                    thumbX = padRadius;
                    thumbY = padRadius;
                    notifyListener();
                    invalidate();
                    return true;
                default:
                    return false;
            }
        }

        private void notifyListener() {
            if (listener != null) {
                listener.onStick(forward(), strafe());
            }
        }
    }
}
