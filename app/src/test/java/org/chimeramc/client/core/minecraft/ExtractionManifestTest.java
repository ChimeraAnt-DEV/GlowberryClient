package org.chimeramc.client.core.minecraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pins the extraction-manifest format.
 *
 * <p>The reuse decision compares the manifest on disk against a freshly built one by string
 * equality, so the format is a contract, not a detail: a changed field order, separator or extra
 * line makes every cache look stale and re-extracts the package on every launch. These cases exist
 * so such a change fails here instead of shipping as a slow launcher.
 */
public class ExtractionManifestTest {

    private static final List<ExtractionManifest.ApkEntry> APKS = Arrays.asList(
            new ExtractionManifest.ApkEntry("/data/app/base.apk.chimera", 123456L, 1700000000000L),
            new ExtractionManifest.ApkEntry("/data/app/splits/config.arm64.apk.chimera", 4096L, 1700000001000L)
    );

    private static String sample() {
        return ExtractionManifest.build(
                5, "arm64-v8a", "guard-token", "isolated", true, APKS);
    }

    @Test
    public void theFormatIsTheDocumentedOne() {
        String expected =
                "extractor=5\n"
                        + "abi=arm64-v8a\n"
                        + "token=guard-token\n"
                        + "mode=isolated\n"
                        + "http=true\n"
                        + "apk=/data/app/base.apk.chimera|123456|1700000000000\n"
                        + "apk=/data/app/splits/config.arm64.apk.chimera|4096|1700000001000\n";
        assertEquals(expected, sample());
    }

    @Test
    public void theSameInputsProduceTheSameText() {
        // The whole point: two callers (extractor and repair) building from the same facts must
        // agree, or the repair's write is discarded by the next launch.
        assertEquals(sample(), sample());
    }

    @Test
    public void aChangedExtractorVersionChangesTheText() {
        assertNotEquals(sample(), ExtractionManifest.build(
                6, "arm64-v8a", "guard-token", "isolated", true, APKS));
    }

    @Test
    public void aChangedAbiChangesTheText() {
        assertNotEquals(sample(), ExtractionManifest.build(
                5, "armeabi-v7a", "guard-token", "isolated", true, APKS));
    }

    @Test
    public void aChangedApkLengthOrTimestampChangesTheText() {
        // A repackaged APK is the reason the manifest records length and mtime: same path, new
        // contents, and the cache must not be reused.
        assertNotEquals(sample(), ExtractionManifest.build(5, "arm64-v8a", "guard-token", "isolated", true,
                Arrays.asList(
                        new ExtractionManifest.ApkEntry("/data/app/base.apk.chimera", 123457L, 1700000000000L),
                        APKS.get(1))));
        assertNotEquals(sample(), ExtractionManifest.build(5, "arm64-v8a", "guard-token", "isolated", true,
                Arrays.asList(
                        new ExtractionManifest.ApkEntry("/data/app/base.apk.chimera", 123456L, 1700000009999L),
                        APKS.get(1))));
    }

    @Test
    public void theApkOrderDoesNotChangeTheManifest() {
        // The two callers collect the same set of packages in different orders: the extractor sorts
        // its list, the repair walks the instance directory and takes the splits as the file system
        // returns them. Comparing by string equality made that ordering difference alone look like a
        // changed cache, so the repair's write was discarded and the package re-extracted - the
        // exact waste the repair was meant to save. The order is canonicalised here, so the two
        // callers cannot disagree.
        String reversed = ExtractionManifest.build(5, "arm64-v8a", "guard-token", "isolated", true,
                Arrays.asList(APKS.get(1), APKS.get(0)));
        assertEquals(sample(), reversed);
    }

    @Test
    public void theCanonicalOrderIsByPath() {
        // Named so a future change to the sort key is a deliberate decision, not an accident: the
        // manifest must be stable for a given set of packages, and path is the only field that
        // identifies one.
        String text = ExtractionManifest.build(5, "arm64-v8a", "guard-token", "isolated", true,
                Arrays.asList(APKS.get(1), APKS.get(0)));
        assertTrue(text.indexOf("base.apk.chimera") < text.indexOf("config.arm64"));
    }

    @Test
    public void anEmptyApkListStillProducesAWellFormedManifest() {
        String text = ExtractionManifest.build(5, "arm64-v8a", "guard-token", "installed", false,
                Collections.emptyList());
        assertEquals("extractor=5\nabi=arm64-v8a\ntoken=guard-token\nmode=installed\nhttp=false\n", text);
        assertFalse(text.contains("apk="));
    }

    @Test
    public void aNullApkListDoesNotThrow() {
        String text = ExtractionManifest.build(5, "arm64-v8a", "guard-token", "installed", false, null);
        assertEquals("extractor=5\nabi=arm64-v8a\ntoken=guard-token\nmode=installed\nhttp=false\n", text);
    }

    @Test
    public void aNullEntryIsSkippedRatherThanRenderedAsNull() {
        String text = ExtractionManifest.build(5, "arm64-v8a", "guard-token", "isolated", true,
                Arrays.asList(APKS.get(0), null));
        assertEquals("extractor=5\nabi=arm64-v8a\ntoken=guard-token\nmode=isolated\nhttp=true\n"
                + "apk=/data/app/base.apk.chimera|123456|1700000000000\n", text);
    }

    @Test
    public void ofReadsTheThreeFieldsFromTheFile() throws Exception {
        java.io.File file = java.io.File.createTempFile("manifest-entry", ".bin");
        try {
            java.nio.file.Files.write(file.toPath(), new byte[]{1, 2, 3, 4, 5});
            ExtractionManifest.ApkEntry entry = ExtractionManifest.ApkEntry.of(file);
            assertEquals(file.getAbsolutePath(), entry.path);
            assertEquals(5L, entry.length);
            assertEquals(file.lastModified(), entry.lastModified);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void ofANullFileIsAnEmptyEntryRatherThanACrash() {
        ExtractionManifest.ApkEntry entry = ExtractionManifest.ApkEntry.of(null);
        assertEquals("", entry.path);
        assertEquals(0L, entry.length);
        assertEquals(0L, entry.lastModified);
    }
}
