package com.tmplayer.music.ui.library

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tmplayer.music.shell.LibraryShelf
import com.tmplayer.music.shell.MainTab
import com.tmplayer.music.ui.theme.MusicTheme

// Previews of the shell's bars on the black theme, at font scale 1.3 and sideways. Fixed colours (a
// preview has no wallpaper); placeholder text only.

@Composable
private fun BarsSample(dark: Boolean) {
    MusicTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                ShellTopBar(title = null, onBack = null, onSearch = {}) {
                    AccountMenu(onSettings = {}, onManage = {}, onSendLogs = {}, onAbout = {}, onLogOut = {})
                }
                LibraryChips(LibraryShelf.Albums, onSelect = {}, grid = true, onGrid = {})
                ShellTopBar(title = "Album title that runs long", onBack = {}, onSearch = {}) {}
                ShellBottomBar(MainTab.Home, sheet = null, onSelect = {})
            }
        }
    }
}

@Preview(name = "Shell bars, dark", widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BarsDark() = BarsSample(dark = true)

@Preview(name = "Shell bars, font 1.3", widthDp = 360, fontScale = 1.3f)
@Composable
private fun BarsLargeText() = BarsSample(dark = false)

@Preview(name = "Shell rail, landscape", widthDp = 860, heightDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RailLandscape() {
    MusicTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                ShellRail(MainTab.Library, sheet = null, onSelect = {})
                Column {
                    ShellTopBar(title = null, onBack = null, onSearch = {}) {}
                    LibraryChips(LibraryShelf.Songs, onSelect = {}, grid = null, onGrid = {})
                    Text("List")
                }
            }
        }
    }
}
