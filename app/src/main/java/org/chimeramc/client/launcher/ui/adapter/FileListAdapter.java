package org.chimeramc.client.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.curseforge.models.ContentFile;
import org.chimeramc.client.ui.components.ListDiff;
import org.chimeramc.client.ui.components.ListDiffDispatch;

import java.util.ArrayList;
import java.util.List;

public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.ViewHolder> {

    private List<ContentFile> files = new ArrayList<>();
    private final OnFileClickListener listener;
    private final int accentColor;

    public interface OnFileClickListener {
        void onDownloadClick(ContentFile file);
    }

    public FileListAdapter(OnFileClickListener listener, int accentColor) {
        this.listener = listener;
        this.accentColor = accentColor;
    }

    /** Replaces the list, animating only what changed; a file row's identity is its file id. */
    public void setFiles(List<ContentFile> files) {
        List<ContentFile> next = files != null ? files : new ArrayList<>();
        ListDiff.Result diff = ListDiff.compute(this.files, next,
                file -> file.id,
                (old, fresh) -> java.util.Objects.equals(old.fileName, fresh.fileName)
                        && java.util.Objects.equals(old.displayName, fresh.displayName)
                        && java.util.Objects.equals(old.fileDate, fresh.fileDate));
        this.files = next;
        ListDiffDispatch.apply(this, diff);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file_detail, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ContentFile file = files.get(position);
        holder.bind(file, listener, accentColor);
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name;
        TextView date;
        TextView version;
        View btnDownload;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.file_name);
            date = itemView.findViewById(R.id.file_date);
            version = itemView.findViewById(R.id.file_version);
            btnDownload = itemView.findViewById(R.id.btn_download);
        }

        void bind(final ContentFile file, final OnFileClickListener listener, int accentColor) {
            name.setText(file.displayName != null ? file.displayName : file.fileName);
            date.setText(file.fileDate != null && file.fileDate.length() >= 10 ? file.fileDate.substring(0, 10) : "");
            
            if (file.gameVersions != null && !file.gameVersions.isEmpty()) {
                String v = file.gameVersions.get(0);
                for (String ver : file.gameVersions) {
                    if (ver.matches(".*\\d+\\.\\d+.*")) {
                        v = ver;
                        break;
                    }
                }
                version.setText(v);
            } else {
                version.setText("");
            }
            
            if (btnDownload instanceof com.google.android.material.button.MaterialButton) {
                com.google.android.material.button.MaterialButton btn = (com.google.android.material.button.MaterialButton) btnDownload;
                btn.setTextColor(accentColor);
                btn.setIconTint(android.content.res.ColorStateList.valueOf(accentColor));
            }

            btnDownload.setOnClickListener(v -> {
                org.chimeramc.client.ui.animation.UiTouchFeedback.confirm(v.getContext());
                listener.onDownloadClick(file);
            });
        }
    }
}
