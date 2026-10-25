package org.chimeramc.client.core.mods.inbuilt;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.chimeramc.client.R;
import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.core.mods.inbuilt.model.ModIds;
import org.chimeramc.client.core.mods.inbuilt.overlay.InbuiltOverlayManager;
import org.chimeramc.client.core.mods.inbuilt.overlay.HitTimingSolver;
import org.chimeramc.client.core.mods.inbuilt.overlay.MoreButtonsEditor;
import org.chimeramc.pojavcontrols.PojavControls;

import java.util.ArrayList;
import java.util.List;

public final class InbuiltModuleProvider {
    private static final String GROUP_ID = "inbuilt";
    private static final String GROUP_PVP_ID = ModIds.GROUP_PVP;
    private static final String MOD_ID = "inbuilt";

    private static final String CFG_OVERLAY_SIZE = "overlay_size";
    private static final String CFG_OVERLAY_OPACITY = "overlay_opacity";
    private static final String CFG_OVERLAY_LOCK = "overlay_lock";
    private static final String CFG_OVERLAY_SHOW_EVERYWHERE = "overlay_show_everywhere";
    private static final String CFG_AUTO_SPRINT_KEYBIND = "auto_sprint_keybind";
    private static final String CFG_CURSOR_SENSITIVITY = "cursor_sensitivity";
    private static final String CFG_ZOOM_LEVEL = "zoom_level";
    private static final String CFG_ZOOM_TRANSITION = "zoom_transition";
    private static final String CFG_ZOOM_KEYBIND = "zoom_keybind";
    private static final String CFG_GYRO_SENSITIVITY_X = "gyro_sensitivity_x";
    private static final String CFG_GYRO_SENSITIVITY_Y = "gyro_sensitivity_y";
    private static final String CFG_GYRO_INVERT_X = "gyro_invert_x";
    private static final String CFG_GYRO_INVERT_Y = "gyro_invert_y";
    private static final String CFG_GYRO_DEADZONE = "gyro_deadzone";
    private static final String CFG_HOTBAR_ITEM_ICONS = "hotbar_item_icons";
    private static final String CFG_OFFHAND_MODE = "offhand_mode";
    private static final String CFG_OFFHAND_INVENTORY_KEY = "offhand_inventory_key";
    private static final String CFG_OFFHAND_SWAP_KEY = "offhand_swap_key";
    private static final String CFG_OFFHAND_USE_KEY = "offhand_use_key";
    private static final String CFG_OFFHAND_KEYBIND = "offhand_keybind";
    private static final String CFG_HOTBAR_SLOT_PREFIX = "hotbar_slot_";
    private static final String CFG_HOTBAR_SLOT_ENABLED = "enabled";
    private static final String CFG_HOTBAR_SLOT_SIZE = "size";
    private static final String CFG_HOTBAR_SLOT_OPACITY = "opacity";
    private static final String CFG_AIM_SMOOTHING = "aim_smoothing";
    private static final String CFG_AIM_CROSSHAIR = "aim_crosshair";
    private static final String CFG_AIM_FLASH = "aim_flash";
    private static final String CFG_AIM_SENSITIVITY = "aim_sensitivity";
    private static final String CFG_AIM_CROSSHAIR_STYLE = "aim_crosshair_style";
    private static final String CFG_AIM_CROSSHAIR_COLOR = "aim_crosshair_color";
    private static final String CFG_ARMOR_HUD_SHOW_TARGET = "armor_hud_show_target";
    private static final String CFG_ARMOR_HUD_SHOW_ENCHANTS = "armor_hud_show_enchants";
    private static final String CFG_ARMOR_HUD_STACKED = "armor_hud_stacked";
    private static final String CFG_ARMOR_HUD_REFRESH_MS = "armor_hud_refresh_ms";
    private static final String CFG_CRYSTAL_MIN_SELF_HP = "crystal_min_self_hp";
    private static final String CFG_CRYSTAL_MAX_RANGE = "crystal_max_range";
    private static final String CFG_CRYSTAL_PLACEMENT_DELAY_MS = "crystal_placement_delay_ms";
    private static final String CFG_CRYSTAL_MANUAL_ASSIST = "crystal_manual_assist";
    private static final String CFG_CRYSTAL_KEYBIND = "crystal_keybind";
    private static final String CFG_HITREG_SENSITIVITY = "hitreg_sensitivity";
    private static final String CFG_HITREG_SMOOTHING = "hitreg_smoothing";
    private static final String CFG_HITREG_PREDICTION = "hitreg_prediction";
    private static final String CFG_HITREG_HAPTIC = "hitreg_haptic";
    private static final String CFG_HIT_TIMING_COOLDOWN_MS = "hit_timing_cooldown_ms";
    private static final String CFG_HIT_TIMING_SHOW_COMBO = "hit_timing_show_combo";
    private static final String CFG_HIT_TIMING_SHOW_BAR = "hit_timing_show_bar";
    private static final String CFG_HITBOX_SHOW_PLAYERS = "hitbox_show_players";
    private static final String CFG_HITBOX_SHOW_MOBS = "hitbox_show_mobs";
    private static final String CFG_HITBOX_SHOW_ITEMS = "hitbox_show_items";
    private static final String CFG_HITBOX_SHOW_PROJECTILES = "hitbox_show_projectiles";
    private static final String CFG_HITBOX_SHOW_THROWN_ITEMS = "hitbox_show_thrown_items";
    private static final String CFG_HITBOX_SHOW_LOOK_LINE = "hitbox_show_look_line";
    private static final String CFG_HITBOX_SHOW_CRIT_LINE = "hitbox_show_crit_line";
    private static final String CFG_HITBOX_SHOW_COMBO_BOX = "hitbox_show_combo_box";
    private static final String CFG_HITBOX_SHOW_TARGET_BOX = "hitbox_show_target_box";
    private static final String CFG_VOICE_RANGE = "voice_range_blocks";
    private static final String CFG_VOICE_VOLUME = "voice_volume_percent";
    private static final String CFG_VOICE_CHANNEL = "voice_channel";
    private static final String CFG_VOICE_MIC = "voice_mic_enabled";
    private static final String CFG_VOICE_ICON_STYLE = "voice_icon_style";
    private static final String CFG_VOICE_ICON_ANIMATE = "voice_icon_animate";
    private static final String CFG_VOICE_ICON_NAMETAG = "voice_icon_show_nametag";
    // PvP Suite (V1.1): overlay-only visual modules, filed under the General tab.
    private static final String CFG_REACH_POSITION = "reach_indicator_position";
    private static final String CFG_TRAJECTORY_COLOR = "trajectory_color";
    private static final String CFG_HIT_PREDICTION_LOOKAHEAD = "hit_prediction_lookahead_ms";
    private static final String CFG_KILL_EFFECT_STYLE = "kill_effect_style";
    private static final String CFG_KILL_EFFECT_COLOR = "kill_effect_color";
    private static final String CFG_CATALOG_CHAT_MACRO = "catalog_chat_macro";
    private static final String CFG_CATALOG_COMMAND_BIND = "catalog_command_bind";

    private InbuiltModuleProvider() {
    }

    public static List<UnifiedMod> load(Activity activity) {
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        String groupName = activity.getString(R.string.mod_menu_group_inbuilt);
        List<UnifiedMod> mods = new ArrayList<>();

        mods.add(create(activity, manager, overlayManager, ModIds.QUICK_DROP,
                R.string.inbuilt_mod_quick_drop, R.string.inbuilt_mod_quick_drop_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.CAMERA_PERSPECTIVE,
                R.string.inbuilt_mod_camera, R.string.inbuilt_mod_camera_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.TOGGLE_HUD,
                R.string.inbuilt_mod_hud, R.string.inbuilt_mod_hud_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.AUTO_SPRINT,
                R.string.inbuilt_mod_autosprint, R.string.inbuilt_mod_autosprint_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.CHICK_PET,
                R.string.inbuilt_mod_chick_pet, R.string.inbuilt_mod_chick_pet_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.ZOOM,
                R.string.inbuilt_mod_zoom, R.string.inbuilt_mod_zoom_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.FPS_DISPLAY,
                R.string.inbuilt_mod_fps_display, R.string.inbuilt_mod_fps_display_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.CPS_DISPLAY,
                R.string.inbuilt_mod_cps_display, R.string.inbuilt_mod_cps_display_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.SNAPLOOK,
                R.string.inbuilt_mod_snaplook, R.string.inbuilt_mod_snaplook_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.VIRTUAL_CURSOR,
                R.string.inbuilt_mod_virtual_cursor, R.string.inbuilt_mod_virtual_cursor_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.GYRO,
                R.string.inbuilt_mod_gyro, R.string.inbuilt_mod_gyro_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.POJAV_CONTROLS,
                R.string.inbuilt_mod_pojav_controls, R.string.inbuilt_mod_pojav_controls_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.MORE_BUTTONS,
                R.string.inbuilt_mod_more_buttons, R.string.inbuilt_mod_more_buttons_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.HOTBAR_SLOT,
                R.string.inbuilt_mod_hotbar_slot, R.string.inbuilt_mod_hotbar_slot_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.OFFHAND,
                R.string.inbuilt_mod_offhand, R.string.inbuilt_mod_offhand_desc,
                groupName));
        // PvP Suite (V1.1): overlay-only visual aids. They sit in the General section, not a
        // separate PvP section - the suite is not gameplay automation and the spec is explicit
        // that no new tab is added.
        mods.add(create(activity, manager, overlayManager, ModIds.REACH_INDICATOR,
                R.string.inbuilt_mod_reach_indicator, R.string.inbuilt_mod_reach_indicator_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.TRAJECTORY_PREDICTION,
                R.string.inbuilt_mod_trajectory_prediction,
                R.string.inbuilt_mod_trajectory_prediction_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.HIT_PREDICTION,
                R.string.inbuilt_mod_hit_prediction, R.string.inbuilt_mod_hit_prediction_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.KILL_EFFECTS,
                R.string.inbuilt_mod_kill_effects, R.string.inbuilt_mod_kill_effects_desc,
                groupName));
        // FPS optimization modules: host-side performance only. They reduce the launcher's own work
        // and never touch the game's renderer, so no switch here promises a frame rate.
        mods.add(create(activity, manager, overlayManager, ModIds.HYPERSPATIAL_CULL,
                R.string.inbuilt_mod_hyperspatial_cull,
                R.string.inbuilt_mod_hyperspatial_cull_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.VERTICAL_RENDER_COALESCE,
                R.string.inbuilt_mod_vertical_render_coalesce,
                R.string.inbuilt_mod_vertical_render_coalesce_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.TICK_RATE_PREDICTOR,
                R.string.inbuilt_mod_tick_rate_predictor,
                R.string.inbuilt_mod_tick_rate_predictor_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.AIM_SETTINGS,
                R.string.inbuilt_mod_aim_settings, R.string.inbuilt_mod_aim_settings_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.ARMOR_HUD,
                R.string.inbuilt_mod_armor_hud, R.string.inbuilt_mod_armor_hud_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.CRYSTAL_OPTIMIZER,
                R.string.inbuilt_mod_crystal_optimizer, R.string.inbuilt_mod_crystal_optimizer_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.HIT_REGISTRATION,
                R.string.inbuilt_mod_hit_registration, R.string.inbuilt_mod_hit_registration_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.HIT_TIMING,
                R.string.inbuilt_mod_hit_timing, R.string.inbuilt_mod_hit_timing_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.HITBOX,
                R.string.inbuilt_mod_hitbox, R.string.inbuilt_mod_hitbox_desc,
                groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.VOICE_CHAT,
                R.string.inbuilt_mod_voice_chat, R.string.inbuilt_mod_voice_chat_desc,
                activity.getString(R.string.mod_menu_group_voice)));

        // Visual / HUD / utility modules. The ones that need a native frame or entity feed are
        // badged and refused by ModAvailability; the ones that work through an existing route
        // (Fullbright, Crosshair, List Players) stay usable.
        mods.add(create(activity, manager, overlayManager, ModIds.FULLBRIGHT,
                R.string.inbuilt_mod_fullbright, R.string.inbuilt_mod_fullbright_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.TIME_CHANGER,
                R.string.inbuilt_mod_time_changer, R.string.inbuilt_mod_time_changer_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.LOW_FIRE,
                R.string.inbuilt_mod_low_fire, R.string.inbuilt_mod_low_fire_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.BLOCK_OUTLINE,
                R.string.inbuilt_mod_block_outline, R.string.inbuilt_mod_block_outline_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.MOTION_BLUR,
                R.string.inbuilt_mod_motion_blur, R.string.inbuilt_mod_motion_blur_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.SATURATION,
                R.string.inbuilt_mod_saturation, R.string.inbuilt_mod_saturation_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.CROSSHAIR,
                R.string.inbuilt_mod_crosshair, R.string.inbuilt_mod_crosshair_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.ARMOR_VIEW_BENDING,
                R.string.inbuilt_mod_armor_view_bending,
                R.string.inbuilt_mod_armor_view_bending_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.PROJECTILE_SCALER,
                R.string.inbuilt_mod_projectile_scaler,
                R.string.inbuilt_mod_projectile_scaler_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.FREECAM,
                R.string.inbuilt_mod_freecam, R.string.inbuilt_mod_freecam_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.TNT,
                R.string.inbuilt_mod_tnt, R.string.inbuilt_mod_tnt_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.LIST_PLAYERS,
                R.string.inbuilt_mod_list_players, R.string.inbuilt_mod_list_players_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.DEBUG_MENU,
                R.string.inbuilt_mod_debug_menu, R.string.inbuilt_mod_debug_menu_desc, groupName));
        mods.add(create(activity, manager, overlayManager, ModIds.THIRD_PERSON_NAMETAG,
                R.string.inbuilt_mod_third_person_nametag,
                R.string.inbuilt_mod_third_person_nametag_desc, groupName));

        addPvpCatalogEntries(activity, manager, overlayManager, mods);

        return groupPvpLast(mods);
    }

    /**
     * Adds the PvP catalogue as menu entries.
     *
     * <p>A catalogue entry that links to a shipped module ({@link
     * org.chimeramc.client.core.mods.pvp.PvpModuleCatalog.Module#linksToExisting()}) is a
     * <em>view</em>: its toggle drives the real module's own enable state, so there is one source of
     * truth and the two can never disagree. A roadmap entry (one this build cannot drive) is listed
     * with the shared badge and is not clickable — {@code ModIds.requiresGameData} covers it, so the
     * grey-out, the badge and the refused toggle all come from the same rule the rest of the menu
     * uses.
     */
    private static void addPvpCatalogEntries(Activity activity, InbuiltModManager manager,
                                             InbuiltOverlayManager overlayManager,
                                             List<UnifiedMod> mods) {
        String pvpGroupName = activity.getString(R.string.mod_menu_group_pvp);
        for (org.chimeramc.client.core.mods.pvp.PvpModuleCatalog.Module entry
                : org.chimeramc.client.core.mods.pvp.PvpModuleCatalog.modules()) {
            // A catalogue entry that duplicates an already-listed module by id is skipped; the menu
            // shows each shipped module once.
            if (entry.linksToExisting()) continue;
            UnifiedMod mod = new UnifiedMod(
                    entry.id,
                    entry.name,
                    entry.description,
                    MOD_ID,
                    UnifiedMod.Source.INBUILT,
                    overlayManager != null
                            ? overlayManager.isModActive(entry.id)
                            : manager.resolveInbuiltModEnabled(entry.id, false),
                    createCatalogConfigs(activity, manager, entry.id),
                    false,
                    GROUP_PVP_ID,
                    pvpGroupName,
                    (m, enabled) -> setEnabled(manager, m, enabled),
                    (m, config, value) -> setConfig(manager, m, config, value)
            );
            mod.setLocalConfigSchema(createCatalogConfigSchema(activity, mod));
            mods.add(mod);
        }
    }

    /** A catalogue module's own options: macro text, or the readout's overlay appearance. */
    private static List<UnifiedMod.ConfigEntry> createCatalogConfigs(Context context,
                                                                     InbuiltModManager manager,
                                                                     String modId) {
        List<UnifiedMod.ConfigEntry> configs = new ArrayList<>();
        if ("util_chat_macro".equals(modId)) {
            configs.add(config(CFG_CATALOG_CHAT_MACRO,
                    context.getString(R.string.catalog_macro_text),
                    UnifiedMod.ConfigType.TEXT, "", "", "",
                    manager.getCatalogChatMacro()));
        } else if ("util_command_bind".equals(modId)) {
            configs.add(config(CFG_CATALOG_COMMAND_BIND,
                    context.getString(R.string.catalog_command_text),
                    UnifiedMod.ConfigType.TEXT, "", "", "",
                    manager.getCatalogCommandBind()));
        }
        if (org.chimeramc.client.core.mods.inbuilt.overlay.CatalogModuleHost
                .isReadout(modId)) {
            configs.add(config(CFG_OVERLAY_SIZE,
                    context.getString(R.string.mod_config_overlay_button_size_dp),
                    UnifiedMod.ConfigType.SLIDER_INT, "56", "20", "100",
                    String.valueOf(manager.getOverlayButtonSize(modId))));
            configs.add(config(CFG_OVERLAY_OPACITY,
                    context.getString(R.string.mod_config_overlay_opacity_percent),
                    UnifiedMod.ConfigType.SLIDER_INT, "100", "0", "100",
                    String.valueOf(manager.getOverlayOpacity(modId))));
            configs.add(config(CFG_OVERLAY_LOCK,
                    context.getString(R.string.overlay_button_lock),
                    UnifiedMod.ConfigType.TOGGLE, "false", "", "",
                    String.valueOf(manager.isOverlayLocked(modId))));
            configs.add(config(CFG_OVERLAY_SHOW_EVERYWHERE,
                    context.getString(R.string.mod_config_overlay_show_everywhere),
                    UnifiedMod.ConfigType.TOGGLE, "false", "", "",
                    String.valueOf(manager.isOverlayShowEverywhere(modId))));
        }
        return configs;
    }

    /**
     * A catalogue module's config dialog: its own content/display options plus a scope note that
     * says which route the module uses, so a working module does not read as a roadmap stub.
     */
    private static RuntimeConfigSchema createCatalogConfigSchema(Context context, UnifiedMod mod) {
        String modId = mod.getId();
        try {
            JSONArray categories = new JSONArray();
            JSONArray nodes = new JSONArray();
            boolean readout = org.chimeramc.client.core.mods.inbuilt.overlay
                    .CatalogModuleHost.isReadout(modId);
            categories.put(configCategory(context, "content", R.string.mod_config_category_behavior));
            if (readout) {
                categories.put(configCategory(context, "display", R.string.mod_config_category_appearance));
            }
            if ("util_chat_macro".equals(modId)) {
                nodes.put(configNode(context, mod, CFG_CATALOG_CHAT_MACRO, "content"));
            } else if ("util_command_bind".equals(modId)) {
                nodes.put(configNode(context, mod, CFG_CATALOG_COMMAND_BIND, "content"));
            }
            if (readout) {
                nodes.put(configNode(context, mod, CFG_OVERLAY_SIZE, "display"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_OPACITY, "display"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_LOCK, "display"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "display"));
            }
            int noteRes = catalogScopeNoteRes(modId);
            if (noteRes != 0) {
                nodes.put(scopeNoteNode(context, mod, noteRes, "content"));
            }
            return RuntimeConfigSchema.parse(new JSONObject().put("version", 2)
                    .put("default_category", "content")
                    .put("categories", categories).put("nodes", nodes).toString());
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to build catalogue config schema", e);
        }
    }

    private static int catalogScopeNoteRes(String modId) {
        switch (org.chimeramc.client.core.mods.inbuilt.catalog.PvpModuleScopes.scopeOf(modId)) {
            case GAME_OPTIONS:
                return R.string.catalog_options_scope_note;
            case LOCAL_FEED:
            case PEER_FEED:
                return R.string.catalog_local_scope_note;
            case DEVICE_INPUT:
                return R.string.catalog_scope_note;
            default:
                return 0;
        }
    }

    /**
     * Orders the menu: one contiguous run per group id, each sorted alphabetically by display name,
     * with the PvP group last.
     *
     * <p>Two invariants meet here. The menu draws one section header per contiguous run of a group
     * id, so every entry of a group must be adjacent — the voice module is declared in the middle of
     * the inbuilt list, and the PvP catalogue entries are appended apart from the hand-declared PvP
     * modules, so neither may simply be left in declaration order. Grouping by the id (rather than
     * only splitting PvP out) fixes both and keeps each header single.
     *
     * <p>And the player scans a section by name, so each run is sorted case-insensitively by display
     * name, with the id as a tie-break for a stable order. The sort is per run, never across the
     * whole list, or it would interleave the groups and split their headers again.
     */
    static List<UnifiedMod> groupPvpLast(List<UnifiedMod> mods) {
        // First-appearance order for the non-PvP groups, so the menu's section order stays the
        // order the provider declares rather than a hash order.
        java.util.LinkedHashMap<String, List<UnifiedMod>> byGroup = new java.util.LinkedHashMap<>();
        List<UnifiedMod> pvp = new ArrayList<>();
        for (UnifiedMod mod : mods) {
            if (ModIds.isPvpModule(mod.getId())) {
                pvp.add(mod);
                continue;
            }
            String group = mod.getGroupId() == null ? "" : mod.getGroupId();
            List<UnifiedMod> bucket = byGroup.get(group);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byGroup.put(group, bucket);
            }
            bucket.add(mod);
        }
        List<UnifiedMod> ordered = new ArrayList<>(mods.size());
        for (List<UnifiedMod> bucket : byGroup.values()) {
            bucket.sort(UnifiedModuleComparator.BY_NAME);
            ordered.addAll(bucket);
        }
        pvp.sort(UnifiedModuleComparator.BY_NAME);
        ordered.addAll(pvp);
        return ordered;
    }

    /** The name-then-id comparator shared by the section sort. */
    private static final class UnifiedModuleComparator {
        static final java.util.Comparator<UnifiedMod> BY_NAME = (a, b) -> {
            String an = a.getName() == null ? "" : a.getName();
            String bn = b.getName() == null ? "" : b.getName();
            int byName = an.compareToIgnoreCase(bn);
            if (byName != 0) return byName;
            String ai = a.getId() == null ? "" : a.getId();
            String bi = b.getId() == null ? "" : b.getId();
            return ai.compareTo(bi);
        };

        private UnifiedModuleComparator() {
        }
    }

    private static UnifiedMod create(Activity activity, InbuiltModManager manager,
                                     InbuiltOverlayManager overlayManager, String id,
                                     int nameRes, int descRes, String groupName) {
        boolean active = overlayManager != null
                ? overlayManager.isModActive(id)
                : manager.resolveInbuiltModEnabled(id, false);
        boolean customConfig = ModIds.POJAV_CONTROLS.equals(id) || ModIds.MORE_BUTTONS.equals(id)
                || ModIds.ARMOR_HUD.equals(id) || ModIds.CRYSTAL_OPTIMIZER.equals(id)
                || ModIds.HIT_REGISTRATION.equals(id) || ModIds.HIT_TIMING.equals(id)
                || ModIds.HITBOX.equals(id) || ModIds.VOICE_CHAT.equals(id)
                || ModIds.REACH_INDICATOR.equals(id) || ModIds.TRAJECTORY_PREDICTION.equals(id)
                || ModIds.HIT_PREDICTION.equals(id) || ModIds.KILL_EFFECTS.equals(id)
                || ModIds.OFFHAND.equals(id)
                || ModIds.CROSSHAIR.equals(id)
                || ModIds.HYPERSPATIAL_CULL.equals(id)
                || ModIds.VERTICAL_RENDER_COALESCE.equals(id)
                || ModIds.TICK_RATE_PREDICTOR.equals(id);
        // Combat modules get their own PvP section so the tab is a real destination, not just a
        // filter over the inbuilt list. They remain inbuilt modules, so the Inbuilt filter and
        // the "Inbuilt" grouping still find them.
        boolean pvpModule = ModIds.isPvpModule(id);
        UnifiedMod result = new UnifiedMod(
                id,
                activity.getString(nameRes),
                activity.getString(descRes),
                MOD_ID,
                UnifiedMod.Source.INBUILT,
                active,
                createConfigs(activity, manager, id),
                customConfig,
                pvpModule ? GROUP_PVP_ID : GROUP_ID,
                pvpModule ? activity.getString(R.string.mod_menu_group_pvp) : groupName,
                (mod, enabled) -> setEnabled(manager, mod, enabled),
                (mod, config, value) -> setConfig(manager, mod, config, value),
                ModIds.POJAV_CONTROLS.equals(id)
                        ? mod -> PojavControls.launchEditor(activity)
                        : (ModIds.MORE_BUTTONS.equals(id) ? mod -> MoreButtonsEditor.show(activity) : null)
        );
        result.setLocalConfigSchema(createLocalConfigSchema(activity, result));
        return result;
    }

    private static RuntimeConfigSchema createLocalConfigSchema(Context context, UnifiedMod mod) {
        String modId = mod.getId();
        if (ModIds.ARMOR_HUD.equals(modId) || ModIds.CRYSTAL_OPTIMIZER.equals(modId)
                || ModIds.HIT_REGISTRATION.equals(modId) || ModIds.HIT_TIMING.equals(modId)
                || ModIds.HITBOX.equals(modId)) {
            return createCombatConfigSchema(context, mod);
        }
        if (ModIds.VOICE_CHAT.equals(modId)) {
            return createVoiceConfigSchema(context, mod);
        }
        if (ModIds.REACH_INDICATOR.equals(modId) || ModIds.TRAJECTORY_PREDICTION.equals(modId)
                || ModIds.HIT_PREDICTION.equals(modId) || ModIds.KILL_EFFECTS.equals(modId)) {
            return createPvpSuiteConfigSchema(context, mod);
        }
        if (ModIds.OFFHAND.equals(modId)) {
            return createOffhandConfigSchema(context, mod);
        }
        if (org.chimeramc.client.core.mods.fps.FpsModuleCatalog.isFpsModule(modId)) {
            return createFpsConfigSchema(context, mod);
        }
        boolean hotbar = ModIds.HOTBAR_SLOT.equals(mod.getId());
        if (!hotbar && !ModIds.GYRO.equals(mod.getId())) return null;
        try {
            JSONArray categories = new JSONArray();
            JSONArray nodes = new JSONArray();
            if (hotbar) {
                categories.put(configCategory(context, "slots", R.string.mod_config_category_slots));
                categories.put(configCategory(context, "appearance", R.string.mod_config_category_appearance));
                categories.put(configCategory(context, "behavior", R.string.mod_config_category_behavior));
                nodes.put(configNode(context, mod, CFG_HOTBAR_ITEM_ICONS, "slots"));
                JSONArray slots = new JSONArray();
                for (int slot = 1; slot <= 9; slot++) {
                    String key = hotbarSlotConfigKey(slot, CFG_HOTBAR_SLOT_ENABLED);
                    slots.put(new JSONObject().put("key", key).put("value", key)
                            .put("label", mod.findConfigEntry(key).displayName));
                    String section = "slot_" + slot;
                    nodes.put(new JSONObject().put("id", section).put("type", "section")
                            .put("category", "appearance").put("collapsible", true)
                            .put("title", context.getString(R.string.mod_config_hotbar_slot_section, slot)));
                    nodes.put(configNode(context, mod, hotbarSlotConfigKey(slot, CFG_HOTBAR_SLOT_SIZE), "appearance")
                            .put("section", section));
                    nodes.put(configNode(context, mod, hotbarSlotConfigKey(slot, CFG_HOTBAR_SLOT_OPACITY), "appearance")
                            .put("section", section));
                }
                nodes.put(new JSONObject().put("id", "visible_slots").put("type", "toggle_group")
                        .put("category", "slots").put("title", context.getString(R.string.mod_config_visible_slots))
                        .put("options", slots));
                nodes.put(configNode(context, mod, CFG_OVERLAY_LOCK, "behavior"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "behavior"));
            } else {
                categories.put(configCategory(context, "motion", R.string.mod_config_category_motion));
                categories.put(configCategory(context, "button", R.string.mod_config_category_button));
                nodes.put(configNode(context, mod, CFG_GYRO_SENSITIVITY_X, "motion"));
                nodes.put(configNode(context, mod, CFG_GYRO_SENSITIVITY_Y, "motion"));
                nodes.put(configNode(context, mod, CFG_GYRO_INVERT_X, "motion"));
                nodes.put(configNode(context, mod, CFG_GYRO_INVERT_Y, "motion"));
                nodes.put(configNode(context, mod, CFG_GYRO_DEADZONE, "motion"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SIZE, "button"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_OPACITY, "button"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_LOCK, "button"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "button"));
            }
            return RuntimeConfigSchema.parse(new JSONObject().put("version", 2)
                    .put("default_category", hotbar ? "slots" : "motion")
                    .put("categories", categories).put("nodes", nodes).toString());
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to build inbuilt config schema", e);
        }
    }

    /**
     * Category layout for the three combat modules. They each get an explicit schema so the
     * dialog groups related settings rather than listing one flat column.
     */
    private static RuntimeConfigSchema createCombatConfigSchema(Context context, UnifiedMod mod) {
        String modId = mod.getId();
        try {
            JSONArray categories = new JSONArray();
            JSONArray nodes = new JSONArray();
            String defaultCategory;
            if (ModIds.ARMOR_HUD.equals(modId)) {
                categories.put(configCategory(context, "display", R.string.mod_config_category_appearance));
                categories.put(configCategory(context, "data", R.string.mod_config_category_behavior));
                defaultCategory = "display";
                nodes.put(configNode(context, mod, CFG_ARMOR_HUD_SHOW_TARGET, "display"));
                nodes.put(configNode(context, mod, CFG_ARMOR_HUD_SHOW_ENCHANTS, "display"));
                nodes.put(configNode(context, mod, CFG_ARMOR_HUD_STACKED, "display"));
                nodes.put(configNode(context, mod, CFG_ARMOR_HUD_REFRESH_MS, "data"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SIZE, "display"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_OPACITY, "display"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_LOCK, "data"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "data"));
            } else if (ModIds.CRYSTAL_OPTIMIZER.equals(modId)) {
                categories.put(configCategory(context, "safety", R.string.mod_config_category_behavior));
                categories.put(configCategory(context, "tuning", R.string.mod_config_category_motion));
                defaultCategory = "safety";
                nodes.put(configNode(context, mod, CFG_CRYSTAL_MANUAL_ASSIST, "safety"));
                nodes.put(configNode(context, mod, CFG_CRYSTAL_MIN_SELF_HP, "safety"));
                nodes.put(configNode(context, mod, CFG_CRYSTAL_MAX_RANGE, "tuning"));
                nodes.put(configNode(context, mod, CFG_CRYSTAL_PLACEMENT_DELAY_MS, "tuning"));
                nodes.put(configNode(context, mod, CFG_CRYSTAL_KEYBIND, "tuning"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SIZE, "tuning"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_OPACITY, "tuning"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_LOCK, "safety"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "safety"));
            } else if (ModIds.HIT_TIMING.equals(modId)) {
                categories.put(configCategory(context, "timing", R.string.mod_config_category_motion));
                categories.put(configCategory(context, "display", R.string.mod_config_category_appearance));
                defaultCategory = "timing";
                nodes.put(configNode(context, mod, CFG_HIT_TIMING_COOLDOWN_MS, "timing"));
                nodes.put(configNode(context, mod, CFG_HIT_TIMING_SHOW_COMBO, "display"));
                nodes.put(configNode(context, mod, CFG_HIT_TIMING_SHOW_BAR, "display"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_LOCK, "display"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "display"));
            } else if (ModIds.HITBOX.equals(modId)) {
                categories.put(configCategory(context, "entities", R.string.mod_config_category_slots));
                categories.put(configCategory(context, "guides", R.string.mod_config_category_appearance));
                defaultCategory = "entities";
                nodes.put(configNode(context, mod, CFG_HITBOX_SHOW_PLAYERS, "entities"));
                nodes.put(configNode(context, mod, CFG_HITBOX_SHOW_MOBS, "entities"));
                nodes.put(configNode(context, mod, CFG_HITBOX_SHOW_ITEMS, "entities"));
                nodes.put(configNode(context, mod, CFG_HITBOX_SHOW_PROJECTILES, "entities"));
                nodes.put(configNode(context, mod, CFG_HITBOX_SHOW_THROWN_ITEMS, "entities"));
                nodes.put(configNode(context, mod, CFG_HITBOX_SHOW_LOOK_LINE, "guides"));
                nodes.put(configNode(context, mod, CFG_HITBOX_SHOW_TARGET_BOX, "guides"));
                nodes.put(configNode(context, mod, CFG_HITBOX_SHOW_CRIT_LINE, "guides"));
                nodes.put(configNode(context, mod, CFG_HITBOX_SHOW_COMBO_BOX, "guides"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "guides"));
            } else {
                categories.put(configCategory(context, "aim", R.string.mod_config_category_motion));
                categories.put(configCategory(context, "feedback", R.string.mod_config_category_button));
                defaultCategory = "aim";
                nodes.put(configNode(context, mod, CFG_HITREG_SENSITIVITY, "aim"));
                nodes.put(configNode(context, mod, CFG_HITREG_SMOOTHING, "aim"));
                nodes.put(configNode(context, mod, CFG_HITREG_PREDICTION, "aim"));
                nodes.put(configNode(context, mod, CFG_HITREG_HAPTIC, "feedback"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_LOCK, "feedback"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "feedback"));
            }
            int noteRes = scopeNoteRes(modId);
            if (noteRes != 0) {
                nodes.put(scopeNoteNode(context, mod, noteRes, defaultCategory));
            }
            return RuntimeConfigSchema.parse(new JSONObject().put("version", 2)
                    .put("default_category", defaultCategory)
                    .put("categories", categories).put("nodes", nodes).toString());
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to build combat config schema", e);
        }
    }

    /**
     * Category layout for the proximity voice module: the microphone and the icon's appearance.
     *
     * <p><b>Scope is deliberately trimmed to mic-only.</b> Channel name, visibility, capacity and
     * per-member mute all live on the Voice tab now, so this dialog does not duplicate them -- a
     * control in two places is a control that can disagree with itself. What remains is global and
     * genuinely belongs to the module: whether the mic transmits at all, and how the in-world
     * nametag icon looks and behaves.
     *
     * <p>The scope note is placed in the default category so it is the first thing read -- the
     * module is named "Proximity Voice Chat" but only applies distance when a position feed
     * exists, and that distinction has to be on screen, not buried.
     */
    private static RuntimeConfigSchema createVoiceConfigSchema(Context context, UnifiedMod mod) {
        try {
            JSONArray categories = new JSONArray();
            JSONArray nodes = new JSONArray();
            categories.put(configCategory(context, "mic", R.string.mod_config_category_behavior));
            categories.put(configCategory(context, "icon", R.string.mod_config_category_appearance));
            categories.put(configCategory(context, "overlay", R.string.mod_config_category_button));
            nodes.put(configNode(context, mod, CFG_VOICE_MIC, "mic"));
            nodes.put(configNode(context, mod, CFG_VOICE_ICON_STYLE, "icon"));
            nodes.put(configNode(context, mod, CFG_VOICE_ICON_ANIMATE, "icon"));
            nodes.put(configNode(context, mod, CFG_VOICE_ICON_NAMETAG, "icon"));
            nodes.put(configNode(context, mod, CFG_OVERLAY_SIZE, "overlay"));
            nodes.put(configNode(context, mod, CFG_OVERLAY_OPACITY, "overlay"));
            nodes.put(configNode(context, mod, CFG_OVERLAY_LOCK, "overlay"));
            nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "overlay"));
            nodes.put(scopeNoteNode(context, mod, R.string.voice_chat_scope_note, "mic"));
            return RuntimeConfigSchema.parse(new JSONObject().put("version", 2)
                    .put("default_category", "mic")
                    .put("categories", categories).put("nodes", nodes).toString());
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to build voice config schema", e);
        }
    }

    /**
     * Category layout for the four PvP Suite modules (V1.1).
     *
     * <p>Each gets a short, focused dialog: the module's own display options plus the shared
     * "show everywhere" switch, and an info node carrying its scope note. The note is deliberate -
     * every module in the suite is named after an outcome (Reach Indicator, Hit Prediction) but
     * only works from the peer feed this build can actually reach, so the dialog says what it can
     * and cannot see rather than leaving the limit to read as a broken feature.
     */
    private static RuntimeConfigSchema createFpsConfigSchema(Context context, UnifiedMod mod) {
        org.chimeramc.client.core.mods.fps.FpsModuleCatalog.Module module =
                org.chimeramc.client.core.mods.fps.FpsModuleCatalog.find(mod.getId());
        if (module == null) return null;
        try {
            JSONArray categories = new JSONArray();
            JSONArray nodes = new JSONArray();
            // The three FPS modules keep only their implemented options, so a category is only
            // created when an option actually uses it.
            categories.put(configCategory(context, "main", R.string.mod_config_category_culling));
            categories.put(configCategory(context, "batching", R.string.mod_config_category_batching));
            categories.put(configCategory(context, "memory", R.string.mod_config_category_memory));
            categories.put(configCategory(context, "timing", R.string.mod_config_category_timing));
            categories.put(configCategory(context, "network", R.string.mod_config_category_network));

            nodes.put(scopeNoteNode(context, mod, R.string.fps_modules_scope_note, "main"));

            for (org.chimeramc.client.core.mods.fps.FpsModuleCatalog.Option option : module.options) {
                JSONObject node = new JSONObject()
                        .put("id", option.key)
                        .put("key", option.key)
                        .put("category", fpsCategoryFor(option.key))
                        .put("title", option.label)
                        .put("type", option.isToggle() ? "toggle" : "slider")
                        .put("default_value", option.defaultValue)
                        .put("min_value", String.valueOf(option.min))
                        .put("max_value", String.valueOf(option.max))
                        .put("description", option.description);
                nodes.put(node);
            }
            return RuntimeConfigSchema.parse(new JSONObject().put("version", 2)
                    .put("default_category", "main")
                    .put("categories", categories).put("nodes", nodes).toString());
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to build FPS module config schema", e);
        }
    }

    /** The category a kept FPS option belongs to. Only the seven implemented options are present. */
    private static String fpsCategoryFor(String key) {
        if (key.startsWith("tr_")) {
            return key.contains("net") || key.contains("coalesce") ? "network" : "timing";
        }
        if (key.startsWith("vc_")) {
            return key.contains("atlas") ? "memory" : "batching";
        }
        return "main";
    }

    private static RuntimeConfigSchema createPvpSuiteConfigSchema(Context context, UnifiedMod mod) {
        String modId = mod.getId();
        try {
            JSONArray categories = new JSONArray();
            JSONArray nodes = new JSONArray();
            categories.put(configCategory(context, "display", R.string.mod_config_category_appearance));
            categories.put(configCategory(context, "behavior", R.string.mod_config_category_behavior));
            if (ModIds.REACH_INDICATOR.equals(modId)) {
                nodes.put(configNode(context, mod, CFG_REACH_POSITION, "display"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "behavior"));
            } else if (ModIds.TRAJECTORY_PREDICTION.equals(modId)) {
                nodes.put(configNode(context, mod, CFG_TRAJECTORY_COLOR, "display"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "behavior"));
            } else if (ModIds.HIT_PREDICTION.equals(modId)) {
                nodes.put(configNode(context, mod, CFG_HIT_PREDICTION_LOOKAHEAD, "display"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "behavior"));
            } else {
                nodes.put(configNode(context, mod, CFG_KILL_EFFECT_STYLE, "display"));
                nodes.put(configNode(context, mod, CFG_KILL_EFFECT_COLOR, "display"));
                nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "behavior"));
            }
            int noteRes = scopeNoteRes(modId);
            if (noteRes != 0) {
                nodes.put(scopeNoteNode(context, mod, noteRes, "display"));
            }
            return RuntimeConfigSchema.parse(new JSONObject().put("version", 2)
                    .put("default_category", "display")
                    .put("categories", categories).put("nodes", nodes).toString());
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to build PvP suite config schema", e);
        }
    }

    /**
     * Category layout for the Offhand module.
     *
     * <p>Two categories: what the on-screen button does (mode + overlay look) and the hardware
     * keybinds. The scope note is in the default category because the module is honest about being
     * a key-sender — Bedrock resolves the swap/use, so it works everywhere the game does.
     */
    private static RuntimeConfigSchema createOffhandConfigSchema(Context context, UnifiedMod mod) {
        try {
            JSONArray categories = new JSONArray();
            JSONArray nodes = new JSONArray();
            categories.put(configCategory(context, "action", R.string.mod_config_category_behavior));
            categories.put(configCategory(context, "keys", R.string.mod_config_category_button));
            categories.put(configCategory(context, "overlay", R.string.mod_config_category_appearance));
            nodes.put(configNode(context, mod, CFG_OFFHAND_MODE, "action"));
            nodes.put(configNode(context, mod, CFG_OFFHAND_INVENTORY_KEY, "keys"));
            nodes.put(configNode(context, mod, CFG_OFFHAND_USE_KEY, "keys"));
            nodes.put(configNode(context, mod, CFG_OFFHAND_KEYBIND, "keys"));
            nodes.put(configNode(context, mod, CFG_OVERLAY_SIZE, "overlay"));
            nodes.put(configNode(context, mod, CFG_OVERLAY_OPACITY, "overlay"));
            nodes.put(configNode(context, mod, CFG_OVERLAY_LOCK, "overlay"));
            nodes.put(configNode(context, mod, CFG_OVERLAY_SHOW_EVERYWHERE, "overlay"));
            nodes.put(scopeNoteNode(context, mod, R.string.offhand_scope_note, "action"));
            return RuntimeConfigSchema.parse(new JSONObject().put("version", 2)
                    .put("default_category", "action")
                    .put("categories", categories).put("nodes", nodes).toString());
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to build offhand config schema", e);
        }
    }

    private static JSONObject configCategory(Context context, String id, int titleRes) throws JSONException {
        return new JSONObject().put("id", id).put("title", context.getString(titleRes));
    }

    private static JSONObject configNode(Context context, UnifiedMod mod, String key, String category) throws JSONException {
        UnifiedMod.ConfigEntry config = mod.findConfigEntry(key);
        JSONObject node = new JSONObject().put("id", key).put("key", key)
                .put("category", category).put("title", config.displayName)
                .put("type", schemaTypeFor(config.type))
                .put("default_value", config.defaultValue)
                .put("min_value", config.minValue).put("max_value", config.maxValue);
        // A choice node needs its options in the schema: the dialog builds the control from the
        // node alone, so a RADIO without options renders an empty selector. The labels live in
        // min_value and the values are their indices, matching the legacy config renderer.
        if (config.type == UnifiedMod.ConfigType.RADIO) {
            JSONArray options = new JSONArray();
            String[] labels = config.minValue != null ? config.minValue.split(",") : new String[0];
            for (int i = 0; i < labels.length; i++) {
                options.put(new JSONObject().put("value", String.valueOf(i))
                        .put("label", labels[i].trim()));
            }
            node.put("options", options);
        }
        int descRes = configDescriptionRes(key);
        if (descRes != 0) {
            node.put("description", context.getString(descRes));
        }
        if (!config.dependsOn.isEmpty()) {
            node.put("enabled_when", new JSONArray().put(new JSONObject()
                    .put("key", config.dependsOn).put("op", "truthy")));
        }
        // An output the native entity feed would have to supply is disabled rather than hidden:
        // the switch stays visible so the option is discoverable, but it cannot be turned on to
        // no effect. The same rule the module-level badge uses, one level down.
        if (ModIds.outputRequiresGameData(mod.getId(), key)) {
            node.put("disabled", true);
        }
        return node;
    }

    /**
     * The `_desc` string for a config key, or 0 when there is none.
     *
     * <p>Resolved through an explicit table rather than a name lookup so a renamed key cannot
     * silently start matching an unrelated string.
     */
    static int configDescriptionRes(String key) {
        switch (key) {
            case CFG_ARMOR_HUD_SHOW_TARGET: return R.string.mod_config_armor_hud_show_target_desc;
            case CFG_ARMOR_HUD_SHOW_ENCHANTS: return R.string.mod_config_armor_hud_show_enchants_desc;
            case CFG_ARMOR_HUD_STACKED: return R.string.mod_config_armor_hud_stacked_desc;
            case CFG_ARMOR_HUD_REFRESH_MS: return R.string.mod_config_armor_hud_refresh_ms_desc;
            case CFG_HITREG_SENSITIVITY: return R.string.mod_config_hitreg_sensitivity_desc;
            case CFG_HITREG_SMOOTHING: return R.string.mod_config_hitreg_smoothing_desc;
            case CFG_HITREG_PREDICTION: return R.string.mod_config_hitreg_prediction_desc;
            case CFG_HITREG_HAPTIC: return R.string.mod_config_hitreg_haptic_desc;
            case CFG_CRYSTAL_PLACEMENT_DELAY_MS: return R.string.mod_config_crystal_placement_delay_ms_desc;
            case CFG_CRYSTAL_MANUAL_ASSIST: return R.string.mod_config_crystal_manual_assist_desc;
            case CFG_CRYSTAL_MIN_SELF_HP: return R.string.mod_config_crystal_min_self_hp_desc;
            case CFG_CRYSTAL_MAX_RANGE: return R.string.mod_config_crystal_max_range_desc;
            case CFG_CRYSTAL_KEYBIND: return R.string.mod_config_crystal_keybind_desc;
            case CFG_HIT_TIMING_COOLDOWN_MS: return R.string.mod_config_hit_timing_cooldown_ms_desc;
            case CFG_HIT_TIMING_SHOW_COMBO: return R.string.mod_config_hit_timing_show_combo_desc;
            case CFG_HIT_TIMING_SHOW_BAR: return R.string.mod_config_hit_timing_show_bar_desc;
            case CFG_HITBOX_SHOW_PLAYERS: return R.string.mod_config_hitbox_show_players_desc;
            case CFG_HITBOX_SHOW_MOBS: return R.string.mod_config_hitbox_show_mobs_desc;
            case CFG_HITBOX_SHOW_ITEMS: return R.string.mod_config_hitbox_show_items_desc;
            case CFG_HITBOX_SHOW_PROJECTILES: return R.string.mod_config_hitbox_show_projectiles_desc;
            case CFG_HITBOX_SHOW_THROWN_ITEMS: return R.string.mod_config_hitbox_show_thrown_items_desc;
            case CFG_HITBOX_SHOW_LOOK_LINE: return R.string.mod_config_hitbox_show_look_line_desc;
            case CFG_HITBOX_SHOW_CRIT_LINE: return R.string.mod_config_hitbox_show_crit_line_desc;
            case CFG_HITBOX_SHOW_COMBO_BOX: return R.string.mod_config_hitbox_show_combo_box_desc;
            case CFG_HITBOX_SHOW_TARGET_BOX: return R.string.mod_config_hitbox_show_target_box_desc;
            case CFG_VOICE_RANGE: return R.string.mod_config_voice_range_desc;
            case CFG_VOICE_VOLUME: return R.string.mod_config_voice_volume_desc;
            case CFG_VOICE_CHANNEL: return R.string.mod_config_voice_channel_desc;
            case CFG_VOICE_MIC: return R.string.mod_config_voice_mic_desc;
            case CFG_VOICE_ICON_STYLE: return R.string.mod_config_voice_icon_style_desc;
            case CFG_VOICE_ICON_ANIMATE: return R.string.mod_config_voice_icon_animate_desc;
            case CFG_VOICE_ICON_NAMETAG: return R.string.mod_config_voice_icon_nametag_desc;
            case CFG_REACH_POSITION: return R.string.mod_config_reach_position_desc;
            case CFG_TRAJECTORY_COLOR: return R.string.mod_config_trajectory_color_desc;
            case CFG_HIT_PREDICTION_LOOKAHEAD: return R.string.mod_config_hit_prediction_lookahead_desc;
            case CFG_KILL_EFFECT_STYLE: return R.string.mod_config_kill_effect_style_desc;
            case CFG_KILL_EFFECT_COLOR: return R.string.mod_config_kill_effect_color_desc;
            default: return 0;
        }
    }

    /**
     * An informational node carrying the module's scope note, or null when it has none.
     *
     * <p>These modules are named after an outcome ("Hit Registration") but deliberately stop
     * short of it — the server decides whether a hit lands, and the box feed may not exist yet.
     * Saying so in the dialog is what stops the honest limit reading as a broken feature.
     */
    private static JSONObject scopeNoteNode(Context context, UnifiedMod mod,
                                            int noteRes, String category) throws JSONException {
        return new JSONObject()
                .put("id", mod.getId() + "_scope_note")
                .put("type", "info")
                .put("category", category)
                .put("title", context.getString(R.string.mod_config_scope_note_title))
                .put("description", context.getString(noteRes));
    }

    private static int scopeNoteRes(String modId) {
        if (ModIds.HIT_REGISTRATION.equals(modId)) return R.string.hitreg_scope_note;
        if (ModIds.CRYSTAL_OPTIMIZER.equals(modId)) return R.string.crystal_optimizer_fairness_note;
        if (ModIds.HIT_TIMING.equals(modId)) return R.string.hit_timing_scope_note;
        if (ModIds.HITBOX.equals(modId)) return R.string.hitbox_scope_note;
        if (ModIds.ARMOR_HUD.equals(modId)) return R.string.armor_hud_no_data;
        if (ModIds.VOICE_CHAT.equals(modId)) return R.string.voice_chat_scope_note;
        if (ModIds.REACH_INDICATOR.equals(modId)) return R.string.reach_indicator_scope_note;
        if (ModIds.TRAJECTORY_PREDICTION.equals(modId)) return R.string.trajectory_prediction_scope_note;
        if (ModIds.HIT_PREDICTION.equals(modId)) return R.string.hit_prediction_scope_note;
        if (ModIds.KILL_EFFECTS.equals(modId)) return R.string.kill_effects_scope_note;
        if (org.chimeramc.client.core.mods.fps.FpsModuleCatalog.isFpsModule(modId)) {
            return R.string.fps_modules_scope_note;
        }
        return 0;
    }

    /**
     * Maps a config entry's type to the schema node type the dialog renders.
     *
     * This must be the real type, not a toggle/slider default: the dialog builds its control
     * from the node type alone, so a KEYBIND node declared as a slider would show a 0..100
     * range and write a small integer over the stored key code, and a RADIO node would lose
     * its options. Anything the schema cannot express (the legacy colour picker) still falls
     * back to a slider rather than silently dropping the setting.
     */
    static String schemaTypeFor(UnifiedMod.ConfigType type) {
        switch (type) {
            case TOGGLE:
                return "toggle";
            case SLIDER_INT:
                return "slider_int";
            case SLIDER_FLOAT:
                return "slider_float";
            case RADIO:
                return "choice";
            case KEYBIND:
                return "keybind";
            case TEXT:
                return "text";
            case BUTTON:
                return "button";
            case COLOR:
            default:
                return "slider_int";
        }
    }

    private static List<UnifiedMod.ConfigEntry> createConfigs(Context context,
                                                              InbuiltModManager manager,
                                                              String modId) {
        List<UnifiedMod.ConfigEntry> configs = new ArrayList<>();
        if (ModIds.POJAV_CONTROLS.equals(modId) || ModIds.MORE_BUTTONS.equals(modId)) return configs;
        if (!ModIds.CHICK_PET.equals(modId)) {
            if (!ModIds.HOTBAR_SLOT.equals(modId)) {
                configs.add(config(CFG_OVERLAY_SIZE,
                        context.getString(R.string.mod_config_overlay_button_size_dp),
                        UnifiedMod.ConfigType.SLIDER_INT,
                        "56", "20", "100",
                        String.valueOf(manager.getOverlayButtonSize(modId))));
                configs.add(config(CFG_OVERLAY_OPACITY,
                        context.getString(R.string.mod_config_overlay_opacity_percent),
                        UnifiedMod.ConfigType.SLIDER_INT,
                        "100", "0", "100",
                        String.valueOf(manager.getOverlayOpacity(modId))));
            }
            configs.add(config(CFG_OVERLAY_LOCK,
                    context.getString(R.string.overlay_button_lock),
                    UnifiedMod.ConfigType.TOGGLE,
                    "false", "", "",
                    String.valueOf(manager.isOverlayLocked(modId))));
            configs.add(config(CFG_OVERLAY_SHOW_EVERYWHERE,
                    context.getString(R.string.mod_config_overlay_show_everywhere),
                    UnifiedMod.ConfigType.TOGGLE,
                    "false", "", "",
                    String.valueOf(manager.isOverlayShowEverywhere(modId))));
        }

        if (ModIds.HOTBAR_SLOT.equals(modId)) {
            configs.add(config(CFG_HOTBAR_ITEM_ICONS,
                    context.getString(R.string.mod_config_hotbar_item_icons),
                    UnifiedMod.ConfigType.TOGGLE,
                    "false", "", "",
                    String.valueOf(manager.isHotbarItemIconsEnabled())));
            for (int slot = 1; slot <= 9; slot++) {
                String enabledKey = hotbarSlotConfigKey(slot, CFG_HOTBAR_SLOT_ENABLED);
                String overlayKey = ModIds.HOTBAR_SLOT + ":" + slot;
                configs.add(config(enabledKey,
                        context.getString(R.string.mod_config_hotbar_slot_enabled, slot),
                        UnifiedMod.ConfigType.TOGGLE,
                        "true", "", "",
                        String.valueOf(manager.isHotbarSlotEnabled(slot))));
                configs.add(config(hotbarSlotConfigKey(slot, CFG_HOTBAR_SLOT_SIZE),
                        context.getString(R.string.mod_config_hotbar_slot_size, slot),
                        UnifiedMod.ConfigType.SLIDER_INT,
                        "56", "20", "100",
                        String.valueOf(manager.getOverlayButtonSize(overlayKey)), enabledKey));
                configs.add(config(hotbarSlotConfigKey(slot, CFG_HOTBAR_SLOT_OPACITY),
                        context.getString(R.string.mod_config_hotbar_slot_opacity, slot),
                        UnifiedMod.ConfigType.SLIDER_INT,
                        "100", "0", "100",
                        String.valueOf(manager.getOverlayOpacity(overlayKey)), enabledKey));
            }
        }

        if (ModIds.OFFHAND.equals(modId)) {
            configs.add(config(CFG_OFFHAND_MODE,
                    context.getString(R.string.mod_config_offhand_mode),
                    UnifiedMod.ConfigType.RADIO,
                    context.getString(R.string.mod_config_offhand_mode_labels), "", "",
                    String.valueOf(manager.getOffhandMode())));
            configs.add(config(CFG_OFFHAND_INVENTORY_KEY,
                    context.getString(R.string.mod_config_offhand_inventory_key),
                    UnifiedMod.ConfigType.KEYBIND,
                    "", "", "",
                    String.valueOf(manager.getOffhandInventoryKey())));
            configs.add(config(CFG_OFFHAND_USE_KEY,
                    context.getString(R.string.mod_config_offhand_use_key),
                    UnifiedMod.ConfigType.KEYBIND,
                    "", "", "",
                    String.valueOf(manager.getOffhandUseKey())));
            configs.add(config(CFG_OFFHAND_KEYBIND,
                    context.getString(R.string.mod_config_offhand_keybind),
                    UnifiedMod.ConfigType.KEYBIND,
                    "", "", "",
                    String.valueOf(manager.getOffhandKeybind())));
        }

        if (ModIds.AUTO_SPRINT.equals(modId)) {
            configs.add(config(CFG_AUTO_SPRINT_KEYBIND,
                    context.getString(R.string.mod_config_auto_sprint_keybind),
                    UnifiedMod.ConfigType.KEYBIND,
                    "", "", "",
                    String.valueOf(manager.getAutoSprintKeybind())));
        } else if (ModIds.VIRTUAL_CURSOR.equals(modId)) {
            configs.add(config(CFG_CURSOR_SENSITIVITY,
                    context.getString(R.string.mod_config_cursor_sensitivity_percent),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "120", "10", "200",
                    String.valueOf(manager.getCursorSensitivity())));
        } else if (ModIds.ZOOM.equals(modId)) {
            configs.add(config(CFG_ZOOM_LEVEL,
                    context.getString(R.string.mod_config_zoom_level_percent),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "10", "-20", "100",
                    String.valueOf(manager.getZoomLevel())));
            configs.add(config(CFG_ZOOM_TRANSITION,
                    context.getString(R.string.mod_config_zoom_transition),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "150", "0", "1000",
                    String.valueOf(manager.getZoomTransitionDuration())));
            configs.add(config(CFG_ZOOM_KEYBIND,
                    context.getString(R.string.mod_config_zoom_keybind),
                    UnifiedMod.ConfigType.KEYBIND,
                    "", "", "",
                    String.valueOf(manager.getZoomKeybind())));
        } else if (ModIds.GYRO.equals(modId)) {
            configs.add(config(CFG_GYRO_SENSITIVITY_X,
                    context.getString(R.string.mod_config_gyro_sensitivity_x),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "100", "10", "300",
                    String.valueOf(manager.getGyroSensitivityX())));
            configs.add(config(CFG_GYRO_SENSITIVITY_Y,
                    context.getString(R.string.mod_config_gyro_sensitivity_y),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "100", "10", "300",
                    String.valueOf(manager.getGyroSensitivityY())));
            configs.add(config(CFG_GYRO_INVERT_X,
                    context.getString(R.string.mod_config_gyro_invert_x),
                    UnifiedMod.ConfigType.TOGGLE,
                    "false", "", "",
                    String.valueOf(manager.isGyroInvertX())));
            configs.add(config(CFG_GYRO_INVERT_Y,
                    context.getString(R.string.mod_config_gyro_invert_y),
                    UnifiedMod.ConfigType.TOGGLE,
                    "false", "", "",
                    String.valueOf(manager.isGyroInvertY())));
            configs.add(config(CFG_GYRO_DEADZONE,
                    context.getString(R.string.mod_config_gyro_deadzone),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "5", "0", "50",
                    String.valueOf(manager.getGyroDeadzone())));
        } else if (ModIds.AIM_SETTINGS.equals(modId)) {
            configs.add(config(CFG_AIM_SENSITIVITY,
                    context.getString(R.string.mod_config_aim_sensitivity),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "100", "10", "300",
                    String.valueOf(manager.getAimSensitivity())));
            configs.add(config(CFG_AIM_SMOOTHING,
                    context.getString(R.string.mod_config_aim_smoothing),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "40", "0", "95",
                    String.valueOf(manager.getAimSmoothing())));
            configs.add(config(CFG_AIM_CROSSHAIR,
                    context.getString(R.string.mod_config_aim_crosshair),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isAimCrosshairEnabled())));
            configs.add(config(CFG_AIM_CROSSHAIR_STYLE,
                    context.getString(R.string.aim_crosshair_style),
                    UnifiedMod.ConfigType.RADIO,
                    "1",
                    context.getString(R.string.aim_crosshair_style_dot) + ","
                            + context.getString(R.string.aim_crosshair_style_cross) + ","
                            + context.getString(R.string.aim_crosshair_style_circle),
                    "",
                    String.valueOf(manager.getAimCrosshairStyle())));
            configs.add(config(CFG_AIM_CROSSHAIR_COLOR,
                    context.getString(R.string.aim_crosshair_color),
                    UnifiedMod.ConfigType.COLOR,
                    "#FF3DDC84", "", "",
                    colorToHex(manager.getAimCrosshairColor())));
            configs.add(config(CFG_AIM_FLASH,
                    context.getString(R.string.mod_config_aim_flash),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isAimFlashEnabled())));
        } else if (ModIds.ARMOR_HUD.equals(modId)) {
            configs.add(config(CFG_ARMOR_HUD_SHOW_TARGET,
                    context.getString(R.string.mod_config_armor_hud_show_target),
                    UnifiedMod.ConfigType.TOGGLE,
                    "false", "", "",
                    String.valueOf(manager.isArmorHudShowTarget())));
            configs.add(config(CFG_ARMOR_HUD_SHOW_ENCHANTS,
                    context.getString(R.string.mod_config_armor_hud_show_enchants),
                    UnifiedMod.ConfigType.TOGGLE,
                    "false", "", "",
                    String.valueOf(manager.isArmorHudShowEnchants())));
            configs.add(config(CFG_ARMOR_HUD_STACKED,
                    context.getString(R.string.mod_config_armor_hud_stacked),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isArmorHudStacked())));
            configs.add(config(CFG_ARMOR_HUD_REFRESH_MS,
                    context.getString(R.string.mod_config_armor_hud_refresh_ms),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "100", "50", "1000",
                    String.valueOf(manager.getArmorHudRefreshMs())));
        } else if (ModIds.CRYSTAL_OPTIMIZER.equals(modId)) {
            // Manual assist is listed first and defaults on: it is the variant that does not
            // automate a player action, so it must be the one a fresh profile lands on.
            configs.add(config(CFG_CRYSTAL_MANUAL_ASSIST,
                    context.getString(R.string.mod_config_crystal_manual_assist),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isCrystalManualAssist())));
            configs.add(config(CFG_CRYSTAL_MIN_SELF_HP,
                    context.getString(R.string.mod_config_crystal_min_self_hp),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "14", "1", "20",
                    String.valueOf(manager.getCrystalMinSelfHp())));
            configs.add(config(CFG_CRYSTAL_MAX_RANGE,
                    context.getString(R.string.mod_config_crystal_max_range),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "4", "1", "8",
                    String.valueOf(manager.getCrystalMaxRange())));
            configs.add(config(CFG_CRYSTAL_PLACEMENT_DELAY_MS,
                    context.getString(R.string.mod_config_crystal_placement_delay_ms),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "50", "0", "1000",
                    String.valueOf(manager.getCrystalPlacementDelayMs())));
            configs.add(config(CFG_CRYSTAL_KEYBIND,
                    context.getString(R.string.mod_config_crystal_keybind),
                    UnifiedMod.ConfigType.KEYBIND,
                    "", "", "",
                    String.valueOf(manager.getCrystalKeybind())));
        } else if (ModIds.HIT_REGISTRATION.equals(modId)) {
            configs.add(config(CFG_HITREG_SENSITIVITY,
                    context.getString(R.string.mod_config_hitreg_sensitivity),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "100", "10", "300",
                    String.valueOf(manager.getHitRegSensitivity())));
            configs.add(config(CFG_HITREG_SMOOTHING,
                    context.getString(R.string.mod_config_hitreg_smoothing),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "25", "0", "95",
                    String.valueOf(manager.getHitRegSmoothing())));
            configs.add(config(CFG_HITREG_PREDICTION,
                    context.getString(R.string.mod_config_hitreg_prediction),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    "35", "0", "100",
                    String.valueOf(manager.getHitRegPrediction())));
            configs.add(config(CFG_HITREG_HAPTIC,
                    context.getString(R.string.mod_config_hitreg_haptic),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isHitRegHapticEnabled())));
        } else if (ModIds.HIT_TIMING.equals(modId)) {
            configs.add(config(CFG_HIT_TIMING_COOLDOWN_MS,
                    context.getString(R.string.mod_config_hit_timing_cooldown_ms),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    String.valueOf(HitTimingSolver.DEFAULT_COOLDOWN_MS), "50", "2000",
                    String.valueOf(manager.getHitTimingCooldownMs())));
            configs.add(config(CFG_HIT_TIMING_SHOW_COMBO,
                    context.getString(R.string.mod_config_hit_timing_show_combo),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isHitTimingShowCombo())));
            configs.add(config(CFG_HIT_TIMING_SHOW_BAR,
                    context.getString(R.string.mod_config_hit_timing_show_bar),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isHitTimingShowTimingBar())));
        } else if (ModIds.HITBOX.equals(modId)) {
            configs.add(config(CFG_HITBOX_SHOW_PLAYERS,
                    context.getString(R.string.mod_config_hitbox_show_players),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isHitboxShowPlayers())));
            configs.add(config(CFG_HITBOX_SHOW_MOBS,
                    context.getString(R.string.mod_config_hitbox_show_mobs),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isHitboxShowMobs())));
            configs.add(config(CFG_HITBOX_SHOW_ITEMS,
                    context.getString(R.string.mod_config_hitbox_show_items),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isHitboxShowItems())));
            configs.add(config(CFG_HITBOX_SHOW_PROJECTILES,
                    context.getString(R.string.mod_config_hitbox_show_projectiles),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isHitboxShowProjectiles())));
            configs.add(config(CFG_HITBOX_SHOW_THROWN_ITEMS,
                    context.getString(R.string.mod_config_hitbox_show_thrown_items),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isHitboxShowThrownItems())));
            configs.add(config(CFG_HITBOX_SHOW_LOOK_LINE,
                    context.getString(R.string.mod_config_hitbox_show_look_line),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isHitboxShowLookLine())));
            configs.add(config(CFG_HITBOX_SHOW_CRIT_LINE,
                    context.getString(R.string.mod_config_hitbox_show_crit_line),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isHitboxShowCritLine())));
            configs.add(config(CFG_HITBOX_SHOW_COMBO_BOX,
                    context.getString(R.string.mod_config_hitbox_show_combo_box),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isHitboxShowComboBox())));
            configs.add(config(CFG_HITBOX_SHOW_TARGET_BOX,
                    context.getString(R.string.mod_config_hitbox_show_target_box),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isHitboxShowTargetBox())));
        } else if (ModIds.REACH_INDICATOR.equals(modId)) {
            configs.add(config(CFG_REACH_POSITION,
                    context.getString(R.string.mod_config_reach_position),
                    UnifiedMod.ConfigType.RADIO,
                    "0",
                    context.getString(R.string.mod_config_reach_position_below) + ","
                            + context.getString(R.string.mod_config_reach_position_hotbar),
                    "",
                    String.valueOf(manager.getReachIndicatorPosition())));
        } else if (ModIds.TRAJECTORY_PREDICTION.equals(modId)) {
            configs.add(config(CFG_TRAJECTORY_COLOR,
                    context.getString(R.string.mod_config_trajectory_color),
                    UnifiedMod.ConfigType.COLOR,
                    "#FF7CFC5A", "", "",
                    colorToHex(manager.getTrajectoryColor())));
        } else if (ModIds.HIT_PREDICTION.equals(modId)) {
            configs.add(config(CFG_HIT_PREDICTION_LOOKAHEAD,
                    context.getString(R.string.mod_config_hit_prediction_lookahead),
                    UnifiedMod.ConfigType.SLIDER_INT,
                    String.valueOf(org.chimeramc.client.core.mods.inbuilt.overlay.HitPredictor.DEFAULT_LOOK_AHEAD_MS),
                    String.valueOf(org.chimeramc.client.core.mods.inbuilt.overlay.HitPredictor.MIN_LOOK_AHEAD_MS),
                    String.valueOf(org.chimeramc.client.core.mods.inbuilt.overlay.HitPredictor.MAX_LOOK_AHEAD_MS),
                    String.valueOf(manager.getHitPredictionLookAheadMs())));
        } else if (ModIds.KILL_EFFECTS.equals(modId)) {
            configs.add(config(CFG_KILL_EFFECT_STYLE,
                    context.getString(R.string.mod_config_kill_effect_style),
                    UnifiedMod.ConfigType.RADIO,
                    "0",
                    context.getString(R.string.kill_effect_style_burst) + ","
                            + context.getString(R.string.kill_effect_style_column) + ","
                            + context.getString(R.string.kill_effect_style_ring),
                    "",
                    String.valueOf(manager.getKillEffectStyle())));
            configs.add(config(CFG_KILL_EFFECT_COLOR,
                    context.getString(R.string.mod_config_kill_effect_color),
                    UnifiedMod.ConfigType.COLOR,
                    "#FFFFC24B", "", "",
                    colorToHex(manager.getKillEffectColor())));
        } else if (ModIds.VOICE_CHAT.equals(modId)) {
            // Trimmed to mic-only scope: the mic master switch plus the in-world icon's look and
            // behaviour. Channel, visibility, capacity and per-member mute are owned by the Voice
            // tab, so they are deliberately absent here to avoid a second source of truth.
            configs.add(config(CFG_VOICE_MIC,
                    context.getString(R.string.mod_config_voice_mic),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isVoiceMicEnabled())));
            configs.add(config(CFG_VOICE_ICON_STYLE,
                    context.getString(R.string.mod_config_voice_icon_style),
                    UnifiedMod.ConfigType.TOGGLE,
                    "false", "", "",
                    String.valueOf(manager.getVoiceIconStyle()
                            == org.chimeramc.client.core.mods.inbuilt.overlay.MicIconStyle.STYLE_SMOOTH)));
            configs.add(config(CFG_VOICE_ICON_ANIMATE,
                    context.getString(R.string.mod_config_voice_icon_animate),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isVoiceIconAnimated())));
            configs.add(config(CFG_VOICE_ICON_NAMETAG,
                    context.getString(R.string.mod_config_voice_icon_nametag),
                    UnifiedMod.ConfigType.TOGGLE,
                    "true", "", "",
                    String.valueOf(manager.isVoiceNametagIconEnabled())));
        }
        if (org.chimeramc.client.core.mods.fps.FpsModuleCatalog.isFpsModule(modId)) {
            // The FPS options come straight from the catalogue, so the dialog, the defaults and the
            // stored values read one source. A missing stored value reads the catalogue default.
            org.chimeramc.client.core.mods.fps.FpsModuleCatalog.Module module =
                    org.chimeramc.client.core.mods.fps.FpsModuleCatalog.find(modId);
            for (org.chimeramc.client.core.mods.fps.FpsModuleCatalog.Option option : module.options) {
                if (option.isToggle()) {
                    boolean current = manager.getModConfigBool(option.key,
                            Boolean.parseBoolean(option.defaultValue));
                    configs.add(config(option.key, option.label, UnifiedMod.ConfigType.TOGGLE,
                            option.defaultValue, "", "", String.valueOf(current)));
                } else {
                    int def = Integer.parseInt(option.defaultValue);
                    int current = manager.getModConfigInt(option.key, def);
                    configs.add(config(option.key, option.label, UnifiedMod.ConfigType.SLIDER_INT,
                            option.defaultValue, String.valueOf(option.min),
                            String.valueOf(option.max), String.valueOf(current)));
                }
            }
        }
        return configs;
    }

    private static UnifiedMod.ConfigEntry config(String key, String displayName,
                                                 UnifiedMod.ConfigType type,
                                                 String defaultValue, String minValue,
                                                 String maxValue, String currentValue) {
        return config(key, displayName, type, defaultValue, minValue, maxValue, currentValue, "");
    }

    private static UnifiedMod.ConfigEntry config(String key, String displayName,
                                                 UnifiedMod.ConfigType type,
                                                 String defaultValue, String minValue,
                                                 String maxValue, String currentValue,
                                                 String dependsOn) {
        return new UnifiedMod.ConfigEntry(
                key, displayName, type, defaultValue, minValue, maxValue,
                currentValue, dependsOn
        );
    }

    private static String hotbarSlotConfigKey(int slot, String setting) {
        return CFG_HOTBAR_SLOT_PREFIX + slot + "_" + setting;
    }

    private static void setEnabled(InbuiltModManager manager, UnifiedMod mod, boolean enabled) {
        manager.setInbuiltModEnabled(mod.getId(), enabled);
        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        if (overlayManager != null) {
            overlayManager.handleModToggle(mod.getId(), enabled);
        }
    }

    private static void setConfig(InbuiltModManager manager, UnifiedMod mod, UnifiedMod.ConfigEntry config,
                                  String value) {
        if (ModIds.HOTBAR_SLOT.equals(mod.getId()) && setHotbarSlotConfig(manager, config.key, value)) return;
        if (org.chimeramc.client.core.mods.fps.FpsModuleCatalog.isFpsModule(mod.getId())) {
            setFpsConfig(manager, mod, config.key, value);
            return;
        }
        switch (config.key) {
            case CFG_CATALOG_CHAT_MACRO:
                manager.setModConfigString(CFG_CATALOG_CHAT_MACRO, value == null ? "" : value);
                break;
            case CFG_CATALOG_COMMAND_BIND:
                manager.setModConfigString(CFG_CATALOG_COMMAND_BIND, value == null ? "" : value);
                break;
            case CFG_OVERLAY_SIZE:
                manager.setOverlayButtonSize(mod.getId(), parseInt(value, manager.getOverlayButtonSize(mod.getId())));
                break;
            case CFG_OVERLAY_OPACITY:
                manager.setOverlayOpacity(mod.getId(), parseInt(value, manager.getOverlayOpacity(mod.getId())));
                break;
            case CFG_OVERLAY_LOCK:
                manager.setOverlayLocked(mod.getId(), parseBoolean(value));
                break;
            case CFG_OVERLAY_SHOW_EVERYWHERE:
                manager.setOverlayShowEverywhere(mod.getId(), parseBoolean(value));
                break;
            case CFG_AUTO_SPRINT_KEYBIND:
                manager.setAutoSprintKeybind(parseInt(value, manager.getAutoSprintKeybind()));
                break;
            case CFG_CURSOR_SENSITIVITY:
                manager.setCursorSensitivity(parseInt(value, manager.getCursorSensitivity()));
                break;
            case CFG_ZOOM_LEVEL:
                manager.setZoomLevel(parseInt(value, manager.getZoomLevel()));
                break;
            case CFG_ZOOM_TRANSITION:
                manager.setZoomTransitionDuration(parseInt(value, manager.getZoomTransitionDuration()));
                break;
            case CFG_ZOOM_KEYBIND:
                manager.setZoomKeybind(parseInt(value, manager.getZoomKeybind()));
                break;
            case CFG_GYRO_SENSITIVITY_X:
                manager.setGyroSensitivityX(parseInt(value, manager.getGyroSensitivityX()));
                break;
            case CFG_GYRO_SENSITIVITY_Y:
                manager.setGyroSensitivityY(parseInt(value, manager.getGyroSensitivityY()));
                break;
            case CFG_GYRO_INVERT_X:
                manager.setGyroInvertX(parseBoolean(value));
                break;
            case CFG_GYRO_INVERT_Y:
                manager.setGyroInvertY(parseBoolean(value));
                break;
            case CFG_GYRO_DEADZONE:
                manager.setGyroDeadzone(parseInt(value, manager.getGyroDeadzone()));
                break;
            case CFG_HOTBAR_ITEM_ICONS:
                manager.setHotbarItemIconsEnabled(parseBoolean(value));
                break;
            case CFG_OFFHAND_MODE:
                manager.setOffhandMode(parseInt(value, manager.getOffhandMode()));
                break;
            case CFG_OFFHAND_INVENTORY_KEY:
                manager.setOffhandInventoryKey(parseInt(value, manager.getOffhandInventoryKey()));
                break;
            case CFG_OFFHAND_SWAP_KEY:
                // Legacy key kept so a profile saved before the rename still applies.
                manager.setOffhandInventoryKey(parseInt(value, manager.getOffhandInventoryKey()));
                break;
            case CFG_OFFHAND_USE_KEY:
                manager.setOffhandUseKey(parseInt(value, manager.getOffhandUseKey()));
                break;
            case CFG_OFFHAND_KEYBIND:
                manager.setOffhandKeybind(parseInt(value, manager.getOffhandKeybind()));
                break;
            case CFG_AIM_SMOOTHING:
                manager.setAimSmoothing(parseInt(value, manager.getAimSmoothing()));
                break;
            case CFG_AIM_SENSITIVITY:
                manager.setAimSensitivity(parseInt(value, manager.getAimSensitivity()));
                break;
            case CFG_AIM_CROSSHAIR:
                manager.setAimCrosshairEnabled(parseBoolean(value));
                break;
            case CFG_AIM_CROSSHAIR_STYLE:
                manager.setAimCrosshairStyle(parseInt(value, manager.getAimCrosshairStyle()));
                break;
            case CFG_AIM_CROSSHAIR_COLOR: {
                try {
                    manager.setAimCrosshairColor(Color.parseColor(value));
                } catch (Exception ignored) {
                }
                break;
            }
            case CFG_AIM_FLASH:
                manager.setAimFlashEnabled(parseBoolean(value));
                break;
            case CFG_ARMOR_HUD_SHOW_TARGET:
                manager.setArmorHudShowTarget(parseBoolean(value));
                break;
            case CFG_ARMOR_HUD_SHOW_ENCHANTS:
                manager.setArmorHudShowEnchants(parseBoolean(value));
                break;
            case CFG_ARMOR_HUD_STACKED:
                manager.setArmorHudStacked(parseBoolean(value));
                break;
            case CFG_ARMOR_HUD_REFRESH_MS:
                manager.setArmorHudRefreshMs(parseInt(value, manager.getArmorHudRefreshMs()));
                break;
            case CFG_CRYSTAL_MANUAL_ASSIST:
                manager.setCrystalManualAssist(parseBoolean(value));
                break;
            case CFG_CRYSTAL_MIN_SELF_HP:
                manager.setCrystalMinSelfHp(parseInt(value, manager.getCrystalMinSelfHp()));
                break;
            case CFG_CRYSTAL_MAX_RANGE:
                manager.setCrystalMaxRange(parseInt(value, manager.getCrystalMaxRange()));
                break;
            case CFG_CRYSTAL_PLACEMENT_DELAY_MS:
                manager.setCrystalPlacementDelayMs(parseInt(value, manager.getCrystalPlacementDelayMs()));
                break;
            case CFG_CRYSTAL_KEYBIND:
                manager.setCrystalKeybind(parseInt(value, manager.getCrystalKeybind()));
                break;
            case CFG_HITREG_SENSITIVITY:
                manager.setHitRegSensitivity(parseInt(value, manager.getHitRegSensitivity()));
                break;
            case CFG_HITREG_SMOOTHING:
                manager.setHitRegSmoothing(parseInt(value, manager.getHitRegSmoothing()));
                break;
            case CFG_HITREG_PREDICTION:
                manager.setHitRegPrediction(parseInt(value, manager.getHitRegPrediction()));
                break;
            case CFG_HITREG_HAPTIC:
                manager.setHitRegHapticEnabled(parseBoolean(value));
                break;
            case CFG_HIT_TIMING_COOLDOWN_MS:
                manager.setHitTimingCooldownMs(parseInt(value, manager.getHitTimingCooldownMs()));
                break;
            case CFG_HIT_TIMING_SHOW_COMBO:
                manager.setHitTimingShowCombo(parseBoolean(value));
                break;
            case CFG_HIT_TIMING_SHOW_BAR:
                manager.setHitTimingShowTimingBar(parseBoolean(value));
                break;
            case CFG_HITBOX_SHOW_PLAYERS:
                manager.setHitboxShowPlayers(parseBoolean(value));
                break;
            case CFG_HITBOX_SHOW_MOBS:
                manager.setHitboxShowMobs(parseBoolean(value));
                break;
            case CFG_HITBOX_SHOW_ITEMS:
                manager.setHitboxShowItems(parseBoolean(value));
                break;
            case CFG_HITBOX_SHOW_PROJECTILES:
                manager.setHitboxShowProjectiles(parseBoolean(value));
                org.chimeramc.client.core.mods.inbuilt.overlay.HitboxMod.onConfigChanged(manager);
                break;
            case CFG_HITBOX_SHOW_THROWN_ITEMS:
                manager.setHitboxShowThrownItems(parseBoolean(value));
                org.chimeramc.client.core.mods.inbuilt.overlay.HitboxMod.onConfigChanged(manager);
                break;
            case CFG_HITBOX_SHOW_LOOK_LINE:
                manager.setHitboxShowLookLine(parseBoolean(value));
                break;
            case CFG_HITBOX_SHOW_CRIT_LINE:
                manager.setHitboxShowCritLine(parseBoolean(value));
                break;
            case CFG_HITBOX_SHOW_COMBO_BOX:
                manager.setHitboxShowComboBox(parseBoolean(value));
                break;
            case CFG_HITBOX_SHOW_TARGET_BOX:
                manager.setHitboxShowTargetBox(parseBoolean(value));
                break;
            case CFG_REACH_POSITION:
                manager.setReachIndicatorPosition(parseInt(value, manager.getReachIndicatorPosition()));
                break;
            case CFG_TRAJECTORY_COLOR:
                try {
                    manager.setTrajectoryColor(Color.parseColor(value));
                } catch (Exception ignored) {
                }
                break;
            case CFG_HIT_PREDICTION_LOOKAHEAD:
                manager.setHitPredictionLookAheadMs(parseInt(value, manager.getHitPredictionLookAheadMs()));
                break;
            case CFG_KILL_EFFECT_STYLE:
                manager.setKillEffectStyle(parseInt(value, manager.getKillEffectStyle()));
                break;
            case CFG_KILL_EFFECT_COLOR:
                try {
                    manager.setKillEffectColor(Color.parseColor(value));
                } catch (Exception ignored) {
                }
                break;
            case CFG_VOICE_MIC:
                manager.setVoiceMicEnabled(parseBoolean(value));
                break;
            case CFG_VOICE_ICON_STYLE:
                manager.setVoiceIconStyle(parseBoolean(value)
                        ? org.chimeramc.client.core.mods.inbuilt.overlay.MicIconStyle.STYLE_SMOOTH
                        : org.chimeramc.client.core.mods.inbuilt.overlay.MicIconStyle.STYLE_CLASSIC);
                break;
            case CFG_VOICE_ICON_ANIMATE:
                manager.setVoiceIconAnimated(parseBoolean(value));
                break;
            case CFG_VOICE_ICON_NAMETAG:
                manager.setVoiceNametagIconEnabled(parseBoolean(value));
                break;
            // These keys are no longer surfaced in the module dialog (they live on the Voice tab),
            // but a config value restored from an older backup can still carry them. They are
            // handled so an import cannot silently drop a stored preference.
            case CFG_VOICE_RANGE:
                manager.setVoiceRangeBlocks(parseInt(value, (int) manager.getVoiceRangeBlocks()));
                break;
            case CFG_VOICE_VOLUME:
                manager.setVoiceVolumePercent(parseInt(value, manager.getVoiceVolumePercent()));
                break;
            case CFG_VOICE_CHANNEL: {
                String typed = value == null ? "" : value.trim();
                String normalized = org.chimeramc.client.core.voice.VoiceChannel.normalize(typed);
                boolean privateChannel =
                        org.chimeramc.client.core.voice.VoiceChannel.isJoinCode(normalized);
                manager.joinVoiceChannel(normalized, typed, privateChannel);
                break;
            }
            default:
                break;
        }
        if (ModIds.AIM_SETTINGS.equals(mod.getId())) {
            org.chimeramc.client.core.mods.inbuilt.overlay.AimSettingsMod.onConfigChanged(manager);
        } else if (ModIds.ARMOR_HUD.equals(mod.getId())) {
            org.chimeramc.client.core.mods.inbuilt.overlay.ArmorHudMod.onConfigChanged(manager);
        } else if (ModIds.CRYSTAL_OPTIMIZER.equals(mod.getId())) {
            org.chimeramc.client.core.mods.inbuilt.overlay.CrystalOptimizerMod.onConfigChanged(manager);
        } else if (ModIds.HIT_REGISTRATION.equals(mod.getId())) {
            org.chimeramc.client.core.mods.inbuilt.overlay.HitRegistrationMod.onConfigChanged(manager);
        } else if (ModIds.HIT_TIMING.equals(mod.getId())) {
            org.chimeramc.client.core.mods.inbuilt.overlay.HitTimingMod.onConfigChanged(manager);
        } else if (ModIds.HITBOX.equals(mod.getId())) {
            org.chimeramc.client.core.mods.inbuilt.overlay.HitboxMod.onConfigChanged(manager);
        } else if (ModIds.REACH_INDICATOR.equals(mod.getId())) {
            org.chimeramc.client.core.mods.inbuilt.overlay.ReachIndicatorMod.onConfigChanged(manager);
        } else if (ModIds.TRAJECTORY_PREDICTION.equals(mod.getId())) {
            org.chimeramc.client.core.mods.inbuilt.overlay.TrajectoryPredictionMod.onConfigChanged(manager);
        } else if (ModIds.HIT_PREDICTION.equals(mod.getId())) {
            org.chimeramc.client.core.mods.inbuilt.overlay.HitPredictionMod.onConfigChanged(manager);
        } else if (ModIds.KILL_EFFECTS.equals(mod.getId())) {
            org.chimeramc.client.core.mods.inbuilt.overlay.KillEffectsMod.onConfigChanged(manager);
        } else if (ModIds.VOICE_CHAT.equals(mod.getId())) {
            org.chimeramc.client.core.voice.VoiceChatModule module =
                    org.chimeramc.client.core.voice.VoiceChatModule.peek();
            if (module != null) module.applyConfig(manager);
        }
    }

    /**
     * Stores an FPS module option. The type is taken from the catalogue, so a toggle key can never be
     * written as an int (or the reverse) — the exact class-cast-on-read bug the settings backup
     * restore guards against.
     */
    private static void setFpsConfig(InbuiltModManager manager, UnifiedMod mod, String key,
                                     String value) {
        org.chimeramc.client.core.mods.fps.FpsModuleCatalog.Option option = null;
        org.chimeramc.client.core.mods.fps.FpsModuleCatalog.Module module =
                org.chimeramc.client.core.mods.fps.FpsModuleCatalog.find(mod.getId());
        if (module != null) {
            for (org.chimeramc.client.core.mods.fps.FpsModuleCatalog.Option candidate : module.options) {
                if (candidate.key.equals(key)) {
                    option = candidate;
                    break;
                }
            }
        }
        if (option == null) return;
        if (option.isToggle()) {
            manager.setModConfigBool(key, parseBoolean(value));
        } else {
            manager.setModConfigInt(key, parseInt(value, Integer.parseInt(option.defaultValue)));
        }
    }

    private static boolean setHotbarSlotConfig(InbuiltModManager manager, String key, String value) {
        for (int slot = 1; slot <= 9; slot++) {
            String overlayKey = ModIds.HOTBAR_SLOT + ":" + slot;
            if (hotbarSlotConfigKey(slot, CFG_HOTBAR_SLOT_ENABLED).equals(key)) {
                manager.setHotbarSlotEnabled(slot, parseBoolean(value));
                return true;
            }
            if (hotbarSlotConfigKey(slot, CFG_HOTBAR_SLOT_SIZE).equals(key)) {
                manager.setOverlayButtonSize(overlayKey, parseInt(value, manager.getOverlayButtonSize(overlayKey)));
                return true;
            }
            if (hotbarSlotConfigKey(slot, CFG_HOTBAR_SLOT_OPACITY).equals(key)) {
                manager.setOverlayOpacity(overlayKey, parseInt(value, manager.getOverlayOpacity(overlayKey)));
                return true;
            }
        }
        return false;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static String colorToHex(int color) {
        return String.format(java.util.Locale.US, "#%08X", color);
    }
}
