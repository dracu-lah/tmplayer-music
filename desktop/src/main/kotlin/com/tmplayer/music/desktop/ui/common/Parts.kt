package com.tmplayer.music.desktop.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.music.desktop.diagnostics.SendLogsButton
import com.tmplayer.music.i18n.L
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether a text field has the keyboard, so Space, the arrows and Ctrl+A stay text there instead of
 * being the player's shortcuts ([Shortcuts]). Every text field in the shell carries [typingField].
 */
object Typing {
    private val focused = AtomicInteger(0)

    val active: Boolean get() = focused.get() > 0

    internal fun changed(isFocused: Boolean) {
        if (isFocused) focused.incrementAndGet() else focused.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}

/** Marks a text field as one the keyboard types into ([Typing]); also counts when it leaves the screen focused. */
fun Modifier.typingField(): Modifier = this.then(Modifier.onFocusChanged { Typing.changed(it.isFocused) })

/** Commands the window's key handler sends to whichever list is on screen. */
object ListCommands {
    private val _selectAll = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Ctrl+A: select every row the list has read. */
    val selectAll: SharedFlow<Unit> = _selectAll.asSharedFlow()

    fun requestSelectAll() {
        _selectAll.tryEmit(Unit)
    }
}

/** Commands the window's key handler sends to the shell. */
object ShellCommands {
    private val _search = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Ctrl+F: open Search with the keyboard in its field. */
    val search: SharedFlow<Unit> = _search.asSharedFlow()

    fun requestSearch() {
        _search.tryEmit(Unit)
    }
}

/** Calls [action] once when Ctrl+A is pressed while this is on screen. */
@Composable
fun OnSelectAll(action: () -> Unit) {
    val current = androidx.compose.runtime.rememberUpdatedState(action)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        ListCommands.selectAll.collect { current.value() }
    }
}

/** A centred line of quiet text, with an optional action under it: an empty list says what to do. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

/** What a failure looks like anywhere: the sentence, Try again and Send logs. Never a dead end. */
@Composable
fun ErrorPanel(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onRetry != null) TextButton(onClick = onRetry) { Text(L.commonRetry) }
            SendLogsButton()
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * The still glyph before the current song's title: three bars of different heights while it plays,
 * three low bars when paused. It never animates, so a list costs nothing while music plays.
 */
@Composable
fun PlayingGlyph(playing: Boolean, modifier: Modifier = Modifier) {
    Icon(
        if (playing) MusicIcons.Equalizer else MusicIcons.EqualizerPaused,
        contentDescription = if (playing) L.listeningNowPlaying else L.listeningNowPaused,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(16.dp),
    )
}

/** Runs [block] when the composition leaves the screen; for cleanups that are not a coroutine's. */
@Composable
fun OnDispose(block: () -> Unit) {
    DisposableEffect(Unit) { onDispose(block) }
}

/**
 * Whether Ctrl (Cmd on a Mac) and Shift are held, for a click that selects: kept from the key events
 * the window sees ([update]), and cleared when the window loses the keyboard, so a key released
 * somewhere else cannot stay "held".
 */
object HeldKeys {
    @Volatile
    var ctrl: Boolean = false
        private set

    @Volatile
    var shift: Boolean = false
        private set

    fun update(ctrl: Boolean, shift: Boolean) {
        this.ctrl = ctrl
        this.shift = shift
    }

    fun release() = update(ctrl = false, shift = false)
}
