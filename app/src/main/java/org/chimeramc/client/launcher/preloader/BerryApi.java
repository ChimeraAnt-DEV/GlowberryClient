package org.chimeramc.client.preloader;

/**
 * Bridge to the bundled BerryAPI native runtime ({@code libBerryAPI.so}).
 *
 * <p>BerryAPI is a separate Bedrock client runtime with its own version, so the launcher never links
 * it directly; the preloader {@code dlopen}s it and reports what it found over this small surface.
 * Every call is a thin read of a cached native answer, so they are cheap enough for the Diagnostics
 * screen and the launch preflight.
 *
 * <p><b>Fail-closed.</b> When the library is missing, or its C ABI does not resolve, {@link
 * #isAvailable()} is false and every capability is clear. Nothing here fabricates a reading, so a
 * feature that depends on BerryAPI is greyed out rather than appearing to work.
 */
public final class BerryApi {

    /** Capability bits, mirroring {@code pl::berry::Capability}. */
    public static final int CAP_LIBRARY_LOADED = 1;
    public static final int CAP_ATTACHED = 1 << 1;
    /**
     * A world/entity seam is bound. Deliberately never set today: BerryAPI ships no Bedrock
     * signature table, so its world and combat reads have nothing to read from.
     */
    public static final int CAP_WORLD_FEED = 1 << 2;

    private BerryApi() {
    }

    /**
     * Loads the bundled library once and returns whether it is usable. Idempotent and safe from any
     * thread; the native side caches the answer.
     */
    public static boolean ensureLoaded() {
        try {
            return nativeEnsureLoaded();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** True when the library loaded and its C ABI resolved. */
    public static boolean isAvailable() {
        try {
            return nativeIsAvailable();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Whether BerryAPI reports a live hook backend. False when absent or its own attach failed. */
    public static boolean isAttached() {
        try {
            return nativeIsAttached();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** BerryAPI's own version string, or an empty string when unavailable. */
    public static String version() {
        try {
            String v = nativeVersion();
            return v != null ? v : "";
        } catch (UnsatisfiedLinkError e) {
            return "";
        }
    }

    /** The capability mask; a missing library reads as {@code 0}. */
    public static int capabilities() {
        try {
            return nativeCapabilities();
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }

    /** Whether one capability bit is set. */
    public static boolean hasCapability(int flag) {
        return hasBit(capabilities(), flag);
    }

    /**
     * Whether every bit in {@code flag} is set in {@code mask}.
     *
     * <p>Pure, so the bit rule is unit-tested without a device: a capability query must require the
     * whole flag, not any overlap, or a caller testing {@code ATTACHED} would pass on a mask that
     * only set an unrelated bit.
     */
    public static boolean hasBit(int mask, int flag) {
        return (mask & flag) == flag;
    }

    /** A one-line status for Diagnostics and About; never empty. */
    public static String summary() {
        try {
            String s = nativeSummary();
            return s != null && !s.isEmpty() ? s : "BerryAPI: not available";
        } catch (UnsatisfiedLinkError e) {
            return "BerryAPI: not available (preloader not loaded)";
        }
    }

    private static native boolean nativeEnsureLoaded();

    private static native boolean nativeIsAvailable();

    private static native boolean nativeIsAttached();

    private static native String nativeVersion();

    private static native int nativeCapabilities();

    private static native String nativeSummary();
}
