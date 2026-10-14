package org.chimeramc.client.launcher.controller;

import java.util.List;

/**
 * Pure list edits for the controller profile store, so the "persist the edited object" rule is
 * unit-testable without a device.
 *
 * <p>The bug this exists to prevent: {@code ControllerProfileManager.getProfiles(type)} deserialises
 * a <b>new</b> list of <b>new</b> objects from prefs on every call. Code that edited a profile
 * object and then saved {@code getProfiles(type)} wrote the unmodified profiles back, so a slider
 * change reached the running processor but was never persisted. The correct edit replaces the slot
 * with the edited object and saves that list.
 */
public final class ControllerProfileList {

    private ControllerProfileList() {
    }

    /**
     * Returns {@code profiles} with {@code slot} replaced by {@code profile}. When the slot is out
     * of range the profile is appended (so a stale binding cannot silently drop the edit), and a
     * negative slot clamps to 0.
     */
    public static List<ControllerProfile> replaceAt(List<ControllerProfile> profiles, int slot,
                                                    ControllerProfile profile) {
        if (profiles == null || profile == null) return profiles;
        if (slot < 0) slot = 0;
        if (slot >= profiles.size()) {
            profiles.add(profile);
        } else {
            profiles.set(slot, profile);
        }
        return profiles;
    }

    /**
     * The index the active slot should persist for {@code slot} given a list of {@code size}
     * profiles, clamped to the valid range (0 when the list is empty).
     */
    public static int clampSlot(int slot, int size) {
        if (size <= 0) return 0;
        return Math.max(0, Math.min(size - 1, slot));
    }
}
