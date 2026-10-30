package org.chimeramc.client.core.minecraft

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import org.chimeramc.client.core.mods.Mod
import org.chimeramc.client.core.mods.ModCrashAttribution
import org.chimeramc.client.core.mods.ModLoadDiagnostics
import org.chimeramc.client.core.mods.ModManager
import org.chimeramc.client.core.mods.ModNativeLoader
import org.chimeramc.client.core.mods.ModSafeMode
import org.chimeramc.client.core.minecraft.MinecraftLauncher
import org.chimeramc.client.core.minecraft.NativeLibraryVersionGate
import org.chimeramc.client.core.auth.InstanceAccountBinding
import org.chimeramc.client.core.auth.MsftAccountStore
import org.chimeramc.client.core.auth.storage.XalExporter
import org.chimeramc.client.core.versions.GameVersion
import io.bambosan.mbloader.launcherUtils.LibBindings
import org.chimeramc.client.preloader.PreloaderInput
import org.chimeramc.client.preloader.PreloaderSignatureRulesManager
import org.chimeramc.client.util.LauncherStorage
import java.io.File

object MinecraftRuntimePreparer {
    data class PreparedRuntime(
        val version: GameVersion?,
        val gameManager: GamePackageManager,
        val skippedIncompatibleMods: List<String> = emptyList()
    )

    private data class NativeModLoadResult(
        val skippedIncompatibleMods: List<String>,
        val loadedModIds: Set<String>
    )

    interface ProgressListener {
        fun onProgress(progress: Int, status: String, detail: String? = null)
        fun onLog(message: String)
    }

    private val noopListener = object : ProgressListener {
        override fun onProgress(progress: Int, status: String, detail: String?) = Unit
        override fun onLog(message: String) = Unit
    }

    /** Wraps the caller's listener so every onLog line is also persisted to launch_debug.log. */
    private class FileMirroringListener(
        private val delegate: ProgressListener,
        private val launchContext: Context
    ) : ProgressListener {
        override fun onProgress(progress: Int, status: String, detail: String?) {
            delegate.onProgress(progress, status, detail)
        }
        override fun onLog(message: String) {
            delegate.onLog(message)
            LaunchLog.append(launchContext, message)
        }
    }

    fun prepare(
        context: Context,
        launchIntent: Intent,
        listener: ProgressListener = noopListener
    ): PreparedRuntime {
        val launchContext = context.applicationContext
        val fileListener = FileMirroringListener(listener, launchContext)
        LaunchLog.append(launchContext, "==== Minecraft launch preparation started ====")
        val trace = LaunchTrace.ensure(launchIntent)
        trace.milestone("Runtime preparation started")
        fileListener.onProgress(4, "Checking selected version")
        val version = resolveGameVersion(launchIntent)

            ?: throw IllegalArgumentException("No Minecraft version specified")
        fileListener.onLog("Using ${version.directoryName} (${version.versionCode})")
        trace.mark("Minecraft version resolved", "${version.directoryName} ${version.versionCode}")

        fileListener.onProgress(12,"Preparing game files")
        // Hand the loading screen's listener to the package manager so the extraction - by far the
        // longest part of a cold launch - reports into the UI instead of only into the trace. It
        // used to pass null, so the progress bar sat on "Preparing game files" for the whole
        // extraction with no log lines, which reads as a hang.
        val gameManager = GamePackageManager.getInstance(context.applicationContext, version, trace, fileListener)
        trace.mark("GamePackageManager ready")

        if (!gameManager.requireLauncherSupportsVersion(version)) {
            val message = gameManager.abiMismatchMessage(version)
                ?: "This Minecraft version requires a 32-bit process, which this launcher install cannot provide."
            fileListener.onLog("ABI preflight failed: $message")
            trace.error("ABI mismatch", message)
            throw IllegalStateException(message)
        }

        fileListener.onProgress(26,"Preparing launch")
        prepareMinecraftIntent(context, launchIntent, gameManager, version)

        trace.mark("Launch intent prepared")

        fileListener.onProgress(34,"Checking mods")
        val modManager = ModManager.getInstance()
        modManager.setCurrentVersion(version)
        trace.mark("ModManager state prepared")

        fileListener.onProgress(40,"Preparing game loader")
        fileListener.onLog("Loading game loader")
        trace.mark("Game loader load started")
        val hasEnabledMods = modManager.getMods().any { it.isEnabled }
        // Optifine mode applies through the preloader even with no mods, so load it whenever
        // either a mod or the master switch needs it.
        val optifineWanted = org.chimeramc.client.settings.FeatureSettings
            .getInstance().isOptifineModeEnabled
        // The preloader must be loaded before *any* PreloaderInput call. configureSignatureRules
        // below goes through that same JNI surface, so when neither a mod nor Optifine Mode wanted
        // the loader the call used to arrive with no library loaded, be discarded by the caught
        // UnsatisfiedLinkError, and the hooks were then installed later (from MinecraftActivity)
        // with no signature rules -- which faulted on the render thread. Loading here is a no-op
        // when MinecraftActivity would have loaded it anyway, and it is what makes the rules land.
        if (!ModManager.ensurePreloaderLoaded()) {
            fileListener.onLog(
                if (hasEnabledMods) "Game loader unavailable; enabled mods will not load"
                else "Game loader unavailable; the session will launch without mods"
            )
            trace.mark("Game loader unavailable",
                if (hasEnabledMods) "enabled mods present" else "no enabled mods")
        } else {
            trace.mark("Game loader load finished")
        }
        if (optifineWanted) {
            org.chimeramc.client.core.minecraft.OptifineModeManager
                .apply(context.applicationContext)
            trace.mark("Optifine mode configured")
        }
        // Only configure the rules once the library handle is live. A failed load is surfaced by
        // the preloader's own "rules not delivered" guard at hook-install time; delivering into
        // the void here would leave the same silent state the crash came from.
        if (ModManager.ensurePreloaderLoaded()) {
            // G5: fail closed on a build the signature DB does not cover. A rule file with no
            // matching entry would feed scan-and-hope byte patterns into the preloader; the
            // hooks silently never install. Refuse to hand the file over and record a reason
            // the diagnostics screen can show instead of pretending the build is supported.
            val coverageFailure = PreloaderSignatureRulesManager
                .describeCoverageFailure(context.applicationContext, version.versionCode)
            if (coverageFailure == null) {
                val signatureRulesFile = PreloaderSignatureRulesManager
                    .getRulesFile(context.applicationContext)
                PreloaderInput.configureSignatureRules(signatureRulesFile, version.versionCode)
                trace.mark("Preloader signature rules configured",
                    signatureRulesFile?.absolutePath ?: "<none>")
            } else {
                fileListener.onLog("Signature rules skipped: $coverageFailure")
                trace.mark("Preloader signature rules skipped (no coverage)", coverageFailure)
            }
        } else {
            fileListener.onLog("Game loader unavailable; preloader signature rules not delivered")
        }

        fileListener.onLog("Loading native libraries")
        loadMinecraftLibraries(gameManager, version, fileListener, trace)

        applyShaderCompatFixer(version, fileListener, trace)

        fileListener.onProgress(78,"Loading enabled mods")
        fileListener.onLog("Loading native mods")

        val nativeModResult = loadNativeMods(context, launchIntent, modManager, fileListener, trace)

        try {
            val profileId = MinecraftLauncher.getStorageProfileId(version)
            val internalGameData = LauncherStorage.getStorageGameDataDir(context, profileId, version.versionIsolation, false)
            val externalGameData = LauncherStorage.getStorageGameDataDir(context, profileId, version.versionIsolation, true)
            val packResult = BundledResourcePackInstaller.sync(
                modManager.mods,
                nativeModResult.loadedModIds,
                internalGameData,
                externalGameData
            )
            if (packResult.packCount > 0) {
                fileListener.onLog("Prepared ${packResult.packCount} bundled native-mod pack(s)")
            }
            if (!packResult.isComplete) {
                // A cosmetic side effect, not a launch gate: the game reads exactly one game-data
                // root, so a failure writing a candidate it will not read must not stop the session.
                // Report it by cause so the Diagnostics screen and logcat agree, then continue.
                val detail = packResult.firstError?.message ?: packResult.firstError?.javaClass?.simpleName
                fileListener.onLog(
                    "Bundled native-mod packs could not be written to " +
                        "${packResult.failedRoots.size} of " +
                        "${packResult.failedRoots.size + packResult.succeededRoots.size} game-data " +
                        "root(s); the launch continues. ${detail ?: ""}".trim()
                )
                trace.warning(
                    "Bundled native-mod pack synchronization incomplete",
                    packResult.firstError?.javaClass?.simpleName
                )
            } else {
                trace.mark("Bundled native-mod packs synchronized", packResult.packCount.toString())
            }
        } catch (error: Throwable) {
            // Even an unexpected failure outside the per-root handling must not block a launch: the
            // packs are a cosmetic convenience, and a session that cannot start is far worse than a
            // mod's bundled resource pack not being applied.
            trace.warning(
                "Bundled native-mod pack synchronization failed",
                error.message ?: error.javaClass.simpleName
            )
        }

        // F2: an instance can be bound to a specific Microsoft account. Export that account's XAL
        // data before the game starts, so the instance signs in as the account it was bound to
        // rather than whichever one is globally active. A binding whose account is gone is left in
        // place and reported, never silently re-pointed; a failure is logged and the launch
        // continues, because an account problem must not block a launch.
        try {
            val boundAccountId = version.boundAccountId
            if (boundAccountId != null && boundAccountId.isNotEmpty()) {
                val accounts = MsftAccountStore.list(context).map {
                    object : InstanceAccountBinding.Account {
                        override fun id() = it.id
                        override fun active() = it.active
                        override fun hasAuthMaterial() = it.serializedAuthManager != null
                    }
                }
                val resolution = InstanceAccountBinding.resolve(boundAccountId, accounts)
                val chosen = resolution.account
                if (chosen != null && chosen.id() == boundAccountId) {
                    if (XalExporter.exportAccount(context, boundAccountId)) {
                        fileListener.onLog("Bound account exported for this instance")
                    } else {
                        fileListener.onLog("Bound account has no usable sign-in data")
                    }
                } else if (resolution.bindingStale) {
                    fileListener.onLog(
                        "This instance is bound to an account that is no longer available; " +
                            "launching with the active account instead")
                }
            }
        } catch (error: Throwable) {
            trace.error("Instance account binding failed", error.javaClass.simpleName)
        }

        // Publish the equipped set into the native cosmetics registry before every launch, so the
        // preloader's player-render hook has the cape/accessory/pet ready the moment the game
        // starts. getEquippedCapeForDisplay applies the Optifine default, so an Optifine user with
        // no cape chosen is not left bare. There is no resource pack to write and nothing to reload.
        // A failure is logged, never fatal: a cosmetic must not block the launch.
        try {
            org.chimeramc.client.core.cosmetics.NativeCosmeticsRuntime.syncEquipped(context)
            fileListener.onLog("Published native cosmetics")
        } catch (error: Throwable) {
            trace.error("Native cosmetics publish failed", error.javaClass.simpleName)
        }

        fileListener.onProgress(100,"Runtime ready", "Entering Minecraft")
        LaunchLog.append(launchContext, "==== Minecraft launch preparation finished ====")
        trace.milestone("Runtime preparation finished")
        return PreparedRuntime(version, gameManager, nativeModResult.skippedIncompatibleMods)
    }

    @JvmStatic
    fun resolveGameVersion(intent: Intent): GameVersion? {
        val parcelableVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(MinecraftLauncher.EXTRA_GAME_VERSION, GameVersion::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<GameVersion>(MinecraftLauncher.EXTRA_GAME_VERSION)
        }
        if (parcelableVersion != null) {
            return parcelableVersion
        }

        val versionDir = intent.getStringExtra("MC_PATH")
        val versionCode = intent.getStringExtra("MINECRAFT_VERSION") ?: ""
        val versionDirName = intent.getStringExtra("MINECRAFT_VERSION_DIR") ?: ""
        val isInstalled = intent.getBooleanExtra("IS_INSTALLED", false)

        return if (!versionDir.isNullOrEmpty()) {
            GameVersion(
                versionDirName,
                versionCode,
                versionCode,
                File(versionDir),
                isInstalled,
                MinecraftLauncher.MC_PACKAGE_NAME,
                ""
            )
        } else if (versionCode.isNotEmpty()) {
            GameVersion(
                versionDirName,
                versionCode,
                versionCode,
                null,
                isInstalled,
                MinecraftLauncher.MC_PACKAGE_NAME,
                ""
            )
        } else {
            null
        }
    }

    private fun prepareMinecraftIntent(
        context: Context,
        launchIntent: Intent,
        gameManager: GamePackageManager,
        version: GameVersion
    ) {
        val profileId = MinecraftLauncher.getStorageProfileId(version)
        val versionIsolation = version.versionIsolation
        val filesDir = LauncherStorage.getStorageFilesRoot(context, profileId, versionIsolation, false)
        val externalFilesDir = LauncherStorage.getStorageFilesRoot(context, profileId, versionIsolation, true)
        val dataDir = LauncherStorage.getStorageDataRoot(context, profileId, versionIsolation)
        val cacheDir = LauncherStorage.getStorageCacheRoot(context, profileId, versionIsolation)

        version.versionDir?.let { launchIntent.putExtra("MC_PATH", it.absolutePath) }
        launchIntent.putExtra("IS_INSTALLED", version.isInstalled)
        launchIntent.putExtra("VERSION_ISOLATION", versionIsolation)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_PROFILE_ID, profileId)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_FILES_DIR, filesDir.absolutePath)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_EXTERNAL_FILES_DIR, externalFilesDir.absolutePath)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_DATA_DIR, dataDir.absolutePath)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_CACHE_DIR, cacheDir.absolutePath)

        val mcInfo: ApplicationInfo = if (version.isInstalled) {
            gameManager.getPackageContext().applicationInfo
        } else {
            MinecraftLauncher(context).createFakeApplicationInfo(
                version,
                MinecraftLauncher.MC_PACKAGE_NAME,
                gameManager.getLaunchAbi()
            )
        }
        launchIntent.putExtra("MC_SRC", mcInfo.sourceDir)
        val splitSourceDirs = mcInfo.splitSourceDirs
        if (splitSourceDirs != null) {
            launchIntent.putExtra("MC_SPLIT_SRC", arrayListOf(*splitSourceDirs))
        }
        launchIntent.putExtra("MINECRAFT_VERSION", version.versionCode)
        launchIntent.putExtra("MINECRAFT_VERSION_DIR", version.directoryName)
        launchIntent.putExtra("LAUNCH_VERTICALLY", version.launchVertically)
        launchIntent.putExtra("VERSION_ISOLATION", version.versionIsolation)
    }

    private fun loadMinecraftLibraries(
        gameManager: GamePackageManager,
        version: GameVersion,
        listener: ProgressListener,
        trace: LaunchTrace
    ) {
        listener.onProgress(46, "Loading native libraries")
        trace.mark("Minecraft library loading started")

        if (shouldLoadHttpClient(version)) {
            loadLibrary(gameManager, "c++_shared", 48, true, listener, trace)
            loadLibrary(gameManager, "HttpClient.Android", 52, true, listener, trace)
        }

        if (shouldLoadMaesdk(version)) {
            val excludeLibs = HashSet<String>()
            val excludeReasons = HashMap<String, String>()
            if (shouldLoadHttpClient(version)) {
                excludeLibs.add("c++_shared")
                excludeLibs.add("HttpClient.Android")
                excludeReasons["c++_shared"] = "already loaded before the bundle"
                excludeReasons["HttpClient.Android"] = "already loaded before the bundle"
            }
            if (!shouldLoadPlayFab(version)) {
                excludeLibs.add("PlayFabMultiplayer")
                excludeReasons["PlayFabMultiplayer"] = "not required by this Minecraft version"
            }
            listener.onProgress(56, "Loading native libraries")
            trace.mark("Minecraft native library bundle loading started", "1.21.110+ layout")
            val failedLibraries = gameManager
                .loadAllLibraries(excludeLibs, trace, listener, 56, 74, excludeReasons)
                .filterNot { it.loaded }
            if (failedLibraries.isNotEmpty()) {
                val details = failedLibraries.joinToString(separator = "\n") { result ->
                    "${result.fileName}: ${result.detail ?: "unknown error"}"
                }
                trace.error("Native library bundle load failed", details)
                throw RuntimeException("Failed to load native libraries:\n$details")
            }
            trace.mark("Minecraft native library bundle loading finished")
        } else {
            if (!shouldLoadHttpClient(version)) {
                loadLibrary(gameManager, "c++_shared", 50, true, listener, trace)
            }
            loadLibrary(gameManager, "fmod", 56, true, listener, trace)
            loadLibrary(gameManager, "MediaDecoders_Android", 62, true, listener, trace)
            loadLibrary(gameManager, "minecraftpe", 70, true, listener, trace)
        }
        trace.mark("Minecraft library loading finished")
    }

    private fun loadLibrary(
        gameManager: GamePackageManager,
        name: String,
        progress: Int,
        required: Boolean,
        listener: ProgressListener,
        trace: LaunchTrace
    ) {
        val fileName = toLibraryFileName(name)
        listener.onProgress(progress, "Loading native libraries", fileName)
        listener.onLog("Loading native library: $fileName")
        trace.mark("Native library load started", fileName)
        val result = gameManager.loadLibraryDetailed(name)
        if (!result.loaded && required) {
            listener.onLog("Failed to load native library: ${result.fileName}")
            trace.error(
                "Required library load failed",
                "${result.fileName} in ${result.durationMs}ms from ${result.source}" +
                    (result.detail?.let { " - $it" } ?: "")
            )
            throw RuntimeException("Failed to load ${result.fileName}: ${result.detail ?: "unknown error"}")
        }
        if (result.loaded) {
            listener.onLog("Loaded native library: ${result.fileName}")
            trace.mark(
                "Native library load finished",
                "${result.fileName} in ${result.durationMs}ms from ${result.source}" +
                    (result.detail?.let { " - $it" } ?: "")
            )
        } else {
            listener.onLog("Skipped native library: ${result.fileName}")
            trace.mark(
                "Native library load skipped",
                "${result.fileName} in ${result.durationMs}ms from ${result.source}" +
                    (result.detail?.let { " - $it" } ?: "")
            )
        }
    }

    private fun loadNativeMods(
        context: Context,
        launchIntent: Intent,
        modManager: ModManager,
        listener: ProgressListener,
        trace: LaunchTrace
    ): NativeModLoadResult {
        val cacheDir = resolveNativeModCacheDir(context, launchIntent)
        trace.mark(
            "Native mod loading started",
            "mods=${modManager.currentVersion?.modsDir?.absolutePath ?: "<unknown>"}"
        )
        val modLoadLabels = java.util.IdentityHashMap<Mod, String>()
        val skippedIncompatibleMods = mutableListOf<String>()
        val loadedModIds = linkedSetOf<String>()
        val failedModRecords = mutableListOf<ModLoadDiagnostics.Record>()

        // G8: per-mod crash attribution. The decision to auto-disable a mod that has crashed
        // the previous N launches comes from the attribution counter; ModSafeMode still owns the
        // single-shot crash-loop marker this launch's outcome is recorded on.
        val crashEvaluation = ModCrashAttribution.onLaunchStart(
            context,
            ModSafeMode.hasCrashLoop(context),
            ModSafeMode.suspectModIds(context)
        )
        if (crashEvaluation.autoDisable.isNotEmpty()) {
            // Same suspects crashed AUTO_DISABLE_THRESHOLD+ launches in a row. Disable them now
            // so this launch is the one that finally goes through; ModsFullscreenActivity shows
            // the explanatory toast when the Mods tab is opened.
            val byId = modManager.mods.associateBy(Mod::getId)
            for (modId in crashEvaluation.autoDisable) {
                val mod = byId[modId]
                if (mod == null) continue
                if (!mod.isEnabled) continue
                modManager.setModEnabled(modId, false)
                val reason = "Disabled after ${crashEvaluation.crashCount} crashes in a row; " +
                    "re-enable one at a time in the Mods tab"
                failedModRecords.add(
                    ModLoadDiagnostics.Record(
                        modId, mod.displayName, mod.version ?: "",
                        reason, ModLoadDiagnostics.KIND_INCOMPATIBLE, System.currentTimeMillis()
                    )
                )
                listener.onLog("Auto-disabled $reason: ${mod.displayName}")
                trace.warning("Mod auto-disabled (crash attribution)", mod.displayName)
            }
        }
        ModSafeMode.beginLaunch(context, modManager.mods.filter { it.isEnabled }.map { it.id })
        ModNativeLoader.loadEnabledSoMods(
            modManager,
            cacheDir,
            object : ModNativeLoader.LoadListener {
                override fun onScanStarted(totalEnabled: Int) {
                    if (totalEnabled > 0) {
                        listener.onLog("Loading $totalEnabled enabled mod(s)")
                    } else {
                        listener.onLog("No enabled native mods")
                    }
                }

                override fun onModLoadStarted(mod: Mod, index: Int, total: Int) {
                    val progress = 80 + ((index - 1) * 15 / total.coerceAtLeast(1))
                    val label = "$index/$total"
                    modLoadLabels[mod] = label
                    listener.onProgress(progress, "Loading native mods", "$label ${mod.displayName}")
                    trace.mark("Native mod load started", "$label ${mod.displayName}")
                }

                override fun onModLoadFinished(mod: Mod) {
                    val label = modLoadLabels.remove(mod)?.let { "$it " }.orEmpty()
                    loadedModIds.add(mod.id)
                    ModSafeMode.markModLoaded(context, mod.id)
                    listener.onLog("Loaded mod: $label${mod.displayName}")
                    trace.mark("Native mod load finished", mod.displayName)
                }

                override fun onModLoadSkipped(mod: Mod, minecraftVersion: String) {
                    val label = modLoadLabels.remove(mod)?.let { "$it " }.orEmpty()
                    skippedIncompatibleMods.add(mod.displayName)
                    failedModRecords.add(
                        ModLoadDiagnostics.Record(
                            mod.id,
                            mod.displayName,
                            mod.version ?: "",
                            "Built for a different Minecraft version (this instance runs $minecraftVersion)",
                            ModLoadDiagnostics.KIND_INCOMPATIBLE,
                            System.currentTimeMillis()
                        )
                    )
                    listener.onLog("Skipped incompatible mod ${label}${mod.displayName} for Minecraft $minecraftVersion")
                    trace.warning("Native mod skipped as incompatible", "${mod.displayName}: $minecraftVersion")
                }

                override fun onModLoadFailed(mod: Mod, error: Throwable) {
                    failedModRecords.add(
                        ModLoadDiagnostics.Record(
                            mod.id,
                            mod.displayName,
                            mod.version ?: "",
                            ModLoadDiagnostics.Classifier.describe(error),
                            ModLoadDiagnostics.Classifier.kindOf(error),
                            System.currentTimeMillis()
                        )
                    )
                    trace.warning("Native mod load failed", "${mod.displayName}: ${error.message ?: error.javaClass.simpleName}")
                    listener.onLog("Failed to load mod ${mod.displayName}: ${error.message ?: error.javaClass.simpleName}")
                }

                override fun onMessage(message: String) {
                    listener.onLog(message)
                    trace.warning("Native mod loader message", message)
                }
            }
        )
        listener.onProgress(96, "Native mods ready")
        listener.onLog("Native mods ready")
        ModLoadDiagnostics.record(context, failedModRecords)
        if (failedModRecords.isNotEmpty()) {
            trace.warning("Native mod failures recorded", "${failedModRecords.size} mod(s) failed to load")
        }
        trace.mark("Native mod loading finished")
        return NativeModLoadResult(skippedIncompatibleMods, loadedModIds)
    }

    private fun resolveNativeModCacheDir(context: Context, launchIntent: Intent): File {
        val versionDirName = launchIntent.getStringExtra("MINECRAFT_VERSION_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?: "default"
        return File(context.cacheDir, "native_mods/$versionDirName").also { it.mkdirs() }
    }

    private fun applyShaderCompatFixer(version: GameVersion, listener: ProgressListener, trace: LaunchTrace) {
        if (version == null) return
        if (!version.shaderCompatEnabled) return
        val is64BitInstance = version.abiList?.takeIf { it.isNotBlank() }?.let { abi ->
            abi == "arm64-v8a" || abi == "x86_64"
        } ?: false
        if (!is64BitInstance) {
            listener.onLog("Skipping shader compatibility fixer: requires a 64-bit instance")
            trace.mark("Shader compatibility fixer skipped", "non-64-bit instance")
            return
        }
        if (Build.SUPPORTED_64_BIT_ABIS.none { it.contains("arm64-v8a") || it.contains("x86_64") }) {
            listener.onLog("Skipping shader compatibility fixer: device does not support 64-bit native code")
            trace.mark("Shader compatibility fixer skipped", "32-bit device")
            return
        }
        if (!LibBindings.isAvailable()) {
            listener.onLog("Shader compatibility fixer unavailable on this build")
            trace.mark("Shader compatibility fixer skipped", "native library missing")
            return
        }
        listener.onLog("Loading shader compatibility fixer (mtbinloader2)")
        trace.mark("Shader compatibility fixer load started")
        try {
            // The fixer is range-gated: it needs the version up front or it cannot tell which
            // material-bin revision it is looking at. Setting it first is what makes the two
            // autofixers below actually engage.
            LibBindings.applyAutofixVersions(version.versionCode)
            LibBindings.setLightmapAutofixer(true)
            LibBindings.setTextureLodAutofixer(true)
            listener.onLog("Shader compatibility fixer loaded for ${version.versionCode}")
            trace.mark("Shader compatibility fixer load finished")
        } catch (throwable: Throwable) {
            listener.onLog("Failed to load shader compatibility fixer: ${throwable.message ?: throwable.javaClass.simpleName}")
            trace.error("Shader compatibility fixer load failed", throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    private fun shouldLoadMaesdk(version: GameVersion): Boolean {
        return NativeLibraryVersionGate.shouldLoadMaesdk(version.versionCode)
    }

    private fun shouldLoadHttpClient(version: GameVersion): Boolean {
        return NativeLibraryVersionGate.shouldLoadHttpClient(version.versionCode)
    }

    private fun shouldLoadPlayFab(version: GameVersion): Boolean {
        return NativeLibraryVersionGate.shouldLoadPlayFab(version.versionCode)
    }

    private fun toLibraryFileName(name: String): String {
        return if (name.startsWith("lib") && name.endsWith(".so")) name else "lib${name.removePrefix("lib").removeSuffix(".so")}.so"
    }
}
