package org.chimeramc.client.core.minecraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pins the native-library load order (item G3, the multi-{@code .so} half).
 *
 * <p>Android resolves {@code DT_NEEDED} against the already-loaded set, so a dependency must be
 * loaded before the library that needs it. These tests pin the ordering, the "a platform library is
 * not a sibling" rule and the cycle/self-reference guards.
 */
public class NativeLibraryLoadOrderTest {

    private static NativeLibraryLoadOrder.Entry entry(String fileName, String soname,
                                                      String... needed) {
        return new NativeLibraryLoadOrder.Entry(fileName, soname,
                needed == null ? Collections.emptyList() : Arrays.asList(needed));
    }

    private static int indexOf(List<String> order, String name) {
        return order.indexOf(name);
    }

    @Test
    public void aDependencyIsLoadedBeforeTheLibraryThatNeedsIt() {
        List<NativeLibraryLoadOrder.Entry> entries = Arrays.asList(
                entry("libentry.so", "libentry.so", "libhelper.so"),
                entry("libhelper.so", "libhelper.so"));
        List<String> order = NativeLibraryLoadOrder.order(entries);
        assertEquals(2, order.size());
        assertTrue(indexOf(order, "libhelper.so") < indexOf(order, "libentry.so"));
    }

    @Test
    public void aThreeDeepChainLoadsLeafFirst() {
        List<NativeLibraryLoadOrder.Entry> entries = Arrays.asList(
                entry("liba.so", "liba.so", "libb.so"),
                entry("libb.so", "libb.so", "libc.so"),
                entry("libc.so", "libc.so"));
        assertEquals(Arrays.asList("libc.so", "libb.so", "liba.so"),
                NativeLibraryLoadOrder.order(entries));
    }

    @Test
    public void aPlatformDependencyThatNoSiblingProvidesIsIgnored() {
        // liblog.so is a platform library; it must not stop libentry.so being returned.
        List<NativeLibraryLoadOrder.Entry> entries = Arrays.asList(
                entry("libentry.so", "libentry.so", "liblog.so", "libc++_shared.so"));
        assertEquals(Arrays.asList("libentry.so"), NativeLibraryLoadOrder.order(entries));
    }

    @Test
    public void aSiblingIsMatchedByItsDeclaredSonameNotOnlyItsFileName() {
        // The entry needs "libhelper.so.1"; the sibling file is libhelper.so but declares that soname.
        List<NativeLibraryLoadOrder.Entry> entries = Arrays.asList(
                entry("libentry.so", "libentry.so", "libhelper.so.1"),
                entry("libhelper.so", "libhelper.so.1"));
        assertEquals(Arrays.asList("libhelper.so", "libentry.so"),
                NativeLibraryLoadOrder.order(entries));
    }

    @Test
    public void independentLibrariesKeepTheirInputOrder() {
        List<NativeLibraryLoadOrder.Entry> entries = Arrays.asList(
                entry("liba.so", "liba.so"),
                entry("libb.so", "libb.so"),
                entry("libc.so", "libc.so"));
        assertEquals(Arrays.asList("liba.so", "libb.so", "libc.so"),
                NativeLibraryLoadOrder.order(entries));
    }

    @Test
    public void aCycleIsBrokenAndEveryLibraryIsReturnedExactlyOnce() {
        List<NativeLibraryLoadOrder.Entry> entries = Arrays.asList(
                entry("liba.so", "liba.so", "libb.so"),
                entry("libb.so", "libb.so", "liba.so"));
        List<String> order = NativeLibraryLoadOrder.order(entries);
        assertEquals(2, order.size());
        assertTrue(order.containsAll(Arrays.asList("liba.so", "libb.so")));
    }

    @Test
    public void aSelfReferenceIsNotADependency() {
        List<NativeLibraryLoadOrder.Entry> entries =
                Collections.singletonList(entry("liba.so", "liba.so", "liba.so"));
        assertEquals(Arrays.asList("liba.so"), NativeLibraryLoadOrder.order(entries));
    }

    @Test
    public void aLibraryWithNoReadableDynamicSectionIsStillReturned() {
        // A null soname + no needed entries is what a statically linked or unreadable sibling looks
        // like; it must not be silently dropped from the load list.
        List<NativeLibraryLoadOrder.Entry> entries = Arrays.asList(
                entry("libstatic.so", null),
                entry("libentry.so", "libentry.so", "libstatic.so"));
        List<String> order = NativeLibraryLoadOrder.order(entries);
        assertEquals(2, order.size());
        assertEquals("libstatic.so", order.get(0));
        assertEquals("libentry.so", order.get(1));
    }

    @Test
    public void anEmptyOrNullBatchOrdersToNothing() {
        assertTrue(NativeLibraryLoadOrder.order(new ArrayList<>()).isEmpty());
        assertTrue(NativeLibraryLoadOrder.order(null).isEmpty());
    }
}
