package com.tmplayer.music.data

import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.Credentials
import com.tmplayer.music.platform.DeviceInfo
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.platform.Paths
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.ConnectionStateReady
import dev.g000sha256.tdl.dto.ConnectionStateUpdating
import dev.g000sha256.tdl.dto.EmailAddressAuthenticationCode
import dev.g000sha256.tdl.dto.OptionValueBoolean
import dev.g000sha256.tdl.dto.OptionValueInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "Td"

/**
 * The single TDLib connection for the whole process.
 *
 * Adapted from TMPlayer's `Td` (GPL-3.0): the sign in, the client loop and the one-client rule are
 * the same; the video library, proxies, storage and line probing are left out until this app needs
 * them. TDLib owns the session on disk, so there is exactly one client and it lives as long as the
 * app does. Logging out closes the client for good, so [clientLoop] builds a replacement: that is
 * the only supported way to log back in.
 */
object Td {

    private val generation = AtomicLong(0)
    private val _session = MutableStateFlow<TdSession?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + JobFailures.handler("Td"))
    private val gate = Mutex()

    @Volatile
    private var current: TdlClient? = null

    /** A client being closed on purpose; its failing requests are not the sign in failing. */
    @Volatile
    private var retiring: TdlClient? = null

    private var lastHandled: AuthorizationState? = null
    private var method: SignInMethod = SignInMethod.Undecided
    private var methodAfterRestart: SignInMethod = SignInMethod.Undecided

    @Volatile
    private var closedSignal: CompletableDeferred<Unit>? = null

    /** Set while the client could not start at all; Try again completes it. */
    @Volatile
    private var clientRetry: CompletableDeferred<Unit>? = null

    @Volatile
    private var startFailure: String? = null

    private val _auth = MutableStateFlow<AuthState>(AuthState.Connecting)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    /** True once a sign in has sat on Connecting for [SIGN_IN_STALL_MS], so the screen offers Retry. */
    private val _signInStalled = MutableStateFlow(false)
    val signInStalled: StateFlow<Boolean> = _signInStalled.asStateFlow()

    private val connectAttempt = MutableStateFlow(0)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Chats whose picture changed, as TDLib reports it ([ChatPhotos] drops the old one). */
    private val _chatPhotoChanged = MutableSharedFlow<Long>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val chatPhotoChanged: SharedFlow<Long> = _chatPhotoChanged.asSharedFlow()

    /**
     * The user's Telegram folders, in their order. Collected here because TDLib announces them once,
     * shortly after the client starts, and again only when they change; emptied with the client.
     */
    private val _folders = MutableStateFlow<List<ChatFolder>>(emptyList())
    val folders: StateFlow<List<ChatFolder>> = _folders.asStateFlow()

    @Volatile
    private var currentPaths: Paths? = null
    private lateinit var device: DeviceInfo
    private lateinit var credentials: Credentials

    /**
     * Starts the client once per process; later calls are ignored. The app supplies where TDLib
     * keeps its files, what the device calls itself, and the API credentials it was built with.
     */
    fun start(paths: Paths, device: DeviceInfo, credentials: Credentials) {
        synchronized(this) {
            if (currentPaths != null) return
            this.device = device
            this.credentials = credentials
            this.currentPaths = paths
        }
        scope.launch { keepClientRunning() }
        scope.launch {
            // A retry counts as a fresh start; moving between two waiting states does not.
            val waiting = combine(_auth, connectAttempt) { auth, attempt -> (auth is AuthState.Connecting) to attempt }
                .distinctUntilChanged()
            AuthStall.watchWaiting(waiting.map { it.first }, _signInStalled, SIGN_IN_STALL_MS)
        }
    }

    /**
     * Runs [clientLoop], and turns anything that escapes it into a sign in failure the user can
     * read. TDLib's native library is loaded by the first client, so a build missing the library
     * for this device throws here; left alone the screen would say Connecting for good.
     */
    private suspend fun keepClientRunning() {
        while (true) {
            try {
                clientLoop()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w(TAG, "TDLib client could not run", e)
                current = null
                _session.value = null
                _connected.value = false
                // A class whose static set up failed once answers every later use with a bare
                // NoClassDefFoundError; the first failure is the one that says what went wrong.
                val reason = startFailure.takeIf { e is NoClassDefFoundError } ?: describe(e)
                if (startFailure == null) startFailure = reason
                val retry = CompletableDeferred<Unit>()
                clientRetry = retry
                _auth.value = AuthState.Failed(L.errorsTdlibUnavailable(reason))
                retry.await()
                clientRetry = null
                _auth.value = AuthState.Connecting
            }
        }
    }

    private fun describe(error: Throwable): String =
        generateSequence(error) { it.cause }.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
            ?: error::class.simpleName.orEmpty()

    private suspend fun clientLoop() = coroutineScope {
        while (isActive) {
            val closed = CompletableDeferred<Unit>()
            val td = TdlClient.create()
            current = td
            retiring = null
            _session.value = TdSession(td, generation.incrementAndGet())
            lastHandled = null
            method = methodAfterRestart
            methodAfterRestart = SignInMethod.Undecided
            closedSignal = closed

            val updates = launch {
                td.authorizationStateUpdates.collect { apply(td, it.authorizationState) }
            }
            val connection = launch {
                td.connectionStateUpdates.collect { update ->
                    // "Updating" means the socket is up and TDLib is pulling in what it missed;
                    // requests are answered from that point on, so it counts as connected.
                    _connected.value = update.state is ConnectionStateReady || update.state is ConnectionStateUpdating
                }
            }
            val photos = launch {
                td.chatPhotoUpdates.collect { _chatPhotoChanged.tryEmit(it.chatId) }
            }
            val folders = launch {
                td.chatFoldersUpdates.collect { update ->
                    // Only the plain characters of the name: a custom emoji this client cannot draw
                    // would print as its placeholder.
                    _folders.value = update.chatFolders.orEmpty().mapNotNull { folder ->
                        val title = folder.name?.text?.text.orEmpty().trim()
                        if (title.isEmpty()) null else ChatFolder(folder.id, title.take(MAX_FOLDER_TITLE))
                    }
                }
            }
            // The first update may already have been emitted before we subscribed, so ask once
            // directly. Both paths go through the same mutex-guarded handler.
            val primer = launch {
                runCatching { td.setLogVerbosityLevel(1) }
                when (val now = td.getAuthorizationState()) {
                    is TdlResult.Success -> apply(td, now.result)
                    is TdlResult.Failure -> Logger.w(TAG, "getAuthorizationState failed: ${now.message}")
                }
            }

            closed.await()
            updates.cancel()
            connection.cancel()
            photos.cancel()
            folders.cancel()
            _folders.value = emptyList()
            primer.cancel()
            _connected.value = false
            current = null
            _session.value = null
        }
    }

    private suspend fun apply(td: TdlClient, state: AuthorizationState) = gate.withLock {
        // The update flow and the one-off getAuthorizationState() often deliver the same state back
        // to back at startup. Acting twice would send TDLib its parameters twice and surface the
        // second attempt's error as a login failure.
        if (state == lastHandled) return@withLock
        lastHandled = state
        val step = AuthReducer.reduce(state, method)
        Logger.i(TAG, "auth: ${state::class.simpleName} -> ${step.state::class.simpleName}")
        perform(td, step)
    }

    /** Shows [step]'s state and carries out its action. Called with [gate] held. */
    private suspend fun perform(td: TdlClient, step: AuthStep) {
        // Never let a stale "connecting" overwrite a terminal failure the user still needs to read.
        if (_auth.value !is AuthState.Failed || step.state !is AuthState.Connecting) {
            _auth.value = step.state
        }
        when (step.action) {
            AuthAction.SendParameters -> sendParameters(td)
            // Not awaited under the gate: with no route to Telegram the request waits for one, and
            // holding the gate that long would leave Closing and Closed unhandled.
            AuthAction.RequestQrCode -> scope.launch {
                val result = td.requestQrCodeAuthentication(longArrayOf())
                if (result is TdlResult.Failure && td === current && td !== retiring) {
                    _auth.value = AuthState.Failed(
                        Failures.humanise(result.message),
                        Failures.floodWaitSeconds(result.message) ?: 0,
                    )
                }
            }
            AuthAction.OnReady -> onReady(td)
            AuthAction.RecreateClient -> closedSignal?.complete(Unit)
            AuthAction.Close -> runCatching { td.close() }
            AuthAction.None -> Unit
        }
    }

    /** Runs the last TDLib state through the reducer again after [method] has moved. */
    private suspend fun replay() {
        val td = current ?: return
        val state = gate.withLock { lastHandled.also { lastHandled = null } } ?: return
        apply(td, state)
    }

    /** The user picked a route on the first sign in screen. */
    suspend fun chooseSignInMethod(chosen: SignInMethod) {
        // Once a QR code is up TDLib is waiting for the other device and takes a phone number only
        // from its first question, so the sign in starts over with the method kept.
        if (chosen == SignInMethod.Phone && _auth.value is AuthState.Qr) {
            restartSignIn(SignInMethod.Phone)
            return
        }
        method = chosen
        replay()
    }

    /** Steps back from the number entry to the choice of method; nothing was sent yet. */
    suspend fun cancelPhoneEntry() {
        method = SignInMethod.Undecided
        replay()
    }

    /**
     * When the last flood wait Telegram put on a sign in request ends, in wall clock milliseconds;
     * zero when there is none. The screen counts it down and holds its button until then, rather
     * than offer a press that would fail the same way.
     */
    private val _signInWaitUntil = MutableStateFlow(0L)
    val signInWaitUntil: StateFlow<Long> = _signInWaitUntil.asStateFlow()

    private fun noteWait(message: String) {
        val seconds = Failures.floodWaitSeconds(message) ?: return
        _signInWaitUntil.value = System.currentTimeMillis() + seconds.coerceIn(0, MAX_WAIT_SECONDS) * 1000L
    }

    /**
     * The number request still with TDLib, and the client and number it was for. TDLib keeps a
     * request it cannot send yet, so after the screen gave up waiting ([SUBMIT_TIMEOUT_MS]) a Try
     * again for the same number waits on that one rather than asking Telegram for a second code.
     */
    private var pendingPhone: Triple<TdlClient, String, Deferred<TdlResult<*>>>? = null

    /** Sends the phone number Telegram should send a code to. Null on success, an error otherwise. */
    suspend fun submitPhoneNumber(phoneNumber: String): String? {
        val td = current ?: return L.errorsNotConnected
        val number = phoneNumber.trim()
        val call = synchronized(this) {
            pendingPhone?.takeIf { (client, asked, job) -> client === td && asked == number && job.isActive }?.third
                ?: scope.async<TdlResult<*>> { td.setAuthenticationPhoneNumber(number) }
                    .also { pendingPhone = Triple(td, number, it) }
        }
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) { call.await() } ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                noteWait(result.message)
                // A number changed from the code step that Telegram refused leaves TDLib on that
                // step, so the screen stays there too, with the number being corrected.
                if (_auth.value is AuthState.Phone) _auth.value = AuthState.Phone(wrong = true)
                when {
                    result.message.contains("PHONE_NUMBER_INVALID") -> L.errorsPhoneInvalid
                    result.message.contains("PHONE_NUMBER_BANNED") -> L.signinErrorPhoneBanned
                    else -> Failures.humanise(result.message)
                }
            }
        }
    }

    /**
     * Every country Telegram takes numbers from, in its own spelling and the app's language, or
     * empty when TDLib has not answered within [LOOKUP_TIMEOUT_MS] (it asks Telegram the first
     * time, so with no line it waits). The screen then keeps the list shipped with the app.
     */
    suspend fun countries(): List<Country> {
        val td = current ?: return emptyList()
        val answer = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { td.getCountries() }?.valueOrNull ?: return emptyList()
        return PhoneCountries.from(answer.countries.toList())
    }

    /** The ISO code Telegram guesses from the address it sees, blank if it will not guess. */
    suspend fun guessedCountryCode(): String {
        val td = current ?: return ""
        return withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { td.getCountryCode() }?.valueOrNull?.text.orEmpty()
    }

    /**
     * TDLib's layout for a national number being typed after [dial]: separators where its country
     * puts them and a "-" for each digit to come ([PhoneFormat]). Null when it has none. Answered
     * from TDLib's own tables, before sign in and with no line to Telegram.
     */
    suspend fun formatPhone(dial: String, national: String): String? {
        val td = current ?: return null
        if (dial.isEmpty()) return null
        return withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { td.getPhoneNumberInfo("+$dial$national") }?.valueOrNull
            ?.takeIf { it.countryCallingCode == dial }
            ?.formattedPhoneNumber
            ?.takeIf { it.isNotBlank() && it.length <= MAX_PATTERN }
    }

    /**
     * The country TDLib places a number in, by its area code where a calling code is shared
     * ("+1 204..." is Canada, "+44 1534..." Jersey). Blank when it cannot tell.
     */
    suspend fun phoneCountry(digits: String): String {
        val td = current ?: return ""
        if (digits.isEmpty()) return ""
        return withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { td.getPhoneNumberInfo("+$digits") }?.valueOrNull?.country?.countryCode.orEmpty()
    }

    /** Submits the login code Telegram sent. Null on success, an error otherwise. */
    suspend fun submitCode(code: String): String? {
        val td = current ?: return L.errorsNotConnected
        // Copied rather than rebuilt: the delivery route, digit count and resend timer live here.
        val asked = _auth.value as? AuthState.Code ?: AuthState.Code("")
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) { td.checkAuthenticationCode(code.trim()) }
            ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                noteWait(result.message)
                _auth.value = asked.copy(wrong = true)
                when {
                    result.message.contains("PHONE_CODE_INVALID") -> L.errorsWrongCode
                    result.message.contains("PHONE_CODE_EXPIRED") -> L.errorsCodeExpired
                    else -> Failures.humanise(result.message)
                }
            }
        }
    }

    /** Asks Telegram to send the code again, by whatever route it has decided is next. */
    suspend fun resendCode(): String? {
        val td = current ?: return L.errorsNotConnected
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) { td.resendAuthenticationCode() } ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                noteWait(result.message)
                if (result.message.contains("SEND_CODE_UNAVAILABLE")) L.signinErrorCodeUnavailable else Failures.humanise(result.message)
            }
        }
    }

    /** Gives Telegram the email address it asked for; it mails a code there next. */
    suspend fun submitEmailAddress(address: String): String? {
        val td = current ?: return L.errorsNotConnected
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) { td.setAuthenticationEmailAddress(address.trim()) }
            ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                _auth.value = AuthState.EmailAddress(wrong = true)
                if (result.message.contains("EMAIL_INVALID")) L.errorsEmailInvalid else Failures.humanise(result.message)
            }
        }
    }

    /** Submits the code Telegram mailed. Null on success, an error otherwise. */
    suspend fun submitEmailCode(code: String): String? {
        val td = current ?: return L.errorsNotConnected
        val asked = _auth.value as? AuthState.EmailCode ?: AuthState.EmailCode("")
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) {
            td.checkAuthenticationEmailCode(EmailAddressAuthenticationCode(code.trim()))
        } ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                _auth.value = asked.copy(wrong = true)
                when {
                    result.message.contains("CODE_INVALID") -> L.errorsWrongCode
                    result.message.contains("CODE_EXPIRED") -> L.errorsCodeExpired
                    else -> Failures.humanise(result.message)
                }
            }
        }
    }

    /** Submits the two-step verification password. Null on success, an error otherwise. */
    suspend fun submitPassword(password: String): String? {
        val td = current ?: return L.errorsNotConnected
        val asked = _auth.value as? AuthState.Password ?: AuthState.Password("")
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) { td.checkAuthenticationPassword(password) }
            ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                noteWait(result.message)
                _auth.value = asked.copy(wrong = true)
                if (result.message == "PASSWORD_HASH_INVALID") L.errorsWrongPassword else Failures.humanise(result.message)
            }
        }
    }

    private suspend fun sendParameters(td: TdlClient) {
        val paths = currentPaths ?: return
        if (!credentials.present) {
            _auth.value = AuthState.Failed(L.errorsNoApiCredentials)
            return
        }
        val result = td.setTdlibParameters(
            useTestDc = false,
            databaseDirectory = paths.databaseDir.absolutePath,
            filesDirectory = paths.filesDir.absolutePath,
            databaseEncryptionKey = ByteArray(0),
            useFileDatabase = true,
            useChatInfoDatabase = true,
            useMessageDatabase = true,
            useSecretChats = false,
            apiId = credentials.apiId,
            apiHash = credentials.apiHash,
            systemLanguageCode = "en",
            deviceModel = device.model,
            systemVersion = device.systemVersion,
            applicationVersion = device.appVersion,
        )
        if (result is TdlResult.Failure) {
            _auth.value = AuthState.Failed(L.errorsTdlibParameters(result.message))
        }
    }

    private suspend fun onReady(td: TdlClient) {
        // Cheap wins on a small phone: no animated emoji or background chatter we never render.
        runCatching {
            td.setOption("disable_animated_emoji", OptionValueBoolean(true))
            td.setOption("ignore_background_updates", OptionValueBoolean(true))
            td.setOption("message_unload_delay", OptionValueInteger(300))
        }
    }

    suspend fun logOut() {
        _auth.value = AuthState.Connecting
        runCatching { current?.logOut() }
    }

    /** A new QR code on request: the sign in starts over with the QR method kept. */
    suspend fun refreshQrCode() = restartSignIn(SignInMethod.Qr)

    /**
     * Abandons a half-finished sign in and goes back to the choice of method, or straight to
     * [next]. Logging out here throws away an attempt rather than an account.
     */
    suspend fun restartSignIn(next: SignInMethod = SignInMethod.Undecided) {
        method = next
        methodAfterRestart = next
        _auth.value = AuthState.Connecting
        // No client to log out of: the last one could not start, and this is the Try again.
        clientRetry?.let {
            it.complete(Unit)
            return
        }
        runCatching { current?.logOut() }
    }

    /**
     * Starts a new client when the sign in has been stuck on Connecting ([signInStalled]). A
     * request TDLib cannot send stays queued, so the client is closed and a fresh one opened over
     * the same database, keeping the chosen method. If TDLib never reports the old client closed,
     * the new one is started anyway after [RESTART_TIMEOUT_MS].
     */
    suspend fun retryConnecting() {
        _signInStalled.value = false
        connectAttempt.value++
        clientRetry?.let {
            it.complete(Unit)
            return
        }
        val td = current ?: return
        methodAfterRestart = method
        retiring = td
        val before = _session.value?.generation
        val signal = closedSignal
        runCatching { td.close() }
        val replaced = withTimeoutOrNull(RESTART_TIMEOUT_MS) {
            _session.first { it != null && it.generation != before }
        }
        if (replaced == null) {
            Logger.w(TAG, "TDLib did not report the stalled client closed; starting a new one anyway")
            signal?.complete(Unit)
        }
    }

    /** The current session once signed in. */
    suspend fun awaitAuthorizedSession(): TdSession =
        combine(_session.filterNotNull(), _auth) { session, auth -> session.takeIf { auth is AuthState.Ready } }
            .filterNotNull()
            .first()

    /** The generation of the client of the moment, or null when there is none; file ids belong to it. */
    fun currentGeneration(): Long? = _session.value?.generation

    internal fun isCurrent(session: TdSession): Boolean =
        current === session.client && generation.get() == session.generation && _auth.value is AuthState.Ready

    private const val SIGN_IN_STALL_MS = 20_000L
    private const val MAX_FOLDER_TITLE = 64
    private const val SUBMIT_TIMEOUT_MS = 30_000L
    private const val RESTART_TIMEOUT_MS = 10_000L
    private const val LOOKUP_TIMEOUT_MS = 10_000L

    /** A wait longer than a day is shown as a day; the screen asks again after it anyway. */
    private const val MAX_WAIT_SECONDS = 86_400

    /** A layout longer than any number is not one, and is ignored. */
    private const val MAX_PATTERN = 32
}

/** A client plus its identity, so late work from a logged-out client cannot update the UI. */
class TdSession internal constructor(
    val client: TdlClient,
    val generation: Long,
) {
    fun isCurrent(): Boolean = Td.isCurrent(this)
}
