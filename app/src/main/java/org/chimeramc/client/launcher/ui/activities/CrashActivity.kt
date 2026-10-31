package org.chimeramc.client.ui.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.chimeramc.client.R
import org.chimeramc.client.core.crash.CrashReporter
import org.chimeramc.client.core.crash.ProcessExitReasons
import org.chimeramc.client.core.mods.ModManager
import org.chimeramc.client.core.mods.ModSafeMode
import org.chimeramc.client.core.versions.GameVersion
import org.chimeramc.client.core.versions.VersionManager
import org.chimeramc.client.launcher.core.minecraft.LaunchErrorDiagnosis
import org.chimeramc.client.ui.animation.DynamicAnim
import java.io.File
import java.util.concurrent.Executors

class CrashActivity : BaseActivity() {

    private var executor = Executors.newSingleThreadExecutor()
    private var logPath: String? = null
    private var disableButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_crash)

        logPath = intent.getStringExtra("LOG_PATH")
        val summary = intent.getStringExtra("SUMMARY")
            ?: intent.getStringExtra("EMERGENCY")
        val crashType = intent.getStringExtra("CRASH_TYPE")
        CrashReporter.sendUnsentReports(this)

        val tvTitle = findViewById<TextView>(R.id.crash_title)
        val tvDetails = findViewById<TextView>(R.id.crash_details)
        val btnBack = findViewById<Button>(R.id.btn_back_to_main)
        val btnShare = findViewById<Button>(R.id.btn_share_log)
        DynamicAnim.applyPressScale(btnBack)
        DynamicAnim.applyPressScale(btnShare)

        tvTitle.text = getString(R.string.crash_title)

        // Name the cause from the crash's own text, so a native dlopen tombstone reads as
        // "Game library not readable / Google Play version" rather than only a raw stack. The raw
        // text stays underneath for the report.
        val cause = LaunchErrorDiagnosis.classifyText(summary)
        val causeLine = getString(R.string.launch_error_cause_label) + " " +
                LaunchErrorDiagnosis.label(cause) + "\n" +
                getString(LaunchErrorDiagnosis.messageRes(cause)) + "\n\n"

        val exitLine = exitReasonLine()
        tvDetails.text = buildString {
            append(causeLine)
            if (exitLine.isNotEmpty()) {
                append(getString(R.string.crash_exit_reason_label)).append(' ').append(exitLine)
                    .append("\n\n")
            }
            appendHeader(crashType, summary)
            if (!logPath.isNullOrBlank()) {
                append(getString(R.string.crash_log_file_label)).append("\n")
                append(logPath).append("\n\n")
            }
            append(getString(R.string.crash_loading_details))
        }

        btnBack.setOnClickListener {
            navigateToMain()
        }
        
        btnShare.setOnClickListener {
            shareLogFile()
        }

        val btnDisable = findViewById<Button>(R.id.btn_disable_last_mod)
        disableButton = btnDisable
        btnDisable.setOnClickListener { disableSuspectMods(tvDetails) }
        DynamicAnim.applyPressScale(btnDisable)
        refreshDisableAction()

        Handler(Looper.getMainLooper()).postDelayed({
            loadCrashDetails(tvDetails, crashType, summary)
        }, 100)
        Handler(Looper.getMainLooper()).postDelayed({
            CrashReporter.sendUnsentReports(this)
        }, 2000)
    }

    override fun shouldSkipNavBar(): Boolean = true

    private fun loadCrashDetails(tvDetails: TextView, crashType: String?, summary: String?) {
        if (isFinishing || isDestroyed) return

        executor.execute {
            val cause = LaunchErrorDiagnosis.classifyText(summary)
            val causeLine = getString(R.string.launch_error_cause_label) + " " +
                    LaunchErrorDiagnosis.label(cause) + "\n" +
                    getString(LaunchErrorDiagnosis.messageRes(cause)) + "\n\n"
            val exitLine = exitReasonLine()
            val detailsText = buildString {
                append(causeLine)
                if (exitLine.isNotEmpty()) {
                    append(getString(R.string.crash_exit_reason_label)).append(' ').append(exitLine)
                        .append("\n\n")
                }
                appendHeader(crashType, summary)

                if (!logPath.isNullOrEmpty()) {
                    append(getString(R.string.crash_log_file_label)).append("\n")
                    append(logPath).append("\n\n")
                    append(getString(R.string.crash_stack_label)).append("\n")
                    try {
                        val f = File(logPath!!)
                        if (f.exists() && f.isFile) {
                            val content = f.readText()
                            val maxLength = 50000
                            if (content.length > maxLength) {
                                append(content.substring(0, maxLength))
                                append("\n\n... [truncated, full log in file] ...")
                            } else {
                                append(content)
                            }
                        } else {
                            append(getString(R.string.crash_log_file_missing)).append("\n")
                        }
                    } catch (e: Exception) {
                        // Name the cause, not the raw exception text: a report that cannot be read
                        // is usually a permission or a deleted file, and "Permission denied" alone
                        // does not tell the user which of the two it is.
                        append(getString(R.string.crash_read_failed, describeReadFailure(e))).append("\n")
                    }
                }
                if (isEmpty()) {
                    append(getString(R.string.crash_no_details))
                }
            }

            if (!isFinishing && !isDestroyed) {
                runOnUiThread {
                    tvDetails.text = detailsText
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /**
     * The platform's own record of why the previous process ended (API 30+), or an empty string
     * below that or when there is no record. This is strictly better than the saved log tail for the
     * cases a truncated log cannot distinguish, so it is shown above the stack when it exists.
     */
    private fun exitReasonLine(): String {
        return try {
            ProcessExitReasons.describe(ProcessExitReasons.read(this))
        } catch (ignored: Throwable) {
            ""
        }
    }

    /**
     * Shows the "disable the mod that crashed" button only when there really are suspect mods to
     * disable, so it is never a button that does nothing. The suspects come from the same safe-mode
     * store the Mods screen uses, so the two screens agree on which mods were live at the crash.
     */
    private fun refreshDisableAction() {
        val button = disableButton ?: return
        val suspects = try {
            ModSafeMode.suspectModIds(this)
        } catch (ignored: Throwable) {
            emptyList()
        }
        button.visibility = if (suspects.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    /**
     * Disables the mods recorded as loaded when the previous launch crashed, then acknowledges the
     * crash so the same prompt is not shown again. This is the crash screen's own copy of the Mods
     * screen's action: a user who landed straight on the crash report can fix it here rather than
     * having to find the Mods tab first.
     */
    private fun disableSuspectMods(tvDetails: TextView) {
        val suspects = try {
            ModSafeMode.suspectModIds(this)
        } catch (ignored: Throwable) {
            emptyList()
        }
        if (suspects.isEmpty()) {
            Toast.makeText(this, R.string.crash_disable_no_suspects, Toast.LENGTH_SHORT).show()
            refreshDisableAction()
            return
        }
        executor.execute {
            var disabled = 0
            try {
                val manager = ModManager.getInstance()
                val version: GameVersion? = VersionManager.getIfInitialized()?.selectedVersion
                if (version != null) {
                    manager.setCurrentVersion(version)
                }
                for (id in suspects) {
                    manager.setModEnabled(id, false)
                    disabled++
                }
            } catch (ignored: Throwable) {
                // A failure to disable is reported below by the count, never as a crash.
            }
            ModSafeMode.acknowledgeCrash(this, true)
            val finalDisabled = disabled
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(
                    this,
                    getString(R.string.crash_disable_done, finalDisabled),
                    Toast.LENGTH_LONG
                ).show()
                refreshDisableAction()
            }
        }
    }

    private fun StringBuilder.appendHeader(crashType: String?, summary: String?) {
        if (!crashType.isNullOrBlank()) {
            append(getString(R.string.crash_type_label)).append("\n")
            append(crashType).append("\n\n")
        }
        if (!summary.isNullOrBlank()) {
            append(getString(R.string.crash_summary_label)).append("\n")
            append(summary).append("\n\n")
        }
    }

    private fun navigateToMain() {
        try {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        } catch (ignored: Exception) {
            finish()
        }
    }

    private fun shareLogFile() {
        val path = logPath
        if (path.isNullOrBlank()) {
            // The button used to do nothing at all when the intent carried no log path, which
            // reads as a broken button rather than as a missing report.
            Toast.makeText(this, R.string.crash_share_no_log, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val logFile = File(path)
            if (!logFile.exists() || !logFile.isFile) {
                Toast.makeText(this, R.string.crash_log_file_missing, Toast.LENGTH_SHORT).show()
                return
            }
            val shareFile = copyLogToShareCache(logFile)
            val logFileUri = FileProvider.getUriForFile(
                this,
                packageName + ".fileprovider",
                shareFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, logFileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, shareFile.name))
        } catch (e: Exception) {
            // Swallowing this left a share that silently did nothing - the user tapped, nothing
            // appeared, and there was no way to tell whether the report existed.
            Toast.makeText(
                this,
                getString(R.string.crash_share_failed, describeReadFailure(e)),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** A short, cause-specific sentence for a report that could not be read or shared. */
    private fun describeReadFailure(error: Throwable): String = when (error) {
        is java.io.FileNotFoundException -> getString(R.string.crash_reason_file_missing)
        is SecurityException -> getString(R.string.crash_reason_no_permission)
        is java.io.IOException -> getString(R.string.crash_reason_io)
        else -> error.javaClass.simpleName
    }

    private fun copyLogToShareCache(logFile: File): File {
        val shareDir = File(cacheDir, "crash_logs_share")
        if (!shareDir.exists()) shareDir.mkdirs()
        val shareFile = File(shareDir, logFile.name)
        logFile.copyTo(shareFile, overwrite = true)
        return shareFile
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            executor.shutdownNow()
        } catch (ignored: Exception) {}
    }
}
