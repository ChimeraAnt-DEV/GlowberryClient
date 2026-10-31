package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
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
import org.chimeramc.client.launcher.ui.views.KeyboardIllustrationView;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.ui.components.UiTokens;
import org.chimeramc.client.util.MainThread;

import java.util.ArrayList;
import java.util.List;

/**
 * The Keyboard page of the Mod Menu (a Supporter page).
 *
 * <p>It was the VIP window's Keyboard tab. With one menu (U4) it is a page of that menu, so the
 * drawn board, the rebindable actions and the pointer settings are reachable from the launcher's
 * Mods screen too. The bind capture goes through {@link MenuBindRelay}, the one sink the game
 * activity feeds at the top of {@code dispatchKeyEvent}, so a captured press is never swallowed by
 * the preloader first.
 */
final class ModMenuKeyboardSection {

    private static final long KEY_CAPTURE_TIMEOUT_MS = 6000L;

    private final Activity activity;
    private final FrameLayout host;
    private final int accent;
    private final Handler handler = MainThread.handler();

    private KeyboardIllustrationView illustration;
    private LinearLayout keysRow;
    private LinearLayout settingsRow;

    ModMenuKeyboardSection(Activity activity, FrameLayout host, int accent) {
        this.activity = activity;
        this.host = host;
        this.accent = accent;
    }

    void build() {
        if (host == null) return;
        host.removeAllViews();
        View view = LayoutInflater.from(activity)
                .inflate(R.layout.vip_keyboard_section, host, false);
        host.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        illustration = view.findViewById(R.id.vip_keyboard_illustration);
        keysRow = view.findViewById(R.id.vip_keyboard_keys);
        settingsRow = view.findViewById(R.id.vip_keyboard_settings);
        if (illustration != null) illustration.setAccentColor(accent);
    }

    void refresh() {
        if (keysRow == null) return;
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        keysRow.removeAllViews();
        if (manager != null) {
            for (KbmAction action : actions()) {
                addKeybindRow(action, manager, action.getter.get(manager));
            }
        }
        rebuildSettings();
    }

    private void rebuildSettings() {
        if (settingsRow == null) return;
        settingsRow.removeAllViews();
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        if (manager != null) {
            addSlider(activity.getString(R.string.mod_config_cursor_sensitivity_percent),
                    manager.getCursorSensitivity(), 10, 300, manager::setCursorSensitivity);
        }
    }

    private void addKeybindRow(final KbmAction action, final InbuiltModManager manager, int code) {
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
        label.setTextColor(UiTokens.TEXT_PRIMARY);
        label.setTextSize(13);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(activity);
        value.setText(bindLabel(code));
        value.setTextColor(accent);
        value.setTextSize(12);
        value.setTypeface(null, Typeface.BOLD);

        row.addView(label);
        row.addView(value);
        DynamicAnim.applyPressScale(row);
        row.setOnClickListener(v -> openKeyCapture(action, value, manager));
        keysRow.addView(row);
    }

    private void openKeyCapture(final KbmAction action, final TextView value,
                                final InbuiltModManager manager) {
        value.setText(R.string.mod_menu_bind_press_key);
        value.setTextColor(UiTokens.SUCCESS);
        MenuBindRelay.set(keyCode -> {
            MenuBindRelay.clear();
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                action.setter.set(manager, keyCode);
                if (illustration != null) {
                    illustration.setHighlightCode(keyCode);
                    illustration.flashKey(keyCode);
                }
            }
            value.setText(bindLabel(action.getter.get(manager)));
            value.setTextColor(accent);
        });
        // A timeout releases the capture, so a stray press does not stay armed forever.
        handler.postDelayed(() -> {
            if (MenuBindRelay.isCapturing()) {
                MenuBindRelay.clear();
                value.setText(bindLabel(action.getter.get(manager)));
                value.setTextColor(accent);
            }
        }, KEY_CAPTURE_TIMEOUT_MS);
    }

    /**
     * Cancels the pending key-capture timeout (item 183).
     *
     * <p>The timeout releases a capture that was armed and then abandoned. Left pending past the
     * page switch it would run against a detached tree and reset a label the user can no longer see,
     * and it holds the section (and its activity) alive for its full duration.
     */
    void onHidden() {
        handler.removeCallbacksAndMessages(null);
        MenuBindRelay.clear();
    }

    private List<KbmAction> actions() {
        List<KbmAction> actions = new ArrayList<>();
        actions.add(new KbmAction("Open Mod Menu",
                InbuiltModManager::getModMenuKeybind, InbuiltModManager::setModMenuKeybind));
        actions.add(new KbmAction("Zoom",
                InbuiltModManager::getZoomKeybind, InbuiltModManager::setZoomKeybind));
        actions.add(new KbmAction("Auto sprint",
                InbuiltModManager::getAutoSprintKeybind, InbuiltModManager::setAutoSprintKeybind));
        return actions;
    }

    private void addSlider(String label, int initial, int min, int max, final ValueListener listener) {
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
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
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
        settingsRow.addView(row);
    }

    private String bindLabel(int keyCode) {
        if (keyCode == 0) return activity.getString(R.string.mod_menu_bind_none);
        String name = KeyEvent.keyCodeToString(keyCode);
        return name.startsWith("KEYCODE_") ? name.substring(8).replace('_', ' ') : name;
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }

    private interface ValueListener {
        void onValue(int value);
    }

    private interface IntGetter {
        int get(InbuiltModManager manager);
    }

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
}
