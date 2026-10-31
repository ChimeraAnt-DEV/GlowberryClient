package org.chimeramc.client.core.mods.inbuilt.model;

import org.chimeramc.client.util.Api28Compat;

public final class ModIds {
    /** Group id for combat/PvP modules; the Mod Menu exposes it as the PvP tab. */
    public static final String GROUP_PVP = "pvp";

    /** Group id for social modules (proximity voice), so the menu can section them apart. */
    public static final String GROUP_VOICE = "voice";

    /**
     * Group id for the HUD section, so the radial wheel's HUD sector and the Mod Menu's HUD filter
     * describe the same set. It is a section id, not a new store: every HUD module keeps its own
     * {@code ModIds} id and stays {@code Source.INBUILT}, so the Inbuilt filter still finds it.
     */
    public static final String GROUP_HUD = "hud";

    public static final String QUICK_DROP = "quick_drop";
    public static final String CAMERA_PERSPECTIVE = "camera_perspective";
    public static final String TOGGLE_HUD = "toggle_hud";
    public static final String AUTO_SPRINT = "auto_sprint";
    public static final String CHICK_PET = "chick_pet";
    public static final String ZOOM = "zoom";
    public static final String FPS_DISPLAY = "fps_display";
    public static final String CPS_DISPLAY = "cps_display";
    public static final String SNAPLOOK = "snaplook";
    public static final String VIRTUAL_CURSOR = "virtual_cursor";
    public static final String GYRO = "gyro";
    public static final String POJAV_CONTROLS = "pojav_controls";
    public static final String MORE_BUTTONS = "more_buttons";
    public static final String HOTBAR_SLOT = "hotbar_slot";
    public static final String OFFHAND = "offhand";
    public static final String AIM_SETTINGS = "aim_settings";
    public static final String MOD_MENU = "mod_menu";
    public static final String ARMOR_HUD = "armor_hud";
    public static final String CRYSTAL_OPTIMIZER = "crystal_optimizer";
    public static final String HIT_REGISTRATION = "hit_registration";
    public static final String HIT_TIMING = "hit_timing";
    public static final String HITBOX = "hitbox";
    public static final String VOICE_CHAT = "voice_chat";
    /** PvP Suite (V1.1): overlay-only visual aids, filed under the General tab. */
    public static final String REACH_INDICATOR = "reach_indicator";
    public static final String TRAJECTORY_PREDICTION = "trajectory_prediction";
    public static final String HIT_PREDICTION = "hit_prediction";
    public static final String KILL_EFFECTS = "kill_effects";

    // --- FPS optimization modules (host-side performance, filed under the General tab) --------
    /** Launcher animation culling: stops the launcher advancing its own off-screen animation. */
    public static final String HYPERSPATIAL_CULL = "hyperspatial_cull";
    /** Launcher overlay batching: batches the launcher's own overlay updates + atlas residency. */
    public static final String VERTICAL_RENDER_COALESCE = "vertical_render_coalesce";
    /** Launcher polling throttle: paces the launcher's own background requests and per-frame work. */
    public static final String TICK_RATE_PREDICTOR = "tick_rate_predictor";

    // --- Visual / HUD / Misc modules (the mod-panel list) ------------------------------------

    /** Brightens the world by removing the dark overlay, a full-bright visual. */
    public static final String FULLBRIGHT = "fullbright";
    /** Cycles the world time locally (client-side, no server command). */
    public static final String TIME_CHANGER = "time_changer";
    /** Lowers the fire overlay so it does not block the view. */
    public static final String LOW_FIRE = "low_fire";
    /** Draws the block the crosshair is on with an outline. */
    public static final String BLOCK_OUTLINE = "block_outline";
    /** A subtle screen motion blur when moving. */
    public static final String MOTION_BLUR = "motion_blur";
    /** Raises colour saturation of the rendered frame. */
    public static final String SATURATION = "saturation";
    /** A customisable crosshair, drawn by the client. */
    public static final String CROSSHAIR = "crosshair";
    /** Softens the armor-bar bending so the HUD reads flatter. */
    public static final String ARMOR_VIEW_BENDING = "armor_view_bending";
    /** Scales thrown-projectile models up so they are easier to see. */
    public static final String PROJECTILE_SCALER = "projectile_scaler";
    /** A detached free camera, separate from the player's view. */
    public static final String FREECAM = "freecam";
    /** A thin-outline block selection for TNT placement, a build aid. */
    public static final String TNT = "tnt";

    // --- Utility / information modules --------------------------------------------------------

    /** Lists players in the world with their distance, a client-side overlay. */
    public static final String LIST_PLAYERS = "list_players";
    /** A client-side debug readout (position, chunk, fps, entities). */
    public static final String DEBUG_MENU = "debug_menu";
    /** Draws a nametag on the player in third person, which vanilla omits. */
    public static final String THIRD_PERSON_NAMETAG = "third_person_nametag";

    /** The module id for the in-game Mod Menu itself. */
    public static final String MOD_MENU_ID = MOD_MENU;

    /**
     * Combat-oriented modules the Mod Menu files under its PvP tab. Kept here rather than
     * derived from the group string at the call site so the filter and the provider cannot
     * drift apart.
     */
    private static final java.util.Set<String> PVP_MODULES = Api28Compat.setOf(
            AIM_SETTINGS, CPS_DISPLAY, SNAPLOOK, ARMOR_HUD, CRYSTAL_OPTIMIZER,
            HIT_REGISTRATION, HIT_TIMING, HITBOX);

    /**
     * The shipped PvP module ids, in a stable order.
     *
     * <p>Exposed so a caller can assert against the real set rather than restating it:
     * {@code PvpSectionPolicy} pins that every id here has a boundary sentence, and that nothing
     * outside it may be advertised as a working PvP feature.
     */
    public static java.util.List<String> pvpModuleIds() {
        return Api28Compat.listOf(AIM_SETTINGS, CPS_DISPLAY, SNAPLOOK, ARMOR_HUD,
                CRYSTAL_OPTIMIZER, HIT_REGISTRATION, HIT_TIMING, HITBOX);
    }

    public static boolean isPvpModule(String modId) {
        if (modId == null) return false;
        if (PVP_MODULES.contains(modId)) return true;
        // A PvP catalogue entry is part of the PvP section too, so the tab filter and the group
        // header agree about it. The catalogue entries are appended last and are all in GROUP_PVP,
        // so they stay one contiguous run.
        return org.chimeramc.client.core.mods.pvp.PvpModuleCatalog.find(modId) != null;
    }

    /**
     * Social modules the Mod Menu files under its Voice section. Same reasoning as
     * {@link #isPvpModule}: the section predicate and the provider's grouping must agree.
     */
    private static final java.util.Set<String> VOICE_MODULES = Api28Compat.setOf(VOICE_CHAT);

    public static boolean isVoiceModule(String modId) {
        return modId != null && VOICE_MODULES.contains(modId);
    }

    /**
     * The HUD section's module ids.
     *
     * <p>The radial wheel's HUD sector opens this section, and the Mod Menu's HUD filter shows
     * exactly it, so the two cannot disagree about what "HUD related" means. These are the modules
     * whose whole output is a widget drawn on the player's own screen; a module that changes the
     * world or the sent input is not here.
     */
    private static final java.util.Set<String> HUD_MODULES = Api28Compat.setOf(
            FPS_DISPLAY, CPS_DISPLAY, ARMOR_HUD, HIT_TIMING, HITBOX, AIM_SETTINGS,
            TOGGLE_HUD, LIST_PLAYERS, DEBUG_MENU, VIRTUAL_CURSOR, HOTBAR_SLOT, CHICK_PET,
            REACH_INDICATOR, HIT_PREDICTION, TRAJECTORY_PREDICTION, KILL_EFFECTS);

    /** True for a module that belongs to the HUD section. */
    public static boolean isHudModule(String modId) {
        return modId != null && HUD_MODULES.contains(modId);
    }

    /** The HUD section's ids, in a stable order, for a caller that must assert against the set. */
    public static java.util.List<String> hudModuleIds() {
        return Api28Compat.listOf(FPS_DISPLAY, CPS_DISPLAY, ARMOR_HUD, HIT_TIMING, HITBOX,
                AIM_SETTINGS, TOGGLE_HUD, LIST_PLAYERS, DEBUG_MENU, VIRTUAL_CURSOR, HOTBAR_SLOT,
                CHICK_PET, REACH_INDICATOR, HIT_PREDICTION, TRAJECTORY_PREDICTION, KILL_EFFECTS);
    }

    /**
     * True for modules that are <em>entirely</em> dependent on a per-frame data feed from the
     * game process and have no other route to a result: durability/target reads (Armor HUD),
     * placement geometry (Crystal Optimizer), the held-item read the trajectory arc needs
     * (Trajectory Prediction) and the opponent-death feed kill credit needs (Custom Kill
     * Effects). With no native provider installed none of them can produce anything, so the Mod
     * Menu badges them and refuses the toggle.
     *
     * <p>The Hitboxes module is deliberately <em>not</em> here. It draws other players from the
     * proximity-voice feed, which needs no native hook at all, so it works today; only some of its
     * entity kinds need the native feed. Those are listed by {@link #outputRequiresGameData} and
     * are disabled per-option rather than taking the whole module down. The Reach Indicator and
     * Hit Prediction share that peer route and stay usable for the same reason.
     */
    public static boolean requiresGameData(String modId) {
        // A PvP catalogue entry is badged only when this build genuinely cannot drive it. The
        // catalogue classifies each entry by the route it needs; a reachable route (device input,
        // the local-player feed, the peer feed, the game options file) gets a real toggle, and only
        // an ENGINE_HOOK or SERVER_DECIDED entry is badged. Reading the classification here rather
        // than the catalogue's own "implemented" flag is what un-greys the reachable half of the
        // list instead of refusing a clock for lack of a native entity feed.
        if (org.chimeramc.client.core.mods.pvp.PvpModuleCatalog.find(modId) != null
                && !org.chimeramc.client.core.mods.pvp.PvpModuleCatalog.find(modId).implemented) {
            return !org.chimeramc.client.core.mods.inbuilt.catalog.PvpModuleScopes
                    .isReachable(modId);
        }
        return ARMOR_HUD.equals(modId) || CRYSTAL_OPTIMIZER.equals(modId)
                || TRAJECTORY_PREDICTION.equals(modId) || KILL_EFFECTS.equals(modId)
                // These visual modules act on the game's rendered frame or its entity list, which
                // this build exposes to no mod yet (the prebuilt libinbuiltmods.so supports only
                // AutoSprint/Fps/Gyro/HotbarSlot/MoreButtons/PojavControls/Snaplook/Zoom), so with
                // no native provider they cannot produce anything. Listing them as if they worked
                // would be the defect. Not listed here, because they reach a result through a route
                // this build already has: Fullbright (a brightness option), Crosshair (an overlay),
                // and List Players / Debug Menu (the local-player feed and the proximity-voice peer
                // list).
                || TIME_CHANGER.equals(modId) || LOW_FIRE.equals(modId)
                || BLOCK_OUTLINE.equals(modId) || MOTION_BLUR.equals(modId)
                || SATURATION.equals(modId) || ARMOR_VIEW_BENDING.equals(modId)
                || PROJECTILE_SCALER.equals(modId) || FREECAM.equals(modId)
                || TNT.equals(modId)
                // Third-person nametags need the drawn actor's world position, which needs the
                // native entity feed.
                || THIRD_PERSON_NAMETAG.equals(modId);
    }

    /**
     * Hitbox outputs that can only be produced by the native entity feed.
     *
     * <p>A hitbox for a mob, a dropped item, a thrown item or a projectile needs the game's
     * entity list, which this build cannot read (see the native-feed notes). The player box, the
     * aim line and the chest-height target box are <em>not</em> here: peers advertise their own
     * position and view over the voice protocol, so those three work without the game process.
     * Keeping the distinction in one place is what stops the config dialog from offering a switch
     * that can never draw anything.
     */
    private static final java.util.Set<String> HITBOX_NATIVE_OUTPUTS = Api28Compat.setOf(
            "hitbox_show_mobs", "hitbox_show_items", "hitbox_show_projectiles",
            "hitbox_show_thrown_items", "hitbox_show_crit_line", "hitbox_show_combo_box");

    public static boolean outputRequiresGameData(String modId, String configKey) {
        return HITBOX.equals(modId) && configKey != null && HITBOX_NATIVE_OUTPUTS.contains(configKey);
    }

    private ModIds() {}
}
