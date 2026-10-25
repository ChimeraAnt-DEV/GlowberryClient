package org.chimeramc.client.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.Locale;

/**
 * Pins the stored-language normalisation that decides which translation the app shows.
 *
 * <p>Indonesian shipped as {@code "idn"}, which is not a language code: {@code
 * Locale.forLanguageTag("idn")} yields a pseudo-language named "idn", so the request matched no
 * resource folder and the app stayed in English no matter how often the user chose Indonesian. The
 * correct code is {@code "id"}, and the resource folder is {@code values-id}.
 *
 * <p>The rule is pure (no Context, no Android runtime), so it runs on the JVM. The tests assert the
 * property that actually mattered - the stored value must resolve to the Indonesian language - and
 * not merely that a particular string is returned.
 */
public class LanguageManagerTest {

    @Test
    public void theLegacyIndonesianCodeIsTranslatedToTheRealOne() {
        assertEquals("id", LanguageManager.normaliseLanguageTag("idn"));
        // Case-insensitively, because the value round-trips through a preference a user or an older
        // build could have written in any case.
        assertEquals("id", LanguageManager.normaliseLanguageTag("IDN"));
        assertEquals("id", LanguageManager.normaliseLanguageTag("  idn  "));
    }

    @Test
    public void theNormalisedIndonesianCodeResolvesToIndonesian() {
        // The point of the whole rule. "idn" is not a language, so this is what failed before.
        assertEquals("id", Locale.forLanguageTag(LanguageManager.normaliseLanguageTag("idn")).getLanguage());
        assertNotEquals("idn", Locale.forLanguageTag(LanguageManager.normaliseLanguageTag("idn")).getLanguage());
    }

    @Test
    public void everyOtherSupportedCodeIsPassedThroughUnchanged() {
        String[] supported = {"en", "zh-CN", "fr", "hi", "ja", "pt", "ru", "es", "tr", "vi"};
        for (String code : supported) {
            assertEquals("a supported code must not be rewritten: " + code,
                    code, LanguageManager.normaliseLanguageTag(code));
        }
    }

    @Test
    public void aBlankStoredValueFallsBackToTheDeviceLanguage() {
        // Never an empty tag: forLanguageTags("") clears the override and the app ignores the
        // device locale, which reads as "my language setting does nothing".
        String device = Locale.getDefault().toLanguageTag();
        assertEquals(device, LanguageManager.normaliseLanguageTag(null));
        assertEquals(device, LanguageManager.normaliseLanguageTag(""));
        assertEquals(device, LanguageManager.normaliseLanguageTag("   "));
    }

    @Test
    public void theNormalisedValueIsStableWhenNormalisedAgain() {
        // applySavedLanguage runs on every activity attach, so the output is fed back in. A rule
        // that kept changing the value would flip the locale on each screen.
        for (String code : new String[]{"idn", "id", "en", "zh-CN", "fr"}) {
            String once = LanguageManager.normaliseLanguageTag(code);
            assertEquals(once, LanguageManager.normaliseLanguageTag(once));
        }
    }
}
