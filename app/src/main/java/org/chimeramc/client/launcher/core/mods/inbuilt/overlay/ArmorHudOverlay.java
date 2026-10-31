package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
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
 * On-screen armor durability readout for the player and, optionally, the current target.
 *
 * <p>Self and target are drawn as two rows of four slot cells. Each cell fills from the bottom
 * with the piece's remaining durability and carries a letter per enchantment. When the data
 * source has published nothing the HUD draws a dimmed "—" per cell, so an absent feed is never
 * mistaken for full durability.
 *
 * <p>Draggable in HUD-editor mode only, matching the other overlays; position persists through
 * {@link InbuiltModManager#setOverlayPosition}.
 */
public final class ArmorHudOverlay {
    private static final float DRAG_THRESHOLD = 10f;
    private static final int DEFAULT_REFRESH_MS = 100;

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
    private long lastRefreshAt;
    private int refreshMs = DEFAULT_REFRESH_MS;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isShowing) return;
            if (overlayView != null) overlayView.invalidate();
            handler.postDelayed(this, refreshMs);
        }
    };

    public ArmorHudOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public void show(int startX, int startY) {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        ArmorHudView view = new ArmorHudView(activity);
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
        refreshMs = InbuiltModManager.getInstance(activity).getArmorHudRefreshMs();
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
        refreshMs = InbuiltModManager.getInstance(activity).getArmorHudRefreshMs();
        isLocked = InbuiltModManager.getInstance(activity).isOverlayLocked(ModIds.ARMOR_HUD);
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
        if (wmParams != null && windowManager != null) {
            OverlayBounds.Position position = OverlayBounds.clampPosition(activity, overlayView, x, y);
            wmParams.x = position.x;
            wmParams.y = position.y;
            windowManager.updateViewLayout(overlayView, wmParams);
        } else if (overlayView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
            OverlayBounds.Position position = OverlayBounds.clampPosition(activity, overlayView, x, y);
            params.leftMargin = position.x;
            params.topMargin = position.y;
            overlayView.setLayoutParams(params);
        }
    }

    private boolean handleTouch(View v, MotionEvent event) {
        return handleTouchInternal(event, wmParams != null ? wmParams.x : 0, wmParams != null ? wmParams.y : 0, false);
    }

    private boolean handleTouchFallback(View v, MotionEvent event) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
        return handleTouchInternal(event, params.leftMargin, params.topMargin, true);
    }

    private boolean handleTouchInternal(MotionEvent event, int baseX, int baseY, boolean fallback) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isHudEditorMode) {
                    InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
                    if (manager != null) manager.selectHudEditorDisplay(ModIds.ARMOR_HUD);
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
                if (isDragging && isHudEditorMode) {
                    savePosition();
                }
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
        if (wmParams != null) {
            InbuiltModManager.getInstance(activity).setOverlayPosition(ModIds.ARMOR_HUD, wmParams.x, wmParams.y);
        } else if (overlayView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
            InbuiltModManager.getInstance(activity).setOverlayPosition(ModIds.ARMOR_HUD, params.leftMargin, params.topMargin);
        }
    }

    private final class ArmorHudView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cell = new RectF();
        private final float density;

        ArmorHudView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);
            setAlpha(0.92f);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            float cellSize = 22f * density;
            float gap = 4f * density;
            float pad = 6f * density;
            int rows = ArmorHudMod.isShowTarget() ? 2 : 1;
            int width = (int) (pad * 2 + cellSize * ArmorHudMod.SLOT_COUNT + gap * (ArmorHudMod.SLOT_COUNT - 1));
            int height = (int) (pad * 2 + cellSize * rows + gap * (rows - 1));
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            ArmorHudMod.Snapshot snapshot = ArmorHudMod.read();
            float cellSize = 22f * density;
            float gap = 4f * density;
            float pad = 6f * density;

            canvas.drawColor(0x66000000);
            drawRow(canvas, snapshot.available ? snapshot.self : null, pad, pad, cellSize, gap);
            if (ArmorHudMod.isShowTarget()) {
                float y = pad + cellSize + gap;
                drawRow(canvas, snapshot.available ? snapshot.target : null, pad, y, cellSize, gap);
            }
        }

        private void drawRow(Canvas canvas, ArmorHudMod.Piece[] pieces, float x, float y,
                             float cellSize, float gap) {
            for (int i = 0; i < ArmorHudMod.SLOT_COUNT; i++) {
                float left = x + i * (cellSize + gap);
                drawCell(canvas, pieces != null ? pieces[i] : null, left, y, cellSize);
            }
        }

        private void drawCell(Canvas canvas, ArmorHudMod.Piece piece, float left, float top, float size) {
            cell.set(left, top, left + size, top + size);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x33FFFFFF);
            canvas.drawRoundRect(cell, 3f * density, 3f * density, paint);

            if (piece == null || !piece.present) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1f * density);
                paint.setColor(0x55FFFFFF);
                canvas.drawRoundRect(cell, 3f * density, 3f * density, paint);

                textPaint.setColor(0x88FFFFFF);
                textPaint.setTextSize(12f * density);
                canvas.drawText("—", cell.centerX(),
                        cell.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
                return;
            }

            // Fill from the bottom so the bar reads as "remaining durability".
            float fraction = piece.fraction();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(durabilityColor(fraction));
            RectF fill = new RectF(cell.left, cell.bottom - cell.height() * fraction, cell.right, cell.bottom);
            canvas.drawRoundRect(fill, 3f * density, 3f * density, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f * density);
            paint.setColor(0xAAFFFFFF);
            canvas.drawRoundRect(cell, 3f * density, 3f * density, paint);

            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextSize(10f * density);
            String label = piece.maxDurability > 0
                    ? Integer.toString(Math.max(0, piece.durability))
                    : "?";
            canvas.drawText(label, cell.centerX(),
                    cell.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);

            if (ArmorHudMod.isShowEnchants() && piece.enchantIds.length > 0) {
                textPaint.setColor(0xFFB388FF);
                textPaint.setTextSize(8f * density);
                StringBuilder glyphs = new StringBuilder();
                for (int id : piece.enchantIds) {
                    glyphs.append(ArmorHudMod.glyphFor(id));
                }
                canvas.drawText(glyphs.toString(), cell.centerX(), cell.top + 8f * density, textPaint);
            }
        }

        private int durabilityColor(float fraction) {
            if (fraction <= 0.25f) return 0xCCE53935;
            if (fraction <= 0.5f) return 0xCCFB8C00;
            return 0xCC43A047;
        }
    }
}
