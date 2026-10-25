package org.chimeramc.client.core.cosmetics;

import java.util.ArrayList;
import java.util.List;

/**
 * The cosmetics preview scene: textured quads in model space, a camera-fitted view, and a render
 * pass through {@link PreviewRaster}.
 *
 * <p>This replaces the preview's {@code Canvas} scene. Because the rasteriser holds a per-pixel
 * depth buffer, the scene does not need a painter's sort and can draw the body, the overlay layer,
 * cosmetics and the pet in one pass with correct occlusion — the triangular holes the painter's sort
 * produced at box seams are gone by construction.
 *
 * <p>The camera fit is the second fix: the caller gives the whole scene's model bounds, and the fit
 * chooses a scale and origin that place <em>every</em> quad inside the viewport with a margin, so a
 * pet at the character's feet or a tall hat can never be clipped (the old preview clipped the legs
 * and the ground-level pet).
 *
 * <p>Pure and Android-free: a {@link Face} refers to a texture by {@code int[]} and the render pass
 * writes into {@link PreviewRaster}'s {@code int[]}, so the whole pipeline is unit-testable and can
 * be snapped to a golden PNG on the JVM.
 */
public final class PreviewScene {

    /** A single textured, two-triangle quad in model space. */
    public static final class Face {
        /** Four corners in model pixels: {@code [TL, TR, BL, BR]}, each {x,y,z}. */
        public final float[][] corners = new float[4][3];
        /** Four UVs in the same corner order, normalised 0..1: {@code [u,v]} per corner. */
        public final float[] uv = new float[8];
        /** Outward model-space normal, for back-face culling. */
        public float nx, ny, nz;
        public int[] atlas;
        public int atlasW, atlasH;
        /**
         * A flat, untextured colour (ARGB), or 0 for a textured face. Used by the procedural pet,
         * whose parts are flat-shaded boxes; the shading is baked into the colour at build time.
         */
        public int flatColor;
        /** Alpha-test this face (overlay/cosmetic) rather than force it opaque (base skin). */
        public boolean alphaTest = true;
        /** Alpha-blend a translucent texel instead of writing it opaque. */
        public boolean blend = true;
        /** Draw both sides (cosmetics) rather than culling a back face. */
        public boolean doubleSided;
        /** 0..1 brightness from the face normal. */
        public float shade = 1f;
        /**
         * A bias subtracted from the projected depth (smaller = nearer), so a face that is coplanar
         * with another — a skin's overlay layer over its base face — reliably wins the depth test
         * instead of z-fighting at an equal depth.
         */
        public float depthBias = 0f;
        /**
         * An optional label for diagnostics — the cosmetic id or "skin"/"cape" — so a skipped face
         * can be attributed to the piece that produced it.
         */
        public String tag;

        void set(float x0, float y0, float z0, float x1, float y1, float z1,
                 float x2, float y2, float z2, float x3, float y3, float z3) {
            corners[0][0] = x0; corners[0][1] = y0; corners[0][2] = z0;
            corners[1][0] = x1; corners[1][1] = y1; corners[1][2] = z1;
            corners[2][0] = x2; corners[2][1] = y2; corners[2][2] = z2;
            corners[3][0] = x3; corners[3][1] = y3; corners[3][2] = z3;
        }
    }

    /** The chosen scale and origin that fit the scene into a viewport. */
    public static final class Fit {
        public float scale;
        public float originX;
        public float originY;
    }

    private final List<Face> faces = new ArrayList<>();

    public List<Face> faces() {
        return faces;
    }

    public void clear() {
        faces.clear();
    }

    /** The model-space bounding box {@code {minX,minY,minZ,maxX,maxY,maxZ}} of every quad. */
    public float[] bounds() {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Face f : faces) {
            for (float[] c : f.corners) {
                minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0]);
                minY = Math.min(minY, c[1]); maxY = Math.max(maxY, c[1]);
                minZ = Math.min(minZ, c[2]); maxZ = Math.max(maxZ, c[2]);
            }
        }
        if (faces.isEmpty()) return new float[]{0, 0, 0, 0, 0, 0};
        return new float[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /**
     * Fits the whole scene into a viewport with a margin, so nothing (legs, a ground pet, a tall hat)
     * is clipped.
     *
     * <p>Screen coordinates are linear in the scale (the perspective divide does not depend on it), so
     * the fit projects every corner once at scale 1, measures the screen bounds, and derives the scale
     * and the centring origin from them.
     */
    public Fit fit(SkinModel.Camera camera, int viewportW, int viewportH, float margin) {
        SkinModel.Camera cam = camera != null
                ? camera : new SkinModel.Camera(0f, 12f, SkinModel.CAMERA_DISTANCE,
                0f, SkinModel.heightPixels() / 2f, 0f);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float[] out = new float[3];
        for (Face f : faces) {
            for (float[] c : f.corners) {
                cam.project(c[0], c[1], c[2], 1f, 0f, 0f, out);
                minX = Math.min(minX, out[0]); maxX = Math.max(maxX, out[0]);
                minY = Math.min(minY, out[1]); maxY = Math.max(maxY, out[1]);
            }
        }
        Fit fit = new Fit();
        float spanX = Math.max(1e-3f, maxX - minX);
        float spanY = Math.max(1e-3f, maxY - minY);
        float usable = 1f - 2f * Math.max(0f, margin);
        float scaleX = (viewportW * usable) / spanX;
        float scaleY = (viewportH * usable) / spanY;
        fit.scale = Math.min(scaleX, scaleY);
        // Centre the unscaled bounding box in the viewport.
        fit.originX = viewportW / 2f - fit.scale * (minX + maxX) / 2f;
        fit.originY = viewportH / 2f - fit.scale * (minY + maxY) / 2f;
        return fit;
    }

    /**
     * Renders every face into {@code raster} with the given camera and fit. A face whose normal
     * points away is culled unless it is double-sided; the depth buffer resolves all ordering, so
     * faces may be drawn in any order.
     */
    public void render(PreviewRaster raster, SkinModel.Camera camera, Fit fit) {
        if (raster == null || camera == null || fit == null) return;
        float[] p = new float[12];
        for (Face f : faces) {
            if (f.atlas == null && f.flatColor == 0) continue;
            // Defence in depth: never hand the rasteriser an atlas smaller than the dimensions the
            // face was built with, or it would index past the end of the pixel array. A face whose
            // atlas is the wrong size is skipped (and recorded for the diagnostics report) rather
            // than crashing the frame.
            if (f.atlas != null && f.atlas.length < f.atlasW * f.atlasH) {
                PreviewSceneDiagnostics.record(f.tag, "atlas smaller than declared size",
                        f.atlasW, f.atlasH, f.atlas.length);
                continue;
            }
            if (!f.doubleSided && !camera.faceVisibleNormal(f.nx, f.ny, f.nz)) continue;
            for (int i = 0; i < 4; i++) {
                float[] c = f.corners[i];
                camera.project(c[0], c[1], c[2], fit.scale, fit.originX, fit.originY, tmpProject);
                p[i * 3] = tmpProject[0];
                p[i * 3 + 1] = tmpProject[1];
                p[i * 3 + 2] = tmpProject[2] - f.depthBias;
            }
            // The corner order is row-major [TL, TR, BL, BR] (indices 0,1,2,3). Splitting on the
            // TL-BR diagonal therefore gives (TL,TR,BR) = (0,1,3) and (TL,BR,BL) = (0,3,2). The
            // earlier (0,1,2)+(0,2,3) pair both covered the left half and left the right-hand
            // triangle (TR..BR) unpainted, so every face lost a triangular notch. The comment has
            // always said the right split; the indices now match it.
            if (f.atlas == null) {
                // A flat-coloured face (the procedural pet): two triangles filled with one colour.
                raster.triangleFlat(p[0], p[1], p[2], p[3], p[4], p[5], p[9], p[10], p[11],
                        f.flatColor, f.blend);
                raster.triangleFlat(p[0], p[1], p[2], p[9], p[10], p[11], p[6], p[7], p[8],
                        f.flatColor, f.blend);
                continue;
            }
            // Two triangles: (TL,TR,BR) and (TL,BR,BL).
            raster.triangle(p[0], p[1], p[2], p[3], p[4], p[5], p[9], p[10], p[11],
                    f.uv[0], f.uv[1], f.uv[2], f.uv[3], f.uv[6], f.uv[7],
                    f.atlas, f.atlasW, f.atlasH, f.alphaTest, f.blend, f.shade);
            raster.triangle(p[0], p[1], p[2], p[9], p[10], p[11], p[6], p[7], p[8],
                    f.uv[0], f.uv[1], f.uv[6], f.uv[7], f.uv[4], f.uv[5],
                    f.atlas, f.atlasW, f.atlasH, f.alphaTest, f.blend, f.shade);
        }
    }

    private final float[] tmpProject = new float[3];

    /** A convenience camera for a yaw with the standard pitch and distance. */
    public static SkinModel.Camera cameraFor(float yawDeg) {
        return new SkinModel.Camera(yawDeg, 12f, SkinModel.CAMERA_DISTANCE,
                0f, SkinModel.heightPixels() / 2f, 0f);
    }

    // --- Builders -----------------------------------------------------------------------------

    /**
     * Adds a {@link SkinModel.Box}'s face as a textured quad from a base or overlay UV table.
     * The corner order matches {@link SkinModel.Box#faceCorners}, so the texture is upright.
     */
    public Face addBoxFace(SkinModel.Box box, SkinModel.Face face, SkinModel.Uv uv,
                           int[] atlas, int atlasW, int atlasH) {
        if (uv == null) return null;
        if (atlas == null || atlasW <= 0 || atlasH <= 0 || atlas.length < atlasW * atlasH) {
            PreviewSceneDiagnostics.record(box == null ? null : box.id,
                    "atlas missing or smaller than declared size", atlasW, atlasH,
                    atlas == null ? 0 : atlas.length);
            return null;
        }
        float[][] c = box.faceCorners(face);
        Face f = new Face();
        f.set(c[0][0], c[0][1], c[0][2], c[1][0], c[1][1], c[1][2],
                c[2][0], c[2][1], c[2][2], c[3][0], c[3][1], c[3][2]);
        f.nx = face.nx; f.ny = face.ny; f.nz = face.nz;
        // UV rectangle -> the four corners, in the same [TL,TR,BL,BR] order.
        float u0 = uv.u / (float) atlasW, v0 = uv.v / (float) atlasH;
        float u1 = (uv.u + uv.w) / (float) atlasW, v1 = (uv.v + uv.h) / (float) atlasH;
        f.uv[0] = u0; f.uv[1] = v0;   // TL
        f.uv[2] = u1; f.uv[3] = v0;   // TR
        f.uv[4] = u0; f.uv[5] = v1;   // BL
        f.uv[6] = u1; f.uv[7] = v1;   // BR
        f.atlas = atlas; f.atlasW = atlasW; f.atlasH = atlasH;
        faces.add(f);
        return f;
    }

    /**
     * Adds a generic textured quad from four model-space corners and four UVs. Used for the cape
     * cloth, a flat-plane or large pet, and any bespoke geometry, so the golden tests can build a
     * scene without the full catalogue.
     */
    public Face addQuad(float[][] corners, float[] uv, int[] atlas, int atlasW, int atlasH) {
        if (corners == null || corners.length != 4 || uv == null || uv.length < 8) return null;
        if (atlas == null || atlasW <= 0 || atlasH <= 0 || atlas.length < atlasW * atlasH) {
            PreviewSceneDiagnostics.record(null, "atlas missing or smaller than declared size",
                    atlasW, atlasH, atlas == null ? 0 : atlas.length);
            return null;
        }
        Face f = new Face();
        f.set(corners[0][0], corners[0][1], corners[0][2],
                corners[1][0], corners[1][1], corners[1][2],
                corners[2][0], corners[2][1], corners[2][2],
                corners[3][0], corners[3][1], corners[3][2]);
        System.arraycopy(uv, 0, f.uv, 0, 8);
        f.atlas = atlas; f.atlasW = atlasW; f.atlasH = atlasH;
        faces.add(f);
        return f;
    }

    /** Adds a flat-coloured quad (the procedural pet's boxes), shaded by the caller's colour. */
    public Face addFlatQuad(float[][] corners, int flatColor) {
        if (corners == null || corners.length != 4) return null;
        Face f = new Face();
        f.set(corners[0][0], corners[0][1], corners[0][2],
                corners[1][0], corners[1][1], corners[1][2],
                corners[2][0], corners[2][1], corners[2][2],
                corners[3][0], corners[3][1], corners[3][2]);
        f.flatColor = flatColor;
        f.alphaTest = false;
        f.blend = false;
        faces.add(f);
        return f;
    }
}
