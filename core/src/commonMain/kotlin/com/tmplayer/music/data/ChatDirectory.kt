package com.tmplayer.music.data

import com.tmplayer.music.platform.Logger
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.ChatList
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatTypeSecret
import dev.g000sha256.tdl.dto.SearchMessagesFilterAudio
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** One row of the chat picker. */
data class ChatEntry(
    val chatId: Long,
    val title: String,
    val savedMessages: Boolean,
    val archived: Boolean,
    /** The small avatar's file id, 0 when the chat has none. */
    val photoFileId: Int,
)

/**
 * The user's own chats, for the picker: Saved Messages first, then the main list in Telegram's
 * order, then the archive. Only chats the user is in: there is no public search here, by design
 * (`plan/RESEARCH.md` section 11).
 *
 * Large accounts are the case to design for. The list is TDLib's own (it is on disk after the
 * first run, so it answers offline); at most [MAX_CHATS] are listed, and each chat's audio count
 * is asked for only when its row is on screen ([audioCount]), a few at a time, and remembered.
 */
class ChatDirectory(private val client: suspend () -> TdlClient) {

    private val counts = ConcurrentHashMap<Long, Int>()
    private val countGate = Semaphore(COUNT_PARALLEL)

    /** Every listable chat, Saved Messages first. Partial on failure, never throws. */
    suspend fun chats(): List<ChatEntry> {
        val td = client()
        val saved = savedMessagesId(td)
        val out = LinkedHashMap<Long, ChatEntry>()
        if (saved != null) entry(td, saved, archived = false, savedId = saved)?.let { out[saved] = it }
        for ((list, archived) in listOf(ChatListMain() to false, ChatListArchive() to true)) {
            for (id in chatIds(td, list, MAX_CHATS - out.size)) {
                if (id in out) continue
                entry(td, id, archived, saved)?.let { out[id] = it }
            }
        }
        return out.values.toList()
    }

    /** The count already known for [chatId], without asking Telegram. */
    fun knownCount(chatId: Long): Int? = counts[chatId]

    /**
     * How many audio messages [chatId] has, from Telegram, or null when it would not say. Audio
     * sent as documents (most FLAC) is not in this count: the scan finds it.
     */
    suspend fun audioCount(chatId: Long): Int? {
        counts[chatId]?.let { return it }
        return countGate.withPermit {
            counts[chatId]?.let { return@withPermit it }
            val td = client()
            var answer: Int? = null
            for (attempt in 0 until COUNT_TRIES) {
                val result = withTimeoutOrNull(COUNT_TIMEOUT_MS) {
                    td.getChatMessageCount(chatId, null, SearchMessagesFilterAudio(), false)
                } ?: break
                when (result) {
                    is TdlResult.Success -> {
                        answer = result.result.count.coerceAtLeast(0)
                        break
                    }
                    is TdlResult.Failure -> {
                        // A flood wait is waited out by this call alone; the other rows go on.
                        val wait = Failures.floodWaitSeconds(result.message) ?: break
                        if (wait > MAX_WAIT_S) break
                        delay(wait * 1000L)
                    }
                }
            }
            answer?.also { counts[chatId] = it }
        }
    }

    private suspend fun savedMessagesId(td: TdlClient): Long? {
        val me = td.getMe().valueOrNull ?: return null
        return td.createPrivateChat(me.id, false).valueOrNull?.id
    }

    /** Loads [list] into TDLib's cache (it answers 404 once everything is loaded), then reads it. */
    private suspend fun chatIds(td: TdlClient, list: ChatList, limit: Int): LongArray {
        if (limit <= 0) return LongArray(0)
        var loaded = 0
        while (loaded < limit) {
            val result = withTimeoutOrNull(LOAD_TIMEOUT_MS) { td.loadChats(list, LOAD_PAGE) } ?: break
            if (result is TdlResult.Failure) break
            loaded += LOAD_PAGE
        }
        return when (val result = td.getChats(list, limit)) {
            is TdlResult.Success -> result.result.chatIds
            is TdlResult.Failure -> {
                Logger.w(TAG, "getChats failed: ${result.message}")
                LongArray(0)
            }
        }
    }

    private suspend fun entry(td: TdlClient, chatId: Long, archived: Boolean, savedId: Long?): ChatEntry? {
        val chat = td.getChat(chatId).valueOrNull ?: return null
        // A secret chat's files cannot be streamed into a library; it is a private chat's copy.
        if (chat.type is ChatTypeSecret) return null
        return ChatEntry(
            chatId = chatId,
            title = chat.title.orEmpty().trim(),
            savedMessages = chatId == savedId,
            archived = archived,
            photoFileId = chat.photo?.small?.id ?: 0,
        )
    }

    private companion object {
        const val TAG = "ChatDirectory"
        const val MAX_CHATS = 4000
        const val LOAD_PAGE = 200
        const val LOAD_TIMEOUT_MS = 15_000L
        const val COUNT_PARALLEL = 3
        const val COUNT_TRIES = 3
        const val COUNT_TIMEOUT_MS = 15_000L
        const val MAX_WAIT_S = 60
    }
}
