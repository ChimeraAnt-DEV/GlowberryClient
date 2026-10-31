package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import org.chimeramc.client.util.MainThread;


/**
 * Full-screen overlay for the Reach Indicator module.
 *
 * <p>A small caption reading the distance to the player under the crosshair. It draws on the
 * whole screen (the reading's position is screen-relative, not a movable widget) and is
 * deliberately non-touchable so it can never eat a tap or a look gesture - the same contract as
 * the Hitboxes overlay.
 *
 * <p>Nothing is drawn when the reading is null, which is every no-data case: no camera, no peer
 * feed, or the crosshair on nobody. That is deliberate - a "0.0 blocks" caption would read as a
 * real measurement of a player standing right on top of you.
 */
public final class ReachIndicatorOverlay {
    private static final int REFRESH_MS = 50;

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

    public ReachIndicatorOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public void show() {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        ReachView view = new ReachView(activity);
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
        // Nothing to position, so HUD-editor mode has no effect on this overlay.
    }

    public void setOverlayVisibility(int visibility) {
        if (overlayView != null && overlayView.getVisibility() != visibility) {
            overlayView.setVisibility(visibility);
        }
    }

    private final class ReachView extends View {
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF pill = new RectF();
        // The border rect is a fixed inset of the pill, so it is reused rather than rebuilt on
        // every draw; this overlay redraws with the game's frame tick.
        private final RectF border = new RectF();
        private final float density;

        ReachView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);
            textPaint.setTextSize(13f * density);
            bgPaint.setColor(0xCC12151B);
            accentPaint.setStyle(Paint.Style.STROKE);
            accentPaint.setStrokeWidth(Math.max(1f, density));
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            HitboxProjector.Camera camera = camera();
            ReachIndicator.Reading reading = ReachIndicatorMod.read(camera);
            if (reading == null) return;

            String text = reading.format();
            float padX = 10f * density;
            float padY = 6f * density;
            float textW = textPaint.measureText(text);
            float w = textW + padX * 2f;
            float h = textPaint.getTextSize() + padY * 2f;

            float cx = getWidth() / 2f;
            float cy = ReachIndicatorMod.isAboveHotbar()
                    ? getHeight() - 84f * density - h / 2f
                    : getHeight() / 2f + 46f * density;
            float left = cx - w / 2f;
            float top = cy - h / 2f;

            float radius = h / 2f;
            pill.set(left, top, left + w, top + h);
            canvas.drawRoundRect(pill, radius, radius, bgPaint);

            // A thin accent border in the module's own colour so the caption reads as a HUD
            // element rather than a debug label.
            accentPaint.setColor(0xFF5AD1FF);
            border.set(left + density, top + density, left + w - density, top + h - density);
            canvas.drawRoundRect(border, radius - density, radius - density, accentPaint);

            textPaint.setColor(0xFFEAF6FF);
            float baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(text, cx, baseline, textPaint);
        }

        /** The camera for the reading, or null when the local player feed is unavailable. */
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
    }
}
