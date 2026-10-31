package org.chimeramc.client.ui.adapter;

import android.content.Context;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.content.WorldEditor.WorldProperty;
import org.chimeramc.client.core.content.nbt.NbtTag;
import org.chimeramc.client.ui.components.ListDiff;
import org.chimeramc.client.ui.components.ListDiffDispatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WorldPropertiesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PROPERTY = 1;

    private final List<Object> items = new ArrayList<>();
    private OnPropertyChangedListener listener;

    public interface OnPropertyChangedListener {
        void onPropertyChanged(WorldProperty property, Object newValue);
    }

    public void setOnPropertyChangedListener(OnPropertyChangedListener listener) {
        this.listener = listener;
    }

    /**
     * Replaces the grouped list, animating only what changed.
     *
     * <p>A group header and a property row share one list, so both need a key: a header is keyed by
     * its title and a property by its path. The key prefix keeps the two apart, because a category
     * and a property can share a name and would otherwise be treated as the same row.
     */
    public void setProperties(Map<String, List<WorldProperty>> grouped) {
        List<Object> next = new ArrayList<>();
        for (Map.Entry<String, List<WorldProperty>> entry : grouped.entrySet()) {
            next.add(new HeaderItem(entry.getKey()));
            next.addAll(entry.getValue());
        }
        ListDiff.Result diff = ListDiff.compute(items, next,
                WorldPropertiesAdapter::keyOf,
                WorldPropertiesAdapter::sameContent);
        items.clear();
        items.addAll(next);
        ListDiffDispatch.apply(this, diff);
    }

    /** A header and a property are different kinds of row, so the key carries the kind. */
    private static Object keyOf(Object item) {
        if (item instanceof HeaderItem) {
            return "H:" + ((HeaderItem) item).title;
        }
        return "P:" + ((WorldProperty) item).getPath();
    }

    private static boolean sameContent(Object oldItem, Object freshItem) {
        if (oldItem instanceof HeaderItem || freshItem instanceof HeaderItem) {
            return oldItem instanceof HeaderItem && freshItem instanceof HeaderItem;
        }
        return java.util.Objects.equals(((WorldProperty) oldItem).getValueString(),
                ((WorldProperty) freshItem).getValueString());
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
        return new PropertyViewHolder(inflater.inflate(R.layout.item_world_property, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        
        if (holder instanceof HeaderViewHolder headerHolder) {
            headerHolder.bind((HeaderItem) item);
        } else if (holder instanceof PropertyViewHolder propHolder) {
            propHolder.bind((WorldProperty) item, listener);
        }

        android.content.Context context = holder.itemView.getContext();
        org.chimeramc.client.util.PersonalizationManager pm = new org.chimeramc.client.util.PersonalizationManager(context);
        pm.applyGlassToView(holder.itemView);
        pm.applyAccentToView(holder.itemView, context);
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
        TextView propertyType;
        EditText propertyValue;
        SwitchCompat propertySwitch;

        PropertyViewHolder(View itemView) {
            super(itemView);
            propertyName = itemView.findViewById(R.id.property_name);
            propertyType = itemView.findViewById(R.id.property_type);
            propertyValue = itemView.findViewById(R.id.property_value);
            propertySwitch = itemView.findViewById(R.id.property_switch);
        }

        void bind(WorldProperty property, OnPropertyChangedListener listener) {
            propertyName.setText(property.getDisplayName());
            propertyType.setText(property.getTypeString());

            propertySwitch.setOnCheckedChangeListener(null);
            propertyValue.setOnEditorActionListener(null);
            propertyValue.setOnFocusChangeListener(null);

            if (property.isBoolean()) {
                propertyValue.setVisibility(View.GONE);
                propertySwitch.setVisibility(View.VISIBLE);
                propertySwitch.setChecked(property.getTag().getByte() == 1);
                
                propertySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (listener != null) {
                        listener.onPropertyChanged(property, isChecked ? "1" : "0");
                    }
                });
            } else {
                propertySwitch.setVisibility(View.GONE);
                propertyValue.setVisibility(View.VISIBLE);
                propertyValue.setText(property.getValueString());

                int inputType = getInputType(property.getType());
                propertyValue.setInputType(inputType | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

                propertyValue.setOnEditorActionListener((v, actionId, event) -> {
                    if (listener != null) {
                        String newValue = propertyValue.getText().toString();
                        if (!newValue.equals(property.getValueString())) {
                            listener.onPropertyChanged(property, newValue);
                        }
                    }
                    hideKeyboard(v);
                    propertyValue.clearFocus();
                    return true;
                });

                propertyValue.setOnFocusChangeListener((v, hasFocus) -> {
                    if (!hasFocus && listener != null) {
                        String newValue = propertyValue.getText().toString();
                        if (!newValue.equals(property.getValueString())) {
                            listener.onPropertyChanged(property, newValue);
                        }
                    }
                });
            }
        }

        private int getInputType(byte nbtType) {
            return switch (nbtType) {
                case NbtTag.TAG_BYTE, NbtTag.TAG_SHORT, NbtTag.TAG_INT, NbtTag.TAG_LONG ->
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
                case NbtTag.TAG_FLOAT, NbtTag.TAG_DOUBLE ->
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
                default -> InputType.TYPE_CLASS_TEXT;
            };
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