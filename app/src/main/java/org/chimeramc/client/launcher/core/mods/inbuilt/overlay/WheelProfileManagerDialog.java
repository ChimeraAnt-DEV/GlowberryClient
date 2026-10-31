package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.replay.ReplayStyle;
import org.chimeramc.client.ui.animation.DynamicAnim;

import java.util.List;
import java.util.function.Consumer;

/**
 * The radial wheel's profile manager.
 *
 * <p>The spec asks for a built-in manager that can create, rename, reorder and delete wheel
 * profiles. {@link WheelProfileStore} owns every one of those operations but had no screen driving
 * them, so the store's create/rename/reorder/delete were unreachable: the wheel could only ever
 * show the built-in default set. This dialog is that screen.
 *
 * <p>It writes straight through the store and re-reads the list after each change, so the manager
 * and the wheel cannot disagree: the store is the one source of truth and this is only a view of
 * it. The dialog is opened from the mod menu, which is where the wheel itself is configured.
 */
final class WheelProfileManagerDialog {

    private WheelProfileManagerDialog() {
    }

    static void show(Activity activity, ModMenuTheme theme) {
        if (activity == null) return;
        final WheelProfileStore store = new WheelProfileStore(activity);
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 12));
        root.setBackground(ReplayStyle.roundedStroked(theme.panelSurface(),
                ModMenuPalette.BORDER_HAIRLINE, 18f, activity));

        TextView title = new TextView(activity);
        title.setText(R.string.wheel_manager_title);
        title.setTextColor(theme.textPrimary());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        // The scroll region is what makes the list survive more profiles than fit on screen.
        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 300));
        scrollParams.topMargin = dp(activity, 10);
        root.addView(scroll, scrollParams);

        // Rebuilding the whole list is correct here: the store reloads on every mutation, and the
        // list is short enough that a diff would be more code than it saves.
        final Runnable rebuild = new Runnable() {
            @Override
            public void run() {
                list.removeAllViews();
                List<WheelEntry.Profile> profiles = store.load();
                String rootId = store.getRootId();
                for (int i = 0; i < profiles.size(); i++) {
                    WheelEntry.Profile profile = profiles.get(i);
                    if (profile == null) continue;
                    addProfileRow(activity, theme, list, store, this, profile,
                            i == 0, i == profiles.size() - 1, profile.id.equals(rootId));
                }
                if (profiles.size() <= 1) {
                    TextView note = new TextView(activity);
                    note.setText(R.string.wheel_manager_last_note);
                    note.setTextColor(theme.textTertiary());
                    note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    p.topMargin = dp(activity, 8);
                    list.addView(note, p);
                }
            }
        };
        rebuild.run();

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(activity, 12);

        TextView add = sheetButton(activity, theme, R.string.wheel_manager_add, false);
        add.setOnClickListener(v -> promptForName(activity, theme, R.string.wheel_manager_add_title,
                "", name -> {
                    store.create(name);
                    rebuild.run();
                }));
        buttons.addView(add);

        TextView done = sheetButton(activity, theme, R.string.wheel_manager_done, true);
        done.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(done);
        root.addView(buttons, buttonParams);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ReplayStyle.dpInt(activity, 360f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
            window.setDimAmount(0.6f);
        }
        DynamicAnim.animateDialogShow(root);
        dialog.show();
    }

    private static void addProfileRow(Activity activity, ModMenuTheme theme, LinearLayout list,
                                      WheelProfileStore store, Runnable rebuild,
                                      WheelEntry.Profile profile, boolean first, boolean last,
                                      boolean isRoot) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 8), dp(activity, 8));
        row.setBackground(ReplayStyle.rounded(theme.disabledCardColor(), 12f, activity));

        LinearLayout identity = new LinearLayout(activity);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(activity);
        name.setText(profile.name == null ? profile.id : profile.name);
        name.setTextColor(theme.textPrimary());
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        identity.addView(name);

        TextView meta = new TextView(activity);
        // The root wheel is the one the bind opens; showing it is what makes "Set as root" legible.
        meta.setText(isRoot
                ? activity.getString(R.string.wheel_manager_root_label)
                : activity.getString(R.string.wheel_manager_entry_count,
                        profile.safeEntries().size()));
        meta.setTextColor(isRoot ? theme.accent() : theme.textTertiary());
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        identity.addView(meta);
        row.addView(identity, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Set as root: only meaningful for a non-root profile.
        if (!isRoot) {
            row.addView(iconButton(activity, theme, R.drawable.ic_check, v -> {
                store.setRootId(profile.id);
                rebuild.run();
            }));
        }
        row.addView(iconButton(activity, theme, R.drawable.ic_edit, v ->
                promptForName(activity, theme, R.string.wheel_manager_rename_title,
                        profile.name, renamed -> {
                            store.rename(profile.id, renamed);
                            rebuild.run();
                        })));
        if (!first) {
            row.addView(iconButton(activity, theme, R.drawable.ic_arrow_up, v -> {
                store.reorder(profile.id, -1);
                rebuild.run();
            }));
        }
        if (!last) {
            row.addView(iconButton(activity, theme, R.drawable.ic_arrow_down, v -> {
                store.reorder(profile.id, 1);
                rebuild.run();
            }));
        }
        if (!isRoot) {
            row.addView(iconButton(activity, theme, R.drawable.ic_delete, v -> {
                store.delete(profile.id, store.getRootId());
                rebuild.run();
            }));
        }

        DynamicAnim.applyPressScale(row);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(activity, 6);
        list.addView(row, params);
    }

    /** A compact icon button for a row action. */
    private static TextView iconButton(Activity activity, ModMenuTheme theme, int iconRes,
                                       View.OnClickListener click) {
        TextView button = new TextView(activity);
        button.setBackground(ReplayStyle.rippled(
                ReplayStyle.rounded(theme.panelSurface(), 10f, activity),
                theme.accentFill(60), activity));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
        button.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        button.setOnClickListener(click);
        DynamicAnim.applyPressScale(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(activity, 4));
        button.setLayoutParams(params);
        return button;
    }

    /** Asks for a name, then runs the action with it. An empty name is refused. */
    private static void promptForName(Activity activity, ModMenuTheme theme, int titleRes,
                                      String initial, Consumer<String> onName) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 12));
        root.setBackground(ReplayStyle.roundedStroked(theme.panelSurface(),
                ModMenuPalette.BORDER_HAIRLINE, 16f, activity));

        TextView title = new TextView(activity);
        title.setText(titleRes);
        title.setTextColor(theme.textPrimary());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(initial == null ? "" : initial);
        input.setHint(R.string.wheel_manager_name_hint);
        input.setTextColor(theme.textPrimary());
        input.setHintTextColor(theme.textTertiary());
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(activity, 10);
        root.addView(input, inputParams);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(activity, 12);

        TextView cancel = sheetButton(activity, theme, R.string.wheel_manager_cancel, false);
        cancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(cancel);

        TextView save = sheetButton(activity, theme, R.string.wheel_manager_save, true);
        save.setOnClickListener(v -> {
            String name = input.getText() == null ? "" : input.getText().toString().trim();
            if (name.isEmpty()) return;
            dialog.dismiss();
            onName.accept(name);
        });
        buttons.addView(save);
        root.addView(buttons, buttonParams);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ReplayStyle.dpInt(activity, 300f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
            window.setDimAmount(0.6f);
        }
        dialog.show();
    }

    private static TextView sheetButton(Activity activity, ModMenuTheme theme, int labelRes,
                                        boolean primary) {
        TextView button = new TextView(activity);
        button.setText(labelRes);
        button.setTextColor(primary ? theme.panelSurface() : theme.textPrimary());
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        button.setTypeface(null, Typeface.BOLD);
        button.setBackground(ReplayStyle.rippled(
                ReplayStyle.pill(primary ? theme.accent() : theme.disabledCardColor(), activity),
                theme.accentFill(60), activity));
        button.setPadding(dp(activity, 12), dp(activity, 7), dp(activity, 12), dp(activity, 7));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(activity, 6));
        button.setLayoutParams(params);
        DynamicAnim.applyPressScale(button);
        return button;
    }

    private static int dp(Activity activity, float value) {
        return ReplayStyle.dpInt(activity, value);
    }
}
