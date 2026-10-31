package com.tmplayer.music.data

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.SearchMessagesFilterAudio
import dev.g000sha256.tdl.dto.SearchMessagesFilterDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** How much audio a chat holds, as the picker shows it. */
sealed interface ChatCount {
    /** Telegram would not say (a failure, a long flood wait, no answer in time): never shown as 0. */
    data object Unknown : ChatCount

    /**
     * [audio] audio messages, and [documents] audio files sent as documents among the newest documents
     * looked at. [more] when there may be more than [total]: documents past those looked at hold some.
     */
    data class Known(val audio: Int, val documents: Int, val more: Boolean) : ChatCount {
        val total: Int get() = audio + documents
    }
}

/** One answer from Telegram for [AudioCounter]. */
sealed interface CountAnswer<out T> {
    data class Value<T>(val value: T) : CountAnswer<T>
    data class FloodWait(val seconds: Int) : CountAnswer<Nothing>
    data class Failed(val message: String) : CountAnswer<Nothing>
}

/**
 * The first page of one of a chat's searches: [total] is Telegram's figure for the whole search (-1
 * when it gave none), [messages] how many messages came back, [audio] how many of them the library
 * keeps as tracks, [next] the cursor to the next page (0 at the end).
 */
data class SearchProbe(val total: Int, val messages: Int, val audio: Int, val next: Long)

/** The TDLib calls a chat is counted with, behind a seam for the tests. */
interface CountCalls {
    /** `getChatMessageCount` with the audio filter, asked of the server; -1 when TDLib does not know. */
    suspend fun audioMessages(chatId: Long): CountAnswer<Int>

    /** The newest [limit] messages of the chat's audio or document search. */
    suspend fun probe(chatId: Long, pass: ScanPass, limit: Int): CountAnswer<SearchProbe>
}

/**
 * Counts a chat's audio for its picker row, lazily (only rows on screen ask), a few chats at a time
 * ([PARALLEL]), and remembers what it found for the session:
 *
 * - Telegram's count of audio messages is taken when it is above 0.
 * - A 0, a -1 or no answer is not taken as "no audio": TDLib answers from its own cached figure when
 *   it has one, without asking the server, and for a supergroup or channel that figure can be stale
 *   or never filled. The audio search itself is asked then (one message): any message found means
 *   audio, with the search's own total.
 * - Audio sent as documents (most FLAC) is counted from the newest [DOC_PROBE] documents; when there
 *   are older documents beyond them the count says "at least" ([ChatCount.Known.more]).
 * - Each call waits out a flood wait of up to [MAX_WAIT_S] (the other rows go on), at most [TRIES]
 *   times, and has [TIMEOUT_MS]; past that its part is unknown. A count with nothing known is
 *   [ChatCount.Unknown], never 0, and is not remembered, so the row asks again when it shows again.
 */
class AudioCounter(
    private val calls: suspend () -> CountCalls,
    private val generation: () -> Long? = { 0L },
    private val wait: suspend (seconds: Int) -> Unit = { delay(it * 1000L) },
) {
    private val known = ConcurrentHashMap<Long, ChatCount.Known>()
    @Volatile
    private var knownFor: Long? = null
    private val gate = Semaphore(PARALLEL)

    /** The count already found for [chatId] in this session, without asking Telegram. */
    fun cached(chatId: Long): ChatCount.Known? = if (sameSession()) known[chatId] else null

    suspend fun count(chatId: Long): ChatCount {
        cached(chatId)?.let { return it }
        return gate.withPermit {
            cached(chatId)?.let { return@withPermit it }
            val session = generation()
            val c = calls()
            val told = ask { c.audioMessages(chatId) }?.takeIf { it >= 0 }
            val audio: Int? = if (told != null && told > 0) {
                told
            } else {
                ask { c.probe(chatId, ScanPass.Audio, 1) }?.let { found ->
                    when {
                        found.messages > 0 -> maxOf(found.total, found.messages, told ?: 0)
                        // An empty page with a total above 0 or a cursor on: audio TDLib did not hand over.
                        found.total > 0 -> found.total
                        found.next != 0L -> null
                        else -> 0
                    }
                }
            }
            val docs = ask { c.probe(chatId, ScanPass.Documents, DOC_PROBE) }
            val result = when {
                audio != null && docs != null -> ChatCount.Known(audio, docs.audio, more = docs.audio > 0 && docs.next != 0L)
                // Only one half known: a number when it found audio, else not known (never 0).
                audio != null -> if (audio > 0) ChatCount.Known(audio, 0, more = false) else ChatCount.Unknown
                docs != null -> if (docs.audio > 0) ChatCount.Known(0, docs.audio, more = true) else ChatCount.Unknown
                else -> ChatCount.Unknown
            }
            if (result is ChatCount.Known && session == generation()) known[chatId] = result
            result
        }
    }

    /** One call: a flood wait waited out by this call alone, a failure or a timeout is null. */
    private suspend fun <T> ask(call: suspend () -> CountAnswer<T>): T? {
        repeat(TRIES) {
            val answer = try {
                withTimeoutOrNull(TIMEOUT_MS) { call() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return null
            when (answer) {
                is CountAnswer.Value -> return answer.value
                is CountAnswer.Failed -> return null
                is CountAnswer.FloodWait -> {
                    if (answer.seconds > MAX_WAIT_S) return null
                    wait(answer.seconds)
                }
            }
        }
        return null
    }

    private fun sameSession(): Boolean {
        val now = generation()
        if (now != knownFor) {
            known.clear()
            knownFor = now
            return false
        }
        return true
    }

    companion object {
        const val PARALLEL = 3
        const val TRIES = 3
        const val TIMEOUT_MS = 15_000L
        const val MAX_WAIT_S = 60

        /** Documents looked at for audio files: one page, the newest. */
        const val DOC_PROBE = 50
    }
}

/** [CountCalls] over a TDLib client. */
class TdCountCalls(private val td: TdlClient) : CountCalls {
    private fun <T> failure(message: String): CountAnswer<T> =
        Failures.floodWaitSeconds(message)?.let { CountAnswer.FloodWait(it) } ?: CountAnswer.Failed(message)

    override suspend fun audioMessages(chatId: Long): CountAnswer<Int> =
        when (val result = td.getChatMessageCount(chatId, null, SearchMessagesFilterAudio(), false)) {
            is TdlResult.Success -> CountAnswer.Value(result.result.count)
            is TdlResult.Failure -> failure(result.message)
        }

    override suspend fun probe(chatId: Long, pass: ScanPass, limit: Int): CountAnswer<SearchProbe> {
        val filter = if (pass == ScanPass.Audio) SearchMessagesFilterAudio() else SearchMessagesFilterDocument()
        return when (val result = td.searchChatMessages(chatId, null, "", null, 0, 0, limit, filter)) {
            is TdlResult.Success -> {
                val messages = result.result.messages.orEmpty()
                val audio = messages.count { runCatching { TrackMapper.fromMessage(it) }.getOrNull() is Mapped.Audio }
                CountAnswer.Value(SearchProbe(result.result.totalCount, messages.size, audio, result.result.nextFromMessageId))
            }
            is TdlResult.Failure -> failure(result.message)
        }
    }
}
