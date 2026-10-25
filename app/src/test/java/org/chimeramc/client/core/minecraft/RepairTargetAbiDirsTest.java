package org.chimeramc.client.core.minecraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pins which native-library folders the repair paths are allowed to clear.
 *
 * <p>The rule exists because two different pieces of code name the extraction folder and they can
 * disagree. {@code GamePackageManager.getDeviceAbi} derives it from the ABIs the version's APK
 * actually ships, while {@link MinecraftLauncher#resolveLaunchAbi} prefers the version's recorded
 * {@code abiList} - a record of what was extracted <em>last</em>, not what the APK offers. A repair
 * narrowed to the folder {@code resolveLaunchAbi} predicts therefore deletes markers in a directory
 * the extractor will not reuse, leaves the stale cache in place, and still reports success.
 *
 * <p>No mocks and no Android runtime: {@link MinecraftLauncher} holds no static platform state, and
 * the rule is a pure mapping over ABI names.
 */
public class RepairTargetAbiDirsTest {

    @Test
    public void coversEveryDirectoryTheExtractorCanWriteTo() {
        Set<String> targets = new HashSet<>(Arrays.asList(MinecraftLauncher.repairTargetAbiDirs()));
        for (String abi : AbiBitness.INSTANCE.getABI_CANDIDATES()) {
            String dir = MinecraftLauncher.abiToSystemLibDir(abi);
            assertTrue("repair must clear the extraction folder for " + abi + " (" + dir + ")",
                    targets.contains(dir));
        }
    }

    @Test
    public void namesTheDirectoriesNotTheAbiStrings() {
        List<String> targets = Arrays.asList(MinecraftLauncher.repairTargetAbiDirs());
        assertTrue("arm64-v8a extracts into arm64", targets.contains("arm64"));
        assertTrue("armeabi-v7a extracts into arm", targets.contains("arm"));
        assertFalse("the extractor never creates a directory named after the ABI string",
                targets.contains("arm64-v8a"));
        assertFalse(targets.contains("armeabi-v7a"));
    }

    @Test
    public void isNotNarrowedToASingleAbi() {
        // The regression this guards: "only clear the folder resolveLaunchAbi predicts". That reads
        // as an optimisation and silently restores the bug, because resolveLaunchAbi can name a
        // folder the extractor will not use. Both bitnesses must stay covered.
        Set<String> targets = new HashSet<>(Arrays.asList(MinecraftLauncher.repairTargetAbiDirs()));
        assertTrue(targets.contains(MinecraftLauncher.abiToSystemLibDir("arm64-v8a")));
        assertTrue(targets.contains(MinecraftLauncher.abiToSystemLibDir("armeabi-v7a")));
    }

    @Test
    public void hasNoDuplicates() {
        String[] targets = MinecraftLauncher.repairTargetAbiDirs();
        Set<String> unique = new HashSet<>(Arrays.asList(targets));
        assertEquals("a duplicated target means the deletes run twice", targets.length, unique.size());
    }

    @Test
    public void mapsAbiStringsToTheFoldersTheExtractorCreates() {
        assertEquals("arm64", MinecraftLauncher.abiToSystemLibDir("arm64-v8a"));
        assertEquals("arm", MinecraftLauncher.abiToSystemLibDir("armeabi-v7a"));
        assertEquals("x86_64", MinecraftLauncher.abiToSystemLibDir("x86_64"));
        assertEquals("x86", MinecraftLauncher.abiToSystemLibDir("x86"));
    }
}
