package org.chimeramc.client.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.content.ResourcePackItem;
import org.chimeramc.client.ui.components.ListDiff;
import org.chimeramc.client.ui.components.ListDiffDispatch;

import java.util.ArrayList;
import java.util.List;

public class ResourcePacksAdapter extends RecyclerView.Adapter<ResourcePacksAdapter.ResourcePackViewHolder> {

    private List<ResourcePackItem> resourcePacks = new ArrayList<>();
    private OnResourcePackActionListener onResourcePackActionListener;

    public interface OnResourcePackActionListener {
        void onResourcePackDelete(ResourcePackItem pack);
        void onResourcePackTransfer(ResourcePackItem pack);
        void onResourcePackExport(ResourcePackItem pack);
    }

    public ResourcePacksAdapter() {
    }

    public void setOnResourcePackActionListener(OnResourcePackActionListener listener) {
        this.onResourcePackActionListener = listener;
    }

    /**
     * Replaces the list, animating only what changed. A pack's identity is its file, so renaming
     * one keeps its row; a pack that was enabled or removed animates in place.
     */
    public void updateResourcePacks(List<ResourcePackItem> resourcePacks) {
        List<ResourcePackItem> next = resourcePacks != null ? resourcePacks : new ArrayList<>();
        ListDiff.Result diff = ListDiff.compute(this.resourcePacks, next,
                item -> item.getFile() != null ? item.getFile().getAbsolutePath() : item.getPackName(),
                (old, fresh) -> old.isEnabled() == fresh.isEnabled()
                        && java.util.Objects.equals(old.getDescription(), fresh.getDescription()));
        this.resourcePacks = next;
        ListDiffDispatch.apply(this, diff);
    }

    @NonNull
    @Override
    public ResourcePackViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_resource_pack, parent, false);
        return new ResourcePackViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ResourcePackViewHolder holder, int position) {
        ResourcePackItem pack = resourcePacks.get(position);

        holder.packName.setText(pack.getPackName());
        holder.packDescription.setText(pack.getDescription());
        holder.packSize.setText(holder.itemView.getContext().getString(R.string.world_size, pack.getFormattedSize()));

        holder.exportButton.setOnClickListener(v -> {
            if (onResourcePackActionListener != null) {
                onResourcePackActionListener.onResourcePackExport(pack);
            }
        });

        holder.deleteButton.setOnClickListener(v -> {
            if (onResourcePackActionListener != null) {
                onResourcePackActionListener.onResourcePackDelete(pack);
            }
        });

        holder.transferButton.setOnClickListener(v -> {
            if (onResourcePackActionListener != null) {
                onResourcePackActionListener.onResourcePackTransfer(pack);
            }
        });

        org.chimeramc.client.util.PersonalizationManager pm = new org.chimeramc.client.util.PersonalizationManager(holder.itemView.getContext());
        pm.applyGlassToView(holder.itemView);
        pm.applyAccentToView(holder.itemView, holder.itemView.getContext());
    }

    @Override
    public int getItemCount() {
        return resourcePacks.size();
    }

    static class ResourcePackViewHolder extends RecyclerView.ViewHolder {
        TextView packName;
        TextView packDescription;
        TextView packSize;
        Button exportButton;
        Button deleteButton;
        Button transferButton;

        public ResourcePackViewHolder(@NonNull View itemView) {
            super(itemView);
            packName = itemView.findViewById(R.id.pack_name);
            packDescription = itemView.findViewById(R.id.pack_description);
            packSize = itemView.findViewById(R.id.pack_size);
            exportButton = itemView.findViewById(R.id.pack_export_button);
            deleteButton = itemView.findViewById(R.id.pack_delete_button);
            transferButton = itemView.findViewById(R.id.pack_transfer_button);
        }
    }
}