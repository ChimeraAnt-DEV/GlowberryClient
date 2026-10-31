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

import org.chimeramc.client.core.voice.VoiceChatModule;
import org.chimeramc.client.core.voice.VoicePeer;
import org.chimeramc.client.util.MainThread;

import java.util.List;

/**
 * Full-screen overlay that draws the in-world Voice nametag icons.
 *
 * <p>Like the Hitboxes overlay this is not a draggable widget: the icons sit beside other players'
 * nametags in world space, so the whole screen is the canvas. It is created
 * {@code FLAG_NOT_TOUCHABLE} so it can never eat a tap or a look gesture.
 *
 * <p>The camera comes from {@link CameraSource}; the tags come from
 * {@link VoiceNametagMod.TagSource}. Both are seams, and with either missing nothing is drawn. The
 * level and self-mute state are <em>not</em> seams: they come from the running
 * {@link VoiceChatModule}, whose audio engine already measures the real microphone RMS that the
 * animation is driven from.
 *
 * <p>Three states, matching {@link NametagMicState}: an X when muted (by them or by you), a
 * green fill that grows with their live volume while speaking, and a neutral static glyph when
 * idle. The muted X is drawn for a local mute exactly as for a self-mute on purpose: the icon says
 * whether you can hear them, and in both cases you cannot. The muted peer is never told.
 */
public final class VoiceNametagOverlay {

    private static final int REFRESH_MS = 33;

    private static final int COLOR_IDLE = 0xFFC8CED6;
    private static final int COLOR_SPEAKING = 0xFF6BD68A;
    private static final int COLOR_MUTED = 0xFFE5484D;
    private static final int COLOR_BACKDROP = 0x99000000;

    /** Supplies the current camera, or null when the world is not visible. */
    public interface CameraSource {
        HitboxProjector.Camera camera(int screenWidth, int screenHeight);
    }

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

    public VoiceNametagOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public void show() {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        NametagView view = new NametagView(activity);
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

    /** Re-reads config; the level/mute state is read fresh each frame, so nothing else to do. */
    public void applyConfigurationChanges() {
        if (overlayView != null) overlayView.invalidate();
    }

    /** No draggable widget, so HUD-editor mode has no effect. */
    public void setHudEditorMode(boolean active) {
    }

    public void setOverlayVisibility(int visibility) {
        if (overlayView != null && overlayView.getVisibility() != visibility) {
            overlayView.setVisibility(visibility);
        }
    }

    /**
     * The camera seam. Static because the overlay is created from the activity but the feed is
     * shared with the Hitboxes module, which installs one source for both.
     */
    private static volatile CameraSource cameraSource;

    public static void setCameraSource(CameraSource source) {
        cameraSource = source;
    }

    /**
     * The installed camera feed, shared with the Hitboxes module.
     *
     * <p>Exposed so the peer-feed hitbox view uses the same camera the nametag icons do. Two
     * cameras would drift apart and the boxes would not line up with the world.
     */
    public static CameraSource cameraSource() {
        return cameraSource;
    }

    private final class NametagView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint spritePaint = new Paint();
        private final RectF rect = new RectF();
        private final float density;

        NametagView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.FILL);
            // Blocky sprite, smooth ring: anti-aliasing is set per pass below.
            spritePaint.setAntiAlias(false);
            spritePaint.setFilterBitmap(false);
            spritePaint.setStyle(Paint.Style.FILL);
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!VoiceNametagMod.isActive()) return;
            CameraSource source = cameraSource;
            if (source == null) return;
            HitboxProjector.Camera camera;
            try {
                camera = source.camera(getWidth(), getHeight());
            } catch (Throwable t) {
                return;
            }
            if (camera == null) return;

            List<NametagIconProjector.Tag> tags = VoiceNametagMod.readTags();
            if (tags.isEmpty()) return;
            List<NametagIconProjector.Placed> placed =
                    NametagIconProjector.project(camera, tags, listenerChannel());
            if (placed.isEmpty()) return;

            VoiceChatModule module = VoiceChatModule.peek();
            for (NametagIconProjector.Placed icon : placed) {
                VoicePeer peer = module == null ? null : module.findPeer(icon.peerId);
                float level = peer == null ? 0f : peer.level;
                boolean selfMuted = peer != null && peer.selfMuted;
                boolean mutedByYou = module != null && module.isMuted(icon.peerId);
                NametagMicState.State resolved =
                        NametagMicState.resolve(level, selfMuted, mutedByYou);
                drawIcon(canvas, icon, resolved, level);
            }
        }

        private String listenerChannel() {
            VoiceChatModule module = VoiceChatModule.peek();
            return module == null ? org.chimeramc.client.core.voice.VoiceChannel.WORLD
                    : module.channel();
        }

        private void drawIcon(Canvas canvas, NametagIconProjector.Placed icon,
                              NametagMicState.State state, float level) {
            float size = icon.size;
            float left = icon.centerX - size / 2f;
            float top = icon.centerY - size / 2f;

            rect.set(left, top, left + size, top + size);
            paint.setAntiAlias(true);
            paint.setColor(COLOR_BACKDROP);
            canvas.drawRoundRect(rect, size * 0.22f, size * 0.22f, paint);
            paint.setAntiAlias(false);

            int cell = Math.max(1, (int) (size / MicIndicatorView.GRID));
            float artW = cell * (float) MicIndicatorView.GRID;
            float artLeft = left + (size - artW) / 2f;
            float artTop = top + (size - artW) / 2f;

            if (state == NametagMicState.State.MUTED) {
                spritePaint.setColor(COLOR_MUTED);
                drawSprite(canvas, artLeft, artTop, cell);
                drawMuteCross(canvas, artLeft, artTop, cell);
                return;
            }

            // Fill from the bottom up, exactly like the HUD indicator: the green climbs the sprite
            // as the peer gets louder, so "how much green" is a reading of their volume rather than
            // a binary talking/not-talking. With animation off, it is all-or-nothing.
            float fill = state == NametagMicState.State.SPEAKING ? 1f
                    : (VoiceNametagMod.isAnimated() ? MicIconStyle.speakingFill(level) : 0f);
            if (state == NametagMicState.State.SPEAKING && VoiceNametagMod.isAnimated()) {
                fill = Math.max(0.15f, MicIconStyle.speakingFill(level));
            }
            int filledRows = Math.round(fill * MicIndicatorView.GRID);

            String[] sprite = MicIndicatorView.sprite();
            for (int row = 0; row < sprite.length; row++) {
                String line = sprite[row];
                boolean inFill = (MicIndicatorView.GRID - 1 - row) < filledRows;
                spritePaint.setColor(inFill ? COLOR_SPEAKING : COLOR_IDLE);
                for (int col = 0; col < line.length(); col++) {
                    if (line.charAt(col) != '#') continue;
                    float x = artLeft + col * cell;
                    float y = artTop + row * cell;
                    canvas.drawRect(x, y, x + cell, y + cell, spritePaint);
                }
            }
        }

        private void drawSprite(Canvas canvas, float left, float top, int cell) {
            String[] sprite = MicIndicatorView.sprite();
            for (int row = 0; row < sprite.length; row++) {
                String line = sprite[row];
                for (int col = 0; col < line.length(); col++) {
                    if (line.charAt(col) != '#') continue;
                    float x = left + col * cell;
                    float y = top + row * cell;
                    canvas.drawRect(x, y, x + cell, y + cell, spritePaint);
                }
            }
        }

        private void drawMuteCross(Canvas canvas, float left, float top, int cell) {
            paint.setAntiAlias(false);
            paint.setColor(COLOR_MUTED);
            for (int i = 0; i < MicIndicatorView.GRID; i++) {
                drawCell(canvas, left, top, cell, i, i);
                drawCell(canvas, left, top, cell, i, MicIndicatorView.GRID - 1 - i);
            }
        }

        private void drawCell(Canvas canvas, float left, float top, int cell, int col, int row) {
            float x = left + col * cell;
            float y = top + row * cell;
            canvas.drawRect(x, y, x + cell, y + cell, paint);
        }
    }
}
