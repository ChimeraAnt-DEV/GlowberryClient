package org.chimeramc.client.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;

import org.chimeramc.client.R;
import org.chimeramc.client.core.modrinth.models.ModrinthProject;
import org.chimeramc.client.ui.components.ListDiff;
import org.chimeramc.client.ui.components.ListDiffDispatch;

import java.util.ArrayList;
import java.util.List;

public class ModrinthContentAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private List<ModrinthProject> projects = new ArrayList<>();
    private final OnProjectClickListener listener;
    private final OnPageChangeListener pageChangeListener;
    private int currentPage = 1;
    private int totalPages = 1;

    public interface OnProjectClickListener {
        void onProjectClick(ModrinthProject project);
    }

    public interface OnPageChangeListener {
        void onNextPage();

        void onPrevPage();
    }

    private static final int VIEW_TYPE_ITEM = 0;
    private static final int VIEW_TYPE_FOOTER = 1;

    public ModrinthContentAdapter(OnProjectClickListener listener, OnPageChangeListener pageChangeListener) {
        this.listener = listener;
        this.pageChangeListener = pageChangeListener;
    }

    /**
     * Replaces the page, animating only what changed.
     *
     * <p>The footer is a synthetic last row, so the diff runs over the projects and the footer is
     * told separately: a page change that keeps a project keeps its row, and the footer's own
     * content (the page numbers) is refreshed in place.
     */
    public void setProjects(List<ModrinthProject> projects, int currentPage, int totalPages) {
        List<ModrinthProject> next = projects != null ? projects : new ArrayList<>();
        ListDiff.Result diff = ListDiff.compute(this.projects, next,
                project -> project.projectId,
                (old, fresh) -> java.util.Objects.equals(old.title, fresh.title)
                        && java.util.Objects.equals(old.description, fresh.description)
                        && java.util.Objects.equals(old.author, fresh.author));
        boolean footerChanged = this.currentPage != currentPage || this.totalPages != totalPages;
        this.projects = next;
        this.currentPage = currentPage;
        this.totalPages = totalPages;
        ListDiffDispatch.apply(this, diff);
        if (footerChanged && !diff.fullRedraw && getItemCount() > 0) {
            notifyItemChanged(next.size());
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_FOOTER) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_curseforge_footer, parent, false);
            return new FooterViewHolder(view);
        }
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_curseforge_content, parent, false);
        return new ProjectViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof ProjectViewHolder) {
            ((ProjectViewHolder) holder).bind(projects.get(position), listener);
        } else if (holder instanceof FooterViewHolder) {
            ((FooterViewHolder) holder).bind(currentPage, totalPages, pageChangeListener);
        }
    }

    @Override
    public int getItemViewType(int position) {
        return position == projects.size() ? VIEW_TYPE_FOOTER : VIEW_TYPE_ITEM;
    }

    @Override
    public int getItemCount() {
        return projects.isEmpty() ? 0 : projects.size() + 1;
    }

    static class ProjectViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView description;
        final TextView author;
        final TextView metadata;

        ProjectViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.mod_icon);
            title = itemView.findViewById(R.id.mod_title);
            description = itemView.findViewById(R.id.mod_description);
            author = itemView.findViewById(R.id.mod_author);
            metadata = itemView.findViewById(R.id.mod_metadata);
        }

        void bind(final ModrinthProject project, final OnProjectClickListener listener) {
            android.content.Context ctx = itemView.getContext();
            title.setText(project.displayName());
            description.setText(project.description);

            author.setText(project.author != null
                    ? ctx.getString(R.string.mod_author_byline, project.author)
                    : "");

            metadata.setText(buildMetadata(ctx, project));

            if (project.iconUrl != null && !project.iconUrl.isEmpty()) {
                Glide.with(ctx)
                        .load(project.iconUrl)
                        .transform(new RoundedCorners(16))
                        .placeholder(R.drawable.ic_minecraft_cube)
                        .error(R.drawable.ic_minecraft_cube)
                        .into(icon);
            } else {
                icon.setImageResource(R.drawable.ic_minecraft_cube);
            }

            new org.chimeramc.client.util.PersonalizationManager(ctx).applyGlassToView(itemView);
            itemView.setOnClickListener(v -> listener.onProjectClick(project));
        }

        private static String buildMetadata(android.content.Context ctx, ModrinthProject project) {
            StringBuilder meta = new StringBuilder();
            meta.append(ctx.getString(R.string.modrinth_downloads_format, formatCount(project.downloads)));
            meta.append(" • ").append(ctx.getString(R.string.modrinth_loader,
                    project.projectType != null ? project.projectType : "project"));
            if (project.dateModified != null && project.dateModified.length() >= 10) {
                meta.append(" • ").append(
                        ctx.getString(R.string.modrinth_updated, project.dateModified.substring(0, 10)));
            }
            return meta.toString();
        }

        private static String formatCount(long value) {
            if (value >= 1_000_000L) {
                return String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0);
            }
            if (value >= 1_000L) {
                return String.format(java.util.Locale.US, "%.1fK", value / 1_000.0);
            }
            return String.valueOf(value);
        }
    }

    static class FooterViewHolder extends RecyclerView.ViewHolder {
        final android.widget.Button btnPrev;
        final android.widget.Button btnNext;
        final TextView tvPageInfo;

        FooterViewHolder(@NonNull View itemView) {
            super(itemView);
            btnPrev = itemView.findViewById(R.id.btn_prev_page);
            btnNext = itemView.findViewById(R.id.btn_next_page);
            tvPageInfo = itemView.findViewById(R.id.tv_page_info);
        }

        void bind(int currentPage, int totalPages, final OnPageChangeListener listener) {
            tvPageInfo.setText(itemView.getContext().getString(R.string.pagination_page_of,
                    currentPage, totalPages > 0 ? String.valueOf(totalPages) : "?"));

            boolean canPrev = currentPage > 1;
            boolean canNext = totalPages <= 0 || currentPage < totalPages;

            btnPrev.setEnabled(canPrev);
            btnPrev.setAlpha(canPrev ? 1f : 0.5f);
            btnNext.setEnabled(canNext);
            btnNext.setAlpha(canNext ? 1f : 0.5f);

            btnPrev.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPrevPage();
                }
            });
            btnNext.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onNextPage();
                }
            });
        }
    }
}
