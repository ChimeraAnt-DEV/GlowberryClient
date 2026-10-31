package org.chimeramc.client.launcher.controller;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import org.chimeramc.client.util.MainThread;

/**
 * Watches for controllers being connected and disconnected anywhere in the app.
 *
 * <p>Device add/remove listening previously lived only inside {@code ControllerSettingsFragment},
 * so nothing reacted unless the Controller screen happened to be open. One listener registered
 * here instead covers every tab, and the same instance can be reached from the in-game overlay.
 *
 * <p>Holds no Activity, so it is safe to keep for the life of the process. A callback is
 * delivered to whatever {@link Listener} is currently attached.
 */
public final class ControllerConnectionMonitor {

    /** How long a reconnect for the same device id is ignored, to swallow flapping. */
    static final long DEBOUNCE_MS = 1500L;

    /** What the app wants to do when a pad arrives or leaves. */
    public interface Listener {
        /** Always called on the main thread. */
        void onControllerConnected(String name, ControllerType type, String profileName);

        /** Always called on the main thread. */
        void onControllerDisconnected(String name);
    }

    /**
     * What the app wants to do when a keyboard arrives or leaves (Phase 12).
     *
     * <p>A separate interface rather than two more methods on {@link Listener}: a keyboard has no
     * profile, no vendor mapping and no dead zone, so a screen that only cares about pads should
     * not be forced to write two empty methods to satisfy the type.
     */
    public interface KeyboardListener {
        /** Always called on the main thread. */
        void onKeyboardConnected(String name);

        /** Always called on the main thread. */
        void onKeyboardDisconnected(String name);
    }

    private final Context appContext;
    private final InputManager inputManager;
    private final Handler mainHandler = MainThread.handler();

    private Listener listener;
    private KeyboardListener keyboardListener;
    private boolean registered;

    private static volatile ControllerConnectionMonitor shared;

    /**
     * Returns the process-wide monitor, creating it on first use.
     *
     * One monitor serves every screen and the game; each visible Activity attaches its own
     * listener so only the front screen shows the pill, and the device listener itself is
     * registered exactly once for the life of the process.
     */
    public static ControllerConnectionMonitor get(Context context) {
        ControllerConnectionMonitor local = shared;
        if (local == null) {
            synchronized (ControllerConnectionMonitor.class) {
                local = shared;
                if (local == null) {
                    local = new ControllerConnectionMonitor(context.getApplicationContext());
                    shared = local;
                }
            }
        }
        return local;
    }

    /** Last known connection state per device id, used for debouncing. */
    private final java.util.Map<Integer, Long> lastEventAt = new java.util.HashMap<>();

    private final InputManager.InputDeviceListener deviceListener = new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {
            handleAdded(deviceId);
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            handleRemoved(deviceId);
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {
        }
    };

    public ControllerConnectionMonitor(Context context) {
        this.appContext = context.getApplicationContext();
        this.inputManager = (InputManager) appContext.getSystemService(Context.INPUT_SERVICE);
    }

    /**
     * Attaches the callback that shows the pill.
     *
     * <p>Attaching also re-announces pads that were already plugged in. Without it a controller
     * connected before the app launched — or while no screen was listening — was never announced
     * at all, because the platform only delivers an add callback at the moment of the add.
     */
    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            mainHandler.post(this::announceExisting);
        }
    }

    /**
     * Detaches a listener, but only if it is still the current one.
     *
     * <p>Navigation here is activity-based: switching tabs starts the next Activity and stops the
     * previous one, and the platform runs the new {@code onStart} <em>before</em> the old
     * {@code onStop}. A plain "clear" from the outgoing screen therefore wiped the listener the
     * incoming screen had just attached, leaving nobody to draw the pill — the "no controller
     * notification in the launcher" report. Comparing identity means a stopping Activity can only
     * remove its own listener.
     */
    public void clearListener(Listener listener) {
        if (this.listener == listener) {
            this.listener = null;
        }
    }

    /** Attaches the keyboard callback (Phase 12). Re-announces a keyboard already attached. */
    public void setKeyboardListener(KeyboardListener listener) {
        this.keyboardListener = listener;
        if (listener != null) {
            mainHandler.post(this::announceExistingKeyboards);
        }
    }

    /** Detaches a keyboard callback, but only if it is still the current one. */
    public void clearKeyboardListener(KeyboardListener listener) {
        if (this.keyboardListener == listener) {
            this.keyboardListener = null;
        }
    }

    /** Registers the listener once; repeated calls are ignored. */
    public void start() {
        if (registered || inputManager == null) return;
        try {
            inputManager.registerInputDeviceListener(deviceListener, mainHandler);
            registered = true;
        } catch (Exception ignored) {
        }
    }

    public void stop() {
        if (!registered || inputManager == null) return;
        try {
            inputManager.unregisterInputDeviceListener(deviceListener);
        } catch (Exception ignored) {
        }
        registered = false;
    }

    /**
     * Handles a newly-added input device.
     *
     * <p>Only gamepads count. A keyboard, the touchscreen and anything else report sources that
     * fail the {@link #isGamepad} test, and a pad we cannot classify is still announced as a
     * generic controller rather than dropped, because "connected and detected" is the useful
     * half of the message even without a vendor match.
     */
    private void handleAdded(int deviceId) {
        if (isDebounced(deviceId)) return;
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null) return;
        if (isGamepad(device)) {
            announce(device);
            return;
        }
        if (isKeyboard(device)) {
            announceKeyboard(device);
        }
    }

    private void handleRemoved(int deviceId) {
        if (isDebounced(deviceId)) return;
        // Forget it, so unplugging and plugging the same pad announces the reconnect.
        announcedIds.remove(deviceId);
        // The device is already gone, so its name cannot be read now; the last known id is all
        // we have. A generic label is used when nothing was cached for it.
        String name = lastKnownNames.remove(deviceId);
        boolean wasKeyboard = keyboardIds.remove(deviceId);
        if (name == null) name = wasKeyboard ? "Keyboard" : "Controller";
        final String reported = name;
        if (wasKeyboard) {
            mainHandler.post(() -> {
                if (keyboardListener != null) keyboardListener.onKeyboardDisconnected(reported);
            });
            return;
        }
        mainHandler.post(() -> {
            if (listener != null) listener.onControllerDisconnected(reported);
        });
    }

    /** Names seen at connect time, so a disconnect can still name the pad that left. */
    private final java.util.Map<Integer, String> lastKnownNames = new java.util.HashMap<>();

    /** Device ids already announced, so a listener attach does not repeat the pill. */
    private final java.util.Set<Integer> announcedIds = new java.util.HashSet<>();

    /**
     * Announces every gamepad that is already attached.
     *
     * <p>Runs when a listener attaches rather than at registration, so the pill appears on the
     * first screen that can draw it. The debounce is deliberately bypassed here: this is a
     * first-sight announcement, not a device event, and routing it through {@code isDebounced}
     * would let a reconnect race swallow it.
     */
    private void announceExisting() {
        if (listener == null || inputManager == null) return;
        int[] ids;
        try {
            ids = inputManager.getInputDeviceIds();
        } catch (Exception e) {
            return;
        }
        if (ids == null) return;
        for (int id : ids) {
            // Only first sight. Re-announcing on every listener attach would show the pill each
            // time the user switched tabs, which is the spam the debounce exists to prevent.
            if (announcedIds.contains(id)) continue;
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || !isGamepad(device)) continue;
            announce(device);
        }
    }

    /** Device ids announced as keyboards, so a disconnect names the right thing. */
    private final java.util.Set<Integer> keyboardIds = new java.util.HashSet<>();

    /**
     * Announces a keyboard that was already attached when a listener arrived (Phase 12).
     *
     * <p>Same reasoning as {@link #announceExisting}: the platform only delivers an add callback at
     * the moment of the add, so a keyboard plugged in before the app started would otherwise never
     * be announced. First sight only, so switching tabs does not re-show the pill.
     */
    private void announceExistingKeyboards() {
        if (keyboardListener == null || inputManager == null) return;
        int[] ids;
        try {
            ids = inputManager.getInputDeviceIds();
        } catch (Exception e) {
            return;
        }
        if (ids == null) return;
        for (int id : ids) {
            if (announcedIds.contains(id) || keyboardIds.contains(id)) continue;
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || !isKeyboard(device)) continue;
            announceKeyboard(device);
        }
    }

    /** Delivers the keyboard-connected callback. */
    private void announceKeyboard(InputDevice device) {
        keyboardIds.add(device.getId());
        announcedIds.add(device.getId());
        String name = device.getName();
        if (name == null || name.trim().isEmpty()) name = "Keyboard";
        lastKnownNames.put(device.getId(), name);
        final String reported = name;
        mainHandler.post(() -> {
            if (keyboardListener != null) keyboardListener.onKeyboardConnected(reported);
        });
    }

    /** True for a device that is a keyboard and not a pad (a pad reports both sources). */
    public static boolean isKeyboard(InputDevice device) {
        return device != null && isKeyboardSource(device.getSources());
    }

    /**
     * Checks a source bitfield for a keyboard that is not also a gamepad.
     *
     * <p>A combined {@code SOURCE_GAMEPAD | SOURCE_KEYBOARD} device is a pad with keys (a gamepad
     * with a chat pad, or a controller's media keys) and belongs on the pad path, which already
     * announced it — so it must not also fire the keyboard toast.
     */
    public static boolean isKeyboardSource(int sources) {
        return (sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
                && !isGamepadSource(sources);
    }

    /** Classifies a pad, loads its profile and delivers the connected callback. */
    private void announce(InputDevice device) {
        announcedIds.add(device.getId());
        ControllerType type = ControllerType.from(device);
        ControllerType effective = type != null ? type : ControllerType.XBOX;
        String name = displayNameOf(device, effective);

        String profileName = loadProfile(effective);

        final ControllerType reported = effective;
        final String reportedProfile = profileName;
        mainHandler.post(() -> {
            if (listener != null) listener.onControllerConnected(name, reported, reportedProfile);
        });
    }

    /**
     * Applies the saved profile for this pad type and returns its name, or null.
     *
     * <p>Loading it here rather than waiting for a screen means the pad's dead zones and remaps
     * are active the moment it is seen, wherever the user is.
     */
    private String loadProfile(ControllerType effective) {
        try {
            ControllerProfileManager manager = new ControllerProfileManager(appContext);
            ControllerProfile profile = manager.getActiveProfile(effective);
            if (profile != null) {
                ControllerInputProcessor.setActiveProfile(effective, profile);
                return profile.getName();
            }
        } catch (Exception ignored) {
            // A profile failure must not stop the notification from being shown.
        }
        return null;
    }

    private boolean isDebounced(int deviceId) {
        long now = System.currentTimeMillis();
        Long last = lastEventAt.get(deviceId);
        lastEventAt.put(deviceId, now);
        return last != null && (now - last) < DEBOUNCE_MS;
    }

    /** True for a source that is a gamepad or joystick rather than a keyboard or touchscreen. */
    public static boolean isGamepad(InputDevice device) {
        return device != null && isGamepadSource(device.getSources());
    }

    /**
     * Checks an Android input-source bitfield for a complete gamepad or joystick source.
     *
     * <p>Use equality against the full source constant, not a nonzero bitwise intersection:
     * {@link InputDevice#SOURCE_GAMEPAD} and {@link InputDevice#SOURCE_KEYBOARD} both include
     * {@code SOURCE_CLASS_BUTTON}, so testing {@code (sources & SOURCE_GAMEPAD) != 0} also
     * classifies an ordinary keyboard as a controller.
     */
    public static boolean isGamepadSource(int sources) {
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private String displayNameOf(InputDevice device, ControllerType type) {
        String name = device.getName();
        if (name == null || name.trim().isEmpty()) {
            name = type != null ? type.getDisplayName() : "Controller";
        }
        lastKnownNames.put(device.getId(), name);
        return name;
    }

    /** Formats the connected message; kept static so it is unit-testable without a device. */
    public static String connectedMessage(String name) {
        return name + " has been connected and detected! Settings loaded.";
    }

    /** Formats the disconnected message. */
    public static String disconnectedMessage(String name) {
        return name + " disconnected. Touch controls active.";
    }
}
