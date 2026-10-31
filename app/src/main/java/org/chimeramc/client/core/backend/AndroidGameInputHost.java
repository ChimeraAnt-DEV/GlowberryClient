package org.chimeramc.client.core.backend;

import android.app.Activity;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Injects input into the running game through its own event dispatcher.
 *
 * <p>This is the piece that replaces the prebuilt {@code libinbuiltmods.so} wrappers: instead of a
 * closed binary forwarding an event, the launcher builds the same {@link KeyEvent}/{@link MotionEvent}
 * a physical device would produce and hands it to {@code Activity.dispatchKeyEvent} /
 * {@code dispatchGenericMotionEvent}, which is the path the game's native input handler already
 * reads. Nothing here reaches into game memory.
 *
 * <p><b>Threading.</b> Every method must be called on the main thread — the dispatcher is the
 * activity's, and a synthetic event dispatched from a background thread races the framework. The
 * caller ({@link LauncherGameBackend}) is only ever reached from the UI input path or from an
 * overlay, both of which are on the main thread.
 */
public final class AndroidGameInputHost implements GameInputHost {

    private final Activity activity;
    /** The last pointer position, so a relative move or a scroll has a sensible anchor. */
    private float lastX = -1f;
    private float lastY = -1f;

    public AndroidGameInputHost(Activity activity) {
        this.activity = activity;
    }

    @Override
    public boolean injectKey(int bedrockKeyCode, boolean down) {
        int androidCode = BedrockKeyCodes.toAndroid(bedrockKeyCode);
        if (androidCode == BedrockKeyCodes.UNKNOWN) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        int action = down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
        KeyEvent event = new KeyEvent(now, now, action, androidCode, 0, 0, -1, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        return activity.dispatchKeyEvent(event);
    }

    @Override
    public boolean injectMouseButton(int androidButton, boolean down) {
        if (androidButton == 0) {
            return false;
        }
        int action = down ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP;
        int buttonState = down ? androidButton : 0;
        // The game reads a mouse press from the button state in the generic-motion path, not from a
        // touch event, so the press is delivered as a mouse-source motion event.
        MotionEvent event = obtainMouseEvent(action, buttonState, lastX, lastY);
        try {
            return activity.dispatchGenericMotionEvent(event);
        } finally {
            event.recycle();
        }
    }

    @Override
    public boolean injectScroll(float vertical) {
        if (vertical == 0f) {
            return false;
        }
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = lastX;
        coords[0].y = lastY;
        // The wheel is the vertical-scroll axis on an ACTION_SCROLL event, which is what a real
        // mouse produces and what the game's hotbar-scroll handler reads.
        coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vertical);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, properties,
                coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        try {
            return activity.dispatchGenericMotionEvent(event);
        } finally {
            event.recycle();
        }
    }

    @Override
    public boolean injectLookDelta(float deltaX, float deltaY) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = lastX;
        coords[0].y = lastY;
        // A mouse reports relative movement through the relative axes; the game's look handler
        // consumes exactly these, which is how a real mouse turns the camera.
        coords[0].setAxisValue(MotionEvent.AXIS_RELATIVE_X, deltaX);
        coords[0].setAxisValue(MotionEvent.AXIS_RELATIVE_Y, deltaY);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, properties,
                coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        try {
            return activity.dispatchGenericMotionEvent(event);
        } finally {
            event.recycle();
        }
    }

    @Override
    public boolean injectPointer(float x, float y) {
        lastX = x;
        lastY = y;
        MotionEvent event = obtainMouseEvent(MotionEvent.ACTION_HOVER_MOVE, 0, x, y);
        try {
            return activity.dispatchGenericMotionEvent(event);
        } finally {
            event.recycle();
        }
    }

    private MotionEvent obtainMouseEvent(int action, int buttonState, float x, float y) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = 1f;
        coords[0].size = 1f;
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, 1, properties, coords, buttonState, 0, 1f, 1f, 0,
                0, InputDevice.SOURCE_MOUSE, 0);
    }
}
