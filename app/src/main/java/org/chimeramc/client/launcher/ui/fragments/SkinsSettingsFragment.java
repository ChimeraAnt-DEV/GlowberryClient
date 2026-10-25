package org.chimeramc.client.ui.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.content.ContentImporter;
import org.chimeramc.client.core.content.ResourcePackItem;
import org.chimeramc.client.core.content.ResourcePackManager;
import org.chimeramc.client.core.content.SkinPackActivator;
import org.chimeramc.client.core.content.SkinPackBuilder;
import org.chimeramc.client.core.cosmetics.PlayerSkinProvider;
import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.core.versions.VersionManager;
import org.chimeramc.client.ui.adapter.SkinsAdapter;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.ui.dialogs.CustomAlertDialog;
import org.chimeramc.client.ui.util.SkinPreviewRenderer;
import org.chimeramc.client.util.LauncherStorage;
import org.chimeramc.client.util.PersonalizationManager;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Skin pack management, hosted inside {@code CustomizeActivity}. */
public class SkinsSettingsFragment extends Fragment {

    private static final String TAG = "SkinsSettingsFragment";

    private static final String PREFS_NAME = "skins_state";
    private static final String KEY_APPLIED_TYPE = "applied_type";

    private RecyclerView recycler;
    private SkinsAdapter adapter;
    private View loadingOverlay;
    private View emptyView;
    private VersionManager versionManager;
    private ActivityResultLauncher<String> importLauncher;
    private ActivityResultLauncher<String[]> multiImageLauncher;

    /** Off the UI thread: decoding and re-encoding several skins is not instant. */
    private ExecutorService ioExecutor;

    private List<PendingSkin> pendingSelection = new ArrayList<>();

    /** One decoded image queued for the import dialog. */
    private static final class PendingSkin {
        final String name;
        final Bitmap bitmap;

        PendingSkin(String name, Bitmap bitmap) {
            this.name = name;
            this.bitmap = bitmap;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_skins, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ioExecutor = Executors.newSingleThreadExecutor();
        PersonalizationManager pm = new PersonalizationManager(requireContext());
        int pad = (int) (org.chimeramc.client.ui.components.UiTokens.SPACE_4
                * getResources().getDisplayMetrics().density);
        view.setPadding(0, pad, 0, pad);

        recycler = view.findViewById(R.id.skins_recycler);
        loadingOverlay = view.findViewById(R.id.skins_loading_overlay);
        emptyView = view.findViewById(R.id.skins_empty);
        Button importButton = view.findViewById(R.id.skins_import_button);
        Button emptyImportButton = view.findViewById(R.id.skins_empty_import_button);
        Button resetButton = view.findViewById(R.id.skins_reset_button);
        importButton.setOnClickListener(v -> startImport());
        emptyImportButton.setOnClickListener(v -> startImport());
        if (resetButton != null) resetButton.setOnClickListener(v -> resetToDefaultSkin());

        versionManager = VersionManager.get(requireContext());
        adapter = new SkinsAdapter();
        adapter.setOnSkinActionListener(this::applySkinPack);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);

        importLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        importSkinFile(uri);
                    }
                }
        );

        // Multi-select so several PNGs can be wrapped into a single pack.
        multiImageLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris != null && !uris.isEmpty()) {
                        beginPngImport(uris);
                    }
                }
        );

        loadSkins();
    }

    /**
     * Submits skin work to the fragment's worker, tolerating a torn-down view.
     *
     * <p>The PNG import result can be delivered as the view is being destroyed, and the pool is
     * shut down and nulled in {@link #onDestroyView()}. Submitting then either threw
     * {@code RejectedExecutionException} or dereferenced the null field, both on the main thread,
     * so picking a skin at the wrong moment took the app down. A submission with no view left has
     * nowhere to report to, so it is dropped.
     */
    private void submitToIo(Runnable work) {
        ExecutorService executor = ioExecutor;
        if (executor == null || executor.isShutdown()) return;
        try {
            executor.execute(work);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            Log.w(TAG, "Skin task dropped: the view is gone", rejected);
        }
    }

    @Override
    public void onDestroyView() {
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
            ioExecutor = null;
        }
        if (adapter != null) {
            // The adapter owns a decode pool; without this the two worker threads outlive the view
            // and stay parked for the life of the process.
            adapter.shutdown();
        }
        recycler = null;
        adapter = null;
        loadingOverlay = null;
        emptyView = null;
        super.onDestroyView();
    }

    /**
     * Asks what kind of file is being imported.
     *
     * The picker used to open {@code *}/{@code *} and hand whatever came back to
     * {@code ContentImporter}, which only understands pack archives — so a plain skin PNG was
     * accepted by the picker and then silently ignored. Splitting the two paths here is what
     * routes a bare image to {@link SkinPackBuilder} instead.
     */
    private void startImport() {
        try {
            String[] choices = {
                    getString(R.string.skins_import_choice_png),
                    getString(R.string.skins_import_choice_pack)
            };
            new CustomAlertDialog(requireContext())
                    .setTitleText(getString(R.string.skins_import_choice_title))
                    .setItems(choices, (dialog, which) -> {
                        if (which == 0) {
                            pickImages();
                        } else {
                            importLauncher.launch("*/*");
                        }
                    })
                    .show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void pickImages() {
        try {
            multiImageLauncher.launch(new String[]{"image/png", "image/*"});
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Decodes the chosen images off the UI thread, then opens the preview/name/arm-model dialog.
     *
     * A single unreadable image aborts the whole selection with a clear message rather than
     * building a pack that silently omits it.
     */
    private void beginPngImport(List<Uri> uris) {
        final Context appContext = requireContext().getApplicationContext();
        Toast.makeText(appContext, R.string.skins_loading, Toast.LENGTH_SHORT).show();
        submitToIo(() -> {
            List<PendingSkin> decoded = new ArrayList<>();
            String error = null;
            for (Uri uri : uris) {
                Bitmap bitmap = decode(appContext, uri);
                if (bitmap == null) {
                    error = appContext.getString(R.string.skins_png_bad_image);
                    break;
                }
                if (!SkinPackBuilder.isAcceptableSize(bitmap.getWidth(), bitmap.getHeight())) {
                    error = appContext.getString(R.string.skins_png_invalid_size);
                    break;
                }
                decoded.add(new PendingSkin(fileNameOf(uri), bitmap));
            }
            final List<PendingSkin> result = decoded;
            final String failure = error;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                View root = getView();
                if (root == null) return;
                if (failure != null) {
                    Toast.makeText(requireContext(), failure, Toast.LENGTH_LONG).show();
                    return;
                }
                pendingSelection = result;
                showImportDialog();
            });
        });
    }

    private Bitmap decode(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            Bitmap raw = BitmapFactory.decodeStream(in, null, opts);
            if (raw == null) return null;
            // Force ARGB_8888 so getPixels below returns alpha rather than a downsampled config.
            if (raw.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap converted = raw.copy(Bitmap.Config.ARGB_8888, false);
                if (converted != null) {
                    raw.recycle();
                    raw = converted;
                }
            }
            return raw;
        } catch (Exception e) {
            return null;
        }
    }

    private String fileNameOf(Uri uri) {
        String path = uri.getLastPathSegment();
        if (path == null) return "skin";
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        int dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(0, dot) : path;
    }

    /** Preview + arm model + name, then write the pack and activate it. */
    private void showImportDialog() {
        if (pendingSelection.isEmpty()) return;
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_skin_import, null);
        ImageView preview = dialogView.findViewById(R.id.skin_import_preview);
        ProgressBar previewProgress = dialogView.findViewById(R.id.skin_import_preview_progress);
        RadioGroup armModel = dialogView.findViewById(R.id.skin_import_arm_model);
        EditText nameEdit = dialogView.findViewById(R.id.skin_import_name);

        String defaultName = pendingSelection.get(0).name;
        if (pendingSelection.size() > 1) {
            defaultName = getString(R.string.skins_png_multi_label, pendingSelection.size());
        }
        nameEdit.setText(defaultName);
        nameEdit.setSelection(nameEdit.getText().length());

        // Preview is rendered from an already-decoded bitmap, so this is a few hundred blits and
        // not worth a thread switch now that decoding is done.
        if (previewProgress != null) previewProgress.setVisibility(View.GONE);
        if (preview != null) preview.setImageBitmap(SkinPreviewRenderer.render(pendingSelection.get(0).bitmap));

        new CustomAlertDialog(requireContext())
                .setTitleText(getString(R.string.skins_import_png))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.add), v -> {
                    boolean slim = armModel != null
                            && armModel.getCheckedRadioButtonId() == R.id.skin_import_arm_slim;
                    buildAndApplyPack(nameEdit.getText().toString().trim(), slim);
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void buildAndApplyPack(String packName, boolean slim) {
        List<PendingSkin> selection = new ArrayList<>(pendingSelection);
        final Context appContext = requireContext().getApplicationContext();
        GameVersion version = versionManager.getSelectedVersion();
        File gameDataDir = resolveGameDataDir(requireContext(), version);
        File skinPacksDir = new File(gameDataDir, "skin_packs");

        Toast.makeText(appContext, R.string.skins_loading, Toast.LENGTH_SHORT).show();
        submitToIo(() -> {
            String error = null;
            SkinPackBuilder.BuiltPack built = null;
            try {
                List<SkinPackBuilder.SkinEntry> entries = new ArrayList<>();
                for (PendingSkin skin : selection) {
                    int w = skin.bitmap.getWidth();
                    int h = skin.bitmap.getHeight();
                    int[] argb = new int[w * h];
                    skin.bitmap.getPixels(argb, 0, w, 0, 0, w, h);
                    entries.add(new SkinPackBuilder.SkinEntry(skin.name, argb, w, h));
                }
                built = SkinPackBuilder.build(skinPacksDir, packName, entries, slim);
            } catch (SkinPackBuilder.InvalidSkinException e) {
                error = e.getMessage();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }

            final SkinPackBuilder.BuiltPack result = built;
            final String failure = error;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (getView() == null) return;
                if (failure != null || result == null) {
                    Toast.makeText(requireContext(),
                            getString(R.string.skins_apply_failed, failure == null ? "" : failure),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                activateBuiltPack(result);
            });
        });
    }

    /** Hands the freshly built pack to the existing activator so there is one install path. */
    private void activateBuiltPack(SkinPackBuilder.BuiltPack built) {
        GameVersion version = versionManager.getSelectedVersion();
        if (version == null) {
            Toast.makeText(requireContext(), R.string.skins_no_version, Toast.LENGTH_LONG).show();
            return;
        }
        File gameDataDir = resolveGameDataDir(requireContext(), version);
        SkinPackActivator.Result activation = SkinPackActivator.apply(built.directory, gameDataDir);
        if (!activation.success) {
            Toast.makeText(requireContext(),
                    getString(R.string.skins_apply_failed, activation.message), Toast.LENGTH_LONG).show();
            return;
        }
        PlayerSkinProvider.setAppliedSkinPackName(requireContext(), built.directory.getName());
        Toast.makeText(requireContext(),
                getString(R.string.skins_png_built, built.directory.getName()), Toast.LENGTH_SHORT).show();
        Toast.makeText(requireContext(), R.string.skins_restart_hint, Toast.LENGTH_LONG).show();
        loadSkins();
    }

    /**
     * Clears the launcher's skin selection, returning the character to the default.
     *
     * Un-applies every pack this launcher applied (rather than guessing one) and clears the
     * custom-skin path, so the preview and the game both fall back to the player's own skin. It
     * only removes packs the launcher manages, never one the player activated in the game.
     */
    private void resetToDefaultSkin() {
        GameVersion version = versionManager.getSelectedVersion();
        if (version == null) {
            Toast.makeText(requireContext(), R.string.skins_no_version, Toast.LENGTH_LONG).show();
            return;
        }
        File gameDataDir = resolveGameDataDir(requireContext(), version);
        submitToIo(() -> {
            for (ResourcePackItem pack : readSkinPacks()) {
                SkinPackActivator.PackIdentity identity = SkinPackActivator.readIdentity(pack.getFile());
                if (identity != null && SkinPackActivator.isAppliedByLauncher(gameDataDir, identity.uuid)) {
                    SkinPackActivator.unapply(gameDataDir, identity.uuid);
                }
            }
            PlayerSkinProvider.setAppliedSkinPackName(requireContext(), null);
            PlayerSkinProvider.setCustomSkinPath(requireContext(), null);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (getView() == null) return;
                Toast.makeText(requireContext(), R.string.skins_reset_done, Toast.LENGTH_SHORT).show();
                loadSkins();
            });
        });
    }

    private void importSkinFile(Uri uri) {
        Toast.makeText(requireContext(), R.string.skins_loading, Toast.LENGTH_SHORT).show();
        GameVersion version = versionManager.getSelectedVersion();
        File gameDataDir = resolveGameDataDir(requireContext(), version);
        File resDir = new File(gameDataDir, "resource_packs");
        File behDir = new File(gameDataDir, "behavior_packs");
        File skinDir = new File(gameDataDir, "skin_packs");
        List<Uri> uris = new ArrayList<>();
        uris.add(uri);
        new ContentImporter(requireContext()).importContent(uris, resDir, behDir, skinDir, null,
                new ContentImporter.ImportCallback() {
                    @Override
                    public void onSuccess(String message) {
                        runOnUi(message, true);
                    }

                    @Override
                    public void onError(String error) {
                        runOnUi(error, false);
                    }

                    @Override
                    public void onProgress(int progress) {
                    }
                });
    }

    private void runOnUi(String message, boolean longToast) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            Toast.makeText(requireContext(), message, longToast ? Toast.LENGTH_LONG : Toast.LENGTH_LONG).show();
            loadSkins();
        });
    }

    private void loadSkins() {
        if (recycler == null) return;
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.VISIBLE);
            recycler.setVisibility(View.GONE);
        }
        new Thread(() -> {
            List<ResourcePackItem> packs = readSkinPacks();
            String applied = readAppliedPackName();
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (adapter == null || recycler == null) return;
                adapter.updateSkinPacks(packs, applied, true);
                boolean hasPacks = !packs.isEmpty();
                if (emptyView != null) emptyView.setVisibility(hasPacks ? View.GONE : View.VISIBLE);
                if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
                recycler.setVisibility(hasPacks ? View.VISIBLE : View.GONE);
                recycler.post(() -> DynamicAnim.staggerRecyclerChildren(recycler));
            });
        }).start();
    }

    private List<ResourcePackItem> readSkinPacks() {
        GameVersion version = versionManager.getSelectedVersion();
        if (version == null) return new ArrayList<>();
        ResourcePackManager manager = new ResourcePackManager(requireContext());
        manager.setCurrentVersion(version);
        List<ResourcePackItem> packs = manager.getSkinPacks();
        return packs != null ? packs : new ArrayList<>();
    }

    /**
     * Turns a skin pack on or off for the selected instance.
     *
     * The pack is registered in the game's global resource pack list, not merely recorded in
     * a launcher preference, because that file is what the game actually reads. The previous
     * implementation only wrote a preference, so "applied" never changed anything in-game.
     */
    private void applySkinPack(ResourcePackItem pack) {
        GameVersion version = versionManager.getSelectedVersion();
        if (version == null) {
            Toast.makeText(requireContext(), R.string.skins_no_version, Toast.LENGTH_LONG).show();
            return;
        }
        File gameDataDir = resolveGameDataDir(requireContext(), version);
        File source = pack.getFile();
        SkinPackActivator.PackIdentity identity = SkinPackActivator.readIdentity(source);
        if (identity == null) {
            Toast.makeText(requireContext(), R.string.skins_not_a_pack, Toast.LENGTH_LONG).show();
            return;
        }

        boolean applied = SkinPackActivator.isAppliedByLauncher(gameDataDir, identity.uuid);
        SkinPackActivator.Result result = applied
                ? SkinPackActivator.unapply(gameDataDir, identity.uuid)
                : SkinPackActivator.apply(source, gameDataDir);
        if (!result.success) {
            Toast.makeText(requireContext(),
                    getString(R.string.skins_apply_failed, result.message), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(requireContext(),
                getString(applied ? R.string.skins_removed : R.string.skins_applied, pack.getPackName()),
                Toast.LENGTH_SHORT).show();
        // Record the pack so the cosmetics preview can find its texture. The game's resource
        // pack list says the pack is active; it does not say which pack that is, so without this
        // the preview has no name to match against and falls back to the placeholder.
        PlayerSkinProvider.setAppliedSkinPackName(requireContext(),
                applied ? null : pack.getPackName());
        loadSkins();
    }

    /** Name of the pack currently activated by the launcher for the selected instance, if any. */
    private String readAppliedPackName() {
        GameVersion version = versionManager.getSelectedVersion();
        if (version == null) return null;
        File gameDataDir = resolveGameDataDir(requireContext(), version);
        for (ResourcePackItem pack : readSkinPacks()) {
            SkinPackActivator.PackIdentity identity = SkinPackActivator.readIdentity(pack.getFile());
            if (identity != null && SkinPackActivator.isAppliedByLauncher(gameDataDir, identity.uuid)) {
                return pack.getPackName();
            }
        }
        return null;
    }

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * The game data root the selected instance actually plays from.
     *
     * <p>Skin packs must land in the same directory the game loads, so this follows the player's
     * storage setting rather than a fixed path. Falling back to the installed-profile id when no
     * version is selected keeps the import usable before an instance is chosen.
     */
    private File resolveGameDataDir(Context context, GameVersion version) {
        if (version == null) {
            return LauncherStorage.getActiveGameDataDir(
                    context,
                    LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID,
                    true,
                    LauncherStorage.readSavedContentStorageType(context));
        }
        return LauncherStorage.getActiveGameDataDir(
                context,
                version.getStorageProfileId(),
                version.versionIsolation,
                LauncherStorage.readSavedContentStorageType(context));
    }
}
