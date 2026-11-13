package com.tmplayer.music.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.DefaultCastPlayerTransferCallback
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.PlayerTransferState
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.player.AudioEffectsSetting
import com.tmplayer.music.player.PlaybackSpeed

/** Why a cast ended, for the sentence the user is told. */
internal enum class EndReason { Normal, Wifi, Sleep }

/**
 * The queue as it was when the cast began (or was last set while casting), kept on the phone. The
 * receiver is sent only a window of it ([CastQueue.WINDOW] items), so this holds the whole queue
 * ([original], in queue order), which ids went over ([sent]) and the items still waiting to be sent
 * ([upcoming], in play order). When the cast ends it is folded back with what the receiver's queue
 * became ([CastQueue.mergeBack]).
 */
internal class CastRecord(
    val original: List<MediaItem>,
    val sent: MutableSet<String>,
    val upcoming: ArrayDeque<MediaItem>,
    /** Shuffle as the user has it. The receiver has none, so the guard re-orders its queue ([CastReorder]) when this changes. */
    @Volatile var shuffle: Boolean,
) {
    val originalIds: List<String> = original.map { it.mediaId }

    /** Fixed by the queue, so the same queue shuffles the same way every time. */
    val seed: Long = CastReorder.seedOf(originalIds)
}

/**
 * What happens to the queue when the session starts and ends. Media3's CastPlayer calls
 * [transferState] with the player that was active and the one that becomes active: here, to the
 * receiver (a window of the queue, the unplayable tracks left out with a sentence, the audio server
 * started on the Wi-Fi address) and back (the queue merged, paused at the same place). Everything
 * runs on the main thread.
 */
@UnstableApi
internal class CastController(
    private val context: Context,
    sources: DataSource.Factory,
) : CastPlayer.TransferCallback {

    val server = CastAudioServer(context, sources)
    val converter = CastItemConverter { server }

    @Volatile
    var record: CastRecord? = null

    @Volatile
    var endReason: EndReason = EndReason.Normal

    /** The playing item and where it was, as last seen while casting: used if the receiver's own state is gone at the end. */
    @Volatile
    private var lastKnown: Pair<String, Long>? = null

    private var stopWatching: (() -> Unit)? = null

    /** How the receiver is doing with what it was sent: reaches this phone or not, and the tracks it could not play. A new one for each cast. */
    @Volatile
    var health = CastHealth()
        private set

    /** The device's name, for the sentences about it. */
    @Volatile
    private var deviceName = ""

    /** The cast could not start and the user has been told why: ending it adds no second sentence. */
    private var quiet = false
    private val main = Handler(Looper.getMainLooper())

    /** Ends the Cast session (the phone's own controls stay); the transfer back to this phone follows. */
    fun endSessionSoon(reason: EndReason = EndReason.Normal) {
        if (reason != EndReason.Normal) endReason = reason
        main.post { runCatching { androidx.media3.cast.Cast.getSingletonInstance(context).endCurrentSession(true) } }
    }

    /**
     * Looks whether the receiver reaches this phone, while [wantsPlay] (the user wants music). True to go on
     * looking; false when it is settled: reached, or not reachable (the user is told and the cast ends) or no cast.
     */
    fun checkReachable(wantsPlay: Boolean): Boolean {
        if (record == null) return true
        if (health.isReached) return false
        if (health.reachable(SystemClock.elapsedRealtime(), server.requests, wantsPlay)) return !health.isReached
        unreachable()
        return false
    }

    /** The receiver asked for nothing: it cannot open this phone's address. Say so plainly, with the way out, and end the cast so nothing spins. */
    private fun unreachable() {
        if (quiet) return
        Logger.w(TAG, "the receiver never reached the cast server")
        CastNotices.problem(context, L.castUnreachable(deviceName.ifBlank { L.castTitle }))
        quiet = true
        endSessionSoon()
    }

    /** What the host does about a track the receiver could not play. */
    enum class Skip { Next, Stop, None }

    /** The receiver could not play [id]. Skip it, or stop when it is the receiver itself that cannot be reached or too many fail in a row. */
    fun receiverFailed(id: String, title: String): Skip {
        if (record == null) return Skip.None
        return when (health.failed(id, title, server.requests)) {
            CastFailAction.Ignore -> Skip.None
            CastFailAction.Skip -> Skip.Next
            CastFailAction.Unreachable -> {
                unreachable()
                Skip.Stop
            }
            CastFailAction.GiveUp -> {
                Logger.w(TAG, "several tracks in a row failed on the receiver")
                CastNotices.problem(context, listOfNotNull(L.castFailedStop, health.summary()).joinToString(" "))
                quiet = true
                endSessionSoon()
                Skip.Stop
            }
        }
    }

    /** The receiver is loading or playing something. */
    fun receiverActive(playing: Boolean) = health.active(playing)

    /** Notes where the cast player is, while it can still be asked. */
    fun capture(player: Player) {
        if (record == null) return
        val id = player.currentMediaItem?.mediaId ?: return
        lastKnown = id to player.currentPosition.coerceAtLeast(0)
    }

    override fun transferState(source: Player, destination: Player) {
        if (destination is RemoteCastPlayer) {
            try {
                toRemote(source, destination)
            } catch (e: Exception) {
                Logger.w(TAG, "the queue could not be moved to the Cast device", e)
                CastNotices.post(context, L.castStopped)
                endSessionSoon()
            }
        } else {
            try {
                toLocal(source, destination)
            } catch (e: Exception) {
                Logger.w(TAG, "the queue could not be moved back to this phone", e)
                record?.let { runCatching { restoreOriginal(it, destination) } }
                finish()
            }
        }
    }

    private fun toRemote(source: Player, destination: Player) {
        endReason = EndReason.Normal
        CastNotices.clear()
        health = CastHealth()
        deviceName = runCatching { androidx.media3.cast.Cast.getSingletonInstance(context).currentCastSession?.castDevice?.friendlyName }.getOrNull().orEmpty()
        val state = PlayerTransferState.fromPlayer(source)
        val items = state.mediaItems
        // The whole local queue is kept first, so whatever happens next the phone can get it back.
        val kept = CastRecord(items, HashSet(), ArrayDeque(), state.shuffleModeEnabled)
        record = kept
        lastKnown = null
        val receiver = runCatching { androidx.media3.cast.Cast.getSingletonInstance(context).currentCastSession?.castDevice?.inetAddress }.getOrNull()
        if (!server.start(receiver)) {
            CastNotices.post(context, L.castNoWifi)
            quiet = true
            endSessionSoon()
            return
        }
        server.address?.let { address -> stopWatching = WifiNetworks.watchLoss(context, address) { main.post { endSessionSoon(EndReason.Wifi) } } }
        if (items.isEmpty()) return
        val order = playOrder(source, items.size)
        val ordered = order.map { items[it] }
        val currentAt = order.indexOf(state.currentMediaItemIndex).coerceAtLeast(0)
        val plan = CastQueue.plan(ordered.map(::entryOf), currentAt)
        val notice = CastQueue.summary(plan.skipped)
        if (plan.window.isEmpty()) {
            CastNotices.post(context, L.castNonePlayable)
            return
        }
        if (notice != null) CastNotices.post(context, notice)
        val window = plan.window.map { ordered[it.sourceIndex] }
        kept.sent += window.map { it.mediaId }
        plan.upcoming.forEach { kept.upcoming.addLast(ordered[it.sourceIndex]) }
        val sameTrack = plan.window[plan.startIndex].sourceIndex == currentAt
        // The user's choice to cast moves the music: it goes on playing there if it was playing here, and stays paused if it was paused.
        PlayerTransferState.Builder()
            .setMediaItems(window)
            .setCurrentMediaItemIndex(plan.startIndex)
            .setCurrentPosition(if (sameTrack) state.currentPosition.coerceAtLeast(0) else 0)
            .setPlayWhenReady(state.playWhenReady)
            .setRepeatMode(state.repeatMode)
            .setShuffleModeEnabled(false)
            .build()
            .setToPlayer(destination)
    }

    /** The items in the order they play: the queue's, or its shuffle order when shuffle is on (the receiver has no shuffle). */
    private fun playOrder(source: Player, count: Int): List<Int> {
        val plain = (0 until count).toList()
        if (!source.shuffleModeEnabled || count <= 1) return plain
        val timeline = source.currentTimeline
        if (timeline.windowCount != count) return plain
        val order = ArrayList<Int>(count)
        var i = timeline.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET && order.size < count) {
            order += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return if (order.size == count) order else plain
    }

    private fun toLocal(source: Player, destination: Player) {
        val kept = record ?: run {
            // Not a cast this controller started: the library's own transfer.
            DefaultCastPlayerTransferCallback().transferState(source, destination)
            return
        }
        val remote = (0 until source.mediaItemCount).map { source.getMediaItemAt(it) }
        val known = lastKnown
        val liveId = source.currentMediaItem?.mediaId
        val currentId = liveId ?: known?.first
        val positionMs = (if (liveId != null) source.currentPosition else known?.second ?: 0L).coerceAtLeast(0)
        val byId = LinkedHashMap<String, MediaItem>()
        kept.original.forEach { byId.putIfAbsent(it.mediaId, it) }
        remote.forEach { byId.putIfAbsent(it.mediaId, it) }
        // With nothing left of the receiver's queue (it vanished with the connection), the queue the phone had comes back whole.
        val merged = if (remote.isEmpty() && kept.sent.isNotEmpty()) kept.originalIds
        else CastQueue.mergeBack(kept.originalIds, kept.sent, remote.map { it.mediaId })
        // With shuffle on, the receiver's queue is in its shuffled order: back here the queue is the one the user built and this phone shuffles it itself.
        val ordered = if (kept.shuffle && merged.toSet().size == merged.size) CastReorder.inQueueOrder(merged, kept.originalIds) else merged
        val items = ordered.mapNotNull { byId[it] }
        if (items.isEmpty()) {
            finish()
            return
        }
        val at = items.indexOfFirst { it.mediaId == currentId }
        // Always paused: coming back never starts the phone's speaker by itself.
        PlayerTransferState.Builder()
            .setMediaItems(items)
            .setCurrentMediaItemIndex(at.coerceAtLeast(0))
            .setCurrentPosition(if (at >= 0) positionMs else 0)
            .setPlayWhenReady(false)
            .setRepeatMode(if (source.mediaItemCount > 0) source.repeatMode else Player.REPEAT_MODE_OFF)
            .setShuffleModeEnabled(kept.shuffle)
            .setPlaybackParameters(localParameters())
            .build()
            .setToPlayer(destination)
        finish()
    }

    /** The speed the user has now (it may have changed while casting), as the local player takes it. */
    private fun localParameters(): PlaybackParameters {
        val choices = AudioEffectsSetting.choices
        val speed = choices.speed.value
        return PlaybackParameters(speed, PlaybackSpeed.pitch(speed, choices.keepPitch.value))
    }

    private fun restoreOriginal(kept: CastRecord, destination: Player) {
        if (kept.original.isEmpty()) return
        PlayerTransferState.Builder().setMediaItems(kept.original).setPlayWhenReady(false).setShuffleModeEnabled(kept.shuffle).build().setToPlayer(destination)
    }

    /** The cast is over: the server and the Wi-Fi watch stop, and the user is told once. */
    private fun finish() {
        val reason = endReason
        endReason = EndReason.Normal
        record = null
        lastKnown = null
        stopWatching?.invoke()
        stopWatching = null
        server.stop()
        // The tracks the receiver could not play are told once, at the end, with why the cast stopped.
        if (!quiet) {
            val stopped = when (reason) { EndReason.Wifi -> L.castStoppedWifi; EndReason.Sleep -> L.castStoppedSleep; else -> L.castStopped }
            CastNotices.post(context, listOfNotNull(stopped, health.summary()).joinToString(" "))
        }
        quiet = false
    }

    /** Ends everything without a transfer (the player is being released). */
    fun release() {
        stopWatching?.invoke()
        stopWatching = null
        server.stop()
        record = null
    }

    private companion object {
        const val TAG = "CastController"
    }
}
