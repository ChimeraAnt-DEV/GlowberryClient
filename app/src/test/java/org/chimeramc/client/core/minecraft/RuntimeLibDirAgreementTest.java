package org.chimeramc.client.core.minecraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.util.LauncherStorage;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Pins that the three pieces of code which name an instance's extracted-library root agree.
 *
 * <p>The extractor writes the cache and its markers through
 * {@code MinecraftLauncher.getRuntimeLibAbiDir(context, getStorageProfileId(version), abi)}, the
 * repair clears and rewrites the same root, and the needs-repair probe reads its markers. All three
 * must resolve to one directory, or a repair writes a cache nothing reads and still reports success.
 *
 * <p><b>Why the equivalence is not obvious.</b> {@code getRuntimeLibDir} sanitises its argument
 * internally, and {@link LauncherStorage#sanitizeProfileId} is idempotent - sanitising an already
 * sanitised id returns it unchanged. So {@code getRuntimeLibDir(version.directoryName)} and
 * {@code getRuntimeLibDir(getStorageProfileId(version))} produce the <em>same</em> path for every
 * input, including a folder name like {@code "My Version:/1"}. That is a real fact worth pinning,
 * because it is the reason the two call sites cannot drift: a future change that made the root
 * builder stop sanitising, or made {@code sanitizeProfileId} non-idempotent, would silently split
 * the repair away from the extractor. The tests below assert that invariant rather than the
 * incidental wording of either call site.
 *
 * <p>No mocks: the rule is pure string and path arithmetic, so it runs on the JVM.
 */
public class RuntimeLibDirAgreementTest {

    @Test
    public void sanitisingAnAlreadySanitisedIdIsANoOp() {
        // The idempotence that makes the two call sites equivalent. Every id an instance can have is
        // already the output of a previous sanitise - the metadata store records profileId as
        // sanitizeProfileId(directoryName) - so a second pass must not change it.
        String[] ids = {
                "Minecraft_1.21.80-beta",
                "My_Version__1",
                "_shared_profile",
                "_legacy_unclassified_profile",
                "a_b",
                "trailing",
                "default",
                "com.mojang.minecraftpe",
                "___",
        };
        for (String id : ids) {
            assertEquals("sanitizeProfileId must be idempotent for " + id,
                    id, LauncherStorage.sanitizeProfileId(id));
        }
    }

    @Test
    public void anUnsanitisedFolderNameIsNormalisedToTheProfileId() {
        // The difference that would exist if the root builder did not sanitise: these are not equal.
        assertEquals("My_Version__1", LauncherStorage.sanitizeProfileId("My Version:/1"));
        assertEquals("trailing", LauncherStorage.sanitizeProfileId("trailing."));
        assertEquals("default", LauncherStorage.sanitizeProfileId(""));
    }

    @Test
    public void bothCallSiteFormsResolveToTheSameDirectory() {
        // The heart of it: the repair's expression and the extractor's expression must agree, so a
        // repair cannot write into a folder the launch never reads. Modelled as the path suffix the
        // root builder produces, since the builder itself needs a Context.
        String[] folderNames = {
                "Minecraft_1.21.80-beta",
                "My Version:/1",
                "_shared",
                "_legacy_unclassified",
                "  spaced  ",
                "trailing.",
                "",
                "com.mojang.minecraftpe",
        };
        for (String folderName : folderNames) {
            String viaRawFolderName = LauncherStorage.sanitizeProfileId(folderName);
            String viaProfileId = LauncherStorage.sanitizeProfileId(
                    LauncherStorage.sanitizeProfileId(folderName));
            assertEquals("the repair and the extractor must name one directory for \""
                            + folderName + "\"", viaRawFolderName, viaProfileId);
        }
    }

    @Test
    public void theProfileIdIsTheSanitisedFolderNameForAnImportedInstance() {
        GameVersion version = new GameVersion("My Version:/1", "My Version:/1", "1.21.80",
                new File("/tmp/x"), false, "org.chimeramc.client", "arm64-v8a");
        assertEquals("My_Version__1", MinecraftLauncher.getStorageProfileId(version));
    }

    @Test
    public void theInstalledPackageAlwaysUsesItsOwnProfileId() {
        // The installed Minecraft package has no per-instance folder, so its id is fixed whatever
        // the folder name says. A repair that used directoryName would write a cache nothing reads.
        GameVersion installed = new GameVersion("whatever", "Minecraft", "1.21.80",
                new File("/tmp/x"), true, "com.mojang.minecraftpe", "arm64-v8a");
        assertEquals(LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID,
                MinecraftLauncher.getStorageProfileId(installed));
    }

    @Test
    public void aNullVersionResolvesRatherThanThrowing() {
        assertEquals(LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID,
                MinecraftLauncher.getStorageProfileId(null));
    }

    @Test
    public void everyRepairTargetIsAPlainDirectoryNameUnderTheRoot() {
        // The repair composes <root>/<abiDir> from MinecraftLauncher.repairTargetAbiDirs(), and the
        // extractor composes the same path from abiToSystemLibDir(abi). If a target ever carried a
        // separator it would escape the root, so the targets are asserted to be single segments.
        for (String abiDir : MinecraftLauncher.repairTargetAbiDirs()) {
            assertFalse("an ABI directory must not be a path: " + abiDir,
                    abiDir.contains("/") || abiDir.contains(File.separator));
            assertTrue("an ABI directory must not be blank", abiDir.trim().length() > 0);
        }
    }

    @Test
    public void theRepairTargetsCoverEveryAbiDirectoryTheExtractorCreates() {
        // repairTargetAbiDirs is the one list the repair clears; abiToSystemLibDir is the one the
        // extractor writes through. If the repair list stopped covering an ABI the extractor can
        // target, the repair would leave that folder's stale cache in place while reporting success.
        Set<String> targets = new HashSet<>(Arrays.asList(MinecraftLauncher.repairTargetAbiDirs()));
        for (String abi : AbiBitness.INSTANCE.getABI_CANDIDATES()) {
            assertTrue("the repair must clear the folder for " + abi,
                    targets.contains(MinecraftLauncher.abiToSystemLibDir(abi)));
        }
    }
}
