package com.tmplayer.music.connect

import com.tmplayer.music.data.ChatLoader
import com.tmplayer.music.data.ChatProblem
import com.tmplayer.music.library.Track
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Send to TV for a whole chat: reads the chat's tracks page by page through a [ChatLoader] (the
 * index when the chat's scan is done, Telegram's pages otherwise) up to [SendPlan.MAX_TOTAL], and
 * keeps only the small [TrackRef]s. The chat is never loaded whole: the loader stops at the cap and
 * the page is dropped with it, so a chat of 20,000 tracks costs the same memory as one of 5,000.
 */
object ChatSend {
    /** What one send of a chat carries at most; the UI says so. */
    const val CAP = SendPlan.MAX_TOTAL

    /** A guard against a loader that makes no progress: more pages than this is never needed for [CAP] rows. */
    private const val MAX_CALLS = 4_000

    /**
     * [refs] in the chat's order (at most [cap]); [capped] when the chat has more than were taken;
     * [stopped] when a failure ended the reading early (the refs read so far are still good).
     */
    data class Collected(val refs: List<TrackRef>, val capped: Boolean, val stopped: ChatProblem?)

    /** Pages [loader] until [cap] rows, the end of the chat or a failure; [onProgress] gets the rows so far. Cancellable at every page. */
    suspend fun collect(loader: ChatLoader, cap: Int = CAP, onProgress: (Int) -> Unit = {}): Collected {
        loader.start()
        var calls = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val s = loader.state.value
            onProgress(minOf(s.rows.size, cap))
            val stopped = s.problem?.takeIf { it is ChatProblem.Failed || it is ChatProblem.NotAvailable || it is ChatProblem.Removed }
            when {
                s.rows.size >= cap -> {
                    // Exactly at the cap with the end not seen yet: one more page tells whether the chat goes on.
                    val last = if (s.rows.size == cap && !s.end) loader.run { more(); state.value } else s
                    return Collected(refs(last.rows, cap), capped = last.rows.size > cap || !last.end, stopped = null)
                }
                s.end -> return Collected(refs(s.rows, cap), capped = false, stopped = stopped)
                stopped != null -> return Collected(refs(s.rows, cap), capped = false, stopped = stopped)
                ++calls > MAX_CALLS -> return Collected(refs(s.rows, cap), capped = false, stopped = null)
            }
            loader.more()
        }
    }

    private fun refs(rows: List<Track>, cap: Int): List<TrackRef> =
        rows.asSequence().take(cap).map { TrackRef(it.chatId, it.messageId, it.groupKey, it.title) }.toList()
}
