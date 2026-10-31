package org.chimeramc.client.ui.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.content.ContentImporter;
import org.chimeramc.client.core.modrinth.ModrinthClient;
import org.chimeramc.client.core.modrinth.models.ModrinthFile;
import org.chimeramc.client.core.modrinth.models.ModrinthProject;
import org.chimeramc.client.core.modrinth.models.ModrinthVersion;
import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.core.versions.VersionManager;
import org.chimeramc.client.settings.FeatureSettings;
import org.chimeramc.client.util.LauncherStorage;
import org.chimeramc.client.ui.adapter.ModrinthVersionAdapter;
import org.chimeramc.client.launcher.core.minecraft.UserFacingError;
import org.chimeramc.client.util.MainThread;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Lists a Modrinth project's versions and downloads the chosen file into the download
 * folder, then hands it to the existing import pipeline.
 *
 * Downloading to disk first (rather than importing the stream) means a failed import leaves
 * the file where the user can retry it from the Downloads tab.
 */
public class ModrinthDetailActivity extends BaseActivity {

    public static final String EXTRA_PROJECT = "extra_modrinth_project";

    private ModrinthProject project;
    private ModrinthClient client;
    private RecyclerView recycler;
    private ProgressBar progress;
    private View empty;
    private ModrinthVersionAdapter adapter;
    private ContentImporter contentImporter;
    private OkHttpClient httpClient;

    private final Handler handler = MainThread.handler();
    // Owned so the download thread is torn down with the screen instead of posting a result
    // (or a failure dialog) to a destroyed Activity.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_modrinth_detail);

        project = (ModrinthProject) getIntent().getSerializableExtra(EXTRA_PROJECT);
        if (project == null) {
            finish();
            return;
        }

        client = ModrinthClient.getInstance(this);
        contentImporter = new ContentImporter(this);

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        org.chimeramc.client.settings.LowLatencyNetworkManager.configure(builder);
        httpClient = builder.build();

        bindHeader();
        setupRecycler();
        loadVersions();
    }

    private void bindHeader() {
        TextView title = findViewById(R.id.modrinth_detail_title);
        TextView author = findViewById(R.id.modrinth_detail_author);
        TextView description = findViewById(R.id.modrinth_detail_description);

        title.setText(project.displayName());
        author.setText(getString(R.string.modrinth_downloads_format, formatCount(project.downloads))
                + (project.author != null ? " • " + project.author : ""));
        description.setText(project.description);
    }

    private void setupRecycler() {
        recycler = findViewById(R.id.modrinth_versions_recycler);
        progress = findViewById(R.id.modrinth_versions_progress);
        empty = findViewById(R.id.modrinth_versions_empty);

        adapter = new ModrinthVersionAdapter(this::downloadVersion);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
    }

    private void loadVersions() {
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);

        // Prefer builds tagged for the running version, but fall back to the full list rather
        // than showing nothing: Bedrock and Java version strings rarely line up anyway.
        List<String> gameVersions = currentGameVersions();

        client.getVersions(project.projectId, gameVersions,
                new ModrinthClient.ModrinthCallback<ModrinthVersion[]>() {
                    @Override
                    public void onSuccess(ModrinthVersion[] result) {
                        if (result != null && result.length > 0) {
                            publishVersions(result);
                            return;
                        }
                        client.getVersions(project.projectId, null,
                                new ModrinthClient.ModrinthCallback<ModrinthVersion[]>() {
                                    @Override
                                    public void onSuccess(ModrinthVersion[] all) {
                                        publishVersions(all);
                                    }

                                    @Override
                                    public void onError(Throwable t) {
                                        failVersions(t);
                                    }
                                });
                    }

                    @Override
                    public void onError(Throwable t) {
                        failVersions(t);
                    }
                });
    }

    private void publishVersions(ModrinthVersion[] versions) {
        List<ModrinthVersion> list = versions != null ? Arrays.asList(versions)
                : Collections.<ModrinthVersion>emptyList();
        handler.post(() -> {
            progress.setVisibility(View.GONE);
            adapter.setVersions(list);
            empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void failVersions(Throwable t) {
        handler.post(() -> {
            progress.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            Toast.makeText(this, UserFacingError.reason(this, t), Toast.LENGTH_LONG).show();
        });
    }

    /** The selected instance's version string, used as a soft filter. */
    private List<String> currentGameVersions() {
        VersionManager versionManager = VersionManager.get(this);
        GameVersion version = versionManager.getSelectedVersion();
        if (version == null || version.versionCode == null) {
            return null;
        }
        return Collections.singletonList(version.versionCode);
    }

    private void downloadVersion(ModrinthVersion version) {
        ModrinthFile file = version.primaryFile();
        if (file == null || file.url == null) {
            Toast.makeText(this, R.string.modrinth_no_versions, Toast.LENGTH_SHORT).show();
            return;
        }

        progress.setVisibility(View.VISIBLE);
        Toast.makeText(this, R.string.curseforge_downloading, Toast.LENGTH_SHORT).show();

        if (worker.isShutdown()) return;
        try {
            worker.execute(() -> {
                try {
                    File downloaded = download(file);
                    postIfAlive(() -> {
                        progress.setVisibility(View.GONE);
                        importDownloaded(downloaded);
                    });
                } catch (final IOException e) {
                    postIfAlive(() -> {
                        progress.setVisibility(View.GONE);
                        Toast.makeText(this, getString(R.string.curseforge_download_failed) + "\n\n"
                                + UserFacingError.reason(ModrinthDetailActivity.this, e), Toast.LENGTH_LONG).show();
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The screen was destroyed between the check and the submit.
        }
    }

    /** Posts to the UI thread only while the screen is still alive. */
    private void postIfAlive(Runnable task) {
        handler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            task.run();
        });
    }

    private File download(ModrinthFile file) throws IOException {
        String fileName = file.filename != null && !file.filename.isEmpty()
                ? file.filename : "modrinth_download.zip";

        File dir = downloadsDir();
        File output = new File(dir, fileName);

        Request request = new Request.Builder().url(file.url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            try (InputStream in = response.body().byteStream();
                 FileOutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        }
        return output;
    }

    private File downloadsDir() {
        File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            dir = new File(getFilesDir(), "downloads");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return getCacheDir();
        }
        return dir;
    }

    private void importDownloaded(File file) {
        File[] dirs = contentDirectories();
        if (dirs == null) {
            Toast.makeText(this, R.string.not_found_version, Toast.LENGTH_SHORT).show();
            return;
        }

        contentImporter.importContent(Collections.singletonList(Uri.fromFile(file)),
                dirs[0], dirs[1], dirs[2], dirs[3],
                new ContentImporter.ImportCallback() {
                    @Override
                    public void onSuccess(String message) {
                        runOnUiThread(() -> Toast.makeText(ModrinthDetailActivity.this,
                                message, Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> Toast.makeText(ModrinthDetailActivity.this,
                                error, Toast.LENGTH_LONG).show());
                    }

                    @Override
                    public void onProgress(int value) {
                    }
                });
    }

    private File[] contentDirectories() {
        VersionManager versionManager = VersionManager.get(this);
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) {
            return null;
        }

        android.content.SharedPreferences prefs = getSharedPreferences("content_management", MODE_PRIVATE);
        FeatureSettings.StorageType storageType;
        try {
            storageType = FeatureSettings.StorageType.valueOf(
                    prefs.getString("storage_type", "INTERNAL"));
        } catch (Exception ignored) {
            storageType = FeatureSettings.StorageType.INTERNAL;
        }
        storageType = LauncherStorage.normalizeContentStorageType(
                storageType, currentVersion.versionIsolation);

        File gameDataDir = LauncherStorage.getContentGameDataDir(
                this, currentVersion.getStorageProfileId(), storageType);
        if (gameDataDir == null) {
            return null;
        }

        return new File[]{
                new File(gameDataDir, "resource_packs"),
                new File(gameDataDir, "behavior_packs"),
                new File(gameDataDir, "skin_packs"),
                new File(gameDataDir, "minecraftWorlds")
        };
    }

    private static String formatCount(long value) {
        if (value >= 1_000_000L) {
            return String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000L) {
            return String.format(java.util.Locale.US, "%.1fK", value / 1_000.0);
        }
        return String.valueOf(value);
    }

    @Override
    protected void onDestroy() {
        // The importer owns a single-thread executor; without this its thread outlives the screen.
        if (contentImporter != null) {
            contentImporter.shutdown();
            contentImporter = null;
        }
        worker.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

}
