package com.tmplayer.music

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.tmplayer.music.demo.DemoAudioSource
import com.tmplayer.music.demo.DemoMode

/**
 * The debug build's side of the build hooks: the demo library for screenshots ([DemoMode]). The
 * release build has the same object with every hook doing nothing (`app/src/release`).
 */
object BuildHooks {
    /** Starts the demo in place of the app when it is armed; true when it did. */
    fun startInstead(app: Application): Boolean = DemoMode.startInstead(app)

    /** Hands the activity to the demo (or in and out of it); true when the demo took it. */
    fun takeOver(activity: ComponentActivity, intent: Intent?): Boolean = DemoMode.takeOver(activity, intent)

    /** In the demo the preferences live in their own file, so the user's own are never touched. */
    fun prefsFile(name: String): String = if (DemoMode.active) "${name}_demo" else name

    /** In the demo the app's own files (the saved queue) get their own name. */
    fun appFile(name: String): String = if (DemoMode.active) "demo_$name" else name

    /** In the demo every track plays a generated tone of its own length; nothing asks Telegram. */
    @OptIn(UnstableApi::class)
    fun audioSource(): DataSource? = if (DemoMode.active) DemoAudioSource() else null
}
