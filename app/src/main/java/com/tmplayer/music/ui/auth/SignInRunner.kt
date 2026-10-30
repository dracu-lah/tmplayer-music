// Adapted from TMPlayer (GPL-3.0), app/src/main/java/com/tmplayer/ui/auth/LoginScreen.kt
// (Submission): here the request outlives the screen.
package com.tmplayer.music.ui.auth

import com.tmplayer.music.data.BundledCountries
import com.tmplayer.music.data.Country
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Td
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.JobFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Which sign in request is on its way, so only its own button shows it busy. */
enum class SignInTask { Phone, Code, Resend, Password, EmailAddress, EmailCode }

/** What the last request answered, for the pane that sent it. */
data class SignInFailure(val task: SignInTask, val message: String) {
    /** Telegram did not answer in time: the button offers Try again rather than the same words. */
    val noAnswer: Boolean get() = message == L.errorsNoAnswer
}

/**
 * Runs the sign in's requests on a scope of the app's, not the screen's.
 *
 * The activity is recreated on a rotation, and a request launched from the screen's own scope
 * would be cancelled with it: the button would come back enabled while TDLib still had the number,
 * and a second press would ask Telegram for a second code. Here the request, its busy flag and its
 * answer all outlive the screen, so a rotation, the app going to the background, or a double tap
 * leaves exactly one request on its way. Failures go through [JobFailures], never up to the
 * thread.
 */
object SignInRunner {
    private val scope = JobFailures.scope("SignIn", Dispatchers.Main.immediate)

    private val _busy = MutableStateFlow<SignInTask?>(null)
    val busy: StateFlow<SignInTask?> = _busy.asStateFlow()

    /** When the request in [busy] was sent, wall clock milliseconds. */
    @Volatile
    var busySince: Long = 0L
        private set

    private val _failure = MutableStateFlow<SignInFailure?>(null)
    val failure: StateFlow<SignInFailure?> = _failure.asStateFlow()

    /** The last request Telegram took, numbered so a screen can tell a new one from one it saw. */
    data class Success(val task: SignInTask, val id: Long)

    private val _lastSuccess = MutableStateFlow<Success?>(null)
    val lastSuccess: StateFlow<Success?> = _lastSuccess.asStateFlow()
    private var successes = 0L

    init {
        // A new step is a clean page: the last step's error belongs to it. A wrong code keeps its
        // step (the same state, marked wrong), so its error stays.
        scope.launch {
            Td.auth.map { it::class }.distinctUntilChanged().drop(1).collect { _failure.value = null }
        }
    }

    /**
     * Sends one request; false, and nothing sent, while another is on its way. [block] answers
     * with an error to show, or null when Telegram took it (the next state follows from TDLib).
     */
    fun run(task: SignInTask, block: suspend () -> String?): Boolean {
        if (!_busy.compareAndSet(null, task)) return false
        busySince = System.currentTimeMillis()
        _failure.value = null
        scope.launch {
            var message: String? = null
            var answered = false
            try {
                message = block()
                answered = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = Failures.humanise(e)
            } finally {
                _failure.value = message?.let { SignInFailure(task, it) }
                if (answered && message == null) _lastSuccess.value = Success(task, ++successes)
                _busy.value = null
            }
        }
        return true
    }

    /** A step that answers with a new state rather than an error (choose, start over, retry). */
    fun fire(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    fun clearFailure() {
        _failure.value = null
    }

    /**
     * The countries for the number step: the list shipped with the app at once, Telegram's own
     * the moment it answers. Asked once per pane appearance until it has answered, so a missing
     * line costs one bounded call ([Td.countries]) per visit and nothing runs by itself.
     */
    private val _countries = MutableStateFlow(BundledCountries.all)
    val countries: StateFlow<List<Country>> = _countries.asStateFlow()

    @Volatile
    private var telegramList = false
    private var loading: Job? = null

    fun loadCountries() {
        if (telegramList || loading?.isActive == true) return
        loading = scope.launch {
            val list = Td.countries()
            if (list.isNotEmpty()) {
                _countries.value = list
                telegramList = true
            }
        }
    }
}
