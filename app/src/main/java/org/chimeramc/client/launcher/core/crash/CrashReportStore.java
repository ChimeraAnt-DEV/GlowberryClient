package org.chimeramc.client.core.crash;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Finds and reads the most recent crash report so the in-app diagnostics screen can show it with
 * Copy/Share.
 *
 * <p>xCrash writes its tombstone/log files into the app's {@code crash_logs} directory (see
 * {@code CrashReporter.crashLogDir}). This class is deliberately <b>file-based and Android-free</b>
 * so the selection rule ("the newest file in the directory") and the truncation are unit-tested
 * without a device: a wrong rule would show the wrong crash or an empty report and send a
 * misleading diagnosis.
 *
 * <p>Reads are bounded, so a multi-megabyte tombstone cannot be loaded whole into the UI thread.
 */
public final class CrashReportStore {

    /** The most a report is allowed to occupy, so the dialog stays responsive. */
    public static final int MAX_BYTES = 256 * 1024;

    private CrashReportStore() {
    }

    /** A located report: the file plus its (possibly truncated) text. */
    public static final class Report {
        public final File file;
        public final String text;
        public final boolean truncated;

        Report(File file, String text, boolean truncated) {
            this.file = file;
            this.text = text;
            this.truncated = truncated;
        }

        /** True when a real report was read. */
        public boolean isPresent() {
            return file != null && text != null && !text.isEmpty();
        }
    }

    /** The newest regular file in the directory, or null when the directory is missing/empty. */
    public static File newestReportFile(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;
        File newest = null;
        for (File child : children) {
            if (child == null || !child.isFile()) continue;
            if (newest == null || child.lastModified() > newest.lastModified()) {
                newest = child;
            }
        }
        return newest;
    }

    /** Reads the newest report in {@code dir}, or an empty report when there is none. */
    public static Report readDir(File dir) {
        return read(newestReportFile(dir));
    }

    /** Reads one report file, truncating past {@link #MAX_BYTES}. Never throws. */
    public static Report read(File file) {
        if (file == null || !file.isFile()) return new Report(null, "", false);
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[MAX_BYTES];
            int read = 0;
            while (read < buffer.length) {
                int n = in.read(buffer, read, buffer.length - read);
                if (n <= 0) break;
                read += n;
            }
            boolean truncated = in.read() != -1;
            String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
            return new Report(file, text, truncated);
        } catch (IOException | RuntimeException e) {
            return new Report(file, "", false);
        }
    }

    /**
     * The last non-empty line of a stack trace, which is the exception line a crash log usually
     * carries at the top. Used for the one-line summary; returns "" when nothing matches.
     */
    public static String firstExceptionLine(String text) {
        if (text == null || text.isEmpty()) return "";
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.contains("Exception") || trimmed.contains("Error")) {
                return trimmed;
            }
        }
        return "";
    }
}
