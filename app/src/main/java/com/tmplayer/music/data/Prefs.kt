package com.tmplayer.music.data

import android.content.Context
import android.content.SharedPreferences
import com.tmplayer.music.data.CachePolicy

/**
 * The few flags the app keeps outside the library (has the user seen Welcome, picked chats once),
 * and the store the library keeps the user's choices in ([LibraryChoices]: the Pause, mobile data,
 * the clip switches, the data used, each chat page's order).
 */
class Prefs(context: Context) : KeyValues {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(com.tmplayer.music.BuildHooks.prefsFile("app"), Context.MODE_PRIVATE)

    var welcomed: Boolean
        get() = prefs.getBoolean(WELCOMED, false)
        set(value) = prefs.edit().putBoolean(WELCOMED, value).apply()

    /** Set once the picker was finished, even with nothing picked, so it is not forced again. */
    var pickerDone: Boolean
        get() = prefs.getBoolean(PICKER_DONE, false)
        set(value) = prefs.edit().putBoolean(PICKER_DONE, value).apply()

    /** The notification permission is asked once, on the first play; never nagged again. */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean(NOTIFICATIONS_ASKED, false)
        set(value) = prefs.edit().putBoolean(NOTIFICATIONS_ASKED, value).apply()

    /** The cache ceiling the user chose, in bytes; the device's default until they choose. */
    fun cacheCeiling(weakDevice: Boolean): Long = prefs.getLong(CACHE_CEILING, CachePolicy.defaultCeiling(weakDevice))

    fun setCacheCeiling(bytes: Long) = prefs.edit().putLong(CACHE_CEILING, bytes).apply()

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()

    private companion object {
        const val CACHE_CEILING = "cache_ceiling"
        const val NOTIFICATIONS_ASKED = "notifications_asked"
        const val WELCOMED = "welcomed"
        const val PICKER_DONE = "picker_done"
    }
}
