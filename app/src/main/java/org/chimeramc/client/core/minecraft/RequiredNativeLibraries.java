package org.chimeramc.client.core.minecraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The native libraries a launch opens out of an instance's extracted cache.
 *
 * <p>Pure and Android-free so a JVM test can assert against it. The list used to be a private field
 * of {@code GamePackageManager}, whose class initialisation reaches the native bridge, so any caller
 * outside it (the repair-need check, the diagnostics screen) either duplicated the list or could not
 * read it at all. Duplicating it is how a cache comes to be judged complete while the loader still
 * wants a library the list omitted — the failure this rule exists to prevent.
 *
 * <p>{@link #required(String)} adds the version-gated libraries from
 * {@link NativeLibraryVersionGate} for the same reason: they are opened from the same directory, so
 * leaving them out lets an interrupted extraction look complete and then fail at {@code dlopen}.
 */
public final class RequiredNativeLibraries {

    private RequiredNativeLibraries() {
    }

    /**
     * Always required, whatever the build.
     *
     * <p>{@code libc++_shared.so} is in the list even though the launcher can supply its own copy:
     * the fallback runs before the completeness check, so by the time this list is consulted the
     * library is either the package's own or the launcher's.
     */
    public static final List<String> BASE = Collections.unmodifiableList(Arrays.asList(
            "libc++_shared.so",
            "libfmod.so",
            "libMediaDecoders_Android.so",
            "libminecraftpe.so"
    ));

    /**
     * Shipped by some builds only; extracted when present but never required.
     *
     * <p>{@code libHttpClient.Android.so} is the case: a build that does not use the bundled layout
     * has no such file, and requiring it refused a launchable version.
     */
    public static final List<String> OPTIONAL = Collections.unmodifiableList(Collections.singletonList(
            "libHttpClient.Android.so"
    ));

    /** The required list for a build, including its version-gated libraries. */
    public static List<String> required(String versionCode) {
        List<String> libs = new ArrayList<>(BASE);
        libs.addAll(NativeLibraryVersionGate.gatedLibraries(versionCode));
        return libs;
    }

    /** Every library the extractor copies when the package ships it. */
    public static List<String> extractable() {
        List<String> libs = new ArrayList<>(BASE);
        libs.addAll(OPTIONAL);
        return libs;
    }
}
