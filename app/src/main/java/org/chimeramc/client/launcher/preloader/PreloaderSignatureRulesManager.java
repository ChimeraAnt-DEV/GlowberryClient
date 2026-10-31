package org.chimeramc.client.preloader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.chimeramc.client.util.JsonIOUtils;
import org.chimeramc.client.util.LauncherStorage;
import org.chimeramc.client.util.AppExecutors;
import org.chimeramc.client.util.MainThread;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class PreloaderSignatureRulesManager {
    private static final String TAG = "PreloaderSigs";
    private static final String ASSET_DEFAULT_RULES = "preloader/preloader_signature_rules_source.json";
    private static final String RESOURCE_DIR = "resources/preloader";
    private static final String LOCAL_RULES_FILE = "preloader_signature_rules.json";
    private static final String PREFS_NAME = "preloader_signature_rules";
    private static final String KEY_LAST_SUCCESSFUL_UPDATE_TIME = "last_successful_update_time";

    /** Where the rules file currently in use came from. Reported by the diagnostics summary. */
    public static final String SOURCE_BUNDLED = "bundled";
    public static final String SOURCE_REMOTE = "remote";
    private static final String KEY_RULES_SOURCE = "rules_source";

    private static volatile String sLastSource = SOURCE_BUNDLED;

    /**
     * Signature rules are fetched from these sources in order. The first source that returns
     * valid rules wins, so Chimera's own copy is authoritative and Levi's upstream file is
     * only the fallback for when our host is unreachable. The bundled asset is the last
     * resort, so an offline launch still has current rules.
     */
    private static final String[] REMOTE_RULES_URLS = {
            "https://raw.githubusercontent.com/ChimeraAnt-DEV/GlowberryClient/main/resources/preloader/preloader_signature_rules_source.json",
            "https://raw.githubusercontent.com/LiteLDev/LeviLaunchroid/refs/heads/main/resources/preloader/preloader_signature_rules_source.json"
    };

    private static final AtomicBoolean refreshRunning = new AtomicBoolean(false);
    private static final OkHttpClient client = buildLatencyTunedClient();

    private static OkHttpClient buildLatencyTunedClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS);
        org.chimeramc.client.settings.LowLatencyNetworkManager.configure(builder);
        return builder.build();
    }

    private PreloaderSignatureRulesManager() {
    }

    public interface RefreshCallback {
        void onFinished(RefreshResult result);
    }

    public static final class RefreshResult {
        public final boolean success;
        public final boolean updated;
        public final String message;
        public final long lastSuccessfulUpdateTime;

        private RefreshResult(boolean success, boolean updated, String message, long lastSuccessfulUpdateTime) {
            this.success = success;
            this.updated = updated;
            this.message = message == null ? "" : message;
            this.lastSuccessfulUpdateTime = lastSuccessfulUpdateTime;
        }
    }

    public static void refreshOnLauncherStart(Context context) {
        refresh(context, null);
    }

    public static void refreshNow(Context context, RefreshCallback callback) {
        refresh(context, callback);
    }

    public static boolean hasRemoteRulesUrl() {
        for (String url : REMOTE_RULES_URLS) {
            if (!isBlank(url)) {
                return true;
            }
        }
        return false;
    }

    public static long getLastSuccessfulUpdateTime(Context context) {
        if (context == null) {
            return 0L;
        }
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SUCCESSFUL_UPDATE_TIME, 0L);
    }

    public static File getRulesFile(Context context) {
        if (context == null) {
            return null;
        }
        return ensureLocalRulesFile(context.getApplicationContext());
    }

    /**
     * The revision number of the signature rules currently in use, or -1 when unreadable.
     * Used by the diagnostics screen so a player can share which rule set their build ran with.
     */
    public static int getRulesRevision(Context context) {
        try {
            String content = readRulesFile(getRulesFile(context));
            return PreloaderRules.revision(content);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * True when the signature-rules DB in use contains at least one rule that covers
     * [version] (format {@code 26.60.28} or {@code 1.26.60.28}).
     *
     * <p>This is the fail-closed half of item G5. Signature-based hook discovery only works when
     * the rule DB actually has an entry for the installed build; a build with no covering rule
     * cannot be hooked by byte pattern, so the launcher must say so before launch rather than
     * hand the rules to the preloader and let it "scan and hope". A build with no covering rule
     * is not necessarily broken — RTTI-slot seams still work — but every byte-pattern-driven
     * hook is off, and the caller (launch preflight / diagnostics) needs to know.
     */
    public static boolean coversInstalledVersion(Context context, String version) {
        try {
            String content = readRulesFile(getRulesFile(context));
            return PreloaderRules.coversVersion(content, version);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A human-readable statement of signature-rule coverage for [version]: null when a rule
     * covers it, or a message naming the version the diagnostics can show when one does not.
     */
    public static String describeCoverageFailure(Context context, String version) {
        if (coversInstalledVersion(context, version)) {
            return null;
        }
        String expected = version != null
                ? (version.startsWith("1.") ? version.substring(2) : version)
                : "?";
        return "No signature rule covers Bedrock " + expected
                + ": byte-pattern hooks (menu/HUD screens) are disabled for this build";
    }

    private static String readRulesFile(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            // Files.readAllBytes is API 26; InputStream.readAllBytes is API 33 and throws
            // NoSuchMethodError on Android 9-12, which is an Error, so the catch below would not
            // have contained it. This runs on the launch path ("Loading game loader").
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static void refresh(Context context, RefreshCallback callback) {
        if (context == null) {
            postCallback(callback, new RefreshResult(false, false, "Context is null", 0L));
            return;
        }
        if (!refreshRunning.compareAndSet(false, true)) {
            postCallback(callback, new RefreshResult(
                    false,
                    false,
                    "Refresh already running",
                    getLastSuccessfulUpdateTime(context)
            ));
            return;
        }

        Context appContext = context.getApplicationContext();
        AppExecutors.run(AppExecutors.Kind.NETWORK, "preloader-sig-refresh", () -> {
            RefreshResult result;
            try {
                ensureLocalRulesFile(appContext);
                result = refreshRemoteRules(appContext);
            } catch (Exception e) {
                Log.w(TAG, "Failed to refresh preloader signature rules", e);
                result = new RefreshResult(false, false, e.getMessage(), getLastSuccessfulUpdateTime(appContext));
            } finally {
                refreshRunning.set(false);
            }
            postCallback(callback, result);
        });
    }

    private static File ensureLocalRulesFile(Context context) {
        File localFile = getLocalRulesFile(context);
        String localRules = JsonIOUtils.read(localFile);
        String bundledRules = readAsset(context, ASSET_DEFAULT_RULES);

        if (!PreloaderRules.hasValidRules(bundledRules)) {
            Log.w(TAG, "Bundled preloader signature rules are invalid");
            return localFile;
        }

        // The bundled copy is the safe floor. It is written whenever the installed copy is invalid
        // OR the bundled copy's revision is at least as high as the local one — using >= rather than
        // > means a locally built APK whose bundled rules were bumped (e.g. to revision 5 with the
        // render slot fields) is not silently held back by an equal-revision file already on disk.
        int localRevision = PreloaderRules.revision(localRules);
        int bundledRevision = PreloaderRules.revision(bundledRules);
        if (!PreloaderRules.hasValidRules(localRules) || bundledRevision >= localRevision) {
            writeFileIfChanged(localFile, bundledRules);
            sLastSource = SOURCE_BUNDLED;
            setRulesSource(context, SOURCE_BUNDLED);
        }
        return localFile;
    }

    private static RefreshResult refreshRemoteRules(Context context) {
        if (!hasRemoteRulesUrl()) {
            return new RefreshResult(
                    false,
                    false,
                    "Remote preloader signature rules URL is empty",
                    getLastSuccessfulUpdateTime(context)
            );
        }

        String remoteRules = null;
        String failureMessage = "Failed to fetch remote preloader signature rules";
        for (String url : REMOTE_RULES_URLS) {
            if (isBlank(url)) {
                continue;
            }
            String candidate = fetchText(url.trim());
            if (isBlank(candidate)) {
                continue;
            }
            if (!PreloaderRules.hasValidRules(candidate)) {
                Log.w(TAG, "Signature rules from " + url + " are invalid, trying next source");
                failureMessage = "Remote preloader signature rules are invalid";
                continue;
            }
            remoteRules = candidate;
            break;
        }

        if (isBlank(remoteRules)) {
            return new RefreshResult(
                    false,
                    false,
                    failureMessage,
                    getLastSuccessfulUpdateTime(context)
            );
        }

        File localFile = getLocalRulesFile(context);
        String localRules = JsonIOUtils.read(localFile);
        String bundledRules = readAsset(context, ASSET_DEFAULT_RULES);

        int remoteRevision = PreloaderRules.revision(remoteRules);
        int floorRevision = Math.max(PreloaderRules.revision(localRules),
                PreloaderRules.revision(bundledRules));
        if (remoteRevision <= floorRevision) {
            // A revision that is not strictly newer than the installed copy cannot silently
            // downgrade the rules. (Previously only a strictly LOWER revision was refused, so an
            // equal-revision remote file replaced a working local copy.)
            return new RefreshResult(
                    true,
                    false,
                    "Remote preloader signature rules are not newer than the installed rules",
                    getLastSuccessfulUpdateTime(context)
            );
        }
        if (!PreloaderRules.coversBundledRules(remoteRules, bundledRules)) {
            // A newer revision that has dropped a field the bundled copy carries (e.g.
            // playerRenderVtableIndex) would install hooks without a slot and fail on the running
            // build, so it is refused. The bundled copy stays authoritative.
            Log.w(TAG, "Remote preloader signature rules are newer but incomplete; keeping bundled rules");
            return new RefreshResult(
                    true,
                    false,
                    "Remote preloader signature rules are newer but missing fields the bundled rules carry",
                    getLastSuccessfulUpdateTime(context)
            );
        }

        WriteResult writeResult = writeFileIfChanged(localFile, remoteRules);
        if (!writeResult.success) {
            return new RefreshResult(
                    false,
                    false,
                    "Failed to write preloader signature rules",
                    getLastSuccessfulUpdateTime(context)
            );
        }

        if (writeResult.changed) {
            sLastSource = SOURCE_REMOTE;
            setRulesSource(context, SOURCE_REMOTE);
        }
        long updateTime = System.currentTimeMillis();
        setLastSuccessfulUpdateTime(context, updateTime);
        return new RefreshResult(true, writeResult.changed, "", updateTime);
    }

    private static String fetchText(String url) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "Remote preloader signature rules request failed: " + response.code());
                return null;
            }

            ResponseBody body = response.body();
            return body == null ? null : body.string();
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch remote preloader signature rules", e);
            return null;
        }
    }

    private static File getLocalRulesFile(Context context) {
        File dir = new File(LauncherStorage.getAppRoot(context), RESOURCE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create preloader resource directory: " + dir.getAbsolutePath());
        }
        return new File(dir, LOCAL_RULES_FILE);
    }

    private static String readAsset(Context context, String path) {
        try (InputStream input = context.getAssets().open(path);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            Log.w(TAG, "Failed to read asset " + path, e);
            return null;
        }
    }

    private static WriteResult writeFileIfChanged(File file, String content) {
        if (file == null || content == null) {
            return WriteResult.failed();
        }

        String existing = JsonIOUtils.read(file);
        if (content.equals(existing)) {
            return WriteResult.unchanged();
        }

        File parent = file.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            Log.w(TAG, "Failed to create directory for preloader signature rules");
            return WriteResult.failed();
        }

        File temp = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        } catch (Exception e) {
            Log.w(TAG, "Failed to write preloader signature rules", e);
            if (!temp.delete() && temp.exists()) {
                Log.w(TAG, "Failed to delete temporary file: " + temp.getAbsolutePath());
            }
            return WriteResult.failed();
        }

        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Failed to replace preloader signature rules: " + file.getAbsolutePath());
            if (!temp.delete() && temp.exists()) {
                Log.w(TAG, "Failed to delete temporary file: " + temp.getAbsolutePath());
            }
            return WriteResult.failed();
        }
        if (!temp.renameTo(file)) {
            Log.w(TAG, "Failed to commit preloader signature rules: " + file.getAbsolutePath());
            if (!temp.delete() && temp.exists()) {
                Log.w(TAG, "Failed to delete temporary file: " + temp.getAbsolutePath());
            }
            return WriteResult.failed();
        }
        return WriteResult.changed();
    }

    private static void setLastSuccessfulUpdateTime(Context context, long updateTime) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putLong(KEY_LAST_SUCCESSFUL_UPDATE_TIME, updateTime)
                .apply();
    }

    private static void setRulesSource(Context context, String source) {
        if (context == null) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RULES_SOURCE, source)
                .apply();
    }

    /**
     * The source of the rules file currently in use ("bundled" or "remote"), recorded at the last
     * resolve. Used by the diagnostics summary so a player can see whether the running build used
     * the shipped rules or a fetched copy, without reading logcat.
     */
    public static String getRulesSource(Context context) {
        if (context != null) {
            String recorded = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_RULES_SOURCE, null);
            if (recorded != null) {
                return recorded;
            }
        }
        return sLastSource;
    }

    private static void postCallback(RefreshCallback callback, RefreshResult result) {
        if (callback == null) {
            return;
        }
        MainThread.post(() -> callback.onFinished(result));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class WriteResult {
        final boolean success;
        final boolean changed;

        private WriteResult(boolean success, boolean changed) {
            this.success = success;
            this.changed = changed;
        }

        static WriteResult changed() {
            return new WriteResult(true, true);
        }

        static WriteResult unchanged() {
            return new WriteResult(true, false);
        }

        static WriteResult failed() {
            return new WriteResult(false, false);
        }
    }
}
