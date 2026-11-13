package com.tmplayer.music.desktop.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.connect.PairLink
import com.tmplayer.music.connect.PairingOffer
import com.tmplayer.music.connect.Paired
import com.tmplayer.music.desktop.connect.ConnectHostStatus
import com.tmplayer.music.desktop.connect.DesktopConnect
import com.tmplayer.music.desktop.ui.auth.QrCode
import com.tmplayer.music.desktop.ui.auth.qrMatrix
import com.tmplayer.music.desktop.ui.common.autoFocus
import com.tmplayer.music.i18n.L
import kotlinx.coroutines.delay

/**
 * Settings, Remote control, on the desktop: the switch (off by default), what the server is doing,
 * Pair a phone (the QR code and the code on this screen) and the paired phones, each with Forget.
 * Nothing listens while the switch is off, and the server only ever listens on this computer's own
 * private address.
 */
@Composable
fun ConnectSettingsSection() {
    val enabled by DesktopConnect.enabled.collectAsState()
    val phones by DesktopConnect.phones.list.collectAsState()
    var pairing by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<Paired?>(null) }

    // A new address (Wi-Fi changed, a cable plugged) is picked up whenever the section is shown.
    LaunchedEffect(enabled) { if (enabled) DesktopConnect.host.refresh() }

    SettingsSection(L.connectDesktopTitle) {
        SwitchLine(L.connectDesktopSwitch, L.connectDesktopSwitchDetail, enabled) { DesktopConnect.setEnabled(it) }
        if (enabled) {
            val status by DesktopConnect.host.status.collectAsState()
            SettingsHint(
                when (val s = status) {
                    is ConnectHostStatus.Listening -> L.connectTvStatusListening(s.address)
                    ConnectHostStatus.NoNetwork -> L.connectTvStatusNoNetwork
                    ConnectHostStatus.Failed -> L.connectTvStatusFailed
                    ConnectHostStatus.Off -> L.connectDesktopStatusOff
                },
            )
            Button(
                onClick = { pairing = true },
                enabled = status is ConnectHostStatus.Listening,
                modifier = Modifier.padding(24.dp, 12.dp),
            ) { Text(L.connectPairPhone) }
        }
        Text(L.connectPhones, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 24.dp, top = 4.dp))
        if (phones.isEmpty()) SettingsHint(L.connectPhonesNone)
        for (phone in phones) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(phone.name.ifBlank { phone.id.take(8) }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { forgetting = phone }) { Text(L.connectForget) }
            }
        }
    }

    if (pairing) PairPhoneDialog(onDismiss = { pairing = false })
    forgetting?.let { phone ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(L.connectForgetTitle(phone.name.ifBlank { phone.id.take(8) })) },
            text = { Text(L.connectForgetBody) },
            confirmButton = {
                TextButton(onClick = {
                    DesktopConnect.host.forget(phone.id)
                    forgetting = null
                }) { Text(L.connectForgetConfirm) }
            },
            dismissButton = { TextButton(modifier = Modifier.autoFocus(), onClick = { forgetting = null }) { Text(L.commonCancel) } },
        )
    }
}

/** What the QR code says for the pairing on show; null while the server is not listening or no offer is open. */
internal fun pairLinkText(status: ConnectHostStatus, deviceId: String, offer: PairingOffer?, deviceName: String = ""): String? {
    val listening = status as? ConnectHostStatus.Listening ?: return null
    offer ?: return null
    return PairLink(listening.address, listening.port, deviceId, offer.token, offer.fingerprint, deviceName).text()
}

/** The picture and the code on this screen. The code changes every two minutes and after a lock; the dialog says "paired" once a phone used it. */
@Composable
private fun PairPhoneDialog(onDismiss: () -> Unit) {
    val host = DesktopConnect.host
    val offer by host.gate.offer.collectAsState()
    val lockedUntil by host.gate.lockedUntil.collectAsState()
    val status by host.status.collectAsState()
    val phones by DesktopConnect.phones.list.collectAsState()
    val sas by host.sasRequest.collectAsState()
    val before = remember { phones.map { it.id }.toSet() }
    val newcomer = phones.firstOrNull { it.id !in before }
    var locked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { host.openPairing() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { host.closePairing() } }
    // A new code when this one runs out, while the dialog is up (and no phone waits to be confirmed); nothing ticks otherwise.
    LaunchedEffect(offer?.expiresAt, sas == null) {
        val end = offer?.expiresAt ?: return@LaunchedEffect
        if (sas != null) return@LaunchedEffect
        delay((end - System.nanoTime() / 1_000_000).coerceAtLeast(0))
        host.openPairing()
    }
    // After a no (the code was spent), the next phone needs a new code; after a yes the "paired" line shows instead.
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(sas) {
        if (sas != null) {
            asked = true
        } else if (asked) {
            asked = false
            delay(400)
            if (DesktopConnect.phones.list.value.none { it.id !in before }) host.openPairing()
        }
    }

    // A phone gave the right code: nothing is stored until this screen's user says the phone shows the same number. Cancel is where the focus starts.
    sas?.let { request ->
        AlertDialog(
            onDismissRequest = { request.decline() },
            title = { Text(L.connectSasTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(L.connectSasHostBody(request.phoneName.ifBlank { request.phoneId.take(8) }), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        request.sas,
                        style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Text(L.connectSasNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { request.accept() }) { Text(L.commonOk) } },
            dismissButton = { TextButton(modifier = Modifier.autoFocus(), onClick = { request.decline() }) { Text(L.commonCancel) } },
        )
        return
    }
    LaunchedEffect(lockedUntil) {
        val wait = lockedUntil - System.nanoTime() / 1_000_000
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (newcomer != null) {
                    Text(L.connectPairDone(newcomer.name.ifBlank { newcomer.id.take(8) }), style = MaterialTheme.typography.bodyLarge)
                } else {
                    // The picture is the primary way: a one time 128 bit token and the address, nothing typed.
                    val link = pairLinkText(status, DesktopConnect.deviceId, offer, DesktopConnect.computerName())
                    if (link != null) {
                        Box(Modifier.size(200.dp).background(Color.White, RoundedCornerShape(12.dp)).align(Alignment.CenterHorizontally)) {
                            QrCode(remember(link) { qrMatrix(link) }, L.connectPairQrDescription)
                        }
                    }
                    Text(
                        offer?.code?.chunked(3)?.joinToString("  ") ?: "",
                        style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Text(L.connectDesktopPairSteps, style = MaterialTheme.typography.bodyMedium)
                    Text(L.connectPairCodeNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (locked) Text(L.connectPairLocked, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.autoFocus(), onClick = onDismiss) { Text(if (newcomer != null) L.commonOk else L.commonCancel) }
        },
    )
}
