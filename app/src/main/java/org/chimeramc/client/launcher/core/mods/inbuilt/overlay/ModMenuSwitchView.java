package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;


/**
 * The Mod Menu's pill switch (M2, M5).
 *
 * <p>A module has an <b>enable state</b>; an action fires once. Rendering both as tap targets is the
 * component-type mismatch that made the menu feel wrong — a stateful switch that looks like a
 * one-shot button never shows whether it is on. This view is the switch half of that rule: it shows
 * the state, animates the state change, and refuses to be a button.
 *
 * <p>Geometry and colours come from the pure {@link ModMenuSwitch}; this class only animates and
 * paints. Both constructors exist so a layout can inflate it.
 */
public class ModMenuSwitchView extends View {

    /** Called when the user flips the switch. */
    public interface OnCheckedChangeListener {
        void onCheckedChanged(boolean checked);
    }

    /** The on-state spring: springier, because an enable is a commitment. */
    private static final PathInterpolator SPRING_ON =
            new PathInterpolator(0.2f, 0f, 0.1f, 1.15f);
    /** The off-state spring: flatter, because a disable is a plain retreat. */
    private static final PathInterpolator SPRING_OFF =
            new PathInterpolator(0.3f, 0f, 0.2f, 1f);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private boolean checked;
    private float progress;
    private boolean interactive = true;
    private boolean failed;
    private boolean hapticsEnabled = true;
    private boolean reducedMotion;
    private ModMenuSettings.ToggleStyle toggleStyle = ModMenuSettings.ToggleStyle.SWITCH;
    private String label;
    private OnCheckedChangeListener listener;
    private ValueAnimator animator;

    public ModMenuSwitchView(Context context) {
        this(context, null);
    }

    public ModMenuSwitchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        // The visual is 52x32dp, which is narrower than the 48dp minimum in one axis; the view
        // reserves the full target so a thumb-sized press still lands.
        setMinimumWidth((int) (ModMenuMetrics.MIN_TOUCH_TARGET_DP * density));
        setMinimumHeight((int) (ModMenuMetrics.MIN_TOUCH_TARGET_DP * density));
        updateContentDescription();
    }

    public boolean isChecked() {
        return checked;
    }

    /** Sets the state without notifying the listener; {@code animate} false snaps. */
    public void setChecked(boolean value, boolean animate) {
        this.checked = value;
        if (!animate || !isAttachedToWindow()) {
            cancelAnimator();
            progress = value ? 1f : 0f;
            updateContentDescription();
            invalidate();
            return;
        }
        if (progress == (value ? 1f : 0f)) return;
        cancelAnimator();
        animator = ValueAnimator.ofFloat(progress, value ? 1f : 0f);
        animator.setDuration(ModMenuMotion.durationMs(reducedMotion));
        // Reduced motion is linear (a short fade, no travel); full motion is the on/off spring.
        animator.setInterpolator(reducedMotion
                ? new LinearInterpolator()
                : (value ? SPRING_ON : SPRING_OFF));
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
        updateContentDescription();
    }

    /** A module that cannot be driven shows the disabled appearance and refuses the tap. */
    public void setInteractive(boolean value) {
        this.interactive = value;
        setClickable(value);
        invalidate();
    }

    /** A module that failed to register shows the error track (M10). */
    public void setFailed(boolean value) {
        this.failed = value;
        invalidate();
    }

    /** Disables the haptic tick for this instance (e.g. a settings preview). */
    public void setHapticsEnabled(boolean value) {
        this.hapticsEnabled = value;
    }

    /**
     * Switches to the reduced-motion scheme (M6, U11).
     *
     * <p>Reduced motion is a short linear transition rather than a spring: still a transition, so
     * the control does not pop, but with no overshoot. The OS setting wins over the in-menu choice,
     * which is why this is pushed in from the overlay rather than read here.
     */
    public void setReducedMotion(boolean value) {
        this.reducedMotion = value;
    }

    /**
     * Switches the control's visual to the player's chosen toggle style (M6).
     *
     * <p>Only the visual changes: the state, the touch target and the listener are identical for all
     * three styles, so a player who picks the checkbox still gets the same module state as one who
     * keeps the pill.
     */
    public void setToggleStyle(ModMenuSettings.ToggleStyle value) {
        ModMenuSettings.ToggleStyle next = value == null
                ? ModMenuSettings.ToggleStyle.SWITCH : value;
        if (this.toggleStyle == next) return;
        this.toggleStyle = next;
        requestLayout();
        invalidate();
    }

    /** The style currently drawn (M6). */
    public ModMenuSettings.ToggleStyle getToggleStyle() {
        return toggleStyle;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener value) {
        this.listener = value;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        // Centre the visual in whatever the layout gave us, so the invisible padding the touch
        // target needs does not shift the control. The visual's width follows the toggle style
        // (M6): a plain pill, a pill with a state word, or a square checkbox.
        float visualWidth = ModMenuSwitch.visualWidthDp(toggleStyle) * density;
        float visualHeight = (toggleStyle == ModMenuSettings.ToggleStyle.CHECKBOX
                ? ModMenuSwitch.CHECKBOX_SIZE_DP
                : ModMenuMetrics.SWITCH_HEIGHT_DP) * density;
        float left = (getWidth() - visualWidth) / 2f;
        float top = (getHeight() - visualHeight) / 2f;
        ModMenuSwitch.draw(canvas, paint, left, top, density, checked, progress, interactive,
                failed, toggleStyle);
    }

    @Override
    public boolean performClick() {
        if (!interactive) return super.performClick();
        boolean next = !checked;
        setChecked(next, true);
        if (hapticsEnabled && ModMenuSwitch.hapticsOnStateChange()) {
            performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? HapticFeedbackConstants.CLOCK_TICK
                    : HapticFeedbackConstants.VIRTUAL_KEY);
        }
        if (listener != null) listener.onCheckedChanged(next);
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimator();
        super.onDetachedFromWindow();
    }

    private void cancelAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void updateContentDescription() {
        // TalkBack announces "Module name, On, Switch": this view's content description carries the
        // module name (the row sets it) and the state, and onInitializeAccessibilityNodeInfo supplies
        // the switch role. A bare "On" loses the module it belongs to and does not announce the
        // control as a switch, so assistive tech cannot tell it is toggleable.
        setContentDescription(ModMenuSwitch.accessibilityLabel(label, checked));
        announceStateChanged();
    }

    /**
     * The module name this switch belongs to, announced before the state.
     *
     * <p>The row sets it on bind so a recycled switch does not announce the previous module's name.
     */
    public void setLabel(String value) {
        this.label = value;
        updateContentDescription();
    }

    /**
     * Re-emits the accessibility node after a state change.
     *
     * <p>An {@code invalidate()} repaints but does not re-announce, so TalkBack would keep reading
     * the previous state. The event is best-effort: an announcement must never be able to break the
     * control, so a failure here is swallowed.
     */
    private void announceStateChanged() {
        if (!isAttachedToWindow()) return;
        try {
            android.view.accessibility.AccessibilityEvent event =
                    android.view.accessibility.AccessibilityEvent.obtain(
                            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            event.setContentChangeTypes(
                    android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION);
            event.setSource(this);
            sendAccessibilityEventUnchecked(event);
        } catch (Throwable ignored) {
            // An accessibility announcement must never be able to break the control.
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        // The role and the state, so TalkBack announces "..., On, Switch" rather than an unnamed
        // clickable view. `setClassName` on the framework Switch is what makes the switch role.
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(checked);
        info.setEnabled(interactive);
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        // Space activates a switch the way it activates a button; the base View already maps
        // Enter/DPAD_CENTER onto performClick, but not Space.
        if (keyCode == android.view.KeyEvent.KEYCODE_SPACE) {
            return performClick();
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // The touch target is always at least 48dp; the pill+label style is wider than that, so the
        // visual is never clipped by the target (M6).
        int desiredW = (int) (Math.max(ModMenuMetrics.MIN_TOUCH_TARGET_DP,
                ModMenuSwitch.visualWidthDp(toggleStyle)) * density);
        int desiredH = (int) (ModMenuMetrics.MIN_TOUCH_TARGET_DP * density);
        setMeasuredDimension(
                resolveSize(desiredW, widthMeasureSpec),
                resolveSize(desiredH, heightMeasureSpec));
    }
}
