package com.tmplayer.music.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.Library
import com.tmplayer.music.diagnostics.rememberSendLogs
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Track
import com.tmplayer.music.lyrics.Lyrics
import com.tmplayer.music.lyrics.LyricsOnline
import com.tmplayer.music.lyrics.LyricsQuery
import com.tmplayer.music.lyrics.OnlineLyrics
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.player.MediaIds
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.player.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What the file and its chat gave: still reading, lyrics, or none (with the track, for the online lookup). */
private sealed interface Local {
    data object Reading : Local

    data class Found(val lyrics: Lyrics) : Local

    data class None(val track: Track?) : Local
}

/**
 * The playing track's lyrics in place of the cover. Lyrics in the file's tags or a `.lrc` in its chat
 * come first; only when there are none, and only when the user turned on "Look up lyrics online" (asked
 * once here, then in Settings), the lyrics service is asked, once per song. Synced lyrics follow the
 * track and scroll by themselves; tap a line to play from it. Plain lyrics scroll.
 *
 * [visible] is false while the sheet is not fully open: then nothing is looked up, nothing is asked,
 * and the position is not read. Nothing here waits on or holds up playback.
 */
@Composable
internal fun LyricsPanel(state: PlayerState, visible: Boolean) {
    val item = state.item?.mediaId?.let { MediaIds.item(it) }
    val id = item?.id
    val songKey = item?.songKey.orEmpty()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val shown = visible && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    val choices = LyricsOnline.choices
    val onlineOn by choices.online.collectAsStateWithLifecycle()
    val asked by choices.asked.collectAsStateWithLifecycle()

    var local by remember(id) { mutableStateOf<Local>(Local.Reading) }
    LaunchedEffect(id) {
        if (id == null) {
            local = Local.None(null)
            return@LaunchedEffect
        }
        local = try {
            val track = Library.index().track(id)
            track?.let { Library.lyricsFor(it) }?.let { Local.Found(it) } ?: Local.None(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            JobFailures.handle("LyricsPanel", e)
            Local.None(null)
        }
    }

    // The online answer: null while it is being looked up. [attempt] counts the user's Try again.
    var remote by remember(id) { mutableStateOf<OnlineLyrics?>(null) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    var done by remember(id) { mutableIntStateOf(-1) }
    var asking by rememberSaveable(id) { mutableStateOf(false) }
    val track = (local as? Local.None)?.track
    LaunchedEffect(track, onlineOn, attempt, shown) {
        if (track == null || !shown || !onlineOn || done == attempt) return@LaunchedEffect
        remote = null
        remote = try {
            LyricsOnline.lookup(LyricsQuery.of(track, songKey.ifBlank { track.groupKey }), retry = attempt > 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            JobFailures.handle("LyricsPanel", e)
            OnlineLyrics.Failed
        }
        done = attempt
    }
    // Asked once, the first time a track with no lyrics of its own is shown here with the switch off.
    LaunchedEffect(local, asked, onlineOn, shown) {
        if (shown && local is Local.None && !asked && !onlineOn) asking = true
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val now = local) {
            Local.Reading -> Unit
            is Local.Found -> LyricsText(now.lyrics, state, shown, source = false)
            is Local.None -> when {
                !onlineOn -> Message(L.lyricsNone) {
                    TextButton(onClick = { asking = true }) { Text(L.lyricsSearchOnline) }
                }
                now.track == null -> Message(L.lyricsNone)
                else -> Online(remote, state, shown, onRetry = { attempt++ })
            }
        }
    }

    if (asking && shown) {
        AlertDialog(
            onDismissRequest = {
                asking = false
                choices.declined()
            },
            title = { Text(L.lyricsAskTitle) },
            text = { Text(L.lyricsAskBody, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    choices.setOnline(true)
                }) { Text(L.lyricsAskAllow) }
            },
            dismissButton = {
                TextButton(onClick = {
                    asking = false
                    choices.declined()
                }) { Text(L.lyricsAskNotNow) }
            },
        )
    }
}

@Composable
private fun Online(remote: OnlineLyrics?, state: PlayerState, shown: Boolean, onRetry: () -> Unit) {
    val sendLogs = rememberSendLogs()
    when (remote) {
        null -> Column(
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(L.lyricsLoading, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is OnlineLyrics.Found -> LyricsText(remote.lyrics, state, shown, source = true)
        OnlineLyrics.Instrumental -> Message(L.lyricsInstrumental)
        OnlineLyrics.NotFound -> Message(L.lyricsNotFound)
        OnlineLyrics.NeedsLength -> Message(L.lyricsNeedLength)
        OnlineLyrics.Off -> Message(L.lyricsNone)
        is OnlineLyrics.Busy -> Message(L.lyricsBusy) { TextButton(onClick = onRetry) { Text(L.commonRetry) } }
        OnlineLyrics.Offline -> Message(L.lyricsOffline) { TextButton(onClick = onRetry) { Text(L.commonRetry) } }
        OnlineLyrics.Failed -> Message(L.lyricsFailed) {
            TextButton(onClick = onRetry) { Text(L.commonRetry) }
            TextButton(onClick = sendLogs) { Text(L.logsSend) }
        }
    }
}

/** A quiet line in the middle of the panel, with the buttons that lead on from it. Scrolls at large text. */
@Composable
private fun Message(text: String, actions: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
private fun LyricsText(lyrics: Lyrics, state: PlayerState, shown: Boolean, source: Boolean) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (lyrics.synced) {
                SyncedLyrics(lyrics, state, shown)
            } else {
                Text(
                    lyrics.plain,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
                )
            }
        }
        if (source && com.tmplayer.music.BuildHooks.lyricsSourceShown()) {
            Text(
                L.lyricsSource,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/**
 * Synced lyrics: the position is read four times a second only while the track plays and this is on
 * screen ([shown]); paused or hidden, nothing ticks. The playing line is bold and kept near the top,
 * unless the user is scrolling the list.
 */
@Composable
private fun SyncedLyrics(lyrics: Lyrics, state: PlayerState, shown: Boolean) {
    var position by remember { mutableLongStateOf(PlayerConnection.position()) }
    LaunchedEffect(state.isPlaying, state.index, shown) {
        position = PlayerConnection.position()
        while (state.isPlaying && shown) {
            delay(TICK_MS)
            position = PlayerConnection.position()
        }
    }
    val current = lyrics.lineAt(position, LATENCY_MS)
    val list = rememberLazyListState()
    val haptics = com.tmplayer.music.ui.common.rememberHaptics()
    LaunchedEffect(current) {
        if (current >= 0 && !list.isScrollInProgress) runCatching { list.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp)) {
        itemsIndexed(lyrics.lines) { i, line ->
            val playing = i == current
            Text(
                line.text.ifBlank { "♪" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                color = if (playing) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { selected = playing }
                    .clickable(onClickLabel = L.lyricsSeek) { haptics.tap(); PlayerConnection.seekTo(line.timeMs) }
                    .padding(vertical = 6.dp, horizontal = 8.dp),
            )
        }
    }
}

/** How far ahead the highlight runs of the player's clock, for the audio path's own delay. */
private const val LATENCY_MS = 150L

/** How often synced lyrics read the position while playing and shown. */
private const val TICK_MS = 250L
