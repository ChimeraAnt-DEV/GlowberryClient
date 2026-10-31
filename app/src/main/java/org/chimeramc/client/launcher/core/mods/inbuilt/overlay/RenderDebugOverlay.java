package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.chimeramc.client.core.cosmetics.CosmeticsDiagnosticsReport;
import org.chimeramc.client.core.cosmetics.RenderDebugLines;
import org.chimeramc.client.ui.components.UiTokens;
import org.chimeramc.client.util.MainThread;

import java.util.List;

/**
 * The live cosmetics-render debug overlay: a small translucent panel that prints the native render
 * values and refreshes once a second, so a device can watch the draw path in third person without
 * leaving the game.
 *
 * <p>It is the on-device instrument for "cosmetics still not showing". It reads the same values the
 * diagnostics screen does (via {@link CosmeticsDiagnosticsReport}) and formats them through the pure
 * {@link RenderDebugLines}, so the wording is unit-tested. It draws nothing when the toggle is off.
 */
public final class RenderDebugOverlay {

    private final Context context;
    private final TextView text;
    private final LinearLayout root;
    private final Handler handler = MainThread.handler();
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            refresh();
            handler.postDelayed(this, 1000L);
        }
    };

    private RenderDebugOverlay(Context context) {
        this.context = context;
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(UiTokens.withAlpha(0xFF000000, 0xC0));
        bg.setCornerRadius(dp(8));
        bg.setStroke(Math.max(1, dp(1)), UiTokens.withAlpha(0xFFFFFFFF, 0x33));
        root.setBackground(bg);

        text = new TextView(context);
        text.setTextColor(UiTokens.TEXT_PRIMARY);
        text.setTextSize(11f);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(text);
    }

    /** A view to add to a window, sized to its content. */
    public View getView() {
        return root;
    }

    /** Starts refreshing (once a second) while the overlay is shown. */
    public void start() {
        running = true;
        refresh();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000L);
    }

    /** Stops refreshing. */
    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    private void refresh() {
        try {
            CosmeticsDiagnosticsReport.Inputs in =
                    CosmeticsDiagnosticsReport.readInputs(context);
            RenderDebugLines.Values v = new RenderDebugLines.Values();
            v.nativeLoaded = in.nativeLoaded;
            v.renderSlot = in.renderSlot;
            v.hookInstalled = in.hookInstalled;
            v.framesSeen = in.framesSeen;
            v.capeEngineRouteLive = in.capeEngineRouteLive;
            v.glBackend = in.glBackend;
            v.eglContextCurrent = in.eglContextCurrent;
            v.geometryBytes = in.geometryBytes;
            v.pieceAtlasMask = in.pieceAtlasMask;
            List<String> lines = RenderDebugLines.lines(v);
            StringBuilder b = new StringBuilder();
            for (String line : lines) b.append(line).append('\n');
            text.setText(b.toString().trim());
        } catch (Throwable t) {
            text.setText("render debug unavailable");
        }
    }

    private int dp(float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Creates the overlay and adds it to a window, positioned top-start. The caller owns its
     * lifetime (add once, remove on hide) and calls {@link #start()}/{@link #stop()}.
     */
    public static RenderDebugOverlay attach(Context context, FrameLayout host) {
        if (context == null || host == null) return null;
        RenderDebugOverlay overlay = new RenderDebugOverlay(context);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        lp.topMargin = (int) (72 * context.getResources().getDisplayMetrics().density);
        lp.leftMargin = (int) (12 * context.getResources().getDisplayMetrics().density);
        host.addView(overlay.getView(), lp);
        return overlay;
    }
}
