package org.chimeramc.client.core.mods.inbuilt.overlay;

import org.chimeramc.client.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Mod Menu's one-shot actions (M3).
 *
 * <p>Kept separate from the module list on purpose: a module is a switch and an action is a
 * chevron, and the two must never be rendered as each other. The catalog is pure, so the action
 * set and its section placement are unit-testable without a device.
 */
public final class ModMenuActionCatalog {

    /** Centre every HUD overlay back to its default position. */
    public static final String ACTION_RESET_HUD = "reset_hud";
    /** Re-scan and re-register every module without restarting the session. */
    public static final String ACTION_RELOAD_MODULES = "reload_modules";
    /** Open the radial wheel's profile manager. */
    public static final String ACTION_MANAGE_WHEEL = "manage_wheel";

    private static final List<ModMenuAction> ACTIONS = Collections.unmodifiableList(build());

    private ModMenuActionCatalog() {
    }

    private static List<ModMenuAction> build() {
        List<ModMenuAction> actions = new ArrayList<>();
        // The section ids match a module's group id, so the action set follows the same filter the
        // module set does: "Reset HUD Position" sits in the inbuilt section, "Reload Modules" too.
        actions.add(new ModMenuAction(ACTION_RESET_HUD,
                R.string.mod_menu_action_reset_hud,
                R.string.mod_menu_action_reset_hud_desc,
                "inbuilt",
                R.drawable.ic_hud));
        actions.add(new ModMenuAction(ACTION_RELOAD_MODULES,
                R.string.mod_menu_action_reload_mods,
                R.string.mod_menu_action_reload_mods_desc,
                "inbuilt",
                R.drawable.ic_reload));
        actions.add(new ModMenuAction(ACTION_MANAGE_WHEEL,
                R.string.mod_menu_action_manage_wheel,
                R.string.mod_menu_action_manage_wheel_desc,
                "inbuilt",
                R.drawable.ic_wheel));
        return actions;
    }

    /** Every action, in display order. */
    public static List<ModMenuAction> all() {
        return ACTIONS;
    }

    /** The actions that belong to one section. */
    public static List<ModMenuAction> forGroup(String groupId) {
        List<ModMenuAction> out = new ArrayList<>();
        for (ModMenuAction action : ACTIONS) {
            if (action.getGroupId() == null ? groupId == null
                    : action.getGroupId().equals(groupId)) {
                out.add(action);
            }
        }
        return out;
    }

    /** The action with this id, or null. */
    public static ModMenuAction byId(String id) {
        if (id == null) return null;
        for (ModMenuAction action : ACTIONS) {
            if (id.equals(action.getId())) return action;
        }
        return null;
    }
}
