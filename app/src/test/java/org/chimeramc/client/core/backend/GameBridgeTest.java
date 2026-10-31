package org.chimeramc.client.core.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pins the bridge's contract: with no backend every call is a quiet no-op, a capability the backend
 * does not provide is never invoked, a throwing backend is contained, and each failure kind is
 * reported exactly once.
 */
public class GameBridgeTest {

    /** A backend whose every capability is on, recording the calls it receives. */
    private static class RecordingBackend implements GameBackend {
        final List<String> calls = new ArrayList<>();
        boolean failKey;

        @Override
        public String name() {
            return "test";
        }

        @Override
        public String version() {
            return "9.9";
        }

        @Override
        public int apiVersion() {
            return API_VERSION;
        }

        @Override
        public boolean supports(Feature feature) {
            return true;
        }

        @Override
        public boolean sendKey(int bedrockKeyCode, boolean down) {
            calls.add("key:" + bedrockKeyCode + ":" + down);
            if (failKey) {
                throw new IllegalStateException("key boom");
            }
            return true;
        }

        @Override
        public boolean sendMouseButton(int androidButton, boolean down) {
            calls.add("mouse:" + androidButton + ":" + down);
            return true;
        }

        @Override
        public boolean sendScroll(float vertical) {
            calls.add("scroll:" + vertical);
            return true;
        }

        @Override
        public boolean sendLookDelta(float deltaX, float deltaY) {
            calls.add("look:" + deltaX + ":" + deltaY);
            return true;
        }

        @Override
        public boolean sendPointer(float x, float y) {
            calls.add("pointer:" + x + ":" + y);
            return true;
        }

        @Override
        public int readFps() {
            calls.add("fps");
            return 60;
        }

        @Override
        public void releaseAll() {
            calls.add("releaseAll");
        }
    }

    private final List<String> warnings = new ArrayList<>();
    private RecordingBackend backend;

    @Before
    public void setUp() {
        GameBridge.resetForTests();
        warnings.clear();
        GameBridge.setLogger((message, cause) -> warnings.add(message));
        backend = new RecordingBackend();
    }

    @After
    public void tearDown() {
        GameBridge.resetForTests();
    }

    @Test
    public void statusLineNamesTheBackendOrItsAbsence() {
        // Item 492: About and Diagnostics show this line, so a build with no backend says so
        // plainly rather than leaving the user to infer it from a feature that does nothing.
        assertTrue(GameBridge.statusLine(), GameBridge.statusLine().contains("none"));
        GameBridge.register(backend);
        String line = GameBridge.statusLine();
        assertTrue(line, line.contains("test"));
        assertTrue(line, line.contains("9.9"));
        assertTrue(line, line.contains("API v" + GameBackend.API_VERSION));
    }

    @Test
    public void withNoBackendEveryCallIsAQuietNoOp() {
        assertFalse(GameBridge.hasBackend());
        assertFalse(GameBridge.sendKey('W', true));
        assertFalse(GameBridge.sendLookDelta(1f, 1f));
        assertFalse(GameBridge.setZoom(true));
        assertFalse(GameBridge.setAutoSprint(true));
        assertFalse(GameBridge.selectHotbarSlot(1));
        assertEquals(-1, GameBridge.readFps());
        assertEquals("", GameBridge.backendName());
        assertEquals(0, GameBridge.backendApiVersion());
        GameBridge.releaseAll();
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void aRegisteredBackendReceivesTheCalls() {
        GameBridge.register(backend);
        assertTrue(GameBridge.sendKey('W', true));
        assertTrue(GameBridge.sendLookDelta(2f, 3f));
        assertEquals("test", GameBridge.backendName());
        assertEquals("9.9", GameBridge.backendVersion());
        assertEquals(GameBackend.API_VERSION, GameBridge.backendApiVersion());
        assertEquals(Arrays.asList("key:87:true", "look:2.0:3.0"), backend.calls);
    }

    @Test
    public void anUnsupportedCapabilityIsNeverInvoked() {
        RecordingBackend limited = new RecordingBackend() {
            @Override
            public boolean supports(Feature feature) {
                return feature == Feature.LOOK_DELTA;
            }
        };
        GameBridge.register(limited);
        assertFalse(GameBridge.sendKey('W', true));
        assertTrue(GameBridge.sendLookDelta(1f, 1f));
        assertEquals(Collections.singletonList("look:1.0:1.0"), limited.calls);
    }

    @Test
    public void aThrowingBackendIsContainedAndReportedOnce() {
        backend.failKey = true;
        GameBridge.register(backend);
        assertFalse(GameBridge.sendKey('W', true));
        assertFalse(GameBridge.sendKey('W', true));
        assertFalse(GameBridge.sendKey('A', true));
        assertEquals("a failure is reported once per kind, not per call", 1, warnings.size());
    }

    @Test
    public void swappingTheBackendReleasesThePreviousOne() {
        GameBridge.register(backend);
        RecordingBackend next = new RecordingBackend();
        GameBridge.register(next);
        assertTrue("the previous backend is released on swap",
                backend.calls.contains("releaseAll"));
        assertFalse("the new backend is not released on install",
                next.calls.contains("releaseAll"));
    }

    @Test
    public void unregisterReleasesAndClearsTheBackend() {
        GameBridge.register(backend);
        GameBridge.unregister();
        assertFalse(GameBridge.hasBackend());
        assertTrue(backend.calls.contains("releaseAll"));
        assertFalse(GameBridge.sendKey('W', true));
    }

    @Test
    public void readFpsReportsUnknownWhenTheBackendCannotReadIt() {
        RecordingBackend noFps = new RecordingBackend() {
            @Override
            public boolean supports(Feature feature) {
                return feature != Feature.FPS;
            }
        };
        GameBridge.register(noFps);
        assertEquals(-1, GameBridge.readFps());
    }
}
