package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.chimeramc.client.R;
import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.core.mods.inbuilt.overlay.RadialWheelLayout.Sector;
import org.chimeramc.client.core.minecraft.FpsOptimizationService;
import org.chimeramc.client.settings.LowLatencyNetworkManager;
import org.chimeramc.client.settings.ThermalGovernor;
import org.chimeramc.client.util.PersonalizationManager;

/**
 * The radial mod wheel as an in-game overlay (section 1 of the update spec).
 *
 * <p>It is a second screen beside the one Mod Menu: it is
 * opened from the same in-game bind and points at the same destinations. The four sectors route to
 * the module list, the cosmetics wardrobe, the settings and the HUD editor; the floating chips
 * toggle individual modules without opening a menu at all.
 *
 * <p>This is original work: the layout, colours and icons are the app's own (the violet/magenta
 * brand), built from {@link RadialMenuView} and the pure {@link RadialWheelLayout}. It deliberately
 * copies no other client's art, name or text.
 */
public class RadialMenuOverlay {

    private final Activity activity;
    private WindowManager windowManager;
    private WindowManager.LayoutParams wmParams;
    private View overlayView;
    private RadialMenuView menuView;
    private boolean isShowing;
    /** The backdrop capture, so the wheel's glass can blur what is behind it (R3/R12). */
    private WheelBackdropCapture backdropCapture;

    /**
     * How the wheel reaches its destinations; supplied by whoever owns the other screens.
     *
     * <p>Each method names the destination, not the sector, so the wheel's sectors and the profile
     * entries share one routing table. The MODS and HUD sectors open the one Mod Menu on the
     * matching section (a filter over the module list, never a second list); CLOSET asks which of
     * the two customization surfaces to open; SETTINGS opens the Mod Menu's settings page.
     */
    public interface Destinations {
        /** The module list (the MODS sector). */
        void openMods();
        /** The HUD section of the module list (the HUD sector). */
        void openHud();
        /** Ask which customization surface to open (the CLOSET sector). */
        void openCustomize();
        /** The Mod Menu's settings page (the SETTINGS sector). */
        void openSettings();
    }

    private Destinations destinations;

    public RadialMenuOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * Shows the wheel over a launcher screen (the Launch tab's quick dock).
     *
     * <p>The in-game wheel routes to the Mod Menu; over the launcher there is no game session, so
     * the destinations are the launcher's own screens. The wheel itself is the same overlay, so a
     * fix to the wheel geometry or the cursor reaches both homes.
     */
    public static void showForActivity(Activity activity) {
        RadialMenuOverlay overlay = new RadialMenuOverlay(activity);
        overlay.setDestinations(new Destinations() {
            @Override
            public void openMods() {
                activity.startActivity(new android.content.Intent(
                        activity, org.chimeramc.client.ui.activities.ModsFullscreenActivity.class));
            }

            @Override
            public void openHud() {
                // Over the launcher there is no game HUD, so the HUD section is the launcher's own
                // HUD-related screen. It is reached through the same activity the Mods sector opens
                // rather than silently doing nothing.
                openMods();
            }

            @Override
            public void openCustomize() {
                // The same chooser the in-game wheel shows, so the two homes behave identically.
                new CustomizeChooser(activity,
                        () -> activity.startActivity(new android.content.Intent(activity,
                                org.chimeramc.client.ui.activities.SettingsActivity.class)),
                        () -> openCosmetics(activity)).show();
            }

            @Override
            public void openSettings() {
                activity.startActivity(new android.content.Intent(
                        activity, org.chimeramc.client.ui.activities.SettingsActivity.class));
            }
        });
        overlay.show();
    }

    /** Opens the cosmetics wardrobe: the Customize host on its Skins tab. */
    private static void openCosmetics(Activity activity) {
        android.content.Intent intent = new android.content.Intent(
                activity, org.chimeramc.client.ui.activities.CustomizeActivity.class);
        intent.putExtra(
                org.chimeramc.client.ui.activities.CustomizeActivity.EXTRA_INITIAL_TAB,
                org.chimeramc.client.ui.activities.CustomizeActivity.TAB_SKINS);
        activity.startActivity(intent);
    }

    public void setDestinations(Destinations destinations) {
        this.destinations = destinations;
    }

    public boolean isShowing() {
        return isShowing;
    }

    /** Opens or closes from the in-game bind. */
    public void toggle() {
        if (isShowing) {
            hide();
        } else {
            show();
        }
    }

    /** Whether the currently open wheel was opened by a hold gesture (see {@link WheelHoldPolicy}). */
    private boolean openedByHold;

    /**
     * Handles the bind's press under the hold-to-open rule.
     *
     * @return true when the press was consumed (the wheel opened, closed, or was already handled)
     */
    public boolean onBindPress(boolean holdToOpen) {
        WheelHoldPolicy.OnPress action =
                WheelHoldPolicy.onPress(holdToOpen, isShowing, openedByHold);
        switch (action) {
            case OPEN_HOLD:
                openedByHold = true;
                show();
                return true;
            case OPEN_TOGGLE:
                openedByHold = false;
                show();
                return true;
            case CLOSE:
                hide();
                return true;
            default:
                return false;
        }
    }

    /**
     * Handles the bind's release under the hold-to-open rule: commits the highlighted selection, or
     * dismisses when nothing is highlighted.
     *
     * @return true when the release was consumed
     */
    public boolean onBindRelease(boolean holdToOpen) {
        boolean hasSelection = menuView != null && menuView.hasSelection();
        WheelHoldPolicy.OnRelease action =
                WheelHoldPolicy.onRelease(holdToOpen, isShowing, openedByHold, hasSelection);
        openedByHold = false;
        switch (action) {
            case CONFIRM:
                if (menuView != null) menuView.controllerSelect();
                hide();
                return true;
            case DISMISS:
                hide();
                return true;
            default:
                return false;
        }
    }

    public void show() {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            showInternal();
        } catch (Throwable e) {
            detachQuietly();
        }
    }

    private void showInternal() {
        overlayView = LayoutInflater.from(activity).inflate(R.layout.overlay_radial_menu, null);
        menuView = overlayView.findViewById(R.id.radial_menu_view);
        menuView.setListener(new RadialMenuView.Listener() {
            @Override
            public void onSector(Sector sector) {
                hide();
                dispatch(sector);
            }

            @Override
            public void onDismiss() {
                hide();
            }
        });
        // Profile mode: the wheel draws the player's own profiles and routes each entry. It is
        // switched on here so the fixed four-destination wheel above stays the fallback and the
        // profile feature cannot regress it.
        try {
            menuView.setProfiles(activity);
            menuView.setEntryListener(new RadialMenuView.EntryListener() {
                @Override
                public void onEntry(WheelEntry entry) {
                    if (entry == null) return;
                    if (entry.action == WheelEntry.Action.TOGGLE_MODULE) {
                        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
                        boolean next = !manager.resolveInbuiltModEnabled(entry.target, false);
                        manager.setInbuiltModEnabled(entry.target, next);
                        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
                        if (overlayManager != null) {
                            overlayManager.handleModToggle(entry.target, next);
                        }
                        menuView.invalidate();
                        return;
                    }
                    hide();
                    dispatchProfileEntry(entry);
                }

                @Override
                public void onBack() {
                    hide();
                }
            });
        } catch (Throwable ignored) {
            // No profiles available: the fixed wheel above is still fully wired.
        }
        menuView.setChipToggle(new RadialMenuView.ChipToggle() {
            @Override
            public void onChipToggled(String moduleId) {
                InbuiltModManager manager = InbuiltModManager.getInstance(activity);
                boolean next = !manager.resolveInbuiltModEnabled(moduleId, false);
                manager.setInbuiltModEnabled(moduleId, next);
                InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
                if (overlayManager != null) {
                    overlayManager.handleModToggle(moduleId, next);
                }
                menuView.invalidate();
            }

            @Override
            public boolean isChipOn(String moduleId) {
                return InbuiltModManager.getInstance(activity)
                        .resolveInbuiltModEnabled(moduleId, false);
            }
        });

        wmParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                // FLAG_NOT_FOCUSABLE is load-bearing, not cosmetic. The wheel must never take window
                // focus: a focusable overlay becomes the key/event target, so the Activity stops
                // receiving the pad's motion events and the controller cursor has nothing to move
                // on. The other overlays already carry this flag; the wheel did not, which is why
                // the cursor appeared but never moved. The wheel drives itself from the events the
                // Activity forwards to handleControllerKey/Motion, so it needs no focus of its own.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            wmParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wmParams.gravity = Gravity.CENTER;
        wmParams.token = activity.getWindow().getDecorView().getWindowToken();
        windowManager.addView(overlayView, wmParams);
        isShowing = true;
        // The glass material: the wheel asks the host for the device state (R2/R8/R10/R12) and
        // captures the window behind it for the blurred backdrop (R3). Both are fail-open: with no
        // capture the material resolves to its solid tier and the wheel is still fully usable.
        menuView.setMaterialEnvProvider(new RadialMenuView.MaterialEnvProvider() {
            @Override
            public RadialWheelMaterial.Env env() {
                return buildMaterialEnv(activity);
            }
        });
        // R3: the backdrop is shown on its own layer behind the wheel and blurred there. The layer
        // is what the RenderEffect is applied to on API 31+, because a RenderEffect on the wheel
        // itself would blur the wheel's sectors and labels.
        View backdrop = overlayView.findViewById(R.id.radial_backdrop_layer);
        if (backdrop instanceof android.widget.ImageView) {
            menuView.setBackdropLayer((android.widget.ImageView) backdrop);
        }
        backdropCapture = new WheelBackdropCapture();
        startBackdropRefreshLoop();
        // A pad-opened wheel enters controller-cursor mode: the cursor appears at the hub and the
        // stick drives it. Touch still works and hides the cursor on the first tap.
        menuView.post(() -> menuView.enableControllerCursor());
    }

    /**
     * Hands one controller key to the open wheel.
     *
     * @return true when the wheel consumed the press (so the game must not also act on it)
     */
    public boolean handleControllerKey(int keyCode, boolean down) {
        if (!isShowing || menuView == null || !down) return false;
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_BUTTON_A:
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
                return menuView.controllerSelect();
            case android.view.KeyEvent.KEYCODE_BUTTON_B:
            case android.view.KeyEvent.KEYCODE_BACK:
                return menuView.controllerBack();
            case android.view.KeyEvent.KEYCODE_BUTTON_X:
                return menuView.controllerPinHoveredChip();
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                menuView.focusNextSector(-1);
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                menuView.focusNextSector(1);
                return true;
            case android.view.KeyEvent.KEYCODE_BUTTON_R1:
                menuView.cycleChip(1);
                return true;
            case android.view.KeyEvent.KEYCODE_BUTTON_L1:
                menuView.cycleChip(-1);
                return true;
            default:
                return false;
        }
    }

    /**
     * Hands one analogue stick frame to the open wheel.
     *
     * @return true when the wheel consumed it (the game must not also move the camera)
     */
    public boolean handleControllerMotion(android.view.MotionEvent event) {
        if (!isShowing || menuView == null) return false;
        // Resolve the right stick by trying each axis convention and taking the first that carries a
        // deflection. Reading only AXIS_Z/AXIS_RZ meant a pad that reports its right stick on
        // AXIS_RX/AXIS_RY (or AXIS_RZ/AXIS_RX) delivered an all-zero vector, so the cursor never
        // moved. GamepadAxes owns that rule and is unit-tested.
        GamepadAxes.Stick stick = GamepadAxes.read(event);
        float lx = event.getAxisValue(android.view.MotionEvent.AXIS_X);
        float ly = event.getAxisValue(android.view.MotionEvent.AXIS_Y);
        boolean precision = Math.hypot(lx, ly) > 0.5f;
        boolean fast = event.getButtonState() != 0;
        long now = android.os.SystemClock.uptimeMillis();
        float dt = lastStickMs == 0L ? 1f / 60f : Math.min(0.1f, (now - lastStickMs) / 1000f);
        lastStickMs = now;
        float deadZone = CursorController.DEFAULT_DEAD_ZONE;
        float curve = CursorController.DEFAULT_CURVE_EXPONENT;
        lastStickSource = stick.source;
        return menuView.handleControllerStick(stick.x, stick.y, dt, precision, fast, deadZone, curve);
    }

    /** The axis convention the last stick frame used; reported by the diagnostics. */
    public String lastControllerStickSource() {
        return lastStickSource;
    }

    private String lastStickSource = "";

    private long lastStickMs;

    private void dispatch(Sector sector) {
        if (destinations == null) return;
        switch (sector) {
            case MODS:
                destinations.openMods();
                break;
            case CLOSET:
                destinations.openCustomize();
                break;
            case SETTINGS:
                destinations.openSettings();
                break;
            case HUD:
                destinations.openHud();
                break;
            default:
                break;
        }
    }

    /**
     * Routes a profile entry that is not a module toggle.
     *
     * <p>It maps onto the same {@link Destinations} the fixed wheel uses, so a profile entry and a
     * sector reach a destination through one path rather than two. A {@code TOGGLE_MODULE} entry
     * never arrives here (it is handled without closing the wheel); a {@code OPEN_SUBWHEEL} entry is
     * opened inside the view and never arrives here either.
     */
    private void dispatchProfileEntry(WheelEntry entry) {
        if (destinations == null || entry == null) return;
        switch (entry.action) {
            case OPEN_MODS:
                destinations.openMods();
                break;
            case OPEN_CLOSET:
                destinations.openCustomize();
                break;
            case OPEN_SETTINGS:
                destinations.openSettings();
                break;
            case OPEN_HUD:
                destinations.openHud();
                break;
            default:
                break;
        }
    }

    /**
     * Builds the environment the wheel's glass resolves against (R2, R8, R10, R12).
     *
     * <p>Reads the same signals the FPS optimizer coordinates — the thermal reading, battery saver
     * and the live-session flag — so the wheel's blur budget cannot disagree with the launcher's own
     * decision to step back while a game runs. The accessibility flags come from the personalization
     * manager, whose "reduce motion" switch already folds in the OS setting.
     */
    private static RadialWheelMaterial.Env buildMaterialEnv(Activity activity) {
        RadialWheelMaterial.Env env = new RadialWheelMaterial.Env();
        env.apiLevel(android.os.Build.VERSION.SDK_INT);
        try {
            PersonalizationManager personalization = new PersonalizationManager(activity);
            env.reduceMotion(personalization.isReduceMotion());
            // The app has no separate "reduce transparency" toggle, so the one accessibility switch
            // that means "calm the UI down" drives the glass too; the OS setting already feeds it.
            env.reduceTransparency(personalization.isReduceMotion());
        } catch (Throwable ignored) {
        }
        try {
            env.sessionActive(LowLatencyNetworkManager.isGameSessionActive());
        } catch (Throwable ignored) {
        }
        try {
            env.thermal(ThermalGovernor.severity(), isBatterySaver(activity), false);
        } catch (Throwable ignored) {
        }
        try {
            // R8: the wheel asks the FPS Optimization coordinator rather than judging the device a
            // second time, so the blur budget cannot disagree with the launcher's own decision.
            env.budget(FpsOptimizationService.wheelBlurBudget());
        } catch (Throwable ignored) {
        }
        return env;
    }

    private static boolean isBatterySaver(Activity activity) {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
            return pm != null && pm.isPowerSaveMode();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Runs the backdrop capture on the capped refresh loop while the wheel is open (R3).
     *
     * <p>The wheel sits over a live game, so the backdrop has to be re-captured or it freezes; the
     * schedule is what keeps that at a few refreshes per second instead of every frame. The loop
     * stops the moment the wheel hides, so nothing keeps capturing after the game is the only thing
     * on screen.
     */
    private void startBackdropRefreshLoop() {
        mainHandler.removeCallbacks(backdropRefreshRunnable);
        mainHandler.post(backdropRefreshRunnable);
    }

    private final Runnable backdropRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isShowing) return;
            requestBackdropCapture();
            RadialMenuView view = menuView;
            int fps = view == null || view.material() == null
                    ? RadialWheelMaterial.BLUR_FPS_DEFAULT : view.material().blurFps;
            long interval = BackdropRefreshSchedule.intervalMs(fps);
            if (interval > 0L) mainHandler.postDelayed(this, interval);
        }
    };

    /** Requests a backdrop capture if the schedule allows, and hands the result to the wheel. */
    private void requestBackdropCapture() {
        WheelBackdropCapture capture = backdropCapture;
        RadialMenuView view = menuView;
        if (capture == null || view == null) return;
        capture.captureIfDue(activity.getWindow(), android.os.SystemClock.uptimeMillis(),
                view.material() == null ? RadialWheelMaterial.BLUR_FPS_DEFAULT
                        : Math.max(1, view.material().blurFps),
                new WheelBackdropCapture.Callback() {
                    @Override
                    public void onCaptured(android.graphics.Bitmap bitmap) {
                        RadialMenuView current = menuView;
                        if (current != null) current.setBackdropCapture(bitmap);
                    }

                    @Override
                    public void onFailed() {
                        // The material resolves to SOLID on the next refresh; nothing else to do.
                        RadialMenuView current = menuView;
                        if (current != null) current.refreshMaterial();
                    }
                });
    }

    public void hide() {
        isShowing = false;
        mainHandler.removeCallbacks(backdropRefreshRunnable);
        RadialMenuView view = menuView;
        menuView = null;
        if (view != null) view.playClose();
        // Detach after the short close animation so the reverse plays rather than popping. The
        // callback is posted on the main Handler rather than on the View: a View's own postDelayed
        // is queued behind attachment, so if the window was torn down by anything else first the
        // callback never ran and the full-screen translucent window stayed attached — the frozen
        // "white transparent overlay" over the game. A Handler post always runs.
        mainHandler.removeCallbacks(detachRunnable);
        mainHandler.postDelayed(detachRunnable, 120L);
    }

    private final android.os.Handler mainHandler =
            org.chimeramc.client.util.MainThread.handler();
    private final Runnable detachRunnable = this::detachQuietly;

    private void detachQuietly() {
        View view = overlayView;
        overlayView = null;
        // Release the backdrop capture and clear the wheel's backdrop layer so nothing is left
        // pointing at a recycled bitmap after the wheel closes (R3/R12).
        if (backdropCapture != null) {
            backdropCapture.release();
            backdropCapture = null;
        }
        RadialMenuView wheel = menuView;
        if (wheel != null) {
            wheel.releaseBackdropLayer();
        }
        if (view == null) return;
        try {
            if (view.getParent() != null) {
                windowManager.removeViewImmediate(view);
            }
        } catch (Exception ignored) {
            // Already detached or the token is gone; nothing to clean up.
        }
        // A second, unconditional attempt: removeViewImmediate can throw for a window the system
        // has already detached, and a translucent MATCH_PARENT window that survives that is exactly
        // the stuck overlay. A removeView of a non-attached view is a safe no-op.
        try {
            windowManager.removeView(view);
        } catch (Exception ignored) {
        }
    }
}
