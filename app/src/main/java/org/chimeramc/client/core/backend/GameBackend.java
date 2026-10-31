package org.chimeramc.client.core.backend;

/**
 * The seam between the launcher's client-side features and whatever actually delivers input to the
 * running game and reads a frame rate back.
 *
 * <p>The launcher used to reach this through the prebuilt {@code libinbuiltmods.so} wrappers under
 * {@code org.levimc}. Those binaries are not part of this project and cannot be rebuilt, so the
 * capability is now an interface: the launcher implements it against the game's own entry points,
 * and a script runtime can implement it too. Nothing in the UI may assume a backend exists — every
 * feature asks {@link #supports} first and greys out when the answer is no, rather than appearing
 * to work and doing nothing.
 *
 * <p><b>Threading.</b> {@link #sendLookDelta} and {@link #sendPointer} are called per frame from the
 * input path, so an implementation must not block and must not allocate on that path.
 */
public interface GameBackend {

    /** The script-API version this backend implements, checked against the launcher's expectation. */
    int API_VERSION = 1;

    /**
     * A capability the UI can grey out when the backend does not provide it.
     *
     * <p>The set is closed so a feature cannot be spelled differently in two places; an action the
     * enum does not name is not a backend capability at all.
     */
    enum Feature {
        /** Inject a key press/release into the game. */
        KEY,
        /** Inject a mouse button press/release. */
        MOUSE_BUTTON,
        /** Inject a scroll wheel notch. */
        SCROLL,
        /** Inject a relative look (camera) delta. */
        LOOK_DELTA,
        /** Inject an absolute pointer position. */
        POINTER,
        /** Read the game's frame rate. */
        FPS,
        /** Drive a field-of-view zoom. */
        ZOOM,
        /** Hold/release the sprint key on the player's behalf. */
        AUTO_SPRINT,
        /** Select a hotbar slot by its number key. */
        HOTBAR_SLOT
    }

    /** A stable name for the backend, shown in About and Diagnostics. */
    String name();

    /** The backend's own version string, shown in About and Diagnostics. */
    String version();

    /** The script-API version this backend implements. */
    int apiVersion();

    /** Whether the backend provides {@code feature}. Authoritative for the UI. */
    boolean supports(Feature feature);

    /** Injects a key press/release. {@code bedrockKeyCode} is a Bedrock (ASCII-style) key code. */
    boolean sendKey(int bedrockKeyCode, boolean down);

    /** Injects a mouse button press/release. {@code androidButton} is a {@code MotionEvent.BUTTON_*}. */
    boolean sendMouseButton(int androidButton, boolean down);

    /** Injects a scroll wheel notch (positive scrolls up). */
    boolean sendScroll(float vertical);

    /** Injects a relative look delta, in device pixels. */
    boolean sendLookDelta(float deltaX, float deltaY);

    /** Injects an absolute pointer position, in device pixels. */
    boolean sendPointer(float x, float y);

    /**
     * The game's current frame rate.
     *
     * @return frames per second, or a negative value when it cannot be read this frame. A caller
     *     must treat a negative value as "unknown" and fall back rather than render it as 0.
     */
    int readFps();

    /** Releases every key and button this backend has injected, so input cannot stay stuck. */
    void releaseAll();

    /**
     * Drives a field-of-view zoom, when the backend can change the FOV.
     *
     * <p>Optional: the default reports that it is not provided, and {@link #supports} with
     * {@link Feature#ZOOM} is what the UI reads.
     */
    default boolean setZoom(boolean active) {
        return false;
    }

    /**
     * Turns auto-sprint on or off.
     *
     * <p>Optional. {@link #supports} with {@link Feature#AUTO_SPRINT} is the authority; this returns
     * false when the backend does not provide it.
     */
    default boolean setAutoSprint(boolean enabled) {
        return false;
    }

    /** Whether auto-sprint is currently active. False when unsupported. */
    default boolean isAutoSprintActive() {
        return false;
    }

    /**
     * Selects a hotbar slot (1..9) by its number key.
     *
     * <p>Optional. {@link #supports} with {@link Feature#HOTBAR_SLOT} is the authority; this returns
     * false when the backend does not provide it or the slot is out of range.
     */
    default boolean selectHotbarSlot(int slot) {
        return false;
    }
}
