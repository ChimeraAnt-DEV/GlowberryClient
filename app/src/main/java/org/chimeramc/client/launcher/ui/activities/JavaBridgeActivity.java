package org.chimeramc.client.ui.activities;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.chimeramc.client.R;
import org.chimeramc.client.core.antegg.AntEggLoader;
import org.chimeramc.client.core.antegg.AntEggManifest;
import org.chimeramc.client.core.javabridge.CfrDecompiler;
import org.chimeramc.client.core.javabridge.JarInspector;
import org.chimeramc.client.core.javabridge.JavaApiMapping;
import org.chimeramc.client.core.javabridge.JavaModManifest;
import org.chimeramc.client.core.javabridge.JavaPorter;
import org.chimeramc.client.core.javabridge.LlmConsent;
import org.chimeramc.client.core.javabridge.LlmSettings;
import org.chimeramc.client.core.javabridge.OpenAiCompatibleLlmClient;
import org.chimeramc.client.core.javabridge.PortabilityReport;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.ui.dialogs.CustomAlertDialog;
import org.chimeramc.client.util.PersonalizationManager;
import org.chimeramc.client.launcher.core.minecraft.UserFacingError;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The JavaBridge screen: import a Java Edition {@code .jar}, see how it scores, and port it.
 *
 * <p>The screen is staged so the user sees the local assessment before anything is sent: the
 * inspection, the portability report and the cheat check all run from the jar alone, and the LLM is
 * only reached after the user presses Port and accepts the consent dialog. A mod that is refused or
 * unsupported never gets as far as the network.
 */
public class JavaBridgeActivity extends BaseActivity {

    /** A {@code content://} URI to open straight away, handed over by the Mod Import screen. */
    public static final String EXTRA_JAR_URI = "extra_jar_uri";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<Intent> pickJarLauncher;

    private View reportCard;
    private View loadingCard;
    private View emptyState;
    private TextView modName;
    private TextView modMeta;
    private TextView score;
    private TextView summary;
    private LinearLayout keptContainer;
    private LinearLayout partialContainer;
    private LinearLayout droppedContainer;
    private TextView partialHeader;
    private TextView droppedHeader;
    private TextView mappedApis;
    private Button portButton;

    private File stagedJar;
    private ObjectAnimator shimmer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_javabridge);
        setActiveNavTab(R.id.nav_tab_mods);

        bindViews();
        DynamicAnim.applyPressScale(findViewById(R.id.javabridge_import_button));
        DynamicAnim.applyPressScale(findViewById(R.id.javabridge_setup_button));
        DynamicAnim.applyPressScale(portButton);

        findViewById(R.id.javabridge_import_button).setOnClickListener(v -> pickJar());
        findViewById(R.id.javabridge_setup_button).setOnClickListener(v -> showSetupDialog());
        portButton.setOnClickListener(v -> confirmAndPort());

        pickJarLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null
                            || result.getData().getData() == null) {
                        return;
                    }
                    onJarPicked(result.getData().getData());
                });

        String incoming = getIntent() != null ? getIntent().getStringExtra(EXTRA_JAR_URI) : null;
        if (incoming != null) {
            onJarPicked(Uri.parse(incoming));
        }
    }

    private void bindViews() {
        reportCard = findViewById(R.id.javabridge_report_card);
        loadingCard = findViewById(R.id.javabridge_loading);
        emptyState = findViewById(R.id.javabridge_empty_state);
        modName = findViewById(R.id.javabridge_mod_name);
        modMeta = findViewById(R.id.javabridge_mod_meta);
        score = findViewById(R.id.javabridge_score);
        summary = findViewById(R.id.javabridge_summary);
        keptContainer = findViewById(R.id.javabridge_kept_container);
        partialContainer = findViewById(R.id.javabridge_partial_container);
        droppedContainer = findViewById(R.id.javabridge_dropped_container);
        partialHeader = findViewById(R.id.javabridge_partial_header);
        droppedHeader = findViewById(R.id.javabridge_dropped_header);
        mappedApis = findViewById(R.id.javabridge_mapped_apis);
        portButton = findViewById(R.id.javabridge_port_button);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        stopShimmer();
    }

    // --- import -------------------------------------------------------------------------------

    private void pickJar() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // A .jar is an ordinary ZIP to most pickers; the extension is checked after picking rather
        // than trusting the MIME type, which OEM pickers often report as octet-stream.
        pickJarLauncher.launch(intent);
    }

    private void onJarPicked(Uri uri) {
        String name = displayName(uri);
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            Toast.makeText(this, R.string.javabridge_not_a_jar, Toast.LENGTH_LONG).show();
            return;
        }
        showLoading(true);
        executor.execute(() -> {
            try {
                File staged = new File(getCacheDir(), "javabridge_import.jar");
                copyToFile(uri, staged);
                JavaPorter porter = new JavaPorter(new CfrDecompiler(),
                        new OpenAiCompatibleLlmClient(this));
                JavaPorter.Assessment result = porter.assess(staged);
                main.post(() -> {
                    if (isFinishing()) return;
                    stagedJar = staged;
                    showAssessment(result);
                });
            } catch (IOException e) {
                main.post(() -> {
                    if (isFinishing()) return;
                    showLoading(false);
                    Toast.makeText(this, getString(R.string.javabridge_port_failed,
                            UserFacingError.reason(this, e)), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // --- report -------------------------------------------------------------------------------

    private void showAssessment(JavaPorter.Assessment result) {
        showLoading(false);
        PortabilityReport report = result.report;

        if (result.isRefused()) {
            emptyState.setVisibility(View.VISIBLE);
            reportCard.setVisibility(View.GONE);
            new CustomAlertDialog(this)
                    .setTitleText(getString(R.string.javabridge_refused_title))
                    .setMessage(getString(R.string.javabridge_refused_message,
                            result.cheatVerdict.pattern))
                    .setPositiveButton(getString(R.string.javabridge_close), v -> {})
                    .show();
            return;
        }

        emptyState.setVisibility(View.GONE);
        reportCard.setVisibility(View.VISIBLE);

        JavaModManifest manifest = result.inspection.manifest;
        modName.setText(manifest != null && !manifest.name.isEmpty()
                ? manifest.name : getString(R.string.javabridge_title));
        modMeta.setText(buildMetaLine(manifest, result.inspection));

        score.setText(getString(R.string.javabridge_score_label, report.scoreLabel));
        summary.setText(report.summary);

        renderItems(keptContainer, report.keptItems(), R.string.javabridge_kept_none);
        renderItems(partialContainer, report.partialItems(), R.string.javabridge_partial_none);
        renderItems(droppedContainer, report.droppedItems(), R.string.javabridge_dropped_none);
        partialHeader.setVisibility(partialContainer.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        droppedHeader.setVisibility(droppedContainer.getChildCount() == 0 ? View.GONE : View.VISIBLE);

        mappedApis.setText(buildMappingText(report.mappedApis));

        portButton.setEnabled(report.canPort());
        portButton.setAlpha(report.canPort() ? 1f : 0.5f);
    }

    private String buildMetaLine(JavaModManifest manifest, JarInspector.Inspection inspection) {
        if (manifest == null) {
            return getString(R.string.javabridge_no_jar_selected);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(manifest.loader);
        if (!manifest.version.isEmpty()) sb.append(" \u00b7 ").append(manifest.version);
        if (!manifest.author.isEmpty()) {
            sb.append(" \u00b7 ").append(getString(R.string.mod_author_byline, manifest.author));
        }
        sb.append(" \u00b7 ").append(inspection.ownClassCount).append(" classes");
        return sb.toString();
    }

    /**
     * The provenance line for the review dialog: what the port was generated from.
     *
     * <p>A ported package always carries {@code aiPorted} plus the origin mod and author, so this
     * cannot present a rewrite as an original. It falls back to a bare "AI-ported" label when the
     * source manifest had no name to record.
     */
    private String buildProvenanceLine(AntEggManifest manifest) {
        if (manifest == null) {
            return getString(R.string.javabridge_ai_ported_badge);
        }
        String origin = manifest.originMod == null ? "" : manifest.originMod;
        if (manifest.originAuthor != null && !manifest.originAuthor.isEmpty()) {
            origin = origin.isEmpty() ? manifest.originAuthor
                    : origin + " \u00b7 " + manifest.originAuthor;
        }
        return origin.isEmpty()
                ? getString(R.string.javabridge_ai_ported_badge)
                : getString(R.string.javabridge_ai_ported_badge_desc, origin);
    }

    private void renderItems(LinearLayout container, List<PortabilityReport.Item> items,
                             int emptyRes) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (PortabilityReport.Item item : items) {
            View row = inflater.inflate(R.layout.item_javabridge_report_line, container, false);
            TextView feature = row.findViewById(R.id.javabridge_item_feature);
            TextView detail = row.findViewById(R.id.javabridge_item_detail);
            View dot = row.findViewById(R.id.javabridge_item_dot);
            feature.setText(item.feature);
            detail.setText(item.detail);
            tintDot(dot, item.disposition);
            container.addView(row);
        }
        if (container.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText(emptyRes);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(11f);
            container.addView(empty);
        }
    }

    private void tintDot(View dot, String tier) {
        int color = PortabilityReport.KEEP.equals(tier) ? R.color.primary
                : PortabilityReport.PARTIAL.equals(tier) ? R.color.secondary
                : R.color.text_secondary;
        dot.getBackground().mutate().setTint(getColor(color));
    }

    private String buildMappingText(List<JavaApiMapping.Entry> entries) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(entries.size(), 12);
        for (int i = 0; i < limit; i++) {
            JavaApiMapping.Entry entry = entries.get(i);
            if (i > 0) sb.append('\n');
            sb.append(entry.source).append(" \u2192 ").append(entry.target);
        }
        if (entries.size() > limit) sb.append("\n\u2026");
        return sb.toString();
    }

    // --- port ---------------------------------------------------------------------------------

    private void confirmAndPort() {
        if (stagedJar == null) {
            Toast.makeText(this, R.string.javabridge_no_jar_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!LlmSettings.isConfigured(this)) {
            Toast.makeText(this, R.string.javabridge_no_llm_configured, Toast.LENGTH_LONG).show();
            showSetupDialog();
            return;
        }
        if (!LlmSettings.hasConsent(this)) {
            showConsentDialog();
            return;
        }
        runPort();
    }

    private void showConsentDialog() {
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.javabridge_consent_title))
                .setMessage(LlmConsent.CONSENT_TEXT)
                .setPositiveButton(getString(R.string.javabridge_port_button), v -> {
                    LlmSettings.grantConsent(this);
                    runPort();
                })
                .setNegativeButton(getString(R.string.javabridge_cancel), v -> {})
                .show();
    }

    private void runPort() {
        final File jar = stagedJar;
        showLoading(true, R.string.javabridge_porting);
        reportCard.setVisibility(View.GONE);
        executor.execute(() -> {
            JavaPorter porter = new JavaPorter(new CfrDecompiler(),
                    new OpenAiCompatibleLlmClient(this));
            File outputDir = AntEggLoader.sandboxRoot(this);
            if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
                main.post(() -> {
                    if (isFinishing()) return;
                    showLoading(false);
                    reportCard.setVisibility(View.VISIBLE);
                    Toast.makeText(this, R.string.javabridge_port_failed, Toast.LENGTH_LONG).show();
                });
                return;
            }
            // The loading overlay is only ever cleared from the post below, so an escaping throw
            // (the CFR decompiler is memory-hungry and a large jar can exhaust the heap) left the
            // spinner up forever with the Port button unusable. The failure is converted to a
            // result the same post can render.
            final JavaPorter.Result result = portSafely(porter, jar, outputDir);
            main.post(() -> {
                if (isFinishing()) return;
                showLoading(false);
                reportCard.setVisibility(View.VISIBLE);
                if (!result.success) {
                    Toast.makeText(this,
                            getString(R.string.javabridge_port_failed,
                                    result.error == null ? "" : result.error),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                showReviewDialog(result);
            });
        });
    }

    /**
     * Runs the port, converting an escaping throw into a failure result.
     *
     * <p>The loading overlay is only cleared from the post that renders the result, so a throw
     * escaping this call (the CFR decompiler is memory-hungry and a large jar can exhaust the
     * heap) left the spinner up forever with the Port button unusable. The failure is named and
     * returned as a result so the same post can render it.
     */
    private JavaPorter.Result portSafely(JavaPorter porter, File jar, File outputDir) {
        try {
            return porter.port(jar, outputDir);
        } catch (Throwable t) {
            String detail = t.getMessage();
            if (detail == null || detail.trim().isEmpty()) {
                detail = t.getClass().getSimpleName();
            }
            return JavaPorter.Result.failure(null, detail);
        }
    }

    /**
     * Shows the generated code and offers to enable it.
     *
     * <p>The mod is written to the sandbox but deliberately <em>not</em> enabled here: the user is
     * asked to read what the model produced first, and "Review later" leaves it disabled with a
     * toast pointing at the Mods tab.
     */
    private void showReviewDialog(JavaPorter.Result result) {
        TextView code = new TextView(this);
        code.setText(result.generatedSource);
        code.setTextSize(11f);
        code.setTypeface(android.graphics.Typeface.MONOSPACE);
        code.setTextColor(getColor(R.color.on_surface));
        code.setPadding(dp(24), dp(24), dp(24), dp(24));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(code);

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        // Provenance first: a ported mod is a rewrite, and the origin is recorded in the manifest
        // precisely so the user sees what it was generated from before enabling it.
        TextView provenance = new TextView(this);
        provenance.setText(buildProvenanceLine(result.manifest));
        provenance.setTextSize(12f);
        provenance.setTextColor(getColor(R.color.text_secondary));
        provenance.setPadding(dp(24), 0, dp(24), dp(6));
        wrapper.addView(provenance);

        // Item G12: every ported package is pinned at generation time, so the review dialog can
        // show the verified checksum as evidence of what will be installed. The pin was computed
        // over the entry bytes by the porter and stored in the manifest — display it, never
        // recompute from a path string.
        TextView pin = new TextView(this);
        if (result.manifest.sha256.isEmpty()) {
            pin.setText(R.string.javabridge_no_pin);
            pin.setTextColor(getColor(R.color.ui_warning));
        } else {
            pin.setText(getString(R.string.javabridge_verified_pin, result.manifest.sha256));
            pin.setTextColor(getColor(R.color.ui_success));
        }
        pin.setTextSize(12f);
        pin.setPadding(dp(24), 0, dp(24), dp(6));
        wrapper.addView(pin);
        TextView note = new TextView(this);
        note.setText(R.string.javabridge_review_note);
        note.setTextSize(12f);
        note.setPadding(dp(24), 0, dp(24), 0);
        wrapper.addView(note);
        wrapper.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.javabridge_review_title))
                .setCustomView(wrapper)
                .setPositiveButton(getString(R.string.javabridge_review_enable), v -> enablePortedMod(result.packageFile))
                .setNegativeButton(getString(R.string.javabridge_review_later), v -> Toast.makeText(this, R.string.javabridge_review_disabled_note,
                        Toast.LENGTH_LONG).show())
                .show();
    }

    private void enablePortedMod(File packageFile) {
        executor.execute(() -> {
            // The button's only feedback is the toast below, so an escaping throw left the press
            // with no visible result at all. The failure is named instead.
            final boolean loaded;
            try {
                loaded = AntEggLoader.loadMod(this, packageFile).success;
            } catch (Throwable t) {
                main.post(() -> {
                    if (isFinishing()) return;
                    Toast.makeText(this, R.string.javabridge_load_failed, Toast.LENGTH_LONG).show();
                });
                return;
            }
            final boolean success = loaded;
            main.post(() -> {
                if (isFinishing()) return;
                Toast.makeText(this, success ? R.string.javabridge_port_success
                        : R.string.javabridge_load_failed, Toast.LENGTH_LONG).show();
            });
        });
    }

    // --- setup --------------------------------------------------------------------------------

    private void showSetupDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));

        EditText endpoint = new EditText(this);
        endpoint.setHint(R.string.javabridge_setup_endpoint);
        endpoint.setText(LlmSettings.endpoint(this));
        form.addView(endpoint);

        EditText key = new EditText(this);
        key.setHint(R.string.javabridge_setup_api_key);
        key.setText(LlmSettings.apiKey(this));
        form.addView(key);

        EditText model = new EditText(this);
        model.setHint(R.string.javabridge_setup_model);
        model.setText(LlmSettings.model(this));
        form.addView(model);

        TextView consentState = new TextView(this);
        consentState.setTextSize(12f);
        consentState.setPadding(0, dp(8), 0, 0);
        consentState.setText(LlmSettings.hasConsent(this)
                ? R.string.javabridge_setup_consent_given : R.string.javabridge_setup_no_consent);
        form.addView(consentState);

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.javabridge_setup_title))
                .setCustomView(form)
                .setPositiveButton(getString(R.string.javabridge_setup_save), v -> {
                    LlmSettings.setEndpoint(this, endpoint.getText().toString());
                    LlmSettings.setApiKey(this, key.getText().toString());
                    LlmSettings.setModel(this, model.getText().toString());
                })
                .setNeutralButton(getString(R.string.javabridge_setup_clear), v -> LlmSettings.revokeConsent(this))
                .show();
    }

    // --- helpers ------------------------------------------------------------------------------

    private void showLoading(boolean loading) {
        showLoading(loading, R.string.javabridge_inspecting);
    }

    private void showLoading(boolean loading, int labelRes) {
        loadingCard.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            TextView label = loadingCard.findViewById(R.id.javabridge_loading_label);
            if (label != null) label.setText(labelRes);
            emptyState.setVisibility(View.GONE);
            startShimmer();
        } else {
            stopShimmer();
        }
    }

    /** A slow alpha pulse on the skeleton bars; gated by the personalization animations flag. */
    private void startShimmer() {
        PersonalizationManager pm = new PersonalizationManager(this);
        if (!pm.isShowAnimations() || shimmer != null) return;
        shimmer = ObjectAnimator.ofFloat(loadingCard, View.ALPHA, 0.55f, 1f);
        shimmer.setDuration(900);
        shimmer.setRepeatCount(ValueAnimator.INFINITE);
        shimmer.setRepeatMode(ValueAnimator.REVERSE);
        shimmer.setInterpolator(new LinearInterpolator());
        shimmer.start();
    }

    private void stopShimmer() {
        if (shimmer != null) {
            shimmer.cancel();
            shimmer = null;
            loadingCard.setAlpha(1f);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && !cursor.isNull(index)) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // Fall through to the path-based name.
        }
        String path = uri.getLastPathSegment();
        return path == null ? null : new File(path).getName();
    }

    private void copyToFile(Uri uri, File destination) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(destination)) {
            if (in == null) throw new IOException("cannot open the selected file");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }
}
