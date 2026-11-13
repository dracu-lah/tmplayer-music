package com.tmplayer.music.desktop.connect

import com.tmplayer.music.connect.PairingStore
import com.tmplayer.music.data.Library
import com.tmplayer.music.desktop.DesktopApp
import com.tmplayer.music.desktop.player.TdTrackSource
import com.tmplayer.music.platform.JobFailures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * This computer as a Connect target (a phone is the remote), wired to the app: the Settings switch
 * (off by default on the desktop, kept in the settings file), the paired phones and the host. The
 * player and the server are made only when the switch is on or the Settings screen shows them, so a
 * computer that never uses Connect pays nothing for it.
 */
object DesktopConnect {
    const val ENABLED_KEY = "connect_enabled"

    private val _enabled = MutableStateFlow(DesktopApp.prefs.getBoolean(ENABLED_KEY, false))

    /** Whether a phone may control this computer; the Settings switch. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** This install's id, as the phone knows it from its pairing. */
    val deviceId: String by lazy { PairingStore.deviceId(DesktopApp.prefs) }

    /** Paired phones; readable without starting anything. */
    val phones: PairingStore by lazy { PairingStore(DesktopApp.prefs, PairingStore.PHONES_KEY) }

    @Volatile
    private var made = false

    val host: DesktopConnectHost by lazy {
        val scope = JobFailures.scope("DesktopConnect", Dispatchers.Default)
        DesktopConnectHost(
            handler = DesktopConnectHandler(DesktopApp.player, TdTrackSource, { Library.ready.value }, scope),
            phones = phones,
            deviceId = deviceId,
            deviceName = computerName(),
            advert = JmdnsAdvert(),
            scope = scope,
        ).also { made = true }
    }

    /** The name this computer is listed under on a phone: its host name, never a DNS lookup. */
    fun computerName(): String {
        val fromEnv = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: System.getenv("HOST")
        val fromFile = runCatching { File("/etc/hostname").takeIf { it.isFile && it.length() < 256 }?.readText() }.getOrNull()
        return (fromEnv?.takeIf { it.isNotBlank() } ?: fromFile?.takeIf { it.isNotBlank() } ?: "Computer").trim().take(40)
    }

    /** From `DesktopApp.start`: listens at once when the switch was left on. */
    fun start() {
        if (_enabled.value) host.start()
    }

    fun setEnabled(on: Boolean) {
        DesktopApp.prefs.putBoolean(ENABLED_KEY, on)
        _enabled.value = on
        if (on) host.start() else if (made) host.stop()
    }

    /** The window is closing: the server stops and its phones are dropped. */
    fun stop() {
        if (made) host.stop()
    }
}
