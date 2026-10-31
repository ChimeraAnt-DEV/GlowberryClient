package org.chimeramc.client.ui.activities;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import org.chimeramc.client.R;
import org.chimeramc.client.core.content.ContentImporter;
import org.chimeramc.client.core.downloads.DownloadsScanner;
import org.chimeramc.client.core.modrinth.ModrinthClient;
import org.chimeramc.client.core.modrinth.models.ModrinthProject;
import org.chimeramc.client.core.modrinth.models.ModrinthSearchResponse;
import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.core.versions.VersionManager;
import org.chimeramc.client.settings.FeatureSettings;
import org.chimeramc.client.util.LauncherStorage;
import org.chimeramc.client.util.PersonalizationManager;
import org.chimeramc.client.ui.adapter.DownloadsAdapter;
import org.chimeramc.client.ui.adapter.ModrinthContentAdapter;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.util.UIHelper;
import org.chimeramc.client.util.MainThread;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.List;

/**
 * One screen for finding mods: browse the Modrinth index, or import something the user
 * already downloaded.
 *
 * Modrinth indexes Java Edition content, which this launcher cannot run, so the browser
 * says so up front rather than letting users download projects that cannot be installed.
 */
public class ModHubActivity extends BaseActivity {

    public static final String EXTRA_INITIAL_TAB = "extra_initial_tab";
    public static final int TAB_MODRINTH = 0;
    public static final int TAB_DOWNLOADS = 1;

    private static final int PAGE_SIZE = 20;

    private TabLayout tabs;
    private View modrinthContainer;
    private View downloadsContainer;

    private EditText searchBox;
    private MaterialButton sortButton;
    private RadioGroup typeGroup;
    private RecyclerView modrinthRecycler;
    private ProgressBar modrinthProgress;
    private View modrinthEmpty;
    private ModrinthContentAdapter modrinthAdapter;
    private ModrinthClient modrinthClient;

    private RecyclerView downloadsRecycler;
    private ProgressBar downloadsProgress;
    private View downloadsEmpty;
    private android.widget.TextView downloadsSummary;
    private MaterialButton downloadsScanButton;
    private MaterialButton downloadsImportAllButton;
    private DownloadsAdapter downloadsAdapter;
    private ContentImporter contentImporter;

    private final Handler handler = MainThread.handler();
    // Owned so a Downloads walk in progress is torn down with the screen instead of posting its
    // result to a destroyed Activity.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<TypeOption> typeOptions = new ArrayList<>();
    private final List<SortOption> sortOptions = new ArrayList<>();

    private SortOption currentSort;
    private int currentOffset;
    private long totalHits;
    private boolean scanning;

    private static final class TypeOption {
        final String label;
        final String value;

        TypeOption(String label, String value) {
            this.label = label;
            this.value = value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class SortOption {
        final String label;
        final String value;

        SortOption(String label, String value) {
            this.label = label;
            this.value = value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mod_hub);

        View root = findViewById(android.R.id.content);
        if (root != null) {
            DynamicAnim.applyPressScaleRecursively(root);
        }

        modrinthClient = ModrinthClient.getInstance(this);
        contentImporter = new ContentImporter(this);

        setupData();
        initViews();
        selectTab(getIntent().getIntExtra(EXTRA_INITIAL_TAB, TAB_MODRINTH));
    }

    private void setupData() {
        typeOptions.add(new TypeOption(getString(R.string.modrinth_type_resourcepack),
                ModrinthClient.TYPE_RESOURCEPACK));
        typeOptions.add(new TypeOption(getString(R.string.modrinth_type_mod), ModrinthClient.TYPE_MOD));
        typeOptions.add(new TypeOption(getString(R.string.modrinth_type_modpack), ModrinthClient.TYPE_MODPACK));
        typeOptions.add(new TypeOption(getString(R.string.modrinth_type_datapack), ModrinthClient.TYPE_DATAPACK));

        sortOptions.add(new SortOption("Relevancy", ModrinthClient.INDEX_RELEVANCY));
        sortOptions.add(new SortOption("Downloads", ModrinthClient.INDEX_DOWNLOADS));
        sortOptions.add(new SortOption("Newest", ModrinthClient.INDEX_NEWEST));
        sortOptions.add(new SortOption("Updated", ModrinthClient.INDEX_UPDATED));
    }

    private void initViews() {
        tabs = findViewById(R.id.mod_hub_tabs);
        modrinthContainer = findViewById(R.id.mod_hub_modrinth_container);
        downloadsContainer = findViewById(R.id.mod_hub_downloads_container);

        tabs.addTab(tabs.newTab().setText(R.string.modrinth_title));
        tabs.addTab(tabs.newTab().setText(R.string.downloads_title));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                selectTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        setupModrinthViews();
        setupDownloadsViews();
    }

    private void setupModrinthViews() {
        searchBox = findViewById(R.id.modrinth_search_box);
        sortButton = findViewById(R.id.modrinth_sort_button);
        typeGroup = findViewById(R.id.modrinth_type_group);
        modrinthRecycler = findViewById(R.id.modrinth_recycler);
        modrinthProgress = findViewById(R.id.modrinth_progress);
        modrinthEmpty = findViewById(R.id.modrinth_empty);

        modrinthAdapter = new ModrinthContentAdapter(this::openProject,
                new ModrinthContentAdapter.OnPageChangeListener() {
                    @Override
                    public void onNextPage() {
                        if (currentOffset + PAGE_SIZE < totalHits) {
                            currentOffset += PAGE_SIZE;
                            loadModrinth();
                        }
                    }

                    @Override
                    public void onPrevPage() {
                        if (currentOffset > 0) {
                            currentOffset = Math.max(0, currentOffset - PAGE_SIZE);
                            loadModrinth();
                        }
                    }
                });
        modrinthRecycler.setLayoutManager(new LinearLayoutManager(this));
        modrinthRecycler.setAdapter(modrinthAdapter);

        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                currentOffset = 0;
                loadModrinth();
                UIHelper.hideKeyboard(this);
                return true;
            }
            return false;
        });

        buildTypeChips();

        currentSort = sortOptions.get(0);
        sortButton.setText(getString(R.string.sort_label_format, currentSort.label));
        sortButton.setOnClickListener(this::showSortMenu);
    }

    private void setupDownloadsViews() {
        downloadsRecycler = findViewById(R.id.downloads_recycler);
        downloadsProgress = findViewById(R.id.downloads_progress);
        downloadsEmpty = findViewById(R.id.downloads_empty);
        downloadsSummary = findViewById(R.id.downloads_summary);
        downloadsScanButton = findViewById(R.id.downloads_scan_button);
        downloadsImportAllButton = findViewById(R.id.downloads_import_all_button);

        downloadsAdapter = new DownloadsAdapter(
                found -> Toast.makeText(this, found.file.getAbsolutePath(), Toast.LENGTH_LONG).show(),
                this::importFound);
        downloadsRecycler.setLayoutManager(new LinearLayoutManager(this));
        downloadsRecycler.setAdapter(downloadsAdapter);

        downloadsScanButton.setOnClickListener(v -> scanDownloads());
        downloadsImportAllButton.setOnClickListener(v -> importAllFound());
    }

    private void buildTypeChips() {
        PersonalizationManager pm = new PersonalizationManager(this);
        int accentColor = pm.getAccentColor();
        int onSurface = ContextCompat.getColor(this, R.color.on_surface);

        ColorStateList textColors = new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{Color.WHITE, onSurface});

        float radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f,
                getResources().getDisplayMetrics());

        for (int i = 0; i < typeOptions.size(); i++) {
            TypeOption option = typeOptions.get(i);
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(option.label);
            rb.setTextColor(textColors);
            rb.setTextSize(14f);
            rb.setButtonDrawable(android.R.color.transparent);

            GradientDrawable checkedBg = new GradientDrawable();
            checkedBg.setShape(GradientDrawable.RECTANGLE);
            checkedBg.setCornerRadius(radiusPx);
            checkedBg.setColor(accentColor);

            GradientDrawable uncheckedBg = new GradientDrawable();
            uncheckedBg.setShape(GradientDrawable.RECTANGLE);
            uncheckedBg.setCornerRadius(radiusPx);
            uncheckedBg.setColor(Color.parseColor("#1A888888"));

            StateListDrawable sld = new StateListDrawable();
            sld.addState(new int[]{android.R.attr.state_checked}, checkedBg);
            sld.addState(new int[]{}, uncheckedBg);

            rb.setBackground(sld);
            rb.setPadding(40, 20, 40, 20);
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 16, 0);
            typeGroup.addView(rb, params);
            rb.setTag(option);
            if (i == 0) {
                rb.setChecked(true);
            }
        }

        typeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            currentOffset = 0;
            loadModrinth();
        });
    }

    private void selectTab(int index) {
        boolean showDownloads = index == TAB_DOWNLOADS;
        if (tabs.getSelectedTabPosition() != index) {
            TabLayout.Tab tab = tabs.getTabAt(index);
            if (tab != null) {
                tab.select();
            }
        }
        modrinthContainer.setVisibility(showDownloads ? View.GONE : View.VISIBLE);
        downloadsContainer.setVisibility(showDownloads ? View.VISIBLE : View.GONE);
        if (showDownloads) {
            scanDownloads();
        } else if (modrinthAdapter.getItemCount() == 0) {
            loadModrinth();
        }
    }

    private void showSortMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        for (int i = 0; i < sortOptions.size(); i++) {
            popup.getMenu().add(0, i, i, sortOptions.get(i).label);
        }
        popup.setOnMenuItemClickListener(item -> {
            currentSort = sortOptions.get(item.getItemId());
            sortButton.setText(getString(R.string.sort_label_format, currentSort.label));
            currentOffset = 0;
            loadModrinth();
            return true;
        });
        popup.show();
    }

    private void loadModrinth() {
        TypeOption type = selectedType();

        modrinthProgress.setVisibility(View.VISIBLE);
        modrinthEmpty.setVisibility(View.GONE);

        modrinthClient.search(searchBox.getText().toString(),
                type != null ? type.value : null,
                null,
                currentOffset, PAGE_SIZE, currentSort != null ? currentSort.value : null,
                new ModrinthClient.ModrinthCallback<ModrinthSearchResponse>() {
                    @Override
                    public void onSuccess(ModrinthSearchResponse result) {
                        handler.post(() -> {
                            modrinthProgress.setVisibility(View.GONE);
                            List<ModrinthProject> hits = result != null && result.hits != null
                                    ? result.hits : Collections.<ModrinthProject>emptyList();
                            totalHits = result != null ? result.totalHits : 0;
                            int totalPages = totalHits > 0
                                    ? (int) Math.ceil((double) totalHits / PAGE_SIZE) : 0;
                            modrinthAdapter.setProjects(hits, (currentOffset / PAGE_SIZE) + 1, totalPages);
                            modrinthEmpty.setVisibility(hits.isEmpty() ? View.VISIBLE : View.GONE);
                            modrinthRecycler.scrollToPosition(0);
                        });
                    }

                    @Override
                    public void onError(Throwable t) {
                        handler.post(() -> {
                            modrinthProgress.setVisibility(View.GONE);
                            modrinthEmpty.setVisibility(View.VISIBLE);
                            Toast.makeText(ModHubActivity.this,
                                    getString(R.string.error_message_format, t.getMessage()),
                                    Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    private TypeOption selectedType() {
        int checkedId = typeGroup.getCheckedRadioButtonId();
        if (checkedId == -1) {
            return null;
        }
        return (TypeOption) findViewById(checkedId).getTag();
    }

    private void openProject(ModrinthProject project) {
        Intent intent = new Intent(this, ModrinthDetailActivity.class);
        intent.putExtra(ModrinthDetailActivity.EXTRA_PROJECT, project);
        startActivity(intent);
    }

    private void scanDownloads() {
        if (scanning) {
            return;
        }
        scanning = true;
        downloadsProgress.setVisibility(View.VISIBLE);
        downloadsEmpty.setVisibility(View.GONE);

        // A full walk of the public Downloads directory can be slow on a busy device;
        // keep it off the UI thread.
        if (worker.isShutdown()) {
            scanning = false;
            return;
        }
        try {
            worker.execute(() -> {
                List<DownloadsScanner.Found> found = DownloadsScanner.scan(ModHubActivity.this);
                postIfAlive(() -> {
                    scanning = false;
                    downloadsProgress.setVisibility(View.GONE);
                    downloadsAdapter.setItems(found);
                    downloadsEmpty.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);

                    int importable = downloadsAdapter.importableCount();
                    downloadsSummary.setText(getString(R.string.downloads_summary, importable, found.size()));
                    downloadsImportAllButton.setEnabled(importable > 0);
                    downloadsImportAllButton.setAlpha(importable > 0 ? 1f : 0.5f);
                });
            });
        } catch (RejectedExecutionException e) {
            scanning = false;
        }
    }

    /** Posts to the UI thread only while the screen is still alive. */
    private void postIfAlive(Runnable task) {
        handler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            task.run();
        });
    }

    private void importFound(DownloadsScanner.Found found) {
        if (!found.importable) {
            return;
        }
        importUris(Collections.singletonList(Uri.fromFile(found.file)));
    }

    private void importAllFound() {
        List<DownloadsScanner.Found> items = downloadsAdapter.importableItems();
        if (items.isEmpty()) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        for (DownloadsScanner.Found item : items) {
            uris.add(Uri.fromFile(item.file));
        }
        importUris(uris);
    }

    private void importUris(List<Uri> uris) {
        File[] dirs = contentDirectories();
        if (dirs == null) {
            Toast.makeText(this, R.string.not_found_version, Toast.LENGTH_SHORT).show();
            return;
        }

        contentImporter.importContent(uris, dirs[0], dirs[1], dirs[2], dirs[3],
                new ContentImporter.ImportCallback() {
                    @Override
                    public void onSuccess(String message) {
                        runOnUiThread(() -> {
                            Toast.makeText(ModHubActivity.this, message, Toast.LENGTH_SHORT).show();
                            scanDownloads();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> Toast.makeText(ModHubActivity.this, error, Toast.LENGTH_LONG).show());
                    }

                    @Override
                    public void onProgress(int progress) {
                    }
                });
    }

    /** Resolves the same pack directories the content manager uses, or null with no version set. */
    private File[] contentDirectories() {
        VersionManager versionManager = VersionManager.get(this);
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) {
            return null;
        }

        android.content.SharedPreferences prefs =
                getSharedPreferences("content_management", MODE_PRIVATE);
        FeatureSettings.StorageType storageType;
        try {
            storageType = FeatureSettings.StorageType.valueOf(
                    prefs.getString("storage_type", "INTERNAL"));
        } catch (Exception ignored) {
            storageType = FeatureSettings.StorageType.INTERNAL;
        }
        storageType = LauncherStorage.normalizeContentStorageType(
                storageType, currentVersion.versionIsolation);

        File gameDataDir = LauncherStorage.getContentGameDataDir(
                this, currentVersion.getStorageProfileId(), storageType);
        if (gameDataDir == null) {
            return null;
        }

        return new File[]{
                new File(gameDataDir, "resource_packs"),
                new File(gameDataDir, "behavior_packs"),
                new File(gameDataDir, "skin_packs"),
                new File(gameDataDir, "minecraftWorlds")
        };
    }

    @Override
    protected void onDestroy() {
        // The importer owns a single-thread executor; without this its thread outlives the screen
        // and stays parked for the life of the process.
        if (contentImporter != null) {
            contentImporter.shutdown();
            contentImporter = null;
        }
        worker.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

}
