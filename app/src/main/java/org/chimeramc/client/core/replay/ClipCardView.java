package org.chimeramc.client.core.replay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.chimeramc.client.R;

/**
 * One clip card in the Replay grid.
 *
 * <p>Built in code because the library is live data, and drawn from {@link ReplayStyle} so the
 * in-game Replay tab and the launcher-side Replay screen render the same card. It carries the
 * thumbnail, name, duration, date/size, the
 * favorite star and the highlight badge; press feedback is the shared
 * {@code DynamicAnim.applyPressScale} used everywhere else.
 */
final class ClipCardView extends FrameLayout {

    private final Context context;
    private final ReplayStyle style;

    private final ImageView thumbnail;
    private final TextView durationPill;
    private final TextView name;
    private final TextView meta;
    private final ImageView favorite;
    private final ImageView highlightBadge;

    private ReplayClip clip;
    private Runnable onOpen;
    private Runnable onFavorite;
    private Runnable onLongPress;
    private ScrubListener scrubListener;
    private Bitmap staticThumbnail;

    /**
     * Hold-and-drag across a card to preview a moment in the clip.
     *
     * <p>A single static thumbnail cannot tell one fight clip from another, so the card supports a
     * scrub gesture: a horizontal drag past touch slop reports a 0..1 fraction and the panel
     * decodes the frame there. The gesture consumes its own up event so scrubbing never also opens
     * the clip.
     */
    interface ScrubListener {
        void onScrubStart(ReplayClip clip);

        void onScrub(ReplayClip clip, float fraction);

        void onScrubEnd(ReplayClip clip);
    }

    ClipCardView(Context context, ReplayStyle style) {
        super(context);
        this.context = context;
        this.style = style;

        setClipToPadding(false);
        setBackground(ReplayStyle.rounded(style.surfaceElevated(), 14f, context));
        setPadding(0, 0, 0, 0);
        setElevation(style.cardElevation());
        if (style.glowEnabled()) {
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            ReplayStyle.dp(context, 14f));
                }
            });
        }

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Thumbnail with a rounded top edge and a bottom scrim.
        FrameLayout thumbFrame = new FrameLayout(context);
        thumbnail = new ImageView(context);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackground(ReplayStyle.verticalGradient(
                ReplayStyle.blend(style.surfaceElevated(), style.accent(), 0.25f),
                ReplayStyle.blend(style.surfaceElevated(), style.accentSecondary(), 0.18f),
                0f, context));
        thumbFrame.addView(thumbnail, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        View scrim = new View(context);
        scrim.setBackground(ReplayStyle.verticalGradient(0x00000000, 0xB3000000, 0f, context));
        FrameLayout.LayoutParams scrimParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        scrimParams.gravity = Gravity.BOTTOM;
        thumbFrame.addView(scrim, scrimParams);

        durationPill = new TextView(context);
        durationPill.setTextColor(0xFFF4F1FF);
        durationPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        durationPill.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        durationPill.setBackground(ReplayStyle.pill(0x99000000, context));
        durationPill.setPadding(ReplayStyle.dpInt(context, 7), ReplayStyle.dpInt(context, 2),
                ReplayStyle.dpInt(context, 7), ReplayStyle.dpInt(context, 2));
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        pillParams.gravity = Gravity.BOTTOM | Gravity.END;
        pillParams.setMargins(0, 0, ReplayStyle.dpInt(context, 8), ReplayStyle.dpInt(context, 8));
        thumbFrame.addView(durationPill, pillParams);

        highlightBadge = new ImageView(context);
        // A vector star, not an emoji glyph: emoji rendering varies by device font and the spec
        // bans emoji icons outright.
        highlightBadge.setImageResource(R.drawable.ic_star_filled);
        highlightBadge.setColorFilter(0xFFFFD166);
        highlightBadge.setBackground(ReplayStyle.pill(0x99000000, context));
        int badgePad = ReplayStyle.dpInt(context, 3);
        highlightBadge.setPadding(badgePad, badgePad, badgePad, badgePad);
        highlightBadge.setVisibility(GONE);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        badgeParams.gravity = Gravity.BOTTOM | Gravity.START;
        badgeParams.setMargins(ReplayStyle.dpInt(context, 8), 0, 0, ReplayStyle.dpInt(context, 8));
        thumbFrame.addView(highlightBadge, badgeParams);

        column.addView(thumbFrame, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, ReplayStyle.dpInt(context, 118f)));

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(ReplayStyle.dpInt(context, 10), ReplayStyle.dpInt(context, 8),
                ReplayStyle.dpInt(context, 10), ReplayStyle.dpInt(context, 10));

        LinearLayout nameRow = new LinearLayout(context);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        name = new TextView(context);
        name.setTextColor(style.textPrimary());
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameRow.addView(name, new LinearLayout.LayoutParams(0,
                LayoutParams.WRAP_CONTENT, 1f));

        favorite = new ImageView(context);
        favorite.setImageResource(R.drawable.ic_star_outline);
        favorite.setColorFilter(style.textTertiary());
        favorite.setPadding(ReplayStyle.dpInt(context, 6), ReplayStyle.dpInt(context, 0),
                ReplayStyle.dpInt(context, 0), 0);
        favorite.setOnClickListener(v -> {
            if (onFavorite != null) onFavorite.run();
        });
        nameRow.addView(favorite);
        info.addView(nameRow);

        meta = new TextView(context);
        meta.setTextColor(style.textTertiary());
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        meta.setSingleLine(true);
        meta.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = ReplayStyle.dpInt(context, 3);
        info.addView(meta, metaParams);

        column.addView(info, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        setOnClickListener(v -> {
            if (onOpen != null) onOpen.run();
        });
        setOnLongClickListener(v -> {
            if (onLongPress != null) {
                onLongPress.run();
                return true;
            }
            return false;
        });
        org.chimeramc.client.ui.animation.DynamicAnim.applyPressScale(this, this::handleScrubTouch);
    }

    /**
     * The scrub gesture, layered on top of the shared press feedback.
     *
     * <p>A horizontal drag past touch slop previews a frame at the finger's x. It is deliberately
     * distinct from the long-press action sheet: a long press without movement still opens the
     * sheet, while a drag scrubs. Once a scrub has begun the gesture consumes its events so the up
     * never fires the card's click and the clip does not also open.
     */
    private boolean handleScrubTouch(View v, android.view.MotionEvent event) {
        if (clip == null || scrubListener == null) return false;
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                scrubActive = false;
                scrubDownX = event.getX();
                scrubDownY = event.getY();
                return false;
            case android.view.MotionEvent.ACTION_MOVE: {
                float dx = Math.abs(event.getX() - scrubDownX);
                float dy = Math.abs(event.getY() - scrubDownY);
                if (!scrubActive) {
                    int slop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
                    if (dx > slop && dx > dy) {
                        scrubActive = true;
                        scrubListener.onScrubStart(clip);
                    }
                }
                if (scrubActive) {
                    float width = Math.max(1f, getWidth());
                    float fraction = Math.max(0f, Math.min(1f, event.getX() / width));
                    scrubListener.onScrub(clip, fraction);
                    return true;
                }
                return false;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                if (scrubActive) {
                    scrubActive = false;
                    scrubListener.onScrubEnd(clip);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private boolean scrubActive;
    private float scrubDownX;
    private float scrubDownY;

    void setClip(ReplayClip value) {
        this.clip = value;
        name.setText(value.displayName());
        durationPill.setText(ReplayFormat.duration(value.durationMs()));
        meta.setText(value.dateLabel() + "  ·  " + ReplayFormat.size(value.sizeBytes()));
        favorite.setImageResource(value.favorite() ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
        favorite.setColorFilter(value.favorite() ? 0xFFFFD166 : style.textTertiary());
        highlightBadge.setVisibility(value.highlight() ? VISIBLE : GONE);
    }

    void setThumbnail(Bitmap bitmap) {
        if (bitmap == null) return;
        staticThumbnail = bitmap;
        thumbnail.setImageBitmap(bitmap);
    }

    /**
     * Shows a scrub frame, keeping the original thumbnail so the preview can be dismissed.
     *
     * <p>A null frame (still decoding, or undecodable) leaves the current image in place rather
     * than flashing the gradient placeholder.
     */
    void setScrubPreview(Bitmap bitmap) {
        if (bitmap != null) thumbnail.setImageBitmap(bitmap);
    }

    /** Restores the static thumbnail after a scrub ends. */
    void clearScrubPreview() {
        if (staticThumbnail != null) thumbnail.setImageBitmap(staticThumbnail);
    }

    void setScrubListener(ScrubListener listener) {
        this.scrubListener = listener;
    }

    void setOnOpen(Runnable runnable) {
        this.onOpen = runnable;
    }

    void setOnFavorite(Runnable runnable) {
        this.onFavorite = runnable;
    }

    void setOnLongPress(Runnable runnable) {
        this.onLongPress = runnable;
    }

    ReplayClip clip() {
        return clip;
    }
}
