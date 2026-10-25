package org.chimeramc.client.launcher.core.minecraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The stored-launch-failure record (the reader the write-only report never had).
 *
 * <p>Pure: the format, the parse and the cause mapping are all exercised as strings, so the
 * round-trip that the launcher depends on is verified without a device. The bug this closes was a
 * report written to disk that nothing read, so the assertions that matter most are the round-trip
 * and the announced flag.
 */
public class LastLaunchFailureTest {

    @Test
    public void aSerializedFailureParsesBackWithItsCauseAndMessage() {
        Throwable failure = new UnsatisfiedLinkError(
                "dlopen failed: library \"libminecraftpe.so\" not found");
        String text = LastLaunchFailure.serialize(failure, false, 1_700_000_000_000L);

        LastLaunchFailure.Report report = LastLaunchFailure.parse(text, 1_700_000_000_000L);

        assertNotNull(report);
        assertEquals("dlopen failed: library \"libminecraftpe.so\" not found", report.message);
        assertEquals(UnsatisfiedLinkError.class.getName(), report.type);
        assertEquals(LaunchErrorDiagnosis.Cause.MISSING_GAME_LIBRARY, report.cause());
        assertFalse(report.announced);
        assertTrue(report.isShowable());
        assertEquals("missing_game_library", LaunchErrorDiagnosis.key(report.cause()));
    }

    @Test
    public void anAnnouncedFailureIsStoredButNotShownAgain() {
        // The loading screen already named the cause, so the relaunched launcher must not repeat it.
        Throwable failure = new IllegalStateException("post-init hook failed");
        String text = LastLaunchFailure.serialize(failure, true, 1L);

        LastLaunchFailure.Report report = LastLaunchFailure.parse(text, 1L);

        assertNotNull(report);
        assertTrue(report.announced);
        assertFalse(report.isShowable());
    }

    @Test
    public void anOlderUnstructuredReportStillClassifiesFromItsMessage() {
        // A report written before the Cause:/Announced: lines existed must still work; that is why
        // parse() falls back to the whole text as the message and the cause is re-derived.
        String legacy = "Time: Mon Jan 01 00:00:00 GMT 2024\n"
                + "Message: dlopen failed: library \"libminecraftpe.so\" not found\n"
                + "Type: java.lang.UnsatisfiedLinkError\n"
                + "Stack:\njava.lang.UnsatisfiedLinkError: ...\n";

        LastLaunchFailure.Report report = LastLaunchFailure.parse(legacy, 0L);

        assertNotNull(report);
        assertEquals(LaunchErrorDiagnosis.Cause.MISSING_GAME_LIBRARY, report.cause());
        assertFalse(report.announced);
        assertTrue(report.isShowable());
    }

    @Test
    public void aStoredCauseKeyIsTrustedOverTheMessageText() {
        // The key is written from the classifier at failure time, so a later rule change cannot
        // silently reclassify a failure the user already saw. A message that would now read as an
        // ABI mismatch must still report the cause it was recorded with.
        String text = "Message: is 32-bit instead of 64-bit\n"
                + "Cause: missing_game_library\n"
                + "Announced: false\n"
                + "Stack:\n";

        LastLaunchFailure.Report report = LastLaunchFailure.parse(text, 0L);

        assertNotNull(report);
        assertEquals(LaunchErrorDiagnosis.Cause.MISSING_GAME_LIBRARY, report.cause());
    }

    @Test
    public void anUnclassifiableMessageStillReadsAsAFailureWithItsRawText() {
        // "it just closed" has to have *an* answer; an unknown cause with the raw text kept is more
        // useful than nothing, and it must not read as announced.
        String text = "Message: something the classifier has never seen\nAnnounced: false\nStack:\n";

        LastLaunchFailure.Report report = LastLaunchFailure.parse(text, 0L);

        assertNotNull(report);
        assertEquals(LaunchErrorDiagnosis.Cause.UNKNOWN, report.cause());
        assertTrue(report.isShowable());
        assertEquals("something the classifier has never seen", LastLaunchFailure.detailOf(report));
    }

    @Test
    public void emptyAndNullTextParseToNothing() {
        assertNull(LastLaunchFailure.parse(null, 0L));
        assertNull(LastLaunchFailure.parse("", 0L));
        assertNull(LastLaunchFailure.parse("   \n  \n", 0L));
    }

    @Test
    public void theKeyIsTheInverseOfTheCauseName() {
        // Every cause round-trips through key()/fromKey(), which is what the stored report relies on.
        for (LaunchErrorDiagnosis.Cause cause : LaunchErrorDiagnosis.Cause.values()) {
            assertEquals(cause, LaunchErrorDiagnosis.fromKey(LaunchErrorDiagnosis.key(cause)));
        }
        // An unrecognised or missing key reads as "no key", not as UNKNOWN, so the reader re-derives
        // the cause from the text instead of reporting the wrong thing.
        assertNull(LaunchErrorDiagnosis.fromKey(null));
        assertNull(LaunchErrorDiagnosis.fromKey(""));
        assertNull(LaunchErrorDiagnosis.fromKey("not_a_cause"));
    }

    @Test
    public void describeNamesTheCauseAndKeepsTheDetailOnOneLine() {
        String text = LastLaunchFailure.serialize(
                new UnsatisfiedLinkError("dlopen failed: library \"libminecraftpe.so\" not found"),
                false, 0L);
        LastLaunchFailure.Report report = LastLaunchFailure.parse(text, 0L);

        String line = LastLaunchFailure.describe(report);

        assertTrue(line.startsWith("missing_game_library - "));
        assertFalse(line.contains("\n"));
    }

    @Test
    public void aTimestampOfZeroOrLessReadsAsUnknown() {
        assertEquals("unknown", LastLaunchFailure.formatTimestamp(0L));
        assertEquals("unknown", LastLaunchFailure.formatTimestamp(-1L));
        assertNotEqualsUnknown(LastLaunchFailure.formatTimestamp(1_700_000_000_000L));
    }

    private static void assertNotEqualsUnknown(String value) {
        assertNotNull(value);
        assertFalse("a real timestamp must not read as unknown", "unknown".equals(value));
    }
}
