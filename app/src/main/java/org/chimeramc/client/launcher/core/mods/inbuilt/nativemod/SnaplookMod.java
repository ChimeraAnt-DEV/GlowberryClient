package org.chimeramc.client.launcher.core.mods.inbuilt.nativemod;

import org.chimeramc.client.core.backend.GameBridge;

/**
 * Forwards snap-look to the active backend.
 *
 * <p>Snap-look is a camera reset, expressed through the same look-delta path touch look uses; a
 * backend that provides {@code LOOK_DELTA} provides it. {@link #init()} reports availability.
 */
public final class SnaplookMod {

    private SnaplookMod() {
    }

    public static boolean init() {
        return GameBridge.supports(
                org.chimeramc.client.core.backend.GameBackend.Feature.LOOK_DELTA);
    }

    public static void nativeOnKeyDown() {
        // Snap-look returns the camera to the stored heading. With no backend that tracks a stored
        // heading there is nothing to restore, so this stays a no-op rather than guessing a delta.
    }

    public static void nativeOnKeyUp() {
    }
}
