package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Window;

/**
 * Captures a downscaled copy of the window behind the wheel, for the glass backdrop (R3, R12).
 *
 * <p>The capture is deliberately at a fraction of the window's size: the blurred sheet is soft
 * anyway, so a 1/4-resolution source is visually identical and one sixteenth of the memory and copy
 * cost. A failed capture (an unsupported API level, a detached window, an OEM that refuses
 * {@code PixelCopy}) leaves the last good capture in place — the wheel then draws its solid-tier
 * fill instead, which is why {@code RadialWheelMaterial} treats "no capture" as SOLID rather than as
 * an error.
 *
 * <p>Every callback runs on the main thread and is guarded by {@code Throwable}: a capture is a
 * cosmetic nicety and must never take the session down.
 */
public final class WheelBackdropCapture {

    /** The downscale divisor: a 1/4-resolution capture. */
    public static final int DOWNSCALE = 4;
    /** The largest capture either dimension may be, so a huge tablet window cannot allocate a slab. */
    public static final int MAX_DIMENSION = 512;

    /** Receives a finished capture, or a failure. */
    public interface Callback {
        void onCaptured(Bitmap capture);
        void onFailed();
    }

    /**
     * The main-thread handler, created lazily.
     *
     * <p>Deliberately not a field initialiser: constructing a {@code Handler} touches
     * {@code Looper.getMainLooper()}, which does not exist in a JVM unit test, so an eager field
     * would make this class un-constructible off-device and its pure size/schedule policy
     * untestable. Only {@link #captureIfDue} needs the handler, so only it pays for it.
     */
    private Handler mainHandler;
    private final BackdropRefreshSchedule schedule = new BackdropRefreshSchedule();
    private Bitmap lastCapture;
    private boolean captureFailed;

    /** True when the platform can capture at all ({@code PixelCopy} is API 24+, guarded for minSdk). */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /** The refresh schedule, so the caller can drive the rate limit (R3). */
    public BackdropRefreshSchedule schedule() {
        return schedule;
    }

    /**
     * The capture size for a window size, in pixels (R3).
     *
     * <p>Pure so the downscale and the bound are testable without a window: a 1/4-resolution capture
     * is visually identical to a full one for a soft sheet and a sixteenth of the copy cost, and the
     * cap stops a very large tablet window allocating a slab.
     */
    public static int captureDimension(int windowDimension) {
        if (windowDimension <= 0) return 0;
        return Math.max(1, Math.min(MAX_DIMENSION, windowDimension / DOWNSCALE));
    }

    /** The last successful capture, or null. Never recycled by the caller. */
    public Bitmap lastCapture() {
        return lastCapture;
    }

    /** Whether the most recent attempt failed; the material resolves to SOLID when true. */
    public boolean hasFailed() {
        return captureFailed;
    }

    /** True when the last attempt succeeded. */
    public boolean hasCapture() {
        return lastCapture != null && !lastCapture.isRecycled();
    }

    /**
     * Requests a capture if one is due at {@code nowMs}.
     *
     * @return true when a capture was started (the callback will fire)
     */
    public boolean captureIfDue(Window window, long nowMs, int fps, Callback callback) {
        if (!isSupported() || window == null) {
            captureFailed = true;
            return false;
        }
        if (!schedule.isDue(nowMs, fps)) return false;

        int width = 0;
        int height = 0;
        try {
            width = window.getDecorView().getWidth();
            height = window.getDecorView().getHeight();
        } catch (Throwable ignored) {
        }
        if (width <= 0 || height <= 0) return false;

        int targetW = captureDimension(width);
        int targetH = captureDimension(height);

        final Bitmap buffer;
        try {
            buffer = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            captureFailed = true;
            return false;
        }
        try {
            if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
            PixelCopy.request(window, buffer, result -> {
                schedule.markCaptured(nowMs);
                if (result == PixelCopy.SUCCESS) {
                    captureFailed = false;
                    // Hand the new capture over BEFORE recycling the previous one: the wheel now
                    // displays the capture on a layer (R3), so the old bitmap must not be recycled
                    // while that layer still points at it.
                    Bitmap previous = lastCapture;
                    lastCapture = buffer;
                    if (callback != null) callback.onCaptured(lastCapture);
                    recycleQuietly(previous);
                } else {
                    captureFailed = true;
                    recycleQuietly(buffer);
                    if (callback != null) callback.onFailed();
                }
            }, mainHandler);
        } catch (Throwable t) {
            captureFailed = true;
            recycleQuietly(buffer);
            return false;
        }
        return true;
    }

    /** Releases the held capture and resets the schedule. */
    public void release() {
        recycleQuietly(lastCapture);
        lastCapture = null;
        captureFailed = false;
        schedule.reset();
    }

    private static void recycleQuietly(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        try {
            bitmap.recycle();
        } catch (Throwable ignored) {
        }
    }
}
