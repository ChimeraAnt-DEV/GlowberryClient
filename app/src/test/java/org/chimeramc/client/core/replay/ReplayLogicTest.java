package org.chimeramc.client.core.replay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pins the Replay system's pure rules: the storage cap and its auto-prune, the highlight trigger,
 * the trim window, the library sort/filter, the control maps, the burn-in line, and the formatters.
 *
 * <p>These are the parts that decide what a player loses and when a clip is saved, so they are
 * tested without a device or a mock. The Android-facing recorder and library are separate classes.
 */
public class ReplayLogicTest {

    private static ReplayClip clip(String name, long durationMs, long sizeBytes, long recordedAtMs) {
        return new ReplayClip(new File("/replays/" + name), durationMs, sizeBytes, recordedAtMs,
                "world", "1.26.60", "survival");
    }

    private static ReplayClip clipWithSidecar(File file, long durationMs, long sizeBytes,
                                              String sidecarText) {
        return ReplayClipRepository.fromFileAndSidecar(file, sidecarText, durationMs);
    }

    // --- storage policy -------------------------------------------------------------------------

    @Test
    public void underNinetyPercentReadsOk() {
        long cap = ReplayStoragePolicy.DEFAULT_CAP_BYTES;
        assertEquals(ReplayStoragePolicy.Level.OK, ReplayStoragePolicy.level(cap / 2, cap));
        assertFalse(ReplayStoragePolicy.shouldWarn(cap / 2, cap));
    }

    @Test
    public void atNinetyPercentReadsWarn() {
        long cap = ReplayStoragePolicy.DEFAULT_CAP_BYTES;
        assertEquals(ReplayStoragePolicy.Level.WARN,
                ReplayStoragePolicy.level((long) (cap * 0.90), cap));
        assertTrue(ReplayStoragePolicy.shouldWarn((long) (cap * 0.95), cap));
    }

    @Test
    public void overCapReadsOver() {
        long cap = ReplayStoragePolicy.DEFAULT_CAP_BYTES;
        assertEquals(ReplayStoragePolicy.Level.OVER, ReplayStoragePolicy.level(cap + 1, cap));
    }

    @Test
    public void pruneTakesOldestFirstAndOnlyWhatIsNeeded() {
        long cap = 1_000L;
        List<ReplayClip> clips = new ArrayList<>(Arrays.asList(
                clip("old.mp4", 60_000, 400, 1_000),
                clip("mid.mp4", 60_000, 400, 2_000),
                clip("new.mp4", 60_000, 400, 3_000)));
        // Used 1200, cap 1000, incoming 300 -> must free 500, so the two oldest go.
        List<ReplayClip> prunable =
                ReplayStoragePolicy.selectPrunable(clips, 1_200L, cap, 300L);
        assertEquals(2, prunable.size());
        assertEquals("old.mp4", prunable.get(0).name());
        assertEquals("mid.mp4", prunable.get(1).name());
    }

    @Test
    public void pruneNeverTouchesFavorites() {
        long cap = 1_000L;
        ReplayClip favorite = clip("keep.mp4", 60_000, 900, 500);
        favorite.setFavorite(true);
        List<ReplayClip> clips = new ArrayList<>(Arrays.asList(
                favorite,
                clip("new.mp4", 60_000, 300, 2_000)));
        List<ReplayClip> prunable =
                ReplayStoragePolicy.selectPrunable(clips, 1_200L, cap, 300L);
        assertEquals(1, prunable.size());
        assertEquals("new.mp4", prunable.get(0).name());
    }

    @Test
    public void pruneIsEmptyWhenFavoritesPinTheLibraryOverTheCap() {
        long cap = 1_000L;
        ReplayClip favorite = clip("keep.mp4", 60_000, 1_500, 500);
        favorite.setFavorite(true);
        List<ReplayClip> prunable = ReplayStoragePolicy.selectPrunable(
                Collections.singletonList(favorite), 1_500L, cap, 100L);
        assertTrue(prunable.isEmpty());
        assertEquals(ReplayStoragePolicy.Level.OVER,
                ReplayStoragePolicy.level(1_500L, cap));
    }

    @Test
    public void pruneIsEmptyWhenTheIncomingClipFits() {
        List<ReplayClip> clips = Collections.singletonList(clip("a.mp4", 1_000, 100, 1L));
        assertTrue(ReplayStoragePolicy.selectPrunable(clips, 100L, 1_000L, 200L).isEmpty());
    }

    @Test
    public void capIsClampedToTheSupportedRange() {
        assertEquals(ReplayStoragePolicy.MIN_CAP_BYTES,
                ReplayStoragePolicy.clampCapBytes(1L));
        assertEquals(ReplayStoragePolicy.MAX_CAP_BYTES,
                ReplayStoragePolicy.clampCapBytes(Long.MAX_VALUE));
    }

    // --- highlight trigger ----------------------------------------------------------------------

    @Test
    public void deathTriggerFiresWhenEnabled() {
        ReplayHighlightTrigger trigger =
                new ReplayHighlightTrigger(true, false, 3, false, 5);
        assertTrue(trigger.shouldFire(ReplayHighlightTrigger.Event.death(), 1_000L));
    }

    @Test
    public void deathTriggerStaysSilentWhenDisabled() {
        ReplayHighlightTrigger trigger =
                new ReplayHighlightTrigger(false, true, 3, false, 5);
        assertFalse(trigger.shouldFire(ReplayHighlightTrigger.Event.death(), 1_000L));
    }

    @Test
    public void killStreakNeedsTheThreshold() {
        ReplayHighlightTrigger trigger =
                new ReplayHighlightTrigger(false, true, 3, false, 5);
        assertFalse(trigger.shouldFire(ReplayHighlightTrigger.Event.killStreak(2), 1_000L));
        assertTrue(trigger.shouldFire(ReplayHighlightTrigger.Event.killStreak(3), 1_000L));
    }

    @Test
    public void cooldownSuppressesASecondTriggerInTheSameWindow() {
        ReplayHighlightTrigger trigger =
                new ReplayHighlightTrigger(true, true, 3, true, 5);
        assertTrue(trigger.shouldFire(ReplayHighlightTrigger.Event.death(), 10_000L));
        // A streak and a combo raised in the same instant must not save the same 30 seconds again.
        assertFalse(trigger.shouldFire(ReplayHighlightTrigger.Event.killStreak(5), 10_500L));
        assertTrue(trigger.shouldFire(ReplayHighlightTrigger.Event.killStreak(5),
                10_000L + ReplayHighlightTrigger.COOLDOWN_MS));
    }

    @Test
    public void resetClearsTheCooldown() {
        ReplayHighlightTrigger trigger =
                new ReplayHighlightTrigger(true, false, 3, false, 5);
        assertTrue(trigger.shouldFire(ReplayHighlightTrigger.Event.death(), 10_000L));
        assertFalse(trigger.shouldFire(ReplayHighlightTrigger.Event.death(), 10_100L));
        trigger.reset();
        assertTrue(trigger.shouldFire(ReplayHighlightTrigger.Event.death(), 10_100L));
    }

    // --- trim -----------------------------------------------------------------------------------

    @Test
    public void trimClampsToTheSource() {
        ReplayTrim trim = new ReplayTrim(-500L, 99_999L, 10_000L);
        assertEquals(0L, trim.startMs());
        assertEquals(10_000L, trim.endMs());
        assertTrue(trim.isExportable());
    }

    @Test
    public void invertedHandlesAreSwappedNotRejected() {
        ReplayTrim trim = new ReplayTrim(8_000L, 2_000L, 10_000L);
        assertEquals(2_000L, trim.startMs());
        assertEquals(8_000L, trim.endMs());
    }

    @Test
    public void aTinyWindowIsWidenedToTheMinimum() {
        ReplayTrim trim = new ReplayTrim(5_000L, 5_050L, 10_000L);
        assertEquals(ReplayTrim.MIN_LENGTH_MS, trim.lengthMs());
        assertEquals(5_000L, trim.startMs());
    }

    @Test
    public void aSourceShorterThanTheMinimumIsNotExportable() {
        ReplayTrim trim = new ReplayTrim(0L, 400L, 400L);
        assertFalse(trim.isExportable());
    }

    @Test
    public void trimmedNameDerivesFromTheSource() {
        assertEquals("fight_trimmed.mp4", ReplayTrim.trimmedName("fight.mp4"));
        assertEquals("clip_trimmed.mp4", ReplayTrim.trimmedName(""));
    }

    // --- library --------------------------------------------------------------------------------

    @Test
    public void sortByDurationPutsLongestFirst() {
        List<ReplayClip> clips = new ArrayList<>(Arrays.asList(
                clip("a.mp4", 1_000, 10, 3),
                clip("b.mp4", 9_000, 10, 1),
                clip("c.mp4", 5_000, 10, 2)));
        ReplayLibrary.sort(clips, ReplayLibrary.Sort.DURATION_LONGEST);
        assertEquals("b.mp4", clips.get(0).name());
        assertEquals("c.mp4", clips.get(1).name());
        assertEquals("a.mp4", clips.get(2).name());
    }

    @Test
    public void sortByDateNewestFirstIsTheDefault() {
        List<ReplayClip> clips = new ArrayList<>(Arrays.asList(
                clip("a.mp4", 1_000, 10, 3),
                clip("b.mp4", 1_000, 10, 1),
                clip("c.mp4", 1_000, 10, 2)));
        ReplayLibrary.sort(clips, ReplayLibrary.Sort.DATE_NEWEST);
        assertEquals("a.mp4", clips.get(0).name());
        assertEquals("c.mp4", clips.get(1).name());
        assertEquals("b.mp4", clips.get(2).name());
    }

    @Test
    public void sortBySizeIsStableOnTies() {
        List<ReplayClip> clips = new ArrayList<>(Arrays.asList(
                clip("a.mp4", 1_000, 500, 3),
                clip("b.mp4", 1_000, 500, 1),
                clip("c.mp4", 1_000, 500, 2)));
        ReplayLibrary.sort(clips, ReplayLibrary.Sort.SIZE_LARGEST);
        // Equal sizes fall back to newest first, so the order is deterministic.
        assertEquals("a.mp4", clips.get(0).name());
        assertEquals("c.mp4", clips.get(1).name());
        assertEquals("b.mp4", clips.get(2).name());
    }

    @Test
    public void filterSelectsFavoritesAndHighlights() {
        ReplayClip fav = clip("fav.mp4", 1_000, 10, 1);
        fav.setFavorite(true);
        ReplayClip hi = clip("hi.mp4", 1_000, 10, 2);
        hi.setHighlight(true);
        ReplayClip plain = clip("plain.mp4", 1_000, 10, 3);

        assertTrue(ReplayLibrary.matches(fav, ReplayLibrary.Filter.ALL));
        assertTrue(ReplayLibrary.matches(fav, ReplayLibrary.Filter.FAVORITES));
        assertFalse(ReplayLibrary.matches(plain, ReplayLibrary.Filter.FAVORITES));
        assertTrue(ReplayLibrary.matches(hi, ReplayLibrary.Filter.HIGHLIGHTS));
        assertFalse(ReplayLibrary.matches(fav, ReplayLibrary.Filter.HIGHLIGHTS));
    }

    @Test
    public void totalBytesSumsTheLibrary() {
        List<ReplayClip> clips = Arrays.asList(
                clip("a.mp4", 1_000, 100, 1),
                clip("b.mp4", 1_000, 250, 2));
        assertEquals(350L, ReplayLibrary.totalBytes(clips));
    }

    // --- controls -------------------------------------------------------------------------------

    @Test
    public void controllerMapMatchesTheSpec() {
        assertEquals(ReplayControls.Action.SELECT,
                ReplayControls.forControllerKey(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(ReplayControls.Action.DELETE,
                ReplayControls.forControllerKey(KeyEvent.KEYCODE_BUTTON_X));
        assertEquals(ReplayControls.Action.FAVORITE,
                ReplayControls.forControllerKey(KeyEvent.KEYCODE_BUTTON_Y));
        assertEquals(ReplayControls.Action.CYCLE_PREV,
                ReplayControls.forControllerKey(KeyEvent.KEYCODE_BUTTON_L1));
        assertEquals(ReplayControls.Action.CYCLE_NEXT,
                ReplayControls.forControllerKey(KeyEvent.KEYCODE_BUTTON_R1));
        assertNull(ReplayControls.forControllerKey(KeyEvent.KEYCODE_DPAD_UP));
    }

    /**
     * Every button the on-screen controller hint names must resolve, or the hint advertises a
     * button that does nothing. The hint is `A play · Start pause · L2/R2 scrub · Select speed ·
     * X delete · Y favorite · L1/R1 cycle`.
     */
    @Test
    public void everyButtonTheHintNamesResolvesToAnAction() {
        assertEquals(ReplayControls.Action.PLAY_PAUSE,
                ReplayControls.forControllerKey(KeyEvent.KEYCODE_BUTTON_START));
        assertEquals(ReplayControls.Action.SCRUB_BACK,
                ReplayControls.forControllerKey(KeyEvent.KEYCODE_BUTTON_L2));
        assertEquals(ReplayControls.Action.SCRUB_FORWARD,
                ReplayControls.forControllerKey(KeyEvent.KEYCODE_BUTTON_R2));
        assertEquals(ReplayControls.Action.SPEED,
                ReplayControls.forControllerKey(KeyEvent.KEYCODE_BUTTON_SELECT));
    }

    /** The D-pad stays out of the map so the Replay tab does not fight grid navigation. */
    @Test
    public void theDpadIsNotMapped() {
        assertNull(ReplayControls.forControllerKey(KeyEvent.KEYCODE_DPAD_LEFT));
        assertNull(ReplayControls.forControllerKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertNull(ReplayControls.forControllerKey(KeyEvent.KEYCODE_DPAD_CENTER));
    }

    @Test
    public void keyboardMapMatchesTheSpec() {
        assertEquals(ReplayControls.Action.DELETE,
                ReplayControls.forKeyboardKey(KeyEvent.KEYCODE_DEL));
        assertEquals(ReplayControls.Action.PLAY_PAUSE,
                ReplayControls.forKeyboardKey(KeyEvent.KEYCODE_SPACE));
        assertEquals(ReplayControls.Action.SCRUB_BACK,
                ReplayControls.forKeyboardKey(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(ReplayControls.Action.SCRUB_FORWARD,
                ReplayControls.forKeyboardKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(ReplayControls.Action.SELECT,
                ReplayControls.forKeyboardKey(KeyEvent.KEYCODE_ENTER));
    }

    @Test
    public void cycleActionsDoNotNeedASelection() {
        assertFalse(ReplayControls.requiresSelection(ReplayControls.Action.CYCLE_NEXT));
        assertTrue(ReplayControls.requiresSelection(ReplayControls.Action.DELETE));
        assertFalse(ReplayControls.requiresSelection(null));
    }

    // --- metadata -------------------------------------------------------------------------------

    @Test
    public void unknownFieldsRenderADashNotAZero() {
        assertEquals(ReplayMetadata.NO_READING,
                ReplayMetadata.value(ReplayMetadata.Field.FPS, 0, 0, 0L, -1, 0));
        assertEquals(ReplayMetadata.NO_READING,
                ReplayMetadata.value(ReplayMetadata.Field.PING, 60, 0, 0L, -1, 0));
    }

    @Test
    public void burnInLineJoinsEnabledFields() {
        List<ReplayMetadata.Field> fields = Arrays.asList(
                ReplayMetadata.Field.FPS,
                ReplayMetadata.Field.TIMER,
                ReplayMetadata.Field.COMBO);
        assertEquals("60 FPS \u00b7 0:42 \u00b7 3x",
                ReplayMetadata.line(fields, 60, 0, 42_000L, -1, 3));
    }

    @Test
    public void emptyFieldSetProducesNoLine() {
        assertEquals("", ReplayMetadata.line(Collections.emptyList(), 60, 0, 0L, 0, 0));
    }

    // --- format ---------------------------------------------------------------------------------

    @Test
    public void durationFormatsAsClock() {
        assertEquals("0:07", ReplayFormat.duration(7_400L));
        assertEquals("1:42", ReplayFormat.duration(102_000L));
        assertEquals(ReplayFormat.NO_READING, ReplayFormat.duration(-1L));
    }

    @Test
    public void sizeScalesThroughTheUnits() {
        assertEquals("812 KB", ReplayFormat.size(812L * 1024L));
        assertEquals("1.5 MB", ReplayFormat.size((long) (1.5 * 1024 * 1024)));
        assertEquals("1.0 GB", ReplayFormat.size(1024L * 1024L * 1024L));
        assertEquals("512 B", ReplayFormat.size(512L));
    }

    @Test
    public void storagePercentClamps() {
        assertEquals(50, ReplayFormat.storagePercent(500L, 1_000L));
        assertEquals(100, ReplayFormat.storagePercent(2_000L, 1_000L));
        assertEquals(0, ReplayFormat.storagePercent(500L, 0L));
    }

    // --- date + stamp ---------------------------------------------------------------------------

    @Test
    public void dateLabelIsDeterministic() {
        // 2026-09-29T14:23:07Z in millis.
        long epochMs = 1_790_691_787_000L;
        ReplayClip clip = clip("a.mp4", 1_000, 10, epochMs);
        assertEquals("2026-09-29 14:23", clip.dateLabel());
        assertEquals("2026-09-29_14-23-07", ReplayMetadata.fileStamp(epochMs));
    }

    // --- quality --------------------------------------------------------------------------------

    @Test
    public void lowEndFallsBackToSevenTwenty() {
        assertEquals(ReplayQuality.LOW, ReplayQuality.select(true));
        assertEquals("720p30", ReplayQuality.LOW.label());
        assertEquals("1080p60", ReplayQuality.HIGH.label());
    }

    // --- sidecar + repository merge -------------------------------------------------------------

    @Test
    public void sidecarRoundTripsThroughSerialize() {
        ReplaySidecar sidecar = ReplaySidecar.of(1_790_691_787_000L, "world", "1.26.60", "survival")
                .setFavorite(true).setHighlight(true);
        ReplaySidecar parsed = ReplaySidecar.parse(sidecar.serialize());
        assertEquals(1_790_691_787_000L, parsed.recordedAtMs());
        assertEquals("world", parsed.world());
        assertEquals("1.26.60", parsed.gameVersion());
        assertEquals("survival", parsed.gameMode());
        assertTrue(parsed.favorite());
        assertTrue(parsed.highlight());
    }

    @Test
    public void sidecarPreservesUnknownKeysOnRewrite() {
        ReplaySidecar parsed = ReplaySidecar.parse("version=1\nfutureField=keepme\nfavorite=0\n");
        parsed.setFavorite(true);
        String rewritten = parsed.serialize();
        assertTrue(rewritten.contains("futureField=keepme"));
        assertTrue(rewritten.contains("favorite=1"));
    }

    @Test
    public void corruptSidecarDegradesToFileMetadata() {
        File file = new File("/replays/broken.mp4");
        // A sidecar with no usable recordedAt must fall back to the file's own timestamp.
        ReplayClip clip = clipWithSidecar(file, 2_000L, 500L, "garbage without equals\n=");
        assertEquals(file.lastModified(), clip.recordedAtMs());
        assertFalse(clip.favorite());
        assertFalse(clip.highlight());
    }

    @Test
    public void missingSidecarStillListsTheClip() {
        File file = new File("/replays/plain.mp4");
        ReplayClip clip = clipWithSidecar(file, 3_000L, 900L, "");
        assertEquals("plain.mp4", clip.name());
        assertEquals(3_000L, clip.durationMs());
        // Size comes from the real file, not the passed duration hint; a non-existent path is 0.
        assertEquals(file.length(), clip.sizeBytes());
    }

    @Test
    public void repositorySanitizesTypedNames() {
        assertEquals("..myclip", ReplayClipRepository.sanitizeName("../my/clip"));
        assertEquals("", ReplayClipRepository.sanitizeName("///"));
        assertEquals("keepme", ReplayClipRepository.sanitizeName("keep:me"));
    }

    @Test
    public void repositoryMergePrefersSidecarFlagsAndFileSize() {
        File file = new File("/replays/fight.mp4");
        String sidecar = "version=1\nrecordedAt=5000\nworld=nether\nfavorite=1\nhighlight=1\n";
        ReplayClip clip = clipWithSidecar(file, 4_000L, 1_234L, sidecar);
        assertEquals(5_000L, clip.recordedAtMs());
        assertEquals("nether", clip.world());
        assertTrue(clip.favorite());
        assertTrue(clip.highlight());
        assertEquals(file.length(), clip.sizeBytes());
    }

    @Test
    public void unknownMemoryIsTreatedAsLowEnd() {
        assertTrue(ReplayQuality.isLowEnd(0L));
        assertTrue(ReplayQuality.isLowEnd(2L * 1024 * 1024 * 1024));
        assertFalse(ReplayQuality.isLowEnd(6L * 1024 * 1024 * 1024));
    }

    @Test
    public void clipNeverReportsNegativeValues() {
        ReplayClip clip = new ReplayClip(new File("/replays/x.mp4"), -5L, -5L, -5L, null, null, null);
        assertEquals(0L, clip.durationMs());
        assertEquals(0L, clip.sizeBytes());
        assertEquals(0L, clip.recordedAtMs());
        assertNotNull(clip.world());
    }
}
