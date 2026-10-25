package org.chimeramc.client.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;

public class ThemeManager {
    public static final int MODE_FOLLOW_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    private static final String THEME_PREFS = "theme_prefs";
    private static final String THEME_MODE_KEY = "theme_mode";
    private static int sThemeChangeGeneration = 0;
    private final SharedPreferences prefs;
    private final Activity activity;

    public ThemeManager(Activity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences(THEME_PREFS, Activity.MODE_PRIVATE);
    }

    /**
     * Material You dynamic color, applied per activity right before the content view is
     * inflated so the derived palette is in place for the first layout pass.
     *
     * Applying it here rather than once from {@code Application} keeps it behind the user's
     * "Match Wallpaper Colors" toggle: the material helper installs an overlay theme, and an
     * activity that has already inflated its views would keep the old colors. The activity is
     * recreated when the toggle flips, so the overlay is added or dropped cleanly.
     *
     * The accent always comes from the active theme (dynamic color when enabled, otherwise the
     * brand fallback). There is no separate custom-accent setting any more (U7), so this only
     * re-tints views whose colour was resolved before the theme was applied.
     */
    public static void applyDynamicColors(Activity activity) {
        if (activity == null) return;
        if (!isDynamicColorEnabled(activity)) return;
        try {
            if (!DynamicColors.isDynamicColorAvailable()) return;
            DynamicColors.applyToActivityIfAvailable(activity);
        } catch (Throwable ignored) {
            // A device without the Material You resources simply keeps the static palette.
        }
    }

    public static boolean isDynamicColorEnabled(Context context) {
        if (context == null) return false;
        return context.getSharedPreferences(PersonalizationManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PersonalizationManager.KEY_DYNAMIC_COLOR, false);
    }

    public static boolean isDynamicColorSupported() {
        try {
            return DynamicColors.isDynamicColorAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void applyTheme() {
        updateNightMode();
    }

    public void setThemeMode(int mode) {
        int previous = prefs.getInt(THEME_MODE_KEY, MODE_FOLLOW_SYSTEM);
        prefs.edit().putInt(THEME_MODE_KEY, mode).apply();
        updateNightMode();
        if (previous != mode) {
            sThemeChangeGeneration++;
        }
        if (activity != null && previous != mode) {
            activity.recreate();
        }
    }

    private void updateNightMode() {
        int mode = prefs.getInt(THEME_MODE_KEY, MODE_FOLLOW_SYSTEM);
        switch (mode) {
            case MODE_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case MODE_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default: // MODE_FOLLOW_SYSTEM
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    public int getCurrentMode() {
        int mode = prefs.getInt(THEME_MODE_KEY, MODE_FOLLOW_SYSTEM);
        if (mode < MODE_FOLLOW_SYSTEM || mode > MODE_DARK) {
            mode = MODE_FOLLOW_SYSTEM;
            prefs.edit().putInt(THEME_MODE_KEY, mode).apply();
        }
        return mode;
    }

    public static int getThemeChangeGeneration() {
        return sThemeChangeGeneration;
    }
}