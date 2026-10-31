package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import org.chimeramc.client.util.MainThread;

import java.util.List;

/**
 * Full-screen overlay for the Trajectory Prediction module.
 *
 * <p>Projects the pure arc from {@link TrajectorySolver} through the same camera basis the
 * Hitboxes overlay uses and draws it as a dotted line, with a small landing ring at the end. It
 * is non-touchable: the arc is world-positioned, so there is nothing to drag and it must never
 * consume a look gesture.
 *
 * <p>Nothing is drawn when the held item is not a projectile, or when no held-item feed is
 * installed - the latter shows a short "waiting for game data" notice, because an enabled module
 * that silently draws nothing is indistinguishable from a broken one.
 */
public final class TrajectoryPredictionOverlay {
    private static final int REFRESH_MS = 50;
    private static final int MAX_SAMPLES = 48;

    private final Activity activity;
    private final WindowManager windowManager;
    private final Handler handler = MainThread.handler();

    private View overlayView;
    private WindowManager.LayoutParams wmParams;
    private boolean isShowing;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isShowing) return;
            if (overlayView != null) overlayView.invalidate();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    public TrajectoryPredictionOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public void show() {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        ArcView view = new ArcView(activity);
        try {
            wmParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            wmParams.gravity = Gravity.TOP | Gravity.START;
            wmParams.token = activity.getWindow().getDecorView().getWindowToken();
            windowManager.addView(view, wmParams);
            overlayView = view;
        } catch (Exception e) {
            wmParams = null;
            ViewGroup root = activity.findViewById(android.R.id.content);
            if (root == null) return;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            view.setClickable(false);
            view.setFocusable(false);
            root.addView(view, params);
            overlayView = view;
        }
        isShowing = true;
        handler.post(refreshRunnable);
    }

    public void hide() {
        if (!isShowing) return;
        isShowing = false;
        handler.removeCallbacks(refreshRunnable);
        try {
            if (wmParams != null && windowManager != null && overlayView != null) {
                windowManager.removeView(overlayView);
            } else if (overlayView != null) {
                ViewGroup root = activity.findViewById(android.R.id.content);
                if (root != null) root.removeView(overlayView);
            }
        } catch (Exception ignored) {
        }
        overlayView = null;
        wmParams = null;
    }

    public boolean isShowing() {
        return isShowing;
    }

    public void applyConfigurationChanges() {
        if (overlayView != null) overlayView.invalidate();
    }

    public void setHudEditorMode(boolean active) {
        // World-positioned; nothing to move.
    }

    public void setOverlayVisibility(int visibility) {
        if (overlayView != null && overlayView.getVisibility() != visibility) {
            overlayView.setVisibility(visibility);
        }
    }

    private final class ArcView extends View {
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint notePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint noteBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        ArcView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            dotPaint.setStyle(Paint.Style.FILL);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(2f * density);
            notePaint.setTextAlign(Paint.Align.CENTER);
            notePaint.setTextSize(12f * density);
            notePaint.setColor(0xFFFFC46B);
            noteBgPaint.setColor(0xCC12151B);
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            HitboxProjector.Camera camera = camera();
            if (camera == null) {
                if (TrajectoryPredictionMod.isAwaitingGameData()) drawAwaitingData(canvas);
                return;
            }
            HitboxProjector.Basis basis = HitboxProjector.basis(camera);
            if (basis == null) return;

            List<TrajectorySolver.Sample> arc =
                    TrajectoryPredictionMod.readArc(camera, MAX_SAMPLES);
            if (arc.isEmpty()) {
                if (TrajectoryPredictionMod.isAwaitingGameData()) drawAwaitingData(canvas);
                return;
            }

            int color = TrajectoryPredictionMod.getColor();
            dotPaint.setColor(color);
            float[] last = null;
            for (TrajectorySolver.Sample sample : arc) {
                float[] screen = HitboxProjector.projectPoint(
                        sample.x, sample.y, sample.z, basis);
                if (screen == null) continue;
                if (last != null) {
                    // Dotted line: draw a small dot at each sample rather than connecting them,
                    // so the arc reads as a trajectory rather than a solid overlay stroke.
                    canvas.drawCircle(screen[0], screen[1], Math.max(1.5f, 1.6f * density), dotPaint);
                }
                last = screen;
            }

            // Landing ring at the arc's final projected point.
            if (last != null) {
                ringPaint.setColor(color);
                canvas.drawCircle(last[0], last[1], 6f * density, ringPaint);
                dotPaint.setAlpha(60);
                canvas.drawCircle(last[0], last[1], 3f * density, dotPaint);
                dotPaint.setAlpha(255);
            }
        }

        private HitboxProjector.Camera camera() {
            // Read the local player's view directly rather than through the voice nametag seam:
            // the suite works without the voice module, so it cannot depend on a seam the voice
            // feature installs. Fail-closed: a null read draws nothing.
            try {
                return LocalPlayerFeed.localCamera(getWidth(), getHeight());
            } catch (Throwable t) {
                return null;
            }
        }

        private void drawAwaitingData(Canvas canvas) {
            String text = "Trajectory: waiting for game data";
            float pad = 12f * density;
            float w = notePaint.measureText(text) + pad * 2f;
            float h = 30f * density;
            float left = getWidth() / 2f - w / 2f;
            float top = getHeight() * 0.16f;
            canvas.drawRoundRect(left, top, left + w, top + h, 10f * density, 10f * density, noteBgPaint);
            float baseline = top + h / 2f - (notePaint.descent() + notePaint.ascent()) / 2f;
            canvas.drawText(text, getWidth() / 2f, baseline, notePaint);
        }
    }
}
