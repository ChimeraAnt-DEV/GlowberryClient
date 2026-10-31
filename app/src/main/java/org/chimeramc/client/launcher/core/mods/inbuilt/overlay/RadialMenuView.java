package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.cosmetics.PlayerSkinProvider;
import org.chimeramc.client.core.cosmetics.PreviewCharacterScene;
import org.chimeramc.client.core.cosmetics.PreviewRaster;
import org.chimeramc.client.core.cosmetics.PreviewScene;
import org.chimeramc.client.core.cosmetics.SkinModel;
import org.chimeramc.client.core.mods.inbuilt.overlay.RadialWheelLayout.Sector;
import org.chimeramc.client.ui.components.UiChip;
import org.chimeramc.client.ui.components.UiDraw;
import org.chimeramc.client.ui.components.UiIcons;
import org.chimeramc.client.ui.components.UiTokens;

import java.util.ArrayList;
import java.util.List;

/**
 * The radial mod wheel: four rounded annular sectors around the player's skin head, with floating
 * quick-toggle chips.
 *
 * <p>Rewritten to the "blueberry" visual language: a scrim, glass sectors with a rim, outline icons
 * over a caps label, a hub disc with the player's real skin head, and chips in three floating
 * clusters. Motion (open scale/alpha with a per-sector stagger, hover scale, press feedback) and
 * haptics are the reference feel; the geometry and the chip layout are pure
 * ({@link RadialWheelLayout}) and unit-tested.
 */
public class RadialMenuView extends View {

    /** Called when the player releases over a destination sector (or taps outside to close). */
    public interface Listener {
        void onSector(Sector sector);
        void onDismiss();
    }

    /** Called when a quick chip is tapped, with the module id it toggles. */
    public interface ChipToggle {
        void onChipToggled(String moduleId);
        boolean isChipOn(String moduleId);
    }

    /**
     * Called when a profile-driven sector is selected, with the entry that owns it.
     *
     * <p>Separate from {@link Listener} on purpose: {@code Listener} is the fixed four-destination
     * wheel's contract, while this carries an arbitrary profile entry (a module toggle, a screen, or
     * a sub-wheel). Keeping them apart means the default wheel's routing cannot be broken by a
     * profile feature it does not use.
     */
    public interface EntryListener {
        /** The player selected an entry. */
        void onEntry(WheelEntry entry);
        /** The player backed out of the open sub-wheel. */
        void onBack();
    }

    private static final Sector[] SECTORS =
            {Sector.MODS, Sector.CLOSET, Sector.SETTINGS, Sector.HUD};

    /**
     * One row of the enabled-module list.
     *
     * <p>The list shows the modules that are actually on, grouped top-to-bottom on the right, so the
     * wheel tells the player what is active without opening the module grid. Selecting a row toggles
     * its module, which is how the wheel switches things off again.
     */
    private static final class Chip {
        final String moduleId;
        final String label;

        Chip(String moduleId, String label) {
            this.moduleId = moduleId;
            this.label = label == null ? "" : label;
        }
    }

    private final Paint sectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path sectorPath = new Path();
    private final Path headClip = new Path();
    private final Rect srcHead = new Rect();
    private final RectF dstHead = new RectF();

    private final List<Chip> quickChips = new ArrayList<>();
    private final List<Chip> chips = new ArrayList<>();
    private Listener listener;
    private ChipToggle chipToggle;
    private EntryListener entryListener;
    private Sector highlighted = Sector.NONE;
    private int accent;

    // --- Profile mode ---------------------------------------------------------------------------
    //
    // The wheel is either the fixed four-destination wheel (activeProfile == null) or a
    // profile-driven one. The fixed wheel is left exactly as it was: the profile feature is
    // additive, so a regression here cannot break the shipped routing.
    private WheelEntry.Profile activeProfile;
    private final WheelNavigation navigation = new WheelNavigation();
    private WheelProfileStore profileStore;
    /** The profile-entry index the pointer or cursor is over, or -1. */
    private int highlightedEntry = -1;

    /** The controller cursor, non-null only while a pad is driving the wheel. */
    private CursorController cursor;
    /** The chip currently under the controller cursor, or -1. */
    private int cursorChip = -1;
    /** True while the cursor is visible (a touch hides it). */
    private boolean cursorVisible;

    /** Tracks release velocity so a flick toward a slice can commit it (R: flick-to-select). */
    private android.view.VelocityTracker flickTracker;

    private float density;

    private Bitmap skinHead;

    // The 3D head hub: a small software-rendered turntable of the player's real skin head.
    private final PreviewRaster headRaster = new PreviewRaster();
    private PreviewScene headScene;
    private int[] headAtlas;
    private Bitmap headBitmap;
    private float headYawDeg;
    private float headPitchDeg;
    private long headLastMs;

    // --- Motion state -------------------------------------------------------------------------

    private float openProgress;          // 0 closed .. 1 open
    private final float[] sectorScale = new float[]{1f, 1f, 1f, 1f};
    private ValueAnimator openAnimator;
    private ValueAnimator hoverAnimator;

    // --- Glass material (R2, R8, R9, R10, R12) -----------------------------------------------

    /** The glass renderer: the only place the API-level blur lives. */
    private final WheelGlassRenderer glassRenderer = new WheelGlassRenderer();
    /** The blurred backdrop layer (R3); supplied by the host, null when the wheel is a plain child. */
    private ImageView backdropLayer;
    /** The resolved material for this open, recomputed when the environment changes. */
    private RadialWheelMaterial.Resolved material = RadialWheelMaterial.resolve(
            RadialWheelMaterial.Tier.REGULAR, null);
    /** The backdrop capture the stack-blur draws under the fill (API 28-30 only). */
    private Bitmap backdropCapture;
    /** Supplies the device/environment state; the host installs it. */
    private MaterialEnvProvider materialEnvProvider;
    /** The most recent backdrop sample, used for the contrast floor (R9). */
    private int backdropSample = 0xFF070A0F;

    /** Supplies the environment the material resolves against. */
    public interface MaterialEnvProvider {
        RadialWheelMaterial.Env env();
    }

    // --- Cancel gesture (R11) -----------------------------------------------------------------

    /** True while a drag past the outer radius has armed the cancel. */
    private boolean cancelArmed;
    private float cancelOpacity = 1f;
    private float cancelScale = 1f;

    // --- Cached sector paths (R13) ------------------------------------------------------------

    /** The four resting sector paths, rebuilt only when the geometry changes. */
    private final Path[] cachedSectorPaths = new Path[4];
    private boolean sectorPathsValid;
    private float cachedPathOuter = -1f;
    private float cachedPathInner = -1f;

    // --- Cached profile-sector paths (R13) ----------------------------------------------------

    /**
     * The profile wheel's sector paths, one per possible sector (R13).
     *
     * <p>The profile wheel's count changes with the open profile, so it cannot reuse the fixed
     * four-path cache above. These are rebuilt only when the count or the ring's radii change —
     * during the open animation the radii move every frame, so the cache is inert then and takes
     * effect the moment the ring settles, which is the steady state the player actually sees.
     */
    private final Path[] cachedProfilePaths = new Path[WheelLayout.MAX_SECTORS];
    private boolean profilePathsValid;
    private int cachedProfileCount = -1;
    private float cachedProfileInner = -1f;
    private float cachedProfileOuter = -1f;

    private static final PathInterpolator FAST_OUT_SLOW_IN =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    /**
     * How square the segment caps are (R1): 1 is a full squircle, 0 a circular fillet. Slightly
     * under 1 keeps the caps from reading as sharp corners at small radii.
     */
    private static final float SQUIRCLE_SQUARENESS = 0.85f;

    /**
     * The backdrop disc's radius as a multiple of the ring's outer radius (R2): the sheet extends a
     * little past the ring so the segments read as sitting on a panel rather than floating.
     */
    private static final float BACKDROP_RADIUS_SCALE = 1.12f;

    public RadialMenuView(Context context) {
        this(context, null);
    }

    public RadialMenuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setClickable(true);
        setFocusable(false);
        density = getResources().getDisplayMetrics().density;
        accent = UiTokens.resolveAccent(context);
        buildChips();
        resolveSkinHead();
        labelPaint.setTextAlign(Paint.Align.CENTER);
        // R6: the segment label is 11sp at wght 500, one line. The weight is a variable-font axis,
        // not a separate typeface file: setting it on the paint is what makes the 500 real rather
        // than a comment.
        labelPaint.setTypeface(Typeface.create(Typeface.DEFAULT,
                RadialWheelMotion.LABEL_WEIGHT, false));
        labelPaint.setTextSize(RadialWheelMotion.LABEL_SP * density);
        applyLabelWeight(labelPaint, RadialWheelMotion.LABEL_WEIGHT);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Installs the environment provider and resolves the material (R2/R8/R10/R12).
     *
     * <p>The wheel owns no device state: the host supplies the API level, the accessibility flags,
     * the thermal reading and whether a session is running, and this resolves them once per open.
     */
    public void setMaterialEnvProvider(MaterialEnvProvider provider) {
        this.materialEnvProvider = provider;
        refreshMaterial();
    }

    /**
     * Hands the wheel the layer it paints the blurred backdrop on (R3).
     *
     * <p>The layer is a sibling behind the wheel in the overlay's {@code FrameLayout}. On API 31+
     * the blur is a {@code RenderEffect} on that layer, which is the only place it can go without
     * blurring the wheel's own content; on API 28-30 the layer stays hidden and the wheel blurs the
     * capture into its own canvas instead.
     */
    public void setBackdropLayer(ImageView layer) {
        this.backdropLayer = layer;
        refreshMaterial();
    }

    /** Re-resolves the glass material from the current environment and applies the blur. */
    public void refreshMaterial() {
        RadialWheelMaterial.Env env = materialEnvProvider == null
                ? new RadialWheelMaterial.Env() : materialEnvProvider.env();
        if (env == null) env = new RadialWheelMaterial.Env();
        material = RadialWheelMaterial.resolveForBackdrop(
                RadialWheelMaterial.tierFor(RadialWheelMaterial.Role.PANEL), env,
                UiTokens.TEXT_PRIMARY, backdropSample);
        applyBackdropLayer();
        invalidate();
    }

    /** Pushes the resolved material and the current capture onto the backdrop layer (R3). */
    private void applyBackdropLayer() {
        glassRenderer.applyBackdrop(backdropLayer, material, backdropCapture, density);
    }

    /**
     * Releases the backdrop layer's bitmap and effect (R3).
     *
     * <p>Called when the overlay detaches. The wheel itself does not own the layer's lifetime, so
     * this must not be left to garbage collection: the layer would keep the last capture alive, and
     * a capture the host has already recycled would leave the layer pointing at dead pixels.
     */
    public void releaseBackdropLayer() {
        glassRenderer.releaseBackdrop(backdropLayer);
        backdropCapture = null;
        backdropLayer = null;
    }

    /**
     * Hands the wheel a backdrop sample for the contrast floor (R9/R3).
     *
     * <p>The capture is the window the wheel sits over, already downscaled; the view only reads one
     * pixel to decide whether the labels need a heavier scrim. A null or recycled bitmap clears the
     * sample and the fixed floor applies.
     */
    public void setBackdropCapture(Bitmap capture) {
        if (backdropCapture != null && backdropCapture != capture && !backdropCapture.isRecycled()) {
            backdropCapture.recycle();
        }
        backdropCapture = capture;
        if (capture != null && !capture.isRecycled()
                && capture.getWidth() > 0 && capture.getHeight() > 0) {
            try {
                backdropSample = capture.getPixel(capture.getWidth() / 2, capture.getHeight() / 2);
            } catch (Throwable ignored) {
                backdropSample = 0xFF070A0F;
            }
        }
        refreshMaterial();
    }

    /** The resolved glass material; exposed so the host can assert the fallback ladder. */
    public RadialWheelMaterial.Resolved material() {
        return material;
    }

    public void setChipToggle(ChipToggle chipToggle) {
        this.chipToggle = chipToggle;
        invalidate();
    }

    public void setEntryListener(EntryListener entryListener) {
        this.entryListener = entryListener;
    }

    /**
     * Switches the wheel to a profile-driven one.
     *
     * <p>Loads the profiles, seeds the navigation at the root and draws the root's entries. Passing
     * null returns the wheel to the fixed four-destination layout, so a caller that does not want
     * profiles is unaffected.
     */
    public void setProfiles(Context context) {
        try {
            profileStore = new WheelProfileStore(context);
            navigation.reset(profileStore.getRootId());
            activeProfile = profileStore.rootProfile();
        } catch (Throwable t) {
            profileStore = null;
            activeProfile = null;
        }
        highlightedEntry = -1;
        invalidate();
    }

    /** True while the wheel is drawing a profile rather than the fixed four destinations. */
    public boolean isProfileMode() {
        return activeProfile != null;
    }

    /** The profile currently open (the top of the navigation stack). */
    WheelEntry.Profile currentProfile() {
        if (profileStore == null) return activeProfile;
        WheelEntry.Profile p = WheelProfileStore.findById(profileStore.load(), navigation.currentId());
        return p == null ? activeProfile : p;
    }

    /**
     * Opens a sub-wheel by profile id.
     *
     * @return true when the profile exists and the stack accepted it
     */
    boolean openSubWheel(String id) {
        if (profileStore == null || id == null) return false;
        if (WheelProfileStore.findById(profileStore.load(), id) == null) return false;
        if (!navigation.push(id)) return false;
        highlightedEntry = -1;
        playOpen();
        return true;
    }

    /**
     * Backs out one level.
     *
     * @return true when a sub-wheel was closed; false at the root, where the caller dismisses
     */
    boolean backOut() {
        if (!navigation.pop()) return false;
        highlightedEntry = -1;
        playOpen();
        return true;
    }

    private void buildChips() {
        // Left column: the quick module options the player can turn on from the wheel. Curated (a
        // short list of the modules that are useful mid-game), so the wheel stays readable.
        quickChips.clear();
        quickChips.add(new Chip("zoom", "Zoom"));
        quickChips.add(new Chip("fullbright", "Fullbright"));
        quickChips.add(new Chip("cps_display", "CPS"));
        quickChips.add(new Chip("fps_display", "FPS"));
        quickChips.add(new Chip("list_players", "Players"));
        quickChips.add(new Chip("debug_menu", "Debug"));
        // Right column: filled live from the enabled modules (see rebuildEnabledList).
        chips.clear();
    }

    /**
     * Rebuilds the enabled-module list from the live provider.
     *
     * <p>Order follows the provider's own list, so the wheel and the Mod Menu show the same order.
     * Modules are shown with their display name; a module that is registered but not enabled is not
     * listed, because the list's job is "what is on right now".
     */
    private void rebuildEnabledList() {
        chips.clear();
        try {
            // InbuiltModuleProvider.load needs an Activity; the wheel is only ever shown over one.
            android.app.Activity activity = null;
            android.content.Context ctx = getContext();
            while (ctx instanceof android.content.ContextWrapper) {
                if (ctx instanceof android.app.Activity) {
                    activity = (android.app.Activity) ctx;
                    break;
                }
                ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
            }
            if (activity == null) return;
            List<org.chimeramc.client.core.mods.inbuilt.UnifiedMod> mods =
                    org.chimeramc.client.core.mods.inbuilt.InbuiltModuleProvider
                            .load(activity);
            for (org.chimeramc.client.core.mods.inbuilt.UnifiedMod mod : mods) {
                if (mod == null || !mod.isEnabled()) continue;
                // Only modules that carry a name are listed; an extension without a label cannot be
                // drawn as a row.
                String name = mod.getName();
                if (name == null || name.trim().isEmpty()) continue;
                chips.add(new Chip(mod.getStableKey(), name));
            }
        } catch (Throwable ignored) {
            chips.clear();
        }
    }

    private void resolveSkinHead() {
        try {
            PlayerSkinProvider.SkinBitmap skin = PlayerSkinProvider.resolve(getContext());
            if (skin != null && skin.bitmap != null) {
                srcHead.set(8, 8, 16, 16);
                skinHead = skin.bitmap;
                if (skin.bitmap.getWidth() == SkinModel.ATLAS_SIZE
                        && skin.bitmap.getHeight() == SkinModel.ATLAS_SIZE) {
                    headAtlas = new int[SkinModel.ATLAS_SIZE * SkinModel.ATLAS_SIZE];
                    skin.bitmap.getPixels(headAtlas, 0, SkinModel.ATLAS_SIZE, 0, 0,
                            SkinModel.ATLAS_SIZE, SkinModel.ATLAS_SIZE);
                    headScene = new PreviewScene();
                    // HEAD ONLY: the hub is the player's head, not a full-body bust. The whole
                    // character belongs in the Closet preview; the wheel's hub is a small disc, so
                    // a body would be a tiny unreadable figure and would also make the "the head
                    // never moves" complaint impossible to judge. "head" is the box id
                    // PreviewCharacterScene keys on, so passing it selects just the head box (and
                    // its overlay) from the same authored skin.
                    PreviewCharacterScene.addCharacter(headScene, headAtlas, SkinModel.ATLAS_SIZE,
                            0f, 0f, "head");
                }
            }
        } catch (Throwable ignored) {
            skinHead = null;
            headScene = null;
        }
    }

    // --- Open/close animation -----------------------------------------------------------------

    /** Runs the open animation (scrim fade + ring scale/alpha with a stagger). */
    public void playOpen() {
        cancelAnimators();
        sectorPathsValid = false;
        profilePathsValid = false;
        openAnimator = ValueAnimator.ofFloat(0f, 1f);
        // R10: reduced motion runs a short linear transition rather than the spring.
        if (material != null && material.reducedMotion) {
            openAnimator.setDuration(RadialWheelMotion.REDUCED_MS);
            openAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        } else {
            openAnimator.setDuration(UiTokens.MOTION_DEFAULT_MS + 20);
            openAnimator.setInterpolator(FAST_OUT_SLOW_IN);
        }
        openAnimator.addUpdateListener(a -> {
            openProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        openAnimator.start();
    }

    /** Runs the close animation. */
    public void playClose() {
        cancelAnimators();
        sectorPathsValid = false;
        profilePathsValid = false;
        openAnimator = ValueAnimator.ofFloat(openProgress, 0f);
        openAnimator.setDuration(material != null && material.reducedMotion
                ? RadialWheelMotion.REDUCED_MS : 120);
        openAnimator.setInterpolator(material != null && material.reducedMotion
                ? new android.view.animation.LinearInterpolator() : FAST_OUT_SLOW_IN);
        openAnimator.addUpdateListener(a -> {
            openProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        openAnimator.start();
    }

    private void cancelAnimators() {
        if (openAnimator != null) openAnimator.cancel();
        if (hoverAnimator != null) hoverAnimator.cancel();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Re-resolve the skin so a change made since the wheel was last shown (a new import, an
        // applied pack, or the game's own skin once a provider is installed) appears immediately.
        refreshSkin();
        // Rebuild the enabled-module list so a toggle made while the wheel was closed shows now.
        rebuildEnabledList();
        // The head is deliberately NOT animated on a timer: it turns only when the player moves the
        // cursor, so the hub reads as a still portrait until it is interacted with. Reset the look
        // to forward-facing so a previous session leaves no stare. Nothing schedules a frame here,
        // which also means the view burns no frames while it sits open.
        headYawDeg = 0f;
        headPitchDeg = 0f;
        headLastMs = android.os.SystemClock.uptimeMillis();
        playOpen();
    }

    /**
     * Re-resolves the player's skin and rebuilds the hub head.
     *
     * <p>Called when the wheel is shown and when a skin change is detected, so the head in the
     * centre tracks the player's real skin without recreating the view. It reads through
     * {@link PlayerSkinProvider}, which prefers the game's live skin when a
     * {@link org.chimeramc.client.core.cosmetics.GameSkinSource} provider is installed.
     */
    public void refreshSkin() {
        resolveSkinHead();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAnimators();
        stopFlickTracker();
    }

    // --- Geometry helpers (shared by draw and touch) ----------------------------------------

    private float centerX() {
        return getWidth() / 2f;
    }

    private float centerY() {
        return getHeight() / 2f;
    }

    private float outerRadius() {
        return RadialWheelLayout.outerRadius(getWidth(), getHeight());
    }

    private float ringInnerRadius() {
        return RadialWheelLayout.ringInnerRadius(outerRadius());
    }

    private float hubRadius() {
        return RadialWheelLayout.hubRadius(outerRadius());
    }

    // --- Draw ---------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float cx = centerX();
        float cy = centerY();
        // R4: the ring springs from 0.86 to 1.0 on open; R11 dims and shrinks it while a cancel
        // drag is armed. Both factors come from the pure motion rules.
        float openScale = RadialWheelMotion.openScale(openProgress);
        float outer = outerRadius() * openScale * cancelScale;
        float inner = RadialWheelLayout.ringInnerRadius(outer);
        float hub = RadialWheelLayout.hubRadius(outer);
        int alpha = (int) (255 * Math.min(1f, openProgress * 1.4f) * cancelOpacity);

        // The scrim is the material's floor (R2), not an ad-hoc wash: an opaque layer was what left
        // a pale film over the game after the wheel closed, so the alpha stays tied to openProgress
        // and the floor is the minimum the labels need over a bright frame.
        int scrimAlpha = (int) (material.scrimAlpha * 255 * openProgress);
        if (scrimAlpha > 0) {
            canvas.drawColor(UiTokens.withAlpha(0xFF000000, scrimAlpha));
        }
        // R2/R3: the glass sheet behind the ring, drawn from the resolved material (the tier's fill,
        // its white tint, and the stack-blurred capture below API 31). Drawn before the sectors so
        // the ring sits on the sheet rather than beside it.
        glassRenderer.drawBackdrop(canvas, material, backdropCapture, cx, cy,
                outer * BACKDROP_RADIUS_SCALE, openProgress);

        // Sectors.
        if (activeProfile != null) {
            drawProfileSectors(canvas, cx, cy, inner, outer, alpha);
        } else {
            for (int i = 0; i < SECTORS.length; i++) {
                Sector s = SECTORS[i];
                float center = (float) RadialWheelLayout.sectorCenterDegrees(s);
                // Per-sector stagger: each starts 20ms after the previous.
                float local = Math.max(0f, Math.min(1f, (openProgress * 180f - i * 20f) / 120f));
                float scale = sectorScale[i] * (0.9f + 0.1f * local);
                float rOuter = outer * scale;
                float rInner = inner * scale;
                drawSector(canvas, cx, cy, rInner, rOuter, center - 45f, 90f,
                        s == highlighted, alpha, local, i);
            }
        }

        // Hub (the player's head only).
        drawHub(canvas, cx, cy, hub, alpha);

        // Icons + labels.
        if (activeProfile != null) {
            drawProfileContent(canvas, cx, cy, (inner + outer) / 2f, alpha);
        } else {
            drawSectorContent(canvas, cx, cy, (inner + outer) / 2f, alpha);
        }

        // The enabled-module list (right side) and the controller cursor.
        drawEnabledList(canvas, getWidth(), getHeight(), alpha);
        drawControllerCursor(canvas, alpha);
    }

    /** Draws the controller cursor as a clean pointer arrow with a small shadow. */
    private void drawControllerCursor(Canvas canvas, int alpha) {
        if (!isCursorVisible()) return;
        Paint shadow = iconPaint;
        Paint arrow = chipPaint;
        UiDraw.pointerCursor(canvas, shadow, arrow, cursor.x(), cursor.y(), density);
    }

    private void drawSector(Canvas canvas, float cx, float cy, float rInner, float rOuter,
                            float startDeg, float sweepDeg, boolean hover, int alpha,
                            float localProgress, int index) {
        float gapPx = RadialWheelLayout.GAP_DP * density;
        // Parallel-sided gap: inset the angular span by the half-gap at the mid radius.
        float midR = (rInner + rOuter) / 2f;
        float halfGapDeg = (float) RadialWheelLayout.angularHalfGapDegrees(midR, gapPx);
        float corner = RadialWheelLayout.SECTOR_CORNER_DP * density;

        // R13: the four resting paths are built once and reused. Only the highlighted sector (whose
        // radius is scaled by the hover/press animation) is rebuilt per frame, so a steady open
        // wheel allocates nothing and re-tessellates nothing.
        Path path;
        if (!hover && index >= 0 && index < cachedSectorPaths.length) {
            if (!sectorPathsValid || cachedPathOuter != rOuter || cachedPathInner != rInner) {
                buildSectorPathCache(cx, cy, rInner, rOuter, corner, halfGapDeg, sweepDeg);
            }
            if (cachedSectorPaths[index] == null) {
                cachedSectorPaths[index] = new Path();
                sectorPath.reset();
                buildRoundedAnnularSector(sectorPath, cx, cy, rInner, rOuter,
                        startDeg + halfGapDeg, sweepDeg - 2f * halfGapDeg, corner);
                cachedSectorPaths[index].set(sectorPath);
            }
            path = cachedSectorPaths[index];
        } else {
            sectorPath.reset();
            buildRoundedAnnularSector(sectorPath, cx, cy, rInner, rOuter,
                    startDeg + halfGapDeg, sweepDeg - 2f * halfGapDeg, corner);
            path = sectorPath;
        }

        // R2: the sector fill is the resolved material's fill and tint, so the glass thickness is
        // one decision for the whole wheel rather than a literal per draw call.
        int base = hover ? UiTokens.WHEEL_SEGMENT_HOVER : UiTokens.WHEEL_SEGMENT_BASE;
        int fill = UiTokens.withAlpha(base, material.fillAlpha);
        sectorPaint.setStyle(Paint.Style.FILL);
        sectorPaint.setColor(UiTokens.withAlpha(fill,
                (int) (((fill >>> 24) & 0xFF) * localProgress)));
        canvas.drawPath(path, sectorPaint);

        // The white tint catches the light on the sheet (R2).
        int tintAlpha = (int) (material.tint * 255 * localProgress);
        if (tintAlpha > 0) {
            rimPaint.setStyle(Paint.Style.FILL);
            rimPaint.setColor(UiTokens.withAlpha(0xFFFFFFFF, tintAlpha));
            canvas.drawPath(path, rimPaint);
        }

        rimPaint.setStyle(Paint.Style.STROKE);
        // R10: a solid tier (reduce transparency, no blur) needs a real 1dp border rather than the
        // faint glass hairline, because there is no blur to define the edge.
        boolean solidTier = material.tier == RadialWheelMaterial.Tier.SOLID;
        rimPaint.setStrokeWidth(Math.max(1f, density) * (solidTier ? 1.5f : 1f));
        rimPaint.setColor(UiTokens.withAlpha(hover ? accent : UiTokens.WHEEL_SEGMENT_RIM,
                (int) ((hover ? 0xAA : (solidTier ? 0x44 : 0x24)) * localProgress)));
        canvas.drawPath(path, rimPaint);
    }

    /** Builds the four resting sector paths for the current geometry (R13). */
    private void buildSectorPathCache(float cx, float cy, float rInner, float rOuter, float corner,
                                      float halfGapDeg, float sweepDeg) {
        for (int i = 0; i < SECTORS.length && i < cachedSectorPaths.length; i++) {
            if (cachedSectorPaths[i] == null) cachedSectorPaths[i] = new Path();
            float start = (float) RadialWheelLayout.sectorCenterDegrees(SECTORS[i]) - sweepDeg / 2f;
            cachedSectorPaths[i].reset();
            buildRoundedAnnularSector(cachedSectorPaths[i], cx, cy, rInner, rOuter,
                    start + halfGapDeg, sweepDeg - 2f * halfGapDeg, corner);
        }
        cachedPathOuter = rOuter;
        cachedPathInner = rInner;
        sectorPathsValid = true;
    }

    // --- Profile mode drawing -----------------------------------------------------------------

    /**
     * Draws the open profile's sectors.
     *
     * <p>The sector count follows the entry count (one to {@link WheelLayout#MAX_SECTORS}), and each
     * sector takes the entry's own colour when it has one, so a profile with four entries looks like
     * the fixed wheel while an eight-entry one is visibly denser. A single entry is drawn as a full
     * ring, matching {@link WheelLayout#sectorAt}'s "one sector owns the whole ring" rule.
     */
    private void drawProfileSectors(Canvas canvas, float cx, float cy, float inner, float outer,
                                    int alpha) {
        WheelEntry.Profile profile = currentProfile();
        if (profile == null) return;
        List<WheelEntry> entries = profile.safeEntries();
        int count = usableSectorCount(entries, outer);
        if (entries.isEmpty()) return;

        if (count == 1) {
            // A one-entry wheel is a ring: draw the annulus with no seam.
            drawRingSector(canvas, cx, cy, inner, outer, entries.get(0), 0 == highlightedEntry,
                    alpha, 1f);
            return;
        }

        double span = WheelLayout.sectorSpanDegrees(count);
        for (int i = 0; i < entries.size() && i < count; i++) {
            float local = Math.max(0f, Math.min(1f, (openProgress * 180f - i * 20f) / 120f));
            float scale = 0.9f + 0.1f * local;
            float start = (float) (WheelLayout.sectorCenterDegrees(i, count) - span / 2.0);
            drawSectorColored(canvas, cx, cy, inner * scale, outer * scale, start, (float) span,
                    i == highlightedEntry, alpha, local, entries.get(i).effectiveColor(accent), i);
        }
    }

    /** Draws a profile sector with a per-entry fill colour (the four-destination path's variant). */
    private void drawSectorColored(Canvas canvas, float cx, float cy, float rInner, float rOuter,
                                   float startDeg, float sweepDeg, boolean hover, int alpha,
                                   float localProgress, int entryColor, int index) {
        Path path = profileSectorPath(cx, cy, rInner, rOuter, startDeg, sweepDeg, index);

        int base = hover ? UiTokens.WHEEL_SEGMENT_HOVER : UiTokens.WHEEL_SEGMENT_BASE;
        // Blend the entry's colour into the glass fill so a coloured sector still reads as glass.
        int fill = blend(UiTokens.withAlpha(entryColor, 0x66), base);
        sectorPaint.setStyle(Paint.Style.FILL);
        sectorPaint.setColor(UiTokens.withAlpha(fill,
                (int) (((fill >>> 24) & 0xFF) * localProgress)));
        canvas.drawPath(path, sectorPaint);

        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1f, density));
        rimPaint.setColor(UiTokens.withAlpha(hover ? entryColor : UiTokens.WHEEL_SEGMENT_RIM,
                (int) ((hover ? 0xAA : 0x24) * localProgress)));
        canvas.drawPath(path, rimPaint);
    }

    /**
     * Returns the cached path for a profile sector, rebuilding the cache when the ring moves (R13).
     *
     * <p>A profile sector's path is a pure function of the count, the radii and its own index, so it
     * only needs rebuilding when one of those changes. During the open animation the radii change
     * every frame, so the cache misses then; once the ring settles it hits, and the per-frame path
     * construction — a full arc with two quadratic corner fillets per sector — stops happening.
     */
    private Path profileSectorPath(float cx, float cy, float rInner, float rOuter, float startDeg,
                                   float sweepDeg, int index) {
        int count = WheelLayout.clampSectorCount(currentProfile() == null ? 1
                : currentProfile().safeEntries().size());
        boolean cacheable = index >= 0 && index < cachedProfilePaths.length;
        if (!cacheable) {
            sectorPath.reset();
            buildProfileSector(sectorPath, cx, cy, rInner, rOuter, startDeg, sweepDeg);
            return sectorPath;
        }
        if (!profilePathsValid || cachedProfileCount != count
                || cachedProfileInner != rInner || cachedProfileOuter != rOuter) {
            rebuildProfilePaths(cx, cy, rInner, rOuter, count);
        }
        Path path = cachedProfilePaths[index];
        if (path == null) {
            path = new Path();
            cachedProfilePaths[index] = path;
            buildProfileSector(path, cx, cy, rInner, rOuter, startDeg, sweepDeg);
        }
        return path;
    }

    /** Rebuilds every profile sector path for the current count and radii (R13). */
    private void rebuildProfilePaths(float cx, float cy, float rInner, float rOuter, int count) {
        double span = WheelLayout.sectorSpanDegrees(count);
        for (int i = 0; i < count && i < cachedProfilePaths.length; i++) {
            if (cachedProfilePaths[i] == null) cachedProfilePaths[i] = new Path();
            cachedProfilePaths[i].reset();
            float start = (float) (WheelLayout.sectorCenterDegrees(i, count) - span / 2.0);
            buildProfileSector(cachedProfilePaths[i], cx, cy, rInner, rOuter, start, (float) span);
        }
        // Drop any stale paths from a larger count so a shrunk wheel cannot draw a ghost sector.
        for (int i = count; i < cachedProfilePaths.length; i++) {
            if (cachedProfilePaths[i] != null) cachedProfilePaths[i].reset();
        }
        cachedProfileCount = count;
        cachedProfileInner = rInner;
        cachedProfileOuter = rOuter;
        profilePathsValid = true;
    }

    /** Builds one rounded annular sector into {@code out}. */
    private void buildProfileSector(Path out, float cx, float cy, float rInner, float rOuter,
                                    float startDeg, float sweepDeg) {
        float gapPx = WheelLayout.GAP_DP * density;
        float midR = (rInner + rOuter) / 2f;
        float halfGapDeg = (float) WheelLayout.angularHalfGapDegrees(midR, gapPx);
        float corner = RadialWheelLayout.SECTOR_CORNER_DP * density;
        buildRoundedAnnularSector(out, cx, cy, rInner, rOuter,
                startDeg + halfGapDeg, sweepDeg - 2f * halfGapDeg, corner);
    }

    /** Draws the single-entry wheel as one full ring. */
    private void drawRingSector(Canvas canvas, float cx, float cy, float rInner, float rOuter,
                                WheelEntry entry, boolean hover, int alpha, float localProgress) {
        float corner = RadialWheelLayout.SECTOR_CORNER_DP * density;
        sectorPath.reset();
        buildRoundedAnnularSector(sectorPath, cx, cy, rInner, rOuter, 0f, 360f, corner);
        int base = hover ? UiTokens.WHEEL_SEGMENT_HOVER : UiTokens.WHEEL_SEGMENT_BASE;
        int fill = blend(UiTokens.withAlpha(entry.effectiveColor(accent), 0x66), base);
        sectorPaint.setStyle(Paint.Style.FILL);
        sectorPaint.setColor(UiTokens.withAlpha(fill,
                (int) (((fill >>> 24) & 0xFF) * localProgress)));
        canvas.drawPath(sectorPath, sectorPaint);
    }

    /** Draws each profile sector's label in its own colour, at the sector's centre. */
    private void drawProfileContent(Canvas canvas, float cx, float cy, float radius, int alpha) {
        WheelEntry.Profile profile = currentProfile();
        if (profile == null) return;
        List<WheelEntry> entries = profile.safeEntries();
        int count = usableSectorCount(entries, outerRadius());
        if (entries.isEmpty()) return;

        float labelSize = RadialWheelMotion.LABEL_SP * density;
        labelPaint.setTextSize(labelSize);
        for (int i = 0; i < entries.size() && i < count; i++) {
            double a = Math.toRadians(WheelLayout.sectorCenterDegrees(i, count));
            float px = cx + (float) (Math.cos(a) * radius);
            float py = cy + (float) (Math.sin(a) * radius);
            // A sub-wheel entry gets a chevron instead of an icon so "this opens another wheel" is
            // visible before it is selected.
            UiIcons.Icon icon = entries.get(i).isSubWheel() ? UiIcons.Icon.CHEVRON : iconFor(entries.get(i));
            UiIcons.draw(canvas, iconPaint, icon, px, py - labelSize * 0.6f,
                    RadialWheelMotion.ICON_DP * density,
                    UiTokens.withAlpha(entries.get(i).effectiveColor(accent), alpha), density);
            String label = RadialWheelMotion.ellipsise(entries.get(i).label);
            if (material.outlinedLabels) {
                Paint outline = glassRenderer.labelOutlinePaint(labelPaint, 0xFF000000, density);
                outline.setTextAlign(Paint.Align.CENTER);
                outline.setTextSize(labelSize);
                canvas.drawText(label, px, py + labelSize * 0.9f, outline);
            }
            labelPaint.setColor(UiTokens.withAlpha(UiTokens.TEXT_PRIMARY, alpha));
            canvas.drawText(label, px, py + labelSize * 0.9f, labelPaint);
        }
        // The open profile's name, so a nested wheel says where it is.
        if (profile.name != null && !profile.name.isEmpty() && !navigation.atRoot()) {
            labelPaint.setTextSize(UiTokens.TEXT_CAPTION * density);
            labelPaint.setColor(UiTokens.withAlpha(UiTokens.TEXT_SECONDARY, alpha));
            canvas.drawText(profile.name, cx, cy + RadialWheelLayout.hubRadius(outerRadius()) * 1.5f,
                    labelPaint);
        }
    }

    /** The icon for a profile entry, by action. */
    private static UiIcons.Icon iconFor(WheelEntry entry) {
        if (entry == null) return UiIcons.Icon.CUBE;
        switch (entry.action) {
            case OPEN_MODS: return UiIcons.Icon.CUBE;
            case OPEN_CLOSET: return UiIcons.Icon.HANGER;
            case OPEN_SETTINGS: return UiIcons.Icon.GEAR;
            case OPEN_HUD: return UiIcons.Icon.PENCIL;
            case OPEN_SUBWHEEL: return UiIcons.Icon.CHEVRON;
            default: return UiIcons.Icon.CUBE;
        }
    }

    /** Alpha-blends {@code over} onto {@code under} using the over's alpha. */
    private static int blend(int over, int under) {
        float a = ((over >>> 24) & 0xFF) / 255f;
        int r = (int) (((over >> 16) & 0xFF) * a + ((under >> 16) & 0xFF) * (1 - a));
        int g = (int) (((over >> 8) & 0xFF) * a + ((under >> 8) & 0xFF) * (1 - a));
        int b = (int) ((over & 0xFF) * a + (under & 0xFF) * (1 - a));
        int outA = Math.max((over >>> 24) & 0xFF, (under >>> 24) & 0xFF);
        return (outA << 24) | (r << 16) | (g << 8) | b;
    }

    /** Builds a rounded annular sector as one closed path (outer arc, fillet, inner arc, fillet). */
    private void buildRoundedAnnularSector(Path path, float cx, float cy, float rInner,
                                           float rOuter, float startDeg, float sweepDeg,
                                           float corner) {
        float r = Math.min(corner, Math.min((rOuter - rInner) / 2f, Math.abs(sweepDeg) / 4f));
        float oInset = (float) Math.toDegrees(r / Math.max(1f, rOuter));
        float iInset = (float) Math.toDegrees(r / Math.max(1f, rInner));
        float a0 = startDeg;
        float a1 = startDeg + sweepDeg;

        path.moveTo(polarX(cx, rOuter, a0 + oInset), polarY(cy, rOuter, a0 + oInset));
        arc(path, cx, cy, rOuter, a0 + oInset, a1 - oInset);
        // R1: the cap is a squircle, not a circular fillet. A cubic with its control points pulled
        // toward the corner by the squircle ratio gives the flat-sided cap the design asks for; a
        // quadTo fillet reads as a plain rounded rectangle.
        squircleCap(path, cx, cy, rOuter, rInner, a1, oInset, iInset, r, false);
        arc(path, cx, cy, rInner, a1 - iInset, a0 + iInset);
        squircleCap(path, cx, cy, rOuter, rInner, a0, oInset, iInset, r, true);
        path.close();
    }

    /**
     * Adds one squircle end cap from the outer edge to the inner edge (R1).
     *
     * <p>The control points sit along each edge's tangent, offset by the fillet radius scaled by
     * {@link RadialWheelMaterial#squircleControl}, which is what makes the cap flat-sided rather than
     * a quarter circle. {@code fromInner} selects the direction of travel.
     */
    private void squircleCap(Path path, float cx, float cy, float rOuter, float rInner, float angle,
                             float oInset, float iInset, float r, boolean fromInner) {
        float k = RadialWheelMaterial.squircleControl(SQUIRCLE_SQUARENESS) * r;
        float aOut = fromInner ? angle + oInset : angle - oInset;
        float aIn = fromInner ? angle - iInset : angle + iInset;
        float ox = polarX(cx, rOuter, aOut), oy = polarY(cy, rOuter, aOut);
        float ix = polarX(cx, rInner, aIn), iy = polarY(cy, rInner, aIn);
        // The tangent of a circle at angle a is (sin a, -cos a) when the angle decreases.
        double aRad = Math.toRadians(angle);
        float tx = (float) Math.sin(aRad), ty = (float) -Math.cos(aRad);
        float c1x = ox + tx * k, c1y = oy + ty * k;
        float c2x = ix - tx * k, c2y = iy - ty * k;
        if (fromInner) {
            path.cubicTo(c2x, c2y, c1x, c1y, ox, oy);
        } else {
            path.cubicTo(c1x, c1y, c2x, c2y, ix, iy);
        }
    }

    private static void arc(Path path, float cx, float cy, float radius, float from, float to) {
        RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        path.arcTo(oval, from, to - from);
    }

    private static float polarX(float cx, float radius, float deg) {
        return cx + (float) (Math.cos(Math.toRadians(deg)) * radius);
    }

    private static float polarY(float cy, float radius, float deg) {
        return cy + (float) (Math.sin(Math.toRadians(deg)) * radius);
    }

    private void drawHub(Canvas canvas, float cx, float cy, float radius, int alpha) {
        // R7: the hub is a squircle-headed disc; the head fills 40dp of it.
        hubPaint.setStyle(Paint.Style.FILL);
        hubPaint.setColor(UiTokens.withAlpha(UiTokens.WHEEL_HUB_FILL,
                (int) (((UiTokens.WHEEL_HUB_FILL >>> 24) & 0xFF) * (alpha / 255f))));
        float headRadius = Math.min(radius, RadialWheelMotion.HUB_HEAD_DP * density / 2f);
        canvas.drawCircle(cx, cy, radius, hubPaint);
        drawHead(canvas, cx, cy, headRadius);
        // R7: the active destination's name under the head, at 10sp and 60% white.
        String active = hubLabel();
        if (active != null && !active.isEmpty()) {
            labelPaint.setTextSize(RadialWheelMotion.HUB_LABEL_SP * density);
            labelPaint.setColor(UiTokens.withAlpha(0xFFFFFFFF,
                    (int) (RadialWheelMotion.HUB_LABEL_ALPHA * 255 * (alpha / 255f))));
            canvas.drawText(RadialWheelMotion.ellipsise(active), cx, cy + radius + 14f * density,
                    labelPaint);
            labelPaint.setTextSize(RadialWheelMotion.LABEL_SP * density);
        }
    }

    /** The active destination's label for the hub, or null when nothing is highlighted (R7). */
    private String hubLabel() {
        if (activeProfile != null) {
            WheelEntry.Profile profile = currentProfile();
            if (profile == null) return null;
            if (highlightedEntry >= 0 && highlightedEntry < profile.safeEntries().size()) {
                return profile.safeEntries().get(highlightedEntry).label;
            }
            return profile.name;
        }
        return RadialWheelLayout.isDestination(highlighted) ? sectorLabel(highlighted) : null;
    }

    private void drawSectorContent(Canvas canvas, float cx, float cy, float radius, int alpha) {
        float iconSize = RadialWheelMotion.ICON_DP * density;
        float labelSize = RadialWheelMotion.LABEL_SP * density;
        labelPaint.setTextSize(labelSize);
        // R6: one line, medium weight, ellipsised at the character budget.
        labelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        // Centre the icon+label block vertically in the ring band: the block is iconSize tall plus a
        // small gap plus the label, so its centre is the band radius and both halves sit around it.
        float gap = 4f * density;
        float blockHeight = iconSize + gap + labelSize;
        float blockTop = -blockHeight / 2f;
        float iconCenterY = blockTop + iconSize / 2f;
        float labelBaselineY = blockTop + iconSize + gap + labelSize * 0.8f;
        for (Sector s : SECTORS) {
            double a = Math.toRadians(RadialWheelLayout.sectorCenterDegrees(s));
            float px = cx + (float) (Math.cos(a) * radius);
            float py = cy + (float) (Math.sin(a) * radius);
            UiIcons.draw(canvas, iconPaint, iconFor(s), px, py + iconCenterY, iconSize,
                    UiTokens.withAlpha(UiTokens.TEXT_PRIMARY, alpha), density);
            String label = RadialWheelMotion.ellipsise(sectorLabel(s));
            // R10: high contrast outlines the label so it stays readable on a bright frame without
            // thickening the whole sheet.
            if (material.outlinedLabels) {
                Paint outline = glassRenderer.labelOutlinePaint(labelPaint, 0xFF000000, density);
                outline.setTextAlign(Paint.Align.CENTER);
                outline.setTextSize(labelSize);
                canvas.drawText(label, px, py + labelBaselineY, outline);
            }
            labelPaint.setColor(UiTokens.withAlpha(UiTokens.TEXT_PRIMARY, alpha));
            canvas.drawText(label, px, py + labelBaselineY, labelPaint);
        }
    }

    private static UiIcons.Icon iconFor(Sector s) {
        switch (s) {
            case MODS: return UiIcons.Icon.CUBE;
            case HUD: return UiIcons.Icon.PENCIL;
            case CLOSET: return UiIcons.Icon.HANGER;
            case SETTINGS: return UiIcons.Icon.GEAR;
            default: return UiIcons.Icon.CUBE;
        }
    }

    private void drawHead(Canvas canvas, float cx, float cy, float radius) {
        if (headScene != null && headAtlas != null) {
            if (drawHead3D(canvas, cx, cy, radius)) return;
        }
        if (skinHead == null || skinHead.isRecycled()) {
            // No skin available: draw a neutral accent disc rather than a glyph or emoji.
            hubPaint.setColor(UiTokens.ACCENT_900);
            canvas.drawCircle(cx, cy, radius * 0.8f, hubPaint);
            return;
        }
        dstHead.set(cx - radius * 0.72f, cy - radius * 0.72f,
                cx + radius * 0.72f, cy + radius * 0.72f);
        headClip.reset();
        headClip.addCircle(cx, cy, radius * 0.9f, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(headClip);
        headPaint.setFilterBitmap(false);
        canvas.drawBitmap(skinHead, srcHead, dstHead, headPaint);
        canvas.restoreToCount(save);
    }

    /** Renders the 3D head turntable into the hub; false when it cannot allocate a bitmap. */
    private boolean drawHead3D(Canvas canvas, float cx, float cy, float radius) {
        int size = Math.max(24, (int) (radius * 1.7f));
        if (headBitmap == null || headBitmap.getWidth() != size) {
            try {
                headBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            } catch (Throwable t) {
                headBitmap = null;
                return false;
            }
        }
        headRaster.prepare(size, size, 0x00000000);
        // The scene holds ONLY the head box (see resolveSkinHead), so the camera targets the head's
        // own centre (model y = 28 for the 8x8x8 head whose bottom sits at 24) rather than the chest
        // of a bust. A tight margin then fills the disc with the face instead of leaving the head
        // adrift in a large empty circle.
        SkinModel.Camera cam = new SkinModel.Camera(headYawDeg, headPitchDeg,
                SkinModel.CAMERA_DISTANCE, 0f, 28f, 0f);
        PreviewScene.Fit fit = headScene.fit(cam, size, size, 0.06f);
        headScene.render(headRaster, cam, fit);
        headBitmap.setPixels(headRaster.pixels(), 0, size, 0, 0, size, size);
        headClip.reset();
        headClip.addCircle(cx, cy, radius * 0.94f, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(headClip);
        headPaint.setFilterBitmap(true);
        dstHead.set(cx - radius * 0.94f, cy - radius * 0.94f,
                cx + radius * 0.94f, cy + radius * 0.94f);
        canvas.drawBitmap(headBitmap, null, dstHead, headPaint);
        canvas.restoreToCount(save);
        return true;
    }

    /**
     * Draws both module columns.
     *
     * <p>Left: the quick options the player can turn on, drawn left-anchored. Right: the modules that
     * are actually enabled, right-anchored. Both are compact columns (one row pitch apart), so they
     * read as grouped lists rather than the old widely-spaced clusters. A row's status dot shows
     * whether its module is currently on.
     */
    private void drawEnabledList(Canvas canvas, int w, int h, int alpha) {
        chipTextPaint.setTextSize(UiChip.LABEL_SP * density);
        if (!quickChips.isEmpty()) {
            float[][] centers = RadialWheelLayout.moduleListCenters(
                    w, h, quickChips.size(), density, false);
            for (int i = 0; i < quickChips.size() && i < centers.length; i++) {
                Chip chip = quickChips.get(i);
                float labelWidth = chipTextPaint.measureText(chip.label);
                boolean on = chipToggle != null && chipToggle.isChipOn(chip.moduleId);
                UiChip.draw(canvas, chipPaint, chipTextPaint, centers[i][0], centers[i][1],
                        labelWidth, chip.label, UiTokens.withAlpha(accent, alpha), on, density,
                        UiChip.Align.CENTER);
            }
        }
        if (!chips.isEmpty()) {
            float[][] centers = RadialWheelLayout.moduleListCenters(
                    w, h, chips.size(), density, true);
            for (int i = 0; i < chips.size() && i < centers.length; i++) {
                Chip chip = chips.get(i);
                float labelWidth = chipTextPaint.measureText(chip.label);
                boolean on = chipToggle != null && chipToggle.isChipOn(chip.moduleId);
                UiChip.draw(canvas, chipPaint, chipTextPaint, centers[i][0], centers[i][1],
                        labelWidth, chip.label, UiTokens.withAlpha(accent, alpha), on, density,
                        UiChip.Align.RIGHT);
            }
        }
    }

    /**
     * The (list, row) hit under a point, or null.
     *
     * <p>Both columns are hit-tested so a tap selects whichever row is under it; the left column is
     * left-anchored, so its hit box hugs the chip's left edge.
     */
    int[] chipHit(float x, float y, int w, int h) {
        if (chipAtIn(quickChips, x, y, w, h, false) >= 0) {
            return new int[] {0, chipAtIn(quickChips, x, y, w, h, false)};
        }
        int right = chipAtIn(chips, x, y, w, h, true);
        return right >= 0 ? new int[] {1, right} : null;
    }

    private int chipAtIn(List<Chip> list, float x, float y, int w, int h, boolean fromRight) {
        if (list.isEmpty()) return -1;
        float[][] centers = RadialWheelLayout.moduleListCenters(w, h, list.size(), density, fromRight);
        chipTextPaint.setTextSize(UiChip.LABEL_SP * density);
        for (int i = 0; i < list.size() && i < centers.length; i++) {
            float cw = UiChip.width(chipTextPaint.measureText(list.get(i).label), density);
            float ch = UiChip.HEIGHT_DP * density;
            float left = fromRight ? centers[i][0] - cw : centers[i][0] - cw / 2f;
            float right = fromRight ? centers[i][0] : centers[i][0] + cw / 2f;
            if (x >= left && x <= right && Math.abs(y - centers[i][1]) <= ch / 2f) {
                return i;
            }
        }
        return -1;
    }

    /** The right-column index under a point, or -1 (kept for the controller cursor's highlight). */
    int chipAt(float x, float y, int w, int h) {
        return chipAtIn(chips, x, y, w, h, true);
    }

    private static String sectorLabel(Sector s) {
        switch (s) {
            case MODS: return "MODS";
            case CLOSET: return "CUSTOMIZE";
            case SETTINGS: return "SETTINGS";
            case HUD: return "HUD";
            default: return "";
        }
    }

    // --- Controller cursor ------------------------------------------------------------------

    /**
     * Enables controller-cursor mode: a cursor appears at the hub and the stick drives it.
     * Called when the wheel is opened by the controller bind.
     *
     * <p>The wheel is enabled from a post on the overlay window, which can run before the view has
     * been measured. Creating the cursor with a 1x1 viewport there clamped it to a single pixel and
     * it could never move — the "controller cursor does not work" report. The viewport is therefore
     * kept in step with the real size in {@link #onSizeChanged}, and the cursor is centred the first
     * time a usable size arrives rather than being pinned at the top-left corner.
     */
    public void enableControllerCursor() {
        cursorVisible = true;
        applyCursorViewport(true);
        invalidate();
    }

    /**
     * Creates or updates the cursor for the current view size.
     *
     * @param centreNow true to recentre the cursor (a fresh open); false to only fix the viewport
     *                  (a rotation/resize must not yank the cursor back to the middle)
     */
    private void applyCursorViewport(boolean centreNow) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return; // Not laid out yet; onSizeChanged will run.
        if (cursor == null) {
            cursor = new CursorController(w, h, density);
        } else {
            cursor.setViewport(w, h);
            if (centreNow) cursor.centre();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // The first real size arrives after the overlay's post; without this the cursor kept the
        // 1x1 viewport it was created with and never moved.
        if (cursor != null) {
            cursor.setViewport(Math.max(1, w), Math.max(1, h));
            if (oldw == 0 || oldh == 0) cursor.centre();
        }
        invalidate();
    }

    /** True when the view is laid out far enough for the cursor to move meaningfully. */
    private boolean cursorViewportUsable() {
        return getWidth() > 1 && getHeight() > 1;
    }

    /** Hides the cursor (a touch, or the wheel closing). */
    public void disableControllerCursor() {
        cursorVisible = false;
        cursorChip = -1;
        invalidate();
    }

    public boolean isCursorVisible() {
        return cursorVisible && cursor != null;
    }

    /**
     * Feeds one frame of controller input.
     *
     * @return true when the wheel consumed it (so the caller must not forward it to the game)
     */
    public boolean handleControllerStick(float rawX, float rawY, float dt, boolean precision,
                                         boolean fast, float deadZone, float curveExponent) {
        // A stick frame can arrive before the overlay's post enabled the cursor (or before layout),
        // so create it here too rather than returning early — the earlier early-return is part of
        // why the cursor never appeared.
        if (cursor == null) {
            applyCursorViewport(true);
            if (cursor == null) return false; // Still not laid out; nothing sensible to move.
        }
        cursorVisible = true;
        float[] shaped = new float[2];
        CursorController.shape(rawX, rawY, deadZone, curveExponent, shaped);
        // Android's stick Y is negative when pushed up, which matches screen Y growing down (an up
        // push must reduce the cursor's y). Pass the shaped axes through unchanged.
        cursor.setMagnetTarget(false, 0f, 0f);
        cursor.update(shaped[0], shaped[1], dt, precision, fast,
                CursorController.DEFAULT_MAX_SPEED_DP);
        // The head looks at the cursor, not at the stick vector: the hub stays a still portrait
        // until the player moves the cursor, then the head turns to face where the cursor now is.
        // The same rule runs for touch (see onTouchEvent), so both inputs turn it consistently.
        updateHeadLook(cursor.x(), cursor.y(), dt);
        // Magnetism: ease toward the sector icon or chip under the cursor.
        applyCursorMagnetism();
        updateCursorHighlight();
        invalidate();
        return true;
    }

    /**
     * Points the hub head at the cursor's current position, eased by one frame.
     *
     * <p>Called from every input path (the right stick and touch), so the head is still until the
     * player moves the cursor, and then turns to face where the cursor is — it never keeps spinning
     * after the cursor stops. Yaw/pitch come from {@link RadialHeadTurn#targetLook} on the cursor's
     * offset from the hub centre, so the exact position (not a velocity) drives the look.
     *
     * @param cursorX   cursor/screen x within the view, pixels
     * @param cursorY   cursor/screen y within the view, pixels
     * @param dtSeconds elapsed time for this input frame (clamped by the caller)
     */
    void updateHeadLook(float cursorX, float cursorY, float dtSeconds) {
        float cx = centerX();
        float cy = centerY();
        float maxH = hubRadius() * 0.9f;
        float maxV = hubRadius() * 0.9f;
        float[] target = RadialHeadTurn.targetLook(cursorX - cx, cursorY - cy, maxH, maxV);
        float[] eased = RadialHeadTurn.stepTowards(
                headYawDeg, headPitchDeg, target[0], target[1], dtSeconds);
        headYawDeg = eased[0];
        headPitchDeg = eased[1];
        invalidate();
    }

    private void applyCursorMagnetism() {
        float cx = centerX();
        float cy = centerY();
        float outer = outerRadius();
        float inner = RadialWheelLayout.ringInnerRadius(outer);
        float radius = (inner + outer) / 2f;
        if (activeProfile != null) {
            int entry = profileEntryAt(cursor.x(), cursor.y());
            if (entry >= 0) {
                int count = WheelLayout.clampSectorCount(currentProfile().safeEntries().size());
                double a = Math.toRadians(WheelLayout.sectorCenterDegrees(entry, count));
                cursor.setMagnetTarget(true, cx + (float) (Math.cos(a) * radius),
                        cy + (float) (Math.sin(a) * radius));
            } else {
                cursor.setMagnetTarget(false, 0f, 0f);
            }
            return;
        }
        Sector s = RadialWheelLayout.sectorAtLinear(
                cursor.x() - cx, cursor.y() - cy, inner, outer, RadialWheelLayout.GAP_DP * density);
        if (RadialWheelLayout.isDestination(s)) {
            double a = Math.toRadians(RadialWheelLayout.sectorCenterDegrees(s));
            float ix = cx + (float) (Math.cos(a) * radius);
            float iy = cy + (float) (Math.sin(a) * radius);
            cursor.setMagnetTarget(true, ix, iy);
        } else {
            cursor.setMagnetTarget(false, 0f, 0f);
        }
    }

    private void updateCursorHighlight() {
        float cx = centerX();
        float cy = centerY();
        float outer = outerRadius();
        float inner = RadialWheelLayout.ringInnerRadius(outer);
        if (activeProfile != null) {
            highlightedEntry = profileEntryAt(cursor.x(), cursor.y());
        } else {
            Sector s = RadialWheelLayout.sectorAtLinear(
                    cursor.x() - cx, cursor.y() - cy, inner, outer,
                    RadialWheelLayout.GAP_DP * density);
            setHighlight(s);
        }
        int[] hit = chipHit(cursor.x(), cursor.y(), getWidth(), getHeight());
        cursorChip = hit != null && hit[0] == 1 ? hit[1] : -1;
    }

    /**
     * Whether anything is highlighted that a release could commit.
     *
     * <p>Used by the hold-to-open rule: a release with nothing highlighted dismisses the wheel
     * rather than activating whatever happens to be under a stationary cursor.
     */
    public boolean hasSelection() {
        if (cursorChip >= 0) return true;
        if (activeProfile != null) return highlightedEntry >= 0;
        return RadialWheelLayout.isDestination(highlighted);
    }

    /**
     * The SELECT button: activates the chip under the cursor if there is one, else the sector.
     *
     * @return true when something was activated
     */
    public boolean controllerSelect() {
        if (cursor == null) return false;
        int[] hit = chipHit(cursor.x(), cursor.y(), getWidth(), getHeight());
        if (hit != null) {
            List<Chip> list = hit[0] == 0 ? quickChips : chips;
            int index = hit[1];
            if (index >= 0 && index < list.size()) {
                if (chipToggle != null) chipToggle.onChipToggled(list.get(index).moduleId);
                applyHaptic(RadialWheelMotion.hapticFor(false, false, true));
                // The right column is the enabled set, so a toggle must rebuild it immediately.
                rebuildEnabledList();
                cursorChip = -1;
                invalidate();
                return true;
            }
        }
        if (activeProfile != null) {
            int entry = profileEntryAt(cursor.x(), cursor.y());
            if (entry >= 0) {
                activateProfileEntry(entry);
                return true;
            }
            return false;
        }
        float cx = centerX();
        float cy = centerY();
        float outer = outerRadius();
        float inner = RadialWheelLayout.ringInnerRadius(outer);
        Sector s = RadialWheelLayout.sectorAtLinear(
                cursor.x() - cx, cursor.y() - cy, inner, outer, RadialWheelLayout.GAP_DP * density);
        if (listener != null && RadialWheelLayout.isDestination(s)) {
            // R5: a committed selection gets the medium pulse.
            applyHaptic(RadialWheelMotion.hapticFor(false, false, true));
            listener.onSector(s);
            return true;
        }
        return false;
    }

    /**
     * The BACK button.
     *
     * <p>In profile mode this backs out of a sub-wheel first, and only dismisses at the root — the
     * same "Back leaves the current level" rule the launcher's own screens use.
     */
    public boolean controllerBack() {
        if (activeProfile != null && backOut()) {
            applyHaptic(RadialWheelMotion.hapticFor(false, true, false));
            return true;
        }
        if (entryListener != null && activeProfile != null) {
            entryListener.onBack();
            return true;
        }
        if (listener != null) {
            listener.onDismiss();
            return true;
        }
        return false;
    }

    /** The PIN button: pin/unpin the hovered chip's module. Returns true when a chip was hit. */
    public boolean controllerPinHoveredChip() {
        int[] hit = cursor == null ? null : chipHit(cursor.x(), cursor.y(), getWidth(), getHeight());
        if (hit != null) {
            List<Chip> list = hit[0] == 0 ? quickChips : chips;
            int index = hit[1];
            if (index >= 0 && index < list.size()) {
                if (chipToggle != null) chipToggle.onChipToggled(list.get(index).moduleId);
                applyHaptic(RadialWheelMotion.hapticFor(false, false, true));
                rebuildEnabledList();
                cursorChip = -1;
                invalidate();
                return true;
            }
        }
        return false;
    }

    /** Moves the cursor to a sector's icon (D-pad focus mode). Hides the free cursor. */
    public void focusSector(Sector s) {
        if (!RadialWheelLayout.isDestination(s)) return;
        float cx = centerX();
        float cy = centerY();
        float outer = outerRadius();
        float inner = RadialWheelLayout.ringInnerRadius(outer);
        float radius = (inner + outer) / 2f;
        double a = Math.toRadians(RadialWheelLayout.sectorCenterDegrees(s));
        if (cursor == null) {
            cursor = new CursorController(Math.max(1, getWidth()), Math.max(1, getHeight()), density);
        }
        cursor.setPosition(cx + (float) (Math.cos(a) * radius),
                cy + (float) (Math.sin(a) * radius));
        cursorVisible = false;
        setHighlight(s);
        invalidate();
    }

    /** The D-pad left/right: step through the sectors (or the open profile's entries). */
    public void focusNextSector(int direction) {
        if (activeProfile != null) {
            WheelEntry.Profile profile = currentProfile();
            if (profile == null || profile.safeEntries().isEmpty()) return;
            int count = WheelLayout.clampSectorCount(profile.safeEntries().size());
            int current = highlightedEntry < 0 ? 0 : highlightedEntry;
            highlightedEntry = ((current + direction) % count + count) % count;
            applyHaptic(RadialWheelMotion.hapticFor(false, true, false));
            invalidate();
            return;
        }
        Sector[] order = {Sector.MODS, Sector.CLOSET, Sector.SETTINGS, Sector.HUD};
        Sector current = highlighted;
        int idx = 0;
        for (int i = 0; i < order.length; i++) if (order[i] == current) idx = i;
        int next = ((idx + direction) % order.length + order.length) % order.length;
        focusSector(order[next]);
        applyHaptic(RadialWheelMotion.hapticFor(false, true, false));
    }

    /** The LB/RB: cycle the chips, selecting the module under the cursor. */
    public void cycleChip(int direction) {
        if (chips.isEmpty()) return;
        int next = cursorChip < 0 ? 0 : (cursorChip + direction + chips.size()) % chips.size();
        cursorChip = next;
        invalidate();
    }

    // --- Touch ------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // A touch hides the controller cursor: the two input methods never fight.
        if (cursorVisible) {
            disableControllerCursor();
        }
        float cx = centerX();
        float cy = centerY();
        float outer = outerRadius();
        float inner = ringInnerRadius();
        float gapPx = RadialWheelLayout.GAP_DP * density;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (handleChipTap(event.getX(), event.getY(), getWidth(), getHeight())) {
                    return true;
                }
                setCancelArmed(false);
                startFlickTracker(event);
                if (activeProfile != null) {
                    highlightedEntry = profileEntryAt(event.getX(), event.getY());
                } else {
                    setHighlight(RadialWheelLayout.sectorAtLinear(
                            event.getX() - cx, event.getY() - cy, inner, outer, gapPx));
                }
                // Touch turns the head the same way the stick does: look at the pointer.
                updateHeadLook(event.getX(), event.getY(), 1f / 60f);
                return true;
            case MotionEvent.ACTION_MOVE:
                // R11: a drag well past the outer radius arms the cancel gesture; the ring dims and
                // shrinks so the player can see the release will dismiss.
                float dist = (float) Math.hypot(event.getX() - cx, event.getY() - cy);
                setCancelArmed(RadialWheelMotion.isCancelArmed(dist, outer, density));
                if (cancelArmed) {
                    updateHeadLook(event.getX(), event.getY(), 1f / 60f);
                    return true;
                }
                if (flickTracker != null) flickTracker.addMovement(event);
                if (activeProfile != null) {
                    highlightedEntry = profileEntryAt(event.getX(), event.getY());
                    invalidate();
                } else {
                    setHighlight(RadialWheelLayout.sectorAtLinear(
                            event.getX() - cx, event.getY() - cy, inner, outer, gapPx));
                }
                updateHeadLook(event.getX(), event.getY(), 1f / 60f);
                return true;
            case MotionEvent.ACTION_UP:
                if (cancelArmed) {
                    stopFlickTracker();
                    setCancelArmed(false);
                    highlighted = Sector.NONE;
                    highlightedEntry = -1;
                    animateHover(null);
                    invalidate();
                    if (listener != null) listener.onDismiss();
                    return true;
                }
                // A flick toward a slice commits it even if the pointer stopped short of the ring;
                // a slow release falls through to the sector under the pointer, which is the click
                // path. Profile wheels resolve by the entry under the pointer, so the flick only
                // applies to the fixed wheel, where the slices are the four fixed destinations.
                Sector flicked = activeProfile == null ? takeFlickSector(outer, gapPx) : Sector.NONE;
                if (activeProfile != null) {
                    int hit = profileEntryAt(event.getX(), event.getY());
                    highlightedEntry = -1;
                    invalidate();
                    if (hit >= 0) {
                        activateProfileEntry(hit);
                    } else if (listener != null) {
                        listener.onDismiss();
                    }
                    return true;
                }
                Sector s = flicked != Sector.NONE ? flicked : RadialWheelLayout.sectorAtLinear(
                        event.getX() - cx, event.getY() - cy, inner, outer, gapPx);
                highlighted = Sector.NONE;
                animateHover(null);
                invalidate();
                if (listener != null) {
                    if (RadialWheelLayout.isDestination(s)) {
                        // R5: a committed selection gets the medium pulse, never on open.
                        applyHaptic(RadialWheelMotion.hapticFor(false, false, true));
                        listener.onSector(s);
                    } else {
                        listener.onDismiss();
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                stopFlickTracker();
                setCancelArmed(false);
                highlighted = Sector.NONE;
                highlightedEntry = -1;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** Starts tracking a gesture's velocity for the flick test. */
    private void startFlickTracker(MotionEvent event) {
        if (flickTracker == null) {
            flickTracker = android.view.VelocityTracker.obtain();
        } else {
            flickTracker.clear();
        }
        flickTracker.addMovement(event);
    }

    /** Stops and recycles the flick tracker. */
    private void stopFlickTracker() {
        if (flickTracker != null) {
            flickTracker.recycle();
            flickTracker = null;
        }
    }

    /**
     * The sector a release flick selects, or {@link Sector#NONE} for a slow release.
     *
     * <p>The velocity is read before the tracker is recycled so the rule sees the real release
     * speed. Everything else is {@link RadialWheelLayout#flickSector}, which owns the threshold and
     * the gap so the flick and the pointer hit test cannot disagree.
     */
    private Sector takeFlickSector(float outer, float gapPx) {
        if (flickTracker == null) return Sector.NONE;
        flickTracker.computeCurrentVelocity(1000);
        float vx = flickTracker.getXVelocity();
        float vy = flickTracker.getYVelocity();
        Sector sector = RadialWheelLayout.flickSector(vx, vy, outer, gapPx);
        stopFlickTracker();
        return sector;
    }

    /** The profile-entry index under a point, or -1. */
    private int profileEntryAt(float x, float y) {
        WheelEntry.Profile profile = currentProfile();
        if (profile == null) return -1;
        List<WheelEntry> entries = profile.safeEntries();
        if (entries.isEmpty()) return -1;
        float outer = outerRadius();
        float inner = ringInnerRadius();
        int count = usableSectorCount(entries, outer);
        return WheelLayout.sectorAt(x - centerX(), y - centerY(), inner, outer,
                WheelLayout.GAP_DP * density, count);
    }

    /**
     * The sector count the wheel will actually draw and hit-test (R1).
     *
     * <p>Delegates to {@link RadialWheelMotion#segmentsFor}, which owns the rule, so the draw path
     * and the hit test cannot compute different counts. This is the view's only involvement in the
     * decision: it supplies the entry count and the measured radius.
     */
    private int usableSectorCount(List<WheelEntry> entries, float outerRadius) {
        return RadialWheelMotion.segmentsFor(entries == null ? 0 : entries.size(),
                outerRadius, density);
    }

    /**
     * Performs a profile entry's action.
     *
     * <p>A sub-wheel is opened in place (the wheel is navigation, not a destination); anything else
     * is handed to the {@link EntryListener}, which owns the launcher-side routing. A sub-wheel
     * entry whose target no longer resolves is refused rather than silently doing nothing, so the
     * caller can dismiss.
     */
    private void activateProfileEntry(int index) {
        WheelEntry.Profile profile = currentProfile();
        if (profile == null) return;
        List<WheelEntry> entries = profile.safeEntries();
        if (index < 0 || index >= entries.size()) return;
        WheelEntry entry = entries.get(index);
        if (entry.isSubWheel() && openSubWheel(entry.target)) {
            applyHaptic(RadialWheelMotion.hapticFor(false, true, false));
            return;
        }
        applyHaptic(RadialWheelMotion.hapticFor(false, false, true));
        if (entryListener != null) entryListener.onEntry(entry);
    }

    /** Toggles the module row under the pointer, if any; returns true when a row was hit. */
    private boolean handleChipTap(float x, float y, int w, int h) {
        int[] hit = chipHit(x, y, w, h);
        if (hit == null) return false;
        List<Chip> list = hit[0] == 0 ? quickChips : chips;
        int index = hit[1];
        if (index < 0 || index >= list.size()) return false;
        if (chipToggle != null) chipToggle.onChipToggled(list.get(index).moduleId);
        applyHaptic(RadialWheelMotion.hapticFor(false, false, true));
        // The right column reflects what is enabled, so a toggle (either direction) must rebuild it.
        rebuildEnabledList();
        invalidate();
        return true;
    }

    private void setHighlight(Sector sector) {
        if (sector == highlighted) return;
        boolean wasDestination = RadialWheelLayout.isDestination(highlighted);
        highlighted = sector;
        if (RadialWheelLayout.isDestination(sector) && !wasDestination) {
            // R5: a light tick when the pointer crosses into a new segment.
            applyHaptic(RadialWheelMotion.hapticFor(false, true, false));
        }
        animateHover(sector);
        invalidate();
    }

    /**
     * Applies the wheel's haptic vocabulary (R5).
     *
     * <p>The mapping from the pure {@link RadialWheelMotion.Haptic} to the platform constant lives
     * here, so the "which signal" decision is testable and this is the only place that touches
     * {@code HapticFeedbackConstants}.
     */
    private void applyHaptic(RadialWheelMotion.Haptic haptic) {
        switch (haptic) {
            case COMMIT:
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                break;
            case TICK:
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                break;
            case NONE:
            default:
                break;
        }
    }

    /**
     * Applies a variable-font weight axis to a paint (R6).
     *
     * <p>{@code Paint#setFontVariationSettings} is API 26, below this project's minSdk, so it needs
     * no guard; the {@code try} is for a device whose default font has no {@code wght} axis, where
     * the call is a no-op the framework may still reject. A missing axis must not cost the label.
     */
    private static void applyLabelWeight(Paint paint, int weight) {
        if (paint == null) return;
        try {
            paint.setFontVariationSettings("'wght' " + weight);
        } catch (Throwable ignored) {
        }
    }

    /** Arms or clears the cancel gesture, dimming and shrinking the ring while armed (R11). */
    private void setCancelArmed(boolean armed) {
        if (cancelArmed == armed) return;
        cancelArmed = armed;
        cancelOpacity = RadialWheelMotion.cancelOpacity(armed);
        cancelScale = RadialWheelMotion.cancelScale(armed);
        sectorPathsValid = false;
        profilePathsValid = false;
        invalidate();
    }

    /** True while a drag past the outer radius has armed the cancel (R11). */
    public boolean isCancelArmed() {
        return cancelArmed;
    }

    /** Scales the hovered sector to 1.04 over 80ms. */
    private void animateHover(Sector sector) {
        int idx = indexOf(sector);
        if (hoverAnimator != null) hoverAnimator.cancel();
        hoverAnimator = ValueAnimator.ofFloat(0f, 1f);
        hoverAnimator.setDuration(UiTokens.MOTION_PRESS_MS);
        hoverAnimator.setInterpolator(FAST_OUT_SLOW_IN);
        final int target = idx;
        hoverAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 0; i < sectorScale.length; i++) {
                float goal = (i == target) ? 1.04f : 1f;
                sectorScale[i] = sectorScale[i] + (goal - sectorScale[i]) * t;
            }
            invalidate();
        });
        hoverAnimator.start();
    }

    private static int indexOf(Sector sector) {
        for (int i = 0; i < SECTORS.length; i++) if (SECTORS[i] == sector) return i;
        return -1;
    }
}
