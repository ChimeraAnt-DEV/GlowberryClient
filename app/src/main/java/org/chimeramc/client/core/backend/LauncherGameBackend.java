package org.chimeramc.client.core.backend;

import java.util.EnumSet;
import java.util.Set;

/**
 * The launcher's own {@link GameBackend}, driving input through the game activity's handler.
 *
 * <p>This replaces the prebuilt {@code libinbuiltmods.so} wrappers that used to provide the same
 * capabilities: the launcher could not rebuild those binaries, so the ability to inject input is
 * implemented here against the game's own entry points instead.
 *
 * <p>Capabilities that genuinely need native code the launcher does not have — a field-of-view
 * change for zoom, a game frame-rate counter, and reading an item out of a hotbar slot — are
 * reported as <b>not supported</b> rather than faked, so the UI greys them out and the overlay
 * falls back to its host-side meter instead of appearing to work (see {@link GameBackend#supports}).
 * The rest are provided by injecting the same key/mouse events a physical device would produce.
 */
public final class LauncherGameBackend implements GameBackend {

    public static final String NAME = "Launcher Input";
    public static final String VERSION = "1.0";

    /** The forward key's Bedrock code, used for the sprint double-tap. */
    private static final int BEDROCK_KEY_W = 87;

    private final GameInputHost host;
    /** Capabilities this backend provides; everything else is reported unsupported. */
    private final Set<Feature> supported = EnumSet.of(
            Feature.KEY, Feature.MOUSE_BUTTON, Feature.SCROLL, Feature.LOOK_DELTA, Feature.POINTER,
            Feature.AUTO_SPRINT, Feature.HOTBAR_SLOT);

    private boolean autoSprintActive;

    public LauncherGameBackend(GameInputHost host) {
        if (host == null) {
            throw new IllegalArgumentException("host");
        }
        this.host = host;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public boolean supports(Feature feature) {
        return feature != null && supported.contains(feature);
    }

    @Override
    public boolean sendKey(int bedrockKeyCode, boolean down) {
        return host.injectKey(bedrockKeyCode, down);
    }

    @Override
    public boolean sendMouseButton(int androidButton, boolean down) {
        return host.injectMouseButton(androidButton, down);
    }

    @Override
    public boolean sendScroll(float vertical) {
        return host.injectScroll(vertical);
    }

    @Override
    public boolean sendLookDelta(float deltaX, float deltaY) {
        return host.injectLookDelta(deltaX, deltaY);
    }

    @Override
    public boolean sendPointer(float x, float y) {
        return host.injectPointer(x, y);
    }

    /**
     * The game's frame rate, which this backend cannot read.
     *
     * <p>Reading it needs a hook into the game's own frame counter, which the launcher does not
     * have; the FPS overlay's host-side {@link org.chimeramc.client.launcher.core.mods.inbuilt
     * .overlay.FrameRateMeter} is the reading it shows instead. Returning a fabricated number here
     * would be the same class of over-claim as promising a ping, so this reports "unknown".
     */
    @Override
    public int readFps() {
        return -1;
    }

    /**
     * Turns auto-sprint on or off by double-tapping forward.
     *
     * <p>Bedrock has no dedicated sprint key: a player sprints by double-tapping forward. This
     * reproduces that, holding the second press so the sprint is sustained. It is an approximation
     * — a player who releases forward stops sprinting, exactly as they would without the module.
     */
    @Override
    public boolean setAutoSprint(boolean enabled) {
        if (enabled == autoSprintActive) {
            return true;
        }
        autoSprintActive = enabled;
        if (enabled) {
            host.injectKey(BEDROCK_KEY_W, true);
            host.injectKey(BEDROCK_KEY_W, false);
            host.injectKey(BEDROCK_KEY_W, true);
        } else {
            host.injectKey(BEDROCK_KEY_W, false);
        }
        return true;
    }

    @Override
    public boolean isAutoSprintActive() {
        return autoSprintActive;
    }

    @Override
    public boolean selectHotbarSlot(int slot) {
        if (slot < 1 || slot > 9) {
            return false;
        }
        int bedrockKey = '0' + slot;
        boolean down = host.injectKey(bedrockKey, true);
        host.injectKey(bedrockKey, false);
        return down;
    }

    @Override
    public void releaseAll() {
        autoSprintActive = false;
    }
}
