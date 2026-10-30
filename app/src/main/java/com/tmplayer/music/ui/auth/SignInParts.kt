// Adapted from TMPlayer (GPL-3.0), app/src/main/java/com/tmplayer/ui/auth/LoginScreen.kt and
// ui/src/shared/kotlin/com/tmplayer/ui/auth/SignInWords.kt (the countdown).
package com.tmplayer.music.ui.auth

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.Td
import com.tmplayer.music.i18n.L
import kotlinx.coroutines.delay

/**
 * One sign in step: an optional back arrow, then the step's own column, centred and no wider than
 * reads well. The column scrolls, and the keyboard pushes it up rather than covering it, so on a
 * small phone in landscape at a large font the whole step is still reachable.
 */
@Composable
fun SignInFrame(onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = L.commonBack)
                    }
                } else {
                    Spacer(Modifier.size(8.dp))
                }
                content()
                Text(
                    L.appUnofficial,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
fun StepBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** An error, read out by a screen reader as soon as it appears. */
@Composable
fun StepError(text: String?) {
    if (text == null) return
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The step's one main action. While its request is on its way it shows a spinner and [busyLabel]
 * and takes no second press; after [SLOW_MS] it says Telegram is slow rather than look stuck.
 * The keyboard opening brings it into view, so it is never left hidden under the keys.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun MainAction(
    label: String,
    busyLabel: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }
    val ime = WindowInsets.isImeVisible
    LaunchedEffect(ime) {
        if (!ime) return@LaunchedEffect
        // After the frame the insets land in, so the column has already shrunk.
        delay(IME_SETTLE_MS)
        runCatching { requester.bringIntoView() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onClick,
            enabled = enabled && !busy,
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester),
        ) {
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(busyLabel)
                }
            } else {
                Text(label)
            }
        }
        if (busy) {
            val slow = rememberSlow()
            if (slow) StepBody(L.signinSlow)
        }
    }
}

/** True once the request in [SignInRunner.busy] has been out for [SLOW_MS], rotation or not. */
@Composable
private fun rememberSlow(): Boolean {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val left = SignInRunner.busySince + SLOW_MS - System.currentTimeMillis()
        if (left > 0) delay(left)
        slow = true
    }
    return slow
}

/**
 * Seconds left of a [seconds] wait that started when [key] was first seen. The deadline is kept
 * as a time of day, so a rotation or a spell in the background neither restarts nor stalls it,
 * and turning the phone does not hand back a resend that Telegram would refuse.
 */
@Composable
fun rememberCountdown(seconds: Int, key: Any?): Int {
    val deadline by rememberSaveable(key) { mutableLongStateOf(System.currentTimeMillis() + seconds.coerceAtLeast(0) * 1000L) }
    return rememberSecondsUntil(deadline)
}

/** Seconds left until [deadlineMs], counted down once a second and stopping at zero. */
@Composable
fun rememberSecondsUntil(deadlineMs: Long): Int {
    fun left() = ((deadlineMs - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0).toInt()
    var left by remember(deadlineMs) { mutableIntStateOf(left()) }
    LaunchedEffect(deadlineMs) {
        while (true) {
            left = left()
            if (left <= 0) break
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    return left
}

/** The flood wait Telegram last put on a sign in request, in seconds left; zero for none. */
@Composable
fun rememberFloodWait(): Int {
    val until by Td.signInWaitUntil.collectAsStateWithLifecycle()
    return rememberSecondsUntil(until)
}

private const val SLOW_MS = 8_000L
private const val IME_SETTLE_MS = 250L
