package org.chimeramc.client.ui.fragments;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.installer.BedrockSource;
import org.chimeramc.client.core.installer.BedrockSource.ResolvedDownload;
import org.chimeramc.client.core.installer.BedrockSource.Version;
import org.chimeramc.client.core.installer.BrowserFallback;
import org.chimeramc.client.core.installer.ListingPager;
import org.chimeramc.client.core.installer.PackageSourceClient;
import org.chimeramc.client.core.installer.SourceRegistry;
import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.core.versions.VersionManager;
import org.chimeramc.client.launcher.core.minecraft.UserFacingError;
import org.chimeramc.client.ui.adapter.InstallationAdapter;
import org.chimeramc.client.ui.dialogs.CustomAlertDialog;
import org.chimeramc.client.util.ApkImportManager;
import org.chimeramc.client.util.DownloadedApksStore;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.util.LauncherStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lists the Bedrock versions a package source publishes and installs a chosen one as an
 * isolated instance.
 *
 * The flow is deliberately: resolve the download link, save the package into the app's
 * private {@code Downloaded_APKs} folder, import from that file, and only delete it once the
 * import reported success. A failed import therefore leaves the package in place so "Retry
 * Import" can run the pipeline again without re-downloading several hundred megabytes.
 *
 * Which mirror is used comes from {@link SourceRegistry}, so this screen names no source:
 * the host in the warning text and every URL are read from the active {@link BedrockSource}.
 */
public class InstallationsFragment extends Fragment {

    /** The inflated view; every findViewById goes through it, since a Fragment has no window. */
    private View root;

    private Context ctx() {
        return requireContext();
    }

    /** True once the fragment is detached, so a late network callback must not touch the view. */
    private boolean gone() {
        return !isAdded() || getView() == null;
    }

    private static final String STATE_PENDING_VERSION = "installations_pending_version";
    private static final String STATE_PENDING_PAGE = "installations_pending_page";
    private static final String STATE_PENDING_FILE = "installations_pending_file";
    private static final String STATE_PAGE_WALK = "installations_page_walk";
    private static final String STATE_NEXT_PAGE = "installations_next_page_url";

    private RecyclerView recycler;
    private ProgressBar progress;
    private View emptyContainer;
    private TextView emptyTitle;
    private TextView emptyMessage;
    private TextView refreshButton;
    private TextView sourceWarning;
    private TextView stagedSummary;
    private TextView stagedRetry;
    private TextView stagedClear;
    private View stagedContainer;
    private LinearLayout paginationContainer;
    private TextView pageLabel;
    private TextView prevPageButton;
    private TextView nextPageButton;

    private InstallationAdapter adapter;
    private PackageSourceClient client;
    private BrowserFallback fallback;
    private WebView browser;
    private FrameLayout browserContainer;
    private TextView browserHint;
    private DownloadedApksStore store;
    private ApkImportManager importManager;
    private VersionManager versionManager;
    private BedrockSource source;

    private final List<Version> versions = new ArrayList<>();
    private final Map<String, ResolvedDownload> resolvedDownloads = new HashMap<>();

    /** The version currently downloading or importing, so a retry targets the same instance. */
    private String pendingVersionName;
    private String pendingPageUrl;
    private File pendingFile;

    /**
     * Which listing page is on screen and how to step between them.
     *
     * The archive is fetched one page at a time, so the screen remembers the walk in order to
     * offer "Back", and the page after the current one in order to offer "Next". The numbering
     * and the boundaries live in {@link ListingPager}, which is unit-tested without a device.
     */
    private ListingPager pager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        root = inflater.inflate(R.layout.activity_installations, container, false);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (savedInstanceState != null) {
            pendingVersionName = savedInstanceState.getString(STATE_PENDING_VERSION);
            pendingPageUrl = savedInstanceState.getString(STATE_PENDING_PAGE);
            String path = savedInstanceState.getString(STATE_PENDING_FILE);
            if (path != null) pendingFile = new File(path);
        }

        source = SourceRegistry.active();
        client = PackageSourceClient.getInstance();
        store = new DownloadedApksStore(ctx());
        versionManager = VersionManager.get(ctx());
        importManager = new ApkImportManager(requireActivity(), null);
        importManager.setOnImportCompleteListener(this::onImportSucceeded);
        importManager.setOnImportFailedListener(this::onImportFailed);

        String savedNext = savedInstanceState == null
                ? null : savedInstanceState.getString(STATE_NEXT_PAGE);
        List<String> savedWalk = savedInstanceState == null
                ? null : savedInstanceState.getStringArrayList(STATE_PAGE_WALK);
        pager = new ListingPager(source.listingUrl(), savedWalk, savedNext);

        bindViews();
        fallback = new BrowserFallback(browser);
        setupRecycler();
        refreshStagedPackages();
        loadCurrentPage();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PENDING_VERSION, pendingVersionName);
        outState.putString(STATE_PENDING_PAGE, pendingPageUrl);
        outState.putString(STATE_PENDING_FILE, pendingFile == null ? null : pendingFile.getAbsolutePath());
        if (pager != null) {
            outState.putStringArrayList(STATE_PAGE_WALK, new ArrayList<>(pager.visitedPages()));
            outState.putString(STATE_NEXT_PAGE, pager.nextPageUrl());
        }
    }

    private void bindViews() {
        recycler = root.findViewById(R.id.installations_recycler);
        progress = root.findViewById(R.id.installations_progress);
        emptyContainer = root.findViewById(R.id.installations_empty);
        emptyTitle = root.findViewById(R.id.installations_empty_title);
        emptyMessage = root.findViewById(R.id.installations_empty_message);
        refreshButton = root.findViewById(R.id.installations_refresh);
        sourceWarning = root.findViewById(R.id.installations_source_warning);
        stagedContainer = root.findViewById(R.id.installations_staged_container);
        stagedSummary = root.findViewById(R.id.installations_staged_summary);
        stagedRetry = root.findViewById(R.id.installations_staged_retry);
        stagedClear = root.findViewById(R.id.installations_staged_clear);
        browser = root.findViewById(R.id.installations_browser);
        browserContainer = root.findViewById(R.id.installations_browser_container);
        browserHint = root.findViewById(R.id.installations_browser_hint);
        paginationContainer = root.findViewById(R.id.installations_pagination);
        pageLabel = root.findViewById(R.id.installations_page_label);
        prevPageButton = root.findViewById(R.id.installations_prev_page);
        nextPageButton = root.findViewById(R.id.installations_next_page);
        root.findViewById(R.id.installations_browser_cancel).setOnClickListener(v -> cancelBrowserCheck());

        sourceWarning.setText(getString(R.string.installations_source_warning, source.displayHost()));
        refreshButton.setOnClickListener(v -> loadVersions());
        stagedRetry.setOnClickListener(v -> retryStagedImport());
        stagedClear.setOnClickListener(v -> confirmClearStaged());
        prevPageButton.setOnClickListener(v -> goToPreviousPage());
        nextPageButton.setOnClickListener(v -> goToNextPage());
        DynamicAnim.applyPressScale(refreshButton);
        DynamicAnim.applyPressScale(stagedRetry);
        DynamicAnim.applyPressScale(stagedClear);
        DynamicAnim.applyPressScale(prevPageButton);
        DynamicAnim.applyPressScale(nextPageButton);
    }

    /**
     * Shows the WebView so the user can clear a browser check.
     *
     * Only reached when a plain request was refused; the browser keeps polling in the
     * background and the flow continues once the check clears.
     */
    private void showBrowser(int hintRes) {
        browserHint.setText(hintRes);
        browserContainer.setVisibility(View.VISIBLE);
        browserContainer.bringToFront();
    }

    private void hideBrowser() {
        browserContainer.setVisibility(View.GONE);
    }

    /**
     * Abandons the browser check.
     *
     * Reloading here would re-request the listing and immediately re-open the check, so the
     * screen settles on an explanatory message instead and the user can press Refresh.
     */
    private void cancelBrowserCheck() {
        hideBrowser();
        if (fallback != null) fallback.release();
        showLoading(false);
        showEmpty(getString(R.string.installations_error_title),
                getString(R.string.installations_challenge_cancelled));
    }

    @Override
    public void onDestroyView() {
        if (fallback != null) fallback.release();
        if (browser != null) browser.destroy();
        // The import manager owns a single-thread pool; without this it outlived the Fragment.
        if (importManager != null) {
            importManager.release();
            importManager = null;
        }
        super.onDestroyView();
    }

    private void setupRecycler() {
        adapter = new InstallationAdapter();
        adapter.setOnDownloadListener(this::confirmAndDownload);
        recycler.setLayoutManager(new LinearLayoutManager(ctx()));
        recycler.setAdapter(adapter);
    }

    /**
     * Reloads the page currently on screen, without changing which page that is.
     *
     * Used on create and after the browser check: the walk is restored, so resuming must not
     * silently jump the reader back to the newest releases.
     */
    private void loadCurrentPage() {
        loadPage(pager.currentPageUrl());
    }

    /**
     * Loads the first listing page, resetting any paging history.
     *
     * Refresh therefore returns to page 1 with the newest releases, which is what a user who
     * pressed Refresh after a failure expects; "Next" is what moves into the archive.
     */
    private void loadVersions() {
        pager.reset();
        loadCurrentPage();
    }

    /** Loads one listing page and lays out its rows. */
    private void loadPage(String pageUrl) {
        showLoading(true);
        client.fetchVersions(source, fallback, pageUrl, new PackageSourceClient.ListingCallback() {
            @Override
            public void onSuccess(List<Version> fetched, String nextUrl) {
                if (gone()) return;
                showLoading(false);
                hideBrowser();
                resolvedDownloads.clear();
                versions.clear();
                versions.addAll(fetched);
                adapter.setVersions(versions);
                markInstalledVersions();
                pager.onPageLoaded(nextUrl);
                emptyContainer.setVisibility(versions.isEmpty() ? View.VISIBLE : View.GONE);
                recycler.setVisibility(versions.isEmpty() ? View.GONE : View.VISIBLE);
                updatePagination();
            }

            @Override
            public void onChallenge() {
                showBrowser(R.string.installations_challenge_hint);
            }

            @Override
            public void onError(Throwable error) {
                if (gone()) return;
                showLoading(false);
                hideBrowser();
                showEmpty(getString(R.string.installations_error_title), describe(error));
            }
        });
    }

    /** Moves to the following listing page. */
    private void goToNextPage() {
        if (pager == null) return;
        String target = pager.advance();
        if (target == null) return;
        loadPage(target);
    }

    /** Returns to the listing page before this one. */
    private void goToPreviousPage() {
        if (pager == null) return;
        String target = pager.retreat();
        if (target == null) return;
        loadPage(target);
    }

    /** Shows the page controls and reflects whether Back and Next are available. */
    private void updatePagination() {
        if (paginationContainer == null || pager == null) return;
        paginationContainer.setVisibility(View.VISIBLE);
        pageLabel.setText(getString(R.string.installations_page_label, pager.pageNumber()));

        boolean hasPrevious = pager.hasPrevious();
        prevPageButton.setEnabled(hasPrevious);
        prevPageButton.setAlpha(hasPrevious ? 1f : 0.4f);

        boolean hasNext = pager.hasNext();
        nextPageButton.setEnabled(hasNext);
        nextPageButton.setAlpha(hasNext ? 1f : 0.4f);
    }

    /** Flags the rows whose version already exists as an instance. */
    private void markInstalledVersions() {
        for (Version version : versions) {
            adapter.markInstalled(version.versionCode, isInstalled(version.versionCode));
        }
    }

    private boolean isInstalled(String versionCode) {
        if (versionCode == null || versionCode.isEmpty()) return false;
        return matches(versionManager.getInstalledVersions(), versionCode)
                || matches(versionManager.getCustomVersions(), versionCode);
    }

    private boolean matches(List<GameVersion> installed, String versionCode) {
        if (installed == null) return false;
        for (GameVersion version : installed) {
            if (versionCode.equals(version.versionCode)) return true;
            if (version.directoryName != null && version.directoryName.contains(versionCode)) return true;
        }
        return false;
    }

    private void confirmAndDownload(Version version) {
        if (version == null) return;
        if (adapter.stateOf(version.pageUrl) == InstallationAdapter.State.DOWNLOADING) {
            Toast.makeText(ctx(), R.string.installations_download_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }

        new CustomAlertDialog(ctx())
                .setTitleText(getString(R.string.installations_confirm_title, version.displayLabel()))
                .setMessage(getString(R.string.installations_confirm_message))
                .setPositiveButton(getString(R.string.installations_download),
                        v -> resolveAndDownload(version))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void resolveAndDownload(Version version) {
        adapter.setState(version.pageUrl, InstallationAdapter.State.DOWNLOADING);
        adapter.setProgress(version.pageUrl, 0);

        ResolvedDownload cached = resolvedDownloads.get(version.pageUrl);
        if (cached != null) {
            startDownload(version, cached);
            return;
        }

        client.resolveDownload(source, fallback, version.pageUrl,
                new PackageSourceClient.DownloadLinkCallback() {
                    @Override
                    public void onSuccess(ResolvedDownload download) {
                        if (gone()) return;
                        resolvedDownloads.put(version.pageUrl, download);
                        startDownload(version, download);
                    }

                    @Override
                    public void onChallenge() {
                        // The browser keeps polling, so clearing the check resumes this request.
                        showBrowser(R.string.installations_challenge_hint);
                    }

                    @Override
                    public void onError(Throwable error) {
                        if (gone()) return;
                        adapter.setError(version.pageUrl, describe(error));
                    }
                });
    }

    private void startDownload(Version version, ResolvedDownload download) {
        pendingVersionName = uniqueVersionName(versionNameFor(version));
        pendingPageUrl = version.pageUrl;

        client.downloadPackage(source, fallback, download, name -> {
                    // The real name is only known once the response headers arrive, so the
                    // destination is chosen here rather than before the request.
                    File destination = store.newFile(name);
                    pendingFile = destination;
                    return destination;
                },
                percent -> adapter.setProgress(version.pageUrl, percent),
                new PackageSourceClient.FileDownloadCallback() {
                    @Override
                    public void onSuccess(File file) {
                        if (gone()) return;
                        pendingFile = file;
                        adapter.setState(version.pageUrl, InstallationAdapter.State.IMPORTING);
                        refreshStagedPackages();
                        importManager.importUri(Uri.fromFile(file), pendingVersionName);
                    }

                    @Override
                    public void onError(Throwable error) {
                        if (gone()) return;
                        // A partial file would be imported as a corrupt package, so drop it.
                        if (pendingFile != null) store.delete(pendingFile);
                        pendingFile = null;
                        pendingPageUrl = null;
                        adapter.setError(version.pageUrl, describe(error));
                        refreshStagedPackages();
                    }
                });
    }

    private void onImportSucceeded() {
        // Only a completed import may remove the package; that ordering is the point of staging.
        boolean deleted = pendingFile != null && store.delete(pendingFile);
        // The row was moved to IMPORTING when the download finished. Nothing moves it back, so
        // without this the action button keeps saying "Importing" forever even though the
        // instance is installed. Return it to IDLE first, then let markInstalledVersions flip it
        // to the "Reinstall" label.
        if (pendingPageUrl != null) {
            adapter.setState(pendingPageUrl, InstallationAdapter.State.IDLE);
        }
        pendingFile = null;
        pendingVersionName = null;
        pendingPageUrl = null;
        resolvedDownloads.clear();
        refreshStagedPackages();
        markInstalledVersions();
        Toast.makeText(ctx(),
                deleted ? R.string.installations_cleanup_done : R.string.installations_cleanup_skipped,
                Toast.LENGTH_SHORT).show();
    }

    private void onImportFailed(String message, String versionName) {
        // The package is kept on purpose so the user can retry without downloading again.
        pendingVersionName = versionName;
        refreshStagedPackages();
        if (pendingPageUrl != null) {
            adapter.setError(pendingPageUrl, message);
        }
        new CustomAlertDialog(ctx())
                .setTitleText(getString(R.string.installations_import_failed_title))
                .setMessage(getString(R.string.installations_import_failed_message, message))
                .setPositiveButton(getString(R.string.installations_retry_import),
                        v -> retryStagedImport())
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    /**
     * Re-runs the import on the staged package without downloading it again.
     *
     * The file on disk is preferred over the remembered one because the activity may have
     * been recreated; whatever is actually staged is what the pipeline should read.
     */
    private void retryStagedImport() {
        File file = pendingFile != null && pendingFile.isFile() ? pendingFile : null;
        if (file == null) {
            List<File> staged = store.listPackages();
            file = staged.isEmpty() ? null : staged.get(0);
        }
        if (file == null) {
            Toast.makeText(ctx(), R.string.installations_nothing_staged, Toast.LENGTH_LONG).show();
            refreshStagedPackages();
            return;
        }

        pendingFile = file;
        if (pendingVersionName == null || pendingVersionName.isEmpty()) {
            pendingVersionName = BedrockSource.versionNameFrom(file.getName());
        }
        String pageUrl = pendingPageUrl != null ? pendingPageUrl : pageUrlFor(file.getName());
        pendingPageUrl = pageUrl;
        if (pageUrl != null) {
            adapter.setState(pageUrl, InstallationAdapter.State.IMPORTING);
        }
        importManager.importUri(Uri.fromFile(file), pendingVersionName);
    }

    /** Finds the row whose version code appears in a staged file's name. */
    private String pageUrlFor(String fileName) {
        String code = BedrockSource.versionNameFrom(fileName);
        if (code == null || "unknown".equals(code)) return null;
        for (Version version : versions) {
            if (code.equals(version.versionCode)) return version.pageUrl;
        }
        return null;
    }

    private void confirmClearStaged() {
        new CustomAlertDialog(ctx())
                .setTitleText(getString(R.string.installations_clear_staged_title))
                .setMessage(getString(R.string.installations_clear_staged_message))
                .setPositiveButton(getString(R.string.installations_clear_staged), v -> {
                    int deleted = store.deleteAll();
                    pendingFile = null;
                    pendingVersionName = null;
                    pendingPageUrl = null;
                    refreshStagedPackages();
                    Toast.makeText(ctx(), getString(R.string.installations_cleared, deleted),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void refreshStagedPackages() {
        List<File> staged = store.listPackages();
        if (staged.isEmpty()) {
            stagedContainer.setVisibility(View.GONE);
            return;
        }
        stagedContainer.setVisibility(View.VISIBLE);
        long bytes = 0;
        for (File file : staged) bytes += file.length();
        stagedSummary.setText(getString(R.string.installations_staged_summary,
                staged.size(), formatSize(bytes)));
    }

    private void showLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            emptyContainer.setVisibility(View.GONE);
            recycler.setVisibility(View.GONE);
        }
    }

    private void showEmpty(String title, String message) {
        emptyTitle.setText(title);
        emptyMessage.setText(message);
        emptyContainer.setVisibility(View.VISIBLE);
        recycler.setVisibility(View.GONE);
    }

    /**
     * Picks an instance name that no existing instance uses.
     *
     * The import pipeline deletes the target directory before extracting, so importing onto
     * an existing name would silently destroy that instance. A suffix keeps both, which is
     * also what makes "Reinstall" create a second copy rather than overwrite the first.
     */
    private String uniqueVersionName(String base) {
        String name = base == null || base.isEmpty() ? "unknown" : base;
        File root = LauncherStorage.getMinecraftRoot(ctx());
        if (!new File(root, name).exists()) return name;
        for (int i = 2; i < 100; i++) {
            String candidate = name + "_" + i;
            if (!new File(root, candidate).exists()) return candidate;
        }
        return name + "_" + System.currentTimeMillis();
    }

    private static String versionNameFor(Version version) {
        String code = version.versionCode;
        if (code == null || code.isEmpty()) {
            code = BedrockSource.stripTags(version.title);
        }
        return BedrockSource.versionNameFrom(code);
    }

    /**
     * The cause-specific reason a listing, download or import failed.
     *
     * <p>Before this the row and the empty state showed the raw exception message — "Unable to
     * resolve host \"mcpedl.org\"" — which names neither the problem nor the fix. The classifier
     * turns it into one actionable sentence and keeps the raw text beneath it for a bug report.
     */
    private String describe(Throwable error) {
        if (error == null) return getString(R.string.error_reason_unknown);
        return UserFacingError.describe(error, getString(UserFacingError.messageRes(
                UserFacingError.classify(error))));
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}
