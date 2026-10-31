package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.mods.inbuilt.UnifiedMod;
import org.chimeramc.client.core.mods.inbuilt.model.ModAvailability;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.ui.components.UiTokens;
import org.chimeramc.client.util.TouchTargetHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ModMenuAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_GROUP = 0;
    private static final int VIEW_TYPE_MOD = 1;
    private static final int VIEW_TYPE_MOD_COMPACT = 2;
    private static final int VIEW_TYPE_ACTION = 3;
    private static final int VIEW_TYPE_ACTION_COMPACT = 4;

    private final List<MenuItem> items = new ArrayList<>();
    private final Map<String, Boolean> toggleStates = new HashMap<>();
    private final Map<String, Boolean> favoriteStates = new HashMap<>();
    /** The one-shot actions appended after the module sections (M3), in display order. */
    private final List<ModMenuAction> actions = new ArrayList<>();
    private OnModActionListener listener;
    private boolean compactMode;
    private ModMenuTheme theme;
    private ModMenuSettings settings;
    /** The reorderable section id, or null when drag-to-reorder is off for this list (M9). */
    private String reorderSectionId;
    /** Per-module row state, from the registry (M10). Absent means "derive from availability". */
    private final Map<String, ModMenuRowState> rowStates = new HashMap<>();
    /** The index a controller has moved the selection to, or NONE. -1 because position 0 is valid. */
    private int controllerFocus = ModMenuNavigation.NONE;
    private RecyclerView recycler;

    public ModMenuAdapter(ModMenuTheme theme) {
        this.theme = theme != null ? theme : new ModMenuTheme(null);
        setHasStableIds(true);
    }

    public interface OnModActionListener {
        void onToggle(UnifiedMod mod, boolean enabled);
        void onConfig(UnifiedMod mod);
        void onFavoriteChanged(UnifiedMod mod, boolean favorite);
        /** A one-shot action row was tapped (M3). Never a switch. */
        void onAction(String actionId);
        /** A row's reason badge was tapped, so the overlay can explain the failure (M10). */
        void onRowReason(String stableKey);
        /** The user finished a drag-to-reorder move within a section (M9). */
        void onReorder(String sectionId, List<String> orderedKeys);
    }

    public void setOnModActionListener(OnModActionListener listener) {
        this.listener = listener;
    }

    /** Applies the menu's appearance settings (M1, M6): accent, density and motion scheme. */
    public void setSettings(ModMenuSettings settings) {
        this.settings = settings;
        if (settings != null) {
            this.theme = new ModMenuTheme(recycler != null ? recycler.getContext() : null, settings);
        }
    }

    public ModMenuSettings getSettings() {
        return settings;
    }

    /**
     * Replaces the per-module row states (M10).
     *
     * <p>The overlay owns what it knows about the registry (did the module register? has it crashed
     * twice? is there a hook for this build?) and hands the answer in; the adapter only renders it.
     */
    public void setRowStates(Map<String, ModMenuRowState> states) {
        rowStates.clear();
        if (states != null) rowStates.putAll(states);
    }

    /**
     * Commits the list's current order for a section (M9).
     *
     * <p>Called when a drag finishes, so the manager is written once per gesture rather than once
     * per intermediate move.
     */
    public void onReorderFinished(String sectionId) {
        if (listener != null && sectionId != null) {
            listener.onReorder(sectionId, currentSectionOrder());
        }
    }

    /** Enables drag-to-reorder for one section (M9). */
    public void setReorderSection(String sectionId) {
        this.reorderSectionId = sectionId;
    }

    public String getReorderSection() {
        return reorderSectionId;
    }

    /** True when the list is currently reorderable. */
    public boolean isReorderEnabled() {
        return reorderSectionId != null;
    }

    /**
     * Moves a module one slot within its section, returning the new order (M9).
     *
     * <p>Group headers and one-shot action rows are not movable and a module may not cross into
     * another section: the header rule (one header per contiguous run) would break, and the saved
     * order is per section. The move returns the resulting key order so the caller can persist it,
     * and returns <b>null</b> when it refuses — the refusal has to be visible, because
     * {@code ItemTouchHelper.onMove} treats a non-null return as "the drag was accepted" and lets a
     * row that was never actually moved keep floating under the finger.
     */
    public List<String> moveModule(int fromPosition, int toPosition) {
        if (fromPosition < 0 || toPosition < 0
                || fromPosition >= items.size() || toPosition >= items.size()) {
            return null;
        }
        MenuItem from = items.get(fromPosition);
        MenuItem to = items.get(toPosition);
        // A group header and a one-shot action row both have no position of their own, and neither
        // carries a module, so neither can be the source or the target of a move.
        if (from.mod == null || to.mod == null) return null;
        if (!Objects.equals(from.mod.getGroupId(), to.mod.getGroupId())) {
            return null;
        }
        items.remove(fromPosition);
        items.add(toPosition, from);
        notifyItemMoved(fromPosition, toPosition);
        return currentSectionOrder();
    }

    /**
     * Whether a drag may move one row onto another (M9).
     *
     * <p>Only two modules of the same section qualify: a header has no position of its own, and
     * crossing sections would break the one-header-per-contiguous-run rule the list renders by.
     */
    public boolean canReorderBetween(RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
        if (from == null || to == null) return false;
        int a = from.getBindingAdapterPosition();
        int b = to.getBindingAdapterPosition();
        if (a == RecyclerView.NO_POSITION || b == RecyclerView.NO_POSITION) return false;
        if (a >= items.size() || b >= items.size()) return false;
        MenuItem first = items.get(a);
        MenuItem second = items.get(b);
        // A header or an action row has no module to compare, so it is not a valid drop target.
        if (first.mod == null || second.mod == null) return false;
        return Objects.equals(first.mod.getGroupId(), second.mod.getGroupId());
    }

    /** The module keys in their current list order, for persistence (M9). */
    public List<String> currentSectionOrder() {
        List<String> out = new ArrayList<>();
        for (MenuItem item : items) {
            if (!item.isGroup() && item.mod != null) out.add(item.mod.getStableKey());
        }
        return out;
    }

    /**
     * Reorders the module list to the saved order, keeping group headers correct (M9).
     *
     * <p>A key that is no longer present is dropped, and a module the saved order does not mention
     * keeps its shipped position at the end of its group. That is what makes a saved order survive
     * a module being added or removed by an update.
     */
    public void applySavedOrder(List<String> savedOrder) {
        if (savedOrder == null || savedOrder.isEmpty() || items.isEmpty()) return;
        // Group the modules by their group id, preserving the shipped group order, and sort each
        // group by the saved order through ModMenuOrdering. A module the saved order does not
        // mention keeps its shipped place at the end of its own group, so an update that adds a
        // module does not scramble the arrangement.
        List<String> groupOrder = new ArrayList<>();
        Map<String, List<MenuItem>> byGroup = new LinkedHashMap<>();
        Map<String, String> groupNames = new HashMap<>();
        Map<String, Boolean> groupLabelFlags = new HashMap<>();
        // The trailing actions block is not reorderable and is not part of any module group; it is
        // carried through untouched and re-appended, so a saved order cannot drop it.
        List<MenuItem> actionBlock = new ArrayList<>();
        boolean inActionBlock = false;
        for (MenuItem item : items) {
            if (item.isAction() || item.isActionsHeader()) {
                inActionBlock = true;
                actionBlock.add(item);
                continue;
            }
            if (inActionBlock) {
                // The action block is always last; anything after it is unexpected, so keep it.
                actionBlock.add(item);
                continue;
            }
            if (item.isGroup()) {
                groupNames.put(item.groupId, item.groupName);
                continue;
            }
            String gid = item.mod.getGroupId();
            if (!byGroup.containsKey(gid)) {
                byGroup.put(gid, new ArrayList<>());
                groupOrder.add(gid);
            }
            groupLabelFlags.putIfAbsent(gid, item.showGroupLabel);
            byGroup.get(gid).add(item);
        }
        if (byGroup.isEmpty()) {
            // Nothing to reorder; the action block (if any) is already in place.
            return;
        }

        List<MenuItem> reordered = new ArrayList<>();
        for (String gid : groupOrder) {
            List<MenuItem> group = byGroup.get(gid);
            // The ordering rule lives in ModMenuOrdering so it is unit-tested; this only applies
            // the resulting key order to the row objects.
            List<String> shippedKeys = new ArrayList<>();
            Map<String, MenuItem> byKey = new HashMap<>();
            for (MenuItem item : group) {
                shippedKeys.add(item.mod.getStableKey());
                byKey.put(item.mod.getStableKey(), item);
            }
            List<MenuItem> orderedGroup = new ArrayList<>();
            for (String key : ModMenuOrdering.apply(shippedKeys, savedOrder)) {
                MenuItem item = byKey.get(key);
                if (item != null) orderedGroup.add(item);
            }
            group = orderedGroup;
            // Rebuild the section header. Dropping it here would silently flatten every group in
            // the non-compact layout, which is the whole navigation structure.
            if (!compactMode) {
                reordered.add(MenuItem.group(gid, groupNames.get(gid)));
            }
            for (MenuItem item : group) {
                boolean showLabel = compactMode
                        && Boolean.TRUE.equals(groupLabelFlags.get(gid));
                reordered.add(item.showGroupLabel == showLabel
                        ? item : MenuItem.mod(item.mod, showLabel));
            }
        }
        if (reordered.isEmpty()) return;
        reordered.addAll(actionBlock);
        items.clear();
        items.addAll(reordered);
        notifyDataSetChanged();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.recycler = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        this.recycler = null;
    }

    /** Columns per row, so vertical moves step by row exactly as the grid lays out. */
    private int columnCount() {
        if (recycler != null && recycler.getLayoutManager() instanceof androidx.recyclerview.widget.GridLayoutManager) {
            return ((androidx.recyclerview.widget.GridLayoutManager) recycler.getLayoutManager()).getSpanCount();
        }
        return compactMode ? 1 : 4;
    }

    /** One flag per item: group headers cannot be selected, module cards can. */
    private boolean[] selectableFlags() {
        boolean[] flags = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            if (item.isActionsHeader()) {
                flags[i] = false;
            } else if (item.isAction()) {
                // An action row is always interactive: it has no enable state to be unavailable.
                flags[i] = true;
            } else {
                flags[i] = !item.isGroup() && item.mod != null
                        && ModAvailability.isInteractive(item.mod.getId());
            }
        }
        return flags;
    }

    /** One flag per item: a group header spans its own row, so the layout has to say so. */
    private boolean[] fullRowFlags() {
        boolean[] flags = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            flags[i] = item.isGroup() || item.isActionsHeader();
        }
        return flags;
    }

    /**
     * Moves the controller selection by one directional press.
     *
     * <p>Returns true when the press was consumed, so the caller can swallow it and the stick does
     * not also reach the game. The move is computed by {@link ModMenuNavigation}, which is pure and
     * tested; this method only applies the result to the list.
     */
    public boolean handleControllerNavigation(ModMenuNavigation.Direction direction) {
        if (items.isEmpty()) return false;
        int target = ModMenuNavigation.move(controllerFocus, columnCount(), fullRowFlags(),
                selectableFlags(), direction);
        if (target == ModMenuNavigation.NONE || target == controllerFocus) {
            // Nothing to move to: still consumed, so a press at the edge does not make the stick
            // act on the game behind the menu.
            return controllerFocus != ModMenuNavigation.NONE;
        }
        int previous = controllerFocus;
        controllerFocus = target;
        if (previous >= 0) notifyItemChanged(previous);
        notifyItemChanged(target);
        if (recycler != null) recycler.smoothScrollToPosition(target);
        return true;
    }

    /**
     * Activates the focused card: a module toggles (or, when unavailable, says why), a card with a
     * gear opens its settings.
     *
     * <p>The same rules the touch path uses, so a controller cannot switch on a module the touch
     * path refuses.
     */
    public boolean handleControllerSelect() {
        if (controllerFocus < 0 || controllerFocus >= items.size()) return false;
        MenuItem item = items.get(controllerFocus);
        if (item.isGroup() || item.isActionsHeader()) return false;
        if (item.isAction()) {
            // An action row fires once, exactly as its touch path does. Never a switch.
            if (listener != null) listener.onAction(item.action.getId());
            return true;
        }
        if (item.mod == null) return false;
        if (!ModAvailability.isInteractive(item.mod.getId())) return true;
        if (listener != null) listener.onToggle(item.mod, !toggleStates.getOrDefault(item.mod.getStableKey(), false));
        return true;
    }

    /** Clears the controller selection, e.g. when the menu closes. */
    public void clearControllerFocus() {
        if (controllerFocus == ModMenuNavigation.NONE) return;
        int previous = controllerFocus;
        controllerFocus = ModMenuNavigation.NONE;
        if (previous >= 0 && previous < items.size()) notifyItemChanged(previous);
    }

    public void setCompactMode(boolean compactMode) {
        if (this.compactMode == compactMode) return;
        this.compactMode = compactMode;
        items.clear();
        toggleStates.clear();
        favoriteStates.clear();
        notifyDataSetChanged();
    }

    /**
     * Replaces the list with the modules and the one-shot actions for the current section (M3).
     *
     * <p>Actions are appended after the modules under their own {@code ACTIONS} header, never mixed
     * into a module's section: a switch and a chevron must not sit in the same run, because that is
     * exactly the "is this a toggle or a button?" ambiguity the split removes.
     *
     * <p>There is deliberately no module-list-only overload. One existed and the density toggle
     * called it, which silently dropped the ACTIONS block until the next filter change; making the
     * action list a required argument means a caller cannot rebuild the list without deciding what
     * happens to the actions.
     */
    public void updateMods(List<UnifiedMod> mods, Set<String> favoriteKeys,
                           List<ModMenuAction> sectionActions) {
        List<MenuItem> nextItems = new ArrayList<>();
        Map<String, Boolean> nextToggleStates = new HashMap<>();
        Map<String, Boolean> nextFavoriteStates = new HashMap<>();
        String lastGroupId = null;
        for (UnifiedMod mod : mods) {
            boolean firstInGroup = !Objects.equals(mod.getGroupId(), lastGroupId);
            if (!compactMode && firstInGroup) {
                nextItems.add(MenuItem.group(mod.getGroupId(), mod.getGroupName()));
            }
            boolean showGroupLabel = compactMode
                && firstInGroup
                && mod.getSource() == UnifiedMod.Source.EXTERNAL;
            nextItems.add(MenuItem.mod(mod, showGroupLabel));
            lastGroupId = mod.getGroupId();
            nextToggleStates.put(mod.getStableKey(), mod.isEnabled());
            nextFavoriteStates.put(mod.getStableKey(),
                favoriteKeys != null && favoriteKeys.contains(mod.getStableKey()));
        }

        List<ModMenuAction> nextActions = new ArrayList<>();
        if (sectionActions != null) {
            nextActions.addAll(sectionActions);
        }
        if (!nextActions.isEmpty()) {
            nextItems.add(MenuItem.actionsHeader());
            for (ModMenuAction action : nextActions) {
                nextItems.add(MenuItem.action(action));
            }
        }

        List<MenuItem> oldItems = new ArrayList<>(items);
        Map<String, Boolean> oldToggleStates = new HashMap<>(toggleStates);
        Map<String, Boolean> oldFavoriteStates = new HashMap<>(favoriteStates);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldItems.size();
            }

            @Override
            public int getNewListSize() {
                return nextItems.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return oldItems.get(oldItemPosition).stableKey()
                    .equals(nextItems.get(newItemPosition).stableKey());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                MenuItem oldItem = oldItems.get(oldItemPosition);
                MenuItem newItem = nextItems.get(newItemPosition);
                if (oldItem.isGroup() || newItem.isGroup()) {
                    return oldItem.isGroup() == newItem.isGroup()
                        && Objects.equals(oldItem.groupName, newItem.groupName);
                }
                if (oldItem.isAction() || newItem.isAction()) {
                    // An action row is static; its identity (the stable key) is the whole content.
                    return oldItem.isAction() == newItem.isAction()
                        && Objects.equals(oldItem.stableKey(), newItem.stableKey());
                }
                String oldKey = oldItem.mod.getStableKey();
                String newKey = newItem.mod.getStableKey();
                return Objects.equals(oldItem.mod.getName(), newItem.mod.getName())
                    && Objects.equals(oldItem.mod.getDescription(), newItem.mod.getDescription())
                    && oldItem.mod.hasConfig() == newItem.mod.hasConfig()
                    && oldItem.showGroupLabel == newItem.showGroupLabel
                    && oldToggleStates.getOrDefault(oldKey, false)
                        .equals(nextToggleStates.getOrDefault(newKey, false))
                    && oldFavoriteStates.getOrDefault(oldKey, false)
                        .equals(nextFavoriteStates.getOrDefault(newKey, false));
            }
        }, false);

        items.clear();
        items.addAll(nextItems);
        actions.clear();
        actions.addAll(nextActions);
        toggleStates.clear();
        toggleStates.putAll(nextToggleStates);
        favoriteStates.clear();
        favoriteStates.putAll(nextFavoriteStates);
        diff.dispatchUpdatesTo(this);
        // A filter change or a refresh renumbers every position, so the controller selection would
        // point at a different card than the one that was highlighted. Drop it rather than let the
        // next A press toggle whatever moved under the index.
        controllerFocus = ModMenuNavigation.NONE;
    }

    public boolean isGroupHeader(int position) {
        return position >= 0 && position < items.size() && items.get(position).isGroup();
    }

    public void setTheme(ModMenuTheme theme) {
        this.theme = theme != null ? theme : new ModMenuTheme(null);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_GROUP) {
            float density = parent.getResources().getDisplayMetrics().density;
            android.widget.LinearLayout row = new android.widget.LinearLayout(parent.getContext());
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (38 * density));
            params.setMargins((int) (8 * density), (int) (8 * density),
                (int) (8 * density), 0);
            row.setLayoutParams(params);

            View bar = new View(parent.getContext());
            android.widget.LinearLayout.LayoutParams barParams =
                new android.widget.LinearLayout.LayoutParams((int) (3 * density), (int) (16 * density));
            barParams.setMarginEnd((int) (8 * density));
            bar.setLayoutParams(barParams);
            row.addView(bar);

            TextView title = new TextView(parent.getContext());
            title.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setTextSize(11);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title);

            return new GroupViewHolder(row, bar, title);
        }

        int layout = viewType == VIEW_TYPE_MOD_COMPACT || viewType == VIEW_TYPE_ACTION_COMPACT
            ? R.layout.item_mod_menu_compact
            : R.layout.item_mod_menu_card;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new ModViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MenuItem item = items.get(position);
        if (item.isGroup()) {
            GroupViewHolder group = (GroupViewHolder) holder;
            group.title.setText(item.groupName);
            group.title.setTextColor(theme.groupLabelColor());
            GradientDrawable barBg = new GradientDrawable();
            barBg.setShape(GradientDrawable.RECTANGLE);
            barBg.setCornerRadius(2f * group.bar.getResources().getDisplayMetrics().density);
            barBg.setColor(theme.groupAccent());
            group.bar.setBackground(barBg);
            return;
        }

        if (item.isActionsHeader()) {
            GroupViewHolder group = (GroupViewHolder) holder;
            group.title.setText(R.string.mod_menu_actions_header);
            group.title.setTextColor(theme.groupLabelColor());
            GradientDrawable barBg = new GradientDrawable();
            barBg.setShape(GradientDrawable.RECTANGLE);
            barBg.setCornerRadius(2f * group.bar.getResources().getDisplayMetrics().density);
            barBg.setColor(theme.groupAccent());
            group.bar.setBackground(barBg);
            return;
        }

        ModViewHolder modHolder = (ModViewHolder) holder;
        if (item.isAction()) {
            bindActionRow(modHolder, item, position);
            return;
        }
        UnifiedMod mod = item.mod;

        // A recycled holder may have been an action row, which hides the module controls. Restore
        // them before binding a module, or the switch stays gone and the module looks untoggleable.
        restoreModuleRowControls(modHolder);

        modHolder.name.setText(mod.getName());
        if (modHolder.groupText != null) {
            // The caption: the module's short description, or the group name when it has none.
            String caption = mod.getDescription();
            if (caption == null || caption.trim().isEmpty()) {
                caption = item.showGroupLabel ? mod.getGroupName() : null;
            }
            if (caption != null && !caption.trim().isEmpty()) {
                modHolder.groupText.setVisibility(View.VISIBLE);
                modHolder.groupText.setText(caption);
            } else {
                modHolder.groupText.setVisibility(View.GONE);
            }
        }

        if (mod.getSource() == UnifiedMod.Source.INBUILT) {
            modHolder.icon.setImageResource(ModIconHelper.getModIcon(mod.getId()));
            modHolder.icon.setImageTintList(null);
            modHolder.icon.setColorFilter(null);
        } else {
            modHolder.icon.setImageResource(R.drawable.ic_glowberry_mod);
            modHolder.icon.setImageTintList(null);
            modHolder.icon.setColorFilter(null);
        }

        boolean isEnabled = toggleStates.getOrDefault(mod.getStableKey(), false);
        boolean available = ModAvailability.isInteractive(mod.getId());
        ModMenuRowState rowState = rowStateFor(mod, available, isEnabled);
        updateStatusView(modHolder, isEnabled, available);
        updateAccentBar(modHolder, mod.getGroupId(), isEnabled);
        updateFavoriteView(modHolder, favoriteStates.getOrDefault(mod.getStableKey(), false));
        updateUnavailableView(modHolder, mod);
        updateRowStateView(modHolder, mod, rowState);

        // The switch is the state control: a smooth slide rather than a tapped status pill. It is
        // set without animating on bind (a recycled view must not slide from the previous item's
        // state), and it is the one control that both shows and changes the state.
        if (modHolder.switchView != null) {
            // The switch follows the row state (M10): a broken module is not merely off, its
            // control is disabled and shows the error track.
            modHolder.switchView.setInteractive(rowState.isInteractive());
            modHolder.switchView.setFailed(rowState.isBroken());
            // The switch's label is the module name, so TalkBack reads "FPS Counter, On, Switch"
            // rather than an unnamed switch beside a separate text view.
            modHolder.switchView.setLabel(mod.getName());
            modHolder.switchView.setChecked(isEnabled && !rowState.isBroken(), false);
            modHolder.switchView.setReducedMotion(settings != null
                    && settings.effectiveMotion(false, false) == ModMenuSettings.Motion.REDUCED);
            // M6: the toggle-style setting reaches the control, so choosing the checkbox variant
            // actually draws a checkbox rather than leaving the stored preference inert.
            if (settings != null) {
                modHolder.switchView.setToggleStyle(settings.toggleStyle());
            }
            modHolder.switchView.setOnCheckedChangeListener(null);
            modHolder.switchView.setOnCheckedChangeListener(checked -> {
                if (!rowState.isInteractive()) {
                    if (listener != null) listener.onRowReason(mod.getStableKey());
                    return;
                }
                toggleStates.put(mod.getStableKey(), checked);
                updateStatusView(modHolder, checked, true);
                updateAccentBar(modHolder, mod.getGroupId(), checked);
                org.chimeramc.client.ui.animation.ModToggleAnim.play(
                        modHolder.icon, mod.getId(), checked);
                if (listener != null) listener.onToggle(mod, checked);
            });
        }

        View.OnClickListener toggleClick = v -> {
            // A module that cannot do its job must not look toggleable. A broken row opens its
            // reason sheet (M10) rather than flipping a switch that changes nothing on screen.
            if (!rowState.isInteractive()) {
                if (listener != null) {
                    listener.onRowReason(mod.getStableKey());
                } else if (modHolder.unavailableText != null) {
                    // The reason-specific wording: a server-decided module is not waiting for a
                    // feed, so "no game data" would be wrong for it.
                    int reason = ModAvailability.REASON_SERVER_DECIDED.equals(
                            ModAvailability.unavailableReason(mod.getId()))
                            ? R.string.mod_unavailable_reason_server
                            : R.string.mod_unavailable_reason;
                    android.widget.Toast.makeText(modHolder.itemView.getContext(),
                            reason, android.widget.Toast.LENGTH_SHORT).show();
                }
                return;
            }
            boolean newState = !toggleStates.getOrDefault(mod.getStableKey(), false);
            toggleStates.put(mod.getStableKey(), newState);
            updateStatusView(modHolder, newState, true);
            updateAccentBar(modHolder, mod.getGroupId(), newState);
            if (modHolder.switchView != null) {
                modHolder.switchView.setChecked(newState, true);
            }
            // Per-icon behaviour on flip, not a generic bounce: Armor HUD "equips", the Voice mic
            // pulses. Only modules with distinct iconography animate; the rest fall through.
            org.chimeramc.client.ui.animation.ModToggleAnim.play(
                    modHolder.icon, mod.getId(), newState);
            if (listener != null) {
                listener.onToggle(mod, newState);
            }
        };

        modHolder.itemView.setOnClickListener(toggleClick);
        if (modHolder.statusText != null) modHolder.statusText.setOnClickListener(toggleClick);
        modHolder.icon.setOnClickListener(toggleClick);
        DynamicAnim.applyPressScale(modHolder.itemView);

        modHolder.favoriteBtn.setOnClickListener(v -> {
            boolean favorite = !favoriteStates.getOrDefault(mod.getStableKey(), false);
            favoriteStates.put(mod.getStableKey(), favorite);
            updateFavoriteView(modHolder, favorite);
            if (listener != null) {
                listener.onFavoriteChanged(mod, favorite);
            }
        });

        if (mod.hasConfig() && available) {
            modHolder.configBtn.setVisibility(View.VISIBLE);
            modHolder.configBtn.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onConfig(mod);
                }
            });
        } else {
            modHolder.configBtn.setVisibility(View.GONE);
        }

        updateCardState(modHolder, isEnabled, available);
        applyControllerFocus(modHolder, position);
    }

    /**
     * Dresses the card a controller has selected with an accent ring.
     *
     * <p>This is why the overlay root is unfocusable: the platform's own focus highlight is the
     * white wash that made the menu look broken, so the selection is drawn here instead, in the
     * theme accent, on exactly one card.
     */
    private void applyControllerFocus(ModViewHolder holder, int position) {
        boolean focused = position == controllerFocus;
        float density = holder.itemView.getResources().getDisplayMetrics().density;
        if (focused) {
            android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            ring.setCornerRadius(14f * density);
            ring.setColor(0x00000000);
            ring.setStroke(Math.max(2, (int) (2f * density)), theme.accent());
            holder.itemView.setForeground(ring);
            holder.itemView.setAlpha(1f);
            holder.itemView.setTranslationZ(6f * density);
        } else {
            holder.itemView.setForeground(null);
            holder.itemView.setTranslationZ(0f);
        }
    }

    /**
     * Shows or hides the "doesn't work or is bugged" outline.
     *
     * <p>A module whose game feed has no provider is not merely off — it cannot be turned on. The
     * label is the whole point: without it the card looks like every other disabled module and the
     * player assumes their toggle is broken.
     */
    private void updateUnavailableView(ModViewHolder holder, UnifiedMod mod) {
        if (holder.unavailableText == null) return;
        boolean unavailable = ModAvailability.isUnavailable(mod.getId());
        holder.unavailableText.setVisibility(unavailable ? View.VISIBLE : View.GONE);
    }

    @Override
    public int getItemViewType(int position) {
        MenuItem item = items.get(position);
        if (item.isGroup() || item.isActionsHeader()) return VIEW_TYPE_GROUP;
        if (item.isAction()) return compactMode ? VIEW_TYPE_ACTION_COMPACT : VIEW_TYPE_ACTION;
        return compactMode ? VIEW_TYPE_MOD_COMPACT : VIEW_TYPE_MOD;
    }

    /**
     * Restores the controls an action row hides, for a holder recycled from an action to a module.
     *
     * <p>The favourite star's visibility is the module path's to set (a module without a favourite
     * action still shows the star), so it is always re-shown here; the config gear and the switch
     * are shown because a module always has a state to display. A module with no config hides the
     * gear later in the bind.
     */
    private void restoreModuleRowControls(ModViewHolder holder) {
        if (holder.switchView != null) holder.switchView.setVisibility(View.VISIBLE);
        if (holder.favoriteBtn != null) holder.favoriteBtn.setVisibility(View.VISIBLE);
        if (holder.configBtn != null) holder.configBtn.setVisibility(View.VISIBLE);
        if (holder.chevron != null) holder.chevron.setVisibility(View.GONE);
    }

    /**
     * Binds a one-shot action row (M3): icon, title, one-line description and a chevron.
     *
     * <p>An action is not a module, so the row hides every module control — the switch, the
     * favourite star and the settings gear — and shows the chevron instead. Tapping the row fires
     * the action exactly once; there is no state for the row to show, which is the point.
     */
    private void bindActionRow(ModViewHolder holder, MenuItem item, int position) {
        ModMenuAction action = item.action;
        holder.name.setText(action.getTitleRes());

        if (holder.groupText != null) {
            int subtitleRes = action.getSubtitleRes();
            if (subtitleRes != 0) {
                holder.groupText.setVisibility(View.VISIBLE);
                holder.groupText.setText(subtitleRes);
            } else {
                holder.groupText.setVisibility(View.GONE);
            }
        }

        holder.icon.setImageResource(action.getIconRes());
        holder.icon.setImageTintList(null);
        holder.icon.setColorFilter(theme.accent());

        // The action's own controls: a chevron, and none of the module controls.
        if (holder.chevron != null) holder.chevron.setVisibility(View.VISIBLE);
        if (holder.switchView != null) holder.switchView.setVisibility(View.GONE);
        if (holder.favoriteBtn != null) holder.favoriteBtn.setVisibility(View.GONE);
        if (holder.configBtn != null) holder.configBtn.setVisibility(View.GONE);
        if (holder.statusDot != null) holder.statusDot.setVisibility(View.GONE);
        if (holder.reasonText != null) holder.reasonText.setVisibility(View.GONE);
        if (holder.unavailableText != null) holder.unavailableText.setVisibility(View.GONE);

        // An action has no state, so its accent bar is always the section's accent rather than an
        // "enabled" wash.
        if (holder.accentBar != null) {
            GradientDrawable bar = new GradientDrawable();
            bar.setShape(GradientDrawable.RECTANGLE);
            bar.setColor(theme.accent());
            holder.accentBar.setBackground(bar);
            holder.accentBar.setVisibility(View.VISIBLE);
        }

        View.OnClickListener click = v -> {
            if (listener != null) listener.onAction(action.getId());
        };
        holder.itemView.setOnClickListener(click);
        if (holder.icon != null) holder.icon.setOnClickListener(click);
        DynamicAnim.applyPressScale(holder.itemView);
        applyControllerFocus(holder, position);
    }

    private void updateFavoriteView(ModViewHolder holder, boolean favorite) {
        int color = favorite ? UiTokens.WARNING : UiTokens.TEXT_SECONDARY;
        holder.favoriteBtn.setImageTintList(ColorStateList.valueOf(color));
        holder.favoriteBtn.setAlpha(favorite ? 1f : 0.9f);
        holder.favoriteBtn.setContentDescription(holder.favoriteBtn.getContext().getString(
            favorite ? R.string.mod_menu_unfavorite : R.string.mod_menu_favorite));
    }

    private void updateStatusView(ModViewHolder holder, boolean enabled,
                                  boolean available) {
        // The layout is now a switch, not a pill: there is no status TextView. The switch's own
        // state is the indicator, so this only keeps the card's surface in step.
        updateCardState(holder, enabled, available);
    }

    /** Paints the top edge strip so each card carries the accent. */
    private void updateAccentBar(ModViewHolder holder, String groupId, boolean enabled) {
        if (holder.accentBar == null) return;
        int color = theme.accent();
        GradientDrawable bar = new GradientDrawable();
        bar.setShape(GradientDrawable.RECTANGLE);
        bar.setColor(enabled ? color : UiTokens.withAlpha(color, 0x40));
        holder.accentBar.setBackground(bar);
    }

    /**
     * The row state for a module (M10).
     *
     * <p>The overlay can supply an explicit state — it knows about crashes and about builds with no
     * hook — and with none the state is derived from what the list itself knows. Deriving rather
     * than defaulting means a row is never silently presented as healthy.
     */
    private ModMenuRowState rowStateFor(UnifiedMod mod, boolean available, boolean enabled) {
        ModMenuRowState explicit = rowStates.get(mod.getStableKey());
        if (explicit != null) return explicit;
        return ModMenuRowState.of(true, available, false, enabled);
    }

    /**
     * Renders the failure surfacing for a row (M10).
     *
     * <p>A broken row is dimmed, its dot is coloured, its reason is stated in one line and its
     * switch is forced off. This is the part most mod menus skip, and it is what turns the worst
     * support burden into a self-explaining screen.
     */
    private void updateRowStateView(ModViewHolder holder, UnifiedMod mod, ModMenuRowState state) {
        if (holder.statusDot != null) {
            int color = ModMenuPalette.dotFor(state);
            if (color == 0) {
                holder.statusDot.setVisibility(View.GONE);
            } else {
                android.graphics.drawable.GradientDrawable dot =
                        new android.graphics.drawable.GradientDrawable();
                dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dot.setColor(color);
                holder.statusDot.setBackground(dot);
                holder.statusDot.setVisibility(View.VISIBLE);
            }
        }
        if (holder.reasonText != null) {
            String reason = reasonFor(mod, state);
            if (reason == null || reason.trim().isEmpty()) {
                holder.reasonText.setVisibility(View.GONE);
            } else {
                holder.reasonText.setText(reason);
                holder.reasonText.setVisibility(View.VISIBLE);
            }
        }
        if (state.isBroken()) {
            holder.itemView.setAlpha(theme.rowAlpha(state));
            if (holder.switchView != null) holder.switchView.setChecked(false, false);
        }
    }

    /**
     * The one-line reason a row cannot run, or null when it can.
     *
     * <p>The wording is specific on purpose: a module with no hook on this build and a module whose
     * game feed has no provider are different problems with different fixes, and a generic
     * "unavailable" tells the player neither.
     */
    private String reasonFor(UnifiedMod mod, ModMenuRowState state) {
        android.content.Context context = holderContext();
        if (context == null) return null;
        switch (state) {
            case FAILED:
                return context.getString(R.string.mod_menu_row_failed);
            case CRASHED:
                return context.getString(R.string.mod_menu_row_crashed);
            case UNSUPPORTED:
                if (ModAvailability.isUnavailable(mod.getId())) {
                    int reason = ModAvailability.REASON_SERVER_DECIDED.equals(
                            ModAvailability.unavailableReason(mod.getId()))
                            ? R.string.mod_unavailable_reason_server
                            : R.string.mod_unavailable_reason;
                    return context.getString(reason);
                }
                return context.getString(R.string.mod_menu_row_unsupported);
            default:
                return null;
        }
    }

    /**
     * Applies the density's row height to a card's row (M6).
     *
     * <p>The row is found from the switch rather than by child index: the card's first child is the
     * accent strip, so an index walk reached a 2dp view and did nothing. The row's own
     * {@code minHeight} is what the layout pins, so the setting goes on {@code minimumHeight} — the
     * row keeps {@code wrap_content} and a taller label still grows it rather than being clipped.
     */
    private void applyRowHeight(android.view.View card, int rowHeightDp) {
        android.view.View row = findRowContainer(card);
        if (row == null) return;
        float density = card.getResources().getDisplayMetrics().density;
        int target = (int) (rowHeightDp * density);
        // Never shrink below what the content already needs.
        if (row.getMinimumHeight() != target) row.setMinimumHeight(target);
    }

    /**
     * The row container inside a card, located through the switch (M6).
     *
     * <p>Returns the switch's parent, which is the horizontal row holding the icon, the text column
     * and the controls. Null when the card has no switch (an action row), in which case there is
     * nothing to size.
     */
    private android.view.View findRowContainer(android.view.View card) {
        android.view.View switchView = card.findViewById(R.id.mod_card_switch);
        if (switchView == null) return null;
        android.view.ViewParent parent = switchView.getParent();
        return parent instanceof android.view.View ? (android.view.View) parent : null;
    }

    /** The adapter holds no Context; the attached RecyclerView supplies one. */
    private android.content.Context holderContext() {
        return recycler != null ? recycler.getContext() : null;
    }

    private void updateCardState(ModViewHolder holder, boolean enabled, boolean available) {
        // Unavailable wins over the enabled/disabled tint: a greyed card is the whole signal.
        holder.itemView.setAlpha(available ? 1f : 0.55f);
        holder.itemView.setEnabled(available);
        holder.itemView.setClickable(available);
        holder.icon.setAlpha(!available ? 0.5f : (enabled ? 1f : 0.8f));
        holder.name.setTextColor(!available ? theme.textTertiary()
                : (enabled ? theme.textPrimary() : ModMenuPalette.blend(
                theme.textPrimary(), theme.textSecondary(), 0.4f)));
        
        if (holder.itemView instanceof androidx.cardview.widget.CardView) {
            androidx.cardview.widget.CardView cv = (androidx.cardview.widget.CardView) holder.itemView;

            // M6: the corner-radius slider and the density both reach the row here. The radius was
            // stored and displayed but the card's radius came from the layout's fixed dp, so the
            // slider moved nothing.
            float density = holder.itemView.getResources().getDisplayMetrics().density;
            cv.setRadius(theme.cornerRadiusDp() * density);
            applyRowHeight(cv, theme.rowHeightDp());

            if (!available) {
                cv.setCardBackgroundColor(theme.cardSurface());
                cv.setCardElevation(0f);
            } else if (enabled) {
                cv.setCardBackgroundColor(theme.enabledCardColor());
                cv.setCardElevation(theme.enabledElevation());
            } else {
                cv.setCardBackgroundColor(theme.disabledCardColor());
                cv.setCardElevation(theme.disabledElevation());
            }
        }
    }


    @Override
    public long getItemId(int position) {
        String key = items.get(position).stableKey();
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static class MenuItem {
        /** A sentinel id for the synthetic {@code ACTIONS} header, which has no group. */
        static final String ACTIONS_HEADER_ID = "__actions__";
        final String groupId;
        final String groupName;
        final UnifiedMod mod;
        final boolean showGroupLabel;
        /** The action this row fires, or null for a module/header row (M3). */
        final ModMenuAction action;

        private MenuItem(String groupId, String groupName, UnifiedMod mod, boolean showGroupLabel,
                         ModMenuAction action) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.mod = mod;
            this.showGroupLabel = showGroupLabel;
            this.action = action;
        }

        static MenuItem group(String groupId, String groupName) {
            return new MenuItem(groupId, groupName, null, false, null);
        }

        static MenuItem mod(UnifiedMod mod, boolean showGroupLabel) {
            return new MenuItem(null, null, mod, showGroupLabel, null);
        }

        /** The synthetic section header above the action rows (M3). */
        static MenuItem actionsHeader() {
            return new MenuItem(ACTIONS_HEADER_ID, null, null, false, null);
        }

        static MenuItem action(ModMenuAction action) {
            return new MenuItem(null, null, null, false, action);
        }

        boolean isGroup() {
            // A section header (or the ACTIONS header) is a group; an action row is not.
            return mod == null && action == null;
        }

        boolean isActionsHeader() {
            return mod == null && action == null && ACTIONS_HEADER_ID.equals(groupId);
        }

        boolean isAction() {
            return action != null;
        }

        String stableKey() {
            if (isAction()) {
                return action.getStableKey();
            }
            if (isActionsHeader()) {
                return "header:" + ACTIONS_HEADER_ID;
            }
            return isGroup() ? "group:" + groupId : "mod:" + mod.getStableKey();
        }
    }

    static class GroupViewHolder extends RecyclerView.ViewHolder {
        final View bar;
        final TextView title;

        GroupViewHolder(View itemView, View bar, TextView title) {
            super(itemView);
            this.bar = bar;
            this.title = title;
        }
    }

    static class ModViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;
        TextView statusText;
        TextView groupText;
        ImageButton favoriteBtn;
        ImageButton configBtn;
        TextView unavailableText;
        TextView reasonText;
        View accentBar;
        View statusDot;
        ImageView chevron;
        ModMenuSwitchView switchView;

        ModViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.mod_card_icon);
            name = itemView.findViewById(R.id.mod_card_name);
            groupText = itemView.findViewById(R.id.mod_card_group);
            favoriteBtn = itemView.findViewById(R.id.mod_card_favorite);
            configBtn = itemView.findViewById(R.id.mod_card_config);
            unavailableText = itemView.findViewById(R.id.mod_card_unavailable);
            reasonText = itemView.findViewById(R.id.mod_card_reason);
            accentBar = itemView.findViewById(R.id.mod_card_accent);
            statusDot = itemView.findViewById(R.id.mod_card_status_dot);
            chevron = itemView.findViewById(R.id.mod_card_chevron);
            switchView = itemView.findViewById(R.id.mod_card_switch);
            // 27dp config and favourite buttons in a card. Enlarging the draw would push the row
            // apart, so the touch area is grown instead and the visuals stay as designed.
            TouchTargetHelper.expand(favoriteBtn);
            TouchTargetHelper.expand(configBtn);
        }
    }
}
