package com.tmplayer.music

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource

/**
 * The release build's side of the hooks the debug build fills in (`app/src/debug`, the demo library
 * for screenshots). Here every hook does nothing, so R8 folds the calls away and no debug code ships.
 */
object BuildHooks {
    /** True when the debug build started something else in place of the app (never here). */
    @Suppress("UNUSED_PARAMETER")
    fun startInstead(app: Application): Boolean = false

    /** True when the debug build took the activity over (never here). */
    @Suppress("UNUSED_PARAMETER")
    fun takeOver(activity: ComponentActivity, intent: Intent?): Boolean = false

    /** The name of the app's preferences file: [name] as it is. */
    fun prefsFile(name: String): String = name

    /** The name of a file of the app's own in its files folder: [name] as it is. */
    fun appFile(name: String): String = name

    /** A source for the player's `tdfile` audio in place of Telegram's (none here). */
    @OptIn(UnstableApi::class)
    fun audioSource(): DataSource? = null

    /** Whether the lyrics page names where its lines came from (always here). */
    fun lyricsSourceShown(): Boolean = true

    /** Whether Now Playing opens on its lyrics page (never here). */
    fun takeLyricsAtStart(): Boolean = false

    /** Whether the account menu opens by itself (never here). */
    fun takeAccountMenuAtStart(): Boolean = false
}
