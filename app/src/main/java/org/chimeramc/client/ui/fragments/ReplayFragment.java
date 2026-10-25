package org.chimeramc.client.ui.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.chimeramc.client.R;
import org.chimeramc.client.core.replay.ReplayControls;
import org.chimeramc.client.core.replay.ReplayPanel;

/**
 * The clip library, hosted inside {@link org.chimeramc.client.ui.activities.LibraryActivity} (U3).
 *
 * <p>It hosts the same {@link ReplayPanel} the two in-game Mod Menu screens host, so there is no
 * second clip list, sort, filter or export implementation. The panel owns a background executor and
 * a live recorder listener, so it is disposed in {@link #onDestroyView()} rather than merely
 * dropped, and {@code onShown}/{@code onHidden} bracket its live refresh around this fragment's
 * visibility.
 *
 * <p>It is also a controller host: the panel shows a controller hint here, so the keys that hint
 * names must actually do something. {@link #handleHardwareKey(int)} resolves them through the
 * shared {@link ReplayControls} table and applies them with {@link ReplayPanel#handleControl}, so
 * the Library behaves exactly like the in-game menu rather than showing buttons that do nothing.
 */
public class ReplayFragment extends Fragment {

    private ReplayPanel replayPanel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_replay, container, false);
        FrameLayout host = root.findViewById(R.id.replay_fragment_container);
        replayPanel = new ReplayPanel(requireActivity(), false);
        host.addView(replayPanel.getView(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        replayPanel.setGamepadDetected(hasConnectedGamepad());
        return root;
    }

    /**
     * Applies a hardware key press to the Replay panel.
     *
     * <p>Controller buttons are tried first, then keyboard keys, so both a pad and a physical
     * keyboard drive the same actions. Nothing is consumed unless an action actually applied, so a
     * key this fragment does not own still reaches the host.
     *
     * @return true when the press was used and must not fall through.
     */
    public boolean handleHardwareKey(int keyCode) {
        if (replayPanel == null) return false;
        ReplayControls.Action action = ReplayControls.forControllerKey(keyCode);
        if (action == null) action = ReplayControls.forKeyboardKey(keyCode);
        return action != null && replayPanel.handleControl(action);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (replayPanel != null) replayPanel.onShown();
    }

    @Override
    public void onPause() {
        if (replayPanel != null) replayPanel.onHidden();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (replayPanel != null) {
            replayPanel.dispose();
            replayPanel = null;
        }
        super.onDestroyView();
    }

    /** The panel switches its controller hint on the detected input, so it needs a real scan. */
    private boolean hasConnectedGamepad() {
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice device = android.view.InputDevice.getDevice(id);
            if (device == null) continue;
            int sources = device.getSources();
            if ((sources & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                    || (sources & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }
}
