package org.chimeramc.client.ui.adapter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.content.ResourcePackItem;
import org.chimeramc.client.ui.util.SkinPreviewRenderer;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SkinsAdapter extends RecyclerView.Adapter<SkinsAdapter.SkinViewHolder> {

    private static final String TAG = "SkinsAdapter";

    public interface OnSkinActionListener {
        void onApply(ResourcePackItem pack);
    }

    private List<ResourcePackItem> skinPacks = new ArrayList<>();
    private OnSkinActionListener listener;
    private String appliedPackName;

    /**
     * Decodes preview textures off the UI thread.
     *
     * <p>A small fixed pool rather than one thread per row: scrolling a large skin list used to
     * spawn an unbounded number of short-lived threads, each holding a decoded bitmap. Two workers
     * keep the visible rows filled without letting a fling open dozens of decoders at once.
     */
    private final ExecutorService previewExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "skin-preview");
        thread.setDaemon(true);
        return thread;
    });

    public SkinsAdapter() {
        setHasStableIds(true);
    }

    /** Releases the decode pool. Call from the host's {@code onDestroy}/{@code onDestroyView}. */
    public void shutdown() {
        previewExecutor.shutdownNow();
    }

    /**
     * Submits a preview decode, tolerating a shut-down pool.
     *
     * <p>{@link #shutdown()} runs from the host's {@code onDestroyView}, but the RecyclerView can
     * still bind a row afterwards while the view tree is being torn down. Submitting to a shut-down
     * executor throws on the main thread, so a fast scroll out of the Skins screen could crash the
     * app. Nothing is left to draw for, so the submission is dropped.
     */
    private void submitPreview(Runnable work) {
        try {
            previewExecutor.execute(work);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            android.util.Log.w(TAG, "Skin preview decode dropped: the adapter is shut down", rejected);
        }
    }

    public void setOnSkinActionListener(OnSkinActionListener listener) {
        this.listener = listener;
    }

    public void updateSkinPacks(List<ResourcePackItem> packs, String appliedPackName, boolean resizeImages) {
        this.appliedPackName = appliedPackName;

        List<ResourcePackItem> sorted = packs != null ? new ArrayList<>(packs) : new ArrayList<>();
        sorted.sort(Comparator.comparing(item -> item.getPackName() == null ? "" : item.getPackName()));
        this.skinPacks = sorted;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SkinViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_skin_pack, parent, false);
        return new SkinViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SkinViewHolder holder, int position) {
        ResourcePackItem pack = skinPacks.get(position);
        holder.name.setText(pack.getPackName());
        holder.desc.setText(pack.getDescription());

        String applied = appliedPackName;
        boolean isApplied = applied != null && applied.equals(pack.getPackName());
        holder.applied.setText(isApplied ? holder.applied.getContext().getString(R.string.skins_applied_to, applied) : "");
        holder.applied.setVisibility(isApplied ? View.VISIBLE : View.GONE);

        holder.applyButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onApply(pack);
            }
        });

        holder.preview.setImageResource(R.drawable.ic_tshirt);
        holder.progress.setVisibility(View.GONE);
        holder.preview.post(() -> loadPreview(holder, pack, holder.getBindingAdapterPosition()));
    }

    /**
     * Loads a pack's preview texture and paints it into the row.
     *
     * <p>Both halves are off the UI thread. Finding the candidate PNG walks the pack directory
     * recursively and the decode reads it; the walk used to run inline from
     * {@code preview.post(...)}, so scrolling the skin list performed a directory walk per bind on
     * the UI thread, and the decode spawned a fresh thread per row. One bounded pool replaces both,
     * and the bitmap is applied only while the row still shows the same pack, so a fast scroll
     * cannot paint a stale texture.
     */
    private void loadPreview(SkinViewHolder holder, ResourcePackItem pack, int position) {
        if (position == RecyclerView.NO_POSITION) return;
        holder.progress.setVisibility(View.VISIBLE);
        submitPreview(() -> {
            Bitmap bitmap = null;
            try {
                File[] candidates = findPreviewCandidates(pack);
                if (candidates != null && candidates.length > 0) {
                    File first = candidates[0];
                    if (first.exists()) {
                        try (FileInputStream fis = new FileInputStream(first)) {
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inScaled = false;
                            bitmap = BitmapFactory.decodeStream(fis, null, opts);
                        }
                    }
                }
            } catch (Exception e) {
                bitmap = null;
            }
            if (bitmap != null && (bitmap.getWidth() % 64 == 0 || bitmap.getWidth() == 64) &&
                    (bitmap.getHeight() % 32 == 0)) {
                bitmap = SkinPreviewRenderer.render(bitmap);
            }
            Bitmap finalBitmap = bitmap;
            holder.itemView.post(() -> {
                if (holder.getBindingAdapterPosition() != position) return;
                holder.progress.setVisibility(View.GONE);
                if (finalBitmap != null) {
                    holder.preview.setImageBitmap(finalBitmap);
                }
            });
        });
    }

    private static File[] findPreviewCandidates(ResourcePackItem pack) {
        List<File> matches = new ArrayList<>();
        collectImages(pack.getFile(), matches);
        File png = new File(pack.getFile(), "skins");
        File[] dir = png.listFiles((d, name) -> name.toLowerCase().endsWith(".png"));
        if (dir != null) {
            Collections.addAll(matches, dir);
        }
        return matches.toArray(new File[0]);
    }

    private static void collectImages(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectImages(f, out);
            } else if (f.getName().toLowerCase().endsWith(".png")) {
                if (out.size() < 8) out.add(f);
            }
        }
    }

    @Override
    public int getItemCount() {
        return skinPacks.size();
    }

    @Override
    public long getItemId(int position) {
        ResourcePackItem pack = skinPacks.get(position);
        File f = pack.getFile();
        return f != null ? f.getAbsolutePath().hashCode() : position;
    }

    static class SkinViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView desc;
        final TextView applied;
        final Button applyButton;
        final ImageView preview;
        final ProgressBar progress;

        SkinViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.skin_name);
            desc = itemView.findViewById(R.id.skin_desc);
            applied = itemView.findViewById(R.id.skin_applied);
            applyButton = itemView.findViewById(R.id.skin_apply_button);
            preview = itemView.findViewById(R.id.skin_preview);
            progress = itemView.findViewById(R.id.skin_preview_progress);
        }
    }
}