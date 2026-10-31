package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.core.mods.inbuilt.model.ModIds;
import org.chimeramc.client.util.MainThread;

/**
 * Top-of-screen hit-timing indicator for the Select Hit module.
 *
 * <p>A deliberately small pill centred at the top of the screen: green while a hit will land,
 * red while the post-hit window is still open. It is sized so it does not cover the crosshair
 * area or the hotbar, and it carries the combo count beside it.
 *
 * <p>Draggable in HUD-editor mode only, matching the other overlays; position persists through
 * {@link InbuiltModManager#setOverlayPosition}.

 * <p><b>What the combo count is, and is not.</b> The counter beside the pill counts the player's
 * own attacks that landed inside the timing window, which this module times from the input path.
 * It is <em>not</em> the game's server-side combo/combo-counter: reading that would need a live
 * per-frame feed from {@code libminecraftpe.so}, and the mask-scan anchors used for the local
 * player position do not expose a combo field in the shipped builds. Showing the local counter is
 * the honest deliverable - it reflects real input timing and never fabricates a value - and the
 * scope note says as much in the dialog.
 */
public final class HitTimingOverlay {
    private static final float DRAG_THRESHOLD = 10f;

    /** The three state verbs, as constants so the draw path never concatenates. */
    private static final String LABEL_READY = "READY";
    private static final String LABEL_HIT = "HIT";
    private static final String LABEL_WAIT = "WAIT";

    private static final int REFRESH_MS = 33;

    private final Activity activity;
    private final WindowManager windowManager;
    private final Handler handler = MainThread.handler();

    private View overlayView;
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
            if (overlayView != null) overlayView.invalidate();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    public HitTimingOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public void show(int startX, int startY) {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        HitTimingView view = new HitTimingView(activity);
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
        isLocked = InbuiltModManager.getInstance(activity).isOverlayLocked(ModIds.HIT_TIMING);
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
        isLocked = InbuiltModManager.getInstance(activity).isOverlayLocked(ModIds.HIT_TIMING);
        if (overlayView != null) overlayView.invalidate();
    }

    public void setHudEditorMode(boolean active) {
        isHudEditorMode = active;
    }

    public void setOverlayVisibility(int visibility) {
        if (overlayView != null && overlayView.getVisibility() != visibility) {
            overlayView.setVisibility(visibility);
        }
    }

    public void updatePosition(int x, int y) {
        if (overlayView == null) return;
        OverlayBounds.Position position = OverlayBounds.clampPosition(activity, overlayView, x, y);
        if (wmParams != null && windowManager != null) {
            wmParams.x = position.x;
            wmParams.y = position.y;
            windowManager.updateViewLayout(overlayView, wmParams);
        } else if (overlayView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
            params.leftMargin = position.x;
            params.topMargin = position.y;
            overlayView.setLayoutParams(params);
        }
    }

    private boolean handleTouch(View v, MotionEvent event) {
        return handleTouchInternal(event, wmParams != null ? wmParams.x : 0, wmParams != null ? wmParams.y : 0);
    }

    private boolean handleTouchFallback(View v, MotionEvent event) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
        return handleTouchInternal(event, params.leftMargin, params.topMargin);
    }

    private boolean handleTouchInternal(MotionEvent event, int baseX, int baseY) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isHudEditorMode) {
                    InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
                    if (manager != null) manager.selectHudEditorDisplay(ModIds.HIT_TIMING);
                }
                initialX = baseX;
                initialY = baseY;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                isDragging = false;
                if (overlayView != null && overlayView.getParent() != null) {
                    overlayView.getParent().requestDisallowInterceptTouchEvent(isHudEditorMode);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - initialTouchX;
                float dy = event.getRawY() - initialTouchY;
                if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                    if (isHudEditorMode) isDragging = true;
                }
                if (isDragging && isHudEditorMode) {
                    updatePosition((int) (initialX + dx), (int) (initialY + dy));
                }
                return isHudEditorMode || !isDragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging && isHudEditorMode) savePosition();
                isDragging = false;
                if (overlayView != null && overlayView.getParent() != null) {
                    overlayView.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            default:
                return false;
        }
    }

    private void savePosition() {
        if (overlayView == null) return;
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        if (wmParams != null) {
            manager.setOverlayPosition(ModIds.HIT_TIMING, wmParams.x, wmParams.y);
        } else if (overlayView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
            manager.setOverlayPosition(ModIds.HIT_TIMING, params.leftMargin, params.topMargin);
        }
    }

    private final class HitTimingView extends View {
        private static final int COLOR_READY = 0xFFF2F2F2;
        private static final int COLOR_HIT = 0xFF35D07F;
        private static final int COLOR_WAIT = 0xFFFF5A5F;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF pill = new RectF();
        private final RectF bar = new RectF();
        private final RectF comboPill = new RectF();
        private final RectF border = new RectF();
        private final float density;

        // onDraw runs at the refresh tick, so anything it builds is built ~30 times a second. The
        // gradient depends only on the view height, the border rect is a fixed inset, and the two
        // captions change only when their value does.
        private LinearGradient bodyGradient;
        private float gradientHeight = -1f;
        private long captionMs = Long.MIN_VALUE;
        private String captionText = "";
        private int comboValue = Integer.MIN_VALUE;
        private String comboText = "";

        /** Smoothed 0..1 fill so the bar tracks the window instead of snapping per frame. */
        private float shownProgress = 1f;
        private int lastColor;
        private boolean pulseRunning;

        HitTimingView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);
            textPaint.setTextSize(12f * density);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setFakeBoldText(true);
            labelPaint.setTextSize(9f * density);
            labelPaint.setLetterSpacing(0.12f);
            setAlpha(0.96f);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = (int) (104f * density);
            int h = (int) (30f * density);
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            HitTimingSolver.Decision decision =
                    HitTimingMod.evaluate(SystemClock.uptimeMillis());

            boolean idle = decision.isIdle();
            int color = idle ? COLOR_READY : (decision.isGreen() ? COLOR_HIT : COLOR_WAIT);

            float w = getWidth();
            float h = getHeight();
            float radius = h / 2f;

            // Smooth the bar rather than letting it reset between refresh ticks; the window is
            // a continuous countdown, so a stepped bar reads as a stutter.
            float target = decision.progress;
            shownProgress += (target - shownProgress) * 0.35f;

            // An idle indicator is deliberate: it fades rather than shouting a status it does
            // not have, so "not in combat yet" cannot be misread as "hit now".
            paint.setAlpha(idle ? 150 : 255);

            // Soft drop shadow so the pill stays legible over bright terrain.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x55000000);
            pill.set(0f, 1.5f * density, w, h + 1.5f * density);
            canvas.drawRoundRect(pill, radius, radius, paint);

            // Body: a dark translucent fill with a subtle top-lit gradient, rebuilt only when the
            // view height changes rather than on every frame.
            if (bodyGradient == null || gradientHeight != h) {
                bodyGradient = new LinearGradient(0f, 0f, 0f, h,
                        0xE61A1D24, 0xE60E1015, Shader.TileMode.CLAMP);
                gradientHeight = h;
            }
            paint.setShader(bodyGradient);
            pill.set(0f, 0f, w, h);
            canvas.drawRoundRect(pill, radius, radius, paint);
            paint.setShader(null);

            // Progress bar hugging the inside of the pill, growing with the elapsed window.
            if (HitTimingMod.isShowTimingBar()) {
                float inset = 3f * density;
                float trackH = h - inset * 2f;
                paint.setColor(0x26FFFFFF);
                bar.set(inset, inset, w - inset, h - inset);
                canvas.drawRoundRect(bar, trackH / 2f, trackH / 2f, paint);
                paint.setColor(color);
                paint.setAlpha(idle ? 90 : 255);
                bar.set(inset, inset, inset + (w - inset * 2f) * Math.max(0f, Math.min(1f, shownProgress)),
                        h - inset);
                canvas.drawRoundRect(bar, trackH / 2f, trackH / 2f, paint);
                paint.setAlpha(idle ? 150 : 255);
            }

            // Crisp 2dp border in the state colour, drawn inside the pill.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * density);
            paint.setColor(color);
            border.set(density, density, w - density, h - density);
            canvas.drawRoundRect(border, radius - density, radius - density, paint);
            paint.setStyle(Paint.Style.FILL);

            // Label: a short verb in the state colour. The remaining time is drawn as a
            // separate dim caption so the two do not compete for the same line.
            // Constants, not built per frame: the label is one of three fixed verbs and drawing
            // runs on the game's frame tick (item 17).
            String label = idle ? LABEL_READY : (decision.isGreen() ? LABEL_HIT : LABEL_WAIT);
            textPaint.setColor(color);
            float textCenterY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            float labelX = w / 2f;
            if (!idle && !decision.isGreen() && HitTimingMod.isShowCombo()) {
                // Shift the verb left to leave room for the countdown caption on the right.
                labelX = w * 0.38f;
            }
            canvas.drawText(label, labelX, textCenterY, textPaint);

            if (!idle && !decision.isGreen()) {
                labelPaint.setColor(0xCCFFFFFF);
                if (captionMs != decision.remainingMs) {
                    captionMs = decision.remainingMs;
                    captionText = captionMs + "ms";
                }
                float capCenterY = h / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f;
                canvas.drawText(captionText, w * 0.72f, capCenterY, labelPaint);
            }

            // Combo badge, drawn as a small pill on the leading edge so a streak is readable
            // at a glance without widening the main indicator.
            if (HitTimingMod.isShowCombo() && decision.combo > 1) {
                if (comboValue != decision.combo) {
                    comboValue = decision.combo;
                    comboText = comboValue + "x";
                }
                labelPaint.setColor(0xFF0E1015);
                float cw = labelPaint.measureText(comboText) + 8f * density;
                float ch = 14f * density;
                comboPill.set(w - cw - 3f * density, (h - ch) / 2f, w - 3f * density, (h + ch) / 2f);
                paint.setColor(color);
                canvas.drawRoundRect(comboPill, ch / 2f, ch / 2f, paint);
                float comboCenterY = (h / 2f) - (labelPaint.descent() + labelPaint.ascent()) / 2f;
                canvas.drawText(comboText, comboPill.centerX(), comboCenterY, labelPaint);
            }

            colorPulseIfChanged(color);
        }

        /**
         * A single short scale pulse when the state flips, so the change registers in peripheral
         * vision. Only ever one animator at a time, and never when animations are off.
         */
        private void colorPulseIfChanged(int color) {
            if (color == lastColor) return;
            lastColor = color;
            if (pulseRunning) return;
            if (!org.chimeramc.client.ui.animation.DynamicAnim.areAnimationsEnabled()) return;
            pulseRunning = true;
            animate().scaleX(1.06f).scaleY(1.06f).setDuration(70).withEndAction(() -> {
                animate().scaleX(1f).scaleY(1f).setDuration(110).withEndAction(() -> pulseRunning = false).start();
            }).start();
        }
    }
}
