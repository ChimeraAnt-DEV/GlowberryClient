package org.chimeramc.client.core.mods;

import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.chimeramc.client.core.antegg.ModHash;
import org.chimeramc.client.core.minecraft.ElfHeaderValidator;
import org.chimeramc.client.core.minecraft.ElfLibraryReader;
import org.chimeramc.client.core.minecraft.NativeLibraryLoadOrder;

public class ModNativeLoader {
    private static final String TAG = "ModNativeLoader";
    private static final int CACHE_MANIFEST_VERSION = 4;
    private static final String DATA_DIRECTORY_NAME = "data";
    private static final String CONFIG_DIRECTORY_NAME = "config";

    private static final class CachedModEntry {
        final File sourceDirectory;
        final File targetFile;

        CachedModEntry(File sourceDirectory, File targetFile) {
            this.sourceDirectory = sourceDirectory;
            this.targetFile = targetFile;
        }
    }

    public static void loadEnabledSoMods(ModManager modManager, File cacheDir) {
        loadEnabledSoMods(modManager, cacheDir, null);
    }

    public interface LoadListener {
        void onScanStarted(int totalEnabled);
        void onModLoadStarted(Mod mod, int index, int total);
        void onModLoadFinished(Mod mod);
        void onModLoadSkipped(Mod mod, String minecraftVersion);
        void onModLoadFailed(Mod mod, Throwable error);
        void onMessage(String message);
    }

    public static void loadEnabledSoMods(ModManager modManager, File cacheDir, LoadListener listener) {
        if (modManager.getCurrentVersion() == null || modManager.getCurrentVersion().modsDir == null) {
            notifyMessage(listener, "Native mod directory is not configured");
            return;
        }
        if (cacheDir == null) {
            notifyMessage(listener, "Native mod working directory is not configured");
            return;
        }

        List<Mod> mods = modManager.getMods();
        String minecraftVersion = modManager.getCurrentVersion().versionCode;
        int totalEnabled = 0;
        for (Mod mod : mods) {
            if (mod.isEnabled()) {
                totalEnabled++;
            }
        }
        if (listener != null) {
            listener.onScanStarted(totalEnabled);
        }

        File cacheModsDir = new File(cacheDir, "mods");
        if (!cacheModsDir.exists() && !cacheModsDir.mkdirs()) {
            Log.e(TAG, "Failed to prepare native mod working directory: " + cacheModsDir.getAbsolutePath());
            notifyMessage(listener, "Failed to prepare native mod working directory: " + cacheModsDir.getAbsolutePath());
            return;
        }

        Set<String> installedModIds = new HashSet<>();
        for (Mod mod : mods) installedModIds.add(mod.getId());
        int currentIndex = 0;
        for (Mod mod : mods) {
            if (!mod.isEnabled()) {
                continue;
            }
            currentIndex++;
            if (listener != null) {
                listener.onModLoadStarted(mod, currentIndex, totalEnabled);
            }

            if (!isCompatibleWithMinecraftVersion(mod, minecraftVersion)) {
                Log.w(TAG, "Skipping incompatible mod " + mod.getDisplayName()
                        + " for Minecraft " + minecraftVersion);
                notifySkipped(listener, mod, minecraftVersion);
                continue;
            }

            // Item G12: a mod whose manifest pins a hash is refused the moment its on-disk library
            // stops matching it, before any copy reaches the loader. An unpinned mod loads as
            // before, and a pinned-but-correct mod passes through without an extra digest pass
            // (the discovery-time check already passed; this guard is defence in depth against a
            // file changed between discovery and load).
            if (!mayLoad(mod)) {
                IOException error = new IOException("refusing to load: entry does not match the "
                        + "pinned checksum in the mod manifest");
                Log.e(TAG, "Refusing to load " + mod.getDisplayName() + ": " + error.getMessage());
                notifyFailure(listener, mod, error);
                continue;
            }

            try {
                CachedModEntry cachedEntry = prepareCachedEntry(modManager, cacheModsDir, mod);
                File targetFile = cachedEntry.targetFile;
                if (targetFile == null || !targetFile.isFile()) {
                    IOException error = new IOException("Entry not found after copy: " + (targetFile == null ? "<null>" : targetFile.getAbsolutePath()));
                    Log.e(TAG, error.getMessage());
                    notifyFailure(listener, mod, error);
                    continue;
                }

                ensureReadOnly(targetFile);

                // Item G3: the entry library's ABI is checked against the process before it reaches
                // dlopen, so a 32-bit module on this arm64-only build is named and refused here
                // rather than surfacing as a bare "is 32-bit instead of 64-bit" from the linker.
                String abiMismatch = ElfHeaderValidator.describeMismatch(
                        targetFile, Build.SUPPORTED_64_BIT_ABIS.length > 0);
                if (abiMismatch != null) {
                    IOException error = new IOException("refusing to load: " + abiMismatch);
                    Log.e(TAG, "Refusing " + mod.getDisplayName() + ": " + abiMismatch);
                    notifyFailure(listener, mod, error);
                    continue;
                }

                // Item G3: a package may ship several .so files, and the entry may depend on a
                // sibling by soname. Android resolves DT_NEEDED against the loaded set and the
                // platform path, not the package directory, so the siblings are loaded first in
                // dependency order. A package with a single library is unaffected.
                try {
                    loadLibraryDependencies(packageRootOf(targetFile, mod.getEntryPath()), targetFile, mod);
                } catch (IOException dependencyError) {
                    Log.e(TAG, "Can't load dependencies of " + mod.getDisplayName() + ": "
                            + dependencyError.getMessage(), dependencyError);
                    notifyFailure(listener, mod, dependencyError);
                    continue;
                }

                System.load(targetFile.getAbsolutePath());

                if (ModManager.ensurePreloaderLoaded()) {
                    if (!ModManager.initializeLoadedMod(
                            targetFile.getAbsolutePath(),
                            cachedEntry.sourceDirectory.getAbsolutePath(),
                            mod)) {
                        Log.e(TAG, "Failed to finish native initialization for " + mod.getDisplayName());
                        notifyFailure(listener, mod, new UnsatisfiedLinkError("Failed to finish native initialization"));
                        continue;
                    }
                }
                if (listener != null) {
                    listener.onModLoadFinished(mod);
                }
            } catch (IOException | UnsatisfiedLinkError e) {
                Log.e(TAG, "Can't load " + mod.getDisplayName() + ": " + e.getMessage(), e);
                notifyFailure(listener, mod, e);
            }
        }

        pruneStaleCachedMods(cacheModsDir, installedModIds);
    }

    public static boolean isCompatibleWithMinecraftVersion(Mod mod, String minecraftVersion) {
        if (mod == null) {
            return true;
        }
        return isCompatibleWithMinecraftVersion(mod.getMinecraftVersions(), minecraftVersion);
    }

    public static boolean isCompatibleWithMinecraftVersion(List<String> patterns, String minecraftVersion) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }

        boolean hasValidPattern = false;
        for (String pattern : patterns) {
            if (pattern == null) {
                continue;
            }

            String normalizedPattern = pattern.trim();
            if (normalizedPattern.isEmpty()) {
                continue;
            }

            hasValidPattern = true;
            if (matchesMinecraftVersionPattern(normalizedPattern, minecraftVersion)) {
                return true;
            }
        }
        return !hasValidPattern;
    }

    public static boolean matchesMinecraftVersionPattern(String pattern, String minecraftVersion) {
        if (pattern == null || minecraftVersion == null) {
            return false;
        }

        String normalizedPattern = pattern.trim();
        String normalizedVersion = minecraftVersion.trim();
        if (normalizedPattern.isEmpty() || normalizedVersion.isEmpty()) {
            return false;
        }

        int wildcardIndex = normalizedPattern.indexOf('*');
        if (wildcardIndex < 0) {
            return normalizedVersion.equals(normalizedPattern);
        }

        String prefix = normalizedPattern.substring(0, wildcardIndex);
        return normalizedVersion.startsWith(prefix);
    }

    private static void notifySkipped(LoadListener listener, Mod mod, String minecraftVersion) {
        if (listener != null) {
            listener.onModLoadSkipped(mod, minecraftVersion);
        }
    }

    private static void notifyFailure(LoadListener listener, Mod mod, Throwable error) {
        if (listener != null) {
            listener.onModLoadFailed(mod, error);
        }
    }

    /**
     * Hash-pinning gate (item G12): a mod whose manifest pins a SHA-256 loads only while the
     * on-disk entry still matches the pin. Unpinned mods always load; the discovery-time
     * verification already passed for pinned-and-correct mods, so they are never re-hashed here.
     */
    static boolean mayLoad(Mod mod) {
        return mod.getDeclaredSha256().isEmpty() || mod.isVerified();
    }

    private static void notifyMessage(LoadListener listener, String message) {
        if (listener != null) {
            listener.onMessage(message);
        }
    }

    /**
     * Loads a package's other shared libraries before the entry library, in dependency order
     * (item G3).
     *
     * <p>Android's linker resolves a library's {@code DT_NEEDED} entries by soname against the
     * already-loaded set and the platform search path, not against the directory the file sits in.
     * A mod that ships {@code libentry.so} plus {@code libhelper.so} therefore fails at
     * {@code dlopen} with a bare "library not found" even though the file is present, unless the
     * helper is loaded first. This reads each sibling's {@code DT_SONAME}/{@code DT_NEEDED} and
     * loads them dependencies-first; the entry itself is excluded (the caller loads it).
     *
     * <p>A single-library package collects one file and returns without loading anything, so the
     * ordinary case is untouched. A library that is not a readable ELF, or that declares no
     * dynamic section, is loaded in file order rather than dropped, because a statically linked
     * sibling may still be needed and only the linker can say otherwise.
     */
    private static void loadLibraryDependencies(File targetDirectory, File entryFile, Mod mod)
            throws IOException {
        List<File> libraries = new ArrayList<>();
        collectSharedLibraries(targetDirectory, libraries);
        if (libraries.size() <= 1) {
            return;
        }

        String entryPath = entryFile.getAbsolutePath();
        List<NativeLibraryLoadOrder.Entry> entries = new ArrayList<>();
        Map<String, File> byFileName = new HashMap<>();
        for (File library : libraries) {
            if (library.getAbsolutePath().equals(entryPath)) {
                continue;
            }
            String name = library.getName();
            byFileName.put(name, library);
            ElfLibraryReader.DynamicInfo info = ElfLibraryReader.read(library);
            entries.add(new NativeLibraryLoadOrder.Entry(name,
                    info == null ? null : info.getSoname(),
                    info == null ? Collections.<String>emptyList() : info.getNeeded()));
        }

        for (String fileName : NativeLibraryLoadOrder.order(entries)) {
            File library = byFileName.get(fileName);
            if (library == null) {
                continue;
            }
            String mismatch = ElfHeaderValidator.describeMismatch(
                    library, Build.SUPPORTED_64_BIT_ABIS.length > 0);
            if (mismatch != null) {
                throw new IOException("dependency of " + mod.getDisplayName()
                        + " cannot load here: " + mismatch);
            }
            try {
                System.load(library.getAbsolutePath());
            } catch (UnsatisfiedLinkError e) {
                throw new IOException("failed to load dependency " + fileName + " of "
                        + mod.getDisplayName() + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * The package root of the entry library: the target file's directory with the entry's own
     * relative directory removed, so a mod whose entry sits in a subdirectory still has its
     * siblings found at the package root rather than only beside the entry.
     */
    private static File packageRootOf(File entryFile, String entryPath) {
        File directory = entryFile.getParentFile();
        if (directory == null || entryPath == null) {
            return directory;
        }
        String normalized = entryPath.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        int depth = 0;
        for (int i = 0; i < (lastSlash < 0 ? 0 : lastSlash); i++) {
            if (normalized.charAt(i) == '/') {
                depth++;
            }
        }
        File root = directory;
        for (int i = 0; i < depth && root != null; i++) {
            root = root.getParentFile();
        }
        return root == null ? directory : root;
    }

    /** Every {@code .so} in the package, sorted by relative path so the order is deterministic. */
    private static void collectSharedLibraries(File root, List<File> out) throws IOException {
        List<File> files = new ArrayList<>();
        collectFiles(root, files);
        files.sort(Comparator.comparing(file -> relativePath(root, file)));
        for (File file : files) {
            if (file.getName().endsWith(".so")) {
                out.add(file);
            }
        }
    }

    private static CachedModEntry prepareCachedEntry(ModManager modManager, File cacheModsDir, Mod mod) throws IOException {
        File sourceDirectory = new File(modManager.getCurrentVersion().modsDir, mod.getId());
        if (!sourceDirectory.isDirectory()) {
            throw new IOException("Mod package directory does not exist: " + sourceDirectory.getAbsolutePath());
        }

        File targetDirectory = new File(cacheModsDir, mod.getId());
        String sourceFingerprint = buildManifest(sourceDirectory, mod);
        File manifestFile = new File(targetDirectory, ".mod_cache_manifest");
        if (targetDirectory.isDirectory() && manifestFile.isFile()) {
            String cached = readFile(manifestFile);
            File targetFile = new File(targetDirectory, mod.getEntryPath());
            if (sourceFingerprint.equals(cached) && targetFile.isFile() && targetFile.length() > 0) {
                ensureReadOnly(targetFile);
                return new CachedModEntry(sourceDirectory, targetFile);
            }
        }

        File tempDirectory = new File(cacheModsDir, mod.getId() + ".tmp");
        if (tempDirectory.exists() && !deleteRecursively(tempDirectory)) {
            throw new IOException("Failed to clear temporary mod directory: " + tempDirectory.getAbsolutePath());
        }
        copyPackageDirectory(sourceDirectory, tempDirectory);
        writeManifest(new File(tempDirectory, ".mod_cache_manifest"), sourceFingerprint);

        if (targetDirectory.exists() && !deleteRecursively(targetDirectory)) {
            deleteRecursively(tempDirectory);
            throw new IOException("Failed to prepare native mod directory: " + targetDirectory.getAbsolutePath());
        }
        if (!tempDirectory.renameTo(targetDirectory)) {
            deleteRecursively(tempDirectory);
            throw new IOException("Failed to prepare native mod directory: " + targetDirectory.getAbsolutePath());
        }

        File targetFile = new File(targetDirectory, mod.getEntryPath());
        ensureReadOnly(targetFile);
        return new CachedModEntry(sourceDirectory, targetFile);
    }

    private static String readFile(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeManifest(File file, String data) throws IOException {
        ensureParentDirectory(file);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(data);
        }
    }

    private static String buildManifest(File sourceDirectory, Mod mod) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("loader=").append(CACHE_MANIFEST_VERSION).append('\n');
        sb.append("id=").append(mod.getId()).append('\n');
        sb.append("entry=").append(mod.getEntryPath()).append('\n');

        List<File> files = new ArrayList<>();
        collectFiles(sourceDirectory, files);
        files.sort(Comparator.comparing(file -> relativePath(sourceDirectory, file)));
        for (File file : files) {
            sb.append("file=")
                    .append(relativePath(sourceDirectory, file))
                    .append('|')
                    .append(file.length())
                    .append('|')
                    .append(file.lastModified())
                    .append('\n');
        }
        return sb.toString();
    }

    private static void collectFiles(File root, List<File> files) throws IOException {
        File[] children = root.listFiles();
        if (children == null) {
            throw new IOException("Failed to list mod directory: " + root.getAbsolutePath());
        }

        for (File child : children) {
            if (isRuntimeDirectory(root, child)) {
                continue;
            }
            if (child.isDirectory()) {
                collectFiles(child, files);
            } else if (child.isFile()) {
                files.add(child);
            }
        }
    }

    private static String relativePath(File root, File file) {
        String rootPath = root.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        String relative = filePath.startsWith(rootPath)
                ? filePath.substring(rootPath.length())
                : file.getName();
        while (relative.startsWith(File.separator)) {
            relative = relative.substring(1);
        }
        return relative.replace(File.separatorChar, '/');
    }

    private static void copyFile(File src, File dst) throws IOException {
        ensureParentDirectory(dst);
        if (dst.exists() && !dst.delete()) {
            throw new IOException("Failed to replace existing file: " + dst.getAbsolutePath());
        }

        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[262144];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }

        ensureReadOnly(dst);
    }

    private static void copyPackageDirectory(File src, File dst) throws IOException {
        copyDirectory(src, dst, src);
    }

    private static void copyDirectory(File src, File dst, File root) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("Failed to create directory: " + dst.getAbsolutePath());
            }

            File[] children = src.listFiles();
            if (children == null) {
                return;
            }

            for (File child : children) {
                if (isRuntimeDirectory(root, child)) {
                    continue;
                }
                copyDirectory(child, new File(dst, child.getName()), root);
            }
            return;
        }

        copyFile(src, dst);
    }

    private static boolean isRuntimeDirectory(File root, File file) {
        if (!file.isDirectory()) {
            return false;
        }

        File parent = file.getParentFile();
        if (parent == null || !parent.equals(root)) {
            return false;
        }

        String name = file.getName();
        return DATA_DIRECTORY_NAME.equals(name) || CONFIG_DIRECTORY_NAME.equals(name);
    }

    private static void ensureParentDirectory(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create parent directory: " + parent.getAbsolutePath());
        }
    }

    private static void ensureReadOnly(File file) throws IOException {
        if (!file.isFile()) {
            throw new IOException("Expected regular file: " + file.getAbsolutePath());
        }

        if (!file.canRead() && !file.setReadable(true, true)) {
            throw new IOException("Failed to mark file readable: " + file.getAbsolutePath());
        }

        if (file.canWrite() && !file.setReadOnly()) {
            throw new IOException("Failed to keep file read-only: " + file.getAbsolutePath());
        }
    }

    private static void pruneStaleCachedMods(File cacheModsDir, Set<String> installedModIds) {
        File[] cachedEntries = cacheModsDir.listFiles(File::isDirectory);
        if (cachedEntries == null) {
            return;
        }

        for (File cachedEntry : cachedEntries) {
            if (installedModIds.contains(cachedEntry.getName())) {
                continue;
            }
            if (cachedEntry.getName().endsWith(".tmp")) {
                deleteRecursively(cachedEntry);
                continue;
            }

            deleteRecursively(cachedEntry);
        }
    }

    private static boolean deleteRecursively(File file) {
        if (!file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }
}
