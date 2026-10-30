package com.tmplayer.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tmplayer.music.data.Library
import com.tmplayer.music.diagnostics.CrashOffer
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.ui.AppRoot
import com.tmplayer.music.ui.theme.MusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PlayerConnection.connect(this)
        setContent {
            // The device's theme: dark or light as the system is, wallpaper colours on Android 12+.
            MusicTheme {
                AppRoot()
                CrashOffer()
            }
        }
    }

    // The tag reader's background trickle runs only while the app is on screen or playing.
    override fun onStart() {
        super.onStart()
        Library.setForeground(true)
    }

    override fun onStop() {
        Library.setForeground(false)
        super.onStop()
    }
}
