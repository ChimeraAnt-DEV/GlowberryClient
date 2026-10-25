package org.chimeramc.client.core.content;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.launcher.core.minecraft.UserFacingError;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ContentManager {
    private static final String TAG = "ContentManager";
    private static ContentManager instance;
    
    private final Context context;
    private final WorldManager worldManager;
    private final ResourcePackManager resourcePackManager;
    private final ScreenshotManager screenshotManager;
    private final ServerManager serverManager;
    private final ExecutorService refreshExecutor;
    
    private GameVersion currentVersion;
    private final MutableLiveData<List<WorldItem>> worldsLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<ResourcePackItem>> resourcePacksLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<ResourcePackItem>> behaviorPacksLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<ResourcePackItem>> skinPacksLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<ScreenshotItem>> screenshotsLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<ServerItem>> serversLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> statusLiveData = new MutableLiveData<>();

    private ContentManager(Context context) {
        this.context = context.getApplicationContext();
        this.worldManager = new WorldManager(this.context);
        this.resourcePackManager = new ResourcePackManager(this.context);
        this.screenshotManager = new ScreenshotManager();
        this.serverManager = new ServerManager();
        this.refreshExecutor = Executors.newSingleThreadExecutor();
    }

    public static synchronized ContentManager getInstance(Context context) {
        if (instance == null) {
            instance = new ContentManager(context);
        }
        return instance;
    }

    public void setCurrentVersion(GameVersion version) {
        this.currentVersion = version;
        worldManager.setCurrentVersion(version);
        resourcePackManager.setCurrentVersion(version);
        refreshContent();
    }

    public void setStorageDirectories(File worldsDir, File resourcePacksDir, File behaviorPacksDir, File skinPacksDir, File screenshotsDir, File minecraftPeDir) {
        worldManager.setWorldsDirectory(worldsDir);
        resourcePackManager.setPackDirectories(resourcePacksDir, behaviorPacksDir, skinPacksDir);
        screenshotManager.setScreenshotsDirectory(screenshotsDir);
        serverManager.setMinecraftPeDirectory(minecraftPeDir);
        refreshContent();
    }

    public void refreshContent() {
        refreshWorlds();
        refreshResourcePacks();
        refreshBehaviorPacks();
        refreshSkinPacks();
        refreshScreenshots();
        refreshServers();
    }

    public void refreshWorlds() {
        runRefresh(worldsLiveData, worldManager::getWorlds);
    }

    public void refreshResourcePacks() {
        runRefresh(resourcePacksLiveData, resourcePackManager::getResourcePacks);
    }

    public void refreshBehaviorPacks() {
        runRefresh(behaviorPacksLiveData, resourcePackManager::getBehaviorPacks);
    }

    public void refreshSkinPacks() {
        runRefresh(skinPacksLiveData, resourcePackManager::getSkinPacks);
    }

    public void refreshScreenshots() {
        runRefresh(screenshotsLiveData, screenshotManager::getScreenshots);
    }

    public void refreshServers() {
        runRefresh(serversLiveData, serverManager::getServers);
    }

    /**
     * Runs one directory scan on the refresh worker and always publishes a result.
     *
     * <p>The screens clear their loading overlay from the LiveData observer, so a scan that threw
     * without posting left that overlay up forever and the tab unusable - the failure was invisible
     * because the only surface that would have reported it was the one that never got called. A
     * failed scan now publishes an empty list (the same thing a genuinely empty directory
     * produces), so the screen settles and the user sees an empty tab rather than a dead spinner.
     */
    private <T> void runRefresh(MutableLiveData<List<T>> target, java.util.concurrent.Callable<List<T>> scan) {
        try {
            refreshExecutor.execute(() -> {
                List<T> result;
                try {
                    result = scan.call();
                } catch (Throwable t) {
                    Log.w(TAG, "Content refresh failed; publishing an empty result", t);
                    result = new ArrayList<>();
                }
                target.postValue(result);
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // The manager was shut down under the caller; settle the screen rather than leaving it
            // waiting on a result that can never arrive.
            target.postValue(new ArrayList<>());
        }
    }

    /**
     * Runs a content operation (delete/add/import) on the refresh worker and always answers its
     * callback.
     *
     * <p>Two things used to go wrong here and each one stranded the screen: submitting to a
     * {@link #shutdown()} executor threw on the caller's thread, and a throw from the operation
     * skipped the callback, so the dialog or row waiting on it never settled. A failure is
     * marshalled to the main thread, where the callbacks and the LiveData they refresh expect to
     * run.
     */
    private void runOperation(Runnable work, ContentOperationCallback callback) {
        final android.os.Handler mainHandler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        try {
            refreshExecutor.execute(() -> {
                try {
                    work.run();
                } catch (Throwable t) {
                    Log.w(TAG, "Content operation failed", t);
                    final String reason = UserFacingError.reason(context, t);
                    mainHandler.post(() -> {
                        setStatus(reason);
                        if (callback != null) callback.onError(reason);
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // The manager was shut down under the caller; answer the callback rather than leaving
            // the screen waiting on a result that can never arrive.
            if (callback != null) callback.onError("The content manager has been shut down");
        }
    }

    public LiveData<List<WorldItem>> getWorldsLiveData() {
        return worldsLiveData;
    }

    public LiveData<List<ResourcePackItem>> getResourcePacksLiveData() {
        return resourcePacksLiveData;
    }

    public LiveData<List<ResourcePackItem>> getBehaviorPacksLiveData() {
        return behaviorPacksLiveData;
    }

    public LiveData<List<ResourcePackItem>> getSkinPacksLiveData() {
        return skinPacksLiveData;
    }

    public LiveData<List<ScreenshotItem>> getScreenshotsLiveData() {
        return screenshotsLiveData;
    }

    public LiveData<List<ServerItem>> getServersLiveData() {
        return serversLiveData;
    }

    public LiveData<String> getStatusLiveData() {
        return statusLiveData;
    }

    public void setStatus(String status) {
        statusLiveData.postValue(status);
    }

    public interface ContentOperationCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    public void deleteScreenshot(ScreenshotItem screenshot, ContentOperationCallback callback) {
        setStatus("Deleting screenshot...");
        runOperation(() -> {
            if (screenshotManager.deleteScreenshot(screenshot)) {
                refreshScreenshots();
                setStatus("Screenshot deleted");
                if (callback != null) callback.onSuccess("Screenshot deleted");
            } else {
                setStatus("Failed to delete screenshot");
                if (callback != null) callback.onError("Failed to delete screenshot");
            }
        }, callback);
    }

    public void deleteServer(ServerItem server, ContentOperationCallback callback) {
        setStatus("Deleting server...");
        runOperation(() -> {
            if (serverManager.deleteServer(server)) {
                refreshServers();
                setStatus("Server deleted");
                if (callback != null) callback.onSuccess("Server deleted");
            } else {
                setStatus("Failed to delete server");
                if (callback != null) callback.onError("Failed to delete server");
            }
        }, callback);
    }

    public void addServer(ServerItem server, ContentOperationCallback callback) {
        setStatus("Adding server...");
        runOperation(() -> {
            ServerManager.AddResult result = serverManager.addServer(server);
            if (result == ServerManager.AddResult.ADDED) {
                refreshServers();
                setStatus("Server added");
                if (callback != null) callback.onSuccess("Server added");
            } else {
                // A duplicate and a write failure are different problems with different fixes; the
                // boolean this replaced reported both as "Failed to add server", and the Quick
                // Launch path reported both as "already in your list".
                String message = result == ServerManager.AddResult.DUPLICATE
                        ? "That server is already in your list"
                        : "Could not add the server";
                setStatus(message);
                if (callback != null) callback.onError(message);
            }
        }, callback);
    }

    public void shutdown() {
        worldManager.shutdown();
        resourcePackManager.shutdown();
        refreshExecutor.shutdown();
    }
    
    public void importWorld(android.net.Uri worldUri, WorldManager.WorldOperationCallback callback) {
        setStatus("Importing world...");
        worldManager.importWorld(worldUri, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                refreshWorlds();
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Import failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                setStatus("Importing world... " + progress + "%");
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void exportWorld(WorldItem world, android.net.Uri exportUri, WorldManager.WorldOperationCallback callback) {
        setStatus("Exporting world...");
        worldManager.exportWorld(world, exportUri, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Export failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                setStatus("Exporting world... " + progress + "%");
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void deleteWorld(WorldItem world, WorldManager.WorldOperationCallback callback) {
        setStatus("Deleting world...");
        worldManager.deleteWorld(world, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                refreshWorlds();
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Delete failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void backupWorld(WorldItem world, WorldManager.WorldOperationCallback callback) {
        setStatus("Creating backup...");
        worldManager.backupWorld(world, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Backup failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                setStatus("Creating backup... " + progress + "%");
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void importResourcePack(android.net.Uri packUri, ResourcePackManager.PackOperationCallback callback) {
        setStatus("Importing resource pack...");
        resourcePackManager.importPack(packUri, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                refreshResourcePacks();
                refreshBehaviorPacks();
                refreshSkinPacks();
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Import failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                setStatus("Importing resource pack... " + progress + "%");
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void deleteResourcePack(ResourcePackItem pack, ResourcePackManager.PackOperationCallback callback) {
        setStatus("Deleting resource pack...");
        resourcePackManager.deletePack(pack, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                refreshResourcePacks();
                refreshBehaviorPacks();
                refreshSkinPacks();
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Delete failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void exportResourcePack(ResourcePackItem pack, android.net.Uri exportUri, ResourcePackManager.PackOperationCallback callback) {
        setStatus("Exporting pack...");
        resourcePackManager.exportPack(pack, exportUri, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Export failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void transferWorld(WorldItem world, java.io.File targetDirectory, WorldManager.WorldOperationCallback callback) {
        setStatus("Transferring world...");
        worldManager.transferWorld(world, targetDirectory, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                refreshWorlds();
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Transfer failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                setStatus("Transferring world... " + progress + "%");
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void transferResourcePack(ResourcePackItem pack, java.io.File targetDirectory, ResourcePackManager.PackOperationCallback callback) {
        setStatus("Transferring pack...");
        resourcePackManager.transferPack(pack, targetDirectory, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                refreshResourcePacks();
                refreshBehaviorPacks();
                refreshSkinPacks();
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Transfer failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                setStatus("Transferring pack... " + progress + "%");
                if (callback != null) callback.onProgress(progress);
            }
        });
    }
}
