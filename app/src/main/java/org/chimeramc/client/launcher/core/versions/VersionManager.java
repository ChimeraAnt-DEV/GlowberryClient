package org.chimeramc.client.core.versions;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.chimeramc.client.R;
import org.chimeramc.client.core.minecraft.AbiBitness;
import org.chimeramc.client.core.minecraft.MinecraftLauncher;
import org.chimeramc.client.core.mods.ModManager;
import org.chimeramc.client.ui.activities.MainActivity;
import org.chimeramc.client.ui.dialogs.CustomAlertDialog;
import org.chimeramc.client.ui.dialogs.LibsRepairDialog;
import org.chimeramc.client.util.ApkUtils;
import org.chimeramc.client.util.LauncherStorage;
import org.levimc.launcher.util.NativeImageGuard;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.chimeramc.client.launcher.core.minecraft.UserFacingError;

public class VersionManager {
    private static final String TAG = "VersionManager";
    private static final String PREFS_NAME = "version_manager";
    private static final String KEY_SELECTED_TYPE = "selected_type";
    private static final String KEY_SELECTED_PACKAGE = "selected_package";
    private static final String KEY_SELECTED_DIR = "selected_dir";
    private static final int BUFFER_SIZE = 131072;
    private static final int PROGRESS_MAX = 100;
    private static final int PROGRESS_EXTRACT_MAX = 92;
    private static final int PROGRESS_FINALIZING = 96;

    private static volatile VersionManager instance;
    private static boolean libsRepairFlowActive;
    private final Context context;
    private final List<GameVersion> installedVersions = new ArrayList<>();
    private final List<GameVersion> customVersions = new ArrayList<>();
    private GameVersion selectedVersion;
    private final SharedPreferences prefs;
    private final VersionProfileMetadataStore metadataStore = new VersionProfileMetadataStore();

    public interface LibsRepairCallback {
        void onRepairStarted();

        void onRepairProgress(int progress);

        void onRepairCompleted(boolean success);

        void onRepairFailed(Exception e);
    }

    public interface OnDeleteVersionCallback {
        void onDeleteCompleted(boolean success);

        void onDeleteFailed(Exception e);
    }

    public interface OnRenameVersionCallback {
        void onRenameCompleted(boolean success);

        void onRenameFailed(Exception e);
    }

    public interface InitCallback {
        void onReady(VersionManager manager);

        /**
         * Called when the manager could not be built at all.
         *
         * <p>Building it scans the instances directory and reads each version's metadata, so it can
         * fail on an unreadable or corrupt store. Without this the caller waited forever: the
         * screen disables its Launch control until {@link #onReady} arrives, and a swallowed
         * failure meant that never happened. A default no-op keeps existing callers source
         * compatible.
         */
        default void onFailed(Throwable error) {
        }
    }

    public static VersionManager get(Context ctx) {
        VersionManager result = instance;
        if (result == null) {
            synchronized (VersionManager.class) {
                result = instance;
                if (result == null) {
                    instance = result = new VersionManager(ctx.getApplicationContext());
                }
            }
        }
        return result;
    }

    public static VersionManager getIfInitialized() {
        return instance;
    }

    public static void initializeAsync(Context ctx, InitCallback callback) {
        Context appContext = ctx.getApplicationContext();
        new Thread(() -> {
            // The build is disk I/O over the instances directory, so it can fail. A failure has to
            // reach the caller: the screen that started this disabled its Launch control and only
            // re-arms it on a callback, so an uncaught throw would strand it disabled with no
            // message. Reported through onFailed rather than swallowed.
            try {
                VersionManager manager = get(appContext);
                if (callback != null) {
                    callback.onReady(manager);
                }
            } catch (Throwable error) {
                android.util.Log.e(TAG, "Version manager initialisation failed", error);
                if (callback != null) {
                    callback.onFailed(error);
                }
            }
        }, "version-manager-init").start();
    }

    public static String getSelectedModsDir(Context ctx) {
        GameVersion v = get(ctx).getSelectedVersion();
        if (v == null || v.modsDir == null) return null;
        return v.modsDir.getAbsolutePath();
    }

    private VersionManager(Context ctx) {
        this.context = ctx;
        this.prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadAllVersions();
    }

    private String inferAbiFromNativeLibDir(String nativeLibDir, GameVersion version) {
        if (version != null && !version.isInstalled) {
            // Read the version's own APK(s) first. The extraction folder below only ever
            // holds the one ABI that was extracted last, so using it alone mislabels a
            // dual-ABI version as 32-bit forever after a single 32-bit extraction, and the
            // preflight then refuses to launch a version that could have run.
            String fromApk = inferAbiFromVersionApks(version);
            if (fromApk != null) return fromApk;

            File libDir = getRuntimeLibDir(version.directoryName);
            File flatSo = new File(libDir, "libminecraftpe.so");
            if (flatSo.exists()) {
                String elfAbi = detectElfAbi(flatSo);
                if (elfAbi != null) return elfAbi;
                return "unknown";
            }
            String[] abiDirs = {"arm64", "arm", "x86_64", "x86"};
            for (String abiDir : abiDirs) {
                File soFile = new File(libDir, abiDir + "/libminecraftpe.so");
                if (soFile.exists()) {
                    String elfAbi = detectElfAbi(soFile);
                    if (elfAbi != null) return elfAbi;
                    return switch (abiDir) {
                        case "arm64" -> "arm64-v8a";
                        case "arm" -> "armeabi-v7a";
                        default -> abiDir;
                    };
                }
            }
            return "unknown";
        }
        if (nativeLibDir == null) return "unknown";
        if (nativeLibDir.contains("arm64")) return "arm64-v8a";
        if (nativeLibDir.contains("armeabi")) return "armeabi-v7a";
        if (nativeLibDir.contains("x86_64")) return "x86_64";
        if (nativeLibDir.contains("x86")) return "x86";
        return "unknown";
    }

    /**
     * The ABI to label this version with, read from the native libraries its APK(s) ship.
     *
     * Where a version ships more than one ABI, the one matching the running process is
     * reported, because that is the one a launch would actually extract and load.
     *
     * Returns null when no APK could be read, so callers can fall back to the extraction
     * folder rather than treating an unreadable archive as "no ABI".
     */
    private String inferAbiFromVersionApks(GameVersion version) {
        if (version == null || version.versionDir == null) return null;
        List<File> apks = new ArrayList<>();
        File base = new File(version.versionDir, "base.apk.chimera");
        if (base.isFile()) apks.add(base);
        File splits = new File(version.versionDir, "splits");
        File[] splitFiles = splits.listFiles();
        if (splitFiles != null) {
            for (File f : splitFiles) {
                if (f.isFile() && f.getName().endsWith(".apk.chimera")) apks.add(f);
            }
        }
        if (apks.isEmpty()) return null;

        List<String> shipped = AbiBitness.INSTANCE.abisInApks(apks);
        if (shipped.isEmpty()) return null;

        boolean processIs64Bit;
        try {
            processIs64Bit = android.os.Process.is64Bit();
        } catch (Throwable t) {
            processIs64Bit = Build.SUPPORTED_64_BIT_ABIS.length > 0;
        }
        String chosen = AbiBitness.INSTANCE.selectLaunchAbi(shipped, processIs64Bit);
        return chosen != null ? chosen : shipped.get(0);
    }

    private static String detectElfAbi(File soFile) {
        try (FileInputStream fis = new FileInputStream(soFile)) {

            byte[] hdr = new byte[20];
            int n = fis.read(hdr);
            if (n < 5) return null;
            if (hdr[0] != 0x7F || hdr[1] != 'E' || hdr[2] != 'L' || hdr[3] != 'F') return null;
            int elfClass = hdr[4]; // 1 = ELFCLASS32 2 = ELFCLASS64
            if (n < 20) {
                return elfClass ==  1 ? "armeabi-v7a" : elfClass ==  2 ? "arm64-v8a" : null;
            }
            int machine = (hdr[18] & 0xFF) | ((hdr[19] & 0xFF) << 8);
            if (elfClass == 1) {
                return machine ==  3 ? "x86" : "armeabi-v7a";
            } else if (elfClass ==  2) {
                return machine ==  62 ? "x86_64" : "arm64-v8a";
            }
            return null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private String getApkVersionName(File apkFile) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (pi != null && pi.versionName != null) {
                return pi.versionName;
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private VersionProfileMetadata loadMetadata(
            String profileId,
            VersionProfileMetadataStore.Defaults defaults
    ) {
        return loadMetadata(LauncherStorage.getProfileMetadataDir(context, profileId), defaults);
    }

    private VersionProfileMetadata loadMetadata(
            File metadataDir,
            VersionProfileMetadataStore.Defaults defaults
    ) {
        try {
            return metadataStore.loadOrCreate(metadataDir, defaults);
        } catch (IOException ignored) {
            VersionProfileMetadata metadata = new VersionProfileMetadata();
            metadata.profileId = defaults.profileId;
            metadata.directoryName = defaults.directoryName;
            metadata.versionName = defaults.versionName;
            metadata.displayName = defaults.displayName;
            metadata.versionIsolation = defaults.versionIsolation;
            metadata.launchVertically = defaults.launchVertically;
            metadata.installed = defaults.installed;
            metadata.packageName = defaults.packageName;
            return metadata;
        }
    }

    private VersionProfileMetadataStore.Defaults metadataDefaults(
            boolean installed,
            String directoryName,
            String packageName,
            String versionName
    ) {
        if (installed) {
            return VersionProfileMetadataStore.Defaults.installed(packageName, versionName);
        }
        return VersionProfileMetadataStore.Defaults.custom(directoryName, versionName);
    }

    private static String safeValue(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    public void repairLibsAsync(GameVersion version, LibsRepairCallback callback) {
        new Thread(() -> {
            callback.onRepairStarted();
            try {
                File versionDir = version.versionDir;
                boolean onlyVersionTxt = version.onlyVersionTxt;
                boolean isExtractFalse = version.isExtractFalse;

                String dirName = versionDir.getName();

                File apkFile;
                if (isExtractFalse) {
                    ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(version.packageName, 0);
                    apkFile = new File(appInfo.sourceDir);
                } else {
                    apkFile = new File(versionDir, "base.apk.chimera");
                }

                String dataDirName = isExtractFalse ? version.directoryName : dirName;
                if (onlyVersionTxt) {
                    callback.onRepairProgress(PROGRESS_FINALIZING);
                    writeVersionMetadata(apkFile, version, dataDirName);
                    loadAllVersions();
                    callback.onRepairProgress(PROGRESS_MAX);
                    callback.onRepairCompleted(true);
                    return;
                }

                List<File> apkFiles = new ArrayList<>();
                apkFiles.add(apkFile);

                if (!isExtractFalse) {
                    File splitsDir = new File(versionDir, "splits");
                    if (splitsDir.exists() && splitsDir.isDirectory()) {
                        File[] splitApks = splitsDir.listFiles((dir, name) -> name.endsWith(".apk.chimera"));
                        if (splitApks != null) {
                            for (File splitApk : splitApks) {
                                apkFiles.add(splitApk);
                            }
                        }
                    }
                }

                long totalSize = 0;
                for (File apk : apkFiles) {
                    totalSize += getLibsTotalSize(apk);
                }
                if (totalSize == 0) totalSize = 1;

                File libDir = getRuntimeLibDir(dataDirName);
                if (libDir.exists()) {
                    deleteDir(libDir);
                }
                long[] progress = {0};
                int[] lastPercent = {-1};

                for (File currentApk : apkFiles) {
                    try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(currentApk)))) {
                        ZipEntry entry;
                        byte[] buffer = new byte[BUFFER_SIZE];
                        while ((entry = zis.getNextEntry()) != null) {
                            if (entry.getName().startsWith("lib/") && !entry.isDirectory()) {
                                String[] parts = entry.getName().split("/");
                                if (parts.length >= 3) {
                                    File outFile = new File(libDir, ApkUtils.abiToSystemLibDir(parts[1]) + "/" + parts[2]);
                                    if (!outFile.getParentFile().exists()) outFile.getParentFile().mkdirs();

                                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                        int len;
                                        while ((len = zis.read(buffer)) != -1) {
                                            fos.write(buffer, 0, len);
                                            progress[0] += len;
                                            int percent = (int) Math.min(PROGRESS_EXTRACT_MAX, (progress[0] * PROGRESS_EXTRACT_MAX) / totalSize);
                                            if (percent != lastPercent[0]) {
                                                callback.onRepairProgress(percent);
                                                lastPercent[0] = percent;
                                            }
                                        }
                                    }
                                    if (!NativeImageGuard.processRequired(outFile)) {
                                        throw new IOException("Failed to prepare native library: " + outFile.getName());
                                    }
                                }
                            }
                        }
                    }
                }

                callback.onRepairProgress(PROGRESS_FINALIZING);
                writeVersionMetadata(apkFile, version, dataDirName);
                loadAllVersions();
                callback.onRepairProgress(PROGRESS_MAX);

                callback.onRepairCompleted(true);

            } catch (Exception e) {
                callback.onRepairFailed(e);
            }
        }).start();
    }

    private void writeVersionMetadata(File apkFile, GameVersion version, String directoryName) throws IOException {
        String versionName = getApkVersionName(apkFile);
        File metadataDir = LauncherStorage.getProfileMetadataDir(context, directoryName);
        VersionProfileMetadataStore.Defaults defaults = metadataDefaults(
                version.isInstalled,
                directoryName,
                version.packageName,
                versionName
        );
        metadataStore.update(metadataDir, defaults, metadata -> {
            metadata.versionName = versionName;
            metadata.installed = version.isInstalled;
            metadata.packageName = version.isInstalled ? version.packageName : null;
        });
    }

    private long getLibsTotalSize(File apkFile) throws IOException {
        long totalSize = 0;
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(apkFile)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name != null && name.startsWith("lib/") && !entry.isDirectory()) {
                    long size = entry.getSize();
                    if (size < 0) {
                        size = entry.getCompressedSize();
                    }
                    if (size > 0) {
                        totalSize += size;
                    }
                }
            }
        }
        return totalSize;
    }

    public void loadAllVersions() {
        installedVersions.clear();
        customVersions.clear();

        File baseDir = LauncherStorage.getMinecraftRoot(context);

        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo pi = pm.getPackageInfo(MinecraftLauncher.MC_PACKAGE_NAME, 0);
            File versionDir = getVersionDirForPackage(baseDir, pi.packageName);
            if (!versionDir.exists()) versionDir.mkdirs();
            
            File gamesDir = LauncherStorage.getProfileGameDataDir(context, pi.packageName);
            if (!gamesDir.exists()) gamesDir.mkdirs();

            String appLabel = String.valueOf(pi.applicationInfo.loadLabel(pm));
            boolean hasSoFiles = hasSoFilesInDir(new File(pi.applicationInfo.nativeLibraryDir));
            VersionProfileMetadata metadata = loadMetadata(
                    LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID,
                    VersionProfileMetadataStore.Defaults.installed(pi.packageName, pi.versionName)
            );
            syncInstalledMetadata(pi.packageName, pi.versionName, metadata);
            String versionName = safeValue(metadata.versionName, pi.versionName);
            String displayBaseName = safeValue(metadata.displayName, appLabel);
            String displayName = displayBaseName + " (" + versionName + ")";

            GameVersion gv = new GameVersion(
                    metadata.directoryName,
                    displayName,
                    versionName,
                    versionDir,
                    true,
                    pi.packageName,
                    "unknown"
            );

            gv.needsRepair = false;
            if (!hasSoFiles) {
                gv.isExtractFalse = true;
            }

            gv.abiList = inferAbiFromNativeLibDir(pi.applicationInfo.nativeLibraryDir, gv);
            gv.versionIsolation = metadata.versionIsolation;
            gv.launchVertically = metadata.launchVertically;
            gv.shaderCompatEnabled = metadata.shaderCompatEnabled;
            gv.inGamePackChangerEnabled = metadata.inGamePackChangerEnabled;
            gv.boundAccountId = metadata.boundAccountId;

            installedVersions.add(gv);
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        File[] dirs = baseDir.listFiles(File::isDirectory);

        if (dirs != null) {
            for (File dir : dirs) {
                File apk = new File(dir, "base.apk.chimera");
                if (!apk.exists()) continue;

                GameVersion gv = getGameVersion(dir);
                gv.needsRepair = false;
                gv.onlyVersionTxt = false;
                customVersions.add(gv);
            }
        }
        restoreSelectedVersion();
    }

    private void syncInstalledMetadata(String packageName, String versionName, VersionProfileMetadata metadata) {
        boolean needsSync = !LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID.equals(metadata.profileId)
                || !LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID.equals(metadata.directoryName)
                || !safeValue(metadata.versionName, "").equals(versionName)
                || !metadata.installed
                || !safeValue(metadata.packageName, "").equals(packageName);
        if (!needsSync) {
            return;
        }
        try {
            File metadataDir = LauncherStorage.getProfileMetadataDir(context, LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID);
            metadataStore.update(
                    metadataDir,
                    VersionProfileMetadataStore.Defaults.installed(packageName, versionName),
                    current -> {
                        current.profileId = LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID;
                        current.directoryName = LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID;
                        current.versionName = versionName;
                        current.installed = true;
                        current.packageName = packageName;
                        metadata.profileId = current.profileId;
                        metadata.directoryName = current.directoryName;
                        metadata.versionName = current.versionName;
                        metadata.installed = current.installed;
                        metadata.packageName = current.packageName;
                        metadata.shaderCompatEnabled = current.shaderCompatEnabled;
                        metadata.inGamePackChangerEnabled = current.inGamePackChangerEnabled;
                        metadata.boundAccountId = current.boundAccountId;
                    }
            );
        } catch (IOException ignored) {
            metadata.profileId = LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID;
            metadata.directoryName = LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID;
            metadata.versionName = versionName;
            metadata.installed = true;
            metadata.packageName = packageName;
        }
    }

    @NonNull
    private File getVersionDirForPackage(String packageName) {
        return LauncherStorage.getVersionDir(context, packageName);
    }

    @NonNull
    private File getVersionDirForPackage(File baseDir, String packageName) {
        File dir = new File(baseDir, packageName);
        LauncherStorage.ensureDir(dir);
        return dir;
    }

    private boolean hasSoFilesInDir(File nativeLibDir) {
        if (nativeLibDir == null) return false;
        File[] files = nativeLibDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".so")) {
                    return true;
                }
            }
        }
        return false;
    }

    @NonNull
    private GameVersion getGameVersion(File dir) {
        File metadataDir = LauncherStorage.getProfileMetadataDir(context, dir.getName());
        VersionProfileMetadata metadata = loadMetadata(
                metadataDir,
                VersionProfileMetadataStore.Defaults.custom(dir.getName(), getApkVersionName(new File(dir, "base.apk.chimera")))
        );
        String directoryName = dir.getName();
        String expectedProfileId = LauncherStorage.sanitizeProfileId(directoryName);
        if (!directoryName.equals(metadata.directoryName) || !expectedProfileId.equals(metadata.profileId)) {
            try {
                metadataStore.update(
                        metadataDir,
                        VersionProfileMetadataStore.Defaults.custom(directoryName, metadata.versionName),
                        current -> {
                            current.directoryName = directoryName;
                            current.profileId = expectedProfileId;
                        }
                );
            } catch (IOException ignored) {
            }
        }
        String versionCode = safeValue(metadata.versionName, dir.getName());
        String displayBaseName = safeValue(metadata.displayName, directoryName);
        String displayName = displayBaseName + " (" + versionCode + ")";

        GameVersion gv = new GameVersion(
                directoryName,
                displayName,
                versionCode,
                dir,
                false,
                null,
                "unknown"
        );

        gv.isExtractFalse = false;
        gv.directoryName = directoryName;

        gv.abiList = inferAbiFromNativeLibDir(null, gv);
        gv.versionIsolation = metadata.versionIsolation;
        gv.launchVertically = metadata.launchVertically;
        gv.shaderCompatEnabled = metadata.shaderCompatEnabled;
        gv.inGamePackChangerEnabled = metadata.inGamePackChangerEnabled;
        gv.boundAccountId = metadata.boundAccountId;

        return gv;
    }

    public void setInstanceVersionIsolation(GameVersion version, boolean enabled) {
        if (version == null) return;
        version.versionIsolation = enabled;
        updateCachedInstance(version, cached -> cached.versionIsolation = enabled);
        try {
            File metadataDir = LauncherStorage.getProfileMetadataDir(context, getMetadataDirectoryName(version));
            metadataStore.update(
                    metadataDir,
                    metadataDefaults(version.isInstalled, version.directoryName, version.packageName, version.versionCode),
                    metadata -> metadata.versionIsolation = enabled
            );
        } catch (Exception ignored) {}
    }

    public void setInstanceLaunchVertically(GameVersion version, boolean enabled) {
        if (version == null) return;
        version.launchVertically = enabled;
        updateCachedInstance(version, cached -> cached.launchVertically = enabled);
        try {
            File metadataDir = LauncherStorage.getProfileMetadataDir(context, getMetadataDirectoryName(version));
            metadataStore.update(
                    metadataDir,
                    metadataDefaults(version.isInstalled, version.directoryName, version.packageName, version.versionCode),
                    metadata -> metadata.launchVertically = enabled
            );
        } catch (Exception ignored) {}
    }

    public void setInstanceShaderCompatEnabled(GameVersion version, boolean enabled) {
        if (version == null) return;
        version.shaderCompatEnabled = enabled;
        updateCachedInstance(version, cached -> cached.shaderCompatEnabled = enabled);
        try {
            File metadataDir = LauncherStorage.getProfileMetadataDir(context, getMetadataDirectoryName(version));
            metadataStore.update(
                    metadataDir,
                    metadataDefaults(version.isInstalled, version.directoryName, version.packageName, version.versionCode),
                    metadata -> metadata.shaderCompatEnabled = enabled
            );
        } catch (Exception ignored) {}
    }

    /**
     * Toggles the in-game resource-pack changer for an instance.
     *
     * <p>Persisted per instance so the in-game Mod Menu can decide whether to expose the changer
     * without re-deriving it from global settings. Defaults off: the changer writes to the
     * instance's pack lists, so it is opt-in rather than silently active.
     */
    public void setInstanceInGamePackChangerEnabled(GameVersion version, boolean enabled) {
        if (version == null) return;
        version.inGamePackChangerEnabled = enabled;
        updateCachedInstance(version, cached -> cached.inGamePackChangerEnabled = enabled);
        try {
            File metadataDir = LauncherStorage.getProfileMetadataDir(context, getMetadataDirectoryName(version));
            metadataStore.update(
                    metadataDir,
                    metadataDefaults(version.isInstalled, version.directoryName, version.packageName, version.versionCode),
                    metadata -> metadata.inGamePackChangerEnabled = enabled
            );
        } catch (Exception ignored) {}
    }

    public boolean isInstanceInGamePackChangerEnabled(GameVersion version) {
        return version != null && version.inGamePackChangerEnabled;
    }

    /**
     * Binds a Microsoft account to an instance, or clears the binding with a null/blank id (F2).
     *
     * <p>Persisted per instance, so an instance launches with its own account instead of whichever
     * one is globally active. A null or blank id clears the binding, which restores the previous
     * behaviour of using the active account. The stored id is <b>not</b> validated here: an id
     * whose account has since been deleted is kept, and {@code InstanceAccountBinding} reports it
     * as stale at launch, so the user is told rather than silently switched.
     */
    public void setInstanceBoundAccount(GameVersion version, String accountId) {
        if (version == null) return;
        String value = accountId == null || accountId.trim().isEmpty() ? null : accountId.trim();
        version.boundAccountId = value;
        updateCachedInstance(version, cached -> cached.boundAccountId = value);
        try {
            File metadataDir = LauncherStorage.getProfileMetadataDir(context, getMetadataDirectoryName(version));
            metadataStore.update(
                    metadataDir,
                    metadataDefaults(version.isInstalled, version.directoryName, version.packageName, version.versionCode),
                    metadata -> metadata.boundAccountId = value
            );
        } catch (Exception ignored) {}
    }

    /** The account id bound to an instance, or null when it uses the active account. */
    public String getInstanceBoundAccount(GameVersion version) {
        return version == null ? null : version.boundAccountId;
    }

    public boolean setInstanceDisplayName(GameVersion version, String displayName) {
        if (version == null || displayName == null || displayName.trim().isEmpty()) return false;
        String value = displayName.trim();
        try {
            File metadataDir = LauncherStorage.getProfileMetadataDir(context, getMetadataDirectoryName(version));
            metadataStore.update(
                    metadataDir,
                    metadataDefaults(version.isInstalled, version.directoryName, version.packageName, version.versionCode),
                    metadata -> metadata.displayName = value
            );
            String fullDisplayName = value;
            if (version.versionCode != null && !version.versionCode.trim().isEmpty()) {
                fullDisplayName = value + " (" + version.versionCode.trim() + ")";
            }
            String finalDisplayName = fullDisplayName;
            version.displayName = finalDisplayName;
            updateCachedInstance(version, cached -> cached.displayName = finalDisplayName);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateCachedInstance(GameVersion source, GameVersionMutator mutator) {
        if (source == null || mutator == null) return;
        for (GameVersion version : installedVersions) {
            if (isSameInstance(version, source)) {
                mutator.mutate(version);
            }
        }
        for (GameVersion version : customVersions) {
            if (isSameInstance(version, source)) {
                mutator.mutate(version);
            }
        }
        if (isSameInstance(selectedVersion, source)) {
            mutator.mutate(selectedVersion);
        }
    }

    private boolean isSameInstance(GameVersion left, GameVersion right) {
        if (left == null || right == null) return false;
        if (left.isInstalled || right.isInstalled) {
            return left.isInstalled
                    && right.isInstalled
                    && safeValue(left.packageName, MinecraftLauncher.MC_PACKAGE_NAME)
                    .equals(safeValue(right.packageName, MinecraftLauncher.MC_PACKAGE_NAME));
        }
        return safeValue(left.directoryName, "").equals(safeValue(right.directoryName, ""));
    }

    private String getMetadataDirectoryName(GameVersion version) {
        if (version == null) return LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID;
        return version.isInstalled ? LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID : version.directoryName;
    }

    private interface GameVersionMutator {
        void mutate(GameVersion version);
    }

    private void restoreSelectedVersion() {
        String type = prefs.getString(KEY_SELECTED_TYPE, null);
        if (type != null) {
            if (type.equals("installed")) {
                String pkg = prefs.getString(KEY_SELECTED_PACKAGE, null);
                for (GameVersion gv : installedVersions) {
                    if (gv.packageName != null && gv.packageName.equals(pkg)) {
                        selectedVersion = gv;
                        break;
                    }
                }
            } else if (type.equals("custom")) {
                String dir = prefs.getString(KEY_SELECTED_DIR, null);
                for (GameVersion gv : customVersions) {
                    if (gv.versionDir.getAbsolutePath().equals(dir)
                            || gv.directoryName.equals(new File(dir == null ? "" : dir).getName())) {
                        selectedVersion = gv;
                        break;
                    }
                }
            }
        }
    }

    public List<GameVersion> getInstalledVersions() {
        return installedVersions;
    }

    public List<GameVersion> getCustomVersions() {
        return customVersions;
    }

    public GameVersion getSelectedVersion() {
        if (selectedVersion != null) return selectedVersion;
        if (!installedVersions.isEmpty()) {
            selectVersion(installedVersions.get(0));
            return installedVersions.get(0);
        }
        if (!customVersions.isEmpty()) {
            selectVersion(customVersions.get(0));
            return customVersions.get(0);
        }
        return null;
    }

    public void selectVersion(GameVersion version) {
        this.selectedVersion = version;
        SharedPreferences.Editor editor = prefs.edit();
        if (version != null && version.isInstalled) {
            editor.putString(KEY_SELECTED_TYPE, "installed");
            editor.putString(KEY_SELECTED_PACKAGE, version.packageName);
            editor.remove(KEY_SELECTED_DIR);
        } else if (version != null) {
            editor.putString(KEY_SELECTED_TYPE, "custom");
            editor.putString(KEY_SELECTED_DIR, version.versionDir.getAbsolutePath());
            editor.remove(KEY_SELECTED_PACKAGE);
        } else {
            editor.remove(KEY_SELECTED_TYPE);
            editor.remove(KEY_SELECTED_DIR);
            editor.remove(KEY_SELECTED_PACKAGE);
        }
        editor.apply();
    }

    public void reload() {
        loadAllVersions();
    }

    public static void attemptRepairLibs(Activity activity, GameVersion version) {
        if (version == null || libsRepairFlowActive) {
            return;
        }
        libsRepairFlowActive = true;
        LibsRepairDialog repairDialog = new LibsRepairDialog(activity);
        Handler mainHandler = new Handler(Looper.getMainLooper());

        VersionManager.LibsRepairCallback callback = new VersionManager.LibsRepairCallback() {
            @Override
            public void onRepairStarted() {
                activity.runOnUiThread(() -> {
                    repairDialog.setTitleText(activity.getString(R.string.repair_libs_in_progress));
                    repairDialog.setStatusText(activity.getString(R.string.repair_preparing));
                    repairDialog.setIndeterminate(true);
                    repairDialog.updateProgress(0);
                });
            }

            @Override
            public void onRepairProgress(int progress) {
                activity.runOnUiThread(() -> {
                    if (progress > 0) {
                        repairDialog.setStatusText(activity.getString(
                                progress >= PROGRESS_FINALIZING
                                        ? R.string.repair_finalizing
                                        : R.string.repair_processing
                        ));
                        repairDialog.setIndeterminate(false);
                    }
                    repairDialog.updateProgress(progress);
                });
            }

            @Override
            public void onRepairCompleted(boolean success) {
                activity.runOnUiThread(() -> {
                    libsRepairFlowActive = false;
                    Runnable showResult = () -> {
                        if (activity.isFinishing()) {
                            return;
                        }
                        if (success) {
                            new CustomAlertDialog(activity)
                                    .setTitleText(activity.getString(R.string.repair_completed))
                                    .setMessage(activity.getString(R.string.repair_libs_success_message))
                                    .setPositiveButton(activity.getString(R.string.confirm), null)
                                    .show();
                            if (activity instanceof MainActivity) {
                                ((MainActivity) activity).setTextMinecraftVersion();
                            }
                        } else {
                            new CustomAlertDialog(activity)
                                    .setTitleText(activity.getString(R.string.repair_failed))
                                    .setMessage(activity.getString(R.string.repair_libs_failed_message))
                                    .setPositiveButton(activity.getString(R.string.confirm), null)
                                    .show();
                        }
                    };
                    repairDialog.setOnDismissAnimationEndListener(showResult);
                    if (repairDialog.isShowing()) {
                        repairDialog.dismiss();
                    } else {
                        showResult.run();
                    }
                });
            }

            @Override
            public void onRepairFailed(Exception e) {
                activity.runOnUiThread(() -> {
                    libsRepairFlowActive = false;
                    Runnable showError = () -> {
                        if (activity.isFinishing()) {
                            return;
                        }
                        new CustomAlertDialog(activity)
                                .setTitleText(activity.getString(R.string.repair_error))
                                .setMessage(repairFailureMessage(activity, e))
                                .setPositiveButton(activity.getString(R.string.confirm), null)
                                .show();
                    };
                    repairDialog.setOnDismissAnimationEndListener(showError);
                    if (repairDialog.isShowing()) {
                        repairDialog.dismiss();
                    } else {
                        showError.run();
                    }
                });
            }
        };

        boolean[] repairConfirmed = {false};
        CustomAlertDialog confirmDialog = new CustomAlertDialog(activity)
                .setTitleText(String.format(activity.getString(R.string.missing_libs_title), version.directoryName))
                .setMessage(activity.getString(R.string.missing_libs_message))
                .setPositiveButton(activity.getString(R.string.repair), v -> {
                    repairConfirmed[0] = true;
                })
                .setNegativeButton(activity.getString(R.string.cancel), null);
        confirmDialog.setOnDismissAnimationEndListener(() -> {
            if (!repairConfirmed[0] || activity.isFinishing()) {
                libsRepairFlowActive = false;
                return;
            }
            mainHandler.post(() -> {
                if (activity.isFinishing()) {
                    libsRepairFlowActive = false;
                    return;
                }
                if (!repairDialog.isShowing()) {
                    repairDialog.show();
                }
                VersionManager.get(activity).repairLibsAsync(version, callback);
            });
        });
        confirmDialog.show();
    }

    /**
     * The repair failure sentence, with the cause named.
     *
     * <p>{@code String.format("...%s", e.getMessage())} printed the literal word "null" whenever an
     * exception carried no message, which is common for the file-system failures repair hits. The
     * classifier names the cause (out of space, permission denied, damaged files) so the dialog
     * says what to do, and falls back to the exception's type when it has no text at all.
     */
    private static String repairFailureMessage(android.content.Context context, Exception error) {
        if (error == null) return context.getString(R.string.error_reason_unknown);
        String reason = context.getString(
                org.chimeramc.client.launcher.core.minecraft.UserFacingError.messageRes(
                        org.chimeramc.client.launcher.core.minecraft.UserFacingError.classify(error)));
        return org.chimeramc.client.launcher.core.minecraft.UserFacingError.describe(error, reason);
    }

    public void renameVersion(GameVersion version, String newDisplayName, OnRenameVersionCallback callback) {
        if (version == null) {
            if (callback != null)
                callback.onRenameFailed(new IllegalArgumentException("Version is null"));
            return;
        }

        if (newDisplayName == null || newDisplayName.trim().isEmpty()) {
            if (callback != null)
                callback.onRenameFailed(new IllegalArgumentException("Display name cannot be empty"));
            return;
        }

        new Thread(() -> {
            try {
                String metaDirName = getMetadataDirectoryName(version);
                File metadataDir = LauncherStorage.getProfileMetadataDir(context, metaDirName);
                metadataStore.update(
                        metadataDir,
                        metadataDefaults(version.isInstalled, version.directoryName, version.packageName, version.versionCode),
                        metadata -> metadata.displayName = newDisplayName.trim()
                );
                reload();
                if (callback != null)
                    callback.onRenameCompleted(true);
            } catch (Exception e) {
                if (callback != null)
                    callback.onRenameFailed(e);
            }
        }).start();
    }

    public void deleteCustomVersion(GameVersion version, OnDeleteVersionCallback callback) {
        if (version == null || version.isInstalled) {
            if (callback != null)
                callback.onDeleteFailed(new IllegalArgumentException(context.getString(R.string.error_delete_builtin_version)));
            return;
        }

        ModManager modManager = ModManager.getInstance();
        if (modManager.getCurrentVersion() != null &&
                modManager.getCurrentVersion().equals(version)) {
            modManager.setCurrentVersion(null);
        }

        boolean isSelected = version.equals(selectedVersion);

        new Thread(() -> {
            try {
                File extDir = version.versionDir;
                if (extDir != null && extDir.exists()) {
                    File worldsDir = new File(LauncherStorage.getProfileGameDataDir(context, extDir.getName()), "minecraftWorlds");
                    if (worldsDir.exists() && worldsDir.isDirectory()) {
                        String backupBase = LauncherStorage.getWorldBackupsDir(context).getAbsolutePath();
                        String timeStr = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(new java.util.Date());
                        File backupDir = new File(backupBase, timeStr);
                        backupDir.mkdirs();

                        File[] worldFolders = worldsDir.listFiles(file -> file.isDirectory());
                        if (worldFolders != null) {
                            for (File worldFolder : worldFolders) {
                                File destFolder = new File(backupDir, worldFolder.getName());
                                copyDirectory(worldFolder, destFolder);
                            }
                        }
                    }
                    deleteDir(extDir);
                }

                File runtimeLibDir = getRuntimeLibDir(extDir != null ? extDir.getName() : "");
                if (runtimeLibDir.exists()) deleteDir(runtimeLibDir);

                if (isSelected) {
                    selectedVersion = null;
                    reload();
                    if (!customVersions.isEmpty()) {
                        selectVersion(customVersions.get(0));
                    } else if (!installedVersions.isEmpty()) {
                        selectVersion(installedVersions.get(0));
                    } else {
                        selectVersion(null);
                    }
                } else {
                    reload();
                }

                if (callback != null)
                    callback.onDeleteCompleted(true);
            } catch (Exception e) {
                if (callback != null)
                    callback.onDeleteFailed(e);
            }
        }).start();
    }

    private void copyDirectory(File sourceDir, File targetDir) throws IOException {
        if (sourceDir.isDirectory()) {
            if (!targetDir.exists())
                targetDir.mkdirs();
            String[] children = sourceDir.list();
            if (children != null) {
                for (String child : children) {
                    copyDirectory(new File(sourceDir, child), new File(targetDir, child));
                }
            }
        } else {
            java.nio.file.Files.copy(sourceDir.toPath(), targetDir.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean deleteDir(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null)
                for (File c : files)
                    deleteDir(c);
        }
        return file.delete();
    }

    private File getRuntimeLibDir(String dirName) {
        return MinecraftLauncher.getRuntimeLibDir(context, dirName);
    }

    /**
     * Sandboxed Multi-Version Manager - Import and split bundled game files (.apks or .xapk)
     * Handles importing, splitting, and swapping between isolated game versions
     */
    public interface VersionCallback {
        void onSuccess(String versionId);
        void onError(String error);
        void onProgress(int percent);
    }

    public void importGameFile(File gameFile, VersionCallback callback) {
        new Thread(() -> {
            try {
                String fileName = gameFile.getName().toLowerCase();
                
                if (fileName.endsWith(".xapk") || fileName.endsWith(".apk")) {
                    extractAndSplit(gameFile, callback);
                } else {
                    callback.onError("Unsupported file format. Please use .apk or .xapk files.");
                }
            } catch (Exception e) {
                e.printStackTrace();
                callback.onError(UserFacingError.shortReasonForText(context, "Import failed", e.getMessage()));
            }
        }).start();
    }

    private void extractAndSplit(File gameFile, VersionCallback callback) throws IOException {
        String versionId = java.util.UUID.randomUUID().toString();
        File baseDir = LauncherStorage.getMinecraftRoot(context);
        File versionDir = new File(baseDir, versionId);
        File tempDir = new File(context.getCacheDir(), "temp_extract_" + versionId);
        
        // Clean temp directory
        deleteDir(tempDir);
        tempDir.mkdirs();
        
        callback.onProgress(10);
        
        // Extract XAPK/APK contents
        if (gameFile.getName().endsWith(".xapk")) {
            extractXapk(gameFile, tempDir, callback);
        } else {
            // Single APK - create simple structure
            File apkDir = new File(tempDir, "apk");
            apkDir.mkdirs();
            copyFile(gameFile, new File(apkDir, "base.apk"));
            
            // Extract version info
            String versionName = getApkVersionName(gameFile);
            saveVersionMetadata(versionId, versionName, gameFile.length(), versionDir);
        }
        
        callback.onProgress(50);
        
        // Split and organize files into sandboxed directories
        splitIntoSandbox(tempDir, versionDir, callback);
        
        callback.onProgress(90);
        
        // Mark as available by creating a custom version entry
        createCustomVersionFromImport(versionId, versionDir);
        
        callback.onProgress(100);
        callback.onSuccess(versionId);
        
        // Cleanup temp
        deleteDir(tempDir);
    }

    private void extractXapk(File xapkFile, File outputDir, VersionCallback callback) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(xapkFile)))) {
            ZipEntry entry;
            int totalEntries = countEntries(xapkFile);
            int processed = 0;
            
            while ((entry = zis.getNextEntry()) != null) {
                File outputFile = new File(outputDir, entry.getName());
                
                if (entry.isDirectory()) {
                    outputFile.mkdirs();
                } else {
                    outputFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                
                processed++;
                int progress = 10 + (processed * 40 / totalEntries);
                callback.onProgress(progress);
                
                zis.closeEntry();
            }
        }
    }

    private int countEntries(File zipFile) throws IOException {
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            while (zis.getNextEntry() != null) {
                count++;
                zis.closeEntry();
            }
        }
        return count;
    }

    private void splitIntoSandbox(File sourceDir, File versionDir, VersionCallback callback) {
        // Create sandboxed directory structure
        File apkDir = new File(versionDir, "apk");
        File obbDir = new File(versionDir, "obb");
        File dataDir = new File(versionDir, "data");
        File configDir = new File(versionDir, "config");
        
        apkDir.mkdirs();
        obbDir.mkdirs();
        dataDir.mkdirs();
        configDir.mkdirs();
        
        // Move APK files
        File sourceApkDir = new File(sourceDir, "apk");
        if (sourceApkDir.exists()) {
            moveDirectory(sourceApkDir, apkDir);
        }
        
        // Move OBB files
        File sourceObbDir = new File(sourceDir, "obb");
        if (sourceObbDir.exists()) {
            moveDirectory(sourceObbDir, obbDir);
        }
        
        // Move manifest/config
        File manifest = new File(sourceDir, "manifest.json");
        if (manifest.exists()) {
            copyFile(manifest, new File(configDir, "manifest.json"));
        }
        
        callback.onProgress(70);
    }

    private void saveVersionMetadata(String versionId, String versionName, long fileSize, File versionDir) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("imported_version_" + versionId + "_name", versionName);
        editor.putLong("imported_version_" + versionId + "_size", fileSize);
        editor.putString("imported_version_" + versionId + "_path", versionDir.getAbsolutePath());
        editor.apply();
    }

    private void moveDirectory(File source, File dest) {
        if (source.isDirectory()) {
            File[] files = source.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        moveDirectory(file, new File(dest, file.getName()));
                    } else {
                        copyFile(file, new File(dest, file.getName()));
                        file.delete();
                    }
                }
            }
        }
    }

    private void copyFile(File source, File dest) {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createCustomVersionFromImport(String versionId, File versionDir) {
        // Create a GameVersion entry for the imported version
        String versionName = prefs.getString("imported_version_" + versionId + "_name", "Imported Version");
        long fileSize = prefs.getLong("imported_version_" + versionId + "_size", 0);
        
        // Copy APK to expected location
        File apkSource = new File(versionDir, "apk/base.apk");
        if (apkSource.exists()) {
            File apkDest = new File(versionDir, "base.apk.chimera");
            copyFile(apkSource, apkDest);
        }
        
        // Reload versions to include the new one
        loadAllVersions();
    }
}
