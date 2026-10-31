package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.core.mods.inbuilt.model.ModIds;
import org.chimeramc.client.core.voice.VoiceChatModule;
import org.chimeramc.client.util.MainThread;

/**
 * The bottom-right in-game microphone indicator.
 *
 * <p>A blocky {@link MicIndicatorView} driven by the live microphone level, plus a tiny text
 * readout of who can hear you. The pixel meter is the point: muted shows the red X immediately,
 * talking fills green from the bottom up with the audio actually being sent, and idle sits
 * neutral. The readout answers the other question the meter cannot -- how many players and which
 * channel -- without turning the corner of the screen into a panel.
 *
 * <p>Draggable in HUD-editor mode like the other overlays. In normal play it never takes a touch
 * (the drag listener declines outside the editor), so it cannot swallow a look gesture.
 */
public final class VoiceChatOverlay {
    private static final int REFRESH_MS = 100;
    private static final float DRAG_THRESHOLD = 10f;

    private final Activity activity;
    private final WindowManager windowManager;
    private final Handler handler = MainThread.handler();

    private View overlayView;
    private MicIndicatorView micView;
    private WindowManager.LayoutParams wmParams;
    private boolean isShowing;
    private boolean isHudEditorMode;
    private boolean isLocked;

    private float initialX, initialY, initialTouchX, initialTouchY;
    private boolean isDragging;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isShowing) return;
            updateMeter();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    public VoiceChatOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public void show(int startX, int startY) {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        View view = buildView(activity);
        try {
            wmParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            wmParams.gravity = Gravity.TOP | Gravity.START;
            OverlayBounds.Position position = OverlayBounds.clampPosition(activity, view, startX, startY);
            wmParams.x = position.x;
            wmParams.y = position.y;
            wmParams.token = activity.getWindow().getDecorView().getWindowToken();
            view.setOnTouchListener(this::handleTouch);
            windowManager.addView(view, wmParams);
            overlayView = view;
        } catch (Exception e) {
            wmParams = null;
            ViewGroup root = activity.findViewById(android.R.id.content);
            if (root == null) return;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.TOP | Gravity.START;
            OverlayBounds.Position position = OverlayBounds.clampPosition(activity, view, startX, startY);
            params.leftMargin = position.x;
            params.topMargin = position.y;
            view.setOnTouchListener(this::handleTouchFallback);
            root.addView(view, params);
            overlayView = view;
        }
        isShowing = true;
        isLocked = InbuiltModManager.getInstance(activity).isOverlayLocked(ModIds.VOICE_CHAT);
        updateMeter();
        handler.post(refreshRunnable);
    }

    private View buildView(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        micView = new MicIndicatorView(context);
        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(
                (int) (30 * context.getResources().getDisplayMetrics().density),
                (int) (30 * context.getResources().getDisplayMetrics().density));
        micParams.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(micView, micParams);

        return column;
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
        micView = null;
        wmParams = null;
    }

    public boolean isShowing() {
        return isShowing;
    }

    public void applyConfigurationChanges() {
        isLocked = InbuiltModManager.getInstance(activity).isOverlayLocked(ModIds.VOICE_CHAT);
        updateMeter();
    }

    public void setHudEditorMode(boolean active) {
        isHudEditorMode = active;
    }

    public void setOverlayVisibility(int visibility) {
        if (overlayView != null && overlayView.getVisibility() != visibility) {
            overlayView.setVisibility(visibility);
        }
    }

    /**
     * Pushes the current muted/level state into the meter.
     *
     * <p>Read straight from the module each tick rather than pushed by the audio thread: the
     * capture thread must never touch a View, and sampling at 10 Hz is faster than the eye.
     */
    private void updateMeter() {
        MicIndicatorView view = micView;
        if (view == null) return;
        VoiceChatModule module = VoiceChatModule.peek();
        if (module == null || !module.isRunning()) {
            view.setMuted(true);
            view.setLevel(0f);
            return;
        }
        view.setMuted(!module.isTransmitting());
        view.setLevel(module.rawMicLevel());
    }

    private boolean handleTouch(View view, MotionEvent event) {
        if (isLocked || !isHudEditorMode) return false;
        return drag(view, event, true);
    }

    private boolean handleTouchFallback(View view, MotionEvent event) {
        if (isLocked || !isHudEditorMode) return false;
        return drag(view, event, false);
    }

    private boolean drag(View view, MotionEvent event, boolean windowMode) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                initialX = windowMode ? wmParams.x : view.getLeft();
                initialY = windowMode ? wmParams.y : view.getTop();
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                isDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - initialTouchX;
                float dy = event.getRawY() - initialTouchY;
                if (!isDragging && Math.hypot(dx, dy) < DRAG_THRESHOLD) return true;
                isDragging = true;
                int newX = (int) (initialX + dx);
                int newY = (int) (initialY + dy);
                if (windowMode && wmParams != null) {
                    wmParams.x = newX;
                    wmParams.y = newY;
                    try {
                        windowManager.updateViewLayout(view, wmParams);
                    } catch (Exception ignored) {
                    }
                } else {
                    ViewGroup.LayoutParams params = view.getLayoutParams();
                    if (params instanceof FrameLayout.LayoutParams) {
                        ((FrameLayout.LayoutParams) params).leftMargin = newX;
                        ((FrameLayout.LayoutParams) params).topMargin = newY;
                        view.setLayoutParams(params);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging) {
                    InbuiltModManager.getInstance(activity).setOverlayPosition(
                            ModIds.VOICE_CHAT, windowMode ? wmParams.x : view.getLeft(),
                            windowMode ? wmParams.y : view.getTop());
                }
                isDragging = false;
                return true;
            default:
                return false;
        }
    }
}
