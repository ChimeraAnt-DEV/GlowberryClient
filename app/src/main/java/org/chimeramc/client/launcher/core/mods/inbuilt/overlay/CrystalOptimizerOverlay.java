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
import android.view.WindowManager;

import org.chimeramc.client.R;
import org.chimeramc.client.util.MainThread;

/**
 * Crystal Optimizer readout.
 *
 * <p>Shows the solver's verdict: a status line, the distance to the chosen spot, and the
 * relative damage it expects. When a {@link SpotProjector} is installed it also draws a marker
 * at the projected screen position of the spot, which is what the manual-assist mode is for.
 *
 * <p>This overlay never issues a place or break action — it only reports. Any automatic
 * placement would have to come from a native provider behind {@link CrystalOptimizerMod}.
 */
public final class CrystalOptimizerOverlay {
    private static final int REFRESH_MS = 100;

    /** Maps a world position to screen pixels, or returns null when it cannot be projected. */
    public interface SpotProjector {
        float[] project(float worldX, float worldY, float worldZ);
    }

    private static volatile SpotProjector projector;

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

    public CrystalOptimizerOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public static void setSpotProjector(SpotProjector value) {
        projector = value;
    }

    public void show() {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        CrystalOptimizerView view = new CrystalOptimizerView(activity);
        try {
            wmParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            wmParams.gravity = Gravity.TOP | Gravity.START;
            wmParams.token = activity.getWindow().getDecorView().getWindowToken();
            windowManager.addView(view, wmParams);
            overlayView = view;
            isShowing = true;
        } catch (Exception ignored) {
            isShowing = false;
            overlayView = null;
            wmParams = null;
            return;
        }
        handler.post(refreshRunnable);
    }

    public void hide() {
        if (!isShowing) return;
        isShowing = false;
        handler.removeCallbacks(refreshRunnable);
        try {
            if (overlayView != null && wmParams != null) windowManager.removeView(overlayView);
        } catch (Exception ignored) {
        }
        overlayView = null;
        wmParams = null;
    }

    public boolean isShowing() {
        return isShowing;
    }

    public void setOverlayVisibility(int visibility) {
        if (overlayView != null && overlayView.getVisibility() != visibility) {
            overlayView.setVisibility(visibility);
        }
    }

    private final class CrystalOptimizerView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        CrystalOptimizerView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            text.setTextAlign(Paint.Align.LEFT);
            text.setFakeBoldText(true);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2f * density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            CrystalPlacementSolver.Candidate candidate = CrystalOptimizerMod.evaluate();

            String status;
            if (!CrystalOptimizerMod.isActive()) {
                status = activity.getString(R.string.crystal_optimizer_status_off);
            } else if (CrystalOptimizerMod.isAwaitingGameData()) {
                status = activity.getString(R.string.crystal_optimizer_status_no_data);
            } else if (candidate == null) {
                status = activity.getString(R.string.crystal_optimizer_status_no_spot);
            } else if (CrystalOptimizerMod.isManualAssist()) {
                status = activity.getString(R.string.crystal_optimizer_status_manual,
                        candidate.distanceToSelf, Math.round(candidate.targetDamage));
            } else {
                status = activity.getString(R.string.crystal_optimizer_status_auto,
                        candidate.distanceToSelf, Math.round(candidate.targetDamage));
            }

            // Status strip along the top-left, clear of the crosshair.
            float pad = 8f * density;
            text.setTextSize(11f * density);
            float textWidth = text.measureText(status);
            float boxHeight = 20f * density;
            fill.setColor(0x99000000);
            canvas.drawRoundRect(pad, pad, pad + textWidth + pad * 2, pad + boxHeight,
                    4f * density, 4f * density, fill);
            text.setColor(CrystalOptimizerMod.isManualAssist() ? 0xFF80CBC4 : 0xFFFFAB40);
            canvas.drawText(status, pad * 2, pad + boxHeight * 0.68f, text);

            if (candidate == null) return;

            SpotProjector spotProjector = projector;
            if (spotProjector == null) return;
            float[] screen;
            try {
                screen = spotProjector.project(candidate.crystalX, candidate.crystalY, candidate.crystalZ);
            } catch (Throwable t) {
                return;
            }
            if (screen == null || screen.length < 2) return;

            float x = screen[0];
            float y = screen[1];
            float radius = 10f * density;
            stroke.setColor(CrystalOptimizerMod.isManualAssist() ? 0xFF80CBC4 : 0xFFFFAB40);
            canvas.drawCircle(x, y, radius, stroke);
            canvas.drawLine(x - radius * 1.6f, y, x + radius * 1.6f, y, stroke);
            canvas.drawLine(x, y - radius * 1.6f, x, y + radius * 1.6f, stroke);
        }
    }
}
