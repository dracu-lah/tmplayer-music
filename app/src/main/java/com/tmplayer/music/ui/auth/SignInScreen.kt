// Adapted from TMPlayer (GPL-3.0), app/src/main/java/com/tmplayer/ui/auth/LoginScreen.kt.
package com.tmplayer.music.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.tmplayer.music.data.AuthState
import com.tmplayer.music.data.SignInMethod
import com.tmplayer.music.data.SignInWords
import com.tmplayer.music.data.Td
import com.tmplayer.music.i18n.L

/**
 * The sign in, one step per screen, each with one main action: choose a way in, then either scan
 * a QR code, or the number ([PhoneStep]), the code ([CodeStep]) and the two-step password; an
 * email address and its code for accounts Telegram signs in by email. Every request goes through
 * [SignInRunner], so a rotation or a double tap never sends a second one. Password recovery and
 * the account reset are not offered yet: Start over is the way out of the password step.
 */
@Composable
fun SignInScreen(auth: AuthState) {
    // "Wrong number? Edit" on the code step. TDLib takes a different number while it waits for a
    // code, so this is a change of screen and nothing more.
    var changingNumber by rememberSaveable { mutableStateOf(false) }
    val isCode = auth is AuthState.Code
    LaunchedEffect(isCode) { if (!isCode) changingNumber = false }
    // A new number taken is the end of changing it, even when Telegram's answer is the same code
    // step as before. Counted rather than signalled, so a rotation in between does not miss it.
    val success by SignInRunner.lastSuccess.collectAsStateWithLifecycle()
    var seenSuccess by rememberSaveable { mutableLongStateOf(SignInRunner.lastSuccess.value?.id ?: 0L) }
    LaunchedEffect(success) {
        val done = success ?: return@LaunchedEffect
        if (done.id == seenSuccess) return@LaunchedEffect
        seenSuccess = done.id
        if (done.task == SignInTask.Phone) changingNumber = false
    }
    val startOver = { SignInRunner.fire { Td.restartSignIn() } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (auth) {
            AuthState.Connecting -> ConnectingStep()
            AuthState.ChooseMethod -> ChooseStep()
            is AuthState.Qr -> QrStep(auth)
            is AuthState.Phone -> PhoneStep(
                state = auth,
                initial = null,
                onBack = { SignInRunner.fire { Td.cancelPhoneEntry() } },
                onUseQr = { SignInRunner.fire { Td.chooseSignInMethod(SignInMethod.Qr) } },
            )
            is AuthState.Code ->
                if (changingNumber) {
                    PhoneStep(
                        state = auth,
                        initial = auth.phoneNumber,
                        onBack = { changingNumber = false },
                        onUseQr = { SignInRunner.fire { Td.restartSignIn(SignInMethod.Qr) } },
                    )
                } else {
                    CodeStep(auth, onChangeNumber = { changingNumber = true }, onStartOver = startOver)
                }
            is AuthState.Password -> PasswordStep(auth, startOver)
            is AuthState.EmailAddress -> EmailAddressStep(auth, startOver)
            is AuthState.EmailCode -> EmailCodeStep(auth, startOver)
            // Not reachable yet: this app never starts a password recovery.
            is AuthState.PasswordRecovery -> ConnectingStep()
            is AuthState.Failed -> FailedStep(auth, startOver)
            AuthState.Ready -> Unit
        }
    }
}

@Composable
private fun ConnectingStep() {
    val stalled by Td.signInStalled.collectAsStateWithLifecycle()
    SignInFrame(onBack = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            StepBody(L.loginConnecting)
        }
        if (stalled) {
            StepBody(L.loginStalled)
            Button(onClick = { SignInRunner.fire { Td.retryConnecting() } }, modifier = Modifier.fillMaxWidth()) {
                Text(L.commonRetry)
            }
        }
    }
}

/**
 * The first step. The number comes first: on a phone the Telegram app is most often on this same
 * phone, and a QR code needs a second device to scan it with.
 */
@Composable
private fun ChooseStep() {
    SignInFrame(onBack = null) {
        Text(L.appName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        StepTitle(L.loginChooseTitle)
        StepBody(L.loginChooseBody)
        Button(
            onClick = { SignInRunner.fire { Td.chooseSignInMethod(SignInMethod.Phone) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(L.loginChoosePhone) }
        OutlinedButton(
            onClick = { SignInRunner.fire { Td.chooseSignInMethod(SignInMethod.Qr) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(L.loginChooseQr) }
    }
}

@Composable
private fun QrStep(auth: AuthState.Qr) {
    val back = { SignInRunner.fire { Td.restartSignIn() } }
    BackHandler(onBack = back)
    SignInFrame(onBack = back) {
        StepTitle(L.loginQrTitle)
        QrStepLine(1, L.signinQrStepOpen)
        QrStepLine(2, L.signinQrStepDevices)
        QrStepLine(3, L.signinQrStepLink)
        val matrix = remember(auth.link) { runCatching { qrMatrix(auth.link) }.getOrNull() }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (matrix != null) {
                QrCode(matrix)
            } else {
                CircularProgressIndicator()
            }
        }
        StepBody(L.signinQrNote)
        OutlinedButton(onClick = { SignInRunner.fire { Td.refreshQrCode() } }, modifier = Modifier.fillMaxWidth()) {
            Text(L.loginQrRefresh)
        }
        Button(
            onClick = { SignInRunner.fire { Td.chooseSignInMethod(SignInMethod.Phone) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(L.signinUsePhone) }
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
 * Black on white whatever the theme, with a quiet zone, so every phone camera reads it. The only
 * fixed colours in the sign in: a scanner needs this contrast, not the theme's.
 */
@Composable
private fun QrCode(matrix: BitMatrix) {
    val description = L.signinQrDescription
    Box(
        Modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.large)
            .background(Color.White)
            .padding(16.dp)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.width / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** Two-step verification: the password with show and hide, the account's own hint. */
@Composable
private fun PasswordStep(auth: AuthState.Password, onStartOver: () -> Unit) {
    // Not saved across a rotation on purpose: a password is not written into the saved state.
    var password by remember { mutableStateOf("") }
    var shown by rememberSaveable { mutableStateOf(false) }
    val busyTask by SignInRunner.busy.collectAsStateWithLifecycle()
    val failure by SignInRunner.failure.collectAsStateWithLifecycle()
    val floodLeft = rememberFloodWait()
    val field = remember { FocusRequester() }
    val send = {
        if (password.isNotEmpty() && floodLeft <= 0) {
            val typed = password
            SignInRunner.run(SignInTask.Password) { Td.submitPassword(typed) }
        }
    }
    BackHandler(onBack = onStartOver)
    SignInFrame(onBack = onStartOver) {
        StepTitle(L.loginPasswordTitle)
        StepBody(L.signinPasswordBlurb)
        if (auth.hint.isNotBlank()) StepBody(L.loginPasswordHint(auth.hint))
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it.take(MAX_PASSWORD)
                SignInRunner.clearFailure()
            },
            label = { Text(L.loginPasswordLabel) },
            singleLine = true,
            isError = auth.wrong && failure?.task == SignInTask.Password,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { shown = !shown }) {
                    Text(if (shown) L.signinHidePassword else L.signinShowPassword, maxLines = 1)
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (shown) KeyboardType.Text else KeyboardType.Password,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { send() }),
            modifier = Modifier.fillMaxWidth().focusRequester(field),
        )
        StepError(failure?.takeIf { it.task == SignInTask.Password }?.message)
        MainAction(
            label = if (floodLeft > 0) L.signinTryAgainIn(SignInWords.clock(floodLeft)) else L.signinSignIn,
            busyLabel = L.signinSigningIn,
            busy = busyTask == SignInTask.Password,
            enabled = password.isNotEmpty() && floodLeft <= 0 && busyTask == null,
            onClick = { send() },
        )
        TextButton(onClick = onStartOver, enabled = busyTask == null, modifier = Modifier.fillMaxWidth()) {
            Text(L.commonStartOver)
        }
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
}

@Composable
private fun EmailAddressStep(auth: AuthState.EmailAddress, onStartOver: () -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    val busyTask by SignInRunner.busy.collectAsStateWithLifecycle()
    val failure by SignInRunner.failure.collectAsStateWithLifecycle()
    val ok = '@' in address && address.trim().length >= 3
    val send = {
        if (ok) {
            val typed = address
            SignInRunner.run(SignInTask.EmailAddress) { Td.submitEmailAddress(typed) }
        }
    }
    BackHandler(onBack = onStartOver)
    SignInFrame(onBack = onStartOver) {
        StepTitle(L.loginEmailAddressTitle)
        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it.take(MAX_EMAIL)
                SignInRunner.clearFailure()
            },
            label = { Text(L.loginEmailAddressLabel) },
            singleLine = true,
            isError = auth.wrong && failure?.task == SignInTask.EmailAddress,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { send() }),
            modifier = Modifier.fillMaxWidth(),
        )
        StepError(failure?.takeIf { it.task == SignInTask.EmailAddress }?.message)
        MainAction(
            label = L.commonContinue,
            busyLabel = L.signinSendingCode,
            busy = busyTask == SignInTask.EmailAddress,
            enabled = ok && busyTask == null,
            onClick = { send() },
        )
        TextButton(onClick = onStartOver, enabled = busyTask == null, modifier = Modifier.fillMaxWidth()) {
            Text(L.commonStartOver)
        }
    }
}

@Composable
private fun EmailCodeStep(auth: AuthState.EmailCode, onStartOver: () -> Unit) {
    var code by rememberSaveable(auth.pattern) { mutableStateOf("") }
    var submitted by rememberSaveable(auth.pattern) { mutableStateOf<String?>(null) }
    val busyTask by SignInRunner.busy.collectAsStateWithLifecycle()
    val failure by SignInRunner.failure.collectAsStateWithLifecycle()
    val send = {
        if (code.isNotEmpty()) {
            val typed = code
            if (SignInRunner.run(SignInTask.EmailCode) { Td.submitEmailCode(typed) }) submitted = typed
        }
    }
    BackHandler(onBack = onStartOver)
    SignInFrame(onBack = onStartOver) {
        StepTitle(L.loginEmailCodeTitle)
        StepBody(L.loginEmailCodeBody(auth.pattern))
        OutlinedTextField(
            value = code,
            onValueChange = {
                code = it.filter(Char::isLetterOrDigit).take(32)
                SignInRunner.clearFailure()
            },
            label = { Text(L.loginCodeLabel) },
            singleLine = true,
            isError = auth.wrong && code == submitted,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { send() }),
            modifier = Modifier.fillMaxWidth(),
        )
        StepError(failure?.takeIf { it.task == SignInTask.EmailCode }?.message)
        MainAction(
            label = L.signinSignIn,
            busyLabel = L.signinSigningIn,
            busy = busyTask == SignInTask.EmailCode,
            enabled = code.isNotEmpty() && code != submitted && busyTask == null,
            onClick = { send() },
        )
        TextButton(onClick = onStartOver, enabled = busyTask == null, modifier = Modifier.fillMaxWidth()) {
            Text(L.commonStartOver)
        }
    }
}

/**
 * A failure, with Try again; when Telegram said how long to wait, the wait counts down and Try
 * again appears once it is over, rather than failing the same way at once. Telegram refusing to
 * send a code without Premium leads straight to the QR code, the one way round it.
 */
@Composable
private fun FailedStep(auth: AuthState.Failed, onStartOver: () -> Unit) {
    val left = rememberCountdown(auth.waitSeconds, auth)
    val qr = SignInWords.offersQr(auth)
    SignInFrame(onBack = null) {
        StepTitle(L.loginFailedTitle)
        StepBody(auth.message)
        when {
            left > 0 -> StepBody(L.signinTryAgainIn(SignInWords.clock(left)))
            qr -> {
                Button(
                    onClick = { SignInRunner.fire { Td.restartSignIn(SignInMethod.Qr) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(L.loginChooseQr) }
                TextButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) { Text(L.commonStartOver) }
            }
            else -> Button(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) { Text(L.commonRetry) }
        }
    }
}

private const val MAX_PASSWORD = 256
private const val MAX_EMAIL = 254
