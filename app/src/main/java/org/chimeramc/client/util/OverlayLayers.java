package org.chimeramc.client.util;

import android.view.View;

/**
 * Keeps a hardware layer only while it is earning its keep (item 189).
 *
 * <p>{@code LAYER_TYPE_HARDWARE} uploads the view to a texture so an animated alpha or
 * transformation is composited on the GPU instead of repainted each frame. That is a real win while
 * an animation runs — and pure overhead when it does not: the layer keeps a full-size texture alive
 * for the life of the view, which on a full-screen overlay is several megabytes the compositor
 * never releases.
 *
 * <p>So the rule is: promote for the duration of an animation, demote back to {@code NONE} when it
 * ends. This wraps that in one place so the promote and the demote cannot drift apart — a view left
 * promoted (an animation cancelled before its end callback, a hide during the fade) was the failure
 * mode this exists to prevent.
 *
 * <p>Every call is on the UI thread, so no locking.
 */
public final class OverlayLayers {

    private OverlayLayers() {}

    /**
     * Promotes {@code view} for the duration of {@code animation}.
     *
     * @param animation the animation whose lifetime the layer should match; null demotes instead.
     */
    public static void hardwareDuring(View view, android.animation.Animator animation) {
        if (view == null) return;
        if (animation == null) {
            demote(view);
            return;
        }
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean ended;

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                finish();
            }

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                finish();
            }

            private void finish() {
                // Both end and cancel land here, and only once: a cancel followed by an end would
                // otherwise demote twice, which is harmless but hides a real double-finish bug.
                if (ended) return;
                ended = true;
                demote(view);
            }
        });
    }

    /**
     * The {@link android.view.ViewPropertyAnimator} variant.
     *
     * <p>It is not an {@code Animator}, so it cannot share the listener path above; it exposes
     * {@code withStartAction}/{@code withEndAction} instead, which is the same promote/demote pair.
     * A start action that is replaced by a second call would leak the layer, so this always demotes
     * first and then promotes for the new animation.
     */
    public static void hardwareDuring(View view, android.view.ViewPropertyAnimator animator) {
        if (view == null) return;
        if (animator == null) {
            demote(view);
            return;
        }
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        animator.withEndAction(() -> demote(view));
    }

    /** Removes the hardware layer, returning the view to the default rendering path. */
    public static void demote(View view) {
        if (view == null) return;
        if (view.getLayerType() != View.LAYER_TYPE_NONE) {
            view.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }
}
