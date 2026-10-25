package org.chimeramc.client.core.minecraft;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
     * @param apkFiles         the packages; the order is canonicalised, so a caller's collection
     *                         order cannot change the text
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
        for (ApkEntry entry : canonical(apkFiles)) {
            builder.append("apk=")
                    .append(entry.path)
                    .append('|')
                    .append(entry.length)
                    .append('|')
                    .append(entry.lastModified)
                    .append('\n');
        }
        return builder.toString();
    }

    /**
     * The entries in a fixed order, with nulls dropped.
     *
     * <p>The manifest identifies a <em>set</em> of packages, and the two callers collect that set
     * differently: the extractor sorts its list, while the repair walks the instance directory and
     * takes the splits in whatever order the file system returns. Comparing the two by string
     * equality made that ordering difference alone look like a changed cache - the repair's write
     * was discarded and the package re-extracted, the exact waste the repair was meant to save.
     * Canonicalising here rather than at each call site means no future caller can reintroduce it.
     */
    private static List<ApkEntry> canonical(List<ApkEntry> apkFiles) {
        if (apkFiles == null || apkFiles.isEmpty()) return Collections.emptyList();
        List<ApkEntry> entries = new ArrayList<>(apkFiles.size());
        for (ApkEntry entry : apkFiles) {
            if (entry != null) entries.add(entry);
        }
        Collections.sort(entries, new Comparator<ApkEntry>() {
            @Override
            public int compare(ApkEntry a, ApkEntry b) {
                return a.path.compareTo(b.path);
            }
        });
        return entries;
    }
}
