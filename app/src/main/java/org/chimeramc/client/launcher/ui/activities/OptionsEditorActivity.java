package org.chimeramc.client.ui.activities;

import org.chimeramc.client.util.SafeExecutor;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.chimeramc.client.R;
import org.chimeramc.client.core.content.OptionsEditor;
import org.chimeramc.client.core.content.OptionsEditor.OptionProperty;
import org.chimeramc.client.databinding.ActivityOptionsEditorBinding;
import org.chimeramc.client.ui.adapter.OptionsPropertiesAdapter;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.launcher.core.minecraft.UserFacingError;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class OptionsEditorActivity extends BaseActivity {

    public static final String EXTRA_OPTIONS_PATH = "options_path";
    public static final String EXTRA_STORAGE_TYPE = "storage_type";

    private ActivityOptionsEditorBinding binding;
    private OptionsEditor optionsEditor;
    private OptionsPropertiesAdapter adapter;
    private ExecutorService executor;

    private List<OptionProperty> allProperties = new ArrayList<>();
    private boolean hasUnsavedChanges = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityOptionsEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        DynamicAnim.applyPressScaleRecursively(binding.getRoot());
        executor = Executors.newSingleThreadExecutor();

        String optionsPath = getIntent().getStringExtra(EXTRA_OPTIONS_PATH);
        String storageType = getIntent().getStringExtra(EXTRA_STORAGE_TYPE);

        if (optionsPath == null) {
            Toast.makeText(this, R.string.options_file_not_found, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        optionsEditor = new OptionsEditor(new File(optionsPath));
        setupUI(storageType);
        loadOptionsData();
    }

    /**
     * Submits options work, tolerating an executor {@link #onDestroy()} has shut down.
     *
     * <p>Both calls are reachable from a button press, and a press that lands as the screen closes
     * would otherwise throw {@link java.util.concurrent.RejectedExecutionException} on the main
     * thread. A dropped load is harmless; a dropped save is not, so the log names it.
     */
    private void runOnExecutor(Runnable work) {
        // A false return means the screen is gone; the log names the dropped task.
        if (!SafeExecutor.submit(executor, work)) {
            android.util.Log.w("OptionsEditor", "Options task dropped: the screen is gone");
        }
    }

    private void setupUI(String storageType) {
        String title = getString(R.string.edit_options);
        if (storageType != null) {
            title += " (" + storageType + ")";
        }
        binding.titleText.setText(title);

        binding.saveButton.setOnClickListener(v -> saveChanges());
        binding.saveButton.setEnabled(false);

        adapter = new OptionsPropertiesAdapter();
        adapter.setOnOptionChangedListener((property, newValue) -> {
            optionsEditor.setValue(property.getKey(), newValue);
            property.setValue(newValue);
            hasUnsavedChanges = true;
            binding.saveButton.setEnabled(true);
        });

        binding.optionsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.optionsRecyclerView.setAdapter(adapter);
        setupSearchFilter();
    }

    private void setupSearchFilter() {
        binding.searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterProperties(s.toString());
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void filterProperties(String query) {
        String lowerQuery = query.toLowerCase().trim();
        List<OptionProperty> filtered;
        if (lowerQuery.isEmpty()) {
            filtered = allProperties;
        } else {
            filtered = allProperties.stream()
                .filter(prop -> prop.getDisplayName().toLowerCase().contains(lowerQuery)
                        || prop.getKey().toLowerCase().contains(lowerQuery)
                        || prop.getCategory().toLowerCase().contains(lowerQuery)
                        || prop.getValue().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
        }

        if (filtered.isEmpty() && !allProperties.isEmpty()) {
            binding.emptyText.setVisibility(View.VISIBLE);
            binding.emptyText.setText(R.string.no_matching_options);
            binding.optionsRecyclerView.setVisibility(View.GONE);
        } else if (!filtered.isEmpty()) {
            binding.emptyText.setVisibility(View.GONE);
            binding.optionsRecyclerView.setVisibility(View.VISIBLE);
            Map<String, List<OptionProperty>> grouped = groupByCategory(filtered);
            adapter.setProperties(grouped);
        }
    }

    private void loadOptionsData() {
        binding.loadingProgress.setVisibility(View.VISIBLE);
        binding.optionsRecyclerView.setVisibility(View.GONE);
        binding.emptyText.setVisibility(View.GONE);

        runOnExecutor(() -> {
            try {
                optionsEditor.load();
                runOnUiThread(this::displayOptions);
            } catch (Throwable e) {
                // Throwable, not Exception: this handler is the only thing that clears the spinner,
                // and parsing a large options file can exhaust the heap.
                runOnUiThread(() -> {
                    binding.loadingProgress.setVisibility(View.GONE);
                    binding.emptyText.setVisibility(View.VISIBLE);
                    binding.emptyText.setText(getString(R.string.error_loading_options) + "\n\n"
                            + UserFacingError.describe(OptionsEditorActivity.this, e));
                });
            }
        });
    }

    private void displayOptions() {
        binding.loadingProgress.setVisibility(View.GONE);

        if (!optionsEditor.isLoaded()) {
            binding.emptyText.setVisibility(View.VISIBLE);
            binding.emptyText.setText(R.string.options_file_not_found);
            binding.optionsRecyclerView.setVisibility(View.GONE);
            return;
        }

        allProperties = optionsEditor.getOptionProperties();

        if (allProperties.isEmpty()) {
            binding.emptyText.setVisibility(View.VISIBLE);
            binding.emptyText.setText(R.string.no_options_found);
            binding.optionsRecyclerView.setVisibility(View.GONE);
        } else {
            binding.emptyText.setVisibility(View.GONE);
            binding.optionsRecyclerView.setVisibility(View.VISIBLE);
            Map<String, List<OptionProperty>> grouped = groupByCategory(allProperties);
            adapter.setProperties(grouped);
        }
    }

    private Map<String, List<OptionProperty>> groupByCategory(List<OptionProperty> properties) {
        Map<String, List<OptionProperty>> grouped = new LinkedHashMap<>();
        for (OptionProperty prop : properties) {
            String category = prop.getCategory();
            grouped.computeIfAbsent(category, k -> new ArrayList<>()).add(prop);
        }
        return grouped;
    }

    private void saveChanges() {
        if (!hasUnsavedChanges) return;

        binding.loadingProgress.setVisibility(View.VISIBLE);
        binding.saveButton.setEnabled(false);

        runOnExecutor(() -> {
            try {
                optionsEditor.save();
                runOnUiThread(() -> {
                    binding.loadingProgress.setVisibility(View.GONE);
                    hasUnsavedChanges = false;
                    Toast.makeText(this, R.string.options_saved, Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable e) {
                // Throwable, not Exception: this handler is the only thing that re-enables Save and
                // clears the spinner.
                runOnUiThread(() -> {
                    binding.loadingProgress.setVisibility(View.GONE);
                    binding.saveButton.setEnabled(true);
                    Toast.makeText(this, getString(R.string.save_failed) + "\n\n"
                            + UserFacingError.reason(this, e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (hasUnsavedChanges) {
            AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.unsaved_changes)
                    .setMessage(R.string.discard_changes_message)
                    .setPositiveButton(R.string.discard, (d, which) -> { d.dismiss(); finish(); })
                    .setNegativeButton(R.string.cancel, (d, which) -> d.dismiss())
                    .show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
            org.chimeramc.client.util.PersonalizationManager pm = new org.chimeramc.client.util.PersonalizationManager(this);
            int accent = pm.getAccentColor();
            if (accent != 0) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent);
            }
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdown();
    }
}
