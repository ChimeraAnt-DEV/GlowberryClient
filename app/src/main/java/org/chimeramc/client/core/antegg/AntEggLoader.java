package org.chimeramc.client.core.antegg;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.chimeramc.client.core.minecraft.ElfHeaderValidator;
import org.chimeramc.client.core.mods.Mod;
import org.chimeramc.client.core.mods.ModLoadDiagnostics;
import org.chimeramc.client.core.mods.ModManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Loads {@code .AntEgg} packages: validate, extract to a sandbox, then hand the entry point to the
 * right runtime.
 *
 * <p>This is the class the launcher UI calls. The two runtimes are deliberately different seams:
 *
 * <ul>
 *   <li><b>native</b> — the extracted {@code .so} is passed by absolute path to the preloader's
 *       existing native-mod injection entry point ({@link ModManager#initializeLoadedMod}), the
 *       same call an ordinary native mod uses. .AntEgg adds packaging and validation, not a second
 *       injection mechanism. (The injection API is referred to in the original spec as
 *       "ApexAntLamina"; in this tree it is the {@code pl} preloader bridge — see AGENTS.md, there
 *       is no class or symbol by the other name here.)</li>
 *   <li><b>script</b> — the entry {@code .lua} file is executed by the embedded Lua VM
 *       ({@link AntEggScriptHost}).</li>
 * </ul>
 *
 * <p>Every failure is recorded through {@link ModLoadDiagnostics} so the Mods tab can show why an
 * import did not take, rather than the file silently not appearing.
 */
public final class AntEggLoader {

    private static final String TAG = "AntEggLoader";
    /** Sandbox root: {@code getExternalFilesDir()/AntEggs/}, per the format spec. */
    public static final String SANDBOX_DIR_NAME = "AntEggs";

    private AntEggLoader() {}

    /** What happened to one package. */
    public static final class LoadResult {
        public final boolean success;
        public final AntEggManifest manifest;
        public final File directory;
        public final String error;

        private LoadResult(boolean success, AntEggManifest manifest, File directory, String error) {
            this.success = success;
            this.manifest = manifest;
            this.directory = directory;
            this.error = error;
        }

        static LoadResult ok(AntEggManifest manifest, File directory) {
            return new LoadResult(true, manifest, directory, null);
        }

        public static LoadResult fail(String error) {
            return new LoadResult(false, null, null, error);
        }
    }

    /** The sandbox root, falling back to internal storage when external is unavailable. */
    public static File sandboxRoot(Context context) {
        File external = context.getExternalFilesDir(null);
        File base = external != null ? external : context.getFilesDir();
        return new File(base, SANDBOX_DIR_NAME);
    }

    /**
     * Imports and loads a package.
     *
     * <p>Extraction happens first and completely; a package that fails validation is never
     * extracted, and a package that fails to load is never removed — it stays in the sandbox so a
     * later fix (a rebuilt {@code .so}, a corrected dependency) can retry without re-importing.
     */
    public static LoadResult loadMod(Context context, File packageFile) {
        if (context == null) {
            return LoadResult.fail("no application context");
        }
        if (packageFile == null || !packageFile.isFile()) {
            return LoadResult.fail("package file does not exist");
        }
        if (!AntEggPackage.looksLikeAntEgg(packageFile.getName())) {
            return LoadResult.fail("not an .AntEgg package: " + packageFile.getName());
        }

        File sandbox = sandboxRoot(context);
        if (!sandbox.isDirectory() && !sandbox.mkdirs()) {
            return fail(context, packageFile.getName(),
                    LoadResult.fail("could not create the AntEggs sandbox"));
        }

        AntEggPackage.Result extracted = AntEggPackage.extract(packageFile, sandbox);
        if (!extracted.isValid()) {
            Log.w(TAG, "Rejected " + packageFile.getName() + ": " + extracted.error);
            return fail(context, packageFile.getName(), LoadResult.fail(extracted.error));
        }
        AntEggManifest manifest = extracted.manifest;

        // Item G12: a manifest that pins a sha256 must be verified. The pin is the hash of the
        // entry-point bytes (the .so or .lua), not the whole archive — the manifest lives inside
        // the archive, so a whole-package pin would be circular (editing the pin changes the
        // bytes it claims to verify). Verifying the entry file keeps the check non-circular and
        // identical in shape to the native-mod manifest.json pin. A mismatch is a hard refusal
        // recorded as KIND_HASH_MISMATCH: a package whose entry differs from what the author
        // pinned is not the package the author shipped.
        if (!manifest.sha256.isEmpty()) {
            File entryFile = new File(extracted.directory, manifest.entryPoint);
            if (!entryFile.isFile()) {
                return fail(context, manifest.name, "entry point is missing: " + manifest.entryPoint,
                        ModLoadDiagnostics.KIND_HASH_MISMATCH);
            }
            if (!ModHash.matches(entryFile, manifest.sha256)) {
                String reason = "entry file does not match the manifest pin (expected sha256 "
                        + manifest.sha256 + ")";
                Log.w(TAG, "Rejected " + packageFile.getName() + ": " + reason);
                return fail(context, manifest.name, reason,
                        ModLoadDiagnostics.KIND_HASH_MISMATCH);
            }
        }

        String dependencyError = missingDependencies(sandbox, manifest);
        if (dependencyError != null) {
            return fail(context, manifest.name, LoadResult.fail(dependencyError));
        }

        if (manifest.isNative()) {
            return loadNative(context, manifest, extracted.directory);
        }
        if (manifest.isScript()) {
            return fail(context, manifest.name, loadScript(manifest, extracted.directory));
        }
        return fail(context, manifest.name,
                LoadResult.fail("unsupported mod type: " + manifest.type));
    }

    private static LoadResult loadNative(Context context, AntEggManifest manifest, File directory) {
        File library = new File(directory, manifest.entryPoint);
        if (!library.isFile()) {
            return LoadResult.fail("native entry point is missing: " + manifest.entryPoint);
        }
        // Item G3: an .AntEgg native entry is held to the same ABI gate as an ordinary native mod,
        // so a 32-bit module on this arm64-only build is named and refused rather than failing later
        // inside the injector with a bare linker message.
        String abiMismatch = ElfHeaderValidator.describeMismatch(
                library, Build.SUPPORTED_64_BIT_ABIS.length > 0);
        if (abiMismatch != null) {
            return fail(context, manifest.name, "refusing to load: " + abiMismatch,
                    ModLoadDiagnostics.KIND_INCOMPATIBLE);
        }
        Mod mod = new Mod(manifest.id(), library.getName(), manifest.entryPoint, manifest.name,
                Collections.emptyList(), manifest.author, manifest.version, null, null,
                manifest.description, directory.getAbsolutePath(),
                new File(directory, "config").getAbsolutePath(), false, 0, true, 0);
        boolean loaded = ModManager.initializeLoadedMod(
                library.getAbsolutePath(), directory.getAbsolutePath(), mod);
        if (!loaded) {
            return LoadResult.fail("native injection failed for " + manifest.name);
        }
        return LoadResult.ok(manifest, directory);
    }

    private static LoadResult loadScript(AntEggManifest manifest, File directory) {
        AntEggScriptHost.Result result = AntEggScriptHost.run(directory, manifest);
        if (!result.success) {
            return LoadResult.fail(result.error);
        }
        return LoadResult.ok(manifest, directory);
    }

    /**
     * Returns a human-readable reason the manifest's dependencies are unmet, or null when they are.
     *
     * <p>Each dependency is parsed as a {@link DependencyRef}: a plain mod id, or an id with an
     * inline version range (item G4). A missing id and a present id whose version does not satisfy
     * the range are both reported, so the author (and the launcher's error UI) knows which half
     * failed. A dependency whose range cannot be parsed is treated as a syntax error rather than
     * silently accepted, because accepting it would admit a manifest the format cannot express.
     */
    static String missingDependencies(File sandbox, AntEggManifest manifest) {
        if (manifest.dependencies.isEmpty()) return null;
        List<String> installed = new ArrayList<>();
        for (String id : AntEggPackage.installedIds(sandbox)) {
            installed.add(id.toLowerCase(Locale.ROOT));
        }
        List<String> missing = new ArrayList<>();
        List<String> versionMismatches = new ArrayList<>();
        for (String dependency : manifest.dependencies) {
            DependencyRef ref = DependencyRef.parse(dependency);
            if (ref == null) {
                // A dependency that does not parse cannot be satisfied; name it so the author
                // can fix the manifest rather than stare at a silent dlopen failure.
                missing.add("\"" + dependency + "\" (malformed)");
                continue;
            }
            if (!installed.contains(ref.id)) {
                missing.add(ref.describe());
                continue;
            }
            if (ref.range != null) {
                String installedVersion = AntEggPackage.versionOfInstalled(sandbox, ref.id);
                if (installedVersion == null) {
                    missing.add(ref.describe() + " (version unreadable)");
                    continue;
                }
                if (!ref.range.matches(installedVersion)) {
                    versionMismatches.add(ref.id + " needs " + ref.range.describe()
                            + ", installed is " + installedVersion);
                }
            }
        }
        StringBuilder out = new StringBuilder();
        if (!missing.isEmpty()) {
            out.append("missing dependencies: ").append(String.join(", ", missing));
        }
        if (!versionMismatches.isEmpty()) {
            if (out.length() > 0) out.append("; ");
            out.append("dependency version mismatch: ").append(String.join("; ", versionMismatches));
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static LoadResult fail(Context context, String modName, LoadResult result) {
        if (!result.success) {
            recordFailure(context, modName, result.error, ModLoadDiagnostics.KIND_DLOPEN);
        }
        return result;
    }

    private static LoadResult fail(Context context, String modName, String reason, String kind) {
        return fail(context, modName, LoadResult.fail(reason), kind);
    }

    private static LoadResult fail(Context context, String modName, LoadResult result, String kind) {
        if (!result.success) {
            recordFailure(context, modName, result.error, kind);
        }
        return result;
    }

    /**
     * Records a failed import in the launch diagnostics so the Mods tab can show it.
     *
     * <p>Diagnostics are a whole-file replace, so the existing set is read, the new failure
     * prepended, and the result written back. A failure to persist the note must never fail the
     * import itself, hence the catch.
     */
    public static void recordFailure(Context context, String modName, String reason) {
        recordFailure(context, modName, reason, ModLoadDiagnostics.KIND_DLOPEN);
    }

    public static void recordFailure(Context context, String modName, String reason, String kind) {
        if (context == null) return;
        try {
            List<ModLoadDiagnostics.Record> records = new ArrayList<>(ModLoadDiagnostics.getAll(context));
            records.add(0, new ModLoadDiagnostics.Record(
                    AntEggManifest.slug(modName), modName, "0", reason,
                    kind, System.currentTimeMillis()));
            ModLoadDiagnostics.record(context, records);
        } catch (Throwable t) {
            Log.w(TAG, "Could not record AntEgg load failure", t);
        }
    }
}
