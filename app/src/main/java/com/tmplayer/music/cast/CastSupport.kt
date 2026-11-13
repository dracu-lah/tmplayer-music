package com.tmplayer.music.cast

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import com.tmplayer.music.ui.tv.isTv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A Cast device the phone found on the Wi-Fi, as the Cast sheet lists it. */
data class CastDeviceInfo(
    val id: String,
    /** "Living room speaker". */
    val name: String,
    /** "Chromecast Audio", "Google Home", or what the device calls itself. */
    val model: String,
    val connected: Boolean = false,
    val connecting: Boolean = false,
)

/** Where the cast stands. Nothing is cast until the user picks a device. */
sealed interface CastState {
    data object Idle : CastState
    data class Connecting(val name: String) : CastState
    data class Connected(val name: String) : CastState

    /** The device did not take the connection; the sheet says so and offers Try again. */
    data class Failed(val name: String) : CastState
}

/** The device's volume, 0 to 1. [fixed]: the device takes none from a phone (its own remote sets it). */
data class CastVolume(val level: Double = 1.0, val muted: Boolean = false, val fixed: Boolean = false)

/** Whether this build and phone can cast, for Settings. */
enum class CastAvailability { Yes, NotInThisBuild, NoPlayServices }

/**
 * Casting as the screens see it, whichever build this is. The regular APK answers through Google's Cast
 * framework (src/cast); a store build and a television get [NoCastSupport], which offers nothing and
 * has none of the Cast classes behind it (AGENTS.md: the Cast SDK and the local audio server belong in the
 * regular APK only). Everything here is called from the main thread.
 */
interface CastSupport {
    val availability: CastAvailability
    val available: Boolean get() = availability == CastAvailability.Yes
    val devices: StateFlow<List<CastDeviceInfo>>
    val state: StateFlow<CastState>
    val volume: StateFlow<CastVolume>

    /** False while the phone has no Wi-Fi to find a device or to serve the audio on. */
    val onWifi: StateFlow<Boolean>

    /** Looks for devices while the sheet is open; [stopDiscovery] when it closes. */
    fun startDiscovery()
    fun stopDiscovery()

    /** The user picked [id]: connect, and move the queue over. */
    fun connect(id: String)

    /** Try again: the cast (if any) ends and the device last picked is connected to afresh. Nothing when none was picked. */
    fun retry()

    /** The user ended the cast: playback comes back to this phone, paused where it was. */
    fun endSession()

    fun setVolume(level: Double)
}

/** The stand-in: no casting. */
object NoCastSupport : CastSupport {
    private val none = MutableStateFlow(emptyList<CastDeviceInfo>())
    private val idle = MutableStateFlow<CastState>(CastState.Idle)
    private val volumeNow = MutableStateFlow(CastVolume())
    private val wifi = MutableStateFlow(true)
    override val availability: CastAvailability get() = CastBuild.noCastReason
    override val devices: StateFlow<List<CastDeviceInfo>> get() = none
    override val state: StateFlow<CastState> get() = idle
    override val volume: StateFlow<CastVolume> get() = volumeNow
    override val onWifi: StateFlow<Boolean> get() = wifi
    override fun startDiscovery() = Unit
    override fun stopDiscovery() = Unit
    override fun connect(id: String) = Unit
    override fun retry() = Unit
    override fun endSession() = Unit
    override fun setVolume(level: Double) = Unit
}

/** The one [CastSupport] of the process, made on first use from the main thread. */
object Cast {
    @Volatile
    private var support: CastSupport? = null

    /** A television has no use for casting: it gets none, and no button. */
    fun support(context: Context): CastSupport {
        support?.let { return it }
        synchronized(this) {
            support?.let { return it }
            val app = context.applicationContext
            val made = if (isTv(app)) NoCastSupport else runCatching { CastBuild.support(app) }.getOrDefault(NoCastSupport)
            support = made
            return made
        }
    }
}

/**
 * What the playback service gives the cast side to work with: the player that plays on this phone,
 * the data source factory that reads Telegram files (the same one the local player streams through),
 * and the service's scope.
 */
class CastHostParts(
    val context: Context,
    /** The player that plays on this phone; the Cast player wraps it. */
    val local: Player,
    val sources: DataSource.Factory,
    val scope: CoroutineScope,
)

/** The player the media session gets, and the handle on the cast behind it. A build with no casting gets the local player and [NoCastHost]. */
class CastSetup(val player: Player, val host: CastHost)

/** The playback service's side of casting. Nothing here connects by itself: only the user's pick does. */
interface CastHost {
    /**
     * The app was removed from recents. A cast ends with it (the phone's controls are gone, and the audio
     * server on the phone must not go on unattended): the queue comes back to this phone, paused. [done]
     * runs once that has happened (at once when nothing was cast).
     */
    fun onTaskRemoved(done: () -> Unit)

    /**
     * The sleep timer ran out. While casting, the receiver is paused and the cast ends, so the queue is
     * back on this phone, paused at the same place. Nothing when no cast is on.
     */
    fun onSleep() = Unit

    fun release()
}

/** Nothing to hand over. */
object NoCastHost : CastHost {
    override fun onTaskRemoved(done: () -> Unit) = done()
    override fun release() = Unit
}

/** The extras key a queue item carries its audio format under, so a cast can tell what the receiver can play. */
const val CAST_FORMAT_KEY = "tmplayer.castFormat"

/** The extras key a queue item carries its file size under (bytes), so the cast server knows the length without asking. */
const val CAST_SIZE_KEY = "tmplayer.castSize"

/**
 * The notices casting leaves. [last] is a short sentence (a track left out, casting stopped) for the Cast
 * sheet and a toast. [problem] is a failure the user has to act on (the receiver cannot reach this phone,
 * tracks keep failing): the Cast sheet shows it with Try again and Send logs, and the Cast button turns to
 * the error colour, until the next connect or until it is dismissed.
 */
object CastNotices {
    private val _last = MutableStateFlow<String?>(null)
    val last: StateFlow<String?> = _last.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    fun post(context: Context, text: String) {
        _last.value = text
        android.widget.Toast.makeText(context.applicationContext, text, android.widget.Toast.LENGTH_LONG).show()
    }

    /** A failure the user has to act on: kept until [clear], with a toast now. */
    fun problem(context: Context, text: String) {
        _problem.value = text
        post(context, text)
    }

    fun clear() {
        _last.value = null
        _problem.value = null
    }

    /** The user has seen the problem: the Cast button stops showing it. */
    fun dismissProblem() {
        _problem.value = null
    }
}
