package org.chimeramc.client.launcher.controller;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.chimeramc.client.R;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.util.PersonalizationManager;
import org.chimeramc.client.util.MainThread;

/**
 * The glass pill that announces a controller connection.
 *
 * <p>Attaches directly to an Activity's content root rather than creating a window of its own:
 * the game runs a single Activity, so the same view appears over Minecraft and over every
 * launcher screen without needing overlay permission or a second window token. Only one pill
 * exists at a time; a second connection replaces the first.
 */
public final class ControllerToastView {

    private static final long AUTO_DISMISS_MS = 3000L;
    private static final long SLIDE_MS = 260L;

    private static ControllerToastView current;

    private final Activity activity;
    private final Handler handler = MainThread.handler();
    private View view;
    private Runnable dismissRunnable;

    private ControllerToastView(Activity activity) {
        this.activity = activity;
    }

    /**
     * Shows the connected pill.
     *
     * @param profileName the loaded profile's name, or null to hide the chip
     */
    public static void showConnected(Activity activity, String controllerName,
                                     String profileName, ControllerType type) {
        show(activity, controllerName,
                activity.getString(R.string.controller_detected_message),
                profileName, type, true);
    }

    /** Shows the disconnected pill. */
    public static void showDisconnected(Activity activity, String controllerName) {
        show(activity, controllerName,
                activity.getString(R.string.controller_disconnected_message),
                null, null, false);
    }

    /**
     * Shows the keyboard-connected pill (Phase 12).
     *
     * <p>The same pill as a pad, with the keyboard glyph and no profile chip: a keyboard has no
     * vendor mapping or dead zone, so there is no profile to name. Tapping does not open the
     * controller settings for the same reason.
     */
    public static void showKeyboardConnected(Activity activity, String keyboardName) {
        show(activity, keyboardName,
                activity.getString(R.string.keyboard_connected_message),
                null, null, false, true);
    }

    /** Shows the keyboard-disconnected pill. */
    public static void showKeyboardDisconnected(Activity activity, String keyboardName) {
        show(activity, keyboardName,
                activity.getString(R.string.keyboard_disconnected_message),
                null, null, false, true);
    }

    private static void show(final Activity activity, final String title, final String message,
                             final String profileName, final ControllerType type,
                             final boolean allowTapToSettings) {
        show(activity, title, message, profileName, type, allowTapToSettings, false);
    }

    private static void show(final Activity activity, final String title, final String message,
                             final String profileName, final ControllerType type,
                             final boolean allowTapToSettings, final boolean keyboard) {
        if (activity == null || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
            return;
        }
        final ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return;

        dismissCurrent();

        final ControllerToastView toast = new ControllerToastView(activity);
        current = toast;
        toast.attach(root, title, message, profileName, type, allowTapToSettings, keyboard);
    }

    private static void dismissCurrent() {
        if (current != null) {
            current.dismiss(false);
            current = null;
        }
    }

    private void attach(ViewGroup root, String title, String message, String profileName,
                        ControllerType type, boolean allowTapToSettings, boolean keyboard) {
        view = LayoutInflater.from(activity).inflate(R.layout.overlay_controller_toast, root, false);
        tint();
        ((TextView) view.findViewById(R.id.controller_toast_title)).setText(title);
        ((TextView) view.findViewById(R.id.controller_toast_message)).setText(message);

        // The glyph says which kind of device arrived; a pad and a keyboard are otherwise the same
        // pill, so without it the message would be the only cue.
        ImageView icon = view.findViewById(R.id.controller_toast_icon);
        icon.setImageResource(keyboard ? R.drawable.ic_keyboard : R.drawable.ic_nav_controller);
        icon.setContentDescription(activity.getString(
                keyboard ? R.string.keyboard_title : R.string.controller_title));

        TextView chip = view.findViewById(R.id.controller_toast_chip);
        if (profileName != null && !profileName.trim().isEmpty()) {
            chip.setText(profileName);
            chip.setVisibility(View.VISIBLE);
        }

        // Top-centre, above everything else in the content root.
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(12);
        params.leftMargin = dp(10);
        params.rightMargin = dp(10);

        view.setAlpha(0f);
        view.setTranslationY(-dp(40));
        root.addView(view, params);

        DynamicAnim.springAlphaTo(view, 1f);
        DynamicAnim.springTranslationYTo(view, 0f);

        attachSwipeToDismiss(allowTapToSettings);

        dismissRunnable = () -> dismiss(true);
        handler.postDelayed(dismissRunnable, AUTO_DISMISS_MS);
    }

    private void tint() {
        PersonalizationManager pm = new PersonalizationManager(activity);
        int accent = pm.getAccentColor();

        View chip = view.findViewById(R.id.controller_toast_chip);
        if (chip.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) chip.getBackground().mutate()).setColor(accent);
        }
        ImageView icon = view.findViewById(R.id.controller_toast_icon);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
    }

    private void attachSwipeToDismiss(final boolean tapOpensSettings) {
        view.setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            private boolean dragged;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = event.getRawY() - startY;
                        if (dy < -dp(4)) dragged = true;
                        if (dy < 0) v.setTranslationY(dy);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragged && v.getTranslationY() < -dp(24)) {
                            dismiss(true);
                        } else {
                            DynamicAnim.springTranslationYTo(v, 0f);
                            if (!dragged && tapOpensSettings) openControllerSettings();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void openControllerSettings() {
        try {
            activity.startActivity(new android.content.Intent(
                    activity, org.chimeramc.client.ui.activities.CustomizeActivity.class));
        } catch (Exception ignored) {
        }
        dismiss(true);
    }

    /** Removes the pill with a slide-up; {@code animate} false removes it immediately. */
    public void dismiss(boolean animate) {
        if (dismissRunnable != null) handler.removeCallbacks(dismissRunnable);
        if (view == null) return;
        final View target = view;
        view = null;
        if (!animate || target.getParent() == null) {
            remove(target);
            return;
        }
        DynamicAnim.springTranslationYTo(target, -dp(48));
        DynamicAnim.springAlphaTo(target, 0f);
        handler.postDelayed(() -> remove(target), SLIDE_MS);
    }

    private void remove(View target) {
        if (target.getParent() instanceof ViewGroup) {
            ((ViewGroup) target.getParent()).removeView(target);
        }
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    public static void reset() {
        dismissCurrent();
    }
}
