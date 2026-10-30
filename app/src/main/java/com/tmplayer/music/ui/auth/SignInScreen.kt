package com.tmplayer.music.ui.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.tmplayer.music.data.AuthState
import com.tmplayer.music.data.CodeDelivery
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.SignInMethod
import com.tmplayer.music.data.Td
import com.tmplayer.music.i18n.L
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The sign in, one pane per TDLib question: choose a route, scan a QR code, or type a number, the
 * code, and the two-step password; an email address and its code for accounts Telegram signs in by
 * email. A smaller take on TMPlayer's LoginScreen (GPL-3.0), phone only. Password recovery and the
 * account reset are not offered yet: Start over is the way out of the password step for now.
 */
@Composable
fun SignInScreen(auth: AuthState) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(L.appName, style = MaterialTheme.typography.headlineMedium)
                when (auth) {
                    AuthState.Connecting -> ConnectingPane()
                    AuthState.ChooseMethod -> ChoosePane()
                    is AuthState.Qr -> QrPane(auth)
                    is AuthState.Phone -> PhonePane()
                    is AuthState.Code -> CodePane(auth)
                    is AuthState.Password -> PasswordPane(auth)
                    is AuthState.EmailAddress -> EmailAddressPane()
                    is AuthState.EmailCode -> EmailCodePane(auth)
                    // Not reachable yet: this app never starts a password recovery.
                    is AuthState.PasswordRecovery -> ConnectingPane()
                    is AuthState.Failed -> FailedPane(auth)
                    AuthState.Ready -> Unit
                }
                Text(
                    L.appUnofficial,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Runs one sign in request from a button. Its own failure is caught and shown, never thrown:
 * the requests return their error as text, and anything else that escapes is humanised here.
 */
private class Submitter {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    suspend fun run(block: suspend () -> String?) {
        if (busy) return
        busy = true
        error = null
        error = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failures.humanise(e)
        } finally {
            busy = false
        }
    }
}

@Composable
private fun rememberSubmitter() = remember { Submitter() }

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ErrorLine(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
}

@Composable
private fun StartOver() {
    val scope = rememberCoroutineScope()
    TextButton(onClick = { scope.launch { runCatching { Td.restartSignIn() } } }) { Text(L.commonStartOver) }
}

@Composable
private fun ConnectingPane() {
    val stalled by Td.signInStalled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    CircularProgressIndicator()
    Body(L.loginConnecting)
    if (stalled) {
        Body(L.loginStalled)
        Button(onClick = { scope.launch { runCatching { Td.retryConnecting() } } }) { Text(L.commonRetry) }
    }
}

@Composable
private fun ChoosePane() {
    val scope = rememberCoroutineScope()
    Title(L.loginChooseTitle)
    Body(L.loginChooseBody)
    Button(
        onClick = { scope.launch { runCatching { Td.chooseSignInMethod(SignInMethod.Qr) } } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(L.loginChooseQr) }
    OutlinedButton(
        onClick = { scope.launch { runCatching { Td.chooseSignInMethod(SignInMethod.Phone) } } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(L.loginChoosePhone) }
}

@Composable
private fun QrPane(auth: AuthState.Qr) {
    val scope = rememberCoroutineScope()
    Title(L.loginQrTitle)
    Body(L.loginQrBody)
    val matrix = remember(auth.link) { runCatching { qrMatrix(auth.link) }.getOrNull() }
    if (matrix != null) QrCode(matrix)
    TextButton(onClick = { scope.launch { runCatching { Td.refreshQrCode() } } }) { Text(L.loginQrRefresh) }
    OutlinedButton(onClick = { scope.launch { runCatching { Td.chooseSignInMethod(SignInMethod.Phone) } } }) {
        Text(L.loginChoosePhone)
    }
}

private fun qrMatrix(link: String): BitMatrix =
    QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))

/** Black on white whatever the theme, with a quiet zone, so every phone camera reads it. */
@Composable
private fun QrCode(matrix: BitMatrix) {
    Box(
        Modifier
            .size(260.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(16.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.width / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
private fun PhonePane() {
    val scope = rememberCoroutineScope()
    val submit = rememberSubmitter()
    var number by rememberSaveable { mutableStateOf("+") }
    val send = { scope.launch { submit.run { Td.submitPhoneNumber(number) } } }
    Title(L.loginPhoneTitle)
    Body(L.loginPhoneBody)
    OutlinedTextField(
        value = number,
        onValueChange = { typed -> number = typed.filter { it.isDigit() || it == '+' || it == ' ' }.take(24) },
        label = { Text(L.loginPhoneLabel) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { send() }),
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorLine(submit.error)
    Button(onClick = { send() }, enabled = !submit.busy && number.count(Char::isDigit) >= 5, modifier = Modifier.fillMaxWidth()) {
        Text(L.commonContinue)
    }
    TextButton(onClick = { scope.launch { runCatching { Td.cancelPhoneEntry() } } }) { Text(L.commonBack) }
}

@Composable
private fun CodePane(auth: AuthState.Code) {
    val scope = rememberCoroutineScope()
    val submit = rememberSubmitter()
    var code by rememberSaveable(auth.phoneNumber) { mutableStateOf("") }
    val send = { scope.launch { submit.run { Td.submitCode(code) } } }
    Title(L.loginCodeTitle)
    Body(if (auth.delivery == CodeDelivery.TelegramApp) L.loginCodeBodyApp else L.loginCodeBody(auth.phoneNumber))
    OutlinedTextField(
        value = code,
        onValueChange = { typed -> code = typed.filter(Char::isLetterOrDigit).take(32) },
        label = { Text(L.loginCodeLabel) },
        singleLine = true,
        isError = auth.wrong,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { send() }),
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorLine(submit.error)
    Button(onClick = { send() }, enabled = !submit.busy && code.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(L.commonContinue)
    }
    if (auth.canResend) {
        TextButton(onClick = { scope.launch { submit.run { Td.resendCode() } } }) { Text(L.loginResend) }
    }
    StartOver()
}

@Composable
private fun PasswordPane(auth: AuthState.Password) {
    val scope = rememberCoroutineScope()
    val submit = rememberSubmitter()
    var password by remember { mutableStateOf("") }
    val send = { scope.launch { submit.run { Td.submitPassword(password) } } }
    Title(L.loginPasswordTitle)
    if (auth.hint.isNotBlank()) Body(L.loginPasswordHint(auth.hint))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(L.loginPasswordLabel) },
        singleLine = true,
        isError = auth.wrong,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { send() }),
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorLine(submit.error)
    Button(onClick = { send() }, enabled = !submit.busy && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(L.commonContinue)
    }
    StartOver()
}

@Composable
private fun EmailAddressPane() {
    val scope = rememberCoroutineScope()
    val submit = rememberSubmitter()
    var address by rememberSaveable { mutableStateOf("") }
    val send = { scope.launch { submit.run { Td.submitEmailAddress(address) } } }
    Title(L.loginEmailAddressTitle)
    OutlinedTextField(
        value = address,
        onValueChange = { address = it.take(254) },
        label = { Text(L.loginEmailAddressLabel) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { send() }),
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorLine(submit.error)
    Button(onClick = { send() }, enabled = !submit.busy && '@' in address, modifier = Modifier.fillMaxWidth()) {
        Text(L.commonContinue)
    }
    StartOver()
}

@Composable
private fun EmailCodePane(auth: AuthState.EmailCode) {
    val scope = rememberCoroutineScope()
    val submit = rememberSubmitter()
    var code by rememberSaveable(auth.pattern) { mutableStateOf("") }
    val send = { scope.launch { submit.run { Td.submitEmailCode(code) } } }
    Title(L.loginEmailCodeTitle)
    Body(L.loginEmailCodeBody(auth.pattern))
    OutlinedTextField(
        value = code,
        onValueChange = { typed -> code = typed.filter(Char::isLetterOrDigit).take(32) },
        label = { Text(L.loginCodeLabel) },
        singleLine = true,
        isError = auth.wrong,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { send() }),
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorLine(submit.error)
    Button(onClick = { send() }, enabled = !submit.busy && code.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(L.commonContinue)
    }
    StartOver()
}

@Composable
private fun FailedPane(auth: AuthState.Failed) {
    val scope = rememberCoroutineScope()
    Title(L.loginFailedTitle)
    Body(auth.message)
    Button(onClick = { scope.launch { runCatching { Td.restartSignIn() } } }) { Text(L.commonRetry) }
}
