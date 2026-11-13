// The lyrics panel follows the Android app's LyricsView (app/.../ui/player/LyricsView.kt): the same
// order (the file's tags or a .lrc in its chat first, the online lookup only when there are none),
// the same core rules (LyricsParser lines and word times, LyricsOffsets timing, LyricsShare text).
package com.tmplayer.music.desktop.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Library
import com.tmplayer.music.desktop.player.PlayerController
import com.tmplayer.music.desktop.player.QueueEntry
import com.tmplayer.music.desktop.ui.common.BackHandler
import com.tmplayer.music.desktop.ui.common.MusicIcons
import com.tmplayer.music.desktop.ui.library.LocalNotify
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Track
import com.tmplayer.music.lyrics.LyricLine
import com.tmplayer.music.lyrics.LyricsOffsets
import com.tmplayer.music.lyrics.LyricsOnline
import com.tmplayer.music.lyrics.LyricsQuery
import com.tmplayer.music.lyrics.LyricsShare
import com.tmplayer.music.lyrics.Lyrics
import com.tmplayer.music.lyrics.OnlineLyrics
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.player.AudioEffectsSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What the file and its chat gave: still reading, lyrics, or none (with the track, for the online lookup). */
private sealed interface Local {
    data object Reading : Local

    data class Found(val lyrics: Lyrics) : Local

    data class None(val track: Track?) : Local
}

/**
 * The lyrics of the playing track as a panel over the page's right edge. Lyrics in the file's tags
 * or a `.lrc` in its chat come first; only when there are none, and only while the lyrics and covers
 * switch is on (Settings), the lyrics service is asked, once per song. Synced lyrics follow the
 * track and scroll by themselves; click a line to play from it. Lyrics with word times light up word
 * by word. Timing (earlier or later, per track) and Copy are on a row under the lyrics. Plain lyrics
 * scroll. Nothing here holds up playback. Escape closes it.
 */
@Composable
fun LyricsPanel(player: PlayerController, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ui by player.ui.collectAsState()
    val entry = ui.current
    BackHandler(onBack = onClose)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
        modifier = modifier.width(LYRICS_PANEL_WIDTH).fillMaxHeight(),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L.lyricsTitle, style = MaterialTheme.typography.titleLarge)
                    if (entry != null) {
                        Text(
                            listOf(entry.title, entry.artist).filter { it.isNotBlank() }.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onClose) { Icon(MusicIcons.Close, contentDescription = L.playerClose) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (entry == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(L.desktopNothingPlaying, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LyricsBody(player, entry)
            }
        }
    }
}

private val LYRICS_PANEL_WIDTH = 420.dp

@Composable
private fun LyricsBody(player: PlayerController, entry: QueueEntry) {
    val id = entry.id
    val onlineOn by LyricsOnline.choices.online.collectAsState()
    var local by remember(id) { mutableStateOf<Local>(Local.Reading) }
    LaunchedEffect(id) {
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
    val track = (local as? Local.None)?.track
    LaunchedEffect(track, onlineOn, attempt) {
        if (track == null || !onlineOn || done == attempt) return@LaunchedEffect
        remote = null
        remote = try {
            LyricsOnline.lookup(LyricsQuery.of(track, entry.songKey.ifBlank { track.groupKey }), retry = attempt > 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            JobFailures.handle("LyricsPanel", e)
            OnlineLyrics.Failed
        }
        done = attempt
    }
    val trackKey = "${id.chatId}:${id.messageId}"
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val now = local) {
            Local.Reading -> CircularProgressIndicator(Modifier.size(28.dp))
            is Local.Found -> LyricsText(now.lyrics, player, entry, source = false, trackKey = trackKey)
            is Local.None -> when {
                !onlineOn -> Message(L.lyricsNone)
                now.track == null -> Message(L.lyricsNone)
                else -> Online(remote, player, entry, trackKey, onRetry = { attempt++ })
            }
        }
    }
}

@Composable
private fun Online(remote: OnlineLyrics?, player: PlayerController, entry: QueueEntry, trackKey: String, onRetry: () -> Unit) {
    when (remote) {
        null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(L.lyricsLoading, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is OnlineLyrics.Found -> LyricsText(remote.lyrics, player, entry, source = true, trackKey = trackKey)
        OnlineLyrics.Instrumental -> Message(L.lyricsInstrumental)
        OnlineLyrics.NotFound -> Message(L.lyricsNotFound)
        OnlineLyrics.NeedsLength -> Message(L.lyricsNeedLength)
        OnlineLyrics.Off -> Message(L.lyricsNone)
        is OnlineLyrics.Busy -> Message(L.lyricsBusy) { TextButton(onClick = onRetry) { Text(L.commonRetry) } }
        OnlineLyrics.Offline -> Message(L.lyricsOffline) { TextButton(onClick = onRetry) { Text(L.commonRetry) } }
        OnlineLyrics.Failed -> Message(L.lyricsFailed) {
            TextButton(onClick = onRetry) { Text(L.commonRetry) }
            com.tmplayer.music.desktop.diagnostics.SendLogsButton()
        }
    }
}

/** A quiet line in the middle of the panel, with the buttons that lead on from it. */
@Composable
private fun Message(text: String, actions: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
private fun LyricsText(lyrics: Lyrics, player: PlayerController, entry: QueueEntry, source: Boolean, trackKey: String) {
    val notify = LocalNotify.current
    val clipboard = LocalClipboardManager.current
    // The user's timing for this file: plus shows the lyrics later, minus earlier. Read once per track.
    var offset by remember(trackKey) { mutableLongStateOf(LyricsOnline.offsets.get(trackKey)) }
    // The line playing now, reported by the synced list, for the menu's "Share this line".
    var currentText by remember(lyrics) { mutableStateOf<String?>(null) }
    val copy: (String?) -> Unit = { text ->
        if (text != null) {
            clipboard.setText(AnnotatedString(text))
            notify(L.desktopCopied)
        } else {
            notify(L.lyricsShareFailed)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (lyrics.synced) {
                SyncedLyrics(lyrics, player, offset, onCurrent = { currentText = it }) { line ->
                    copy(LyricsShare.line(line, entry.title, entry.artist))
                }
            } else {
                Text(
                    lyrics.plain,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                )
            }
        }
        LyricsTools(
            synced = lyrics.synced,
            offset = offset,
            onOffset = {
                offset = it
                LyricsOnline.offsets.set(trackKey, it)
            },
            onShareLine = currentText?.takeIf { it.isNotBlank() }?.let { line -> { copy(LyricsShare.line(line, entry.title, entry.artist)) } },
            onShareAll = { copy(LyricsShare.all(lyrics, entry.title, entry.artist)) },
        )
        if (source) {
            Text(
                L.lyricsSource,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
    }
}

/**
 * Under the lyrics: the timing (a tenth of a second per press, Reset when it is not zero) for synced
 * lyrics, and the share menu (it copies the text to the clipboard: this computer has no share sheet).
 */
@Composable
private fun LyricsTools(synced: Boolean, offset: Long, onOffset: (Long) -> Unit, onShareLine: (() -> Unit)?, onShareAll: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (synced) {
            IconButton(onClick = { onOffset(LyricsOffsets.step(offset, -1)) }) { Icon(MusicIcons.Minus, contentDescription = L.lyricsOffsetEarlier) }
            Text(L.lyricsOffsetValue(offsetLabel(offset)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { onOffset(LyricsOffsets.step(offset, 1)) }) { Icon(MusicIcons.Plus, contentDescription = L.lyricsOffsetLater) }
            TextButton(onClick = { onOffset(0L) }, enabled = offset != 0L) { Text(L.lyricsOffsetReset) }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(MusicIcons.More, contentDescription = L.lyricsShareMenu) }
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

/** `+0.3 s`, `-1.2 s` or `0.0 s`, with the computer's decimal mark. */
internal fun offsetLabel(ms: Long): String {
    if (ms == 0L) return String.format(java.util.Locale.getDefault(), "%.1f s", 0.0)
    return String.format(java.util.Locale.getDefault(), "%+.1f s", ms / 1000.0)
}

/**
 * Synced lyrics. The player says the position four times a second; between those the clock is carried
 * on here (twenty times a second, and only while the playing line has word times), so a word lights
 * as it is sung. Paused or with nothing on screen, nothing ticks. The playing line is bold and kept
 * near the top, unless the user is scrolling the list. [offset] is the user's timing: plus shows
 * the lyrics later.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SyncedLyrics(lyrics: Lyrics, player: PlayerController, offset: Long, onCurrent: (String?) -> Unit, onShareLine: (String) -> Unit) {
    val ui by player.ui.collectAsState()
    val reported by player.position.collectAsState()
    val speed by AudioEffectsSetting.choices.speed.collectAsState()
    val clock = remember { mutableLongStateOf(reported) }
    val lead = -offset
    val playing = ui.playing
    LaunchedEffect(reported, playing, lyrics, lead) {
        // The reported position is the truth; the clock runs on from it while the sound does.
        val base = reported
        val since = System.nanoTime()
        clock.longValue = base
        while (playing) {
            val line = lyrics.lines.getOrNull(lyrics.lineAt(clock.longValue, lead))
            delay(if (line?.hasWords == true) WORD_TICK_MS else TICK_MS)
            clock.longValue = base + ((System.nanoTime() - since) / 1_000_000 * speed).toLong()
        }
    }
    val current by remember(lyrics, lead) { derivedStateOf { lyrics.lineAt(clock.longValue, lead) } }
    val list = rememberLazyListState()
    var menuLine by remember(lyrics) { mutableIntStateOf(-1) }
    LaunchedEffect(lyrics, lead) {
        snapshotFlow { current }.collect { now ->
            onCurrent(lyrics.lines.getOrNull(now)?.text)
            if (now >= 0 && !list.isScrollInProgress) runCatching { list.animateScrollToItem((now - 2).coerceAtLeast(0)) }
        }
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp, horizontal = 16.dp)) {
        itemsIndexed(lyrics.lines) { i, line ->
            val playingLine = i == current
            Box {
                LyricRow(
                    line = line,
                    next = lyrics.lines.getOrNull(i + 1)?.timeMs ?: -1L,
                    playing = playingLine,
                    clock = if (playingLine) clock else null,
                    lead = lead,
                    onClick = { player.seekTo((line.timeMs + offset).coerceAtLeast(0)) },
                    onMenu = { menuLine = i },
                )
                DropdownMenu(expanded = menuLine == i, onDismissRequest = { menuLine = -1 }) {
                    DropdownMenuItem(text = { Text(L.lyricsSeek) }, onClick = {
                        menuLine = -1
                        player.seekTo((line.timeMs + offset).coerceAtLeast(0))
                    })
                    DropdownMenuItem(text = { Text(L.lyricsShareLine) }, onClick = {
                        menuLine = -1
                        onShareLine(line.text)
                    })
                }
            }
        }
    }
}

private const val TICK_MS = 250L
private const val WORD_TICK_MS = 50L

@Composable
private fun LyricRow(
    line: LyricLine,
    next: Long,
    playing: Boolean,
    clock: androidx.compose.runtime.State<Long>?,
    lead: Long,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    val sung = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val text: AnnotatedString = if (playing && clock != null && line.hasWords) {
        // Word by word: the words sung so far, and the one being sung, in the accent colour.
        val at = clock.value + lead
        val word = line.wordAt(at)
        buildAnnotatedString {
            line.words.forEachIndexed { i, w ->
                withStyle(SpanStyle(color = if (i <= word) sung else rest)) { append(w.text) }
            }
        }
    } else {
        AnnotatedString(line.text)
    }
    Text(
        text,
        style = if (playing) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
        fontWeight = if (playing) FontWeight.Bold else null,
        color = if (playing) (if (line.hasWords) rest else sung) else quiet,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    )
}
