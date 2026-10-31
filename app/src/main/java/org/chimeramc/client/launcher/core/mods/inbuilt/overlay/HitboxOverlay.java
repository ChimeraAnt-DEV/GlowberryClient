package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.core.mods.inbuilt.model.ModIds;
import org.chimeramc.client.util.MainThread;

/**
 * Full-screen overlay that draws the Hitboxes module's projected frame.
 *
 * <p>Unlike the other overlays this one is not a draggable widget: the boxes are positioned in
 * world space, so the whole screen is the canvas and there is nothing to move. It is
 * deliberately non-touchable so it can never eat a tap or a look gesture.
 *
 * <p>Colour contract, kept in one place because the module's value is that the colours mean
 * something: entity boxes are white, a box the crosshair is on turns blue, the critical-hit line
 * is red (blue when aimed at), and the combo box is red (blue when aimed at).
 */
public final class HitboxOverlay {
    private static final int REFRESH_MS = 33;
    /** How far a peer's look ray is drawn, in blocks. */
    private static final float LOOK_LINE_BLOCKS = 10f;

    private static final int COLOR_ENTITY = 0xFFF2F2F2;
    private static final int COLOR_AIMED = 0xFF3DA9FC;
    private static final int COLOR_CRIT = 0xFFE74C3C;
    private static final int COLOR_COMBO = 0xFFE74C3C;
    // One colour per entity class, so a busy fight stays readable: a green mob, an amber dropped
    // item, an orange thrown item and a violet projectile are told apart without reading labels.
    private static final int COLOR_MOB = 0xFF8BE36B;
    private static final int COLOR_ITEM = 0xFFFFD86B;
    private static final int COLOR_THROWN = 0xFFFF9F45;
    private static final int COLOR_PROJECTILE = 0xFFC08BFF;
    /** The chest-height target strip inside a peer box: a distinct, cooler colour than the box. */
    private static final int COLOR_TARGET = 0x9978C8FF;

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

    public HitboxOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public void show() {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        HitboxView view = new HitboxView(activity);
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
        // Nothing to position, so HUD-editor mode has no effect on this overlay.
    }

    public void setOverlayVisibility(int visibility) {
        if (overlayView != null && overlayView.getVisibility() != visibility) {
            overlayView.setVisibility(visibility);
        }
    }

    private final class HitboxView extends View {
        private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint notePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint noteBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF noteRect = new RectF();
        private final float density;

        HitboxView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setStrokeWidth(Math.max(1.5f, density));
            fillPaint.setStyle(Paint.Style.FILL);
            notePaint.setAntiAlias(true);
            notePaint.setColor(0xFFE8ECF1);
            notePaint.setTextSize(12f * density);
            notePaint.setTextAlign(Paint.Align.CENTER);
            noteBgPaint.setColor(0xCC12151B);
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            HitboxProjector.Frame frame = HitboxMod.readFrame();
            if (frame == null) {
                // No native entity feed -- but the peer feed (voice positions) needs no native
                // hook, so draw what the network actually reported before falling back to the
                // "waiting for game data" notice. Showing the notice while peer boxes are
                // available would claim the module is unwired when it is working.
                if (drawPeerBoxes(canvas)) return;
                // Only blame the missing native feed when there is no peer route either: with the
                // voice link running the module is working, it just has nobody in range, and the
                // notice would be a lie.
                if (HitboxMod.isAwaitingGameData() && !HitboxMod.hasPeerFeed()) {
                    drawAwaitingData(canvas);
                }
                return;
            }

            if (HitboxMod.isShowLookLine() && frame.lookLine != null) {
                boxPaint.setColor(COLOR_AIMED);
                boxPaint.setStrokeWidth(2f * density);
                canvas.drawLine(frame.lookLine.left, frame.lookLine.top,
                        frame.lookLine.right, frame.lookLine.bottom, boxPaint);
                boxPaint.setStrokeWidth(Math.max(1.5f, density));
            }

            for (HitboxProjector.Projected projected : frame.entities) {
                int color = projected.aimedAt ? COLOR_AIMED : colorFor(projected.kind);
                boxPaint.setColor(color);
                canvas.drawRect(projected.box.left, projected.box.top,
                        projected.box.right, projected.box.bottom, boxPaint);

                if (projected.kind == HitboxProjector.Kind.PLAYER) {
                    if (HitboxMod.isShowCritLine() && projected.critY != null) {
                        boxPaint.setColor(projected.aimedAt ? COLOR_AIMED : COLOR_CRIT);
                        canvas.drawLine(projected.box.left, projected.critY,
                                projected.box.right, projected.critY, boxPaint);
                    }
                    if (HitboxMod.isShowComboBox() && projected.comboBox != null) {
                        int comboColor = projected.aimedAt ? COLOR_AIMED : COLOR_COMBO;
                        boxPaint.setColor(comboColor);
                        HitboxProjector.Rect combo = projected.comboBox;
                        canvas.drawRect(combo.left, combo.top, combo.right, combo.bottom, boxPaint);
                        // A faint wash makes the best-combo target readable at a glance without
                        // hiding the entity behind it.
                        fillPaint.setColor((comboColor & 0x00FFFFFF) | 0x33000000);
                        canvas.drawRect(combo.left, combo.top, combo.right, combo.bottom, fillPaint);
                    }
                }
            }
        }

        /**
         * Draws the peer feed's boxes, and reports whether anything was drawn.
         *
         * <p>Returns false when the feed is absent or empty, so the caller can fall through to the
         * native "waiting for game data" notice. Returns true the moment one box is drawn, because
         * once we are drawing real geometry the notice would be a lie.
         */
        private boolean drawPeerBoxes(Canvas canvas) {
            HitboxProjector.Camera camera = camera();
            if (camera == null) return false;
            HitboxProjector.Basis basis = HitboxProjector.basis(camera);
            if (basis == null) return false;

            List<PeerHitboxSource.PeerHitbox> peers = PeerHitboxSource.build(PeerHitboxSource::readPeers);
            if (peers.isEmpty()) return false;

            int base = HitboxMod.peerColor();
            List<HitboxProjector.Projected> projected = new ArrayList<>(peers.size());
            for (PeerHitboxSource.PeerHitbox peer : peers) {
                HitboxProjector.Projected p =
                        HitboxProjector.projectEntity(peer.entity, basis);
                if (p != null) projected.add(p);
            }
            // Far to near, matching the native frame's ordering so a nearer box paints on top.
            projected.sort((a, b) -> Float.compare(b.distance, a.distance));

            for (HitboxProjector.Projected p : projected) {
                boxPaint.setStrokeWidth(Math.max(1.5f, density));
                boxPaint.setColor(base);
                canvas.drawRect(p.box.left, p.box.top, p.box.right, p.box.bottom, boxPaint);
                drawPeerGuides(canvas, p);
            }

            // Look-direction rays last, so a line is never painted over by a nearer box's fill.
            // Colour matches the aimed-at blue, because both answer "where is this player looking".
            if (HitboxMod.isShowLookLine()) {
                boxPaint.setStrokeWidth(Math.max(1.5f, density));
                boxPaint.setColor(COLOR_AIMED);
                for (PeerHitboxSource.PeerHitbox peer : peers) {
                    if (!peer.hasRotation) continue;
                    HitboxProjector.Rect ray = HitboxProjector.peerLookRay(
                            peer.entity.x, peer.entity.y, peer.entity.z,
                            peer.yaw, peer.pitch, LOOK_LINE_BLOCKS, basis);
                    if (ray == null) continue;
                    canvas.drawLine(ray.left, ray.top, ray.right, ray.bottom, boxPaint);
                }
            }
            return true;
        }

        /** The target sub-box inside a peer box: the chest-height strip a hit lands in. */
        private void drawPeerGuides(Canvas canvas, HitboxProjector.Projected p) {
            if (!HitboxMod.isShowTargetBox()) return;
            float width = p.box.width() * 0.5f;
            float cx = (p.box.left + p.box.right) / 2f;
            float top = p.box.top + p.box.height() * 0.16f;
            float bottom = p.box.top + p.box.height() * 0.55f;
            boxPaint.setColor(COLOR_TARGET);
            boxPaint.setStrokeWidth(Math.max(1f, density * 0.9f));
            canvas.drawRect(cx - width / 2f, top, cx + width / 2f, bottom, boxPaint);
        }

        /** The camera for the peer view, or null when the local player feed is unavailable. */
        private HitboxProjector.Camera camera() {
            // Read the local player's view directly, like the rest of the suite. The peer boxes
            // must not depend on the voice nametag seam: that seam is installed by startVoiceChat,
            // so a player with voice off would get no boxes even though the peer feed works.
            try {
                return LocalPlayerFeed.localCamera(getWidth(), getHeight());
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * A small, centred notice shown only while the entity feed is missing.
         *
         * Deliberately understated: it is not an error banner, it is a status. The module is
         * functioning; it is waiting on the native game-data provider that reads entity
         * positions, which this build does not yet ship.
         */
        private void drawAwaitingData(Canvas canvas) {
            String line1 = "Hitboxes: waiting for game data";
            String line2 = "Entity positions need a native feed, which is not installed";
            float pad = 12f * density;
            float textW = Math.max(notePaint.measureText(line1), notePaint.measureText(line2));
            float boxW = textW + pad * 2f;
            float boxH = 46f * density;
            float left = getWidth() / 2f - boxW / 2f;
            float top = getHeight() * 0.18f;
            noteRect.set(left, top, left + boxW, top + boxH);
            canvas.drawRoundRect(noteRect, 10f * density, 10f * density, noteBgPaint);
            notePaint.setColor(0xFFFFC46B);
            canvas.drawText(line1, getWidth() / 2f, top + 19f * density, notePaint);
            notePaint.setColor(0xFF9AA4B2);
            notePaint.setTextSize(10f * density);
            canvas.drawText(line2, getWidth() / 2f, top + 35f * density, notePaint);
            notePaint.setTextSize(12f * density);
        }

        /** Each entity class gets its own colour, so a fight is readable at a glance. */
        private int colorFor(HitboxProjector.Kind kind) {
            switch (kind) {
                case PLAYER:
                    return COLOR_ENTITY;
                case MOB:
                    return COLOR_MOB;
                case DROPPED_ITEM:
                    return COLOR_ITEM;
                case THROWN_ITEM:
                    return COLOR_THROWN;
                case PROJECTILE:
                    return COLOR_PROJECTILE;
                default:
                    return COLOR_ENTITY;
            }
        }
    }
}
