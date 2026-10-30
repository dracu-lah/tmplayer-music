// From TMPlayer (GPL-3.0), app/src/main/java/com/tmplayer/ui/auth/LoginScreen.kt (TouchCodePane,
// CodeBoxes).
package com.tmplayer.music.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.AuthState
import com.tmplayer.music.data.CodeInput
import com.tmplayer.music.data.SignInWords
import com.tmplayer.music.data.Td
import com.tmplayer.music.i18n.L

/**
 * The code step: a row of boxes (or a plain field for a word or a phrase), a resend that counts
 * down, and a way back to the number.
 *
 * The code typed, the last code sent and the resend clock are all kept across a rotation, and
 * nothing on this step asks for a code by itself: only the resend button does, once its countdown
 * is over. The last digit typed is the whole answer, sent once; a wrong code left in the boxes is
 * not sent again until it is changed.
 */
@Composable
fun CodeStep(state: AuthState.Code, onChangeNumber: () -> Unit, onStartOver: () -> Unit) {
    val boxed = SignInWords.isDigits(state.delivery)
    val length = SignInWords.codeLength(state)
    var code by rememberSaveable(state.phoneNumber) { mutableStateOf("") }
    var submitted by rememberSaveable(state.phoneNumber) { mutableStateOf<String?>(null) }
    var resent by rememberSaveable(state.phoneNumber) { mutableIntStateOf(0) }
    val remaining = rememberCountdown(state.timeout, "${state.phoneNumber}/$resent")
    val busyTask by SignInRunner.busy.collectAsStateWithLifecycle()
    val failure by SignInRunner.failure.collectAsStateWithLifecycle()
    val floodLeft = rememberFloodWait()
    val field = remember { FocusRequester() }
    val own = failure?.takeIf { it.task == SignInTask.Code || it.task == SignInTask.Resend }

    val submit = {
        if (code.isNotEmpty() && floodLeft <= 0) {
            val sending = code
            if (SignInRunner.run(SignInTask.Code) { Td.submitCode(sending) }) submitted = sending
        }
    }

    // Back is the number, not a fresh start: TDLib takes a corrected number at this step, and the
    // code already sent stays good if the user comes straight back.
    BackHandler(onBack = onChangeNumber)

    LaunchedEffect(code) {
        if (busyTask == null && CodeInput.shouldAutoSubmit(code, length, boxed, submitted)) submit()
    }

    SignInFrame(onBack = onChangeNumber) {
        StepTitle(L.loginCodeTitle)
        StepBody(SignInWords.codeBlurb(state))

        if (boxed) {
            CodeBoxes(
                code = code,
                length = length,
                wrong = state.wrong && code == submitted,
                onChange = {
                    code = CodeInput.clean(it, true, length)
                    SignInRunner.clearFailure()
                },
                onDone = submit,
                modifier = Modifier.focusRequester(field),
            )
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = CodeInput.clean(it, false, 0)
                    SignInRunner.clearFailure()
                },
                label = { Text(L.loginCodeLabel) },
                singleLine = true,
                isError = state.wrong && code == submitted,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
        }

        StepError(own?.message)

        MainAction(
            label = if (floodLeft > 0) L.signinTryAgainIn(SignInWords.clock(floodLeft)) else L.signinSignIn,
            busyLabel = L.signinSigningIn,
            busy = busyTask == SignInTask.Code,
            enabled = code.isNotEmpty() && code != submitted && floodLeft <= 0 && busyTask == null,
            onClick = submit,
        )

        // Only where TDLib offers a next route: with none it refuses the resend.
        if (state.canResend) {
            OutlinedButton(
                onClick = {
                    // The clock starts again with the request, so a second press cannot follow it.
                    if (SignInRunner.run(SignInTask.Resend) { Td.resendCode() }) {
                        resent++
                        submitted = null
                    }
                },
                enabled = remaining <= 0 && floodLeft <= 0 && busyTask == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        busyTask == SignInTask.Resend -> L.signinSendingCode
                        remaining > 0 -> L.signinResendIn(SignInWords.clock(remaining))
                        else -> SignInWords.resendLabel(state.next)
                    },
                )
            }
        }

        TextButton(onClick = onChangeNumber, enabled = busyTask == null, modifier = Modifier.fillMaxWidth()) {
            Text(L.signinWrongNumber)
        }
        TextButton(onClick = onStartOver, enabled = busyTask == null, modifier = Modifier.fillMaxWidth()) {
            Text(L.commonStartOver)
        }
    }

    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
}

/**
 * One text field drawn as [length] separate boxes.
 *
 * Five real fields would each have to hand focus to the next, and none would take a pasted code
 * or an autofill from a text message, which is how most codes on a phone are entered. So the real
 * field sits on top of the boxes with its text and caret invisible, keeping the keyboard, the
 * paste menu and the autofill, while the boxes underneath are the readout.
 */
@Composable
private fun CodeBoxes(
    code: String,
    length: Int,
    wrong: Boolean,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BasicTextField(
            value = code,
            // Never closed while a code is being checked: a closed field drops the keyboard, and a
            // second send is held back by [SignInRunner] instead.
            onValueChange = onChange,
            singleLine = true,
            // Invisible, in both senses: the glyphs and the caret. The boxes below are the readout.
            textStyle = MaterialTheme.typography.titleLarge.copy(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.SmsOtpCode },
            decorationBox = { inner ->
                Box {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (index in 0 until length) {
                            val active = index == code.length
                            val edge = when {
                                wrong -> colors.error
                                active -> colors.primary
                                else -> colors.outline
                            }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 56.dp)
                                    .clip(shape)
                                    .background(colors.surfaceContainerHigh)
                                    .border(if (active || wrong) 2.dp else 1.dp, edge, shape)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    code.getOrNull(index)?.toString().orEmpty(),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = colors.onSurface,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    Box(Modifier.matchParentSize()) { inner() }
                }
            },
        )
    }
}
