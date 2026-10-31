package org.chimeramc.client.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import org.chimeramc.client.R;
import org.chimeramc.client.core.mods.inbuilt.manager.InbuiltModManager;
import org.chimeramc.client.preloader.PreloaderInput;
import org.chimeramc.client.util.MainThread;

public class ModMenuButton {

    private final Activity activity;
    private View buttonView;
    private WindowManager windowManager;
    private WindowManager.LayoutParams wmParams;
    private final Handler handler = MainThread.handler();

    // The button attaches 500ms after show() so the game window is up. A hide in that window used to
    // be a no-op (isShowing was still false) and the button appeared afterwards with nothing
    // tracking it.
    private final OverlayShowGate showGate = new OverlayShowGate();
    private boolean isShowing = false;
    
    private float initialX, initialY, initialTouchX, initialTouchY;
    private boolean isDragging = false;
    private long touchDownTime = 0;
    private static final long TAP_TIMEOUT = 200;
    private static final float DRAG_THRESHOLD = 10f;
    
    /** The one Mod Menu (U4). It used to be two windows; the bind and the launcher open this. */
    private ModMenuOverlay menuOverlay;
    /** The radial mod wheel (section 1), opened from the same bind. */
    private org.chimeramc.client.core.mods.inbuilt.overlay.RadialMenuOverlay radialMenuOverlay;
    
    public ModMenuButton(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
    }
    
    public void show(int startX, int startY) {
        if (isShowing) return;
        long token = showGate.begin();
        handler.postDelayed(() -> {
            if (!showGate.isCurrent(token)) return;
            showInternal(startX, startY);
        }, 500);
    }
    
    private void showInternal(int startX, int startY) {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        
        try {
            buttonView = LayoutInflater.from(activity).inflate(R.layout.overlay_mod_menu_button, null);
            ImageButton btn = buttonView.findViewById(R.id.mod_menu_fab);
            
            float density = activity.getResources().getDisplayMetrics().density;
            int buttonSize = (int) (53 * density);
            
            wmParams = new WindowManager.LayoutParams(
                buttonSize,
                buttonSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT
            );
            wmParams.gravity = Gravity.TOP | Gravity.START;
            wmParams.x = startX;
            wmParams.y = startY;
            wmParams.token = activity.getWindow().getDecorView().getWindowToken();
            
            btn.setOnTouchListener(this::handleTouch);
            buttonView.setVisibility(View.GONE);
            windowManager.addView(buttonView, wmParams);
            isShowing = true;
            applyOpacity();
            applyRuntimeVisibility();
        } catch (Exception e) {
            showFallback(startX, startY);
        }
    }
    
    private void showFallback(int startX, int startY) {
        if (isShowing) return;
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;
        
        buttonView = LayoutInflater.from(activity).inflate(R.layout.overlay_mod_menu_button, null);
        ImageButton btn = buttonView.findViewById(R.id.mod_menu_fab);
        
        float density = activity.getResources().getDisplayMetrics().density;
        int buttonSize = (int) (48 * density);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonSize, buttonSize);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = startX;
        params.topMargin = startY;
        
        btn.setOnTouchListener(this::handleTouchFallback);
        buttonView.setVisibility(View.GONE);
        rootView.addView(buttonView, params);
        isShowing = true;
        wmParams = null;
        applyOpacity();
        applyRuntimeVisibility();
    }

    private void applyOpacity() {
        if (buttonView != null) {
            int opacity = InbuiltModManager.getInstance(activity).getModMenuButtonOpacity();
            buttonView.setAlpha(opacity / 100f);
        }
    }

    private void applyButtonOpacity() {
        applyOpacity();
    }

    private void applyRuntimeVisibility() {
        if (buttonView == null) return;
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        boolean visible = !manager.isPauseMenuOnly() ||
                (PreloaderInput.isPauseMenuOpen() && PreloaderInput.isShowingMenu());
        if (!visible) {
            hideMenu();
        }
        buttonView.setVisibility(visible ? View.VISIBLE : View.GONE);
        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        if (overlayManager != null) {
            overlayManager.refreshRuntimeVisibility();
        }
    }

    private boolean handleTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                initialX = wmParams.x;
                initialY = wmParams.y;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                isDragging = false;
                touchDownTime = SystemClock.uptimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - initialTouchX;
                float dy = event.getRawY() - initialTouchY;
                if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                    isDragging = true;
                }
                if (isDragging && windowManager != null && buttonView != null) {
                    wmParams.x = (int) (initialX + dx);
                    wmParams.y = (int) (initialY + dy);
                    windowManager.updateViewLayout(buttonView, wmParams);
                }
                return true;
            case MotionEvent.ACTION_UP:
                long elapsed = SystemClock.uptimeMillis() - touchDownTime;
                if (!isDragging && elapsed < TAP_TIMEOUT) {
                    handler.post(this::onButtonClick);
                }
                isDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                return true;
        }
        return false;
    }
    
    private boolean handleTouchFallback(View v, MotionEvent event) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) buttonView.getLayoutParams();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                initialX = params.leftMargin;
                initialY = params.topMargin;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                isDragging = false;
                touchDownTime = SystemClock.uptimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - initialTouchX;
                float dy = event.getRawY() - initialTouchY;
                if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                    isDragging = true;
                }
                if (isDragging) {
                    params.leftMargin = (int) (initialX + dx);
                    params.topMargin = (int) (initialY + dy);
                    buttonView.setLayoutParams(params);
                }
                return true;
            case MotionEvent.ACTION_UP:
                long elapsed = SystemClock.uptimeMillis() - touchDownTime;
                if (!isDragging && elapsed < TAP_TIMEOUT) {
                    handler.post(this::onButtonClick);
                }
                isDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                return true;
        }
        return false;
    }
    
    private void onButtonClick() {
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        if (manager.isPauseMenuOnly() &&
                (!PreloaderInput.isPauseMenuOpen() || !PreloaderInput.isShowingMenu())) {
            hideMenu();
            return;
        }

        if (menuOverlay == null) {
            menuOverlay = new ModMenuOverlay(activity);
            menuOverlay.setCallback(new ModMenuCallback());
        }

        if (menuOverlay.isShowing()) {
            menuOverlay.hide();
        } else {
            menuOverlay.show();
        }
    }
    

    
    private void applyConfigurationChanges(String modId) {
        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        if (overlayManager != null) {
            overlayManager.applyConfigurationChanges(modId);
        }
    }
    
    public void hide() {
        // Invalidated before the isShowing check so a hide during the attach delay still wins.
        showGate.invalidate();
        handler.removeCallbacksAndMessages(null);
        if (menuOverlay != null) {
            menuOverlay.hide();
            menuOverlay = null;
        }
        if (!isShowing || buttonView == null) return;
        handler.post(() -> {
            try {
                if (wmParams != null && windowManager != null) {
                    windowManager.removeView(buttonView);
                } else {
                    ViewGroup rootView = activity.findViewById(android.R.id.content);
                    if (rootView != null) {
                        rootView.removeView(buttonView);
                    }
                }
            } catch (Exception ignored) {}
            buttonView = null;
            isShowing = false;
        });
    }
    
    public void setVisibility(int visibility) {
        if (buttonView != null) {
            buttonView.setVisibility(visibility);
        }
    }
    
    public boolean isShowing() {
        return isShowing;
    }

    /**
     * The open menu, or null.
     *
     * <p>Exists so the game activity can hand controller input to the menu before the preloader may
     * swallow it — the overlay root is unfocusable, so the framework never routes a key or a stick
     * movement to it on its own.
     */
    public ModMenuOverlay getMenuOverlay() {
        return menuOverlay;
    }

    public boolean isMenuShowing() {
        return (menuOverlay != null && menuOverlay.isShowing())
                || (radialMenuOverlay != null && radialMenuOverlay.isShowing());
    }

    /**
     * Opens or closes the Mod Menu from the bound input.
     *
     * <p>The bind opens the radial wheel, whose sectors route into the one Mod Menu (U4). The
     * launcher's Mods screen opens the same menu, so a module or a page cannot exist in one and be
     * missing from the other.
     */
    public void toggleMenuFromBind() {
        if (!isShowing) return;
        ensureRadialMenuOverlay();
        radialMenuOverlay.toggle();
    }

    /**
     * The radial wheel, created lazily and wired to this menu's destinations.
     *
     * <p>Exposed so the hold-to-open path can reach the wheel on the *press* (before any toggle has
     * happened) — creating it inside {@code toggleMenuFromBind} alone left the first press with a
     * null wheel, which is exactly the gesture hold-to-open needs to work.
     */
    public org.chimeramc.client.core.mods.inbuilt.overlay.RadialMenuOverlay
            ensureRadialMenuOverlay() {
        if (!isShowing) return null;
        if (radialMenuOverlay == null) {
            radialMenuOverlay =
                    new org.chimeramc.client.core.mods.inbuilt.overlay.RadialMenuOverlay(activity);
            radialMenuOverlay.setDestinations(new org.chimeramc.client.core.mods.inbuilt.overlay
                    .RadialMenuOverlay.Destinations() {
                @Override
                public void openMods() {
                    openMenuFiltered(
                            org.chimeramc.client.core.mods.inbuilt.overlay.ModMenuOverlay
                                    .ModuleFilter.ALL);
                }

                @Override
                public void openHud() {
                    // The HUD sector is a filter over the one module list, so it lands on the same
                    // rows the HUD chip shows rather than a second, hand-built list.
                    openMenuFiltered(
                            org.chimeramc.client.core.mods.inbuilt.overlay.ModMenuOverlay
                                    .ModuleFilter.HUD);
                }

                @Override
                public void openCustomize() {
                    new org.chimeramc.client.core.mods.inbuilt.overlay.CustomizeChooser(
                            activity,
                            () -> openMenuSection(org.chimeramc.client.core.mods.inbuilt.overlay
                                    .ModMenuPage.SETTINGS),
                            () -> openMenuSection(org.chimeramc.client.core.mods.inbuilt.overlay
                                    .ModMenuPage.COSMETICS)).show();
                }

                @Override
                public void openSettings() {
                    openMenuSection(
                            org.chimeramc.client.core.mods.inbuilt.overlay.ModMenuPage.SETTINGS);
                }
            });
        }
        return radialMenuOverlay;
    }

    /**
     * Opens the one Mod Menu on a specific page, creating it if needed.
     *
     * <p>The radial wheel's sectors route here, so a sector opens the same menu the launcher and
     * the bind do, on the page it names.
     */
    private void openMenuSection(org.chimeramc.client.core.mods.inbuilt.overlay.ModMenuPage page) {
        if (menuOverlay == null) {
            menuOverlay = new ModMenuOverlay(activity);
            menuOverlay.setCallback(new ModMenuCallback());
        }
        menuOverlay.showPage(page);
    }

    /**
     * Opens the one Mod Menu's module list already filtered to a section.
     *
     * <p>The wheel's MODS and HUD sectors route here, so the wheel lands on the same filtered rows
     * the filter chip shows. The menu is created lazily and the filter is applied on the first
     * frame, so a cold open and an already-open menu behave identically.
     */
    private void openMenuFiltered(
            org.chimeramc.client.core.mods.inbuilt.overlay.ModMenuOverlay.ModuleFilter filter) {
        if (menuOverlay == null) {
            menuOverlay = new ModMenuOverlay(activity);
            menuOverlay.setCallback(new ModMenuCallback());
        }
        menuOverlay.showModulesFiltered(filter);
    }

    /** The callback the overlay calls back into, kept in one place so both paths share it. */
    private final class ModMenuCallback implements ModMenuOverlay.ModMenuCallback {
        @Override
        public void onModToggled(String modId, boolean enabled) {
        }

        @Override
        public void onButtonOpacityChanged(int opacity) {
            applyButtonOpacity();
        }
    }

    /** The open radial wheel, or null. */
    public org.chimeramc.client.core.mods.inbuilt.overlay.RadialMenuOverlay getRadialMenuOverlay() {
        return radialMenuOverlay;
    }

    public void hideMenu() {
        if (menuOverlay != null && menuOverlay.isShowing()) {
            menuOverlay.hide();
        }
        if (radialMenuOverlay != null && radialMenuOverlay.isShowing()) {
            radialMenuOverlay.hide();
        }
    }
}
