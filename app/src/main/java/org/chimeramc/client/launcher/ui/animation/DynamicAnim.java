package org.chimeramc.client.ui.animation;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import androidx.recyclerview.widget.RecyclerView;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.chimeramc.client.R;
import org.chimeramc.client.ui.components.UiTokens;

public final class DynamicAnim {
    private DynamicAnim() {}

    private static final long DIALOG_DISMISS_DURATION_MS = 160L;
    private static float globalSpeedMultiplier = 1.0f;
    private static boolean animationsEnabled = true;

    /**
     * The motion scheme in force (U6). Every animated surface here reads its duration, damping and
     * stiffness from this one object rather than hard-coding a spring, so "Reduce motion" is a
     * single switch instead of a hunt through the codebase.
     *
     * <p>Kept in step with {@link #animationsEnabled}: {@link UiTokens.motionScheme} derives it from
     * the app's reduce-motion switch and the platform's "remove animations" setting, and
     * {@link #enableAnimations}/{@link #disableAnimations} are the two entry points that set it.
     * A plain static field, never synchronized, because it is only read on the UI thread.
     */
    private static UiTokens.Motion motion = UiTokens.Motion.EXPRESSIVE;

    /** The scheme in force. Prefer this over a literal duration. */
    public static UiTokens.Motion motionScheme() {
        return motion;
    }

    public static void setGlobalSpeedMultiplier(float multiplier) {
        globalSpeedMultiplier = Math.max(0.1f, multiplier);
    }

    public static float getGlobalSpeedMultiplier() {
        return globalSpeedMultiplier;
    }

    public static void enableAnimations() {
        animationsEnabled = true;
        motion = UiTokens.Motion.EXPRESSIVE;
    }

    public static void disableAnimations() {
        animationsEnabled = false;
        motion = UiTokens.Motion.REDUCED;
    }

    /**
     * Selects the scheme from the app's reduce-motion switch and the platform's animation scale.
     *
     * <p>Callers pass both so this class does not have to reach for a Context on the animation path.
     * The platform setting wins: a user who has told Android to remove animations must not get
     * springs back because the app's own switch happens to be on.
     */
    public static void applyMotionScheme(boolean reduceMotion, boolean platformAnimationsOff) {
        motion = UiTokens.motionScheme(reduceMotion, platformAnimationsOff);
        animationsEnabled = motion != UiTokens.Motion.REDUCED;
    }

    /** The duration for a decorative transition, in the scheme currently in force. */
    public static long motionDurationMs(long expressiveMs) {
        return motion.linear ? motion.durationMs : scaled(expressiveMs);
    }

    public static boolean areAnimationsEnabled() {
        return animationsEnabled;
    }

    public static SpringAnimation springAlphaTo(View view, float target) {
        SpringAnimation anim = new SpringAnimation(view, DynamicAnimation.ALPHA, target);
        anim.setSpring(springFor(target, SpringForce.DAMPING_RATIO_NO_BOUNCY));
        return anim;
    }

    public static SpringAnimation springTranslationYTo(View view, float target) {
        SpringAnimation anim = new SpringAnimation(view, DynamicAnimation.TRANSLATION_Y, target);
        anim.setSpring(springFor(target, SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY));
        return anim;
    }

    public static SpringAnimation springTranslationXTo(View view, float target) {
        SpringAnimation anim = new SpringAnimation(view, DynamicAnimation.TRANSLATION_X, target);
        anim.setSpring(springFor(target, SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY));
        return anim;
    }

    public static SpringAnimation springScaleXTo(View view, float target) {
        SpringAnimation anim = new SpringAnimation(view, DynamicAnimation.SCALE_X, target);
        anim.setSpring(springFor(target, SpringForce.DAMPING_RATIO_NO_BOUNCY));
        return anim;
    }

    public static SpringAnimation springScaleYTo(View view, float target) {
        SpringAnimation anim = new SpringAnimation(view, DynamicAnimation.SCALE_Y, target);
        anim.setSpring(springFor(target, SpringForce.DAMPING_RATIO_NO_BOUNCY));
        return anim;
    }

    /**
     * A spring for {@code target}, taken from the scheme in force (U6).
     *
     * <p>{@code expressiveDamping} is the damping this surface wants when motion is allowed; the
     * reduced scheme overrides it with a non-bouncy ratio so the transition still happens but does
     * not overshoot. Stiffness comes from the scheme too, so "Expressive" and "Reduce motion" are
     * two different feels rather than the same spring at two durations.
     */
    private static SpringForce springFor(float target, float expressiveDamping) {
        UiTokens.Motion scheme = motion;
        float damping = scheme.linear ? SpringForce.DAMPING_RATIO_NO_BOUNCY : expressiveDamping;
        float stiffness = scheme.stiffness > 0f ? scheme.stiffness : SpringForce.STIFFNESS_LOW;
        return new SpringForce(target)
                .setDampingRatio(damping)
                .setStiffness(stiffness);
    }

    /** View tag marking a view that already carries the press listener. */
    private static final int KEY_PRESS_STATE = R.id.dynamic_anim_press_state;
    private static final int KEY_DETACH_GUARD = R.id.dynamic_anim_detach_guard;

    private static final float PRESS_SCALE = 0.955f;
    private static final float PRESS_Z_DP = 6f;

    /**
     * Per-view press state. The two springs are built once and re-targeted on every
     * touch, so scrolling a long list does not allocate a SpringAnimation per event.
     */
    private static final class PressState {
        final SpringAnimation scaleX;
        final SpringAnimation scaleY;
        final View.OnTouchListener delegate;
        final int touchSlop;
        float downX;
        float downY;
        boolean dragging;
        boolean pressed;

        PressState(View view, View.OnTouchListener delegate) {
            this.delegate = delegate;
            this.touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
            scaleX = new SpringAnimation(view, DynamicAnimation.SCALE_X, 1f);
            scaleY = new SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f);
            SpringForce force = new SpringForce(1f)
                    .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                    .setStiffness(SpringForce.STIFFNESS_HIGH);
            scaleX.setSpring(force);
            // Separate SpringForce instances: a SpringForce cannot be shared between
            // two animations, it holds the animation's own state.
            scaleY.setSpring(new SpringForce(1f)
                    .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                    .setStiffness(SpringForce.STIFFNESS_HIGH));
        }

        void press() {
            pressed = true;
            dragging = false;
            // Velocity must be set before re-targeting: animateToFinalPosition starts
            // the animation immediately, so a velocity applied afterwards is dropped.
            scaleX.setStartVelocity(-1.5f);
            scaleY.setStartVelocity(-1.5f);
            scaleX.animateToFinalPosition(PRESS_SCALE);
            scaleY.animateToFinalPosition(PRESS_SCALE);
        }

        void release() {
            if (!pressed) return;
            pressed = false;
            scaleX.setStartVelocity(1.5f);
            scaleY.setStartVelocity(1.5f);
            scaleX.animateToFinalPosition(1f);
            scaleY.animateToFinalPosition(1f);
        }

        /** Drop the pressed look immediately, e.g. once the gesture became a scroll. */
        void snapBack(View view) {
            if (!pressed) return;
            pressed = false;
            scaleX.cancel();
            scaleY.cancel();
            view.setScaleX(1f);
            view.setScaleY(1f);
        }
    }

    /**
     * Apply press-scale + elevation + haptic feedback to a view.
     *
     * The listener never consumes the event, so click handling still runs. Pass
     * {@code delegate} when the view needs its own touch listener; it is invoked after
     * the press effect and its return value is honoured. Installing press feedback with
     * {@code setOnTouchListener} would otherwise silently replace that listener — for a
     * RecyclerView drag handle that means drag-to-reorder stops working with no error.
     */
    /**
     * Cancels a view's running animations and clears any partial transform when it detaches
     * (item 74).
     *
     * <p>A fast tab switch detaches a view mid-animation, and the animation then keeps running
     * against a detached view: it holds the view, the transform is left part-way (a tab stuck at
     * partial alpha or scale) and, if the same view instance is reused, it reappears in that state.
     * Cancelling on detach is the fix, and it is attached once per view through a tag so repeated
     * calls cannot stack listeners.
     */
    public static void cancelOnDetach(final View view) {
        if (view == null) return;
        if (view.getTag(KEY_DETACH_GUARD) != null) return;
        view.setTag(KEY_DETACH_GUARD, Boolean.TRUE);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.animate().cancel();
                Object state = v.getTag(KEY_PRESS_STATE);
                if (state instanceof PressState) ((PressState) state).snapBack(v);
                v.setAlpha(1f);
                v.setScaleX(1f);
                v.setScaleY(1f);
            }
        });
    }

    public static void applyPressScale(View view, @Nullable View.OnTouchListener delegate) {
        if (view == null) return;
        if (view.getTag(KEY_PRESS_STATE) != null) return;
        cancelOnDetach(view);

        view.setClickable(true);
        final PressState state = new PressState(view, delegate);
        view.setTag(KEY_PRESS_STATE, state);

        view.setOnTouchListener((v, event) -> {
            if (animationsEnabled) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        state.downX = event.getX();
                        state.downY = event.getY();
                        state.press();
                        animateElevation(v, PRESS_Z_DP, 8f);
                        UiTouchFeedback.pressView(v);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        // Once the finger travels past touch slop the gesture belongs to
                        // an enclosing scroll view; un-shrink so rows do not stay dimmed.
                        if (!state.dragging
                                && Math.hypot(event.getX() - state.downX,
                                event.getY() - state.downY) > state.touchSlop) {
                            state.dragging = true;
                            state.snapBack(v);
                            animateElevation(v, 0f, 8f);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        state.release();
                        animateElevation(v, 0f, 8f);
                        break;
                    default:
                        break;
                }
            }
            return state.delegate != null && state.delegate.onTouch(v, event);
        });
    }

    /**
     * Hover/press feedback for a clickable surface: a soft bloom halo (elevation lift plus a scale
     * on hover) that signals interactivity, then returns on exit.
     *
     * <p>On touch-only screens there is no hover, so this is a no-op there; it exists for the pointer
     * and controller-cursor cases the spec's hover states describe. Reduced motion and the
     * animations-disabled switch both skip it, and any failure is swallowed so feedback can never
     * break a click.
     */
    public static void applyHoverBloom(final View view) {
        if (view == null) return;
        if (!animationsEnabled) return;
        view.setOnHoverListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_HOVER_ENTER:
                    animateElevation(v, PRESS_Z_DP * 1.5f, 140f);
                    v.animate().scaleX(1.02f).scaleY(1.02f)
                            .setDuration(140L)
                            .setInterpolator(getDefaultInterpolator())
                            .start();
                    break;
                case android.view.MotionEvent.ACTION_HOVER_EXIT:
                    animateElevation(v, 0f, 180f);
                    v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(180L)
                            .setInterpolator(getDefaultInterpolator())
                            .start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /**
     * Apply press feedback to a view that manages its own click, not its own touch.
     * Callers that need a touch listener must use the two-argument overload.
     */
    public static void applyPressScale(View view) {
        applyPressScale(view, null);
    }

    private static void animateElevation(View view, float target, float durationMs) {
        try {
            view.animate().cancel();
            view.animate()
                    .translationZ(dp(view.getContext(), target))
                    .setDuration((long) durationMs)
                    .setInterpolator(getDefaultInterpolator())
                    .start();
        } catch (Throwable ignored) {
            // Elevation is purely cosmetic; never break touch handling.
        }
    }

    /**
     * Dialog enter: alpha 0->1 and scale 0.94->1.
     *
     * <p>Under the reduced scheme the dialog is placed at its final state instead of springing, so
     * the transition is a plain appearance rather than a travel-and-settle.
     */
    public static void animateDialogShow(View root) {
        if (root == null) return;
        if (!animationsEnabled || motion.linear) {
            root.setAlpha(1f);
            root.setScaleX(1f);
            root.setScaleY(1f);
            return;
        }
        root.setAlpha(0f);
        root.setScaleX(0.94f);
        root.setScaleY(0.94f);
        springAlphaTo(root, 1f).start();
        springScaleXTo(root, 1f).start();
        springScaleYTo(root, 1f).start();
    }

    /**
     * Dialog dismiss with spring; call onEnd when finished.
     */
    public static void animateDialogDismiss(View root, @Nullable Runnable onEnd) {
        if (root == null) {
            if (onEnd != null) onEnd.run();
            return;
        }
        root.animate().cancel();
        root.animate()
                .alpha(0f)
                .scaleX(0.94f)
                .scaleY(0.94f)
                .setDuration(motionDurationMs(DIALOG_DISMISS_DURATION_MS))
                .setInterpolator(motion.linear ? new LinearInterpolator()
                        : new AccelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        root.animate().setListener(null);
                        if (onEnd != null) onEnd.run();
                    }
                })
                .start();
    }

    public static void applyPressScaleRecursively(View root) {
        if (root == null) return;
        if (root.isClickable()) applyPressScale(root);
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyPressScaleRecursively(vg.getChildAt(i));
            }
        }
    }

    // 对 RecyclerView 可见子项做阶梯式入场（淡入 + 上滑）
    public static void staggerRecyclerChildren(RecyclerView rv) {
        if (rv == null) return;
        rv.post(() -> {
            int count = rv.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = rv.getChildAt(i);
                if (child == null) continue;
                float dy = dp(rv.getContext(), 12f);
                child.setAlpha(0f);
                child.setTranslationY(dy);
                final int delay = i * 40; // 40ms 阶梯
                rv.postDelayed(() -> {
                    springAlphaTo(child, 1f).start();
                    springTranslationYTo(child, 0f).start();
                }, delay);
            }
        });
    }

    private static float dp(Context ctx, float value) {
        return value * ctx.getResources().getDisplayMetrics().density;
    }

    public static android.view.animation.Interpolator getDefaultInterpolator() {
        return new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    }

    // ------------------------------------------------------------------ arrival motion system
    //
    // One shared language for "something arrived on this screen": scale in from a little under
    // full with an overshoot, staggered across a row. Keeping it here rather than per screen is
    // what makes the nav bar, the home cards and any later surface read as one motion system
    // instead of each screen inventing its own feel.

    /** Where an arriving view starts; small enough to read as a pop, not a zoom. */
    public static final float ARRIVAL_FROM_SCALE = 0.7f;

    /** Gap between successive views in a staggered arrival. */
    public static final long ARRIVAL_STEP_MS = 50L;

    /**
     * Scales a view in from {@link #ARRIVAL_FROM_SCALE} with a slight overshoot.
     *
     * <p>Honours the global animation switch and the speed multiplier, so reduced motion skips it
     * and the accessibility "animation speed" setting still applies.
     */
    public static void overshootScaleIn(View view, long startDelayMs) {
        if (view == null) return;
        cancelOnDetach(view);
        if (!animationsEnabled) {
            view.setAlpha(1f);
            view.setScaleX(1f);
            view.setScaleY(1f);
            return;
        }
        view.setAlpha(0f);
        view.setScaleX(ARRIVAL_FROM_SCALE);
        view.setScaleY(ARRIVAL_FROM_SCALE);
        // The reduced scheme keeps the appearance but drops the overshoot, so an arrival still
        // reads as an arrival without the bounce (U6).
        boolean overshoot = !motion.linear;
        if (!overshoot) {
            view.setScaleX(1f);
            view.setScaleY(1f);
        }
        view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(scaled(startDelayMs))
                .setDuration(motionDurationMs(260L))
                .setInterpolator(overshoot ? new OvershootInterpolator(1.6f)
                        : new LinearInterpolator())
                .start();
    }

    /**
     * Staggers {@link #overshootScaleIn} across a row of views.
     *
     * <p>Order is the array order, so a caller controls the beat by controlling the order it passes.
     */
    public static void staggerArrival(View[] views, long baseDelayMs) {
        if (views == null) return;
        for (int i = 0; i < views.length; i++) {
            overshootScaleIn(views[i], baseDelayMs + i * ARRIVAL_STEP_MS);
        }
    }

    /**
     * Runs a numeric count-up and hands each intermediate value to {@code onValue}.
     *
     * <p>The caller formats, so a plain count and a duration that reads as "3h 12m" both animate
     * without this class knowing about either. On the reduced-motion path it jumps straight to the
     * target instead of tweening.
     */
    public static void countUp(long target, long durationMs,
                               final java.util.function.LongConsumer onValue) {
        if (onValue == null) return;
        if (!animationsEnabled) {
            onValue.accept(target);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(motionDurationMs(durationMs));
        animator.setInterpolator(motion.linear ? new LinearInterpolator()
                : new PathInterpolator(0.22f, 1f, 0.36f, 1f));
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            onValue.accept(Math.round(target * t));
        });
        animator.start();
    }

    /** Applies the global speed multiplier to a duration, never returning zero. */
    private static long scaled(long durationMs) {
        return Math.max(1L, (long) (durationMs / globalSpeedMultiplier));
    }
}
