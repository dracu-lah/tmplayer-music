package com.tmplayer.music.i18n

import com.tmplayer.music.data.KeyValues
import com.tmplayer.music.data.MemoryKeyValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The language the user picked in Settings: a registry tag, or "" for "follow the device" (the
 * default). Held in memory (and "") until [start] gives it the app's store, like `HapticSetting`.
 * Not part of the library backup: that file carries favourites, playlists and plays only, and the
 * device's own language is the better default on a new phone.
 */
object LanguageSetting {
    /** The key in the app's store, for code that must read it before [start] (an activity's base context). */
    const val KEY = "ui_language"

    private var store: KeyValues = MemoryKeyValues()
    private val state = MutableStateFlow("")

    /** The saved tag, or "" for the system's language. */
    val saved: StateFlow<String> get() = state.asStateFlow()

    fun start(store: KeyValues) {
        this.store = store
        state.value = clean(runCatching { store.getString(KEY) }.getOrNull())
    }

    fun set(tag: String) {
        val value = clean(tag)
        runCatching { store.putString(KEY, value) }
        state.value = value
    }

    /** A stored value that is not a known tag (a damaged file, a newer app's language) reads as the system's. */
    internal fun clean(raw: String?): String = raw?.let { Languages.find(it)?.tag } ?: ""
}
