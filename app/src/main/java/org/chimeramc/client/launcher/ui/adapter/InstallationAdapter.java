package org.chimeramc.client.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.installer.BedrockSource.Version;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.ui.components.ListDiff;
import org.chimeramc.client.ui.components.ListDiffDispatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Rows for the Installations tab: one Bedrock release each, with its own action button.
 *
 * Download state is held per row rather than globally because the user can start a second
 * download while the first is still running.
 */
public class InstallationAdapter extends RecyclerView.Adapter<InstallationAdapter.VersionHolder> {

    public interface OnDownloadListener {
        void onDownload(Version version);
    }

    /** What a row is doing right now. */
    public enum State {
        IDLE,
        DOWNLOADING,
        IMPORTING,
        FAILED
    }

    private final List<Version> versions = new ArrayList<>();
    private final List<State> states = new ArrayList<>();
    private final List<Integer> progress = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<Boolean> installed = new ArrayList<>();
    private OnDownloadListener listener;

    public void setOnDownloadListener(OnDownloadListener listener) {
        this.listener = listener;
    }

    /**
     * Replaces the page of versions, keeping the per-row download state of the rows that survive.
     *
     * <p>The old code cleared every parallel list, so paging forward and back discarded the state
     * of a download that was still running: the row said "Download" again while the transfer was in
     * progress. A version's identity is its page URL, so a row that is still on the page keeps its
     * state and only the new rows start at IDLE.
     */
    public void setVersions(List<Version> newVersions) {
        List<Version> next = newVersions != null ? newVersions : new ArrayList<>();
        List<State> nextStates = new ArrayList<>(next.size());
        List<Integer> nextProgress = new ArrayList<>(next.size());
        List<String> nextErrors = new ArrayList<>(next.size());
        List<Boolean> nextInstalled = new ArrayList<>(next.size());
        for (Version version : next) {
            int existing = indexOf(version.pageUrl);
            if (existing >= 0) {
                nextStates.add(states.get(existing));
                nextProgress.add(progress.get(existing));
                nextErrors.add(errors.get(existing));
                nextInstalled.add(installed.get(existing));
            } else {
                nextStates.add(State.IDLE);
                nextProgress.add(0);
                nextErrors.add(null);
                nextInstalled.add(false);
            }
        }

        ListDiff.Result diff = ListDiff.compute(versions, next,
                version -> version.pageUrl,
                (old, fresh) -> java.util.Objects.equals(old.title, fresh.title)
                        && old.release == fresh.release);
        versions.clear();
        versions.addAll(next);
        states.clear();
        states.addAll(nextStates);
        progress.clear();
        progress.addAll(nextProgress);
        errors.clear();
        errors.addAll(nextErrors);
        installed.clear();
        installed.addAll(nextInstalled);
        ListDiffDispatch.apply(this, diff);
    }

    /** True when [versionCode] already exists as an instance, so the row can say so. */
    public void markInstalled(String versionCode, boolean isInstalled) {
        if (versionCode == null) return;
        for (int i = 0; i < versions.size(); i++) {
            if (versionCode.equals(versions.get(i).versionCode)) {
                installed.set(i, isInstalled);
                notifyItemChanged(i);
            }
        }
    }

    public void setState(String pageUrl, State state) {
        int index = indexOf(pageUrl);
        if (index < 0) return;
        states.set(index, state);
        if (state != State.FAILED) errors.set(index, null);
        if (state == State.IDLE) progress.set(index, 0);
        notifyItemChanged(index);
    }

    public void setProgress(String pageUrl, int percent) {
        int index = indexOf(pageUrl);
        if (index < 0) return;
        progress.set(index, Math.max(0, Math.min(100, percent)));
        notifyItemChanged(index);
    }

    public void setError(String pageUrl, String message) {
        int index = indexOf(pageUrl);
        if (index < 0) return;
        states.set(index, State.FAILED);
        errors.set(index, message);
        notifyItemChanged(index);
    }

    public State stateOf(String pageUrl) {
        int index = indexOf(pageUrl);
        return index < 0 ? State.IDLE : states.get(index);
    }

    private int indexOf(String pageUrl) {
        if (pageUrl == null) return -1;
        for (int i = 0; i < versions.size(); i++) {
            if (pageUrl.equals(versions.get(i).pageUrl)) return i;
        }
        return -1;
    }

    @NonNull
    @Override
    public VersionHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_installation_version, parent, false);
        return new VersionHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VersionHolder holder, int position) {
        Version version = versions.get(position);
        State state = states.get(position);
        boolean isInstalled = installed.get(position);

        holder.title.setText(version.displayLabel());
        holder.releaseTag.setVisibility(version.release ? View.VISIBLE : View.GONE);
        holder.installedTag.setVisibility(isInstalled ? View.VISIBLE : View.GONE);

        boolean busy = state == State.DOWNLOADING || state == State.IMPORTING;
        holder.progress.setVisibility(state == State.DOWNLOADING ? View.VISIBLE : View.GONE);
        holder.progress.setProgress(progress.get(position));

        holder.status.setText(statusText(holder, version, state, position, isInstalled));
        holder.action.setText(actionText(holder, state, isInstalled));
        holder.action.setEnabled(!busy);
        holder.action.setAlpha(busy ? 0.5f : 1f);

        holder.action.setOnClickListener(v -> {
            if (listener != null && holder.action.isEnabled()) {
                listener.onDownload(version);
            }
        });
        DynamicAnim.applyPressScale(holder.action);
    }

    private String statusText(VersionHolder holder, Version version, State state,
                              int position, boolean isInstalled) {
        switch (state) {
            case DOWNLOADING:
                return holder.itemView.getContext().getString(
                        R.string.installations_status_downloading, progress.get(position));
            case IMPORTING:
                return holder.itemView.getContext().getString(R.string.installations_status_importing);
            case FAILED: {
                String error = errors.get(position);
                return error == null
                        ? holder.itemView.getContext().getString(R.string.installations_status_failed)
                        : error;
            }
            default:
                if (isInstalled) {
                    return holder.itemView.getContext().getString(R.string.installations_status_installed);
                }
                String code = version.versionCode;
                return code == null || code.isEmpty()
                        ? holder.itemView.getContext().getString(R.string.installations_status_ready)
                        : holder.itemView.getContext().getString(R.string.installations_status_version, code);
        }
    }

    private String actionText(VersionHolder holder, State state, boolean isInstalled) {
        switch (state) {
            case DOWNLOADING:
                return holder.itemView.getContext().getString(R.string.installations_action_cancel_hint);
            case IMPORTING:
                return holder.itemView.getContext().getString(R.string.installations_action_importing);
            case FAILED:
                return holder.itemView.getContext().getString(R.string.installations_action_retry);
            default:
                return holder.itemView.getContext().getString(
                        isInstalled ? R.string.installations_reinstall : R.string.installations_download);
        }
    }

    @Override
    public int getItemCount() {
        return versions.size();
    }

    static class VersionHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView status;
        final TextView action;
        final TextView releaseTag;
        final TextView installedTag;
        final ProgressBar progress;

        VersionHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.installation_title);
            status = itemView.findViewById(R.id.installation_status);
            action = itemView.findViewById(R.id.installation_action);
            releaseTag = itemView.findViewById(R.id.installation_release_tag);
            installedTag = itemView.findViewById(R.id.installation_installed_tag);
            progress = itemView.findViewById(R.id.installation_progress);
        }
    }
}
