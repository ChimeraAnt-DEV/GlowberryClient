package org.chimeramc.client.core.content;

import org.chimeramc.client.util.SafeExecutor;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.chimeramc.client.core.content.leveldb.LevelDBEntry;
import org.chimeramc.client.core.content.leveldb.LevelDBManager;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.chimeramc.client.launcher.core.minecraft.UserFacingError;

public class StructureExtractor {
    private static final String TAG = "StructureExtractor";

    public interface ExtractionCallback {
        void onComplete(int extractedCount, String outputPath);
        void onError(String error);
    }

    public interface StructureListCallback {
        void onComplete(List<StructureInfo> structures);
        void onError(String error);
    }

    public static class StructureInfo {
        private final String id;
        private final String name;
        private final byte[] data;

        public StructureInfo(String id, byte[] data) {
            this.id = id;
            this.data = data;
            if (id.contains(":")) {
                this.name = id.substring(id.indexOf(':') + 1);
            } else {
                this.name = id;
            }
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public byte[] getData() {
            return data;
        }

        public int getSize() {
            return data != null ? data.length : 0;
        }

        public String getFormattedSize() {
            int size = getSize();
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        }

        public String getFileName() {
            return sanitizeFileName(name) + ".mcstructure";
        }

        private String sanitizeFileName(String name) {
            return name.replace("/", "_")
                       .replace("\\", "_")
                       .replace(":", "_")
                       .replace("*", "_")
                       .replace("?", "_")
                       .replace("\"", "_")
                       .replace("<", "_")
                       .replace(">", "_")
                       .replace("|", "_");
        }
    }

    private final Context context;
    private final ExecutorService executor;
    private List<StructureInfo> cachedStructures;
    private File cachedWorldDir;

    public StructureExtractor(Context context) {
        this.context = context;
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void loadStructures(File worldDir, StructureListCallback callback) {
        if (!SafeExecutor.submit(executor, () -> {
            LevelDBManager dbManager = null;
            try {
                dbManager = new LevelDBManager(worldDir);
                final LevelDBManager finalDbManager = dbManager;

                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<List<LevelDBEntry>> structuresRef = new AtomicReference<>();
                AtomicReference<String> errorRef = new AtomicReference<>();

                finalDbManager.loadDatabase(new LevelDBManager.LoadCallback() {
                    @Override
                    public void onProgress(int current, int total) {}

                    @Override
                    public void onComplete(List<LevelDBEntry> entries) {
                        structuresRef.set(finalDbManager.getStructureEntries());
                        latch.countDown();
                    }

                    @Override
                    public void onError(String error) {
                        errorRef.set(error);
                        latch.countDown();
                    }
                });

                boolean completed = latch.await(60, TimeUnit.SECONDS);

                if (!completed) {
                    callback.onError("Database loading timed out");
                    finalDbManager.shutdown();
                    return;
                }

                if (errorRef.get() != null) {
                    // The LevelDB layer reports a raw message; classify it here so the toast names
                    // the cause instead of echoing an exception's text.
                    callback.onError(UserFacingError.shortReasonForText(
                            context, "Failed to load structures", errorRef.get()));
                    finalDbManager.shutdown();
                    return;
                }

                List<LevelDBEntry> structureEntries = structuresRef.get();
                List<StructureInfo> structures = new ArrayList<>();

                if (structureEntries != null) {
                    for (LevelDBEntry entry : structureEntries) {
                        String structureId = entry.getKey().getStructureId();
                        byte[] value = entry.getValue();
                        if (structureId != null && !structureId.isEmpty() && value != null && value.length > 0) {
                            structures.add(new StructureInfo(structureId, value));
                        }
                    }
                }

                cachedStructures = structures;
                cachedWorldDir = worldDir;

                finalDbManager.shutdown();
                callback.onComplete(structures);

            } catch (Throwable e) {
                // Throwable, not Exception: this handler is the only thing that answers the callback.
                Log.e(TAG, "Failed to load structures", e);
                callback.onError(UserFacingError.shortReasonForText(context, "Failed to load structures", e.getMessage()));
                if (dbManager != null) {
                    dbManager.shutdown();
                }
            }
        })) {
            // shutdown() tore the pool down under the caller; answer the callback rather than
            // leaving the screen waiting on a result that cannot arrive.
            callback.onError("The structure extractor has been shut down");
        }
    }

    public void exportSingleStructure(StructureInfo structure, Uri outputUri, ExtractionCallback callback) {
        if (!SafeExecutor.submit(executor, () -> {
            // try-with-resources: the old code closed the stream by hand inside each branch, so a
            // write that failed partway (a full or unwritable destination) leaked the descriptor
            // and left a half-written file behind.
            try (OutputStream outputStream = context.getContentResolver().openOutputStream(outputUri)) {
                if (outputStream == null) {
                    callback.onError("Cannot create output file");
                    return;
                }

                byte[] data = structure.getData();
                if (data != null && data.length > 0) {
                    outputStream.write(data);
                    outputStream.flush();
                    callback.onComplete(1, outputUri.toString());
                } else {
                    callback.onError("Structure has no data");
                }

            } catch (Throwable e) {
                // Throwable, not Exception: this handler is the only thing that answers the callback.
                Log.e(TAG, "Failed to export structure", e);
                callback.onError(UserFacingError.shortReasonForText(context, "Export failed", e.getMessage()));
            }
        })) {
            // shutdown() tore the pool down under the caller; answer the callback rather than
            // leaving the screen waiting on a result that cannot arrive.
            callback.onError("The structure extractor has been shut down");
        }
    }

    public void shutdown() {
        executor.shutdown();
        cachedStructures = null;
        cachedWorldDir = null;
    }
}
