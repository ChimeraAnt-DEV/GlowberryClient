package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.os.SystemClock;

import org.chimeramc.client.BuildConfig;
import org.chimeramc.client.core.mods.inbuilt.catalog.DebugScreenText;
import org.chimeramc.client.core.mods.inbuilt.model.ModIds;
import org.chimeramc.client.preloader.PreloaderInput;

/**
 * The in-game debug screen, built in the shape of the Java Edition F3 overlay: version, frame
 * rate, position, block and chunk, facing, speed, health and memory.
 *
 * <p>Everything here comes from data the client already reads — the local-player feed
 * ({@link PreloaderInput}), the host frame meter and the render-thread frame-pacing feed — so it
 * needs no native entity feed. The block itself is assembled by the pure {@link DebugScreenText},
 * which shares {@code LocalPlayerReadouts} with the catalogue's coordinates/direction/chunk
 * modules, so the debug menu and those readouts cannot disagree about which way is north or where
 * a chunk starts.
 *
 * <p>Lines the Java F3 screen shows but this build cannot source are named "unavailable" rather
 * than omitted or faked: the entity count, the biome and the light level all need the game's own
 * world structures, which the shipped builds do not expose (game classes export no symbols). An
 * omitted line reads as if the value were fine; an invented one is worse.
 */
public class DebugMenuOverlay extends TextOverlay {

    private final FrameRateMeter frameMeter = new FrameRateMeter();

    public DebugMenuOverlay(Activity activity) {
        super(activity, ModIds.DEBUG_MENU);
    }

    @Override
    public void show(int startX, int startY) {
        super.show(startX, startY);
        try {
            frameMeter.start(fps -> { });
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void hide() {
        try {
            frameMeter.stop();
        } catch (Throwable ignored) {
        }
        super.hide();
    }

    @Override
    protected long refreshIntervalMs() {
        return 500L;
    }

    @Override
    protected String buildText() {
        DebugScreenText.Snapshot s = new DebugScreenText.Snapshot();
        s.clientVersion = BuildConfig.VERSION_NAME;
        s.gameVersion = selectedGameVersion();
        s.abi = primaryAbi();
        s.fps = frameMeter.currentFps();
        s.onePercentLowFps = frameMeter.currentOnePercentLowFps();
        s.position = PreloaderInput.readLocalPlayerPosition();
        s.rotation = PreloaderInput.readLocalPlayerRotation();
        s.health = PreloaderInput.readLocalPlayerHealth();
        s.speed = measureSpeed(s.position);
        Runtime runtime = Runtime.getRuntime();
        s.usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        s.totalMemoryMb = runtime.totalMemory() / (1024 * 1024);

        int[] pacing = PreloaderInput.readFramePacing();
        if (pacing != null && pacing.length >= 4) {
            s.framePacingAvailable = true;
            s.frameAverageUs = pacing[1];
            s.frameWorstUs = pacing[2];
            s.frameJittering = (pacing[3] & 1) != 0;
            s.frameVsyncLocked = (pacing[3] & 2) != 0;
        }
        return DebugScreenText.build(s);
    }

    /** The selected instance's version code, or "none selected". Never throws. */
    private String selectedGameVersion() {
        try {
            org.chimeramc.client.core.versions.VersionManager manager =
                    org.chimeramc.client.core.versions.VersionManager.getIfInitialized();
            if (manager == null) return "none selected";
            org.chimeramc.client.core.versions.GameVersion version =
                    manager.getSelectedVersion();
            if (version == null) return "none selected";
            return version.versionCode != null && !version.versionCode.isEmpty()
                    ? version.versionCode : version.displayName;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String primaryAbi() {
        try {
            String[] abis = android.os.Build.SUPPORTED_ABIS;
            return abis != null && abis.length > 0 ? abis[0] : "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // The horizontal speed is measured from two position samples; the previous sample and its
    // timestamp are kept here rather than in the pure formatter, which only formats what it is given.
    private float[] lastPosition;
    private long lastPositionMs;

    private double measureSpeed(float[] position) {
        if (position == null || position.length < 3) return Double.NaN;
        long now = SystemClock.uptimeMillis();
        double speed = Double.NaN;
        if (lastPosition != null && now > lastPositionMs) {
            double dx = position[0] - lastPosition[0];
            double dz = position[2] - lastPosition[2];
            speed = Math.hypot(dx, dz) / ((now - lastPositionMs) / 1000.0);
        }
        lastPosition = position;
        lastPositionMs = now;
        return speed;
    }
}
