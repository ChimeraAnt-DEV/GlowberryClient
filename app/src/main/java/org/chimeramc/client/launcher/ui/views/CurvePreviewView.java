package org.chimeramc.client.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import org.chimeramc.client.launcher.controller.ControllerProfile;
import org.chimeramc.client.launcher.controller.ControllerResponse;

/**
 * Plots the profile's stick response curves so the user can see what a preset actually does
 * before committing to it. Curve editors are notoriously hard to reason about from numeric
 * sliders alone, and "does this feel right" is easier to answer from a shape.
 *
 * It draws both sticks over the full 0..1 input range, plus the dead zone as a shaded band so
 * the flat region at the start of the curve is visible rather than implied.
 */
public class CurvePreviewView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint leftPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint deadZonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private ControllerProfile profile;
    private int accentColor = 0xFF4CC2FF;

    // The response is a pure function of the profile, and this view only redraws when the profile
    // or the accent changes, so it is rebuilt on setProfile rather than on every draw (item 17).
    private ControllerResponse response;

    public CurvePreviewView(Context context) {
        super(context);
        init();
    }

    public CurvePreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CurvePreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setColor(0x33888888);

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(dp(1.5f));
        axisPaint.setColor(0x66888888);

        leftPaint.setStyle(Paint.Style.STROKE);
        leftPaint.setStrokeWidth(dp(2.5f));
        leftPaint.setStrokeCap(Paint.Cap.ROUND);

        rightPaint.setStyle(Paint.Style.STROKE);
        rightPaint.setStrokeWidth(dp(2.5f));
        rightPaint.setStrokeCap(Paint.Cap.ROUND);

        deadZonePaint.setStyle(Paint.Style.FILL);
        deadZonePaint.setColor(0x22FF9800);

        setMinimumHeight((int) dp(120));
    }

    public void setProfile(ControllerProfile profile) {
        this.profile = profile;
        this.response = profile == null ? null : new ControllerResponse(profile, false);
        invalidate();
    }

    public void setAccentColor(int color) {
        this.accentColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float padding = dp(10);
        float left = padding;
        float top = padding;
        float right = getWidth() - padding;
        float bottom = getHeight() - padding;
        if (right <= left || bottom <= top) return;

        leftPaint.setColor(accentColor);
        rightPaint.setColor(blendRightColor());

        // Dead zones, drawn first so the curves sit on top of them.
        if (profile != null) {
            float leftDead = clamp01(profile.getLeftDeadZone());
            if (leftDead > 0f) {
                float x = left + (right - left) * leftDead;
                canvas.drawRect(left, top, x, bottom, deadZonePaint);
            }
        }

        // Grid at 25% intervals.
        for (int i = 1; i < 4; i++) {
            float fraction = i / 4f;
            canvas.drawLine(left + (right - left) * fraction, top,
                    left + (right - left) * fraction, bottom, gridPaint);
            canvas.drawLine(left, top + (bottom - top) * fraction,
                    right, top + (bottom - top) * fraction, gridPaint);
        }
        canvas.drawRect(left, top, right, bottom, axisPaint);

        // Paint the output curve over the full travel. Dead-zone flattening and sensitivity
        // clamping are part of the response, so plotting through it shows the real result
        // rather than the raw curve shape.
        ControllerResponse active = response;
        if (active != null) {
            drawCurve(canvas, active, true, left, top, right, bottom, leftPaint);
            drawCurve(canvas, active, false, left, top, right, bottom, rightPaint);
        }
    }

    private void drawCurve(Canvas canvas, ControllerResponse response, boolean isLeft,
                           float left, float top, float right, float bottom, Paint paint) {
        path.reset();
        final int steps = 64;
        for (int i = 0; i <= steps; i++) {
            float input = i / (float) steps;
            // The horizontal axis is positive stick travel only; the response math is
            // symmetric, so drawing one half is honest and readable.
            float output = isLeft
                    ? response.adjustAxis(android.view.MotionEvent.AXIS_X, input)
                    : response.adjustAxis(android.view.MotionEvent.AXIS_Z, input);
            float x = left + (right - left) * input;
            float y = bottom - (bottom - top) * clamp01(output);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, paint);
    }

    /** The right-stick line is dimmed so both are readable where they overlap. */
    private int blendRightColor() {
        int alpha = 0x99;
        return (accentColor & 0x00FFFFFF) | (alpha << 24);
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
