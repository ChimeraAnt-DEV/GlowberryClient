package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.ui.components.UiTokens;
import org.chimeramc.client.util.TouchTargetHelper;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.core.mods.inbuilt.ExternalModuleProvider;
import org.chimeramc.client.core.mods.inbuilt.InbuiltModuleProvider;
import org.chimeramc.client.core.mods.inbuilt.UnifiedMod;
import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.core.replay.ReplayControls;
import org.chimeramc.client.core.replay.ReplayPanel;
import org.chimeramc.client.core.mods.inbuilt.model.ModIds;
import org.chimeramc.client.core.mods.pvp.PvpSectionPolicy;
import org.chimeramc.client.core.mods.inbuilt.model.ModLoadoutStore;
import org.chimeramc.client.core.mods.inbuilt.vip.ModCategory;
import org.chimeramc.client.launcher.controller.ControllerConnectionMonitor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ModMenuOverlay {
    private enum ModuleFilter {
        ALL,
        FAVORITES,
        ENABLED,
        INBUILT,
        EXTERNAL,
        PVP,
        // The category split the VIP window used to own. With one menu (U4) they are filters of
        // this menu, and their predicates come from the tested ModCategory rather than a second
        // implementation.
        VISUALS,
        MISC
    }

    private final Activity activity;
    private View overlayView;
    private WindowManager windowManager;
    private WindowManager.LayoutParams wmParams;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isShowing = false;

    private RecyclerView modsRecycler;
    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable searchRunnable = this::applyFilters;
    private ModMenuAdapter adapter;
    private ModMenuTheme theme;
    /** The menu's own appearance settings (M6), read on open and re-applied from the Settings tab. */
    private ModMenuSettings menuSettings;
    /** The destination the rail is currently showing, so a theme rebuild can restore it. */
    private ModMenuPage activePage;
    /** The page to open on, set when showPage() has to open the menu first. */
    private ModMenuPage pendingPage;
    private boolean hasStaggeredOnce = false;
    private EditText searchInput;
    private ImageButton clearSearchBtn;
    private TextView navGeneral, navSettings, navHudEditor, navCosmetics, navPacks, navVoice, navReplay;

    /** The icon rail. Built from ModMenuPage.pages(), the one page registry the whole menu reads. */
    private ModMenuRail rail;
    private TextView filterAll, filterFavorites, filterEnabled, filterInbuilt, filterExternal,
            filterPvp, filterVisuals, filterMisc;
    private TextView moduleCountText, emptyStateText;
    private TextView compactFilterSelector, compactModuleCount;

    // The live stats strip in the top bar. Refreshed on a one-second tick while the menu is open,
    // and never when it is closed, so it costs nothing during gameplay.
    private TextView statsFps, statsPing, statsBattery;
    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            refreshStatsStrip();
            if (isShowing) handler.postDelayed(this, 1000L);
        }
    };

    /**
     * Refreshes the blurred panel backdrop at the configured blur rate (M4/M6).
     *
     * <p>Self-rescheduling and only while the menu is open, so the capture costs nothing during
     * ordinary play. The interval comes from the blur-fps setting through the same pure rule the
     * wheel uses; a setting of 0 means "no refresh", which is why this re-posts with a guard rather
     * than a fixed delay.
     */
    private final Runnable backdropTick = new Runnable() {
        @Override
        public void run() {
            if (!isShowing || overlayView == null) return;
            // The OS accessibility switches can force the glass off, in which case there is nothing
            // to refresh.
            if (effectivePanelGlass() == ModMenuSettings.GlassIntensity.OFF) return;
            long interval = menuSettings == null
                    ? RadialWheelMaterial.blurIntervalMs(RadialWheelMaterial.BLUR_FPS_DEFAULT)
                    : RadialWheelMaterial.blurIntervalMs(menuSettings.blurFps());
            if (interval <= 0L) return;
            // Drop the cached capture so the next apply requests a fresh one.
            if (backdropCapture != null) {
                backdropCapture.captureIfDue(activity.getWindow(),
                        android.os.SystemClock.uptimeMillis(),
                        menuSettings == null ? RadialWheelMaterial.BLUR_FPS_DEFAULT
                                : menuSettings.blurFps(),
                        new WheelBackdropCapture.Callback() {
                            @Override
                            public void onCaptured(android.graphics.Bitmap capture) {
                                if (overlayView == null || capture == null) return;
                                backdropBitmap = capture;
                                android.widget.ImageView view =
                                        overlayView.findViewById(R.id.mod_menu_backdrop);
                                if (view != null) showBackdropBitmap(view, capture);
                            }

                            @Override
                            public void onFailed() {
                            }
                        });
            }
            handler.postDelayed(this, interval);
        }
    };
    private View settingsContainer;
    private View modulesContainer;
    private FrameLayout cosmeticsContainer;
    private FrameLayout packsContainer;
    private FrameLayout voiceContainer;
    private VoicePanel voicePanel;
    private FrameLayout replayContainer;
    private ReplayPanel replayPanel;
    private FrameLayout controllerContainer;
    private FrameLayout keyboardContainer;
    private ModMenuControllerSection controllerSection;
    private ModMenuKeyboardSection keyboardSection;
    private PackChangerPanel packChangerPanel;
    private View emptyState;
    private View menuContainer;
    private View modMenuTopBar;
    private View modMenuLogo;
    private View filterBar;
    private View compactFilterBar;
    private CosmeticsPanel cosmeticsPanel;

    // --- Panel glass (M4/M6) ------------------------------------------------------------------

    /** The glass renderer, shared with the wheel so the panel and the wheel are one material. */
    private final WheelGlassRenderer glassRenderer = new WheelGlassRenderer();
    /** The backdrop capture for the panel, created lazily on the first glass-on render. */
    private WheelBackdropCapture backdropCapture;
    /** The raw capture currently shown (or pending) on the backdrop layer. */
    private android.graphics.Bitmap backdropBitmap;
    /** The blurred copy for the pre-API-31 route, cached so a refresh does not re-blur. */
    private android.graphics.Bitmap backdropBlurred;
    private Switch notificationsSwitch;
    private Switch pauseMenuOnlySwitch;
    private Switch compactModeSwitch;
    private SeekBar modMenuOpacitySeekBar;
    private TextView modMenuOpacityText;
    private SeekBar modMenuPrimaryOpacitySeekBar;
    private TextView modMenuPrimaryOpacityText;
    private SeekBar modMenuButtonOpacitySeekBar;
    private TextView modMenuButtonOpacityText;
    private SeekBar hudButtonSizeSeekBar;
    private TextView hudButtonSizeText;
    private boolean updatingHudButtonSize = false;
    private boolean compactMode = false;
    private GridLayoutManager modsLayoutManager;

    /** Live keybind labels on the Settings tab, refreshed when a bind is set or cleared. */
    private TextView keyboardBindText;
    private TextView controllerBindText;
    /** The connected pad's 2D illustration shown beside the controller bind row. */
    private org.chimeramc.client.launcher.ui.views.Controller3DView controllerBindIllustration;
    /** Chips for the saved module loadouts on the Settings tab. */
    private android.widget.LinearLayout loadoutChipRow;

    /** Delivers a hardware key to an open bind picker. Returns true when one consumed it. */
    public interface BindCapture {
        void onKey(int keyCode);
    }

    /**
     * Offers the raw key to whichever menu is currently waiting for a bind.
     *
     * <p>The capture itself lives in {@link org.chimeramc.client.core.mods.inbuilt.vip.MenuBindRelay},
     * shared by every bind picker in the menu, so two pickers cannot both hold a capture and
     * silently steal each other's presses. This method is kept as the single
     * entry point the game activity calls at the top of {@code dispatchKeyEvent}.
     */
    public static boolean deliverBindKey(int keyCode) {
        return org.chimeramc.client.core.mods.inbuilt.vip.MenuBindRelay.deliver(keyCode);
    }

    static boolean isCapturingBind() {
        return org.chimeramc.client.core.mods.inbuilt.vip.MenuBindRelay.isCapturing();
    }


    private List<UnifiedMod> allMods = new ArrayList<>();
    private List<UnifiedMod> filteredMods = new ArrayList<>();
    private final Set<String> favoriteKeys = new HashSet<>();
    private ModuleFilter activeFilter = ModuleFilter.ALL;
    /**
     * How many one-shot action rows the current section shows (M3).
     *
     * <p>The empty state needs it: the actions live in the adapter, and an empty module list with an
     * action row on screen is not an empty screen.
     */
    private int visibleActionCount = 0;

    private ModMenuCallback callback;
    private ModNotificationManager notificationManager;

    private void crossfade(View view) {
        view.setAlpha(0f);
        view.setTranslationX(30f);
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(250)
            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
            .start();
    }

    private void animateMenuEnter(final View menuContainer) {
        menuContainer.setAlpha(0f);
        menuContainer.setScaleX(0.85f);
        menuContainer.setScaleY(0.85f);

        menuContainer.post(() -> {
            menuContainer.setPivotX(menuContainer.getWidth() / 2f);
            menuContainer.setPivotY(menuContainer.getHeight() / 2f);

            int opacity = InbuiltModManager.getInstance(activity).getModMenuOpacity();
            float targetAlpha = opacity / 100f;

            menuContainer.animate()
                .alpha(targetAlpha)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .withLayer()
                .start();
        });
    }

    private void animateMenuExit(final View menuContainer, Runnable onEnd) {
        menuContainer.setPivotX(menuContainer.getWidth() / 2f);
        menuContainer.setPivotY(menuContainer.getHeight() / 2f);

        menuContainer.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(180)
            .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
            .withLayer()
            .setListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    menuContainer.animate().setListener(null);
                    onEnd.run();
                }
            })
            .start();
    }

    private int getAccentColor() {
        return theme != null ? theme.accent() : ModMenuTheme.DEFAULT_ACCENT;
    }

    public interface ModMenuCallback {
        void onModToggled(String modId, boolean enabled);
        void onButtonOpacityChanged(int opacity);
    }

    public ModMenuOverlay(Activity activity) {
        this.activity = activity;
        this.theme = new ModMenuTheme(activity);
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
        this.notificationManager = new ModNotificationManager(activity);
    }

    public void setCallback(ModMenuCallback callback) {
        this.callback = callback;
    }

    public void show() {
        if (isShowing) {
            refreshMods();
            refreshVoiceSectionIfVisible();
            return;
        }
        showInternal();
    }

    /**
     * Opens the menu on a named page, for callers that are not a rail tap (the radial wheel's
     * sectors). Safe when the menu is already open: it just switches page.
     */
    public void showPage(ModMenuPage page) {
        ModMenuPage target = page == null ? ModMenuPage.GENERAL : page;
        if (!isShowing) {
            pendingPage = target;
            show();
            return;
        }
        selectPage(target);
    }

    private void showInternal() {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;

        try {
            overlayView = LayoutInflater.from(activity).inflate(R.layout.overlay_mod_menu, null);

            // The overlay root is clickable so a background tap closes the menu. A clickable
            // View is focusable, and a controller's d-pad or stick moves the focus onto it; the
            // platform then paints its default focus highlight — a translucent white wash over
            // the whole screen, which reads as "the menu put a 50% white overlay up" whenever the
            // player so much as nudges the stick. The highlight is suppressed here rather than
            // only in XML so it cannot come back through a theme or a re-inflated layout.
            // Applied *after* setupViews/loadMods below: the module list is populated there and
            // can take focus for its first card, which would restore the very highlight this
            // suppresses.

            int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            overlayView.setSystemUiVisibility(uiOptions);

            overlayView.setOnSystemUiVisibilityChangeListener(visibility -> {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    if (overlayView != null) {
                        overlayView.setSystemUiVisibility(uiOptions);
                    }
                }
            });

            setupViews();
            loadMods();
            disableFocusHighlight(overlayView);

            wmParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            );
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                wmParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            wmParams.gravity = Gravity.CENTER;
            wmParams.token = activity.getWindow().getDecorView().getWindowToken();

            windowManager.addView(overlayView, wmParams);
            isShowing = true;
            maybeAttachRenderDebugOverlay();

            // Start the stats strip tick. Only while the menu is open, so the FPS/battery read
            // costs nothing during ordinary play.
            handler.removeCallbacks(statsTick);
            refreshStatsStrip();
            handler.postDelayed(statsTick, 1000L);

            // Start the backdrop refresh tick at the configured blur rate (M6). Also removed in
            // hide(), so the capture never runs while the menu is closed.
            handler.removeCallbacks(backdropTick);
            handler.postDelayed(backdropTick, Math.max(1L,
                    RadialWheelMaterial.blurIntervalMs(menuSettings == null
                            ? RadialWheelMaterial.BLUR_FPS_DEFAULT : menuSettings.blurFps())));

            overlayView.setAlpha(0f);
            overlayView.animate().alpha(1f).setDuration(220).start();

            View menuContainer = overlayView.findViewById(R.id.mod_menu_container);
            if (menuContainer != null) {
                animateMenuEnter(menuContainer);
            }
        } catch (Exception e) {
            showFallback();
        }
    }

    private void showFallback() {
        if (isShowing) return;
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;

        overlayView = LayoutInflater.from(activity).inflate(R.layout.overlay_mod_menu, null);
        setupViews();
        loadMods();
        disableFocusHighlight(overlayView);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        rootView.addView(overlayView, params);
        isShowing = true;
        wmParams = null;
        maybeAttachRenderDebugOverlay();

        overlayView.setAlpha(0f);
        overlayView.animate().alpha(1f).setDuration(220).start();

        View menuContainer = overlayView.findViewById(R.id.mod_menu_container);
        if (menuContainer != null) {
            animateMenuEnter(menuContainer);
        }
    }

    /**
     * Stops the platform painting a focus highlight over the whole overlay.
     *
     * <p>A clickable View is implicitly focusable. The d-pad and the analogue stick both move
     * focus, and when the overlay root takes it the framework draws its default focus highlight:
     * a full-screen translucent white wash. To the player that is a 50% white overlay appearing
     * the moment the controller is touched, which obscures the game and the menu itself.
     *
     * <p>{@code focusable=false} on the root is the primary fix — it removes the view from focus
     * search entirely, so no highlight can be painted for any input. It is applied to the root
     * passed in (this helper is called with the overlay root); children keep their focusability
     * for controller navigation and only get the highlight flag cleared. Only the highlight flag
     * is ever touched — never the background, which carries each control's real styling. minSdk
     * is 28, so {@code setDefaultFocusHighlightEnabled} is always available.
     */
    private static void disableFocusHighlight(View view) {
        if (view == null) return;
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setDefaultFocusHighlightEnabled(false);
        // Clearing an already-held focus matters as much as clearing the flag. The highlight can
        // be painted for the view that holds focus right now, and a stick nudge can hand focus to
        // the root — or, after a rebind, to a card — while the menu is already open. Dropping the
        // flag does not retroactively remove the highlight from the currently-focused view, so
        // focus is released here. The walk also clears the flag on every descendant (buttons stay
        // focusable for controller navigation, but must not paint the highlight), and releases
        // focus held by any descendant rather than the root alone.
        clearFocusHighlightRecursive(view);
    }

    /**
     * Refreshes the FPS / ping / battery strip in the top bar.
     *
     * <p>FPS comes from the same inbuilt {@code FpsMod} the FPS overlay uses, so the two can never
     * disagree. Battery comes from the sticky {@code ACTION_BATTERY_CHANGED} broadcast, and ping
     * has no source in this build — it renders the em dash rather than a fabricated figure, which
     * is why {@link ModStatsFormatter} owns the "no reading" rule.
     */
    private void refreshStatsStrip() {
        if (statsFps != null) {
            int fps = 0;
            try {
                if (org.chimeramc.client.launcher.core.mods.inbuilt.nativemod.FpsMod.nativeIsInitialized()) {
                    fps = org.chimeramc.client.launcher.core.mods.inbuilt.nativemod.FpsMod.nativeGetFps();
                }
            } catch (Throwable ignored) {
                // A native read must never take the menu down; the em dash is the honest fallback.
            }
            statsFps.setText(ModStatsFormatter.fps(fps));
        }
        if (statsPing != null) {
            statsPing.setText(ModStatsFormatter.ping(0));
        }
        if (statsBattery != null) {
            statsBattery.setText(readBattery());
        }
    }

    private String readBattery() {
        try {
            android.content.Intent battery = activity.registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return ModStatsFormatter.battery(-1, false);
            int level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) return ModStatsFormatter.battery(-1, false);
            int percent = Math.round(level * 100f / scale);
            int status = battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
            return ModStatsFormatter.battery(percent, charging);
        } catch (Throwable t) {
            return ModStatsFormatter.battery(-1, false);
        }
    }

    private static void clearFocusHighlightRecursive(View view) {
        if (view == null) return;
        view.setDefaultFocusHighlightEnabled(false);
        if (view.isFocused()) view.clearFocus();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearFocusHighlightRecursive(group.getChildAt(i));
            }
        }
    }


    /**
     * Attaches the live render debug overlay when the player turned it on, so the native draw
     * values can be watched in third person. Off by default; a no-op then.
     */
    private void maybeAttachRenderDebugOverlay() {
        try {
            InbuiltModManager manager = InbuiltModManager.getInstance(activity);
            if (manager == null || !manager.isRenderDebugOverlayEnabled()) return;
            if (overlayView instanceof FrameLayout) {
                renderDebugOverlay = RenderDebugOverlay.attach(activity, (FrameLayout) overlayView);
                if (renderDebugOverlay != null) renderDebugOverlay.start();
            }
        } catch (Throwable ignored) {
            // A debug overlay must never block the menu.
        }
    }

    private RenderDebugOverlay renderDebugOverlay;

    private void setupViews() {
        // The overlay tree is re-inflated on every show(), so any panel that was attached to the
        // previous tree is now parented to a detached view. Reset the cached references (and
        // release the ones that own executors) here, or a section that caches its panel would
        // build it once and then render blank on every reopen because its view belongs to the
        // old, discarded tree.
        releasePanels();
        menuContainer = overlayView.findViewById(R.id.mod_menu_container);
        modMenuTopBar = overlayView.findViewById(R.id.mod_menu_topbar);
        modMenuLogo = overlayView.findViewById(R.id.mod_menu_logo);
        filterBar = overlayView.findViewById(R.id.filter_bar);
        compactFilterBar = overlayView.findViewById(R.id.compact_filter_bar);
        compactFilterSelector = overlayView.findViewById(R.id.compact_filter_selector);
        compactModuleCount = overlayView.findViewById(R.id.compact_module_count);
        ImageButton closeBtn = overlayView.findViewById(R.id.btn_close_menu);
        searchInput = overlayView.findViewById(R.id.search_input);
        clearSearchBtn = overlayView.findViewById(R.id.btn_clear_search);
        modsRecycler = overlayView.findViewById(R.id.mods_grid_recycler);
        // The destination entries left the top bar for the icon rail, so these ids no longer exist
        // in the layout. The fields are kept (always null) so updateNavigationItems' call sites do
        // not change shape; the rail is what paints selection now.
        navGeneral = null;
        navVoice = null;
        navSettings = null;
        navHudEditor = null;
        navCosmetics = null;
        navPacks = null;
        navReplay = null;
        buildRail();
        determineInputMode();
        filterAll = overlayView.findViewById(R.id.filter_all);
        filterFavorites = overlayView.findViewById(R.id.filter_favorites);
        filterEnabled = overlayView.findViewById(R.id.filter_enabled);
        filterInbuilt = overlayView.findViewById(R.id.filter_inbuilt);
        filterExternal = overlayView.findViewById(R.id.filter_external);
        filterPvp = overlayView.findViewById(R.id.filter_pvp);
        filterVisuals = overlayView.findViewById(R.id.filter_visuals);
        filterMisc = overlayView.findViewById(R.id.filter_misc);
        moduleCountText = overlayView.findViewById(R.id.module_count_text);
        statsFps = overlayView.findViewById(R.id.mod_stats_fps);
        statsPing = overlayView.findViewById(R.id.mod_stats_ping);
        statsBattery = overlayView.findViewById(R.id.mod_stats_battery);
        settingsContainer = overlayView.findViewById(R.id.settings_container);
        modulesContainer = overlayView.findViewById(R.id.modules_container);
        cosmeticsContainer = overlayView.findViewById(R.id.cosmetics_container);
        packsContainer = overlayView.findViewById(R.id.packs_container);
        voiceContainer = overlayView.findViewById(R.id.voice_container);
        replayContainer = overlayView.findViewById(R.id.replay_container);
        controllerContainer = overlayView.findViewById(R.id.controller_container);
        keyboardContainer = overlayView.findViewById(R.id.keyboard_container);
        emptyState = overlayView.findViewById(R.id.empty_state);
        emptyStateText = overlayView.findViewById(R.id.empty_state_text);
        notificationsSwitch = overlayView.findViewById(R.id.switch_notifications);
        pauseMenuOnlySwitch = overlayView.findViewById(R.id.switch_pause_menu_only);
        compactModeSwitch = overlayView.findViewById(R.id.switch_compact_mod_menu);

        View hudEditorTools = overlayView.findViewById(R.id.hud_editor_tools);
        View hudEditorDragHandle = overlayView.findViewById(R.id.hud_editor_drag_handle);
        if (hudEditorDragHandle != null) setupHudEditorDragHandle(hudEditorDragHandle, hudEditorTools);
        View btnHudSave = overlayView.findViewById(R.id.btn_hud_save);
        View btnHudCancel = overlayView.findViewById(R.id.btn_hud_cancel);
        View modMenuContainer = overlayView.findViewById(R.id.mod_menu_container);
        hudButtonSizeSeekBar = overlayView.findViewById(R.id.seekbar_hud_button_size);
        hudButtonSizeText = overlayView.findViewById(R.id.text_hud_button_size);

        if (hudButtonSizeSeekBar != null) {
            hudButtonSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || updatingHudButtonSize) return;
                    InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
                    if (manager != null) {
                        manager.setSelectedHudEditorButtonSize(progress);
                    }
                    updateHudButtonSizeText(progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            updateHudEditorSizeControls(0);
        }

        // Touch feedback on the stable chrome (filter chips + close). Recycler rows get their own
        // feedback in the adapter, since they are recycled and rebound; the rail tiles get theirs
        // from ModMenuRail's own click handling.
        for (View v : new View[]{
                filterAll, filterFavorites, filterEnabled, filterInbuilt, filterExternal, filterPvp,
                filterVisuals, filterMisc, closeBtn, clearSearchBtn}) {
            if (v != null) DynamicAnim.applyPressScale(v);
        }

        // The chrome icons draw at 22-36dp. They stay that size and gain a 48dp touch area, which
        // is the difference between a control a player hits first time and one they miss while a
        // game is running behind the menu.
        for (int id : new int[]{R.id.btn_close_menu, R.id.btn_clear_search,
                R.id.btn_back_to_modules, R.id.btn_hud_save, R.id.btn_hud_reset,
                R.id.btn_hud_cancel}) {
            TouchTargetHelper.expand(overlayView.findViewById(id));
        }

        if (btnHudSave != null) {
            btnHudSave.setOnClickListener(v -> {
                exitHudEditorMode(modMenuContainer, hudEditorTools);
            });
        }

        View btnHudReset = overlayView.findViewById(R.id.btn_hud_reset);
        if (btnHudReset != null) {
            btnHudReset.setOnClickListener(v -> {
                InbuiltOverlayManager.getInstance().resetAllPositionsToCenter();
            });
        }

        if (btnHudCancel != null) {
            btnHudCancel.setOnClickListener(v -> {
                exitHudEditorMode(modMenuContainer, hudEditorTools);
            });
        }

        // Close on background tap
        overlayView.setOnClickListener(v -> {
            // Only hide if not in HUD editor mode
            if (hudEditorTools == null || hudEditorTools.getVisibility() != View.VISIBLE) {
                hide();
            }
        });
        menuContainer.setOnClickListener(v -> {}); // Consume clicks
        if (hudEditorTools != null) {
            hudEditorTools.setOnClickListener(v -> {}); // Consume clicks
        }

        closeBtn.setOnClickListener(v -> hide());

        // Search functionality
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchHandler.removeCallbacks(searchRunnable);
                searchHandler.postDelayed(searchRunnable, 60L);
                clearSearchBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        clearSearchBtn.setOnClickListener(v -> {
            searchInput.setText("");
            clearSearchBtn.setVisibility(View.GONE);
        });
        setupFilterButtons();
        setupCompactFilter();

        View btnBackToModules = overlayView.findViewById(R.id.btn_back_to_modules);
        if (btnBackToModules != null) {
            btnBackToModules.setOnClickListener(v -> showModulesSection());
        }

        // Navigation. The top bar carries no destinations any more; these text entries are gone
        // from the layout and the rail drives routing via selectPage(). The bindings stay
        // null-guarded so a layout that still declares one keeps working.
        if (navVoice != null) navVoice.setOnClickListener(v -> showVoiceSection());
        if (navGeneral != null) navGeneral.setOnClickListener(v -> showModulesSection());
        if (navSettings != null) navSettings.setOnClickListener(v -> showSettingsSection());
        if (navCosmetics != null) navCosmetics.setOnClickListener(v -> showCosmeticsSection());
        if (navPacks != null) navPacks.setOnClickListener(v -> showPacksSection());
        if (navReplay != null) navReplay.setOnClickListener(v -> showReplaySection());

        // Settings
        InbuiltModManager modManager = InbuiltModManager.getInstance(activity);
        compactMode = modManager.isModMenuCompact();
        notificationsSwitch.setChecked(modManager.isNotificationsEnabled());
        notificationsSwitch.setOnCheckedChangeListener((btn, checked) -> {
            modManager.setNotificationsEnabled(checked);
        });

        if (pauseMenuOnlySwitch != null) {
            pauseMenuOnlySwitch.setChecked(modManager.isPauseMenuOnly());
            pauseMenuOnlySwitch.setOnCheckedChangeListener((btn, checked) -> {
                modManager.setPauseMenuOnly(checked);
            });
        }

        if (compactModeSwitch != null) {
            compactModeSwitch.setChecked(compactMode);
            compactModeSwitch.setOnCheckedChangeListener((btn, checked) -> {
                modManager.setModMenuCompact(checked);
                setCompactMode(checked);
            });
        }

        modMenuOpacitySeekBar = overlayView.findViewById(R.id.seekbar_mod_menu_opacity);
        modMenuOpacityText = overlayView.findViewById(R.id.text_mod_menu_opacity);
        int currentMenuOpacity = modManager.getModMenuOpacity();
        modMenuOpacitySeekBar.setProgress(currentMenuOpacity);
        modMenuOpacityText.setText(activity.getString(R.string.mod_menu_percent_value, currentMenuOpacity));
        modMenuOpacitySeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    modMenuOpacityText.setText(activity.getString(R.string.mod_menu_percent_value, progress));
                    modManager.setModMenuOpacity(progress);
                    applyMenuOpacity();
                }
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        modMenuPrimaryOpacitySeekBar = overlayView.findViewById(R.id.seekbar_mod_menu_primary_opacity);
        modMenuPrimaryOpacityText = overlayView.findViewById(R.id.text_mod_menu_primary_opacity);
        if (modMenuPrimaryOpacitySeekBar != null) {
            int currentPrimaryOpacity = modManager.getModMenuPrimaryOpacity();
            modMenuPrimaryOpacitySeekBar.setProgress(currentPrimaryOpacity);
            if (modMenuPrimaryOpacityText != null) {
                modMenuPrimaryOpacityText.setText(
                        activity.getString(R.string.mod_menu_percent_value, currentPrimaryOpacity));
            }
            modMenuPrimaryOpacitySeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    if (modMenuPrimaryOpacityText != null) {
                        modMenuPrimaryOpacityText.setText(
                                activity.getString(R.string.mod_menu_percent_value, progress));
                    }
                    modManager.setModMenuPrimaryOpacity(progress);
                    applyMenuOpacity();
                }
                @Override
                public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
            });
        }

        modMenuButtonOpacitySeekBar = overlayView.findViewById(R.id.seekbar_mod_menu_button_opacity);
        modMenuButtonOpacityText = overlayView.findViewById(R.id.text_mod_menu_button_opacity);
        int currentButtonOpacity = modManager.getModMenuButtonOpacity();
        modMenuButtonOpacitySeekBar.setProgress(currentButtonOpacity);
        modMenuButtonOpacityText.setText(activity.getString(R.string.mod_menu_percent_value, currentButtonOpacity));
        modMenuButtonOpacitySeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    modMenuButtonOpacityText.setText(activity.getString(R.string.mod_menu_percent_value, progress));
                    modManager.setModMenuButtonOpacity(progress);
                    if (callback != null) {
                        callback.onButtonOpacityChanged(progress);
                    }
                }
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        // M1/M6: the menu's own appearance settings drive the theme, the accent and the motion.
        // Read before the surfaces are applied — a theme built while the settings are still null
        // resolves to the defaults, so the panel would open with the wrong preset and no glass.
        menuSettings = InbuiltModManager.getInstance(activity).getModMenuSettings();

        applyMenuOpacity();

        setupKeybindSettings(modManager);
        setupLoadoutSettings(modManager);
        setupModMenuAppearanceSettings();

        adapter = new ModMenuAdapter(new ModMenuTheme(activity, menuSettings));
        adapter.setSettings(menuSettings);
        adapter.setCompactMode(compactMode);
        // Two columns on a wide window, one below the 560dp threshold (the spec's rule). The old
        // four-column grid was too dense; the two-column module list is the blueberry layout.
        int spanCount = compactMode ? 1 : 2;
        modsLayoutManager = new GridLayoutManager(activity, spanCount);
        modsLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter != null && adapter.isGroupHeader(position)
                    ? modsLayoutManager.getSpanCount()
                    : 1;
            }
        });
        modsRecycler.setLayoutManager(modsLayoutManager);
        modsRecycler.setItemAnimator(null);
        modsRecycler.setHasFixedSize(true);
        adapter.setOnModActionListener(new ModMenuAdapter.OnModActionListener() {
            @Override
            public void onToggle(UnifiedMod mod, boolean enabled) {
                mod.applyEnabled(enabled);
                InbuiltModManager modManager = InbuiltModManager.getInstance(activity);
                if (enabled && modManager.isNotificationsEnabled()) {
                    notificationManager.show(mod.getName(), mod.getStableKey());
                }
                if (callback != null) {
                    callback.onModToggled(mod.getStableKey(), enabled);
                }
                if (activeFilter == ModuleFilter.ENABLED) {
                    applyFilters();
                }
            }
            @Override
            public void onConfig(UnifiedMod mod) {
                showConfigSection(mod);
            }
            @Override
            public void onFavoriteChanged(UnifiedMod mod, boolean favorite) {
                InbuiltModManager.getInstance(activity).setModFavorite(mod.getStableKey(), favorite);
                if (favorite) {
                    favoriteKeys.add(mod.getStableKey());
                } else {
                    favoriteKeys.remove(mod.getStableKey());
                }
                if (activeFilter == ModuleFilter.FAVORITES) {
                    applyFilters();
                }
            }
            @Override
            public void onAction(String actionId) {
                runMenuAction(actionId);
            }
            @Override
            public void onRowReason(String stableKey) {
                showRowReasonSheet(stableKey);
            }
            @Override
            public void onReorder(String sectionId, java.util.List<String> orderedKeys) {
                InbuiltModManager.getInstance(activity).setModMenuSectionOrder(sectionId, orderedKeys);
            }
        });
        setupReorderTouch();
        modsRecycler.setAdapter(adapter);
        applyCompactModeLayout(compactMode);

        // A caller that asked for a specific page (the radial wheel) opened the menu for us, so
        // honour it here — after the views exist. Otherwise the module list is the default.
        ModMenuPage initial = pendingPage;
        pendingPage = null;
        selectPage(initial == null ? ModMenuPage.GENERAL : initial);
    }

    private void showModulesSection() {
        updateNavigationItems(true, false, false, false, false, false, false);

        if (modulesContainer.getVisibility() != View.VISIBLE) {
            modulesContainer.setVisibility(View.VISIBLE);
            crossfade(modulesContainer);
        }
        settingsContainer.setVisibility(View.GONE);
        hideCosmetics();
        hidePacks();
        hideVoice();
        hideReplay();
        hideSupporterPages();

        if (overlayView != null) {
            View modConfigContainer = overlayView.findViewById(R.id.mod_config_container);
            View searchContainer = overlayView.findViewById(R.id.search_container);
            View configHeader = overlayView.findViewById(R.id.config_header);
            if (modConfigContainer != null) modConfigContainer.setVisibility(View.GONE);
            if (searchContainer != null) searchContainer.setVisibility(View.VISIBLE);
            if (configHeader != null) configHeader.setVisibility(View.GONE);
            updateFilterBarVisibility();
        }
    }

    /**
     * Runs a one-shot action row (M3).
     *
     * <p>Actions are dispatched by id rather than by a captured Runnable, so the list can be
     * rebuilt without the handler pointing at a stale view.
     */
    private void runMenuAction(String actionId) {
        if (actionId == null) return;
        switch (actionId) {
            case ModMenuActionCatalog.ACTION_RESET_HUD:
                InbuiltOverlayManager.getInstance().resetAllPositionsToCenter();
                android.widget.Toast.makeText(activity, R.string.mod_menu_action_reset_hud_done,
                        android.widget.Toast.LENGTH_SHORT).show();
                break;
            case ModMenuActionCatalog.ACTION_RELOAD_MODULES:
                reloadModules();
                break;
            default:
                break;
        }
    }

    /**
     * Re-scans and re-registers every module, then refreshes the list (M3).
     *
     * <p>The providers read the registry live on every {@code load}, so re-reading them *is* the
     * reload: a module added or removed while the menu was open appears without restarting the
     * session. The list's selection is dropped by {@link #applyFilters()} so a renumbered row
     * cannot be toggled by a stale controller index.
     */
    private void reloadModules() {
        loadMods();
    }

    /**
     * Explains why a row cannot run (M10).
     *
     * <p>The dialog names the state and offers the one action that makes sense: a crash-disabled
     * module can be re-enabled (that is the whole point of the message), while a module with no
     * hook on this build has nothing to offer but the explanation.
     */
    private void showRowReasonSheet(String stableKey) {
        if (stableKey == null || activity == null) return;
        UnifiedMod mod = findModByKey(stableKey);
        if (mod == null) return;
        ModMenuRowState state = ModMenuRowState.of(true,
                org.chimeramc.client.core.mods.inbuilt.model.ModAvailability
                        .isInteractive(mod.getId()), false, mod.isEnabled());
        String reason = rowReasonText(mod, state);
        if (reason == null) return;

        boolean reEnableable = state == ModMenuRowState.CRASHED;
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity)
                .setTitle(mod.getName())
                .setMessage(reason)
                .setNegativeButton(R.string.mod_menu_row_reason_dismiss, null);
        if (reEnableable) {
            builder.setPositiveButton(R.string.mod_menu_row_reason_re_enable, (d, w) -> {
                mod.applyEnabled(true);
                loadMods();
            });
        }
        builder.show();
    }

    /** The explanation text for a row's state, or null when the row is healthy. */
    private String rowReasonText(UnifiedMod mod, ModMenuRowState state) {
        switch (state) {
            case CRASHED:
                return activity.getString(R.string.mod_menu_row_crashed);
            case FAILED:
                return activity.getString(R.string.mod_menu_row_failed);
            case UNSUPPORTED:
                if (org.chimeramc.client.core.mods.inbuilt.model.ModAvailability
                        .isUnavailable(mod.getId())) {
                    int reason = org.chimeramc.client.core.mods.inbuilt.model.ModAvailability
                            .REASON_SERVER_DECIDED.equals(
                            org.chimeramc.client.core.mods.inbuilt.model.ModAvailability
                                    .unavailableReason(mod.getId()))
                            ? R.string.mod_unavailable_reason_server
                            : R.string.mod_unavailable_reason;
                    return activity.getString(reason);
                }
                return activity.getString(R.string.mod_menu_row_unsupported);
            default:
                return null;
        }
    }

    /** Finds a module by its stable key, or null when it is not in the current list. */
    private UnifiedMod findModByKey(String stableKey) {
        for (UnifiedMod mod : allMods) {
            if (stableKey.equals(mod.getStableKey())) return mod;
        }
        return null;
    }

    /**
     * Enables drag-to-reorder within the module list (M9).
     *
     * <p>Long-press starts the drag so a normal tap still toggles, and the move is committed to the
     * manager on drop. Only modules move: a group header has no position of its own.
     */
    private void setupReorderTouch() {
        if (modsRecycler == null) return;
        androidx.recyclerview.widget.ItemTouchHelper helper =
                new androidx.recyclerview.widget.ItemTouchHelper(
                        new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                                androidx.recyclerview.widget.ItemTouchHelper.UP
                                        | androidx.recyclerview.widget.ItemTouchHelper.DOWN
                                        | androidx.recyclerview.widget.ItemTouchHelper.START
                                        | androidx.recyclerview.widget.ItemTouchHelper.END,
                                0) {
                            @Override
                            public boolean isLongPressDragEnabled() {
                                return adapter != null;
                            }

                            @Override
                            public boolean onMove(RecyclerView recyclerView,
                                                  RecyclerView.ViewHolder viewHolder,
                                                  RecyclerView.ViewHolder target) {
                                if (adapter == null) return false;
                                int from = viewHolder.getBindingAdapterPosition();
                                int to = target.getBindingAdapterPosition();
                                if (from == RecyclerView.NO_POSITION
                                        || to == RecyclerView.NO_POSITION) {
                                    return false;
                                }
                                return adapter.moveModule(from, to) != null;
                            }

                            @Override
                            public boolean canDropOver(RecyclerView recyclerView,
                                                       RecyclerView.ViewHolder current,
                                                       RecyclerView.ViewHolder target) {
                                // A module may only move within its own section: crossing into
                                // another section would break the one-header-per-run rule and
                                // the saved order is stored per section.
                                return adapter != null
                                        && adapter.canReorderBetween(current, target);
                            }

                            @Override
                            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                                // No swipe action: a swipe that deletes a module would be a
                                // destructive gesture on a list of toggles.
                            }

                            @Override
                            public void clearView(RecyclerView recyclerView,
                                                  RecyclerView.ViewHolder viewHolder) {
                                super.clearView(recyclerView, viewHolder);
                                if (adapter != null) {
                                    adapter.onReorderFinished(activeFilter.name());
                                }
                            }
                        });
        helper.attachToRecyclerView(modsRecycler);
    }

    private void showSettingsSection() {
        updateNavigationItems(false, false, false, true, false, false, false);

        modulesContainer.setVisibility(View.GONE);
        hideCosmetics();
        hidePacks();
        hideVoice();
        hideReplay();
        hideSupporterPages();
        if (settingsContainer.getVisibility() != View.VISIBLE) {
            settingsContainer.setVisibility(View.VISIBLE);
            crossfade(settingsContainer);
        }

        if (overlayView != null) {
            View modConfigContainer = overlayView.findViewById(R.id.mod_config_container);
            View searchContainer = overlayView.findViewById(R.id.search_container);
            View configHeader = overlayView.findViewById(R.id.config_header);
            if (modConfigContainer != null) modConfigContainer.setVisibility(View.GONE);
            if (searchContainer != null) searchContainer.setVisibility(View.VISIBLE);
            if (configHeader != null) configHeader.setVisibility(View.GONE);
            if (filterBar != null) filterBar.setVisibility(View.GONE);
            if (compactFilterBar != null) compactFilterBar.setVisibility(View.GONE);
        }
    }

    /**
     * Wires the Settings tab's appearance controls (M6).
     *
     * <p>Segmented presets for the enumerated choices and one slider per continuous value, and
     * nothing free-form: a colour picker or a custom blur radius is the setting that generates
     * "why does my menu look broken" reports. Every control writes through
     * {@link InbuiltModManager#setModMenuSettings} and then re-applies, so the change is visible
     * immediately rather than on the next open.
     */
    private void setupModMenuAppearanceSettings() {
        if (overlayView == null) return;
        if (menuSettings == null) {
            menuSettings = InbuiltModManager.getInstance(activity).getModMenuSettings();
        }
        final ModMenuSettings current = menuSettings;

        bindSegmentedGroup(R.id.group_mod_setting_theme, new String[]{
                activity.getString(R.string.mod_menu_theme_navy),
                activity.getString(R.string.mod_menu_theme_true_black),
                activity.getString(R.string.mod_menu_theme_system)},
                current.theme().ordinal(),
                index -> applyMenuSettings(current.withTheme(
                        ModMenuSettings.Theme.values()[index])));

        bindSegmentedGroup(R.id.group_mod_setting_glass, new String[]{
                activity.getString(R.string.mod_menu_glass_off),
                activity.getString(R.string.mod_menu_glass_subtle),
                activity.getString(R.string.mod_menu_glass_full)},
                current.glassIntensity().ordinal(),
                index -> applyMenuSettings(current.withGlassIntensity(
                        ModMenuSettings.GlassIntensity.values()[index])));

        bindSegmentedGroup(R.id.group_mod_setting_density, new String[]{
                activity.getString(R.string.mod_menu_density_compact),
                activity.getString(R.string.mod_menu_density_comfortable)},
                current.density().ordinal(),
                index -> applyMenuSettings(current.withDensity(
                        ModMenuSettings.Density.values()[index])));

        bindSegmentedGroup(R.id.group_mod_setting_toggle_style, new String[]{
                activity.getString(R.string.mod_menu_toggle_switch),
                activity.getString(R.string.mod_menu_toggle_pill_label),
                activity.getString(R.string.mod_menu_toggle_checkbox)},
                current.toggleStyle().ordinal(),
                index -> applyMenuSettings(current.withToggleStyle(
                        ModMenuSettings.ToggleStyle.values()[index])));

        bindSegmentedGroup(R.id.group_mod_setting_motion, new String[]{
                activity.getString(R.string.mod_menu_motion_full),
                activity.getString(R.string.mod_menu_motion_reduced),
                activity.getString(R.string.mod_menu_motion_system)},
                current.motion().ordinal(),
                index -> applyMenuSettings(current.withMotion(
                        ModMenuSettings.Motion.values()[index])));

        bindAppearanceSlider(R.id.seekbar_mod_setting_radius, R.id.value_mod_setting_radius,
                ModMenuSettings.MIN_RADIUS_DP, ModMenuSettings.MAX_RADIUS_DP,
                current.cornerRadiusDp(), R.string.mod_menu_setting_value_dp,
                value -> applyMenuSettings(current.withCornerRadiusDp(value)));

        bindAppearanceSlider(R.id.seekbar_mod_setting_blur_fps, R.id.value_mod_setting_blur_fps,
                ModMenuSettings.MIN_BLUR_FPS, ModMenuSettings.MAX_BLUR_FPS,
                current.blurFps(), R.string.mod_menu_setting_value_fps,
                value -> applyMenuSettings(current.withBlurFps(value)));

        bindAppearanceSlider(R.id.seekbar_mod_setting_hud_opacity,
                R.id.value_mod_setting_hud_opacity,
                ModMenuSettings.MIN_HUD_OPACITY, ModMenuSettings.MAX_HUD_OPACITY,
                current.hudOpacity(), R.string.mod_menu_setting_value_percent,
                value -> applyMenuSettings(current.withHudOpacity(value)));

        buildAccentSwatches(current);
    }

    /**
     * Builds a segmented row from a label array and reports the chosen index.
     *
     * <p>The chips are plain TextViews rather than a Spinner: a Spinner's popup is a second window
     * that outlives the host view, and the menu already had a stale-popup bug from one.
     */
    private void bindSegmentedGroup(int groupId, String[] labels, int selected,
                                    java.util.function.IntConsumer onSelected) {
        ViewGroup group = overlayView.findViewById(groupId);
        if (group == null) return;
        group.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            TextView chip = new TextView(activity);
            chip.setText(labels[i]);
            chip.setTextSize(12);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding((int) (14 * density()), (int) (8 * density()),
                    (int) (14 * density()), (int) (8 * density()));
            boolean active = i == selected;
            chip.setTextColor(active ? ModMenuPalette.TEXT_PRIMARY : ModMenuPalette.TEXT_SECONDARY);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(ModMenuMetrics.PANEL_RADIUS_DP / 2f * density());
            bg.setColor(active
                    ? ModMenuPalette.ACCENT
                    : ModMenuPalette.BG_SURFACE_3);
            chip.setBackground(bg);
            android.widget.LinearLayout.LayoutParams params =
                    new android.widget.LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.setMarginEnd((int) (6 * density()));
            chip.setLayoutParams(params);
            final int index = i;
            chip.setOnClickListener(v -> onSelected.accept(index));
            DynamicAnim.applyPressScale(chip);
            group.addView(chip);
        }
    }

    /** Binds one appearance slider and its value label. */
    private void bindAppearanceSlider(int seekBarId, int valueId, int min, int max, int current,
                                      int valueFormatRes,
                                      java.util.function.IntConsumer onChanged) {
        SeekBar bar = overlayView.findViewById(seekBarId);
        TextView label = overlayView.findViewById(valueId);
        if (bar == null) return;
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, current - min)));
        if (label != null) {
            label.setText(activity.getString(valueFormatRes, current));
        }
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = min + progress;
                if (label != null) {
                    label.setText(activity.getString(valueFormatRes, value));
                }
                if (fromUser) onChanged.accept(value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    /** Builds the six accent swatches (M6). */
    private void buildAccentSwatches(ModMenuSettings current) {
        ViewGroup group = overlayView.findViewById(R.id.group_mod_setting_accent);
        if (group == null) return;
        group.removeAllViews();
        int[] swatches = ModMenuSettings.ACCENT_SWATCHES;
        for (int i = 0; i < swatches.length; i++) {
            View swatch = new View(activity);
            int size = (int) (32 * density());
            android.widget.LinearLayout.LayoutParams params =
                    new android.widget.LinearLayout.LayoutParams(size, size);
            params.setMarginEnd((int) (10 * density()));
            swatch.setLayoutParams(params);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(swatches[i]);
            if (i == current.accentIndex()) {
                bg.setStroke((int) Math.max(2f, 2f * density()), ModMenuPalette.TEXT_PRIMARY);
            }
            swatch.setBackground(bg);
            swatch.setContentDescription(activity.getString(R.string.mod_menu_setting_accent)
                    + " " + (i + 1));
            final int index = i;
            swatch.setOnClickListener(v -> applyMenuSettings(current.withAccentIndex(index)));
            DynamicAnim.applyPressScale(swatch);
            group.addView(swatch);
        }
    }

    /**
     * Persists and applies a new appearance settings value (M6).
     *
     * <p>Re-applying immediately is what makes the settings tab useful: a change is visible on the
     * menu behind the sheet rather than on the next open.
     */
    private void applyMenuSettings(ModMenuSettings settings) {
        if (settings == null) return;
        menuSettings = settings;
        InbuiltModManager.getInstance(activity).setModMenuSettings(settings);
        if (adapter != null) {
            adapter.setSettings(settings);
            adapter.setCompactMode(compactMode);
        }
        theme = new ModMenuTheme(activity, settings);
        if (adapter != null) adapter.setTheme(theme);
        applyCompactModeLayout(compactMode);
        applyMenuOpacity();
        // The controls are rebuilt so the active chip/swatch reflects the new value.
        setupModMenuAppearanceSettings();
        if (modsRecycler != null && modsRecycler.getAdapter() != null) {
            modsRecycler.getAdapter().notifyDataSetChanged();
        }
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }

    /**
     * Wires the Settings tab's keybind rows.
     *
     * Keyboard capture listens on the row itself (the row is focusable and grabs the key), while
     * the controller capture opens a dialog carrying a live illustration — a control that opens a
     * "press a button" prompt only works if the prompt can actually receive the button, so the
     * dialog takes focus and forwards every key/motion event to the illustration.
     */
    private void setupKeybindSettings(InbuiltModManager modManager) {
        keyboardBindText = overlayView.findViewById(R.id.text_keyboard_bind);
        controllerBindText = overlayView.findViewById(R.id.text_controller_bind);
        controllerBindIllustration = overlayView.findViewById(R.id.bind_controller_illustration);
        View keyboardRow = overlayView.findViewById(R.id.setting_keyboard_bind);
        View controllerRow = overlayView.findViewById(R.id.setting_controller_bind);

        refreshKeyboardBindLabel(modManager);
        refreshControllerBindLabel(modManager);
        refreshControllerBindIllustration(modManager);

        if (keyboardRow != null) {
            keyboardRow.setFocusableInTouchMode(true);
            keyboardRow.setOnClickListener(v -> openKeyboardBindCapture(modManager));
        }
        if (controllerRow != null) {
            controllerRow.setOnClickListener(v -> openControllerBindPicker(modManager));
        }
    }

    /**
     * Wires the Settings tab's loadout row: a chip per saved set (tap to apply, long-press to
     * delete) and a "save current" action. A loadout only touches the ids it stored, so applying
     * an older preset cannot switch off a module added since.
     */
    private void setupLoadoutSettings(InbuiltModManager modManager) {
        loadoutChipRow = overlayView.findViewById(R.id.loadout_chip_row);
        View saveRow = overlayView.findViewById(R.id.setting_save_loadout);
        if (saveRow != null) {
            saveRow.setOnClickListener(v -> promptSaveLoadout(modManager));
        }
        rebuildLoadoutChips(modManager);
    }

    private void rebuildLoadoutChips(InbuiltModManager modManager) {
        if (loadoutChipRow == null) return;
        loadoutChipRow.removeAllViews();
        java.util.List<ModLoadoutStore.Loadout> loadouts = modManager.getModLoadouts();

        if (loadouts.isEmpty()) {
            TextView hint = new TextView(activity);
            hint.setText(R.string.mod_menu_loadout_none);
            hint.setTextColor(0xFF888888);
            hint.setTextSize(11f);
            loadoutChipRow.addView(hint);
            return;
        }

        int margin = (int) (8 * activity.getResources().getDisplayMetrics().density);
        for (ModLoadoutStore.Loadout loadout : loadouts) {
            TextView chip = new TextView(activity);
            chip.setText(loadout.name);
            chip.setTextColor(0xFFF1F4F6);
            chip.setTextSize(12f);
            chip.setPadding(margin * 2, margin, margin * 2, margin);
            chip.setBackgroundResource(R.drawable.bg_search_field);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = margin;
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                if (modManager.applyLoadout(loadout.name)) {
                    loadMods();
                    String name = loadout.name;
                    Toast.makeText(activity,
                            activity.getString(R.string.mod_menu_loadout_applied, name),
                            Toast.LENGTH_SHORT).show();
                }
            });
            chip.setOnLongClickListener(v -> {
                confirmDeleteLoadout(modManager, loadout.name);
                return true;
            });
            loadoutChipRow.addView(chip);
        }
    }

    private void promptSaveLoadout(InbuiltModManager modManager) {
        final EditText input = new EditText(activity);
        input.setHint(R.string.mod_menu_loadout_name_hint);
        // Seed with a sensible preset name so saving takes one tap in the common case.
        boolean empty = modManager.getModLoadouts().isEmpty();
        input.setText(empty ? activity.getString(R.string.mod_menu_loadout_preset_pvp) : "");

        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.mod_menu_loadout_save)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    modManager.saveLoadout(name, currentModuleIds());
                    rebuildLoadoutChips(modManager);
                    Toast.makeText(activity,
                            activity.getString(R.string.mod_menu_loadout_saved, name),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDeleteLoadout(InbuiltModManager modManager, String name) {
        new android.app.AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.mod_menu_loadout_delete_confirm, name))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    modManager.deleteLoadout(name);
                    rebuildLoadoutChips(modManager);
                    Toast.makeText(activity,
                            activity.getString(R.string.mod_menu_loadout_deleted, name),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private java.util.List<String> currentModuleIds() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (UnifiedMod mod : allMods) {
            if (mod.getSource() == UnifiedMod.Source.INBUILT) ids.add(mod.getId());
        }
        return ids;
    }

    private void refreshKeyboardBindLabel(InbuiltModManager modManager) {
        if (keyboardBindText == null) return;
        keyboardBindText.setText(bindLabel(modManager.getModMenuKeybind()));
    }

    private void refreshControllerBindLabel(InbuiltModManager modManager) {
        if (controllerBindText == null) return;
        int code = modManager.getModMenuControllerBind();
        controllerBindText.setText(code == 0
                ? activity.getString(R.string.mod_menu_bind_none)
                : bindLabel(code));
        refreshControllerBindIllustration(modManager);
    }

    /**
     * Shows the connected pad's 2D map beside the bind row, with the bound button lit green.
     *
     * A bare key name ("BUTTON L1") does not tell a player which physical button that is, so the
     * whole controller is drawn and the bound control highlighted. Hidden until a bind exists —
     * an unlit controller would read as a decorate nobody asked for.
     */
    private void refreshControllerBindIllustration(InbuiltModManager modManager) {
        if (controllerBindIllustration == null) return;
        int code = modManager.getModMenuControllerBind();
        if (code == 0) {
            controllerBindIllustration.setVisibility(View.GONE);
            return;
        }
        android.view.InputDevice device = firstGamepad();
        if (device == null) {
            controllerBindIllustration.setVisibility(View.GONE);
            return;
        }
        org.chimeramc.client.launcher.controller.ControllerType type =
                org.chimeramc.client.launcher.controller.ControllerType.from(device);
        if (type == null) type = org.chimeramc.client.launcher.controller.ControllerType.XBOX;
        controllerBindIllustration.setType(type);
        controllerBindIllustration.clearConfirmed();
        controllerBindIllustration.setRegionConfirmed(
                controllerBindIllustration.regionIdForKey(code), true);
        controllerBindIllustration.setVisibility(View.VISIBLE);
    }

    private String bindLabel(int keyCode) {
        if (keyCode == 0) return activity.getString(R.string.mod_menu_bind_none);
        String name = android.view.KeyEvent.keyCodeToString(keyCode);
        return name.startsWith("KEYCODE_") ? name.substring(8).replace('_', ' ') : name;
    }

    /** Captures the next key press (including the mouse buttons) on a focusable dialog. */
    private void openKeyboardBindCapture(InbuiltModManager modManager) {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.mod_menu_keyboard_bind)
                .setMessage(R.string.mod_menu_bind_press_key)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.mod_menu_bind_clear, (d, w) -> {
                    modManager.setModMenuKeybind(0);
                    refreshKeyboardBindLabel(modManager);
                })
                .create();
        dialog.setCanceledOnTouchOutside(false);

        Runnable commit = () -> {
            org.chimeramc.client.core.mods.inbuilt.vip.MenuBindRelay.clear();
            dialog.dismiss();
        };
        Runnable capture = () -> {
            org.chimeramc.client.core.mods.inbuilt.vip.MenuBindRelay.set(keyCode -> {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    commit.run();
                    return;
                }
                modManager.setModMenuKeybind(keyCode);
                refreshKeyboardBindLabel(modManager);
                commit.run();
            });
        };
        dialog.setOnDismissListener(d ->
                org.chimeramc.client.core.mods.inbuilt.vip.MenuBindRelay.clear());
        // The dialog's own key listener covers the launcher-side receiver; the static capture
        // covers the in-game path where the preloader dispatches keys first.
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                d.dismiss();
                return true;
            }
            modManager.setModMenuKeybind(keyCode);
            refreshKeyboardBindLabel(modManager);
            d.dismiss();
            return true;
        });
        capture.run();
        dialog.show();
    }

    /**
     * The controller bind picker.
     *
     * Shows the connected pad's illustration (a cached vector, not a live render, so nothing on
     * the game's frame budget is touched) and highlights the pressed button green for a moment
     * before closing, so the player sees exactly which control was captured.
     */
    private void openControllerBindPicker(InbuiltModManager modManager) {
        android.view.InputDevice device = firstGamepad();
        if (device == null) {
            android.widget.Toast.makeText(activity, R.string.mod_menu_bind_no_controller,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final org.chimeramc.client.launcher.controller.ControllerType type =
                org.chimeramc.client.launcher.controller.ControllerType.from(device) != null
                        ? org.chimeramc.client.launcher.controller.ControllerType.from(device)
                        : org.chimeramc.client.launcher.controller.ControllerType.XBOX;

        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_mod_menu_bind, null);
        final org.chimeramc.client.launcher.ui.views.Controller3DView illustration =
                content.findViewById(R.id.bind_dialog_illustration);
        final TextView statusText = content.findViewById(R.id.bind_dialog_status);
        illustration.setType(type);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d ->
                org.chimeramc.client.core.mods.inbuilt.vip.MenuBindRelay.clear());

        content.findViewById(R.id.bind_dialog_clear).setOnClickListener(v -> {
            modManager.setModMenuControllerBind(0);
            refreshControllerBindLabel(modManager);
            dialog.dismiss();
        });

        // The in-game activity offers every raw key here first, so a controller press is captured
        // even when the preloader would otherwise consume it.
        org.chimeramc.client.core.mods.inbuilt.vip.MenuBindRelay.set(keyCode -> {
            illustration.handleKeyEvent(keyCode, true);
            String region = illustration.regionIdForKey(keyCode);
            if (region == null) return;
            illustration.setRegionConfirmed(region, true);
            illustration.animateConfirm(region);
            statusText.setText(activity.getString(R.string.mod_menu_bind_detected, bindLabel(keyCode)));
            modManager.setModMenuControllerBind(keyCode);
            refreshControllerBindLabel(modManager);
            content.postDelayed(dialog::dismiss, 550);
        });

        // Every key and stick movement is mirrored on the illustration; a click on a pad button is
        // what commits the bind, after which the button flashes green and the dialog closes.
        dialog.setOnKeyListener((d, keyCode, event) -> {
            illustration.handleKeyEvent(keyCode, event.getAction() == android.view.KeyEvent.ACTION_DOWN);
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                d.dismiss();
                return true;
            }
            String region = illustration.regionIdForKey(keyCode);
            if (region == null) return true; // a gamepad key we do not map: swallow, keep waiting
            illustration.setRegionConfirmed(region, true);
            illustration.animateConfirm(region);
            statusText.setText(activity.getString(R.string.mod_menu_bind_detected, bindLabel(keyCode)));
            modManager.setModMenuControllerBind(keyCode);
            refreshControllerBindLabel(modManager);
            android.view.View target = content;
            (target != null ? target : content).postDelayed(dialog::dismiss, 550);
            return true;
        });

        View.OnGenericMotionListener motionListener = (v, event) -> {
            illustration.handleMotionEvent(event);
            return true;
        };
        content.setOnGenericMotionListener(motionListener);
        if (illustration != null) {
            illustration.requestFocus();
        }

        dialog.show();
    }

    private android.view.InputDevice firstGamepad() {
        android.hardware.input.InputManager im =
                (android.hardware.input.InputManager) activity.getSystemService(android.content.Context.INPUT_SERVICE);
        if (im == null) return null;
        for (int id : im.getInputDeviceIds()) {
            android.view.InputDevice device = im.getInputDevice(id);
            if (org.chimeramc.client.launcher.controller.ControllerConnectionMonitor
                    .isGamepad(device)) {
                return device;
            }
        }
        return null;
    }

    /**
     * Cosmetics: the player's Minecraft character plus capes and accessories.
     *
     * The panel is created lazily and owns the animated cape preview, so this method only
     * decides which section is on screen.
     */
    private void showCosmeticsSection() {
        updateNavigationItems(false, true, false, false, false, false, false);

        modulesContainer.setVisibility(View.GONE);
        settingsContainer.setVisibility(View.GONE);
        hidePacks();
        hideVoice();
        hideReplay();
        hideSupporterPages();
        if (cosmeticsContainer != null) {
            if (cosmeticsPanel == null) {
                cosmeticsPanel = new CosmeticsPanel(activity, compactMode);
                cosmeticsContainer.addView(cosmeticsPanel.getView(),
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
            }
            cosmeticsContainer.setVisibility(View.VISIBLE);
            // Re-resolve the skin so a change made since the Closet was last open appears now.
            cosmeticsPanel.onShown();
            crossfade(cosmeticsContainer);
        }

        if (overlayView != null) {
            View modConfigContainer = overlayView.findViewById(R.id.mod_config_container);
            View searchContainer = overlayView.findViewById(R.id.search_container);
            View configHeader = overlayView.findViewById(R.id.config_header);
            if (modConfigContainer != null) modConfigContainer.setVisibility(View.GONE);
            if (searchContainer != null) searchContainer.setVisibility(View.VISIBLE);
            if (configHeader != null) configHeader.setVisibility(View.GONE);
            if (filterBar != null) filterBar.setVisibility(View.GONE);
            if (compactFilterBar != null) compactFilterBar.setVisibility(View.GONE);
        }
    }

    private void hideCosmetics() {
        // A spinner's dropdown list is a separate window that survives hiding the container, so it
        // must be collapsed explicitly or it floats over the game after the menu closes.
        if (cosmeticsPanel != null) cosmeticsPanel.onHidden();
        if (cosmeticsContainer != null) cosmeticsContainer.setVisibility(View.GONE);
    }

    /**
     * Drops every panel that was attached to the previous overlay tree.
     *
     * <p>Called from {@code setupViews} after a re-inflate. Most panels are cheap and rebuilt on
     * entry; the ones that are cached must be released so they are rebuilt against the new tree,
     * and the Replay panel additionally owns a background executor and a live recorder listener,
     * so it is disposed rather than just dropped.
     */
    private void releasePanels() {
        if (replayPanel != null) {
            replayPanel.dispose();
            replayPanel = null;
        }
        cosmeticsPanel = null;
        packChangerPanel = null;
        voicePanel = null;
    }

    /**
     * The in-game pack changer: the instance's resource packs with an on/off toggle each.
     *
     * Built lazily and rebuilt on entry because the pack list changes whenever the player
     * imports a pack from the launcher. Only reachable when the instance enabled it, which the
     * panel reports itself rather than hiding the tab — a hidden destination reads as a bug.
     */
    private void showPacksSection() {
        updateNavigationItems(false, false, false, false, true, false, false);

        modulesContainer.setVisibility(View.GONE);
        settingsContainer.setVisibility(View.GONE);
        hideCosmetics();
        hidePacks();
        hideVoice();
        hideReplay();
        hideSupporterPages();
        if (packsContainer != null) {
            packsContainer.removeAllViews();
            packChangerPanel = new PackChangerPanel(activity, compactMode);
            packsContainer.addView(packChangerPanel.getView(),
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            packsContainer.setVisibility(View.VISIBLE);
            crossfade(packsContainer);
        }

        if (overlayView != null) {
            View modConfigContainer = overlayView.findViewById(R.id.mod_config_container);
            View searchContainer = overlayView.findViewById(R.id.search_container);
            View configHeader = overlayView.findViewById(R.id.config_header);
            if (modConfigContainer != null) modConfigContainer.setVisibility(View.GONE);
            if (searchContainer != null) searchContainer.setVisibility(View.GONE);
            if (configHeader != null) configHeader.setVisibility(View.VISIBLE);
            TextView configTitle = overlayView.findViewById(R.id.config_title);
            if (configTitle != null) configTitle.setText(R.string.pack_changer_title);
            if (filterBar != null) filterBar.setVisibility(View.GONE);
            if (compactFilterBar != null) compactFilterBar.setVisibility(View.GONE);
        }
    }

    /**
     * Voice: the proximity chat channel, its members and the public directory.
     *
     * The panel is built lazily and rebuilt on entry so it reflects the live channel state; it
     * never starts the module, which is the deliberate action behind the Mods-tab switch.
     */
    private void showVoiceSection() {
        updateNavigationItems(false, false, false, false, false, true, false);

        if (modulesContainer != null) modulesContainer.setVisibility(View.GONE);
        if (settingsContainer != null) settingsContainer.setVisibility(View.GONE);
        hideCosmetics();
        hidePacks();
        hideVoice();
        hideReplay();
        hideSupporterPages();
        if (voiceContainer != null) {
            voiceContainer.removeAllViews();
            voicePanel = new VoicePanel(activity, compactMode);
            voiceContainer.addView(voicePanel.getView(),
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            voiceContainer.setVisibility(View.VISIBLE);
            crossfade(voiceContainer);
        }

        if (overlayView != null) {
            View modConfigContainer = overlayView.findViewById(R.id.mod_config_container);
            View searchContainer = overlayView.findViewById(R.id.search_container);
            View configHeader = overlayView.findViewById(R.id.config_header);
            if (modConfigContainer != null) modConfigContainer.setVisibility(View.GONE);
            if (searchContainer != null) searchContainer.setVisibility(View.GONE);
            if (configHeader != null) configHeader.setVisibility(View.VISIBLE);
            TextView configTitle = overlayView.findViewById(R.id.config_title);
            if (configTitle != null) configTitle.setText(R.string.voice_chat_title);
            if (filterBar != null) filterBar.setVisibility(View.GONE);
            if (compactFilterBar != null) compactFilterBar.setVisibility(View.GONE);
        }
    }

    /**
     * Refreshes the panel if it is the section on screen.
     *
     * Called while the menu is open so a channel joined from elsewhere, or a peer arriving, shows
     * up without reopening the menu.
     */
    void refreshVoiceSectionIfVisible() {
        if (voicePanel != null && voiceContainer != null
                && voiceContainer.getVisibility() == View.VISIBLE) {
            voicePanel.refresh();
        }
    }

    private void hideVoice() {
        if (voiceContainer != null) voiceContainer.setVisibility(View.GONE);
    }

    /**
     * Replay: the recorder controls and the clip library.
     *
     * <p>Built lazily and kept alive once built, because the panel owns a background executor and
     * the live recorder listener. It is the same {@link ReplayPanel} the launcher's Replay screen
     * hosts, so both surfaces show one feature rather than two implementations.
     */
    private void showReplaySection() {
        updateNavigationItems(false, false, false, false, false, false, true);

        if (modulesContainer != null) modulesContainer.setVisibility(View.GONE);
        if (settingsContainer != null) settingsContainer.setVisibility(View.GONE);
        hideCosmetics();
        hidePacks();
        hideVoice();
        hideSupporterPages();
        if (replayContainer != null) {
            if (replayPanel == null) {
                replayPanel = new ReplayPanel(activity, compactMode);
                replayContainer.addView(replayPanel.getView(),
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
            }
            replayPanel.setGamepadDetected(hasConnectedGamepad());
            replayContainer.setVisibility(View.VISIBLE);
            replayPanel.onShown();
            crossfade(replayContainer);
        }

        if (overlayView != null) {
            View modConfigContainer = overlayView.findViewById(R.id.mod_config_container);
            View searchContainer = overlayView.findViewById(R.id.search_container);
            View configHeader = overlayView.findViewById(R.id.config_header);
            if (modConfigContainer != null) modConfigContainer.setVisibility(View.GONE);
            if (searchContainer != null) searchContainer.setVisibility(View.GONE);
            if (configHeader != null) configHeader.setVisibility(View.VISIBLE);
            TextView configTitle = overlayView.findViewById(R.id.config_title);
            if (configTitle != null) configTitle.setText(R.string.replay_header_title);
            if (filterBar != null) filterBar.setVisibility(View.GONE);
            if (compactFilterBar != null) compactFilterBar.setVisibility(View.GONE);
        }
    }

    /** True when any device on the InputManager reports a gamepad source. */
    private boolean hasConnectedGamepad() {
        try {
            android.hardware.input.InputManager inputManager =
                    (android.hardware.input.InputManager)
                            activity.getSystemService(android.content.Context.INPUT_SERVICE);
            if (inputManager == null) return false;
            for (int id : inputManager.getInputDeviceIds()) {
                android.view.InputDevice device = inputManager.getInputDevice(id);
                if (device == null) continue;
                if (ControllerConnectionMonitor.isGamepad(device)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void hideReplay() {
        if (replayContainer != null) replayContainer.setVisibility(View.GONE);
        if (replayPanel != null) replayPanel.onHidden();
    }

    private void hidePacks() {
        if (packsContainer != null) packsContainer.setVisibility(View.GONE);
    }

    private void showConfigSection(UnifiedMod mod) {
        if (mod.openCustomConfig()) {
            hide();
            return;
        }
        updateNavigationItems(false, false, false, false, false, false, false);

        modulesContainer.setVisibility(View.GONE);
        settingsContainer.setVisibility(View.GONE);
        hideCosmetics();
        hidePacks();
        hideReplay();
        hideSupporterPages();

        if (overlayView != null) {
            View modConfigContainer = overlayView.findViewById(R.id.mod_config_container);
            View searchContainer = overlayView.findViewById(R.id.search_container);
            View configHeader = overlayView.findViewById(R.id.config_header);
            ViewGroup modConfigContent = overlayView.findViewById(R.id.mod_config_content);
            TextView configTitle = overlayView.findViewById(R.id.config_title);

            if (modConfigContainer != null) {
                modConfigContainer.setVisibility(View.VISIBLE);
                crossfade(modConfigContainer);
            }
            if (searchContainer != null) searchContainer.setVisibility(View.GONE);
            if (configHeader != null) configHeader.setVisibility(View.VISIBLE);
            if (filterBar != null) filterBar.setVisibility(View.GONE);
            if (compactFilterBar != null) compactFilterBar.setVisibility(View.GONE);
            if (configTitle != null) configTitle.setText(mod.getName());

            if (modConfigContent != null) {
                ModConfigView.render(activity, modConfigContent, mod, compactMode, () -> {
                    InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
                    if (overlayManager != null) {
                        overlayManager.applyConfigurationChanges(mod.getId());
                    }
                });
            }
        }
    }

    private void setupHudEditorDragHandle(View handle, View tools) {
        final int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private int pointerId = -1;
            private boolean dragging;

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (overlayView == null || tools == null || tools.getVisibility() != View.VISIBLE) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pointerId = event.getPointerId(0);
                        downX = event.getRawX();
                        downY = event.getRawY();
                        dragging = false;
                        if (wmParams != null) {
                            startX = wmParams.x;
                            startY = wmParams.y;
                        } else {
                            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
                            startX = params.leftMargin;
                            startY = params.topMargin;
                        }
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (pointerId < 0 || event.getPointerId(0) != pointerId) return true;
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) dragging = true;
                        if (dragging) moveHudEditorTools(startX + Math.round(dx), startY + Math.round(dy));
                        return true;
                    case MotionEvent.ACTION_POINTER_UP:
                        if (event.getPointerId(event.getActionIndex()) != pointerId) return true;
                        pointerId = -1;
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (pointerId >= 0 && !dragging) view.performClick();
                        // Fall through to release the gesture on both up and cancellation.
                    case MotionEvent.ACTION_CANCEL:
                        pointerId = -1;
                        dragging = false;
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void moveHudEditorTools(int x, int y) {
        if (overlayView == null) return;
        OverlayBounds.Position position = OverlayBounds.clampPosition(activity, overlayView, x, y);
        if (wmParams != null && windowManager != null) {
            wmParams.x = position.x;
            wmParams.y = position.y;
            windowManager.updateViewLayout(overlayView, wmParams);
        } else if (overlayView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
            params.leftMargin = position.x;
            params.topMargin = position.y;
            overlayView.setLayoutParams(params);
        }
    }

    private void enterHudEditorMode(View modMenuContainer, View hudEditorTools) {
        updateNavigationItems(false, false, true, false, false, false, false);

        if (modMenuContainer != null) {
            modMenuContainer.setVisibility(View.GONE);
        }
        if (hudEditorTools != null) {
            hudEditorTools.setVisibility(View.VISIBLE);
            hudEditorTools.setAlpha(1f);
            hudEditorTools.setTranslationX(0f);
        }
        if (overlayView != null) {
            overlayView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            overlayView.setClickable(false);
        }
        if (wmParams != null && windowManager != null) {
            wmParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
            wmParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            wmParams.gravity = Gravity.TOP | Gravity.LEFT;
            wmParams.x = 0;
            wmParams.y = 0;
            windowManager.updateViewLayout(overlayView, wmParams);
        } else if (overlayView != null && overlayView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.leftMargin = 0;
            params.topMargin = 0;
            overlayView.setLayoutParams(params);
        }
        // Wait for the compact tool window to be measured before centering it.
        final View editorRoot = overlayView;
        if (editorRoot != null) editorRoot.post(() -> {
            if (overlayView != editorRoot || hudEditorTools == null || hudEditorTools.getVisibility() != View.VISIBLE) return;
            OverlayBounds.Position rightEdge = OverlayBounds.clampPosition(
                    activity, editorRoot, Integer.MAX_VALUE, 0);
            moveHudEditorTools(rightEdge.x / 2, 0);
        });
        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        if (overlayManager != null) {
            overlayManager.setHudEditorSelectionListener(this::updateHudEditorSizeControls);
            overlayManager.setHudEditorMode(true);
        }
    }

    private void exitHudEditorMode(View modMenuContainer, View hudEditorTools) {
        if (hudEditorTools != null) {
            hudEditorTools.setVisibility(View.GONE);
        }
        if (overlayView != null) {
            overlayView.setClickable(true);
        }
        if (wmParams != null && windowManager != null) {
            wmParams.width = WindowManager.LayoutParams.MATCH_PARENT;
            wmParams.height = WindowManager.LayoutParams.MATCH_PARENT;
            wmParams.gravity = Gravity.CENTER;
            wmParams.x = 0;
            wmParams.y = 0;
            windowManager.updateViewLayout(overlayView, wmParams);
        } else if (overlayView != null && overlayView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.CENTER;
            params.leftMargin = 0;
            params.topMargin = 0;
            overlayView.setLayoutParams(params);
        }
        if (modMenuContainer != null) {
            modMenuContainer.setVisibility(View.VISIBLE);
            animateMenuEnter(modMenuContainer);
        }
        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        if (overlayManager != null) {
            overlayManager.setHudEditorMode(false);
            overlayManager.setHudEditorSelectionListener(null);
        }
        showModulesSection();
    }

    private void updateHudEditorSizeControls(int sizeDp) {
        if (hudButtonSizeSeekBar == null) return;
        boolean hasSelection = sizeDp > 0;
        hudButtonSizeSeekBar.setEnabled(hasSelection);
        if (!hasSelection) {
            updateHudButtonSizeText(0);
            return;
        }
        updatingHudButtonSize = true;
        hudButtonSizeSeekBar.setProgress(sizeDp);
        updatingHudButtonSize = false;
        updateHudButtonSizeText(sizeDp);
    }

    private void updateHudButtonSizeText(int size) {
        if (hudButtonSizeText == null) return;
        if (size <= 0) {
            hudButtonSizeText.setText(activity.getString(R.string.overlay_button_size));
        } else {
            hudButtonSizeText.setText(activity.getString(R.string.overlay_button_size_value, size));
        }
    }

    private void setupCompactFilter() {
        if (compactFilterSelector == null) return;
        compactFilterSelector.setOnClickListener(this::showCompactFilterMenu);
        updateCompactFilterSelector();
    }

    private void showCompactFilterMenu(View anchor) {
        PopupMenu popup = new PopupMenu(activity, anchor);
        popup.getMenu().add(0, 100, 0, R.string.filter_all);
        popup.getMenu().add(0, 101, 1, R.string.mod_menu_favorites);
        popup.getMenu().add(0, 102, 2, R.string.mod_menu_filter_enabled);
        popup.getMenu().add(0, 103, 3, R.string.mod_menu_filter_inbuilt);
        popup.getMenu().add(0, 104, 4, R.string.mod_menu_filter_external);
        popup.getMenu().add(0, 105, 5, R.string.mod_menu_filter_pvp);
        popup.getMenu().add(0, 106, 6, R.string.vip_category_visuals);
        popup.getMenu().add(0, 107, 7, R.string.vip_category_misc);
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 101:
                    setModuleFilter(ModuleFilter.FAVORITES);
                    break;
                case 102:
                    setModuleFilter(ModuleFilter.ENABLED);
                    break;
                case 103:
                    setModuleFilter(ModuleFilter.INBUILT);
                    break;
                case 104:
                    setModuleFilter(ModuleFilter.EXTERNAL);
                    break;
                case 105:
                    setModuleFilter(ModuleFilter.PVP);
                    break;
                case 106:
                    setModuleFilter(ModuleFilter.VISUALS);
                    break;
                case 107:
                    setModuleFilter(ModuleFilter.MISC);
                    break;
                case 100:
                default:
                    setModuleFilter(ModuleFilter.ALL);
                    break;
            }
            return true;
        });
        popup.show();
    }

    private void setCompactMode(boolean compact) {
        compactMode = compact;
        if (modsLayoutManager != null) {
            modsLayoutManager.setSpanCount(compact ? 1 : 4);
        }
        if (adapter != null) {
            adapter.setCompactMode(compact);
            // Rebuild through the filter path rather than handing the adapter the module list
            // alone: the two-argument updateMods appends no section actions, so toggling density
            // silently dropped the ACTIONS block (Reset HUD Position / Reload Modules) until the
            // next filter change. applyFilters() recomputes both the modules and the actions for
            // the section that is on screen.
            applyFilters();
        }
        applyCompactModeLayout(compact);
        updateFilterBarVisibility();
        updateFilterButtons();
        updateModuleCount();
    }

    private void applyCompactModeLayout(boolean compact) {
        if (menuContainer == null) return;
        ViewGroup.LayoutParams rawParams = menuContainer.getLayoutParams();
        if (rawParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) rawParams;
            if (compact) {
                int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
                int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
                int availableWidth = Math.max(1, screenWidth - dp(36));
                int desiredWidth = Math.min(dp(392), Math.round(screenWidth * 0.40f));
                int minimumWidth = Math.min(dp(312), availableWidth);
                params.width = Math.min(availableWidth, Math.max(minimumWidth, desiredWidth));
                int availableHeight = Math.max(1, screenHeight - dp(24));
                params.height = Math.min(dp(560), availableHeight);
                params.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
                params.setMargins(dp(18), dp(12), 0, dp(12));
                params.setMarginStart(dp(18));
                params.setMarginEnd(0);
            } else {
                int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
                int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
                int width = Math.min(dp(UiTokens.WINDOW_MAX_WIDTH_DP),
                        Math.round(screenWidth * UiTokens.WINDOW_WIDTH_FRACTION));
                params.width = Math.max(1, width);
                params.height = Math.max(1, Math.round(screenHeight * UiTokens.WINDOW_HEIGHT_FRACTION));
                params.gravity = Gravity.CENTER;
                params.setMargins(0, 0, 0, 0);
                params.setMarginStart(0);
                params.setMarginEnd(0);
            }
            menuContainer.setLayoutParams(params);
        }

        // The top bar keeps every destination reachable in both modes. Compact mode only
        // narrows the window; it must never hide the navigation, which is what the old
        // sidebar/compact-icon swap did once the compact icons were removed.
        boolean modulesSelected = modulesContainer != null && modulesContainer.getVisibility() == View.VISIBLE;
        boolean settingsSelected = settingsContainer != null && settingsContainer.getVisibility() == View.VISIBLE;
        boolean cosmeticsSelected = cosmeticsContainer != null && cosmeticsContainer.getVisibility() == View.VISIBLE;
        boolean packsSelected = packsContainer != null && packsContainer.getVisibility() == View.VISIBLE;
        boolean voiceSelected = voiceContainer != null && voiceContainer.getVisibility() == View.VISIBLE;
        boolean replaySelected = replayContainer != null && replayContainer.getVisibility() == View.VISIBLE;
        updateNavigationItems(modulesSelected, cosmeticsSelected, false, settingsSelected, packsSelected, voiceSelected, replaySelected);

        if (modsRecycler != null) {
            int padding = dp(compact ? 4 : 14);
            modsRecycler.setPadding(padding, padding, padding, padding);
        }
        menuContainer.requestLayout();
    }

    private void updateFilterBarVisibility() {
        boolean modulesVisible = modulesContainer != null && modulesContainer.getVisibility() == View.VISIBLE;
        if (filterBar != null) {
            filterBar.setVisibility(modulesVisible && !compactMode ? View.VISIBLE : View.GONE);
        }
        if (compactFilterBar != null) {
            compactFilterBar.setVisibility(modulesVisible && compactMode ? View.VISIBLE : View.GONE);
        }
    }

    private void updateCompactFilterSelector() {
        if (compactFilterSelector == null) return;
        compactFilterSelector.setText(getFilterLabelRes(activeFilter));
        compactFilterSelector.setTextColor(getAccentColor());
    }

    private int getFilterLabelRes(ModuleFilter filter) {
        switch (filter) {
            case FAVORITES:
                return R.string.mod_menu_favorites;
            case ENABLED:
                return R.string.mod_menu_filter_enabled;
            case INBUILT:
                return R.string.mod_menu_filter_inbuilt;
            case EXTERNAL:
                return R.string.mod_menu_filter_external;
            case PVP:
                return R.string.mod_menu_filter_pvp;
            case VISUALS:
                return R.string.vip_category_visuals;
            case MISC:
                return R.string.vip_category_misc;
            case ALL:
            default:
                return R.string.filter_all;
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void setupFilterButtons() {
        if (filterAll != null) {
            filterAll.setOnClickListener(v -> setModuleFilter(ModuleFilter.ALL));
        }
        if (filterFavorites != null) {
            filterFavorites.setOnClickListener(v -> setModuleFilter(ModuleFilter.FAVORITES));
        }
        if (filterEnabled != null) {
            filterEnabled.setOnClickListener(v -> setModuleFilter(ModuleFilter.ENABLED));
        }
        if (filterInbuilt != null) {
            filterInbuilt.setOnClickListener(v -> setModuleFilter(ModuleFilter.INBUILT));
        }
        if (filterExternal != null) {
            filterExternal.setOnClickListener(v -> setModuleFilter(ModuleFilter.EXTERNAL));
        }
        if (filterPvp != null) {
            filterPvp.setOnClickListener(v -> setModuleFilter(ModuleFilter.PVP));
        }
        if (filterVisuals != null) {
            filterVisuals.setOnClickListener(v -> setModuleFilter(ModuleFilter.VISUALS));
        }
        if (filterMisc != null) {
            filterMisc.setOnClickListener(v -> setModuleFilter(ModuleFilter.MISC));
        }
        updateFilterButtons();
    }

    private void setModuleFilter(ModuleFilter filter) {
        activeFilter = filter;
        updateFilterButtons();
        applyFilters();
    }

    private void applyFilters() {
        filteredMods.clear();
        String query = searchInput != null
            ? searchInput.getText().toString().trim().toLowerCase(Locale.ROOT)
            : "";

        Map<String, GroupedMods> groupedMatches = new LinkedHashMap<>();
        for (UnifiedMod mod : allMods) {
            if (matchesActiveFilter(mod) && matchesQuery(mod, query)) {
                GroupedMods group = groupedMatches.computeIfAbsent(
                    mod.getGroupId(), ignored -> new GroupedMods());
                group.add(mod, isFavorite(mod));
            }
        }
        for (GroupedMods group : groupedMatches.values()) {
            group.appendTo(filteredMods);
        }

        if (adapter != null) {
            List<ModMenuAction> sectionActions = actionsForActiveSection(query);
            visibleActionCount = sectionActions.size();
            adapter.updateMods(filteredMods, favoriteKeys, sectionActions);
            // M9: the user's saved order is applied after the shipped order is loaded, so an
            // update that adds a module does not discard the arrangement the player made.
            adapter.applySavedOrder(InbuiltModManager.getInstance(activity)
                    .getModMenuSectionOrder(activeFilter.name()));
            if (!hasStaggeredOnce) {
                hasStaggeredOnce = true;
                DynamicAnim.staggerRecyclerChildren(modsRecycler);
            }
        } else {
            visibleActionCount = actionsForActiveSection(query).size();
        }
        updateEmptyState();
        updateModuleCount();
    }

    /**
     * The one-shot actions for the section the current filter shows (M3).
     *
     * <p>The section rule lives in {@link ModMenuActionVisibility}, so the action set cannot drift
     * from the module set it sits under. A search query narrows the actions the same way it narrows
     * the modules, matched against the action's own title and description.
     */
    private List<ModMenuAction> actionsForActiveSection(String query) {
        List<ModMenuAction> out = new ArrayList<>();
        for (ModMenuAction action : ModMenuActionCatalog.all()) {
            if (!ModMenuActionVisibility.isVisibleIn(activeFilter.name(), action.getGroupId())) {
                continue;
            }
            if (!query.isEmpty() && !matchesActionQuery(action, query)) {
                continue;
            }
            out.add(action);
        }
        return out;
    }

    /** True when the query matches the action's title or description text. */
    private boolean matchesActionQuery(ModMenuAction action, String query) {
        String title = activity.getString(action.getTitleRes());
        String subtitle = action.getSubtitleRes() != 0
                ? activity.getString(action.getSubtitleRes()) : "";
        return (title + " " + subtitle).toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean matchesActiveFilter(UnifiedMod mod) {
        switch (activeFilter) {
            case FAVORITES:
                return isFavorite(mod);
            case ENABLED:
                return mod.isEnabled();
            case INBUILT:
                return mod.getSource() == UnifiedMod.Source.INBUILT;
            case EXTERNAL:
                return mod.getSource() == UnifiedMod.Source.EXTERNAL;
            case PVP:
                return ModIds.isPvpModule(mod.getId());
            case VISUALS:
                return ModCategory.matches(ModCategory.Tab.VISUALS, mod, null);
            case MISC:
                return ModCategory.matches(ModCategory.Tab.MISC, mod, null);
            case ALL:
            default:
                return true;
        }
    }

    private boolean matchesQuery(UnifiedMod mod, String query) {
        if (query.isEmpty()) return true;
        String searchText = (
            safeString(mod.getName()) + " " +
            safeString(mod.getDescription()) + " " +
            safeString(mod.getId()) + " " +
            safeString(mod.getModId()) + " " +
            safeString(mod.getGroupName()) + " " +
            safeString(mod.getGroupId())
        ).toLowerCase(Locale.ROOT);
        return searchText.contains(query);
    }

    private boolean isFavorite(UnifiedMod mod) {
        return favoriteKeys.contains(mod.getStableKey());
    }

    private String safeString(String value) {
        return value != null ? value : "";
    }

    private void updateFilterButtons() {
        updateFilterButton(filterAll, activeFilter == ModuleFilter.ALL);
        updateFilterButton(filterFavorites, activeFilter == ModuleFilter.FAVORITES);
        updateFilterButton(filterEnabled, activeFilter == ModuleFilter.ENABLED);
        updateFilterButton(filterInbuilt, activeFilter == ModuleFilter.INBUILT);
        updateFilterButton(filterExternal, activeFilter == ModuleFilter.EXTERNAL);
        updateFilterButton(filterPvp, activeFilter == ModuleFilter.PVP);
        updateFilterButton(filterVisuals, activeFilter == ModuleFilter.VISUALS);
        updateFilterButton(filterMisc, activeFilter == ModuleFilter.MISC);
        updateCompactFilterSelector();
    }

    private void updateFilterButton(TextView view, boolean selected) {
        if (view == null) return;
        view.setTextColor(selected ? getAccentColor() : 0xFFA8B0B8);
        view.setAlpha(1f);
        Drawable background = view.getBackground();
        if (background != null) {
            background.mutate().setTint(selected ? theme.accentFill(51) : 0xFF24282C);
        }
    }

    /**
     * Re-tints one top-bar entry for the selected/unselected state.
     *
     * Each entry is a single {@code TextView} carrying its icon as a compound drawable, so
     * tinting the compound drawable is what colours the icon — there is no separate
     * {@code ImageButton} to keep in step.
     */
    private void updateNavigationItem(TextView view, boolean selected) {
        if (view == null) return;
        int color = selected ? getAccentColor() : 0xFFA8B0B8;
        view.setTextColor(color);
        view.setAlpha(selected ? 1f : 0.82f);
        view.setCompoundDrawableTintList(ColorStateList.valueOf(color));
        view.setTypeface(view.getTypeface(), selected
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    /**
     * Tints every top-bar entry, selected or not.
     *
     * <p>One explicit boolean per destination, in the layout's left-to-right order, so adding a tab
     * is a compile error at each call site rather than a silently untinted entry.
     */
    private void updateNavigationItems(boolean modules, boolean cosmetics, boolean hudEditor,
                                       boolean settings, boolean packs, boolean voice,
                                       boolean replay) {
        updateNavigationItem(navVoice, voice);
        updateNavigationItem(navGeneral, modules);
        updateNavigationItem(navCosmetics, cosmetics);
        updateNavigationItem(navHudEditor, hudEditor);
        updateNavigationItem(navSettings, settings);
        updateNavigationItem(navPacks, packs);
        updateNavigationItem(navReplay, replay);
        // The rail is the same selection in icon form. The explicit booleans above still drive the
        // (now hidden) text entries, so a caller that lights a destination also lights its rail
        // tile. Exactly one boolean is true per call site.
        if (rail != null) {
            ModMenuPage page = null;
            if (voice) page = ModMenuPage.VOICE;
            else if (modules) page = ModMenuPage.GENERAL;
            else if (cosmetics) page = ModMenuPage.COSMETICS;
            else if (hudEditor) page = ModMenuPage.HUD_EDITOR;
            else if (settings) page = ModMenuPage.SETTINGS;
            else if (packs) page = ModMenuPage.PACKS;
            else if (replay) page = ModMenuPage.REPLAY;
            if (page != null) rail.setSelected(page);
            if (page != null) updateTopBarTitle(page);
            if (page != null) activePage = page;
        }
    }

    /** Sets the top-bar title to the active destination, like the VIP shell's lockup. */
    private void updateTopBarTitle(ModMenuPage page) {
        if (overlayView == null || page == null) return;
        TextView title = overlayView.findViewById(R.id.mod_menu_title);
        if (title != null) {
            title.setText(page.getTitleRes());
        }
    }

    /** Builds the destination rail from the shared page registry. */
    private void buildRail() {
        LinearLayout railContainer = overlayView == null
                ? null : overlayView.findViewById(R.id.mod_menu_tab_bar);
        if (railContainer == null) return;
        rail = new ModMenuRail(activity, railContainer, getAccentColor());
        rail.build(this::selectPage);
    }

    /**
     * Fills the top-bar input-mode badge from the connected input, so the shell reads the same as
     * the VIP menu's. A pad present shows the controller badge, otherwise the keyboard badge.
     */
    private void determineInputMode() {
        if (overlayView == null) return;
        TextView badge = overlayView.findViewById(R.id.mod_menu_mode_badge);
        if (badge == null) return;
        boolean controller = hasConnectedGamepad();
        badge.setText(controller ? R.string.vip_mode_controller : R.string.vip_mode_keyboard);
        badge.setCompoundDrawablesRelativeWithIntrinsicBounds(
                controller ? R.drawable.ic_controller : R.drawable.ic_keyboard, 0, 0, 0);
    }

    /** Dispatches a rail selection to the matching section. The single source of tab routing. */
    private void selectPage(ModMenuPage page) {
        if (page == null) return;
        // The active page is recorded here, not only in updateNavigationItems: a page that passes
        // all-false booleans (Controller, Keyboard) still has to be the active one, or the
        // shoulder cycle and the controller-input routing would not know where they are.
        activePage = page;
        if (rail != null) rail.setSelected(page);
        updateTopBarTitle(page);
        switch (page) {
            case GENERAL:
                showModulesSection();
                break;
            case VOICE:
                showVoiceSection();
                break;
            case PACKS:
                showPacksSection();
                break;
            case COSMETICS:
                showCosmeticsSection();
                break;
            case HUD_EDITOR:
                showHudEditorSection();
                break;
            case REPLAY:
                showReplaySection();
                break;
            case SETTINGS:
                showSettingsSection();
                break;
            case CONTROLLER:
                showControllerSection();
                break;
            case KEYBOARD:
                showKeyboardSection();
                break;
            default:
                break;
        }
    }

    /**
     * The Controller page (Supporter): profiles, dead zone, sensitivity and rumble, plus the
     * shared full editor. It was the VIP window's own tab; with one menu it is an ordinary page.
     */
    private void showControllerSection() {
        updateNavigationItems(false, false, false, false, false, false, false);
        hideAllContentExcept(controllerContainer);
        if (controllerContainer == null) return;
        if (controllerSection == null) {
            controllerSection = new ModMenuControllerSection(activity, controllerContainer,
                    getAccentColor());
            controllerSection.build();
            controllerSection.setOnSectionChanged(this::refreshMods);
        }
        controllerContainer.setVisibility(View.VISIBLE);
        controllerSection.refresh();
        crossfade(controllerContainer);
    }

    /** The Keyboard page (Supporter): the drawn board, the rebindable actions, pointer settings. */
    private void showKeyboardSection() {
        updateNavigationItems(false, false, false, false, false, false, false);
        hideAllContentExcept(keyboardContainer);
        if (keyboardContainer == null) return;
        if (keyboardSection == null) {
            keyboardSection = new ModMenuKeyboardSection(activity, keyboardContainer,
                    getAccentColor());
            keyboardSection.build();
        }
        keyboardContainer.setVisibility(View.VISIBLE);
        keyboardSection.refresh();
        crossfade(keyboardContainer);
    }

    /**
     * Hides every content host except {@code keep}.
     *
     * <p>The page routing used to hand-hide each host at each call site, so adding a page meant
     * remembering every one of them. One helper means a new host is added in exactly one place.
     */
    private void hideAllContentExcept(View keep) {
        // Hiding a host is not always enough: a Cosmetics spinner's list is a separate window and a
        // Replay panel owns a live recorder listener, so both need their own onHidden() when their
        // host goes away. Running it here means every page switch is correct by construction.
        if (keep != cosmeticsContainer) hideCosmetics();
        if (keep != replayContainer) hideReplay();
        if (keep != voiceContainer) hideVoice();
        if (keep != packsContainer && packsContainer != null) {
            packsContainer.setVisibility(View.GONE);
        }
        for (View host : new View[]{modulesContainer, settingsContainer, cosmeticsContainer,
                packsContainer, voiceContainer, replayContainer, controllerContainer,
                keyboardContainer}) {
            if (host != null && host != keep) host.setVisibility(View.GONE);
        }
        if (overlayView != null) {
            View modConfigContainer = overlayView.findViewById(R.id.mod_config_container);
            if (modConfigContainer != null && modConfigContainer != keep) {
                modConfigContainer.setVisibility(View.GONE);
            }
        }
    }

    /** Hides the two Supporter pages' hosts, for the pages that hide their siblings by hand. */
    private void hideSupporterPages() {
        if (controllerContainer != null) controllerContainer.setVisibility(View.GONE);
        if (keyboardContainer != null) keyboardContainer.setVisibility(View.GONE);
    }

    /** Enters HUD-editor mode from the rail (the top bar no longer carries that destination). */
    private void showHudEditorSection() {
        if (overlayView == null) return;
        View modMenuContainer = overlayView.findViewById(R.id.mod_menu_container);
        View hudEditorTools = overlayView.findViewById(R.id.hud_editor_tools);
        if (modMenuContainer != null && hudEditorTools != null) {
            enterHudEditorMode(modMenuContainer, hudEditorTools);
        }
    }

    private void updateModuleCount() {
        String value = activity.getString(
            R.string.mod_menu_module_count,
            filteredMods.size(),
            allMods.size());
        if (moduleCountText != null) {
            moduleCountText.setText(value);
        }
        if (compactModuleCount != null) {
            compactModuleCount.setText(value);
        }
    }

    private void loadMods() {
        allMods.clear();

        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        favoriteKeys.clear();
        favoriteKeys.addAll(manager.getFavoriteModKeys());
        allMods.addAll(InbuiltModuleProvider.load(activity));
        allMods.addAll(ExternalModuleProvider.load(activity));

        applyRowStates(manager);
        applyFilters();
    }

    /**
     * Derives each module's row state and hands it to the adapter (M10).
     *
     * <p>This is what makes the menu self-explaining: a module the crash guard disabled says so,
     * and a module with no hook on this build says that instead of looking like a plain off
     * toggle. The state is derived from the same sources the rest of the launcher uses, so the
     * menu cannot claim a module is healthy when the crash guard has disabled it.
     */
    private void applyRowStates(InbuiltModManager manager) {
        if (adapter == null) return;
        java.util.Map<String, ModMenuRowState> states = new java.util.HashMap<>();
        for (UnifiedMod mod : allMods) {
            boolean available = org.chimeramc.client.core.mods.inbuilt.model.ModAvailability
                    .isInteractive(mod.getId());
            // A recorded load failure is what "failed to register" means in this build; there is
            // no separate registration flag on the row model.
            boolean registered = org.chimeramc.client.core.mods.ModLoadDiagnostics
                    .getForMod(activity, mod.getId()) == null;
            boolean autoDisabled = false;
            try {
                autoDisabled = org.chimeramc.client.core.mods.ModCrashAttribution
                        .wasAutoDisabled(activity, mod.getId());
            } catch (Throwable ignored) {
                // An unreadable crash record must not hide the module; it reads as not crashed.
            }
            states.put(mod.getStableKey(), ModMenuRowState.of(
                    registered, available, autoDisabled, mod.isEnabled()));
        }
        adapter.setRowStates(states);
    }

    private void filterMods(String query) {
        applyFilters();
    }

    private void updateEmptyState() {
        // The action rows count as content: an empty module list with "Reset HUD Position" still on
        // screen is not an empty screen, and the "no mods" notice over it reads as a bug.
        boolean hasContent = !filteredMods.isEmpty() || visibleActionCount > 0;
        if (emptyState != null) {
            emptyState.setVisibility(hasContent ? View.GONE : View.VISIBLE);
        }
        if (emptyStateText != null) {
            String query = searchInput != null ? searchInput.getText().toString().trim() : "";
            if (!query.isEmpty()) {
                emptyStateText.setText(R.string.mod_menu_no_matches);
            } else if (activeFilter == ModuleFilter.FAVORITES) {
                emptyStateText.setText(R.string.mod_menu_no_favorites);
            } else if (activeFilter == ModuleFilter.PVP) {
                // The positioning statement comes from PvpSectionPolicy, the same rule the docs and
                // the release notes read, so the screen cannot describe the section differently
                // from the policy that bounds it.
                emptyStateText.setText(PvpSectionPolicy.positioning());
            } else {
                emptyStateText.setText(R.string.mod_menu_no_mods);
            }
        }
    }

    public void refreshMods() {
        loadMods();
    }

    /**
     * Applies the player's two independent opacities to the menu window.
     *
     * <p>The container's alpha is the background opacity; the accent surfaces inside (the rail's
     * selection, the enabled cards, the filter chips) read the primary opacity from the theme, so a
     * clear panel with a solid accent — or the reverse — is expressible. Setting the container alpha
     * alone was the whole of the old behaviour, which is why the two could not move separately.
     */
    private void applyMenuOpacity() {
        if (overlayView == null) return;
        View menuContainer = overlayView.findViewById(R.id.mod_menu_container);
        if (menuContainer == null) return;
        // Re-resolve the theme so a change to the accent or its opacity redraws the accent surfaces
        // without recreating the overlay. The menu's own settings are passed in: building the theme
        // from the theme-state alone silently dropped the theme preset, the density, the radius and
        // the HUD opacity, so any live change reset them to their defaults.
        theme = new ModMenuTheme(activity,
                menuSettings != null ? menuSettings : InbuiltModManager.getInstance(activity)
                        .getModMenuSettings());
        // applyThemeToSurfaces owns the container alpha now: the menu-opacity control and the
        // HUD-opacity setting are two multipliers on the same view, and setting it in two places is
        // how one of them silently stops working.
        applyThemeToSurfaces();
    }

    /**
     * Re-tints the accent surfaces from the current theme.
     *
     * <p>The rail's selection, the filter chips and the enabled cards all derive their colour from
     * the accent, so they must follow a theme change; leaving them on the value they were built with
     * is how a live colour change "only half applies".
     */
    private void applyThemeToSurfaces() {
        if (overlayView == null || theme == null) return;
        try {
            applyPanelSurfaces();
            // The rail bakes the accent into its icons and pills when it is built, so a colour
            // change means rebuilding it rather than patching each tile. Only rebuild a rail that
            // already exists — at first setup the rail is built later, by its own path.
            if (rail != null) {
                buildRail();
                if (activePage != null) rail.setSelected(activePage);
            }
            if (compactFilterSelector != null) {
                compactFilterSelector.setTextColor(getAccentColor());
            }
            // The adapter carries the theme, so a re-bind repaints every card in the new accent.
            if (adapter != null) {
                adapter.setTheme(theme);
            }
        } catch (Throwable ignored) {
            // Theme re-application is cosmetic; never let it break the open menu.
        }
    }

    /**
     * Paints the panel, the scrim and the HUD opacity from the resolved theme (M6).
     *
     * <p>Three settings met here and none of them previously reached a pixel: the theme preset (the
     * panel's fill came from the layout's fixed {@code mod_menu_bg_surface1}), the scrim (fixed in
     * the layout) and the HUD-opacity slider. The glass intensity reaches the panel through
     * {@link #applyPanelBackdrop()} — a blurred layer behind the panel — and through the panel's own
     * fill, so glass on is a real material rather than an opaque card.
     */
    private void applyPanelSurfaces() {
        View container = overlayView.findViewById(R.id.mod_menu_container);
        ModMenuSettings.GlassIntensity glass = effectivePanelGlass();
        if (container instanceof androidx.cardview.widget.CardView) {
            androidx.cardview.widget.CardView cv = (androidx.cardview.widget.CardView) container;
            cv.setCardBackgroundColor(ModMenuPalette.panelFill(theme.scheme(), glass));
            cv.setRadius(theme.cornerRadiusDp() * density());
        }
        View scrim = overlayView.findViewById(R.id.mod_menu_scrim);
        if (scrim != null) {
            // The glass intensity drives how much of the game the scrim lets through: full glass
            // keeps it light, solid hides the game behind the panel. Never fully transparent, or the
            // menu text lands directly on the game with no separation.
            float scrimAlpha = scrimAlphaFor(glass);
            scrim.setBackgroundColor(ModMenuPalette.blend(0x00000000, theme.scrimColor(),
                    scrimAlpha));
        }
        applyPanelBackdrop(glass);
        // The HUD-opacity slider is a multiplier on the whole panel, independent of the panel's own
        // alpha (which the menu-opacity control owns).
        if (container != null) {
            container.setAlpha(menuOpacity() * theme.hudOpacity());
        }
    }

    /**
     * The glass intensity actually in effect (M6).
     *
     * <p>The player's in-menu choice is filtered through the accessibility switches: "reduce motion"
     * (which the app folds the OS reduce-transparency signal into) and the platform's
     * "animations off" scale both force the solid surface. A user-facing override that beats the OS
     * accessibility setting is a bug, so the OS always wins here — the in-menu value is only the
     * starting point.
     */
    private ModMenuSettings.GlassIntensity effectivePanelGlass() {
        if (menuSettings == null) return ModMenuSettings.GlassIntensity.OFF;
        boolean reduce = false;
        boolean animationsOff = false;
        try {
            reduce = new org.chimeramc.client.util.PersonalizationManager(activity)
                    .isReduceMotion();
        } catch (Throwable ignored) {
        }
        try {
            animationsOff = android.provider.Settings.Global.getFloat(
                    activity.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Throwable ignored) {
        }
        return menuSettings.effectiveGlass(reduce, animationsOff);
    }

    /**
     * Captures the game behind the panel and blurs it onto the backdrop layer (M4).
     *
     * <p>Reuses the wheel's capture machinery: a 1/4-resolution {@code PixelCopy} of the launcher's
     * window, then either a {@code RenderEffect} on the layer (API 31+) or a downscale/upscale blit
     * (API 28-30). The capture is taken once when the menu opens and then refreshed at the configured
     * blur rate while it is open — never per frame, because the panel is drawn over a live game and a
     * per-frame blur is the fastest way to lose the frame budget on a mid-range GPU.
     *
     * <p>Everything here is fail-open: no capture, no {@code PixelCopy} support, or a failed blit
     * leaves the layer hidden and the panel shows its opaque-ish fill, which is what
     * {@link ModMenuTheme#panelFill()} already accounts for.
     */
    private void applyPanelBackdrop(ModMenuSettings.GlassIntensity glass) {
        android.widget.ImageView layer = overlayView == null ? null
                : overlayView.findViewById(R.id.mod_menu_backdrop);
        if (layer == null) return;
        if (glass == ModMenuSettings.GlassIntensity.OFF
                || !WheelBackdropCapture.isSupported()) {
            releasePanelBackdrop(layer);
            return;
        }
        if (backdropCapture == null) backdropCapture = new WheelBackdropCapture();
        if (backdropBitmap != null && !backdropBitmap.isRecycled()) {
            showBackdropBitmap(layer, backdropBitmap);
            return;
        }
        // No capture yet: ask for one. The callback re-enters here and shows it.
        backdropCapture.captureIfDue(activity.getWindow(),
                android.os.SystemClock.uptimeMillis(), menuSettings.blurFps(),
                new WheelBackdropCapture.Callback() {
                    @Override
                    public void onCaptured(android.graphics.Bitmap capture) {
                        if (overlayView == null || capture == null) return;
                        backdropBitmap = capture;
                        android.widget.ImageView view = overlayView.findViewById(R.id.mod_menu_backdrop);
                        if (view != null) showBackdropBitmap(view, capture);
                    }

                    @Override
                    public void onFailed() {
                        // Keep the previous capture; the panel stays on its solid fill.
                    }
                });
    }

    /**
     * Puts a capture on the backdrop layer, blurred (M4).
     *
     * <p>Two routes, chosen by API level exactly as the wheel's are. The blurred bitmap for the
     * pre-31 route is cached on the overlay so a refresh reuses the same allocation; it is recycled
     * when a new capture replaces it.
     */
    private void showBackdropBitmap(android.widget.ImageView layer, android.graphics.Bitmap capture) {
        try {
            layer.setVisibility(android.view.View.VISIBLE);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                layer.setImageBitmap(capture);
                // The blur radius is the regular tier's, matching the wheel's panel backdrop. The
                // RenderEffect type is reached only through the renderer's guarded holder.
                glassRenderer.applyLayerBlur(layer, RadialWheelMaterial.REGULAR_BLUR_DP * density());
            } else {
                // Below 31 there is no RenderEffect: blur by blitting the capture down and back up.
                android.graphics.Bitmap blurred = glassRenderer.stackBlurCopy(capture);
                if (blurred == null) {
                    releasePanelBackdrop(layer);
                    return;
                }
                android.graphics.Bitmap previous = backdropBlurred;
                backdropBlurred = blurred;
                layer.setImageBitmap(blurred);
                recycleQuietly(previous);
            }
        } catch (Throwable ignored) {
            releasePanelBackdrop(layer);
        }
    }

    private void releasePanelBackdrop(android.widget.ImageView layer) {
        if (layer != null) {
            glassRenderer.clearViewEffect(layer);
            try {
                layer.setImageDrawable(null);
                layer.setVisibility(android.view.View.GONE);
            } catch (Throwable ignored) {
            }
        }
        recycleQuietly(backdropBlurred);
        backdropBlurred = null;
        backdropBitmap = null;
        if (backdropCapture != null) {
            backdropCapture.release();
            backdropCapture = null;
        }
    }

    private static void recycleQuietly(android.graphics.Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        try {
            bitmap.recycle();
        } catch (Throwable ignored) {
        }
    }

    /** The menu-opacity control's value (0..1), the panel's own alpha. */
    private float menuOpacity() {
        if (activity == null) return 1f;
        int opacity = InbuiltModManager.getInstance(activity).getModMenuOpacity();
        return Math.max(0f, Math.min(1f, opacity / 100f));
    }

    /**
     * The scrim opacity for a glass intensity (M6).
     *
     * <p>Pure so the three steps are pinned by a test. More glass means less scrim: a fully opaque
     * scrim behind a "Full" blur would hide the very thing the blur is sampling.
     */
    static float scrimAlphaFor(ModMenuSettings.GlassIntensity intensity) {
        if (intensity == null) return SCRIM_ALPHA_FULL;
        switch (intensity) {
            case OFF:
                return SCRIM_ALPHA_OFF;
            case SUBTLE:
                return SCRIM_ALPHA_SUBTLE;
            case FULL:
            default:
                return SCRIM_ALPHA_FULL;
        }
    }

    /** The scrim opacity when glass is off (solid panel, most separation). */
    static final float SCRIM_ALPHA_OFF = 0.85f;
    /** The scrim opacity for subtle glass. */
    static final float SCRIM_ALPHA_SUBTLE = 0.62f;
    /** The scrim opacity for full glass (lightest, so the blur has something to sample). */
    static final float SCRIM_ALPHA_FULL = 0.40f;

    public void hide() {
        if (!isShowing || overlayView == null) return;

        handler.removeCallbacks(statsTick);
        handler.removeCallbacks(backdropTick);
        // Release the panel's backdrop capture and its blurred copy: the layer is about to be
        // detached, and a capture held past that point keeps a full-window bitmap alive for nothing.
        releasePanelBackdrop(overlayView.findViewById(R.id.mod_menu_backdrop));
        if (renderDebugOverlay != null) {
            renderDebugOverlay.stop();
            renderDebugOverlay = null;
        }
        if (replayPanel != null) replayPanel.onHidden();
        // Collapse any open cosmetics dropdown before the tree is removed; a spinner's list is a
        // separate window and would otherwise stay floating over the game after the menu closes.
        if (cosmeticsPanel != null) cosmeticsPanel.onHidden();

        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        if (overlayManager != null) {
            overlayManager.setHudEditorMode(false);
            overlayManager.setHudEditorSelectionListener(null);
        }

        Runnable performHide = () -> {
            handler.post(() -> {
                try {
                    if (wmParams != null && windowManager != null) {
                        windowManager.removeView(overlayView);
                    } else {
                        ViewGroup rootView = activity.findViewById(android.R.id.content);
                        if (rootView != null) {
                            rootView.removeView(overlayView);
                        }
                    }
                } catch (Exception ignored) {}
                overlayView = null;
                isShowing = false;
                hasStaggeredOnce = false;
            });
        };

        View menuContainer = overlayView.findViewById(R.id.mod_menu_container);
        if (menuContainer != null) {
            animateMenuExit(menuContainer, performHide);
        } else {
            performHide.run();
        }
        overlayView.animate().alpha(0f).setDuration(180).start();
    }

    /**
     * Routes one controller key press into the open menu: a d-pad/left-stick direction moves the
     * selection, a face/shoulder button toggles the selected module.
     *
     * <p>Called from the game activity before the preloader sees the key, because the overlay root
     * is unfocusable on purpose (see {@link #disableFocusHighlight}) — so the framework cannot move
     * focus for us and the direction has to be applied to the list directly.
     *
     * @return true when the press was used by the menu and must be swallowed
     */
    public boolean handleControllerKey(int keyCode, boolean down) {
        if (!isShowing || !down) return false;
        // The Controller page owns the raw press while it is showing: the illustration highlights
        // the physically pressed button, and a modifier capture in the full editor takes the next
        // press. This is why the page must be offered the key before any navigation handling.
        if (activePage == ModMenuPage.CONTROLLER && controllerSection != null) {
            if (controllerSection.isCapturingModifier()
                    && controllerSection.handleCaptureKey(keyCode)) {
                return true;
            }
            controllerSection.handleHardwareKey(keyCode);
        }
        // The Replay section owns input while it is on screen: its buttons (record, cards) are
        // touch-driven and controller input cycles the selection and acts on it.
        if (replayPanel != null && replayContainer != null
                && replayContainer.getVisibility() == View.VISIBLE) {
            return handleReplayKey(keyCode);
        }
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_BUTTON_L1:
            case android.view.KeyEvent.KEYCODE_BUTTON_L2:
                selectPage(ModMenuPage.previous(activePage == null
                        ? ModMenuPage.GENERAL : activePage));
                return true;
            case android.view.KeyEvent.KEYCODE_BUTTON_R1:
            case android.view.KeyEvent.KEYCODE_BUTTON_R2:
                selectPage(ModMenuPage.next(activePage == null
                        ? ModMenuPage.GENERAL : activePage));
                return true;
            default:
                break;
        }
        if (adapter == null) return false;
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                return adapter.handleControllerNavigation(ModMenuNavigation.Direction.UP);
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                return adapter.handleControllerNavigation(ModMenuNavigation.Direction.DOWN);
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                return adapter.handleControllerNavigation(ModMenuNavigation.Direction.LEFT);
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                return adapter.handleControllerNavigation(ModMenuNavigation.Direction.RIGHT);
            case android.view.KeyEvent.KEYCODE_BUTTON_A:
            case android.view.KeyEvent.KEYCODE_ENTER:
                return adapter.handleControllerSelect();
            case android.view.KeyEvent.KEYCODE_BUTTON_START:
                hide();
                return true;
            default:
                return false;
        }
    }

    /**
     * Controller handling for the Replay section.
     *
     * <p>Every binding the on-screen hint names is resolved through {@link ReplayControls} and
     * applied by {@link ReplayPanel#handleControl}, so the in-game menu and the Library's Replay tab
     * run one implementation and cannot drift. Only the cinematic-camera button is a menu-only
     * extra, because the Library does not offer that route.
     */
    private boolean handleReplayKey(int keyCode) {
        ReplayControls.Action action = ReplayControls.forControllerKey(keyCode);
        if (action != null) {
            // Start is the transport play/pause only while a clip is open; otherwise it is not
            // consumed here, so the caller can fall back to its own binding.
            if (action == ReplayControls.Action.PLAY_PAUSE && !replayPanel.isPlayerOpen()) {
                return false;
            }
            return replayPanel.handleControl(action);
        }
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_ENTER:
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                replayPanel.playSelected();
                return true;
            case android.view.KeyEvent.KEYCODE_BUTTON_THUMBR:
                replayPanel.cinematicSelected();
                return true;
            default:
                return false;
        }
    }

    /**
     * Routes analogue stick movement into the open menu, so the selection can be moved with the
     * stick and not only the d-pad.
     *
     * <p>A repeat delay is applied because a held stick would otherwise race through the list.
     *
     * @return true when the movement was used by the menu and must be swallowed
     */
    public boolean handleControllerMotion(android.view.MotionEvent event) {
        if (!isShowing) return false;
        if (activePage == ModMenuPage.CONTROLLER && controllerSection != null) {
            controllerSection.handleHardwareMotion(event);
            return false;
        }
        if (adapter == null) return false;
        float axisX = event.getAxisValue(android.view.MotionEvent.AXIS_X);
        float axisY = event.getAxisValue(android.view.MotionEvent.AXIS_Y);
        if (Math.abs(axisX) < NAV_AXIS_THRESHOLD && Math.abs(axisY) < NAV_AXIS_THRESHOLD) return false;

        long now = android.os.SystemClock.uptimeMillis();
        if (now < nextAxisNavMs) return true;
        nextAxisNavMs = now + NAV_AXIS_REPEAT_MS;

        if (Math.abs(axisX) > Math.abs(axisY)) {
            return adapter.handleControllerNavigation(axisX > 0
                    ? ModMenuNavigation.Direction.RIGHT : ModMenuNavigation.Direction.LEFT);
        }
        return adapter.handleControllerNavigation(axisY > 0
                ? ModMenuNavigation.Direction.DOWN : ModMenuNavigation.Direction.UP);
    }

    private static final float NAV_AXIS_THRESHOLD = 0.6f;
    private static final long NAV_AXIS_REPEAT_MS = 180L;
    private volatile long nextAxisNavMs = 0L;

    public boolean isShowing() {
        return isShowing;
    }

    private static class GroupedMods {
        private final List<UnifiedMod> favorites = new ArrayList<>();
        private final List<UnifiedMod> others = new ArrayList<>();

        void add(UnifiedMod mod, boolean favorite) {
            if (favorite) {
                favorites.add(mod);
            } else {
                others.add(mod);
            }
        }

        void appendTo(List<UnifiedMod> target) {
            sort(favorites);
            sort(others);
            target.addAll(favorites);
            target.addAll(others);
        }

        private void sort(List<UnifiedMod> mods) {
            mods.sort((left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        }
    }
}
