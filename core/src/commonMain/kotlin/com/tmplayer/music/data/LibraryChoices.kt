package com.tmplayer.music.data

import com.tmplayer.music.library.ClipRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A small key value store the app keeps across runs (its Prefs on Android); in memory for tests. */
interface KeyValues {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** A [KeyValues] that forgets everything when the process ends. */
class MemoryKeyValues : KeyValues {
    private val map = HashMap<String, Any>()

    @Synchronized
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default

    @Synchronized
    override fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }

    @Synchronized
    override fun getLong(key: String, default: Long) = map[key] as? Long ?: default

    @Synchronized
    override fun putLong(key: String, value: Long) {
        map[key] = value
    }

    @Synchronized
    override fun getString(key: String) = map[key] as? String

    @Synchronized
    override fun putString(key: String, value: String) {
        map[key] = value
    }
}

/**
 * What the user chose about the library, kept across restarts: the scan and tag reader's Pause (a
 * paused reader stays paused after the app is closed), whether tag reading may use mobile data and
 * whether that was asked, the short clip and voice file switches, the bytes tag reading has fetched
 * since install, and each chat page's order. Read once at start, written on every change; the byte
 * count at most every [BYTES_SAVE_MS], as reads add to it many times a minute.
 */
class LibraryChoices(private val store: KeyValues, private val now: () -> Long = System::currentTimeMillis) {
    private val _paused = MutableStateFlow(read { store.getBoolean(PAUSED, false) } ?: false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _mobileAllowed = MutableStateFlow(read { store.getBoolean(MOBILE_ALLOWED, false) } ?: false)
    val mobileAllowed: StateFlow<Boolean> = _mobileAllowed.asStateFlow()

    private val _mobileAsked = MutableStateFlow((read { store.getBoolean(MOBILE_ASKED, false) } ?: false) || _mobileAllowed.value)
    val mobileAsked: StateFlow<Boolean> = _mobileAsked.asStateFlow()

    private val _showShort = MutableStateFlow(read { store.getBoolean(SHOW_SHORT, false) } ?: false)
    val showShort: StateFlow<Boolean> = _showShort.asStateFlow()

    private val _showVoice = MutableStateFlow(read { store.getBoolean(SHOW_VOICE, false) } ?: false)
    val showVoice: StateFlow<Boolean> = _showVoice.asStateFlow()

    private val _tagBytes = MutableStateFlow((read { store.getLong(TAG_BYTES, 0) } ?: 0L).coerceAtLeast(0))
    val tagBytes: StateFlow<Long> = _tagBytes.asStateFlow()

    /**
     * Whether the reading progress banner shows above the library. On for a new install; Hide on the
     * banner turns it off, the Settings switch back on. Off keeps it off for every later scan, and
     * never hides what needs the user ([LibraryStatus.banner]).
     */
    private val _showProgress = MutableStateFlow(read { store.getBoolean(SHOW_PROGRESS, true) } ?: true)
    val showProgress: StateFlow<Boolean> = _showProgress.asStateFlow()

    private var bytesSavedAt = 0L

    fun setShowProgress(on: Boolean) {
        _showProgress.value = on
        write { store.putBoolean(SHOW_PROGRESS, on) }
    }

    /** The [ClipRules] mask the two switches make. */
    val hiddenMask: Int get() = ClipRules.mask(_showShort.value, _showVoice.value)

    fun setPaused(on: Boolean) {
        _paused.value = on
        write { store.putBoolean(PAUSED, on) }
    }

    /** The user's answer about mobile data; any answer counts as asked. */
    fun setMobileAllowed(allowed: Boolean) {
        _mobileAllowed.value = allowed
        _mobileAsked.value = true
        write {
            store.putBoolean(MOBILE_ALLOWED, allowed)
            store.putBoolean(MOBILE_ASKED, true)
        }
    }

    fun setShowShort(on: Boolean) {
        _showShort.value = on
        write { store.putBoolean(SHOW_SHORT, on) }
    }

    fun setShowVoice(on: Boolean) {
        _showVoice.value = on
        write { store.putBoolean(SHOW_VOICE, on) }
    }

    /** Adds [bytes] fetched by tag reading; saved at most every [BYTES_SAVE_MS]. */
    @Synchronized
    fun addTagBytes(bytes: Long) {
        if (bytes <= 0) return
        val total = _tagBytes.value + bytes
        _tagBytes.value = total
        val at = now()
        if (at - bytesSavedAt >= BYTES_SAVE_MS) {
            bytesSavedAt = at
            write { store.putLong(TAG_BYTES, total) }
        }
    }

    /** Saves the byte count now, for the app going to the background. */
    @Synchronized
    fun flush() {
        bytesSavedAt = now()
        write { store.putLong(TAG_BYTES, _tagBytes.value) }
    }

    /** The order [chatId]'s page was last shown in; oldest first until the user picks. */
    fun chatOrder(chatId: Long): ChatOrder =
        read { store.getString(ORDER + chatId) }?.let { name -> ChatOrder.entries.firstOrNull { it.name == name } } ?: ChatOrder.OldestFirst

    fun setChatOrder(chatId: Long, order: ChatOrder) {
        write { store.putString(ORDER + chatId, order.name) }
    }

    /** A store that cannot be read (a damaged file) gives the defaults, never a crash. */
    private inline fun <T> read(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    private inline fun write(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Kept for this run; the next start reads the default.
        }
    }

    companion object {
        const val BYTES_SAVE_MS = 10_000L
        private const val PAUSED = "library_paused"
        private const val MOBILE_ALLOWED = "tags_mobile_allowed"
        private const val MOBILE_ASKED = "tags_mobile_asked"
        private const val SHOW_SHORT = "show_short_clips"
        private const val SHOW_VOICE = "show_voice_files"
        private const val TAG_BYTES = "tag_bytes"
        private const val SHOW_PROGRESS = "show_reading_progress"
        private const val ORDER = "chat_order_"
    }
}
