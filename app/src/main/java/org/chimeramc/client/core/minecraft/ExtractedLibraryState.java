package org.chimeramc.client.core.minecraft;

import java.util.Collection;
import java.util.List;

/**
 * Whether an instance's extracted native-library cache is damaged and should be repaired.
 *
 * <p>This is the fact behind the launch-time "Required game library files are missing. Do you want
 * to repair them now?" prompt. That prompt is driven by {@code GameVersion.needsRepair}, which the
 * launch path ({@code MinecraftLauncher.launch}) and {@code MainActivity.repairNeededVersions()}
 * both read, but which no loader ever set to true — every writer hard-coded false. So the offer
 * could not appear, and a version whose libraries had been deleted or truncated failed at
 * {@code dlopen} with no way to fix it in-app.
 *
 * <p>The rule is pure and Android-free, so it is unit-tested without a device. Its one input that
 * is not a file listing is {@code extractionMarkerCurrent}: the extraction marker (and manifest)
 * are written <em>only after</em> a complete extraction, so a current marker is the record that
 * the cache was once whole. Requiring it is what separates the three cases that look alike on disk:
 *
 * <ul>
 *   <li><b>Never extracted.</b> No marker and no libraries. A fresh import, or an instance that
 *       has not been launched yet. Not damage — the launch extracts it. Reporting this would
 *       prompt a repair for every new import.</li>
 *   <li><b>Extraction superseded.</b> The marker belongs to an older launcher build, so the cache
 *       is stale rather than damaged. The launch wipes and re-extracts it automatically
 *       ({@code ExtractionCachePolicy.shouldWipeStaleLibraries}); prompting here would nag on
 *       every launcher update.</li>
 *   <li><b>Damaged.</b> The marker is current, so the cache was complete for this build, and a
 *       required library is now missing or zero-length. This is the case the Repair action is
 *       for.</li>
 * </ul>
 *
 * <p>A zero-length file counts as missing for the same reason the cache-hit check treats it that
 * way: a library truncated mid-copy is not loadable, and {@code dlopen} reports it as a file it
 * cannot open rather than as a truncated copy.
 */
public final class ExtractedLibraryState {

    private ExtractedLibraryState() {
    }

    /**
     * True when every required library is present and non-empty in at least one ABI directory.
     *
     * <p>Any directory will do rather than only the one this launch is predicted to use: the
     * extraction folder is derived from the ABIs the version's APK ships, while the launch ABI is
     * resolved from the version's recorded label, and the two can name different directories (the
     * same disagreement the repair-target rule exists for). Requiring the predicted directory would
     * report a complete cache as damaged.
     */
    public static boolean anyDirectoryHasAllLibraries(Collection<String> required,
                                                     List<String> abiDirNames,
                                                     DirectoryProbe probe) {
        if (required == null || required.isEmpty() || abiDirNames == null || probe == null) {
            return false;
        }
        for (String abiDir : abiDirNames) {
            // The directory must exist as well as hold the files. A probe that reports a library
            // without its directory would otherwise let a cache pass this check on evidence that
            // cannot be on disk.
            if (abiDir == null || !probe.directoryExists(abiDir)) continue;
            boolean complete = true;
            for (String lib : required) {
                if (!probe.fileIsNonEmpty(abiDir, lib)) {
                    complete = false;
                    break;
                }
            }
            if (complete) return true;
        }
        return false;
    }

    /**
     * The decision the caller actually wants: is this instance's cache damaged?
     *
     * <p>Only when the extraction marker is current (so a complete extraction did happen for this
     * build) and the cache is no longer complete. See the class note for why each of the other two
     * states must not be reported.
     */
    public static boolean needsRepair(Collection<String> required,
                                      List<String> abiDirNames,
                                      boolean extractionMarkerCurrent,
                                      DirectoryProbe probe) {
        if (!extractionMarkerCurrent) return false;
        return !anyDirectoryHasAllLibraries(required, abiDirNames, probe);
    }

    /** Reads the file facts the rule needs. */
    public interface DirectoryProbe {
        /** True when the named ABI directory exists under the library root. */
        boolean directoryExists(String abiDirName);

        /** True when {@code libName} exists in that ABI directory with a non-zero length. */
        boolean fileIsNonEmpty(String abiDirName, String libName);
    }
}
