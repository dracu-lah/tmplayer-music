// From TMPlayer (GPL-3.0), app/src/main/java/com/tmplayer/ui/auth/LoginScreen.kt (TouchPhonePane),
// ui/src/shared/kotlin/com/tmplayer/ui/auth/{PhoneEntry,SignInWords,CountryGuess}.kt and
// app/src/main/java/com/tmplayer/data/DeviceCountry.kt.
package com.tmplayer.music.ui.auth

import android.content.Context
import android.telephony.TelephonyManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.AuthState
import com.tmplayer.music.data.Country
import com.tmplayer.music.data.CountryGuess
import com.tmplayer.music.data.DeviceRegion
import com.tmplayer.music.data.PhoneEntry
import com.tmplayer.music.data.PhoneFormat
import com.tmplayer.music.data.PhoneProblem
import com.tmplayer.music.data.PhoneRules
import com.tmplayer.music.data.SignInWords
import com.tmplayer.music.data.Td
import com.tmplayer.music.i18n.L
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * The number step as Telegram itself asks for a number: the country first, then a narrow dial
 * code beside a wide national number.
 *
 * Both fields hold digits and nothing else, exactly as typed; the grouping is drawn
 * ([PhoneGrouping]), never written into the field, so the caret stays put. A whole number pasted
 * or autofilled into either field ("+44 7700 900123", "0044-7700-900123") is taken apart and the
 * country follows its code. The country starts on the SIM's, else Telegram's guess, else the
 * locale's; with none of those the list opens with its search box ready.
 *
 * [initial] is the number being changed from the code step, where Back returns to the code.
 */
@Composable
fun PhoneStep(
    state: AuthState,
    initial: String?,
    onBack: () -> Unit,
    onUseQr: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SignInRunner.loadCountries() }
    val countries by SignInRunner.countries.collectAsStateWithLifecycle()
    var entry by rememberSaveable(stateSaver = EntrySaver) { mutableStateOf(PhoneEntry()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var guessing by rememberSaveable { mutableStateOf(initial == null && entry.dial.isEmpty() && !entry.touched) }
    // Said only once a send was tried, so nobody is told off for a number they are still typing.
    var tried by rememberSaveable { mutableStateOf(false) }
    val busyTask by SignInRunner.busy.collectAsStateWithLifecycle()
    val failure by SignInRunner.failure.collectAsStateWithLifecycle()
    val floodLeft = rememberFloodWait()
    val busy = busyTask == SignInTask.Phone

    val country = rememberEntryCountry(entry, countries)
    val pattern = rememberPhonePattern(entry.dial, entry.national)
    val dialField = remember { FocusRequester() }
    val numberField = remember { FocusRequester() }

    // The number being changed, taken apart once.
    LaunchedEffect(initial, countries) {
        if (initial == null || entry.dial.isNotEmpty() || entry.national.isNotEmpty()) return@LaunchedEffect
        entry.take("+" + PhoneRules.digits(initial), countries)?.let { entry = it }
    }

    // The country, filled in for the user. Nothing types into the fields meanwhile (three seconds
    // at most, and none at all with a SIM), so typing never races a code going in front of it.
    LaunchedEffect(Unit) {
        if (!guessing) return@LaunchedEffect
        val found = try {
            CountryGuess.forPhone(
                countries = countries,
                deviceIso = DeviceCountry.simOrNetwork(context),
                telegramIso = { Td.guessedCountryCode() },
                localeIso = { DeviceCountry.locale() },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (found != null) {
            entry = entry.guess(found)
        } else if (!entry.touched && entry.dial.isEmpty()) {
            // No SIM, no answer from Telegram and a locale with no country: the list, ready to search.
            picking = true
        }
        guessing = false
    }

    if (picking) {
        CountryPicker(
            countries = countries,
            selected = country,
            onPick = {
                entry = entry.pick(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
        return
    }

    BackHandler(onBack = onBack)

    val problem = entry.problem(country)
    // A code nobody has is wrong as soon as it is typed; the rest waits for a send.
    val shownProblem = when {
        problem == PhoneProblem.UnknownCode && entry.dial.isNotEmpty() -> problem
        tried -> problem
        else -> null
    }
    val phoneFailure = failure?.takeIf { it.task == SignInTask.Phone }
    val send = {
        tried = true
        when (problem) {
            null -> SignInRunner.run(SignInTask.Phone) { Td.submitPhoneNumber(entry.full) }
            PhoneProblem.NoCountry -> picking = true
            PhoneProblem.UnknownCode -> runCatching { dialField.requestFocus() }
            PhoneProblem.Empty, PhoneProblem.TooShort -> runCatching { numberField.requestFocus() }
        }
        Unit
    }

    SignInFrame(onBack = onBack) {
        StepTitle(L.loginPhoneTitle)
        StepBody(L.signinPhoneBlurb)

        CountryRow(country = country, guessing = guessing, invalid = entry.dial.isNotEmpty() && country == null) { picking = true }

        // A number reads left to right in every language, Arabic and Persian included.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = entry.dial,
                    onValueChange = {
                        entry = entry.typeDial(it, countries)
                        SignInRunner.clearFailure()
                    },
                    // The floating label has words of its own: "+44" is no name for the field.
                    label = { Text(L.signinDialCode, maxLines = 1) },
                    prefix = { Text("+") },
                    singleLine = true,
                    enabled = !guessing && !busy,
                    isError = shownProblem == PhoneProblem.NoCountry || shownProblem == PhoneProblem.UnknownCode,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onNext = { runCatching { numberField.requestFocus() } }),
                    modifier = Modifier
                        .weight(DIAL_SHARE)
                        .widthIn(min = 96.dp)
                        .focusRequester(dialField),
                )
                OutlinedTextField(
                    value = entry.national,
                    // Digits and nothing else: the dialpad is a hint the keyboard may ignore, and a
                    // letter here would sit in the field looking accepted while being dropped.
                    onValueChange = {
                        entry = entry.typeNational(it, countries)
                        SignInRunner.clearFailure()
                    },
                    label = { Text(L.signinPhoneNumber, maxLines = 1) },
                    singleLine = true,
                    enabled = !guessing && !busy,
                    isError = shownProblem == PhoneProblem.Empty || shownProblem == PhoneProblem.TooShort ||
                        (phoneFailure != null && state is AuthState.Phone && state.wrong),
                    visualTransformation = PhoneGrouping(pattern),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onDone = { send() }),
                    modifier = Modifier
                        .weight(1f - DIAL_SHARE)
                        .focusRequester(numberField)
                        // Backspace in an empty number goes back into the code, as in Telegram.
                        .onPreviewKeyEvent { event ->
                            val back = event.type == KeyEventType.KeyDown && event.key == Key.Backspace && entry.national.isEmpty()
                            if (back) runCatching { dialField.requestFocus() }
                            back
                        }
                        .semantics { contentType = ContentType.PhoneNumberNational },
                )
            }
        }

        StepError(phoneFailure?.message ?: shownProblem?.let(SignInWords::problem))

        MainAction(
            label = when {
                floodLeft > 0 -> L.signinTryAgainIn(SignInWords.clock(floodLeft))
                phoneFailure?.noAnswer == true -> L.commonRetry
                else -> L.signinSendCode
            },
            busyLabel = L.signinSendingCode,
            busy = busy,
            enabled = !guessing && floodLeft <= 0 && busyTask == null,
            onClick = send,
        )
        TextButton(onClick = onUseQr, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(L.signinUseQr)
        }
    }

    // The keyboard is up either way on this step, so it is pointed at the field to fill.
    LaunchedEffect(guessing) {
        if (guessing) return@LaunchedEffect
        runCatching { if (entry.dial.isEmpty()) dialField.requestFocus() else numberField.requestFocus() }
    }
}

/** The country, as a button that opens the list; while it is being found, says so. */
@Composable
private fun CountryRow(country: Country?, guessing: Boolean, invalid: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = !guessing,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (guessing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    L.signinFindingCountry,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                if (country != null) {
                    Text(country.flag, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    country?.name ?: L.signinChooseCountry,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val EntrySaver = Saver<PhoneEntry, ArrayList<String>>(
    save = { ArrayList(it.save()) },
    restore = { PhoneEntry.restore(it) },
)

/**
 * The country the entry's code belongs to, asking TDLib for the area code only when the code is
 * shared, so typing "+1 204" turns the flag Canadian.
 */
@Composable
private fun rememberEntryCountry(entry: PhoneEntry, countries: List<Country>): Country? {
    val shared = PhoneRules.candidates(countries, entry.dial).size > 1
    val areaIso by produceState<String?>(null, shared, entry.dial, entry.national.take(AREA_DIGITS)) {
        value = if (shared && entry.national.isNotEmpty()) {
            try {
                Td.phoneCountry(entry.dial + entry.national)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
    return PhoneRules.country(countries, entry.dial, areaIso, entry.chosenIso)
}

/**
 * TDLib's layout for the national number typed after [dial] ([Td.formatPhone]), asked again as
 * the number grows, since the layout of some countries depends on the first digits.
 */
@Composable
private fun rememberPhonePattern(dial: String, national: String): String? {
    val pattern by produceState<String?>(null, dial, national.take(PATTERN_PREFIX)) {
        value = try {
            Td.formatPhone(dial, national)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    return pattern
}

/**
 * Draws a field of bare digits grouped as [pattern] lays them out ([PhoneFormat]), with the caret
 * kept on the digit it was on. Nothing is written into the field itself.
 */
private class PhoneGrouping(private val pattern: String?) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val formatted = PhoneFormat.format(text.text, pattern)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = PhoneFormat.toFormatted(offset, formatted)
            override fun transformedToOriginal(offset: Int): Int = PhoneFormat.toRaw(offset, formatted).coerceAtMost(text.length)
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }

    override fun equals(other: Any?) = other is PhoneGrouping && other.pattern == pattern
    override fun hashCode() = pattern.hashCode()
}

/**
 * Where the device thinks it is, for when Telegram will not say. None of it needs a permission;
 * each answer can be blank, and [DeviceRegion] keeps only a real two letter code.
 */
private object DeviceCountry {
    /** The SIM's country, else the network's. Blank on a phone with neither (no SIM, airplane mode). */
    fun simOrNetwork(context: Context): String = runCatching {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        DeviceRegion.pick(telephony?.simCountryIso, telephony?.networkCountryIso, null)
    }.getOrDefault("")

    /** The locale's country: what language somebody reads, the weakest of the guesses. */
    fun locale(): String = DeviceRegion.pick(null, null, runCatching { Locale.getDefault().country }.getOrNull())
}

/** Enough of the national number for TDLib to tell area codes apart. */
private const val AREA_DIGITS = 4

/** Enough of the number to tell its layout apart; past this the layout no longer changes. */
private const val PATTERN_PREFIX = 4

/** The dial code's share of the number row. */
private const val DIAL_SHARE = 0.34f
