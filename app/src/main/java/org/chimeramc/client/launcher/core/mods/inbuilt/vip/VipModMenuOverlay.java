package org.chimeramc.client.core.mods.inbuilt.vip;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.mods.inbuilt.ExternalModuleProvider;
import org.chimeramc.client.core.mods.inbuilt.InbuiltModuleProvider;
import org.chimeramc.client.core.mods.inbuilt.UnifiedMod;
import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.core.mods.inbuilt.model.ModAvailability;
import org.chimeramc.client.core.mods.inbuilt.overlay.ControllerProfileEditor;
import org.chimeramc.client.core.mods.inbuilt.overlay.InbuiltOverlayManager;
import org.chimeramc.client.core.mods.inbuilt.overlay.ModConfigView;
import org.chimeramc.client.core.mods.inbuilt.overlay.ModMenuGuard;
import org.chimeramc.client.core.mods.inbuilt.overlay.ModMenuNavigation;
import org.chimeramc.client.core.replay.ReplayPanel;
import org.chimeramc.client.launcher.controller.ControllerInputProcessor;
import org.chimeramc.client.launcher.controller.ControllerProfile;
import org.chimeramc.client.launcher.controller.ControllerProfileManager;
import org.chimeramc.client.launcher.controller.ControllerType;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.ui.components.UiTokens;
import org.chimeramc.client.launcher.ui.views.Controller3DView;
import org.chimeramc.client.launcher.ui.views.KeyboardIllustrationView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The VIP Mod Menu — Screen B.
 *
 * <p>Opened only by the in-game bind (controller button or KBM key), never by the launcher nav bar.
 * It is a distinct screen from the touch Mod Menu (Screen A, {@code ModMenuOverlay}), not a variant
 * of it: where Screen A is a dense utilitarian panel, Screen B is the premium surface, with a
 * four-tier palette, sliding tab indicator, animated section transitions, skeleton loading, and an
 * illustrative empty state.
 *
 * <p>It carries two input sub-modes chosen by what the player is holding:
 * <ul>
 *   <li><b>Controller</b> — mirrors the launcher's Controller tab (profiles, sticks, rumble) but
 *       inside this shell, and controller input drives the module grid.</li>
 *   <li><b>Keyboard</b> — a drawn keyboard with the selected control lit, plus the key/mouse
 *       settings.</li>
 * </ul>
 *
 * <p>The module grid reuses the tested {@code ModMenuNavigation} layout maths rather than a second
 * implementation, so a controller steps both screens identically.
 */
public class VipModMenuOverlay {

    private static final int COLUMNS = 3;
    private static final float NAV_AXIS_THRESHOLD = 0.6f;
    private static final long NAV_AXIS_REPEAT_MS = 180L;
    /** Minimum time the skeleton stays up, so a fast load does not flash the placeholder. */
    private static final long SKELETON_MIN_MS = 320L;
    /** How long a key-capture stays armed before it releases itself. */
    private static final long KEY_CAPTURE_TIMEOUT_MS = 6000L;

    private final Activity activity;
    private final VipTheme theme;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private WindowManager.LayoutParams wmParams;
    private View overlayView;
    private boolean isShowing;

    private FrameLayout container;
    private TextView modeBadge;
    private TextView tabGeneral;
    private TextView tabReplay;
    private TextView tabController;
    private TextView tabKeyboard;
    private View tabIndicator;
    private View tabBar;
    private View generalView;
    private FrameLayout controllerView;
    private FrameLayout keyboardView;
    private FrameLayout replayView;
    private View configView;
    private ReplayPanel replayPanel;

    private RecyclerView recycler;
    private VipModuleAdapter adapter;
    private EditText searchInput;
    /** The active category tab; ALL shows everything, FAVORITES shows the pinned set. */
    private ModCategory.Tab activeCategory = ModCategory.Tab.ALL;
    private LinearLayout categoryRow;
    private ImageButton searchClear;
    private TextView moduleCount;
    private TextView activeCount;
    private View skeleton;
    private View emptyState;
    private TextView emptyTitle;
    private TextView emptyMessage;

    private Controller3DView controllerIllustration;
    private TextView controllerTypeText;
    private TextView controllerStatusText;
    private LinearLayout controllerProfiles;
    private LinearLayout controllerSliders;
    /** The shared full-profile editor, opened from the controller section. */
    private ControllerProfileEditor profileEditor;
    private TextView fullEditorButton;
    private View controllerEmpty;
    private KeyboardIllustrationView keyboardIllustration;
    private LinearLayout keyboardKeys;
    private LinearLayout keyboardSettings;

    private TextView configTitle;
    private LinearLayout configContent;

    private final List<UnifiedMod> allMods = new ArrayList<>();
    private final List<UnifiedMod> visibleMods = new ArrayList<>();

    private VipTab activeTab = VipTab.GENERAL;
    private VipInputMode.Mode inputMode = VipInputMode.Mode.KEYBOARD;
    private long nextAxisNavMs;
    private long shownAtMs;
    private ControllerProfileManager profileManager;

    private final Runnable searchRunnable = this::applySearch;

    /** Getter for one rebindable KBM action, so the row table needs no pref-key constants. */
    private interface IntGetter {
        int get(InbuiltModManager manager);
    }

    /** Setter paired with {@link IntGetter}. */
    private interface IntSetter {
        void set(InbuiltModManager manager, int keyCode);
    }

    private static final class KbmAction {
        final String label;
        final IntGetter getter;
        final IntSetter setter;

        KbmAction(String label, IntGetter getter, IntSetter setter) {
            this.label = label;
            this.getter = getter;
            this.setter = setter;
        }
    }

    public VipModMenuOverlay(Activity activity) {
        this.activity = activity;
        this.theme = new VipTheme(activity);
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return isShowing;
    }

    /**
     * Opens the menu directly on a section, for the radial wheel's sectors.
     *
     * <p>The wheel routes MODS to the module list and SETTINGS to the settings surface; a section
     * that has no dedicated surface (CLOSET, HUD) opens the module list, which is where those
     * modules live. Safe to call when the menu is already open: it just switches section.
     */
    public void showSection(VipTab tab) {
        if (!isShowing) {
            show();
        }
        if (isShowing) {
            selectTabSafe(tab == null ? VipTab.GENERAL : tab);
        }
    }

    /** Opens the menu on the module list, the surface the radial MODS sector points at. */
    public void showModuleList() {
        showSection(VipTab.GENERAL);
    }

    /** Opens or closes from the in-game bind. */
    public void toggle() {
        if (isShowing) {
            hide();
        } else {
            show();
        }
    }

    public void show() {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            showInternal();
        } catch (Throwable e) {
            // showInternal may have failed after addView, leaving a half-built panel attached. Drop
            // it before the fallback builds a fresh tree, or the fallback's addView would layer a
            // second copy (and a second set of listeners) on top of the broken one. Catch Throwable,
            // not Exception: a class-init or link failure while inflating a section surfaces as an
            // Error, and that must still degrade to the simple fallback rather than kill the game.
            detachWindowViewQuietly();
            try {
                showFallback();
            } catch (Throwable ignored) {
                // Both trees failed; leave the game untouched rather than crashing over it.
            }
        }
    }

    /** Removes [overlayView] from the window manager if it was attached, swallowing any failure. */
    private void detachWindowViewQuietly() {
        View view = overlayView;
        overlayView = null;
        if (view == null) return;
        try {
            if (view.getParent() != null) {
                windowManager.removeViewImmediate(view);
            }
        } catch (Exception ignored) {
            // The view may never have been added, or the window token may already be gone; either
            // way the fallback adds its own tree, so a failed detach is not fatal.
        }
    }

    private void showInternal() {
        overlayView = LayoutInflater.from(activity).inflate(R.layout.overlay_vip_mod_menu, null);
        applySystemUi();
        setupViews();
        determineInputMode();
        loadMods(true);
        disableFocusHighlight(overlayView);

        wmParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
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
        playEnterAnimation();
    }

    private void showFallback() {
        if (isShowing) return;
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;
        overlayView = LayoutInflater.from(activity).inflate(R.layout.overlay_vip_mod_menu, null);
        setupViews();
        determineInputMode();
        loadMods(true);
        disableFocusHighlight(overlayView);
        rootView.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        isShowing = true;
        wmParams = null;
        playEnterAnimation();
    }

    private void applySystemUi() {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        overlayView.setSystemUiVisibility(flags);
        overlayView.setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0 && overlayView != null) {
                overlayView.setSystemUiVisibility(flags);
            }
        });
    }

    private void setupViews() {
        // The overlay tree is inflated fresh on every show, so any panel built against the previous
        // tree must not be carried over.
        replayPanel = null;
        container = overlayView.findViewById(R.id.vip_menu_container);
        modeBadge = overlayView.findViewById(R.id.vip_mode_badge);
        tabGeneral = overlayView.findViewById(R.id.vip_tab_general);
        tabReplay = overlayView.findViewById(R.id.vip_tab_replay);
        tabController = overlayView.findViewById(R.id.vip_tab_controller);
        tabKeyboard = overlayView.findViewById(R.id.vip_tab_keyboard);
        tabIndicator = overlayView.findViewById(R.id.vip_tab_indicator);
        tabBar = overlayView.findViewById(R.id.vip_tab_bar);
        generalView = overlayView.findViewById(R.id.vip_general_view);
        controllerView = overlayView.findViewById(R.id.vip_controller_view);
        keyboardView = overlayView.findViewById(R.id.vip_keyboard_view);
        replayView = overlayView.findViewById(R.id.vip_replay_view);
        configView = overlayView.findViewById(R.id.vip_config_view);

        recycler = overlayView.findViewById(R.id.vip_mods_recycler);
        searchInput = overlayView.findViewById(R.id.vip_search_input);
        searchClear = overlayView.findViewById(R.id.vip_search_clear);
        moduleCount = overlayView.findViewById(R.id.vip_module_count);
        activeCount = overlayView.findViewById(R.id.vip_active_count);
        skeleton = overlayView.findViewById(R.id.vip_skeleton);
        emptyState = overlayView.findViewById(R.id.vip_empty);
        emptyTitle = overlayView.findViewById(R.id.vip_empty_title);
        emptyMessage = overlayView.findViewById(R.id.vip_empty_message);

        configTitle = overlayView.findViewById(R.id.vip_config_title);
        configContent = overlayView.findViewById(R.id.vip_config_content);
        ImageButton configBack = overlayView.findViewById(R.id.vip_config_back);
        if (configBack != null) configBack.setOnClickListener(v -> closeConfig());

        buildCategoryRow();

        ImageView logo = overlayView.findViewById(R.id.vip_logo);
        logo.setImageTintList(ColorStateList.valueOf(theme.accent()));
        // Build the badge's border rather than casting whatever drawable the XML set: the badge
        // uses a <shape> resource today, but a future colour or a re-inflated tree would make the
        // old cast throw. Owning the drawable keeps the stroke on the accent with no cast to fail.
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.RECTANGLE);
        badgeBg.setCornerRadius(10 * density());
        badgeBg.setColor(UiTokens.BG_SURFACE_HOVER);
        badgeBg.setStroke(Math.max(1, (int) density()),
                VipTheme.withAlpha(theme.accent(), 0x88));
        modeBadge.setBackground(badgeBg);

        adapter = new VipModuleAdapter(theme);
        GridLayoutManager layoutManager = new GridLayoutManager(activity, COLUMNS);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter != null && adapter.isGroupAt(position) ? COLUMNS : 1;
            }
        });
        recycler.setLayoutManager(layoutManager);
        recycler.setItemAnimator(null);
        recycler.setHasFixedSize(true);
        recycler.setAdapter(adapter);
        adapter.setListener(new VipModuleAdapter.Listener() {
            @Override
            public void onToggle(UnifiedMod mod, boolean enabled) {
                // A toggle applies live into the running game; if any overlay setup throws, the
                // menu must survive it. Swallow and refresh rather than letting it reach the
                // handler, which would take the whole session down with it.
                try {
                    mod.applyEnabled(enabled);
                    InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
                    if (manager != null) manager.applyConfigurationChanges(mod.getId());
                } catch (Throwable ignored) {
                }
                // Update the grid's own state from the model, so the pill reflects what actually
                // happened. Calling only refreshStates() re-bound the stale snapshot and left a
                // controller-toggled module showing "Off" while it was on.
                if (adapter != null) {
                    adapter.setEnabledState(mod.getStableKey(), mod.isEnabled());
                }
                updateCounts();
            }

            @Override
            public void onConfig(UnifiedMod mod) {
                openConfig(mod);
            }

            @Override
            public void onFavorite(UnifiedMod mod, boolean favorite) {
                InbuiltModManager manager = InbuiltModManager.getInstance(activity);
                if (manager != null) {
                    manager.setModFavorite(mod.getStableKey(), favorite);
                }
                applySearch();
            }
        });

        if (searchInput != null) {
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                    handler.removeCallbacks(searchRunnable);
                    handler.postDelayed(searchRunnable, 60L);
                    if (searchClear != null) {
                        searchClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }
        if (searchClear != null) {
            searchClear.setOnClickListener(v -> {
                if (searchInput != null) searchInput.setText("");
            });
        }

        tabGeneral.setOnClickListener(v -> selectTabSafe(VipTab.GENERAL));
        tabReplay.setOnClickListener(v -> selectTabSafe(VipTab.REPLAY));
        tabController.setOnClickListener(v -> selectTabSafe(VipTab.CONTROLLER));
        tabKeyboard.setOnClickListener(v -> selectTabSafe(VipTab.KEYBOARD));
        ImageButton close = overlayView.findViewById(R.id.vip_close);
        close.setOnClickListener(v -> hide());

        for (View v : new View[]{tabGeneral, tabReplay, tabController, tabKeyboard, close,
                searchClear, modeBadge}) {
            if (v != null) DynamicAnim.applyPressScale(v);
        }

        // Background tap closes; the panel swallows its own taps.
        overlayView.setOnClickListener(v -> hide());
        container.setOnClickListener(v -> {
        });

        buildControllerSection();
        buildKeyboardSection();
    }

    /**
     * Suppresses the platform focus highlight exactly as the touch menu does.
     *
     * <p>The overlay root is clickable (so a tap closes the menu) and therefore focusable; the
     * framework would paint its translucent white highlight over the whole screen the moment the
     * stick moved. The root is made unfocusable, every descendant has the highlight flag cleared,
     * and any already-held focus is released — all three parts are load-bearing.
     */
    private static void disableFocusHighlight(View view) {
        if (view == null) return;
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setDefaultFocusHighlightEnabled(false);
        clearFocusHighlightRecursive(view);
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

    // ---------------------------------------------------------------------------------------------
    // Entrance / section transitions
    // ---------------------------------------------------------------------------------------------

    private void playEnterAnimation() {
        container.setAlpha(0f);
        container.setScaleX(0.92f);
        container.setScaleY(0.92f);
        container.setTranslationY(24f);
        container.post(() -> {
            container.setPivotX(container.getWidth() / 2f);
            container.setPivotY(container.getHeight() / 2f);
            container.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(280)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .withLayer()
                    .start();
        });
        // The indicator settles under the default tab after layout, so it never slides in from the
        // far left on the first frame.
        tabBar.post(() -> moveIndicator(activeTab, false));
        modeBadge.setAlpha(0f);
        modeBadge.animate().alpha(1f).setStartDelay(120).setDuration(220).start();
    }

    /** Cross-fades a section in from a small offset, so switching tabs is a transition not a cut. */
    private void crossfade(View view) {
        view.setAlpha(0f);
        view.setTranslationX(24f);
        view.animate()
                .alpha(1f).translationX(0f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }

    private void moveIndicator(VipTab tab, boolean animate) {
        TextView target = tabView(tab);
        if (target == null || tabBar == null || tabIndicator == null) return;
        int width = tabIndicator.getWidth();
        if (width == 0) width = (int) (60 * density());
        float center = target.getLeft() + target.getWidth() / 2f;
        float targetX = Math.max(0f, center - width / 2f);
        if (!animate) {
            tabIndicator.setTranslationX(targetX);
            return;
        }
        tabIndicator.animate()
                .translationX(targetX)
                .setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    private TextView tabView(VipTab tab) {
        switch (tab) {
            case REPLAY:
                return tabReplay;
            case CONTROLLER:
                return tabController;
            case KEYBOARD:
                return tabKeyboard;
            case GENERAL:
            default:
                return tabGeneral;
        }
    }

    private void selectTab(VipTab tab) {
        activeTab = tab;
        updateTabSelection();
        moveIndicator(tab, true);

        generalView.setVisibility(tab == VipTab.GENERAL ? View.VISIBLE : View.GONE);
        controllerView.setVisibility(tab == VipTab.CONTROLLER ? View.VISIBLE : View.GONE);
        keyboardView.setVisibility(tab == VipTab.KEYBOARD ? View.VISIBLE : View.GONE);
        replayView.setVisibility(tab == VipTab.REPLAY ? View.VISIBLE : View.GONE);
        configView.setVisibility(View.GONE);

        View shown = tab == VipTab.GENERAL ? generalView
                : tab == VipTab.REPLAY ? replayView
                : tab == VipTab.CONTROLLER ? controllerView : keyboardView;
        if (tab == VipTab.REPLAY) {
            ensureReplayPanel();
            replayPanel.setGamepadDetected(inputMode == VipInputMode.Mode.CONTROLLER);
            replayPanel.onShown();
        } else if (replayPanel != null) {
            replayPanel.onHidden();
        }
        crossfade(shown);
        if (tab == VipTab.GENERAL && adapter != null) {
            adapter.clearFocus();
        }
    }

    private void ensureReplayPanel() {
        if (replayPanel != null || replayView == null) return;
        replayPanel = new ReplayPanel(activity, false);
        replayView.addView(replayPanel.getView(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** One tab is accented and bold; the rest are muted. */
    private void updateTabSelection() {
        for (VipTab tab : VipTab.values()) {
            TextView view = tabView(tab);
            if (view == null) continue;
            boolean selected = tab == activeTab;
            int color = selected ? theme.accent() : theme.textSecondary();
            view.setTextColor(color);
            view.setAlpha(selected ? 1f : 0.85f);
            view.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            view.setCompoundDrawableTintList(ColorStateList.valueOf(color));
            view.setBackground(selected ? activity.getDrawable(R.drawable.bg_vip_tab_selected) : null);
        }
    }

    private void determineInputMode() {
        InputDevice gamepad = firstGamepad();
        inputMode = VipInputMode.forGamepad(gamepad != null);
        boolean controller = inputMode == VipInputMode.Mode.CONTROLLER;

        modeBadge.setText(controller ? R.string.vip_mode_controller : R.string.vip_mode_keyboard);
        modeBadge.setCompoundDrawablesRelativeWithIntrinsicBounds(
                controller ? R.drawable.ic_controller : R.drawable.ic_keyboard, 0, 0, 0);

        // Opening a section is where the Modules tab used to die: the Controller and Keyboard
        // builders run here and a single bad profile/illustration lookup would propagate out of
        // show() and crash the game. Each is isolated via ModMenuGuard so the failure is recorded
        // for the diagnostics report (with its class, message and first 12 frames) instead of
        // killing the process, and the module grid -- the tab's real content -- still comes up.
        selectTabSafe(VipTab.defaultFor(inputMode));
        ModMenuGuard.run("refreshControllerSection", this::refreshControllerSection);
        ModMenuGuard.run("refreshKeyboardSection", this::refreshKeyboardSection);
    }

    /** Runs {@link #selectTab} without letting a section-build failure escape into the game. */
    private void selectTabSafe(VipTab tab) {
        try {
            selectTab(tab);
        } catch (Throwable t) {
            ModMenuGuard.record("selectTab:" + tab, t, SystemClock.uptimeMillis());
            // The tab indicator or a section host failed; fall back to making the General (module)
            // section visible directly, since that is the one surface a controller user needs.
            if (generalView != null) generalView.setVisibility(View.VISIBLE);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Module grid
    // ---------------------------------------------------------------------------------------------

    private void loadMods(boolean showSkeleton) {
        shownAtMs = SystemClock.uptimeMillis();
        if (showSkeleton && skeleton != null) {
            skeleton.setVisibility(View.VISIBLE);
        }
        if (emptyState != null) emptyState.setVisibility(View.GONE);
        allMods.clear();
        // Each provider is isolated: one malformed external manifest (or a module whose config
        // build throws) must not empty the whole grid or, worse, take the menu down. The menu is
        // opened over a running game, so a crash here would kill the session.
        allMods.addAll(safeLoad(() -> InbuiltModuleProvider.load(activity)));
        allMods.addAll(safeLoad(() -> ExternalModuleProvider.load(activity)));
        applySearch();
        long elapsed = SystemClock.uptimeMillis() - shownAtMs;
        handler.postDelayed(() -> {
            if (isShowing && skeleton != null) skeleton.setVisibility(View.GONE);
        }, Math.max(0L, SKELETON_MIN_MS - elapsed));
    }

    private interface ModLoader {
        List<UnifiedMod> load();
    }

    private static List<UnifiedMod> safeLoad(ModLoader loader) {
        try {
            List<UnifiedMod> mods = loader.load();
            return mods != null ? mods : Collections.<UnifiedMod>emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    private void applySearch() {
        // The whole rebuild is guarded: a malformed module name or a diff that throws must leave
        // the previous grid on screen rather than propagate to the main looper over a live game.
        try {
            String query = searchInput == null ? "" : searchInput.getText().toString().trim();
            InbuiltModManager manager = InbuiltModManager.getInstance(activity);
            Set<String> favorites = manager != null
                    ? manager.getFavoriteModKeys() : Collections.<String>emptySet();

            visibleMods.clear();
            for (UnifiedMod mod : allMods) {
                if (!ModCategory.matches(activeCategory, mod, favorites)) continue;
                if (query.isEmpty() || ModCategory.matchesQuery(mod, query)) {
                    visibleMods.add(mod);
                }
            }
            if (adapter != null) adapter.submit(visibleMods, favorites);
            updateCounts();
            updateEmptyState(query);
        } catch (Throwable ignored) {
        }
    }

    /** Builds the category tab chips (All / Favorites / Visuals / PvP / Misc). */
    private void buildCategoryRow() {
        categoryRow = overlayView.findViewById(R.id.vip_category_row);
        if (categoryRow == null) return;
        categoryRow.removeAllViews();
        addCategoryChip(ModCategory.Tab.ALL, R.string.vip_category_all);
        addCategoryChip(ModCategory.Tab.FAVORITES, R.string.vip_category_favorites);
        addCategoryChip(ModCategory.Tab.VISUALS, R.string.vip_category_visuals);
        addCategoryChip(ModCategory.Tab.PVP, R.string.vip_category_pvp);
        addCategoryChip(ModCategory.Tab.MISC, R.string.vip_category_misc);
        refreshCategoryChips();
    }

    private void addCategoryChip(ModCategory.Tab tab, int labelRes) {
        TextView chip = new TextView(activity);
        chip.setText(labelRes);
        chip.setTextSize(11f);
        chip.setPadding((int) (14 * density()), (int) (6 * density()),
                (int) (14 * density()), (int) (6 * density()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int) (8 * density()));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            activeCategory = tab;
            refreshCategoryChips();
            applySearch();
        });
        categoryRow.addView(chip);
    }

    /** Re-tints the chips so the active category reads as selected. */
    private void refreshCategoryChips() {
        if (categoryRow == null) return;
        int active = theme.accent();
        int idle = UiTokens.BG_SURFACE_HOVER;
        int activeText = 0xFFFFFFFF;
        int idleText = UiTokens.TEXT_SECONDARY;
        ModCategory.Tab[] tabs = {ModCategory.Tab.ALL, ModCategory.Tab.FAVORITES,
                ModCategory.Tab.VISUALS, ModCategory.Tab.PVP, ModCategory.Tab.MISC};
        for (int i = 0; i < categoryRow.getChildCount() && i < tabs.length; i++) {
            View child = categoryRow.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView chip = (TextView) child;
            boolean selected = tabs[i] == activeCategory;
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(14 * density());
            bg.setColor(selected ? active : idle);
            chip.setBackground(bg);
            chip.setTextColor(selected ? activeText : idleText);
        }
    }

    private void updateCounts() {
        int active = 0;
        for (UnifiedMod mod : visibleMods) {
            if (mod.isEnabled()) active++;
        }
        if (moduleCount != null) {
            moduleCount.setText(activity.getString(R.string.vip_module_count, visibleMods.size()));
        }
        if (activeCount != null) {
            activeCount.setText(activity.getString(R.string.vip_active_count, active));
        }
    }

    private void updateEmptyState(String query) {
        if (emptyState == null) return;
        boolean empty = visibleMods.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;
        emptyTitle.setText(R.string.vip_empty_title);
        emptyMessage.setText(query != null && !query.isEmpty()
                ? R.string.vip_empty_no_matches : R.string.vip_empty_no_mods);
    }

    private void openConfig(UnifiedMod mod) {
        try {
            if (mod.openCustomConfig()) {
                hide();
                return;
            }
        } catch (Throwable ignored) {
            // A custom config surface (a full editor screen) that throws on open must not take the
            // menu, or the game, down. Fall through to the in-overlay config body.
        }
        if (configTitle != null) configTitle.setText(mod.getName());
        if (configContent != null) {
            configContent.removeAllViews();
            try {
                ModConfigView.render(activity, configContent, mod, false, () -> {
                    InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
                    if (manager != null) manager.applyConfigurationChanges(mod.getId());
                });
            } catch (Throwable t) {
                // A single module's config failing to build must not crash the menu over the game;
                // show the title and an empty body instead, exactly as an empty config would.
                configContent.removeAllViews();
            }
        }
        if (configView != null) {
            configView.setVisibility(View.VISIBLE);
            crossfade(configView);
        }
    }

    private void closeConfig() {
        if (configView != null) configView.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------------------------------------
    // Controller sub-mode
    // ---------------------------------------------------------------------------------------------

    private void buildControllerSection() {
        if (controllerView == null) return;
        View view = LayoutInflater.from(activity)
                .inflate(R.layout.vip_controller_section, (ViewGroup) controllerView, false);
        controllerView.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        controllerIllustration = view.findViewById(R.id.vip_controller_illustration);
        controllerTypeText = view.findViewById(R.id.vip_controller_type);
        controllerStatusText = view.findViewById(R.id.vip_controller_status);
        controllerProfiles = view.findViewById(R.id.vip_controller_profiles);
        controllerSliders = view.findViewById(R.id.vip_controller_sliders);
        controllerEmpty = view.findViewById(R.id.vip_controller_empty);
        // The overlay tree is re-inflated on every show, so any view built against the previous
        // tree must be dropped or the "Full settings" button would be added twice.
        fullEditorButton = null;
        profileEditor = null;
        if (controllerIllustration != null) {
            controllerIllustration.setAccentColor(theme.accent());
        }
        profileManager = new ControllerProfileManager(activity);
    }

    private void refreshControllerSection() {
        if (controllerView == null || controllerIllustration == null) return;
        InputDevice gamepad = firstGamepad();
        boolean hasPad = gamepad != null && inputMode == VipInputMode.Mode.CONTROLLER;

        if (controllerEmpty != null) {
            controllerEmpty.setVisibility(hasPad ? View.GONE : View.VISIBLE);
        }
        controllerIllustration.setVisibility(hasPad ? View.VISIBLE : View.GONE);

        ControllerType type = hasPad ? ControllerType.from(gamepad) : ControllerType.XBOX;
        if (type == null) type = ControllerType.XBOX;
        // Each step is isolated so one bad widget (the illustration, a chip, a slider) cannot take
        // down the whole section -- or the game. Failures land in the diagnostics report.
        final ControllerType resolvedType = type;
        ModMenuGuard.run("controller.setType", () -> {
            controllerIllustration.setType(resolvedType);
            controllerTypeText.setText(resolvedType.getDisplayName());
        });

        if (profileManager == null) return;
        final ControllerType sectionType = resolvedType;
        ModMenuGuard.run("controller.status", () -> {
            List<ControllerProfile> profiles = profileManager.getProfiles(sectionType);
            int activeSlot = profileManager.getActiveSlot(sectionType);
            String name = profiles.isEmpty() ? "Default"
                    : profiles.get(Math.min(activeSlot, profiles.size() - 1)).getName();
            controllerStatusText.setText(activity.getString(
                    R.string.vip_controller_active_profile) + ": " + name);
        });
        ModMenuGuard.run("controller.bindGlow", () -> {
            InbuiltModManager modManager = InbuiltModManager.getInstance(activity);
            controllerIllustration.clearConfirmed();
            if (modManager != null) {
                String region = controllerIllustration.regionIdForKey(
                        modManager.getModMenuControllerBind());
                if (region != null) controllerIllustration.setRegionConfirmed(region, true);
            }
        });
        ModMenuGuard.run("controller.chips", () -> {
            List<ControllerProfile> profiles = profileManager.getProfiles(sectionType);
            int activeSlot = profileManager.getActiveSlot(sectionType);
            rebuildProfileChips(sectionType, profiles, activeSlot);
        });
        ModMenuGuard.run("controller.sliders", () -> {
            List<ControllerProfile> profiles = profileManager.getProfiles(sectionType);
            int activeSlot = profileManager.getActiveSlot(sectionType);
            rebuildStickSliders(sectionType, profiles, activeSlot);
        });
        ModMenuGuard.run("controller.fullEditorButton", () -> ensureFullEditorButton(sectionType));
    }

    /**
     * Adds the "Full settings" button that opens the shared controller editor — the same editor the
     * launcher's Controller tab uses, so the in-game screen is no longer limited to the two quick
     * sliders. Idempotent: it only adds the button once per section build.
     */
    private void ensureFullEditorButton(final ControllerType type) {
        if (controllerSliders == null || fullEditorButton != null) return;
        TextView button = new TextView(activity);
        button.setText(R.string.controller_edit);
        button.setTextSize(13);
        button.setTextColor(0xFFFFFFFF);
        int pad = (int) (16 * density());
        button.setPadding(pad, pad / 2, pad, pad / 2);
        button.setGravity(android.view.Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(12 * density());
        bg.setColor(theme.accent());
        button.setBackground(bg);
        DynamicAnim.applyPressScale(button);
        button.setOnClickListener(v -> {
            boolean ok = ModMenuGuard.run("openFullControllerEditor", () -> {
                if (profileEditor == null) {
                    profileEditor = new ControllerProfileEditor(activity, profileManager);
                }
                profileEditor.show(type, saved -> ModMenuGuard.run(
                        "refreshControllerSection(after edit)", this::refreshControllerSection));
            });
            if (!ok) toastGuardFailure();
        });
        controllerSliders.addView(button);
        fullEditorButton = button;
    }

    private void rebuildProfileChips(final ControllerType type, List<ControllerProfile> profiles,
                                     int activeSlot) {
        if (controllerProfiles == null) return;
        controllerProfiles.removeAllViews();
        for (int i = 0; i < profiles.size(); i++) {
            final int slot = i;
            TextView chip = new TextView(activity);
            chip.setText(profiles.get(i).getName());
            chip.setTextSize(12);
            chip.setSingleLine(true);
            int pad = (int) (14 * density());
            chip.setPadding(pad, pad / 2, pad, pad / 2);
            boolean selected = i == activeSlot;
            chip.setTextColor(selected ? 0xFFFFFFFF : theme.textSecondary());
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(12 * density());
            bg.setColor(selected ? theme.accent() : UiTokens.BG_SURFACE_HOVER);
            chip.setBackground(bg);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = (int) (8 * density());
            params.bottomMargin = (int) (8 * density());
            chip.setLayoutParams(params);
            DynamicAnim.applyPressScale(chip);
            chip.setOnClickListener(v -> {
                profileManager.setActiveSlot(type, slot);
                ControllerInputProcessor.setActiveProfile(type,
                        profileManager.getActiveProfile(type));
                refreshControllerSection();
            });
            controllerProfiles.addView(chip);
        }
    }

    private void rebuildStickSliders(final ControllerType type, List<ControllerProfile> profiles,
                                     int activeSlot) {
        if (controllerSliders == null) return;
        controllerSliders.removeAllViews();
        final ControllerProfile profile = profiles.get(Math.min(activeSlot, profiles.size() - 1));
        addSlider(controllerSliders, activity.getString(R.string.vip_dead_zone),
                (int) (profile.getRightDeadZone() * 100), 0, 60, value -> {
                    profile.setRightDeadZone(value / 100f);
                    profile.setLeftDeadZone(value / 100f);
                    persistProfile(type, activeSlot, profile);
                });
        addSlider(controllerSliders, activity.getString(R.string.vip_sensitivity),
                (int) (profile.getRightStickSensitivity() * 100), 25, 300, value -> {
                    profile.setRightStickSensitivity(value / 100f);
                    profile.setLeftStickSensitivity(value / 100f);
                    persistProfile(type, activeSlot, profile);
                });
        addSlider(controllerSliders, activity.getString(R.string.vip_rumble),
                profile.getRumbleStrength(), 0, 100, value -> {
                    profile.setRumbleStrength(value);
                    profile.setVibrationEnabled(value > 0);
                    persistProfile(type, activeSlot, profile);
                });
    }

    private void persistProfile(ControllerType type, int slot, ControllerProfile profile) {
        if (profileManager == null) return;
        // Save the edited object, not a fresh read from prefs. `getProfiles` deserialises new
        // objects each call, so saving that list wrote the *unmodified* profiles back and a slider
        // change was applied to the running processor but never persisted.
        java.util.List<ControllerProfile> profiles =
                org.chimeramc.client.launcher.controller.ControllerProfileList
                        .replaceAt(profileManager.getProfiles(type), slot, profile);
        profileManager.saveProfiles(type, profiles);
        profileManager.setActiveSlot(type, slot);
        ControllerInputProcessor.setActiveProfile(type, profile);
        ControllerInputProcessor.reload(activity);
    }

    private interface ValueListener {
        void onValue(int value);
    }

    private void addSlider(LinearLayout parent, String label, int initial, int min, int max,
                           final ValueListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = (int) (14 * density());
        row.setLayoutParams(rowParams);

        TextView header = new TextView(activity);
        header.setText(label);
        header.setTextColor(theme.textSecondary());
        header.setTextSize(12);
        row.addView(header);

        final TextView value = new TextView(activity);
        value.setText(String.valueOf(initial));
        value.setTextColor(theme.accent());
        value.setTextSize(12);
        value.setTypeface(null, Typeface.BOLD);
        value.setGravity(Gravity.END);
        row.addView(value);

        SeekBar bar = new SeekBar(activity);
        bar.setMax(Math.max(1, max - min));
        bar.setProgress(Math.max(0, Math.min(max - min, initial - min)));
        bar.setProgressTintList(ColorStateList.valueOf(theme.accent()));
        bar.setThumbTintList(ColorStateList.valueOf(theme.accent()));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int actual = min + progress;
                value.setText(String.valueOf(actual));
                if (fromUser) listener.onValue(actual);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(bar);
        parent.addView(row);
    }

    // ---------------------------------------------------------------------------------------------
    // Keyboard sub-mode
    // ---------------------------------------------------------------------------------------------

    private List<KbmAction> kbmActions() {
        List<KbmAction> actions = new ArrayList<>();
        actions.add(new KbmAction("Open VIP menu",
                InbuiltModManager::getModMenuKeybind, InbuiltModManager::setModMenuKeybind));
        actions.add(new KbmAction("Zoom",
                InbuiltModManager::getZoomKeybind, InbuiltModManager::setZoomKeybind));
        actions.add(new KbmAction("Auto sprint",
                InbuiltModManager::getAutoSprintKeybind, InbuiltModManager::setAutoSprintKeybind));
        return actions;
    }

    private void buildKeyboardSection() {
        if (keyboardView == null) return;
        View view = LayoutInflater.from(activity)
                .inflate(R.layout.vip_keyboard_section, (ViewGroup) keyboardView, false);
        keyboardView.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        keyboardIllustration = view.findViewById(R.id.vip_keyboard_illustration);
        keyboardKeys = view.findViewById(R.id.vip_keyboard_keys);
        keyboardSettings = view.findViewById(R.id.vip_keyboard_settings);
        if (keyboardIllustration != null) {
            keyboardIllustration.setAccentColor(theme.accent());
        }
    }

    private void refreshKeyboardSection() {
        if (keyboardView == null || keyboardKeys == null) return;
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        keyboardKeys.removeAllViews();
        if (manager != null) {
            for (KbmAction action : kbmActions()) {
                addKeybindRow(keyboardKeys, action, manager, action.getter.get(manager));
            }
        }
        rebuildKeyboardSettings();
    }

    private void addKeybindRow(LinearLayout parent, final KbmAction action,
                               final InbuiltModManager manager, int code) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(activity.getDrawable(R.drawable.bg_vip_surface));
        int pad = (int) (14 * density());
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = (int) (8 * density());
        row.setLayoutParams(params);

        TextView label = new TextView(activity);
        label.setText(action.label);
        label.setTextColor(theme.textPrimary());
        label.setTextSize(13);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(activity);
        value.setText(bindLabel(code));
        value.setTextColor(theme.accent());
        value.setTextSize(12);
        value.setTypeface(null, Typeface.BOLD);

        row.addView(label);
        row.addView(value);
        DynamicAnim.applyPressScale(row);
        row.setOnClickListener(v -> openKeyCapture(action, value, manager));
        parent.addView(row);
    }

    private void openKeyCapture(final KbmAction action, final TextView value,
                                final InbuiltModManager manager) {
        value.setText(R.string.mod_menu_bind_press_key);
        value.setTextColor(theme.statusActive());
        MenuBindRelay.set(keyCode -> {
            MenuBindRelay.clear();
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                action.setter.set(manager, keyCode);
                if (keyboardIllustration != null) {
                    keyboardIllustration.setHighlightCode(keyCode);
                    keyboardIllustration.flashKey(keyCode);
                }
            }
            value.setText(bindLabel(action.getter.get(manager)));
            value.setTextColor(theme.accent());
        });
        // A timeout releases the capture so a stray press does not stay armed forever.
        handler.postDelayed(() -> {
            if (MenuBindRelay.isCapturing()) {
                MenuBindRelay.clear();
                value.setText(bindLabel(action.getter.get(manager)));
                value.setTextColor(theme.accent());
            }
        }, KEY_CAPTURE_TIMEOUT_MS);
    }

    private void rebuildKeyboardSettings() {
        if (keyboardSettings == null) return;
        keyboardSettings.removeAllViews();
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        if (manager != null) {
            addSlider(keyboardSettings,
                    activity.getString(R.string.mod_config_cursor_sensitivity_percent),
                    manager.getCursorSensitivity(), 10, 300, manager::setCursorSensitivity);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Input routing (called by the game activity through InbuiltOverlayManager)
    // ---------------------------------------------------------------------------------------------

    /**
     * Routes one raw key press into the open VIP screen.
     *
     * <p>On the General tab a d-pad/stick moves the selection and a face button toggles the focused
     * module; the shoulders cycle tabs and Start closes. On the Controller/Keyboard tabs the face
     * buttons are left to the tab, so only the shoulders and Start are claimed. The pressed control
     * is also echoed onto the pad illustration so the Controller tab shows the live input.
     *
     * @return true when the press was used and must be swallowed
     */
    public boolean handleControllerKey(int keyCode, boolean down) {
        if (!isShowing || !down) return false;
        if (MenuBindRelay.isCapturing()) {
            return MenuBindRelay.deliver(keyCode);
        }
        // A layer-modifier capture in the full controller editor takes the next press first, so the
        // captured button is the one the player meant rather than a tab switch.
        if (profileEditor != null && profileEditor.isCapturingModifier()
                && profileEditor.handleHardwareKey(keyCode)) {
            return true;
        }
        if (controllerIllustration != null) {
            controllerIllustration.handleKeyEvent(keyCode, true);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_L1:
                selectTab(activeTab.previous());
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                selectTab(activeTab.next());
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
                if (configView.getVisibility() == View.VISIBLE) {
                    closeConfig();
                } else {
                    hide();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                if (configView.getVisibility() == View.VISIBLE) {
                    closeConfig();
                    return true;
                }
                return false;
            default:
                break;
        }
        if (activeTab == VipTab.REPLAY) {
            return handleReplayKey(keyCode);
        }
        if (activeTab != VipTab.GENERAL || configView.getVisibility() == View.VISIBLE
                || adapter == null) {
            return false;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return adapter.move(ModMenuNavigation.Direction.UP);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return adapter.move(ModMenuNavigation.Direction.DOWN);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return adapter.move(ModMenuNavigation.Direction.LEFT);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return adapter.move(ModMenuNavigation.Direction.RIGHT);
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return adapter.select();
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
                return adapter.openConfig();
            default:
                return false;
        }
    }

    /** Controller handling for the VIP Replay tab, matching its bind hints. */
    private boolean handleReplayKey(int keyCode) {
        if (replayPanel == null || replayView.getVisibility() != View.VISIBLE) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_L1:
                replayPanel.cycleSelection(false);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                replayPanel.cycleSelection(true);
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                replayPanel.playSelected();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                replayPanel.deleteSelectedOrFirst();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                replayPanel.favoriteSelected();
                return true;
            default:
                return false;
        }
    }

    /** Routes analogue stick movement into the open VIP screen (module grid navigation). */
    public boolean handleControllerMotion(MotionEvent event) {
        if (!isShowing || adapter == null) return false;
        if (controllerIllustration != null) controllerIllustration.handleMotionEvent(event);
        if (activeTab != VipTab.GENERAL || configView.getVisibility() == View.VISIBLE) return false;
        float axisX = event.getAxisValue(MotionEvent.AXIS_X);
        float axisY = event.getAxisValue(MotionEvent.AXIS_Y);
        if (Math.abs(axisX) < NAV_AXIS_THRESHOLD && Math.abs(axisY) < NAV_AXIS_THRESHOLD) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (now < nextAxisNavMs) return true;
        nextAxisNavMs = now + NAV_AXIS_REPEAT_MS;
        if (Math.abs(axisX) > Math.abs(axisY)) {
            return adapter.move(axisX > 0
                    ? ModMenuNavigation.Direction.RIGHT : ModMenuNavigation.Direction.LEFT);
        }
        return adapter.move(axisY > 0
                ? ModMenuNavigation.Direction.DOWN : ModMenuNavigation.Direction.UP);
    }

    /**
     * Offers an in-game bind picker the raw press before the preloader can consume it.
     *
     * <p>The game activity calls this at the very top of {@code dispatchKeyEvent}; the relay is
     * shared with the touch menu so only one screen can hold the capture.
     */
    public static boolean deliverBindKey(int keyCode) {
        return MenuBindRelay.deliver(keyCode);
    }

    public void hide() {
        if (!isShowing || overlayView == null) return;
        if (replayPanel != null) {
            replayPanel.dispose();
            replayPanel = null;
        }
        MenuBindRelay.clear();
        handler.removeCallbacks(searchRunnable);
        if (adapter != null) adapter.clearFocus();

        Runnable performHide = () -> handler.post(() -> {
            try {
                if (wmParams != null && windowManager != null) {
                    windowManager.removeView(overlayView);
                } else {
                    ViewGroup rootView = activity.findViewById(android.R.id.content);
                    if (rootView != null) rootView.removeView(overlayView);
                }
            } catch (Exception ignored) {
            }
            overlayView = null;
            isShowing = false;
        });

        container.setPivotX(container.getWidth() / 2f);
        container.setPivotY(container.getHeight() / 2f);
        container.animate()
                .alpha(0f).scaleX(0.94f).scaleY(0.94f)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(performHide)
                .start();
    }

    private InputDevice firstGamepad() {
        android.hardware.input.InputManager im = (android.hardware.input.InputManager)
                activity.getSystemService(Context.INPUT_SERVICE);
        if (im == null) return null;
        for (int id : im.getInputDeviceIds()) {
            InputDevice device = im.getInputDevice(id);
            if (isGamepad(device)) return device;
        }
        return null;
    }

    private boolean isGamepad(InputDevice device) {
        if (device == null) return false;
        if (ControllerType.from(device) != null) return true;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private String bindLabel(int keyCode) {
        if (keyCode == 0) return activity.getString(R.string.mod_menu_bind_none);
        String name = KeyEvent.keyCodeToString(keyCode);
        return name.startsWith("KEYCODE_") ? name.substring(8).replace('_', ' ') : name;
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }

    /**
     * Shows a short toast after a guarded menu step fails, so the failure is visible to the player
     * (and the diagnostics report carries the details) instead of the screen silently doing nothing.
     */
    private void toastGuardFailure() {
        try {
            android.widget.Toast.makeText(activity, R.string.vip_controller_step_failed,
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // A toast failure must never be the thing that breaks the menu.
        }
    }
}
