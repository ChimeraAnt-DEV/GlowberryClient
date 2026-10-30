package org.chimeramc.client.launcher.core.mods.inbuilt.nativemod;

import org.chimeramc.client.core.backend.GameBackend;
import org.chimeramc.client.core.backend.GameBridge;

/**
 * Forwards auto-sprint to the active backend.
 *
 * <p>Bedrock has no dedicated sprint key; the backend reproduces the sprint by double-tapping
 * forward. {@link #setEnabled(boolean)} returns false when no backend provides it.
 */
public final class AutoSprintMod {

    private AutoSprintMod() {
    }

    public static boolean initialize() {
        return GameBridge.supports(GameBackend.Feature.AUTO_SPRINT);
    }

    public static boolean setEnabled(boolean enabled) {
        if (!enabled) {
            if (!GameBridge.supports(GameBackend.Feature.AUTO_SPRINT)) {
                return true;
            }
            return GameBridge.setAutoSprint(false);
        }
        return GameBridge.setAutoSprint(true);
    }

    public static boolean isEnabled() {
        return GameBridge.isAutoSprintActive();
    }
}
