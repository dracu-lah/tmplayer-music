package com.tmplayer.music.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.ChatPhoto
import com.tmplayer.music.data.Library
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filter

/**
 * A chat's round picture for a row that knows the chat by id alone (the Chats tab, Manage chats, a
 * chat page): the photo is asked of Telegram when the row shows ([com.tmplayer.music.data.ChatPhotos]),
 * the inline preview shows while it downloads, and the initials on a colour when there is no photo,
 * it could not be read or its download failed. A photo changed in Telegram is shown again.
 */
@Composable
fun ChatAvatar(chatId: Long, name: String, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    var photo by remember(chatId) { mutableStateOf<ChatPhoto?>(Library.chatPhotos.cached(chatId)) }
    LaunchedEffect(chatId) {
        suspend fun load() {
            photo = try {
                Library.chatPhotos.photo(chatId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        if (photo == null) load()
        Library.chatPhotos.changes.filter { it == chatId }.collect { load() }
    }
    Cover(name = name, fileId = photo?.fileId ?: 0, mini = photo?.mini, round = true, size = size, modifier = modifier)
}
