package org.chimeramc.client.core.backend;

import android.view.KeyEvent;

/**
 * The one Android key code to Bedrock key code translation table.
 *
 * <p>Bedrock's key handling uses AWT-style key codes (letters are their ASCII values, {@code Enter}
 * is 13, {@code Escape} is 27), while an Android {@link KeyEvent} uses {@code KeyEvent.KEYCODE_*}.
 * The launcher passes Bedrock codes to a {@link GameBackend} (that is what the on-screen buttons and
 * the mods compute), and the host must turn them back into an Android code before it can build a
 * {@link KeyEvent}. Doing that translation in one place, with a test for every entry, is what stops
 * a key from silently meaning two different things on the two sides.
 *
 * <p>An unmapped code returns {@link #UNKNOWN}; a caller must treat that as "cannot inject" rather
 * than guessing, so an unsupported key does nothing instead of pressing the wrong one.
 */
public final class BedrockKeyCodes {

    /** Returned for a code this table does not name. */
    public static final int UNKNOWN = KeyEvent.KEYCODE_UNKNOWN;

    private BedrockKeyCodes() {
    }

    /**
     * Translates a Bedrock (AWT-style) key code to an Android {@code KeyEvent} code.
     *
     * @return the Android code, or {@link #UNKNOWN} when the code is not in the table.
     */
    public static int toAndroid(int bedrockCode) {
        if (bedrockCode >= 'A' && bedrockCode <= 'Z') {
            return KeyEvent.KEYCODE_A + (bedrockCode - 'A');
        }
        if (bedrockCode >= '0' && bedrockCode <= '9') {
            return KeyEvent.KEYCODE_0 + (bedrockCode - '0');
        }
        if (bedrockCode >= 112 && bedrockCode <= 123) {
            // F1..F12 are 112..123 in the Bedrock/AWT layout.
            return KeyEvent.KEYCODE_F1 + (bedrockCode - 112);
        }
        if (bedrockCode >= 96 && bedrockCode <= 105) {
            // Numpad 0..9 are 96..105 in the Bedrock/AWT layout.
            return KeyEvent.KEYCODE_NUMPAD_0 + (bedrockCode - 96);
        }
        switch (bedrockCode) {
            case 8: return KeyEvent.KEYCODE_DEL;
            case 9: return KeyEvent.KEYCODE_TAB;
            case 10: return KeyEvent.KEYCODE_ENTER;
            case 13: return KeyEvent.KEYCODE_ENTER;
            case 16: return KeyEvent.KEYCODE_SHIFT_LEFT;
            case 17: return KeyEvent.KEYCODE_CTRL_LEFT;
            case 18: return KeyEvent.KEYCODE_ALT_LEFT;
            case 19: return KeyEvent.KEYCODE_BREAK;
            case 20: return KeyEvent.KEYCODE_CAPS_LOCK;
            case 27: return KeyEvent.KEYCODE_ESCAPE;
            case 32: return KeyEvent.KEYCODE_SPACE;
            case 33: return KeyEvent.KEYCODE_PAGE_UP;
            case 34: return KeyEvent.KEYCODE_PAGE_DOWN;
            case 35: return KeyEvent.KEYCODE_MOVE_END;
            case 36: return KeyEvent.KEYCODE_MOVE_HOME;
            case 37: return KeyEvent.KEYCODE_DPAD_LEFT;
            case 38: return KeyEvent.KEYCODE_DPAD_UP;
            case 39: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 40: return KeyEvent.KEYCODE_DPAD_DOWN;
            case 44: return KeyEvent.KEYCODE_SYSRQ;
            case 45: return KeyEvent.KEYCODE_INSERT;
            case 46: return KeyEvent.KEYCODE_FORWARD_DEL;
            case 91: return KeyEvent.KEYCODE_META_LEFT;
            case 92: return KeyEvent.KEYCODE_META_RIGHT;
            case 93: return KeyEvent.KEYCODE_MENU;
            case 106: return KeyEvent.KEYCODE_NUMPAD_MULTIPLY;
            case 107: return KeyEvent.KEYCODE_NUMPAD_ADD;
            case 109: return KeyEvent.KEYCODE_NUMPAD_SUBTRACT;
            case 110: return KeyEvent.KEYCODE_NUMPAD_DOT;
            case 111: return KeyEvent.KEYCODE_NUMPAD_DIVIDE;
            case 144: return KeyEvent.KEYCODE_NUM_LOCK;
            case 145: return KeyEvent.KEYCODE_SCROLL_LOCK;
            case 186: return KeyEvent.KEYCODE_SEMICOLON;
            case 187: return KeyEvent.KEYCODE_EQUALS;
            case 188: return KeyEvent.KEYCODE_COMMA;
            case 189: return KeyEvent.KEYCODE_MINUS;
            case 190: return KeyEvent.KEYCODE_PERIOD;
            case 191: return KeyEvent.KEYCODE_SLASH;
            case 192: return KeyEvent.KEYCODE_GRAVE;
            case 219: return KeyEvent.KEYCODE_LEFT_BRACKET;
            case 220: return KeyEvent.KEYCODE_BACKSLASH;
            case 221: return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case 222: return KeyEvent.KEYCODE_APOSTROPHE;
            default: return UNKNOWN;
        }
    }

    /** True when {@link #toAndroid(int)} names a key. */
    public static boolean isMapped(int bedrockCode) {
        return toAndroid(bedrockCode) != UNKNOWN;
    }
}
