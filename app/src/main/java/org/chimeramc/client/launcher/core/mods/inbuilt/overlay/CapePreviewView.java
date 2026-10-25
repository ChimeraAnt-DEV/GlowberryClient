package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuffColorFilter;
import android.view.MotionEvent;
import android.view.View;

import org.chimeramc.client.core.cosmetics.CapeSimulator;
import org.chimeramc.client.core.cosmetics.GameSkinSource;
import org.chimeramc.client.core.cosmetics.CosmeticCatalog;
import org.chimeramc.client.core.cosmetics.HeadLookTransform;
import org.chimeramc.client.core.cosmetics.AccessoryGeometry;
import org.chimeramc.client.core.cosmetics.AuthoredHatModels;
import org.chimeramc.client.core.cosmetics.AuthoredGeometry;
import org.chimeramc.client.core.cosmetics.AuthoredPetModels;
import org.chimeramc.client.core.cosmetics.PetModel;
import org.chimeramc.client.core.cosmetics.PetGeometry;
import org.chimeramc.client.core.cosmetics.PetPose;
import org.chimeramc.client.core.cosmetics.PlayerSkinProvider;
import org.chimeramc.client.core.cosmetics.PreviewAccessoryScene;
import org.chimeramc.client.core.cosmetics.PreviewAuthoredScene;
import org.chimeramc.client.core.cosmetics.PreviewCapeScene;
import org.chimeramc.client.core.cosmetics.PreviewCharacterScene;
import org.chimeramc.client.core.cosmetics.PreviewPetScene;
import org.chimeramc.client.core.cosmetics.PreviewRaster;
import org.chimeramc.client.core.cosmetics.PreviewScene;
import org.chimeramc.client.core.cosmetics.SkinAlphaFilter;
import org.chimeramc.client.core.cosmetics.SkinModel;
import org.chimeramc.client.core.cosmetics.geometry.BedrockGeometry;
import org.chimeramc.client.core.cosmetics.geometry.BedrockGeometryParser;
import org.chimeramc.client.core.cosmetics.geometry.PreviewMeshModel;
import org.chimeramc.client.ui.animation.DynamicAnim;

import java.util.List;

/**
 * A real textured 3D render of the player's own Minecraft character, wearing the equipped cape.
 *
 * <p>The model is built from the skin's actual texture: each face of each box samples its own
 * rectangle out of the 64x64 atlas, so the preview shows the player's skin — their hair, their
 * shirt, their face — rather than a generic stand-in. The projection is a real perspective camera
 * ({@link SkinModel.Camera}): the near side of the body is magnified and the far side recedes, which
 * is what gives the render depth instead of reading as a flat isometric sketch. Hidden faces are
 * dropped by a normal test that uses the same transform as the projection, so a face is drawn
 * exactly when it points at the camera.
 *
 * <p>The scene is rasterised by {@link PreviewRaster}, which owns a per-pixel float z-buffer, so
 * occlusion is exact: a nearer surface always wins and draw order does not matter. The old
 * painter's-algorithm sort (a two-level box-then-face key) could interleave two boxes' faces at
 * yaws where their centroids tie, which is what produced the triangular holes at box seams.
 * Textured faces are drawn anti-aliased with bilinear filtering, so the skin reads smoothly
 * rather than as hard pixel blocks.
 *
 * <p>The cape is a {@link CapeSimulator} cloth mesh, drawn behind the body, with the animated
 * Chimera mark crawling across it. The frame callback only runs while there is motion to show,
 * so a static cape under reduced motion costs nothing per frame.
 *
 * <p>Scope note carried by the UI: this previews how the cosmetics <em>look</em>. Bedrock does
 * not expose a custom cape slot to a third-party launcher, so the cape is not injected into the
 * running game — see {@code cosmetics_scope_note}.
 */
public class CapePreviewView extends View {

    /** One crawl cycle of the brand mark. */
    private static final long CRAWL_PERIOD_MS = 3600L;

    /** Idle spin, in degrees per second, so the character keeps showing its depth. */
    private static final float IDLE_SPIN_DEG_PER_SEC = 14f;

    /**
     * The height, in model pixels, an authored pet mesh is normalised to. Chosen to match the
     * procedural body plan (which is roughly this tall with legs, body and head), so an authored pet
     * and a procedural one read as the same size companion.
     */
    private static final float AUTHORED_PET_HEIGHT_PX = 14f;

    /**
     * The preview's own head look, in degrees, animated so a worn hat visibly tracks the head — the
     * preview counterpart of the pack's {@code animation.chimera_hat_tilt}. The head box and the
     * accessory are rotated by the same angles about the same neck pivot, so they cannot detach.
     */
    private float headPitchDeg;
    private float headYawDeg;
    private float headLookMs;

    private static final float DEFAULT_PITCH = 18f;

    private final Paint pixelPaint = new Paint();

    /**
     * Per-face cropped textures, indexed by box then face. Built once per skin, with the alpha test
     * already baked in (see {@link #crop}), so a face's transparent texels are never drawn and never
     * erase what is beneath them.
     */
    private Bitmap[][] baseFaceCrops;
    private Bitmap[][] overlayFaceCrops;

    /**
     * The player's skin, resolved once and refreshed on demand.
     *
     * <p>Not final: {@link #refreshSkin()} re-resolves it so a skin change (a different account
     * skin read from the game, a new import, or a switch in the dressing room) is picked up without
     * recreating the view — the "instant wardrobe update" the radial hub and this preview promise.
     */
    private PlayerSkinProvider.SkinBitmap skin;
    private CosmeticCatalog.Cape cape;
    private CosmeticCatalog.Accessory accessory;
    private CosmeticCatalog.Pet pet;
    private CosmeticCatalog.PetLocomotion petLocomotion = CosmeticCatalog.PetLocomotion.WALK;
    private final PetPose petPose = new PetPose();
    private float petPhase;

    private final CapeSimulator capeSim = new CapeSimulator();

    /** Offscreen 2x supersample target; reallocated only when the view size changes. */
    private Bitmap frame;

    // --- Phase D: the software-rasterised scene ----------------------------------------------
    // The whole scene is drawn by the pure PreviewRaster/PreviewScene pipeline instead of Canvas: it
    // gives a per-pixel depth buffer (so every piece occludes correctly) and nearest-neighbour
    // sampling (so a transparent texel cannot bleed into an opaque one). The skin atlas is an int[]
    // copy, taken once per skin.
    private final PreviewRaster raster = new PreviewRaster();
    private PreviewScene scene;
    /** The pet scene, rebuilt when the pose advances; rendered into the same buffer as the body. */
    private PreviewScene petScene;
    /** The cape cloth scene, rebuilt when the cloth steps; rendered into the same buffer. */
    private PreviewScene capeScene;
    /** The procedural accessory scene; rendered into the same buffer as the body. */
    private PreviewScene accessoryScene;
    private int[] skinAtlasPixels;
    /** The rasteriser's ARGB buffer copied into a Bitmap the Canvas can blit. */
    private Bitmap rasterBitmap;
    /** 2x internal supersample: the scene renders at twice the view size, then downscales. */
    private static final int RASTER_SUPERSAMPLE = 2;
    /** Cap on the rasteriser's internal edge, so a large view cannot blow the memory budget. */
    private static final int RASTER_MAX_EDGE = 1024;

    /**
     * The authored Blockbench mesh for the equipped accessory, resolved once per selection.
     *
     * <p>When an accessory has a hand-authored {@code .geo.json} (see {@code AuthoredHatModels}),
     * the preview draws its real geometry — its bone pivots, per-cube UVs and mirrored faces — rather
     * than the procedural box stack. The procedural path stays for the many accessories that have no
     * authored mesh, so nothing regresses.
     */
    private List<PreviewMeshModel.Box> accessoryMesh;
    /** The authored mesh's atlas, decoded lazily; null until the first frame that needs it. */
    private Bitmap accessoryAtlas;
    private boolean accessoryAtlasLoaded;
    /** The authored mesh's identifier, used to pick the atlas file. */
    private String accessoryMeshTextureFile;
    /**
     * The atlas size the accessory's geometry declares ({@code texture_width}/{@code texture_height}).
     * UVs are normalised against this, while the rasteriser samples the decoded bitmap's real size;
     * the two differ for many shipped PNGs (a 32x32 hat, a 64x48 hat, ...).
     */
    private int accessoryGeoTexW = 64;
    private int accessoryGeoTexH = 64;

    /**
     * The authored Blockbench mesh for the equipped pet, resolved once per selection.
     *
     * <p>Same pipeline as the accessory mesh ({@code .geo.json} -> {@code BedrockGeometryParser} ->
     * {@code PreviewMeshModel}), so a pet authored in Blockbench is drawn as its real rig rather than
     * the procedural body plan. A pet with no authored mesh (or an unreadable asset) leaves this null
     * and the procedural {@code PetModel} path draws it as before.
     */
    private List<PreviewMeshModel.Box> petMesh;
    private Bitmap petAtlas;
    private boolean petAtlasLoaded;
    private String petMeshTextureFile;
    /** The pet geometry's declared atlas size; UVs normalise against this (see accessory note). */
    private int petGeoTexW = 64;
    private int petGeoTexH = 64;

    private float yawDeg = 28f;
    private float pitchDeg = DEFAULT_PITCH;
    private float spinVelocity;
    private boolean userRotating;
    private long lastFrameNanos;

    /**
     * The perspective camera for the current frame. Rebuilt once per {@code onDraw} from the live
     * yaw/pitch/scale, and used by every projection and cull test so the two cannot disagree.
     */
    private SkinModel.Camera camera;

    private long animStartMs;
    private boolean animating;
    private boolean attached;
    /** When true, idle motion runs even with no cape/pet equipped (the Launch-tab hero). */
    private boolean alwaysAnimate;

    private float lastTouchX;
    private long lastTouchMs;

    private final android.view.Choreographer.FrameCallback frameCallback =
            new android.view.Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    if (!animating) return;
                    float dt = lastFrameNanos == 0L ? 0f
                            : (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
                    lastFrameNanos = frameTimeNanos;
                    // Clamp so a stall cannot fast-forward the cloth.
                    dt = Math.min(dt, 0.1f);
                    advance(dt);
                    invalidate();
                    android.view.Choreographer.getInstance().postFrameCallback(this);
                }
            };

    public CapePreviewView(Context context) {
        this(context, null);
    }

    /**
     * XML constructor, so the preview can be declared in a layout rather than only built in code.
     *
     * <p>The Launch-tab hero panel hosts a preview, and inflating it from XML keeps that layout
     * declarative; the code path in {@code CosmeticsPanel} keeps the one-argument constructor.
     */
    public CapePreviewView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        // Textured faces are sampled with bilinear filtering and anti-aliasing set per draw in
        // drawModel; flat accessory quads use `paint`, which is anti-aliased but unfiltered, so a
        // solid colour stays hard-edged.
        pixelPaint.setFilterBitmap(false);
        pixelPaint.setAntiAlias(false);
        this.skin = PlayerSkinProvider.resolve(context);
        rebuildFaceCrops();
        rebuildCharacterScene();
    }

    public void setCape(CosmeticCatalog.Cape cape) {
        this.cape = cape;
        if (cape != null) capeSim.reset(0f, 0f, 0f);
        rebuildCapeScene();
        syncAnimation();
        invalidate();
    }

    public void setAccessory(CosmeticCatalog.Accessory accessory) {
        this.accessory = accessory;
        rebuildAccessoryMesh();
        rebuildAccessoryScene();
        syncAnimation();
        invalidate();
    }

    /**
     * Resolves the equipped accessory's authored Blockbench mesh, if it has one.
     *
     * <p>Runs once per selection, never per frame. The mesh is parsed from the pack assets and the
     * bone hierarchy is flattened to world-space corners here; the renderer then only projects.
     * An accessory with no authored model (or an unreadable asset) leaves {@link #accessoryMesh}
     * null and the procedural path draws it as before, so a broken file cannot blank the preview.
     */
    private void rebuildAccessoryMesh() {
        accessoryMesh = null;
        accessoryMeshTextureFile = null;
        accessoryGeoTexW = 64;
        accessoryGeoTexH = 64;
        if (accessory == null || accessory.modelId == null) return;
        if (!AuthoredHatModels.hasAuthoredModel(accessory.modelId)) return;

        String json = AuthoredHatModels.loadFromAssets(this::openAsset, accessory.modelId);
        if (json == null) return;
        BedrockGeometry geometry = BedrockGeometryParser.parse(json);
        if (geometry == null) return;
        List<PreviewMeshModel.Box> boxes = PreviewMeshModel.build(geometry);
        if (boxes.isEmpty()) return;

        accessoryMesh = boxes;
        accessoryMeshTextureFile = AuthoredHatModels.textureFor(accessory.modelId);
        // The atlas the geometry's UVs are written against. This is what UVs normalise by, and it can
        // differ from the decoded PNG's real size — using a constant here is what made a 32x32 hat
        // crash the app.
        if (!geometry.models.isEmpty()) {
            BedrockGeometry.Description desc = geometry.models.get(0).description;
            if (desc.textureWidth > 0) accessoryGeoTexW = desc.textureWidth;
            if (desc.textureHeight > 0) accessoryGeoTexH = desc.textureHeight;
        }
        // A new accessory means a new atlas; drop the cached decode so the next frame reloads it.
        if (accessoryAtlas != null && !accessoryAtlas.isRecycled()) accessoryAtlas.recycle();
        accessoryAtlas = null;
        accessoryAtlasLoaded = false;
    }

    /** Opens a pack asset, matching the {@link AuthoredGeometry.AssetOpener} contract. */
    private java.io.InputStream openAsset(String path) throws java.io.IOException {
        return getContext().getAssets().open(path);
    }

    public void setPet(CosmeticCatalog.Pet pet) {
        this.pet = pet;
        rebuildPetMesh();
        rebuildPetScene();
        syncAnimation();
        invalidate();
    }

    /**
     * Resolves the equipped pet's authored Blockbench mesh, if it has one.
     *
     * <p>Runs once per selection, never per frame, mirroring {@link #rebuildAccessoryMesh}. A pet
     * without an authored model keeps the procedural body plan, so the shipped set of authored pets
     * is a strict upgrade with no regression for the rest.
     */
    private void rebuildPetMesh() {
        petMesh = null;
        petMeshTextureFile = null;
        petGeoTexW = 64;
        petGeoTexH = 64;
        if (pet == null) return;
        String petId = pet.modelId != null ? pet.modelId : pet.id;
        if (!AuthoredPetModels.hasAuthoredModel(petId)) return;

        String json = AuthoredPetModels.loadFromAssets(this::openAsset, petId);
        if (json == null) return;
        BedrockGeometry geometry = BedrockGeometryParser.parse(json);
        if (geometry == null) return;
        List<PreviewMeshModel.Box> boxes = PreviewMeshModel.build(geometry);
        if (boxes.isEmpty()) return;
        // Authored exports are in their own model units; rescale to the procedural body-plan scale so
        // an authored pet is the same size as the rest (an un-normalised dragon rendered several
        // times too large and off-screen).
        PreviewMeshModel.normalizeToHeight(boxes, AUTHORED_PET_HEIGHT_PX);

        petMesh = boxes;
        petMeshTextureFile = AuthoredPetModels.textureFor(petId);
        if (!geometry.models.isEmpty()) {
            BedrockGeometry.Description desc = geometry.models.get(0).description;
            if (desc.textureWidth > 0) petGeoTexW = desc.textureWidth;
            if (desc.textureHeight > 0) petGeoTexH = desc.textureHeight;
        }
        if (petAtlas != null && !petAtlas.isRecycled()) petAtlas.recycle();
        petAtlas = null;
        petAtlasLoaded = false;
    }

    /** The gait the equipped pet is currently animating; the panel offers the ones it supports. */
    public void setPetLocomotion(CosmeticCatalog.PetLocomotion locomotion) {
        this.petLocomotion = PetPose.resolveGait(pet == null ? null : pet.species, locomotion);
        rebuildPetScene();
        syncAnimation();
        invalidate();
    }

    public CosmeticCatalog.PetLocomotion getPetLocomotion() {
        return petLocomotion;
    }

    /** True when the displayed skin is the grey last-resort placeholder, not a real skin. */
    public boolean isShowingFallbackSkin() {
        return skin == null || skin.isFallback;
    }

    /** True when the preview is showing the built-in Steve character (no skin imported yet). */
    public boolean isShowingDefaultSteve() {
        return skin != null && skin.isDefaultSteve;
    }

    public String getSkinSourceName() {
        return skin == null ? "" : skin.sourceName;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        syncAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) syncAnimation();
        else stopAnimation();
    }

    /**
     * Stops the frame callback. Called when the host panel is hidden so an off-screen preview does
     * not keep driving Choreographer frames; {@link #startPreview()} restarts it when the view is
     * shown again.
     */
    public void stopPreview() {
        stopAnimation();
    }

    /**
     * Restarts the frame callback after {@link #stopPreview()}. A view that was stopped while the
     * host was paused does not resume by itself, so the host must call this when it returns.
     */
    public void startPreview() {
        syncAnimation();
    }

    /**
     * Re-resolves the player's skin and rebuilds the character.
     *
     * <p>Called when the game skin becomes available (a session started), when the player changes
     * their skin, or when the panel is shown. It picks up the game's live skin through
     * {@link org.chimeramc.client.core.cosmetics.GameSkinSource} if a provider is installed,
     * otherwise the imported/applied/Steve chain — so a skin change is reflected without recreating
     * the view.
     */
    public void refreshSkin() {
        PlayerSkinProvider.SkinBitmap resolved = PlayerSkinProvider.resolve(getContext());
        if (resolved == null || resolved.bitmap == null) return;
        this.skin = resolved;
        rebuildFaceCrops();
        rebuildCharacterScene();
        invalidate();
    }

    /** True when the displayed skin came from the game (the player's real account skin). */
    public boolean isShowingGameSkin() {
        return skin != null && GameSkinSource.hasProvider() && !skin.isDefaultSteve
                && !skin.isFallback;
    }

    /**
     * Keeps the idle motion running even with no cosmetic equipped.
     *
     * <p>By default the preview only animates while there is a cape or pet to move, which is right
     * for the Cosmetics editor (nothing equipped means nothing to animate). The Launch-tab hero
     * wants the character to turn and glance regardless, so it opts in here rather than changing
     * the editor's behaviour.
     */
    public void setAlwaysAnimate(boolean value) {
        this.alwaysAnimate = value;
        syncAnimation();
    }

    /**
     * Releases every bitmap this view cached and stops its animation.
     *
     * <p>Called from the host's {@code onPause}/{@code onDestroy} so switching away from the Launch
     * tab (or the Mod Menu) cannot leave the face crops, alpha masks, decoded atlases and the 2x
     * supersample target resident. On a low-end device those are tens of megabytes of ARGB_8888
     * that the next screen has to compete with, which is the "black box" the report saw: the
     * allocation failed and {@link #ensureFrame} returned null mid-frame.
     *
     * <p>Idempotent and safe to call off the animation: {@code rebuildFaceCrops()} re-derives the
     * crops from the skin the next time the view is shown, so a released view still draws.
     */
    public void release() {
        stopAnimation();
        recycle(baseFaceCrops);
        recycle(overlayFaceCrops);
        baseFaceCrops = null;
        overlayFaceCrops = null;
        recycleOne(accessoryAtlas);
        recycleOne(petAtlas);
        accessoryAtlas = null;
        petAtlas = null;
        accessoryAtlasLoaded = false;
        petAtlasLoaded = false;
        recycleOne(frame);
        frame = null;
        rebuildFaceCrops();
    }

    private static void recycle(Bitmap[][] grid) {
        if (grid == null) return;
        for (Bitmap[] row : grid) {
            if (row == null) continue;
            for (Bitmap b : row) recycleOne(b);
        }
    }

    private static void recycleOne(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private void syncAnimation() {
        boolean want = DynamicAnim.areAnimationsEnabled() && getVisibility() == VISIBLE && attached
                && (alwaysAnimate || pet != null || cape != null);
        if (want && !animating) {
            animating = true;
            lastFrameNanos = 0L;
            animStartMs = android.os.SystemClock.uptimeMillis();
            android.view.Choreographer.getInstance().postFrameCallback(frameCallback);
        } else if (!want) {
            stopAnimation();
        }
    }

    private void stopAnimation() {
        animating = false;
        lastFrameNanos = 0L;
        android.view.Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    private void advance(float dt) {
        if (!DynamicAnim.areAnimationsEnabled()) return;
        if (!userRotating) {
            // A flick keeps spinning for a moment, then settles back to the slow idle turn.
            if (Math.abs(spinVelocity) > 1f) {
                yawDeg += spinVelocity * dt;
                spinVelocity *= Math.pow(0.12f, dt);
            } else {
                yawDeg += IDLE_SPIN_DEG_PER_SEC * dt;
            }
            if (yawDeg > 360f) yawDeg -= 360f;
        }
        if (cape != null) {
            // The cloth is driven by the residual spin velocity, so a flick sends the cape out
            // and it settles naturally instead of looping forever. The drive is clamped: an
            // unclamped flick could reach hundreds of blocks/s of "wind", which is what made the
            // cape flap wildly and look broken. A hard cap keeps even the sharpest flick to a
            // believable billow, and the cape's own damping does the rest.
            float speed = Math.min(Math.abs(spinVelocity) * 0.03f, 2.6f);
            float fx = (float) Math.sin(Math.toRadians(yawDeg));
            float fz = (float) Math.cos(Math.toRadians(yawDeg));
            capeSim.step(dt, 0f, 0f, -0.02f, fx, fz, speed);
            // The cape is part of the software scene now, so its cloth is rebuilt for the new state.
            rebuildCapeScene();
        }
        if (pet != null) {
            petPhase += PetPose.cyclesPerSecond(petLocomotion) * dt;
            if (petPhase > 1f) petPhase -= (float) Math.floor(petPhase);
            petPose.compute(pet.species, petLocomotion, petPhase);
            // The procedural pet is part of the software scene, so its geometry is rebuilt for the
            // new pose. An authored pet is still a Canvas layer and leaves petScene null.
            rebuildPetScene();
        }
        // A slow idle head look, so a worn hat is visibly on the head rather than a static block
        // that only lines up at one angle. Bounded and gentle: the character glances, it does not
        // swivel. The accessory applies these angles only when it is a head-worn piece.
        headLookMs += dt * 1000f;
        float[] look = headLookScratch;
        HeadLookTransform.idleLook((long) headLookMs, look);
        // Rebuild the software character scene only when the head look moved materially, so the
        // per-frame path does not re-derive the whole model for a sub-degree change.
        if (Math.abs(look[0] - headPitchDeg) > 0.75f || Math.abs(look[1] - headYawDeg) > 0.75f) {
            headPitchDeg = look[0];
            headYawDeg = look[1];
            rebuildCharacterScene();
            // A head-worn accessory tracks the head, so its scene is rebuilt with the head look.
            rebuildAccessoryScene();
        }
    }

    private final float[] headLookScratch = new float[2];
    private final float[] headPointScratch = new float[3];
    private final float[] headNormalScratch = new float[3];

    /**
     * Rotates a model-space point by the current head look about the neck pivot, writing into
     * {@code out}.
     *
     * <p>Applied to the head box's corners and to the worn accessory's mesh corners with the same
     * angles and the same pivot, so the hat and the head move as one — the preview's equivalent of
     * the pack's {@code acc} bone being driven by the head-look queries.
     */

    // ---- Touch: drag to spin, flick to set it turning -----------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                userRotating = true;
                spinVelocity = 0f;
                lastTouchX = event.getX();
                lastTouchMs = event.getEventTime();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - lastTouchX;
                long dtMs = Math.max(1L, event.getEventTime() - lastTouchMs);
                yawDeg -= dx * 0.6f;
                spinVelocity = -(dx * 0.6f) / (dtMs / 1000f);
                lastTouchX = event.getX();
                lastTouchMs = event.getEventTime();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                userRotating = false;
                if (Math.abs(spinVelocity) < 40f) spinVelocity = 0f;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    // ---- Rendering ----------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Defence in depth: a draw bug must not kill the app. This is NOT a fallback renderer — it
        // draws a small notice and records the exception for the diagnostics report, then returns.
        try {
            // Phase D: the whole scene — character, pet, cape, accessories, authored meshes — is
            // rasterised by the pure software renderer, so it needs no Canvas layers and the depth
            // buffer resolves every piece against every other.
            int internalW = Math.min(w * RASTER_SUPERSAMPLE, RASTER_MAX_EDGE);
            int internalH = Math.min(h * RASTER_SUPERSAMPLE, RASTER_MAX_EDGE);
            Bitmap target = ensureFrame(internalW, internalH);
            if (target == null) {
                // No offscreen target could be allocated: draw nothing rather than a broken half-frame.
                return;
            }
            // Background: transparent, so the glass panel behind the preview shows through.
            raster.prepare(internalW, internalH, 0x00000000);
            camera = new SkinModel.Camera(yawDeg, pitchDeg, SkinModel.CAMERA_DISTANCE, 0f, 18f, 0f);
            renderScene(camera, internalW, internalH);
            target.setPixels(raster.pixels(), 0, internalW, 0, 0, internalW, internalH);
            canvas.drawBitmap(target, new android.graphics.Rect(0, 0, internalW, internalH),
                    new android.graphics.Rect(0, 0, w, h), pixelPaint);
        } catch (Throwable t) {
            lastRenderError = t;
            drawPreviewError(canvas, w, h);
        }
    }

    /** The last exception the render path threw, surfaced in the diagnostics report; null if none. */
    private volatile Throwable lastRenderError;

    /** The most recent render exception, or null. Used by the cosmetics diagnostics dialog. */
    public Throwable getLastRenderError() {
        return lastRenderError;
    }

    /** Paints a small "preview error" notice instead of a broken half-frame. */
    private void drawPreviewError(Canvas canvas, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFF5C5C);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(Math.max(11f, Math.min(w, h) * 0.07f));
        canvas.drawText("preview error", w / 2f, h / 2f, p);
    }

    /**
     * Renders every scene into the software renderer's buffer, with a camera fit that frames the
     * whole scene (so the legs and a ground-level pet are never clipped).
     *
     * <p>All the sub-scenes are rendered through one {@link PreviewScene.Fit}, computed from the
     * union of their bounds, so the character, pet, cape and accessories are placed on a common
     * frame and cannot drift apart.
     */
    private void renderScene(SkinModel.Camera cam, int internalW, int internalH) {
        PreviewScene.Fit fit = fitScene(cam, internalW, internalH);
        renderInto(scene, cam, fit);
        renderInto(petScene, cam, fit);
        renderInto(capeScene, cam, fit);
        renderInto(accessoryScene, cam, fit);
    }

    private void renderInto(PreviewScene s, SkinModel.Camera cam, PreviewScene.Fit fit) {
        if (s != null) s.render(raster, cam, fit);
    }

    /**
     * Frames every sub-scene into the viewport with a margin, so nothing is clipped. The fit is
     * derived from the union of the sub-scenes' model-space bounds.
     */
    private PreviewScene.Fit fitScene(SkinModel.Camera cam, int w, int h) {
        PreviewScene combined = new PreviewScene();
        addBounds(combined, scene);
        addBounds(combined, petScene);
        addBounds(combined, capeScene);
        addBounds(combined, accessoryScene);
        if (combined.faces().isEmpty()) {
            // Nothing to frame (no skin, nothing equipped): use the character's default fit.
            combined = scene != null ? scene : new PreviewScene();
        }
        return combined.fit(cam, w, h, 0.06f);
    }

    /** Copies a sub-scene's faces into {@code into} so the union of their bounds can be measured. */
    private static void addBounds(PreviewScene into, PreviewScene from) {
        if (from == null) return;
        for (PreviewScene.Face f : from.faces()) into.faces().add(f);
    }

    /** Builds the character scene from the current skin, once per skin. */
    private void rebuildCharacterScene() {
        Bitmap atlas = skin == null ? null : skin.bitmap;
        // The scene samples the atlas as a 64x64 grid (the UV table's contract, which
        // PlayerSkinProvider.normalise upholds). A differently-sized bitmap would be read out of
        // bounds, so fail closed to the Canvas pass rather than mis-sample it.
        if (atlas == null || atlas.getWidth() != SkinModel.ATLAS_SIZE
                || atlas.getHeight() != SkinModel.ATLAS_SIZE) {
            scene = null;
            skinAtlasPixels = null;
            return;
        }
        int aw = atlas.getWidth();
        int ah = atlas.getHeight();
        skinAtlasPixels = new int[aw * ah];
        atlas.getPixels(skinAtlasPixels, 0, aw, 0, 0, aw, ah);
        scene = new PreviewScene();
        PreviewCharacterScene.addCharacter(scene, skinAtlasPixels, SkinModel.ATLAS_SIZE,
                headPitchDeg, headYawDeg);
    }

    /**
     * Rebuilds the pet scene for the current pose, from the authored mesh when the pet has one and
     * the procedural body plan otherwise.
     */
    private void rebuildPetScene() {
        if (pet == null) {
            petScene = null;
            return;
        }
        petScene = new PreviewScene();
        if (petMesh != null && !petMesh.isEmpty()) {
            Bitmap atlasBitmap = ensurePetAtlas();
            int[] atlas = atlasPixels(atlasBitmap);
            if (atlas != null) {
                // Sample with the bitmap's REAL size; normalise UVs against the geometry's declared
                // size. These differ for several shipped pets, so using one constant for both is
                // exactly the crash this fixes.
                int aw = atlasBitmap.getWidth();
                int ah = atlasBitmap.getHeight();
                PreviewAuthoredScene.addPet(petScene, petMesh, atlas, aw, ah,
                        petGeoTexW, petGeoTexH,
                        PreviewPetScene.OFFSET_X, petPose.bodyBob - petPose.crouchDrop, 0f, petPose);
            }
        } else {
            PreviewPetScene.addPet(petScene, pet, petPose, petLocomotion);
        }
    }

    /**
     * Rebuilds the cape cloth scene from the current simulator state. Called when the cloth steps.
     */
    private void rebuildCapeScene() {
        if (cape == null) {
            capeScene = null;
            return;
        }
        capeScene = new PreviewScene();
        PreviewCapeScene.addCape(capeScene, cape, capeSim);
    }

    /**
     * Rebuilds the accessory scene, from the authored mesh when the accessory has one and the
     * procedural boxes otherwise.
     */
    private void rebuildAccessoryScene() {
        if (accessory == null || accessory.kind == CosmeticCatalog.AccessoryKind.NONE) {
            accessoryScene = null;
            return;
        }
        accessoryScene = new PreviewScene();
        if (accessoryMesh != null) {
            Bitmap atlasBitmap = ensureAccessoryAtlas();
            int[] atlas = atlasPixels(atlasBitmap);
            if (atlas != null) {
                int aw = atlasBitmap.getWidth();
                int ah = atlasBitmap.getHeight();
                PreviewAuthoredScene.addAccessory(accessoryScene, accessoryMesh, atlas, aw, ah,
                        accessoryGeoTexW, accessoryGeoTexH,
                        headPitchDeg, headYawDeg, accessory.kind.followsHead());
            }
        } else {
            PreviewAccessoryScene.addAccessoryBehind(accessoryScene, accessory);
            PreviewAccessoryScene.addAccessoryFront(accessoryScene, accessory,
                    headPitchDeg, headYawDeg);
        }
    }

    /** A bitmap's pixels as an int[] ARGB atlas, or null. */
    private static int[] atlasPixels(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return null;
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        return pixels;
    }

    /** The offscreen supersample target, or null when it cannot be allocated. */
    private Bitmap ensureFrame(int width, int height) {
        if (frame != null && frame.getWidth() == width && frame.getHeight() == height) {
            frame.eraseColor(0);
            return frame;
        }
        try {
            frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            frame = null;
        }
        return frame;
    }

    // ---- Pet -----------------------------------------------------------------------------

    /**
     * Draws the equipped pet beside the character.
     *
     * <p>The pet's mesh comes from {@link PetModel}, the same vanilla-grounded body plan the in-game
     * geometry uses, so the preview and the game cannot drift. The pose comes from {@link PetPose},
     * which now animates per-bone: legs swing for a walk or run, a bird flaps and bobs, a serpent
     * undulates, a swimmer paddles. When the player is running, crouching, flying or swimming, the
     * crawling bugs switch to their contextual behaviour (riding the head, looking around, clinging
     * against the wind or the water) exactly as the in-game controller does.
     */

    /**
     * Draws an authored Blockbench pet beside the player.
     *
     * <p>The mesh is centred on its own X/Z (authored pets are symmetric about the origin) and
     * placed at the same ground line the procedural pet uses, so the two paths look like the same
     * companion rather than two different toys. A soft contact shadow grounds it, and the gait lift
     * matches the procedural path so a flying or swimming pet is visibly off the ground.
     */

    /** Scratch for {@link #petBoneRotation(String)}, so the per-frame draw path allocates nothing. */
    private final float[] petBoneRotScratch = new float[3];

    /**
     * The rotation, in degrees, the current {@link PetPose} applies to a bone, written into
     * {@link #petBoneRotScratch}, or null when the bone is not animated (so a cube on an unnamed
     * bone keeps its authored pose and costs nothing).
     *
     * <p>The bone names are the ones {@link PetBoneRetarget} maps an authored model onto, so an
     * authored pet animates with the same gait the procedural body plan uses. The caller must
     * consume the result before the next call.
     */

    /** Rotates a model-space point about a pivot by the bone's X/Y/Z degrees (Z, then Y, then X). */

    /** The atlas for the current authored pet, decoded once and cached. */
    private Bitmap ensurePetAtlas() {
        if (petAtlasLoaded) return petAtlas;
        petAtlasLoaded = true;
        petAtlas = null;
        if (petMeshTextureFile == null) return null;
        try (java.io.InputStream input = getContext().getAssets()
                .open(AuthoredPetModels.DIR + "/" + petMeshTextureFile)) {
            petAtlas = buildCutout(android.graphics.BitmapFactory.decodeStream(input));
        } catch (java.io.IOException | RuntimeException e) {
            petAtlas = null;
        }
        return petAtlas;
    }

    /** A soft contact shadow under the pet, faded by how far it is off the ground. */

    /**
     * Paints every box of the pet's {@link PetModel} plan, grouped by bone so the pose can move the
     * head, tail, wings and legs independently.
     *
     * <p>Each bone's transform is a rotation about its own pivot (the joint), which is what makes a
     * leg swing from the hip and a tail wag from the rump rather than the whole animal tilting.
     */

    /** Rotates a point by the bone's X/Y/Z degrees (Z, then Y, then X), in model space. */

    /** A single shaded pet box, projected with the same camera as the character. */

    /**
     * A single shaded pet box whose shading follows the bone's rotation.
     *
     * <p>Shading the face from its <em>rotated</em> normal is what makes an animated limb read as
     * three-dimensional: a leg swinging through its arc brightens and darkens with the swing, the
     * way a lit surface does, instead of staying at a constant shade while the geometry moves. The
     * rotation is the same one the box's centre was rotated by, so the light and the geometry
     * cannot disagree.
     */

    /** Shades a face using its normal rotated by the bone's own rotation. */

    /** An opaque grey for a 0..255 shade value, used as a MULTIPLY filter's colour. */

    /**
     * Cached MULTIPLY shade filters, one per grey value.
     *
     * <p>Shading a textured face creates a {@code PorterDuffColorFilter}; the character has a few
     * dozen faces and the value only takes one of a small set of greys, so caching them keeps the
     * per-frame draw path allocation-free (the same discipline the rest of the preview follows).
     */
    private final PorterDuffColorFilter[] shadeFilters = new PorterDuffColorFilter[256];

    /** The cached MULTIPLY filter for a grey value, or null at full brightness (no filter). */

    /**
     * Fills the quad described by {@code v}, which is in {@link SkinModel.Box#faceCorners} grid
     * order: {@code [TL, TR, BL, BR]}.
     *
     * <p>Traversing the array in order would draw a bow-tie, so the perimeter is walked
     * TL → TR → BR → BL instead. That order is correct for both the flat accessory fills and the
     * character's shading overlay; the textured pass uses {@code drawBitmapMesh}, which wants the
     * grid order directly.
     */

    /** Average camera depth of the projected cloth, set by {@link #projectCape}. */
    private float capeAverageDepth;

    /**
     * Projects the cloth mesh once for this frame and records the average depth.
     *
     * <p>Split out of the paint so the caller can decide whether the cape is in front of or behind
     * the body before choosing a draw order. Without this the cape was always painted first and so
     * vanished whenever the camera swung around to the player's back.
     */

    /**
     * Paints the cloth mesh with the animated mark printed on it.
     *
     * <p>Requires {@link #projectCape} to have run for this frame; the draw order in {@code onDraw}
     * guarantees it. Kept separate from the projection so the depth sort and the paint order can
     * disagree about nothing.
     */

    /** Farthest-first insertion sort; small fixed quad count, allocation-free. */

    /**
     * The Chimera mark crawling across the cloth.
     *
     * The mark walks along the mesh's parameter space so it follows the cloth's curve instead of
     * sliding over a flat quad, and it is clipped to the cloth so it reads as printed on the
     * fabric. The phase comes from uptime, so the crawl speed does not depend on frame rate.
     */

    /**
     * Accessories behind the character: anything worn on the back.
     *
     * <p>Wings are geometry anchored to the shoulder blades, so they are painted before the cape
     * and the body. A back-worn piece drawn in the foreground pass covers the torso and looks
     * pasted onto the chest instead of worn.
     */

    /** A box worn on the back, with a strap over each shoulder and a pocket flap. */

    /**
     * Accessories in front of the character: worn on or around the head, face and torso.
     *
     * <p>Drawn after the model so they sit over the head instead of being hidden inside it.
     */

    // ---- Authored Blockbench mesh ---------------------------------------------------------

    /**
     * Draws the equipped accessory's authored {@code .geo.json} mesh, if it has one.
     *
     * <p>This is the payoff of the preview pipeline: instead of a procedural box stack the preview
     * shows the exact authored model — every bone pivot, every cube, every mirrored face UV. The
     * mesh was resolved to world-space corners in {@link #rebuildAccessoryMesh}; here it is only
     * projected, depth-sorted and painted.
     *
     * <p>Colour comes from the accessory's palette when no atlas is available, and from the decoded
     * atlas when it is, so a hat authored with a real texture shows that texture and a hat without
     * one still shows its silhouette.
     */

    /**
     * Draws an authored pet mesh at a model-space offset, animating it per bone.
     *
     * <p>An authored pet is retargeted onto the gait vocabulary ({@code head}, {@code tail},
     * {@code wing_l}/{@code wing_r}, {@code leg_a}..{@code leg_c}) but its resolved corners carry
     * only the authored pose, so without this the mesh would render stiff. Each cube is swung about
     * its own bone pivot by the {@link PetPose} rotation for its bone, exactly as the procedural
     * body plan is, so an authored pet walks, flaps and wags instead of standing still.
     */

    /**
     * Draws an authored mesh at a model-space offset.
     *
     * <p>Shared by the accessory (offset zero) and the pet (offset to the player's side), so the two
     * cannot drift on projection, depth sorting, lighting or UV mirroring. The offset is applied to
     * each corner before projection, which is the same as translating the mesh in the world without
     * rebuilding it.
     *
     * @param headLooked true for a worn head accessory: its corners are rotated by the same head-look
     *                   angles and neck pivot the head box uses, so the hat tracks the head
     * @param animatePet true for an authored pet: each cube is swung about its bone pivot by the
     *                   current {@link PetPose} rotation for its bone, so the mesh animates
     */

    /**
     * The part of a projected quad that is visible against the depth buffer, or null when it is
     * fully occluded.
     *
     * <p>The preview has no hardware depth test, so occlusion is resolved by clipping. Testing at
     * each scanline against the per-pixel depth buffer (the body, and any nearer cosmetic already
     * recorded) gives a vertical band of visible pixels instead of a whole-quad in/out choice, so a
     * cape that is nearer at the top than the body and farther at the bottom draws exactly the
     * visible part rather than the whole quad or none of it. The band is one {@link Path}, so the
     * textured pass still issues a single bitmap draw.
     */

    private final Path visibleBandPath = new Path();
    private final Path fullQuadPathScratch = new Path();

    /** Paints one authored-mesh face from the atlas, honouring the face's mirror flags. */

    /**
     * A copy of a cosmetic atlas with the alpha test baked in: a texel below
     * {@link SkinAlphaFilter#ALPHA_THRESHOLD} becomes fully transparent, and a surviving texel is
     * forced to full alpha. This is the authored-mesh equivalent of {@link #crop}'s quantisation,
     * and it is what makes a hat a hard-edged shape instead of a translucent rectangle without a
     * separate destructive pass.
     */
    private static Bitmap buildCutout(Bitmap atlas) {
        if (atlas == null) return null;
        int w = atlas.getWidth();
        int h = atlas.getHeight();
        if (w <= 0 || h <= 0) return null;
        int[] pixels = new int[w * h];
        atlas.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = SkinAlphaFilter.isOpaque(pixels[i])
                    ? (pixels[i] | 0xFF000000) : 0x00000000;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    private final Path meshClip = new Path();

    /** The atlas for the current authored mesh, decoded once and cached. */
    private Bitmap ensureAccessoryAtlas() {
        if (accessoryAtlasLoaded) return accessoryAtlas;
        accessoryAtlasLoaded = true;
        accessoryAtlas = null;
        if (accessoryMeshTextureFile == null) return null;
        try (java.io.InputStream input = getContext().getAssets()
                .open(AuthoredHatModels.DIR + "/" + accessoryMeshTextureFile)) {
            // Bake the alpha test in once here, so the per-frame textured pass is a plain draw.
            accessoryAtlas = buildCutout(android.graphics.BitmapFactory.decodeStream(input));
        } catch (java.io.IOException | RuntimeException e) {
            accessoryAtlas = null;
        }
        return accessoryAtlas;
    }

    /**
     * A resolved box's centroid depth, the primary painter's key for its faces.
     *
     * <p>{@code boneRot} is the animated bone rotation applied to the box, or null for a static
     * accessory. The centroid must be transformed the same way the corners are, or an animated pet's
     * cube would sort by its resting position and a swinging leg could paint behind the body it has
     * moved in front of.
     */

    /** A face's outward normal, rotated by the bone chain so the cull and the light agree. */

    /**
     * True while the accessory currently being drawn is a head-worn piece, so {@link #drawBox}
     * rotates its boxes by the head look. Set by {@link #drawAccessoryFront} from
     * {@link CosmeticCatalog.AccessoryKind#followsHead()}.
     */
    private boolean headLookBoxes;

    // ---- Face texture crops --------------------------------------------------------------

    /**
     * Slices the atlas into one small bitmap per face, once per skin.
     *
     * Cropping up front is what lets {@code drawBitmapMesh} texture a projected quad: it maps a
     * whole bitmap across the mesh and has no source rectangle of its own. A fully transparent
     * region is skipped so the overlay layer costs nothing on a classic skin with no hat.
     */
    private void rebuildFaceCrops() {
        Bitmap atlas = skin == null ? null : skin.bitmap;
        if (atlas == null) {
            baseFaceCrops = null;
            overlayFaceCrops = null;
            return;
        }
        List<SkinModel.Box> boxes = SkinModel.boxes();
        int faces = SkinModel.Face.values().length;
        baseFaceCrops = new Bitmap[boxes.size()][];
        overlayFaceCrops = new Bitmap[boxes.size()][];
        for (int b = 0; b < boxes.size(); b++) {
            SkinModel.Box box = boxes.get(b);
            baseFaceCrops[b] = new Bitmap[faces];
            overlayFaceCrops[b] = new Bitmap[faces];
            for (SkinModel.Face face : SkinModel.Face.values()) {
                int f = face.ordinal();
                // The base skin layer is opaque by definition: a player's base texture is drawn as
                // a solid surface, and forcing its alpha to 255 stops a semi-transparent texel in an
                // HD skin (or a stray alpha channel) from making the body read as see-through.
                baseFaceCrops[b][f] = crop(atlas, box.baseUv(face), true);
                overlayFaceCrops[b][f] = crop(atlas, box.overlayUv(face), false);
            }
        }
    }

    /**
     * Slices one face's region out of the atlas and bakes a hard alpha test into it.
     *
     * <p>The alpha test is baked into the crop rather than applied as a separate {@code DST_IN} pass
     * over the shared canvas. That earlier pass multiplied the destination alpha wherever the mask
     * was transparent, so a hat/hair/jacket overlay's transparent texels punched holes straight
     * through the base face that had already been painted underneath — the character turned
     * see-through and the sides of the head vanished. Baking it here means a transparent texel is
     * simply never drawn, and the base layer beneath it survives.
     *
     * <p>An entirely transparent region (a classic skin's unused overlay) is dropped, so the overlay
     * pass does not draw invisible quads over every limb.
     */
    private static Bitmap crop(Bitmap atlas, SkinModel.Uv uv, boolean forceOpaque) {
        if (uv == null || !SkinModel.uvWithinAtlas(uv)) return null;
        if (uv.u + uv.w > atlas.getWidth() || uv.v + uv.h > atlas.getHeight()) return null;
        int w = uv.w;
        int h = uv.h;
        int[] pixels = new int[w * h];
        atlas.getPixels(pixels, 0, w, uv.u, uv.v, w, h);
        boolean anyOpaque = false;
        for (int i = 0; i < pixels.length; i++) {
            if (forceOpaque) {
                // Base layer: keep every texel, forced fully opaque. An HD skin's semi-transparent
                // texels (or a stray zero-alpha pixel) must not punch holes in the body.
                pixels[i] = pixels[i] | 0xFF000000;
                anyOpaque = true;
            } else if (SkinAlphaFilter.isOpaque(pixels[i])) {
                anyOpaque = true;
                // Quantise the surviving texel to full alpha so the bilinear sample at a shape's
                // edge cannot blend the transparent background back in.
                pixels[i] = pixels[i] | 0xFF000000;
            } else {
                pixels[i] = 0x00000000;
            }
        }
        // Nothing survives the overlay's alpha test: drop the region entirely.
        if (!anyOpaque) return null;
        // Always a standalone copy so {@link #release} can recycle it without touching the shared
        // atlas.
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }
}
