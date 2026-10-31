package org.chimeramc.client.core.mods.inbuilt.overlay;

import org.chimeramc.client.core.mods.inbuilt.model.ModIds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a wheel entry can be assigned to.
 *
 * <p>The manager's entry editor needs a fixed list to pick from. Without one it would have to ask
 * the live module registry, which only exists during a session, so a profile could not be edited
 * outside the game. This catalog is that list, built from the same {@link ModIds} constants the
 * module registry uses, so an id offered here is always an id the wheel can resolve.
 *
 * <p>Pure (ids, labels and resource ids only), so the set and its labels are unit-testable.
 */
public final class WheelEntryCatalog {

    /** One assignable action. */
    public static final class Choice {
        /** The {@link WheelEntry.Action} the entry will carry. */
        public final WheelEntry.Action action;
        /** The module id for {@code TOGGLE_MODULE}, or the destination/profile id otherwise. */
        public final String target;
        /** The label resource, or 0 when {@link #literalLabel} is used. */
        public final int labelRes;
        /** A literal label for a module, which has no string resource of its own. */
        public final String literalLabel;

        Choice(WheelEntry.Action action, String target, int labelRes, String literalLabel) {
            this.action = action;
            this.target = target;
            this.labelRes = labelRes;
            this.literalLabel = literalLabel;
        }
    }

    private static final List<Choice> CHOICES = Collections.unmodifiableList(build());

    private WheelEntryCatalog() {
    }

    private static List<Choice> build() {
        List<Choice> out = new ArrayList<>();
        // The four destinations are the wheel's own navigation, so they come first.
        out.add(new Choice(WheelEntry.Action.OPEN_MODS, "", 0, "Mods"));
        out.add(new Choice(WheelEntry.Action.OPEN_CLOSET, "", 0, "Closet"));
        out.add(new Choice(WheelEntry.Action.OPEN_HUD, "", 0, "HUD"));
        out.add(new Choice(WheelEntry.Action.OPEN_SETTINGS, "", 0, "Settings"));

        // The toggles a player actually puts on a wheel: the PvP set and the readouts.
        out.add(module(ModIds.SNAPLOOK));
        out.add(module(ModIds.CPS_DISPLAY));
        out.add(module(ModIds.HIT_REGISTRATION));
        out.add(module(ModIds.HIT_TIMING));
        out.add(module(ModIds.HITBOX));
        out.add(module(ModIds.ARMOR_HUD));
        out.add(module(ModIds.CRYSTAL_OPTIMIZER));
        out.add(module(ModIds.REACH_INDICATOR));
        out.add(module(ModIds.HIT_PREDICTION));
        out.add(module(ModIds.TRAJECTORY_PREDICTION));
        out.add(module(ModIds.KILL_EFFECTS));

        out.add(module(ModIds.AUTO_SPRINT));
        out.add(module(ModIds.ZOOM));
        out.add(module(ModIds.GYRO));
        out.add(module(ModIds.FPS_DISPLAY));
        out.add(module(ModIds.FULLBRIGHT));
        out.add(module(ModIds.FREECAM));
        out.add(module(ModIds.VOICE_CHAT));
        out.add(module(ModIds.POJAV_CONTROLS));
        out.add(module(ModIds.HOTBAR_SLOT));
        out.add(module(ModIds.OFFHAND));
        out.add(module(ModIds.TOGGLE_HUD));
        return out;
    }

    private static Choice module(String moduleId) {
        return new Choice(WheelEntry.Action.TOGGLE_MODULE, moduleId, 0, prettyLabel(moduleId));
    }

    /** A module id turned into a readable sector label: {@code auto_sprint} becomes {@code Auto Sprint}. */
    public static String prettyLabel(String moduleId) {
        if (moduleId == null || moduleId.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (int i = 0; i < moduleId.length(); i++) {
            char c = moduleId.charAt(i);
            if (c == '_') {
                upper = true;
                sb.append(' ');
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Every assignable choice, in display order. */
    public static List<Choice> all() {
        return CHOICES;
    }

    /**
     * The choices plus one sub-wheel entry per profile in {@code profiles}.
     *
     * <p>A sub-wheel is offered only for a profile other than the one being edited, because an entry
     * that opens its own wheel would recurse forever; {@link WheelProfileStore#setEntries} refuses
     * it too, so the two agree.
     */
    public static List<Choice> forProfile(List<WheelEntry.Profile> profiles, String editingProfileId) {
        List<Choice> out = new ArrayList<>(CHOICES);
        if (profiles == null) return out;
        for (WheelEntry.Profile p : profiles) {
            if (p == null || p.id == null || p.id.equals(editingProfileId)) continue;
            out.add(new Choice(WheelEntry.Action.OPEN_SUBWHEEL, p.id, 0,
                    p.name == null || p.name.trim().isEmpty() ? p.id : p.name.trim()));
        }
        return out;
    }

    /** The display label for a choice, resolved against the activity's resources. */
    public static String label(Choice choice, android.content.Context context) {
        if (choice == null) return "—";
        if (choice.labelRes != 0 && context != null) return context.getString(choice.labelRes);
        return choice.literalLabel == null ? "—" : choice.literalLabel;
    }

    /** The entry a choice builds, with its label already set. */
    public static WheelEntry toEntry(Choice choice, android.content.Context context) {
        return new WheelEntry(choice.action, choice.target, label(choice, context));
    }
}
