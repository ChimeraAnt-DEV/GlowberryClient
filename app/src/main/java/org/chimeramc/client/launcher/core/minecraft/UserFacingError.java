package org.chimeramc.client.launcher.core.minecraft;

import android.content.Context;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.zip.ZipException;

import javax.net.ssl.SSLException;

import org.chimeramc.client.R;

/**
 * Turns a download, import or file failure into the specific reason it happened.
 *
 * <p>Before this class the launcher's non-launch failures all reached the user as one of three
 * sentences: "Unknown error", "An error occurred during repair: null", or the raw
 * {@code java.net.UnknownHostException: Unable to resolve host "mcpedl.org"} text. None of them say
 * what the user can do. A DNS failure, a full disk and a Cloudflare check are three different
 * problems with three different fixes, and reporting them identically is what turns a
 * five-second fix into a support ticket.
 *
 * <p>This is the download/import counterpart to {@link LaunchErrorDiagnosis}: the same shape, the
 * same rules — a pure mapping from a failure to a named cause, unit-tested with no device, and the
 * single place the copy lives so two call sites cannot describe one failure two ways.
 *
 * <p>Classification order is deliberate and documented at each branch. The most specific signal
 * wins: a {@link SocketTimeoutException} is a subclass of {@link InterruptedIOException}, and an
 * {@link SSLException} is a subclass of {@link IOException}, so the narrower tests must run first
 * or a TLS handshake failure would be reported as "the download was interrupted".
 */
public final class UserFacingError {

    private UserFacingError() {
    }

    /** The named reasons a download, import or file operation can fail. */
    public enum Cause {
        /** The device has no usable network, or the route to the host is down. */
        NO_CONNECTION,
        /** A TCP connection was refused or could not be established. */
        CONNECTION_REFUSED,
        /** The host name could not be resolved — usually no connectivity or a wrong address. */
        HOST_NOT_FOUND,
        /** The server accepted the connection but took too long to answer. */
        TIMEOUT,
        /** The TLS handshake failed: a captive portal, a bad clock, or an intercepting proxy. */
        SECURE_CONNECTION_FAILED,
        /** The source refused the request — an HTTP 403 or an anti-bot browser check. */
        SERVER_REFUSED,
        /** The source reported an internal error (HTTP 5xx). */
        SERVER_ERROR,
        /** The source answered, but with something the launcher could not use. */
        BAD_RESPONSE,
        /** The device is out of storage. */
        OUT_OF_SPACE,
        /**
         * The launcher's own process ran out of memory.
         *
         * <p>Distinct from {@link #OUT_OF_SPACE} because the fix is different and the two were
         * conflated: an {@code OutOfMemoryError} while extracting a large package used to be
         * reported as "not enough free storage", which sent the user to free disk space that was
         * never the problem. There is plenty of disk; the heap the launcher is allowed to use ran
         * out.
         */
        OUT_OF_MEMORY,
        /** The app is not allowed to read or write the target location. */
        PERMISSION_DENIED,
        /** The file is damaged or is not the kind of package it claims to be. */
        CORRUPT_PACKAGE,
        /** The transfer stopped before it finished. */
        INTERRUPTED,
        /** Nothing recognisable in the failure. */
        UNKNOWN,
    }

    /**
     * Classifies a failure from its message text.
     *
     * <p>Text rather than a {@link Throwable} because the same rules must also serve a stored
     * failure string, and because the network layer reports some failures only as text.
     */
    public static Cause classifyText(String text) {
        if (text == null || text.trim().isEmpty()) return Cause.UNKNOWN;
        String lower = text.toLowerCase(Locale.ROOT);

        // Storage and permission are checked before the generic IO branch: an ENOSPC or EACCES
        // arrives as a plain IOException whose message is the only signal, and "the transfer
        // stopped" would send the user to retry a download that can never fit.
        if (lower.contains("no space left") || lower.contains("enospc")
                || lower.contains("insufficient storage") || lower.contains("disk full")) {
            return Cause.OUT_OF_SPACE;
        }
        if (lower.contains("permission denied") || lower.contains("eacces")
                || lower.contains("operation not permitted")) {
            return Cause.PERMISSION_DENIED;
        }
        // A damaged archive is recognised before the generic branches: a ZipException message also
        // contains "invalid", which the bad-response branch would otherwise claim.
        if (lower.contains("zip") && (lower.contains("invalid") || lower.contains("corrupt"))
                || lower.contains("not a valid")
                || lower.contains("unexpected end of")
                || lower.contains("crc32")
                || lower.contains("corrupt")) {
            return Cause.CORRUPT_PACKAGE;
        }
        // Name resolution before the connection branch: "unable to resolve host" contains neither
        // "refused" nor "timed out", but the opposite ordering mistake is common, so it is pinned.
        if (lower.contains("unable to resolve host") || lower.contains("nodename nor servname")
                || lower.contains("no address associated")
                || lower.contains("unknownhost")) {
            return Cause.HOST_NOT_FOUND;
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return Cause.TIMEOUT;
        }
        // TLS before the generic IO branch (an SSLException is an IOException).
        if (lower.contains("ssl") || lower.contains("certificate") || lower.contains("trust anchor")
                || lower.contains("handshake")) {
            return Cause.SECURE_CONNECTION_FAILED;
        }
        if (lower.contains("connection refused") || lower.contains("econnrefused")
                || lower.contains("no route to host")) {
            return Cause.CONNECTION_REFUSED;
        }
        if (lower.contains("unable to connect") || lower.contains("network is unreachable")
                || lower.contains("network unreachable") || lower.contains("connect failed")) {
            return Cause.NO_CONNECTION;
        }
        // The source's own refusal, including an anti-bot interstitial, is a 403 in practice. The
        // "real browser" wording is the launcher's own hint for the same condition, so it is
        // matched too — otherwise the one string the user actually sees would fall through.
        if (lower.contains("403") || lower.contains("forbidden") || lower.contains("cloudflare")
                || lower.contains("challenge") || lower.contains("browser check")
                || lower.contains("real browser")) {
            return Cause.SERVER_REFUSED;
        }
        if (lower.contains("500") || lower.contains("502") || lower.contains("503")
                || lower.contains("504") || lower.contains("server error")
                || lower.contains("bad gateway") || lower.contains("service unavailable")) {
            return Cause.SERVER_ERROR;
        }
        if (lower.contains("interrupted")) {
            return Cause.INTERRUPTED;
        }
        // The launcher's own parser wording for a page it could not use.
        if (lower.contains("did not reveal")
                || lower.contains("returned no content")
                || lower.contains("redirected without")
                || lower.contains("redirected to an unknown")
                || lower.contains("no downloadable package")
                || lower.contains("unexpected page")) {
            return Cause.BAD_RESPONSE;
        }
        if (lower.contains("file not found") || lower.contains("no such file")) {
            return Cause.CORRUPT_PACKAGE;
        }
        return Cause.UNKNOWN;
    }

    /**
     * Classifies a throwable, following its cause chain to the root.
     *
     * <p>The type tests run before the text tests because an exception's class is a stronger signal
     * than its message, and the text rules then act as the fallback for the many failures that
     * arrive wrapped in a generic {@link IOException}.
     */
    public static Cause classify(Throwable error) {
        if (error == null) return Cause.UNKNOWN;

        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        // Most specific type first. SocketTimeoutException and SSLException are both IOExceptions,
        // so their tests must precede the generic IOException branch.
        if (root instanceof UnknownHostException) return Cause.HOST_NOT_FOUND;
        if (root instanceof SocketTimeoutException) return Cause.TIMEOUT;
        if (root instanceof SSLException) return Cause.SECURE_CONNECTION_FAILED;
        if (root instanceof ConnectException || root instanceof NoRouteToHostException) {
            return Cause.CONNECTION_REFUSED;
        }
        if (root instanceof ZipException) return Cause.CORRUPT_PACKAGE;
        if (root instanceof SecurityException) return Cause.PERMISSION_DENIED;
        if (root instanceof FileNotFoundException) {
            // A missing file on a download path is a source-side 404 far more often than a local
            // storage problem, so it is reported as a bad response unless the text says otherwise.
            Cause byText = classifyText(root.getMessage());
            return byText == Cause.UNKNOWN ? Cause.BAD_RESPONSE : byText;
        }
        if (root instanceof OutOfMemoryError) return Cause.OUT_OF_MEMORY;
        if (root instanceof InterruptedIOException) return Cause.INTERRUPTED;

        Cause byText = classifyText(root.getMessage());
        if (byText != Cause.UNKNOWN) return byText;

        // The class name is the last signal available when a message is empty or opaque; an
        // IOException with no text is still a transfer problem, not an unidentifiable one.
        if (root instanceof IOException) return Cause.BAD_RESPONSE;
        return Cause.UNKNOWN;
    }

    /**
     * The user-facing sentence for a cause.
     *
     * <p>Each one names what happened and the next action, because a reason with no action is the
     * same dead end as "Unknown error" with more words.
     */
    public static int messageRes(Cause cause) {
        if (cause == null) return R.string.error_reason_unknown;
        switch (cause) {
            case NO_CONNECTION:
                return R.string.error_reason_no_connection;
            case CONNECTION_REFUSED:
                return R.string.error_reason_connection_refused;
            case HOST_NOT_FOUND:
                return R.string.error_reason_host_not_found;
            case TIMEOUT:
                return R.string.error_reason_timeout;
            case SECURE_CONNECTION_FAILED:
                return R.string.error_reason_secure_connection;
            case SERVER_REFUSED:
                return R.string.error_reason_server_refused;
            case SERVER_ERROR:
                return R.string.error_reason_server_error;
            case BAD_RESPONSE:
                return R.string.error_reason_bad_response;
            case OUT_OF_SPACE:
                return R.string.error_reason_out_of_space;
            case OUT_OF_MEMORY:
                return R.string.error_reason_out_of_memory;
            case PERMISSION_DENIED:
                return R.string.error_reason_permission_denied;
            case CORRUPT_PACKAGE:
                return R.string.error_reason_corrupt_package;
            case INTERRUPTED:
                return R.string.error_reason_interrupted;
            default:
                return R.string.error_reason_unknown;
        }
    }

    /** A stable, non-translatable key for the cause, for a bug report to quote. */
    public static String label(Cause cause) {
        return cause == null ? Cause.UNKNOWN.name() : cause.name();
    }

    /**
     * The reason sentence followed by the raw text, when there is one.
     *
     * <p>The raw text is kept beneath the human copy on purpose: the sentence tells the user what
     * to do, and the original string is what a bug report needs. Suppressing it would trade a
     * diagnosable report for a tidy dialog.
     */
    public static String describe(Throwable error, String reason) {
        if (error == null) return reason;
        String raw = error.getMessage();
        if (raw == null || raw.trim().isEmpty() || raw.equals(reason)) return reason;
        return reason + "\n\n" + label(classify(error)) + ": " + raw.trim();
    }

    /**
     * Just the actionable sentence for a failure, resolved from resources.
     *
     * <p>For a compact surface — a toast, a status line — where a second line of raw text would be
     * clipped. The sentence already names the cause and the next step, so nothing actionable is
     * lost; the raw text belongs on a surface that can show it ({@link #describe(Context,
     * Throwable)}).
     */
    public static String reason(Context context, Throwable error) {
        if (context == null) return "";
        return context.getString(messageRes(classify(error)));
    }

    /**
     * The sentence followed by the raw text, for a surface with room for both.
     *
     * <p>A dialog or a detail pane is where the raw string pays for itself: the user gets the
     * instruction, and the bug report gets the original message and the cause key.
     */
    public static String describe(Context context, Throwable error) {
        return describe(error, reason(context, error));
    }

    /**
     * The cause sentence for a failure that carries its own headline.
     *
     * <p>For a background manager whose callback already names the operation ("Import failed"),
     * where only the cause is missing. The operation is not repeated — the caller's message already
     * says it — so the result reads as one sentence instead of two stitched fragments.
     *
     * <p>Unlike {@link #describe(Context, Throwable)} this never appends the raw text: the callback
     * string is displayed in a toast, where a second paragraph is clipped.
     */
    public static String reason(Context context, String operation, Throwable error) {
        String cause = reason(context, error);
        if (operation == null || operation.trim().isEmpty()) return cause;
        if (cause == null || cause.trim().isEmpty()) return operation;
        return operation.trim() + ": " + cause;
    }

    /**
     * {@link #reason(Context, String, Throwable)} for a callback that reports its own error string.
     *
     * <p>A manager callback receives a message, not a throwable, so the caller keeps the operation
     * prefix it already built and swaps the raw tail for the cause. The throwable is not available
     * at that point, so the tail is classified from its text.
     */
    public static String reasonForText(Context context, String operation, String rawText) {
        if (context == null) return operation == null ? "" : operation;
        String cause = context.getString(messageRes(classifyText(rawText)));
        if (operation == null || operation.trim().isEmpty()) return cause;
        return operation.trim() + ": " + cause;
    }


    /**
     * The short cause phrase, for a toast or a one-line status.
     *
     * <p>{@link #messageRes(Cause)} is a full sentence with the next step, which is right for a
     * dialog. A toast clips at roughly two lines, so a content operation that reports
     * "Import failed: " followed by that sentence would lose the instruction anyway and keep only
     * the length. The short form names the cause in a phrase that fits beside the operation, so
     * "Import failed: no internet connection" stays readable.
     */
    public static int shortMessageRes(Cause cause) {
        if (cause == null) return R.string.error_short_unknown;
        switch (cause) {
            case NO_CONNECTION:
                return R.string.error_short_no_connection;
            case CONNECTION_REFUSED:
                return R.string.error_short_connection_refused;
            case HOST_NOT_FOUND:
                return R.string.error_short_host_not_found;
            case TIMEOUT:
                return R.string.error_short_timeout;
            case SECURE_CONNECTION_FAILED:
                return R.string.error_short_secure_connection;
            case SERVER_REFUSED:
                return R.string.error_short_server_refused;
            case SERVER_ERROR:
                return R.string.error_short_server_error;
            case BAD_RESPONSE:
                return R.string.error_short_bad_response;
            case OUT_OF_SPACE:
                return R.string.error_short_out_of_space;
            case OUT_OF_MEMORY:
                return R.string.error_short_out_of_memory;
            case PERMISSION_DENIED:
                return R.string.error_short_permission_denied;
            case CORRUPT_PACKAGE:
                return R.string.error_short_corrupt_package;
            case INTERRUPTED:
                return R.string.error_short_interrupted;
            default:
                return R.string.error_short_unknown;
        }
    }

    /**
     * The short cause phrase for a throwable.
     *
     * <p>The compact counterpart of {@link #reason(Context, Throwable)}, for a toast.
     */
    public static String shortReason(Context context, Throwable error) {
        if (context == null) return "";
        return context.getString(shortMessageRes(classify(error)));
    }

    /**
     * The short cause phrase for a manager callback that reports its own error string.
     *
     * <p>For a background manager whose callback message already names the operation
     * ("Import failed"), where only the cause is missing and the surface is a toast.
     */
    public static String shortReasonForText(Context context, String operation, String rawText) {
        if (context == null) return operation == null ? "" : operation;
        String cause = context.getString(shortMessageRes(classifyText(rawText)));
        if (operation == null || operation.trim().isEmpty()) return cause;
        return operation.trim() + ": " + cause;
    }
}
