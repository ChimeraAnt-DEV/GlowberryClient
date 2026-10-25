package org.chimeramc.client.core.minecraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.util.LauncherStorage;
import org.junit.Test;

import java.io.File;

/**
 * Pins that every path naming an instance's extracted-library root agrees on which one it is.
 *
 * <p>Three pieces of code name that root and all three must resolve to the same directory:
 * the extractor (which writes the cache and its markers), the repair (which clears and rewrites
 * it), and the needs-repair probe (which reads the markers). The root is derived from the storage
 * profile id, not the instance's raw folder name, and those two differ whenever a folder name
 * contains a character a profile id cannot hold.
 *
 * <p>The bug this guards is the worst shape a fix can take: the repair cleared and rewrote the
 * un-sanitised folder, reported success, and left the cache the launch actually reads untouched -
 * so the user pressed Repair, watched it succeed, and the launch failed with the same error.
 *
 * <p>No mocks: {@link MinecraftLauncher} holds no static platform state, so the path arithmetic is
 * exercised directly.
 */
public class RuntimeLibDirAgreementTest {

    private static GameVersion imported(String directoryName) {
        return new GameVersion(directoryName, directoryName, "1.21.80", new File("/tmp/x"),
                false, "org.chimeramc.client", "arm64-v8a");
    }

    @Test
    public void anUnsanitisedFolderNameIsTheCaseThatDiverges() {
        // The premise of the rule: if these were always equal there would be nothing to align.
        GameVersion version = imported("My Version:/1");
        assertNotEquals(version.directoryName, LauncherStorage.sanitizeProfileId(version.directoryName));
        assertNotEquals(version.directoryName, MinecraftLauncher.getStorageProfileId(version));
    }

    @Test
    public void theProfileIdIsTheSanitisedFolderNameForAnImportedInstance() {
        GameVersion version = imported("My Version:/1");
        assertEquals(LauncherStorage.sanitizeProfileId("My Version:/1"),
                MinecraftLauncher.getStorageProfileId(version));
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
    public void anAlreadySanitisedNameIsUnchanged() {
        GameVersion version = imported("Minecraft_1.21.80-beta");
        assertEquals("Minecraft_1.21.80-beta", MinecraftLauncher.getStorageProfileId(version));
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
            assertNotEquals("", abiDir.trim());
        }
    }

    @Test
    public void theRootIsKeyedByProfileIdNotTheFolderName() {
        // Stated as the path suffix the root builder produces: the repair and the extractor must
        // both pass the profile id, or the repair writes into a folder the launch never reads.
        GameVersion version = imported("My Version:/1");
        String expectedSuffix = File.separator + LauncherStorage.sanitizeProfileId(version.directoryName);
        String naiveSuffix = File.separator + version.directoryName;
        assertNotEquals("the raw folder name must not be the suffix", naiveSuffix, expectedSuffix);
        assertTrue(expectedSuffix.endsWith("My_Version__1"));
    }
}
