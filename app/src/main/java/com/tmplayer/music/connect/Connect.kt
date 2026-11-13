package com.tmplayer.music.connect

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.tmplayer.music.BuildConfig
import com.tmplayer.music.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connect, the phone as a remote for the TV app: what this install knows about it. The TV listens
 * and keeps its paired phones ([phones]); a phone keeps its paired TVs ([tvs]). The protocol, the
 * pairing and the security are in core (`com.tmplayer.music.connect`); the Android glue is here.
 *
 * [available] is false in a store build, where the screens are never shown and [TvConnect] never
 * starts: no server, no advertising, no discovery.
 */
object Connect {
    const val ENABLED_KEY = "connect_enabled"
    private const val ACTIVE_TV_KEY = "connect_active_tv"

    /** True in the regular APK; a store build (`-PtmStore=true`) has no Connect. */
    val available: Boolean get() = BuildConfig.CONNECT

    private lateinit var prefs: Prefs
    private val _enabled = MutableStateFlow(false)

    /** Whether the TV may listen for phones; the Settings switch. Default on for a TV, off elsewhere. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Paired phones (kept by a TV). */
    lateinit var phones: PairingStore
        private set

    /** Paired TVs (kept by a phone). */
    lateinit var tvs: PairingStore
        private set

    lateinit var deviceId: String
        private set

    var deviceName: String = "TMPlayer Music"
        private set

    @Volatile
    private var ready = false

    /** True once [init] ran: not in a demo build's start, nor in a store build. */
    val isReady: Boolean get() = ready

    /** Reads the saved pairings and choices; once, from the application's `onCreate` or the first screen. */
    @Synchronized
    fun init(context: Context, tv: Boolean) {
        if (ready || !available) return
        val app = context.applicationContext
        prefs = Prefs(app)
        phones = PairingStore(prefs, PairingStore.PHONES_KEY)
        tvs = PairingStore(prefs, PairingStore.TVS_KEY)
        deviceId = PairingStore.deviceId(prefs)
        deviceName = nameOf(app)
        _enabled.value = prefs.getBoolean(ENABLED_KEY, tv)
        ready = true
    }

    fun setEnabled(on: Boolean) {
        if (!ready) return
        prefs.putBoolean(ENABLED_KEY, on)
        _enabled.value = on
    }

    private val none = MutableStateFlow<List<Paired>>(emptyList()).asStateFlow()

    /** The paired TVs, safe to read before [init] (and in a demo or a store build, where it is always empty). */
    val tvList: StateFlow<List<Paired>> get() = if (ready) tvs.list else none

    /** The TV the phone sends to and controls: the last one used, else the first paired. Null with none. */
    fun activeTv(): Paired? {
        if (!ready) return null
        val list = tvs.list.value
        val saved = prefs.getString(ACTIVE_TV_KEY)
        return list.firstOrNull { it.id == saved } ?: list.firstOrNull()
    }

    fun setActiveTv(id: String) {
        if (ready) prefs.putString(ACTIVE_TV_KEY, id)
    }

    /** The name this device goes by: the one the user gave it in Android's settings, else its model. */
    private fun nameOf(context: Context): String {
        val given = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        return (given?.takeIf { it.isNotBlank() } ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: "TMPlayer Music").trim().take(40)
    }
}
