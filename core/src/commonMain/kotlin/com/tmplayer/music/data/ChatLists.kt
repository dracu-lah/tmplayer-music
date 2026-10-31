// Adapted from TMPlayer (GPL-3.0), core/src/commonMain/kotlin/com/tmplayer/data/ChatListLoad.kt and
// ChatRepository.kt: the list read from TDLib's own copy first, then loaded page by page (404 is the
// end), the archive after the main list, folders read behind the list, getChat in chunks.
package com.tmplayer.music.data

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatListFolder
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatTypeSecret
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/** One of the chat lists TDLib keeps: the main list, the archive, or a folder. */
sealed interface ChatListRef {
    data object Main : ChatListRef
    data object Archive : ChatListRef
    data class Folder(val id: Int) : ChatListRef
}

/** How one `loadChats` went. */
sealed interface ChatLoad {
    /** A page came in; there may be more. */
    data object Loaded : ChatLoad

    /** TDLib's 404: every chat of the list is already local, the documented "no more". */
    data object AllLoaded : ChatLoad

    /** Telegram asked to wait this long before the next page. */
    data class FloodWait(val seconds: Int) : ChatLoad

    /** Anything else, with TDLib's own message. */
    data class Failed(val message: String) : ChatLoad
}

/** A chat as the picker needs it, read from TDLib's memory. */
data class ChatFacts(
    val chatId: Long,
    val title: String,
    val photoFileId: Int,
    val miniThumbnail: ByteArray?,
    /** In the archive now, from the chat's own positions (which list it came from can be a moment old). */
    val archived: Boolean,
    /** A secret chat: its files cannot be streamed into a library. */
    val secret: Boolean,
)

/** A chat moved between the main list and the archive (Telegram's `updateChatPosition`). */
data class ChatMove(val chatId: Long, val archived: Boolean)

/** One of the user's Telegram folders. */
data class ChatFolder(val id: Int, val title: String)

/** The TDLib calls the picker's list is read with, behind a seam for the tests. */
interface ChatListCalls {
    suspend fun load(list: ChatListRef, limit: Int): ChatLoad

    /** `getChats`: the first [limit] ids of [list] in TDLib's order; null when TDLib fails. */
    suspend fun ids(list: ChatListRef, limit: Int): List<Long>?

    /** One chat; null when TDLib has no answer. */
    suspend fun chat(chatId: Long): ChatFacts?

    /** Saved Messages' chat id; null when not known. */
    suspend fun savedMessagesId(): Long?

    /** Chats moving between the main list and the archive while the picker is open. */
    val moves: Flow<ChatMove>
}

/** [ChatListCalls] over a TDLib client. */
class TdChatListCalls(private val td: TdlClient) : ChatListCalls {
    private fun ChatListRef.td(): dev.g000sha256.tdl.dto.ChatList = when (this) {
        ChatListRef.Main -> ChatListMain()
        ChatListRef.Archive -> ChatListArchive()
        is ChatListRef.Folder -> ChatListFolder(id)
    }

    override suspend fun load(list: ChatListRef, limit: Int): ChatLoad =
        when (val result = td.loadChats(list.td(), limit)) {
            is TdlResult.Success -> ChatLoad.Loaded
            is TdlResult.Failure -> when {
                // The 404 is in the code; the message is "Not Found".
                result.code == 404 || result.message.contains("404") -> ChatLoad.AllLoaded
                else -> Failures.floodWaitSeconds(result.message)?.let { ChatLoad.FloodWait(it) } ?: ChatLoad.Failed(result.message)
            }
        }

    override suspend fun ids(list: ChatListRef, limit: Int): List<Long>? =
        (td.getChats(list.td(), limit) as? TdlResult.Success)?.result?.chatIds?.toList()

    override suspend fun chat(chatId: Long): ChatFacts? {
        val chat = (td.getChat(chatId) as? TdlResult.Success)?.result ?: return null
        return ChatFacts(
            chatId = chat.id,
            title = chat.title.orEmpty().trim(),
            photoFileId = chat.photo?.small?.id ?: 0,
            miniThumbnail = chat.photo?.minithumbnail?.data,
            archived = chat.positions.orEmpty().any { it.list is ChatListArchive && it.order != 0L },
            secret = chat.type is ChatTypeSecret,
        )
    }

    override suspend fun savedMessagesId(): Long? {
        val me = (td.getMe() as? TdlResult.Success)?.result ?: return null
        return (td.createPrivateChat(me.id, false) as? TdlResult.Success)?.result?.id
    }

    override val moves: Flow<ChatMove>
        get() = td.chatPositionUpdates.mapNotNull { update ->
            val position = update.position
            // An order of 0 takes the chat off that list; the other list's update says where it went.
            if (position.order == 0L) return@mapNotNull null
            when (position.list) {
                is ChatListMain -> ChatMove(update.chatId, archived = false)
                is ChatListArchive -> ChatMove(update.chatId, archived = true)
                else -> null
            }
        }
}
