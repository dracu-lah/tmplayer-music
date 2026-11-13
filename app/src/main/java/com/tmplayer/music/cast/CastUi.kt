package com.tmplayer.music.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.i18n.L
import com.tmplayer.music.ui.tv.TvSheet
import com.tmplayer.music.ui.tv.tvFocusRing

/**
 * A television screen, the outline of one with the waves of a signal in its corner, the icon of the Cast
 * button; drawn like the other icons (1.5 wide round strokes on a 24 dp grid).
 */
val CastIcon: ImageVector = ImageVector.Builder(
    name = "Cast",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
)
    .addPath(
        pathData = addPathNodes(
            "M3 8V7a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3h-6 " +
                "M3 12.5a8.5 8.5 0 0 1 8.5 8.5 M3 16.5a4.5 4.5 0 0 1 4.5 4.5 M3 21h.01",
        ),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.5f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
    .build()

/** How the Cast button looks: the same on Now Playing and in the mini-player, from one place. */
internal enum class CastLook { Idle, Connecting, Connected, Problem }

/** The look for [state], with [problem] true while the cast has told the user about a failure ([CastNotices.problem]). */
internal fun castLook(state: CastState, problem: Boolean): CastLook = when {
    state is CastState.Connected -> if (problem) CastLook.Problem else CastLook.Connected
    state is CastState.Connecting -> CastLook.Connecting
    state is CastState.Failed || problem -> CastLook.Problem
    else -> CastLook.Idle
}

/**
 * The Cast button, on Now Playing and in the mini-player: opens the Cast sheet. Nothing is drawn where
 * casting is not offered (a store build, a television, a phone without Google Play services), so no
 * screen has a dead button. Connected is the primary colour, connecting the same colour dimmed (and
 * named so for a screen reader), a problem the error colour.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val support = remember { Cast.support(context) }
    if (!support.available) return
    val state by support.state.collectAsStateWithLifecycle()
    val problem by CastNotices.problem.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val look = castLook(state, problem != null)
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            CastIcon,
            contentDescription = when (val now = state) {
                is CastState.Connected -> L.castCastingTo(now.name)
                is CastState.Connecting -> L.castConnecting(now.name)
                else -> L.castTitle
            },
            tint = when (look) {
                CastLook.Connected -> MaterialTheme.colorScheme.primary
                CastLook.Connecting -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                CastLook.Problem -> MaterialTheme.colorScheme.error
                CastLook.Idle -> LocalContentColor.current
            },
        )
    }
    if (open) CastSheet(support) { open = false }
}

/** Whether the mini-player has room for the Cast button: a phone wide enough, or any width while a cast is on or being made. */
internal fun castFitsMini(widthDp: Int, state: CastState): Boolean =
    widthDp >= MINI_CAST_MIN_WIDTH_DP || state is CastState.Connected || state is CastState.Connecting

/** The mini-player's row has the cover, the title, three transport buttons and this one: below this width the title would be squeezed out. */
internal const val MINI_CAST_MIN_WIDTH_DP = 400

/**
 * The Cast button as the mini-player has it: phones only (a television has no Cast and is left out), and
 * only where there is room ([castFitsMini]).
 */
@Composable
fun MiniCastButton(modifier: Modifier = Modifier) {
    if (com.tmplayer.music.ui.tv.LocalTv.current) return
    val context = LocalContext.current
    val support = remember { Cast.support(context) }
    if (!support.available) return
    val state by support.state.collectAsStateWithLifecycle()
    if (!castFitsMini(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp, state)) return
    CastButton(modifier)
}

/** True while a cast is on (connected), for screens that change what they offer then. Always false in a store build and on a television. */
@Composable
fun rememberCasting(): Boolean {
    val context = LocalContext.current
    val support = remember { Cast.support(context) }
    val state by support.state.collectAsStateWithLifecycle()
    return state is CastState.Connected
}

/**
 * The Cast sheet: the devices found on the Wi-Fi while it is open (looking stops when it closes), the
 * state of the connection, and, once connected, the device's volume and Stop casting. It says what
 * is going on in words: no Wi-Fi, no devices yet, a failed connection, a track that was left out.
 */
@Composable
internal fun CastSheet(support: CastSupport, onDismiss: () -> Unit) {
    DisposableEffect(support) {
        support.startDiscovery()
        onDispose { support.stopDiscovery() }
    }
    val devices by support.devices.collectAsStateWithLifecycle()
    val state by support.state.collectAsStateWithLifecycle()
    val volume by support.volume.collectAsStateWithLifecycle()
    val onWifi by support.onWifi.collectAsStateWithLifecycle()
    val notice by CastNotices.last.collectAsStateWithLifecycle()
    val problem by CastNotices.problem.collectAsStateWithLifecycle()
    val sendLogs = com.tmplayer.music.diagnostics.rememberSendLogs()
    // Seen once, then let go: the button stops being red when the sheet is closed.
    DisposableEffect(Unit) { onDispose { CastNotices.dismissProblem() } }
    TvSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(L.castTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            val connected = state as? CastState.Connected
            if (connected != null) {
                Text(
                    L.castCastingTo(connected.name),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                if (!volume.fixed) {
                    val label = L.castVolume(connected.name)
                    Slider(
                        value = volume.level.toFloat(),
                        onValueChange = { support.setVolume(it.toDouble()) },
                        modifier = Modifier.padding(horizontal = 20.dp).semantics { contentDescription = label },
                    )
                }
                TextButton(
                    onClick = {
                        support.endSession()
                        onDismiss()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp).tvFocusRing(),
                ) { Text(L.castDisconnect) }
            } else {
                if (!onWifi) {
                    Note(L.castNoWifi)
                } else {
                    for (device in devices) DeviceLine(device, enabled = state !is CastState.Connecting) { support.connect(device.id) }
                    if (devices.isEmpty()) Note(if (state is CastState.Connecting) L.castConnecting((state as CastState.Connecting).name) else L.castEmpty)
                }
                (state as? CastState.Failed)?.let { Note(L.castConnectFailed(it.name), error = true) }
                (state as? CastState.Connecting)?.let { if (devices.isNotEmpty()) Note(L.castConnecting(it.name)) }
            }
            problem?.let { Note(it, error = true) }
            if (problem != null || state is CastState.Failed) {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { support.retry() }, enabled = state !is CastState.Connecting, modifier = Modifier.tvFocusRing()) { Text(L.commonRetry) }
                    TextButton(onClick = sendLogs, modifier = Modifier.tvFocusRing()) { Text(L.logsSend) }
                }
            }
            // The failure above is the news; the notice repeats it otherwise.
            if (problem == null) notice?.let { Note(it) }
            Text(
                L.castSentNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun DeviceLine(device: CastDeviceInfo, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).tvFocusRing().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(CastIcon, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (device.model.isNotBlank()) {
                Text(device.model, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (device.connecting) Text(L.castConnecting(device.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/** Settings: one row saying whether this version of the app can cast, and what it does. Not drawn on a television. */
@Composable
fun CastSettingsRow() {
    val context = LocalContext.current
    if (com.tmplayer.music.ui.tv.isTv(context)) return
    val support = remember { Cast.support(context) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(L.castSettingsTitle, style = MaterialTheme.typography.bodyLarge)
        Text(
            when (support.availability) {
                CastAvailability.Yes -> L.castSettingsAvailable
                CastAvailability.NotInThisBuild -> L.castSettingsUnavailable
                CastAvailability.NoPlayServices -> L.castSettingsNoServices
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
