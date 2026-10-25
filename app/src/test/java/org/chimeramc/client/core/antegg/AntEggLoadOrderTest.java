package org.chimeramc.client.core.antegg;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the batch dependency ordering (item G4, the "ordering" half).
 *
 * <p>The rule exists because a multi-select import used to load in picker order, so a dependency
 * that was in the same selection could still be reported missing. These tests use real archives on
 * disk, because {@link AntEggLoadOrder#entryFor} reads the manifest out of the ZIP — a fake entry
 * list would not prove the ids and ranges are read from the package.
 */
public class AntEggLoadOrderTest {

    private static File packageWith(String json) throws Exception {
        File zip = File.createTempFile("antegg-order", ".antegg");
        zip.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("egg.json"));
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("bin/libmod.so"));
            out.write(new byte[]{1, 2, 3});
            out.closeEntry();
        }
        return zip;
    }

    private static String manifest(String name, String dependencies) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"version\":\"1.0.0\","
                + "\"author\":\"Someone\","
                + "\"type\":\"native\","
                + "\"entry_point\":\"bin/libmod.so\","
                + "\"dependencies\":[" + dependencies + "]"
                + "}";
    }

    private static List<Integer> indicesOf(List<AntEggLoadOrder.Entry> entries) {
        return AntEggLoadOrder.order(entries);
    }

    @Test
    public void readsTheIdAndDependenciesOutOfTheArchive() throws Exception {
        File lib = packageWith(manifest("Core Lib", "\"Other Dep>=1.2.0\""));
        AntEggLoadOrder.Entry entry = AntEggLoadOrder.entryFor(0, lib);
        assertEquals("core-lib", entry.id);
        assertEquals(Arrays.asList("other-dep"), entry.dependencyIds);
    }

    @Test
    public void aDependencyInTheBatchIsOrderedFirst() throws Exception {
        // The picker lists the dependent package first; the resolver must still install the
        // dependency first.
        File plugin = packageWith(manifest("Plugin", "\"Core Lib\""));
        File lib = packageWith(manifest("Core Lib", ""));

        List<AntEggLoadOrder.Entry> entries = new ArrayList<>();
        entries.add(AntEggLoadOrder.entryFor(0, plugin));
        entries.add(AntEggLoadOrder.entryFor(1, lib));

        assertEquals(Arrays.asList(1, 0), indicesOf(entries));
    }

    @Test
    public void aChainOrdersRootLast() throws Exception {
        File root = packageWith(manifest("Root", "\"Middle\""));
        File middle = packageWith(manifest("Middle", "\"Leaf\""));
        File leaf = packageWith(manifest("Leaf", ""));

        List<AntEggLoadOrder.Entry> entries = new ArrayList<>();
        entries.add(AntEggLoadOrder.entryFor(0, root));
        entries.add(AntEggLoadOrder.entryFor(1, middle));
        entries.add(AntEggLoadOrder.entryFor(2, leaf));

        assertEquals(Arrays.asList(2, 1, 0), indicesOf(entries));
    }

    @Test
    public void unconstrainedEntriesKeepTheirInputOrder() throws Exception {
        File a = packageWith(manifest("Alpha", ""));
        File b = packageWith(manifest("Beta", ""));
        File c = packageWith(manifest("Gamma", ""));

        List<AntEggLoadOrder.Entry> entries = new ArrayList<>();
        entries.add(AntEggLoadOrder.entryFor(0, a));
        entries.add(AntEggLoadOrder.entryFor(1, b));
        entries.add(AntEggLoadOrder.entryFor(2, c));

        assertEquals(Arrays.asList(0, 1, 2), indicesOf(entries));
    }

    @Test
    public void aDependencyOutsideTheBatchIsIgnored() throws Exception {
        // "Absent" is not in the batch; it must not reorder anything (the loader's unmet-dep check
        // reports it) and must not be mistaken for a cycle.
        File plugin = packageWith(manifest("Plugin", "\"Absent\""));
        File other = packageWith(manifest("Other", ""));

        List<AntEggLoadOrder.Entry> entries = new ArrayList<>();
        entries.add(AntEggLoadOrder.entryFor(0, plugin));
        entries.add(AntEggLoadOrder.entryFor(1, other));

        assertEquals(Arrays.asList(0, 1), indicesOf(entries));
    }

    @Test
    public void aCyclePlacesEveryInputExactlyOnce() throws Exception {
        File a = packageWith(manifest("Alpha", "\"Beta\""));
        File b = packageWith(manifest("Beta", "\"Alpha\""));

        List<AntEggLoadOrder.Entry> entries = new ArrayList<>();
        entries.add(AntEggLoadOrder.entryFor(0, a));
        entries.add(AntEggLoadOrder.entryFor(1, b));

        List<Integer> order = indicesOf(entries);
        assertEquals(2, order.size());
        assertTrue(order.containsAll(Arrays.asList(0, 1)));
    }

    @Test
    public void aSelfDependencyDoesNotLoop() throws Exception {
        File a = packageWith(manifest("Alpha", "\"Alpha\""));

        List<AntEggLoadOrder.Entry> entries = new ArrayList<>();
        entries.add(AntEggLoadOrder.entryFor(0, a));

        assertEquals(Arrays.asList(0), indicesOf(entries));
    }

    @Test
    public void anUnreadablePackageIsPlacedNotDropped() throws Exception {
        File broken = File.createTempFile("antegg-broken", ".antegg");
        broken.deleteOnExit();
        // Not a ZIP at all: entryFor must still yield a usable entry.
        try (FileOutputStream out = new FileOutputStream(broken)) {
            out.write("not a zip".getBytes(StandardCharsets.UTF_8));
        }
        File lib = packageWith(manifest("Core Lib", ""));

        List<AntEggLoadOrder.Entry> entries = new ArrayList<>();
        entries.add(AntEggLoadOrder.entryFor(0, broken));
        entries.add(AntEggLoadOrder.entryFor(1, lib));

        List<Integer> order = indicesOf(entries);
        assertEquals(2, order.size());
        assertTrue(order.containsAll(Arrays.asList(0, 1)));
    }

    @Test
    public void anEmptyBatchOrdersToNothing() {
        assertTrue(indicesOf(new ArrayList<>()).isEmpty());
        assertTrue(indicesOf(null).isEmpty());
    }
}
