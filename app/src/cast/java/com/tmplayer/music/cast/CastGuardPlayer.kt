package com.tmplayer.music.cast

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.player.AudioEffectsSetting
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The player the media session has: Media3's [CastPlayer] (this phone's player or the Cast device's,
 * whichever is active) with the cast rules put in front of the queue changes the user makes while
 * casting:
 *
 *  - Tracks the receiver cannot play are left out with a sentence, never silently.
 *  - A new queue is sent as a window (the receiver takes a bounded queue); the rest waits in the
 *    [CastRecord] and is added as playback gets near the end of what was sent.
 *  - Clearing the queue clears what would be folded back at the end.
 *  - Shuffle and repeat work: the receiver has no shuffle, so turning it on or off re-orders the queue the
 *    phone keeps and sends the receiver a new window of it ([CastReorder]); repeat is the receiver's own.
 *  - The speed the user chose is passed on to the receiver (it takes 0.5x to 2x, no pitch).
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
            refreshCommands()
            syncSpeed()
            // Awake while the user wants it playing, asleep when paused or ended.
            if (controller.record != null) {
                controller.server.hold(cast.isCastSessionAvailable && cast.playWhenReady && cast.playbackState != Player.STATE_ENDED && cast.playbackState != Player.STATE_IDLE)
            }
        }
    }

    /** The listeners of whoever uses this player, so the shuffle the guard keeps itself (the receiver has none) can be announced. */
    private val outside = CopyOnWriteArraySet<Player.Listener>()
    private var announcedCommands: Player.Commands? = null

    /** The speed last sent to the receiver in this cast; null when none is. */
    private var remoteSpeed: Float? = null

    init {
        cast.addListener(keepUp)
    }

    override fun addListener(listener: Player.Listener) {
        outside += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        outside -= listener
        super.removeListener(listener)
    }

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        if (!casting()) return base
        return base.buildUpon().addAll(Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE).build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    /** Tells the listeners when casting starts or stops giving the shuffle and repeat commands. */
    private fun refreshCommands() {
        val now = availableCommands
        if (now == announcedCommands) return
        announcedCommands = now
        outside.forEach { it.onAvailableCommandsChanged(now) }
    }

    /** Passes the speed the user chose to the receiver, once per change (a receiver that ignores it is not asked again and again). */
    fun syncSpeed() {
        if (!casting()) {
            remoteSpeed = null
            return
        }
        val want = AudioEffectsSetting.choices.speed.value
        if (remoteSpeed == want) return
        remoteSpeed = want
        runCatching { cast.playbackParameters = PlaybackParameters(want) }.onFailure { Logger.w(TAG, "the speed could not be sent to the receiver", it) }
    }

    override fun getShuffleModeEnabled(): Boolean = if (casting()) controller.record?.shuffle ?: false else super.getShuffleModeEnabled()

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        val kept = controller.record
        if (!casting() || kept == null) return super.setShuffleModeEnabled(shuffleModeEnabled)
        if (kept.shuffle == shuffleModeEnabled) return
        kept.shuffle = shuffleModeEnabled
        runCatching { reorder(kept) }.onFailure { Logger.w(TAG, "the queue could not be re-ordered on the receiver", it) }
        outside.forEach { it.onShuffleModeEnabledChanged(shuffleModeEnabled) }
    }

    /**
     * Re-orders what is cast to match [CastRecord.shuffle]: the whole cast queue (what the receiver holds
     * plus what waits to be sent) in the order [CastReorder.order] gives, with the playing track kept,
     * as a new window sent in one go. The receiver reloads the playing track at its position (a short gap),
     * which is one step that either happens or does not, where a run of inserts and removes could be
     * half done.
     */
    private fun reorder(kept: CastRecord) {
        val count = cast.mediaItemCount
        val at = cast.currentMediaItemIndex
        if (count == 0 || at !in 0 until count) return
        val byId = LinkedHashMap<String, MediaItem>()
        for (i in 0 until count) cast.getMediaItemAt(i).let { byId.putIfAbsent(it.mediaId, it) }
        val remoteIds = (0 until count).map { cast.getMediaItemAt(it).mediaId }
        kept.upcoming.forEach { byId.putIfAbsent(it.mediaId, it) }
        val all = remoteIds + kept.upcoming.map { it.mediaId }
        val currentId = remoteIds[at]
        val order = CastReorder.order(all, currentId, kept.shuffle, kept.originalIds, kept.seed)
        val layout = order?.let { CastReorder.layout(it, currentId) }
        if (layout == null) {
            Logger.w(TAG, "shuffle changed but this queue has a repeated track: the order on the receiver stays")
            CastNotices.post(context, L.castShuffleKept)
            return
        }
        val window = layout.window.mapNotNull { byId[it] }
        if (window.size != layout.window.size) return
        val position = cast.currentPosition.coerceAtLeast(0)
        kept.upcoming.clear()
        layout.upcoming.mapNotNull { byId[it] }.forEach { kept.upcoming.addLast(it) }
        val sentNow = CastReorder.sentAfter(kept.sent, all, layout.window)
        kept.sent.clear()
        kept.sent.addAll(sentNow)
        super.setMediaItems(window, layout.startIndex, position)
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
        val old = controller.record
        val shuffle = old?.shuffle ?: false
        // With shuffle on, the queue goes to the receiver in the seeded order, the track to start first.
        val ids = items.map { it.mediaId }
        val shuffledIds = if (shuffle) CastReorder.order(ids, ids[at], true, ids, CastReorder.seedOf(ids)) else null
        val ordered = shuffledIds?.let { order -> val byId = items.associateBy { it.mediaId }; order.mapNotNull { byId[it] } }?.takeIf { it.size == items.size } ?: items
        val orderedAt = if (ordered === items) at else 0
        val plan = CastQueue.plan(ordered.map(::entryOf), orderedAt)
        CastQueue.summary(plan.skipped)?.let { CastNotices.post(context, it) }
        if (plan.window.isEmpty()) {
            CastNotices.post(context, L.castNonePlayable)
            return null
        }
        val window = plan.window.map { ordered[it.sourceIndex] }
        val upcoming = ArrayDeque<MediaItem>().apply { plan.upcoming.forEach { addLast(ordered[it.sourceIndex]) } }
        controller.record = CastRecord(items, window.map { it.mediaId }.toMutableSet(), upcoming, shuffle)
        return Staged(window, plan.startIndex, plan.window[plan.startIndex].sourceIndex == orderedAt)
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
            controller.record = CastRecord(emptyList(), HashSet(), ArrayDeque(), old?.shuffle ?: false)
        }
        super.clearMediaItems()
    }

    override fun release() {
        cast.removeListener(keepUp)
        outside.clear()
        super.release()
    }

    private companion object {
        const val TAG = "CastGuardPlayer"
    }
}
