package org.chimeramc.client.launcher.core.mods.inbuilt.nativemod;

import org.chimeramc.client.core.backend.GameBackend;
import org.chimeramc.client.core.backend.GameBridge;

/**
 * Forwards zoom to the active backend.
 *
 * <p>Zoom needs a field-of-view change, which no launcher-side backend can provide yet, so
 * {@link #init()} returns false and the overlay stays hidden rather than appearing to work.
 */
public final class ZoomMod {

    private ZoomMod() {
    }

    /** False while no backend provides {@link GameBackend.Feature#ZOOM}. */
    public static boolean init() {
        return GameBridge.supports(GameBackend.Feature.ZOOM);
    }

    public static void nativeSetZoomLevel(long level) {
        // The zoom level is only meaningful to a backend that changes the FOV; the backend decides
        // how to interpret it, so the raw value is not sent here.
    }

    public static void nativeSetTransitionDuration(int duration) {
        // Transition timing is a backend concern; nothing to forward to a backend that lacks zoom.
    }

    public static void nativeOnKeyDown() {
        GameBridge.setZoom(true);
    }

    public static void nativeOnKeyUp() {
        GameBridge.setZoom(false);
    }

    public static void nativeOnScroll(float delta) {
        GameBridge.sendScroll(delta);
    }
}
