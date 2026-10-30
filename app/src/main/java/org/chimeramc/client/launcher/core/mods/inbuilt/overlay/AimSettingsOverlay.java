package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import org.chimeramc.client.util.PersonalizationManager;

/**
 * Center-screen crosshair + hit-flash feedback for the hit-registration module.
 * Drawn as a lightweight full-window overlay (non-touchable) so it can animate on the
 * same UI thread as the rest of the game overlay without stealing input.
 */
public class AimSettingsOverlay {
    private final Activity activity;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private CrosshairView view;
    private WindowManager.LayoutParams wmParams;
    private boolean showing;
    private volatile long lastFlashAt;

    public AimSettingsOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public void show() {
        if (showing || activity.isFinishing() || activity.isDestroyed()) return;
        view = new CrosshairView(activity);
        wmParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        wmParams.gravity = Gravity.TOP | Gravity.START;
        wmParams.token = activity.getWindow().getDecorView().getWindowToken();
        try {
            windowManager.addView(view, wmParams);
            showing = true;
        } catch (Exception ignored) {
            showing = false;
        }
    }

    public void hide() {
        if (!showing) return;
        try {
            if (view != null) windowManager.removeView(view);
        } catch (Exception ignored) {
        }
        view = null;
        showing = false;
    }

    public boolean isShowing() {
        return showing;
    }

    /** Trigger a short glow flash around the crosshair. Coalesces rapid triggers. */
    public void flash() {
        if (!showing || view == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastFlashAt < 90L) return;
        lastFlashAt = now;
        view.beginFlash();
        view.invalidate();
        handler.removeCallbacks(view);
        handler.postDelayed(view, 600L);
    }

    private final class CrosshairView extends View implements Runnable {
        private final Paint reticle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        // The flash glow runs on its own 40ms loop, so its Paint is a field: allocating one per
        // frame is exactly the kind of churn an overlay that shares the game's frame budget must
        // not add.
        private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path flashPath = new Path();
        private float flashStrength;
        private final float density;

        CrosshairView(android.content.Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            reticle.setColor(Color.WHITE);
            reticle.setStyle(Paint.Style.FILL);
            reticle.setStrokeWidth(1.6f * density);
            ring.setColor(Color.WHITE);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(1.2f * density);
            ring.setAlpha(150);
            setAlpha(0.9f);
        }

        void beginFlash() {
            flashStrength = 1f;
        }

        @Override
        public void run() {
            flashStrength = Math.max(0f, flashStrength - 0.18f);
            if (flashStrength <= 0f) return;
            invalidate();
            handler.postDelayed(this, 40L);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            boolean showReticle = AimSettingsMod.isCrosshairEnabled();
            if (!showReticle && flashStrength <= 0f) return;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            int color = AimSettingsMod.getCrosshairColor();

            if (showReticle) {
                reticle.setColor(color);
                reticle.setAlpha(230);
                switch (AimSettingsMod.getCrosshairStyle()) {
                    case AimSettingsMod.STYLE_DOT:
                        canvas.drawCircle(cx, cy, 3.2f * density, reticle);
                        break;
                    case AimSettingsMod.STYLE_CIRCLE:
                        ring.setColor(color);
                        ring.setAlpha(230);
                        ring.setStyle(Paint.Style.STROKE);
                        canvas.drawCircle(cx, cy, 14f * density, ring);
                        canvas.drawCircle(cx, cy, 2f * density, reticle);
                        break;
                    case AimSettingsMod.STYLE_CROSS:
                    default:
                        float gap = 9f * density;
                        float arm = 7f * density;
                        canvas.drawRect(cx - arm, cy - 1.2f * density, cx - gap + 2f * density, cy + 1.2f * density, reticle);
                        canvas.drawRect(cx + gap - 2f * density, cy - 1.2f * density, cx + arm, cy + 1.2f * density, reticle);
                        canvas.drawRect(cx - 1.2f * density, cy - arm, cx + 1.2f * density, cy - gap + 2f * density, reticle);
                        canvas.drawRect(cx - 1.2f * density, cy + gap - 2f * density, cx + 1.2f * density, cy + arm, reticle);
                        canvas.drawCircle(cx, cy, 2.4f * density, reticle);
                        ring.setColor(color);
                        ring.setAlpha(120);
                        ring.setStyle(Paint.Style.STROKE);
                        canvas.drawCircle(cx, cy, 22f * density, ring);
                        break;
                }
            }

            if (flashStrength > 0f) {
                int accent = flashAccent();
                flashPaint.setStyle(Paint.Style.STROKE);
                flashPaint.setStrokeWidth(3.2f * density * flashStrength);
                flashPaint.setColor(accent);
                flashPaint.setAlpha((int) (200 * flashStrength));
                canvas.drawCircle(cx, cy, 26f * density, flashPaint);
                canvas.drawCircle(cx, cy, 12f * density, flashPaint);
            }
        }

        private int flashAccent() {
            int accent = AimSettingsMod.getCrosshairColor();
            if (accent != 0) return accent;
            try {
                int themed = new PersonalizationManager(activity).getAccentColor();
                if (themed != 0) return themed;
            } catch (Throwable ignored) {
            }
            return 0xFF0F5BE4;
        }
    }
}