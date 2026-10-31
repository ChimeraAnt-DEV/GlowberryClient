package org.chimeramc.client.launcher.ui.views;

/**
 * The launcher controller pointer's input rules (Phase 12), as a pure class.
 *
 * <p>The pointer itself is a {@code View} and cannot be unit-tested off-device; what decides
 * <em>whether</em> it acts is not. Keeping the two decisions here — which buttons activate the
 * element under the pointer, and whether a stick frame is worth integrating — means the accepted
 * key set and the "ignore an idle stick" rule are asserted in a JVM test rather than only being
 * visible on a device.
 */
public final class LauncherCursorInput {

    /**
     * Stick deflection below which a frame is treated as idle.
     *
     * <p>Well under the cursor's dead zone: this only skips the work of integrating an essentially
     * centred stick, it does not decide the dead zone. That stays in {@code CursorController} so
     * the pointer and the in-game cursor share one value.
     */
    public static final float IDLE_STICK = 0.01f;

    private LauncherCursorInput() {
    }

    /**
     * The buttons that activate the element under the pointer.
     *
     * <p>A is the pad's confirm button and {@code BUTTON_1} is the same physical button on pads
     * that number their faces from one, which is why both are accepted. The d-pad centre is the
     * other convention. Enter and space cover a keyboard, so a Chromebook user gets the pointer
     * without a separate path.
     *
     * <p>B is deliberately <b>not</b> accepted: it is the back button, and mapping back onto the
     * pointer would make the same press mean two things depending on where the pointer happens to
     * sit.
     */
    public static boolean isSelectKey(int keyCode) {
        return keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A
                || keyCode == android.view.KeyEvent.KEYCODE_BUTTON_1
                || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == android.view.KeyEvent.KEYCODE_ENTER
                || keyCode == android.view.KeyEvent.KEYCODE_SPACE;
    }

    /**
     * Whether a stick frame should be integrated.
     *
     * <p>A centred frame is skipped unless the pointer is already visible, in which case the frame
     * is still passed on so the cursor can advance its own timestamp. Skipping every centred frame
     * would leave the timestamp stale and make the first real movement jump by the whole idle gap.
     */
    public static boolean shouldIntegrate(float stickX, float stickY, boolean cursorVisible) {
        if (Math.abs(stickX) > IDLE_STICK || Math.abs(stickY) > IDLE_STICK) return true;
        return cursorVisible;
    }
}
