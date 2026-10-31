package org.chimeramc.client.core.antegg;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.chimeramc.client.settings.LowLatencyNetworkManager;
import org.chimeramc.client.util.AppExecutors;
import org.chimeramc.client.util.MainThread;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads an {@code .AntEgg} named by a shared or pasted link, then hands it to the ordinary
 * validated loader (F1).
 *
 * <p>The download is deliberately a staging step, never an import: the file lands in the cache and
 * is then loaded through {@link AntEggLoader}, so a link import runs exactly the same validation,
 * sandbox resolution and atomic install as a picked file. There is no second code path into the
 * loader, which is what keeps "imported from a link" from being a weaker guarantee than "imported
 * from a file".
 *
 * <p>Two behaviours matter for a phone on a mobile network:
 *
 * <ul>
 *   <li><b>The size is capped before anything is written.</b> {@code Content-Length} is checked
 *       first, and the stream is also counted while it is read, because a chunked response has no
 *       declared length. A link is untrusted input, and an uncapped download is a way to fill a
 *       device's storage from a share sheet.</li>
 *   <li><b>A redirect is followed by the HTTP client, but only for http(s).</b> The scheme of the
 *       link itself was already checked by {@link AntEggLink}; OkHttp will not follow a redirect to
 *       a non-http scheme, so the allow-list cannot be widened by a server's response.</li>
 * </ul>
 *
 * <p>Callbacks are posted to the main thread, so a caller can touch views directly.
 */
public final class AntEggLinkDownloader {

    /** The largest package we will fetch from a link. */
    public static final long MAX_BYTES = 64L * 1024L * 1024L;

    private static final int BUFFER_SIZE = 65536;

    private final OkHttpClient client;
    private final Handler handler = MainThread.handler();

    public AntEggLinkDownloader() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true);
        LowLatencyNetworkManager.configure(builder);
        this.client = builder.build();
    }

    /** Progress is reported as whole percent, and only while the total size is known. */
    public interface ProgressListener {
        void onProgress(int percent);
    }

    /** The staged file on success, or the reason on failure. Both run on the main thread. */
    public interface Callback {
        void onSuccess(File staged, String fileName);

        void onError(String message);
    }

    /**
     * Fetches {@code url} into the cache and reports the staged file.
     *
     * <p>The caller owns the staged file and must delete it. A link that is not importable is
     * refused here as well as at the UI, so a direct caller cannot bypass the rule.
     */
    public void download(Context context, String url, ProgressListener listener, Callback callback) {
        if (!AntEggLink.isImportable(url)) {
            post(() -> callback.onError("That link does not point at an .AntEgg package."));
            return;
        }
        final String fileName = AntEggLink.fileNameOf(url);
        final Context appContext = context.getApplicationContext();
        AppExecutors.run(AppExecutors.Kind.NETWORK, "antegg-download", () -> {
            File staged = null;
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Accept", "*/*")
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("The server answered " + response.code() + ".");
                    }
                    ResponseBody body = response.body();
                    if (body == null) throw new IOException("The download returned no content.");

                    long total = body.contentLength();
                    if (total > MAX_BYTES) {
                        throw new IOException("That package is larger than the "
                                + (MAX_BYTES / (1024 * 1024)) + " MB limit.");
                    }

                    staged = new File(appContext.getCacheDir(),
                            "antegg_link_" + System.currentTimeMillis() + AntEggPackage.EXTENSION);
                    long read = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    try (InputStream in = body.byteStream();
                         OutputStream out = new FileOutputStream(staged)) {
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            read += count;
                            // A chunked response declares no length, so the running count is the
                            // only bound that holds.
                            if (read > MAX_BYTES) {
                                throw new IOException("That package is larger than the "
                                        + (MAX_BYTES / (1024 * 1024)) + " MB limit.");
                            }
                            out.write(buffer, 0, count);
                            if (listener != null && total > 0) {
                                int percent = (int) Math.min(100, (read * 100) / total);
                                post(() -> listener.onProgress(percent));
                            }
                        }
                    }
                }
                File result = staged;
                post(() -> callback.onSuccess(result, fileName));
            } catch (Exception e) {
                if (staged != null && staged.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    staged.delete();
                }
                String message = e.getMessage() == null ? "The download failed." : e.getMessage();
                post(() -> callback.onError(message));
            }
        });
    }

    private void post(Runnable runnable) {
        handler.post(runnable);
    }
}
