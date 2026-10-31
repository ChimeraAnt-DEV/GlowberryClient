package org.chimeramc.client.core.replay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the free camera and the cinematic camera path: the movement basis, the pitch clamp and the
 * keyframe sampling, plus the sidecar round-trip so an authored shot survives a save.
 */
public class ReplayCameraTest {

    @Test
    public void forwardMovementFollowsTheYaw() {
        ReplayCamera camera = new ReplayCamera();
        camera.set(0f, 0f, 0f, 0f, 0f);
        camera.setSpeed(10f);
        camera.move(1f, 0f, 0f, 1_000L);
        // Yaw 0 is +Z, so a full second at 10 blocks/s lands on (0,0,10).
        assertEquals(0f, camera.x(), 0.001f);
        assertEquals(10f, camera.z(), 0.001f);
    }

    @Test
    public void strafeIsPerpendicularToTheLook() {
        ReplayCamera camera = new ReplayCamera();
        camera.set(0f, 0f, 0f, 0f, 0f);
        camera.setSpeed(10f);
        camera.move(0f, 1f, 0f, 1_000L);
        // Yaw 0 faces +Z, so right is +X; a mirrored sign would put it on -X.
        assertEquals(10f, camera.x(), 0.001f);
        assertEquals(0f, camera.z(), 0.001f);
    }

    @Test
    public void verticalInputMovesStraightUp() {
        ReplayCamera camera = new ReplayCamera();
        camera.set(0f, 5f, 0f, 90f, 0f);
        camera.setSpeed(4f);
        camera.move(0f, 0f, 1f, 1_000L);
        assertEquals(9f, camera.y(), 0.001f);
    }

    @Test
    public void pitchIsClampedClearOfThePoles() {
        ReplayCamera camera = new ReplayCamera();
        camera.set(0f, 0f, 0f, 0f, 0f);
        camera.look(0f, 400f);
        assertEquals(ReplayCamera.MAX_PITCH_DEG, camera.pitchDeg(), 0.001f);
        camera.look(0f, -800f);
        assertEquals(-ReplayCamera.MAX_PITCH_DEG, camera.pitchDeg(), 0.001f);
    }

    @Test
    public void yawWrapsIntoARange() {
        ReplayCamera camera = new ReplayCamera();
        camera.set(0f, 0f, 0f, 350f, 0f);
        camera.look(30f, 0f);
        assertEquals(20f, camera.yawDeg(), 0.001f);
    }

    @Test
    public void anEmptyPathLeavesTheCameraAlone() {
        ReplayCamera camera = new ReplayCamera();
        camera.set(1f, 2f, 3f, 10f, 10f);
        ReplayCameraPath path = new ReplayCameraPath();
        assertFalse(path.sample(0L, camera));
        assertEquals(1f, camera.x(), 0.001f);
    }

    @Test
    public void anOrbitPresetCirclesTheSubjectAndFacesIt() {
        ReplayCamera camera = new ReplayCamera();
        camera.set(0f, 10f, 0f, 0f, 0f);
        ReplayCameraPath path = new ReplayCameraPath();
        path.generate(ReplayCameraPath.Preset.ORBIT, camera, 10_000L);
        assertTrue(path.size() > 4);
        ReplayCamera out = new ReplayCamera();
        assertTrue(path.sample(0L, out));
        float startRadius = (float) Math.hypot(out.x(), out.z());
        assertTrue("orbit should hold a radius", startRadius > 20f);
        assertTrue(path.sample(5_000L, out));
        float midRadius = (float) Math.hypot(out.x(), out.z());
        assertEquals("the orbit radius must not drift", startRadius, midRadius, 3f);
    }

    @Test
    public void aDollyPresetMovesTheCameraCloser() {
        ReplayCamera camera = new ReplayCamera();
        camera.set(0f, 10f, 0f, 0f, 0f);
        ReplayCameraPath path = new ReplayCameraPath();
        path.generate(ReplayCameraPath.Preset.DOLLY_ZOOM, camera, 8_000L);
        ReplayCamera out = new ReplayCamera();
        path.sample(0L, out);
        float startDist = (float) Math.hypot(out.x(), out.z());
        path.sample(8_000L, out);
        float endDist = (float) Math.hypot(out.x(), out.z());
        assertTrue("a dolly zoom must push in", endDist < startDist);
    }

    @Test
    public void samplingIsSmoothBetweenKeyframes() {
        // Catmull-Rom through a straight line stays on the line and moves monotonically.
        ReplayCameraPath path = new ReplayCameraPath();
        path.addKeyframe(new ReplayCameraPath.Keyframe(0L, 0f, 0f, 0f, 0f, 0f));
        path.addKeyframe(new ReplayCameraPath.Keyframe(1_000L, 0f, 0f, 10f, 0f, 0f));
        path.addKeyframe(new ReplayCameraPath.Keyframe(2_000L, 0f, 0f, 20f, 0f, 0f));
        ReplayCamera out = new ReplayCamera();
        path.sample(500L, out);
        float first = out.z();
        path.sample(1_000L, out);
        float middle = out.z();
        path.sample(1_500L, out);
        float last = out.z();
        assertTrue(first < middle && middle < last);
        assertEquals(0f, out.x(), 0.001f);
    }

    @Test
    public void thePathRoundTripsThroughTheSidecar() {
        ReplayCamera camera = new ReplayCamera();
        camera.set(4f, 20f, -3f, 45f, 10f);
        ReplayCameraPath path = new ReplayCameraPath();
        path.generate(ReplayCameraPath.Preset.DRONE, camera, 6_000L);

        ReplaySidecar sidecar = ReplaySidecar.of(1L, "w", "v", "survival");
        sidecar.setCameraPath(path);
        ReplaySidecar parsed = ReplaySidecar.parse(sidecar.serialize());

        ReplayCameraPath restored = parsed.cameraPath();
        assertEquals(path.preset(), restored.preset());
        assertEquals(path.size(), restored.size());
        ReplayCamera a = new ReplayCamera();
        ReplayCamera b = new ReplayCamera();
        assertTrue(path.sample(3_000L, a));
        assertTrue(restored.sample(3_000L, b));
        assertEquals(a.x(), b.x(), 0.01f);
        assertEquals(a.y(), b.y(), 0.01f);
        assertEquals(a.z(), b.z(), 0.01f);
    }

    @Test
    public void aCorruptPathDegradesToEmptyNotAThrow() {
        ReplayCameraPath path = ReplayCameraPathCodec.parse("ORBIT;nonsense;1,2,3");
        assertTrue(path.isEmpty());
        assertEquals(ReplayCameraPath.Preset.ORBIT, path.preset());
        assertTrue(ReplayCameraPathCodec.parse("").isEmpty());
        assertTrue(ReplayCameraPathCodec.parse(null).isEmpty());
    }

    @Test
    public void dozensOfShotPresetsShip() {
        // The spec asks for "dozens" of presets. Pinned so the set cannot quietly shrink back to a
        // handful.
        assertTrue("expected at least 20 presets, found " + ReplayCameraPath.PRESET_COUNT,
                ReplayCameraPath.PRESET_COUNT >= 20);
    }

    @Test
    public void everyPresetGeneratesAUsablePath() {
        // A preset that produces no keyframes would render nothing; each one must fill the path with
        // at least two poses spanning the clip.
        ReplayCamera camera = new ReplayCamera();
        camera.set(3f, 12f, -5f, 40f, 0f);
        for (ReplayCameraPath.Preset preset : ReplayCameraPath.Preset.values()) {
            ReplayCameraPath path = new ReplayCameraPath();
            path.generate(preset, camera, 8_000L);
            assertEquals("preset " + preset + " must produce a path", preset, path.preset());
            assertTrue("preset " + preset + " needs at least two keyframes", path.size() >= 2);
            ReplayCamera out = new ReplayCamera();
            assertTrue("preset " + preset + " must sample", path.sample(4_000L, out));
            assertTrue("preset " + preset + " must keep pitch in range",
                    Math.abs(out.pitchDeg()) <= ReplayCamera.MAX_PITCH_DEG + 0.001f);
        }
    }

    @Test
    public void everyPresetHasADisplayName() {
        // A preset with no label would render an empty row in the cinematic dialog.
        for (ReplayCameraPath.Preset preset : ReplayCameraPath.Preset.values()) {
            assertTrue("preset " + preset + " has no label",
                    ReplayCinematicDialog.presetLabelRes(preset) != 0);
        }
    }

    @Test
    public void aRevealShotDescendsTowardTheSubject() {
        ReplayCamera camera = new ReplayCamera();
        camera.set(0f, 10f, 0f, 0f, 0f);
        ReplayCameraPath path = new ReplayCameraPath();
        path.generate(ReplayCameraPath.Preset.REVEAL, camera, 6_000L);
        ReplayCamera out = new ReplayCamera();
        path.sample(0L, out);
        float startDist = (float) Math.hypot(out.x(), out.z());
        path.sample(6_000L, out);
        float endDist = (float) Math.hypot(out.x(), out.z());
        assertTrue("a reveal must close the distance", endDist < startDist);
    }

    @Test
    public void aPullOutShotOpensTheDistance() {
        ReplayCamera camera = new ReplayCamera();
        camera.set(0f, 10f, 0f, 0f, 0f);
        ReplayCameraPath path = new ReplayCameraPath();
        path.generate(ReplayCameraPath.Preset.PULL_OUT, camera, 6_000L);
        ReplayCamera out = new ReplayCamera();
        path.sample(0L, out);
        float startDist = (float) Math.hypot(out.x(), out.z());
        path.sample(6_000L, out);
        float endDist = (float) Math.hypot(out.x(), out.z());
        assertTrue("a pull out must open the distance", endDist > startDist);
    }

    @Test
    public void everyPresetRoundTripsThroughTheSidecar() {
        // A preset name that does not survive the codec would restore as STATIC and silently lose
        // the shot's identity, so every name must parse back.
        for (ReplayCameraPath.Preset preset : ReplayCameraPath.Preset.values()) {
            ReplayCameraPath path = new ReplayCameraPath();
            ReplayCamera camera = new ReplayCamera();
            camera.set(1f, 2f, 3f, 0f, 0f);
            path.generate(preset, camera, 4_000L);
            ReplayCameraPath restored = ReplayCameraPathCodec.parse(
                    ReplayCameraPathCodec.serialize(path));
            assertEquals("preset " + preset + " did not round-trip", preset, restored.preset());
        }
    }
}
