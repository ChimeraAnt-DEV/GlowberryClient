package org.chimeramc.client.core.minecraft;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.chimeramc.client.core.content.InGamePackChanger;
import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.core.versions.VersionManager;
import org.chimeramc.client.util.MainThread;

/**
 * Relaunches the running Minecraft instance so a change that needs a fresh load takes effect.
 *
 * <p>The in-game pack changer can write the pack lists, but this build has no verified native
 * in-place refresh (the preloader's {@code nativeReloadResourcePacks} resolves to no hook on the
 * shipped builds and returns false), so the
 * loaded world keeps its cached pack stack. The supported way to apply it is to relaunch the same
 * instance — which is exactly what the launcher's Play button does — without the player having to
 * back out to the launcher and find it again.
 *
 * <p>The relaunch reuses the launcher's own validated launch path rather than reimplementing it:
 * {@link MinecraftProcessRestarter#restartLauncherAfterMinecraftExit} shows the restart screen,
 * kills the game process (releasing the game's file locks and its cached state), reopens
 * {@code MainActivity} and then re-runs the launch for the selected instance. Rebuilding an
 * Activity or process death alone would not work: {@code MinecraftActivity.onCreate} rebuilds the
 * game from its storage directories, and reusing the live process leaves the already-loaded native
 * libraries and cached pack stack in place.
 *
 * <p>Called from the in-game overlay, on the game's own thread. The relaunch is posted so the
 * toggle handler returns before the game tears down.
 */
public final class MinecraftSessionRestarter {

    private static final String PREFS = "minecraft_session_restart";
    private static final String KEY_PENDING = "pending_relaunch";
    private static final String KEY_PENDING_AT = "pending_relaunch_at";
    /** A request older than this is ignored, so a failed relaunch cannot auto-start a later cold boot. */
    private static final long PENDING_MAX_AGE_MS = 120_000L;

    private static final Handler MAIN = MainThread.handler();

    private MinecraftSessionRestarter() {}

    /**
     * Schedules a relaunch of the currently selected instance.
     *
     * @return true when a relaunch was scheduled; false when there is no running game to relaunch.
     */
    public static boolean restartCurrentInstance() {
        Activity activity = MinecraftActivityState.getCurrentActivity();
        if (activity == null) return false;
        if (!MinecraftActivityState.isRunning() && !MinecraftActivityState.isResumed()) return false;

        GameVersion version = null;
        try {
            version = VersionManager.get(activity).getSelectedVersion();
        } catch (Throwable ignored) {
        }
        if (version == null) return false;

        // Persisted, not a static: the relaunch kills this process, so in-memory state would be
        // gone before the fresh launcher could read it.
        setPendingRelaunch(activity);
        final Context appContext = activity.getApplicationContext();
        MAIN.post(() -> MinecraftProcessRestarter.INSTANCE
                .restartLauncherAfterMinecraftExit(appContext));
        return true;
    }

    /**
     * Reads and clears the pending-relaunch flag; true only once per request.
     *
     * <p>Stored in SharedPreferences because the relaunch restarts the process: a static field set
     * before the restart is lost when the old process is killed.
     */
    public static boolean consumePendingRelaunch(Context context) {
        if (context == null) return false;
        try {
            android.content.SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_PENDING, false)) return false;
            long requestedAt = prefs.getLong(KEY_PENDING_AT, 0L);
            prefs.edit().putBoolean(KEY_PENDING, false).remove(KEY_PENDING_AT).apply();
            // Ignore a stale request: if the relaunch failed and the user later cold-starts the
            // app, an old flag must not launch a game they never asked for.
            return requestedAt > 0L
                    && System.currentTimeMillis() - requestedAt <= PENDING_MAX_AGE_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Clears a pending relaunch without honouring it. */
    public static void clearPendingRelaunch(Context context) {
        if (context == null) return;
        try {
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_PENDING, false).remove(KEY_PENDING_AT).apply();
        } catch (Throwable ignored) {
        }
    }

    private static void setPendingRelaunch(Context context) {
        try {
            // commit(), not apply(): the whole process is killed moments later, and an async write
            // could be lost, silently dropping the relaunch.
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_PENDING, true)
                    .putLong(KEY_PENDING_AT, System.currentTimeMillis())
                    .commit();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Installs the relaunch hook into {@link InGamePackChanger} for this process.
     *
     * <p>Idempotent; called once at application start so the hook is present whenever a game
     * session is running, whether or not the in-game pack changer happens to be enabled.
     */
    public static void install() {
        InGamePackChanger.setRestarter(MinecraftSessionRestarter::restartCurrentInstance);
    }

    /** Removes the relaunch hook; used on teardown so a dead process does not keep one. */
    public static void uninstall() {
        InGamePackChanger.setRestarter(null);
    }
}
