package com.tmplayer.music.data

import dev.g000sha256.tdl.dto.AuthenticationCodeInfo
import dev.g000sha256.tdl.dto.AuthenticationCodeType
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFirebaseAndroid
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFirebaseIos
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFlashCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFragment
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeMissedCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSms
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSmsPhrase
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSmsWord
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeTelegramMessage
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateClosing
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailAddress
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPremiumPurchase
import dev.g000sha256.tdl.dto.AuthorizationStateWaitRegistration
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.EmailAddressResetState
import dev.g000sha256.tdl.dto.EmailAddressResetStateAvailable
import dev.g000sha256.tdl.dto.EmailAddressResetStatePending

/**
 * TDLib's authorization states as tdl-coroutines hands them over, mapped onto the TDLib-neutral
 * [TdAuthorization] and reduced by [AuthFlow], which the browser build shares. The states, the
 * actions and the wording live there (AuthState.kt in the pure source set).
 */
object AuthReducer {

    fun reduce(
        state: AuthorizationState,
        method: SignInMethod = SignInMethod.Qr,
    ): AuthStep = AuthFlow.reduce(neutral(state), method)

    /** The tdl-coroutines state as the shared flow reads it. */
    fun neutral(state: AuthorizationState): TdAuthorization = when (state) {
        is AuthorizationStateWaitTdlibParameters -> TdAuthorization.WaitParameters
        is AuthorizationStateWaitPhoneNumber -> TdAuthorization.WaitPhoneNumber
        is AuthorizationStateWaitCode -> TdAuthorization.WaitCode(codeInfo(state.codeInfo))
        is AuthorizationStateWaitOtherDeviceConfirmation -> TdAuthorization.WaitOtherDevice(state.link)
        is AuthorizationStateWaitPassword -> TdAuthorization.WaitPassword(
            hint = state.passwordHint,
            recoveryEmail = state.recoveryEmailAddressPattern.takeIf { state.hasRecoveryEmailAddress },
        )
        is AuthorizationStateReady -> TdAuthorization.Ready
        is AuthorizationStateLoggingOut -> TdAuthorization.LoggingOut
        is AuthorizationStateClosing -> TdAuthorization.Closing
        is AuthorizationStateClosed -> TdAuthorization.Closed
        is AuthorizationStateWaitEmailAddress -> TdAuthorization.WaitEmailAddress
        is AuthorizationStateWaitEmailCode ->
            TdAuthorization.WaitEmailCode(
                state.codeInfo.emailAddressPattern,
                state.codeInfo.length,
                emailReset(state.emailAddressResetState),
            )
        is AuthorizationStateWaitRegistration -> TdAuthorization.WaitRegistration
        is AuthorizationStateWaitPremiumPurchase -> TdAuthorization.WaitPremiumPurchase
        else -> TdAuthorization.Unknown
    }

    /** See [AuthFlow.close]. */
    fun close(): AuthStep = AuthFlow.close()

    /** Everything the code screen needs, lifted out of the TDLib DTO in one place. */
    fun codeState(info: AuthenticationCodeInfo, wrong: Boolean = false): AuthState.Code =
        AuthFlow.codeState(codeInfo(info), wrong)

    fun codeInfo(info: AuthenticationCodeInfo): CodeInfo = CodeInfo(
        phoneNumber = info.phoneNumber,
        delivery = delivery(info.type),
        length = length(info.type),
        timeout = info.timeout,
        next = info.nextType?.let(::delivery),
        hasNext = info.nextType != null,
    )

    fun emailReset(state: EmailAddressResetState?): EmailReset = when (state) {
        is EmailAddressResetStateAvailable -> EmailReset.Available(state.waitPeriod)
        is EmailAddressResetStatePending -> EmailReset.Pending(state.resetIn)
        else -> EmailReset.None
    }

    fun delivery(type: AuthenticationCodeType): CodeDelivery = when (type) {
        is AuthenticationCodeTypeTelegramMessage -> CodeDelivery.TelegramApp
        is AuthenticationCodeTypeSms -> CodeDelivery.Sms
        is AuthenticationCodeTypeCall -> CodeDelivery.Call
        is AuthenticationCodeTypeMissedCall -> CodeDelivery.MissedCall
        is AuthenticationCodeTypeFlashCall -> CodeDelivery.FlashCall
        is AuthenticationCodeTypeFragment -> CodeDelivery.Fragment
        is AuthenticationCodeTypeSmsWord -> CodeDelivery.SmsWord
        is AuthenticationCodeTypeSmsPhrase -> CodeDelivery.SmsPhrase
        is AuthenticationCodeTypeFirebaseAndroid, is AuthenticationCodeTypeFirebaseIos ->
            CodeDelivery.Firebase
        else -> CodeDelivery.Unknown
    }

    /** Zero where the type has no length of its own, which the screen turns into its default. */
    private fun length(type: AuthenticationCodeType): Int = when (type) {
        is AuthenticationCodeTypeTelegramMessage -> type.length
        is AuthenticationCodeTypeSms -> type.length
        is AuthenticationCodeTypeCall -> type.length
        is AuthenticationCodeTypeMissedCall -> type.length
        is AuthenticationCodeTypeFragment -> type.length
        is AuthenticationCodeTypeFirebaseAndroid -> type.length
        is AuthenticationCodeTypeFirebaseIos -> type.length
        else -> 0
    }
}
