package org.chimeramc.client.core.backend;

/**
 * The host the {@link LauncherGameBackend} injects input through.
 *
 * <p>Implemented by the game activity, which is the only thing that can reach the running game's
 * own input handler. Kept separate from {@link GameBackend} so the backend is testable without an
 * Activity: a test supplies a host that records calls.
 */
public interface GameInputHost {

    /** Injects a key press/release. {@code bedrockKeyCode} is a Bedrock (AWT-style) key code. */
    boolean injectKey(int bedrockKeyCode, boolean down);

    /** Injects a mouse button press/release, keyed by a {@code MotionEvent.BUTTON_*}. */
    boolean injectMouseButton(int androidButton, boolean down);

    /** Injects a scroll wheel notch (positive scrolls up). */
    boolean injectScroll(float vertical);

    /** Injects a relative look delta, in device pixels. */
    boolean injectLookDelta(float deltaX, float deltaY);

    /** Injects an absolute pointer position, in device pixels. */
    boolean injectPointer(float x, float y);
}
