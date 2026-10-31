package org.chimeramc.client.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import org.chimeramc.client.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import org.chimeramc.client.util.AppLog;

public class PersonalizationManager {

    private static final String TAG = "PersonalizationManager";
    public static final String PREFS_NAME = "personalization_prefs";
    private static final String KEY_ACCENT_COLOR = "accent_color";
    private static final String KEY_BG_IMAGE_PATH = "bg_image_path";
    private static final String KEY_BG_IMAGE_BLUR = "bg_image_blur";
    private static final String KEY_BG_IMAGE_BRIGHTNESS = "bg_image_brightness";
    
    // UI Customization Settings Keys
    private static final String KEY_ANIMATION_SPEED = "animation_speed";
    private static final String KEY_UI_TRANSPARENCY = "ui_transparency";
    private static final String KEY_CARD_ROUNDING = "card_rounding";
    private static final String KEY_ICON_SIZE = "icon_size";
    public static final String KEY_FONT_SCALE = "font_scale";
    /** Persisted font key from {@link LauncherFonts}. */
    public static final String KEY_FONT_FAMILY = "font_family";
    private static final String KEY_BLUR_INTENSITY = "blur_intensity";
    private static final String KEY_SHOW_ANIMATIONS = "show_animations";
    private static final String KEY_ENABLE_GLOW = "enable_glow_effects";
    private static final String KEY_HAPTIC_FEEDBACK = "haptic_feedback";
    public static final String KEY_DYNAMIC_COLOR = "dynamic_color";

    public static final int BG_BLUR_MIN = 0;
    public static final int BG_BLUR_MAX = 25;
    public static final int BG_BRIGHTNESS_MIN = 1;
    public static final int BG_BRIGHTNESS_MAX = 150;
    public static final int BG_BRIGHTNESS_DEFAULT = 100;
    
    // UI Customization Constants
    public static final float ANIMATION_SPEED_MIN = 0.5f;
    public static final float ANIMATION_SPEED_MAX = 3.0f;
    public static final float ANIMATION_SPEED_DEFAULT = 1.0f;
    public static final int UI_TRANSPARENCY_MIN = 0;
    public static final int UI_TRANSPARENCY_MAX = 100;
    public static final int UI_TRANSPARENCY_DEFAULT = 85;
    public static final int CARD_ROUNDING_MIN = 0;
    public static final int CARD_ROUNDING_MAX = 32;
    public static final int CARD_ROUNDING_DEFAULT = 16;
    public static final int ICON_SIZE_MIN = 50;
    public static final int ICON_SIZE_MAX = 150;
    public static final int ICON_SIZE_DEFAULT = 100;
    public static final float FONT_SCALE_MIN = 0.8f;
    public static final float FONT_SCALE_MAX = 1.5f;
    public static final float FONT_SCALE_DEFAULT = 1.0f;
    public static final int BLUR_INTENSITY_MIN = 0;
    public static final int BLUR_INTENSITY_MAX = 100;
    public static final int BLUR_INTENSITY_DEFAULT = 50;

    private static int sChangeGeneration = 0;

    private final SharedPreferences prefs;
    private final Context context;

    private static final int GLASS_ALPHA_DARK = 50;
    private static final int GLASS_R_DARK = 25;
    private static final int GLASS_G_DARK = 25;
    private static final int GLASS_B_DARK = 25;

    private static final int GLASS_ALPHA_LIGHT = 50;
    private static final int GLASS_R_LIGHT = 255;
    private static final int GLASS_G_LIGHT = 255;
    private static final int GLASS_B_LIGHT = 255;

    public PersonalizationManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * The one accent colour (U7): the theme's {@code colorPrimary}, which Material You rewrites
     * from the wallpaper when dynamic colour is on and which otherwise resolves to the brand
     * colour from {@code colors.xml}.
     *
     * <p>There is deliberately no user-selected accent any more. Two sources of truth — a stored
     * swatch and the theme attribute — meant a wallpaper-derived palette and a hand-picked colour
     * fought over every tinted view. Reading the theme makes dynamic colour and the brand
     * fallback the only two outcomes, which is what the design system asks for.
     */
    public int getAccentColor() {
        if (context == null) return BRAND_ACCENT;
        return resolveThemeColor(context, androidx.appcompat.R.attr.colorPrimary, R.color.primary);
    }

    /** The brand accent, used when no theme is available to resolve one. */
    public static final int BRAND_ACCENT = 0xFF0F5BE4;

    public String getBackgroundImagePath() {
        return prefs.getString(KEY_BG_IMAGE_PATH, null);
    }

    public boolean hasBackgroundImage() {
        String path = getBackgroundImagePath();
        if (path == null) return false;
        return new File(path).exists();
    }

    public int getBackgroundImageBlur() {
        return clamp(prefs.getInt(KEY_BG_IMAGE_BLUR, BG_BLUR_MIN), BG_BLUR_MIN, BG_BLUR_MAX);
    }

    public void setBackgroundImageBlur(int blurRadius) {
        int clamped = clamp(blurRadius, BG_BLUR_MIN, BG_BLUR_MAX);
        if (getBackgroundImageBlur() == clamped) return;
        prefs.edit().putInt(KEY_BG_IMAGE_BLUR, clamped).apply();
        sChangeGeneration++;
    }

    public int getBackgroundImageBrightness() {
        return clamp(prefs.getInt(KEY_BG_IMAGE_BRIGHTNESS, BG_BRIGHTNESS_DEFAULT),
                BG_BRIGHTNESS_MIN, BG_BRIGHTNESS_MAX);
    }

    public void setBackgroundImageBrightness(int brightnessPercent) {
        int clamped = clamp(brightnessPercent, BG_BRIGHTNESS_MIN, BG_BRIGHTNESS_MAX);
        if (getBackgroundImageBrightness() == clamped) return;
        prefs.edit().putInt(KEY_BG_IMAGE_BRIGHTNESS, clamped).apply();
        sChangeGeneration++;
    }

    public void setBackgroundImage(Uri sourceUri, Context activityContext) {
        try {
            File destDir = new File(context.getFilesDir(), "personalization");
            if (!destDir.exists()) destDir.mkdirs();
            File destFile = new File(destDir, "background.jpg");

            InputStream is = activityContext.getContentResolver().openInputStream(sourceUri);
            if (is == null) return;

            Bitmap original = BitmapFactory.decodeStream(is);
            is.close();
            if (original == null) return;

            int maxDim = 2048;
            float scale = Math.min((float) maxDim / original.getWidth(), (float) maxDim / original.getHeight());
            if (scale < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(original,
                        (int) (original.getWidth() * scale),
                        (int) (original.getHeight() * scale), true);
                original.recycle();
                original = scaled;
            }

            FileOutputStream fos = new FileOutputStream(destFile);
            original.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close();
            original.recycle();

            prefs.edit().putString(KEY_BG_IMAGE_PATH, destFile.getAbsolutePath()).apply();
            sChangeGeneration++;
        } catch (Exception e) {
            AppLog.warn(TAG, "Saving the background image failed", e);
        }
    }

    public void clearBackgroundImage() {
        String path = getBackgroundImagePath();
        if (path != null) {
            File f = new File(path);
            if (f.exists()) f.delete();
        }
        prefs.edit().remove(KEY_BG_IMAGE_PATH).apply();
        sChangeGeneration++;
    }

    public Bitmap loadBackgroundBitmap() {
        String path = getBackgroundImagePath();
        if (path == null) return null;
        File f = new File(path);
        if (!f.exists()) return null;
        try {
            return BitmapFactory.decodeFile(path);
        } catch (Exception e) {
            return null;
        }
    }

    // UI Customization Getters and Setters
    
    public float getAnimationSpeed() {
        return prefs.getFloat(KEY_ANIMATION_SPEED, ANIMATION_SPEED_DEFAULT);
    }

    public void setAnimationSpeed(float speed) {
        float clamped = Math.max(ANIMATION_SPEED_MIN, Math.min(ANIMATION_SPEED_MAX, speed));
        if (getAnimationSpeed() == clamped) return;
        prefs.edit().putFloat(KEY_ANIMATION_SPEED, clamped).apply();
        sChangeGeneration++;
    }

    public int getUiTransparency() {
        return prefs.getInt(KEY_UI_TRANSPARENCY, UI_TRANSPARENCY_DEFAULT);
    }

    public void setUiTransparency(int transparency) {
        int clamped = Math.max(UI_TRANSPARENCY_MIN, Math.min(UI_TRANSPARENCY_MAX, transparency));
        if (getUiTransparency() == clamped) return;
        prefs.edit().putInt(KEY_UI_TRANSPARENCY, clamped).apply();
        sChangeGeneration++;
    }

    public int getCardRounding() {
        return prefs.getInt(KEY_CARD_ROUNDING, CARD_ROUNDING_DEFAULT);
    }

    public float getCardRoundingPx(Context ctx) {
        return getCardRounding() * ctx.getResources().getDisplayMetrics().density;
    }

    public void setCardRounding(int roundingPx) {
        int clamped = Math.max(CARD_ROUNDING_MIN, Math.min(CARD_ROUNDING_MAX, roundingPx));
        if (getCardRounding() == clamped) return;
        prefs.edit().putInt(KEY_CARD_ROUNDING, clamped).apply();
        sChangeGeneration++;
    }

    public int getIconSize() {
        return prefs.getInt(KEY_ICON_SIZE, ICON_SIZE_DEFAULT);
    }

    public void setIconSize(int sizePercent) {
        int clamped = Math.max(ICON_SIZE_MIN, Math.min(ICON_SIZE_MAX, sizePercent));
        if (getIconSize() == clamped) return;
        prefs.edit().putInt(KEY_ICON_SIZE, clamped).apply();
        sChangeGeneration++;
    }

    public float getFontScale() {
        return prefs.getFloat(KEY_FONT_SCALE, FONT_SCALE_DEFAULT);
    }

    public void setFontScale(float scale) {
        float clamped = Math.max(FONT_SCALE_MIN, Math.min(FONT_SCALE_MAX, scale));
        if (getFontScale() == clamped) return;
        prefs.edit().putFloat(KEY_FONT_SCALE, clamped).apply();
        sChangeGeneration++;
    }

    /** The persisted font key; see {@link LauncherFonts}. */
    public String getFontFamily() {
        return prefs.getString(KEY_FONT_FAMILY,
                LauncherFonts.DEFAULT_KEY);
    }

    public void setFontFamily(String key) {
        String resolved = key == null
                ? LauncherFonts.DEFAULT_KEY : key;
        if (resolved.equals(getFontFamily())) return;
        prefs.edit().putString(KEY_FONT_FAMILY, resolved).apply();
        sChangeGeneration++;
    }

    public int getBlurIntensity() {
        return prefs.getInt(KEY_BLUR_INTENSITY, BLUR_INTENSITY_DEFAULT);
    }

    public void setBlurIntensity(int intensity) {
        int clamped = Math.max(BLUR_INTENSITY_MIN, Math.min(BLUR_INTENSITY_MAX, intensity));
        if (getBlurIntensity() == clamped) return;
        prefs.edit().putInt(KEY_BLUR_INTENSITY, clamped).apply();
        sChangeGeneration++;
    }

    public boolean isShowAnimations() {
        return prefs.getBoolean(KEY_SHOW_ANIMATIONS, true);
    }

    public void setShowAnimations(boolean enabled) {
        if (isShowAnimations() == enabled) return;
        prefs.edit().putBoolean(KEY_SHOW_ANIMATIONS, enabled).apply();
        sChangeGeneration++;
    }

    public boolean isEnableGlowEffects() {
        return prefs.getBoolean(KEY_ENABLE_GLOW, true);
    }

    /**
     * The single accessibility switch: one control that turns off decorative motion and the
     * glow/glass treatment together.
     *
     * <p>Two separate toggles ("show animations", "enable glow") meant a player who wanted calm
     * had to find both, and the OS "remove animations" setting only covered the first. This is
     * derived from the two stored flags so an older install keeps its choice, and writing it
     * moves both.
     */
    public boolean isReduceMotion() {
        return !isShowAnimations() || !isEnableGlowEffects();
    }

    public void setReduceMotion(boolean reduce) {
        setShowAnimations(!reduce);
        setEnableGlowEffects(!reduce);
    }

    public void setEnableGlowEffects(boolean enabled) {
        if (isEnableGlowEffects() == enabled) return;
        prefs.edit().putBoolean(KEY_ENABLE_GLOW, enabled).apply();
        sChangeGeneration++;
    }

    /**
     * Whether touch interactions emit haptics. On by default: the signals are deliberately
     * light, and the heavier ones only fire on confirm/reject. Turning it off silences every
     * signal from {@link org.chimeramc.client.ui.animation.UiTouchFeedback}.
     */
    public boolean isHapticFeedbackEnabled() {
        return prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true);
    }

    public void setHapticFeedbackEnabled(boolean enabled) {
        if (isHapticFeedbackEnabled() == enabled) return;
        prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, enabled).apply();
        org.chimeramc.client.ui.animation.UiTouchFeedback.setEnabled(enabled);
        sChangeGeneration++;
    }

    /**
     * Material You palette derived from the wallpaper. Off by default, and only ever takes
     * effect on Android 12+ where the platform can produce one.
     */
    public boolean isDynamicColorEnabled() {
        return prefs.getBoolean(KEY_DYNAMIC_COLOR, false);
    }

    public void setDynamicColorEnabled(boolean enabled) {
        if (isDynamicColorEnabled() == enabled) return;
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply();
        sChangeGeneration++;
    }

    public void resetAllCustomizations() {
        prefs.edit()
            .remove(KEY_ACCENT_COLOR)
            .remove(KEY_BG_IMAGE_PATH)
            .remove(KEY_BG_IMAGE_BLUR)
            .remove(KEY_BG_IMAGE_BRIGHTNESS)
            .remove(KEY_ANIMATION_SPEED)
            .remove(KEY_UI_TRANSPARENCY)
            .remove(KEY_CARD_ROUNDING)
            .remove(KEY_ICON_SIZE)
            .remove(KEY_FONT_SCALE)
            .remove(KEY_FONT_FAMILY)
            .remove(KEY_BLUR_INTENSITY)
            .remove(KEY_SHOW_ANIMATIONS)
            .remove(KEY_ENABLE_GLOW)
            .remove(KEY_HAPTIC_FEEDBACK)
            .remove(KEY_DYNAMIC_COLOR)
            .apply();
        org.chimeramc.client.ui.animation.UiTouchFeedback.setEnabled(true);
        sChangeGeneration++;
    }


    public static int getChangeGeneration() {
        return sChangeGeneration;
    }

    public void applyToActivity(Activity activity) {
        if (activity instanceof org.chimeramc.client.ui.activities.SplashActivity) return;

        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;

        int accent = getAccentColor();
        boolean hasBg = hasBackgroundImage();

        if (hasBg) {
            applyBackgroundImage(activity, rootView);
        }

        if (accent != 0) {
            applyAccentColorRecursive(rootView, accent, activity);
            applyNavBarAccent(activity, accent);
        }

        applyFontFamilyToActivity(activity, rootView);

        // The Icon Size preference is a real scale on every fixed-size icon. Applied here rather
        // than per-screen so the nav bar (injected into the same content view) and every screen
        // honour it from one place.
        org.chimeramc.client.launcher.util.IconSizeApplier.apply(rootView, getIconSize());
    }

    /**
     * Re-skins every text view with the chosen font.
     *
     * Runs after the theme's own {@code android:fontFamily} so a chosen face wins, and is a
     * no-op for the default face to avoid pointless work on the common path.
     */
    public void applyFontFamilyToActivity(Activity activity, ViewGroup rootView) {
        LauncherFonts.Entry entry = null;
        String key = getFontFamily();
        for (LauncherFonts.Entry e
                : LauncherFonts.entries()) {
            if (e.key.equals(key)) {
                entry = e;
                break;
            }
        }
        if (entry == null) return;
        if (LauncherFonts.DEFAULT_KEY.equals(entry.key)) return;
        android.graphics.Typeface typeface =
                LauncherFonts.typeface(activity, entry.key);
        LauncherFonts.applyRecursive(rootView, typeface);
    }

    private void applyNavBarAccent(Activity activity, int accent) {
        TextView appName = activity.findViewById(R.id.nav_app_name);
        if (appName != null) {
            applySolidAccentText(appName, accent);
        }

        View signInBtn = activity.findViewById(R.id.nav_sign_in_button);
        if (signInBtn instanceof Button) {
            Button btn = (Button) signInBtn;
            btn.setBackgroundTintList(ColorStateList.valueOf(accent));
            btn.setTextColor(Color.WHITE);
        }
    }

    public void applySolidAccentText(TextView textView, int accentColor) {
        textView.getPaint().setShader(null);
        textView.setTextColor(accentColor);
        textView.invalidate();
    }

    private void applyBackgroundImage(Activity activity, ViewGroup rootView) {
        ImageView bgView = rootView.findViewWithTag("personalization_bg");
        if (bgView == null) {
            bgView = new ImageView(activity);
            bgView.setTag("personalization_bg");
            bgView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            bgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            rootView.addView(bgView, 0);
        }
        if (!applyBackgroundImageToView(bgView)) return;

        View overlayView = rootView.findViewWithTag("personalization_overlay");
        if (overlayView == null) {
            overlayView = new View(activity);
            overlayView.setTag("personalization_overlay");
            overlayView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            int bgIndex = rootView.indexOfChild(bgView);
            rootView.addView(overlayView, bgIndex + 1);
        }

        boolean isDark = isDarkMode(activity);
        overlayView.setBackgroundColor(isDark ? Color.argb(100, 0, 0, 0) : Color.argb(80, 255, 255, 255));
        overlayView.setVisibility(View.VISIBLE);

        makeChildBackgroundsTranslucent(rootView, activity);
    }

    public void refreshBackgroundEffects(Activity activity) {
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;
        ImageView bgView = rootView.findViewWithTag("personalization_bg");
        if (bgView != null) {
            refreshBackgroundImageView(bgView);
        }
    }

    public void refreshBackgroundColorEffects(Activity activity) {
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;
        ImageView bgView = rootView.findViewWithTag("personalization_bg");
        if (bgView != null) {
            applyBackgroundImageEffects(bgView);
        }
    }

    public boolean supportsRealtimeBackgroundBlur() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    public boolean applyBackgroundImageToView(ImageView bgView) {
        Bitmap bmp = loadBackgroundBitmap();
        if (bmp == null) return false;

        Bitmap displayBitmap = bmp;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && getBackgroundImageBlur() > 0) {
            Bitmap blurredBitmap = createBlurredBitmap(bmp, getBackgroundImageBlur());
            if (blurredBitmap != null) {
                displayBitmap = blurredBitmap;
                if (displayBitmap != bmp) {
                    bmp.recycle();
                }
            }
        }

        bgView.setImageBitmap(displayBitmap);
        applyBackgroundImageEffects(bgView);
        return true;
    }

    public void refreshBackgroundImageView(ImageView bgView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            applyBackgroundImageToView(bgView);
        } else {
            applyBackgroundImageEffects(bgView);
        }
    }

    public void applyBackgroundImageEffects(ImageView bgView) {
        int blurRadius = getBackgroundImageBlur();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (blurRadius > 0) {
                bgView.setRenderEffect(RenderEffect.createBlurEffect(
                        blurRadius, blurRadius, Shader.TileMode.CLAMP));
            } else {
                bgView.setRenderEffect(null);
            }
        }

        float brightness = getBackgroundImageBrightness() / 100f;
        if (Math.abs(brightness - 1f) < 0.001f) {
            bgView.clearColorFilter();
            return;
        }

        ColorMatrix matrix = new ColorMatrix(new float[]{
                brightness, 0, 0, 0, 0,
                0, brightness, 0, 0, 0,
                0, 0, brightness, 0, 0,
                0, 0, 0, 1, 0
        });
        bgView.setColorFilter(new ColorMatrixColorFilter(matrix));
    }

    private Bitmap createBlurredBitmap(Bitmap source, int radius) {
        if (source == null || radius <= 0) return source;
        try {
            int maxDim = 720;
            int width = source.getWidth();
            int height = source.getHeight();
            float scale = Math.min(1f, (float) maxDim / Math.max(width, height));
            int scaledWidth = Math.max(1, Math.round(width * scale));
            int scaledHeight = Math.max(1, Math.round(height * scale));
            Bitmap working = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
                    .copy(Bitmap.Config.ARGB_8888, true);

            int w = working.getWidth();
            int h = working.getHeight();
            int[] pixels = new int[w * h];
            int[] temp = new int[w * h];
            int[] output = new int[w * h];
            working.getPixels(pixels, 0, w, 0, 0, w, h);

            int windowSize = radius * 2 + 1;
            for (int y = 0; y < h; y++) {
                int row = y * w;
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                for (int i = -radius; i <= radius; i++) {
                    int color = pixels[row + clamp(i, 0, w - 1)];
                    a += Color.alpha(color);
                    r += Color.red(color);
                    g += Color.green(color);
                    b += Color.blue(color);
                }
                for (int x = 0; x < w; x++) {
                    temp[row + x] = Color.argb(a / windowSize, r / windowSize, g / windowSize, b / windowSize);

                    int removeColor = pixels[row + clamp(x - radius, 0, w - 1)];
                    int addColor = pixels[row + clamp(x + radius + 1, 0, w - 1)];
                    a += Color.alpha(addColor) - Color.alpha(removeColor);
                    r += Color.red(addColor) - Color.red(removeColor);
                    g += Color.green(addColor) - Color.green(removeColor);
                    b += Color.blue(addColor) - Color.blue(removeColor);
                }
            }

            for (int x = 0; x < w; x++) {
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                for (int i = -radius; i <= radius; i++) {
                    int color = temp[clamp(i, 0, h - 1) * w + x];
                    a += Color.alpha(color);
                    r += Color.red(color);
                    g += Color.green(color);
                    b += Color.blue(color);
                }
                for (int y = 0; y < h; y++) {
                    output[y * w + x] = Color.argb(a / windowSize, r / windowSize, g / windowSize, b / windowSize);

                    int removeColor = temp[clamp(y - radius, 0, h - 1) * w + x];
                    int addColor = temp[clamp(y + radius + 1, 0, h - 1) * w + x];
                    a += Color.alpha(addColor) - Color.alpha(removeColor);
                    r += Color.red(addColor) - Color.red(removeColor);
                    g += Color.green(addColor) - Color.green(removeColor);
                    b += Color.blue(addColor) - Color.blue(removeColor);
                }
            }

            working.setPixels(output, 0, w, 0, 0, w, h);
            return working;
        } catch (Exception e) {
            return null;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int getGlassColor(boolean isDark) {
        if (isDark) {
            return Color.argb(GLASS_ALPHA_DARK, GLASS_R_DARK, GLASS_G_DARK, GLASS_B_DARK);
        } else {
            return Color.argb(GLASS_ALPHA_LIGHT, GLASS_R_LIGHT, GLASS_G_LIGHT, GLASS_B_LIGHT);
        }
    }

    private void makeChildBackgroundsTranslucent(ViewGroup parent, Activity activity) {
        int bgColor = ContextCompat.getColor(activity, R.color.background);
        int surfColor = ContextCompat.getColor(activity, R.color.surface);
        int surfVariantColor = ContextCompat.getColor(activity, R.color.surface_variant);
        boolean isDark = isDarkMode(activity);
        int glassColor = getGlassColor(isDark);

        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if ("personalization_bg".equals(child.getTag()) || "personalization_overlay".equals(child.getTag())) {
                continue;
            }

            if (child instanceof CardView) {
                CardView cv = (CardView) child;
                int cardColor = cv.getCardBackgroundColor().getDefaultColor();
                if (cardColor == surfColor || cardColor == bgColor || cardColor == surfVariantColor
                        || isNearBlack(cardColor) || isNearWhite(cardColor)) {
                    cv.setCardBackgroundColor(glassColor);
                }
                makeChildBackgroundsTranslucent((ViewGroup) child, activity);
                continue;
            }

            Drawable bg = child.getBackground();
            if (bg instanceof ColorDrawable) {
                int color = ((ColorDrawable) bg).getColor();
                if (color == bgColor || isNearBlack(color)) {
                    child.setBackgroundColor(Color.TRANSPARENT);
                } else if (color == surfColor || color == surfVariantColor || isNearWhite(color)) {
                    child.setBackgroundColor(glassColor);
                }
            } else if (bg instanceof GradientDrawable) {
                GradientDrawable gd = (GradientDrawable) bg;
                try {
                    if (gd.getColor() != null) {
                        int gdColor = gd.getColor().getDefaultColor();
                        if (gdColor == surfColor || gdColor == bgColor || gdColor == surfVariantColor
                                || isNearBlack(gdColor) || isNearWhite(gdColor)) {
                            GradientDrawable newGd = new GradientDrawable();
                            newGd.setCornerRadius(dpToPx(activity, 12));
                            newGd.setColor(glassColor);
                            child.setBackground(newGd);
                        }
                    }
                } catch (Exception ignored) {
                    AppLog.warnOnce(TAG, "tint-child", "Applying the accent to a child view failed", ignored);
                }
            } else if (bg instanceof StateListDrawable) {
                StateListDrawable sld = (StateListDrawable) bg;
                boolean modified = false;
                // API 29 accessors; see stateDrawables().
                java.util.List<Drawable> states = stateDrawables(sld);
                for (int si = 0; si < states.size(); si++) {
                    Drawable stateDrawable = states.get(si);
                    if (stateDrawable instanceof GradientDrawable) {
                        GradientDrawable sgd = (GradientDrawable) stateDrawable;
                        try {
                            if (sgd.getColor() != null) {
                                int sgdColor = sgd.getColor().getDefaultColor();
                                if (sgdColor == surfColor || sgdColor == bgColor || sgdColor == surfVariantColor
                                        || isNearBlack(sgdColor) || isNearWhite(sgdColor)) {
                                    sgd.setColor(glassColor);
                                    modified = true;
                                }
                            }
                        } catch (Exception ignored) {
                            AppLog.warnOnce(TAG, "tint-gradient", "Applying the accent to a gradient failed", ignored);
                        }
                    }
                }
                if (modified) {
                    sld.invalidateSelf();
                }
            }

            if (child instanceof ViewGroup) {
                makeChildBackgroundsTranslucent((ViewGroup) child, activity);
            }
        }
    }

    private boolean isNearBlack(int color) {
        return Color.red(color) < 20 && Color.green(color) < 20 && Color.blue(color) < 20 && Color.alpha(color) > 200;
    }

    private boolean isNearWhite(int color) {
        return Color.red(color) > 235 && Color.green(color) > 235 && Color.blue(color) > 235 && Color.alpha(color) > 200;
    }

    /**
     * The state drawables of a {@link StateListDrawable}, on every API level.
     *
     * <p>{@code StateListDrawable.getStateCount()} and {@code getStateDrawable(int)} are API 29, and
     * this app runs on API 28 with core-library desugaring off, so calling them throws
     * {@code NoSuchMethodError} — an {@link Error}, which a surrounding {@code catch (Exception)}
     * does not contain, so it would unwind the accent pass. {@code DrawableContainerState} exposes
     * the same children and has existed since API 1.
     */
    private static java.util.List<Drawable> stateDrawables(StateListDrawable stateList) {
        java.util.List<Drawable> out = new java.util.ArrayList<>();
        if (stateList == null) {
            return out;
        }
        try {
            android.graphics.drawable.Drawable.ConstantState constantState = stateList.getConstantState();
            if (constantState instanceof android.graphics.drawable.DrawableContainer.DrawableContainerState) {
                android.graphics.drawable.DrawableContainer.DrawableContainerState container =
                        (android.graphics.drawable.DrawableContainer.DrawableContainerState) constantState;
                for (int i = 0; i < container.getChildCount(); i++) {
                    out.add(container.getChild(i));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public void applyAccentColorRecursive(View view, int accentColor, Activity activity) {
        applyAccentColorRecursive(view, accentColor, (Context) activity);
    }

    public void applyAccentColorRecursive(View view, int accentColor, Context ctx) {
        int defaultPrimary = resolveThemeColor(ctx, androidx.appcompat.R.attr.colorPrimary, R.color.primary);
        int defaultPrimaryContainer = resolveThemeColor(ctx, com.google.android.material.R.attr.colorPrimaryContainer, R.color.primary_container);
        int defaultSecondary = resolveThemeColor(ctx, com.google.android.material.R.attr.colorSecondary, R.color.secondary);
        int defaultTertiary = resolveThemeColor(ctx, com.google.android.material.R.attr.colorTertiary, R.color.tertiary);
        int defaultAccentText = ContextCompat.getColor(ctx, R.color.accent_text);

        if (view instanceof com.google.android.material.switchmaterial.SwitchMaterial) {
            com.google.android.material.switchmaterial.SwitchMaterial sw =
                    (com.google.android.material.switchmaterial.SwitchMaterial) view;
            try {
                int[][] states = {{android.R.attr.state_checked}, {}};
                sw.setThumbTintList(new ColorStateList(states, new int[]{accentColor, 0xFFAAAAAA}));
                int trackChecked = Color.argb(100, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor));
                sw.setTrackTintList(new ColorStateList(states, new int[]{trackChecked, 0xFF555555}));
            } catch (Exception ignored) {
                AppLog.warnOnce(TAG, "tint-switch", "Tinting a switch failed", ignored);
            }
        }

        if (view instanceof Switch && !(view instanceof com.google.android.material.switchmaterial.SwitchMaterial)) {
            Switch sw = (Switch) view;
            try {
                int[][] states = {{android.R.attr.state_checked}, {}};
                sw.setThumbTintList(new ColorStateList(states, new int[]{accentColor, 0xFFAAAAAA}));
                int trackChecked = Color.argb(100, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor));
                sw.setTrackTintList(new ColorStateList(states, new int[]{trackChecked, 0xFF555555}));
            } catch (Exception ignored) {
                AppLog.warnOnce(TAG, "tint-switch2", "Tinting a switch failed", ignored);
            }
        }

        if (view instanceof androidx.appcompat.widget.SwitchCompat && !(view instanceof com.google.android.material.switchmaterial.SwitchMaterial)) {
            androidx.appcompat.widget.SwitchCompat sw = (androidx.appcompat.widget.SwitchCompat) view;
            try {
                int[][] states = {{android.R.attr.state_checked}, {}};
                sw.setThumbTintList(new ColorStateList(states, new int[]{accentColor, 0xFFAAAAAA}));
                int trackChecked = Color.argb(100, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor));
                sw.setTrackTintList(new ColorStateList(states, new int[]{trackChecked, 0xFF555555}));
            } catch (Exception ignored) {
                AppLog.warnOnce(TAG, "tint-switch3", "Tinting a switch failed", ignored);
            }
        }

        if (view instanceof SeekBar) {
            SeekBar sb = (SeekBar) view;
            try {
                sb.setProgressTintList(ColorStateList.valueOf(accentColor));
                sb.setThumbTintList(ColorStateList.valueOf(accentColor));
            } catch (Exception ignored) {
                AppLog.warnOnce(TAG, "tint-seekbar", "Tinting a seek bar failed", ignored);
            }
        }

        if (view instanceof ProgressBar) {
            ProgressBar pb = (ProgressBar) view;
            try {
                pb.setProgressTintList(ColorStateList.valueOf(accentColor));
                pb.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(42, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))));
                pb.setIndeterminateTintList(ColorStateList.valueOf(accentColor));
            } catch (Exception ignored) {
                AppLog.warnOnce(TAG, "tint-progress", "Tinting a progress bar failed", ignored);
            }
        }

        if (view instanceof Button) {
            Button btn = (Button) view;
            try {
                if (btn.getBackgroundTintList() != null) {
                    int currentTint = btn.getBackgroundTintList().getDefaultColor();
                    if (currentTint == defaultPrimary || currentTint == defaultSecondary || currentTint == defaultTertiary) {
                        btn.setBackgroundTintList(ColorStateList.valueOf(accentColor));
                        btn.setTextColor(Color.WHITE);
                    }
                }
            } catch (Exception ignored) {
                AppLog.warnOnce(TAG, "tint-button", "Tinting a button failed", ignored);
            }

            if (btn.getCurrentTextColor() == defaultPrimary || btn.getCurrentTextColor() == defaultAccentText) {
                btn.setTextColor(accentColor);
            }

            Drawable bg = btn.getBackground();
            if (bg instanceof GradientDrawable) {
                GradientDrawable gd = (GradientDrawable) bg;
                try {
                    if (gd.getColor() != null) {
                        int gdColor = gd.getColor().getDefaultColor();
                        if (gdColor == defaultPrimary || gdColor == defaultSecondary || gdColor == defaultTertiary
                                || gdColor == defaultPrimaryContainer) {
                            gd.setColor(accentColor);
                            btn.setTextColor(Color.WHITE);
                        }
                    }
                } catch (Exception ignored) {
                    AppLog.warnOnce(TAG, "tint-button2", "Tinting a button failed", ignored);
                }
            }
        }

        if (view instanceof TextView && !(view instanceof Button)) {
            TextView tv = (TextView) view;
            int textColor = tv.getCurrentTextColor();

            if (textColor == defaultPrimary || textColor == defaultAccentText) {
                tv.setTextColor(accentColor);
            }

            try {
                if (tv.getCompoundDrawableTintList() != null) {
                    int tintColor = tv.getCompoundDrawableTintList().getDefaultColor();
                    if (tintColor == defaultPrimary) {
                        tv.setCompoundDrawableTintList(ColorStateList.valueOf(accentColor));
                    }
                }
            } catch (Exception ignored) {
                AppLog.warnOnce(TAG, "tint-textview", "Tinting a text view drawable failed", ignored);
            }
        }

        if (view instanceof ImageView && !(view instanceof Button)) {
            ImageView iv = (ImageView) view;
            if (iv.getImageTintList() != null) {
                int tint = iv.getImageTintList().getDefaultColor();
                if (tint == defaultPrimary) {
                    iv.setImageTintList(ColorStateList.valueOf(accentColor));
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyAccentColorRecursive(group.getChildAt(i), accentColor, ctx);
            }
        }
    }

    public void applyAccentToView(View view, Context context) {
        int accent = getAccentColor();
        if (accent == 0) return;
        applyAccentColorRecursive(view, accent, context);
    }

    private boolean isDarkMode(Activity activity) {
        return isDarkMode((Context) activity);
    }

    /**
     * Resolves a theme attribute to a concrete color, falling back to a static resource.
     *
     * The accent-override logic compares a view's current color against "the theme's
     * primary" to decide whether the view is still wearing the default palette. With
     * Material You that default is no longer a fixed {@code @color/primary} — it depends on
     * the wallpaper — so comparing against the static resource would silently stop matching
     * and a custom accent would no longer override tinted views.
     */
    private static int resolveThemeColor(Context ctx, int attr, int fallbackRes) {
        try {
            android.util.TypedValue value = new android.util.TypedValue();
            if (ctx.getTheme().resolveAttribute(attr, value, true) && value.resourceId != 0) {
                return ContextCompat.getColor(ctx, value.resourceId);
            }
            if (value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
        } catch (Throwable ignored) {
        }
        return ContextCompat.getColor(ctx, fallbackRes);
    }

    public boolean isDarkMode(Context ctx) {
        int nightModeFlags = ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public int getEffectiveSurfaceColor() {
        if (hasBackgroundImage()) {
            return getGlassColor(isDarkMode(context));
        }
        return ContextCompat.getColor(context, R.color.surface);
    }

    public void applyGlassToView(View view) {
        if (!hasBackgroundImage()) return;
        boolean isDark = isDarkMode(context);
        int glassColor = getGlassColor(isDark);
        int surfColor = ContextCompat.getColor(context, R.color.surface);
        int bgColor = ContextCompat.getColor(context, R.color.background);
        int surfVariantColor = ContextCompat.getColor(context, R.color.surface_variant);

        Drawable bg = view.getBackground();
        if (bg instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) bg;
            try {
                if (gd.getColor() != null) {
                    int gdColor = gd.getColor().getDefaultColor();
                    if (gdColor == surfColor || gdColor == bgColor || gdColor == surfVariantColor
                            || isNearBlack(gdColor) || isNearWhite(gdColor)) {
                        gd.setColor(glassColor);
                    }
                }
            } catch (Exception ignored) {
                AppLog.warnOnce(TAG, "tint-card", "Tinting a card background failed", ignored);
            }
        } else if (bg instanceof StateListDrawable) {
            StateListDrawable sld = (StateListDrawable) bg;
            // API 29 accessors; see stateDrawables().
            java.util.List<Drawable> states = stateDrawables(sld);
            for (int si = 0; si < states.size(); si++) {
                Drawable stateDrawable = states.get(si);
                if (stateDrawable instanceof GradientDrawable) {
                    GradientDrawable sgd = (GradientDrawable) stateDrawable;
                    try {
                        if (sgd.getColor() != null) {
                            int sgdColor = sgd.getColor().getDefaultColor();
                            if (sgdColor == surfColor || sgdColor == bgColor || sgdColor == surfVariantColor
                                    || isNearBlack(sgdColor) || isNearWhite(sgdColor)) {
                                sgd.setColor(glassColor);
                            }
                        }
                    } catch (Exception ignored) {
                        AppLog.warnOnce(TAG, "tint-card2", "Tinting a card background failed", ignored);
                    }
                }
            }
            sld.invalidateSelf();
        } else if (view instanceof CardView) {
            CardView cv = (CardView) view;
            int cardColor = cv.getCardBackgroundColor().getDefaultColor();
            if (cardColor == surfColor || cardColor == bgColor || cardColor == surfVariantColor
                    || isNearBlack(cardColor) || isNearWhite(cardColor)) {
                cv.setCardBackgroundColor(glassColor);
            }
        }
    }

    private float dpToPx(Context context, float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }

}
