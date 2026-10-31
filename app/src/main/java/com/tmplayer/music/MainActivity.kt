package com.tmplayer.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tmplayer.music.ui.theme.enableBlackEdgeToEdge
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.SupportChecks
import com.tmplayer.music.data.SupportReminder
import com.tmplayer.music.diagnostics.CrashOffer
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.ui.AppRoot
import com.tmplayer.music.ui.library.SupportAskHost
import com.tmplayer.music.ui.theme.MusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Always black: transparent system bars with light icons, in either device mode.
        enableBlackEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A debug build's demo library takes the screen (app/src/debug); never in a release.
        if (BuildHooks.takeOver(this, intent)) return
        PlayerConnection.connect(this)
        if (savedInstanceState == null) handleLaunch(intent)
        setContent {
            // The one look: pure black, the same on every phone.
            MusicTheme {
                // On a TV, everything inside the overscan-safe area; a phone is unchanged.
                com.tmplayer.music.ui.tv.TvFrame {
                    AppRoot()
                    CrashOffer()
                    SupportAskHost()
                    // The update check when the app opens and its popup; nothing in a store build.
                    com.tmplayer.music.ui.update.UpdateHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildHooks.takeOver(this, intent)) return
        handleLaunch(intent)
    }

    /**
     * A press on the sponsor reminder opens the Buy Me a Coffee window once the library is free for
     * it. A debug build can force the window, to check it by eye:
     * `adb shell am start -n com.tmplayer.music/.MainActivity --ez support_reminder true`.
     */
    private fun handleLaunch(intent: Intent?) {
        if (intent?.getStringExtra(SupportChecks.EXTRA_OPEN) == SupportChecks.OPEN_SUPPORT) SupportReminder.requestOpen()
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_SUPPORT_REMINDER, false) == true) SupportReminder.forced = true
    }

    // The tag reader's background reading (documents, trickle) runs only while the app is on screen.
    override fun onStart() {
        super.onStart()
        Library.setForeground(true)
    }

    override fun onStop() {
        Library.setForeground(false)
        super.onStop()
    }

    companion object {
        /** Debug builds only: show the support window at the next chance. See [SupportReminder.forced]. */
        const val EXTRA_SUPPORT_REMINDER = "support_reminder"
    }
}
