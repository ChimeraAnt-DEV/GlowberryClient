package org.chimeramc.client.core.mods.inbuilt.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The Java-Edition-style debug screen (Phase 7).
 *
 * <p>The block is pure text so every rule is pinned without a device: a value that has no reading
 * must read "unavailable" (never a fabricated zero), a present value must be formatted with the
 * same convention the catalogue readouts use, and the frame-rate line must show the 1% low beside
 * the average.
 */
public class DebugScreenTextTest {

    private static DebugScreenText.Snapshot full() {
        DebugScreenText.Snapshot s = new DebugScreenText.Snapshot();
        s.clientVersion = "1.2.3";
        s.gameVersion = "1.26.33.1";
        s.abi = "arm64-v8a";
        s.fps = 60;
        s.onePercentLowFps = 41;
        s.position = new float[] {10.5f, 64.0f, -20.25f};
        s.rotation = new float[] {180f, -12f};
        s.health = new float[] {18f};
        s.speed = 4.25;
        s.usedMemoryMb = 220;
        s.totalMemoryMb = 512;
        s.framePacingAvailable = true;
        s.frameAverageUs = 16700;
        s.frameWorstUs = 24000;
        s.frameVsyncLocked = true;
        return s;
    }

    @Test
    public void aFullSnapshotRendersEveryLineTheJavaDebugScreenShows() {
        String text = DebugScreenText.build(full());
        assertTrue(text, text.contains("Glowberry Client 1.2.3"));
        assertTrue(text, text.contains("Minecraft 1.26.33.1 (arm64-v8a)"));
        assertTrue(text, text.contains("FPS: 60 (1% low 41)"));
        assertTrue(text, text.contains("Frame: 16.7 ms average, 24.0 ms worst (vsync-locked)"));
        assertTrue(text, text.contains("XYZ: 10.5, 64.0, -20.3"));
        assertTrue(text, text.contains("Block: 10 64 -21"));
        assertTrue(text, text.contains("Chunk: 0, -2 (origin 0, -32)"));
        assertTrue(text, text.contains("Facing: N 180° pitch -12°"));
        assertTrue(text, text.contains("Speed: 4.25 b/s"));
        assertTrue(text, text.contains("Health: 18"));
        assertTrue(text, text.contains("Memory: 220 / 512 MB"));
    }

    @Test
    public void aLineWithNoReadingSaysUnavailableNeverZero() {
        // The whole point of the honest-empty-state rule: a debug screen that prints 0 for a value
        // it could not read is worse than one that says so, because 0 looks like a real reading.
        String text = DebugScreenText.build(new DebugScreenText.Snapshot());
        assertTrue(text, text.contains("FPS: unavailable"));
        assertTrue(text, text.contains("Frame: no reading yet"));
        assertTrue(text, text.contains("XYZ: unavailable"));
        assertTrue(text, text.contains("Block: unavailable"));
        assertTrue(text, text.contains("Chunk: unavailable"));
        assertTrue(text, text.contains("Facing: unavailable"));
        assertTrue(text, text.contains("Speed: unavailable"));
        assertTrue(text, text.contains("Health: unavailable"));
        assertTrue(text, text.contains("Memory: unavailable"));
        assertTrue(text, text.contains("Minecraft unknown"));
    }

    @Test
    public void worldStructuresThisBuildCannotReadAreNamedNotDropped() {
        // A missing line reads as if the value were fine; the debug screen names the three F3 lines
        // it cannot source so a report is not silently short.
        String text = DebugScreenText.build(new DebugScreenText.Snapshot());
        assertTrue(text, text.contains("Entities: unavailable"));
        assertTrue(text, text.contains("Biome: unavailable"));
        assertTrue(text, text.contains("Light: unavailable"));
    }

    @Test
    public void theOnePercentLowIsOmittedWhenItEqualsTheAverage() {
        DebugScreenText.Snapshot s = full();
        s.onePercentLowFps = s.fps;
        String text = DebugScreenText.build(s);
        assertTrue(text, text.contains("FPS: 60"));
        assertFalse(text, text.contains("1% low"));
    }

    @Test
    public void aHitchingReadingIsLabelledHitchingNotLocked() {
        DebugScreenText.Snapshot s = full();
        s.frameVsyncLocked = false;
        s.frameJittering = true;
        String text = DebugScreenText.build(s);
        assertTrue(text, text.contains("(hitching)"));
        assertFalse(text, text.contains("vsync-locked"));
    }

    @Test
    public void microsFormattingMatchesTheDiagnosticsReport() {
        assertEquals("500 us", DebugScreenText.formatMicros(500));
        assertEquals("16.7 ms", DebugScreenText.formatMicros(16700));
    }
}
