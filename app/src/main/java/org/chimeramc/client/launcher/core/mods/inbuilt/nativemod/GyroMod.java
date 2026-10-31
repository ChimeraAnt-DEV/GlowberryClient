package org.chimeramc.client.launcher.core.mods.inbuilt.nativemod;

import org.chimeramc.client.core.backend.GameBackend;
import org.chimeramc.client.core.backend.GameBridge;

/**
 * Forwards gyro look to the active backend.
 *
 * <p>The gyro overlay computes its own sensitivity, inversion and dead zone and sends a look delta
 * through the shared look path, so this class only reports availability and forwards the delta.
 */
public final class GyroMod {

    private GyroMod() {
    }

    public static boolean init() {
        return GameBridge.supports(GameBackend.Feature.LOOK_DELTA);
    }

    /** Kept for the launch-time pre-resolve call; there is nothing to pre-resolve without a backend. */
    public static void nativePreResolve() {
    }

    public static void nativeSetEnabled(boolean enabled) {
    }

    public static void nativeUpdateDelta(float deltaYaw, float deltaPitch) {
        GameBridge.sendLookDelta(deltaYaw, deltaPitch);
    }

    public static void nativeSetSensitivityX(float sensitivity) {
    }

    public static void nativeSetSensitivityY(float sensitivity) {
    }

    public static void nativeSetInvertX(boolean invert) {
    }

    public static void nativeSetInvertY(boolean invert) {
    }

    public static void nativeSetDeadzone(float deadzone) {
    }

    public static boolean nativeIsInitialized() {
        return init();
    }

    public static boolean nativeIsEnabled() {
        return init();
    }
}
