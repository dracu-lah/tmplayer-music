package com.tmplayer.music.data

import android.content.Context
import android.content.SharedPreferences
import com.tmplayer.music.data.CachePolicy

/** The few flags the app keeps outside the library: has the user seen Welcome, picked chats once. */
class Prefs(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)

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

    private companion object {
        const val CACHE_CEILING = "cache_ceiling"
        const val NOTIFICATIONS_ASKED = "notifications_asked"
        const val WELCOMED = "welcomed"
        const val PICKER_DONE = "picker_done"
    }
}
