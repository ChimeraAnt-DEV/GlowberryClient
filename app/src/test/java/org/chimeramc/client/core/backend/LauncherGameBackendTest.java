package org.chimeramc.client.core.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pins the launcher backend against a recording host: which capabilities it claims, that input
 * reaches the host, and that the capabilities it cannot provide are reported as unsupported rather
 * than silently doing nothing.
 */
public class LauncherGameBackendTest {

    private static final class RecordingHost implements GameInputHost {
        final List<String> calls = new ArrayList<>();

        @Override
        public boolean injectKey(int bedrockKeyCode, boolean down) {
            calls.add("key:" + bedrockKeyCode + ":" + down);
            return true;
        }

        @Override
        public boolean injectMouseButton(int androidButton, boolean down) {
            calls.add("mouse:" + androidButton + ":" + down);
            return true;
        }

        @Override
        public boolean injectScroll(float vertical) {
            calls.add("scroll:" + vertical);
            return true;
        }

        @Override
        public boolean injectLookDelta(float deltaX, float deltaY) {
            calls.add("look:" + deltaX + ":" + deltaY);
            return true;
        }

        @Override
        public boolean injectPointer(float x, float y) {
            calls.add("pointer:" + x + ":" + y);
            return true;
        }
    }

    @Test
    public void inputCapabilitiesAreReportedAsSupported() {
        LauncherGameBackend backend = new LauncherGameBackend(new RecordingHost());
        assertTrue(backend.supports(GameBackend.Feature.KEY));
        assertTrue(backend.supports(GameBackend.Feature.MOUSE_BUTTON));
        assertTrue(backend.supports(GameBackend.Feature.SCROLL));
        assertTrue(backend.supports(GameBackend.Feature.LOOK_DELTA));
        assertTrue(backend.supports(GameBackend.Feature.POINTER));
        assertTrue(backend.supports(GameBackend.Feature.AUTO_SPRINT));
        assertTrue(backend.supports(GameBackend.Feature.HOTBAR_SLOT));
    }

    @Test
    public void capabilitiesNeedingAGameHookAreNotClaimed() {
        LauncherGameBackend backend = new LauncherGameBackend(new RecordingHost());
        assertFalse("zoom needs a FOV change the launcher cannot make",
                backend.supports(GameBackend.Feature.ZOOM));
        assertFalse("a game frame counter needs a hook the launcher does not have",
                backend.supports(GameBackend.Feature.FPS));
        assertEquals(-1, backend.readFps());
    }

    @Test
    public void keyAndLookCallsReachTheHost() {
        RecordingHost host = new RecordingHost();
        LauncherGameBackend backend = new LauncherGameBackend(host);
        assertTrue(backend.sendKey('W', true));
        assertTrue(backend.sendLookDelta(4f, -2f));
        assertEquals(Arrays.asList("key:87:true", "look:4.0:-2.0"), host.calls);
    }

    @Test
    public void aHotbarSlotPressesItsNumberKey() {
        RecordingHost host = new RecordingHost();
        LauncherGameBackend backend = new LauncherGameBackend(host);
        assertTrue(backend.selectHotbarSlot(3));
        assertEquals(Arrays.asList("key:51:true", "key:51:false"), host.calls);
    }

    @Test
    public void anOutOfRangeHotbarSlotIsRefused() {
        RecordingHost host = new RecordingHost();
        LauncherGameBackend backend = new LauncherGameBackend(host);
        assertFalse(backend.selectHotbarSlot(0));
        assertFalse(backend.selectHotbarSlot(10));
        assertTrue(host.calls.isEmpty());
    }

    @Test
    public void autoSprintDoubleTapsForwardAndReleases() {
        RecordingHost host = new RecordingHost();
        LauncherGameBackend backend = new LauncherGameBackend(host);
        assertTrue(backend.setAutoSprint(true));
        assertEquals(Arrays.asList("key:87:true", "key:87:false", "key:87:true"), host.calls);
        assertTrue(backend.isAutoSprintActive());

        host.calls.clear();
        assertTrue(backend.setAutoSprint(false));
        assertEquals(Collections.singletonList("key:87:false"), host.calls);
        assertFalse(backend.isAutoSprintActive());
    }

    @Test
    public void releaseAllClearsTheSprintState() {
        RecordingHost host = new RecordingHost();
        LauncherGameBackend backend = new LauncherGameBackend(host);
        backend.setAutoSprint(true);
        backend.releaseAll();
        assertFalse(backend.isAutoSprintActive());
    }

    @Test
    public void aNullHostIsRefusedAtConstruction() {
        try {
            new LauncherGameBackend(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // The backend cannot function without a host; failing loudly at construction beats a
            // NullPointerException on the first input event.
        }
    }
}
