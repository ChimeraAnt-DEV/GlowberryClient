package org.chimeramc.client.ui.components;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.chimeramc.client.R;

/**
 * The five type roles as one call (U8).
 *
 * <p>A screen picks a role — display, title, body, label or caption — and this applies the family,
 * the size and the weight together. Setting a size by hand is what lets two screens that show the
 * same kind of text drift apart, so the roles are the only public entry point.
 *
 * <p>The family is the app's one <b>CJK-capable</b> family. It is deliberately not a variable
 * Latin family: no font shipped here is both variable and CJK-capable, and the app ships Chinese,
 * Japanese and Hindi, so a Latin-only family would render those locales as tofu. A role therefore
 * carries a size and a family, and a role that wants emphasis relies on the bold flag;
 * {@link #applyWeight} is the continuous {@code wght} path and is only meaningful on a surface
 * that has deliberately chosen a variable family (the user-selectable font in
 * {@code LauncherFonts}), where {@code fontVariationSettings} (API 26+, and minSdk is 28) applies.
 *
 * <p>{@code TypeRoleTest} reads {@code res/values/styles.xml} and asserts that
 * {@code TextAppearance.Glowberry.*} carries the same sizes this class does, so the XML half and
 * the code half cannot drift apart.
 */
public final class UiType {

    private UiType() {
    }

    /** The one family every role uses. The app's only CJK-capable family. */
    public static final int FAMILY = R.font.misans;

    /** The five roles, in descending prominence. */
    public enum Role {
        DISPLAY(UiTokens.TEXT_DISPLAY, UiTokens.DISPLAY_WEIGHT),
        TITLE(UiTokens.TEXT_TITLE_ROLE, UiTokens.TITLE_WEIGHT),
        BODY(UiTokens.TEXT_BODY, UiTokens.BODY_WEIGHT),
        LABEL(UiTokens.TEXT_LABEL, UiTokens.ROW_TITLE_WEIGHT),
        CAPTION(UiTokens.TEXT_CAPTION, UiTokens.CAPTION_WEIGHT);

        public final float sizeSp;
        public final int weight;

        Role(float sizeSp, int weight) {
            this.sizeSp = sizeSp;
            this.weight = weight;
        }
    }

    /**
     * The {@code fontVariationSettings} string for a weight, e.g. {@code "'wght' 600"}.
     *
     * <p>Pure, so the exact string the platform is handed is unit-tested rather than assumed.
     */
    public static String weightAxis(int weight) {
        int clamped = Math.max(1, Math.min(1000, weight));
        return "'wght' " + clamped;
    }

    /**
     * Applies a role's family, size and weight to a {@code TextView}.
     *
     * <p>Emphasis is the bold flag, not a {@code wght} axis: the role family is the CJK-capable
     * static font, and asking a static font for a variation axis is at best ignored and on some
     * platform versions resets the typeface. A caller that has deliberately selected a variable
     * family (the user's font choice) can still call {@link #applyWeight} itself.
     */
    public static void apply(TextView view, Role role) {
        if (view == null || role == null) return;
        Context context = view.getContext();
        Typeface base = androidx.core.content.res.ResourcesCompat.getFont(context, FAMILY);
        if (base == null) base = Typeface.DEFAULT;
        view.setTypeface(base, role.weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        view.setTextSize(role.sizeSp);
    }

    /**
     * Sets the weight axis on a {@code TextView} whose family is already a variable font.
     *
     * <p>Kept separate from {@link #apply} because it is only meaningful for a variable family:
     * the role family is static, so a role expresses emphasis with the bold flag instead. The
     * user-selectable fonts in {@code LauncherFonts} include variable ones, and those are the
     * surfaces this is for.
     */
    public static void applyWeight(TextView view, int weight) {
        if (view == null) return;
        view.setFontVariationSettings(weightAxis(weight));
    }

    /** The role whose size is closest to [sizeSp]; used by the UI gallery to name a sample. */
    @Nullable
    public static Role closestRole(float sizeSp) {
        Role best = null;
        float bestDelta = Float.MAX_VALUE;
        for (Role role : Role.values()) {
            float delta = Math.abs(role.sizeSp - sizeSp);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = role;
            }
        }
        return best;
    }
}
