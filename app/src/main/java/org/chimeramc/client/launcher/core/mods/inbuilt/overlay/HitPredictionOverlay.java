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
 * Full-screen overlay for the Hit Prediction module.
 *
 * <p>Draws a ghost box at each peer's predicted next position, plus a thin tether from the
 * current position to the ghost so the lead is legible. It shares the Hitboxes module's camera
 * basis, so the ghost and the real box cannot disagree about where the peer is.
 *
 * <p>Non-touchable and world-positioned, like the other suite overlays. A peer moving too fast for
 * a straight-line guess produces no ghost at all - {@link HitPredictor} returns null there, and
 * the overlay simply skips it rather than drawing a marker the player would aim at and miss.
 */
public final class HitPredictionOverlay {
    private static final int REFRESH_MS = 33;

    private static final int COLOR_GHOST = 0xFFFF9F45;
    private static final int COLOR_TETHER = 0x66FF9F45;

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

    public HitPredictionOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public void show() {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        GhostView view = new GhostView(activity);
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

    private final class GhostView extends View {
        private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tetherPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        GhostView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setStrokeWidth(2f * density);
            boxPaint.setColor(COLOR_GHOST);
            tetherPaint.setColor(COLOR_TETHER);
            tetherPaint.setStrokeWidth(Math.max(1f, density));
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(0x33FF9F45);
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            HitboxProjector.Camera camera = camera();
            if (camera == null) return;
            HitboxProjector.Basis basis = HitboxProjector.basis(camera);
            if (basis == null) return;

            List<HitPredictor.Prediction> markers = HitPredictionMod.readMarkers();
            if (markers.isEmpty()) return;

            for (HitPredictor.Prediction marker : markers) {
                HitboxProjector.Entity current = null;
                // Find the peer's current position so the tether has a start point.
                for (ReachIndicator.VoicePeerPosition peer : PeerPositions.read()) {
                    if (peer.id.equals(marker.peerId)) {
                        current = HitboxProjector.Entity.player(peer.x, peer.y, peer.z);
                        break;
                    }
                }
                HitboxProjector.Entity ghost = HitboxProjector.Entity.player(
                        marker.x, marker.y, marker.z);

                HitboxProjector.Projected projected = HitboxProjector.projectEntity(ghost, basis);
                if (projected == null) continue;
                HitboxProjector.Rect box = projected.box;
                canvas.drawRect(box.left, box.top, box.right, box.bottom, fillPaint);
                canvas.drawRect(box.left, box.top, box.right, box.bottom, boxPaint);

                if (current != null) {
                    HitboxProjector.Projected from = HitboxProjector.projectEntity(current, basis);
                    if (from != null) {
                        float fx = (from.box.left + from.box.right) / 2f;
                        float fy = (from.box.top + from.box.bottom) / 2f;
                        float tx = (box.left + box.right) / 2f;
                        float ty = (box.top + box.bottom) / 2f;
                        canvas.drawLine(fx, fy, tx, ty, tetherPaint);
                    }
                }
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
    }
}
