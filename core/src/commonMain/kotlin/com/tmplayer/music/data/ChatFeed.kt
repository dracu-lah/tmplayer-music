package com.tmplayer.music.data

import com.tmplayer.music.library.Track
import kotlinx.coroutines.delay

/** The order a chat page lists its tracks in; remembered per chat. */
enum class ChatOrder { OldestFirst, NewestFirst }

/** What one step of a [ChatFeed] brought. */
sealed interface FeedStep {
    /** New tracks (not seen before in this feed), in no particular order; may be empty. */
    data class Added(val tracks: List<Track>) : FeedStep

    /** Both searches reached the end of the chat. */
    data object End : FeedStep

    /** Telegram would not answer; [notAvailable] when the chat itself is gone or closed to the user. */
    data class Failed(val message: String, val notAvailable: Boolean) : FeedStep
}

/**
 * A chat's audio straight from Telegram, page by page, in message order.
 * Two searches run side by side, as the scanner's do: audio messages, and documents
 * (where FLAC and other lossless files usually are). Each keeps its own keyset cursor, and each
 * [next] asks only the search that is furthest behind, so the rows gathered so far are always in
 * order and never the whole chat at once.
 *
 * Newest first pages from the newest message down ([PageResult.Page.nextFromMessageId] is the next
 * cursor). Oldest first starts at the oldest message and pages up with a negative offset, the newest
 * id of a page being the next cursor; a page with nothing newer is the end only when the search's
 * newest message is not further on (else it is asked again, [MAX_EMPTY] times at most). A message
 * seen twice is kept once. A flood wait is waited out by the call that got it (up to [MAX_WAIT_S],
 * [onWait] tells the screen), and then the same page is asked again. A failed page leaves the cursor
 * where it was, so Try again asks the same page.
 *
 * [fetch] is TDLib's `searchChatMessages` behind a seam: (pass, fromMessageId, offset, limit).
 */
class ChatFeed(
    val order: ChatOrder,
    private val fetch: suspend (pass: ScanPass, fromMessageId: Long, offset: Int, limit: Int) -> PageResult,
    private val onWait: (seconds: Int) -> Unit = {},
    private val wait: suspend (seconds: Int) -> Unit = { delay(it * 1000L) },
) {
    private class Stream(val pass: ScanPass) {
        var cursor = 0L
        var started = false
        var ended = false

        /** Pages in a row that brought nothing while the search's newest message is further on. */
        var empty = 0

        /** The message id this search has reached: the lowest seen newest first, the highest oldest first. */
        var frontier = 0L
    }

    private val streams = ScanPass.entries.map(::Stream)
    private val seen = HashSet<Long>()

    val ended: Boolean get() = streams.all { it.ended }

    /**
     * The message id up to which the gathered rows are complete: every track on the right side of it
     * that both searches can find has been found. Null before either search answered.
     */
    val complete: Long?
        get() {
            val open = streams.filter { !it.ended }
            if (open.any { !it.started }) return null
            if (open.isEmpty()) return if (order == ChatOrder.NewestFirst) Long.MIN_VALUE else Long.MAX_VALUE
            return if (order == ChatOrder.NewestFirst) open.maxOf { it.frontier } else open.minOf { it.frontier }
        }

    /** One page of the search furthest behind. */
    suspend fun next(): FeedStep {
        val stream = behind() ?: return FeedStep.End
        val (from, offset) = when {
            order == ChatOrder.NewestFirst -> (if (stream.started) stream.cursor else 0L) to 0
            // From the oldest message up: a negative offset asks for the page after from.
            else -> (if (stream.started) stream.cursor else 1L) to -(PAGE - 1)
        }
        val page = when (val result = ask(stream.pass, from, offset, PAGE)) {
            is PageResult.Page -> result
            is PageResult.Failed -> return failed(result)
            is PageResult.FloodWait -> return failed(PageResult.Failed("FLOOD_WAIT_${result.seconds}"))
        }
        if (order == ChatOrder.OldestFirst && page.messageIds.none { it > from }) {
            // Nothing newer than the cursor: the end of the chat, unless the newest message of this
            // search says otherwise (Telegram can answer a page with nothing). Then the same page is
            // asked again on the next call, and after a few such answers the page says it failed.
            val probe = when (val result = ask(stream.pass, 0L, 0, 1)) {
                is PageResult.Page -> result
                is PageResult.Failed -> return failed(result)
                is PageResult.FloodWait -> return failed(PageResult.Failed("FLOOD_WAIT_${result.seconds}"))
            }
            val newest = probe.messageIds.maxOrNull()
            if (newest != null && newest > from) {
                if (++stream.empty >= MAX_EMPTY) {
                    stream.empty = 0
                    return FeedStep.Failed(Failures.DEFAULT, notAvailable = false)
                }
                return FeedStep.Added(emptyList())
            }
        }
        stream.empty = 0
        return take(stream, from, page)
    }

    /** One fetch, a flood wait waited out (up to [MAX_WAITS] times, each up to [MAX_WAIT_S]). */
    private suspend fun ask(pass: ScanPass, from: Long, offset: Int, limit: Int): PageResult {
        var waits = 0
        while (true) {
            val result = fetch(pass, from, offset, limit)
            if (result !is PageResult.FloodWait) return result
            if (++waits > MAX_WAITS || result.seconds > MAX_WAIT_S) return result
            onWait(result.seconds)
            try {
                wait(result.seconds)
            } finally {
                // Also when the page is left mid wait, so the screen does not keep saying it waits.
                onWait(0)
            }
        }
    }

    private fun failed(result: PageResult.Failed): FeedStep.Failed =
        FeedStep.Failed(Failures.humanise(result.message), isGone(result.message))

    private fun behind(): Stream? {
        val open = streams.filter { !it.ended }
        open.firstOrNull { !it.started }?.let { return it }
        return if (order == ChatOrder.NewestFirst) open.maxByOrNull { it.frontier } else open.minByOrNull { it.frontier }
    }

    private fun take(stream: Stream, from: Long, page: PageResult.Page): FeedStep {
        val ids = page.messageIds
        val first = !stream.started
        stream.started = true
        if (order == ChatOrder.NewestFirst) {
            val next = page.nextFromMessageId
            // Telegram can answer a page with nothing but a cursor further down: go on from there.
            val moved = next != 0L && (from == 0L || next < from)
            val low = ids.minOrNull() ?: next.takeIf { moved }
            low?.let { stream.frontier = if (first) it else minOf(stream.frontier, it) }
            stream.cursor = next
            if (next == 0L || (ids.isEmpty() && !moved)) stream.ended = true
        } else {
            val newer = ids.filter { it > from }
            val high = (newer.maxOrNull() ?: from)
            stream.frontier = if (first) high else maxOf(stream.frontier, high)
            stream.cursor = high
            // A page with nothing newer than where it started is the end of the chat.
            if (newer.isEmpty()) stream.ended = true
        }
        if (stream.ended) {
            stream.frontier = if (order == ChatOrder.NewestFirst) Long.MIN_VALUE else Long.MAX_VALUE
        }
        val fresh = page.items.mapNotNull { (it as? Mapped.Audio)?.track }.filter { seen.add(it.messageId) }
        if (seen.size > MAX_SEEN) seen.clear()
        return FeedStep.Added(fresh)
    }

    companion object {
        const val PAGE = 100
        const val MAX_WAITS = 5
        const val MAX_WAIT_S = 600

        /** Empty answers in a row, short of the chat's end, before a page says it failed. */
        const val MAX_EMPTY = 3

        /** Bounds the ids remembered for removing repeats; past it a repeat can come through once. */
        private const val MAX_SEEN = 100_000

        private val GONE = listOf(
            "CHAT_NOT_FOUND", "Chat not found", "CHANNEL_PRIVATE", "CHANNEL_INVALID", "PEER_ID_INVALID",
            "USER_BANNED_IN_CHANNEL", "Have no access", "Can't access the chat", "CHAT_FORBIDDEN",
        )

        /** True when Telegram's answer means the chat is gone or closed to the user, not a passing failure. */
        fun isGone(message: String?): Boolean {
            val raw = message.orEmpty()
            return GONE.any { raw.contains(it, ignoreCase = true) }
        }
    }
}
