package org.chimeramc.client.launcher.core.content;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Window;
import org.chimeramc.client.util.AppExecutors;
import org.chimeramc.client.util.MainThread;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Saves a PNG of the launcher's own window.
 *
 * <p>This backs the catalogue's {@code util_screenshot}. It uses {@link PixelCopy} on the
 * activity's window, which needs no capture consent — the media-projection flow
 * ({@code createScreenCaptureIntent}) is only required to read <em>another</em> app's surface. The
 * launcher owns its own window, so capturing it is an ordinary screenshot of itself.
 *
 * <p>Honest boundary: a {@link PixelCopy} of the window reads the window's content, and a
 * {@code SurfaceView} (which is what the game renders into) may not be included by every device's
 * compositor. Where it is not, the capture is still the launcher's own UI — the caller says so
 * rather than claiming the world was captured.
 *
 * <p>The copy is delivered on the main thread by the framework; the file write runs on a background
 * thread so a large PNG never blocks input.
 */
public final class ScreenshotCapture {

    private ScreenshotCapture() {
    }

    /** Receives the saved file, or a failure. */
    public interface Callback {
        void onSaved(File file);

        void onFailed();
    }

    /**
     * Captures the activity's window into {@code screenshotsDir}.
     *
     * <p>Silently fails (calls {@link Callback#onFailed}) rather than throwing: a screenshot that
     * cannot be taken must not take the session down.
     */
    public static void capture(Activity activity, File screenshotsDir, Callback callback) {
        if (activity == null || screenshotsDir == null) {
            if (callback != null) callback.onFailed();
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            if (callback != null) callback.onFailed();
            return;
        }
        int width = window.getDecorView().getWidth();
        int height = window.getDecorView().getHeight();
        if (width <= 0 || height <= 0) {
            if (callback != null) callback.onFailed();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (callback != null) callback.onFailed();
            return;
        }
        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            if (callback != null) callback.onFailed();
            return;
        }
        try {
            PixelCopy.request(window, bitmap, result -> {
                if (result != PixelCopy.SUCCESS) {
                    bitmap.recycle();
                    if (callback != null) callback.onFailed();
                    return;
                }
                writeAsync(bitmap, screenshotsDir, callback);
            }, MainThread.handler());
        } catch (Throwable t) {
            bitmap.recycle();
            if (callback != null) callback.onFailed();
        }
    }

    private static void writeAsync(Bitmap bitmap, File dir, Callback callback) {
        AppExecutors.run(AppExecutors.Kind.DISK, "glowberry-screenshot", () -> {
            File file = null;
            try {
                if (!dir.exists()) dir.mkdirs();
                file = new File(dir, "glowberry_" + System.currentTimeMillis() + ".png");
                try (OutputStream out = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            } catch (Throwable t) {
                file = null;
            } finally {
                bitmap.recycle();
            }
            final File saved = file;
            if (callback != null) {
                MainThread.post(() -> {
                    if (saved != null) callback.onSaved(saved);
                    else callback.onFailed();
                });
            }
        });
    }
}
