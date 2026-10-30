package org.chimeramc.client.ui.navigation;

import android.app.Activity;
import android.view.KeyEvent;

import org.chimeramc.client.R;
import org.chimeramc.client.ui.activities.LibraryActivity;
import org.chimeramc.client.ui.activities.MainActivity;
import org.chimeramc.client.ui.activities.ModsFullscreenActivity;
import org.chimeramc.client.ui.activities.SettingsActivity;

/**
 * The launcher's top-level destinations.
 *
 * The launcher navigates by {@link Activity}, not by swapping fragments inside one screen,
 * so a tab is a label plus the activity it opens. Declaration order is the order shown in
 * the nav bar and the order controller bumpers cycle through, so the two cannot drift.
 *
 * There are four destinations, not six (U1). Instances, Install and Replay are all "a list you
 * browse and act on", so they are one {@link LibraryActivity} with an inner segmented control.
 * Cosmetics is a profile sheet on Play rather than a tab. Controller, Skins and Voice are reached
 * from Settings, which is where a configuration screen belongs. A tab that is really a section of
 * another tab is what made the old bar too long for a phone.
 *
 * <p>Voice in particular is deliberately <b>not</b> a launcher nav tab. Its in-game home is the
 * Mod Menu's own Voice section, and its launcher-side home is Settings; a nav tab for it put a
 * communication feature in the same row as navigation.
 */
public enum LauncherTab {
    LAUNCH(MainActivity.class, R.id.nav_tab_launch, R.string.nav_launch, R.drawable.ic_nav_launch),
    LIBRARY(LibraryActivity.class, R.id.nav_tab_library, R.string.nav_library,
            R.drawable.ic_nav_instances),
    MODS(ModsFullscreenActivity.class, R.id.nav_tab_mods, R.string.nav_mods,
            R.drawable.ic_inbuilt_mods),
    SETTINGS(SettingsActivity.class, R.id.nav_tab_settings, R.string.nav_settings,
            R.drawable.ic_nav_settings);

    private final Class<? extends Activity> activity;
    private final int viewId;
    private final int labelRes;
    private final int iconRes;

    LauncherTab(Class<? extends Activity> activity, int viewId, int labelRes, int iconRes) {
        this.activity = activity;
        this.viewId = viewId;
        this.labelRes = labelRes;
        this.iconRes = iconRes;
    }

    public Class<? extends Activity> activity() {
        return activity;
    }

    /**
     * The id of this tab's view, shared by {@code nav_bar.xml} and {@code nav_rail.xml}.
     *
     * <p>Both layouts declare the same ids, so a screen never branches on which one inflated.
     * Keeping the id here, beside the activity and the label, is what makes adding a destination a
     * single edit: the enum, the two layouts and the string and drawable resources.
     */
    public int viewId() {
        return viewId;
    }

    /** The string resource for this destination's label. */
    public int labelRes() {
        return labelRes;
    }

    /** The drawable resource for this destination's icon. */
    public int iconRes() {
        return iconRes;
    }

    /** The tab whose view has [viewId], or null when the id is not a destination. */
    public static LauncherTab forViewId(int viewId) {
        for (LauncherTab tab : values()) {
            if (tab.viewId == viewId) return tab;
        }
        return null;
    }

    /** Every destination's view id, in declaration order. */
    public static int[] viewIds() {
        LauncherTab[] tabs = values();
        int[] ids = new int[tabs.length];
        for (int i = 0; i < tabs.length; i++) ids[i] = tabs[i].viewId;
        return ids;
    }

    /** Index of this tab, used to position the selection indicator. */
    public int index() {
        return ordinal();
    }

    /**
     * The tab [steps] positions away, wrapping at both ends.
     *
     * Console dashboards cycle rather than stopping; wrapping keeps a right-bumper press
     * from dead-ending on the last tab with no feedback.
     */
    public LauncherTab offset(int steps) {
        LauncherTab[] tabs = values();
        int count = tabs.length;
        int next = ((ordinal() + steps) % count + count) % count;
        return tabs[next];
    }

    /** The tab whose activity is [activityClass], or null when it is not a tab. */
    public static LauncherTab forActivity(Class<?> activityClass) {
        for (LauncherTab tab : values()) {
            if (tab.activity == activityClass) return tab;
        }
        return null;
    }

    /**
     * Direction a key press moves through the tab bar, or 0 when the key is not tab
     * navigation.
     *
     * Both bumper pairs are accepted because L1/R1 (shoulders) and L2/R2 (triggers) report
     * as different key codes across controller types. D-pad left/right is included so a
     * controller with non-working bumpers is not locked to one tab.
     */
    public static int directionForKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return -1;
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return 1;
            default:
                return 0;
        }
    }

    /** True when [keyCode] should move the tab selection. */
    public static boolean isTabNavigationKey(int keyCode) {
        return directionForKey(keyCode) != 0;
    }

    /**
     * Whether a bumper press should switch tabs right now.
     *
     * Guarded on two conditions. The nav bar must actually be present, and no game session
     * may be running. The session check is defence in depth: gameplay runs in its own
     * activity which has no nav bar, but this makes it impossible for any future in-game
     * screen to have a bumper press navigate away from a running game.
     */
    public static boolean shouldHandleKey(int keyCode, boolean navBarPresent, boolean gameSessionActive) {
        return navBarPresent && !gameSessionActive && isTabNavigationKey(keyCode);
    }
}