package org.chimeramc.client.core.minecraft

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.chimeramc.client.BuildConfig
import org.chimeramc.client.core.versions.GameVersion
import org.levimc.launcher.util.NativeBridgeHelper
import org.levimc.launcher.util.NativeImageGuard
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

import org.chimeramc.client.core.minecraft.ExtractionCachePolicy
import org.chimeramc.client.core.minecraft.ExtractionManifest
import org.chimeramc.client.core.minecraft.NativeLibraryVersionGate
import org.chimeramc.client.core.minecraft.RequiredNativeLibraries

class GamePackageManager private constructor(
    private val context: Context,
    private val version: GameVersion?,
    launchTrace: LaunchTrace?,
    progressListener: MinecraftRuntimePreparer.ProgressListener?
) {

    /**
     * Where this instance reports progress.
     *
     * <p>Not constructor `val`s: the instance is cached per version and reused by the next launch
     * of that version, so a sink captured once would keep reporting into the *previous* launch's
     * {@link LaunchTrace}. The second launch of an instance then had no extraction milestones in
     * its trace at all, which is exactly the diagnostic that explains a slow launch. {@code
     * getInstance} re-points both on reuse.
     */
    private var launchTrace: LaunchTrace? = launchTrace
    private var progressListener: MinecraftRuntimePreparer.ProgressListener? = progressListener

    private val packageContext: Context
    private val assetManager: AssetManager
    private val nativeLibDir: String
    private val applicationInfo: ApplicationInfo
    private var launchAbi: String? = null

    /** ABI scan of this version's APK(s); the archives are immutable for a given version. */
    private var cachedShippedAbis: List<String>? = null

    private val knownPackages = arrayOf(MinecraftLauncher.MC_PACKAGE_NAME)

    // The list itself lives in RequiredNativeLibraries (pure, Android-free) so the repair-need
    // check and this manager cannot disagree about what the loader will open.
    private val requiredLibs = RequiredNativeLibraries.BASE.toTypedArray()

    private val optionalLibs = RequiredNativeLibraries.OPTIONAL.toTypedArray()

    private val extractableLibs = requiredLibs + optionalLibs

    private val systemLoadedLibs = arrayOf(
        "libPlayFabMultiplayer.so",
        "libmaesdk.so",
        "libgxcore.so",
    )

    data class LibraryLoadResult(
        val name: String,
        val fileName: String,
        val source: String,
        val loaded: Boolean,
        val durationMs: Long,
        val detail: String? = null
    )

    init {
        report("GamePackageManager init started")
        val packageName = detectGamePackage() ?: throw IllegalStateException("Minecraft not found")
        report("Detected Minecraft package: $packageName")
        packageContext = context.createPackageContext(
            packageName,
            Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
        )
        
        if (version != null && !version.isInstalled) {
            applicationInfo = MinecraftLauncher(context).createFakeApplicationInfo(version, MinecraftLauncher.MC_PACKAGE_NAME)
        } else {
            applicationInfo = packageContext.applicationInfo
        }
        // The entire native-library path (extraction + loading) must derive from the
        // version's own ABI, not the device's primary ABI, so 32-bit-only versions load
        // their 32-bit libraries consistently from the matching architecture folder.
        // Hold it in a local: reading the mutable field back through `!!` would re-assert a
        // non-null the assignment just guaranteed, and would silently break if the field were
        // ever cleared between the two statements.
        val resolvedAbi = getDeviceAbi(collectApkFiles())
        launchAbi = resolvedAbi
        nativeLibDir = MinecraftLauncher.getRuntimeLibAbiDir(
            context,
            MinecraftLauncher.getStorageProfileId(version),
            resolvedAbi
        ).absolutePath
        File(nativeLibDir).mkdirs()
        if (version != null && !version.isInstalled) {
            // Keep the fake ApplicationInfo aligned with the version-aware native lib dir.
            applicationInfo.nativeLibraryDir = nativeLibDir
        }

        extractLibraries()
        report("Creating AssetManager")
        assetManager = createAssetManager()
        report("AssetManager ready")
        setupSecurityProvider()
        report("GamePackageManager init finished")
    }

    private fun detectGamePackage(): String? {
        return knownPackages.firstOrNull { isPackageInstalled(it) }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** ABI (e.g. arm64-v8a / armeabi-v7a) this launch uses for extracting and loading native libraries. */
    fun getLaunchAbi(): String {
        return launchAbi ?: getDeviceAbi(collectApkFiles()).also { launchAbi = it }
    }

    /**
     * Verifies the version's declared ABI can actually load in this process.
     *
     * Android fixes a process's bitness at install time from the ABI of the app's own
     * native libraries, and the platform offers no way to start a differently-sized process
     * from inside an app. So a 32-bit library set can never dlopen inside a 64-bit process,
     * and {@code android:use32bitAbi} cannot help: it only tells the installer to prefer the
     * 32-bit native libs an app already ships, so it is a build/packaging choice, not a
     * runtime one. This check surfaces the mismatch clearly before the first native library
     * is attempted, instead of a confusing "dlopen failed: is 32-bit instead of 64-bit".
     */
    fun requireLauncherSupportsVersion(version: GameVersion?): Boolean {
        return abiCompatibility(version) != AbiCompatibility.INCOMPATIBLE
    }

    /** How a version's declared ABI relates to the ABI this launcher process can load. */
    enum class AbiCompatibility {
        /** No ABI declared, or it matches the process. */
        OK,
        /** Declared 32-bit, process is 64-bit: cannot load, no runtime workaround. */
        INCOMPATIBLE,
    }

    fun abiCompatibility(version: GameVersion?): AbiCompatibility {
        val shipped = shippedAbisOf(version)
        return if (shipped.isEmpty()) {
            // No ABI information at all: let the load attempt speak rather than blocking.
            AbiCompatibility.OK
        } else if (AbiBitness.hasLoadableAbi(shipped, processIs64Bit())) {
            AbiCompatibility.OK
        } else {
            AbiCompatibility.INCOMPATIBLE
        }
    }

    /**
     * Every ABI the version's APK(s) actually ship, read from the archives.
     *
     * This deliberately inspects the APK rather than trusting {@code version.abiList}. That
     * field is a *label* inferred from whichever libraries happen to sit in the extraction
     * folder (see VersionManager.inferAbiFromNativeLibDir), so it records what was last
     * extracted, not what the version offers. Once a version had been extracted as arm
     * libraries the label said armeabi-v7a forever, and the preflight then refused to launch
     * it in a 64-bit process even when the same APK also shipped arm64-v8a and could have run.
     *
     * Falls back to the label only when no APK can be read, so an unreadable or absent
     * archive still gets a sensible answer instead of being waved through.
     */
    private fun shippedAbisOf(version: GameVersion?): List<String> {
        cachedShippedAbis?.let { return it }
        val fromApks = AbiBitness.abisInApks(collectApkFiles())
        val result = if (fromApks.isNotEmpty()) {
            fromApks
        } else {
            listOfNotNull(version?.abiList?.takeIf { it.isNotBlank() && it != "unknown" })
        }
        cachedShippedAbis = result
        return result
    }

    fun abiMismatchMessage(version: GameVersion?): String? {
        val shipped = shippedAbisOf(version)
        val declared = shipped.firstOrNull() ?: version?.abiList
        if (declared.isNullOrBlank()) return null
        if (abiCompatibility(version) != AbiCompatibility.INCOMPATIBLE) return null
        return buildString {
            append("This Minecraft version ships only $declared libraries, but GlowberryClient ")
            append("runs as a 64-bit process, and a 64-bit process cannot load 32-bit native code.")
            append(" Use a 64-bit build of this version.")
        }
    }

    private fun processIs64Bit(): Boolean = try {
        android.os.Process.is64Bit()
    } catch (_: Throwable) {
        // is64Bit is API 23+. Falling back to the primary ABI is accurate enough for a
        // preflight message and never throws.
        Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && Build.SUPPORTED_32_BIT_ABIS.isEmpty()
    }

    private fun getDeviceAbi(apkFiles: List<File> = emptyList()): String =
        resolveDeviceAbi(version, apkFiles)

    private fun resolveAbiFromApks(apkFiles: List<File>): String? {
        val shipped = AbiBitness.abisInApks(apkFiles)
        if (shipped.isEmpty()) return null
        // Prefer an ABI this process can actually load. The version's label is only a hint
        // about what was extracted last, so a dual-ABI APK must be allowed to serve a
        // 64-bit process even if the label previously settled on the 32-bit half.
        return AbiBitness.selectLaunchAbi(shipped, processIs64Bit())
    }

    private fun extractLibraries() {
        report("Preparing Minecraft library cache")
        val outputDir = File(nativeLibDir)
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val apkFiles = collectApkFiles()
        val manifestString = buildExtractionManifest(apkFiles)
        val manifestFile = File(outputDir, ".extraction_manifest")
        val markerMatches = try {
            manifestFile.isFile && manifestFile.readText() == manifestString
        } catch (_: Exception) {
            false
        }
        // A launcher update changes the extraction pipeline (new NativeImageGuard step, ABI
        // handling, packaging rules). Stale extracted libs from an older extractor can fail
        // dlopen with confusing errors until wiped. Invalidate on both a manual extractor bump
        // and the app's own version so a plain update always wipes + re-extracts before any
        // dlopen can touch the old files.
        val nativeExtractionVersion = nativeExtractionVersion()
        val versionFile = File(nativeLibDir, "native_extraction_version")
        val versionMatches = try {
            ExtractionCachePolicy.markerMatches(
                if (versionFile.isFile) versionFile.readText() else null,
                nativeExtractionVersion
            )
        } catch (_: Exception) {
            false
        }
        val cacheRequiredLibs = getCacheRequiredLibs()

        if (ExtractionCachePolicy.shouldWipeStaleLibraries(markerMatches, versionMatches)) {
            report("Extraction version changed ($nativeExtractionVersion): wiping stale native library cache")
            wipeExtractedLibraries(outputDir, cacheRequiredLibs)
        }

        val allPresent = ExtractionCachePolicy.isCacheUsable(
            markerMatches,
            versionMatches,
            // `asList` (the Kotlin stdlib view over an Array), not `toList`: the latter is textually
            // the same call as the API-34 Stream.toList the Api28Compat guard forbids, so the guard
            // cannot tell the two apart and flags a perfectly safe Kotlin call.
            ExtractionCachePolicy.allLibrariesPresent(cacheRequiredLibs.asList()) { lib ->
                File(outputDir, lib).let { it.exists() && it.length() > 0L }
            }
        )

        if (allPresent) {
            report("Minecraft library cache hit: ${outputDir.absolutePath}")
            for (lib in extractableLibs) {
                try {
                    val file = File(outputDir, lib)
                    if (file.exists()) {
                        ensureReadOnly(file)
                    }
                } catch (e: Exception) {
                    mirrorLogcat('W', "Failed ensureReadOnly: ${e.message}")
                }
            }
            return
        }
        report("Minecraft library cache miss: extracting libraries")

        val abi: String
        if (version != null && !version.isInstalled) {
            // Resolve once: getDeviceAbi scans every APK, so calling it per file would
            // re-open all of them for each one.
            abi = getDeviceAbi(apkFiles)
            apkFiles.forEach { extractFromApk(it.absolutePath, outputDir, abi) }
        } else {
            val appInfo = packageContext.applicationInfo
            if (File(appInfo.nativeLibraryDir).exists()) {
                copyFromNativeDir(appInfo.nativeLibraryDir, outputDir)
            }
            abi = getDeviceAbi(apkFiles)
            apkFiles.forEach { extractFromApk(it.absolutePath, outputDir, abi) }
        }
        // The STL fallback runs before the completeness check, not after. libc++_shared.so is in
        // the required list, and a package that ships no copy is supplied from the launcher's own
        // native dir - so checking first would refuse a perfectly launchable version for a library
        // the launcher was about to provide.
        ensureLibCppSharedFallback(outputDir)
        // Both extraction paths refuse a missing required library with the same named reason. The
        // installed-package path used to only *log* it, so an incomplete cache passed this point
        // and died later at dlopen with "libX.so not found in <dir>" - a message that names the
        // file but not the cause.
        requireLibrariesPresent(outputDir, cacheRequiredLibs, abi)
        verifyLibraries(outputDir)

        if (cacheRequiredLibs.all { File(outputDir, it).let { f -> f.exists() && f.length() > 0 } }) {
            try {
                manifestFile.writeText(manifestString)
                File(outputDir, ".extraction_marker").delete()
                writeNativeExtractionVersion(versionFile, nativeExtractionVersion)
                report("Minecraft library cache manifest written")
            } catch (e: Exception) {
                mirrorLogcat('W', "Failed to write extraction manifest: ${e.message}")
                report("Failed to write extraction manifest: ${e.message}")
            }
        }
    }

    /**
     * Version of the extraction pipeline itself. Tied to both an explicit manual bump and the
     * launcher's own version code, so shipping a new launcher build always invalidates the
     * extracted library cache instead of trusting stale `.so` files.
     */
    private fun nativeExtractionVersion(): Int =
        Companion.calculateNativeExtractionVersion()

    private fun writeNativeExtractionVersion(versionFile: File, version: Int) {
        try {
            versionFile.writeText(version.toString())
        } catch (e: Exception) {
            mirrorLogcat('W', "Failed to write native extraction version: ${e.message}")
        }
    }

    private fun wipeExtractedLibraries(outputDir: File, keep: Array<out String>) {
        val keepSet = keep.toHashSet()
        keepSet.add(".extraction_manifest")
        keepSet.add("native_extraction_version")
        val children = outputDir.listFiles() ?: return
        for (child in children) {
            if (child.isFile && child.name.endsWith(".so")) {
                if (!keepSet.contains(child.name)) {
                    try {
                        child.setWritable(true, true)
                        if (child.delete()) {
                            mirrorLogcat('W', "Wiped stale extracted library: ${child.name}")
                        }
                    } catch (e: Exception) {
                        mirrorLogcat('W', "Failed to wipe stale library ${child.name}: ${e.message}")
                    }
                }
            }
        }
        // Remove any .tmp leftover from an interrupted extraction.
        outputDir.listFiles()?.forEach { child ->
            if (child.isFile && child.name.endsWith(".tmp") && child.delete()) {
                mirrorLogcat('W', "Removed interrupted extraction temp file: ${child.name}")
            }
        }
    }

    private fun collectApkFiles(): List<File> {
        val paths = mutableListOf<String>()
        if (version != null && !version.isInstalled) {
            applicationInfo.sourceDir?.let { paths.add(it) }
            applicationInfo.splitSourceDirs?.let { paths.addAll(it) }
        } else {
            val appInfo = packageContext.applicationInfo
            appInfo.sourceDir?.let { paths.add(it) }
            appInfo.splitPublicSourceDirs?.let { paths.addAll(it) }
        }

        return paths
            .map { File(it) }
            .filter {
                if (it.exists()) {
                    true
                } else {
                    mirrorLogcat('W', "APK file not found: ${it.absolutePath}")
                    report("APK file not found: ${it.absolutePath}")
                    false
                }
            }
            .sortedBy { it.absolutePath }
    }

    private fun buildExtractionManifest(apkFiles: List<File>): String {
        // One format, in ExtractionManifest, because the reader compares the file by equality: the
        // repair writes this same text and any drift between two builders turns every repaired cache
        // back into a cache miss.
        return ExtractionManifest.build(
            ExtractionCachePolicy.EXTRACTOR_VERSION,
            getDeviceAbi(apkFiles),
            NativeImageGuard.TOKEN,
            if (version != null && !version.isInstalled) "isolated" else "installed",
            shouldLoadHttpClient(),
            apkFiles.map { ExtractionManifest.ApkEntry.of(it) }
        )
    }

    private fun copyFromNativeDir(sourceDir: String, destDir: File) {
        val source = File(sourceDir)
        if (!source.exists()) {
            mirrorLogcat('W', "Source native library directory does not exist: $sourceDir")
            return
        }

        // G3: copy every `.so` the installed package ships, not only the names the launcher lists,
        // for the same reason `extractFromApk` does. The named list gates what *must* be present;
        // covering everything means a transitive DT_NEEDED dependency the launcher does not know
        // about is still in the directory when the game's libraries dlopen it. Before this, an
        // installed-package launch could fail with "library not found" for a dependency that was
        // sitting right there in the source directory.
        val shipped = source.listFiles { file -> file.isFile && file.name.endsWith(".so") }
        // `asList` (the Kotlin stdlib view over an Array), not `toList`: the latter is textually the
        // same call as the API-34 Stream.toList the Api28Compat guard forbids, so the guard cannot
        // tell the two apart and flags a perfectly safe Kotlin call.
        val named = extractableLibs.asList()
        val toCopy = if (shipped.isNullOrEmpty()) {
            // A directory that cannot be listed (permissions, an unusual OEM layout) falls back to
            // the named list rather than copying nothing.
            named
        } else {
            (shipped.map { it.name } + named).distinct()
        }

        for (lib in toCopy) {
            val srcFile = File(source, lib)
            val dstFile = File(destDir, lib)
            if (srcFile.exists() && srcFile.length() > 0) {
                try {
                    srcFile.inputStream().use { input ->
                        copyStreamToReadOnlyFile(input, dstFile)
                    }
                    report("Copied $lib from native lib directory")
                    if (!processNativeImage(dstFile, true)) {
                        dstFile.delete()
                        throw IOException("Failed to prepare native library: ${dstFile.name}")
                    }
                    logFileOperation("Copied", lib)
                } catch (e: Exception) {
                    logFileOperation("Failed to copy", lib, e = e)
                }
            } else {
                mirrorLogcat('W', "Library $lib not found in $sourceDir")
            }
        }
    }

    private fun extractFromApk(apkPath: String, outputDir: File, abi: String) {
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            mirrorLogcat('W', "APK file does not exist: $apkPath")
            return
        }
        if (!apkPath.contains("arm") && !apkPath.contains("x86") && !apkPath.contains("base.apk")) {
            return
        }

        try {
            // G3: extract every `.so` the package ships for this ABI, not only the ones the
            // launcher names. A Bedrock package can carry extra multi-.so deps (codecs, fmod
            // variants, a packaged libc++_shared.so) that a DT_NEEDED lookup will demand from
            // the same directory; the named list gates what must be present, extraction covers
            // everything, so a runtime dlopen never misses a transitive dependency.
            ZipFile(apkPath).use { zip ->
                val abiPath = "lib/$abi/"
                val entries = zip.entries()
                val extracted = mutableListOf<String>()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || entry.name.isEmpty()) continue
                    if (!entry.name.startsWith(abiPath) || !entry.name.endsWith(".so")) continue
                    val libName = entry.name.removePrefix(abiPath)
                    val output = File(outputDir, libName)
                    // Per-entry containment: one library that cannot be replaced (a previous
                    // extraction left it read-only) or prepared must not abort the remaining
                    // entries, or a single bad file would leave the cache half-populated and the
                    // launch failing with a confusing missing-dependency error.
                    try {
                        zip.getInputStream(entry).use { input ->
                            copyStreamToReadOnlyFile(input, output)
                        }
                        if (!processNativeImage(output, true)) {
                            output.delete()
                            throw IOException("Failed to prepare native library: ${output.name}")
                        }
                        extracted += libName
                        report("Extracted $libName from ${apkFile.name} for $abi")
                    } catch (e: Exception) {
                        mirrorLogcat('W', "Failed to extract ${output.name} from $apkPath: ${e.message}")
                    }
                }
                if (extracted.isEmpty()) {
                    mirrorLogcat('W', "No .so files found under $abiPath in $apkPath")
                }
            }
        } catch (e: Exception) {
            mirrorLogcat('W', "Failed to extract libraries from $apkPath: ${e.message}")
        }
    }

    private fun copyStreamToReadOnlyFile(input: InputStream, output: File) {
        ensureParentDirectory(output)
        if (output.exists()) {
            // A previous extraction marks every library read-only, and a read-only file cannot be
            // deleted. Without making it writable first, re-extraction (Repair, or a version bump)
            // failed to replace its own cache and the launch kept loading the stale library.
            output.setWritable(true, true)
            if (!output.delete()) {
                throw IOException("Failed to replace existing file: ${output.absolutePath}")
            }
        }

        val tempFile = File(output.absolutePath + ".tmp")
        if (tempFile.exists()) tempFile.delete()

        FileOutputStream(tempFile).use { out ->
            input.copyTo(out)
            out.fd.sync()
        }

        if (!tempFile.renameTo(output)) {
            tempFile.delete()
            throw IOException("Failed to rename temporary file to ${output.absolutePath}")
        }

        ensureReadOnly(output)
    }

    private fun ensureParentDirectory(file: File) {
        val parent = file.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create parent directory: ${parent.absolutePath}")
        }
    }

    private fun ensureReadOnly(file: File) {
        if (!file.isFile) {
            throw IOException("Expected regular file: ${file.absolutePath}")
        }
        if (!file.setReadable(true, true) && !file.canRead()) {
            throw IOException("Failed to mark file readable: ${file.absolutePath}")
        }
        if (!file.setReadOnly() && file.canWrite()) {
            throw IOException("Failed to keep file read-only: ${file.absolutePath}")
        }
    }

    private fun verifyLibraries(dir: File) {
        val missing = getCacheRequiredLibs().filterNot {
            File(dir, it).let { f -> f.exists() && f.length() > 0 }
        }
        if (missing.isNotEmpty()) {
            mirrorLogcat('W', "Missing libraries in $dir: ${missing.joinToString()}")
            report("Missing libraries in ${dir.absolutePath}: ${missing.joinToString()}")
        }

        verifyLibraryAbi(dir)
    }

    /**
     * G3: SONAME/ABI validation. Reads each extracted ELF header and reports any library whose
     * architecture cannot load in this process *before* a launch attempt, instead of the raw
     * "dlopen failed: is 32-bit instead of 64-bit" surfacing mid-launch. A mismatched library is
     * not removed here — the launch preflight decides what to do — but every mismatch is logged
     * so the diagnostics report and logcat agree about the failure cause.
     */
    /**
     * Fails with the specific cause when a required library is absent from the extracted cache.
     *
     * <p>A zero-length file is what an interrupted extraction leaves behind, and it is not loadable,
     * so it must fail here with the named reason rather than passing the preflight and dying at
     * {@code dlopen} with an error the user cannot act on.
     *
     * <p>Shared by both extraction paths so an imported version and an installed version cannot
     * report the same condition two different ways.
     */
    private fun requireLibrariesPresent(outputDir: File, required: Array<String>, abi: String) {
        val missingLibs = ExtractionCachePolicy.missingLibraries(required.asList()) { lib ->
            File(outputDir, lib).let { it.exists() && it.length() > 0L }
        }
        if (missingLibs.isNotEmpty()) {
            throw IllegalStateException(
                "Required native libraries for ABI $abi are missing from this version's APK/splits: "
                    + missingLibs.joinToString()
            )
        }
    }

    private fun verifyLibraryAbi(dir: File) {
        val processIs64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val libs = getCacheRequiredLibs().plus(systemLoadedLibs)
        for (name in libs) {
            val file = File(dir, name)
            if (!file.isFile) continue
            val mismatch = ElfHeaderValidator.describeMismatch(file, processIs64Bit)
            if (mismatch != null) {
                mirrorLogcat('E', "ABI mismatch: $mismatch")
                report("ABI-mismatched native library: $mismatch")
            }
        }
    }

    /**
     * G3: multi-`.so` robustness. When a version's package does not ship `libc++_shared.so`, fall
     * back to the launcher's own bundled copy (the preloader is linked against `c++_shared`), so
     * the extracted cache always has the STL the game's libraries load against. Copied only when
     * the file is absent — the game's own copy, if present, is the correct version for that build.
     */
    private fun ensureLibCppSharedFallback(outputDir: File) {
        val target = File(outputDir, "libc++_shared.so")
        if (target.exists() && target.length() > 0) {
            return
        }
        try {
            val appNativeDir = File(context.applicationInfo.nativeLibraryDir)
            val source = File(appNativeDir, "libc++_shared.so")
            if (source.isFile && source.length() > 0) {
                copyStreamToReadOnlyFile(source.inputStream(), File(outputDir, "libc++_shared.so"))
                report("libc++_shared.so missing from game package; copied launcher-bundled copy")
            } else {
                mirrorLogcat('W', "libc++_shared.so not found in APK or launcher native dir")
            }
        } catch (e: Exception) {
            mirrorLogcat('W', "libc++_shared.so fallback failed: ${e.message}")
        }
    }

    private fun processNativeImage(file: File, force: Boolean = false): Boolean {
        if (!file.name.endsWith(".so")) {
            return true
        }
        return if (force) {
            NativeImageGuard.processRequired(file)
        } else {
            NativeImageGuard.processIfNeeded(file)
        }
    }

    private fun processNativeImages(dir: File) {
        NativeImageGuard.processDirectory(dir)
    }

    private fun getCacheRequiredLibs(): Array<String> {
        // The cache is "complete" only when every library the loader will open is present. The
        // version-gated libraries are loaded out of this same extracted directory by
        // loadAllLibraries, so leaving them out of this list let an interrupted extraction look
        // complete and then fail at load with "libmaesdk.so not found" - the same class of
        // cache/loader disagreement the HTTP-client gate had. The list itself comes from the shared
        // rule, so the cache and the loader cannot drift again.
        return RequiredNativeLibraries.required(version?.versionCode).toTypedArray()
    }

    private fun shouldLoadHttpClient(): Boolean {
        return NativeLibraryVersionGate.shouldLoadHttpClient(version?.versionCode)
    }

    private fun mirrorLogcat(level: Char, message: String) {
        when (level) {
            'W' -> Log.w(TAG, message)
            'E' -> Log.e(TAG, message)
            else -> Log.i(TAG, message)
        }
        LaunchLog.appendLogcat(context, level, TAG, message)
    }

    private fun logFileOperation(action: String, lib: String, extra: String? = null, e: Exception? = null) {
        val message = buildString {
            append("$action $lib")
            if (extra != null) append(" $extra")
            if (e != null) append(": ${e.message}")
        }
        if (e != null) mirrorLogcat('W', message)
    }

    private fun report(message: String) {
        LaunchLog.append(context, message)
        // Both sinks, always. The listener drives the loading screen and the trace drives the
        // diagnostics report, and they are read by different people at different times; reporting
        // to only one of them is how the two end up disagreeing about what a launch actually did.
        progressListener?.onLog(message)
        launchTrace?.mark(message)
    }

    private fun createAssetManager(): AssetManager {
        val assets = AssetManager::class.java.newInstance()
        val addAssetPathMethod = AssetManager::class.java.getMethod("addAssetPath", String::class.java)

        val paths = mutableListOf<String>()
        
        if (version != null && !version.isInstalled) {
            val baseApk = File(applicationInfo.sourceDir)
            if (baseApk.exists()) {
                paths.add(applicationInfo.sourceDir)
            } else {
                mirrorLogcat('W', "Base APK for assets not found: ${applicationInfo.sourceDir}")
            }
            applicationInfo.splitSourceDirs?.forEach {
                if (File(it).exists()) {
                    paths.add(it)
                } else {
                    mirrorLogcat('W', "Split APK for assets not found: $it")
                }
            }
        } else {
            paths.add(packageContext.packageResourcePath)
            val splitPath = packageContext.packageResourcePath.replace("base.apk", "split_install_pack.apk")
            if (File(splitPath).exists()) paths.add(splitPath)
        }
        
        paths.add(context.packageResourcePath)

        paths.forEach { path ->
            try {
                addAssetPathMethod.invoke(assets, path)
            } catch (e: Exception) {
                mirrorLogcat('W', "Failed to add asset path $path: ${e.message}")
            }
        }
        return assets
    }

    private fun setupSecurityProvider() {
        try {
            java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
        } catch (e: Exception) {
            mirrorLogcat('W', "Conscrypt init failed: ${e.message}")
        }
    }

    fun resolveLibraryPath(name: String): String? {
        val libFile = File(nativeLibDir, toLibraryFileName(name))
        return if (libFile.exists() && libFile.length() > 0) {
            libFile.absolutePath
        } else {
            null
        }
    }

    /**
     * A 32-bit-only version must run as a 32-bit process end-to-end. When it is launched
     * from a 64-bit process, any 32-bit library will fail to dlopen; surface that clearly
     * instead of reporting a generic "is 32-bit instead of 64-bit" error.
     */
    private fun processBitnessMismatchDetail(e: UnsatisfiedLinkError): String? {
        val abi = getLaunchAbi()
        val is32BitAbi = abi == "armeabi-v7a" || abi == "x86"
        if (!is32BitAbi) return null
        val processIs64Bit = try {
            android.os.Process.is64Bit()
        } catch (_: Throwable) {
            false
        }
        if (!processIs64Bit) return null
        return "${e.message ?: e.javaClass.simpleName} - 32-bit version ($abi) requires a 32-bit process, " +
            "but the current process is 64-bit"
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun loadLibrary(name: String): Boolean {
        return loadLibraryDetailed(name).loaded
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun loadLibraryDetailed(name: String): LibraryLoadResult {
        val fileName = toLibraryFileName(name)
        val normalizedName = normalizeLibraryName(name)
        val startedAt = SystemClock.elapsedRealtime()

        if (systemLoadedLibs.contains(fileName)) {
            val source = if (normalizedName == "gxcore") {
                "launcher bundled bootstrap"
            } else {
                "launcher bundled library"
            }
            try {
                if (normalizedName == "gxcore") {
                    if (!NativeBridgeHelper.isGxCoreEnabled()) {
                        mirrorLogcat('W', "gxcore verification disabled by feature flag — skipping bootstrap")
                        return LibraryLoadResult(
                            normalizedName,
                            fileName,
                            source,
                            true,
                            elapsedSince(startedAt),
                            "skipped gxcore bootstrap (feature flag gxcoreEnabled=false)"
                        )
                    }
                    if (!NativeBridgeHelper.bootstrapGxCore()) {
                        val detail = "gxcore bootstrap failed"
                        mirrorLogcat('E', "Failed to load $fileName from $source: $detail")
                        return LibraryLoadResult(normalizedName, fileName, source, false, elapsedSince(startedAt), detail)
                    }
                } else {
                    System.loadLibrary(normalizedName)
                }
                return LibraryLoadResult(
                    normalizedName,
                    fileName,
                    source,
                    true,
                    elapsedSince(startedAt)
                )
            } catch (e: UnsatisfiedLinkError) {
                // Same bitness annotation the file path uses. A launcher-bundled library is
                // always the process's own ABI, so this is usually a transitive dependency of the
                // bundled lib that is 32-bit - and "dlopen failed: is 32-bit instead of 64-bit"
                // does not say which one.
                val detail = processBitnessMismatchDetail(e) ?: (e.message ?: e.javaClass.simpleName)
                mirrorLogcat('E', "Failed to load $fileName from $source: $detail")
                return LibraryLoadResult(normalizedName, fileName, source, false, elapsedSince(startedAt), detail)
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                mirrorLogcat('E', "Failed to load $fileName from $source: $detail")
                return LibraryLoadResult(normalizedName, fileName, source, false, elapsedSince(startedAt), detail)
            }
        }

        val resolvedPath = resolveLibraryPath(name)
        val libFile = resolvedPath?.let(::File) ?: File(nativeLibDir, fileName)
        val source = "Minecraft extracted library cache"
        return if (libFile.exists() && libFile.length() > 0) {
            // G3: refuse a library whose ELF architecture cannot load in this process before
            // dlopen; the raw error names neither the file nor the mismatch.
            val processIs64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
            val abiMismatch = ElfHeaderValidator.describeMismatch(libFile, processIs64Bit)
            if (abiMismatch != null) {
                val detail = abiMismatch
                mirrorLogcat('E', "Refusing to load $fileName from ${libFile.absolutePath}: $detail")
                return LibraryLoadResult(normalizedName, fileName, source, false, elapsedSince(startedAt), detail)
            }
            try {
                ensureReadOnly(libFile)
                System.load(libFile.absolutePath)
                LibraryLoadResult(normalizedName, fileName, source, true, elapsedSince(startedAt), libFile.absolutePath)
            } catch (e: UnsatisfiedLinkError) {
                val detail = processBitnessMismatchDetail(e) ?: (e.message ?: e.javaClass.simpleName)
                mirrorLogcat('E', "Failed to load $fileName from ${libFile.absolutePath}: $detail")
                LibraryLoadResult(normalizedName, fileName, source, false, elapsedSince(startedAt), detail)
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                mirrorLogcat('E', "Failed to load $fileName from ${libFile.absolutePath}: $detail")
                LibraryLoadResult(normalizedName, fileName, source, false, elapsedSince(startedAt), detail)
            }
        } else {
            val detail = "$fileName not found in $nativeLibDir"
            mirrorLogcat('W', detail)
            LibraryLoadResult(normalizedName, fileName, source, false, elapsedSince(startedAt), detail)
        }
    }

    fun loadAllLibraries(
        excludeLibs: Set<String> = emptySet(),
        trace: LaunchTrace? = null,
        listener: MinecraftRuntimePreparer.ProgressListener? = null,
        progressStart: Int = 46,
        progressEnd: Int = 74,
        excludeReasons: Map<String, String> = emptyMap()
    ): List<LibraryLoadResult> {
        val deviceSupports64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val allLibs = (requiredLibs + systemLoadedLibs).filterNot { lib ->
            // PlayFab/maesdk/gxcore are arm64-only closed-source prebuilts; on a 32-bit
            // device they aren't bundled, so exclude them up front instead of letting
            // System.loadLibrary fail mid-launch.
            systemLoadedLibs.contains(lib) && !deviceSupports64Bit
        }
        val loadableLibs = allLibs.filterNot { lib ->
            val libName = normalizeLibraryName(lib)
            excludeLibs.contains(libName) || excludeLibs.contains(lib)
        }
        val total = loadableLibs.size.coerceAtLeast(1)
        var loadIndex = 0
        val results = mutableListOf<LibraryLoadResult>()

        allLibs.forEach { lib ->
            val libName = normalizeLibraryName(lib)
            if (excludeLibs.contains(libName) || excludeLibs.contains(lib)) {
                val reason = excludeReasons[libName]
                    ?: excludeReasons[lib]
                    ?: "not required in this launch path"
                listener?.onLog("Skipped native library: $lib")
                trace?.mark("System.load skipped", "$lib - $reason")
                return@forEach
            }

            loadIndex += 1
            val progress = progressStart + ((progressEnd - progressStart) * (loadIndex - 1) / total)
            listener?.onProgress(progress, "Loading Minecraft", "$loadIndex/$total")
            listener?.onLog("Loading native library: $lib")
            trace?.mark("System.load started", lib)

            val result = loadLibraryDetailed(libName)
            results.add(result)
            val detail = formatLoadResult(result)
            trace?.mark(
                if (result.loaded) "System.load finished" else "System.load failed",
                detail
            )
            if (!result.loaded) {
                mirrorLogcat('E', "Failed to load bundle library $libName: ${result.detail ?: "unknown error"}")
                listener?.onLog("Failed to load native library: ${result.fileName}")
            } else {
                listener?.onLog("Loaded native library: ${result.fileName}")
            }
        }

        return results
    }

    private fun toLibraryFileName(name: String): String {
        return if (name.startsWith("lib") && name.endsWith(".so")) name else "lib${normalizeLibraryName(name)}.so"
    }

    private fun normalizeLibraryName(name: String): String {
        return name.removePrefix("lib").removeSuffix(".so")
    }

    private fun elapsedSince(startedAt: Long): Long {
        return SystemClock.elapsedRealtime() - startedAt
    }

    private fun formatLoadResult(result: LibraryLoadResult): String {
        val status = if (result.loaded) "Loaded" else "Failed to load"
        return buildString {
            append(status)
            append(' ')
            append(result.fileName)
            append(" in ")
            append(result.durationMs)
            append("ms from ")
            append(result.source)
            result.detail?.let {
                append(" - ")
                append(it)
            }
        }
    }

    fun getAssets(): AssetManager = assetManager

    fun getGameStringResource(name: String): String? {
        return try {
            val resources = android.content.res.Resources(
                assetManager,
                context.resources.displayMetrics,
                context.resources.configuration
            )
            val packageName = applicationInfo.packageName ?: MinecraftLauncher.MC_PACKAGE_NAME
            val identifier = resources.getIdentifier(name, "string", packageName)
            if (identifier == 0) null else resources.getString(identifier).takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            null
        }
    }

    fun getPackageContext(): Context = packageContext

    fun getApplicationInfo(): ApplicationInfo = applicationInfo

    fun getVersionName(): String? {
        return try {
            context.packageManager.getPackageInfo(packageContext.packageName, 0).versionName
        } catch (e: Exception) {
            version?.versionCode
        }
    }

    companion object {
        private const val TAG = "GamePackageManager"

        @Volatile
        private var instance: GamePackageManager? = null
        private var lastVersionKey: String? = null

        @JvmStatic
        fun getInstance(context: Context, version: GameVersion? = null): GamePackageManager {
            return getInstance(context, version, null, null)
        }

        @JvmStatic
        fun getInstance(
            context: Context,
            version: GameVersion? = null,
            launchTrace: LaunchTrace? = null,
            progressListener: MinecraftRuntimePreparer.ProgressListener? = null
        ): GamePackageManager {
            return synchronized(this) {
                val newVersionKey = buildVersionKey(version)
                val current = instance
                if (current == null || newVersionKey != lastVersionKey) {
                    val created = GamePackageManager(
                        context.applicationContext, version, launchTrace, progressListener
                    )
                    instance = created
                    lastVersionKey = newVersionKey
                    created
                } else {
                    // Reused: re-point the sinks at this launch. The instance outlives a launch, so
                    // a stale LaunchTrace would silently drop this launch's extraction milestones
                    // and a stale listener would log into the previous loading screen.
                    current.launchTrace = launchTrace
                    current.progressListener = progressListener
                    current
                }
            }
        }

        private fun buildVersionKey(version: GameVersion?): String {
            if (version == null) return "installed-default"
            return listOf(
                version.isInstalled.toString(),
                version.packageName.orEmpty(),
                version.versionCode.orEmpty(),
                version.directoryName.orEmpty(),
                version.versionDir?.absolutePath.orEmpty(),
                version.abiList.orEmpty()
            ).joinToString("|")
        }

        fun isInitialized() = instance != null

        /** Manual extractor pipeline version; bump when the extraction logic changes. */
        @JvmStatic
        fun extractorVersion(): Int = ExtractionCachePolicy.EXTRACTOR_VERSION

        /**
         * The extraction version this launcher build expects, as written to the
         * `native_extraction_version` marker. Combining the manual pipeline version with the
         * app version code means a launcher update automatically invalidates stale extracted
         * libs without a manual bump.
         */
        @JvmStatic
        fun calculateNativeExtractionVersion(): Int {
            val appVersion = try {
                BuildConfig.VERSION_CODE
            } catch (_: Throwable) {
                0
            }
            return ExtractionCachePolicy.extractionVersion(
                ExtractionCachePolicy.EXTRACTOR_VERSION,
                appVersion
            )
        }

        /**
         * True when the extraction cache for this version still holds the expected marker and
         * will be reused on the next launch instead of being wiped + re-extracted.
         */
        /**
         * True when an extraction has run for this version at all, whether or not it is current.
         *
         * <p>Distinct from [isExtractionCacheCurrent]: that answers "is the cache up to date", this
         * answers "does a cache exist". The diagnostics screen needs both to tell "not extracted
         * yet" (the launch will do it) from "extracted and damaged" (Repair is the fix), which is
         * the same distinction the repair rule draws.
         */
        /**
         * Records that [abiDirs] now hold a complete, current extraction of [version]'s libraries.
         *
         * <p>Both the manifest and the version marker are written, in the same place and with the
         * same content `extractLibraries` writes, because the reuse decision reads exactly those two
         * files. `VersionManager.repairLibsAsync` rebuilds the cache itself (it cannot call
         * `extractLibraries`, which is instance-bound and runs the whole launch preparation), and it
         * used to stop at the libraries: no marker, no manifest. The next launch then saw an
         * unrecognised cache, discarded it and re-extracted the whole package - several hundred
         * megabytes of work thrown away, on the launch the user was waiting for. Writing the same
         * record makes the repaired cache a cache hit.
         *
         * <p>Best-effort: a failure to write the marker leaves the cache unrecognised, which is the
         * previous behaviour (a re-extract), never a wrongly-trusted one.
         */
        @JvmStatic
        fun writeExtractionMarkers(
            version: GameVersion?,
            apkFiles: List<File>,
            abiDirs: List<File>
        ): Boolean {
            try {
                if (version == null || abiDirs.isEmpty()) return false
                val required = RequiredNativeLibraries.required(version.versionCode)
                val manifest = buildExtractionManifest(version, apkFiles)
                val marker = calculateNativeExtractionVersion().toString()
                var wrote = false
                for (dir in abiDirs) {
                    if (!dir.isDirectory) continue
                    // Only a directory that actually holds every required library is marked. Marking
                    // an incomplete one would make the next launch trust a cache that still cannot
                    // load, and suppress the repair prompt that would have fixed it - a false
                    // success is worse than the re-extract this replaces.
                    if (!ExtractionCachePolicy.allLibrariesPresent(required) { lib ->
                            File(dir, lib).let { it.isFile && it.length() > 0L }
                        }) {
                        continue
                    }
                    try {
                        File(dir, ".extraction_manifest").writeText(manifest)
                        File(dir, "native_extraction_version").writeText(marker)
                        wrote = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to write extraction markers in ${dir.absolutePath}: ${e.message}")
                    }
                }
                return wrote
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to write extraction markers: ${e.message}")
                return false
            }
        }

        /**
         * The ABI the extractor will target for [version], from [apkFiles].
         *
         * <p>The one implementation, shared by the instance path (`getDeviceAbi`) and the repair
         * path's manifest. It has to be shared, not copied: the manifest records this value, and the
         * reuse decision compares the recorded manifest against a freshly built one, so any drift
         * between the two builders silently turns a repaired cache back into a cache miss.
         */
        @JvmStatic
        fun resolveDeviceAbi(version: GameVersion?, apkFiles: List<File>): String {
            // The APK is the authority: it is what actually decides which libraries exist to
            // load. The version's label is a record of a previous extraction, so it is consulted
            // only when no APK could be inspected.
            val fromApk = try {
                val shipped = AbiBitness.abisInApks(apkFiles)
                if (shipped.isEmpty()) null
                else AbiBitness.selectLaunchAbi(shipped, processIs64BitStatic())
            } catch (_: Throwable) {
                null
            }
            if (fromApk != null) return fromApk
            if (version != null && "armeabi-v7a".equals(version.abiList)) {
                return if (Build.SUPPORTED_32_BIT_ABIS.contains("armeabi-v7a")) {
                    "armeabi-v7a"
                } else {
                    device64BitAbi() ?: device32BitAbi()
                        ?: (Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a")
                }
            }
            return device64BitAbi() ?: device32BitAbi()
                ?: (Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a")
        }

        private fun device64BitAbi(): String? = Build.SUPPORTED_64_BIT_ABIS.firstOrNull {
            it.contains("arm64-v8a") || it.contains("x86_64")
        }

        private fun device32BitAbi(): String? = Build.SUPPORTED_32_BIT_ABIS.firstOrNull {
            it.contains("armeabi-v7a") || it.contains("x86")
        }

        /**
         * The manifest for an explicit version, matching the instance method byte for byte.
         *
         * <p>Static so the repair path can write the same record without constructing the package
         * manager (whose initialisation performs a whole extraction).
         */
        private fun buildExtractionManifest(version: GameVersion, apkFiles: List<File>): String =
            ExtractionManifest.build(
                ExtractionCachePolicy.EXTRACTOR_VERSION,
                resolveDeviceAbi(version, apkFiles),
                NativeImageGuard.TOKEN,
                if (!version.isInstalled) "isolated" else "installed",
                shouldLoadHttpClientStatic(version),
                apkFiles.map { ExtractionManifest.ApkEntry.of(it) }
            )

        private fun shouldLoadHttpClientStatic(version: GameVersion): Boolean =
            NativeLibraryVersionGate.shouldLoadHttpClient(version.versionCode)

        private fun processIs64BitStatic(): Boolean = try {
            android.os.Process.is64Bit()
        } catch (_: Throwable) {
            Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && Build.SUPPORTED_32_BIT_ABIS.isEmpty()
        }

        @JvmStatic
        fun isExtractionAttempted(context: Context, version: GameVersion?): Boolean {
            return try {
                if (version == null) return false
                val profileId = MinecraftLauncher.getStorageProfileId(version)
                val dir = MinecraftLauncher.getRuntimeLibDir(context, profileId)
                if (!dir.isDirectory) return false
                MinecraftLauncher.repairTargetAbiDirs().any { abi ->
                    File(dir, abi).isDirectory
                }
            } catch (_: Throwable) {
                false
            }
        }

        @JvmStatic
        fun isExtractionCacheCurrent(context: Context, version: GameVersion?): Boolean {
            return try {
                if (version == null) return false
                val profileId = MinecraftLauncher.getStorageProfileId(version)
                val expected = calculateNativeExtractionVersion()
                val dir = MinecraftLauncher.getRuntimeLibDir(context, profileId)
                val abis = listOf("arm64", "arm", "x86_64", "x86")
                for (abi in abis) {
                    val versionFile = File(File(dir, abi), "native_extraction_version")
                    if (versionFile.isFile && versionFile.readText().trim() == expected.toString()) {
                        return true
                    }
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Whether a version's APK ships an ABI this process can load, without constructing the
         * package manager.
         *
         * <p>The launch preflight only needs the ABI answer, but the instance's ABI check used to go
         * through [getInstance], whose `init` block runs the whole native-library extraction and
         * builds an AssetManager. So merely asking "would this launch be refused?" performed the
         * expensive preparation work, and the launch then threw that instance away and built another
         * one - a large part of the reported lag when Launch is pressed. This reads the APK's ABI
         * table directly, which is the same authority [abiCompatibility] uses, and leaves the
         * extraction to the launch itself.
         *
         * <p>An unreadable ABI is not incompatible: [AbiBitness.isIncompatible] treats an unknown as
         * "nothing proven" so the real error is the one the user sees.
         */
        @JvmStatic
        fun versionAbiIsIncompatible(context: Context, version: GameVersion?): Boolean {
            return try {
                if (version == null) return false
                val apks = collectVersionApkFiles(context, version)
                val shipped = if (apks.isEmpty()) null else AbiBitness.abisInApks(apks)
                val processIs64Bit = try {
                    android.os.Process.is64Bit()
                } catch (_: Throwable) {
                    Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
                }
                AbiBitness.isIncompatible(shipped, processIs64Bit)
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * The ABI a launch of [version] would extract and load, without constructing the package
         * manager.
         *
         * <p>{@link #getInstance} runs the entire native-library extraction and builds an
         * AssetManager in its `init` block, so asking it this one question from a diagnostics
         * screen performed the whole preparation on the UI thread - the same defect the launch
         * preflight was fixed for. This reads the APK's own ABI table, which is the authority
         * [getDeviceAbi] uses, and mirrors its fallback order exactly, so the diagnostics screen
         * reports the ABI the launch would really use rather than "unknown".
         *
         * @return the ABI name; never null.
         */
        @JvmStatic
        fun resolveLaunchAbi(context: Context, version: GameVersion?): String? {
            return try {
                val shipped = AbiBitness.abisInApks(collectVersionApkFiles(context, version))
                if (shipped.isNotEmpty()) {
                    val processIs64Bit = try {
                        android.os.Process.is64Bit()
                    } catch (_: Throwable) {
                        Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
                    }
                    return AbiBitness.selectLaunchAbi(shipped, processIs64Bit)
                }
                // No APK could be inspected. The version's label is a record of a previous
                // extraction, so it is a hint at best; mirror getDeviceAbi's preference for an
                // ABI this process can actually load.
                if (version != null && "armeabi-v7a" == version.abiList) {
                    return if (Build.SUPPORTED_32_BIT_ABIS.contains("armeabi-v7a")) {
                        "armeabi-v7a"
                    } else {
                        Build.SUPPORTED_64_BIT_ABIS.firstOrNull {
                            it.contains("arm64-v8a") || it.contains("x86_64")
                        } ?: Build.SUPPORTED_32_BIT_ABIS.firstOrNull {
                            it.contains("armeabi-v7a") || it.contains("x86")
                        } ?: (Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a")
                    }
                }
                Build.SUPPORTED_64_BIT_ABIS.firstOrNull {
                    it.contains("arm64-v8a") || it.contains("x86_64")
                } ?: Build.SUPPORTED_32_BIT_ABIS.firstOrNull {
                    it.contains("armeabi-v7a") || it.contains("x86")
                } ?: (Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a")
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * The APK files a version's native libraries come from, without touching the extraction
         * cache. Mirrors the paths the instance's own `collectApkFiles` uses.
         */
        private fun collectVersionApkFiles(context: Context, version: GameVersion?): List<File> {
            if (version == null) return emptyList()
            val paths = mutableListOf<String>()
            if (version.isInstalled) {
                val appInfo = context.packageManager
                    .getPackageInfo(version.packageName ?: MinecraftLauncher.MC_PACKAGE_NAME, 0)
                    .applicationInfo
                appInfo?.sourceDir?.let { paths.add(it) }
                appInfo?.splitPublicSourceDirs?.let { paths.addAll(it) }
            } else if (version.versionDir != null) {
                val base = File(version.versionDir, "base.apk.chimera")
                if (base.isFile) paths.add(base.absolutePath)
                val splits = File(version.versionDir, "splits").listFiles()
                splits?.forEach {
                    if (it.isFile && it.name.endsWith(".apk.chimera")) paths.add(it.absolutePath)
                }
            }
            return paths.map { File(it) }.filter { it.exists() }
        }

        /** Removes the extraction version marker so the next launch re-extracts native libs. */
        @JvmStatic
        fun invalidateExtractionCache(context: Context, version: GameVersion?) {
            // Drop the cached manager as well as the on-disk markers. The manager extracts in its
            // `init`, so a cached instance would never re-extract: the next launch reused it, found
            // the cache "ready" from the already-populated nativeLibDir, and the repair was a
            // no-op. Discarding it here is what makes Repair (and the automatic stale-library
            // repair) actually rebuild the cache.
            synchronized(this) {
                instance = null
                lastVersionKey = null
            }
            try {
                if (version == null) return
                val profileId = MinecraftLauncher.getStorageProfileId(version)
                val dir = MinecraftLauncher.getRuntimeLibDir(context, profileId)
                // Every ABI directory the extractor can target, not just the one this version is
                // expected to use. The extraction folder is derived from the APK's shipped ABIs
                // (getDeviceAbi), while resolveLaunchAbi prefers the version's recorded abiList -
                // and that label is a record of what was extracted *last*, so it can name a
                // different directory than the extractor will actually write to. Clearing every
                // candidate is what makes the repair cover the folder in use whichever it is.
                for (abi in MinecraftLauncher.repairTargetAbiDirs()) {
                    val abiDir = File(dir, abi)
                    val versionFile = File(abiDir, "native_extraction_version")
                    val manifestFile = File(abiDir, ".extraction_manifest")
                    if (versionFile.exists() && !versionFile.delete()) {
                        Log.w(TAG, "Failed to remove extraction version marker: ${versionFile.absolutePath}")
                    }
                    if (manifestFile.exists() && !manifestFile.delete()) {
                        Log.w(TAG, "Failed to remove extraction manifest: ${manifestFile.absolutePath}")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to invalidate extraction cache: ${e.message}")
            }
        }
    }
}
