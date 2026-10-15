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
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.ui.components.UiTokens;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.core.mods.inbuilt.ExternalModuleProvider;
import org.chimeramc.client.core.mods.inbuilt.InbuiltModuleProvider;
import org.chimeramc.client.core.mods.inbuilt.UnifiedMod;
import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.core.replay.ReplayPanel;
import org.chimeramc.client.core.mods.inbuilt.model.ModIds;
import org.chimeramc.client.core.mods.inbuilt.model.ModLoadoutStore;
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
        PVP
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
    private boolean hasStaggeredOnce = false;
    private EditText searchInput;
    private ImageButton clearSearchBtn;
    private TextView navGeneral, navSettings, navHudEditor, navCosmetics, navPacks, navVoice, navReplay;
    private TextView filterAll, filterFavorites, filterEnabled, filterInbuilt, filterExternal, filterPvp;
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
    private View settingsContainer;
    private View modulesContainer;
    private FrameLayout cosmeticsContainer;
    private FrameLayout packsContainer;
    private FrameLayout voiceContainer;
    private VoicePanel voicePanel;
    private FrameLayout replayContainer;
    private ReplayPanel replayPanel;
    private PackChangerPanel packChangerPanel;
    private View emptyState;
    private View menuContainer;
    private View modMenuTopBar;
    private View modMenuLogo;
    private View filterBar;
    private View compactFilterBar;
    private CosmeticsPanel cosmeticsPanel;
    private Switch notificationsSwitch;
    private Switch pauseMenuOnlySwitch;
    private Switch compactModeSwitch;
    private SeekBar modMenuOpacitySeekBar;
    private TextView modMenuOpacityText;
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
     * shared with the VIP Mod Menu (Screen B), so the touch screen and the VIP screen cannot both
     * hold a capture and silently steal each other's presses. This method is kept as the single
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
                if (org.levimc.launcher.core.mods.inbuilt.nativemod.FpsMod.nativeIsInitialized()) {
                    fps = org.levimc.launcher.core.mods.inbuilt.nativemod.FpsMod.nativeGetFps();
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
        navGeneral = overlayView.findViewById(R.id.nav_general);
        navVoice = overlayView.findViewById(R.id.nav_voice);
        navSettings = overlayView.findViewById(R.id.nav_settings);
        navHudEditor = overlayView.findViewById(R.id.nav_hud_editor);
        navCosmetics = overlayView.findViewById(R.id.nav_cosmetics);
        navPacks = overlayView.findViewById(R.id.nav_packs);
        navReplay = overlayView.findViewById(R.id.nav_replay);
        filterAll = overlayView.findViewById(R.id.filter_all);
        filterFavorites = overlayView.findViewById(R.id.filter_favorites);
        filterEnabled = overlayView.findViewById(R.id.filter_enabled);
        filterInbuilt = overlayView.findViewById(R.id.filter_inbuilt);
        filterExternal = overlayView.findViewById(R.id.filter_external);
        filterPvp = overlayView.findViewById(R.id.filter_pvp);
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

        if (navHudEditor != null) {
            navHudEditor.setOnClickListener(v -> enterHudEditorMode(modMenuContainer, hudEditorTools));
        }

        // Touch feedback on the stable chrome (nav + filter chips + close). Recycler rows get
        // their own feedback in the adapter, since they are recycled and rebound.
        for (View v : new View[]{navVoice, navGeneral, navSettings, navHudEditor, navCosmetics,
                navPacks, navReplay,
                filterAll, filterFavorites, filterEnabled, filterInbuilt, filterExternal, filterPvp,
                closeBtn, clearSearchBtn}) {
            if (v != null) DynamicAnim.applyPressScale(v);
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

        // Navigation
        if (navVoice != null) navVoice.setOnClickListener(v -> showVoiceSection());
        navGeneral.setOnClickListener(v -> showModulesSection());
        navSettings.setOnClickListener(v -> showSettingsSection());
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

        applyMenuOpacity();

        setupKeybindSettings(modManager);
        setupLoadoutSettings(modManager);

        adapter = new ModMenuAdapter(new ModMenuTheme(activity));
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
        });
        modsRecycler.setAdapter(adapter);
        applyCompactModeLayout(compactMode);

        showModulesSection();
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

    private void showSettingsSection() {
        updateNavigationItems(false, false, false, true, false, false, false);

        modulesContainer.setVisibility(View.GONE);
        hideCosmetics();
        hidePacks();
        hideVoice();
        hideReplay();
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
     * the live recorder listener. It is the same {@link ReplayPanel} the VIP menu hosts, so the
     * touch menu and the in-game bind show one feature rather than two implementations.
     */
    private void showReplaySection() {
        updateNavigationItems(false, false, false, false, false, false, true);

        if (modulesContainer != null) modulesContainer.setVisibility(View.GONE);
        if (settingsContainer != null) settingsContainer.setVisibility(View.GONE);
        hideCosmetics();
        hidePacks();
        hideVoice();
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
                int sources = device.getSources();
                if ((sources & android.view.InputDevice.SOURCE_GAMEPAD) != 0
                        || (sources & android.view.InputDevice.SOURCE_JOYSTICK) != 0) {
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
            adapter.updateMods(filteredMods, favoriteKeys);
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
            adapter.updateMods(filteredMods, favoriteKeys);
            if (!hasStaggeredOnce) {
                hasStaggeredOnce = true;
                DynamicAnim.staggerRecyclerChildren(modsRecycler);
            }
        }
        updateEmptyState();
        updateModuleCount();
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

        applyFilters();
    }

    private void filterMods(String query) {
        applyFilters();
    }

    private void updateEmptyState() {
        if (emptyState != null) {
            emptyState.setVisibility(filteredMods.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (emptyStateText != null) {
            String query = searchInput != null ? searchInput.getText().toString().trim() : "";
            if (!query.isEmpty()) {
                emptyStateText.setText(R.string.mod_menu_no_matches);
            } else if (activeFilter == ModuleFilter.FAVORITES) {
                emptyStateText.setText(R.string.mod_menu_no_favorites);
            } else if (activeFilter == ModuleFilter.PVP) {
                emptyStateText.setText(R.string.mod_menu_pvp_empty);
            } else {
                emptyStateText.setText(R.string.mod_menu_no_mods);
            }
        }
    }

    public void refreshMods() {
        loadMods();
    }

    private void applyMenuOpacity() {
        if (overlayView != null) {
            View menuContainer = overlayView.findViewById(R.id.mod_menu_container);
            if (menuContainer != null) {
                int opacity = InbuiltModManager.getInstance(activity).getModMenuOpacity();
                menuContainer.setAlpha(opacity / 100f);
            }
        }
    }

    public void hide() {
        if (!isShowing || overlayView == null) return;

        handler.removeCallbacks(statsTick);
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
        // The Replay section owns input while it is on screen: its buttons (record, cards) are
        // touch-driven and controller input cycles the selection and acts on it.
        if (replayPanel != null && replayContainer != null
                && replayContainer.getVisibility() == View.VISIBLE) {
            return handleReplayKey(keyCode);
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

    /** Controller handling for the Replay section, matching the VIP menu's bind hints. */
    private boolean handleReplayKey(int keyCode) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_BUTTON_L1:
                replayPanel.cycleSelection(false);
                return true;
            case android.view.KeyEvent.KEYCODE_BUTTON_R1:
                replayPanel.cycleSelection(true);
                return true;
            case android.view.KeyEvent.KEYCODE_BUTTON_A:
            case android.view.KeyEvent.KEYCODE_ENTER:
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                replayPanel.playSelected();
                return true;
            case android.view.KeyEvent.KEYCODE_BUTTON_X:
                replayPanel.deleteSelected();
                return true;
            case android.view.KeyEvent.KEYCODE_BUTTON_Y:
                replayPanel.favoriteSelected();
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
        if (!isShowing || adapter == null) return false;
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
