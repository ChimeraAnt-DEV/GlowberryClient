package org.chimeramc.client.core.minecraft

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.graphics.Color
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import com.mojang.minecraftpe.MainActivity
import org.chimeramc.client.core.crash.CrashReporter
import org.chimeramc.client.core.mods.ModManager
import org.chimeramc.client.core.mods.ModSafeMode
import org.chimeramc.client.launcher.core.minecraft.LastLaunchFailure
import org.levimc.launcher.core.mods.inbuilt.nativemod.PojavControlsMod
import org.chimeramc.client.core.mods.inbuilt.overlay.InbuiltOverlayManager
import org.chimeramc.client.launcher.controller.ControllerInputProcessor
import org.chimeramc.client.settings.PerformancePresetManager
import org.chimeramc.client.util.DisplayModePreference
import org.chimeramc.client.preloader.PreloaderInput
import org.chimeramc.pojavcontrols.PojavControls
import org.chimeramc.pojavcontrols.PojavControlsHost
import java.io.File
import java.io.FileOutputStream

class MinecraftActivity : MainActivity(), PojavControlsHost {

    companion object {
        private const val PRELOADER_DOCUMENT_REQUEST = 0x4C50
    }

    private lateinit var gameManager: GamePackageManager
    private lateinit var trace: LaunchTrace
    private var overlayManager: InbuiltOverlayManager? = null

    /** Scaled touch slop, resolved once; used to tell an attack tap from a camera drag. */
    private val touchSlopPx by lazy {
        android.view.ViewConfiguration.get(this).scaledTouchSlop
    }

    private var normalExitPrepared = false
    private var normalExitRestartScheduled = false
    private var gameRuntimeStarted = false
    private var preloaderTextInput: PreloaderTextInput? = null
    private var previousInputFocus: View? = null
    private val playtimeHeartbeat = object : Runnable {
        override fun run() {
            PlaytimeManager.heartbeat()
            playtimeHeartbeatHandler.postDelayed(this, PlaytimeManager.HEARTBEAT_INTERVAL_MS)
        }
    }
    private val playtimeHeartbeatHandler by lazy { Handler(Looper.getMainLooper()) }

    private class PreloaderTextInput(context: Context) : AppCompatEditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val target = super.onCreateInputConnection(outAttrs) ?: return null
            return object : InputConnectionWrapper(target, true) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    if (!text.isNullOrEmpty() && PreloaderInput.onTextInput(text)) {
                        super.commitText(text, newCursorPosition)
                        return true
                    }
                    return super.commitText(text, newCursorPosition)
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    if (beforeLength > 0 && dispatchBackspace()) {
                        return true
                    }
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }

                override fun deleteSurroundingTextInCodePoints(
                    beforeLength: Int,
                    afterLength: Int
                ): Boolean {
                    if (beforeLength > 0 && dispatchBackspace()) {
                        return true
                    }
                    return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                }

                private fun dispatchBackspace(): Boolean {
                    val downConsumed = PreloaderInput.onKeyEvent(
                        KeyEvent.KEYCODE_DEL,
                        0,
                        true
                    )
                    val upConsumed = PreloaderInput.onKeyEvent(
                        KeyEvent.KEYCODE_DEL,
                        0,
                        false
                    )
                    return downConsumed || upConsumed
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        trace = LaunchTrace.ensure(intent)
        trace.mark("MinecraftActivity onCreate entered")
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(resolveLaunchBackgroundColor()))

        if (savedInstanceState != null) {
            trace.mark("MinecraftActivity finishing restored instance")
            gameRuntimeStarted = true
            super.onCreate(null)
            finish()
            return
        }

        try {
            val preparedRuntime = MinecraftLaunchSession.getPreparedRuntime()
                ?: MinecraftRuntimePreparer.prepare(applicationContext, intent)
            gameManager = preparedRuntime.gameManager
            configureMinecraftFirebase()
            trace.mark("Prepared runtime consumed")
        } catch (throwable: Throwable) {
            trace.error("MinecraftActivity prepare failed", formatLaunchFailure(throwable))
            returnToLauncherAfterLaunchFailure(throwable)
            return
        }
        trace.mark("Native mod enable started")
        ModManager.enableLoadedMods()
        trace.mark("Native mod enable finished")
        trace.mark("Mojang MainActivity super.onCreate starting")
        try {
            gameRuntimeStarted = true
            super.onCreate(savedInstanceState)
        } catch (throwable: Throwable) {
            trace.error("Mojang MainActivity super.onCreate failed", formatLaunchFailure(throwable))
            returnToLauncherAfterLaunchFailure(throwable)
            return
        }
        trace.mark("Mojang MainActivity super.onCreate finished")
        ModSafeMode.completeLaunch(this)
        trace.mark("Mod crash-loop marker cleared")
        // The game process is up, so the previous launch's crash attribution (if any) is stale:
        // this launch succeeded, so the consecutive-crash counter restarts.
        org.chimeramc.client.core.mods.ModCrashAttribution.clear(this)
        // The game process reached its own onCreate, so the optifine set active for this launch
        // did not kill it before the game started; clear the crash attribution.
        org.chimeramc.client.core.minecraft.OptifineModeManager.onSessionReachedWorld(this)

        MinecraftForegroundService.startIfEnabled(this)

        val launchVertically = intent.getBooleanExtra("LAUNCH_VERTICALLY", false)
        if (launchVertically) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        
        try {
            initializePreloaderTextInput()
            PreloaderInput.setActivity(this)
            val storageProfileId = intent.getStringExtra(MinecraftLauncher.EXTRA_STORAGE_PROFILE_ID)
            // A per-instance controller binding takes precedence over plain pad detection, so
            // an instance can bring its own controller setup into the session.
            if (!ControllerInputProcessor.applyInstanceBinding(this, storageProfileId)) {
                ControllerInputProcessor.detectAndLoad(this)
            }
            MinecraftActivityState.onCreated(this)
            startPlaytimeSession(storageProfileId)
            applyHighRefreshRateMode()
        } catch (throwable: Throwable) {
            trace.error("Post-init hook failed", formatLaunchFailure(throwable))
        }
        trace.mark("MinecraftActivity onCreate finished")
    }

    /**
     * Asks the window manager to run this window on a high-refresh display mode so the game
     * can render past the panel's default rate.
     *
     * Only the Performance preset asks for this. Balanced deliberately leaves the panel alone,
     * and Battery must not raise the rate at all, so an unconditional request would both
     * contradict the preset the user picked and switch a 120Hz panel out from under someone
     * who never asked for it.
     *
     * Only a mode that keeps the resolution already in use is selected. On many panels the
     * fast modes are lower resolution, and picking the fastest mode outright would trade a
     * sharper picture for frames without saying so. If no same-resolution fast mode exists
     * the window is left untouched.
     */
    private fun applyHighRefreshRateMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!PerformancePresetManager.shouldRequestHighRefresh(this)) return
        try {
            val current = display?.mode ?: return
            val modes = display?.supportedModes?.map {
                DisplayModePreference.Mode(it.modeId, it.refreshRate, it.physicalWidth, it.physicalHeight)
            } ?: return
            val chosen = DisplayModePreference.selectHighRefreshModeId(
                modes, current.physicalWidth, current.physicalHeight
            )
            if (chosen == DisplayModePreference.NO_MODE || chosen == current.modeId) return
            val params = window.attributes
            params.preferredDisplayModeId = chosen
            window.attributes = params
            trace.mark("High refresh display mode applied: $chosen")
        } catch (throwable: Throwable) {
            trace.error("High refresh display mode failed", formatLaunchFailure(throwable))
        }
    }


    private fun startPlaytimeSession(profileId: String?) {
        PlaytimeManager.startSession(profileId)
        playtimeHeartbeatHandler.removeCallbacks(playtimeHeartbeat)
        playtimeHeartbeatHandler.postDelayed(playtimeHeartbeat, PlaytimeManager.HEARTBEAT_INTERVAL_MS)
    }

    private fun stopPlaytimeSession() {
        playtimeHeartbeatHandler.removeCallbacks(playtimeHeartbeat)
        PlaytimeManager.stopSession()
    }

    private fun configureMinecraftFirebase() {
        val appId = gameManager.getGameStringResource("google_app_id")
            ?: "1:486187589451:android:b2331110821fe2304bd2ce"
        val apiKey = gameManager.getGameStringResource("google_api_key")
            ?: gameManager.getGameStringResource("google_crash_reporting_api_key")
            ?: ""
        val projectId = gameManager.getGameStringResource("project_id")
            ?: "minecraft-bedrock-57580"
        val senderId = gameManager.getGameStringResource("gcm_defaultSenderId")
            ?: "486187589451"
        intent.putExtra("MINECRAFT_FIREBASE_APP_ID", appId)
        intent.putExtra("MINECRAFT_FIREBASE_API_KEY", apiKey)
        intent.putExtra("MINECRAFT_FIREBASE_PROJECT_ID", projectId)
        intent.putExtra("MINECRAFT_FIREBASE_SENDER_ID", senderId)
    }

    private fun returnToLauncherAfterLaunchFailure(throwable: Throwable? = null) {
        gameRuntimeStarted = false
        MinecraftLaunchSession.clear()
        // A failure before the game's own onCreate is attributed to the optifine items active
        // for this launch, so a bad hook is disabled rather than retried forever.
        org.chimeramc.client.core.minecraft.OptifineModeManager.onLaunchFailed(this)
        // This activity cannot show a dialog: it tears the launcher down to restart it, so by the
        // time the failure is known the Activity that would own the dialog is gone. The report is
        // therefore stored with its classified cause and announced=false, and the relaunched
        // launcher shows it once. Writing the file without ever reading it back (the previous
        // behaviour) left the user watching the app vanish with the cause sitting unread on disk.
        LastLaunchFailure.write(this, throwable, false)
        LaunchLog.append(this, "==== Launch failed; recorded for the launcher to report ====")
        trace.error(
            "Recorded launch failure for the launcher",
            LastLaunchFailure.keyOf(LastLaunchFailure.read(this))
        )
        MinecraftProcessRestarter.restartLauncherAfterMinecraftExit(this)
        finish()
    }

    private fun formatLaunchFailure(throwable: Throwable): String {
        return throwable.message ?: throwable.javaClass.simpleName
    }

    private fun resolveLaunchBackgroundColor(): Int {
        val typedValue = android.util.TypedValue()
        return if (theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)) {
            typedValue.data
        } else {
            Color.BLACK
        }
    }

    private fun startInbuiltModServices() {
        overlayManager = InbuiltOverlayManager(this)
        overlayManager?.showEnabledOverlays()
    }

    private fun stopInbuiltModServices() {
        overlayManager?.hideAllOverlays()
        overlayManager = null
    }

    override fun onNewIntent(intent: Intent) {
        setIntent(intent)
        super.onNewIntent(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PRELOADER_DOCUMENT_REQUEST) {
            handlePreloaderDocumentResult(resultCode, data)
            return
        }
        if (org.chimeramc.client.core.mods.inbuilt.overlay.MoreButtonsEditor.onActivityResult(requestCode, resultCode, data)) return
        if (org.chimeramc.client.core.replay.ReplayManager.onActivityResult(requestCode, resultCode, data)) return
        if (PojavControls.onActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    fun openPreloaderDocumentPicker(mimeType: String) {
        runOnUiThread {
            try {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mimeType.ifBlank { "*/*" }
                }
                startActivityForResult(picker, PRELOADER_DOCUMENT_REQUEST)
            } catch (throwable: Throwable) {
                PreloaderInput.onDocumentResult(false, "", "", throwable.message ?: "Unable to open document picker")
            }
        }
    }

    private fun handlePreloaderDocumentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            PreloaderInput.onDocumentResult(false, "", "", "Import cancelled")
            return
        }
        val uri = data.data ?: return
        var temporaryFile: File? = null
        try {
            var displayName = "selected_file"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) displayName = cursor.getString(index) ?: displayName
                }
            }
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "selected_file" }
            val directory = File(cacheDir, "preloader_documents").also { it.mkdirs() }
            val importedFile = File(directory, "${System.nanoTime()}_$safeName")
            temporaryFile = importedFile
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(importedFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Unable to read selected document")
            PreloaderInput.onDocumentResult(true, importedFile.absolutePath, displayName, "")
        } catch (throwable: Throwable) {
            PreloaderInput.onDocumentResult(false, "", "", throwable.message ?: "Unable to import selected document")
        } finally {
            temporaryFile?.delete()
        }
    }

    override fun onBackPressed() {
        if (org.chimeramc.client.core.mods.inbuilt.overlay.MoreButtonsEditor.closeEditor()) return
        if (PojavControls.closeEditor()) return
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) {
            normalExitPrepared = false
            normalExitRestartScheduled = false
        }
        MinecraftActivityState.onResumed(this)
        org.chimeramc.client.core.mods.inbuilt.overlay.MoreButtonsEditor.onResume()

        if (overlayManager == null) {
            startInbuiltModServices()
        }
        attachControllerMonitor()
    }

    /**
     * Listens for controllers while gameplay is in front, so the connection pill appears over
     * Minecraft and the pad's profile is loaded the moment it is plugged in mid-session.
     */
    private var controllerMonitorListener:
        org.chimeramc.client.launcher.controller.ControllerConnectionMonitor.Listener? = null

    private fun attachControllerMonitor() {
        val monitor = org.chimeramc.client.launcher.controller.ControllerConnectionMonitor.get(this)
        if (controllerMonitorListener == null) {
            controllerMonitorListener =
                object : org.chimeramc.client.launcher.controller.ControllerConnectionMonitor.Listener {
                    override fun onControllerConnected(
                        name: String,
                        type: org.chimeramc.client.launcher.controller.ControllerType,
                        profileName: String?
                    ) {
                        org.chimeramc.client.launcher.controller.ControllerToastView.showConnected(
                            this@MinecraftActivity, name, profileName, type)
                    }

                    override fun onControllerDisconnected(name: String) {
                        org.chimeramc.client.launcher.controller.ControllerToastView.showDisconnected(
                            this@MinecraftActivity, name)
                    }
                }
        }
        monitor.setListener(controllerMonitorListener)
        monitor.start()
    }

    private fun detachControllerMonitor() {
        // Keep the listener in a field so the detach can be identity-checked against it. A bare
        // clear could drop a listener the game had already re-attached on resume.
        org.chimeramc.client.launcher.controller.ControllerConnectionMonitor
            .get(this)
            .clearListener(controllerMonitorListener)
        controllerMonitorListener = null
    }

    private fun isMouseSource(source: Int): Boolean {
        return (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
    }

    /**
     * True for the controller buttons a player expects to attack with: the right shoulder and the
     * right trigger. Both are treated as the attack input so Select Hit works whichever the player
     * uses; the launcher's own control overlay maps the trigger to the primary mouse button, so a
     * touch-control player is covered by the mouse path instead.
     */
    private fun isControllerAttackButton(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_BUTTON_R2
    }

    private fun getMouseButton(event: KeyEvent): Int {
        if (!isMouseSource(event.source)) {
            return 0
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_1 -> MotionEvent.BUTTON_PRIMARY
            KeyEvent.KEYCODE_BUTTON_2 -> MotionEvent.BUTTON_SECONDARY
            KeyEvent.KEYCODE_BUTTON_3 -> MotionEvent.BUTTON_TERTIARY
            KeyEvent.KEYCODE_BUTTON_4 -> MotionEvent.BUTTON_BACK
            KeyEvent.KEYCODE_BUTTON_5 -> MotionEvent.BUTTON_FORWARD
            else -> 0
        }
    }

    private fun shouldConsumeMouseMotion(event: MotionEvent): Boolean {
        return isMouseSource(event.source) &&
            PreloaderInput.onMouseMotion(
                event.actionMasked,
                event.actionButton,
                event.buttonState
            )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A bind picker in the Mod Menu must see the raw press before the preloader can consume
        // it, otherwise the button the player presses never reaches the picker.
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (org.chimeramc.client.core.mods.inbuilt.overlay.ModMenuOverlay
                    .deliverBindKey(event.keyCode)) {
                return true
            }
        }
        // An open Mod Menu takes controller navigation next. Its overlay root is unfocusable so the
        // platform stops painting the white focus wash over the game, which also means the
        // framework will not route a d-pad or A press to it — so it is offered the raw key here,
        // before the preloader can swallow the press for the game.
        if (overlayManager?.handleMenuControllerKey(event.keyCode, event.action) == true) {
            return true
        }
        val mouseButton = getMouseButton(event)
        if (mouseButton != 0 &&
            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) &&
            PreloaderInput.onMouse(mouseButton, event.action == KeyEvent.ACTION_DOWN)) {
            return true
        }
        // Recorded regardless of whether the preloader consumed the press: the player pressed
        // attack either way, and Select Hit needs the real input timing.
        if (mouseButton == MotionEvent.BUTTON_PRIMARY && event.action == KeyEvent.ACTION_DOWN) {
            overlayManager?.notifyAttack()
        }
        // A physical controller's attack trigger arrives as a gamepad key (KEYCODE_BUTTON_R2),
        // not as a mouse-source event, so it never reached the block above — which is why Select
        // Hit did nothing for controller players. Record it here as the attack input too. A
        // duplicate call in the same instant is harmless: the solver treats a click inside the
        // window as discarded, so re-recording cannot advance the combo or extend the window.
        if (event.action == KeyEvent.ACTION_DOWN && isControllerAttackButton(event.keyCode)) {
            overlayManager?.notifyAttack()
        }

        // Android reports a control code point for dedicated keys (Enter is '\n' on many IMEs).
        // Forwarding that as a "character" alongside the key press is what crashed the instance
        // when Enter was pressed in chat; a control point reads as "no character" instead.
        val unicodeChar = TextInputSanitizer.sanitizeUnicodeChar(event.unicodeChar)
        // A layer modifier switches the whole button map while held. Recorded on the raw code
        // (before any remap) and swallowed, so the modifier button does not also fire its own
        // action — holding it is purely a switch.
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            if (ControllerInputProcessor.handleLayerModifier(
                    event.keyCode, event.action == KeyEvent.ACTION_DOWN)) {
                return true
            }
        }
        val remappedKey = ControllerInputProcessor.processKeyEvent(event.keyCode)
        // Per-button CPS limit and hold-to-repeat. The limit only drops the press the game sees
        // when the button is over its cap; the real press still drives hit-timing above.
        if (event.repeatCount == 0) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (ControllerInputProcessor.onKeyDown(remappedKey, event.eventTime)) {
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                ControllerInputProcessor.onKeyUp(remappedKey)
            }
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (org.chimeramc.client.preloader.PreloaderInput.onKeyEvent(remappedKey, unicodeChar, false)) {
                return true
            }
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (org.chimeramc.client.preloader.PreloaderInput.onKeyEvent(remappedKey, unicodeChar, true)) {
                return true
            }
        }

        overlayManager?.let { manager ->
            if (manager.handleKeyEvent(remappedKey, event.action, event.keyCode)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (shouldConsumeMouseMotion(event)) {
            return true
        }

        if (org.chimeramc.client.core.mods.inbuilt.overlay.VirtualCursorMod.isActive()) {
            org.chimeramc.client.core.mods.inbuilt.overlay.VirtualCursorMod.processTouchEvent(event, this)
            return true
        }

        if (PojavControls.ownsTouchInput()) {
            return super.dispatchTouchEvent(event)
        }

        // Classify the gesture BEFORE the preloader gets a chance to consume it. The game
        // consumes the raw event itself, so a vanilla tap attack has no other funnel to observe;
        // but the preloader also consumes touches (it returns true above), and returning early
        // left the detector's state machine without its UP for that pointer. The next DOWN then
        // restarted tracking from scratch, so the following tap's UP arrived with `tracking`
        // already false and the attack was dropped — the metronome silently under-counted.
        overlayManager?.handleTouchEvent(event)
        overlayManager?.notifyTouchForAttack(event, touchSlopPx)

        val actionIndex = event.actionIndex
        if (org.chimeramc.client.preloader.PreloaderInput.onTouch(
                event.actionMasked,
                event.getPointerId(actionIndex),
                event.getX(actionIndex),
                event.getY(actionIndex)
            )) {
            return true
        }

        return super.dispatchTouchEvent(event)
    }

    fun dispatchGenericMotionEventToGame(event: MotionEvent): Boolean {
        return super.dispatchGenericMotionEvent(event)
    }

    fun dispatchTouchEventToGame(event: MotionEvent): Boolean {
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (shouldConsumeMouseMotion(event)) {
            return true
        }

        // While the Mod Menu is open the left stick moves the menu selection, so it must not also
        // reach the game as a look input.
        if (overlayManager?.handleMenuControllerMotion(event) == true) {
            return true
        }

        if (ControllerInputProcessor.isWithinDeadZone(event)) {
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            overlayManager?.handleMouseEvent(event)
        }

        if (event.action == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vScroll != 0f) {
                overlayManager?.let { manager ->
                    if (manager.handleScrollEvent(vScroll)) {
                        return true
                    }
                }
            }
        }

        // Apply the active profile's response curves to the stick and trigger axes the game
        // will read. Identity when no profile/curve is active, in which case the same event
        // instance comes back and there is nothing to recycle.
        val shaped = ControllerInputProcessor.transformMotionEvent(event)
        if (shaped !== event) {
            try {
                return super.dispatchGenericMotionEvent(shaped)
            } finally {
                shaped.recycle()
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun pojavSendKey(bedrockKeyCode: Int, down: Boolean) {
        PojavControlsMod.nativeSendKey(bedrockKeyCode, down)
    }

    override fun pojavSendMouseButton(androidButton: Int, down: Boolean) {
        if (down && org.chimeramc.client.core.mods.inbuilt.overlay.AimSettingsMod.isActive()) {
            overlayManager?.let { it.flashAimFeedback() }
        }
        if (down && androidButton == MotionEvent.BUTTON_PRIMARY) {
            overlayManager?.notifyAttack()
        }
        PojavControlsMod.nativeSendMouseButton(androidButton, down)
    }

    override fun pojavSendScroll(vertical: Float) {
        PojavControlsMod.nativeSendScroll(vertical)
    }

    override fun pojavSendLookDelta(deltaX: Float, deltaY: Float) {
        // One shaper, never more: each carries its own smoothing, so stacking them would
        // compound the damping. Hit Registration supersedes the others when it is on; the
        // catalogue's Rotation Smoother is next; Aim Settings is the default. Aim Settings keeps
        // drawing its crosshair regardless, since that is separate.
        val smoothed = if (org.chimeramc.client.core.mods.inbuilt.overlay.HitRegistrationMod
                .isActive()) {
            org.chimeramc.client.core.mods.inbuilt.overlay.HitRegistrationMod
                .shapeLookDelta(deltaX, deltaY)
        } else if (org.chimeramc.client.launcher.core.mods.inbuilt.overlay.RotationSmootherMod
                .isActive()) {
            org.chimeramc.client.launcher.core.mods.inbuilt.overlay.RotationSmootherMod
                .smoothLookDelta(deltaX, deltaY)
        } else {
            org.chimeramc.client.core.mods.inbuilt.overlay.AimSettingsMod
                .smoothLookDelta(deltaX, deltaY)
        }
        PojavControlsMod.nativeSendLookDelta(smoothed[0], smoothed[1])
    }

    override fun pojavSendPointer(x: Float, y: Float) {
        PojavControlsMod.nativeSendPointer(x, y)
    }

    override fun pojavSendTouch(event: MotionEvent): Boolean {
        return super.onTouchEvent(event)
    }

    override fun pojavShowKeyboard() {
        showSoftKeyboard()
    }

    override fun pojavIsMenuOpen(): Boolean {
        if (PreloaderInput.isShowingMenu() || PreloaderInput.isPauseMenuOpen()) return true
        return !PreloaderInput.shouldForceGlobalModMenu() && !PreloaderInput.isHudScreenOpen()
    }

    override fun onPause() {
        val shouldRestartAfterNormalExit = shouldRestartAfterNormalExit()
        if (shouldRestartAfterNormalExit) {
            PreloaderInput.cancelDocumentRequest("Minecraft closed")
            ModManager.disableAndUnloadLoadedMods()
            prepareNormalExitCleanup()
            scheduleNormalExitProcessRestart()
        }
        MinecraftActivityState.onPaused(this)
        super.onPause()
    }

    override fun onDestroy() {
        detachControllerMonitor()
        PreloaderInput.cancelDocumentRequest("Minecraft closed")
        ModManager.disableAndUnloadLoadedMods()
        val shouldPrepareNormalExit = shouldRestartAfterNormalExit()
        if (shouldPrepareNormalExit) {
            prepareNormalExitCleanup()
        }

        preloaderTextInput = null
        previousInputFocus = null
        PreloaderInput.clearActivity()
        MinecraftActivityState.onDestroyed(this)
        MinecraftLaunchSession.clear()
        stopInbuiltModServices()
        stopPlaytimeSession()
        MinecraftForegroundService.stop(this)

        try {
            super.onDestroy()
        } finally {
            if (shouldPrepareNormalExit) {
                scheduleNormalExitProcessRestart()
            }
        }
    }

    private fun shouldRestartAfterNormalExit(): Boolean {
        return gameRuntimeStarted && isFinishing && !CrashReporter.isHandlingCrash()
    }

    private fun prepareNormalExitCleanup() {
        if (normalExitPrepared) return
        normalExitPrepared = true
    }

    private fun scheduleNormalExitProcessRestart() {
        if (normalExitRestartScheduled) return
        normalExitRestartScheduled = true

        MinecraftProcessRestarter.restartLauncherAfterMinecraftExit(this)
    }

    override fun getAssets(): AssetManager {
        return if (::gameManager.isInitialized) {
            gameManager.getAssets()
        } else {
            super.getAssets()
        }
    }

    override fun getFilesDir(): File {
        return resolveStorageDir(MinecraftLauncher.EXTRA_STORAGE_FILES_DIR, super.getFilesDir())
    }

    override fun tick() {
        // The native hooks install here, not in onCreate. `tick()` is the game's own frame pump, so
        // by now the graphics backend is up and libminecraftpe.so is fully mapped — the RTTI/vtable
        // resolution the hook depends on sees a complete module, and the code patch runs on the same
        // thread that calls the render entry rather than racing it. A single native atomic load once
        // the install has run, so the steady-state cost is negligible. It runs before super.tick()
        // so the hook exists before the frame it will observe renders.
        ModManager.pumpGameHookInstall()
        super.tick()
        overlayManager?.tick()
    }

    override fun getDataDir(): File {
        return resolveStorageDir(MinecraftLauncher.EXTRA_STORAGE_DATA_DIR, super.getDataDir())
    }

    override fun getExternalFilesDir(type: String?): File? {
        val baseDir = resolveStorageDir(
            MinecraftLauncher.EXTRA_STORAGE_EXTERNAL_FILES_DIR,
            super.getExternalFilesDir(null)
        )
        return if (type.isNullOrEmpty()) {
            baseDir
        } else {
            File(baseDir, type).also { it.mkdirs() }
        }
    }

    override fun getInternalStoragePath(): String {
        return getFilesDir().absolutePath
    }

    override fun getExternalStoragePath(): String {
        return (getExternalFilesDir(null) ?: getFilesDir()).absolutePath
    }

    private fun resolveStorageDir(extraName: String, fallback: File?): File {
        val path = intent?.getStringExtra(extraName)
        val dir = if (!path.isNullOrEmpty()) File(path) else fallback ?: super.getFilesDir()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    override fun getDatabasePath(name: String): File {
        val dbDir = File(getDataDir(), "databases")
        if (!dbDir.exists()) {
            dbDir.mkdirs()
        }
        return File(dbDir, name)
    }

    override fun getCacheDir(): File {
        return resolveStorageDir(MinecraftLauncher.EXTRA_STORAGE_CACHE_DIR, super.getCacheDir())
    }

    private fun initializePreloaderTextInput() {
        val input = PreloaderTextInput(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isEmojiCompatEnabled = false
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.TRANSPARENT)
            isCursorVisible = false
            alpha = 0f
            visibility = View.GONE
        }
        findViewById<ViewGroup>(android.R.id.content).addView(
            input,
            ViewGroup.LayoutParams(1, 1)
        )
        preloaderTextInput = input
    }

    fun showSoftKeyboard() {
        runOnUiThread {
            val input = preloaderTextInput ?: return@runOnUiThread
            previousInputFocus = currentFocus?.takeUnless { it === input }
            input.visibility = View.VISIBLE
            input.setText("")
            input.requestFocus()
            input.setSelection(0)

            val inputMethodManager =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.restartInput(input)
            if (!inputMethodManager.showSoftInput(
                    input,
                    InputMethodManager.SHOW_IMPLICIT
                )
            ) {
                inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
            }
        }
    }

    fun hideSoftKeyboard() {
        runOnUiThread {
            val input = preloaderTextInput ?: return@runOnUiThread
            val inputMethodManager =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
            input.visibility = View.GONE

            previousInputFocus
                ?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }
                ?.requestFocus()
            previousInputFocus = null
        }
    }
}
