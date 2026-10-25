package org.chimeramc.client.launcher.core.minecraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.zip.ZipException;

import javax.net.ssl.SSLHandshakeException;

import org.junit.Test;

/**
 * The download/import failure classifier (Requirement 3).
 *
 * <p>Pure: message text and exception types only, no Context. The point of these cases is that the
 * two orderings the class documents are the two that are easy to get wrong — a timeout is an
 * {@link InterruptedIOException} and an SSL failure is an {@link IOException}, so a narrower test
 * that ran after its broader parent would silently report the wrong cause.
 */
public class UserFacingErrorTest {

    @Test
    public void hostResolutionFailureIsNotReportedAsAGenericTransferError() {
        assertEquals(UserFacingError.Cause.HOST_NOT_FOUND,
                UserFacingError.classify(new UnknownHostException(
                        "Unable to resolve host \"mcpedl.org\": No address associated with hostname")));
    }

    @Test
    public void aTimeoutWinsOverTheInterruptedParentClass() {
        // SocketTimeoutException extends InterruptedIOException: the timeout branch must run first
        // or every slow server reads as "the download was interrupted", which points the user at
        // the wrong fix.
        assertEquals(UserFacingError.Cause.TIMEOUT,
                UserFacingError.classify(new SocketTimeoutException("Read timed out")));
    }

    @Test
    public void aTlsFailureIsNotReportedAsABadResponse() {
        // SSLHandshakeException extends IOException.
        assertEquals(UserFacingError.Cause.SECURE_CONNECTION_FAILED,
                UserFacingError.classify(new SSLHandshakeException("Trust anchor for certification path not found")));
    }

    @Test
    public void aRefusedConnectionIsDistinguishedFromNoConnectionAtAll() {
        assertEquals(UserFacingError.Cause.CONNECTION_REFUSED,
                UserFacingError.classify(new ConnectException("Connection refused")));
    }

    @Test
    public void storageExhaustionIsRecognisedFromTheMessage() {
        // ENOSPC arrives as a plain IOException whose only signal is the text.
        assertEquals(UserFacingError.Cause.OUT_OF_SPACE,
                UserFacingError.classify(new IOException("write failed: ENOSPC (No space left on device)")));
    }

    @Test
    public void anOutOfMemoryErrorIsNotReportedAsAFullDisk() {
        // The launcher's heap running out and the device being out of storage have different fixes
        // and used to share one message, which sent the user to free disk space that was never the
        // problem. The type is the only signal an OutOfMemoryError carries.
        assertEquals(UserFacingError.Cause.OUT_OF_MEMORY,
                UserFacingError.classify(new OutOfMemoryError("Failed to allocate a 12 byte allocation")));
        assertNotEquals(UserFacingError.Cause.OUT_OF_SPACE,
                UserFacingError.classify(new OutOfMemoryError()));
    }

    @Test
    public void permissionDeniedIsRecognisedFromTheMessage() {
        assertEquals(UserFacingError.Cause.PERMISSION_DENIED,
                UserFacingError.classify(new IOException("open failed: EACCES (Permission denied)")));
    }

    @Test
    public void aDamagedArchiveIsNotReportedAsABadResponse() {
        // A ZipException message contains "invalid", which the bad-response text branch would claim
        // if the corrupt-package test did not run first.
        assertEquals(UserFacingError.Cause.CORRUPT_PACKAGE,
                UserFacingError.classify(new ZipException("invalid entry CRC (expected 0x0 but got 0x1)")));
    }

    @Test
    public void theSourcesOwnRefusalIsNamed() {
        assertEquals(UserFacingError.Cause.SERVER_REFUSED,
                UserFacingError.classifyText("HTTP 403 Forbidden"));
        assertEquals(UserFacingError.Cause.SERVER_REFUSED,
                UserFacingError.classifyText("The download source is checking that this is a real browser"));
    }

    @Test
    public void aServerSideErrorIsNamed() {
        assertEquals(UserFacingError.Cause.SERVER_ERROR,
                UserFacingError.classifyText("Server returned HTTP response code: 503 for URL"));
    }

    @Test
    public void theLaunchersOwnParserWordingReadsAsABadResponse() {
        assertEquals(UserFacingError.Cause.BAD_RESPONSE,
                UserFacingError.classifyText("The download page did not reveal a package link"));
        assertEquals(UserFacingError.Cause.BAD_RESPONSE,
                UserFacingError.classifyText("The download page returned no content"));
    }

    @Test
    public void anOpaqueIOExceptionIsStillATransferProblemNotAnUnidentifiedOne() {
        assertEquals(UserFacingError.Cause.BAD_RESPONSE,
                UserFacingError.classify(new IOException()));
    }

    @Test
    public void anEmptyOrNullFailureIsUnknownRatherThanMisattributed() {
        assertEquals(UserFacingError.Cause.UNKNOWN, UserFacingError.classify(null));
        assertEquals(UserFacingError.Cause.UNKNOWN, UserFacingError.classifyText(null));
        assertEquals(UserFacingError.Cause.UNKNOWN, UserFacingError.classifyText("   "));
    }

    @Test
    public void aTypedButSilentIOExceptionIsStillATransferProblem() {
        // With no message at all the exception's own type is the remaining signal, and an
        // IOException is a transfer problem rather than an unidentifiable one.
        assertEquals(UserFacingError.Cause.BAD_RESPONSE, UserFacingError.classify(new IOException()));
        assertEquals(UserFacingError.Cause.BAD_RESPONSE, UserFacingError.classify(new IOException("   ")));
    }

    @Test
    public void aMissingFileOnADownloadPathIsASourceProblemNotALocalOne() {
        assertEquals(UserFacingError.Cause.BAD_RESPONSE,
                UserFacingError.classify(new FileNotFoundException("minecraft-26-60-28.apk")));
    }

    @Test
    public void theWrappedCauseDecidesTheReason() {
        IOException wrapper = new IOException("download failed",
                new UnknownHostException("Unable to resolve host \"file.mcpedl.org\""));
        assertEquals(UserFacingError.Cause.HOST_NOT_FOUND, UserFacingError.classify(wrapper));
    }

    @Test
    public void everyCauseHasActionableCopy() {
        // A cause with no message would fall back to the vague sentence this class exists to
        // remove, so each one must resolve to a real string.
        for (UserFacingError.Cause cause : UserFacingError.Cause.values()) {
            assertNotEquals("missing copy for " + cause, 0, UserFacingError.messageRes(cause));
        }
        assertNotEquals(0, UserFacingError.messageRes(null));
    }

    @Test
    public void theRawTextIsKeptBeneathTheReasonForABugReport() {
        IOException error = new IOException("Unable to resolve host \"mcpedl.org\"");
        String described = UserFacingError.describe(error, "Check your connection.");
        assertTrue(described.startsWith("Check your connection."));
        assertTrue(described.contains("HOST_NOT_FOUND"));
        assertTrue(described.contains("mcpedl.org"));
    }

    @Test
    public void aFailureWithNoTextDoesNotPrintTheWordNull() {
        String described = UserFacingError.describe(new IOException(), "Check your connection.");
        assertFalse(described.contains("null"));
        assertEquals("Check your connection.", described);
    }

    @Test
    public void theLabelIsStableAndNotTranslated() {
        assertEquals("HOST_NOT_FOUND", UserFacingError.label(UserFacingError.Cause.HOST_NOT_FOUND));
        assertEquals("UNKNOWN", UserFacingError.label(null));
    }

    /**
     * The short form must exist for every cause the long form does.
     *
     * <p>It is what a toast uses, and a missing mapping would return the unknown-cause resource id
     * for a cause the classifier did identify — the message would contradict the diagnosis.
     */
    @Test
    public void everyCauseHasAShortPhraseDistinctFromTheUnknownOne() {
        int unknown = UserFacingError.shortMessageRes(UserFacingError.Cause.UNKNOWN);
        assertTrue("the unknown cause must have a phrase", unknown != 0);
        for (UserFacingError.Cause cause : UserFacingError.Cause.values()) {
            int res = UserFacingError.shortMessageRes(cause);
            assertTrue("no short phrase for " + cause, res != 0);
            if (cause != UserFacingError.Cause.UNKNOWN) {
                assertNotEquals("a real cause must not fall back to the unknown phrase: " + cause,
                        unknown, res);
            }
        }
    }

    @Test
    public void aNullCauseStillHasAShortPhrase() {
        assertTrue(UserFacingError.shortMessageRes(null) != 0);
    }
}
