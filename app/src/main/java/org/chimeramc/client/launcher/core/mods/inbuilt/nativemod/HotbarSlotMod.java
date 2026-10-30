package org.chimeramc.client.launcher.core.mods.inbuilt.nativemod;

import org.chimeramc.client.core.backend.GameBackend;
import org.chimeramc.client.core.backend.GameBridge;

/**
 * Forwards hotbar-slot selection to the active backend.
 *
 * <p>Selecting a slot is a number-key press, which a backend provides. Reading whether a slot holds
 * an item needs a game-memory read the launcher does not have, so {@link #hasItem(int)} reports
 * false and the overlay falls back to drawing the slot number instead of an item icon.
 */
public final class HotbarSlotMod {

    private HotbarSlotMod() {
    }

    public static boolean initialize() {
        return GameBridge.supports(GameBackend.Feature.HOTBAR_SLOT);
    }

    public static void setEnabled(boolean enabled) {
        // Slot selection needs no persistent state; it acts per press.
    }

    public static void setSlotState(int slot, float x, float y, float width, float height,
                                    float surfaceWidth, float surfaceHeight, float alpha,
                                    boolean visible, boolean pressed) {
        // Positioning the slot's visual is a backend concern; nothing to forward without one.
    }

    /** False: reading an item out of a slot needs a game-memory read the launcher does not have. */
    public static boolean hasItem(int slot) {
        return false;
    }

    public static void clearSlot(int slot) {
    }
}
