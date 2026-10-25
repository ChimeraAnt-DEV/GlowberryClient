package org.chimeramc.client.core.replay;

import android.app.Activity;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.chimeramc.client.R;
import org.chimeramc.client.ui.animation.DynamicAnim;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Replay section, hosted by the one Mod Menu (and by the launcher's Replay screen).
 *
 * <p>One panel is used by both screens so the data, controls and polish are identical — the spec
 * requires the two tabs to be the same feature. It is built in code because everything on it is
 * live: the recorder state, the library, the storage meter. The screens only host the view.
 *
 * <p>What it does, matching the feature tiers:
 * <ul>
 *   <li><b>Capture</b> — start/stop, elapsed timer, quality badge, the clip-length limit and the
 *       highlight-trigger switches.</li>
 *   <li><b>Review</b> — the clip grid with real thumbnails, play (in an embedded in-overlay
 *       player), rename, delete, favorite, sort and filter.</li>
 *   <li><b>Export</b> — trim to a window and export to the gallery, both real file operations.</li>
 * </ul>
 *
 * <p><b>Honest limits.</b> Highlight capture flags the clip being recorded; this build has no ring
 * buffer of encoded frames to rewind, and the scope note in the settings says so. Playback is
 * embedded in this overlay so watching never leaves the game; the recorder is paused while a clip
 * plays so the two do not share the decoder.
 */
public final class ReplayPanel {

    private final Activity activity;
    private final ReplayStyle style;
    private final boolean compact;
    private final ReplayClipRepository repository;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "replay-panel-io");
        thread.setDaemon(true);
        return thread;
    });

    private final LinearLayout root;
    /** Holds the library and, when a clip is playing, the embedded player over it. */
    private final FrameLayout host;
    /** The library column, hidden while the player is shown. */
    private final LinearLayout libraryContent;
    private final LinearLayout grid;
    private final TextView emptyTitle;
    private final TextView emptyMessage;
    private final ReplayEmptyArtView emptyArt;
    private final ReplaySkeletonView skeleton;
    private TextView recordButton;
    private TextView timer;
    private TextView qualityBadge;
    private TextView storageText;
    private View storageBar;
    private View storageBarFill;
    private TextView clipCount;
    private TextView filterAll;
    private TextView filterFavorites;
    private TextView filterHighlights;
    private TextView sortButton;
    private TextView stitchButton;

    private final List<TextView> filterChips = new ArrayList<>();
    private final List<ClipCardView> cards = new ArrayList<>();

    private ReplayLibrary.Filter filter = ReplayLibrary.Filter.ALL;
    private ReplayLibrary.Sort sort;
    private boolean loading;
    private ReplayClip selected;
    private final List<ReplayClip> lastFiltered = new ArrayList<>();
    private boolean gamepadDetected;
    private TextView hintView;
    private ReplayPlayerView playerView;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ReplayManager.Listener stateListener = (state, elapsed, profile) ->
            renderRecordingState(state, elapsed, profile);
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ReplayManager manager = ReplayManager.get();
            if (manager != null && manager.isRecording()) {
                timer.setText(ReplayFormat.duration(manager.elapsedMs()));
                mainHandler.postDelayed(this, 500L);
            }
        }
    };

    public ReplayPanel(Activity activity, boolean compact) {
        this.activity = activity;
        this.compact = compact;
        this.style = new ReplayStyle(activity);
        this.repository = new ReplayClipRepository(activity);
        this.sort = ReplaySettings.get(activity).sort();

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(style.canvas());
        root.setPadding(ReplayStyle.dpInt(activity, compact ? 10 : 16),
                ReplayStyle.dpInt(activity, compact ? 8 : 12),
                ReplayStyle.dpInt(activity, compact ? 10 : 16),
                ReplayStyle.dpInt(activity, compact ? 8 : 12));

        root.addView(buildHeader());
        root.addView(buildStorageMeter());

        root.addView(buildRecordRow());

        root.addView(buildLibraryHeader());

        // Content: the library column and, when a clip plays, the embedded player over it. The
        // player is a sibling of the library in this frame, not a child of the column, so it can
        // cover the whole section without being squeezed into leftover space.
        host = new FrameLayout(activity);

        libraryContent = new LinearLayout(activity);
        libraryContent.setOrientation(LinearLayout.VERTICAL);

        FrameLayout contentFrame = new FrameLayout(activity);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentParams.topMargin = ReplayStyle.dpInt(activity, 8);

        ScrollView scroll = new ScrollView(activity);
        scroll.setClipToPadding(false);
        grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(grid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentFrame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        skeleton = new ReplaySkeletonView(activity);
        skeleton.setVisibility(View.GONE);
        contentFrame.addView(skeleton, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout empty = new LinearLayout(activity);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        emptyArt = new ReplayEmptyArtView(activity);
        empty.addView(emptyArt, new LinearLayout.LayoutParams(
                ReplayStyle.dpInt(activity, 140), ReplayStyle.dpInt(activity, 110)));
        emptyTitle = new TextView(activity);
        emptyTitle.setText(R.string.replay_empty_title);
        emptyTitle.setTextColor(style.textPrimary());
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        emptyTitle.setTypeface(null, Typeface.BOLD);
        emptyTitle.setGravity(Gravity.CENTER);
        empty.addView(emptyTitle, topMarginParams(activity, 10));
        emptyMessage = new TextView(activity);
        emptyMessage.setText(R.string.replay_empty_message);
        emptyMessage.setTextColor(style.textTertiary());
        emptyMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        emptyMessage.setGravity(Gravity.CENTER);
        emptyMessage.setMaxWidth(ReplayStyle.dpInt(activity, 320));
        empty.addView(emptyMessage, topMarginParams(activity, 6));
        contentFrame.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        libraryContent.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        host.addView(libraryContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(host, contentParams);

        root.addView(buildHintStrip());
    }

    public View getView() {
        return root;
    }

    /** Called by the host when the section becomes visible; starts the live refresh. */
    public void onShown() {
        ReplayManager manager = ReplayManager.get();
        if (manager != null) {
            manager.addListener(stateListener);
            renderRecordingState(manager.state(), manager.elapsedMs(), manager.profile());
        }
        refreshLibrary();
    }

    /** Called by the host when the section is hidden; stops the live refresh and any playback. */
    public void onHidden() {
        ReplayManager manager = ReplayManager.get();
        if (manager != null) manager.removeListener(stateListener);
        mainHandler.removeCallbacks(tick);
        // Never leave a decode session running behind a closed menu: it competes with the game
        // for the same hardware and would keep the ReplayPlaybackGate latched.
        closePlayer();
    }

    /** Releases the panel's background executor when the host discards it. */
    public void dispose() {
        onHidden();
        io.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------
    // Header, storage, record
    // ---------------------------------------------------------------------------------------------

    private View buildHeader() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(activity);
        title.setText(R.string.replay_header_title);
        title.setTextColor(style.textPrimary());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        title.setTypeface(null, Typeface.BOLD);
        title.setLetterSpacing(0.01f);
        titles.addView(title);

        TextView subtitle = new TextView(activity);
        subtitle.setText(R.string.replay_header_subtitle);
        subtitle.setTextColor(style.textTertiary());
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        titles.addView(subtitle);

        row.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        qualityBadge = new TextView(activity);
        qualityBadge.setTextColor(style.textSecondary());
        qualityBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        qualityBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        qualityBadge.setBackground(ReplayStyle.pill(style.surfaceElevated(), activity));
        qualityBadge.setPadding(ReplayStyle.dpInt(activity, 10), ReplayStyle.dpInt(activity, 4),
                ReplayStyle.dpInt(activity, 10), ReplayStyle.dpInt(activity, 4));
        row.addView(qualityBadge);

        ImageView settings = new ImageView(activity);
        // A vector gear, not the emoji glyph: the spec bans emoji icons, and the emoji renders
        // differently on every device font.
        settings.setImageResource(R.drawable.ic_settings);
        settings.setColorFilter(style.textSecondary());
        settings.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        settings.setBackground(ReplayStyle.rippled(
                ReplayStyle.rounded(style.surfaceElevated(), 10f, activity),
                style.accentFill(60), activity));
        settings.setPadding(ReplayStyle.dpInt(activity, 9), ReplayStyle.dpInt(activity, 4),
                ReplayStyle.dpInt(activity, 9), ReplayStyle.dpInt(activity, 4));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        settingsParams.setMarginStart(ReplayStyle.dpInt(activity, 10));
        settings.setOnClickListener(v -> ReplaySettingsDialog.show(activity, style, this::refreshLibrary));
        DynamicAnim.applyPressScale(settings);
        row.addView(settings, settingsParams);

        return row;
    }

    private View buildStorageMeter() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ReplayStyle.dpInt(activity, 12);

        storageText = new TextView(activity);
        storageText.setTextColor(style.textSecondary());
        storageText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        column.addView(storageText);

        FrameLayout track = new FrameLayout(activity);
        track.setBackground(ReplayStyle.rounded(style.surfaceElevated(), 4f, activity));
        storageBarFill = new View(activity);
        storageBarFill.setBackground(ReplayStyle.rounded(style.accent(), 4f, activity));
        track.addView(storageBarFill, new FrameLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ReplayStyle.dpInt(activity, 5f));
        trackParams.topMargin = ReplayStyle.dpInt(activity, 5);
        column.addView(track, trackParams);

        storageBar = track;
        column.setLayoutParams(params);
        return column;
    }

    private View buildRecordRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = ReplayStyle.dpInt(activity, 12);
        row.setLayoutParams(rowParams);

        recordButton = buildRecordButton();
        row.addView(recordButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        timer = new TextView(activity);
        timer.setText(ReplayFormat.duration(0));
        timer.setTextColor(style.textSecondary());
        timer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        timer.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        timer.setGravity(Gravity.CENTER);
        timer.setMinWidth(ReplayStyle.dpInt(activity, 72f));
        timer.setBackground(ReplayStyle.rounded(style.surfaceElevated(), 12f, activity));
        timer.setPadding(ReplayStyle.dpInt(activity, 10), ReplayStyle.dpInt(activity, 11),
                ReplayStyle.dpInt(activity, 10), ReplayStyle.dpInt(activity, 11));
        LinearLayout.LayoutParams timerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timerParams.setMarginStart(ReplayStyle.dpInt(activity, 10));
        row.addView(timer, timerParams);
        return row;
    }

    private TextView buildRecordButton() {
        TextView button = new TextView(activity);
        button.setText(R.string.replay_record_start);
        button.setTextColor(style.textPrimary());
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(ReplayStyle.dpInt(activity, 44f));
        button.setBackground(ReplayStyle.rippled(
                ReplayStyle.verticalGradient(style.accentFill(200), style.secondaryFill(200),
                        14f, activity), 0x40FFFFFF, activity));
        button.setOnClickListener(v -> toggleRecording());
        DynamicAnim.applyPressScale(button);
        return button;
    }

    private View buildLibraryHeader() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        columnParams.topMargin = ReplayStyle.dpInt(activity, 16);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(activity);
        label.setText(R.string.replay_header_title);
        label.setTextColor(style.textSecondary());
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setTypeface(null, Typeface.BOLD);
        label.setLetterSpacing(0.08f);
        label.setVisibility(View.GONE);
        row.addView(label);

        clipCount = new TextView(activity);
        clipCount.setTextColor(style.textTertiary());
        clipCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        row.addView(clipCount, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sortButton = new TextView(activity);
        sortButton.setTextColor(style.textSecondary());
        sortButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        sortButton.setBackground(ReplayStyle.rippled(
                ReplayStyle.rounded(style.surfaceElevated(), 10f, activity),
                style.accentFill(60), activity));
        sortButton.setPadding(ReplayStyle.dpInt(activity, 10), ReplayStyle.dpInt(activity, 5),
                ReplayStyle.dpInt(activity, 10), ReplayStyle.dpInt(activity, 5));
        sortButton.setOnClickListener(v -> cycleSort());
        DynamicAnim.applyPressScale(sortButton);
        row.addView(sortButton);

        stitchButton = new TextView(activity);
        stitchButton.setText(R.string.replay_action_stitch);
        stitchButton.setTextColor(style.textSecondary());
        stitchButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        stitchButton.setBackground(ReplayStyle.rippled(
                ReplayStyle.rounded(style.surfaceElevated(), 10f, activity),
                style.accentFill(60), activity));
        stitchButton.setPadding(ReplayStyle.dpInt(activity, 10), ReplayStyle.dpInt(activity, 5),
                ReplayStyle.dpInt(activity, 10), ReplayStyle.dpInt(activity, 5));
        LinearLayout.LayoutParams stitchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stitchParams.setMarginStart(ReplayStyle.dpInt(activity, 6));
        stitchButton.setLayoutParams(stitchParams);
        stitchButton.setOnClickListener(v -> stitchFavorites());
        DynamicAnim.applyPressScale(stitchButton);
        row.addView(stitchButton);
        column.addView(row);

        LinearLayout chips = new LinearLayout(activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        filterAll = chip(activity, R.string.replay_filter_all, ReplayLibrary.Filter.ALL);
        filterFavorites = chip(activity, R.string.replay_filter_favorites,
                ReplayLibrary.Filter.FAVORITES);
        filterHighlights = chip(activity, R.string.replay_filter_highlights,
                ReplayLibrary.Filter.HIGHLIGHTS);
        chips.addView(filterAll);
        chips.addView(filterFavorites);
        chips.addView(filterHighlights);
        LinearLayout.LayoutParams chipsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsParams.topMargin = ReplayStyle.dpInt(activity, 8);
        column.addView(chips, chipsParams);

        column.setLayoutParams(columnParams);
        return column;
    }

    private TextView chip(Activity activity, int textRes, ReplayLibrary.Filter value) {
        TextView chip = new TextView(activity);
        chip.setText(textRes);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        chip.setTypeface(null, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(ReplayStyle.dpInt(activity, 12), ReplayStyle.dpInt(activity, 5),
                ReplayStyle.dpInt(activity, 12), ReplayStyle.dpInt(activity, 5));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(ReplayStyle.dpInt(activity, 6));
        chip.setLayoutParams(params);
        chip.setOnClickListener(v -> {
            filter = value;
            renderChips();
            renderLibrary();
        });
        DynamicAnim.applyPressScale(chip);
        chip.setTag(value);
        filterChips.add(chip);
        return chip;
    }

    private View buildHintStrip() {
        hintView = new TextView(activity);
        hintView.setTextColor(style.textTertiary());
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        hintView.setGravity(Gravity.CENTER);
        hintView.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ReplayStyle.dpInt(activity, 8);
        hintView.setLayoutParams(params);
        renderHint();
        return hintView;
    }

    /**
     * Selects which bind hint is shown.
     *
     * <p>The hint names real bindings, so it follows the detected input rather than the screen: a
     * controller hint on a keyboard-only player (or the reverse) would teach buttons that do
     * nothing.
     */
    public void setGamepadDetected(boolean detected) {
        if (gamepadDetected == detected) return;
        gamepadDetected = detected;
        renderHint();
    }

    private void renderHint() {
        if (hintView == null) return;
        hintView.setText(gamepadDetected
                ? R.string.replay_controller_hint : R.string.replay_keyboard_hint);
    }

    private void renderChips() {
        for (TextView chip : filterChips) {
            boolean selected = chip.getTag() == filter;
            chip.setTextColor(selected ? style.textPrimary() : style.textTertiary());
            chip.setBackground(selected
                    ? ReplayStyle.rounded(style.accentFill(70), 20f, activity)
                    : ReplayStyle.rounded(style.surfaceElevated(), 20f, activity));
        }
        renderSortLabel();
    }

    private void renderSortLabel() {
        sortButton.setText(activity.getString(R.string.replay_sort_label) + ": "
                + activity.getString(sortLabelRes(sort)));
    }

    /** The label for a sort mode. Static and pure so the mapping is obvious and testable. */
    static int sortLabelRes(ReplayLibrary.Sort sort) {
        if (sort == null) return R.string.replay_sort_date_newest;
        switch (sort) {
            case DATE_OLDEST:
                return R.string.replay_sort_date_oldest;
            case DURATION_LONGEST:
                return R.string.replay_sort_duration_longest;
            case DURATION_SHORTEST:
                return R.string.replay_sort_duration_shortest;
            case SIZE_LARGEST:
                return R.string.replay_sort_size_largest;
            case SIZE_SMALLEST:
                return R.string.replay_sort_size_smallest;
            case DATE_NEWEST:
            default:
                return R.string.replay_sort_date_newest;
        }
    }

    private void cycleSort() {
        ReplayLibrary.Sort[] values = ReplayLibrary.Sort.values();
        sort = values[(sort.ordinal() + 1) % values.length];
        ReplaySettings.get(activity).setSort(sort);
        renderSortLabel();
        renderLibrary();
    }

    // ---------------------------------------------------------------------------------------------
    // Recording state
    // ---------------------------------------------------------------------------------------------

    private void toggleRecording() {
        ReplayManager manager = ReplayManager.get();
        if (manager == null) return;
        if (manager.isRecording()) {
            manager.stop();
            return;
        }
        manager.requestStart(activity);
    }

    private void renderRecordingState(ReplayManager.State state, long elapsedMs,
                                      ReplayQuality.Profile profile) {
        boolean recording = state == ReplayManager.State.RECORDING;
        if (recording) {
            recordButton.setText(R.string.replay_record_stop);
            recordButton.setBackground(ReplayStyle.rippled(
                    ReplayStyle.verticalGradient(style.statusRecord(), style.statusRecord(),
                            14f, activity), 0x40FFFFFF, activity));
            mainHandler.removeCallbacks(tick);
            mainHandler.post(tick);
        } else {
            recordButton.setText(R.string.replay_record_start);
            recordButton.setBackground(ReplayStyle.rippled(
                    ReplayStyle.verticalGradient(style.accentFill(200), style.secondaryFill(200),
                            14f, activity), 0x40FFFFFF, activity));
            mainHandler.removeCallbacks(tick);
        }
        ReplayQuality.Profile resolved = profile;
        if (resolved == null) {
            resolved = ReplayQuality.select(ReplaySettings.get(activity).forceLowQuality());
        }
        qualityBadge.setText(resolved.label());
    }

    // ---------------------------------------------------------------------------------------------
    // Library
    // ---------------------------------------------------------------------------------------------

    /** Rescans the library off the UI thread and repaints. */
    public void refreshLibrary() {
        if (loading) return;
        loading = true;
        skeleton.setVisibility(View.VISIBLE);
        io.execute(() -> {
            final List<ReplayClip> scanned = repository.scan();
            final long usage = ReplayStorage.usageBytes(activity);
            mainHandler.post(() -> {
                loading = false;
                skeleton.setVisibility(View.GONE);
                renderStorage(usage);
                renderLibrary(scanned);
            });
        });
    }

    private void renderStorage(long usage) {
        long cap = ReplaySettings.get(activity).storageCapBytes();
        ReplayStoragePolicy.Level level = ReplayStoragePolicy.level(usage, cap);
        storageText.setText(activity.getString(R.string.replay_storage_usage,
                ReplayFormat.size(usage), ReplayFormat.size(cap)));
        int fillColor;
        switch (level) {
            case OVER:
                fillColor = style.statusRecord();
                storageText.setText(R.string.replay_storage_over);
                break;
            case WARN:
                fillColor = style.statusWarn();
                storageText.setText(R.string.replay_storage_warning);
                break;
            case OK:
            default:
                fillColor = style.accent();
                break;
        }
        storageBarFill.setBackground(ReplayStyle.rounded(fillColor, 4f, activity));
        int percent = ReplayFormat.storagePercent(usage, cap);
        storageBar.post(() -> {
            int width = Math.round(storageBar.getWidth() * (percent / 100f));
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) storageBarFill.getLayoutParams();
            params.width = width;
            storageBarFill.setLayoutParams(params);
        });
    }

    private void renderLibrary() {
        io.execute(() -> {
            final List<ReplayClip> scanned = repository.scan();
            final long usage = ReplayStorage.usageBytes(activity);
            mainHandler.post(() -> {
                renderStorage(usage);
                renderLibrary(scanned);
            });
        });
    }

    private void renderLibrary(List<ReplayClip> all) {
        List<ReplayClip> filtered = repository.filtered(all, filter, sort);
        lastFiltered.clear();
        lastFiltered.addAll(filtered);
        clipCount.setText(activity.getString(R.string.replay_clip_count, filtered.size()));
        renderChips();

        grid.removeAllViews();
        cards.clear();
        boolean isEmpty = filtered.isEmpty();
        emptyArt.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        emptyTitle.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        emptyMessage.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        if (isEmpty) {
            boolean filteredOut = !all.isEmpty();
            emptyTitle.setText(filteredOut
                    ? R.string.replay_empty_filter_title : R.string.replay_empty_title);
            emptyMessage.setText(filteredOut
                    ? R.string.replay_empty_filter_message : R.string.replay_empty_message);
            return;
        }

        // Rows of cards; 3 across when there is room, 2 when compact.
        int columns = compact ? 2 : 3;
        LinearLayout row = null;
        for (int i = 0; i < filtered.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowParams.topMargin = ReplayStyle.dpInt(activity, 10);
                grid.addView(row, rowParams);
            }
            ClipCardView card = new ClipCardView(activity, style);
            final ReplayClip clip = filtered.get(i);
            card.setClip(clip);
            card.setOnOpen(() -> {
                selected = clip;
                highlightSelection();
                openClip(clip);
            });
            card.setOnFavorite(() -> {
                selected = clip;
                toggleFavorite(clip);
            });
            card.setOnLongPress(() -> {
                selected = clip;
                highlightSelection();
                showClipActions(clip);
            });
            card.setScrubListener(new ClipCardView.ScrubListener() {
                @Override
                public void onScrubStart(ReplayClip c) {
                    // A scrub is a preview, not a selection; it must not disturb the selected card.
                }

                @Override
                public void onScrub(ReplayClip c, float fraction) {
                    io.execute(() -> {
                        final android.graphics.Bitmap frame = ReplayThumbnails.frameAt(c, fraction);
                        if (frame == null) return;
                        mainHandler.post(() -> {
                            // The card may have been recycled onto another clip while the frame
                            // decoded; only apply it if it still holds the scrubbed clip.
                            if (card.clip() == c) card.setScrubPreview(frame);
                        });
                    });
                }

                @Override
                public void onScrubEnd(ReplayClip c) {
                    card.clearScrubPreview();
                }
            });
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % columns != 0) cardParams.setMarginStart(ReplayStyle.dpInt(activity, 10));
            row.addView(card, cardParams);
            cards.add(card);
            loadThumbnail(card, clip);
        }
        // Keep the last row's cards the same width as a full row.
        if (row != null) {
            int remainder = filtered.size() % columns;
            if (remainder != 0) {
                for (int i = 0; i < columns - remainder; i++) {
                    View spacer = new View(activity);
                    LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                            0, 1, 1f);
                    spacerParams.setMarginStart(ReplayStyle.dpInt(activity, 10));
                    row.addView(spacer, spacerParams);
                }
            }
        }
    }

    private void loadThumbnail(ClipCardView card, ReplayClip clip) {
        android.graphics.Bitmap cached = ReplayThumbnails.cached(clip);
        if (cached != null) {
            card.setThumbnail(cached);
            return;
        }
        io.execute(() -> {
            final android.graphics.Bitmap bitmap = ReplayThumbnails.load(clip);
            if (bitmap == null) return;
            mainHandler.post(() -> {
                // The card may have been recycled into a different clip; only apply if it still
                // holds the clip this frame was decoded for.
                if (card.clip() == clip) card.setThumbnail(bitmap);
            });
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Clip actions
    // ---------------------------------------------------------------------------------------------

    /** The touch action sheet for a clip: play, trim, export, favorite, rename, delete. */
    private void showClipActions(ReplayClip clip) {
        ReplayClipActionsDialog.showActions(activity, style, clip,
                new ReplayClipActionsDialog.Actions() {
                    @Override
                    public void onPlay(ReplayClip c) {
                        openClip(c);
                    }

                    @Override
                    public void onTrim(ReplayClip c) {
                        trimClip(c);
                    }

                    @Override
                    public void onExport(ReplayClip c) {
                        exportClip(c);
                    }

                    @Override
                    public void onShare(ReplayClip c) {
                        shareClip(c);
                    }

                    @Override
                    public void onFavorite(ReplayClip c) {
                        toggleFavorite(c);
                    }

                    @Override
                    public void onRename(ReplayClip c) {
                        renameClip(c);
                    }

                    @Override
                    public void onCinematic(ReplayClip c) {
                        openCinematicEditor(c);
                    }

                    @Override
                    public void onDelete(ReplayClip c) {
                        confirmDelete(c);
                    }
                });
    }

    private void trimClip(ReplayClip clip) {
        ReplayTrimDialog.show(activity, style, clip, trim -> io.execute(() -> {
            File result = ReplayExporter.trim(activity, clip, trim);
            mainHandler.post(() -> {
                Toast.makeText(activity, result != null
                        ? R.string.replay_clip_saved : R.string.replay_export_failed,
                        Toast.LENGTH_SHORT).show();
                if (result != null) refreshLibrary();
            });
        }));
    }

    private void exportClip(ReplayClip clip) {
        io.execute(() -> {
            Uri uri = ReplayExporter.exportToGallery(activity, clip);
            mainHandler.post(() -> Toast.makeText(activity, uri != null
                    ? R.string.replay_exported : R.string.replay_export_failed,
                    Toast.LENGTH_SHORT).show());
        });
    }

    /**
     * Opens the system share sheet for a clip.
     *
     * <p>The chooser is launched on the UI thread and the file is handed over as a FileProvider
     * content uri, so a receiving app can read it without any storage permission. Best-effort: a
     * missing receiver is reported by the share helper rather than crashing the overlay.
     */
    private void shareClip(ReplayClip clip) {
        if (clip == null || clip.file() == null || !clip.file().isFile()) {
            Toast.makeText(activity, R.string.replay_share_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        ReplayShareSheet.share(activity, clip);
    }

    /**
     * Stitches the favorite clips into one reel, in the library's current order.
     *
     * <p>The favorites are read from the last scan rather than a fresh disk walk so the reel
     * matches what the grid is showing. Fewer than two usable favorites is a message, not a mux:
     * a "reel" of one clip is just that clip.
     */
    private void stitchFavorites() {
        List<ReplayClip> favorites = new ArrayList<>();
        for (ReplayClip clip : lastFiltered) {
            if (clip.favorite()) favorites.add(clip);
        }
        if (!ReplayStitch.isStitchable(favorites)) {
            Toast.makeText(activity, R.string.replay_stitch_needs_two, Toast.LENGTH_SHORT).show();
            return;
        }
        io.execute(() -> {
            File reel = ReplayExporter.stitch(activity, favorites);
            mainHandler.post(() -> {
                Toast.makeText(activity, reel != null
                        ? R.string.replay_reel_saved : R.string.replay_stitch_failed,
                        Toast.LENGTH_SHORT).show();
                if (reel != null) refreshLibrary();
            });
        });
    }

    private void renameClip(ReplayClip clip) {
        ReplayClipActionsDialog.showRename(activity, style, clip, name -> io.execute(() -> {
            File renamed = repository.rename(clip, name);
            mainHandler.post(() -> {
                if (renamed != null) {
                    ReplayThumbnails.invalidate(clip.file());
                    refreshLibrary();
                } else {
                    Toast.makeText(activity, R.string.replay_rename_failed,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }));
    }

    private void openCinematicEditor(ReplayClip clip) {
        if (clip == null) return;
        ReplayCameraPath existing = clip.cameraPath();
        ReplayCamera camera = new ReplayCamera();
        // Seed the camera from the clip's own duration so a preset spans the whole shot.
        ReplayCinematicDialog.show(activity, style, existing, camera, clip.durationMs(), path -> {
            clip.setCameraPath(path);
            repository.updateSidecar(clip);
            Toast.makeText(activity, R.string.replay_cinematic_saved, Toast.LENGTH_SHORT).show();
            refreshLibrary();
        });
    }

    private void openClip(ReplayClip clip) {
        selected = clip;
        if (clip == null || clip.file() == null || !clip.file().isFile()) {
            Toast.makeText(activity, R.string.replay_playback_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        // Embedded playback: the clip plays inside this overlay window rather than being handed to
        // an external player, so watching never leaves the game.
        if (playerView == null) {
            playerView = new ReplayPlayerView(activity, style, clip, new ReplayPlayerView.Callbacks() {
                @Override
                public void onClose() {
                    closePlayer();
                }

                @Override
                public void onPlaybackStarted() {
                    ReplayPlaybackGate.onPlaybackStarted();
                    // If a capture is running, end it: the encoder and the decoder must not fight
                    // over the same hardware while a clip plays.
                    ReplayManager manager = ReplayManager.get();
                    if (manager != null && manager.isRecording()) manager.stop();
                }

                @Override
                public void onPlaybackEnded() {
                    ReplayPlaybackGate.onPlaybackEnded();
                }
            });
            host.addView(playerView.getView(), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        libraryContent.setVisibility(View.GONE);
        playerView.show(clip);
    }

    /** Stops playback, releases the player and returns to the library grid. */
    private void closePlayer() {
        if (playerView != null) {
            playerView.release();
            host.removeView(playerView.getView());
            playerView = null;
        }
        ReplayPlaybackGate.onPlaybackEnded();
        if (libraryContent != null) libraryContent.setVisibility(View.VISIBLE);
    }

    private void toggleFavorite(ReplayClip clip) {
        clip.setFavorite(!clip.favorite());
        repository.updateSidecar(clip);
        Toast.makeText(activity, clip.favorite()
                ? R.string.replay_favorited_on : R.string.replay_favorited_off,
                Toast.LENGTH_SHORT).show();
        renderLibrary();
    }

    /** Deletes a clip after a confirmation. Public so both screens' controller routes can call it. */
    public void deleteSelected() {
        ReplayClip clip = selected;
        if (clip == null) return;
        confirmDelete(clip);
    }

    /** Toggles the favorite on the selected clip. */
    public void favoriteSelected() {
        if (selected != null) toggleFavorite(selected);
    }

    /** Plays the selected clip, or the first one when nothing is selected yet. */
    public void playSelected() {
        ReplayClip clip = selected;
        if (clip == null && !lastFiltered.isEmpty()) clip = lastFiltered.get(0);
        if (clip != null) openClip(clip);
    }

    /** Deletes the selected clip, or the first one when nothing is selected yet. */
    public void deleteSelectedOrFirst() {
        ReplayClip clip = selected;
        if (clip == null && !lastFiltered.isEmpty()) clip = lastFiltered.get(0);
        if (clip != null) confirmDelete(clip);
    }

    private void confirmDelete(ReplayClip clip) {
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.replay_delete_title)
                .setMessage(activity.getString(R.string.replay_delete_message,
                        clip.displayName()))
                .setNegativeButton(R.string.replay_action_cancel, null)
                .setPositiveButton(R.string.replay_action_delete, (d, w) -> {
                    io.execute(() -> {
                        repository.delete(clip);
                        ReplayThumbnails.invalidate(clip.file());
                        mainHandler.post(() -> {
                            if (selected == clip) selected = null;
                            refreshLibrary();
                        });
                    });
                })
                .show();
    }

    /** Exports the selected clip to the gallery. */
    public void exportSelected() {
        if (selected != null) exportClip(selected);
    }

    /** Opens the trim dialog for the selected clip. */
    public void trimSelected() {
        if (selected != null) trimClip(selected);
    }

    /** Opens the cinematic camera editor for the selected clip. */
    public void cinematicSelected() {
        if (selected != null) openCinematicEditor(selected);
    }

    /** Toggles the detached free camera while a clip is playing. */
    public void toggleFreeCamera() {
        if (playerView != null) playerView.toggleFreeCamera();
    }

    /** True when a clip is open in the embedded player. */
    public boolean isPlayerOpen() {
        return playerView != null;
    }

    /**
     * Toggles playback of the open clip, for the Replay controller route.
     *
     * @return true when a player was open and the toggle applied.
     */
    public boolean togglePlayback() {
        if (playerView == null) return false;
        playerView.togglePlayback();
        return true;
    }

    /** Steps the playback speed of the open clip, for the Replay controller route. */
    public boolean stepPlaybackSpeed() {
        if (playerView == null) return false;
        playerView.stepSpeed();
        return true;
    }

    /** Seeks the open clip by a fraction, for the Replay controller's scrub route. */
    public boolean scrubPlayback(float fraction) {
        if (playerView == null) return false;
        playerView.seekByFraction(fraction);
        return true;
    }

    /** Moves the selection through the filtered list, used by the controller's bumper cycle. */
    public void cycleSelection(boolean forward) {
        List<ReplayClip> filtered = lastFiltered;
        if (filtered.isEmpty()) return;
        int index = selected == null ? -1 : indexOfName(filtered, selected);
        int next = forward ? index + 1 : (index <= 0 ? filtered.size() - 1 : index - 1);
        if (index < 0) next = forward ? 0 : filtered.size() - 1;
        if (next < 0) next = filtered.size() - 1;
        if (next >= filtered.size()) next = 0;
        selected = filtered.get(next);
        highlightSelection();
    }

    /**
     * Performs one {@link ReplayControls.Action}, so every screen shares one implementation.
     *
     * <p>This is the single place an action turns into behaviour. The in-game Mod Menu and the
     * Library's Replay tab both route through it, so a mapping cannot do one thing on one screen
     * and something else on the other. {@link ReplayControls} owns the key→action table; this owns
     * action→behaviour.
     *
     * @return true when the action applied. {@code PLAY_PAUSE} is false when no clip is open, which
     *         is what lets the in-game menu fall through to its own Start binding.
     */
    public boolean handleControl(ReplayControls.Action action) {
        if (action == null) return false;
        switch (action) {
            case SELECT:
                playSelected();
                return true;
            case DELETE:
                deleteSelected();
                return true;
            case FAVORITE:
                favoriteSelected();
                return true;
            case CYCLE_PREV:
                cycleSelection(false);
                return true;
            case CYCLE_NEXT:
                cycleSelection(true);
                return true;
            case PLAY_PAUSE:
                return isPlayerOpen() && togglePlayback();
            case SCRUB_BACK:
                return scrubPlayback(-0.1f);
            case SCRUB_FORWARD:
                return scrubPlayback(0.1f);
            case SPEED:
                return stepPlaybackSpeed();
            default:
                return false;
        }
    }

    private static int indexOfName(List<ReplayClip> clips, ReplayClip target) {
        for (int i = 0; i < clips.size(); i++) {
            if (clips.get(i).name().equals(target.name())) return i;
        }
        return -1;
    }

    private void highlightSelection() {
        for (ClipCardView card : cards) {
            boolean isSelected = selected != null && card.clip() == selected;
            card.setBackground(isSelected
                    ? ReplayStyle.roundedStroked(style.surfaceElevated(), style.accent(), 14f,
                            activity)
                    : ReplayStyle.rounded(style.surfaceElevated(), 14f, activity));
            if (isSelected) card.bringToFront();
        }
    }

    /** True when the panel currently has a selected clip. */
    public boolean hasSelection() {
        return selected != null;
    }

    private static LinearLayout.LayoutParams topMarginParams(Activity activity, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ReplayStyle.dpInt(activity, topDp);
        return params;
    }
}
