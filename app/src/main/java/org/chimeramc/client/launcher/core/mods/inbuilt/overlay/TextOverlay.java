package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.core.mods.inbuilt.model.ModIds;
import org.chimeramc.client.util.MainThread;

/**
 * A small draggable text overlay shared by the client-side readouts (List Players, Debug Menu).
 *
 * <p>These overlays need only local data the client already reads (the local-player feed and the
 * proximity-voice peer list), so they work without a native entity feed. The window plumbing —
 * add, fallback into the content view, drag, lock, opacity, size, position — is the same for each,
 * so it lives here and the subclasses supply the text and its refresh cadence.
 */
abstract class TextOverlay {

    protected final Activity activity;
    private final WindowManager windowManager;
    private final Handler handler = MainThread.handler();
    private final String modId;

    private View overlayView;
    private TextView textView;
    private WindowManager.LayoutParams wmParams;
    private boolean isShowing;
    private boolean locked;
    private float initialX, initialY, initialTouchX, initialTouchY;
    private boolean dragging;
    private final int dragThreshold;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isShowing) return;
            refreshText();
            handler.postDelayed(this, refreshIntervalMs());
        }
    };

    TextOverlay(Activity activity, String modId) {
        this.activity = activity;
        this.modId = modId;
        this.windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        this.dragThreshold = (int) (10 * activity.getResources().getDisplayMetrics().density);
    }

    /** The overlay's layout, with one {@code R.id.stats_text} TextView. */
    protected int layoutRes() {
        return R.layout.overlay_stats_display;
    }

    /** How often the text refreshes. */
    protected long refreshIntervalMs() {
        return 500L;
    }

    /** The text to show; called on the refresh cadence and on show. */
    protected abstract String buildText();

    public boolean isShowing() {
        return isShowing;
    }

    public void show(int startX, int startY) {
        if (isShowing) return;
        showInternal(startX, startY);
    }

    private void showInternal(int startX, int startY) {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            overlayView = LayoutInflater.from(activity).inflate(layoutRes(), null);
            textView = overlayView.findViewById(R.id.stats_text);
            locked = InbuiltModManager.getInstance(activity).isOverlayLocked(modId);
            refreshText();

            wmParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            wmParams.gravity = Gravity.TOP | Gravity.START;
            wmParams.x = startX;
            wmParams.y = startY;
            wmParams.token = activity.getWindow().getDecorView().getWindowToken();

            overlayView.setOnTouchListener(this::handleTouch);
            windowManager.addView(overlayView, wmParams);
            isShowing = true;
            handler.post(refreshRunnable);
        } catch (Throwable t) {
            showFallback(startX, startY);
        }
    }

    private void showFallback(int startX, int startY) {
        if (isShowing) return;
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        try {
            overlayView = LayoutInflater.from(activity).inflate(layoutRes(), null);
            textView = overlayView.findViewById(R.id.stats_text);
            refreshText();
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.leftMargin = Math.max(0, startX);
            params.topMargin = Math.max(0, startY);
            overlayView.setOnTouchListener(this::handleTouchFallback);
            root.addView(overlayView, params);
            isShowing = true;
            handler.post(refreshRunnable);
        } catch (Throwable ignored) {
            // A readout must never take the session down; leaving it off is acceptable.
        }
    }

    protected void refreshText() {
        if (textView == null) return;
        try {
            textView.setText(buildText());
        } catch (Throwable ignored) {
            textView.setText(activity.getString(R.string.mod_overlay_data_unavailable));
        }
    }

    public void hide() {
        isShowing = false;
        handler.removeCallbacks(refreshRunnable);
        View view = overlayView;
        overlayView = null;
        textView = null;
        if (view == null) return;
        try {
            if (view.getParent() != null) windowManager.removeViewImmediate(view);
        } catch (Throwable ignored) {
        }
    }

    /** Re-applies lock/opacity/size after a settings change. */
    public void refresh() {
        locked = InbuiltModManager.getInstance(activity).isOverlayLocked(modId);
        if (overlayView != null) applyAppearance();
        refreshText();
    }

    /** Shows or hides the overlay for the current world/menu state. */
    public void setVisibility(int visibility) {
        if (overlayView != null) overlayView.setVisibility(visibility);
    }

    private void applyAppearance() {
        if (overlayView == null) return;
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        float alpha = Math.max(0.2f, manager.getOverlayOpacity(modId) / 100f);
        overlayView.setAlpha(alpha);
    }

    private boolean handleTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (locked) return false;
                initialX = wmParams.x;
                initialY = wmParams.y;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (locked) return false;
                float dx = event.getRawX() - initialTouchX;
                float dy = event.getRawY() - initialTouchY;
                if (!dragging && Math.hypot(dx, dy) > dragThreshold) dragging = true;
                if (dragging) {
                    wmParams.x = (int) (initialX + dx);
                    wmParams.y = (int) (initialY + dy);
                    try {
                        windowManager.updateViewLayout(overlayView, wmParams);
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    InbuiltModManager.getInstance(activity).setOverlayPosition(modId, wmParams.x, wmParams.y);
                }
                dragging = false;
                return true;
            default:
                return false;
        }
    }

    private boolean handleTouchFallback(View v, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP && overlayView != null) {
            ViewGroup.LayoutParams lp = overlayView.getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams) {
                InbuiltModManager.getInstance(activity)
                        .setOverlayPosition(modId, ((FrameLayout.LayoutParams) lp).leftMargin,
                                ((FrameLayout.LayoutParams) lp).topMargin);
            }
        }
        return true;
    }
}
