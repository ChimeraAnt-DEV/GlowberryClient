package org.chimeramc.client.core.replay;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chimeramc.client.R;
import org.chimeramc.client.ui.animation.DynamicAnim;

import java.util.function.Consumer;

/**
 * The cinematic camera editor for a clip: preset shots, keyframes and the path save.
 *
 * <p>The spec asks for camera keyframes, smooth paths and preset shots. The maths is all in
 * {@link ReplayCameraPath}; this dialog only builds the shot list and hands the result back, so the
 * authoring surface can change without touching the sampling rules. A generated preset replaces the
 * current path (a preset is a whole shot), while "add keyframe" appends the camera's live pose so a
 * hand-built path is possible too.
 */
final class ReplayCinematicDialog {

    private ReplayCinematicDialog() {
    }

    static void show(Activity activity, ReplayStyle style, ReplayCameraPath path,
                     ReplayCamera camera, long durationMs, Consumer<ReplayCameraPath> onSaved) {
        if (path == null) path = new ReplayCameraPath();
        ReplayCameraPath working = path;

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 12));
        root.setBackground(ReplayStyle.roundedStroked(style.surfaceGlass(), style.hairline(),
                18f, activity));

        TextView title = new TextView(activity);
        title.setText(R.string.replay_cinematic_title);
        title.setTextColor(style.textPrimary());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView count = new TextView(activity);
        count.setTextColor(style.textSecondary());
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        count.setText(activity.getString(R.string.replay_cinematic_keyframes, working.size()));
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countParams.topMargin = dp(activity, 4);
        root.addView(count, countParams);

        final ReplayCameraPath[] holder = {working};
        Runnable refresh = () -> count.setText(activity.getString(
                R.string.replay_cinematic_keyframes, holder[0].size()));

        // Every preset is listed, one row each. A preset regenerates the whole shot, which is what
        // "a preset shot" means; the count updates so the user sees the path was replaced, not
        // appended to. The list is long (dozens of shots), so it scrolls inside a fixed height.
        ScrollView presetScroll = new ScrollView(activity);
        presetScroll.setVerticalScrollBarEnabled(true);
        LinearLayout presetList = new LinearLayout(activity);
        presetList.setOrientation(LinearLayout.VERTICAL);
        presetScroll.addView(presetList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (ReplayCameraPath.Preset preset : ReplayCameraPath.Preset.values()) {
            addPresetRow(activity, style, presetList, presetLabelRes(preset), preset, holder, camera,
                    durationMs, refresh);
        }
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 260));
        scrollParams.topMargin = dp(activity, 8);
        root.addView(presetScroll, scrollParams);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(activity, 12);

        TextView keyframe = sheetButton(activity, style, R.string.replay_cinematic_keyframe_add,
                false);
        keyframe.setOnClickListener(v -> {
            holder[0].capture(camera, durationMs);
            refresh.run();
        });
        buttons.addView(keyframe);

        TextView clear = sheetButton(activity, style, R.string.replay_cinematic_clear, false);
        clear.setOnClickListener(v -> {
            holder[0].clear();
            refresh.run();
        });
        buttons.addView(clear);

        TextView save = sheetButton(activity, style, R.string.replay_action_save, true);
        save.setOnClickListener(v -> {
            dialog.dismiss();
            if (onSaved != null) onSaved.accept(holder[0]);
        });
        buttons.addView(save);
        root.addView(buttons, buttonParams);

        TextView note = new TextView(activity);
        note.setText(R.string.replay_camera_note);
        note.setTextColor(style.textTertiary());
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(activity, 10);
        root.addView(note, noteParams);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ReplayStyle.dpInt(activity, 340f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
            window.setDimAmount(0.6f);
        }
        DynamicAnim.animateDialogShow(root);
        dialog.show();
    }

    private static void addPresetRow(Activity activity, ReplayStyle style, LinearLayout root,
                                     int labelRes, ReplayCameraPath.Preset preset,
                                     ReplayCameraPath[] holder, ReplayCamera camera,
                                     long durationMs, Runnable refresh) {
        TextView row = new TextView(activity);
        row.setText(labelRes);
        row.setTextColor(style.textPrimary());
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        row.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        row.setBackground(ReplayStyle.rippled(
                ReplayStyle.rounded(style.surfaceElevated(), 12f, activity),
                style.accentFill(60), activity));
        row.setOnClickListener(v -> {
            holder[0].generate(preset, camera, durationMs);
            refresh.run();
        });
        DynamicAnim.applyPressScale(row);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(activity, 6);
        root.addView(row, params);
    }

    /** The display name of a preset. Every preset must have a label; a test pins the coverage. */
    static int presetLabelRes(ReplayCameraPath.Preset preset) {
        switch (preset) {
            case ORBIT: return R.string.replay_cinematic_preset_orbit;
            case ORBIT_LOW: return R.string.replay_cinematic_preset_orbit_low;
            case ORBIT_HIGH: return R.string.replay_cinematic_preset_orbit_high;
            case ARC: return R.string.replay_cinematic_preset_arc;
            case DOLLY_ORBIT: return R.string.replay_cinematic_preset_dolly_orbit;
            case DOLLY_ZOOM: return R.string.replay_cinematic_preset_dolly;
            case PUSH_IN: return R.string.replay_cinematic_preset_push_in;
            case PULL_OUT: return R.string.replay_cinematic_preset_pull_out;
            case REVEAL: return R.string.replay_cinematic_preset_reveal;
            case TRUCK_LEFT: return R.string.replay_cinematic_preset_truck_left;
            case TRUCK_RIGHT: return R.string.replay_cinematic_preset_truck_right;
            case PEDESTAL_UP: return R.string.replay_cinematic_preset_pedestal_up;
            case SWEEP: return R.string.replay_cinematic_preset_sweep;
            case FLY_BY: return R.string.replay_cinematic_preset_fly_by;
            case TILT_UP: return R.string.replay_cinematic_preset_tilt_up;
            case TILT_DOWN: return R.string.replay_cinematic_preset_tilt_down;
            case TOPDOWN: return R.string.replay_cinematic_preset_topdown;
            case DRONE: return R.string.replay_cinematic_preset_drone;
            case CRANE: return R.string.replay_cinematic_preset_crane;
            case FLYOVER: return R.string.replay_cinematic_preset_flyover;
            case SPIRAL: return R.string.replay_cinematic_preset_spiral;
            case HELIX: return R.string.replay_cinematic_preset_helix;
            case CHASE: return R.string.replay_cinematic_preset_chase;
            case STATIC:
            default: return R.string.replay_cinematic_preset_static;
        }
    }

    private static TextView sheetButton(Activity activity, ReplayStyle style, int labelRes,
                                        boolean primary) {
        TextView button = new TextView(activity);
        button.setText(labelRes);
        button.setTextColor(primary ? style.canvas() : style.textPrimary());
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        button.setTypeface(null, Typeface.BOLD);
        button.setBackground(ReplayStyle.rippled(
                ReplayStyle.pill(primary ? style.accent() : style.surfaceElevated(), activity),
                style.accentFill(60), activity));
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
