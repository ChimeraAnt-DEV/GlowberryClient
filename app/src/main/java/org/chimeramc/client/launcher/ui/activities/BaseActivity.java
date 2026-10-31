package org.chimeramc.client.ui.activities;

import org.chimeramc.client.util.SafeExecutor;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.graphics.Rect;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.TextViewCompat;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.chimeramc.client.R;
import org.chimeramc.client.core.auth.MsftAccountStore;
import org.chimeramc.client.launcher.controller.ControllerConnectionMonitor;
import org.chimeramc.client.core.mods.inbuilt.overlay.CursorController;
import org.chimeramc.client.core.mods.inbuilt.overlay.GamepadAxes;
import org.chimeramc.client.launcher.ui.views.LauncherCursorInput;
import org.chimeramc.client.launcher.ui.views.LauncherCursorOverlay;
import org.chimeramc.client.launcher.controller.ControllerToastView;
import org.chimeramc.client.launcher.controller.ControllerType;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.ui.navigation.EdgeToEdgeInsets;
import org.chimeramc.client.ui.navigation.LauncherTab;
import org.chimeramc.client.ui.navigation.NavLayoutMode;
import org.chimeramc.client.ui.components.UiShape;
import org.chimeramc.client.ui.components.UiTokens;
import org.chimeramc.client.ui.components.UiType;
import org.chimeramc.client.util.AccountTextUtils;
import org.chimeramc.client.util.PersonalizationManager;
import org.chimeramc.client.util.ThemeManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class BaseActivity extends AppCompatActivity {
    private int appliedThemeGeneration = -1;
    private int appliedPersonalizationGeneration = -1;

    /** Shows the connection pill for whichever screen is in front. */
    private final ControllerConnectionMonitor.Listener controllerListener =
            new ControllerConnectionMonitor.Listener() {
                @Override
                public void onControllerConnected(String name, ControllerType type, String profileName) {
                    if (isFinishing()) return;
                    ControllerToastView.showConnected(BaseActivity.this, name, profileName, type);
                }

                @Override
                public void onControllerDisconnected(String name) {
                    if (isFinishing()) return;
                    ControllerToastView.showDisconnected(BaseActivity.this, name);
                    hideControllerCursor();
                }
            };

    /** Shows the keyboard pill for whichever screen is in front (Phase 12). */
    private final ControllerConnectionMonitor.KeyboardListener keyboardListener =
            new ControllerConnectionMonitor.KeyboardListener() {
                @Override
                public void onKeyboardConnected(String name) {
                    if (isFinishing()) return;
                    ControllerToastView.showKeyboardConnected(BaseActivity.this, name);
                }

                @Override
                public void onKeyboardDisconnected(String name) {
                    if (isFinishing()) return;
                    ControllerToastView.showKeyboardDisconnected(BaseActivity.this, name);
                }
            };
    private boolean navBarInjected = false;
    /** The macOS-style pointer a controller drives on launcher screens (Phase 12). */
    private LauncherCursorOverlay controllerCursor;
    /** Which adaptive navigation shape this window is using (U5). */
    private NavLayoutMode navLayoutMode = NavLayoutMode.BOTTOM_NAV;
    private final OkHttpClient navAvatarClient = BaseActivity.buildLatencyTunedClient();

    private static OkHttpClient buildLatencyTunedClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        org.chimeramc.client.settings.LowLatencyNetworkManager.configure(builder);
        return builder.build();
    }
    private final ExecutorService navAccountExecutor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<Intent> navAccountLoginLauncher;

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences prefs = newBase.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String languageCode = prefs.getString("language", Locale.getDefault().toLanguageTag());
        Locale locale = Locale.forLanguageTag(languageCode);
        Locale.setDefault(locale);
        float fontScale = newBase.getSharedPreferences(PersonalizationManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(PersonalizationManager.KEY_FONT_SCALE, PersonalizationManager.FONT_SCALE_DEFAULT);
        Resources res = newBase.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(locale);
        config.fontScale = fontScale;
        Context localizedContext = newBase.createConfigurationContext(config);
        super.attachBaseContext(localizedContext);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager themeManager = new ThemeManager(this);
        themeManager.applyTheme();
        // Must run before super.onCreate so the dynamic-color overlay theme is installed
        // before any view is inflated in this activity.
        ThemeManager.applyDynamicColors(this);
        appliedThemeGeneration = ThemeManager.getThemeChangeGeneration();
        appliedPersonalizationGeneration = PersonalizationManager.getChangeGeneration();
        super.onCreate(savedInstanceState);
        navAccountLoginLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleNavAccountLoginResult(result.getResultCode(), result.getData()));
        hideSystemUI();
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
                visibility -> getWindow().getDecorView().post(this::hideSystemUI));
    }

    @Override
    public void setContentView(int layoutResID) {
        View contentView = LayoutInflater.from(this).inflate(layoutResID, null);
        wrapWithNavBar(contentView);
    }

    @Override
    public void setContentView(View view) {
        wrapWithNavBar(view);
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        wrapWithNavBar(view);
    }

    /** True once the nav indicator has been placed once, so later moves spring instead of jump. */
    private boolean navIndicatorPlaced;

    /**
     * The one-shot layout listener waiting for the active tab to re-measure after its label
     * changed, or null when no placement is pending.
     *
     * <p>{@link #setActiveNavTab} swaps the tab's text (a compact bar shows only the active
     * label), which invalidates the tab's width. Reading {@code getWidth()} in that same frame
     * returns the *old* width, so the indicator would be positioned under where the tab used to
     * be. One listener is kept and replaced so a fast tab switch cannot stack placements.
     */
    @Nullable
    private View.OnLayoutChangeListener pendingIndicatorListener;

    /** The column holding the nav shape and the content, kept so the nav can be swapped (item 67). */
    private LinearLayout navColumn;
    /** The inflated navigation view, kept so it can be replaced on a window-shape change. */
    private View navShapeView;
    /** The screen's own content view, re-measured when the nav shape changes. */
    private View navContentView;

    private void wrapWithNavBar(View contentView) {
        if (shouldSkipNavBar()) {
            super.setContentView(contentView);
            applyPersonalization();
            return;
        }
        navContentView = contentView;

        // The navigation shape is chosen from the window width (U5): a bottom bar on a compact
        // window, a vertical icon rail on a medium one, and an expanded rail on a large one. Both
        // layouts declare the same ids, so everything below this line is layout-agnostic.
        navLayoutMode = resolveNavLayoutMode();
        boolean rail = navLayoutMode.isRail();
        logNavDecision();

        // The root is a FrameLayout so the controller pointer (Phase 12) can be a MATCH_PARENT
        // overlay above the nav and the content. As a third child of a vertical LinearLayout it
        // would have been measured as another row and pushed the content off screen.
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout column = new LinearLayout(this);
        navColumn = column;
        column.setOrientation(rail ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        wrapper.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View navBar = LayoutInflater.from(this)
                .inflate(rail ? R.layout.nav_rail : R.layout.nav_bar, column, false);
        navShapeView = navBar;

        LinearLayout.LayoutParams contentParams;
        if (rail) {
            // A rail is the leading sibling; the content takes the rest of the width.
            column.addView(navBar);
            contentParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        } else {
            // A bar is the top sibling; the content takes the rest of the height.
            column.addView(navBar);
            contentParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        }
        contentView.setLayoutParams(contentParams);
        column.addView(contentView);

        // The launcher targets SDK 35, so on Android 15 the window is drawn edge-to-edge whether it
        // asks to be or not: statusBarColor/navigationBarColor are ignored and the layout runs behind
        // both bars. Without consuming the insets the nav shape is drawn under the status bar (clipped
        // by a cutout) and the content under the gesture bar. Applied to the live views rather than a
        // wrapper so the padding is the only thing that changes.
        applyEdgeToEdgeInsets(navBar, contentView, rail);

        contentView.setAlpha(0f);
        // A vertical enter offset is the "drop in" for a top bar; a rail arrives from the side,
        // which is the axis the content actually gains room on when the rail is used.
        contentView.setTranslationY(rail ? 0f : 8f * getResources().getDisplayMetrics().density);
        contentView.setTranslationX(rail ? 10f * getResources().getDisplayMetrics().density : 0f);

        super.setContentView(wrapper);
        // The controller pointer sits above the whole screen (nav included), added last so it
        // draws on top; it is non-interactive, so a touch passes straight through to the view
        // under it. Hidden until a pad is actually used.
        controllerCursor = LauncherCursorOverlay.attach(this, wrapper);
        navBarInjected = true;
        setupBaseNavBar();

        applyPersonalization();

        contentView.post(() -> {
            DynamicAnim.springAlphaTo(contentView, 1f).start();
            if (rail) {
                DynamicAnim.springTranslationXTo(contentView, 0f).start();
            } else {
                DynamicAnim.springTranslationYTo(contentView, 0f).start();
            }
        });
    }

    /**
     * Pads the navigation shape and the content for the system bars.
     *
     * <p>Only the padding is set, never a background, so the navigation surface keeps whatever the
     * layout and the theme give it. The decision lives in {@link EdgeToEdgeInsets} so it is tested
     * without a window.
     */
    private void applyEdgeToEdgeInsets(View navBar, View contentView, boolean rail) {
        if (navBar == null || contentView == null) {
            return;
        }
        // The layout's own padding is the base and the insets are added to it: overwriting would
        // drop a declared padding, and the nav layouts do set one.
        final int navBaseLeft = navBar.getPaddingLeft();
        final int navBaseTop = navBar.getPaddingTop();
        final int navBaseRight = navBar.getPaddingRight();
        final int navBaseBottom = navBar.getPaddingBottom();
        final int contentBaseLeft = contentView.getPaddingLeft();
        final int contentBaseTop = contentView.getPaddingTop();
        final int contentBaseRight = contentView.getPaddingRight();
        final int contentBaseBottom = contentView.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(navBar, (view, windowInsets) -> {
            WindowInsetsCompat compat = windowInsets;
            androidx.core.graphics.Insets bars =
                    compat.getInsets(WindowInsetsCompat.Type.systemBars());
            // The display cutout is a separate inset type from the system bars, and in landscape
            // the notch is on one side, so the leading rail would sit under it without this
            // (item 68). Unioned rather than replaced: on a device that reports both, the larger
            // of the two on each edge is what actually needs clearing.
            androidx.core.graphics.Insets cutout =
                    compat.getInsets(WindowInsetsCompat.Type.displayCutout());
            EdgeToEdgeInsets.Insets insets = new EdgeToEdgeInsets.Insets(
                    Math.max(bars.left, cutout.left),
                    Math.max(bars.top, cutout.top),
                    Math.max(bars.right, cutout.right),
                    Math.max(bars.bottom, cutout.bottom));
            EdgeToEdgeInsets.Padding navPadding = EdgeToEdgeInsets.forNavBar(rail, insets);
            view.setPadding(navBaseLeft + navPadding.left, navBaseTop + navPadding.top,
                    navBaseRight + navPadding.right, navBaseBottom + navPadding.bottom);
            EdgeToEdgeInsets.Padding contentPadding = EdgeToEdgeInsets.forContent(rail, insets);
            contentView.setPadding(contentBaseLeft + contentPadding.left,
                    contentBaseTop + contentPadding.top,
                    contentBaseRight + contentPadding.right,
                    contentBaseBottom + contentPadding.bottom);
            // Consumed: the app is a full-screen surface and nothing above it needs the insets.
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(navBar);
    }

    /** The window width in dp, from the smallest width the window can be. */
    private int currentWidthDp() {
        Configuration config = getResources().getConfiguration();
        // smallestScreenWidthDp is the stable breakpoint reading: it does not flip mid-rotation
        // the way screenWidthDp can, so the navigation shape does not change under the user when
        // a device rotates.
        if (config.smallestScreenWidthDp > 0) return config.smallestScreenWidthDp;
        return (int) (getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
    }

    /** The current window width in dp, which tracks a split-screen or freeform column. */
    private int currentWindowWidthDp() {
        Configuration config = getResources().getConfiguration();
        if (config.screenWidthDp > 0) return config.screenWidthDp;
        return (int) (getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
    }

    /**
     * The navigation shape for the current window.
     *
     * Both readings are needed: the smallest width is stable across rotation but stays a tablet's
     * width inside a narrow split-screen column, and the current width tracks the column but flips
     * on rotation. {@link NavLayoutMode#forWindow} takes the smaller classification, so the rail
     * never lands on a window too narrow to hold it.
     */
    private NavLayoutMode resolveNavLayoutMode() {
        return NavLayoutMode.forWindow(currentWidthDp(), currentWindowWidthDp());
    }

    /**
     * Logs the inputs to the navigation-shape decision once per screen (item 94).
     *
     * <p>A "the layout is wrong on my device" report is otherwise unanswerable: the shape is a
     * function of two width readings, the density and the mode, and none of them appear anywhere.
     * Debug-only, so a release build carries no log line for a cosmetic decision.
     */
    private void logNavDecision() {
        if (!org.chimeramc.client.BuildConfig.DEBUG) return;
        android.util.Log.d("NavLayout", "mode=" + navLayoutMode
                + " smallestWidthDp=" + currentWidthDp()
                + " windowWidthDp=" + currentWindowWidthDp()
                + " density=" + getResources().getDisplayMetrics().density);
    }

    private void applyPersonalization() {
        PersonalizationManager pm = new PersonalizationManager(this);
        pm.applyToActivity(this);
        applyMotionScheme(pm);
    }

    /**
     * Puts the shared motion system into the scheme this screen should use (items 75, 345).
     *
     * <p>{@link DynamicAnim} holds one static scheme, and only {@code MainActivity} used to set it.
     * Every other screen therefore animated with whatever the previous screen had left behind -
     * usually the expressive springs, even for a user who had reduced motion on, which is exactly
     * the accessibility setting that must not be ignored. Setting it here means the scheme is a
     * property of the process that every screen re-asserts on the way in.
     *
     * <p>The platform's own "remove animations" scale wins over the in-app switch: a user who told
     * Android to remove animations must not get springs back because a stale preference says
     * motion is on.
     */
    private void applyMotionScheme(PersonalizationManager pm) {
        boolean platformAnimationsOff = false;
        try {
            platformAnimationsOff = android.provider.Settings.Global.getFloat(
                    getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Throwable ignored) {
            // Unreadable on a restricted device; treat motion as allowed.
        }
        DynamicAnim.applyMotionScheme(pm.isReduceMotion(), platformAnimationsOff);
    }

    protected boolean shouldSkipNavBar() {
        return false;
    }

    /**
     * Whether controller bumper keys should switch tabs.
     *
     * Overridden by screens that need the raw button presses themselves: the in-game activity
     * passes keys straight to the game, and the controller screen uses them to highlight
     * buttons. Without an override the tab handler would swallow those presses.
     */
    protected boolean shouldHandleNavKeys() {
        return true;
    }

    /**
     * Moves the controller pointer from the right stick, and consumes the stick while it is up so
     * the screen below does not also scroll (Phase 12).
     *
     * <p>The pointer appears the first time the right stick is moved, not when the pad connects:
     * auto-showing it would make the d-pad centre button click through the pointer for a user who
     * navigates by focus and never wants it. Moving the stick is the explicit request for it.
     *
     * <p>The right stick drives the pointer and the left stick is left alone, so scrolling a list
     * with the left stick still works exactly as it did; only the pointer's own axis is captured.
     * The axis convention comes from {@link GamepadAxes}, the same class the in-game wheel uses, so
     * a pad that reports its right stick on AXIS_Z/AXIS_RZ and one that uses AXIS_RX/AXIS_RY both
     * work without a second table.
     */
    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        if (shouldShowControllerCursor()
                && controllerCursor != null
                && GamepadAxes.isGamepadSource(event)) {
            GamepadAxes.Stick stick = GamepadAxes.read(event);
            if (LauncherCursorInput.shouldIntegrate(stick.x, stick.y,
                    controllerCursor.isCursorVisible())) {
                controllerCursor.move(stick.x, stick.y,
                        CursorController.DEFAULT_DEAD_ZONE, CursorController.DEFAULT_CURVE_EXPONENT);
            }
            // While the pointer is up the right stick belongs to it, so the frame is consumed and
            // does not also scroll the list underneath. The left stick is untouched, so list
            // scrolling still works; B dismisses the pointer and hands the stick back.
            if (controllerCursor.isCursorVisible()) return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    /** Hides the controller pointer (the pad left, or a touch took over). */
    protected void hideControllerCursor() {
        if (controllerCursor != null) controllerCursor.disable();
    }

    /**
     * Whether this screen shows the controller pointer.
     *
     * <p>Off where a screen needs the raw pad presses for itself (the controller editor), so the
     * pointer never competes with the screen's own use of the stick and buttons.
     */
    protected boolean shouldShowControllerCursor() {
        return shouldHandleNavKeys() && !shouldSkipNavBar();
    }


    /**
     * Console-style tab switching. Shoulder buttons and D-pad left/right cycle the top-level
     * destinations, which is how a controller user expects to move between sections.
     *
     * Screens that need those raw presses opt out via {@link #shouldHandleNavKeys()}, and
     * {@link LauncherTab#shouldHandleKey} suppresses the behaviour while a game session is
     * running so a stray bumper press can never drop out of a game.
     */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        // The controller pointer claims the face button as its click before the tab handler runs,
        // so a pad that is pointing at something activates it rather than switching tabs.
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && controllerCursor != null
                && controllerCursor.isCursorVisible()
                && event.getKeyCode() == android.view.KeyEvent.KEYCODE_BUTTON_B) {
            // B dismisses the pointer and hands the right stick back to the screen.
            hideControllerCursor();
            return true;
        }
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && controllerCursor != null
                && controllerCursor.isCursorVisible()
                && LauncherCursorInput.isSelectKey(event.getKeyCode())) {
            if (controllerCursor.select()) return true;
        }
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && navBarInjected
                && shouldHandleNavKeys()
                && LauncherTab.shouldHandleKey(
                        event.getKeyCode(), true,
                        org.chimeramc.client.settings.LowLatencyNetworkManager.isGameSessionActive())) {
            LauncherTab current = LauncherTab.forActivity(getClass());
            LauncherTab next = (current != null)
                    ? current.offset(LauncherTab.directionForKey(event.getKeyCode()))
                    : LauncherTab.LAUNCH;
            if (next != current) {
                startActivity(new Intent(this, next.activity()));
                overridePendingTransition(R.anim.nav_tab_in, R.anim.nav_tab_out);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** True on a narrow window, where only the selected tab shows its label. */
    private boolean compactNavTabs;

    /**
     * Applies the navigation's label configuration for the chosen {@link NavLayoutMode}.
     *
     * A compact window hides the wordmark (the bar has no room for it beside the tabs and the
     * account controls without the two colliding) and shows an icon-only bar whose selected entry
     * keeps its label. A medium window is a rail with no labels at all; an expanded window is a
     * rail that names every destination. The mode is decided from the window width, so all three
     * configurations come from one answer rather than from a resource qualifier that could be
     * half-applied.
     */
    private void applyNavCompactMode() {
        View appName = findViewById(R.id.nav_app_name);
        if (appName != null) {
            // The wordmark is a width decision on the bar and a label decision on the rail
            // (item 84). Hiding it on every compact window left the bar with empty space beside
            // the icon-only tabs, so the brand was invisible even where it fit; the bar's own
            // constraints (the wordmark is pinned between the logo and the tab strip) are what
            // keep it off the tabs, so a width threshold is safe to introduce.
            boolean showWordmark = navLayoutMode.showsWordmark(currentWindowWidthDp());
            appName.setVisibility(showWordmark ? View.VISIBLE : View.GONE);
        }
        // On a rail the row is wide enough for its label, so the label is all-or-nothing: the
        // expanded rail names every destination, the icon-only rail names none.
        compactNavTabs = !navLayoutMode.showsAllLabels();
        LauncherTab own = LauncherTab.forActivity(getClass());
        int ownTabId = own != null ? own.viewId() : 0;
        for (int id : LauncherTab.viewIds()) {
            TextView tab = findViewById(id);
            if (tab == null) continue;
            if (navLayoutMode.isRail()) {
                tab.setText(compactNavTabs ? "" : getString(labelForTab(id)));
            } else if (compactNavTabs) {
                // The bar is horizontal, so it defaults the visible label to this screen's own
                // tab: a screen that never calls setActiveNavTab still shows one label rather
                // than four bare icons. Activities that do call it override this with the same
                // answer.
                tab.setText(id == ownTabId ? getString(labelForTab(id)) : "");
            }
        }
    }

    private void setupBaseNavBar() {
        PersonalizationManager pm = new PersonalizationManager(this);

        // The wordmark is dropped entirely on a narrow window rather than left to shrink, and
        // the tab strips are icon-only there with just the selected label shown. Both come from
        // values/bools.xml (overridden in values-w600dp), so the two configurations are resolved
        // by the resource system and cannot be half-applied.
        applyNavCompactMode();

        // Each tab is a single TextView carrying its own icon via drawableStart, so tinting
        // the compound drawable is what colours the icon.
        for (LauncherTab destination : LauncherTab.values()) {
            TextView tab = findViewById(destination.viewId());
            if (tab == null) continue;
            // The label and the icon come from the enum, so the two nav layouts and the
            // destination list cannot disagree about what a tab is (U5).
            tab.setText(destination.labelRes());
            tab.setCompoundDrawablesRelativeWithIntrinsicBounds(destination.iconRes(), 0, 0, 0);
            tab.setContentDescription(getString(destination.labelRes()));
            tab.setTooltipText(getString(destination.labelRes()));
            int color = getResources().getColor(R.color.text_secondary, getTheme());
            // The one type system (U8): a destination label is the LABEL role, so its size and its
            // variable-font weight come from the shared scale rather than the layout's own values.
            // Applied once at inflation (item 73); setActiveNavTab only changes the weight axis,
            // which is a state, not a re-application of the typeface.
            UiType.apply(tab, UiType.Role.LABEL);
            tab.setTextColor(color);
            TextViewCompat.setCompoundDrawableTintList(tab, ColorStateList.valueOf(color));
            DynamicAnim.applyPressScale(tab);
            if (navLayoutMode.isRail()) applyRailTabShape(tab, false);
        }

        if (pm.hasBackgroundImage()) {
            View navRoot = findViewById(R.id.nav_bar_root);
            if (navRoot != null) {
                boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
                navRoot.setBackgroundColor(isDark
                        ? android.graphics.Color.argb(90, 25, 25, 25)
                        : android.graphics.Color.argb(110, 255, 255, 255));
            }
            View navDivider = findViewById(R.id.nav_divider);
            if (navDivider != null) {
                boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
                navDivider.setBackgroundColor(isDark
                        ? android.graphics.Color.argb(40, 255, 255, 255)
                        : android.graphics.Color.argb(40, 0, 0, 0));
            }
        }

        View backButton = findViewById(R.id.nav_back_button);
        if (backButton != null) {
            // A root destination has nothing to go back to, and a disabled arrow reads as a broken
            // button rather than "you are home" (item 90). Hidden rather than greyed so the header
            // only offers an action that works. Predictive back goes through the dispatcher, which
            // is the AndroidX path available on every supported version (item 89).
            boolean isRootDestination = LauncherTab.forActivity(getClass()) == LauncherTab.LAUNCH;
            backButton.setVisibility(isRootDestination ? View.GONE : View.VISIBLE);
            backButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
            DynamicAnim.applyPressScale(backButton);
        }

        // The logo is a real target, not decoration: tapping the brand is the universal "go home"
        // gesture, and a 28dp view is under the 48dp minimum (item 91). A TouchDelegate widens the
        // hit area around it without changing how it draws, and the click routes to the launch tab
        // so the affordance does something rather than swallowing the tap.
        View logo = findViewById(R.id.nav_logo);
        if (logo != null) {
            logo.setClickable(true);
            logo.setContentDescription(getString(R.string.nav_logo_home));
            logo.setOnClickListener(v -> {
                if (!(this instanceof MainActivity)) {
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    switchNavTab(intent);
                }
            });
            DynamicAnim.applyPressScale(logo);
            View logoParent = (View) logo.getParent();
            if (logoParent != null) {
                logoParent.post(() -> {
                    Rect bounds = new Rect(0, 0, Math.max(dp(48), logo.getWidth()),
                            Math.max(dp(48), logo.getHeight()));
                    logoParent.setTouchDelegate(new TouchDelegate(bounds, logo));
                });
            }
        }

        View signIn = findViewById(R.id.nav_sign_in_button);
        if (signIn != null) {
            signIn.setOnClickListener(v -> navAccountLoginLauncher.launch(new Intent(this, MsftLoginActivity.class)));
            DynamicAnim.applyPressScale(signIn);
        }

        View avatarContainer = findViewById(R.id.nav_account_avatar_container);
        if (avatarContainer != null) {
            avatarContainer.setOnClickListener(v -> startActivity(new Intent(this, AccountsActivity.class)));
            DynamicAnim.applyPressScale(avatarContainer);
        }

        // Click the whole row, not the 22dp icon, so the label is part of the hit target.
        findViewById(R.id.nav_tab_launch).setOnClickListener(v -> {
            if (!(this instanceof MainActivity)) {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                switchNavTab(intent);
            }
        });
        findViewById(R.id.nav_tab_library).setOnClickListener(v -> {
            if (!(this instanceof LibraryActivity)) {
                switchNavTab(new Intent(this, LibraryActivity.class));
            }
        });
        findViewById(R.id.nav_tab_mods).setOnClickListener(v -> {
            if (!(this instanceof ModsFullscreenActivity)) {
                switchNavTab(new Intent(this, ModsFullscreenActivity.class));
            }
        });
        findViewById(R.id.nav_tab_settings).setOnClickListener(v -> {
            if (!(this instanceof SettingsActivity)) {
                switchNavTab(new Intent(this, SettingsActivity.class));
            }
        });

        refreshNavAccountUI();
    }

    /**
     * Plays the nav bar's arrival animation: each tab pops in with a slight overshoot, staggered
     * left to right.
     *
     * <p>Called only on a cold start that came through the splash, so it reads as the launcher
     * arriving rather than replaying every time a tab is revisited. The overshoot language is
     * shared with the home cards
     * ({@link org.chimeramc.client.ui.animation.DynamicAnim#overshootScaleIn}), so the whole
     * arrival is one motion rather than two unrelated effects.
     */
    protected void animateNavTabsArrival() {
        if (!navBarInjected) return;
        int[] tabIds = LauncherTab.viewIds();
        View[] tabs = new View[tabIds.length];
        for (int i = 0; i < tabIds.length; i++) {
            tabs[i] = findViewById(tabIds[i]);
        }
        org.chimeramc.client.ui.animation.DynamicAnim.staggerArrival(tabs, 60L);
    }

    protected void refreshNavAccountUI() {
        if (!navBarInjected) return;
        java.util.List<MsftAccountStore.MsftAccount> list = MsftAccountStore.list(this);
        MsftAccountStore.MsftAccount active = null;
        for (MsftAccountStore.MsftAccount a : list) if (a.active) { active = a; break; }
        View signIn = findViewById(R.id.nav_sign_in_button);
        View avatarContainer = findViewById(R.id.nav_account_avatar_container);
        if (active == null) {
            if (signIn != null) signIn.setVisibility(View.VISIBLE);
            if (avatarContainer != null) avatarContainer.setVisibility(View.GONE);
            clearNavAvatar();
        } else {
            if (signIn != null) signIn.setVisibility(View.GONE);
            if (avatarContainer != null) avatarContainer.setVisibility(View.VISIBLE);
            loadNavXboxAvatar(active);
        }
    }

    private void clearNavAvatar() {
        com.microsoft.xbox.idp.toolkit.CircleImageView avatar = findViewById(R.id.nav_account_avatar);
        ProgressBar progress = findViewById(R.id.nav_avatar_progress);
        if (avatar != null) avatar.setImageResource(R.drawable.ic_minecraft_cube);
        if (progress != null) progress.setVisibility(View.GONE);
    }

    private void loadNavXboxAvatar(MsftAccountStore.MsftAccount active) {
        com.microsoft.xbox.idp.toolkit.CircleImageView avatar = findViewById(R.id.nav_account_avatar);
        ProgressBar progress = findViewById(R.id.nav_avatar_progress);
        if (avatar == null) return;

        String url = AccountTextUtils.sanitizeUrl(active != null ? active.xboxAvatarUrl : null);
        if (url == null) {
            avatar.setImageResource(R.drawable.ic_minecraft_cube);
            if (progress != null) progress.setVisibility(View.GONE);
            return;
        }

        Object currentUrl = avatar.getTag(R.id.nav_account_avatar);
        if (url.equals(currentUrl) && avatar.getDrawable() != null) {
            if (progress != null) progress.setVisibility(View.GONE);
            return;
        }

        Bitmap cached = AccountTextUtils.getCachedAvatar(url);
        if (cached != null) {
            avatar.setTag(R.id.nav_account_avatar, url);
            avatar.setImageBitmap(cached);
            if (progress != null) progress.setVisibility(View.GONE);
            return;
        }

        avatar.setTag(R.id.nav_account_avatar, url);
        avatar.setImageResource(R.drawable.ic_minecraft_cube);
        if (progress != null) progress.setVisibility(View.VISIBLE);
        if (!runOnNavAccountExecutor(() -> {
            Bitmap bmp = null;
            try (Response imgResp = navAvatarClient.newCall(new Request.Builder().url(url).build()).execute()) {
                if (imgResp.isSuccessful() && imgResp.body() != null) {
                    bmp = android.graphics.BitmapFactory.decodeStream(imgResp.body().byteStream());
                }
            } catch (Exception ignored) {
            }

            final Bitmap loaded = bmp;
            runOnUiThread(() -> {
                if (!url.equals(avatar.getTag(R.id.nav_account_avatar))) return;
                if (loaded != null) {
                    AccountTextUtils.cacheAvatar(url, loaded);
                    avatar.setImageBitmap(loaded);
                }
                if (progress != null) progress.setVisibility(View.GONE);
            });
        })) {
            // onDestroy shut the pool down while this avatar was being requested; the placeholder
            // stays up, which is the same result as a failed download.
            if (progress != null) progress.setVisibility(View.GONE);
        }
    }

    private void handleNavAccountLoginResult(int resultCode, Intent data) {
        if (resultCode == RESULT_OK && data != null
                && data.getBooleanExtra(MsftLoginActivity.EXTRA_LOGIN_COMPLETED, false)) {
            String name = data.getStringExtra(MsftLoginActivity.EXTRA_LOGIN_NAME);
            String statusName = name != null ? name : getString(R.string.not_signed_in);
            Toast.makeText(this, getString(R.string.ms_login_success, statusName), Toast.LENGTH_SHORT).show();
            refreshNavAccountUI();
            onNavAccountChanged();
            return;
        }
        refreshNavAccountUI();
    }

    protected void onNavAccountChanged() {
    }

    protected void setActiveNavTab(int activeTabId) {
        // Remember the destination so a cold start reopens where the user left off (item 77).
        // Recorded here, in the one place that knows the active tab, rather than in each
        // activity's onCreate: every screen already calls this to tint its own entry, so the
        // memory cannot drift from the highlight.
        LauncherTab visited = LauncherTab.forViewId(activeTabId);
        if (visited != null) {
            org.chimeramc.client.settings.FeatureSettings.getInstance()
                    .setLastNavTab(visited.name());
        }
        if (!navBarInjected) return;

        PersonalizationManager pm = new PersonalizationManager(this);
        int accent = pm.getAccentColor();
        int onSurface = getResources().getColor(R.color.on_surface, getTheme());
        // The accent is only usable as the active-tab colour if it is readable on the navigation
        // surface. Under Material You it comes from the wallpaper, so it can land almost on top of
        // the surface and make the selected destination unreadable (item 87); NavAccent applies the
        // contrast floor and falls back to on-surface, which the indicator and the weight still
        // distinguish.
        int surface = getResources().getColor(R.color.ui_bg_surface, getTheme());
        int accentColor = org.chimeramc.client.ui.navigation.NavAccent.resolve(accent, surface, onSurface);
        int inactive = getResources().getColor(R.color.text_secondary, getTheme());

        // The two colours are installed once as a state list, so every tab recolours itself from
        // its own `selected` state and this method never branches on the colour (item 88). The
        // state list is rebuilt here because the accent can change while the app is running.
        ColorStateList tabTint = navTabTint(accentColor, inactive);

        for (int id : LauncherTab.viewIds()) {
            TextView tab = findViewById(id);
            if (tab == null) continue;
            boolean active = id == activeTabId;
            // `selected` is what a screen reader announces, and it is also the state the compound
            // drawable tint, the text colour and the press feedback key off, so it is the only
            // thing that has to change here.
            tab.setTextColor(tabTint);
            TextViewCompat.setCompoundDrawableTintList(tab, tabTint);
            tab.setSelected(active);
            tab.setContentDescription(describeTab(id, active));
            // The weight axis carries the emphasis: the active entry is heavier, not just a
            // different colour, which is the Expressive type treatment (U6). The bold flag stays
            // as the fallback for a device that ignores the axis.
            tab.setTypeface(tab.getTypeface(), active
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            UiType.applyWeight(tab, active ? UiTokens.TITLE_WEIGHT : UiTokens.ROW_TITLE_WEIGHT);
            if (navLayoutMode.isRail()) applyRailTabShape(tab, active);
            if (navLayoutMode.isRail()) {
                tab.setText(navLayoutMode.showsAllLabels() ? getString(labelForTab(id)) : "");
            } else if (compactNavTabs) {
                tab.setText(id == activeTabId ? getString(labelForTab(id)) : "");
            }
            if (id == activeTabId) moveNavIndicator(tab, accentColor);
        }
    }

    /**
     * The two-colour state list every destination is tinted from (item 88).
     *
     * <p>Building it here rather than branching inside the update loop means the text colour, the
     * compound icon and anything else state-aware all recolour from the tab's own
     * {@code selected} flag: one state list, one place the colours are defined, and no per-state
     * colour arithmetic at each update.
     */
    private ColorStateList navTabTint(int accentColor, int inactiveColor) {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_selected},
                        new int[]{},
                },
                new int[]{accentColor, inactiveColor});
    }

    /**
     * Slides the accent indicator under the active tab.
     *
     * <p>The indicator lives in the same {@code FrameLayout} as the tab row, so it scrolls with the
     * strip instead of floating over it. It is positioned from the tab's own bounds once layout has
     * run (a fresh screen's tabs have no width yet at {@code onResume}), and its width tracks the
     * tab so a wider label keeps the bar centred. The move is a spring so the marker glides rather
     * than snapping; reduced motion makes it instant through {@link DynamicAnim}.
     */
    private void moveNavIndicator(TextView tab, int accentColor) {
        View indicator = findViewById(R.id.nav_active_indicator);
        if (indicator == null) return;
        indicator.setVisibility(View.VISIBLE);
        tintIndicator(indicator, accentColor);
        Runnable place = () -> {
            View parent = (View) indicator.getParent();
            if (parent == null) return;
            int tabWidth = tab.getWidth();
            if (tabWidth <= 0) return;
            boolean rail = navLayoutMode.isRail();
            ViewGroup.LayoutParams lp = indicator.getLayoutParams();
            if (rail) {
                // A vertical indicator: its length tracks the row height and it slides on Y.
                int indicatorHeight = Math.max(dp(18), (int) (tab.getHeight() * 0.55f));
                if (lp.height != indicatorHeight) {
                    lp.height = indicatorHeight;
                    indicator.setLayoutParams(lp);
                }
                float targetY = tab.getTop() + (tab.getHeight() - indicatorHeight) / 2f;
                placeIndicator(indicator, 0f, targetY, true);
                return;
            }
            int indicatorWidth = Math.max(dp(24), (int) (tabWidth * 0.55f));
            if (lp.width != indicatorWidth) {
                lp.width = indicatorWidth;
                indicator.setLayoutParams(lp);
            }
            float targetX = tab.getLeft() + (tabWidth - indicatorWidth) / 2f;
            placeIndicator(indicator, targetX, 0f, false);
        };
        placeIndicatorWhenMeasured(tab, place);
    }

    /**
     * Runs [place] once the tab has a real width.
     *
     * <p>If the tab already has a width and no layout is pending the placement runs immediately.
     * Otherwise it waits for the next layout pass, because the width read in this frame is stale:
     * {@link #setActiveNavTab} has just changed the label, and on a compact bar that change is what
     * gives the active tab its width. A pending listener is replaced rather than added, so a rapid
     * tab switch leaves exactly one waiting placement and never a burst of them.
     */
    private void placeIndicatorWhenMeasured(View tab, Runnable place) {
        if (pendingIndicatorListener != null) {
            tab.removeOnLayoutChangeListener(pendingIndicatorListener);
            pendingIndicatorListener = null;
        }
        if (tab.getWidth() > 0 && !tab.isLayoutRequested()) {
            place.run();
            return;
        }
        View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (v.getWidth() <= 0) return;
                v.removeOnLayoutChangeListener(this);
                if (pendingIndicatorListener == this) pendingIndicatorListener = null;
                place.run();
            }
        };
        pendingIndicatorListener = listener;
        tab.addOnLayoutChangeListener(listener);
        // Fallback: if the view is already attached and no layout pass is coming (for example the
        // width did not actually change), place from the current bounds so the indicator is never
        // left unpositioned.
        tab.post(() -> {
            if (pendingIndicatorListener != listener) return;
            tab.removeOnLayoutChangeListener(listener);
            pendingIndicatorListener = null;
            place.run();
        });
    }

    /**
     * Gives a rail destination its selection shape (U6).
     *
     * <p>On a rail the selected row is a filled surface rather than a bare row, and its corners
     * morph between the scale's CHIP and CARD stops as the selection moves — the shape change is
     * what makes the selection read as a state, not a highlight. The radius is animated so the
     * morph is visible; reduced motion snaps it. The fill is an accent wash, so the shape follows
     * the accent without introducing a second accent source.
     */
    private void applyRailTabShape(TextView tab, boolean selected) {
        PersonalizationManager pm = new PersonalizationManager(this);
        int accent = pm.getAccentColor();
        if (accent == 0) accent = getResources().getColor(R.color.primary, getTheme());
        float density = getResources().getDisplayMetrics().density;

        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        shape.setColor(selected
                ? org.chimeramc.client.ui.components.UiTokens.withAlpha(accent, 40)
                : android.graphics.Color.TRANSPARENT);
        int from = (selected ? UiShape.Role.CHIP : UiShape.Role.CARD).radiusDp;
        int to = (selected ? UiShape.Role.CARD : UiShape.Role.CHIP).radiusDp;
        shape.setCornerRadius(UiShape.morph(from, to, 1f) * density);

        android.graphics.drawable.RippleDrawable ripple =
                new android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(
                                org.chimeramc.client.ui.components.UiTokens.withAlpha(accent, 48)),
                        shape, null);
        tab.setBackground(ripple);

        if (!DynamicAnim.areAnimationsEnabled()) return;
        android.animation.ValueAnimator morph = android.animation.ValueAnimator.ofFloat(0f, 1f);
        morph.setDuration(org.chimeramc.client.ui.components.UiTokens.MOTION_DEFAULT_MS);
        morph.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            shape.setCornerRadius(UiShape.morph(from, to, t) * density);
        });
        morph.start();
    }

    /**
     * Moves the indicator to a target offset on the axis the current layout uses.
     *
     * <p>The first placement fades in rather than sliding in from the far edge, and reduced motion
     * makes every placement instant. Kept in one helper so the bar and the rail cannot diverge.
     */
    private void placeIndicator(View indicator, float targetX, float targetY, boolean vertical) {
        if (!DynamicAnim.areAnimationsEnabled()) {
            indicator.setTranslationX(targetX);
            indicator.setTranslationY(targetY);
            indicator.setAlpha(1f);
            navIndicatorPlaced = true;
            return;
        }
        if (!navIndicatorPlaced) {
            // First placement: no slide, just fade in under the entry.
            indicator.setTranslationX(targetX);
            indicator.setTranslationY(targetY);
            indicator.setAlpha(0f);
            indicator.animate().alpha(1f).setDuration(180L).start();
            navIndicatorPlaced = true;
            return;
        }
        if (vertical) {
            DynamicAnim.springTranslationYTo(indicator, targetY).start();
        } else {
            DynamicAnim.springTranslationXTo(indicator, targetX).start();
        }
        indicator.setAlpha(1f);
    }

    /** Tints the sliding indicator to the active accent. */
    private void tintIndicator(View indicator, int color) {
        android.graphics.drawable.Drawable bg = indicator.getBackground();
        if (bg == null) return;
        android.graphics.drawable.Drawable tinted = androidx.core.graphics.drawable.DrawableCompat.wrap(bg.mutate());
        androidx.core.graphics.drawable.DrawableCompat.setTint(tinted, color);
        indicator.setBackground(tinted);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** The label resource for a tab view id, or 0 for an id that is not a destination. */
    private int labelForTab(int tabId) {
        LauncherTab tab = LauncherTab.forViewId(tabId);
        return tab != null ? tab.labelRes() : 0;
    }

    /**
     * The destination a cold start should open, or null to leave the caller's own default
     * (item 77).
     *
     * <p>Reads the persisted tab name through {@link LauncherTab#fromStoredName}, so a name this
     * build no longer has - or a settings file written by an older build - is "no preference"
     * rather than a crash. The caller decides what "no preference" means; the splash opens Play.
     */
    public static LauncherTab rememberedTab() {
        try {
            return LauncherTab.fromStoredName(
                    org.chimeramc.client.settings.FeatureSettings.getInstance().getLastNavTab());
        } catch (Throwable t) {
            // A settings read must never be able to stop the launcher from starting; falling
            // through to the default screen is the same result as having no preference.
            return null;
        }
    }

    /**
     * The accessibility description for a tab.
     *
     * <p>The icon-only rail and the compact bar hide the label with {@code setText("")}, so without
     * this a screen reader would announce a bare, nameless node. The active destination also says
     * so, because {@code selected} alone is not read on every Android version.
     */
    private CharSequence describeTab(int tabId, boolean active) {
        int label = labelForTab(tabId);
        if (label == 0) return null;
        String name = getString(label);
        return active ? getString(R.string.nav_tab_selected, name) : name;
    }

    @Override
    protected void onStart() {
        super.onStart();
        ControllerConnectionMonitor monitor = controllerMonitor();
        monitor.setListener(controllerListener);
        monitor.setKeyboardListener(keyboardListener);
        monitor.start();
    }

    /**
     * Returns the process-wide connection monitor.
     *
     * One monitor serves every screen; each activity attaches its own listener while visible so
     * exactly the front activity shows the pill.
     */
    private ControllerConnectionMonitor controllerMonitor() {
        return ControllerConnectionMonitor.get(this);
    }

    /** The process-wide monitor, so MinecraftActivity can share it. */
    public static ControllerConnectionMonitor sharedControllerMonitor(Context context) {
        return ControllerConnectionMonitor.get(context);
    }

    @Override
    protected void onResume() {
        super.onResume();
        int currentGen = ThemeManager.getThemeChangeGeneration();
        int currentPGen = PersonalizationManager.getChangeGeneration();
        if (appliedThemeGeneration != currentGen || appliedPersonalizationGeneration != currentPGen) {
            appliedThemeGeneration = currentGen;
            appliedPersonalizationGeneration = currentPGen;
            recreate();
            return;
        }
        getDelegate().applyDayNight();
        hideSystemUI();
        refreshNavAccountUI();
    }

    @Override
    protected void onStop() {
        // Detach only this screen's listener. The monitor's device registration is process-wide
        // and deliberately left running: stopping it here unregistered the listener while the
        // incoming screen (or the game) was already in front, so a pad plugged in during a tab
        // change was never seen.
        controllerMonitor().clearListener(controllerListener);
        controllerMonitor().clearKeyboardListener(keyboardListener);
        super.onStop();
    }

    /**
     * Submits nav-bar account work, tolerating an executor {@link #onDestroy()} has shut down.
     *
     * <p>The avatar fetch is kicked off from a UI event, so a submit that raced the teardown threw
     * {@link java.util.concurrent.RejectedExecutionException} on the main thread and took the screen
     * down. Returns false when the work could not be submitted.
     */
    private boolean runOnNavAccountExecutor(Runnable work) {
        return SafeExecutor.submit(navAccountExecutor, work);
    }

    @Override
    protected void onDestroy() {
        navAccountExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        getDelegate().applyDayNight();
        hideSystemUI();
        // A window that crosses a breakpoint (a foldable unfolding, a tablet entering split screen)
        // needs the other navigation shape. The nav is swapped in place rather than by recreating
        // the activity (item 67): a recreate would throw away the screen's scroll position, any
        // in-progress text and the whole view hierarchy, for a change that only concerns the nav
        // chrome. Only the nav view and the content's layout params are rebuilt.
        NavLayoutMode next = resolveNavLayoutMode();
        if (navBarInjected && next != navLayoutMode) {
            swapNavShape(next);
        }
    }

    /**
     * Replaces the navigation view with the one for {@code mode}, keeping the content view and the
     * activity state (item 67).
     *
     * <p>The column holds exactly two children - the nav and the content - and only the nav is
     * recreated. The content's layout params swap axis with the column so the rail's leading
     * sibling becomes the bar's top sibling, and the insets are re-applied because the two shapes
     * absorb different edges.
     */
    private void swapNavShape(NavLayoutMode mode) {
        if (navColumn == null || navShapeView == null || navContentView == null) return;
        boolean rail = mode.isRail();
        navLayoutMode = mode;

        navColumn.removeView(navShapeView);
        navShapeView = LayoutInflater.from(this)
                .inflate(rail ? R.layout.nav_rail : R.layout.nav_bar, navColumn, false);
        navColumn.setOrientation(rail ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        navColumn.addView(navShapeView, 0);

        LinearLayout.LayoutParams contentParams = rail
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        navContentView.setLayoutParams(contentParams);

        applyEdgeToEdgeInsets(navShapeView, navContentView, rail);
        logNavDecision();
        setupBaseNavBar();
        // The content was already visible, so it must not replay its arrival animation; only the
        // nav shape needs to settle into place.
        LauncherTab own = LauncherTab.forActivity(getClass());
        if (own != null) setActiveNavTab(own.viewId());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    protected void hideSystemUI() {
        View decorView = getWindow().getDecorView();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        }

        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private boolean shouldSuppressTransition(Intent intent) {
        return intent != null && (intent.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0;
    }

    private void switchNavTab(Intent intent){
        startActivity(intent);
        if (!shouldSuppressTransition(intent)) {
            overridePendingTransition(R.anim.nav_tab_in, R.anim.nav_tab_out);
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    @Override
    public void startActivity(Intent intent) {
        super.startActivity(intent);
        if (!shouldSuppressTransition(intent)) {
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        }
    }

    @Override
    public void startActivity(Intent intent, @Nullable Bundle options) {
        super.startActivity(intent, options);
        if (!shouldSuppressTransition(intent)) {
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        }
    }

    @Override
    public void finishAfterTransition() {
        super.finishAfterTransition();
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }
}
