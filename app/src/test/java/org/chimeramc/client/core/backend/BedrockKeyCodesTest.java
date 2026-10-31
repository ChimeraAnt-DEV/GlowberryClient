package org.chimeramc.client.core.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

/**
 * Pins the Bedrock-to-Android key translation, entry by entry.
 *
 * <p>The launcher computes Bedrock key codes (letters are their ASCII values) and the host must turn
 * them back into {@code KeyEvent.KEYCODE_*}; a wrong entry presses a different key in the game,
 * which is invisible until a player notices the wrong menu opening. Every branch of the table is
 * checked, plus the ranges it derives from.
 */
public class BedrockKeyCodesTest {

    @Test
    public void lettersMapToTheirAndroidCodes() {
        for (char c = 'A'; c <= 'Z'; c++) {
            assertEquals("letter " + c, KeyEvent.KEYCODE_A + (c - 'A'),
                    BedrockKeyCodes.toAndroid(c));
        }
    }

    @Test
    public void digitsMapToTheirAndroidCodes() {
        for (char c = '0'; c <= '9'; c++) {
            assertEquals("digit " + c, KeyEvent.KEYCODE_0 + (c - '0'),
                    BedrockKeyCodes.toAndroid(c));
        }
    }

    @Test
    public void functionKeysMapInOrder() {
        for (int i = 0; i < 12; i++) {
            assertEquals("F" + (i + 1), KeyEvent.KEYCODE_F1 + i,
                    BedrockKeyCodes.toAndroid(112 + i));
        }
    }

    @Test
    public void numpadDigitsMapInOrder() {
        for (int i = 0; i < 10; i++) {
            assertEquals("numpad " + i, KeyEvent.KEYCODE_NUMPAD_0 + i,
                    BedrockKeyCodes.toAndroid(96 + i));
        }
    }

    @Test
    public void namedKeysMapToTheirAndroidCodes() {
        assertEquals(KeyEvent.KEYCODE_ENTER, BedrockKeyCodes.toAndroid(13));
        assertEquals(KeyEvent.KEYCODE_ENTER, BedrockKeyCodes.toAndroid(10));
        assertEquals(KeyEvent.KEYCODE_ESCAPE, BedrockKeyCodes.toAndroid(27));
        assertEquals(KeyEvent.KEYCODE_SPACE, BedrockKeyCodes.toAndroid(32));
        assertEquals(KeyEvent.KEYCODE_TAB, BedrockKeyCodes.toAndroid(9));
        assertEquals(KeyEvent.KEYCODE_DEL, BedrockKeyCodes.toAndroid(8));
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, BedrockKeyCodes.toAndroid(38));
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, BedrockKeyCodes.toAndroid(40));
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, BedrockKeyCodes.toAndroid(37));
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, BedrockKeyCodes.toAndroid(39));
        assertEquals(KeyEvent.KEYCODE_MOVE_HOME, BedrockKeyCodes.toAndroid(36));
        assertEquals(KeyEvent.KEYCODE_MOVE_END, BedrockKeyCodes.toAndroid(35));
        assertEquals(KeyEvent.KEYCODE_PAGE_UP, BedrockKeyCodes.toAndroid(33));
        assertEquals(KeyEvent.KEYCODE_PAGE_DOWN, BedrockKeyCodes.toAndroid(34));
        assertEquals(KeyEvent.KEYCODE_INSERT, BedrockKeyCodes.toAndroid(45));
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, BedrockKeyCodes.toAndroid(46));
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, BedrockKeyCodes.toAndroid(16));
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, BedrockKeyCodes.toAndroid(17));
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, BedrockKeyCodes.toAndroid(18));
    }

    @Test
    public void punctuationKeysMapToTheirAndroidCodes() {
        assertEquals(KeyEvent.KEYCODE_SEMICOLON, BedrockKeyCodes.toAndroid(186));
        assertEquals(KeyEvent.KEYCODE_EQUALS, BedrockKeyCodes.toAndroid(187));
        assertEquals(KeyEvent.KEYCODE_COMMA, BedrockKeyCodes.toAndroid(188));
        assertEquals(KeyEvent.KEYCODE_MINUS, BedrockKeyCodes.toAndroid(189));
        assertEquals(KeyEvent.KEYCODE_PERIOD, BedrockKeyCodes.toAndroid(190));
        assertEquals(KeyEvent.KEYCODE_SLASH, BedrockKeyCodes.toAndroid(191));
        assertEquals(KeyEvent.KEYCODE_GRAVE, BedrockKeyCodes.toAndroid(192));
        assertEquals(KeyEvent.KEYCODE_LEFT_BRACKET, BedrockKeyCodes.toAndroid(219));
        assertEquals(KeyEvent.KEYCODE_BACKSLASH, BedrockKeyCodes.toAndroid(220));
        assertEquals(KeyEvent.KEYCODE_RIGHT_BRACKET, BedrockKeyCodes.toAndroid(221));
        assertEquals(KeyEvent.KEYCODE_APOSTROPHE, BedrockKeyCodes.toAndroid(222));
    }

    @Test
    public void anUnknownCodeReportsUnknownRatherThanGuessing() {
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, BedrockKeyCodes.toAndroid(0));
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, BedrockKeyCodes.toAndroid(-1));
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, BedrockKeyCodes.toAndroid(9999));
        assertFalse(BedrockKeyCodes.isMapped(0));
    }

    @Test
    public void mappedCodesReportAsMapped() {
        assertTrue(BedrockKeyCodes.isMapped('W'));
        assertTrue(BedrockKeyCodes.isMapped(13));
        assertTrue(BedrockKeyCodes.isMapped(112));
    }
}
