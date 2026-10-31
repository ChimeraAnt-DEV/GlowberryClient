package org.chimeramc.client.launcher.ui.activities;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chimeramc.client.ui.components.UiShape;
import org.chimeramc.client.ui.components.UiTokens;

/**
 * The hidden UI gallery: every shared component in every state, so a reviewer can compare the app
 * against the reference on a real phone. Reached from Settings &gt; Updates by tapping the version
 * seven times. It is a debug surface — nothing here is product UI — so it draws plainly with the
 * same tokens the product screens use.
 */
public class UiGalleryActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float density = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiTokens.BG_WINDOW);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (UiTokens.SPACE_4 * density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        int accent = UiTokens.resolveAccent(this);
        addTitle(root, density, "GlowberryClient — UI gallery");
        addTitle(root, density, "Accent: #" + Integer.toHexString(accent).toUpperCase());

        addSection(root, density, "Colours");
        addSwatch(root, density, "bg.window", UiTokens.BG_WINDOW);
        addSwatch(root, density, "bg.surface", UiTokens.BG_SURFACE);
        addSwatch(root, density, "bg.surface.hover", UiTokens.BG_SURFACE_HOVER);
        addSwatch(root, density, "accent.500", UiTokens.ACCENT_500);
        addSwatch(root, density, "accent.400", UiTokens.ACCENT_400);
        addSwatch(root, density, "accent.300", UiTokens.ACCENT_300);
        addSwatch(root, density, "accent.900", UiTokens.ACCENT_900);
        addSwatch(root, density, "success", UiTokens.SUCCESS);
        addSwatch(root, density, "warning", UiTokens.WARNING);
        addSwatch(root, density, "danger", UiTokens.DANGER);

        addSection(root, density, "Type roles (one variable family)");
        addRole(root, density, "Display 22sp/600", org.chimeramc.client.ui.components.UiType.Role.DISPLAY);
        addRole(root, density, "Title 16sp/600", org.chimeramc.client.ui.components.UiType.Role.TITLE);
        addRole(root, density, "Body 14sp/400", org.chimeramc.client.ui.components.UiType.Role.BODY);
        addRole(root, density, "Label 13sp/500", org.chimeramc.client.ui.components.UiType.Role.LABEL);
        addRole(root, density, "Caption 11sp/400", org.chimeramc.client.ui.components.UiType.Role.CAPTION);
        addLabel(root, density, "Section 10sp/600 caps", UiTokens.TEXT_SECTION,
                UiTokens.TEXT_SECONDARY);

        addSection(root, density, "Shape scale (4 / 8 / 12 / 16 / 28 / pill)");
        addShapeSample(root, density, UiShape.Role.INDICATOR);
        addShapeSample(root, density, UiShape.Role.CHIP);
        addShapeSample(root, density, UiShape.Role.FIELD);
        addShapeSample(root, density, UiShape.Role.CARD);
        addShapeSample(root, density, UiShape.Role.WINDOW);

        addSection(root, density, "Toggle (off / on / disabled)");
        root.addView(new UiToggleView(this, accent, false, false));
        root.addView(new UiToggleView(this, accent, true, false));
        root.addView(new UiToggleView(this, accent, false, true));

        addSection(root, density, "Row (off / on)");
        root.addView(new UiRowView(this, accent, false, false));
        root.addView(new UiRowView(this, accent, true, false));

        addSection(root, density, "Window chrome");
        root.addView(new UiWindowView(this, density));

        addSection(root, density, "Segmented (first / second selected)");
        root.addView(new UiSegmentedView(this, accent, 0));
        root.addView(new UiSegmentedView(this, accent, 1));

        addSection(root, density, "Search field (empty / with text)");
        root.addView(new UiSearchFieldView(this, false));
        root.addView(new UiSearchFieldView(this, true));

        addSection(root, density, "Slider (25% / 75%)");
        root.addView(new UiSliderView(this, accent, 25f));
        root.addView(new UiSliderView(this, accent, 75f));

        addSection(root, density, "Buttons (primary / secondary / pressed / disabled)");
        root.addView(new UiButtonView(this, accent, org.chimeramc.client.ui.components.UiButton.Style.PRIMARY, false, true));
        root.addView(new UiButtonView(this, accent, org.chimeramc.client.ui.components.UiButton.Style.SECONDARY, false, true));
        root.addView(new UiButtonView(this, accent, org.chimeramc.client.ui.components.UiButton.Style.PRIMARY, true, true));
        root.addView(new UiButtonView(this, accent, org.chimeramc.client.ui.components.UiButton.Style.SECONDARY, false, false));

        addSection(root, density, "Card surface");
        root.addView(new UiCardView(this));

        addSection(root, density, "Rail buttons (selected / unselected)");
        root.addView(new UiRailButtonView(this, accent, true));
        root.addView(new UiRailButtonView(this, accent, false));

        setContentView(scroll);
    }

    private void addTitle(LinearLayout root, float density, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(UiTokens.TEXT_PRIMARY);
        tv.setTextSize(UiTokens.TEXT_TITLE);
        tv.setPadding(0, (int) (UiTokens.SPACE_2 * density), 0, (int) (UiTokens.SPACE_2 * density));
        root.addView(tv);
    }

    private void addSection(LinearLayout root, float density, String text) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase());
        tv.setTextColor(UiTokens.TEXT_SECONDARY);
        tv.setTextSize(UiTokens.TEXT_SECTION);
        tv.setLetterSpacing(UiTokens.SECTION_LETTER_SPACING);
        tv.setPadding(0, (int) (UiTokens.SPACE_4 * density), 0, (int) (UiTokens.SPACE_2 * density));
        root.addView(tv);
    }

    /** A sample rendered by the shared {@code UiType} role, so the gallery shows the real font. */
    private void addRole(LinearLayout root, float density,
                         String text, org.chimeramc.client.ui.components.UiType.Role role) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(UiTokens.TEXT_PRIMARY);
        org.chimeramc.client.ui.components.UiType.apply(tv, role);
        root.addView(tv);
    }

    /** A rounded rectangle at one stop of the shape scale, labelled with its dp value. */
    private void addShapeSample(LinearLayout root, float density,
                                org.chimeramc.client.ui.components.UiShape.Role role) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View box = new View(this);
        int h = (int) (32 * density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (72 * density), h);
        lp.rightMargin = (int) (UiTokens.SPACE_3 * density);
        box.setLayoutParams(lp);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(UiTokens.BG_SURFACE_HOVER);
        shape.setCornerRadius(org.chimeramc.client.ui.components.UiShape
                .clampToBox(role.radiusDp * density, lp.width, h));
        box.setBackground(shape);
        row.addView(box);
        TextView label = new TextView(this);
        label.setText(role.name() + "  " + role.radiusDp + "dp");
        label.setTextColor(UiTokens.TEXT_SECONDARY);
        label.setTextSize(UiTokens.TEXT_CAPTION);
        row.addView(label);
        row.setPadding(0, (int) (UiTokens.SPACE_1 * density), 0, (int) (UiTokens.SPACE_1 * density));
        root.addView(row);
    }

    private void addLabel(LinearLayout root, float density, String text, float size, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(size);
        root.addView(tv);
    }

    private void addSwatch(LinearLayout root, float density, String name, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View box = new View(this);
        int size = (int) (28 * density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = (int) (UiTokens.SPACE_3 * density);
        box.setLayoutParams(lp);
        box.setBackgroundColor(color);
        row.addView(box);
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(UiTokens.TEXT_SECONDARY);
        label.setTextSize(UiTokens.TEXT_CAPTION);
        row.addView(label);
        row.setPadding(0, (int) (UiTokens.SPACE_1 * density), 0, (int) (UiTokens.SPACE_1 * density));
        root.addView(row);
    }

    /** A live window chrome rendered by {@code UiWindow}, at the real panel geometry. */
    private static final class UiWindowView extends View {
        private final float density;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        // Reused so the draw path allocates nothing; the gallery repaints on every state change.
        private final android.graphics.RectF scratch = new android.graphics.RectF();

        UiWindowView(Activity a, float density) {
            super(a);
            this.density = density;
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (180 * density)));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            scratch.set(0, 0, getWidth(), getHeight() - 8 * density);
            org.chimeramc.client.ui.components.UiWindow.draw(canvas, paint, scratch,
                    UiTokens.RADIUS_WINDOW * density, density, 12 * density, 0.35f);
        }
    }

    /** A live segmented control rendered by {@code UiSegmented}. */
    private static final class UiSegmentedView extends View {
        private final int accent;
        private final int selected;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF scratch = new android.graphics.RectF();
        private final android.graphics.Paint text = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);

        UiSegmentedView(Activity a, int accent, int selected) {
            super(a);
            this.accent = accent;
            this.selected = selected;
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (44 * a.getResources()
                    .getDisplayMetrics().density)));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float h = org.chimeramc.client.ui.components.UiSegmented.HEIGHT_DP * density;
            scratch.set(0, (getHeight() - h) / 2f, Math.min(getWidth(), 240 * density),
                    (getHeight() + h) / 2f);
            org.chimeramc.client.ui.components.UiSegmented.draw(canvas, paint, text, scratch,
                    new String[]{"Navy", "Black", "System"}, selected, accent, density);
        }
    }

    /** A live search field rendered by {@code UiSearchField}. */
    private static final class UiSearchFieldView extends View {
        private final boolean withText;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF scratch = new android.graphics.RectF();

        UiSearchFieldView(Activity a, boolean withText) {
            super(a);
            this.withText = withText;
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (44 * a.getResources()
                    .getDisplayMetrics().density)));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float h = org.chimeramc.client.ui.components.UiSearchField.HEIGHT_DP * density;
            scratch.set(0, (getHeight() - h) / 2f, Math.min(getWidth(), 240 * density),
                    (getHeight() + h) / 2f);
            org.chimeramc.client.ui.components.UiSearchField.draw(canvas, paint, scratch,
                    withText ? "fps" : "", withText, density);
        }
    }

    /** A live slider rendered by {@code UiSlider} at the given percentage. */
    private static final class UiSliderView extends View {
        private final int accent;
        private final float percent;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);

        UiSliderView(Activity a, int accent, float percent) {
            super(a);
            this.accent = accent;
            this.percent = percent;
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (32 * a.getResources()
                    .getDisplayMetrics().density)));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float right = Math.min(getWidth(), 240 * density);
            org.chimeramc.client.ui.components.UiSlider.draw(canvas, paint,
                    8 * density, right - 8 * density, getHeight() / 2f,
                    percent, 0f, 100f, accent, density);
        }
    }

    /** A live button rendered by {@code UiButton}. */
    private static final class UiButtonView extends View {
        private final int accent;
        private final org.chimeramc.client.ui.components.UiButton.Style style;
        private final boolean pressed;
        private final boolean enabled;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF scratch = new android.graphics.RectF();

        UiButtonView(Activity a, int accent, org.chimeramc.client.ui.components.UiButton.Style style,
                     boolean pressed, boolean enabled) {
            super(a);
            this.accent = accent;
            this.style = style;
            this.pressed = pressed;
            this.enabled = enabled;
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (56 * a.getResources()
                    .getDisplayMetrics().density)));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            scratch.set(0, 6 * density, Math.min(getWidth(), 180 * density), getHeight() - 6 * density);
            org.chimeramc.client.ui.components.UiButton.draw(canvas, paint, scratch,
                    UiTokens.RADIUS_BUTTON * density, accent, style, pressed, enabled);
        }
    }

    /** A live card surface rendered by {@code UiCard}. */
    private static final class UiCardView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF scratch = new android.graphics.RectF();

        UiCardView(Activity a) {
            super(a);
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (72 * a.getResources()
                    .getDisplayMetrics().density)));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            scratch.set(0, 6 * density, Math.min(getWidth(), 260 * density), getHeight() - 6 * density);
            org.chimeramc.client.ui.components.UiCard.draw(canvas, paint, scratch,
                    UiTokens.RADIUS_CARD * density);
        }
    }

    /** A live rail button rendered by {@code UiRailButton}. */
    private static final class UiRailButtonView extends View {
        private final int accent;
        private final boolean selected;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);

        UiRailButtonView(Activity a, int accent, boolean selected) {
            super(a);
            this.accent = accent;
            this.selected = selected;
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * a.getResources()
                    .getDisplayMetrics().density)));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            android.graphics.RectF b = org.chimeramc.client.ui.components.UiRailButton
                    .bounds(0f, (getHeight() - UiTokens.RAIL_BUTTON * density) / 2f, density, 0);
            org.chimeramc.client.ui.components.UiRailButton.drawBackground(canvas, paint, b,
                    UiTokens.RADIUS_BUTTON * density, accent, selected, false);
            paint.setColor(org.chimeramc.client.ui.components.UiRailButton
                    .iconColor(accent, selected));
            canvas.drawCircle(b.centerX(), b.centerY(), 8 * density, paint);
        }
    }

    /** A live toggle rendered by {@code UiToggle}, at a fixed 36x20dp. */
    private static final class UiToggleView extends View {
        private final int accent;
        private final boolean on;
        private final boolean disabled;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);

        UiToggleView(Activity a, int accent, boolean on, boolean disabled) {
            super(a);
            this.accent = accent;
            this.on = on;
            this.disabled = disabled;
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * a.getResources()
                    .getDisplayMetrics().density)));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float w = UiTokens.TOGGLE_WIDTH * density;
            float h = UiTokens.TOGGLE_HEIGHT * density;
            float top = (getHeight() - h) / 2f;
            int c = disabled ? UiTokens.TEXT_DISABLED : accent;
            org.chimeramc.client.ui.components.UiToggle.draw(canvas, paint, 0f, top, w, h,
                    c, on, on ? 1f : 0f, density);
        }
    }

    /** A live module row rendered by {@code UiRow}. */
    private static final class UiRowView extends View {
        private final int accent;
        private final boolean on;
        private final boolean pressed;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF scratch = new android.graphics.RectF();
        private final android.graphics.RectF scratch2 = new android.graphics.RectF();
        private final android.graphics.Paint text = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);

        UiRowView(Activity a, int accent, boolean on, boolean pressed) {
            super(a);
            this.accent = accent;
            this.on = on;
            this.pressed = pressed;
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * a.getResources()
                    .getDisplayMetrics().density)));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            scratch.set(0, 4 * density, getWidth(), getHeight() - 4 * density);
            org.chimeramc.client.ui.components.UiRow.drawBackground(canvas, paint, scratch,
                    UiTokens.RADIUS_CARD * density, accent, on, pressed);
            text.setColor(UiTokens.TEXT_PRIMARY);
            text.setTextSize(UiTokens.TEXT_ROW_TITLE * density);
            android.graphics.Paint.FontMetrics fm = text.getFontMetrics();
            float ty = scratch.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText("Example module", scratch.left + 44 * density, ty, text);
            org.chimeramc.client.ui.components.UiRow.toggleBoundsInto(scratch2, scratch, density);
            org.chimeramc.client.ui.components.UiToggle.draw(canvas, paint,
                    scratch2.left, scratch2.top, scratch2.width(), scratch2.height(),
                    accent, on, on ? 1f : 0f, density);
        }
    }
}
