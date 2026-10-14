package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.chimeramc.client.R;
import org.chimeramc.client.core.cosmetics.CosmeticsDiagnosticsReport;
import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;

/**
 * The in-app cosmetics diagnostics screen.
 *
 * <p>The build environment has no device and no adb, so the player must be able to read the state of
 * the native render path from inside the app. Logcat is not app-readable without {@code READ_LOGS},
 * so this screen reads the native feed through {@link CosmeticsDiagnosticsReport} and shows it as
 * plain text with Copy and Share buttons. It also carries the debug-cube toggle, which draws a bright
 * cube above the local player in game to prove the draw path.
 *
 * <p>Nothing here claims anything works in game: the report describes the native render path only,
 * and says so on the screen.
 */
final class CosmeticsDiagnosticsDialog {

    private static final int ACCENT = 0xFF0F5BE4;
    private static final int TEXT_PRIMARY = 0xFFF1F4F6;
    private static final int TEXT_SECONDARY = 0xFFA8B0B8;
    private static final int CARD = 0xFF202428;

    private CosmeticsDiagnosticsDialog() {
    }

    static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 10));

        TextView note = new TextView(activity);
        note.setText(R.string.cosmetics_diagnostics_note);
        note.setTextSize(12f);
        note.setTextColor(TEXT_SECONDARY);
        note.setPadding(0, 0, 0, dp(activity, 8));
        root.addView(note);

        // The report itself, in a monospace-ish scroll so long lines wrap predictably.
        final TextView reportView = new TextView(activity);
        reportView.setTextSize(11f);
        reportView.setTextColor(TEXT_PRIMARY);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        reportView.setBackground(rounded(activity, CARD, 10, 0xFF3A3F45));
        reportView.setText(CosmeticsDiagnosticsReport.readLive(activity));

        ScrollView scroller = new ScrollView(activity);
        scroller.addView(reportView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroller, scrollerParams);

        // The debug-cube toggle: a diagnostic instrument, off by default.
        final CheckBox debugCube = new CheckBox(activity);
        debugCube.setText(R.string.cosmetics_diagnostics_debug_cube);
        debugCube.setTextColor(TEXT_PRIMARY);
        debugCube.setTextSize(13f);
        debugCube.setPadding(0, dp(activity, 8), 0, 0);
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        debugCube.setChecked(manager.isCosmeticDebugCubeEnabled());
        debugCube.setOnCheckedChangeListener((button, checked) -> {
            manager.setCosmeticDebugCubeEnabled(checked);
            reportView.setText(CosmeticsDiagnosticsReport.readLive(activity));
        });
        root.addView(debugCube);

        TextView cubeNote = new TextView(activity);
        cubeNote.setText(R.string.cosmetics_diagnostics_debug_cube_note);
        cubeNote.setTextSize(11f);
        cubeNote.setTextColor(TEXT_SECONDARY);
        root.addView(cubeNote);

        // The third-person hint: in first person the game does not render the local player model,
        // so a hook inside the player-render path is never called for you and cosmetics anchored to
        // it are never drawn. This is the single most common "nothing shows" cause.
        TextView thirdPerson = new TextView(activity);
        thirdPerson.setText(R.string.third_person_hint);
        thirdPerson.setTextSize(11f);
        thirdPerson.setTextColor(TEXT_SECONDARY);
        thirdPerson.setPadding(0, dp(activity, 6), 0, 0);
        root.addView(thirdPerson);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(activity, 10), 0, 0);

        Button refresh = new Button(activity);
        refresh.setText(R.string.cosmetics_diagnostics_open);
        refresh.setOnClickListener(v -> reportView.setText(CosmeticsDiagnosticsReport.readLive(activity)));
        buttons.addView(refresh);

        Button copy = new Button(activity);
        copy.setText(R.string.cosmetics_diagnostics_copy);
        copy.setOnClickListener(v -> {
            String text = CosmeticsDiagnosticsReport.readLive(activity);
            reportView.setText(text);
            copyToClipboard(activity, text);
            Toast.makeText(activity, R.string.cosmetics_diagnostics_copied, Toast.LENGTH_SHORT).show();
        });
        buttons.addView(copy);

        Button share = new Button(activity);
        share.setText(R.string.cosmetics_diagnostics_share);
        share.setOnClickListener(v -> {
            String text = CosmeticsDiagnosticsReport.readLive(activity);
            reportView.setText(text);
            share(activity, text);
        });
        buttons.addView(share);

        root.addView(buttons);

        // A dedicated Copy/Share for the last crash report, so a device crash trace can be sent
        // without hunting through the full report.
        LinearLayout crashButtons = new LinearLayout(activity);
        crashButtons.setOrientation(LinearLayout.HORIZONTAL);
        crashButtons.setGravity(Gravity.END);

        Button copyCrash = new Button(activity);
        copyCrash.setText(R.string.crash_report_copy);
        copyCrash.setOnClickListener(v -> {
            String crash = readLastCrash(activity);
            if (crash.isEmpty()) {
                Toast.makeText(activity, R.string.crash_report_none, Toast.LENGTH_SHORT).show();
                return;
            }
            copyToClipboard(activity, crash);
            Toast.makeText(activity, R.string.crash_report_copied, Toast.LENGTH_SHORT).show();
        });
        crashButtons.addView(copyCrash);

        Button shareCrash = new Button(activity);
        shareCrash.setText(R.string.crash_report_share);
        shareCrash.setOnClickListener(v -> {
            String crash = readLastCrash(activity);
            if (crash.isEmpty()) {
                Toast.makeText(activity, R.string.crash_report_none, Toast.LENGTH_SHORT).show();
                return;
            }
            shareText(activity, crash, activity.getString(R.string.crash_report_share_title));
        });
        crashButtons.addView(shareCrash);
        root.addView(crashButtons);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.cosmetics_diagnostics_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static void copyToClipboard(Context context, String text) {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("GlowberryClient diagnostics", text));
            }
        } catch (Throwable ignored) {
            // A clipboard failure must not break the screen.
        }
    }

    private static void share(Activity activity, String text) {
        shareText(activity, text, activity.getString(R.string.cosmetics_diagnostics_share_title));
    }

    /** Shares arbitrary text through the system chooser; a missing target is not an error. */
    private static void shareText(Activity activity, String text, String chooserTitle) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, chooserTitle);
            intent.putExtra(Intent.EXTRA_TEXT, text);
            activity.startActivity(Intent.createChooser(intent, chooserTitle));
        } catch (Throwable ignored) {
            // No share target is not an error worth surfacing.
        }
    }

    /** Reads the most recent crash report text, or "" when there is none. Never throws. */
    private static String readLastCrash(Activity activity) {
        try {
            org.chimeramc.client.core.crash.CrashReportStore.Report report =
                    org.chimeramc.client.core.crash.CrashReportStore.readDir(
                            org.chimeramc.client.util.LauncherStorage.getCrashLogsDir(activity));
            return report.isPresent() ? report.text : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static android.graphics.drawable.GradientDrawable rounded(
            Context context, int fill, int radiusDp, int stroke) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (stroke != 0) drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
