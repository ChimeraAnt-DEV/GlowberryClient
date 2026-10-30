package org.chimeramc.client.core.replay;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.chimeramc.client.core.mods.inbuilt.overlay.HitTimingMod;

/**
 * The metadata burn-in layer.
 *
 * <p>Because the recording is a full-screen {@code MediaProjection} capture, the honest way to burn
 * metadata into the clip is to <em>show</em> it while recording — anything drawn here lands in the
 * file. It is a real window (attached to the game activity, the same way the other in-game overlays
 * are) rather than a post-process step, so nothing has to re-encode the video.
 *
 * <p>It is created by {@link ReplayManager} for the duration of a capture and torn down with it, so
 * the layer cannot outlive the recording. A watermark is the same layer's corner mark, on when the
 * player asked for it.
 */
final class ReplayMetadataOverlay {

    private static final long TICK_MS = 1_000L;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private LinearLayout root;
    private TextView metadataText;
    private TextView watermark;
    private WindowManager.LayoutParams params;
    private boolean showing;
    private long startedAtMs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!showing) return;
            update();
            handler.postDelayed(this, TICK_MS);
        }
    };

    ReplayMetadataOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
    }

    /** Attaches the layer. Safe to call twice. */
    void attach(boolean burnMetadata, boolean showWatermark, long startedAtMs) {
        if (showing || activity.isFinishing() || activity.isDestroyed()) return;
        this.startedAtMs = startedAtMs;
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.END);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        metadataText = new TextView(activity);
        metadataText.setTextColor(Color.WHITE);
        metadataText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        metadataText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        metadataText.setShadowLayer(dp(2), 0f, dp(1), Color.argb(200, 0, 0, 0));
        metadataText.setVisibility(burnMetadata ? View.VISIBLE : View.GONE);
        root.addView(metadataText);

        watermark = new TextView(activity);
        watermark.setText("GlowberryClient");
        watermark.setTextColor(Color.argb(150, 255, 255, 255));
        watermark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        watermark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        watermark.setShadowLayer(dp(2), 0f, dp(1), Color.argb(180, 0, 0, 0));
        watermark.setVisibility(showWatermark ? View.VISIBLE : View.GONE);
        root.addView(watermark);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.y = dp(48);
        try {
            params.token = activity.getWindow().getDecorView().getWindowToken();
            windowManager.addView(root, params);
        } catch (Throwable t) {
            root = null;
            return;
        }
        showing = true;
        update();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
    }

    /** Detaches the layer. Safe to call when not attached. */
    void detach() {
        showing = false;
        handler.removeCallbacks(tick);
        if (root != null && windowManager != null) {
            try {
                windowManager.removeView(root);
            } catch (Throwable ignored) {
                // Already gone; nothing to do.
            }
        }
        root = null;
    }

    private void update() {
        if (metadataText == null || metadataText.getVisibility() != View.VISIBLE) return;
        int fps = 0;
        try {
            if (org.chimeramc.client.launcher.core.mods.inbuilt.nativemod.FpsMod.nativeIsInitialized()) {
                fps = org.chimeramc.client.launcher.core.mods.inbuilt.nativemod.FpsMod.nativeGetFps();
            }
        } catch (Throwable ignored) {
            // The em dash is the honest fallback for a native read that is not ready.
        }
        int combo = 0;
        try {
            if (HitTimingMod.isActive()) combo = HitTimingMod.getCombo();
        } catch (Throwable ignored) {
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - startedAtMs);
        String line = ReplayMetadata.line(ReplayMetadata.defaultFields(), fps, 0, elapsed, -1, combo);
        metadataText.setText(line);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }
}
