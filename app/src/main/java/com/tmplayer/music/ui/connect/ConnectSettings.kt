package com.tmplayer.music.ui.connect

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.tmplayer.music.connect.PairLink
import com.tmplayer.music.connect.PairingOffer
import com.tmplayer.music.ui.auth.QrCode
import com.tmplayer.music.ui.auth.qrMatrix
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.connect.Connect
import com.tmplayer.music.connect.ConnectErrors
import com.tmplayer.music.connect.FoundTv
import com.tmplayer.music.connect.HostStatus
import com.tmplayer.music.connect.Paired
import com.tmplayer.music.connect.PairResult
import com.tmplayer.music.connect.PhoneRemote
import com.tmplayer.music.connect.TvConnect
import com.tmplayer.music.connect.TvDiscovery
import com.tmplayer.music.i18n.L
import com.tmplayer.music.ui.library.SwitchLine
import com.tmplayer.music.ui.tv.LocalTv
import com.tmplayer.music.ui.tv.requestFocusSoon
import com.tmplayer.music.ui.tv.tvAutoFocus
import com.tmplayer.music.ui.tv.tvFocusRing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings, Remote control. On a TV: the switch (on by default), what the server is doing, Pair a
 * phone (the code on the screen) and the paired phones, each with Forget. On a phone: the paired
 * TVs, Pair a TV and the remote itself. Nothing in a store build.
 */
@Composable
fun ConnectSettingsSection() {
    if (!Connect.available || !Connect.isReady) return
    if (LocalTv.current) TvSection() else PhoneSection()
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

// ---- The TV: switch, status, pair, paired phones.

@Composable
private fun TvSection() {
    val enabled by Connect.enabled.collectAsStateWithLifecycle()
    val status by TvConnect.status.collectAsStateWithLifecycle()
    val phones by Connect.phones.list.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pairing by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<Paired?>(null) }
    val pairFocus = remember { FocusRequester() }

    Title(L.connectTvTitle)
    SwitchLine(L.connectTvSwitch, L.connectTvSwitchDetail, enabled) { Connect.setEnabled(it) }
    Note(
        when (val s = status) {
            is HostStatus.Listening -> L.connectTvStatusListening(s.address)
            HostStatus.NoNetwork -> L.connectTvStatusNoNetwork
            HostStatus.Failed -> L.connectTvStatusFailed
            HostStatus.Off -> L.connectTvStatusOff
        },
    )
    Button(
        onClick = { pairing = true },
        enabled = enabled && status is HostStatus.Listening,
        modifier = Modifier.padding(16.dp).focusRequester(pairFocus).tvFocusRing(),
    ) { Text(L.connectPairPhone) }

    Text(L.connectPhones, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
    if (phones.isEmpty()) Note(L.connectPhonesNone)
    for (phone in phones) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(phone.name.ifBlank { phone.id.take(8) }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(modifier = Modifier.tvFocusRing(), onClick = { forgetting = phone }) { Text(L.connectForget) }
        }
    }

    if (pairing) PairPhoneDialog(onDismiss = {
        pairing = false
        scope.launch { requestFocusSoon(pairFocus) }
    })
    forgetting?.let { phone ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(L.connectForgetTitle(phone.name.ifBlank { phone.id.take(8) })) },
            text = { Text(L.connectForgetBody) },
            confirmButton = {
                TextButton(modifier = Modifier.tvFocusRing(), onClick = {
                    TvConnect.forget(phone.id)
                    forgetting = null
                    // The row that had focus is gone: the Pair button takes it, so the D-pad is never left nowhere.
                    scope.launch { requestFocusSoon(pairFocus) }
                }) { Text(L.connectForgetConfirm) }
            },
            dismissButton = { TextButton(modifier = Modifier.tvAutoFocus().tvFocusRing(), onClick = { forgetting = null }) { Text(L.commonCancel) } },
        )
    }
}

/** The code on the TV's screen. It changes every two minutes and after a lock; closes with a "paired" line once a phone used it. */
@Composable
private fun PairPhoneDialog(onDismiss: () -> Unit) {
    val offer by TvConnect.gate.offer.collectAsStateWithLifecycle()
    val lockedUntil by TvConnect.gate.lockedUntil.collectAsStateWithLifecycle()
    val phones by Connect.phones.list.collectAsStateWithLifecycle()
    val status by TvConnect.status.collectAsStateWithLifecycle()
    val before = remember { phones.map { it.id }.toSet() }
    val newcomer = phones.firstOrNull { it.id !in before }
    var locked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { TvConnect.openPairing() }
    DisposableEffect(Unit) { onDispose { TvConnect.closePairing() } }
    // A new code when this one runs out, while the dialog is up; nothing ticks otherwise.
    LaunchedEffect(offer?.expiresAt) {
        val end = offer?.expiresAt ?: return@LaunchedEffect
        delay((end - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        TvConnect.openPairing()
    }
    LaunchedEffect(lockedUntil) {
        val wait = lockedUntil - SystemClock.elapsedRealtime()
        if (wait > 0) {
            locked = true
            delay(wait)
            locked = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.connectPairTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (newcomer != null) {
                    Text(L.connectPairDone(newcomer.name.ifBlank { newcomer.id.take(8) }), style = MaterialTheme.typography.bodyLarge)
                } else {
                    // The picture is the primary way: it carries a one time 128 bit token and the address, so nothing is typed.
                    val link = pairLinkText(status, offer)
                    if (link != null) {
                        Box(Modifier.size(200.dp).background(Color.White, RoundedCornerShape(12.dp)).align(Alignment.CenterHorizontally)) {
                            QrCode(remember(link) { qrMatrix(link) }, L.connectPairQrDescription)
                        }
                    }
                    Text(
                        offer?.code?.chunked(3)?.joinToString("  ") ?: "",
                        style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    Text(L.connectPairSteps, style = MaterialTheme.typography.bodyMedium)
                    Text(L.connectPairCodeNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (locked) Text(L.connectPairLocked, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.tvAutoFocus().tvFocusRing(), onClick = onDismiss) { Text(if (newcomer != null) L.commonOk else L.commonCancel) }
        },
    )
}

/** What the QR code says for the pairing on show; null while the server is not listening or no offer is open. */
private fun pairLinkText(status: HostStatus, offer: PairingOffer?): String? {
    val listening = status as? HostStatus.Listening ?: return null
    offer ?: return null
    return PairLink(listening.address, listening.port, Connect.deviceId, offer.token).text()
}

// ---- The phone: paired TVs, pair, open the remote.

@Composable
private fun PhoneSection() {
    val tvs by Connect.tvs.list.collectAsStateWithLifecycle()
    var pairing by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<Paired?>(null) }

    Title(L.connectPhoneTitle)
    Note(L.connectIntro)
    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(modifier = Modifier.tvFocusRing(), onClick = { pairing = true }) { Text(L.connectPairTv) }
        if (tvs.isNotEmpty()) TextButton(modifier = Modifier.tvFocusRing(), onClick = { remote = true }) { Text(L.connectOpenRemote) }
    }
    Text(L.connectTvs, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
    if (tvs.isEmpty()) Note(L.connectTvsNone)
    for (tv in tvs) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tv.name.ifBlank { tv.id.take(8) }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(modifier = Modifier.tvFocusRing(), onClick = { forgetting = tv }) { Text(L.connectForget) }
        }
    }

    if (pairing) PairTvDialog(onDismiss = { pairing = false }, onPaired = {
        pairing = false
        remote = true
    })
    if (remote) RemoteDialog(onDismiss = { remote = false })
    forgetting?.let { tv ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(L.connectForgetTvTitle(tv.name.ifBlank { tv.id.take(8) })) },
            text = { Text(L.connectForgetTvBody) },
            confirmButton = {
                TextButton(onClick = {
                    PhoneRemote.forget(tv.id)
                    forgetting = null
                }) { Text(L.connectForgetTvConfirm) }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text(L.commonCancel) } },
        )
    }
}

/** Pairs this phone with a TV: pick one found on the network (or type its address), type the code from its screen. */
@Composable
fun PairTvDialog(onDismiss: () -> Unit, onPaired: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val found by produceState(emptyList<FoundTv>()) { TvDiscovery.scan(context).collect { value = it } }
    var picked by remember { mutableStateOf<FoundTv?>(null) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // One TV on the network is the one: no extra tap.
    val target = picked ?: found.singleOrNull()
    val typed = if (target == null) PhoneRemote.parseAddress(address) else null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(L.connectPairTv) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (found.isEmpty()) Text(L.connectFoundNone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (tv in found) {
                    TextButton(onClick = { picked = tv }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            (if (tv.id == target?.id) "• " else "") + tv.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (found.isEmpty()) {
                    OutlinedTextField(
                        value = address, onValueChange = { address = it.take(80) }, singleLine = true,
                        label = { Text(L.connectAddress) }, supportingText = { Text(L.connectAddressHint) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                OutlinedTextField(
                    value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, singleLine = true,
                    label = { Text(L.connectCode) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && code.length == 6 && (target != null || typed != null),
                onClick = {
                    val place = target?.let { it.host to it.port } ?: typed ?: return@TextButton
                    busy = true
                    error = null
                    scope.launch {
                        when (val r = PhoneRemote.pair(place.first, place.second, code)) {
                            is PairResult.Success -> onPaired()
                            is PairResult.Refused -> error = refusal(r)
                            is PairResult.Unreachable -> error = L.connectErrUnreachable
                        }
                        busy = false
                    }
                },
            ) { Text(if (busy) L.connectPairing else L.connectPairGo) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(L.commonCancel) } },
    )
}

/**
 * The TV's QR code, scanned with the phone's camera app, opens this: one question (never a silent
 * pairing), then the pairing runs with the code's token and no typing. Nothing is shown otherwise.
 */
@Composable
fun PairLinkHost() {
    val link by PhoneRemote.pendingLink.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val pending = link ?: return
    AlertDialog(
        onDismissRequest = {
            if (!busy) {
                PhoneRemote.dismissLink()
                error = null
                done = false
            }
        },
        title = { Text(L.connectPairLinkTitle) },
        text = {
            Text(
                when {
                    done -> L.connectPairLinkDone
                    error != null -> error.orEmpty()
                    else -> L.connectPairLinkBody(pending.host)
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (done) {
                        PhoneRemote.dismissLink()
                        done = false
                        return@TextButton
                    }
                    busy = true
                    error = null
                    scope.launch {
                        when (val r = PhoneRemote.pairLink(pending)) {
                            is PairResult.Success -> done = true
                            is PairResult.Refused -> error = refusal(r)
                            is PairResult.Unreachable -> error = L.connectErrUnreachable
                        }
                        busy = false
                    }
                },
            ) { Text(if (busy) L.connectPairing else if (done) L.commonOk else L.connectPairGo) }
        },
        dismissButton = {
            if (!done) {
                TextButton(enabled = !busy, onClick = {
                    PhoneRemote.dismissLink()
                    error = null
                }) { Text(L.commonCancel) }
            }
        },
    )
}

private fun refusal(r: PairResult.Refused): String = when (r.code) {
    ConnectErrors.BAD_CODE -> L.connectErrBadCode(r.left)
    ConnectErrors.LOCKED -> L.connectErrLocked(r.retrySeconds)
    ConnectErrors.CLOSED -> L.connectErrClosed
    ConnectErrors.VERSION -> L.connectErrVersion
    else -> L.connectErrOther
}
