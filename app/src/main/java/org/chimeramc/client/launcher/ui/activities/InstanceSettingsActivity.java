package org.chimeramc.client.ui.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.chimeramc.client.R;
import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.core.versions.VersionManager;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.ui.dialogs.CustomAlertDialog;
import org.chimeramc.client.ui.dialogs.InstallProgressDialog;
import org.chimeramc.client.util.InstanceBackupManager;
import org.chimeramc.client.util.InstanceShortcutManager;
import org.chimeramc.client.launcher.core.minecraft.UserFacingError;

public class InstanceSettingsActivity extends BaseActivity {
    private static final int REQUEST_BACKUP_STORAGE = 4201;

    private GameVersion version;
    private VersionManager versionManager;
    private InstanceBackupManager backupManager;
    private InstallProgressDialog backupProgressDialog;
    private Button backupButton;

    private TextView tabGeneral, tabLaunchOptions, tabManagement;
    private View sectionGeneral, sectionLaunchOptions, sectionManagement;

    private EditText editName;
    private SwitchMaterial switchIsolation;
    private SwitchMaterial switchLaunchVertically;
    private SwitchMaterial switchShaderCompat;
    private SwitchMaterial switchInGamePackChanger;
    private View shaderCompatContainer;
    private View inGamePackChangerContainer;
    private View boundAccountContainer;
    private TextView boundAccountValue;
    private TextView boundAccountWarning;
    private EditText editShortcutName;
    private ImageView shortcutIconPreview;
    private String shortcutIconUri;
    private ActivityResultLauncher<String[]> shortcutIconPicker;
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingNameSave;
    private boolean populatingData;
    private String lastSavedInstanceName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_instance_settings);

        shortcutIconPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            shortcutIconUri = uri.toString();
            updateShortcutIconPreview();
            saveShortcutSettings();
        });

        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));

        setupNavBar();

        versionManager = VersionManager.get(this);
        backupManager = new InstanceBackupManager(this);

        version = getIntent().getParcelableExtra("version");
        if (version == null) {
            finish();
            return;
        }

        initViews();
        populateData();
        selectTab(tabGeneral);
    }

    private void initViews() {
        tabGeneral = findViewById(R.id.tab_general);
        tabLaunchOptions = findViewById(R.id.tab_launch_options);
        tabManagement = findViewById(R.id.tab_management);

        sectionGeneral = findViewById(R.id.section_general);
        sectionLaunchOptions = findViewById(R.id.section_launch_options);
        sectionManagement = findViewById(R.id.section_management);

        editName = findViewById(R.id.edit_instance_name);
        switchIsolation = findViewById(R.id.switch_version_isolation);
        switchLaunchVertically = findViewById(R.id.switch_launch_vertically);
        switchShaderCompat = findViewById(R.id.switch_shader_compat);
        shaderCompatContainer = findViewById(R.id.shader_compat_container);
        switchInGamePackChanger = findViewById(R.id.switch_in_game_pack_changer);
        inGamePackChangerContainer = findViewById(R.id.in_game_pack_changer_container);
        boundAccountContainer = findViewById(R.id.bound_account_container);
        boundAccountValue = findViewById(R.id.bound_account_value);
        boundAccountWarning = findViewById(R.id.bound_account_warning);
        setupBoundAccountRow();
        editShortcutName = findViewById(R.id.edit_shortcut_name);
        shortcutIconPreview = findViewById(R.id.shortcut_icon_preview);

        tabGeneral.setOnClickListener(v -> selectTab(tabGeneral));
        tabLaunchOptions.setOnClickListener(v -> selectTab(tabLaunchOptions));
        tabManagement.setOnClickListener(v -> selectTab(tabManagement));

        if (shaderCompatContainer != null) {
            shaderCompatContainer.setVisibility(View.VISIBLE);
        }

        switchIsolation.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (populatingData) return;
            versionManager.setInstanceVersionIsolation(version, isChecked);
            setResult(RESULT_OK);
        });
        switchLaunchVertically.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (populatingData) return;
            versionManager.setInstanceLaunchVertically(version, isChecked);
            setResult(RESULT_OK);
        });
        switchShaderCompat.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (populatingData) return;
            versionManager.setInstanceShaderCompatEnabled(version, isChecked);
            setResult(RESULT_OK);
        });
        if (switchInGamePackChanger != null) {
            switchInGamePackChanger.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (populatingData) return;
                versionManager.setInstanceInGamePackChangerEnabled(version, isChecked);
                setResult(RESULT_OK);
            });
        }

        editName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!populatingData) scheduleInstanceNameSave();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        editShortcutName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!populatingData) saveShortcutSettings();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        findViewById(R.id.btn_choose_shortcut_icon).setOnClickListener(v ->
                shortcutIconPicker.launch(new String[]{"image/*"}));
        findViewById(R.id.btn_reset_shortcut_icon).setOnClickListener(v -> {
            shortcutIconUri = null;
            updateShortcutIconPreview();
            saveShortcutSettings();
        });
        findViewById(R.id.btn_add_instance_shortcut).setOnClickListener(v -> addInstanceShortcut());

        Button btnDelete = findViewById(R.id.btn_delete_instance);
        backupButton = findViewById(R.id.btn_backup_instance);
        if (backupButton != null) {
            backupButton.setOnClickListener(v -> confirmBackup());
        }
        if (version.isInstalled) {
            btnDelete.setEnabled(false);
            btnDelete.setAlpha(0.4f);
        } else {
            btnDelete.setOnClickListener(v -> confirmDelete());
        }
    }

    private void populateData() {
        TextView instanceInfo = findViewById(R.id.instance_info);
        String type = version.isInstalled ? getString(R.string.tag_installed) : getString(R.string.tag_custom);
        String info = "Game Version: " + (version.versionCode != null ? version.versionCode : "—")
                + " · Name: " + (version.directoryName != null ? version.directoryName : "—")
                + " · " + type;
        instanceInfo.setText(info);

        String currentName = version.versionCode != null ? version.versionCode : "";
        if (version.displayName != null && !version.displayName.isEmpty()) {
            String dn = version.displayName;
            int parenIdx = dn.lastIndexOf(" (");
            if (parenIdx > 0) {
                currentName = dn.substring(0, parenIdx);
            } else {
                currentName = dn;
            }
        }
        populatingData = true;
        lastSavedInstanceName = currentName.trim();
        editName.setText(currentName);
        switchIsolation.setChecked(version.versionIsolation);
        switchLaunchVertically.setChecked(version.launchVertically);
        
        if (switchShaderCompat != null) {
            switchShaderCompat.setChecked(version.shaderCompatEnabled);
        }

        if (switchInGamePackChanger != null) {
            switchInGamePackChanger.setChecked(version.inGamePackChangerEnabled);
        }

        refreshBoundAccountRow();
        
        editShortcutName.setText(InstanceShortcutManager.getSavedName(this, version));
        shortcutIconUri = InstanceShortcutManager.getSavedIconUri(this, version);
        updateShortcutIconPreview();
        populatingData = false;
    }

    /**
     * Wires the per-instance account binding row (F2).
     *
     * <p>The row is a button rather than a switch because it opens a picker: an instance can be
     * bound to any signed-in account or to "whatever is active". The current choice and a stale
     * binding are both shown as text, so the state is readable without opening the picker.
     */
    private void setupBoundAccountRow() {
        if (boundAccountContainer == null) return;
        Button change = findViewById(R.id.button_bound_account);
        if (change != null) {
            change.setOnClickListener(v -> showAccountPicker());
            DynamicAnim.applyPressScale(change);
        }
    }

    private void showAccountPicker() {
        java.util.List<org.chimeramc.client.core.auth.MsftAccountStore.MsftAccount> accounts =
                org.chimeramc.client.core.auth.MsftAccountStore.list(this);
        if (accounts.isEmpty()) {
            Toast.makeText(this, R.string.instance_account_no_accounts, Toast.LENGTH_LONG).show();
            return;
        }
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<String> ids = new java.util.ArrayList<>();
        // "Use the active account" is always the first entry: clearing a binding must be as easy
        // as setting one, and it is the behaviour an unbound instance has.
        labels.add(getString(R.string.instance_account_use_active));
        ids.add(null);
        for (org.chimeramc.client.core.auth.MsftAccountStore.MsftAccount account : accounts) {
            String name = account.xboxGamertag != null && !account.xboxGamertag.isEmpty()
                    ? account.xboxGamertag
                    : (account.minecraftUsername != null ? account.minecraftUsername : account.id);
            labels.add(name);
            ids.add(account.id);
        }
        String[] items = labels.toArray(new String[0]);
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.instance_account_picker_title))
                .setItems(items, (dialog, which) -> {
                    versionManager.setInstanceBoundAccount(version, ids.get(which));
                    setResult(RESULT_OK);
                    refreshBoundAccountRow();
                })
                .show();
    }

    /** Re-renders the binding row from the current metadata, including a stale binding. */
    private void refreshBoundAccountRow() {
        if (boundAccountContainer == null || boundAccountValue == null) return;
        String boundId = versionManager.getInstanceBoundAccount(version);
        java.util.List<org.chimeramc.client.core.auth.MsftAccountStore.MsftAccount> accounts =
                org.chimeramc.client.core.auth.MsftAccountStore.list(this);

        if (boundId == null || boundId.isEmpty()) {
            boundAccountValue.setText(R.string.instance_account_active);
            if (boundAccountWarning != null) boundAccountWarning.setVisibility(View.GONE);
            return;
        }

        org.chimeramc.client.core.auth.MsftAccountStore.MsftAccount bound = null;
        for (org.chimeramc.client.core.auth.MsftAccountStore.MsftAccount account : accounts) {
            if (boundId.equals(account.id)) {
                bound = account;
                break;
            }
        }
        boolean stale = bound == null || bound.serializedAuthManager == null;
        if (stale) {
            boundAccountValue.setText(R.string.instance_account_active);
            if (boundAccountWarning != null) boundAccountWarning.setVisibility(View.VISIBLE);
        } else {
            String name = bound.xboxGamertag != null && !bound.xboxGamertag.isEmpty()
                    ? bound.xboxGamertag
                    : (bound.minecraftUsername != null ? bound.minecraftUsername : bound.id);
            boundAccountValue.setText(getString(R.string.instance_account_bound, name));
            if (boundAccountWarning != null) boundAccountWarning.setVisibility(View.GONE);
        }
    }

    private void selectTab(TextView selectedTab) {
        TextView[] tabs = {tabGeneral, tabLaunchOptions, tabManagement};
        View[] sections = {sectionGeneral, sectionLaunchOptions, sectionManagement};

        org.chimeramc.client.util.PersonalizationManager pm = new org.chimeramc.client.util.PersonalizationManager(this);
        int accent = pm.getAccentColor();

        for (int i = 0; i < tabs.length; i++) {
            boolean isSelected = tabs[i] == selectedTab;

            if (isSelected) {
                if (accent != 0) {
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                    gd.setColor(accent);
                    gd.setCornerRadius(16 * getResources().getDisplayMetrics().density);
                    tabs[i].setBackground(gd);
                } else {
                    tabs[i].setBackgroundResource(R.drawable.bg_tab_selected);
                }
                tabs[i].setTextColor(android.graphics.Color.WHITE);
            } else {
                tabs[i].setBackgroundResource(R.drawable.bg_tab_unselected);
                tabs[i].setTextColor(getColor(R.color.text_secondary));
            }

            if (isSelected) {
                sections[i].setVisibility(View.VISIBLE);
                sections[i].setAlpha(0f);
                sections[i].animate().alpha(1f).setDuration(200).start();
            } else {
                sections[i].setVisibility(View.GONE);
            }
        }
    }

    private void scheduleInstanceNameSave() {
        if (pendingNameSave != null) {
            autoSaveHandler.removeCallbacks(pendingNameSave);
        }
        pendingNameSave = this::saveInstanceNameNow;
        autoSaveHandler.postDelayed(pendingNameSave, 250);
    }

    private void saveInstanceNameNow() {
        if (pendingNameSave != null) {
            autoSaveHandler.removeCallbacks(pendingNameSave);
            pendingNameSave = null;
        }
        if (editName == null || version == null) return;
        String newName = editName.getText().toString().trim();
        if (newName.isEmpty() || newName.equals(lastSavedInstanceName)) return;
        if (versionManager.setInstanceDisplayName(version, newName)) {
            lastSavedInstanceName = newName;
            setResult(RESULT_OK);
        } else {
            Toast.makeText(this, R.string.instance_settings_auto_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void saveShortcutSettings() {
        if (populatingData || version == null || editShortcutName == null) return;
        String shortcutName = editShortcutName.getText().toString().trim();
        InstanceShortcutManager.saveSettings(this, version, shortcutName, shortcutIconUri);
        if (InstanceShortcutManager.isPinned(this, version)) {
            InstanceShortcutManager.createOrUpdate(this, version, shortcutName, shortcutIconUri);
        }
        setResult(RESULT_OK);
    }

    private void addInstanceShortcut() {
        saveShortcutSettings();
        String shortcutName = editShortcutName == null ? "" : editShortcutName.getText().toString().trim();
        boolean accepted = InstanceShortcutManager.createOrUpdate(this, version, shortcutName, shortcutIconUri);
        if (!accepted) {
            Toast.makeText(this, R.string.instance_shortcut_not_supported, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateShortcutIconPreview() {
        if (shortcutIconPreview == null) return;
        android.graphics.Bitmap bitmap = InstanceShortcutManager.loadIconBitmap(this, shortcutIconUri);
        if (bitmap != null) {
            shortcutIconPreview.setImageBitmap(bitmap);
        } else {
            shortcutIconPreview.setImageResource(R.mipmap.ic_launcher);
        }
    }

    private void confirmDelete() {
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.instance_delete_confirm_title))
                .setMessage(getString(R.string.instance_delete_confirm_msg))
                .setPositiveButton(getString(R.string.delete), v -> {
                    versionManager.deleteCustomVersion(version, new VersionManager.OnDeleteVersionCallback() {
                        @Override
                        public void onDeleteCompleted(boolean success) {
                            runOnUiThread(() -> {
                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onDeleteFailed(Exception e) {
                            runOnUiThread(() -> Toast.makeText(InstanceSettingsActivity.this,
                                    getString(R.string.toast_delete_failed, UserFacingError.reason(InstanceSettingsActivity.this, e)),
                                    Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void confirmBackup() {
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.instance_backup_title))
                .setMessage(getString(R.string.instance_backup_confirm_message))
                .setPositiveButton(getString(R.string.backup), v -> startBackupWithPermissionCheck())
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void startBackupWithPermissionCheck() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_BACKUP_STORAGE);
            return;
        }
        startBackup();
    }

    private void startBackup() {
        if (backupButton != null) {
            backupButton.setEnabled(false);
            backupButton.setAlpha(0.55f);
        }
        backupProgressDialog = new InstallProgressDialog(this);
        backupProgressDialog.setTitleText(getString(R.string.instance_backup_title));
        backupProgressDialog.setStatusText(getString(R.string.instance_backup_in_progress));
        backupProgressDialog.setProgress(0);
        backupProgressDialog.show();

        backupManager.backup(version, new InstanceBackupManager.BackupCallback() {
            @Override
            public void onStarted() {
                if (backupProgressDialog != null) {
                    backupProgressDialog.setProgress(0);
                    backupProgressDialog.setStatusText(getString(R.string.instance_backup_in_progress));
                }
            }

            @Override
            public void onProgress(int progress) {
                if (backupProgressDialog != null) {
                    backupProgressDialog.setProgress(progress);
                }
            }

            @Override
            public void onSuccess(String displayPath) {
                finishBackupProgress();
                new CustomAlertDialog(InstanceSettingsActivity.this)
                        .setTitleText(getString(R.string.instance_backup_success_title))
                        .setMessage(getString(R.string.instance_backup_success_message, displayPath))
                        .setPositiveButton(getString(R.string.confirm), null)
                        .show();
            }

            @Override
            public void onError(String message) {
                finishBackupProgress();
                new CustomAlertDialog(InstanceSettingsActivity.this)
                        .setTitleText(getString(R.string.instance_backup_failed_title))
                        .setMessage(getString(R.string.instance_backup_failed_message, message))
                        .setPositiveButton(getString(R.string.confirm), null)
                        .show();
            }
        });
    }

    private void finishBackupProgress() {
        if (backupProgressDialog != null && backupProgressDialog.isShowing()) {
            backupProgressDialog.dismiss();
        }
        backupProgressDialog = null;
        if (backupButton != null) {
            backupButton.setEnabled(true);
            backupButton.setAlpha(1f);
        }
    }

    @Override
    protected void onPause() {
        saveInstanceNameNow();
        saveShortcutSettings();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (pendingNameSave != null) {
            autoSaveHandler.removeCallbacks(pendingNameSave);
            pendingNameSave = null;
        }
        // The backup manager owns a single-thread pool created for this screen; without this it
        // outlived the Activity.
        if (backupManager != null) {
            backupManager.close();
            backupManager = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BACKUP_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startBackup();
            } else {
                Toast.makeText(this, R.string.storage_permission_not_granted, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setupNavBar() {
        setActiveNavTab(R.id.nav_tab_library);
    }
}
