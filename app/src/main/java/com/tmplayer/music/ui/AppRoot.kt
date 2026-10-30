package com.tmplayer.music.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.AuthState
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.Prefs
import com.tmplayer.music.data.Td
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.library.ChatPick
import com.tmplayer.music.ui.auth.SignInScreen
import com.tmplayer.music.ui.auth.rememberScanHold
import com.tmplayer.music.ui.library.LibraryHome
import com.tmplayer.music.ui.onboarding.ChatPicker
import com.tmplayer.music.ui.onboarding.Welcome
import com.tmplayer.music.ui.player.PlayerSheetHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The onboarding order (`plan/RESEARCH.md` 4.1): Welcome once, sign in, pick the chats with music
 * once, then the library. The picker is not forced again after it was finished, even empty;
 * Manage chats opens it later.
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var welcomed by remember { mutableStateOf(prefs.welcomed) }
    var pickerDone by remember { mutableStateOf(prefs.pickerDone) }
    val tdAuth by Td.auth.collectAsStateWithLifecycle()
    // A scanned QR code stays up for a moment with a tick before the sign in moves on.
    val auth = rememberScanHold(tdAuth)
    val ready by Library.ready.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var hasChats by remember { mutableStateOf<Boolean?>(null) }
    // Picked in this session: the library opens on its Chats tab, where they play at once.
    var justPicked by remember { mutableStateOf(false) }

    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        hasChats = try {
            Library.index().pickedChats().isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    when {
        !welcomed -> Welcome {
            prefs.welcomed = true
            welcomed = true
        }
        auth !is AuthState.Ready -> SignInScreen(auth)
        !ready || hasChats == null -> Loading()
        !pickerDone && hasChats == false -> ChatPicker(
            directory = Library.directory,
            already = emptySet(),
            onDone = { picked ->
                scope.launch {
                    runCatching { Library.pick(picked.map { ChatPick(it.chatId, it.title) }) }
                    prefs.pickerDone = true
                    pickerDone = true
                    hasChats = picked.isNotEmpty()
                    justPicked = picked.isNotEmpty()
                }
            },
        )
        else -> PlayerSheetHost { LibraryHome(
            startOnChats = justPicked,
            onLogOut = {
                scope.launch {
                    PlayerConnection.clear()
                    // The avatar and the name go with the account.
                    com.tmplayer.music.data.Account.clear()
                    com.tmplayer.music.ui.library.AvatarCache.clear()
                    runCatching { context.deleteFile("queue.json") }
                    runCatching { Library.wipe() }
                    prefs.pickerDone = false
                    pickerDone = false
                    hasChats = null
                    runCatching { Td.logOut() }
                }
            },
        ) }
    }
}

@Composable
private fun Loading() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}
