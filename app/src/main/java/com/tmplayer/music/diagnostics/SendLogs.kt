// From TMPlayer (GPL-3.0), app/src/main/java/com/tmplayer/ui/settings/LogActions.kt.
package com.tmplayer.music.diagnostics

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.LogReport
import com.tmplayer.music.platform.Logger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SendLogs"
private const val MIME = "text/plain"

/** When the file cannot be written (a full disk), the newest part goes as text instead, small enough for an intent. */
private const val TEXT_FALLBACK_BYTES = 100_000

/**
 * Send logs: the redacted report ([Diagnostics.report]) in a text file, handed to the system share
 * sheet, so the user picks the mail app, the chat or anything else. Nothing leaves the phone until
 * they do; this app sends nothing over the network itself.
 *
 * A second tap while the first is still being prepared does nothing. Failures say so in a toast:
 * the report could not be built, or no app on the phone takes a text file.
 */
@Composable
fun rememberSendLogs(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    return {
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    val outcome = withContext(Dispatchers.IO) { prepare(context) }
                    val said = when (outcome) {
                        null -> L.logsFailed
                        else -> share(context, outcome)
                    }
                    said?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Send logs failed", e)
                    runCatching { Toast.makeText(context, L.logsFailed, Toast.LENGTH_LONG).show() }
                } finally {
                    busy = false
                }
            }
        }
    }
}

/** What goes to the share sheet: the file, or the newest part as text when no file could be written. */
private sealed interface Prepared {
    class AsFile(val file: File) : Prepared
    class AsText(val text: String) : Prepared
}

private suspend fun prepare(context: Context): Prepared? {
    val report = try {
        Diagnostics.report(context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "the log report could not be built", e)
        return null
    }
    return try {
        // One file at a time in the one folder the FileProvider serves (see log_paths.xml).
        val dir = File(context.cacheDir, "logs-share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, Diagnostics.fileName())
        file.writeText(report, Charsets.UTF_8)
        Prepared.AsFile(file)
    } catch (e: Exception) {
        // A full disk, most likely: whatever was written goes, and the newest lines go as text.
        Logger.w(TAG, "the log file could not be written, sharing it as text", e)
        runCatching { File(context.cacheDir, "logs-share").listFiles()?.forEach { it.delete() } }
        val tail = report.toByteArray(Charsets.UTF_8).let { bytes ->
            if (bytes.size <= TEXT_FALLBACK_BYTES) report
            else LogReport.newestThatFit(report.split('\n'), TEXT_FALLBACK_BYTES).joinToString("\n")
        }
        Prepared.AsText(tail)
    }
}

/** Opens the share sheet. Null when it opened, else what to tell the user. */
private fun share(context: Context, prepared: Prepared): String? {
    val send = Intent(Intent.ACTION_SEND).setType(MIME)
        // For the maintainer, who reads reports in English, like the file's own header.
        .putExtra(Intent.EXTRA_SUBJECT, "${L.appName} logs")
    when (prepared) {
        is Prepared.AsFile -> {
            val uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.logs", prepared.file)
            } catch (e: IllegalArgumentException) {
                Logger.w(TAG, "the log file is outside the shared folder", e)
                return L.logsFailed
            }
            send.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        is Prepared.AsText -> send.putExtra(Intent.EXTRA_TEXT, prepared.text)
    }
    return try {
        context.startActivity(
            Intent.createChooser(send, L.logsSendTitle(L.appName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        null
    } catch (e: ActivityNotFoundException) {
        L.logsNoApp
    } catch (e: Exception) {
        Logger.w(TAG, "the share sheet did not open", e)
        L.logsFailed
    }
}

/**
 * The quieter button an error offers beside Try again: Send logs, then the share sheet.
 */
@Composable
fun SendLogsButton(modifier: Modifier = Modifier) {
    val send = rememberSendLogs()
    TextButton(onClick = send, modifier = modifier) { Text(L.logsSend) }
}

/**
 * After a crash, the next open offers Send logs once, in a dialog that can be dismissed. The offer
 * is marked made as soon as it shows, so a crash while it is up does not bring the same one back,
 * and only a new crash makes a new offer. Nothing is sent unless the user picks Send logs and then
 * an app in the share sheet.
 */
@Composable
fun CrashOffer() {
    val context = LocalContext.current
    // Saveable, so the dialog survives a rotation (the offer itself is already marked made).
    var show by rememberSaveable { mutableStateOf(false) }
    // Remembered outside the dialog, so its job outlives the dialog closing on the tap.
    val send = rememberSendLogs()
    LaunchedEffect(Unit) {
        val offer = withContext(Dispatchers.IO) {
            runCatching {
                val journal = Diagnostics.journal(context)
                journal.shouldOffer().also { if (it) journal.markOffered() }
            }.getOrDefault(false)
        }
        if (offer) show = true
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = { show = false },
        title = { Text(L.logsCrashTitle(L.appName)) },
        text = { Text(L.logsCrashBody) },
        confirmButton = {
            TextButton(onClick = {
                show = false
                send()
            }) { Text(L.logsSend) }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text(L.logsNotNow) } },
    )
}
