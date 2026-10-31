package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import org.chimeramc.client.util.MainThread;

import java.util.List;

/**
 * Full-screen overlay for the Custom Kill Effects module.
 *
 * <p>Draws a short client-side particle burst at the position of a player whose death was
 * credited to you. Three styles, all projected through the shared camera basis:
 *
 * <ul>
 *   <li><b>Burst</b> - sparks radiating outward, the default.</li>
 *   <li><b>Column</b> - a rising pillar of particles.</li>
 *   <li><b>Ring</b> - an expanding ground ring.</li>
 * </ul>
 *
 * <p>Particles are computed from the burst's elapsed time, not accumulated, so a dropped frame
 * cannot make the effect jump or run long. When no burst is alive the draw is a no-op.
 *
 * <p>Non-touchable and world-positioned. Nothing is drawn when the death feed is absent, and the
 * module shows a "waiting for game data" notice in that case - a kill effect that silently never
 * fires is the exact "looks broken" state the notice exists to prevent.
 */
public final class KillEffectsOverlay {
    private static final int REFRESH_MS = 33;
    private static final int PARTICLE_COUNT = 14;

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

    public KillEffectsOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public void show() {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        BurstView view = new BurstView(activity);
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

    private final class BurstView extends View {
        private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint notePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint noteBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        BurstView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            particlePaint.setStyle(Paint.Style.FILL);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(2.5f * density);
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
            if (camera == null) return;
            HitboxProjector.Basis basis = HitboxProjector.basis(camera);
            if (basis == null) return;

            long now = SystemClock.uptimeMillis();
            List<KillEffectsMod.Burst> bursts = KillEffectsMod.activeBursts();
            if (bursts.isEmpty()) {
                if (KillEffectsMod.isAwaitingGameData()) drawAwaitingData(canvas);
                return;
            }

            for (KillEffectsMod.Burst burst : bursts) {
                float[] screen = HitboxProjector.projectPoint(burst.x, burst.y, burst.z, basis);
                if (screen == null) continue;
                float progress = burst.progress(now);
                int alpha = (int) (255 * (1f - progress));
                if (alpha <= 0) continue;
                switch (burst.style) {
                    case KillEffectsMod.STYLE_COLUMN:
                        drawColumn(canvas, screen[0], screen[1], progress, alpha, burst.color);
                        break;
                    case KillEffectsMod.STYLE_RING:
                        drawRing(canvas, screen[0], screen[1], progress, alpha, burst.color);
                        break;
                    case KillEffectsMod.STYLE_BURST:
                    default:
                        drawBurst(canvas, screen[0], screen[1], progress, alpha, burst.color);
                        break;
                }
            }
        }

        private void drawBurst(Canvas canvas, float cx, float cy, float progress,
                               int alpha, int color) {
            particlePaint.setColor(color);
            particlePaint.setAlpha(alpha);
            float spread = 46f * density * progress;
            float size = (1f - progress * 0.6f) * 3.2f * density;
            for (int i = 0; i < PARTICLE_COUNT; i++) {
                double angle = (Math.PI * 2 * i) / PARTICLE_COUNT;
                float px = cx + (float) Math.cos(angle) * spread;
                float py = cy + (float) Math.sin(angle) * spread * 0.6f - progress * 10f * density;
                canvas.drawCircle(px, py, Math.max(1f, size), particlePaint);
            }
        }

        private void drawColumn(Canvas canvas, float cx, float cy, float progress,
                                int alpha, int color) {
            particlePaint.setColor(color);
            particlePaint.setAlpha(alpha);
            float rise = 60f * density * progress;
            float size = (1f - progress * 0.5f) * 3f * density;
            for (int i = 0; i < PARTICLE_COUNT; i++) {
                float t = i / (float) PARTICLE_COUNT;
                float py = cy - rise * t;
                float wobble = (float) Math.sin(t * Math.PI * 3) * 5f * density;
                canvas.drawCircle(cx + wobble, py, Math.max(1f, size), particlePaint);
            }
        }

        private void drawRing(Canvas canvas, float cx, float cy, float progress,
                              int alpha, int color) {
            ringPaint.setColor(color);
            ringPaint.setAlpha(alpha);
            float radius = 8f * density + 44f * density * progress;
            canvas.drawCircle(cx, cy, radius, ringPaint);
            // A second, fainter ring a step behind reads as an expanding shockwave.
            ringPaint.setAlpha((int) (alpha * 0.5f));
            canvas.drawCircle(cx, cy, radius * 0.7f, ringPaint);
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
            String text = "Kill Effects: waiting for game data";
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
