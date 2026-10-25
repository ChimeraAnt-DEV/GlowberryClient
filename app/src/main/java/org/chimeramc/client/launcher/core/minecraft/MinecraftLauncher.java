package org.chimeramc.client.core.minecraft;

import org.chimeramc.client.util.Api28Compat;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.widget.Toast;

import org.chimeramc.client.R;
import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.launcher.core.minecraft.LaunchErrorDiagnosis;
import org.chimeramc.client.util.LauncherStorage;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;

public class MinecraftLauncher {
    private static final String TAG = "MinecraftLauncher";
    private final Context context;
    public static final String MC_PACKAGE_NAME = "com.mojang.minecraftpe";
    public static final String EXTRA_GAME_VERSION = "org.chimeramc.client.extra.GAME_VERSION";
    public static final String EXTRA_STORAGE_PROFILE_ID = "org.chimeramc.client.extra.STORAGE_PROFILE_ID";
    public static final String EXTRA_STORAGE_FILES_DIR = "org.chimeramc.client.extra.STORAGE_FILES_DIR";
    public static final String EXTRA_STORAGE_EXTERNAL_FILES_DIR = "org.chimeramc.client.extra.STORAGE_EXTERNAL_FILES_DIR";
    public static final String EXTRA_STORAGE_DATA_DIR = "org.chimeramc.client.extra.STORAGE_DATA_DIR";
    public static final String EXTRA_STORAGE_CACHE_DIR = "org.chimeramc.client.extra.STORAGE_CACHE_DIR";

    public interface LaunchCallback {
        void onLaunchStarted();

        /**
         * The launch did not start.
         *
         * <p>{@link Throwable}, not {@code Exception}: a heap exhaustion while the launch is being
         * prepared is a real outcome, and the callback is what resets the Play button. Typing this
         * as {@code Exception} meant an {@code Error} could not be reported and the button stayed
         * in its "launching" state with no message.
         */
        void onLaunchFailed(Throwable e);
    }

    public MinecraftLauncher(Context context) {
        this.context = context;
    }

    public static String abiToSystemLibDir(String abi) {
        if ("arm64-v8a".equals(abi)) return "arm64";
        if ("armeabi-v7a".equals(abi)) return "arm";
        return abi;
    }

    public static File getRuntimeLibDir(Context context, String profileId) {
        return new File(context.getFilesDir(), "minecraft_libs/" + LauncherStorage.sanitizeProfileId(profileId));
    }

    public static File getRuntimeLibAbiDir(Context context, String profileId, String abi) {
        return new File(getRuntimeLibDir(context, profileId), abiToSystemLibDir(abi));
    }

    /**
     * Resolves the ABI a version must be launched with, preferring the version's declared
     * ABI ({@code abiList}) over the device's primary ABI. The entire native-library
     * loading path (extraction folder + load path) derives from this value, so a
     * 32-bit-only version loads its 32-bit libraries from the {@code arm} folder instead
     * of defaulting to the device's (64-bit) {@code arm64} folder.
     */
    public static String resolveLaunchAbi(GameVersion version) {
        String abiList = version != null ? version.abiList : null;
        if (abiList != null && Api28Compat.isNotBlank(abiList)) {
            String abi = abiList.trim();
            if ("arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi) || "x86_64".equals(abi) || "x86".equals(abi)) {
                return abi;
            }
        }
        return Build.SUPPORTED_ABIS[0];
    }

    public ApplicationInfo createFakeApplicationInfo(GameVersion version, String packageName) {
        return createFakeApplicationInfo(version, packageName, resolveLaunchAbi(version));
    }

    public ApplicationInfo createFakeApplicationInfo(GameVersion version, String packageName, String launchAbi) {
        ApplicationInfo fakeInfo = new ApplicationInfo();
        File apkFile = new File(version.versionDir, "base.apk.chimera");
        fakeInfo.sourceDir = apkFile.getAbsolutePath();
        fakeInfo.publicSourceDir = fakeInfo.sourceDir;
        String rawAbi = (launchAbi != null && Api28Compat.isNotBlank(launchAbi)) ? launchAbi : resolveLaunchAbi(version);
        File dstLibDir = getRuntimeLibAbiDir(context, getStorageProfileId(version), rawAbi);
        fakeInfo.nativeLibraryDir = dstLibDir.getAbsolutePath();
        fakeInfo.packageName = packageName;
        fakeInfo.dataDir = LauncherStorage.getProfileDataRoot(context, getStorageProfileId(version)).getAbsolutePath();

        File splitsFolder = new File(version.versionDir, "splits");
        if (splitsFolder.exists() && splitsFolder.isDirectory()) {
            File[] splits = splitsFolder.listFiles();
            if (splits != null) {
                ArrayList<String> splitPathList = new ArrayList<>();
                for (File f : splits) {
                    if (f.isFile() && f.getName().endsWith(".apk.chimera")) {
                        splitPathList.add(f.getAbsolutePath());
                    }
                }
                if (!splitPathList.isEmpty()) {
                    fakeInfo.splitSourceDirs = splitPathList.toArray(new String[0]);
                }
            }
        }
        return fakeInfo;
    }

    public void launch(Intent sourceIntent, GameVersion version) {
        launch(sourceIntent, version, null);
    }

    public void launch(Intent sourceIntent, GameVersion version, LaunchCallback callback) {
        Activity activity = (Activity) context;

        try {
            if (version == null) {
                Log.e(TAG, "No version selected");
                showLaunchErrorOnUi("No version selected");
                notifyLaunchFailed(callback, new IllegalArgumentException("No version selected"));
                return;
            }

            if (version.needsRepair) {
                activity.runOnUiThread(() ->
                        org.chimeramc.client.core.versions.VersionManager.attemptRepairLibs(activity, version)
                );
                notifyLaunchFailed(callback, new IllegalStateException("Selected version needs repair"));
                return;
            }

            activity.runOnUiThread(() -> {
                try {
                    launchMinecraftActivity(sourceIntent, version, false);
                    notifyLaunchStarted(callback);
                } catch (Throwable e) {
                    // Throwable, not Exception: preparing a launch allocates the storage paths and
                    // builds the game intent, so a heap exhaustion here is a real outcome, and an
                    // escaping Error skipped both the message and the callback - the Play button
                    // simply did nothing. The classifier names whatever comes back.
                    Log.e(TAG, "Launch failed: " + e.getMessage(), e);
                    showLaunchErrorOnUi(describe(e));
                    notifyLaunchFailed(callback, e);
                }
            });
        } catch (Throwable e) {
            Log.e(TAG, "Launch failed: " + e.getMessage(), e);
            showLaunchErrorOnUi(describe(e));
            notifyLaunchFailed(callback, e);
        }
    }

    /** A message for the failure classifier, never empty. */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return (message == null || message.trim().isEmpty())
                ? e.getClass().getSimpleName()
                : "Launch failed: " + message;
    }

    public static String getStorageProfileId(GameVersion version) {
        if (version == null) return LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID;
        if (version.isInstalled) return LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID;
        return LauncherStorage.sanitizeProfileId(version.directoryName);
    }

    private void fillIntentWithStoragePaths(Intent sourceIntent, GameVersion version) {
        String profileId = getStorageProfileId(version);
        boolean versionIsolation = version.versionIsolation;

        File filesDir = LauncherStorage.getStorageFilesRoot(context, profileId, versionIsolation, false);
        File externalFilesDir = LauncherStorage.getStorageFilesRoot(context, profileId, versionIsolation, true);
        File dataDir = LauncherStorage.getStorageDataRoot(context, profileId, versionIsolation);
        File cacheDir = LauncherStorage.getStorageCacheRoot(context, profileId, versionIsolation);

        sourceIntent.putExtra("MC_PATH", version.versionDir == null ? "" : version.versionDir.getAbsolutePath());
        sourceIntent.putExtra("IS_INSTALLED", version.isInstalled);
        sourceIntent.putExtra("VERSION_ISOLATION", versionIsolation);
        sourceIntent.putExtra(EXTRA_STORAGE_PROFILE_ID, profileId);
        sourceIntent.putExtra(EXTRA_STORAGE_FILES_DIR, filesDir.getAbsolutePath());
        sourceIntent.putExtra(EXTRA_STORAGE_EXTERNAL_FILES_DIR, externalFilesDir.getAbsolutePath());
        sourceIntent.putExtra(EXTRA_STORAGE_DATA_DIR, dataDir.getAbsolutePath());
        sourceIntent.putExtra(EXTRA_STORAGE_CACHE_DIR, cacheDir.getAbsolutePath());
    }

    private void launchMinecraftActivity(Intent sourceIntent, GameVersion version, boolean modsEnabled) {
        Activity activity = (Activity) context;

        Intent launchIntent = sourceIntent == null ? new Intent() : new Intent(sourceIntent);
        LaunchTrace trace = LaunchTrace.ensure(launchIntent);
        trace.mark("Building MinecraftLoadingActivity intent");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            launchIntent.putExtra("DISABLE_SPLASH_SCREEN", true);
        }
        fillIntentWithStoragePaths(launchIntent, version);
        launchIntent.setClass(context, MinecraftLoadingActivity.class);
        launchIntent.putExtra(EXTRA_GAME_VERSION, version);
        launchIntent.putExtra("MODS_ENABLED", modsEnabled);
        launchIntent.putExtra("MINECRAFT_VERSION", version.versionCode);
        launchIntent.putExtra("MINECRAFT_VERSION_DIR", version.directoryName);
        launchIntent.putExtra("LAUNCH_VERTICALLY", version.launchVertically);
        launchIntent.putExtra("VERSION_ISOLATION", version.versionIsolation);
        launchIntent.removeExtra("LAUNCH_WITH_URI");

        activity.startActivity(launchIntent);
        activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        trace.mark("MinecraftLoadingActivity startActivity called");
    }

    private void showLaunchErrorOnUi(String message) {
        Activity activity = (Activity) context;
        // Name the cause, then keep the raw text. This path reported the raw dlopen string alone,
        // which is accurate but leaves the user with nothing to act on; the classifier turns it into
        // the specific reason (Google Play install, wrong bitness, missing dependency, ...).
        LaunchErrorDiagnosis.Cause cause = LaunchErrorDiagnosis.classifyText(message);
        String text = activity.getString(LaunchErrorDiagnosis.messageRes(cause))
                + (message != null && !message.isEmpty() ? "\n\n" + message : "");
        activity.runOnUiThread(() -> Toast.makeText(
                activity, text, Toast.LENGTH_LONG).show()
        );
    }

    private void notifyLaunchStarted(LaunchCallback callback) {
        if (callback != null) {
            callback.onLaunchStarted();
        }
    }

    private void notifyLaunchFailed(LaunchCallback callback, Throwable e) {
        if (callback != null) {
            callback.onLaunchFailed(e);
        }
    }
}
