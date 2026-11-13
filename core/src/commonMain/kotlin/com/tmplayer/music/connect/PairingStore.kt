package com.tmplayer.music.connect

import com.tmplayer.music.data.KeyValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pairings of one side, kept in the app's private settings ([KeyValues]) under [key]. Reading
 * the list is cheap and always current ([list]); a change is written at once. The settings file is
 * app private and not backed up (`allowBackup` is off), which is where the secrets live.
 */
class PairingStore(private val values: KeyValues, private val key: String) {
    private val _list = MutableStateFlow(PairingBook.decode(values.getString(key)))
    val list: StateFlow<List<Paired>> = _list.asStateFlow()

    fun find(id: String): Paired? = _list.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(entry: Paired) = update(PairingBook.with(_list.value, entry))

    @Synchronized
    fun forget(id: String) = update(PairingBook.without(_list.value, id))

    /** Remembers where [id] was last found, as a hint for the next connect. */
    @Synchronized
    fun seen(id: String, host: String, port: Int) {
        val entry = find(id) ?: return
        if (entry.host == host && entry.port == port) return
        update(_list.value.map { if (it.id == id) it.copy(host = host, port = port) else it })
    }

    private fun update(next: List<Paired>) {
        _list.value = next
        values.putString(key, PairingBook.encode(next))
    }

    companion object {
        const val PHONES_KEY = "connect_paired_phones"
        const val TVS_KEY = "connect_paired_tvs"
        const val DEVICE_ID_KEY = "connect_device_id"

        /** This install's id, made once. */
        fun deviceId(values: KeyValues): String {
            val saved = values.getString(DEVICE_ID_KEY)
            if (saved != null && saved.length in 16..64 && saved.all { Character.digit(it, 16) >= 0 }) return saved
            return ConnectHandshake.newId().also { values.putString(DEVICE_ID_KEY, it) }
        }
    }
}
