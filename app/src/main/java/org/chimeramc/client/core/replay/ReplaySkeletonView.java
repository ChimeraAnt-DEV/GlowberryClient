package org.chimeramc.client.core.replay;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import org.chimeramc.client.ui.animation.DynamicAnim;

/**
 * A skeleton shimmer for the Replay library while it scans.
 *
 * <p>The spec forbids spinners, so a scan shows placeholder cards with a highlight sweeping across
 * them. Drawn in one {@link View} with cached paints: the grid of bars is not a tree of Views, and
 * nothing is allocated per frame. The animation stops itself when detached and is skipped entirely
 * when the user turned animations off.
 */
public class ReplaySkeletonView extends View {

    private static final int CARD_COUNT = 6;
    private static final long SWEEP_MS = 1_150L;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    // The sweep gradient is one shader whose position is moved with a local matrix. Rebuilding a
    // LinearGradient per card per frame would allocate during the very scan the skeleton exists to
    // cover, and a skeleton that stutters is worse than the spinner it replaced.
    private final Matrix sweepMatrix = new Matrix();
    private final LinearGradient sweepGradient;
    private final float sweepUnitWidth;

    private final int surface;
    private final int barColor;

    // Geometry inputs are context-derived and cannot change while the view is attached, so they are
    // resolved once rather than per frame.
    private final float padding;
    private final float gap;
    private final float wideBreakpoint;
    private final float radius;
    private final float barInset;
    private final float barBottomInset;
    private final float barHeight;
    private final float barRadius;

    private ValueAnimator animator;
    private float phase;

    public ReplaySkeletonView(Context context) {
        this(context, null);
    }

    public ReplaySkeletonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ReplayStyle style = new ReplayStyle(context);
        surface = style.surfaceElevated();
        barColor = ReplayStyle.blend(surface, style.textTertiary(), 0.25f);
        padding = ReplayStyle.dp(context, 12f);
        gap = ReplayStyle.dp(context, 10f);
        wideBreakpoint = ReplayStyle.dp(context, 520f);
        radius = ReplayStyle.dp(context, 14f);
        barInset = ReplayStyle.dp(context, 10f);
        barBottomInset = ReplayStyle.dp(context, 22f);
        barHeight = ReplayStyle.dp(context, 8f);
        barRadius = ReplayStyle.dp(context, 4f);
        // The gradient spans a unit of card width and is translated per card; 1f is the unit.
        sweepUnitWidth = 1f;
        sweepGradient = new LinearGradient(0f, 0f, sweepUnitWidth, 0f,
                new int[]{0x00FFFFFF, 0x18FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startSweep();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSweep();
        super.onDetachedFromWindow();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) {
            startSweep();
        } else {
            stopSweep();
        }
    }

    private void startSweep() {
        if (animator != null) return;
        if (!DynamicAnim.areAnimationsEnabled()) {
            phase = 0.5f;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SWEEP_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopSweep() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        int columns = width > wideBreakpoint ? 3 : 2;
        float cardWidth = (width - padding * 2 - gap * (columns - 1)) / columns;
        float cardHeight = cardWidth * 0.62f;

        int drawn = 0;
        for (int row = 0; drawn < CARD_COUNT; row++) {
            float top = padding + row * (cardHeight + gap);
            if (top > height) break;
            for (int col = 0; col < columns && drawn < CARD_COUNT; col++, drawn++) {
                float left = padding + col * (cardWidth + gap);
                rect.set(left, top, left + cardWidth, top + cardHeight);
                cardPaint.setColor(surface);
                canvas.drawRoundRect(rect, radius, radius, cardPaint);

                // The sweep is clipped to each card so the highlight reads as a moving sheen
                // rather than a translucent band floating over the whole screen.
                int save = canvas.save();
                canvas.clipRect(rect);
                float sweepWidth = cardWidth * 0.9f;
                float sweepX = rect.left - sweepWidth + phase * (cardWidth + sweepWidth) * 1.4f;
                // Move the one gradient rather than building a new one for every card.
                sweepMatrix.setScale(sweepWidth / sweepUnitWidth, 1f);
                sweepMatrix.postTranslate(sweepX, 0f);
                sweepGradient.setLocalMatrix(sweepMatrix);
                sweepPaint.setShader(sweepGradient);
                canvas.drawRect(rect, sweepPaint);
                canvas.restoreToCount(save);

                barPaint.setColor(barColor);
                float barLeft = left + barInset;
                float barTop = top + cardHeight - barBottomInset;
                rect.set(barLeft, barTop, barLeft + cardWidth * 0.55f, barTop + barHeight);
                canvas.drawRoundRect(rect, barRadius, barRadius, barPaint);
            }
        }
    }
}
