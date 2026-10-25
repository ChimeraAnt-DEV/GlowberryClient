package org.chimeramc.client.core.minecraft

import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.chimeramc.client.R
import org.chimeramc.client.core.minecraft.LaunchLogBuffer
import org.chimeramc.client.launcher.core.minecraft.LastLaunchFailure
import org.chimeramc.client.launcher.core.minecraft.LaunchErrorDiagnosis
import org.chimeramc.client.ui.activities.BaseActivity
import org.chimeramc.client.ui.dialogs.CustomAlertDialog
import org.chimeramc.client.util.PersonalizationManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MinecraftLoadingActivity : BaseActivity(), MinecraftRuntimePreparer.ProgressListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var progressBar: ProgressBar
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var returnButton: Button
    private lateinit var trace: LaunchTrace

    @Volatile
    private var returningToLauncher = false
    private var preparingStarted = false
    private var enteringGame = false
    private var progressAnimator: ValueAnimator? = null
    private var currentProgress = 0
    private var lastLogMessage: String? = null
    private var logRenderScheduled = false
    private var statusRenderScheduled = false
    private var lastRenderedStatus: String? = null
    private var lastRenderedDetail: String? = null
    // The cap and the render live in a pure, tested class; the view is assigned its whole output
    // so the two can never disagree about how many lines exist.
    private val launchLog = LaunchLogBuffer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trace = LaunchTrace.ensure(intent)
        trace.mark("MinecraftLoadingActivity onCreate")
        applyLaunchOrientation()
        hideSystemUi()
        window.decorView.setOnSystemUiVisibilityChangeListener {
            if (!isFinishing && !isDestroyed && !returningToLauncher) {
                window.decorView.post { hideSystemUi() }
            }
        }
        setContentView(R.layout.activity_minecraft_loading)
        hideSystemUi()

        progressBar = findViewById(R.id.minecraftLaunchProgress)
        statusView = findViewById(R.id.minecraftLaunchStatus)
        detailView = findViewById(R.id.minecraftLaunchDetail)
        logView = findViewById(R.id.minecraftLaunchLog)
        logScroll = findViewById(R.id.minecraftLaunchLogScroll)
        returnButton = findViewById(R.id.minecraftLaunchReturn)

        returnButton.setOnClickListener {
            appendLog("Returning to launcher")
            returnToLauncher()
        }

        applyLaunchTheme()

        MinecraftLaunchSession.clear()
        trace.milestone("Launch screen ready")
        appendLog("Preparing launch")
        appendLog("Preparing game files")
        startPreparingAfterFirstDraw()
    }

    override fun shouldSkipNavBar(): Boolean = true

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    private fun startPreparingAfterFirstDraw() {
        val content = window.decorView
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (content.viewTreeObserver.isAlive) {
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                }
                content.post {
                    hideSystemUi()
                    startPreparingOnce("loading first frame drawn")
                }
                return true
            }
        })
        content.postDelayed({
            hideSystemUi()
            startPreparingOnce("loading first frame fallback")
        }, FIRST_FRAME_FALLBACK_MS)
    }

    private fun startPreparingOnce(reason: String) {
        if (preparingStarted) return
        preparingStarted = true
        trace.milestone("Runtime preparation continuing")
        appendLog("Preparing game")
        startPreparing()
    }

    private fun applyLaunchOrientation() {
        val version = MinecraftRuntimePreparer.resolveGameVersion(intent)
        val launchVertically = version?.launchVertically
            ?: intent.getBooleanExtra("LAUNCH_VERTICALLY", false)
        if (launchVertically) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun startPreparing() {
        executor.execute {
            try {
                val gameIntent = Intent(intent).apply {
                    setClass(this@MinecraftLoadingActivity, MinecraftActivity::class.java)
                }
                val preparedRuntime = MinecraftRuntimePreparer.prepare(this, gameIntent, this)
                MinecraftLaunchSession.setPreparedRuntime(preparedRuntime)

                mainHandler.post {
                    if (returningToLauncher || isFinishing || isDestroyed) return@post
                    updateProgress(100, getString(R.string.minecraft_loading_complete), getString(R.string.minecraft_loading_entering_game))
                    trace.milestone("Entering game")
                    appendLog("Entering Minecraft")
                    // Starting the game activity is the last thing that can fail, and it failed
                    // outside any guard: a throw from startActivity (a missing target, a
                    // TransactionTooLarge, an OOM while the game activity inflated) unwound through
                    // the main thread and killed the process, so the user saw the app vanish with
                    // no message at all. It is contained here and reported through the same
                    // cause-naming path every other launch failure uses.
                    try {
                        showSkippedIncompatibleModsIfNeeded(preparedRuntime, gameIntent)
                    } catch (throwable: Throwable) {
                        showFailure(throwable)
                    }
                }
            } catch (throwable: Throwable) {
                mainHandler.post {
                    showFailure(throwable)
                }
            }
        }
    }

    override fun onProgress(progress: Int, status: String, detail: String?) {
        mainHandler.post {
            updateProgress(progress, status, detail)
        }
    }

    override fun onLog(message: String) {
        mainHandler.post {
            appendLog(message)
        }
    }

    private fun updateProgress(progress: Int, status: String, detail: String?) {
        if (isFinishing || isDestroyed) return
        animateProgressTo(progress.coerceIn(0, 100))
        scheduleStatusRender(status, detail)
    }

    private fun scheduleStatusRender(status: String, detail: String?) {
        if (isFinishing || isDestroyed) return
        if (status == lastRenderedStatus && detail == lastRenderedDetail) return
        lastRenderedStatus = status
        lastRenderedDetail = detail
        if (statusRenderScheduled) return
        statusRenderScheduled = true
        mainHandler.postDelayed({
            statusRenderScheduled = false
            if (isFinishing || isDestroyed) return@postDelayed
            statusView.text = status
            detailView.text = detail.orEmpty()
        }, STATUS_RENDER_DEBOUNCE_MS)
    }

    private fun appendLog(message: String) {
        if (isFinishing || isDestroyed) return
        if (message == lastLogMessage) return
        val timestamp = timeFormat.format(Date())
        lastLogMessage = message
        launchLog.add("[$timestamp] ${trace.formatForUi(message)}")
        scheduleLogRender()
    }

    private fun scheduleLogRender() {
        if (logRenderScheduled) return
        logRenderScheduled = true
        mainHandler.postDelayed({
            logRenderScheduled = false
            if (isFinishing || isDestroyed) return@postDelayed
            // The buffer mirrors what should be on screen, so it is re-rendered wholesale. Appending
            // only the newly arrived lines treated it as a delta queue, which left the line cap
            // bounding the *buffer* and not the view: the TextView accumulated every line of the
            // whole launch with no ceiling, so a slow extraction grew it into a long-layout,
            // high-memory scroll that also duplicated lines when a render drained the queue.
            logView.text = launchLog.render()
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }, LOG_RENDER_DEBOUNCE_MS)
    }

    private fun showFailure(throwable: Throwable) {
        if (isFinishing || isDestroyed) return
        val message = throwable.message ?: throwable.javaClass.simpleName
        // Name the cause, then the raw text. This is the screen a launch actually dies on, so it is
        // where "dlopen failed: library \"libminecraftpe.so\" not found" has to become "this is a
        // Google Play version or a version missing a file needed to launch the game".
        val cause = LaunchErrorDiagnosis.classify(throwable)
        val causeLine = getString(LaunchErrorDiagnosis.messageRes(cause))
        updateProgress(100, getString(LaunchErrorDiagnosis.titleRes(cause)), null)
        detailView.visibility = View.GONE
        trace.error("Launch failed", message)
        appendLog("Launch failed")
        appendLog(getString(R.string.launch_error_cause_label) + " " + LaunchErrorDiagnosis.label(cause))
        appendLog(causeLine)
        appendLog(message)
        // The user is looking at the cause on this screen, so the launcher must not repeat it after
        // the restart. The report is stored as announced and any earlier pending report is dropped:
        // this screen is authoritative whenever it can be shown.
        LastLaunchFailure.write(this, throwable, true)
        returnButton.visibility = View.VISIBLE
    }

    private fun animateProgressTo(targetProgress: Int) {
        if (progressBar.progress == targetProgress) return
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofInt(currentProgress, targetProgress).apply {
            duration = PROGRESS_ANIMATION_MS
            addUpdateListener { animator ->
                currentProgress = animator.animatedValue as Int
                progressBar.progress = currentProgress
            }
            start()
        }
    }

    private fun applyLaunchTheme() {
        val configuredAccent = PersonalizationManager(this).getAccentColor()
        val accent = if (configuredAccent != 0) configuredAccent else ContextCompat.getColor(this, R.color.primary)
        val accentTint = ColorStateList.valueOf(accent)
        val secondaryText = ContextCompat.getColor(this, R.color.text_secondary)

        progressBar.progressTintList = accentTint
        progressBar.progressBackgroundTintList = ColorStateList.valueOf(withAlpha(accent, TRACK_ALPHA))
        progressBar.indeterminateTintList = accentTint
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            progressBar.progressDrawable?.colorFilter = null
        }

        logScroll.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (isDarkMode()) Color.argb(238, 16, 20, 18) else ContextCompat.getColor(this@MinecraftLoadingActivity, R.color.surface))
            setStroke(dp(1), withAlpha(accent, if (isDarkMode()) 88 else 120))
        }
        logView.setTextColor(blendColors(ContextCompat.getColor(this, R.color.on_background), accent, if (isDarkMode()) 0.10f else 0.18f))
        detailView.setTextColor(blendColors(secondaryText, accent, 0.18f))
        returnButton.backgroundTintList = accentTint
    }

    private fun isDarkMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val boundedRatio = ratio.coerceIn(0f, 1f)
        val inverse = 1f - boundedRatio
        return Color.rgb(
            (Color.red(from) * inverse + Color.red(to) * boundedRatio).toInt(),
            (Color.green(from) * inverse + Color.green(to) * boundedRatio).toInt(),
            (Color.blue(from) * inverse + Color.blue(to) * boundedRatio).toInt()
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun transitionToGame(gameIntent: Intent) {
        if (enteringGame) return
        enteringGame = true

        if (returningToLauncher || isFinishing || isDestroyed) return
        startActivity(gameIntent)
        finish()
        overridePendingTransition(0, 0)  // no animation, test only
    }

    private fun showSkippedIncompatibleModsIfNeeded(
        preparedRuntime: MinecraftRuntimePreparer.PreparedRuntime,
        gameIntent: Intent
    ) {
        val skippedMods = preparedRuntime.skippedIncompatibleMods
        if (skippedMods.isEmpty()) {
            transitionToGame(gameIntent)
            return
        }

        val minecraftVersion = preparedRuntime.version?.versionCode
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.minecraft)
        val modList = skippedMods.joinToString(separator = "\n") { "- $it" }
        val dialog = CustomAlertDialog(this)
            .setTitleText(getString(R.string.dialog_title_incompatible_mods))
            .setMessage(getString(R.string.dialog_message_incompatible_mods, minecraftVersion, modList))
            .setBlurBackground(true)
            .setPositiveButton(getString(R.string.dialog_positive_ok)) {
                transitionToGame(gameIntent)
            }
        dialog.setCancelable(false)
        dialog.show()
    }

    override fun onBackPressed() {
        appendLog("Returning to launcher")
        returnToLauncher()
    }

    private fun returnToLauncher() {
        if (returningToLauncher) return
        returningToLauncher = true

        MinecraftLaunchSession.clear()
        MinecraftProcessRestarter.restartLauncherAfterMinecraftExit(this)
        finish()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        progressAnimator?.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun hideSystemUi() {
        val decorView = window.decorView

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            decorView.windowInsetsController?.let { controller ->
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        }

        decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private companion object {
        private const val FIRST_FRAME_FALLBACK_MS = 240L
        private const val PROGRESS_ANIMATION_MS = 140L
        private const val LOG_RENDER_DEBOUNCE_MS = 200L
        private const val STATUS_RENDER_DEBOUNCE_MS = 100L
        private const val TRACK_ALPHA = 42
    }
}
