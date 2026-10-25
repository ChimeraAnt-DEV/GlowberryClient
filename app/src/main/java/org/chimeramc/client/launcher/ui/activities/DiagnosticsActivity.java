package org.chimeramc.client.launcher.ui.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.chimeramc.client.BuildConfig;
import org.chimeramc.client.R;
import org.chimeramc.client.core.minecraft.GamePackageManager;
import org.chimeramc.client.util.PlayStoreValidator;
import org.chimeramc.client.launcher.core.auth.AccountDiagnostics;
import org.chimeramc.client.core.auth.MsftAccountStore;
import org.chimeramc.client.core.cosmetics.AccountSkinCache;
import org.chimeramc.client.core.cosmetics.AccountSkinService;
import org.chimeramc.client.launcher.core.minecraft.AndroidLaunchProbe;
import org.chimeramc.client.launcher.core.minecraft.LastLaunchFailure;
import org.chimeramc.client.launcher.core.minecraft.LaunchProbe;
import org.chimeramc.client.launcher.core.minecraft.LaunchReadiness;
import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.core.versions.VersionManager;
import org.chimeramc.client.core.mods.ModLoadDiagnostics;
import org.chimeramc.client.preloader.PreloaderInput;
import org.chimeramc.client.preloader.PreloaderSignatureRulesManager;
import org.chimeramc.client.ui.activities.BaseActivity;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.ui.dialogs.CustomAlertDialog;
import org.chimeramc.client.util.PersonalizationManager;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app diagnostics (U16) plus the "Repair installation" action (G2).
 *
 * <p>The launch pipeline is hard to debug from logcat (no adb on a consumer device), so this screen
 * reads the same live state the pipeline uses — app/preloader versions, extraction version, signature
 * rule source+revision, native render hook, and the persisted mod-load failure store — and renders it
 * as shareable plain text. Every unknown reading renders as "unknown", never as a made-up number.
 *
 * <p>Every reading is I/O: stat and hash the instance's package, open the APK(s) to read their ABI
 * tables, read and parse the signature-rule file, read the account and mod stores. Reading them on
 * the UI thread froze the screen — and, worse, an earlier version asked
 * {@code GamePackageManager.getInstance()} for the launch ABI, whose `init` block runs the entire
 * native-library extraction. The screen therefore gathers a {@link Snapshot} on a background thread
 * and only paints it on the UI thread; nothing on the paint path touches the disk.
 */
public class DiagnosticsActivity extends BaseActivity {

    private VersionManager versionManager;
    private PersonalizationManager personalizationManager;
    private TextView repairStatus;

    /** Runs the read-only probes. A single thread, so two taps cannot overlap two gathers. */
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** The most recently gathered reading. Written on the UI thread only, so the paint path is safe. */
    private Snapshot snapshot;

    /**
     * Everything the screen renders, gathered off the UI thread.
     *
     * <p>A plain field bag rather than a builder: the screen is the only consumer, and the point of
     * the type is that the gather and the paint cannot be interleaved by accident.
     */
    private static final class Snapshot {
        GameVersion version;
        String launchAbi;
        LaunchReadiness.State readiness = LaunchReadiness.State.NO_VERSION_SELECTED;
        boolean extractionCurrent;
        int extractionExpected;
        String rulesSource;
        int rulesRevision = -1;
        /** Non-null when the rule DB has no entry covering the selected build. */
        String coverageFailure;
        boolean nativeLoaded;
        boolean skinSeam;
        /** Whether the preloader's player-render hook is live (read once, off the UI thread). */
        boolean renderHookLive;
        AccountDiagnostics.Snapshot account = new AccountDiagnostics.Snapshot();
        List<ModLoadDiagnostics.Record> modRecords = Collections.emptyList();
        /** The last launch that died inside the game activity, or null when none is recorded. */
        LastLaunchFailure.Report lastLaunchFailure;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        personalizationManager = new PersonalizationManager(this);
        versionManager = VersionManager.get(this);

        setActiveNavTab(R.id.nav_tab_settings);
        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));

        setupRepairButton();
        setupActionButtons();

        refresh();
    }

    private void setupRepairButton() {
        Button repair = findViewById(R.id.diagnostics_repair_button);
        if (repair == null) return;
        repair.setOnClickListener(v -> confirmRepair());
        DynamicAnim.applyPressScale(repair);
    }

    private void setupActionButtons() {
        Button copy = findViewById(R.id.diagnostics_copy_button);
        if (copy != null) {
            copy.setOnClickListener(v -> copyReport());
            DynamicAnim.applyPressScale(copy);
        }
        Button share = findViewById(R.id.diagnostics_share_button);
        if (share != null) {
            share.setOnClickListener(v -> shareReport());
            DynamicAnim.applyPressScale(share);
        }
        Button refresh = findViewById(R.id.diagnostics_refresh_button);
        if (refresh != null) {
            refresh.setOnClickListener(v -> refresh());
            DynamicAnim.applyPressScale(refresh);
        }
    }

    /**
     * Re-reads every probe and repaints.
     *
     * <p>The gather is disk and JNI work, so it runs on {@link #probeExecutor}; only the paint runs
     * here. Running it inline made the screen freeze for as long as the package hashes and APK scans
     * took, which on a cold cache is long enough for the system to call it an ANR.
     */
    private void refresh() {
        probeExecutor.execute(() -> {
            Snapshot gathered = gatherSnapshot();
            uiHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                snapshot = gathered;
                renderAll();
            });
        });
    }

    private void renderAll() {
        renderAppRows();
        renderReadinessRows();
        renderLibRows();
        renderRulesRows();
        renderNativeRows();
        renderAccountRows();
        renderModRows();
    }

    /** Reads every fact the screen shows. Never throws: a failed read degrades to "unknown". */
    private Snapshot gatherSnapshot() {
        Snapshot out = new Snapshot();
        out.version = selectedVersion();
        out.launchAbi = readLaunchAbi(out.version);
        try {
            LaunchReadiness.Facts facts = LaunchProbe.gather(
                    new AndroidLaunchProbe(this, out.version, null));
            out.readiness = LaunchReadiness.evaluate(facts);
        } catch (Throwable ignored) {
            out.readiness = LaunchReadiness.State.NO_VERSION_SELECTED;
        }
        out.extractionExpected = GamePackageManager.calculateNativeExtractionVersion();
        out.extractionCurrent = out.version != null
                && GamePackageManager.isExtractionCacheCurrent(this, out.version);
        out.rulesSource = PreloaderSignatureRulesManager.getRulesSource(this);
        out.rulesRevision = PreloaderSignatureRulesManager.getRulesRevision(this);
        if (out.version != null && out.version.versionCode != null
                && !out.version.versionCode.isEmpty()) {
            out.coverageFailure = PreloaderSignatureRulesManager
                    .describeCoverageFailure(this, out.version.versionCode);
        }
        try {
            out.nativeLoaded = PreloaderInput.readRenderDiagnostics() != null;
        } catch (Throwable ignored) {
            out.nativeLoaded = false;
        }
        try {
            out.renderHookLive = out.nativeLoaded && PreloaderInput.nativeIsPlayerRenderHookLive();
        } catch (Throwable ignored) {
            out.renderHookLive = false;
        }
        try {
            out.skinSeam = out.nativeLoaded && PreloaderInput.nativeHasCapturedSkin();
        } catch (Throwable ignored) {
            out.skinSeam = false;
        }
        out.account = readAccountSnapshot();
        try {
            List<ModLoadDiagnostics.Record> records = ModLoadDiagnostics.getAll(this);
            out.modRecords = records != null ? records : Collections.<ModLoadDiagnostics.Record>emptyList();
        } catch (Throwable ignored) {
            out.modRecords = Collections.emptyList();
        }
        // The last launch that died inside the game activity. Read here as well as announced on the
        // Launch tab: the dialog is consumed once, so without this the report would be unreadable
        // from the screen whose whole job is to explain a failed launch.
        try {
            out.lastLaunchFailure = LastLaunchFailure.read(this);
        } catch (Throwable ignored) {
            out.lastLaunchFailure = null;
        }
        return out;
    }

    private Snapshot current() {
        if (snapshot == null) {
            // The first gather is still in flight. Painting an empty snapshot would show "unknown"
            // for everything, which reads as a broken install; the rows are simply not there yet.
            return new Snapshot();
        }
        return snapshot;
    }

    /**
     * The named pre-launch state (U14/U16).
     *
     * <p>The same {@link LaunchReadiness} evaluation the Play button uses, so the diagnostics screen
     * and the launch preflight cannot disagree about why a launch would be refused. The state key is
     * shown verbatim (not a translated sentence) because it is what a bug report should quote.
     */
    private void renderReadinessRows() {
        LinearLayout rows = findViewById(R.id.diagnostics_readiness_rows);
        if (rows == null) return;
        rows.removeAllViews();

        // The same LaunchReadiness evaluation the Play button uses, so the diagnostics screen and the
        // launch preflight cannot disagree about why a launch would be refused. The state key is
        // shown verbatim (not a translated sentence) because it is what a bug report should quote.
        // The facts were gathered off the UI thread (see gatherSnapshot).
        LaunchReadiness.State state = current().readiness;
        addRow(rows, R.string.diagnostics_field_launch_state, LaunchReadiness.key(state));
        addRow(rows, R.string.diagnostics_field_launch_blocks,
                getString(LaunchReadiness.blocksLaunch(state)
                        ? R.string.diagnostics_launch_blocked : R.string.diagnostics_launch_proceeds));

        // The last launch that actually died, with its classified cause. The preflight above says
        // whether a launch *would* be refused; this says what happened to the last one that ran, so
        // a "it just closes" report has an answer on the same screen.
        LastLaunchFailure.Report failure = current().lastLaunchFailure;
        if (failure != null) {
            addRow(rows, R.string.diagnostics_field_last_launch_failure,
                    LastLaunchFailure.describe(failure));
            addRow(rows, R.string.diagnostics_field_last_launch_failure_time,
                    LastLaunchFailure.formatTimestamp(failure.recordedAtMs));
        }
    }

    private GameVersion selectedVersion() {
        try {
            return versionManager != null ? versionManager.getSelectedVersion() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The ABI the launcher would extract and load for [version].
     *
     * <p>Distinct from {@link Build#SUPPORTED_ABIS} above: that is what the device offers, this is
     * what the launch pipeline actually picks from the version's own APK. A launch that fails with
     * "32-bit instead of 64-bit" is only diagnosable when both are visible. Never throws — a missing
     * version or an unreadable package reads as "unknown" rather than a guessed ABI.
     *
     * <p>Read through {@link GamePackageManager#resolveLaunchAbi}, not
     * {@code GamePackageManager.getInstance(...).getLaunchAbi()}: the factory's `init` block runs the
     * whole native-library extraction and builds an AssetManager, so asking it this one question
     * performed the entire launch preparation — on the UI thread, from a read-only screen. That is
     * the same defect the launch preflight was fixed for.
     */
    private String readLaunchAbi(GameVersion version) {
        try {
            if (version == null) return "unknown (no version selected)";
            String abi = GamePackageManager.resolveLaunchAbi(this, version);
            return abi != null && !abi.isEmpty() ? abi : "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ---------------------------------------------------------------- sections

    private void renderAppRows() {
        LinearLayout rows = findViewById(R.id.diagnostics_app_rows);
        if (rows == null) return;
        rows.removeAllViews();

        Snapshot data = current();
        addRow(rows, R.string.diagnostics_field_app_version, BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ")");
        addRow(rows, R.string.diagnostics_field_android_version, Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        addRow(rows, R.string.diagnostics_field_device, Build.MANUFACTURER + " " + Build.MODEL);
        // The app's real target SDK, read from the installed package. The old value guessed from the
        // device API (>= 33 ? "35" : "28"), so it was wrong on every device that was not on exactly
        // API 33+ and did not match the manifest's targetSdkVersion either.
        addRow(rows, R.string.diagnostics_field_sdk_build,
                String.valueOf(getApplicationInfo().targetSdkVersion));
        addRow(rows, R.string.diagnostics_field_min_sdk,
                String.valueOf(getApplicationInfo().minSdkVersion));
        addRow(rows, R.string.diagnostics_field_abi, String.join(", ", Build.SUPPORTED_ABIS));
        addRow(rows, R.string.diagnostics_field_launcher_abi,
                data.launchAbi != null ? data.launchAbi : "unknown");

        GameVersion version = data.version;
        if (version != null) {
            addRow(rows, R.string.diagnostics_field_selected_version, version.directoryName
                    + " (" + (version.versionCode != null ? version.versionCode : "?") + ")");
        }
    }

    private void renderLibRows() {
        LinearLayout rows = findViewById(R.id.diagnostics_libs_rows);
        if (rows == null) return;
        rows.removeAllViews();

        Snapshot data = current();
        if (data.version == null) {
            addRow(rows, R.string.diagnostics_field_extraction_version, getString(R.string.diagnostics_no_version));
            return;
        }
        addRow(rows, R.string.diagnostics_field_extraction_version,
                data.extractionCurrent
                        ? getString(R.string.diagnostics_extraction_ok) + " (v" + data.extractionExpected + ")"
                        : getString(R.string.diagnostics_extraction_stale) + " (v" + data.extractionExpected + ")");
        addRow(rows, "Extractor", String.valueOf(GamePackageManager.extractorVersion()));
    }

    private void renderRulesRows() {
        LinearLayout rows = findViewById(R.id.diagnostics_rules_rows);
        if (rows == null) return;
        rows.removeAllViews();

        Snapshot data = current();
        String sourceLabel;
        if (data.rulesSource == null) {
            sourceLabel = getString(R.string.diagnostics_rules_unknown);
        } else if ("remote".equals(data.rulesSource)) {
            sourceLabel = getString(R.string.diagnostics_rules_remote);
        } else {
            sourceLabel = getString(R.string.diagnostics_rules_bundled);
        }
        addRow(rows, R.string.diagnostics_field_signature_rules_source, sourceLabel);

        addRow(rows, R.string.diagnostics_field_signature_rules_revision,
                data.rulesRevision < 0 ? "?" : String.valueOf(data.rulesRevision));

        // G5: fail-closed coverage visibility. When the rule DB has no entry for the selected
        // build, every byte-pattern hook is off; the report must say that explicitly.
        if (data.version != null && data.version.versionCode != null
                && !data.version.versionCode.isEmpty()) {
            addRow(rows, R.string.diagnostics_field_signature_rules_coverage,
                    data.coverageFailure == null ? getString(R.string.diagnostics_rules_covers_build)
                            : getString(R.string.diagnostics_rules_no_coverage));
        }
    }

    private void renderNativeRows() {
        LinearLayout rows = findViewById(R.id.diagnostics_native_rows);
        if (rows == null) return;
        rows.removeAllViews();

        Snapshot data = current();
        addRow(rows, R.string.diagnostics_field_cosmetics_hook,
                data.renderHookLive
                        ? getString(R.string.diagnostics_hook_live)
                        : getString(R.string.diagnostics_hook_inactive));
        addRow(rows, R.string.diagnostics_field_skin_seam,
                data.skinSeam ? getString(R.string.diagnostics_hook_live)
                              : getString(R.string.diagnostics_hook_inactive));
    }

    /**
     * The account and skin-sync section.
     *
     * <p>Reported as "auth is broken" and "my skin is not syncing", both of which had no observable
     * state before this row set: the screen could not say whether there was an account, whether its
     * token blob survived, or whether a skin was cached. The labels come from
     * {@link AccountDiagnostics}, which is pure, so the wording and thresholds are tested.
     */
    private void renderAccountRows() {
        LinearLayout rows = findViewById(R.id.diagnostics_account_rows);
        if (rows == null) return;
        rows.removeAllViews();

        AccountDiagnostics.Snapshot snapshot = current().account;
        if (!snapshot.signedIn) {
            addRow(rows, R.string.diagnostics_field_account_signed_in,
                    getString(R.string.diagnostics_no));
            addRow(rows, getString(R.string.diagnostics_field_skin_source),
                    AccountDiagnostics.describeSkinSource(false, snapshot.skinLookupEnabled,
                            snapshot.skinCached));
            return;
        }
        addRow(rows, R.string.diagnostics_field_account_signed_in,
                getString(R.string.diagnostics_yes));
        addRow(rows, R.string.diagnostics_field_account_xuid,
                snapshot.hasXuid ? getString(R.string.diagnostics_yes)
                        : getString(R.string.diagnostics_no));
        addRow(rows, R.string.diagnostics_field_account_token,
                AccountDiagnostics.describeToken(snapshot.hasTokenBlob, snapshot.tokenAgeMs));
        addRow(rows, R.string.diagnostics_field_skin_lookup,
                AccountDiagnostics.describeSkinLookup(true, snapshot.skinLookupEnabled,
                        snapshot.skinLookupAvailable));
        addRow(rows, R.string.diagnostics_field_skin_source,
                AccountDiagnostics.describeSkinSource(true, snapshot.skinLookupEnabled,
                        snapshot.skinCached));
    }

    /** Reads the real account and skin state. Never throws: a missing read is "not signed in". */
    private AccountDiagnostics.Snapshot readAccountSnapshot() {
        AccountDiagnostics.Snapshot snapshot = new AccountDiagnostics.Snapshot();
        try {
            MsftAccountStore.MsftAccount active = null;
            for (MsftAccountStore.MsftAccount account : MsftAccountStore.list(this)) {
                if (account != null && account.active) {
                    active = account;
                    break;
                }
            }
            if (active != null) {
                snapshot.signedIn(true);
                snapshot.hasXuid(active.xuid != null && !active.xuid.isEmpty());
                snapshot.hasTokenBlob(active.serializedAuthManager != null
                        && !active.serializedAuthManager.isEmpty());
                snapshot.tokenAgeMs(active.lastUpdated > 0
                        ? System.currentTimeMillis() - active.lastUpdated
                        : -1L);
            }
        } catch (Throwable ignored) {
        }
        try {
            snapshot.skinLookupEnabled(AccountSkinService.isEnabled(this));
            snapshot.skinLookupAvailable(AccountSkinService.canLookup(this));
            // Cached means cached for the account signed in now: an entry for a previous account is
            // refreshed rather than shown, so reporting it as cached would misdiagnose the report.
            AccountSkinCache.Entry entry = AccountSkinCache.read(getFilesDir());
            snapshot.skinCached(AccountSkinCache.isValidFor(entry,
                    AccountSkinService.activeXuid(this)));
        } catch (Throwable ignored) {
        }
        return snapshot;
    }

    private void renderModRows() {
        LinearLayout rows = findViewById(R.id.diagnostics_mods_rows);
        if (rows == null) return;
        rows.removeAllViews();

        java.util.List<ModLoadDiagnostics.Record> records = current().modRecords;
        if (records.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.diagnostics_no_mod_errors);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(12);
            empty.setPadding(0, 8, 0, 0);
            rows.addView(empty);
            return;
        }
        for (ModLoadDiagnostics.Record record : records) {
            addRow(rows, record.modName != null ? record.modName : (record.modId != null ? record.modId : "?"),
                    record.reason != null ? record.reason : "?");
        }
    }

    // ---------------------------------------------------------------- rows

    private void addRow(LinearLayout parent, int labelRes, String value) {
        addRow(parent, getString(labelRes), value);
    }

    private void addRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 8, 0, 8);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(getColor(R.color.text_secondary));
        labelView.setTextSize(12);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueView = new TextView(this);
        valueView.setText(value != null ? value : "?");
        valueView.setTextColor(getColor(R.color.on_surface));
        valueView.setTextSize(12);
        valueView.setGravity(android.view.Gravity.END);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(labelView);
        row.addView(valueView);
        parent.addView(row);
    }

    // ---------------------------------------------------------------- actions

    private void confirmRepair() {
        GameVersion version = selectedVersion();
        if (version == null) {
            Toast.makeText(this, R.string.diagnostics_no_version, Toast.LENGTH_SHORT).show();
            return;
        }
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.diagnostics_repair_confirm_title))
                .setMessage(getString(R.string.diagnostics_repair_confirm_message))
                .setPositiveButton(getString(R.string.repair), v -> runRepair(version))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void runRepair(GameVersion version) {
        Toast.makeText(this, R.string.diagnostics_repair_started, Toast.LENGTH_SHORT).show();
        // Invalidate the extraction marker + manifest and let the normal launch re-extract.
        GamePackageManager.invalidateExtractionCache(this, version);
        VersionManager.attemptRepairLibs(this, version);
    }

    private void copyReport() {
        String report = buildReport();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("GlowberryClient diagnostics", report));
            Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareReport() {
        String report = buildReport();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, report);
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_share_title));
        startActivity(Intent.createChooser(intent, getString(R.string.diagnostics_share_title)));
    }

    /**
     * Builds the shareable report from the already-gathered {@link Snapshot}.
     *
     * <p>It deliberately reads no device state of its own. It used to re-run every probe — including
     * the package hash and the APK ABI scan — on the UI thread from the Copy/Share handlers, so
     * copying the report froze the screen for exactly as long as the refresh had. Everything the
     * report needs is already in the snapshot.
     */
    private String buildReport() {
        Snapshot data = current();
        StringBuilder sb = new StringBuilder();
        sb.append("GlowberryClient Diagnostics\n");
        sb.append("===========================\n\n");
        sb.append("App version: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("Target SDK: ").append(getApplicationInfo().targetSdkVersion).append("\n");
        sb.append("ABIs: ").append(String.join(", ", Build.SUPPORTED_ABIS)).append("\n");
        sb.append("Launch ABI: ").append(data.launchAbi != null ? data.launchAbi : "unknown").append("\n");

        GameVersion version = data.version;
        if (version != null) {
            sb.append("Selected version: ").append(version.directoryName)
                    .append(" (").append(version.versionCode).append(")\n");
        } else {
            sb.append("Selected version: (none)\n");
        }

        // The launch state, so a report says why a launch is refused without the reporter having to
        // paraphrase a dialog. The key is stable and non-translatable, which is the point.
        sb.append("Launch state: ").append(LaunchReadiness.key(data.readiness)).append("\n");
        sb.append("Launch blocked: ").append(LaunchReadiness.blocksLaunch(data.readiness) ? "yes" : "no").append("\n");
        if (data.lastLaunchFailure != null) {
            sb.append("Last launch failure: ")
                    .append(LastLaunchFailure.describe(data.lastLaunchFailure)).append("\n");
            sb.append("Last launch failure at: ")
                    .append(LastLaunchFailure.formatTimestamp(data.lastLaunchFailure.recordedAtMs))
                    .append("\n");
        } else {
            sb.append("Last launch failure: (none recorded)\n");
        }

        sb.append("Extraction version: ")
                .append(data.extractionCurrent ? "up to date" : "stale")
                .append(" (expected ").append(data.extractionExpected).append(")\n");

        sb.append("Signature rules source: ")
                .append(data.rulesSource != null ? data.rulesSource : "unknown").append("\n");
        sb.append("Signature rules revision: ").append(data.rulesRevision).append("\n");
        if (version != null && version.versionCode != null && !version.versionCode.isEmpty()) {
            sb.append("Signature rule coverage: ")
                    .append(data.coverageFailure == null
                            ? "covered" : "NOT COVERED (byte-pattern hooks disabled)").append("\n");
        }

        AccountDiagnostics.Snapshot account = data.account;
        sb.append("\nAccount:\n");
        sb.append("  Signed in: ").append(account.signedIn ? "yes" : "no").append("\n");
        if (account.signedIn) {
            sb.append("  XUID present: ").append(account.hasXuid ? "yes" : "no").append("\n");
            sb.append("  Token cache: ")
                    .append(AccountDiagnostics.describeToken(account.hasTokenBlob, account.tokenAgeMs))
                    .append("\n");
            sb.append("  Skin lookup: ")
                    .append(AccountDiagnostics.describeSkinLookup(true, account.skinLookupEnabled,
                            account.skinLookupAvailable))
                    .append("\n");
            sb.append("  Skin source: ")
                    .append(AccountDiagnostics.describeSkinSource(true, account.skinLookupEnabled,
                            account.skinCached))
                    .append("\n");
        }

        sb.append("\nNative library loaded: ").append(data.nativeLoaded ? "yes" : "no").append("\n");
        if (data.nativeLoaded) {
            sb.append("Cosmetics render hook: ")
                    .append(data.renderHookLive ? "live" : "inactive").append("\n");
            sb.append("Skin capture seam: ")
                    .append(data.skinSeam ? "live" : "inactive").append("\n");
        }

        sb.append("\nMod load errors:\n");
        if (data.modRecords.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (ModLoadDiagnostics.Record r : data.modRecords) {
                String name = r.modName != null ? r.modName : (r.modId != null ? r.modId : "?");
                sb.append("  - ").append(name).append(": ").append(r.reason != null ? r.reason : "?").append("\n");
            }
        }

        sb.append("\n");
        sb.append(Locale.getDefault().toString()).append("\n");
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        // The gather touches the disk; leaving the thread alive after the screen is gone would let a
        // late read post into a destroyed Activity.
        probeExecutor.shutdownNow();
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}