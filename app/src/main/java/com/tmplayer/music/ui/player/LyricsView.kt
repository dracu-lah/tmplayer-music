package com.tmplayer.music.ui.player

import androidx.compose.foundation.focusable
import com.tmplayer.music.ui.tv.tvAutoFocus
import com.tmplayer.music.ui.tv.tvFocusRing
import com.tmplayer.music.ui.tv.tvScrollKeys
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.tmplayer.music.data.Library
import com.tmplayer.music.diagnostics.rememberSendLogs
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Track
import com.tmplayer.music.lyrics.LyricLine
import com.tmplayer.music.lyrics.Lyrics
import com.tmplayer.music.lyrics.LyricsOffsets
import com.tmplayer.music.lyrics.LyricsShare
import com.tmplayer.music.lyrics.LyricsOnline
import com.tmplayer.music.lyrics.LyricsQuery
import com.tmplayer.music.lyrics.OnlineLyrics
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.player.MediaIds
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.player.PlayerState
import com.tmplayer.music.ui.common.MusicIcons
import com.tmplayer.music.ui.theme.LocalReducedMotion
import com.tmplayer.music.ui.tv.LocalTv
import com.tmplayer.music.ui.tv.tvFocusRing
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
 * track and scroll by themselves; tap a line to play from it, long-press it to share it. Lyrics with
 * word times light up word by word. Timing (earlier or later, per track) and Share are on a row
 * under the lyrics, which the TV's remote reaches. Plain lyrics scroll.
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
    // Not collectAsStateWithLifecycle: that stops collecting at the stop and could keep STARTED for good.
    val lifecycleState by lifecycle.currentStateAsState()
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
            is Local.Found -> LyricsText(now.lyrics, state, shown, source = false, trackKey = id?.let { "${it.chatId}:${it.messageId}" }.orEmpty())
            is Local.None -> when {
                !onlineOn -> Message(L.lyricsNone) {
                    TextButton(onClick = { asking = true }) { Text(L.lyricsSearchOnline) }
                }
                now.track == null -> Message(L.lyricsNone)
                else -> Online(remote, state, shown, trackKey = id?.let { "${it.chatId}:${it.messageId}" }.orEmpty(), onRetry = { attempt++ })
            }
        }
    }

    if (asking && shown) {
        // The remote starts on Not now: a stray OK never turns the online lookup on.
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
                TextButton(modifier = Modifier.tvAutoFocus(), onClick = {
                    asking = false
                    choices.declined()
                }) { Text(L.lyricsAskNotNow) }
            },
        )
    }
}

@Composable
private fun Online(remote: OnlineLyrics?, state: PlayerState, shown: Boolean, trackKey: String, onRetry: () -> Unit) {
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
        is OnlineLyrics.Found -> LyricsText(remote.lyrics, state, shown, source = true, trackKey = trackKey)
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
private fun LyricsText(lyrics: Lyrics, state: PlayerState, shown: Boolean, source: Boolean, trackKey: String) {
    val context = LocalContext.current
    val title = state.metadata.title?.toString()
    val artist = state.metadata.artist?.toString()
    // The user's timing for this file: plus shows the lyrics later, minus earlier. Read once per track.
    var offset by remember(trackKey) { mutableLongStateOf(if (trackKey.isEmpty()) 0L else LyricsOnline.offsets.get(trackKey)) }
    // The line playing now, reported by the synced list, for the menu's "Share this line".
    var currentText by remember(lyrics) { mutableStateOf<String?>(null) }
    val share: (String?) -> Unit = { text -> if (text != null) shareText(context, text) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (lyrics.synced) {
                SyncedLyrics(lyrics, state, shown, offset, onCurrent = { currentText = it }) { line ->
                    share(LyricsShare.line(line, title, artist))
                }
            } else {
                val scroll = rememberScrollState()
                Text(
                    lyrics.plain,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    // A TV: the page takes the D-pad and Up and Down scroll it; at its ends they leave it.
                    modifier = Modifier
                        .fillMaxSize()
                        .tvReadable(scroll)
                        .verticalScroll(scroll)
                        .padding(8.dp),
                )
            }
        }
        LyricsTools(
            synced = lyrics.synced,
            offset = offset,
            onOffset = {
                offset = it
                if (trackKey.isNotEmpty()) LyricsOnline.offsets.set(trackKey, it)
            },
            onShareLine = currentText?.takeIf { it.isNotBlank() }?.let { line -> { share(LyricsShare.line(line, title, artist)) } },
            onShareAll = { share(LyricsShare.all(lyrics, title, artist)) },
        )
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
 * Under the lyrics: the timing (a tenth of a second per press, Reset when it is not zero) for synced
 * lyrics, and the share menu. Plain buttons, so the TV's remote reaches them; the lines themselves
 * are not focusable there. "Share this line" is offered while a line is playing.
 */
@Composable
private fun LyricsTools(
    synced: Boolean,
    offset: Long,
    onOffset: (Long) -> Unit,
    onShareLine: (() -> Unit)?,
    onShareAll: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (synced) {
            IconButton(
                onClick = { onOffset(LyricsOffsets.step(offset, -1)) },
                modifier = Modifier.tvFocusRing(),
            ) { Icon(MusicIcons.Minus, contentDescription = L.lyricsOffsetEarlier) }
            Text(
                L.lyricsOffsetValue(offsetLabel(offset)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { onOffset(LyricsOffsets.step(offset, 1)) },
                modifier = Modifier.tvFocusRing(),
            ) { Icon(MusicIcons.Plus, contentDescription = L.lyricsOffsetLater) }
            TextButton(onClick = { onOffset(0L) }, enabled = offset != 0L, modifier = Modifier.tvFocusRing()) { Text(L.lyricsOffsetReset) }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.tvFocusRing()) {
                Icon(MusicIcons.More, contentDescription = L.lyricsShareMenu)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (onShareLine != null) {
                    DropdownMenuItem(text = { Text(L.lyricsShareLine) }, onClick = {
                        menu = false
                        onShareLine()
                    })
                }
                DropdownMenuItem(text = { Text(L.lyricsShareAll) }, onClick = {
                    menu = false
                    onShareAll()
                })
            }
        }
    }
}

/** `+0.3 s`, `-1.2 s` or `0.0 s`, with the device's decimal mark. */
private fun offsetLabel(ms: Long): String {
    if (ms == 0L) return String.format(java.util.Locale.getDefault(), "%.1f s", 0.0)
    return String.format(java.util.Locale.getDefault(), "%+.1f s", ms / 1000.0)
}

/** The system share sheet with [text] as plain text; a phone with nothing to take it gets a short note. */
private fun shareText(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(
            android.content.Intent.createChooser(send, L.lyricsShareMenu).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, L.lyricsShareFailed, android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        JobFailures.handle("LyricsShare", e)
        android.widget.Toast.makeText(context, L.lyricsShareFailed, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * Synced lyrics: the position is read only while the track plays and this is on screen ([shown]);
 * paused or hidden, nothing ticks. It is read four times a second, and twenty times a second only
 * while the playing line has word times. The position lives in a state that only the playing line
 * reads, so a tick redraws that one line (and only when its word or its fill step changes), never the
 * list. The playing line is bold and kept near the top, unless the user is scrolling the list.
 * [offset] is the user's timing: plus shows the lyrics later.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SyncedLyrics(
    lyrics: Lyrics,
    state: PlayerState,
    shown: Boolean,
    offset: Long,
    onCurrent: (String?) -> Unit,
    onShareLine: (String) -> Unit,
) {
    val tv = LocalTv.current
    val position = remember { mutableLongStateOf(PlayerConnection.position()) }
    val lead = LATENCY_MS - offset
    val currentLead by rememberUpdatedState(lead)
    LaunchedEffect(state.isPlaying, state.index, shown, lyrics) {
        position.longValue = PlayerConnection.position()
        while (state.isPlaying && shown) {
            val line = lyrics.lines.getOrNull(lyrics.lineAt(position.longValue, currentLead))
            delay(if (line?.hasWords == true) WORD_TICK_MS else TICK_MS)
            position.longValue = PlayerConnection.position()
        }
    }
    val current by remember(lyrics, lead) { derivedStateOf { lyrics.lineAt(position.longValue, lead) } }
    val list = rememberLazyListState()
    val haptics = com.tmplayer.music.ui.common.rememberHaptics()
    var menuLine by remember(lyrics) { mutableIntStateOf(-1) }
    LaunchedEffect(lyrics, lead) {
        snapshotFlow { current }.collect { now ->
            onCurrent(lyrics.lines.getOrNull(now)?.text)
            if (now >= 0 && !list.isScrollInProgress) runCatching { list.animateScrollToItem((now - 2).coerceAtLeast(0)) }
        }
    }
    // A TV does not make every line a stop for the D-pad (fifty Downs to get out): the list is one stop
    // that scrolls with Up and Down, and the seek bar moves the track.
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().tvReadable(list), contentPadding = PaddingValues(vertical = 24.dp)) {
        itemsIndexed(lyrics.lines) { i, line ->
            val playing = i == current
            // Not tappable on a TV: the remote has no use for a line, the buttons under the list do the work.
            val tap = if (tv) {
                Modifier
            } else {
                Modifier.combinedClickable(
                    onClickLabel = L.lyricsSeek,
                    onClick = {
                        haptics.tap()
                        PlayerConnection.seekTo((line.timeMs + offset).coerceAtLeast(0))
                        position.longValue = PlayerConnection.position()
                    },
                    onLongClickLabel = L.lyricsShareLine,
                    onLongClick = {
                        haptics.confirm()
                        menuLine = i
                    },
                )
            }
            Box(Modifier.fillMaxWidth()) {
                val common = Modifier
                    .fillMaxWidth()
                    .semantics { selected = playing }
                    .then(tap)
                    .padding(vertical = 6.dp, horizontal = 8.dp)
                if (playing && line.hasWords) {
                    WordLine(line, lyrics.lines.getOrNull(i + 1)?.timeMs ?: Long.MAX_VALUE, position, lead, common)
                } else {
                    Text(
                        line.text.ifBlank { "♪" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                        color = if (playing) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = common,
                    )
                }
                if (menuLine == i) {
                    DropdownMenu(expanded = true, onDismissRequest = { menuLine = -1 }) {
                        DropdownMenuItem(text = { Text(L.lyricsShareLine) }, onClick = {
                            menuLine = -1
                            onShareLine(line.text)
                        })
                    }
                }
            }
        }
    }
}

/**
 * The playing line with word times: words already sung are fully lit, the one being sung fills in
 * with its progress, the rest stay dim. The same text and weight as a line without words, so nothing
 * moves as it lights. Only its fill step is read from the clock (about ten steps a word, or whole
 * words when animations are off), so it redraws a few times a second, not every tick.
 */
@Composable
private fun WordLine(line: LyricLine, nextLineMs: Long, position: State<Long>, lead: Long, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val step by remember(line, lead, reduced, nextLineMs) {
        derivedStateOf {
            val at = position.value + lead
            val word = line.wordAt(at)
            val fraction = if (word < 0) 0f else if (reduced) 1f else line.wordFraction(word, at, nextLineMs)
            (word + 1) * FILL_STEPS + (fraction * (FILL_STEPS - 1)).toInt()
        }
    }
    val lit = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Text(
        wordText(line, step, lit, dim),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier.semantics { contentDescription = line.text },
    )
}

private fun wordText(line: LyricLine, step: Int, lit: Color, dim: Color): AnnotatedString = buildAnnotatedString {
    val word = step / FILL_STEPS - 1
    val fraction = (step % FILL_STEPS) / (FILL_STEPS - 1).toFloat()
    line.words.forEachIndexed { i, w ->
        val color = when {
            i < word -> lit
            i == word -> lerp(dim, lit, fraction)
            else -> dim
        }
        pushStyle(SpanStyle(color = color))
        append(w.text)
        pop()
    }
}

/** How far ahead the highlight runs of the player's clock, for the audio path's own delay. */
private const val LATENCY_MS = 150L

/** How often synced lyrics read the position while playing and shown. */
private const val TICK_MS = 250L

/** The lyrics page on a TV: one stop for the D-pad, with a ring, that Up and Down scroll. Nothing on a phone. */
@Composable
private fun Modifier.tvReadable(state: androidx.compose.foundation.gestures.ScrollableState): Modifier {
    if (!com.tmplayer.music.ui.tv.LocalTv.current) return this
    return this
        .tvFocusRing(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
        .tvScrollKeys(state)
        .focusable()
}

/** The same, while the playing line has word times: twenty times a second, so a word fills smoothly. */
private const val WORD_TICK_MS = 50L

/** Fill steps per word: the fill's colour moves in tenths, so a word redraws ten times at most. */
private const val FILL_STEPS = 10
