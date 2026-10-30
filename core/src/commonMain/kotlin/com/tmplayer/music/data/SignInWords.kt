// From TMPlayer (GPL-3.0), ui/src/shared/kotlin/com/tmplayer/ui/auth/SignInWords.kt and
// core/src/pureMain/kotlin/com/tmplayer/data/SignInSteps.kt.
package com.tmplayer.music.data

import com.tmplayer.music.i18n.L

/** The words the sign in screens use for each step, kept here so a test can read them. */
object SignInWords {

    /** Where the code went, in the user's words rather than TDLib's. */
    fun codeBlurb(state: AuthState.Code): String = when (state.delivery) {
        CodeDelivery.TelegramApp -> L.signinDeliveryApp
        CodeDelivery.Sms -> L.signinDeliverySms(state.phoneNumber)
        CodeDelivery.Call -> L.signinDeliveryCall(state.phoneNumber)
        CodeDelivery.MissedCall -> L.signinDeliveryMissedCall
        CodeDelivery.FlashCall -> L.signinDeliveryFlashCall(state.phoneNumber)
        CodeDelivery.Fragment -> L.signinDeliveryFragment
        CodeDelivery.SmsWord -> L.signinDeliveryWord
        CodeDelivery.SmsPhrase -> L.signinDeliveryPhrase
        CodeDelivery.Firebase -> L.signinDeliveryFirebase(state.phoneNumber)
        CodeDelivery.Unknown -> L.signinDeliveryUnknown(state.phoneNumber)
    }

    /** Named after what pressing it will actually do, which is not always "the same again". */
    fun resendLabel(next: CodeDelivery?): String = when (next) {
        CodeDelivery.Sms, CodeDelivery.SmsWord, CodeDelivery.SmsPhrase -> L.signinResendSms
        CodeDelivery.Call, CodeDelivery.MissedCall, CodeDelivery.FlashCall -> L.signinResendCall
        CodeDelivery.TelegramApp -> L.signinResendApp
        CodeDelivery.Fragment -> L.signinResendFragment
        else -> L.signinResendAgain
    }

    /** What the number step says about a number that cannot be sent yet. */
    fun problem(problem: PhoneProblem): String = when (problem) {
        PhoneProblem.NoCountry -> L.signinNoCountry
        PhoneProblem.UnknownCode -> L.signinInvalidCountryCode
        PhoneProblem.Empty -> L.signinNumberEmpty
        PhoneProblem.TooShort -> L.signinNumberTooShort
    }

    /** True where the code is digits of a known length, which a row of boxes can hold. */
    fun isDigits(delivery: CodeDelivery): Boolean = when (delivery) {
        CodeDelivery.SmsWord, CodeDelivery.SmsPhrase, CodeDelivery.FlashCall, CodeDelivery.MissedCall -> false
        else -> true
    }

    /** The code length to expect: TDLib's when it says, else Telegram's usual five. */
    fun codeLength(state: AuthState.Code): Int = state.length.takeIf { it in 1..MAX_CODE_LENGTH } ?: DEFAULT_CODE_LENGTH

    /** A countdown as "m:ss". Negative time reads as none. */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /**
     * Whether a sign in failure is Telegram refusing to send this number a code without Premium,
     * which only a QR code from the Telegram app gets round, so the screen offers that.
     */
    fun offersQr(state: AuthState.Failed): Boolean = state.message == L.errorsPremiumSignIn

    const val DEFAULT_CODE_LENGTH = 5
    const val MAX_CODE_LENGTH = 8
}

/**
 * What a login code field keeps of what was typed, pasted or autofilled into it, kept away from
 * any field so a test can feed it a whole text message.
 */
object CodeInput {

    /**
     * The code in [raw]. For a code of digits a paste is often the whole message ("Telegram code
     * 12345. Do not give this code to anyone"), so a run of exactly [length] digits is taken when
     * there is one; otherwise every digit in it, up to [length]. A word or a phrase is kept as
     * typed, since its letters and spaces are the code, up to [MAX_TEXT] characters.
     */
    fun clean(raw: String, digits: Boolean, length: Int): String {
        val clipped = if (raw.length > MAX_PASTE) raw.take(MAX_PASTE) else raw
        if (!digits) return clipped.take(MAX_TEXT)
        if (length > 0) {
            Regex("""(?<!\d)\d{$length}(?!\d)""").find(clipped)?.let { return it.value }
        }
        val kept = PhoneRules.digits(clipped)
        return if (length > 0) kept.take(length) else kept.take(MAX_TEXT)
    }

    /**
     * True when [code] is the whole answer and has not been sent yet. A word or a phrase has no
     * last box to notice and waits for the button. [submitted] is the last code sent, so a wrong
     * code left in the field does not send itself again on every redraw, or after a rotation.
     */
    fun shouldAutoSubmit(code: String, length: Int, digits: Boolean, submitted: String?): Boolean =
        digits && length > 0 && code.length == length && code.all { it in '0'..'9' } && code != submitted

    private const val MAX_PASTE = 4096
    private const val MAX_TEXT = 64
}
