package org.chimeramc.client.core.cosmetics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Geometry for a Minecraft player model: the six boxes, the texture region for each face, and
 * the isometric projection that turns a box corner into a screen point.
 *
 * <p>This is the pure half of the character preview. It carries no Android types, so the model
 * layout, the UV table and the visible-face test are all unit-testable — which matters because a
 * wrong UV is invisible until it is rendered against a real skin, and a wrong visible-face test
 * silently drops a side of the head.
 *
 * <p>Units are skin-model pixels (the player is 32 px tall for 1.8 blocks, so one pixel is
 * 0.05625 blocks). The origin sits between the feet, so y grows upward and x/z are centred.
 *
 * <p>Scope: only the base layer plus the head's hat overlay are composited. The remaining
 * second-layer regions are not drawn; a skin that relies entirely on an overlay for its
 * appearance will look like its base layer. That is stated in the cosmetics UI rather than
 * papered over with guessed UVs.
 */
public final class SkinModel {

    /** Atlas size the UV table is written against. 64x32 skins use the same top-left 64x32. */
    public static final int ATLAS_SIZE = 64;

    /** One model pixel, expressed in blocks. The player is 32 px for 1.8 blocks. */
    public static final float PIXELS_TO_BLOCKS = 1.8f / 32f;

    /**
     * Camera distance in model pixels, for {@link Camera}.
     *
     * <p>The model is ~16 px deep and 32 px tall, so a camera 46 px out gives a gentle, believable
     * perspective: a near part is magnified by roughly 1.2x and a far part shrunk by roughly 0.8x.
     * A shorter distance would bow the body, a longer one collapses back to the flat orthographic
     * look this replaced.
     */
    public static final float CAMERA_DISTANCE = 46f;

    /** Floor on the camera-space depth so a point at the camera cannot divide by zero. */
    private static final float MIN_CAMERA_DEPTH = 8f;

    /** A texture rectangle in atlas pixels: (u,v) top-left, (w,h) size. */
    public static final class Uv {
        public final int u, v, w, h;

        public Uv(int u, int v, int w, int h) {
            this.u = u;
            this.v = v;
            this.w = w;
            this.h = h;
        }
    }

    /** The six box faces, named by their outward normal. */
    public enum Face {
        TOP(0, 1, 0),
        BOTTOM(0, -1, 0),
        LEFT(-1, 0, 0),
        RIGHT(1, 0, 0),
        FRONT(0, 0, 1),
        BACK(0, 0, -1);

        public final int nx, ny, nz;

        Face(int nx, int ny, int nz) {
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
        }
    }

    /** One cube of the model, with the base-layer and (optional) overlay UV per face. */
    public static final class Box {
        public final String id;
        /** Centre in model pixels. */
        public final float cx, cy, cz;
        /** Size in model pixels. */
        public final float w, h, d;
        private final Uv[] base = new Uv[Face.values().length];
        private final Uv[] overlay = new Uv[Face.values().length];

        Box(String id, float cx, float cy, float cz, float w, float h, float d) {
            this.id = id;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.w = w;
            this.h = h;
            this.d = d;
        }

        /**
         * Builds a box outside the model table, for accessories that are not part of the player
         * model. Public because the renderer lives in another package.
         */
        public static Box of(String id, float cx, float cy, float cz,
                             float w, float h, float d) {
            return new Box(id, cx, cy, cz, w, h, d);
        }

        Box base(Face f, int u, int v, int w, int h) {
            base[f.ordinal()] = new Uv(u, v, w, h);
            return this;
        }

        Box over(Face f, int u, int v, int w, int h) {
            overlay[f.ordinal()] = new Uv(u, v, w, h);
            return this;
        }

        public Uv baseUv(Face f) {
            return base[f.ordinal()];
        }

        public Uv overlayUv(Face f) {
            return overlay[f.ordinal()];
        }

        public float minX() { return cx - w / 2f; }
        public float maxX() { return cx + w / 2f; }
        public float minY() { return cy - h / 2f; }
        public float maxY() { return cy + h / 2f; }
        public float minZ() { return cz - d / 2f; }
        public float maxZ() { return cz + d / 2f; }

        /**
         * The four corners of a face in <b>grid order</b>: top-left, top-right, bottom-left,
         * bottom-right of the texture rectangle.
         *
         * <p>The order is load-bearing twice over. The textured character is drawn with
         * {@code Canvas.drawBitmapMesh}, which maps a bitmap's corners to {@code verts} in the
         * order {@code [TL, TR, BL, BR]} — it has no source rectangle of its own, so this array is
         * the UV mapping. A perimeter winding (the previous TL, TR, BR, BL order) therefore drew
         * every face rotated 180 degrees: the head's eyes and mouth ended up on its forehead, and
         * the arm/leg hand and boot bands ended up at the shoulder and hip instead of the wrist
         * and ankle. That was the "mismatched textures" defect.
         *
         * <p>The same order also keeps the projected quad simple (non-self-intersecting) for the
         * flat accessory boxes, which are filled with {@code drawPath} rather than textured.
         *
         * <p>Each face maps its texture's <em>top</em> edge to the model's <em>+y</em> edge, so a
         * texture is upright on every side. The horizontal direction is chosen so that adjacent
         * faces stay continuous around the box.
         */
        public float[][] faceCorners(Face f) {
            float x0 = minX(), x1 = maxX(), y0 = minY(), y1 = maxY(), z0 = minZ(), z1 = maxZ();
            switch (f) {
                case TOP:
                    return new float[][]{{x0, y1, z0}, {x1, y1, z0}, {x0, y1, z1}, {x1, y1, z1}};
                case BOTTOM:
                    return new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x0, y0, z0}, {x1, y0, z0}};
                case LEFT:
                    return new float[][]{{x0, y1, z1}, {x0, y1, z0}, {x0, y0, z1}, {x0, y0, z0}};
                case RIGHT:
                    return new float[][]{{x1, y1, z0}, {x1, y1, z1}, {x1, y0, z0}, {x1, y0, z1}};
                case FRONT:
                    return new float[][]{{x0, y1, z1}, {x1, y1, z1}, {x0, y0, z1}, {x1, y0, z1}};
                case BACK:
                    return new float[][]{{x1, y1, z0}, {x0, y1, z0}, {x1, y0, z0}, {x0, y0, z0}};
                default:
                    return new float[0][];
            }
        }
    }

    // Standard 1.8 player layout. Regions are the well-documented ones from the skin format;
    // each is stated per face so a mistake is a single wrong number, not a systematic offset.
    private static final List<Box> BOXES;

    static {
        List<Box> boxes = new ArrayList<>();

        // Head, 8x8x8, sitting on the body.
        boxes.add(new Box("head", 0, 28, 0, 8, 8, 8)
                .base(Face.TOP, 8, 0, 8, 8)
                .base(Face.BOTTOM, 16, 0, 8, 8)
                .base(Face.RIGHT, 0, 8, 8, 8)
                .base(Face.FRONT, 8, 8, 8, 8)
                .base(Face.LEFT, 16, 8, 8, 8)
                .base(Face.BACK, 24, 8, 8, 8)
                // Hat / hair second layer.
                .over(Face.TOP, 40, 0, 8, 8)
                .over(Face.BOTTOM, 48, 0, 8, 8)
                .over(Face.RIGHT, 32, 8, 8, 8)
                .over(Face.FRONT, 40, 8, 8, 8)
                .over(Face.LEFT, 48, 8, 8, 8)
                .over(Face.BACK, 56, 8, 8, 8));

        // Body, 8 wide x 12 tall x 4 deep.
        boxes.add(new Box("body", 0, 18, 0, 8, 12, 4)
                .base(Face.TOP, 20, 16, 8, 4)
                .base(Face.BOTTOM, 28, 16, 8, 4)
                .base(Face.RIGHT, 16, 20, 4, 12)
                .base(Face.FRONT, 20, 20, 8, 12)
                .base(Face.LEFT, 28, 20, 4, 12)
                .base(Face.BACK, 32, 20, 8, 12)
                // Jacket / torso second layer (standard 1.8 region at 16,32). Without it a skin
                // whose jacket, shirt logo or armour trim lives on the overlay renders as its bare
                // base layer, which is the "my skin looks wrong in the preview" case.
                .over(Face.TOP, 20, 32, 8, 4)
                .over(Face.BOTTOM, 28, 32, 8, 4)
                .over(Face.RIGHT, 16, 36, 4, 12)
                .over(Face.FRONT, 20, 36, 8, 12)
                .over(Face.LEFT, 28, 36, 4, 12)
                .over(Face.BACK, 32, 36, 8, 12));

        // Arms, 4x12x4, attached at the shoulders. Right is -x (the model's own right).
        boxes.add(new Box("arm_r", -6, 18, 0, 4, 12, 4)
                .base(Face.TOP, 44, 16, 4, 4)
                .base(Face.BOTTOM, 48, 16, 4, 4)
                .base(Face.RIGHT, 40, 20, 4, 12)
                .base(Face.FRONT, 44, 20, 4, 12)
                .base(Face.LEFT, 48, 20, 4, 12)
                .base(Face.BACK, 52, 20, 4, 12)
                .over(Face.TOP, 44, 32, 4, 4)
                .over(Face.BOTTOM, 48, 32, 4, 4)
                .over(Face.RIGHT, 40, 36, 4, 12)
                .over(Face.FRONT, 44, 36, 4, 12)
                .over(Face.LEFT, 48, 36, 4, 12)
                .over(Face.BACK, 52, 36, 4, 12));

        boxes.add(new Box("arm_l", 6, 18, 0, 4, 12, 4)
                .base(Face.TOP, 36, 48, 4, 4)
                .base(Face.BOTTOM, 40, 48, 4, 4)
                .base(Face.RIGHT, 32, 52, 4, 12)
                .base(Face.FRONT, 36, 52, 4, 12)
                .base(Face.LEFT, 40, 52, 4, 12)
                .base(Face.BACK, 44, 52, 4, 12)
                .over(Face.TOP, 52, 48, 4, 4)
                .over(Face.BOTTOM, 56, 48, 4, 4)
                .over(Face.RIGHT, 48, 52, 4, 12)
                .over(Face.FRONT, 52, 52, 4, 12)
                .over(Face.LEFT, 56, 52, 4, 12)
                .over(Face.BACK, 60, 52, 4, 12));

        // Legs, 4x12x4, from the feet up.
        boxes.add(new Box("leg_r", -2, 6, 0, 4, 12, 4)
                .base(Face.TOP, 4, 16, 4, 4)
                .base(Face.BOTTOM, 8, 16, 4, 4)
                .base(Face.RIGHT, 0, 20, 4, 12)
                .base(Face.FRONT, 4, 20, 4, 12)
                .base(Face.LEFT, 8, 20, 4, 12)
                .base(Face.BACK, 12, 20, 4, 12)
                .over(Face.TOP, 4, 32, 4, 4)
                .over(Face.BOTTOM, 8, 32, 4, 4)
                .over(Face.RIGHT, 0, 36, 4, 12)
                .over(Face.FRONT, 4, 36, 4, 12)
                .over(Face.LEFT, 8, 36, 4, 12)
                .over(Face.BACK, 12, 36, 4, 12));

        boxes.add(new Box("leg_l", 2, 6, 0, 4, 12, 4)
                .base(Face.TOP, 20, 48, 4, 4)
                .base(Face.BOTTOM, 24, 48, 4, 4)
                .base(Face.RIGHT, 16, 52, 4, 12)
                .base(Face.FRONT, 20, 52, 4, 12)
                .base(Face.LEFT, 24, 52, 4, 12)
                .base(Face.BACK, 28, 52, 4, 12)
                .over(Face.TOP, 4, 48, 4, 4)
                .over(Face.BOTTOM, 8, 48, 4, 4)
                .over(Face.RIGHT, 0, 52, 4, 12)
                .over(Face.FRONT, 4, 52, 4, 12)
                .over(Face.LEFT, 8, 52, 4, 12)
                .over(Face.BACK, 12, 52, 4, 12));

        BOXES = Collections.unmodifiableList(boxes);
    }

    private SkinModel() {
    }

    public static List<Box> boxes() {
        return BOXES;
    }

    /** Total model height in pixels, from the feet to the top of the head. */
    public static float heightPixels() {
        return 32f;
    }

    /**
     * Projects a model-space point to screen space with the legacy orthographic isometric view.
     *
     * <p>Kept because the preview's rotation widgets and several tests are written against it.
     * The character render uses {@link Camera} for a true perspective projection; this stays as
     * the documented reference for the yaw/pitch convention.
     *
     * @param out receives {screenX, screenY, depth}; depth grows toward the viewer
     */
    public static void project(float x, float y, float z, float yawDeg, float pitchDeg,
                               float scale, float originX, float originY, float[] out) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cosY = Math.cos(yaw), sinY = Math.sin(yaw);
        double cosP = Math.cos(pitch), sinP = Math.sin(pitch);

        double x1 = x * cosY + z * sinY;
        double z1 = -x * sinY + z * cosY;

        double y2 = y * cosP - z1 * sinP;
        double z2 = y * sinP + z1 * cosP;

        out[0] = originX + (float) (x1 * scale);
        out[1] = originY - (float) (y2 * scale);
        out[2] = (float) z2;
    }

    /**
     * Whether a face is turned toward the viewer, given the same yaw/pitch used to project.
     *
     * <p>The normal is rotated by the same transform and kept only if its depth component is
     * positive, so the test cannot disagree with the projection.
     */
    public static boolean faceVisible(Face face, float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cosY = Math.cos(yaw), sinY = Math.sin(yaw);
        double cosP = Math.cos(pitch), sinP = Math.sin(pitch);

        double nx = face.nx, ny = face.ny, nz = face.nz;
        double nx1 = nx * cosY + nz * sinY;
        double nz1 = -nx * sinY + nz * cosY;
        double nz2 = ny * sinP + nz1 * cosP;
        return nz2 > 0.0001;
    }

    /**
     * A reusable perspective camera for the character preview.
     *
     * <p>{@link #project} and {@link #faceVisible} use the <em>same</em> rotation and the same
     * translation, so the cull test can never disagree with the projection: a face is drawn exactly
     * when its rotated normal points at the camera. That invariant is why the camera is one object
     * rather than two free functions — a second rotation convention is how the preview ends up
     * culling a side it is drawing, or drawing one it culled.
     *
     * <p>The camera sits on the +Z axis at {@code distance}, looking toward −Z. The model is
     * translated to it and rotated by yaw about Y then pitch about X, then divided by camera depth.
     * The result is a real perspective: the head is a little larger and the feet a little smaller,
     * which is what stops the render reading as a flat cardboard cut-out.
     *
     * <p>{@code scale} and the screen origin are passed per call rather than baked in, because the
     * character and the pet share one camera but are drawn at different scales. Allocation-free
     * after construction: {@link #project} writes into a caller array, so the per-frame draw path
     * allocates nothing.
     */
    public static final class Camera {
        public float yawDeg;
        public float pitchDeg;
        public float distance;

        private double cosY, sinY, cosP, sinP;
        private float ox, oy, oz;

        public Camera(float yawDeg, float pitchDeg, float distance,
                      float orbitX, float orbitY, float orbitZ) {
            set(yawDeg, pitchDeg, distance, orbitX, orbitY, orbitZ);
        }

        /**
         * Re-targets this camera in place.
         *
         * <p>A preview that orbits the model rebuilds its camera every frame, and the constructor
         * recomputes six trigonometric values. Reusing the instance keeps the per-frame cost to
         * those six calls with no allocation, which is what lets the render loop stay off the GC.
         * The camera is only ever read on the thread that drives the draw, so the fields are
         * deliberately not volatile.
         */
        public void set(float yawDeg, float pitchDeg, float distance,
                        float orbitX, float orbitY, float orbitZ) {
            this.yawDeg = yawDeg;
            this.pitchDeg = pitchDeg;
            this.distance = distance;
            double yaw = Math.toRadians(yawDeg);
            double pitch = Math.toRadians(pitchDeg);
            this.cosY = Math.cos(yaw);
            this.sinY = Math.sin(yaw);
            this.cosP = Math.cos(pitch);
            this.sinP = Math.sin(pitch);
            this.ox = orbitX;
            this.oy = orbitY;
            this.oz = orbitZ;
        }

        /** Perspective magnification at a model point: 1 at the orbit centre, >1 nearer. */
        public float perspectiveAt(float x, float y, float z) {
            float dx = x - ox;
            float dy = y - oy;
            float dz = z - oz;
            double z1 = -dx * sinY + dz * cosY;
            double z2 = dy * sinP + z1 * cosP;
            float depth = (float) (distance - z2);
            if (depth < MIN_CAMERA_DEPTH) depth = MIN_CAMERA_DEPTH;
            return distance / depth;
        }

        /**
         * Projects a model-space point to {screenX, screenY, cameraDepth}.
         *
         * <p>Camera depth grows toward the viewer, matching the legacy projection, so the painter's
         * sort order is unchanged.
         */
        public void project(float x, float y, float z, float scale, float originX, float originY,
                            float[] out) {
            float dx = x - ox;
            float dy = y - oy;
            float dz = z - oz;

            double x1 = dx * cosY + dz * sinY;
            double z1 = -dx * sinY + dz * cosY;
            double y1 = dy * cosP - z1 * sinP;
            double z2 = dy * sinP + z1 * cosP;

            float depth = (float) (distance - z2);
            if (depth < MIN_CAMERA_DEPTH) depth = MIN_CAMERA_DEPTH;
            float perspective = distance / depth;

            out[0] = originX + (float) (x1 * scale * perspective);
            out[1] = originY - (float) (y1 * scale * perspective);
            out[2] = depth;
        }

        /** Whether a face's rotated normal points at the camera. */
        public boolean faceVisible(Face face) {
            double nx1 = face.nx * cosY + face.nz * sinY;
            double nz1 = -face.nx * sinY + face.nz * cosY;
            double nz2 = face.ny * sinP + nz1 * cosP;
            return nz2 > 0.0001;
        }

        /**
         * Whether an arbitrary (already bone-rotated) normal points at the camera.
         *
         * <p>Authored Blockbench meshes do not use the six axis-aligned {@link Face} normals — a
         * rotated bone turns a cube's face away from its base direction. Sharing this transform with
         * {@link #faceVisible(Face)} keeps the mesh cull and the character cull identical.
         */
        public boolean faceVisibleNormal(float nx, float ny, float nz) {
            double nx1 = nx * cosY + nz * sinY;
            double nz1 = -nx * sinY + nz * cosY;
            double nz2 = ny * sinP + nz1 * cosP;
            return nz2 > 0.0001;
        }
    }

    /** True when the UV lies inside the atlas, so a typo cannot sample out of bounds. */
    public static boolean uvWithinAtlas(Uv uv) {
        if (uv == null) return false;
        return uv.u >= 0 && uv.v >= 0
                && uv.u + uv.w <= ATLAS_SIZE && uv.v + uv.h <= ATLAS_SIZE;
    }
}
