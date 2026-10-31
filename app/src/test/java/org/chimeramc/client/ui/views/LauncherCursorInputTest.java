package org.chimeramc.client.ui.views;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.chimeramc.client.launcher.ui.views.LauncherCursorInput;
import org.junit.Test;

/**
 * The launcher pointer's pure input rules (Phase 12). No mocks: the class is Android-free apart
 * from the {@link KeyEvent} constants, which are inlined ints.
 */
public class LauncherCursorInputTest {

    @Test
    public void theFaceButtonAndDpadCentreActivateThePointer() {
        assertTrue(LauncherCursorInput.isSelectKey(KeyEvent.KEYCODE_BUTTON_A));
        assertTrue(LauncherCursorInput.isSelectKey(KeyEvent.KEYCODE_BUTTON_1));
        assertTrue(LauncherCursorInput.isSelectKey(KeyEvent.KEYCODE_DPAD_CENTER));
    }

    @Test
    public void enterAndSpaceActivateThePointerForAKeyboard() {
        assertTrue(LauncherCursorInput.isSelectKey(KeyEvent.KEYCODE_ENTER));
        assertTrue(LauncherCursorInput.isSelectKey(KeyEvent.KEYCODE_SPACE));
    }

    @Test
    public void theBackButtonDoesNotActivateThePointer() {
        // B is back; mapping it onto the pointer would make one press mean two things depending
        // on where the pointer happened to sit.
        assertFalse(LauncherCursorInput.isSelectKey(KeyEvent.KEYCODE_BUTTON_B));
        assertFalse(LauncherCursorInput.isSelectKey(KeyEvent.KEYCODE_BACK));
    }

    @Test
    public void anUnrelatedKeyDoesNotActivateThePointer() {
        assertFalse(LauncherCursorInput.isSelectKey(KeyEvent.KEYCODE_A));
        assertFalse(LauncherCursorInput.isSelectKey(KeyEvent.KEYCODE_BUTTON_L1));
        assertFalse(LauncherCursorInput.isSelectKey(KeyEvent.KEYCODE_VOLUME_UP));
    }

    @Test
    public void aDeflectedStickIsAlwaysIntegrated() {
        assertTrue(LauncherCursorInput.shouldIntegrate(0.5f, 0f, false));
        assertTrue(LauncherCursorInput.shouldIntegrate(0f, -0.5f, false));
        assertTrue(LauncherCursorInput.shouldIntegrate(0.02f, 0f, false));
    }

    @Test
    public void anIdleStickIsSkippedWhileThePointerIsHidden() {
        assertFalse(LauncherCursorInput.shouldIntegrate(0f, 0f, false));
        assertFalse(LauncherCursorInput.shouldIntegrate(0.005f, -0.005f, false));
    }

    @Test
    public void anIdleStickIsStillPassedOnWhileThePointerIsVisible() {
        // Otherwise the cursor's timestamp goes stale and the first real movement jumps by the
        // whole idle gap.
        assertTrue(LauncherCursorInput.shouldIntegrate(0f, 0f, true));
    }
}
