package org.chimeramc.client.core.replay;

import android.view.KeyEvent;

/**
 * The Replay tab's control contract, shared by both screens.
 *
 * <p>The spec fixes two input maps, and they are the same everywhere because the tab
 * is one feature with one backend:
 * <ul>
 *   <li>Controller — A select, X delete, Y favorite, L1/R1 cycle clips.</li>
 *   <li>KBM — click select, Del delete, Space play/pause, arrows scrub.</li>
 * </ul>
 *
 * <p>Pure: it maps a raw key code to an action and never touches the UI, so both screens route
 * through one table and a change to the mapping cannot land on only one of them.
 */
public final class ReplayControls {

    /** What a key or button asks the Replay tab to do. */
    public enum Action {
        SELECT,
        DELETE,
        FAVORITE,
        CYCLE_PREV,
        CYCLE_NEXT,
        PLAY_PAUSE,
        SCRUB_BACK,
        SCRUB_FORWARD,
        /** Steps the playback speed of the open clip. */
        SPEED
    }

    private ReplayControls() {
    }

    /**
     * The action for a controller button.
     *
     * <p>Deliberately narrow: the D-pad is left to the shared grid navigation, so the Replay tab
     * does not fight the module grid for the same keys. Only the face buttons, the bumpers, the
     * triggers and Start carry a Replay action — exactly the set the on-screen controller hint
     * names, so the hint cannot advertise a button that does nothing.
     */
    public static Action forControllerKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return Action.SELECT;
            case KeyEvent.KEYCODE_BUTTON_X:
                return Action.DELETE;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return Action.FAVORITE;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return Action.CYCLE_PREV;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return Action.CYCLE_NEXT;
            case KeyEvent.KEYCODE_BUTTON_START:
                return Action.PLAY_PAUSE;
            case KeyEvent.KEYCODE_BUTTON_L2:
                return Action.SCRUB_BACK;
            case KeyEvent.KEYCODE_BUTTON_R2:
                return Action.SCRUB_FORWARD;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return Action.SPEED;
            default:
                return null;
        }
    }

    /**
     * The action for a keyboard key.
     *
     * <p>Space and Enter are not interchangeable: the spec gives Space to play/pause, and a
     * keyboard that also used Space to select would make playback impossible to start.
     */
    public static Action forKeyboardKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return Action.SELECT;
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_DEL:
                return Action.DELETE;
            case KeyEvent.KEYCODE_SPACE:
                return Action.PLAY_PAUSE;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return Action.SCRUB_BACK;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return Action.SCRUB_FORWARD;
            default:
                return null;
        }
    }

    /** True when the action needs a clip to be selected first. */
    public static boolean requiresSelection(Action action) {
        return action != null && action != Action.CYCLE_PREV && action != Action.CYCLE_NEXT;
    }
}
