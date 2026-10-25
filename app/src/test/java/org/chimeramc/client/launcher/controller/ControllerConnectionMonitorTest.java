package org.chimeramc.client.launcher.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.InputDevice;

import org.junit.Test;

/**
 * Covers the parts of {@link ControllerConnectionMonitor} that do not need a device: the source
 * filter and the message text. The device-add path itself is instrumented territory.
 */
public class ControllerConnectionMonitorTest {

    @Test
    public void connectedMessageNamesTheControllerAndSaysSettingsLoaded() {
        assertEquals("Xbox Wireless Controller has been connected and detected! Settings loaded.",
                ControllerConnectionMonitor.connectedMessage("Xbox Wireless Controller"));
    }

    @Test
    public void disconnectedMessageFallsBackToTouchControls() {
        assertEquals("DualSense disconnected. Touch controls active.",
                ControllerConnectionMonitor.disconnectedMessage("DualSense"));
    }

    @Test
    public void sourceFilterRejectsKeyboardAndDpadClassBits() {
        // These devices share SOURCE_CLASS_BUTTON with SOURCE_GAMEPAD. A partial/nonzero mask
        // check would mistake both for controllers.
        assertFalse(ControllerConnectionMonitor.isGamepadSource(InputDevice.SOURCE_KEYBOARD));
        assertFalse(ControllerConnectionMonitor.isGamepadSource(InputDevice.SOURCE_DPAD));
        assertFalse(ControllerConnectionMonitor.isGamepadSource(InputDevice.SOURCE_MOUSE));
        assertFalse(ControllerConnectionMonitor.isGamepadSource(InputDevice.SOURCE_TOUCHSCREEN));
    }

    @Test
    public void sourceFilterAcceptsFullGamepadAndJoystickSources() {
        assertTrue(ControllerConnectionMonitor.isGamepadSource(InputDevice.SOURCE_GAMEPAD));
        assertTrue(ControllerConnectionMonitor.isGamepadSource(InputDevice.SOURCE_JOYSTICK));
        assertTrue(ControllerConnectionMonitor.isGamepadSource(
                InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_KEYBOARD));
    }

    @Test
    public void debounceWindowIsShortEnoughToFeelResponsive() {
        assertTrue(ControllerConnectionMonitor.DEBOUNCE_MS > 0);
        assertTrue("a reconnect flap must be swallowed",
                ControllerConnectionMonitor.DEBOUNCE_MS >= 1000L);
        assertFalse("but a genuine replug must not be held back forever",
                ControllerConnectionMonitor.DEBOUNCE_MS > 3000L);
    }
}
