// The onboarding order of the Android app's AppRoot (app/.../ui/AppRoot.kt).
package com.tmplayer.music.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tmplayer.music.data.Account
import com.tmplayer.music.data.AuthState
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.Td
import com.tmplayer.music.desktop.DesktopApp
import com.tmplayer.music.desktop.DesktopPaths
import com.tmplayer.music.desktop.ui.auth.SignInScreen
import com.tmplayer.music.desktop.ui.auth.rememberScanHold
import com.tmplayer.music.desktop.ui.common.ErrorPanel
import com.tmplayer.music.desktop.ui.common.Loading
import com.tmplayer.music.desktop.ui.library.ChatPicker
import com.tmplayer.music.library.ChatPick
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The onboarding order: sign in, pick the chats with music once, then the library. The picker is
 * not forced again after it was finished, even empty; Manage chats opens it later. A restored
 * queue is back in the player (paused) once the library is ready, never before the account check.
 */
@Composable
fun AppRoot() {
    val prefs = DesktopApp.prefs
    var pickerDone by remember { mutableStateOf(prefs.pickerDone) }
    val tdAuth by Td.auth.collectAsState()
    // A scanned QR code stays up for a moment with a tick before the sign in moves on.
    val auth = rememberScanHold(tdAuth)
    val ready by Library.ready.collectAsState()
    val scope = rememberCoroutineScope()
    var hasChats by remember { mutableStateOf<Boolean?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(ready, attempt) {
        if (!ready) return@LaunchedEffect
        failure = null
        hasChats = try {
            Library.index().pickedChats().isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("AppRoot", "could not read the chosen chats", e)
            failure = Failures.humanise(e)
            null
        }
        // The queue of the last run, paused: nothing plays and nothing downloads until Play.
        if (hasChats != null) DesktopApp.player.restore()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            auth !is AuthState.Ready -> SignInScreen(auth)
            failure != null -> ErrorPanel(failure.orEmpty(), onRetry = { attempt++ })
            !ready || hasChats == null -> Loading()
            !pickerDone && hasChats == false -> ChatPicker(
                directory = Library.directory,
                already = emptySet(),
                onDone = { picked ->
                    scope.launch(JobFailures.handler("ChatPicker")) {
                        runCatching { Library.pick(picked.map { ChatPick(it.chatId, it.title) }) }
                        prefs.pickerDone = true
                        pickerDone = true
                        hasChats = picked.isNotEmpty()
                    }
                },
            )
            else -> Shell(
                onLogOut = {
                    scope.launch(JobFailures.handler("LogOut")) {
                        DesktopApp.player.clear()
                        // The avatar and the name go with the account.
                        Account.clear()
                        runCatching { DesktopPaths.queueFile.delete() }
                        runCatching { Library.wipe() }
                        prefs.pickerDone = false
                        pickerDone = false
                        hasChats = null
                        runCatching { Td.logOut() }
                    }
                },
            )
        }
    }
}
