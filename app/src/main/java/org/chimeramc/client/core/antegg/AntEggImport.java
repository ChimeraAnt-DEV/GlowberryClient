package org.chimeramc.client.core.antegg;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The bridge between a picked {@code content://} URI and the .AntEgg loader.
 *
 * <p>The loader is file-based (a ZIP reader needs random access to the central directory, which a
 * content stream cannot provide), so the picked document is first copied into the cache. This is
 * the same shape as the launcher's other imports, and it keeps the loader free of Android
 * {@code Uri} handling, which is what makes it unit-testable.
 */
public final class AntEggImport {

    private AntEggImport() {}

    /** Resolves the display name of a picked document, or a fallback. */
    public static String displayName(Context context, Uri uri) {
        if (context == null || uri == null) return "mod.antegg";
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && !cursor.isNull(index)) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // Fall through to the path-based name; a picker without DISPLAY_NAME is not fatal.
        }
        String path = uri.getLastPathSegment();
        return path == null || path.isEmpty() ? "mod.antegg" : new File(path).getName();
    }

    /**
     * Copies a picked document to a cache file and loads it.
     *
     * <p>Returns a {@link AntEggLoader.LoadResult}; the caller shows {@code result.error} on
     * failure. The cache copy is deleted after the load, since the loader has already extracted
     * the mod into its sandbox by then.
     */
    public static AntEggLoader.LoadResult importUri(Context context, Uri uri) {
        if (context == null || uri == null) {
            return failedResult("no file was selected");
        }
        String name = displayName(context, uri);
        if (!AntEggPackage.looksLikeAntEgg(name)) {
            return failedResult("not an .AntEgg package: " + name);
        }
        File staged = new File(context.getCacheDir(), "antegg_import_" + System.currentTimeMillis()
                + AntEggPackage.EXTENSION);
        try {
            copyToFile(context, uri, staged);
            AntEggLoader.LoadResult result = AntEggLoader.loadMod(context, staged);
            if (!result.success) {
                AntEggLoader.recordFailure(context, name, result.error);
            }
            return result;
        } catch (IOException e) {
            AntEggLoader.recordFailure(context, name, e.getMessage());
            return failedResult("could not read the package: " + e.getMessage());
        } finally {
            if (staged.exists()) {
                //noinspection ResultOfMethodCallIgnored
                staged.delete();
            }
        }
    }

    /**
     * Imports a batch of picked documents, installing a dependency before the mod that needs it
     * (item G4).
     *
     * <p>Picking several packages at once used to load them in picker order, so a selection that
     * contained both {@code lib.antegg} and the {@code plugin.antegg} that depends on it could
     * reject the plugin merely because the picker listed it first. The batch is therefore staged
     * first, read once to learn each package's id and dependencies, ordered so dependencies come
     * first, and only then loaded. The existing unmet-dependency check is unchanged: a dependency
     * that is not in the batch and not installed is still reported.
     *
     * <p>The staged copies are deleted whether or not each load succeeds, since the loader has
     * already extracted the mod into its sandbox by then.
     *
     * @return the number that loaded successfully, and the last error seen (null when all loaded)
     */
    public static BatchResult importUriBatch(Context context, List<Uri> uris) {
        if (context == null || uris == null || uris.isEmpty()) {
            return new BatchResult(0, "no files were selected");
        }
        if (uris.size() == 1) {
            AntEggLoader.LoadResult single = importUri(context, uris.get(0));
            return new BatchResult(single.success ? 1 : 0, single.success ? null : single.error);
        }

        List<File> staged = new ArrayList<>(uris.size());
        List<String> names = new ArrayList<>(uris.size());
        for (Uri uri : uris) {
            String name = displayName(context, uri);
            File copy = new File(context.getCacheDir(),
                    "antegg_import_" + System.currentTimeMillis() + "_" + staged.size()
                            + AntEggPackage.EXTENSION);
            try {
                copyToFile(context, uri, copy);
                staged.add(copy);
                names.add(name);
            } catch (IOException e) {
                AntEggLoader.recordFailure(context, name, e.getMessage());
                //noinspection ResultOfMethodCallIgnored
                copy.delete();
                staged.add(null);
                names.add(name);
            }
        }

        List<AntEggLoadOrder.Entry> entries = new ArrayList<>(staged.size());
        for (int i = 0; i < staged.size(); i++) {
            entries.add(AntEggLoadOrder.entryFor(i, staged.get(i)));
        }

        int success = 0;
        String lastError = null;
        try {
            for (int index : AntEggLoadOrder.order(entries)) {
                File copy = staged.get(index);
                String name = names.get(index);
                if (copy == null) {
                    lastError = "could not read the package: " + name;
                    continue;
                }
                AntEggLoader.LoadResult result = AntEggLoader.loadMod(context, copy);
                if (result.success) {
                    success++;
                } else {
                    AntEggLoader.recordFailure(context, name, result.error);
                    lastError = result.error;
                }
            }
        } finally {
            for (File copy : staged) {
                if (copy != null && copy.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    copy.delete();
                }
            }
        }
        return new BatchResult(success, lastError);
    }

    /** The outcome of a batch import: how many loaded, and the last failure reason if any. */
    public static final class BatchResult {
        public final int successCount;
        public final String error;

        BatchResult(int successCount, String error) {
            this.successCount = successCount;
            this.error = error;
        }
    }

    private static void copyToFile(Context context, Uri uri, File destination) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(destination)) {
            if (in == null) throw new IOException("cannot open the selected file");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static AntEggLoader.LoadResult failedResult(String message) {
        return AntEggLoader.LoadResult.fail(message);
    }
}
