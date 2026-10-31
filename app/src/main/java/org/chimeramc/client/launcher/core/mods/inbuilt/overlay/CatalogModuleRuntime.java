package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.chimeramc.client.core.mods.inbuilt.catalog.ActionTimers;
import org.chimeramc.client.core.mods.inbuilt.catalog.CatalogSessionCounters;
import org.chimeramc.client.core.mods.inbuilt.catalog.CatalogTimerRules;
import org.chimeramc.client.core.mods.inbuilt.catalog.ChatInjector;
import org.chimeramc.client.core.mods.inbuilt.catalog.KeyStateTracker;
import org.chimeramc.client.core.mods.inbuilt.catalog.LocalPlayerReadouts;
import org.chimeramc.client.core.mods.inbuilt.catalog.PeerTargets;
import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.preloader.PreloaderInput;
import org.chimeramc.client.util.MainThread;

import java.util.HashMap;
import java.util.Map;

/**
 * The live side of the reachable catalogue modules.
 *
 * <p>Each reachable module is one of three shapes, and this class owns all three:
 *
 * <ul>
 *   <li><b>A readout</b> — a draggable {@link CatalogReadoutOverlay} whose text is built from the
 *       launcher's own state, the local-player feed or the session counters. {@link #showReadout}
 *       installs it; {@link #tick} refreshes the counters and the drag positions.</li>
 *   <li><b>An action</b> — a one-shot performed when the module is switched on: a chat/command
 *       injection through the existing native key path, or a screenshot.</li>
 *   <li><b>An options write</b> — a key written into the instance's {@code options.txt} through the
 *       existing {@link org.chimeramc.client.core.content.OptionsEditor}. The game applies it on
 *       its next load, and the module says so.</li>
 * </ul>
 *
 * <p>The honest-empty-state rule is the same one the rest of the client uses: a readout whose feed
 * has no reading shows the shared "waiting for game data" string rather than a fabricated zero, and
 * a failed action reports rather than silently doing nothing.
 *
 * <p>Nothing here is on a per-frame hot path: the readouts refresh on their own 500&nbsp;ms timer
 * and {@link #tick} only advances the counters from the same signals Select Hit already polls.
 */
public final class CatalogModuleRuntime {

    private final Activity activity;
    private final Map<String, CatalogReadoutOverlay> readouts = new HashMap<>();
    private final CatalogSessionCounters counters = new CatalogSessionCounters();
    private final KeyStateTracker keyState = new KeyStateTracker();
    private final Handler handler = MainThread.handler();

    private String lastCreditedKill;
    private boolean lastHealthAvailable;
    private float lastHealth = 20f;
    private float[] lastPosition;
    private long lastPositionMs;
    private double lastSpeed;
    private final float[] lastVelocity = new float[3];
    private boolean hasVelocity;
    private float fallStartY = Float.NaN;
    private float[] waypoint;
    private final Map<String, ActionTimers> timers = new HashMap<>();
    private final java.util.Set<String> sprintHelpers = new java.util.LinkedHashSet<>();

    public CatalogModuleRuntime(Activity activity) {
        this.activity = activity;
    }

    /** The shared per-session counters, so a readout and the Replay triggers agree. */
    public CatalogSessionCounters counters() {
        return counters;
    }

    // --- readouts ---------------------------------------------------------------------------

    public boolean showReadout(String modId, int x, int y) {
        if (readouts.containsKey(modId)) return true;
        CatalogReadoutOverlay overlay = new CatalogReadoutOverlay(activity, modId, () -> textFor(modId));
        readouts.put(modId, overlay);
        overlay.show(x, y);
        return true;
    }

    public void hideReadout(String modId) {
        CatalogReadoutOverlay overlay = readouts.remove(modId);
        if (overlay != null) overlay.hide();
    }

    public boolean isReadoutShowing(String modId) {
        return readouts.containsKey(modId);
    }

    /**
     * Shows or hides every readout for the current world/menu state.
     *
     * <p>A readout is a HUD element, so it follows the same rule the shipped overlays do: visible
     * in the world, hidden under the pause menu and the loading screen. The "show everywhere"
     * override is applied by the caller, which is why the flag is passed in rather than read here.
     */
    public void setReadoutsVisible(boolean visible) {
        int state = visible ? android.view.View.VISIBLE : android.view.View.GONE;
        for (CatalogReadoutOverlay overlay : readouts.values()) {
            overlay.setVisibility(state);
        }
    }

    /** Clears per-session state; call when a session starts. */
    public void reset() {
        counters.reset();
        keyState.reset();
        lastCreditedKill = null;
        lastPosition = null;
        lastSpeed = 0;
        hasVelocity = false;
        fallStartY = Float.NaN;
        waypoint = null;
    }

    /**
     * Advances the counters from the client's own signals, once per game frame.
     *
     * <p>The landed-hit signal is the same one Select Hit uses (a fresh advance of the solver's
     * combo), and the credited kill is the existing {@link KillEffectsMod} credit heuristic, so
     * these readouts cannot disagree with the modules they mirror.
     */
    public void tick() {
        // A landed attack: HitTimingMod advances its combo only when a click landed after the
        // post-hit window, which is the client's best evidence of a hit.
        int combo = HitTimingMod.getCombo();
        if (combo > counters.combo()) {
            counters.onLandedHit();
            // The client's own landed hit arms the hit marker and the swing timers, so the marker
            // and the counter cannot disagree about when a hit landed.
            long now = SystemClock.uptimeMillis();
            timer("combat_hit_marker").arm(now, CatalogTimerRules.HIT_MARKER_MS);
        }
        // A death: the local health feed dropping to zero from a live reading.
        float[] health = PreloaderInput.readLocalPlayerHealth();
        boolean available = health != null && health.length >= 1;
        if (available) {
            float value = health[0];
            if (lastHealthAvailable && lastHealth > 0f && value <= 0f) {
                counters.onDeath();
            }
            lastHealth = value;
        }
        lastHealthAvailable = available;
        // A credited kill: the same heuristic the kill-effect overlay uses.
        String credited = KillEffectsMod.pollCreditedKill();
        if (credited != null && !credited.equals(lastCreditedKill)) {
            lastCreditedKill = credited;
            counters.onCreditedKill();
        }
        // The speed/position samples the speed readouts need.
        float[] position = PreloaderInput.readLocalPlayerPosition();
        if (position != null && position.length >= 3) {
            long now = SystemClock.uptimeMillis();
            if (lastPosition != null && now > lastPositionMs) {
                double dx = position[0] - lastPosition[0];
                double dy = position[1] - lastPosition[1];
                double dz = position[2] - lastPosition[2];
                double dt = (now - lastPositionMs) / 1000.0;
                lastSpeed = Math.hypot(dx, dz) / dt;
                // Per-axis velocity is measured from the same two samples the speed uses, so the
                // velocity readout cannot disagree with the speed readout. It is not fabricated:
                // the vertical component is the real Δy/Δt, which the old code dropped entirely.
                lastVelocity[0] = (float) (dx / dt);
                lastVelocity[1] = (float) (dy / dt);
                lastVelocity[2] = (float) (dz / dt);
                hasVelocity = true;
            }
            lastPosition = position;
            lastPositionMs = now;
            // The fall tracker: the highest point seen while descending is where the fall began.
            if (Float.isNaN(fallStartY) || position[1] >= fallStartY) {
                fallStartY = position[1];
            }
        }
    }

    /** Feeds a key state to the keystrokes readout. */
    public void onKey(int keyCode, boolean down) {
        keyState.onKey(keyCode, down);
    }

    /**
     * Arms the input-driven cooldown timers when the player uses the matching action.
     *
     * <p>The game owns the authoritative cooldowns, but the event that starts each one is the
     * player's own input, which the launcher already sees. The durations are the game's own where
     * they are known (a swing is 0.625&nbsp;s, an ender pearl 1&nbsp;s) and a documented nominal
     * window otherwise; every readout is worded "since last use" so it is never mistaken for the
     * game's value.
     */
    public void onAttackAction(long nowMs) {
        for (CatalogTimerRules.Arm arm : CatalogTimerRules.forAttack()) {
            timer(arm.modId).arm(nowMs, arm.durationMs);
        }
        applySprintHelpers(nowMs);
    }

    /**
     * Arms the item-use timers (a thrown pearl, an eaten gapple, a raised shield).
     *
     * <p>The shield timer is armed here too: in Bedrock the off-hand use key is what raises a
     * shield, so it is the same input that starts the shield window. Leaving it unarmed was the
     * "enabled but not working" defect — the readout existed and was wired, but no input ever
     * started its timer, so it showed the empty state forever.
     */
    public void onUseAction(long nowMs) {
        for (CatalogTimerRules.Arm arm : CatalogTimerRules.forUse()) {
            timer(arm.modId).arm(nowMs, arm.durationMs);
        }
    }

    /** Enables/disables a sprint helper; a helper re-sends the sprint key after an action. */
    public void setSprintHelper(String modId, boolean enabled) {
        if (enabled) sprintHelpers.add(modId);
        else sprintHelpers.remove(modId);
    }

    /**
     * Re-sends the sprint key when a sprint helper is on.
     *
     * <p>This sends the same sprint input a player would send; it does not change the game's
     * physics, which the server arbitrates. It is called after the client's own attack so sprint
     * is not dropped by the swing.
     */
    private void applySprintHelpers(long nowMs) {
        if (sprintHelpers.isEmpty()) return;
        int sprintKey = InbuiltModManager.getInstance(activity).getAutoSprintKeybind();
        injectKey(sprintKey);
    }

    private ActionTimers timer(String modId) {
        ActionTimers value = timers.get(modId);
        if (value == null) {
            value = new ActionTimers();
            timers.put(modId, value);
        }
        return value;
    }

    private String textFor(String modId) {
        switch (modId) {
            case "hud_clock":
                return android.text.format.DateFormat.format("HH:mm:ss",
                        System.currentTimeMillis()).toString();
            case "hud_memory": {
                Runtime runtime = Runtime.getRuntime();
                long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
                return usedMb + " MB";
            }
            case "hud_keystrokes":
                return keyState.line(
                        new int[]{KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A,
                                KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D,
                                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_SHIFT_LEFT},
                        new String[]{"W", "A", "S", "D", "JMP", "SPR"});
            case "hud_watermark":
                return watermark();
            case "hud_combo_display":
            case "combat_combo_counter":
                return counters.combo() > 0 ? "Combo " + counters.combo() : null;
            case "combat_death_counter":
                return "Deaths " + counters.deaths();
            case "combat_kdr_tracker":
                return "K/D " + counters.kdr();
            case "combat_streak_tracker":
                return "Streak " + counters.streak() + " (best " + counters.bestStreak() + ")";
            case "combat_kill_counter":
                return "Kills " + counters.kills();
            case "hud_coordinates":
            case "move_position_display":
                return LocalPlayerReadouts.coordinates(PreloaderInput.readLocalPlayerPosition());
            case "hud_direction":
                return LocalPlayerReadouts.direction(PreloaderInput.readLocalPlayerRotation());
            case "hud_compass":
                return LocalPlayerReadouts.direction(PreloaderInput.readLocalPlayerRotation());
            case "hud_chunk_borders":
                return LocalPlayerReadouts.chunkOrigin(PreloaderInput.readLocalPlayerPosition());
            case "hud_speedometer":
            case "move_speed_display":
                return LocalPlayerReadouts.speed(lastSpeed);
            case "move_velocity_display":
                return hasVelocity
                        ? LocalPlayerReadouts.velocity(lastVelocity[0], lastVelocity[1], lastVelocity[2])
                        : null;
            case "hud_health_display":
                return LocalPlayerReadouts.health(PreloaderInput.readLocalPlayerHealth());
            case "move_fall_damage_warn":
                return LocalPlayerReadouts.fallWarning(fallDistance());
            case "world_waypoints":
            case "world_death_waypoint":
                return waypointText();
            case "combat_pearl_timer":
                return timerText("combat_pearl_timer");
            case "combat_gapple_timer":
                return timerText("combat_gapple_timer");
            case "combat_crit_timer":
                return timerText("combat_crit_timer");
            case "combat_attack_cooldown":
            case "combat_attack_indicator":
                return timerText("combat_attack_cooldown");
            case "combat_shield_timer":
                // The shield cooldown starts from the player's own shield use, which the launcher
                // sees; the readout is worded "since last use" so it is not a server value.
                return timerText("combat_shield_timer");
            case "combat_reach_circle":
                return reachCircleText();
            case "combat_target_hud":
            case "hud_target_info":
                return targetText();
            case "combat_hit_marker":
                return hitMarkerText();
            case "hud_saturation":
            case "hud_hunger_display":
            case "hud_xp_display":
                // These need a hunger/xp feed this build does not have. They are not in the
                // reachable set, so this branch is unreachable by design; it stays explicit so
                // adding one to the set without a real source is a visible no-op, not a wrong
                // number.
                return null;
            default:
                return null;
        }
    }

    /** The remaining time on an input-driven timer, or null when it is not running. */
    private String timerText(String modId) {
        ActionTimers value = timers.get(modId);
        if (value == null) return null;
        return value.remainingText(SystemClock.uptimeMillis());
    }

    /**
     * The nearest peer's distance against the attack-reach radius.
     *
     * <p>Reads the proximity-voice peer feed, which needs no game hook, so the module works without
     * the native entity list. Vanilla players are invisible to it; with no peer in range the
     * readout is null and the overlay shows the honest empty state rather than "0 blocks".
     */
    private String reachCircleText() {
        float[] position = PreloaderInput.readLocalPlayerPosition();
        if (position == null || position.length < 3) return null;
        java.util.List<ReachIndicator.VoicePeerPosition> peers = PeerPositions.read();
        if (peers.isEmpty()) return null;
        double best = Double.MAX_VALUE;
        for (ReachIndicator.VoicePeerPosition peer : peers) {
            double dx = peer.x - position[0];
            double dy = peer.y - position[1];
            double dz = peer.z - position[2];
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        if (best == Double.MAX_VALUE) return null;
        return activity.getString(org.chimeramc.client.R.string.catalog_reach_circle,
                String.format(java.util.Locale.US, "%.1f", best));
    }

    /**
     * The nearest peer's name and distance, for the target readouts.
     *
     * <p>Reads the proximity-voice peer feed, which needs no game hook. Vanilla players are
     * invisible to it; with no peer in range the readout is null and the overlay shows the honest
     * empty state rather than a fabricated target.
     */
    private String targetText() {
        float[] position = PreloaderInput.readLocalPlayerPosition();
        if (position == null || position.length < 3) return null;
        java.util.List<ReachIndicator.VoicePeerPosition> peers = PeerPositions.read();
        if (peers.isEmpty()) return null;
        java.util.List<PeerTargets.Peer> converted = new java.util.ArrayList<>(peers.size());
        for (ReachIndicator.VoicePeerPosition peer : peers) {
            converted.add(new PeerTargets.Peer(peer.id, peer.name, peer.x, peer.y, peer.z));
        }
        return PeerTargets.describe(PeerTargets.nearest(position, converted));
    }

    /**
     * The hit marker: a short flash on the client's own landed attack.
     *
     * <p>Driven by the same signal the combo counter uses, so the two agree. It is a local
     * indication that the client registered a swing landing in its own window, not a confirmation
     * from the server.
     */
    private String hitMarkerText() {
        ActionTimers value = timers.get("combat_hit_marker");
        if (value == null || !value.isActive(SystemClock.uptimeMillis())) return null;
        return activity.getString(org.chimeramc.client.R.string.catalog_hit_marker);
    }

    /**
     * The distance to the saved waypoint, or an instruction to set one.
     *
     * <p>The waypoint is a position the player marks from where they are standing; the readout then
     * shows how far away it is. Both halves need only the local-player feed, so the module works
     * without the native entity list — and when the feed is unavailable the readout says so rather
     * than showing a distance from nowhere.
     */
    private String waypointText() {
        float[] position = PreloaderInput.readLocalPlayerPosition();
        if (position == null || position.length < 3) return null;
        if (waypoint == null) {
            return activity.getString(org.chimeramc.client.R.string.catalog_waypoint_unset);
        }
        double dx = position[0] - waypoint[0];
        double dy = position[1] - waypoint[1];
        double dz = position[2] - waypoint[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return activity.getString(org.chimeramc.client.R.string.catalog_waypoint_distance,
                String.format(java.util.Locale.US, "%.1f", distance));
    }

    /** Marks the current position as the waypoint. */
    public boolean setWaypointHere() {
        float[] position = PreloaderInput.readLocalPlayerPosition();
        if (position == null || position.length < 3) return false;
        waypoint = new float[]{position[0], position[1], position[2]};
        return true;
    }

    /** The watermark line, which is a static client mark. */
    private String watermark() {
        return "Glowberry Client";
    }

    /**
     * How far the player has descended since the fall began.
     *
     * <p>The feed gives only the current height, so the fall is measured from the highest point
     * seen since the descent started ({@link #fallStartY}). That is an approximation the warning's
     * wording ("Falling N blocks") does not overstate.
     */
    private double fallDistance() {
        if (Float.isNaN(fallStartY) || lastPosition == null || lastPosition.length < 3) return 0;
        return Math.max(0, fallStartY - lastPosition[1]);
    }

    // --- actions ----------------------------------------------------------------------------

    /**
     * Performs a module's one-shot action.
     *
     * <p>Chat and command lines go through {@link ChatInjector} into the launcher's existing native
     * key path — the same path a real key press takes — so a macro types exactly what the player
     * would type. A macro with no saved text is refused with the shared "not configured" message
     * rather than opening an empty chat box.
     */
    public void performAction(String modId) {
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        switch (modId) {
            case "util_drop_all":
                injectKey(KeyEvent.KEYCODE_Q);
                break;
            case "util_auto_gg":
                sendChat("gg");
                break;
            case "util_auto_tip":
                sendChat("/tip");
                break;
            case "util_chat_macro": {
                String macro = manager.getCatalogChatMacro();
                if (macro == null || macro.trim().isEmpty()) {
                    toast(org.chimeramc.client.R.string.catalog_macro_not_configured);
                    return;
                }
                sendChat(macro);
                break;
            }
            case "util_command_bind": {
                String command = manager.getCatalogCommandBind();
                if (command == null || command.trim().isEmpty()) {
                    toast(org.chimeramc.client.R.string.catalog_macro_not_configured);
                    return;
                }
                for (ChatInjector.Step step : ChatInjector.command(command)) {
                    injectKey(step.keyCode, step.codePoint);
                }
                break;
            }
            case "util_screenshot":
                captureScreenshot();
                break;
            default:
                break;
        }
    }

    /**
     * Saves a PNG of the launcher's own window into the instance's {@code Screenshots} folder.
     *
     * <p>PixelCopy of our own window needs no capture consent, unlike the media-projection flow the
     * Replay feature uses for the game surface. The result is reported either way, so a device whose
     * compositor excludes the game's surface still tells the player what was saved rather than
     * silently doing nothing.
     */
    private void captureScreenshot() {
        java.io.File dir = screenshotsDir();
        if (dir == null) {
            toast(org.chimeramc.client.R.string.catalog_options_no_instance);
            return;
        }
        org.chimeramc.client.launcher.core.content.ScreenshotCapture.capture(activity, dir,
                new org.chimeramc.client.launcher.core.content.ScreenshotCapture.Callback() {
                    @Override
                    public void onSaved(java.io.File file) {
                        toast(org.chimeramc.client.R.string.catalog_screenshot_saved);
                    }

                    @Override
                    public void onFailed() {
                        toast(org.chimeramc.client.R.string.catalog_screenshot_failed);
                    }
                });
    }

    /** The selected instance's Screenshots folder, resolved as the content screen resolves it. */
    private java.io.File screenshotsDir() {
        org.chimeramc.client.core.versions.VersionManager manager =
                org.chimeramc.client.core.versions.VersionManager.getIfInitialized();
        if (manager == null) return null;
        org.chimeramc.client.core.versions.GameVersion version = manager.getSelectedVersion();
        if (version == null) return null;
        android.content.SharedPreferences prefs = activity.getSharedPreferences(
                org.chimeramc.client.util.LauncherStorage.CONTENT_MANAGEMENT_PREFS_NAME,
                android.content.Context.MODE_PRIVATE);
        org.chimeramc.client.settings.FeatureSettings.StorageType stored =
                org.chimeramc.client.settings.FeatureSettings.StorageType.INTERNAL;
        try {
            stored = org.chimeramc.client.settings.FeatureSettings.StorageType.valueOf(prefs.getString(
                    org.chimeramc.client.util.LauncherStorage.CONTENT_STORAGE_TYPE_KEY,
                    org.chimeramc.client.settings.FeatureSettings.StorageType.INTERNAL.name()));
        } catch (Throwable ignored) {
            // A malformed preference reads as the default rather than aborting the capture.
        }
        org.chimeramc.client.settings.FeatureSettings.StorageType resolved =
                org.chimeramc.client.util.LauncherStorage.normalizeContentStorageType(
                        stored, version.versionIsolation);
        java.io.File gameDataDir = org.chimeramc.client.util.LauncherStorage.getContentGameDataDir(
                activity, version.getStorageProfileId(), resolved);
        if (gameDataDir == null) return null;
        return new java.io.File(gameDataDir, "Screenshots");
    }

    private void sendChat(String line) {
        for (ChatInjector.Step step : ChatInjector.chat(line)) {
            injectKey(step.keyCode, step.codePoint);
        }
    }

    private void injectKey(int keyCode) {
        injectKey(keyCode, 0);
    }

    private void injectKey(int keyCode, int codePoint) {
        if (keyCode == 0) return;
        PreloaderInput.onKeyEvent(keyCode, codePoint, true);
        PreloaderInput.onKeyEvent(keyCode, codePoint, false);
    }

    private void toast(int stringRes) {
        handler.post(() -> android.widget.Toast
                .makeText(activity, stringRes, android.widget.Toast.LENGTH_SHORT).show());
    }
}
