package org.chimeramc.client.core.minecraft

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.chimeramc.client.core.crash.CrashReporter
import org.chimeramc.client.settings.FeatureSettings
import org.chimeramc.client.settings.LowLatencyNetworkManager
import org.chimeramc.client.settings.ThermalGovernor
import org.chimeramc.client.ui.dialogs.LogcatOverlayManager

class LauncherApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        // Point the failure reporter at the application context before anything else can fail, so
        // the first failure is durable rather than only reaching logcat.
        org.chimeramc.client.util.AppLog.install(applicationContext)
        FeatureSettings.init(applicationContext)
        LowLatencyNetworkManager.init(applicationContext)
        ThermalGovernor.init(applicationContext)
        org.chimeramc.client.core.minecraft.FpsOptimizationService.init(applicationContext)
        org.chimeramc.client.core.mods.fps.FpsModulesService.init(applicationContext)
        // Attribute a launch that never reported a session to the optifine items that were
        // active for it, so a hook that kills the process cannot trap the user in a crash loop.
        org.chimeramc.client.core.minecraft.OptifineModeManager.reconcileOnStartup(applicationContext)
        CrashReporter.init(this)
        // Send any crash report captured by a previous run, but only when the user's own
        // "Crash report upload" switch is on and this build has a collector configured. Off the
        // main thread: it reads files and performs a network request, and a launcher that stalls
        // on startup to report an old crash has the priorities backwards.
        Thread({
            try {
                org.chimeramc.client.core.crash.CrashReportUploader.uploadPending(applicationContext)
            } catch (_: Throwable) {
                // Reporting a crash must never itself crash the launcher; the uploader logs.
            }
        }, "crash-report-upload").apply { isDaemon = true }.start()
        // Mirror the persisted haptics preference into the static feedback layer so the
        // very first interaction honours it, before Settings is ever opened.
        org.chimeramc.client.ui.animation.UiTouchFeedback.setEnabled(
            org.chimeramc.client.util.PersonalizationManager(applicationContext)
                .isHapticFeedbackEnabled()
        )
        // A migrated instance whose storage-migration foreground service was killed mid-run leaves
        // the running flag set; a fresh process is proof the run died, so record the interruption
        // and clear the flag before the launcher can blind-resume it on the next launch.
        org.chimeramc.client.util.StorageMigrationService.reconcileOnProcessStart(applicationContext)

        val processName = Application.getProcessName()
        if (processName.endsWith(":crash")) return

        LogcatOverlayManager.init(this)
        PlaytimeManager.init(applicationContext)
        // The in-game pack changer applies a change by relaunching the running instance; install
        // the relaunch hook once here so it is present whenever a session is live, independent of
        // whether any particular overlay has been opened.
        MinecraftSessionRestarter.install()
        // The Replay recorder's state lives in a process singleton so a screen opened mid-capture
        // sees the running state; init here so it exists before any Mod Menu screen asks for it.
        org.chimeramc.client.core.replay.ReplayManager.init(applicationContext)

        // Boot the native cosmetics system once: publish the equipped set into the native registry
        // and install the sink that renders a peer's advertised cosmetics. There is no resource pack
        // path any more, so this is the single initialisation point.
        org.chimeramc.client.core.cosmetics.NativeCosmeticsRuntime.init(applicationContext)

        preferences = PreferenceManager.getDefaultSharedPreferences(this)
    }

    /**
     * Feeds the OS memory-pressure signal to the FPS optimizer.
     *
     * <p>{@code onTrimMemory} is the only place the platform tells the host it is being squeezed,
     * and the optimizer treats it as one of the reasons to shed background work during a session.
     * The constant is {@code ComponentCallbacks2.TRIM_MEMORY_RUNNING_*} — referencing the int is
     * safe on minSdk 28 because it is a compile-time constant, not a type.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val critical = level >= TRIM_MEMORY_RUNNING_CRITICAL
        org.chimeramc.client.core.minecraft.FpsOptimizationService.setLowMemory(critical)
        if (critical) {
            org.chimeramc.client.core.minecraft.FpsOptimizationService.apply()
        }
    }

    companion object {
        @JvmStatic
        lateinit var context: Context
            private set

        @JvmStatic
        lateinit var preferences: SharedPreferences
            private set
    }
}
