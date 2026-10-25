package org.chimeramc.client.ui.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.mods.config.ModConfigManager;
import org.chimeramc.client.ui.adapter.ModConfigAdapter;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.ui.dialogs.CustomAlertDialog;
import org.chimeramc.client.util.PersonalizationManager;
import org.chimeramc.client.launcher.core.minecraft.UserFacingError;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class ModConfigActivity extends BaseActivity {
    public static final String EXTRA_MOD_NAME = "mod_name";
    public static final String EXTRA_MOD_ROOT = "mod_root";

    private final ModConfigManager configManager = new ModConfigManager();
    //
    // One worker for this screen instead of a fresh Thread per action: a raw Thread posted its
    // result with runOnUiThread even after the Activity was destroyed, so a config that finished
    // loading on the way out touched a dead view tree (or showed a dialog on a dead window).
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<ModConfigManager.ConfigFile> configFiles = new ArrayList<>();
    private TextView titleText;
    private Spinner configSpinner;
    private RecyclerView recyclerView;
    private ProgressBar loadingProgress;
    private TextView emptyText;
    private ModConfigAdapter adapter;
    private ModConfigManager.ConfigFile currentFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mod_config);

        View root = findViewById(R.id.mod_config_root);
        if (root != null) {
            DynamicAnim.applyPressScaleRecursively(root);
        }

        titleText = findViewById(R.id.mod_config_title);
        configSpinner = findViewById(R.id.mod_config_file_spinner);
        recyclerView = findViewById(R.id.mod_config_recycler);
        loadingProgress = findViewById(R.id.mod_config_loading);
        emptyText = findViewById(R.id.mod_config_empty);
        Button saveButton = findViewById(R.id.mod_config_save_button);
        PersonalizationManager pm = new PersonalizationManager(this);
        pm.applySolidAccentText(titleText, pm.getAccentColor());
        pm.applyAccentToView(saveButton, this);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        saveButton.setOnClickListener(v -> saveCurrentConfig());

        String modName = getIntent().getStringExtra(EXTRA_MOD_NAME);
        String modRootPath = getIntent().getStringExtra(EXTRA_MOD_ROOT);
        titleText.setText(getString(R.string.mod_config_title_format, modName == null ? "" : modName));

        if (modRootPath == null || modRootPath.trim().isEmpty()) {
            showEmpty(getString(R.string.mod_config_not_found));
            return;
        }

        loadConfigFiles(new File(modRootPath));
    }

    private void loadConfigFiles(File modRoot) {
        loadingProgress.setVisibility(View.VISIBLE);
        runOnWorker(() -> {
            List<ModConfigManager.ConfigFile> files = configManager.scanConfigFiles(modRoot);
            runOnUiThreadIfAlive(() -> {
                loadingProgress.setVisibility(View.GONE);
                configFiles.clear();
                configFiles.addAll(files);
                if (configFiles.isEmpty()) {
                    showEmpty(getString(R.string.mod_config_not_found));
                    return;
                }
                setupSpinner();
                loadConfig(configFiles.get(0));
            });
        });
    }

    private void setupSpinner() {
        List<String> names = new ArrayList<>();
        for (ModConfigManager.ConfigFile file : configFiles) {
            names.add(file.getDisplayName());
        }

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, names);
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        configSpinner.setAdapter(spinnerAdapter);
        configSpinner.setVisibility(configFiles.size() > 1 ? View.VISIBLE : View.GONE);
        configSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < configFiles.size() && configFiles.get(position) != currentFile) {
                    loadConfig(configFiles.get(position));
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void loadConfig(ModConfigManager.ConfigFile file) {
        currentFile = file;
        loadingProgress.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        runOnWorker(() -> {
            try {
                ModConfigManager.LoadedConfig loadedConfig = configManager.load(file);
                runOnUiThreadIfAlive(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    adapter = new ModConfigAdapter(loadedConfig.getValue(), loadedConfig.getSchema());
                    recyclerView.setAdapter(adapter);
                    recyclerView.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                runOnUiThreadIfAlive(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    // Name the file as well as the cause. The screen can be showing several config
                    // files, and a bare getMessage() on a malformed one is usually null or
                    // "Expected BEGIN_OBJECT but was STRING at line 1 column 1" - which names
                    // neither the file nor the next step.
                    showEmpty(getString(R.string.mod_config_load_failed, file.getDisplayName())
                            + "\n\n" + UserFacingError.describe(ModConfigActivity.this, e));
                });
            }
        });
    }

    private void saveCurrentConfig() {
        if (adapter == null || currentFile == null) {
            return;
        }

        String validationError = adapter.validate();
        if (validationError != null) {
            new CustomAlertDialog(this)
                    .setTitleText(getString(R.string.mod_config_validation_failed))
                    .setMessage(validationError)
                    .setPositiveButton(getString(R.string.dialog_positive_ok), null)
                    .show();
            return;
        }

        loadingProgress.setVisibility(View.VISIBLE);
        runOnWorker(() -> {
            try {
                configManager.save(currentFile, adapter.getRoot());
                runOnUiThreadIfAlive(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.mod_config_saved, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThreadIfAlive(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    new CustomAlertDialog(this)
                            .setTitleText(getString(R.string.mod_config_save_failed))
                            .setMessage(UserFacingError.describe(ModConfigActivity.this, e))
                            .setPositiveButton(getString(R.string.dialog_positive_ok), null)
                            .show();
                });
            }
        });
    }

    /**
     * Runs {@code task} on this screen's worker, or reports nothing when it is already torn down.
     *
     * <p>The old raw {@code Thread} per action had no owner, so a scan that was still running when
     * the screen closed posted its result to a destroyed Activity anyway.
     */
    private void runOnWorker(Runnable task) {
        if (worker.isShutdown()) return;
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // The screen was destroyed between the check and the submit; nothing to do.
        }
    }

    /**
     * Posts to the UI thread only while the screen is still alive.
     *
     * <p>Guarding here rather than at each call site is what stops a late result from touching a
     * dead view tree, and stops a failure dialog from being shown on a dead window
     * ({@code WindowManager.BadTokenException}).
     */
    private void runOnUiThreadIfAlive(Runnable task) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            task.run();
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void showEmpty(String message) {
        recyclerView.setVisibility(View.GONE);
        emptyText.setText(message);
        emptyText.setVisibility(View.VISIBLE);
    }
}
