package org.chimeramc.client.ui.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pins the shared UI tokens: the exact palette values, the contrast ratios the spec requires
 * (text.primary on bg.surface &gt;= 7:1, text.secondary &gt;= 4.5:1) and the toggle state machine.
 * Pure JVM — no device, and no Android graphics calls.
 */
public class UiTokensTest {

    @Test
    public void thePaletteMatchesTheSpec() {
        assertEquals(0xFF17191D, UiTokens.BG_SURFACE);
        assertEquals(0xFF1E2126, UiTokens.BG_SURFACE_HOVER);
        assertEquals(0xFF0F5BE4, UiTokens.ACCENT_500);
        assertEquals(0xFF3682E2, UiTokens.ACCENT_400);
        assertEquals(0xFF71ACEF, UiTokens.ACCENT_300);
        assertEquals(0xFF10254F, UiTokens.ACCENT_900);
        assertEquals(0xFFE8EAED, UiTokens.TEXT_PRIMARY);
        assertEquals(0xFF9AA0A6, UiTokens.TEXT_SECONDARY);
        assertEquals(0xFF5F6368, UiTokens.TEXT_DISABLED);
        assertEquals(0xFF2A2D33, UiTokens.TOGGLE_OFF_TRACK);
    }

    @Test
    public void theWindowIsNearlyOpaque() {
        int alpha = (UiTokens.BG_WINDOW >>> 24) & 0xFF;
        assertTrue("window should be ~0.96 alpha", alpha >= 240 && alpha <= 250);
    }

    @Test
    public void textPrimaryOnSurfaceMeetsWcagAaa() {
        double ratio = UiTokens.contrastRatio(UiTokens.TEXT_PRIMARY, UiTokens.BG_SURFACE);
        assertTrue("text.primary contrast was " + ratio, ratio >= 7.0);
    }

    @Test
    public void textSecondaryOnSurfaceMeetsWcagAa() {
        double ratio = UiTokens.contrastRatio(UiTokens.TEXT_SECONDARY, UiTokens.BG_SURFACE);
        assertTrue("text.secondary contrast was " + ratio, ratio >= 4.5);
    }

    @Test
    public void textPrimaryOnWindowMeetsWcagAa() {
        double ratio = UiTokens.contrastRatio(UiTokens.TEXT_PRIMARY, UiTokens.BG_WINDOW);
        assertTrue("text.primary on window contrast was " + ratio, ratio >= 4.5);
    }

    @Test
    public void blendAndAlphaArePureIntegerMaths() {
        assertEquals(0xFF000000, UiTokens.blend(0xFF000000, 0xFFFFFFFF, 0f));
        assertEquals(0xFFFFFFFF, UiTokens.blend(0xFF000000, 0xFFFFFFFF, 1f));
        // Half blend of black and white is ~0x808080.
        int mid = UiTokens.blend(0xFF000000, 0xFFFFFFFF, 0.5f);
        assertEquals(0x7F, (mid >>> 16) & 0xFF);
        assertEquals(128, UiTokens.withAlpha(0xFF0F5BE4, 128) >>> 24);
    }

    @Test
    public void theDefaultAccentIsBlue() {
        assertEquals(UiTokens.ACCENT_500, UiTokens.resolveAccent(null));
    }

    @Test
    public void toggleTrackColourReflectsState() {
        assertEquals(UiTokens.ACCENT_500, UiToggle.trackColor(UiTokens.ACCENT_500, true));
        assertEquals(UiTokens.TOGGLE_OFF_TRACK, UiToggle.trackColor(UiTokens.ACCENT_500, false));
    }

    @Test
    public void toggleThumbTravelsTheTrack() {
        float left = 100f, width = 36f, height = 20f, thumb = 16f, inset = 2f;
        float off = UiToggle.thumbCenterX(left, width, height, thumb, inset, false, 1f);
        float on = UiToggle.thumbCenterX(left, width, height, thumb, inset, true, 1f);
        assertEquals(left + inset + thumb / 2f, off, 0.01f);
        assertEquals(left + width - inset - thumb / 2f, on, 0.01f);
        assertTrue(on > off);
    }

    @Test
    public void toggleFractionClamps() {
        assertEquals(0f, UiToggle.thumbFraction(false, 1f), 0.001f);
        assertEquals(1f, UiToggle.thumbFraction(true, 1f), 0.001f);
        assertEquals(0.5f, UiToggle.thumbFraction(true, 0.5f), 0.001f);
    }

    @Test
    public void theTouchTargetsMeetTheMinimum() {
        assertTrue("row height must be a comfortable touch target",
                UiTokens.ROW_HEIGHT >= 44);
        assertTrue("the toggle must be at least 48dp wide with its row padding",
                UiTokens.TOGGLE_WIDTH + 24 >= 48);
    }

    @Test
    public void motionDurationsStayWithinTheCap() {
        assertTrue(UiTokens.MOTION_DEFAULT_MS <= UiTokens.MOTION_MAX_MS);
        assertTrue(UiTokens.MOTION_PRESS_MS <= UiTokens.MOTION_MAX_MS);
        assertTrue(UiTokens.TOGGLE_SLIDE_MS <= UiTokens.MOTION_MAX_MS);
    }

    @Test
    public void chipDotColourReflectsState() {
        assertEquals(UiTokens.ACCENT_500, UiChip.dotColor(UiTokens.ACCENT_500, true));
        assertEquals(UiTokens.TEXT_DISABLED, UiChip.dotColor(UiTokens.ACCENT_500, false));
    }

    @Test
    public void chipWidthGrowsWithTheLabel() {
        float a = UiChip.width(20f, 1f);
        float b = UiChip.width(60f, 1f);
        assertTrue(b > a);
    }

    @Test
    public void segmentedHitTestingPicksTheRightSegment() {
        // 3 segments across [0, 300): segment width 100.
        assertEquals(0, UiSegmented.segmentAt(10f, 0f, 300f, 3));
        assertEquals(1, UiSegmented.segmentAt(150f, 0f, 300f, 3));
        assertEquals(2, UiSegmented.segmentAt(290f, 0f, 300f, 3));
        assertEquals(-1, UiSegmented.segmentAt(-5f, 0f, 300f, 3));
        assertEquals(-1, UiSegmented.segmentAt(305f, 0f, 300f, 3));
    }

    @Test
    public void sliderMapsValueToThumb() {
        assertEquals(0f, UiSlider.thumbX(0f, 0f, 100f, 0f, 200f), 0.001f);
        assertEquals(200f, UiSlider.thumbX(100f, 0f, 100f, 0f, 200f), 0.001f);
        assertEquals(100f, UiSlider.thumbX(50f, 0f, 100f, 0f, 200f), 0.001f);
    }

    @Test
    public void theRadiusScaleHasExactlyFourSteps() {
        assertEquals(8, UiTokens.RADIUS_SMALL);
        assertEquals(12, UiTokens.RADIUS_MEDIUM);
        assertEquals(16, UiTokens.RADIUS_LARGE);
        assertEquals(28, UiTokens.RADIUS_XLARGE);
        // Every named surface radius is one of the four, never a bespoke value.
        assertEquals(UiTokens.RADIUS_XLARGE, UiTokens.RADIUS_WINDOW);
        assertEquals(UiTokens.RADIUS_LARGE, UiTokens.RADIUS_CARD);
        assertEquals(UiTokens.RADIUS_MEDIUM, UiTokens.RADIUS_CHIP);
        assertEquals(UiTokens.RADIUS_LARGE, UiTokens.RADIUS_BUTTON);
    }

    @Test
    public void theSpacingScaleIsTheFourEightProgression() {
        assertEquals(4, UiTokens.SPACE_1);
        assertEquals(8, UiTokens.SPACE_2);
        assertEquals(12, UiTokens.SPACE_3);
        assertEquals(16, UiTokens.SPACE_4);
        assertEquals(24, UiTokens.SPACE_6);
        assertEquals(32, UiTokens.SPACE_8);
    }

    @Test
    public void theTypeScaleIsOrderedAndHasFiveRoles() {
        float[] roles = {
                UiTokens.TEXT_DISPLAY, UiTokens.TEXT_TITLE_ROLE, UiTokens.TEXT_BODY,
                UiTokens.TEXT_LABEL, UiTokens.TEXT_CAPTION
        };
        assertEquals(5, roles.length);
        for (int i = 1; i < roles.length; i++) {
            assertTrue("role " + i + " must be smaller than the one above it",
                    roles[i] < roles[i - 1]);
        }
    }

    @Test
    public void reduceMotionSelectsTheFlatScheme() {
        assertEquals(UiTokens.Motion.EXPRESSIVE, UiTokens.motionScheme(false, false));
        assertEquals(UiTokens.Motion.REDUCED, UiTokens.motionScheme(true, false));
        // The platform "remove animations" setting wins too.
        assertEquals(UiTokens.Motion.REDUCED, UiTokens.motionScheme(false, true));
        assertTrue(UiTokens.Motion.REDUCED.linear);
        assertTrue(UiTokens.Motion.REDUCED.durationMs <= UiTokens.Motion.EXPRESSIVE.durationMs);
        assertTrue("reduced motion must not overshoot", UiTokens.Motion.REDUCED.damping >= 1f);
    }

    @Test
    public void windowBoundsAreCentredAndCapped() {
        // On a very wide screen the 720dp cap applies.
        float[] wide = UiWindow.boundsArray(4000, 2000, 1f);
        assertEquals(720f, wide[2] - wide[0], 0.5f);
        assertEquals((4000 - 720) / 2f, wide[0], 0.5f);
        // On a narrow screen the fraction applies.
        float[] narrow = UiWindow.boundsArray(400, 800, 1f);
        assertEquals(400 * 0.66f, narrow[2] - narrow[0], 0.5f);
        assertEquals(800 * 0.78f, narrow[3] - narrow[1], 0.5f);
    }

    @Test
    public void windowTitleBarAndContentTileTheWindowWithoutOverlap() {
        float[] window = UiWindow.boundsArray(1200, 800, 1f);
        float[] bar = UiWindow.titleBarArray(window[0], window[1], window[2], window[3], 1f);
        float[] content = UiWindow.contentArray(window[0], window[1], window[2], window[3], 1f);
        assertEquals(UiTokens.TITLEBAR_HEIGHT, bar[3] - bar[1], 0.5f);
        assertEquals(window[0], bar[0], 0.5f);
        assertEquals(window[2], bar[2], 0.5f);
        // The content starts exactly where the bar ends and reaches the window's bottom.
        assertEquals(bar[3], content[1], 0.5f);
        assertEquals(window[3], content[3], 0.5f);
    }

    @Test
    public void searchFieldClearButtonSitsInsideTheField() {
        float[] clear = UiSearchField.clearBoundsArray(0f, 0f, 200f, UiSearchField.HEIGHT_DP, 1f);
        assertTrue("the clear button must be inside the field horizontally",
                clear[0] >= 0f && clear[2] <= 200f);
        assertTrue("the clear button must be inside the field vertically",
                clear[1] >= 0f && clear[3] <= UiSearchField.HEIGHT_DP);
        assertTrue(clear[2] - clear[0] > 0f && clear[3] - clear[1] > 0f);
    }

    @Test
    public void railButtonsStackDownTheColumnWithoutOverlap() {
        float density = 1f;
        float[] first = UiRailButton.boundsArray(0f, 0f, density, 0);
        float[] second = UiRailButton.boundsArray(0f, 0f, density, 1);
        assertEquals(UiTokens.RAIL_BUTTON, first[2] - first[0], 0.5f);
        assertEquals(UiTokens.RAIL_BUTTON, first[3] - first[1], 0.5f);
        assertTrue("the second button must sit below the first", second[1] > first[3]);
        // The button is centred in the rail column.
        assertEquals((UiTokens.RAIL_WIDTH - UiTokens.RAIL_BUTTON) / 2f, first[0], 0.5f);
    }

    @Test
    public void aSelectedRailButtonDrawsItsIconInWhiteAndAnUnselectedOneInSecondary() {
        assertEquals(0xFFFFFFFF, UiRailButton.iconColor(UiTokens.ACCENT_500, true));
        assertEquals(UiTokens.TEXT_SECONDARY, UiRailButton.iconColor(UiTokens.ACCENT_500, false));
    }

    @Test
    public void theCardAndButtonRadiiComeFromTheScaleNotABespokeValue() {
        // The two surface helpers take their radius as a parameter, and every caller in the app
        // passes a scale token. Pinning the mapping here is what keeps a caller from inventing a
        // radius: UiCard draws at RADIUS_CARD and UiButton at RADIUS_BUTTON.
        assertEquals(UiTokens.RADIUS_LARGE, UiTokens.RADIUS_CARD);
        assertEquals(UiTokens.RADIUS_LARGE, UiTokens.RADIUS_BUTTON);
    }

    /**
     * The XML card tokens must be the same four scale values, not a fifth and sixth radius.
     *
     * <p>This is the half that drifted: {@code UiTokens} declared 8/12/16/28 and AGENTS.md called the
     * scale closed, while {@code dimens.xml} kept a 14dp card and a 10dp small card that every shared
     * card drawable read. A card drawn in XML and one drawn in code (UiCard, at RADIUS_CARD = 16)
     * therefore rounded a visibly different corner. The test reads the real resource file so a future
     * literal cannot slip back in unnoticed.
     */
    @Test
    public void theXmlRadiusTokensAreTheSameScaleAsTheCode() throws IOException {
        String dimens = readResource("app/src/main/res/values/dimens.xml");
        assertEquals(UiTokens.RADIUS_SMALL + "dp", dimenValue(dimens, "radius_small"));
        assertEquals(UiTokens.RADIUS_MEDIUM + "dp", dimenValue(dimens, "radius_medium"));
        assertEquals(UiTokens.RADIUS_LARGE + "dp", dimenValue(dimens, "radius_large"));
        assertEquals(UiTokens.RADIUS_XLARGE + "dp", dimenValue(dimens, "radius_xlarge"));

        // The card tokens alias the scale rather than restating a value.
        assertEquals("@dimen/radius_large", dimenValue(dimens, "chimera_card_radius"));
        assertEquals("@dimen/radius_medium", dimenValue(dimens, "chimera_card_radius_small"));
    }

    private static String dimenValue(String xml, String name) {
        Matcher m = Pattern.compile(
                "<dimen name=\"" + Pattern.quote(name) + "\">([^<]+)</dimen>").matcher(xml);
        assertTrue("dimen " + name + " is missing from dimens.xml", m.find());
        return m.group(1).trim();
    }

    private static String readResource(String relativePath) throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not find " + relativePath + " from "
                + Paths.get("").toAbsolutePath());
    }
}
