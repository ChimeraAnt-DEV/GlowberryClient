package org.chimeramc.client.launcher.core.mods.inbuilt.nativemod;

import org.chimeramc.client.core.backend.GameBackend;
import org.chimeramc.client.core.backend.GameBridge;

/**
 * Forwards a synthetic key press/release to the active backend.
 *
 * <p>Used by the on-screen buttons (offhand, hotbar slot, more-buttons) that need to press a game
 * key. Returns false when no backend can inject input.
 */
public final class MoreButtonsMod {

    private MoreButtonsMod() {
    }

    public static boolean initialize() {
        return GameBridge.supports(GameBackend.Feature.KEY);
    }

    /** Injects a key press/release. {@code keyCode} is a Bedrock key code. */
    public static boolean sendKey(int keyCode, boolean down) {
        if (keyCode <= 0 || keyCode >= 256) {
            return false;
        }
        return GameBridge.sendKey(keyCode, down);
    }
}
