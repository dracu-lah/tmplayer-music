package com.tmplayer.music.data

import android.content.Context
import android.content.SharedPreferences

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

    private companion object {
        const val WELCOMED = "welcomed"
        const val PICKER_DONE = "picker_done"
    }
}
