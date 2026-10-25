package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.core.mods.inbuilt.vip.MenuBindRelay;
import org.chimeramc.client.launcher.controller.ControllerConnectionMonitor;
import org.chimeramc.client.launcher.controller.ControllerInputProcessor;
import org.chimeramc.client.launcher.controller.ControllerProfile;
import org.chimeramc.client.launcher.controller.ControllerProfileList;
import org.chimeramc.client.launcher.controller.ControllerProfileManager;
import org.chimeramc.client.launcher.controller.ControllerType;
import org.chimeramc.client.launcher.ui.views.Controller3DView;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.ui.components.UiTokens;

import java.util.List;

/**
 * The Controller page of the Mod Menu (a Supporter page).
 *
 * <p>It was the VIP window's own Controller tab. Now that there is one menu (U4) it is a page of
 * that menu, so it is reachable from the launcher's Mods screen as well as from the in-game bind.
 * It mirrors the launcher's Controller settings — profiles, dead zone, sensitivity, rumble — and
 * opens the same {@link ControllerProfileEditor} the launcher uses, so there is one editor rather
 * than two that drift.
 *
 * <p>Every step is isolated through {@link ModMenuGuard}: this page is built over a running game,
 * and a bad profile or illustration lookup must land in the diagnostics report instead of taking
 * the session down.
 */
final class ModMenuControllerSection {

    private final Activity activity;
    private final FrameLayout host;
    private final int accent;

    private Controller3DView illustration;
    private TextView typeText;
    private TextView statusText;
    private LinearLayout profileRow;
    private LinearLayout sliderRow;
    private View emptyView;
    private TextView fullEditorButton;
    private ControllerProfileEditor profileEditor;
    private ControllerProfileManager profileManager;
    private Runnable onSectionChanged;

    ModMenuControllerSection(Activity activity, FrameLayout host, int accent) {
        this.activity = activity;
        this.host = host;
        this.accent = accent;
    }

    /** Called when a change (a new active profile) should repaint the whole page. */
    void setOnSectionChanged(Runnable callback) {
        this.onSectionChanged = callback;
    }

    void build() {
        if (host == null) return;
        host.removeAllViews();
        View view = LayoutInflater.from(activity)
                .inflate(R.layout.vip_controller_section, host, false);
        host.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        illustration = view.findViewById(R.id.vip_controller_illustration);
        typeText = view.findViewById(R.id.vip_controller_type);
        statusText = view.findViewById(R.id.vip_controller_status);
        profileRow = view.findViewById(R.id.vip_controller_profiles);
        sliderRow = view.findViewById(R.id.vip_controller_sliders);
        emptyView = view.findViewById(R.id.vip_controller_empty);
        fullEditorButton = null;
        profileEditor = null;
        if (illustration != null) illustration.setAccentColor(accent);
        profileManager = new ControllerProfileManager(activity);
    }

    void refresh() {
        if (host == null || illustration == null || profileManager == null) return;
        InputDevice gamepad = firstGamepad();
        boolean hasPad = gamepad != null;
        if (emptyView != null) emptyView.setVisibility(hasPad ? View.GONE : View.VISIBLE);
        illustration.setVisibility(hasPad ? View.VISIBLE : View.GONE);

        ControllerType type = hasPad ? ControllerType.from(gamepad) : ControllerType.XBOX;
        if (type == null) type = ControllerType.XBOX;
        final ControllerType resolved = type;

        ModMenuGuard.run("controller.setType", () -> {
            illustration.setType(resolved);
            if (typeText != null) typeText.setText(resolved.getDisplayName());
        });
        final ControllerType sectionType = resolved;
        ModMenuGuard.run("controller.status", () -> {
            List<ControllerProfile> profiles = profileManager.getProfiles(sectionType);
            int activeSlot = profileManager.getActiveSlot(sectionType);
            String name = profiles.isEmpty() ? "Default"
                    : profiles.get(Math.min(activeSlot, profiles.size() - 1)).getName();
            if (statusText != null) {
                statusText.setText(activity.getString(
                        R.string.vip_controller_active_profile) + ": " + name);
            }
        });
        ModMenuGuard.run("controller.bindGlow", () -> {
            InbuiltModManager modManager = InbuiltModManager.getInstance(activity);
            illustration.clearConfirmed();
            if (modManager != null) {
                String region = illustration.regionIdForKey(modManager.getModMenuControllerBind());
                if (region != null) illustration.setRegionConfirmed(region, true);
            }
        });
        ModMenuGuard.run("controller.chips", () -> {
            List<ControllerProfile> profiles = profileManager.getProfiles(sectionType);
            rebuildProfileChips(sectionType, profiles, profileManager.getActiveSlot(sectionType));
        });
        ModMenuGuard.run("controller.sliders", () -> {
            List<ControllerProfile> profiles = profileManager.getProfiles(sectionType);
            rebuildSliders(sectionType, profiles, profileManager.getActiveSlot(sectionType));
        });
        ModMenuGuard.run("controller.fullEditorButton", () -> ensureFullEditorButton(sectionType));
    }

    /** The raw pad button is echoed onto the illustration, so the page shows the live input. */
    boolean handleHardwareKey(int keyCode) {
        if (illustration == null) return false;
        illustration.handleKeyEvent(keyCode, true);
        return false;
    }

    boolean handleHardwareMotion(android.view.MotionEvent event) {
        if (illustration == null) return false;
        illustration.handleMotionEvent(event);
        return false;
    }

    boolean isCapturingModifier() {
        return profileEditor != null && profileEditor.isCapturingModifier();
    }

    boolean handleCaptureKey(int keyCode) {
        return profileEditor != null && profileEditor.handleHardwareKey(keyCode);
    }

    private void ensureFullEditorButton(final ControllerType type) {
        if (sliderRow == null || fullEditorButton != null) return;
        TextView button = new TextView(activity);
        button.setText(R.string.controller_edit);
        button.setTextSize(13);
        button.setTextColor(0xFFFFFFFF);
        int pad = (int) (16 * density());
        button.setPadding(pad, pad / 2, pad, pad / 2);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(12 * density());
        bg.setColor(accent);
        button.setBackground(bg);
        DynamicAnim.applyPressScale(button);
        button.setOnClickListener(v -> {
            boolean ok = ModMenuGuard.run("openFullControllerEditor", () -> {
                if (profileEditor == null) {
                    profileEditor = new ControllerProfileEditor(activity, profileManager);
                }
                profileEditor.show(type, saved -> ModMenuGuard.run(
                        "refreshControllerSection(after edit)", this::refresh));
            });
            if (!ok) {
                try {
                    android.widget.Toast.makeText(activity, R.string.vip_controller_step_failed,
                            android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
        sliderRow.addView(button);
        fullEditorButton = button;
    }

    private void rebuildProfileChips(final ControllerType type, List<ControllerProfile> profiles,
                                     int activeSlot) {
        if (profileRow == null) return;
        profileRow.removeAllViews();
        for (int i = 0; i < profiles.size(); i++) {
            final int slot = i;
            TextView chip = new TextView(activity);
            chip.setText(profiles.get(i).getName());
            chip.setTextSize(12);
            chip.setSingleLine(true);
            int pad = (int) (14 * density());
            chip.setPadding(pad, pad / 2, pad, pad / 2);
            boolean selected = i == activeSlot;
            chip.setTextColor(selected ? 0xFFFFFFFF : UiTokens.TEXT_SECONDARY);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(12 * density());
            bg.setColor(selected ? accent : UiTokens.BG_SURFACE_HOVER);
            chip.setBackground(bg);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = (int) (8 * density());
            params.bottomMargin = (int) (8 * density());
            chip.setLayoutParams(params);
            DynamicAnim.applyPressScale(chip);
            chip.setOnClickListener(v -> {
                profileManager.setActiveSlot(type, slot);
                ControllerInputProcessor.setActiveProfile(type, profileManager.getActiveProfile(type));
                refresh();
                if (onSectionChanged != null) onSectionChanged.run();
            });
            profileRow.addView(chip);
        }
    }

    private void rebuildSliders(final ControllerType type, List<ControllerProfile> profiles,
                                int activeSlot) {
        if (sliderRow == null || profiles.isEmpty()) return;
        sliderRow.removeAllViews();
        final ControllerProfile profile = profiles.get(Math.min(activeSlot, profiles.size() - 1));
        addSlider(sliderRow, activity.getString(R.string.vip_dead_zone),
                (int) (profile.getRightDeadZone() * 100), 0, 60, value -> {
                    profile.setRightDeadZone(value / 100f);
                    profile.setLeftDeadZone(value / 100f);
                    persistProfile(type, activeSlot, profile);
                });
        addSlider(sliderRow, activity.getString(R.string.vip_sensitivity),
                (int) (profile.getRightStickSensitivity() * 100), 25, 300, value -> {
                    profile.setRightStickSensitivity(value / 100f);
                    profile.setLeftStickSensitivity(value / 100f);
                    persistProfile(type, activeSlot, profile);
                });
        addSlider(sliderRow, activity.getString(R.string.vip_rumble),
                profile.getRumbleStrength(), 0, 100, value -> {
                    profile.setRumbleStrength(value);
                    profile.setVibrationEnabled(value > 0);
                    persistProfile(type, activeSlot, profile);
                });
    }

    private void persistProfile(ControllerType type, int slot, ControllerProfile profile) {
        if (profileManager == null) return;
        // Save the edited object, not a fresh read: getProfiles deserialises new objects each call,
        // so saving that list wrote the unmodified profiles back and a slider change was applied to
        // the running processor but never persisted.
        List<ControllerProfile> profiles =
                ControllerProfileList.replaceAt(profileManager.getProfiles(type), slot, profile);
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
        header.setTextColor(UiTokens.TEXT_SECONDARY);
        header.setTextSize(12);
        row.addView(header);

        final TextView value = new TextView(activity);
        value.setText(String.valueOf(initial));
        value.setTextColor(accent);
        value.setTextSize(12);
        value.setTypeface(null, Typeface.BOLD);
        value.setGravity(Gravity.END);
        row.addView(value);

        SeekBar bar = new SeekBar(activity);
        bar.setMax(Math.max(1, max - min));
        bar.setProgress(Math.max(0, Math.min(max - min, initial - min)));
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
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

    private InputDevice firstGamepad() {
        android.hardware.input.InputManager im = (android.hardware.input.InputManager)
                activity.getSystemService(Activity.INPUT_SERVICE);
        if (im == null) return null;
        for (int id : im.getInputDeviceIds()) {
            InputDevice device = im.getInputDevice(id);
            if (ControllerConnectionMonitor.isGamepad(device)) return device;
        }
        return null;
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }
}
