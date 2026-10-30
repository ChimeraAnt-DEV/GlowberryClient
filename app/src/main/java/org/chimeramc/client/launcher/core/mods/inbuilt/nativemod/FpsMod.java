package org.chimeramc.client.launcher.core.mods.inbuilt.nativemod;

import org.chimeramc.client.core.backend.GameBackend;
import org.chimeramc.client.core.backend.GameBridge;

/**
 * Reads the frame rate from the active backend.
 *
 * <p>When no backend reports one, {@link #nativeGetFps()} returns a negative value and the overlay
 * uses its host-side {@code FrameRateMeter} fallback instead of rendering a fabricated number.
 */
public final class FpsMod {

    private FpsMod() {
    }

    public static boolean init() {
        return GameBridge.supports(GameBackend.Feature.FPS);
    }

    public static boolean nativeIsInitialized() {
        return init();
    }

    /** The frame rate, or a negative value when unknown. */
    public static int nativeGetFps() {
        return GameBridge.readFps();
    }
}
