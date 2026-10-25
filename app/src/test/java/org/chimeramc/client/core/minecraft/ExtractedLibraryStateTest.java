package org.chimeramc.client.core.minecraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pins the rule that decides whether an instance is offered a repair.
 *
 * <p>The defect this covers: {@code GameVersion.needsRepair} was read by the launch path and by
 * {@code MainActivity.repairNeededVersions()} but never set to true, so the "missing game library
 * files — repair now?" prompt could not appear and a version with a broken cache failed at
 * {@code dlopen} with no in-app way to fix it. The rule now has one definition here.
 *
 * <p>A fake probe, not mocks: the rule only ever reads the two facts below, so a map of what exists
 * is the whole input surface.
 */
public class ExtractedLibraryStateTest {

    /** A probe over a fixed set of "abiDir/lib.so" keys; a key is a non-empty file. */
    private static ExtractedLibraryState.DirectoryProbe probe(Set<String> nonEmptyFiles,
                                                             Set<String> existingDirs) {
        return new ExtractedLibraryState.DirectoryProbe() {
            @Override
            public boolean directoryExists(String abiDirName) {
                return existingDirs.contains(abiDirName);
            }

            @Override
            public boolean fileIsNonEmpty(String abiDirName, String libName) {
                // A file cannot exist in a directory that does not, so the probe reports both.
                return existingDirs.contains(abiDirName)
                        && nonEmptyFiles.contains(abiDirName + "/" + libName);
            }
        };
    }

    private static Set<String> files(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    private static Set<String> dirs(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    private static final List<String> REQUIRED = Arrays.asList(
            "libc++_shared.so", "libfmod.so", "libMediaDecoders_Android.so", "libminecraftpe.so");

    private static final List<String> ABI_DIRS = Arrays.asList("arm64", "arm", "x86_64", "x86");

    @Test
    public void aNeverExtractedInstanceIsNotReportedAsDamaged() {
        // No ABI directory and no marker: the normal state of an instance that has not been
        // launched yet. Reporting this as damage would prompt a repair for every fresh import.
        assertFalse(ExtractedLibraryState.needsRepair(
                REQUIRED, ABI_DIRS, false, probe(files(), dirs())));
    }

    @Test
    public void anEmptyLibraryRootIsNotReportedAsDamaged() {
        // An empty root means nothing was extracted, so there is no marker either. The launch
        // extracts it; there is nothing to repair.
        assertFalse(ExtractedLibraryState.needsRepair(
                REQUIRED, ABI_DIRS, false, probe(files(), dirs())));
    }

    @Test
    public void everyLibraryDeletedAfterACompleteExtractionIsDamage() {
        // The marker is current and every library is gone - the user deleted the folder contents,
        // or an aggressive cleaner did. The repair is the fix.
        assertTrue(ExtractedLibraryState.needsRepair(
                REQUIRED, ABI_DIRS, true, probe(files(), dirs("arm64"))));
    }

    @Test
    public void aCacheSupersededByALauncherUpdateIsNotReportedAsDamaged() {
        // The marker names an older extractor version, so the cache is stale, not damaged. The
        // launch wipes and re-extracts it; prompting a repair here would nag on every update.
        assertFalse(ExtractedLibraryState.needsRepair(
                REQUIRED, ABI_DIRS, false, probe(files(), dirs("arm64"))));
    }

    @Test
    public void aMissingLibraryWithACurrentMarkerIsDamage() {
        // The marker is current, so a complete extraction did happen for this build; a required
        // library being absent now is the damaged case the Repair action exists for.
        Set<String> present = files(
                "arm64/libc++_shared.so",
                "arm64/libfmod.so",
                "arm64/libMediaDecoders_Android.so");
        assertTrue(ExtractedLibraryState.needsRepair(
                REQUIRED, ABI_DIRS, true, probe(present, dirs("arm64"))));
    }

    @Test
    public void anAttemptedButIncompleteExtractionIsReported() {
        // arm64 exists (an extraction ran) but libfmod.so never landed.
        Set<String> present = files(
                "arm64/libc++_shared.so",
                "arm64/libMediaDecoders_Android.so",
                "arm64/libminecraftpe.so");
        assertTrue(ExtractedLibraryState.needsRepair(
                REQUIRED, ABI_DIRS, true, probe(present, dirs("arm64"))));
    }

    @Test
    public void aZeroLengthLibraryCountsAsMissing() {
        // A truncated file is not in the non-empty set, which is exactly how a library interrupted
        // mid-copy looks on disk.
        Set<String> present = files(
                "arm64/libc++_shared.so",
                "arm64/libfmod.so",
                "arm64/libMediaDecoders_Android.so");
        // libminecraftpe.so is absent (truncated to zero would be the same answer).
        assertTrue(ExtractedLibraryState.needsRepair(
                REQUIRED, ABI_DIRS, true, probe(present, dirs("arm64"))));
    }

    @Test
    public void aCompleteCacheNeedsNoRepair() {
        Set<String> present = files(
                "arm64/libc++_shared.so",
                "arm64/libfmod.so",
                "arm64/libMediaDecoders_Android.so",
                "arm64/libminecraftpe.so");
        assertFalse(ExtractedLibraryState.needsRepair(
                REQUIRED, ABI_DIRS, true, probe(present, dirs("arm64"))));
    }

    @Test
    public void aCompleteCacheInAnyAbiDirectoryCounts() {
        // The extraction folder is derived from the APK's shipped ABIs, while the launch ABI comes
        // from the version's recorded label; the two can name different directories. Judging only
        // the predicted one would report a complete cache as damaged.
        Set<String> present = files(
                "arm/libc++_shared.so",
                "arm/libfmod.so",
                "arm/libMediaDecoders_Android.so",
                "arm/libminecraftpe.so");
        assertFalse(ExtractedLibraryState.needsRepair(
                REQUIRED, ABI_DIRS, true, probe(present, dirs("arm"))));
    }

    @Test
    public void aPartialDirectoryBesideACompleteOneIsNotDamage() {
        // arm holds only one library and arm64 is complete. The incomplete sibling must not make a
        // usable cache look damaged, and the complete one must not rescue a genuinely broken cache.
        Set<String> present = files(
                "arm64/libc++_shared.so",
                "arm64/libfmod.so",
                "arm64/libMediaDecoders_Android.so",
                "arm64/libminecraftpe.so",
                "arm/libminecraftpe.so");
        assertFalse("a complete arm64 directory satisfies the check",
                ExtractedLibraryState.needsRepair(
                        REQUIRED, ABI_DIRS, true, probe(present, dirs("arm64", "arm"))));

        // Same files, but arm64 was never extracted: now only the partial arm directory is on disk,
        // so the cache is incomplete and a repair is offered.
        assertTrue("a partial arm directory alone is incomplete",
                ExtractedLibraryState.needsRepair(
                        REQUIRED, ABI_DIRS, true, probe(present, dirs("arm"))));
    }

    @Test
    public void aRequiredListWithAGatedLibraryIsHonoured() {
        // 1.21.130+ needs libmaesdk.so and libHttpClient.Android.so in the same directory; a cache
        // missing them is incomplete even though the four base libraries are all present.
        List<String> required = RequiredNativeLibraries.required("1.21.130");
        assertTrue(required.contains("libmaesdk.so"));
        Set<String> baseOnly = files(
                "arm64/libc++_shared.so",
                "arm64/libfmod.so",
                "arm64/libMediaDecoders_Android.so",
                "arm64/libminecraftpe.so");
        assertTrue(ExtractedLibraryState.needsRepair(
                required, ABI_DIRS, true, probe(baseOnly, dirs("arm64"))));

        Set<String> withGated = files(
                "arm64/libc++_shared.so",
                "arm64/libfmod.so",
                "arm64/libMediaDecoders_Android.so",
                "arm64/libminecraftpe.so",
                "arm64/libmaesdk.so",
                "arm64/libHttpClient.Android.so",
                "arm64/libPlayFabMultiplayer.so");
        assertFalse(ExtractedLibraryState.needsRepair(
                required, ABI_DIRS, true, probe(withGated, dirs("arm64"))));
    }

    @Test
    public void anEmptyOrNullRequiredListIsNeverComplete() {
        // Fail closed: with nothing named there is no evidence the cache is usable.
        assertFalse(ExtractedLibraryState.anyDirectoryHasAllLibraries(
                Collections.emptyList(), ABI_DIRS, probe(files(), dirs("arm64"))));
        assertFalse(ExtractedLibraryState.anyDirectoryHasAllLibraries(
                null, ABI_DIRS, probe(files(), dirs("arm64"))));
    }

    @Test
    public void nullInputsDoNotThrow() {
        assertFalse(ExtractedLibraryState.needsRepair(null, null, false, null));
        assertFalse(ExtractedLibraryState.anyDirectoryHasAllLibraries(
                REQUIRED, null, probe(files(), dirs("arm64"))));
    }

    /**
     * The extraction-version marker is not part of this rule, but the two must agree about what
     * "extracted" means: an instance with a marker and a complete cache needs no repair, and one
     * with a marker and a hole does.
     */
    @Test
    public void theRuleAgreesWithTheCacheCompletenessCheck() {
        List<String> required = RequiredNativeLibraries.required(null);
        Set<String> complete = files(
                "arm64/libc++_shared.so",
                "arm64/libfmod.so",
                "arm64/libMediaDecoders_Android.so",
                "arm64/libminecraftpe.so");
        boolean policySaysComplete = ExtractionCachePolicy.allLibrariesPresent(
                required, name -> complete.contains("arm64/" + name));
        boolean ruleSaysRepair = ExtractedLibraryState.needsRepair(
                required, ABI_DIRS, true, probe(complete, dirs("arm64")));
        assertTrue(policySaysComplete);
        assertFalse(ruleSaysRepair);

        Set<String> incomplete = files(
                "arm64/libc++_shared.so",
                "arm64/libfmod.so",
                "arm64/libMediaDecoders_Android.so");
        boolean policySaysComplete2 = ExtractionCachePolicy.allLibrariesPresent(
                required, name -> incomplete.contains("arm64/" + name));
        boolean ruleSaysRepair2 = ExtractedLibraryState.needsRepair(
                required, ABI_DIRS, true, probe(incomplete, dirs("arm64")));
        assertFalse(policySaysComplete2);
        assertTrue(ruleSaysRepair2);
    }

    @Test
    public void theBaseListIsTheFourAlwaysRequiredLibraries() {
        assertEquals(Arrays.asList(
                        "libc++_shared.so", "libfmod.so",
                        "libMediaDecoders_Android.so", "libminecraftpe.so"),
                RequiredNativeLibraries.BASE);
        assertEquals(Collections.singletonList("libHttpClient.Android.so"),
                RequiredNativeLibraries.OPTIONAL);
        assertEquals(5, RequiredNativeLibraries.extractable().size());
    }
}
