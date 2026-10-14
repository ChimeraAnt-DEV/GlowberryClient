package org.chimeramc.client.core.mods.inbuilt.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins {@link ModMenuGuard}: it must swallow a throwable, record it with its class/message and a
 * bounded number of frames, keep newest-first, and never grow without bound. This is the containment
 * that stops one bad widget from killing the game process.
 */
public class ModMenuGuardTest {

    @Before
    public void setUp() {
        ModMenuGuard.clear();
    }

    @After
    public void tearDown() {
        ModMenuGuard.clear();
    }

    @Test
    public void aSuccessfulStepReturnsTrueAndRecordsNothing() {
        boolean ok = ModMenuGuard.run("build", () -> { });
        assertTrue(ok);
        assertFalse(ModMenuGuard.hasFailures());
    }

    @Test
    public void aThrowingStepIsContainedAndRecorded() {
        boolean ok = ModMenuGuard.run("buildControllerSection", () -> {
            throw new IllegalStateException("already has a parent");
        });
        assertFalse("a throwing step must report failure", ok);
        assertTrue(ModMenuGuard.hasFailures());
        assertEquals(1, ModMenuGuard.failures().size());
        ModMenuGuard.Failure f = ModMenuGuard.failures().get(0);
        assertEquals("buildControllerSection", f.label);
        assertEquals("java.lang.IllegalStateException", f.throwableClass);
        assertEquals("already has a parent", f.message);
        assertFalse("frames should be captured", f.frames.isEmpty());
        assertTrue("frames should be bounded", f.frames.size() <= ModMenuGuard.MAX_FRAMES);
    }

    @Test
    public void framesAreTruncatedToTheCap() {
        Throwable deep = deepThrowable(40);
        ModMenuGuard.record("deep", deep, 0L);
        assertEquals(ModMenuGuard.MAX_FRAMES, ModMenuGuard.failures().get(0).frames.size());
    }

    @Test
    public void failuresAreNewestFirst() {
        ModMenuGuard.record("first", new RuntimeException("1"), 0L);
        ModMenuGuard.record("second", new RuntimeException("2"), 0L);
        assertEquals("second", ModMenuGuard.failures().get(0).label);
        assertEquals("first", ModMenuGuard.failures().get(1).label);
    }

    @Test
    public void theBufferIsBounded() {
        for (int i = 0; i < ModMenuGuard.MAX_ENTRIES + 10; i++) {
            ModMenuGuard.record("step" + i, new RuntimeException("x"), i);
        }
        assertEquals(ModMenuGuard.MAX_ENTRIES, ModMenuGuard.failures().size());
    }

    @Test
    public void aNullThrowableIsIgnored() {
        ModMenuGuard.record("null", null, 0L);
        assertFalse(ModMenuGuard.hasFailures());
    }

    @Test
    public void theFormatListsFailuresOrSaysNone() {
        assertTrue(ModMenuGuard.format().contains("none"));
        ModMenuGuard.record("build", new IllegalStateException("boom"), 0L);
        String text = ModMenuGuard.format();
        assertTrue(text.contains("build"));
        assertTrue(text.contains("IllegalStateException"));
        assertTrue(text.contains("boom"));
    }

    /** Builds a throwable whose stack trace has {@code depth} frames. */
    private static Throwable deepThrowable(int depth) {
        RuntimeException e = new RuntimeException("deep");
        StackTraceElement[] trace = new StackTraceElement[depth];
        for (int i = 0; i < depth; i++) {
            trace[i] = new StackTraceElement("C", "m" + i, "C.java", i);
        }
        e.setStackTrace(trace);
        return e;
    }
}
