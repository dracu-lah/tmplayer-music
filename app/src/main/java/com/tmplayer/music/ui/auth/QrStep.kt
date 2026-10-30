// Adapted from TMPlayer (GPL-3.0): app/src/main/java/com/tmplayer/ui/auth/LoginScreen.kt (QrPane)
// and ui/src/shared/kotlin/com/tmplayer/ui/auth/QrScanned.kt (the veils, the life cue, the hold).
package com.tmplayer.music.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.tmplayer.music.data.AuthState
import com.tmplayer.music.data.QrClock
import com.tmplayer.music.data.QrExpiry
import com.tmplayer.music.data.QrPhase
import com.tmplayer.music.data.QrRefreshPolicy
import com.tmplayer.music.data.QrScanHold
import com.tmplayer.music.data.SignInMethod
import com.tmplayer.music.data.Td
import com.tmplayer.music.diagnostics.SendLogsButton
import com.tmplayer.music.i18n.L
import com.tmplayer.music.ui.common.MusicIcons
import kotlinx.coroutines.delay

/**
 * The QR code's state that outlives the pane: each new code starts a new TDLib client, and the
 * pane leaves the screen while it does. Touched from the main thread only.
 */
internal object QrCodes {
    val policy = QrRefreshPolicy()

    /**
     * The link a refresh is replacing, while the new code is on its way. The pane keeps the old
     * code up under a "Getting a new code" veil meanwhile, rather than flick to Connecting.
     */
    var renewing by mutableStateOf<String?>(null)

    /** Asks for a new code; [manual] for a press, which always goes and restarts the count. */
    fun refresh(link: String, manual: Boolean) {
        if (manual) {
            policy.onManualRefresh()
        } else if (policy.onExpired(link) != QrExpiry.Refresh) {
            return
        }
        renewing = link
        SignInRunner.fire { Td.refreshQrCode() }
    }

    fun reset() {
        policy.reset()
        renewing = null
    }
}

/**
 * The sign in state to draw: [auth], except for [QrScanHold.SCANNED_HOLD_MS] after a QR code is
 * scanned, when the same code stays up with a tick ([QrScanHold]).
 */
@Composable
fun rememberScanHold(auth: AuthState): AuthState {
    // A plain object written while composing: the decision has to be in the first frame after
    // the scan, and only the hold's end ([ended]) is state, set later by the timer.
    val hold = remember { QrScanHold() }
    var ended by remember { mutableIntStateOf(0) }
    hold.observe(auth)
    val id = hold.holdId
    LaunchedEffect(id) {
        if (hold.held == null) return@LaunchedEffect
        delay(QrScanHold.SCANNED_HOLD_MS)
        ended = id
    }
    val signedIn = auth is AuthState.Ready
    LaunchedEffect(signedIn) { if (signedIn) QrCodes.reset() }
    return hold.shown(auth, ended)
}

/** Where the code on screen is in its life ([QrClock]). */
@Stable
class QrLife(val phase: QrPhase, val remaining: Float)

/**
 * The life of [link] on screen, ticking twice a second while [running] and the app is in the
 * foreground, and stopping by itself once the code is stale, when [onExpired] is told once. The
 * age is wall clock, so a spell in the background is counted when the app comes back.
 */
@Composable
private fun rememberQrLife(link: String, running: Boolean, onExpired: (String) -> Unit): QrLife {
    val policy = QrCodes.policy
    val arrived = remember(link) { policy.onLink(link, System.currentTimeMillis()) }
    var now by remember(link) { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val expired by rememberUpdatedState(onExpired)
    LaunchedEffect(link, running) {
        if (!running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                if (now - arrived >= QrClock.STALE_MS) {
                    expired(link)
                    break
                }
                delay(QR_TICK_MS)
            }
        }
    }
    val lifetime = policy.lifetime()
    val age = (now - arrived).coerceAtLeast(0)
    return QrLife(QrClock.phase(age, lifetime), QrClock.remaining(age, lifetime))
}

/**
 * Scan a QR code with Telegram on another phone. The code renews itself; one TDLib could not renew
 * is veiled as expired and a new one asked for, a few times at most ([QrRefreshPolicy]); Get a new
 * code is always there. Once scanned the code stays up with a tick ([rememberScanHold]) and nothing
 * here can be pressed, so a stray Back cannot throw away the sign in that just went through.
 * [renewing] draws the old code under a veil while its replacement is on its way.
 */
@Composable
internal fun QrStep(auth: AuthState.Qr, renewing: Boolean = false) {
    val scanned = auth.scanned
    val idle = !scanned && !renewing
    val back = {
        if (idle) {
            QrCodes.reset()
            SignInRunner.fire { Td.restartSignIn() }
        }
    }
    BackHandler(onBack = back)
    val life = rememberQrLife(auth.link, running = idle) { QrCodes.refresh(it, manual = false) }
    val refresh = { QrCodes.refresh(auth.link, manual = true) }
    val stuck = idle && life.phase == QrPhase.Expired && QrCodes.policy.exhausted
    SignInFrame(onBack = if (idle) back else null) {
        StepTitle(L.loginQrTitle)
        QrStepLine(1, L.signinQrStepOpen)
        QrStepLine(2, L.signinQrStepDevices)
        QrStepLine(3, L.signinQrStepLink)
        QrPlate(auth.link, scanned = scanned, renewing = renewing, life = life, onRefresh = refresh)
        if (stuck) {
            StepError(L.signinQrStuck)
            SendLogsButton(Modifier.fillMaxWidth())
        }
        StepBody(L.signinQrNote)
        QrRefreshButton(onRefresh = refresh, enabled = idle, busy = renewing)
        Button(
            onClick = {
                QrCodes.reset()
                SignInRunner.fire { Td.chooseSignInMethod(SignInMethod.Phone) }
            },
            enabled = idle,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(L.signinUsePhone) }
    }
}

/**
 * The code on its white plate with the life bar under it, and over it a veil when it was scanned,
 * has expired, or is being replaced. Stateless, so the previews draw every state.
 */
@Composable
internal fun QrPlate(link: String, scanned: Boolean, renewing: Boolean, life: QrLife, onRefresh: () -> Unit) {
    val matrix = remember(link) { runCatching { qrMatrix(link) }.getOrNull() }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .widthIn(max = PLATE)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.large)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (matrix != null) {
                QrCode(matrix)
            } else {
                // A link no QR code can hold: never drawn as a code that does not sign in.
                CircularProgressIndicator(color = INK)
            }
            QrScannedVeil(scanned, Modifier.matchParentSize())
            QrRenewingVeil(renewing && !scanned, Modifier.matchParentSize())
            QrExpiredVeil(
                life.phase == QrPhase.Expired && !scanned && !renewing || matrix == null && !scanned && !renewing,
                onRefresh,
                Modifier.matchParentSize(),
            )
        }
        if (!scanned) QrLifeCue(life, renewing, Modifier.widthIn(max = PLATE).fillMaxWidth())
    }
}

@Composable
private fun QrStepLine(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .sizeIn(minWidth = 28.dp, minHeight = 28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun qrMatrix(link: String): BitMatrix =
    QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))

/**
 * Black on white whatever the theme, inset from the plate's edge for the quiet zone a scanner
 * needs to find the corners. The only fixed colours in the sign in: a camera needs this contrast.
 */
@Composable
private fun QrCode(matrix: BitMatrix) {
    val description = L.signinQrDescription
    Canvas(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .semantics { contentDescription = description },
    ) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** The code fades under a white veil with a tick: the phone got it and the sign in is going on. */
@Composable
private fun QrScannedVeil(scanned: Boolean, modifier: Modifier) {
    AnimatedVisibility(scanned, modifier = modifier, enter = fadeIn(tween(VEIL_MS)), exit = fadeOut(tween(VEIL_MS))) {
        Veil {
            Box(
                Modifier
                    .animateEnterExit(enter = scaleIn(tween(VEIL_MS), initialScale = 0.6f))
                    .size(56.dp)
                    .background(INK, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MusicIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Text(L.signinQrScanned, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = INK)
            Text(L.signinSigningIn, style = MaterialTheme.typography.bodyMedium, color = INK_MUTED)
        }
    }
}

/** While a new code is on its way: the old one veiled, a spinner, "Getting a new code". */
@Composable
private fun QrRenewingVeil(renewing: Boolean, modifier: Modifier) {
    AnimatedVisibility(renewing, modifier = modifier, enter = fadeIn(tween(VEIL_MS)), exit = fadeOut(tween(VEIL_MS))) {
        Veil {
            CircularProgressIndicator(color = INK, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            Text(L.signinQrRenewing, style = MaterialTheme.typography.titleMedium, color = INK)
        }
    }
}

/** A code that no longer signs anybody in, never left looking usable, with a button for a new one. */
@Composable
private fun QrExpiredVeil(expired: Boolean, onRefresh: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(expired, modifier = modifier, enter = fadeIn(tween(VEIL_MS)), exit = fadeOut(tween(VEIL_MS))) {
        Veil {
            Text(L.signinQrExpired, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = INK, textAlign = TextAlign.Center)
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = INK, contentColor = Color.White),
            ) {
                Icon(MusicIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(L.loginQrRefresh)
            }
        }
    }
}

/** The white veil over the plate, read out as it appears. Dark ink whatever the theme. */
@Composable
private fun Veil(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = 0.9f))
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

/**
 * A thin bar that drains over the code's life, and in its last seconds a line saying a new code
 * is on its way, so a code that changes under the camera is expected rather than a surprise.
 */
@Composable
private fun QrLifeCue(life: QrLife, renewing: Boolean, modifier: Modifier) {
    val bar by animateFloatAsState(
        life.remaining,
        animationSpec = tween(QR_TICK_MS.toInt(), easing = LinearEasing),
        label = "qrLife",
    )
    val soon = renewing || life.phase != QrPhase.Fresh
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(if (renewing || life.phase == QrPhase.Renewing) 1f else bar)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(if (soon) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary),
            )
        }
        // Always laid out, so the pane does not jump when the line appears.
        Text(
            when {
                renewing -> L.signinQrRenewing
                life.phase == QrPhase.ExpiringSoon -> L.signinQrExpiresSoon
                life.phase == QrPhase.Renewing -> L.signinQrRenewing
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * "Get a new code", always on the pane. It spins for a moment after a press (or while [busy]), so
 * the press shows before TDLib answers, and a second press in that time does nothing.
 */
@Composable
private fun QrRefreshButton(onRefresh: () -> Unit, enabled: Boolean, busy: Boolean) {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            delay(REFRESH_SPIN_MS)
            pressed = false
        }
    }
    val spinning = pressed || busy
    OutlinedButton(
        onClick = {
            if (!spinning) {
                pressed = true
                onRefresh()
            }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (spinning) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        } else {
            Icon(MusicIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(L.loginQrRefresh)
    }
}

private val PLATE = 280.dp
private val INK = Color(0xFF111111)
private val INK_MUTED = Color(0xFF55555E)
private const val QR_TICK_MS = 500L
private const val REFRESH_SPIN_MS = 4_000L
private const val VEIL_MS = 220
