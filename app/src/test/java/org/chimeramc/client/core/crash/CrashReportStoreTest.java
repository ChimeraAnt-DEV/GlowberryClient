package org.chimeramc.client.core.crash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Pins {@link CrashReportStore}: it must pick the newest report, read it, truncate a huge one, and
 * degrade to an empty report on a missing directory. A wrong selection rule would send the wrong
 * crash trace to the developer.
 */
public class CrashReportStoreTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File write(String name, String content, long lastModified) throws IOException {
        File f = new File(temp.getRoot(), name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        f.setLastModified(lastModified);
        return f;
    }

    @Test
    public void theNewestFileIsChosen() throws IOException {
        write("old.log", "old crash", 1_000L);
        write("new.log", "new crash", 5_000L);
        write("mid.log", "mid crash", 3_000L);
        File newest = CrashReportStore.newestReportFile(temp.getRoot());
        assertNotNull(newest);
        assertEquals("new.log", newest.getName());
    }

    @Test
    public void aMissingDirectoryReadsAsEmpty() {
        File missing = new File(temp.getRoot(), "does-not-exist");
        CrashReportStore.Report report = CrashReportStore.read(missing);
        assertFalse(report.isPresent());
        assertNull(report.file);
        assertEquals("", report.text);
    }

    @Test
    public void anEmptyDirectoryReadsAsEmpty() {
        CrashReportStore.Report report = CrashReportStore.readDir(temp.getRoot());
        assertFalse(report.isPresent());
    }

    @Test
    public void aReportIsReadBack() throws IOException {
        write("crash.log", "FATAL EXCEPTION: main\njava.lang.IllegalStateException: boom\n\tat A.b",
                5_000L);
        CrashReportStore.Report report = CrashReportStore.readDir(temp.getRoot());
        assertTrue(report.isPresent());
        assertTrue(report.text.contains("IllegalStateException"));
        assertFalse(report.truncated);
    }

    @Test
    public void anOversizedReportIsTruncated() throws IOException {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < CrashReportStore.MAX_BYTES + 1000; i++) {
            big.append('x');
        }
        write("huge.log", big.toString(), 5_000L);
        CrashReportStore.Report report = CrashReportStore.readDir(temp.getRoot());
        assertTrue(report.isPresent());
        assertTrue("must flag the truncation", report.truncated);
        assertTrue("must not exceed the cap", report.text.length() <= CrashReportStore.MAX_BYTES);
    }

    @Test
    public void theExceptionLineIsExtracted() {
        String trace = "header\njava.lang.NullPointerException: at foo\n\tat A.b(C.java:1)";
        assertEquals("java.lang.NullPointerException: at foo",
                CrashReportStore.firstExceptionLine(trace));
        assertEquals("", CrashReportStore.firstExceptionLine("no exception here"));
        assertEquals("", CrashReportStore.firstExceptionLine(null));
    }

    @Test
    public void directoriesInsideTheCrashDirAreIgnored() throws IOException {
        write("real.log", "crash", 5_000L);
        File sub = new File(temp.getRoot(), "subdir");
        assertTrue(sub.mkdirs());
        sub.setLastModified(9_000L);  // newer, but not a file
        File newest = CrashReportStore.newestReportFile(temp.getRoot());
        assertEquals("real.log", newest.getName());
    }
}
