package org.chimeramc.client.launcher.core.mods.inbuilt.nativemod;

import org.chimeramc.client.core.backend.GameBridge;

/**
 * Forwards touch/key/mouse injection to the active {@link GameBridge} backend.
 *
 * <p>This keeps the name the overlays and the game activity already import, so replacing the old
 * prebuilt-library wrapper is an import change, not a rewrite. Every method is a no-op returning
 * false when no backend is registered, so touch controls degrade quietly rather than crashing.
 */
public final class PojavControlsMod {

    private PojavControlsMod() {
    }

    /** True when a backend is registered and can inject input at all. */
    public static boolean initialize() {
        return GameBridge.supports(org.chimeramc.client.core.backend.GameBackend.Feature.KEY);
    }

    /** Enables or disables the touch controls; false when no backend can inject input. */
    public static boolean setEnabled(boolean enabled) {
        if (!enabled) {
            GameBridge.releaseAll();
            return true;
        }
        return initialize();
    }

    public static void nativeSendKey(int keyCode, boolean down) {
        GameBridge.sendKey(keyCode, down);
    }

    public static void nativeSendMouseButton(int androidButton, boolean down) {
        GameBridge.sendMouseButton(androidButton, down);
    }

    public static void nativeSendScroll(float vertical) {
        GameBridge.sendScroll(vertical);
    }

    public static void nativeSendLookDelta(float deltaX, float deltaY) {
        GameBridge.sendLookDelta(deltaX, deltaY);
    }

    public static void nativeSendPointer(float x, float y) {
        GameBridge.sendPointer(x, y);
    }
}
