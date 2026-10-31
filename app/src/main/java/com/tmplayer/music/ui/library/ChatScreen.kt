package com.tmplayer.music.ui.library

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.ChatLoader
import com.tmplayer.music.data.ChatOrder
import com.tmplayer.music.data.ChatProblem
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.diagnostics.SendLogsButton
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ChatRow
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.TagState
import com.tmplayer.music.library.Track
import com.tmplayer.music.ui.common.ChatAvatar
import com.tmplayer.music.ui.common.Cover
import com.tmplayer.music.ui.common.Format
import com.tmplayer.music.ui.common.MusicIcons
import com.tmplayer.music.ui.theme.MusicTheme
import com.tmplayer.music.ui.theme.Pill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * One file of a chat as a row the player takes: that chat's copy of the song, so playing from a chat
 * page plays what is in that chat even when another chat holds a better version.
 */
fun Track.asSong(): Song = Song(
    groupKey = "",
    title = title.ifBlank { fileName },
    artist = artist,
    album = album,
    durationMs = durationMs,
    defaultTrack = id,
    versionCount = 1,
    bestFormat = format,
    thumbFileId = thumbFileId,
    coverKey = coverKey,
    variant = variant,
    addedSec = dateSec,
)

/** A second Play within this long of the first is the same press, not a restart. */
private const val PLAY_DEBOUNCE_MS = 1_500L

/**
 * The Chats tab: the chosen chats with the tracks the index holds for each, a Play button that plays
 * the chat in its own order, and Add chats as the last item, centred after the last chat (or under the
 * empty text when there are none). Tap a chat for its page.
 */
@Composable
fun ChatsTab(actions: SongActions, onChat: (ChatRow) -> Unit, onAdd: () -> Unit) {
    val changes by Library.changes.collectAsStateWithLifecycle()
    val status by Library.scanner.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf<List<ChatRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(changes) {
        try {
            chats = Library.index().pickedChats()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = Failures.humanise(e)
        }
    }
    val listState = rememberLazyListState()
    RestoreScroll(listState, ready = chats != null)
    ScrollToTopOnReselect(listState)
    // The body sits above the mini-player and the bottom bar (the shell's spacer keeps that room),
    // so this padding is only breathing room under the last item.
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        error?.let { message ->
            item(key = "error") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    SendLogsButton()
                }
            }
        }
        val list = chats
        if (list != null && list.isEmpty()) {
            item(key = "empty") {
                Text(L.chatfirstChatsEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
        }
        items(list.orEmpty(), key = { it.chatId }) { chat ->
            val failed = status.failed[chat.chatId]
            val line = when {
                failed != null -> failed
                chat.scan.audio.done && chat.scan.documents.done -> L.manageTracks(chat.trackCount)
                else -> L.manageScanning(chat.trackCount)
            }
            Row(
                Modifier.fillMaxWidth().clickable { onChat(chat) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ChatAvatar(chatId = chat.chatId, name = chat.title, size = 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(chat.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                if (starting == chat.chatId) {
                    CircularProgressIndicator(Modifier.size(40.dp).padding(8.dp))
                } else {
                    FilledTonalIconButton(
                        onClick = {
                            if (starting != null) return@FilledTonalIconButton
                            starting = chat.chatId
                            scope.launch {
                                try {
                                    val loader = Library.chatLoader(chat.chatId, Library.choices.chatOrder(chat.chatId))
                                    // Offline or slow, the button gives up after a while instead of spinning on.
                                    kotlinx.coroutines.withTimeoutOrNull(PLAY_START_MS) { loader.start() }
                                    val rows = loader.shown()
                                    if (rows.isNotEmpty()) actions.play(rows.map { it.asSong() }, 0) else error = Failures.DEFAULT
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = Failures.humanise(e)
                                } finally {
                                    starting = null
                                }
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = L.chatfirstPlayChat(chat.title) },
                    ) { Icon(MusicIcons.Play, contentDescription = null) }
                }
            }
        }
        // One Add chats button, the last item (so TalkBack reaches it last): after the last chat, or
        // under the empty text when there are none. Hidden only while the first load is running and
        // nothing has failed, so it does not flash in above rows that are about to arrive.
        if (list != null || error != null) {
            item(key = "add") { AddChatsButton(onAdd) }
        }
    }
}

/** Add chats, centred under the list with room above it; a 48 dp touch target at least. */
@Composable
private fun AddChatsButton(onAdd: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp), contentAlignment = Alignment.Center) {
        FilledTonalButton(onClick = onAdd, shape = Pill, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(MusicIcons.Plus, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(L.chatfirstChatsAdd)
        }
    }
}

// The end of the Chats list on the black theme and at font scale 1.3: placeholder rows, then the button.
@Composable
private fun ChatsEndSample(dark: Boolean) {
    MusicTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                listOf("Chat one", "Chat two").forEach { title ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Cover(name = title, fileId = 0, round = true, size = 48.dp)
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                AddChatsButton(onAdd = {})
            }
        }
    }
}

@Preview(name = "Chats end, dark", widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatsEndDark() = ChatsEndSample(dark = true)

@Preview(name = "Chats end, font 1.3", widthDp = 360, fontScale = 1.3f)
@Composable
private fun ChatsEndLargeText() = ChatsEndSample(dark = false)

/**
 * A chat as a playlist in message order: Oldest first or Newest
 * first (remembered per chat), Play and Shuffle, loaded a page at a time as the list nears its end.
 * Tapping a row plays the chat's order from there. The rows on screen get their tags read first.
 * Offline, a flood wait, a chat that is gone and a chat removed while open each say so and offer
 * the way out. [onGone] leaves the page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(chat: ChatRow, actions: SongActions, onGone: () -> Unit) {
    var order by remember(chat.chatId) { mutableStateOf(Library.choices.chatOrder(chat.chatId)) }
    val loader = remember(chat.chatId, order) { Library.chatLoader(chat.chatId, order) }
    key(loader) { ChatPageBody(chat, loader, actions, order, onOrder = {
        order = it
        Library.choices.setChatOrder(chat.chatId, it)
    }, onGone = onGone) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatPageBody(
    chat: ChatRow,
    loader: ChatLoader,
    actions: SongActions,
    order: ChatOrder,
    onOrder: (ChatOrder) -> Unit,
    onGone: () -> Unit,
) {
    val state by loader.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState(loader.scrollIndex, loader.scrollOffset)
    val scope = rememberCoroutineScope()
    val changes by Library.changes.collectAsStateWithLifecycle()
    var lastPlay by remember { mutableLongStateOf(0L) }

    DisposableEffect(loader, list) {
        onDispose {
            loader.scrollIndex = list.firstVisibleItemIndex
            loader.scrollOffset = list.firstVisibleItemScrollOffset
        }
    }
    // First rows, then the next page as the list nears its end. Leaving the page or switching the
    // order cancels a page that is still loading.
    LaunchedEffect(loader) {
        loader.start()
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= loader.state.value.rows.size - NEAR_END }
            .collect {
                // A page of other documents adds no row and moves no scroll: go on while the end of
                // the list is on screen, until the chat ends, fails or a few pages bring nothing.
                var idle = 0
                while (idle < IDLE_PAGES) {
                    val before = loader.state.value
                    if (before.end || before.problem != null) break
                    loader.more()
                    val after = loader.state.value
                    if (after.rows.size == before.rows.size) idle++ else idle = 0
                    val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    if (last < after.rows.size - NEAR_END) break
                }
            }
    }
    // A tag read or a switch changed the index: the rows around the screen read again, and a chat
    // removed meanwhile is noticed.
    LaunchedEffect(loader) {
        snapshotFlow { changes }.drop(1).collectLatest {
            delay(REFRESH_SETTLE_MS)
            val rows = loader.state.value.rows
            val from = (list.firstVisibleItemIndex - REFRESH_AROUND).coerceAtLeast(0)
            loader.refresh(rows.drop(from).take(REFRESH_AROUND * 3).map { it.messageId })
        }
    }
    // The rows on screen go to the front of the tag reader's queue once the scroll settles.
    LaunchedEffect(loader) {
        snapshotFlow { list.firstVisibleItemIndex to state.rows.size }.distinctUntilChanged().collectLatest { (first, _) ->
            delay(VISIBLE_SETTLE_MS)
            val shown = loader.state.value.rows.drop((first - 1).coerceAtLeast(0)).take(VISIBLE_ROWS)
            val waiting = shown.filter { it.tagState == TagState.TelegramOnly }.map { it.id }
            if (waiting.isNotEmpty()) Library.enricher.prefer(waiting)
        }
    }
    LaunchedEffect(state.problem) {
        if (state.problem == ChatProblem.Removed) onGone()
    }

    fun play(start: Int, shuffle: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastPlay < PLAY_DEBOUNCE_MS) return
        lastPlay = now
        val rows = loader.shown()
        if (rows.isEmpty()) return
        val songs = rows.map { it.asSong() }
        if (shuffle) actions.shuffle(songs) else actions.play(songs, start.coerceIn(0, songs.lastIndex))
    }

    LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { play(0, false) }, enabled = state.rows.isNotEmpty(), shape = Pill) {
                            Icon(MusicIcons.Play, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(L.chatfirstPlay)
                        }
                        FilledTonalButton(onClick = { play(0, true) }, enabled = state.rows.isNotEmpty(), shape = Pill) {
                            Icon(MusicIcons.Shuffle, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(L.chatfirstShuffle)
                        }
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val choices = listOf(ChatOrder.OldestFirst to L.chatfirstOrderOldest, ChatOrder.NewestFirst to L.chatfirstOrderNewest)
                        choices.forEachIndexed { i, (value, label) ->
                            SegmentedButton(
                                selected = order == value,
                                onClick = { if (order != value) onOrder(value) },
                                shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                            ) { Text(label, maxLines = 1) }
                        }
                    }
                    val note = when {
                        state.rows.isNotEmpty() && state.end -> L.chatfirstChatEnd(state.rows.size)
                        else -> null
                    }
                    note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        state.problem?.let { problem ->
            item(key = "problem") { ProblemLine(problem, chat, loader, scope, onGone) }
        }
        itemsIndexedTracks(state.rows, onPlay = { play(it, false) }, onMenu = { track ->
            scope.launch {
                val song = runCatching { Library.index().songOf(track.id) }.getOrNull()
                if (song != null) actions.menu(song.copy(defaultTrack = track.id))
            }
        })
        when {
            state.loading || !state.started -> item(key = "loading") {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    if (state.fromTelegram) Text(L.chatfirstChatLoading, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.rows.isEmpty() && state.end && state.problem == null -> item(key = "empty") {
                Text(L.chatfirstChatEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedTracks(
    rows: List<Track>,
    onPlay: (Int) -> Unit,
    onMenu: (Track) -> Unit,
) {
    items(rows.size, key = { rows[it].messageId }) { i ->
        val track = rows[i]
        TrackRow(track, onClick = { onPlay(i) }, onLongClick = { onMenu(track) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(track: Track, onClick: () -> Unit, onLongClick: () -> Unit) {
    val title = track.title.ifBlank { track.fileName }
    val artist = track.artist.ifBlank { L.libraryUnknownArtist }
    val length = Format.duration(track.durationMs)
    val mark = playingMarkOfTrack(track.id)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOf(title, artist, length).filter { it.isNotBlank() }.joinToString(", ")
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(
            name = title,
            fileId = 0,
            coverPath = Library.coverFile(track.coverKey)?.path.orEmpty(),
            track = track.id.takeIf { track.thumbFileId != 0 },
            art = com.tmplayer.music.library.CoverArt.keyOf(track),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mark != null) PlayingGlyph(playing = mark, modifier = Modifier.padding(end = 6.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (mark != null) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                    fontWeight = if (mark != null) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                listOf(artist, length).filter { it.isNotBlank() }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.format.lossless) Badge(track.format.label)
    }
}

@Composable
private fun ProblemLine(
    problem: ChatProblem,
    chat: ChatRow,
    loader: ChatLoader,
    scope: kotlinx.coroutines.CoroutineScope,
    onGone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (problem) {
            ChatProblem.Offline -> Text(L.chatfirstChatOffline, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is ChatProblem.Waiting -> Text(L.chatfirstChatWaiting(problem.seconds), color = MaterialTheme.colorScheme.onSurfaceVariant)
            is ChatProblem.Failed -> {
                Text(problem.message, color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton(onClick = {
                        loader.retry()
                        scope.launch { loader.more() }
                    }) { Text(L.commonRetry) }
                    SendLogsButton()
                }
            }
            ChatProblem.NotAvailable -> {
                Text(L.chatfirstChatNotAvailable, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    scope.launch {
                        runCatching { Library.removeChat(chat.chatId) }
                        onGone()
                    }
                }) { Text(L.chatfirstChatCleanUp) }
            }
            ChatProblem.Removed -> {
                Text(L.chatfirstChatRemoved, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box { TextButton(onClick = onGone) { Text(L.commonBack) } }
            }
        }
    }
}

private const val NEAR_END = 30
private const val PLAY_START_MS = 10_000L
private const val IDLE_PAGES = 5
private const val VISIBLE_ROWS = 12
private const val VISIBLE_SETTLE_MS = 400L
private const val REFRESH_SETTLE_MS = 600L
private const val REFRESH_AROUND = 60
