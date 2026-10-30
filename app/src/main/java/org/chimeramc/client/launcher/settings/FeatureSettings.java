package org.chimeramc.client.settings;

import android.content.Context;

public class FeatureSettings {
    private static volatile FeatureSettings INSTANCE;
    private static Context appContext;
    private boolean launcherManagedMcLoginEnabled = false;
    private boolean logcatOverlayEnabled = false;
    private boolean lowInputDelayEnabled = false;
    private Boolean crashUploadEnabled = true;
    private boolean foregroundServiceEnabled = false;
    private boolean reduceNetworkLatencyEnabled = false;
    private boolean fpsOptimizerEnabled = false;

    // --- Game Performance Mode -------------------------------------------------------------
    // The master switch plus one toggle per optimization, so a user can isolate an item that
    // misbehaves on their device. Tier-1 (host) items default on; Tier-2 (game hook) items
    // default off, because a hook that resolves wrong can change how the game renders.
    private boolean optifineModeEnabled = false;
    private boolean optifineAllocatorEnabled = true;
    private boolean optifineRenderPriorityEnabled = true;
    private boolean optifineCpuAffinityEnabled = true;
    private boolean optifineRefreshRateEnabled = true;
    private boolean optifineEntityCullingEnabled = false;
    private boolean optifineParticleCullingEnabled = false;
    private boolean optifineRenderDistanceEnabled = false;
    private boolean optifineCallbackTrimmingEnabled = false;
    private boolean optifineOreUiStrippingEnabled = false;
    /**
     * Consecutive launches that ended before a session started, keyed by optifine item id.
     * Managed by {@code OptifineModeManager}; an item is auto-disabled at three.
     */
    private java.util.Map<String, Integer> optifineCrashCounts = new java.util.HashMap<>();

    public enum StorageType {
        INTERNAL,
        EXTERNAL,
        VERSION_ISOLATION,
        VERSION_ISOLATION_INTERNAL,
        VERSION_ISOLATION_EXTERNAL
    }

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static FeatureSettings getInstance() {
        if (INSTANCE == null) {
            synchronized (FeatureSettings.class) {
                if (INSTANCE == null) {
                    INSTANCE = SettingsStorage.load(appContext);
                    if (INSTANCE == null) {
                        INSTANCE = new FeatureSettings();
                    }
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Re-reads the persisted settings into the singleton.
     *
     * Restoring a backup writes the store directly, but the singleton survives for the life of
     * the process, so without this the current session would keep serving the pre-import values
     * until the next launch. Callers must hold no references to the old instance.
     */
    public static void reload(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        synchronized (FeatureSettings.class) {
            INSTANCE = SettingsStorage.load(appContext);
            if (INSTANCE == null) {
                INSTANCE = new FeatureSettings();
            }
        }
    }

    public boolean isLauncherManagedMcLoginEnabled() { return launcherManagedMcLoginEnabled; }
    public void setLauncherManagedMcLoginEnabled(boolean enabled) { this.launcherManagedMcLoginEnabled = enabled; autoSave(); }

    public boolean isLogcatOverlayEnabled() { return logcatOverlayEnabled; }
    public void setLogcatOverlayEnabled(boolean enabled) { this.logcatOverlayEnabled = enabled; autoSave(); }

    public boolean isLowInputDelayEnabled() { return lowInputDelayEnabled; }
    public void setLowInputDelayEnabled(boolean enabled) { this.lowInputDelayEnabled = enabled; autoSave(); }

    public boolean isCrashUploadEnabled() { return crashUploadEnabled == null || crashUploadEnabled; }
    public void setCrashUploadEnabled(boolean enabled) { this.crashUploadEnabled = enabled; autoSave(); }

    public boolean isForegroundServiceEnabled() { return foregroundServiceEnabled; }
    public void setForegroundServiceEnabled(boolean enabled) { this.foregroundServiceEnabled = enabled; autoSave(); }


    /**
     * Whether the FPS optimization module may quiet the host during a session.
     *
     * <p>Off by default, like anti stick drift: enabling it changes how the launcher behaves on
     * every launch, so an existing setup cannot be altered silently. It only ever reduces
     * launcher-side work during a session -- see {@code FpsOptimizer} for what that does and,
     * more importantly, what it deliberately does not do.
     */
    public boolean isFpsOptimizerEnabled() { return fpsOptimizerEnabled; }
    public void setFpsOptimizerEnabled(boolean enabled) { this.fpsOptimizerEnabled = enabled; autoSave(); }

    // --- Game Performance Mode accessors ---------------------------------------------------

    public boolean isOptifineModeEnabled() { return optifineModeEnabled; }
    public void setOptifineModeEnabled(boolean enabled) {
        this.optifineModeEnabled = enabled;
        autoSave();
    }

    /**
     * Whether one optifine item's own toggle is on, by stable item id.
     *
     * <p>An unknown id reads as off, so a settings file written by a newer build cannot turn on
     * an item this build does not implement.
     */
    public boolean isOptifineItemEnabled(String itemId) {
        switch (itemId == null ? "" : itemId) {
            case "allocator": return optifineAllocatorEnabled;
            case "render_priority": return optifineRenderPriorityEnabled;
            case "cpu_affinity": return optifineCpuAffinityEnabled;
            case "refresh_rate": return optifineRefreshRateEnabled;
            case "entity_culling": return optifineEntityCullingEnabled;
            case "particle_culling": return optifineParticleCullingEnabled;
            case "dynamic_render_distance": return optifineRenderDistanceEnabled;
            case "callback_trimming": return optifineCallbackTrimmingEnabled;
            case "oreui_stripping": return optifineOreUiStrippingEnabled;
            default: return false;
        }
    }

    public void setOptifineItemEnabled(String itemId, boolean enabled) {
        switch (itemId == null ? "" : itemId) {
            case "allocator": optifineAllocatorEnabled = enabled; break;
            case "render_priority": optifineRenderPriorityEnabled = enabled; break;
            case "cpu_affinity": optifineCpuAffinityEnabled = enabled; break;
            case "refresh_rate": optifineRefreshRateEnabled = enabled; break;
            case "entity_culling": optifineEntityCullingEnabled = enabled; break;
            case "particle_culling": optifineParticleCullingEnabled = enabled; break;
            case "dynamic_render_distance": optifineRenderDistanceEnabled = enabled; break;
            case "callback_trimming": optifineCallbackTrimmingEnabled = enabled; break;
            case "oreui_stripping": optifineOreUiStrippingEnabled = enabled; break;
            default: return;
        }
        autoSave();
    }

    /** Consecutive-crash count for an optifine item, 0 when it has not crashed. */
    public int getOptifineCrashCount(String itemId) {
        if (itemId == null || optifineCrashCounts == null) return 0;
        Integer value = optifineCrashCounts.get(itemId);
        return value == null ? 0 : value;
    }

    public void setOptifineCrashCount(String itemId, int count) {
        if (itemId == null) return;
        if (optifineCrashCounts == null) optifineCrashCounts = new java.util.HashMap<>();
        if (count <= 0) {
            optifineCrashCounts.remove(itemId);
        } else {
            optifineCrashCounts.put(itemId, count);
        }
        autoSave();
    }

    /** Clears every optifine crash counter, e.g. after the user re-enables an item by hand. */
    public void clearOptifineCrashCounts() {
        if (optifineCrashCounts == null || optifineCrashCounts.isEmpty()) return;
        optifineCrashCounts.clear();
        autoSave();
    }

    public boolean isReduceNetworkLatencyEnabled() { return reduceNetworkLatencyEnabled; }
    public void setReduceNetworkLatencyEnabled(boolean enabled) {
        if (this.reduceNetworkLatencyEnabled == enabled) return;
        this.reduceNetworkLatencyEnabled = enabled;
        autoSave();
        if (enabled) {
            LowLatencyNetworkManager.prefetchDnsOnBackground();
        }
    }


    private void autoSave() {
        if (appContext != null) {
            SettingsStorage.save(appContext, this);
        }
    }
}
