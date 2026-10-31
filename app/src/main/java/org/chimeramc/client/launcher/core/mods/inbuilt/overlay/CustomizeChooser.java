package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.chimeramc.client.R;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.ui.components.UiIconView;
import org.chimeramc.client.ui.components.UiIcons;
import org.chimeramc.client.ui.components.UiTokens;

/**
 * The radial wheel's CLOSET ("customize") chooser.
 *
 * <p>The sector asks a question rather than picking for the player: a small rounded panel, lifted
 * above the screen centre, with a header and two icon buttons — the Mod Menu's settings page and the
 * cosmetics wardrobe. Each button carries the same glyph the Mod Menu uses for that page, so the
 * choice is recognisable from the wheel alone.
 *
 * <p>It is a plain {@link Dialog} rather than an overlay window, so it inherits the standard
 * back-dismiss and dim behaviour and needs no manual attach/detach bookkeeping. The two callbacks
 * are plain {@link Runnable}s, so the caller owns the routing and this class stays a pure surface.
 */
public class CustomizeChooser extends Dialog {

    private final Activity activity;
    private final Runnable onSettings;
    private final Runnable onCosmetics;

    public CustomizeChooser(Activity activity, Runnable onSettings, Runnable onCosmetics) {
        super(activity);
        this.activity = activity;
        this.onSettings = onSettings;
        this.onCosmetics = onCosmetics;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(buildContent());
        setCanceledOnTouchOutside(true);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            // Lifted above the centre: the wheel's hub sits at the screen centre, so a centred
            // panel would cover it and read as part of the wheel. The offset is a fraction of the
            // screen height rather than a fixed dp, so the gap looks the same on a phone and a
            // tablet.
            window.setGravity(Gravity.CENTER);
            WindowManager.LayoutParams attrs = window.getAttributes();
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            attrs.y = -Math.round(metrics.heightPixels * 0.16f);
            attrs.dimAmount = 0.45f;
            window.setAttributes(attrs);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    private View buildContent() {
        int accent = UiTokens.resolveAccent(activity);
        int pad = dp(UiTokens.SPACE_4);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable panel = new GradientDrawable();
        panel.setShape(GradientDrawable.RECTANGLE);
        panel.setCornerRadius(dp(UiTokens.RADIUS_XLARGE));
        panel.setColor(UiTokens.BG_SURFACE);
        panel.setStroke(Math.max(1, dp(1)), UiTokens.STROKE_HAIRLINE);
        root.setBackground(panel);

        TextView title = new TextView(activity);
        title.setText(R.string.wheel_customize_title);
        title.setTextColor(UiTokens.TEXT_PRIMARY);
        title.setTextSize(15f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(pad, dp(UiTokens.SPACE_2), pad, pad);
        root.addView(title);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        buttons.addView(choiceButton(R.string.wheel_customize_mod_settings, UiIcons.Icon.GEAR,
                accent, () -> {
                    dismiss();
                    if (onSettings != null) onSettings.run();
                }));
        LinearLayout.LayoutParams cosmeticsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cosmeticsParams.leftMargin = dp(UiTokens.SPACE_3);
        View cosmetics = choiceButton(R.string.wheel_customize_cosmetics, UiIcons.Icon.HANGER,
                accent, () -> {
                    dismiss();
                    if (onCosmetics != null) onCosmetics.run();
                });
        cosmetics.setLayoutParams(cosmeticsParams);
        buttons.addView(cosmetics);

        root.addView(buttons);
        return root;
    }

    private View choiceButton(int labelRes, UiIcons.Icon icon, int accent, Runnable action) {
        LinearLayout button = new LinearLayout(activity);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(UiTokens.SPACE_4), dp(UiTokens.SPACE_3),
                dp(UiTokens.SPACE_4), dp(UiTokens.SPACE_3));
        button.setMinimumWidth(dp(132));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(UiTokens.RADIUS_CARD));
        bg.setColor(UiTokens.BG_SURFACE_HOVER);
        bg.setStroke(Math.max(1, dp(1)), accent);
        button.setBackground(bg);

        UiIconView iconView = new UiIconView(activity);
        int iconSize = dp(28);
        button.addView(iconView, new LinearLayout.LayoutParams(iconSize, iconSize));
        iconView.setIcon(icon);
        iconView.setIconColor(accent);

        TextView label = new TextView(activity);
        label.setText(labelRes);
        label.setTextColor(UiTokens.TEXT_PRIMARY);
        label.setTextSize(12f);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setPadding(0, dp(UiTokens.SPACE_2), 0, 0);
        button.addView(label);

        button.setOnClickListener(v -> action.run());
        DynamicAnim.applyPressScale(button);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
