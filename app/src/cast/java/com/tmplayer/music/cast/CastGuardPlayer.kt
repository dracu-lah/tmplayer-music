package com.tmplayer.music.cast

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.tmplayer.music.i18n.L

/**
 * The player the media session has: Media3's [CastPlayer] (this phone's player or the Cast device's,
 * whichever is active) with the cast rules put in front of the queue changes the user makes while
 * casting:
 *
 *  - Tracks the receiver cannot play are left out with a sentence, never silently.
 *  - A new queue is sent as a window (the receiver takes a bounded queue); the rest waits in the
 *    [CastRecord] and is added as playback gets near the end of what was sent.
 *  - Clearing the queue clears what would be folded back at the end.
 *
 * With no cast on, every call goes straight through.
 */
@UnstableApi
internal class CastGuardPlayer(
    private val context: Context,
    private val cast: CastPlayer,
    private val controller: CastController,
) : ForwardingPlayer(cast) {

    private val keepUp = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = topUp()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = topUp()
        override fun onEvents(player: Player, events: Player.Events) {
            controller.capture(cast)
            // Awake while the user wants it playing, asleep when paused or ended.
            if (controller.record != null) {
                controller.server.hold(cast.isCastSessionAvailable && cast.playWhenReady && cast.playbackState != Player.STATE_ENDED && cast.playbackState != Player.STATE_IDLE)
            }
        }
    }

    init {
        cast.addListener(keepUp)
    }

    private fun casting(): Boolean = cast.isCastSessionAvailable && controller.record != null

    /** Adds the next few waiting tracks once the receiver is near the end of its queue. */
    private fun topUp() {
        val kept = controller.record ?: return
        if (!cast.isCastSessionAvailable || kept.upcoming.isEmpty()) return
        val left = cast.mediaItemCount - cast.currentMediaItemIndex - 1
        if (left > CastQueue.LOW_WATER) return
        val chunk = ArrayList<MediaItem>()
        repeat(CastQueue.CHUNK) { kept.upcoming.removeFirstOrNull()?.let(chunk::add) }
        if (chunk.isEmpty()) return
        kept.sent += chunk.map { it.mediaId }
        cast.addMediaItems(chunk)
    }

    private class Staged(val items: List<MediaItem>, val start: Int, val sameStart: Boolean)

    /** A whole new queue while casting: a window of it goes to the receiver. Null when none of it can play. */
    private fun stage(items: List<MediaItem>, start: Int): Staged? {
        if (items.isEmpty()) return Staged(emptyList(), 0, true)
        val at = if (start == C.INDEX_UNSET) 0 else start.coerceIn(0, items.lastIndex)
        val plan = CastQueue.plan(items.map(::entryOf), at)
        CastQueue.summary(plan.skipped)?.let { CastNotices.post(context, it) }
        if (plan.window.isEmpty()) {
            CastNotices.post(context, L.castNonePlayable)
            return null
        }
        val window = plan.window.map { items[it.sourceIndex] }
        val old = controller.record
        val upcoming = ArrayDeque<MediaItem>().apply { plan.upcoming.forEach { addLast(items[it.sourceIndex]) } }
        controller.record = CastRecord(items, window.map { it.mediaId }.toMutableSet(), upcoming, old?.shuffle ?: false, old?.params ?: androidx.media3.common.PlaybackParameters.DEFAULT)
        return Staged(window, plan.startIndex, plan.window[plan.startIndex].sourceIndex == at)
    }

    /** What may be added to a running cast: the tracks the receiver plays; the others get their sentence. */
    private fun admit(items: List<MediaItem>): List<MediaItem> {
        val skipped = ArrayList<CastSkip>()
        val kept = ArrayList<MediaItem>()
        items.forEachIndexed { index, item ->
            val entry = entryOf(item)
            when (val verdict = CastQueue.verdict(entry)) {
                is CastVerdict.Playable -> kept += item
                is CastVerdict.Refused -> skipped += CastSkip(index, entry, verdict.reason)
            }
        }
        CastQueue.summary(skipped)?.let { CastNotices.post(context, it) }
        controller.record?.sent?.addAll(kept.map { it.mediaId })
        return kept
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (!casting()) return super.setMediaItems(mediaItems, startIndex, startPositionMs)
        val staged = stage(mediaItems, startIndex) ?: return
        super.setMediaItems(staged.items, staged.start, if (staged.sameStart) startPositionMs else 0L)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        if (!casting()) return super.setMediaItems(mediaItems, resetPosition)
        if (resetPosition) setMediaItems(mediaItems, 0, 0L) else setMediaItems(mediaItems, currentMediaItemIndex, currentPosition)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        if (!casting()) return super.setMediaItems(mediaItems)
        setMediaItems(mediaItems, true)
    }

    override fun setMediaItem(mediaItem: MediaItem) = setMediaItems(listOf(mediaItem))

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = setMediaItems(listOf(mediaItem), 0, startPositionMs)

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = setMediaItems(listOf(mediaItem), resetPosition)

    override fun addMediaItem(mediaItem: MediaItem) = addMediaItems(listOf(mediaItem))

    override fun addMediaItem(index: Int, mediaItem: MediaItem) = addMediaItems(index, listOf(mediaItem))

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        if (!casting()) return super.addMediaItems(mediaItems)
        val ok = admit(mediaItems)
        if (ok.isNotEmpty()) super.addMediaItems(ok)
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        if (!casting()) return super.addMediaItems(index, mediaItems)
        val ok = admit(mediaItems)
        if (ok.isNotEmpty()) super.addMediaItems(index, ok)
    }

    override fun clearMediaItems() {
        if (casting()) {
            val old = controller.record
            // Nothing left to fold back: what the user cleared stays cleared.
            controller.record = CastRecord(emptyList(), HashSet(), ArrayDeque(), old?.shuffle ?: false, old?.params ?: androidx.media3.common.PlaybackParameters.DEFAULT)
        }
        super.clearMediaItems()
    }

    override fun release() {
        cast.removeListener(keepUp)
        super.release()
    }
}
