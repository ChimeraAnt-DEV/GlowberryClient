package org.chimeramc.client.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.modrinth.models.ModrinthVersion;
import org.chimeramc.client.ui.components.ListDiff;
import org.chimeramc.client.ui.components.ListDiffDispatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ModrinthVersionAdapter extends RecyclerView.Adapter<ModrinthVersionAdapter.ViewHolder> {

    public interface OnDownloadClickListener {
        void onDownloadClick(ModrinthVersion version);
    }

    private final List<ModrinthVersion> versions = new ArrayList<>();
    private final OnDownloadClickListener listener;

    public ModrinthVersionAdapter(OnDownloadClickListener listener) {
        this.listener = listener;
    }

    /** Replaces the list, animating only what changed; a version's identity is its id. */
    public void setVersions(List<ModrinthVersion> items) {
        List<ModrinthVersion> next = items != null ? items : new ArrayList<>();
        ListDiff.Result diff = ListDiff.compute(versions, next,
                version -> version.id,
                (old, fresh) -> java.util.Objects.equals(old.versionNumber, fresh.versionNumber)
                        && java.util.Objects.equals(old.name, fresh.name)
                        && java.util.Objects.equals(old.versionType, fresh.versionType));
        versions.clear();
        versions.addAll(next);
        ListDiffDispatch.apply(this, diff);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_modrinth_version, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(versions.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return versions.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView details;
        final Button download;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.version_name);
            details = itemView.findViewById(R.id.version_details);
            download = itemView.findViewById(R.id.version_download);
        }

        void bind(final ModrinthVersion version, final OnDownloadClickListener listener) {
            android.content.Context ctx = itemView.getContext();

            String label = version.name != null && !version.name.isEmpty()
                    ? version.name : version.versionNumber;
            name.setText(label);
            details.setText(buildDetails(ctx, version));

            boolean hasFile = version.primaryFile() != null && version.primaryFile().url != null;
            download.setEnabled(hasFile);
            download.setAlpha(hasFile ? 1f : 0.5f);
            download.setOnClickListener(hasFile ? v -> listener.onDownloadClick(version) : null);

            new org.chimeramc.client.util.PersonalizationManager(ctx).applyGlassToView(itemView);
        }

        private static String buildDetails(android.content.Context ctx, ModrinthVersion version) {
            StringBuilder sb = new StringBuilder();
            if (version.versionType != null) {
                sb.append(version.versionType);
            }
            if (version.gameVersions != null && !version.gameVersions.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" • ");
                }
                sb.append(summariseVersions(version.gameVersions));
            }
            long size = version.primaryFile() != null ? version.primaryFile().size : 0;
            if (size > 0) {
                if (sb.length() > 0) {
                    sb.append(" • ");
                }
                sb.append(formatSize(size));
            }
            return sb.toString();
        }

        /** Shows the newest few versions; a full list of 30 game versions is noise on a phone. */
        private static String summariseVersions(List<String> versions) {
            int shown = Math.min(3, versions.size());
            StringBuilder sb = new StringBuilder();
            for (int i = versions.size() - shown; i < versions.size(); i++) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(versions.get(i));
            }
            if (versions.size() > shown) {
                sb.insert(0, "... ");
            }
            return sb.toString();
        }

        private static String formatSize(long bytes) {
            if (bytes >= 1024L * 1024L) {
                return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
            }
            if (bytes >= 1024L) {
                return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
            }
            return bytes + " B";
        }
    }
}
