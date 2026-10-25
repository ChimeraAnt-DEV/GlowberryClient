package org.chimeramc.client.core.minecraft;

import java.io.File;
import java.util.List;

/**
 * The exact text of the extraction manifest, built in one place.
 *
 * <p>The manifest is the record of <em>which</em> APK files a cache was extracted from: the reuse
 * decision rebuilds this same text and compares it against the file on disk, so any difference —
 * a field order, a separator, a missing line — makes every cache look stale and re-extracts several
 * hundred megabytes on every launch.
 *
 * <p>That is why it is a pure class rather than a private method with a second copy beside it. The
 * launcher builds the manifest from two places: the extractor
 * ({@code GamePackageManager.extractLibraries}, which writes it) and the repair
 * ({@code GamePackageManager.writeExtractionMarkers}, which must write a byte-identical record so
 * the work it just did is reused). Two hand-kept copies of a format the reader compares by
 * equality is a silent-cache-miss waiting to happen, and a silent cache miss looks exactly like a
 * slow launcher.
 *
 * <p>Android-free, so the format is unit-tested with no device.
 */
public final class ExtractionManifest {

    private ExtractionManifest() {
    }

    /** One APK the libraries were extracted from, as the manifest records it. */
    public static final class ApkEntry {
        public final String path;
        public final long length;
        public final long lastModified;

        public ApkEntry(String path, long length, long lastModified) {
            this.path = path;
            this.length = length;
            this.lastModified = lastModified;
        }

        /** From a real file, so the two builders cannot read the three fields differently. */
        public static ApkEntry of(File file) {
            if (file == null) return new ApkEntry("", 0L, 0L);
            return new ApkEntry(file.getAbsolutePath(), file.length(), file.lastModified());
        }
    }

    /**
     * The manifest text for one extraction.
     *
     * @param extractorVersion the pipeline version, so a launcher update invalidates the cache
     * @param abi              the ABI directory the libraries were extracted into
     * @param imageGuardToken  the native-image guard token the files were prepared with
     * @param mode             {@code isolated} for an imported instance, {@code installed} otherwise
     * @param httpClientLoaded whether the version-gated HTTP client library was included
     * @param apkFiles         the packages, in the order they were read
     */
    public static String build(int extractorVersion,
                              String abi,
                              String imageGuardToken,
                              String mode,
                              boolean httpClientLoaded,
                              List<ApkEntry> apkFiles) {
        StringBuilder builder = new StringBuilder(256);
        builder.append("extractor=").append(extractorVersion).append('\n');
        builder.append("abi=").append(abi).append('\n');
        builder.append("token=").append(imageGuardToken).append('\n');
        builder.append("mode=").append(mode).append('\n');
        builder.append("http=").append(httpClientLoaded).append('\n');
        if (apkFiles != null) {
            for (ApkEntry entry : apkFiles) {
                if (entry == null) continue;
                builder.append("apk=")
                        .append(entry.path)
                        .append('|')
                        .append(entry.length)
                        .append('|')
                        .append(entry.lastModified)
                        .append('\n');
            }
        }
        return builder.toString();
    }
}
