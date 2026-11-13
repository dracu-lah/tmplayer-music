// Adapted from the Android app's SendLogs (app/.../diagnostics/SendLogs.kt), itself from TMPlayer (GPL-3.0).
package com.tmplayer.music.desktop.diagnostics

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.LogReport
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * Send logs on the desktop: the redacted report ([Diagnostics.report]) is written to a text file in
 * the logs folder, the folder is opened in the file manager where the OS can, and a dialog says
 * where the file is, so the user attaches it to a mail or a chat themselves. Nothing leaves the
 * computer: this app sends nothing over the network itself.
 */
object SendLogs {
    private const val TAG = "SendLogs"

    /** The outcome to tell the user: where the file is, or that it could not be made. */
    data class Outcome(val message: String, val file: File?)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    fun dismiss() {
        _outcome.value = null
    }

    /** Builds and saves the report. A second call while one is running does nothing. */
    suspend fun run() {
        if (!_busy.compareAndSet(expect = false, update = true)) return
        try {
            _outcome.value = withContext(Dispatchers.IO) { prepare() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Send logs failed", e)
            _outcome.value = Outcome(L.logsFailed, null)
        } finally {
            _busy.value = false
        }
    }

    private suspend fun prepare(): Outcome {
        val report = try {
            Diagnostics.report()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "the log report could not be built", e)
            return Outcome(L.logsFailed, null)
        }
        return try {
            // One file at a time, in a folder of its own.
            val dir = Diagnostics.shareDir().apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, Diagnostics.fileName())
            file.writeText(report, Charsets.UTF_8)
            openFolder(dir)
            Outcome(L.logsSavedTo(file.absolutePath), file)
        } catch (e: Exception) {
            // A full disk, most likely: the newest part is on the clipboard instead, via the dialog.
            Logger.w(TAG, "the log file could not be written", e)
            Outcome(L.logsFailed, null)
        }
    }

    private fun openFolder(dir: File) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir)
            }
        }
    }
}

/** What an error offers beside Try again: Send logs. */
@Composable
fun SendLogsButton(modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val busy by SendLogs.busy.collectAsState()
    TextButton(onClick = { scope.launch { SendLogs.run() } }, enabled = !busy, modifier = modifier) { Text(L.logsSend) }
}

/**
 * The dialog for the outcome of Send logs, and, after a crash, the offer to send logs once. The
 * offer is marked made as soon as it shows, so a crash while it is up does not bring it back.
 */
@Composable
fun LogsHost() {
    val outcome by SendLogs.outcome.collectAsState()
    val clipboard = LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var offer by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        offer = withContext(Dispatchers.IO) { Diagnostics.shouldOfferAfterCrash() }
    }
    if (offer) {
        AlertDialog(
            onDismissRequest = { offer = false },
            title = { Text(L.logsCrashTitle(L.appName)) },
            text = { Text(L.logsCrashBody) },
            confirmButton = {
                TextButton(onClick = {
                    offer = false
                    scope.launch { SendLogs.run() }
                }) { Text(L.logsSend) }
            },
            dismissButton = { TextButton(onClick = { offer = false }) { Text(L.logsNotNow) } },
        )
    }
    outcome?.let { done ->
        AlertDialog(
            onDismissRequest = SendLogs::dismiss,
            title = { Text(L.logsSend) },
            text = { Text(done.message) },
            confirmButton = {
                TextButton(onClick = SendLogs::dismiss) { Text(L.commonOk) }
            },
            dismissButton = done.file?.let { file ->
                { TextButton(onClick = { clipboard.setText(AnnotatedString(file.absolutePath)) }) { Text(L.logsCopyPath) } }
            },
        )
    }
}
