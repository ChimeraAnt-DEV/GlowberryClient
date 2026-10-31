package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.widget.ImageButton;

import org.chimeramc.client.R;
import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.core.mods.inbuilt.model.ModIds;
import org.chimeramc.client.core.mods.inbuilt.model.OffhandAction;
import org.chimeramc.pojavcontrols.KeyMapper;
import org.chimeramc.client.launcher.core.mods.inbuilt.nativemod.MoreButtonsMod;

/**
 * The Offhand button: opens the inventory so an allowed item can go in the off-hand slot, and/or
 * uses the off-hand item.
 *
 * <p><b>Why this opens the inventory rather than "swapping".</b> Bedrock Edition has no swap-to-off-
 * hand key at all — there is no {@code key.offhand} in the shipped input map, so Java's {@code F}
 * swap simply does not exist here. The off-hand slot is filled from the inventory screen and accepts
 * only a handful of items (shields, arrows, firework rockets, totems of undying, maps and nautilus
 * shells). The working action is therefore to open the inventory ({@code key.inventory}, {@code E})
 * and let the player place an allowed item, which is what the module's primary key now sends.
 *
 * <p>It injects the game's own keys through {@link MoreButtonsMod#sendKey}, the same native
 * key-injection path the working on-screen button modules use (Quick Drop, Toggle HUD, Hotbar Slot).
 * A key that only reached {@code Activity.dispatchKeyEvent} is not seen by the game's input loop,
 * which is why an earlier version of this module did nothing at all. The path is key-level, so it
 * behaves identically in a local world, a Realm and a large server such as The Hive.
 *
 * <p>The hardware keybind mirrors the button so a keyboard/controller player can trigger the same
 * action without the on-screen control.
 */
public class OffhandOverlay extends BaseOverlayButton {

    /**
     * Re-entrancy guard for the synthetic keys.
     *
     * <p>A synthetic key is dispatched back through the activity, which re-enters the overlay
     * manager's key handler. If the player bound the hardware key to the same code as the inventory
     * or use key, that would match the bind again and recurse. The guard makes the injected press
     * skip the bind match and simply reach the game. Dispatch is synchronous on the UI thread, so a
     * plain static flag is enough.
     */
    private static boolean injecting;

    public OffhandOverlay(Activity activity) {
        super(activity);
    }

    @Override
    protected String getModId() {
        return ModIds.OFFHAND;
    }

    @Override
    protected int getIconResource() {
        return R.drawable.ic_offhand;
    }

    @Override
    protected void onButtonPressStart() {
        setPressedIcon(true);
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        performAction(activity, manager.getOffhandMode(),
                manager.getOffhandInventoryKey(), manager.getOffhandUseKey());
    }

    @Override
    protected void onButtonPressEnd() {
        setPressedIcon(false);
    }

    @Override
    protected void onButtonClick() {
        // The press already fired the action; a tap is a press + release, so doing it here too
        // would double-fire.
    }

    /** True while a synthetic off-hand key is being delivered, so the bind match is skipped. */
    static boolean isInjecting() {
        return injecting;
    }

    /**
     * Sends the configured off-hand key(s) synchronously through the activity's key dispatch.
     *
     * <p>Mode 0 opens the inventory, 1 uses the off-hand item, 2 does both in order, matching the
     * config dialog's choice. Shared by the on-screen button and the hardware keybind so both
     * deliver exactly the same keys.
     */
    static void performAction(Activity activity, int mode, int inventoryKey, int useKey) {
        if (injecting) return;
        injecting = true;
        try {
            for (int keyCode : OffhandAction.keysFor(mode, inventoryKey, useKey)) {
                dispatchKey(activity, keyCode);
            }
        } finally {
            injecting = false;
        }
    }

    /**
     * Sends one off-hand key through the native key-injection path the working button modules use.
     *
     * <p>{@code MoreButtonsMod.sendKey} takes a Bedrock key code, so the stored Android key code is
     * converted through {@link KeyMapper}. If the native path is unavailable on this build the
     * call falls back to {@code Activity.dispatchKeyEvent}, which still reaches the game when the
     * launcher owns the input pipeline — the module degrades rather than doing nothing.
     */
    private static void dispatchKey(Activity activity, int androidKeyCode) {
        int bedrockCode = KeyMapper.toBedrock(KeyMapper.fromAndroidKeyCode(androidKeyCode));
        if (bedrockCode > 0 && MoreButtonsMod.sendKey(bedrockCode, true)
                && MoreButtonsMod.sendKey(bedrockCode, false)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, androidKeyCode,
                0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD));
        activity.dispatchKeyEvent(new KeyEvent(now, now + 10, KeyEvent.ACTION_UP, androidKeyCode,
                0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    private void setPressedIcon(boolean pressed) {
        if (overlayView instanceof ImageButton) {
            ((ImageButton) overlayView).setImageResource(
                    pressed ? R.drawable.ic_offhand_pressed : R.drawable.ic_offhand);
        }
    }
}
