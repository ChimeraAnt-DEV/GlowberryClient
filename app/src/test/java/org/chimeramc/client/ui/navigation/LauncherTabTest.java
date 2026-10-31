package org.chimeramc.client.ui.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.chimeramc.client.ui.activities.LibraryActivity;
import org.chimeramc.client.ui.activities.MainActivity;
import org.chimeramc.client.ui.activities.ModsFullscreenActivity;
import org.chimeramc.client.ui.activities.SettingsActivity;
import org.junit.Test;

/**
 * The four-destination navigation contract (U1).
 *
 * <p>Instances, Install and Replay are one {@link LibraryActivity}; Cosmetics is a profile sheet on
 * Play; Voice, Controller and Skins live under Settings. These tests pin the destination list, its
 * order (which is also the bumper cycle order) and the two guards that stop a bumper press from
 * navigating away from a running game.
 */
public class LauncherTabTest {

    @Test
    public void tabOrderMatchesConsoleLayout() {
        LauncherTab[] tabs = LauncherTab.values();
        assertSame(LauncherTab.LAUNCH, tabs[0]);
        assertSame(LauncherTab.LIBRARY, tabs[1]);
        assertSame(LauncherTab.MODS, tabs[2]);
        assertSame(LauncherTab.SETTINGS, tabs[3]);
        // Four destinations fit a phone without a scroll. About/Controller/Skins/Voice live inside
        // Settings, Cosmetics is a profile sheet on Play, and Instances/Install/Replay are the
        // Library's inner tabs, so none of them is a destination of its own.
        assertEquals(4, tabs.length);
    }

    @Test
    public void tabsMapToTheirActivities() {
        assertSame(MainActivity.class, LauncherTab.LAUNCH.activity());
        assertSame(LibraryActivity.class, LauncherTab.LIBRARY.activity());
        assertSame(ModsFullscreenActivity.class, LauncherTab.MODS.activity());
        assertSame(SettingsActivity.class, LauncherTab.SETTINGS.activity());
    }

    @Test
    public void forActivityResolvesKnownTabs() {
        assertSame(LauncherTab.LAUNCH, LauncherTab.forActivity(MainActivity.class));
        assertSame(LauncherTab.LIBRARY, LauncherTab.forActivity(LibraryActivity.class));
        assertSame(LauncherTab.MODS, LauncherTab.forActivity(ModsFullscreenActivity.class));
        assertSame(LauncherTab.SETTINGS, LauncherTab.forActivity(SettingsActivity.class));
        assertNull(LauncherTab.forActivity(String.class));
    }

    @Test
    public void everyTabHasADistinctActivity() {
        LauncherTab[] tabs = LauncherTab.values();
        for (int i = 0; i < tabs.length; i++) {
            for (int j = i + 1; j < tabs.length; j++) {
                assertFalse("duplicate activity for " + tabs[i] + " / " + tabs[j],
                        tabs[i].activity() == tabs[j].activity());
            }
        }
    }

    @Test
    public void offsetWrapsAtBothEnds() {
        LauncherTab first = LauncherTab.values()[0];
        LauncherTab last = LauncherTab.values()[LauncherTab.values().length - 1];

        // A wrap keeps a right-bumper press from dead-ending on the last tab.
        assertSame(first, last.offset(1));
        assertSame(last, first.offset(-1));
    }

    @Test
    public void offsetMovesWithinBounds() {
        assertSame(LauncherTab.LIBRARY, LauncherTab.LAUNCH.offset(1));
        assertSame(LauncherTab.LAUNCH, LauncherTab.LIBRARY.offset(-1));
        assertSame(LauncherTab.LAUNCH, LauncherTab.LAUNCH.offset(0));
    }

    @Test
    public void indexIsTheDeclarationOrder() {
        for (LauncherTab tab : LauncherTab.values()) {
            assertSame(tab, LauncherTab.values()[tab.index()]);
        }
    }

    @Test
    public void bumpersAndDpadDriveNavigation() {
        assertEquals(1, LauncherTab.directionForKey(KeyEvent.KEYCODE_BUTTON_R1));
        assertEquals(1, LauncherTab.directionForKey(KeyEvent.KEYCODE_BUTTON_R2));
        assertEquals(1, LauncherTab.directionForKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(-1, LauncherTab.directionForKey(KeyEvent.KEYCODE_BUTTON_L1));
        assertEquals(-1, LauncherTab.directionForKey(KeyEvent.KEYCODE_BUTTON_L2));
        assertEquals(-1, LauncherTab.directionForKey(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(0, LauncherTab.directionForKey(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(0, LauncherTab.directionForKey(KeyEvent.KEYCODE_DPAD_UP));
    }

    @Test
    public void gameSessionSuppressesTabSwitching() {
        assertFalse(LauncherTab.shouldHandleKey(KeyEvent.KEYCODE_BUTTON_R1, true, true));
        assertFalse(LauncherTab.shouldHandleKey(KeyEvent.KEYCODE_BUTTON_L1, true, true));
    }

    @Test
    public void switchingOnlyWhenNavBarIsPresent() {
        assertFalse(LauncherTab.shouldHandleKey(KeyEvent.KEYCODE_BUTTON_R1, false, false));
        assertTrue(LauncherTab.shouldHandleKey(KeyEvent.KEYCODE_BUTTON_R1, true, false));
    }

    @Test
    public void aStoredTabNameRoundTripsAndAnUnknownOneIsNoPreference() {
        // Item 77: the last destination is persisted so a cold start reopens where the user left
        // off. It is stored by name, so a name this build does not have reads as "no preference"
        // rather than opening a wrong tab - which is what storing a resource id would risk.
        for (LauncherTab tab : LauncherTab.values()) {
            assertSame(tab, LauncherTab.fromStoredName(tab.name()));
        }
        assertNull(LauncherTab.fromStoredName(null));
        assertNull(LauncherTab.fromStoredName(""));
        assertNull(LauncherTab.fromStoredName("COSMETICS"));
        // A numeric id from an older build's settings file is not a name either.
        assertNull(LauncherTab.fromStoredName("2131362345"));
    }

    @Test
    public void nonNavigationKeysAreIgnored() {
        assertFalse(LauncherTab.shouldHandleKey(KeyEvent.KEYCODE_BUTTON_A, true, false));
        assertFalse(LauncherTab.shouldHandleKey(KeyEvent.KEYCODE_DPAD_UP, true, false));
        assertFalse(LauncherTab.shouldHandleKey(KeyEvent.KEYCODE_BACK, true, false));
    }
}
