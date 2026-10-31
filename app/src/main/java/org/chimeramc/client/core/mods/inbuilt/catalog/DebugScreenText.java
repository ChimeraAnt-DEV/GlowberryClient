package org.chimeramc.client.core.mods.inbuilt.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The body of the client's debug screen, laid out the way the Java Edition F3 overlay is.
 *
 * <p>Phase 7 asks for a debug menu that works like the Java Edition one. The shape of F3 is a
 * left-aligned block of labelled lines: the version, the frame rate, the position, the block and
 * chunk the player is standing in, the facing, and the memory use. This class produces exactly that
 * block from a {@link Snapshot} the caller fills from feeds the client already reads.
 *
 * <p>It is deliberately English and literal, matching F3, which is not localised in Java Edition
 * either: a debug overlay a bug report is read against should not change wording per locale. That
 * also makes the whole block pure and unit-testable without a device.
 *
 * <p><b>Honest scope.</b> A line F3 shows that this build cannot source (the entity count, the
 * biome, the light level — all of which need the game's own world structures, which the stripped
 * build does not expose) is written as "unavailable" rather than omitted or faked. A debug screen
 * that silently drops a line reads as if the value were fine; one that invents a value is worse.
 */
public final class DebugScreenText {

    private DebugScreenText() {
    }

    /** Everything the debug block is built from. Plain fields so a test can set them directly. */
    public static final class Snapshot {
        public String clientVersion = "unknown";
        public String gameVersion = "unknown";
        public String abi = "unknown";
        /** Frames per second over the last second, or 0 before two frames are known. */
        public int fps;
        /** The 1% low frame rate (the worst frame in the window), or 0 when unknown. */
        public int onePercentLowFps;
        /** The player's world position, or null when the feed has no reading. */
        public float[] position;
        /** The player's {yaw, pitch}, or null when the feed has no reading. */
        public float[] rotation;
        /** The player's health reading, or null. */
        public float[] health;
        /** Horizontal speed in blocks per second, or NaN when not measured. */
        public double speed = Double.NaN;
        /** Heap used and total, in megabytes, or -1 when unknown. */
        public long usedMemoryMb = -1;
        public long totalMemoryMb = -1;
        /** The smoothed render-thread frame interval in microseconds, or -1 when unavailable. */
        public int frameAverageUs = -1;
        /** The worst recent frame interval in microseconds, or -1. */
        public int frameWorstUs = -1;
        /** True when the worst interval exceeded 1.5x the expected one. */
        public boolean frameJittering;
        /** True when the frame interval matched the display interval. */
        public boolean frameVsyncLocked;
        /** True when a frame-pacing reading exists at all this session. */
        public boolean framePacingAvailable;
    }

    /** The full debug block, one line per entry, without a trailing newline. */
    public static String build(Snapshot s) {
        Snapshot v = s == null ? new Snapshot() : s;
        List<String> lines = new ArrayList<>();

        lines.add("Glowberry Client " + text(v.clientVersion));
        lines.add("Minecraft " + text(v.gameVersion) + " (" + text(v.abi) + ")");
        lines.add(frameRateLine(v));
        lines.add("Frame: " + framePacingText(v));
        lines.add(positionLine(v));
        lines.add(blockLine(v));
        lines.add(chunkLine(v));
        lines.add(facingLine(v));
        lines.add(speedLine(v));
        lines.add(healthLine(v));
        lines.add(memoryLine(v));
        // Lines F3 shows that need world structures this build does not expose. Named as
        // unavailable rather than dropped, so a missing line is never read as a healthy value.
        lines.add("Entities: unavailable (needs the native entity list)");
        lines.add("Biome: unavailable (needs the native world feed)");
        lines.add("Light: unavailable (needs the native world feed)");

        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) b.append('\n');
            b.append(lines.get(i));
        }
        return b.toString();
    }

    /** "FPS: 60 (1% low 41)". The low is omitted when the window has no reading for it. */
    private static String frameRateLine(Snapshot v) {
        if (v.fps <= 0) return "FPS: unavailable";
        if (v.onePercentLowFps > 0 && v.onePercentLowFps != v.fps) {
            return "FPS: " + v.fps + " (1% low " + v.onePercentLowFps + ")";
        }
        return "FPS: " + v.fps;
    }

    /**
     * The render-thread frame interval line. The verdict never claims a lock without a reading, and
     * "no reading yet" is distinct from a bad interval — the renderer has to produce two frames
     * before any interval exists.
     */
    private static String framePacingText(Snapshot v) {
        if (!v.framePacingAvailable || v.frameAverageUs < 0) return "no reading yet";
        StringBuilder s = new StringBuilder();
        s.append(formatMicros(v.frameAverageUs)).append(" average");
        if (v.frameWorstUs >= 0) {
            s.append(", ").append(formatMicros(v.frameWorstUs)).append(" worst");
        }
        if (v.frameJittering) {
            s.append(" (hitching)");
        } else if (v.frameVsyncLocked) {
            s.append(" (vsync-locked)");
        } else {
            s.append(" (steady)");
        }
        return s.toString();
    }

    private static String positionLine(Snapshot v) {
        String coords = LocalPlayerReadouts.coordinates(v.position);
        return coords == null ? "XYZ: unavailable" : "XYZ: " + coords;
    }

    /** The integer block the player occupies, the way F3 shows "Block: bx by bz". */
    private static String blockLine(Snapshot v) {
        if (v.position == null || v.position.length < 3) return "Block: unavailable";
        return String.format(Locale.US, "Block: %d %d %d",
                (int) Math.floor(v.position[0]),
                (int) Math.floor(v.position[1]),
                (int) Math.floor(v.position[2]));
    }

    private static String chunkLine(Snapshot v) {
        String chunk = LocalPlayerReadouts.chunk(v.position);
        if (chunk == null) return "Chunk: unavailable";
        String origin = LocalPlayerReadouts.chunkOrigin(v.position);
        return origin == null ? "Chunk: " + chunk : "Chunk: " + chunk + " (origin " + origin + ")";
    }

    private static String facingLine(Snapshot v) {
        String direction = LocalPlayerReadouts.direction(v.rotation);
        if (direction == null) return "Facing: unavailable";
        String pitch = LocalPlayerReadouts.pitch(v.rotation);
        return pitch == null ? "Facing: " + direction : "Facing: " + direction + " pitch " + pitch;
    }

    private static String speedLine(Snapshot v) {
        String speed = LocalPlayerReadouts.speed(v.speed);
        return speed == null ? "Speed: unavailable" : "Speed: " + speed;
    }

    private static String healthLine(Snapshot v) {
        String health = LocalPlayerReadouts.health(v.health);
        return health == null ? "Health: unavailable" : "Health: " + health;
    }

    private static String memoryLine(Snapshot v) {
        if (v.usedMemoryMb < 0 || v.totalMemoryMb <= 0) return "Memory: unavailable";
        return "Memory: " + v.usedMemoryMb + " / " + v.totalMemoryMb + " MB";
    }

    /** Microseconds to a short "16.7 ms" form; a sub-millisecond value is shown in microseconds. */
    static String formatMicros(int micros) {
        if (micros < 1000) return micros + " us";
        return String.format(Locale.US, "%.1f ms", micros / 1000.0);
    }

    private static String text(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value.trim();
    }
}
