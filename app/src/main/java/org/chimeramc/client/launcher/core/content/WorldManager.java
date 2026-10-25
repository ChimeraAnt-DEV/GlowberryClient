package org.chimeramc.client.core.content;

import org.chimeramc.client.util.SafeExecutor;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.util.LauncherStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.chimeramc.client.launcher.core.minecraft.UserFacingError;

public class WorldManager {
    private static final String TAG = "WorldManager";
    private static final int BUFFER_SIZE = 8192;
    
    private final Context context;
    private final ExecutorService executor;
    private File worldsDirectory;
    
    public interface WorldOperationCallback {
        void onSuccess(String message);
        void onError(String error);
        void onProgress(int progress);
    }

    public WorldManager(Context context) {
        this.context = context;
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Submits world work, closing the race the bare {@code isShutdown()} check left open.
     *
     * <p>{@code if (executor.isShutdown()) return;} followed by {@code executor.execute(...)} is a
     * check-then-act: {@link #shutdown()} can run between the two on another thread (the user backs
     * out of the screen while a world operation is starting), and submitting to a shut-down executor
     * throws {@link java.util.concurrent.RejectedExecutionException} on the calling - main - thread.
     * The check is kept as a fast path so a shut-down manager still reports a cause, and the catch is
     * what makes the race harmless.
     */
    private boolean submit(Runnable work, WorldOperationCallback callback) {
        if (!SafeExecutor.submit(executor, work)) {
            callback.onError("WorldManager has been shut down");
            return false;
        }
        return true;
    }

    public void setCurrentVersion(GameVersion version) {
        if (version != null && version.versionDir != null) {
            this.worldsDirectory = new File(
                    LauncherStorage.getProfileGameDataDir(context, version.getStorageProfileId()),
                    "minecraftWorlds"
            );
            if (!worldsDirectory.exists()) {
                worldsDirectory.mkdirs();
            }
        } else {
            this.worldsDirectory = null;
        }
    }

    public void setWorldsDirectory(File directory) {
        this.worldsDirectory = directory;
        if (worldsDirectory != null && !worldsDirectory.exists()) {
            worldsDirectory.mkdirs();
        }
    }

    public List<WorldItem> getWorlds() {
        List<WorldItem> worlds = new ArrayList<>();
        
        if (worldsDirectory == null || !worldsDirectory.exists()) {
            return worlds;
        }

        File[] worldDirs = worldsDirectory.listFiles(File::isDirectory);
        if (worldDirs != null) {
            for (File worldDir : worldDirs) {
                WorldItem world = new WorldItem(worldDir.getName(), worldDir);
                if (world.isValid()) {
                    worlds.add(world);
                }
            }
        }

        return worlds;
    }

    public void importWorld(Uri worldUri, WorldOperationCallback callback) {
        submit(() -> {
            try {
                if (worldsDirectory == null) {
                    callback.onError("No version selected");
                    return;
                }

                String tempDirName = "temp_world_" + System.currentTimeMillis();
                File tempDir = new File(context.getCacheDir(), tempDirName);
                tempDir.mkdirs();

                File tempZip = new File(context.getCacheDir(), tempDirName + "_zip.mcworld");

                // The source stream is a resource for the whole staging step, so it is opened in
                // the try-with-resources rather than closed by hand in a finally. Anything thrown
                // between the open and the try - a mkdirs the platform refuses - used to leave the
                // descriptor open.
                try (InputStream inputStream = context.getContentResolver().openInputStream(worldUri)) {
                    if (inputStream == null) {
                        callback.onError("Cannot open world file");
                        return;
                    }
                    try {
                        copyStreamToFile(inputStream, tempZip);
                        extractZip(tempZip, tempDir, callback);

                        File worldDir = findWorldDirectory(tempDir);
                        if (worldDir == null) {
                            callback.onError("Invalid world file - no world data found");
                            return;
                        }

                        String worldName = generateUniqueWorldName(worldDir.getName(), worldsDirectory);
                        File targetDir = new File(worldsDirectory, worldName);

                        copyDirectory(worldDir, targetDir);

                        callback.onSuccess("World imported successfully");

                    } finally {
                        deleteDirectory(tempDir);
                        tempZip.delete();
                    }
                }

            } catch (Throwable e) {
                // Throwable, not Exception: these handlers are the only thing that answers
                // the callback, and a world copy is a large allocation - an OutOfMemoryError
                // skipped them, leaving the screen waiting on a result that never arrived.
                Log.e(TAG, "Failed to import world", e);
                callback.onError(UserFacingError.shortReasonForText(context, "Import failed", e.getMessage()));
            }
        }, callback);
    }

    public void exportWorld(WorldItem world, Uri exportUri, WorldOperationCallback callback) {
        submit(() -> {
            try {
                OutputStream outputStream = context.getContentResolver().openOutputStream(exportUri);
                if (outputStream == null) {
                    callback.onError("Cannot create export file");
                    return;
                }

                try {
                    createWorldZip(world.getFile(), outputStream, callback);
                    callback.onSuccess("World exported successfully");
                } finally {
                    outputStream.close();
                }

            } catch (Throwable e) {
                // Throwable, not Exception: these handlers are the only thing that answers
                // the callback, and a world copy is a large allocation - an OutOfMemoryError
                // skipped them, leaving the screen waiting on a result that never arrived.
                Log.e(TAG, "Failed to export world", e);
                callback.onError(UserFacingError.shortReasonForText(context, "Export failed", e.getMessage()));
            }
        }, callback);
    }

    public void deleteWorld(WorldItem world, WorldOperationCallback callback) {
        submit(() -> {
            try {
                createBackup(world);
                
                if (deleteDirectory(world.getFile())) {
                    callback.onSuccess("World deleted successfully");
                } else {
                    callback.onError("Failed to delete world");
                }

            } catch (Throwable e) {
                // Throwable, not Exception: these handlers are the only thing that answers
                // the callback, and a world copy is a large allocation - an OutOfMemoryError
                // skipped them, leaving the screen waiting on a result that never arrived.
                Log.e(TAG, "Failed to delete world", e);
                callback.onError(UserFacingError.shortReasonForText(context, "Delete failed", e.getMessage()));
            }
        }, callback);
    }

    public void backupWorld(WorldItem world, WorldOperationCallback callback) {
        submit(() -> {
            try {
                String backupPath = createBackup(world);
                callback.onSuccess("Backup created: " + backupPath);

            } catch (Throwable e) {
                // Throwable, not Exception: these handlers are the only thing that answers
                // the callback, and a world copy is a large allocation - an OutOfMemoryError
                // skipped them, leaving the screen waiting on a result that never arrived.
                Log.e(TAG, "Failed to backup world", e);
                callback.onError(UserFacingError.shortReasonForText(context, "Backup failed", e.getMessage()));
            }
        }, callback);
    }

    private void extractZip(File zipFile, File targetDir, WorldOperationCallback callback) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[BUFFER_SIZE];
            
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File entryFile = new File(targetDir, entry.getName());

                if (!entryFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath())) {
                    continue;
                }
                
                if (entry.isDirectory()) {
                    entryFile.mkdirs();
                } else {
                    entryFile.getParentFile().mkdirs();
                    try (InputStream is = zip.getInputStream(entry);
                         FileOutputStream fos = new FileOutputStream(entryFile)) {
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }

    private File findWorldDirectory(File searchDir) {
        File levelDat = new File(searchDir, "level.dat");
        if (levelDat.exists()) {
            return searchDir;
        }
        
        File[] files = searchDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    File levelDatInSubdir = new File(file, "level.dat");
                    if (levelDatInSubdir.exists()) {
                        return file;
                    }
                    File found = findWorldDirectory(file);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private String generateUniqueWorldName(String baseName, File directory) {
        String worldName = baseName;
        int counter = 1;
        
        while (new File(directory, worldName).exists()) {
            worldName = baseName + "_" + counter;
            counter++;
        }
        
        return worldName;
    }

    private void copyStreamToFile(InputStream input, File output) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(output)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = input.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    private void copyDirectory(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists()) {
                target.mkdirs();
            }
            
            File[] files = source.listFiles();
            if (files != null) {
                for (File file : files) {
                    copyDirectory(file, new File(target, file.getName()));
                }
            }
        } else {
            copyFile(source, target);
        }
    }

    private void copyFile(File source, File target) throws IOException {
        target.getParentFile().mkdirs();
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(target)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    private void createWorldZip(File worldDir, OutputStream outputStream, WorldOperationCallback callback) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(outputStream);
        zipDirectory(worldDir, "", zos);
        zos.close();
    }

    private void zipDirectory(File dir, String basePath, ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                String entryPath = basePath.isEmpty() ? file.getName() : basePath + "/" + file.getName();
                if (file.isDirectory()) {
                    zipDirectory(file, entryPath, zos);
                } else {
                    ZipEntry entry = new ZipEntry(entryPath);
                    zos.putNextEntry(entry);
                    
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int len;
                        while ((len = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }
                    zos.closeEntry();
                }
            }
        }
    }

    private String createBackup(WorldItem world) throws IOException {
        File backupDir = LauncherStorage.getWorldBackupsDir(context);
        backupDir.mkdirs();
        
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String backupName = world.getName() + "_" + timestamp + ".mcworld";
        File backupFile = new File(backupDir, backupName);
        
        try (FileOutputStream fos = new FileOutputStream(backupFile)) {
            createWorldZip(world.getFile(), fos, null);
        }
        
        return backupFile.getAbsolutePath();
    }

    private boolean deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return false;
        
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        return dir.delete();
    }

    public void shutdown() {
        executor.shutdown();
    }

    public void transferWorld(WorldItem world, File targetDirectory, WorldOperationCallback callback) {
        submit(() -> {
            try {
                if (targetDirectory == null) {
                    callback.onError("Target directory not available");
                    return;
                }

                if (!targetDirectory.exists()) {
                    targetDirectory.mkdirs();
                }

                File sourceDir = world.getFile();
                if (sourceDir == null || !sourceDir.exists()) {
                    callback.onError("Source world not found");
                    return;
                }

                if (sourceDir.getParentFile().equals(targetDirectory)) {
                    callback.onError("Content is already in this location");
                    return;
                }

                String worldName = generateUniqueWorldName(sourceDir.getName(), targetDirectory);
                File targetDir = new File(targetDirectory, worldName);

                copyDirectory(sourceDir, targetDir);
                deleteDirectory(sourceDir);

                callback.onSuccess("World transferred successfully");

            } catch (Throwable e) {
                // Throwable, not Exception: these handlers are the only thing that answers
                // the callback, and a world copy is a large allocation - an OutOfMemoryError
                // skipped them, leaving the screen waiting on a result that never arrived.
                Log.e(TAG, "Failed to transfer world", e);
                callback.onError(UserFacingError.shortReasonForText(context, "Transfer failed", e.getMessage()));
            }
        }, callback);
    }
}
