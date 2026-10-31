package org.chimeramc.client.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.content.OptionsEditor.OptionProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.chimeramc.client.ui.components.ListDiff;
import org.chimeramc.client.ui.components.ListDiffDispatch;

public class OptionsPropertiesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PROPERTY = 1;

    private final List<Object> items = new ArrayList<>();
    private OnOptionChangedListener listener;

    public interface OnOptionChangedListener {
        void onOptionChanged(OptionProperty property, String newValue);
    }

    public void setOnOptionChangedListener(OnOptionChangedListener listener) {
        this.listener = listener;
    }

    /**
     * Replaces the grouped list, animating only what changed.
     *
     * <p>A header and a property share one list, so the key carries the kind: a header by its title
     * and a property by its key, prefixed so a category and a property of the same name cannot
     * collide.
     */
    public void setProperties(Map<String, List<OptionProperty>> grouped) {
        List<Object> next = new ArrayList<>();
        for (Map.Entry<String, List<OptionProperty>> entry : grouped.entrySet()) {
            next.add(new HeaderItem(entry.getKey()));
            next.addAll(entry.getValue());
        }
        ListDiff.Result diff = ListDiff.compute(items, next,
                OptionsPropertiesAdapter::keyOf,
                OptionsPropertiesAdapter::sameContent);
        items.clear();
        items.addAll(next);
        ListDiffDispatch.apply(this, diff);
    }

    private static Object keyOf(Object item) {
        if (item instanceof HeaderItem) {
            return "H:" + ((HeaderItem) item).title;
        }
        return "P:" + ((OptionProperty) item).getKey();
    }

    private static boolean sameContent(Object oldItem, Object freshItem) {
        if (oldItem instanceof HeaderItem || freshItem instanceof HeaderItem) {
            return oldItem instanceof HeaderItem && freshItem instanceof HeaderItem;
        }
        return java.util.Objects.equals(((OptionProperty) oldItem).getValue(),
                ((OptionProperty) freshItem).getValue());
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item instanceof HeaderItem) return TYPE_HEADER;
        return TYPE_PROPERTY;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.item_property_header, parent, false));
        }
        return new PropertyViewHolder(inflater.inflate(R.layout.item_option_property, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        
        if (holder instanceof HeaderViewHolder headerHolder) {
            headerHolder.bind((HeaderItem) item);
        } else if (holder instanceof PropertyViewHolder propHolder) {
            propHolder.bind((OptionProperty) item, listener);
        }

        android.content.Context ctx = holder.itemView.getContext();
        org.chimeramc.client.util.PersonalizationManager pm = new org.chimeramc.client.util.PersonalizationManager(ctx);
        pm.applyGlassToView(holder.itemView);
        pm.applyAccentToView(holder.itemView, ctx);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static class HeaderItem {
        final String title;
        HeaderItem(String title) { this.title = title; }
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView headerText;

        HeaderViewHolder(View itemView) {
            super(itemView);
            headerText = itemView.findViewById(R.id.header_text);
        }

        void bind(HeaderItem item) {
            headerText.setText(item.title);
        }
    }

    static class PropertyViewHolder extends RecyclerView.ViewHolder {
        TextView propertyName;
        TextView propertyKey;
        EditText propertyValue;

        PropertyViewHolder(View itemView) {
            super(itemView);
            propertyName = itemView.findViewById(R.id.property_name);
            propertyKey = itemView.findViewById(R.id.property_key);
            propertyValue = itemView.findViewById(R.id.property_value);
        }

        void bind(OptionProperty property, OnOptionChangedListener listener) {
            propertyName.setText(property.getDisplayName());
            propertyKey.setText(property.getKey());

            propertyValue.setOnEditorActionListener(null);
            propertyValue.setOnFocusChangeListener(null);
            propertyValue.setText(property.getValue());

            propertyValue.setOnEditorActionListener((v, actionId, event) -> {
                if (listener != null) {
                    String newValue = propertyValue.getText().toString();
                    if (!newValue.equals(property.getValue())) {
                        listener.onOptionChanged(property, newValue);
                    }
                }
                hideKeyboard(v);
                propertyValue.clearFocus();
                return true;
            });

            propertyValue.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus && listener != null) {
                    String newValue = propertyValue.getText().toString();
                    if (!newValue.equals(property.getValue())) {
                        listener.onOptionChanged(property, newValue);
                    }
                }
            });
        }

        private void hideKeyboard(View view) {
            Context context = view.getContext();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        }
    }
}
