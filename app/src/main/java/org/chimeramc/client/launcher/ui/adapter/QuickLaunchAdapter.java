package org.chimeramc.client.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.ui.components.ListDiff;
import org.chimeramc.client.ui.components.ListDiffDispatch;

import java.util.ArrayList;
import java.util.List;

public class QuickLaunchAdapter extends RecyclerView.Adapter<QuickLaunchAdapter.ViewHolder> {

    public enum ActionType {
        HOW_TO_PLAY,
        SERVERS_TAB,
        PROFILE_SCREEN,
        STORE_HOME,
        MINECOIN_OFFERS,
        MARKETPLACE_PASS,
        CONNECT_SERVER,
        ADD_SERVER,
        REALM_INVITE,
        LOAD_WORLD,
        SLASH_COMMAND,
        CUSTOM_URI
    }

    public static class QuickLaunchItem {
        public final String title;
        public final String description;
        public final ActionType actionType;

        public QuickLaunchItem(String title, String description, ActionType actionType) {
            this.title = title;
            this.description = description;
            this.actionType = actionType;
        }
    }

    public interface OnActionClickListener {
        void onActionClick(ActionType actionType);
    }

    private List<QuickLaunchItem> items = new ArrayList<>();
    private OnActionClickListener listener;

    public void setOnActionClickListener(OnActionClickListener listener) {
        this.listener = listener;
    }

    /**
     * Replaces the list, animating only what changed. A quick-launch row's identity is its action,
     * so a filter that reorders the same rows moves them instead of rebuilding the grid.
     */
    public void updateItems(List<QuickLaunchItem> newItems) {
        List<QuickLaunchItem> next = newItems != null ? newItems : new ArrayList<>();
        ListDiff.Result diff = ListDiff.compute(this.items, next,
                item -> item.actionType.name(),
                (old, fresh) -> java.util.Objects.equals(old.title, fresh.title)
                        && java.util.Objects.equals(old.description, fresh.description));
        this.items = next;
        ListDiffDispatch.apply(this, diff);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_quick_launch, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        QuickLaunchItem item = items.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView titleText;
        private final TextView descriptionText;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            titleText = itemView.findViewById(R.id.title_text);
            descriptionText = itemView.findViewById(R.id.description_text);
        }

        void bind(QuickLaunchItem item) {
            titleText.setText(item.title);
            descriptionText.setText(item.description);
            
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onActionClick(item.actionType);
                }
            });
        }
    }
}
