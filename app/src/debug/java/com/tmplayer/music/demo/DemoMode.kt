package com.tmplayer.music.demo

/*
 * The demo library: a debug-only mode for marketing screenshots, with no real chat content.
 *
 * Everything in it is invented ([DemoCatalog]): 8 artists, 13 albums (one of them live), 84 songs in
 * 89 files over 5 chats, three playlists, favourites, plays and Speed dial cards left part way. The
 * covers are drawn in code ([DemoCovers]) and every track plays a quiet generated tone exactly as
 * long as its invented duration ([DemoAudioSource]).
 *
 * It never touches the user's own data: its index lives in `files/demo/library` (made afresh on each
 * start), its preferences in `app_demo.xml`, its saved queue in `demo_queue.json`, its library
 * choices in memory. Sign in, Welcome and the chat picker are skipped, and TDLib is never started in
 * a demo process. The release build has none of this: `app/src/release/.../BuildHooks.kt` is a stub.
 *
 * Start it with:
 *
 *     adb shell am start -n com.tmplayer.music.debug/com.tmplayer.music.MainActivity --ez demo true
 *
 * A process that is not in demo mode arms it (the marker file `files/demo_mode`) and restarts
 * itself, so the demo always runs in a process that never started Telegram. `scripts/screenshots.sh`
 * arms the marker with `run-as` first, so each shot starts straight in the demo.
 *
 * Intent extras (all optional except `demo`):
 *
 *     --ez demo true            enter the demo (or stay in it); `--ez demo false` leaves it
 *     --es demo_screen NAME     home (default), chats, songs, albums, artists, playlists, favourites,
 *                               album, artist, playlist, settings, nowplaying, queue
 *     --ez demo_dark true       dark theme; false for light; left out, the system's
 *     --ez demo_dynamic true    wallpaper colours on Android 12+ (off by default: the app's own
 *                               palette, the same on every phone)
 *     --ez demo_playing false   the player paused (playing by default)
 *     --es demo_album TITLE     the album for `album` (default Northern Glass)
 *
 * Opening the app from the launcher while the demo runs leaves the demo and restarts into the
 * user's own library. A tap on the player's notification stays in the demo.
 */

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.MemoryKeyValues
import com.tmplayer.music.data.Prefs
import com.tmplayer.music.data.Storage
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.shell.LibraryShelf
import com.tmplayer.music.shell.MainTab
import com.tmplayer.music.shell.ShellChoices
import com.tmplayer.music.ui.library.LibraryHome
import com.tmplayer.music.ui.library.ShellModel
import com.tmplayer.music.ui.player.LocalPlayerSheet
import com.tmplayer.music.ui.player.PlayerSheetHost
import com.tmplayer.music.ui.player.QueueSheet
import com.tmplayer.music.ui.theme.MusicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference

internal object DemoMode {
    private const val TAG = "DemoMode"
    private const val MARKER = "demo_mode"

    const val EXTRA_DEMO = "demo"
    const val EXTRA_SCREEN = "demo_screen"
    const val EXTRA_DARK = "demo_dark"
    const val EXTRA_DYNAMIC = "demo_dynamic"
    const val EXTRA_PLAYING = "demo_playing"
    const val EXTRA_ALBUM = "demo_album"

    /** True in a process started as the demo; fixed for the life of the process. */
    @Volatile
    var active = false
        private set

    private val scope = JobFailures.scope("Demo", Dispatchers.IO)
    private val _loaded = MutableStateFlow(false)

    /** True once the demo index is filled and the library says it is ready. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** The options the queue was last started for, so turning the phone does not start it again. */
    private var playedFor: Options? = null
    private var shownIn = WeakReference<ComponentActivity>(null)

    private fun marker(context: Context) = File(context.applicationContext.filesDir, MARKER)

    /** The process start: the demo in place of the app when the marker is there. */
    fun startInstead(app: Application): Boolean {
        if (!marker(app).isFile) return false
        active = true
        Logger.i(TAG, "starting the demo library")
        val root = File(app.filesDir, "demo")
        val folder = File(root, "library").apply { mkdirs() }
        // A fresh index every start, so each run shows the same screens; the covers are kept.
        listOf("library.db", "library.db-wal", "library.db-shm", "account").forEach { File(folder, it).delete() }
        app.deleteFile("demo_queue.json")
        app.getSharedPreferences("app_demo", Context.MODE_PRIVATE).edit().clear().commit()
        Storage.cacheDir = File(root, "cache").apply { mkdirs() }
        Library.start(folder, MemoryKeyValues())
        scope.launch {
            DemoLibrary.fill(File(folder, "covers"))
            holdReady()
            Library.notifyChanged()
            _loaded.value = true
        }
        return true
    }

    /**
     * The library is ready only after a sign in, which the demo never has: its private flag is held
     * true from here (debug builds are not minified, so the name is there to find).
     */
    @Suppress("UNCHECKED_CAST")
    private fun holdReady() {
        val ready = runCatching {
            Library::class.java.getDeclaredField("_ready").apply { isAccessible = true }.get(Library) as MutableStateFlow<Boolean>
        }.onFailure { Logger.w(TAG, "could not mark the demo library ready", it) }.getOrNull() ?: return
        ready.value = true
        scope.launch { ready.collect { if (!it) ready.value = true } }
    }

    fun takeOver(activity: ComponentActivity, intent: Intent?): Boolean {
        val asked: Boolean? = intent?.takeIf { it.hasExtra(EXTRA_DEMO) }?.getBooleanExtra(EXTRA_DEMO, false)
        if (!active) {
            if (asked != true) return false
            runCatching { marker(activity).writeText("on") }
            restart(activity, intent)
            return true
        }
        val fromLauncher = intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        if (asked == false || (asked == null && fromLauncher)) {
            marker(activity).delete()
            restart(activity, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            return true
        }
        // A tap on the notification in an activity that shows the demo already: nothing to change.
        if (asked == null && shownIn.get() === activity) return true
        show(activity, Options.from(intent))
        return true
    }

    /** A new process for the activity, so the demo (or the app) starts from the process start. */
    private fun restart(activity: ComponentActivity, intent: Intent) {
        Logger.i(TAG, if (active) "leaving the demo" else "arming the demo")
        val next = Intent(intent).setClass(activity, activity.javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(next)
        activity.finish()
        Runtime.getRuntime().exit(0)
    }

    private fun show(activity: ComponentActivity, options: Options) {
        shownIn = WeakReference(activity)
        val bars = when (options.dark) {
            true -> SystemBarStyle.dark(Color.TRANSPARENT)
            false -> SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            null -> SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        PlayerConnection.connect(activity)
        options.screen.shelf?.let { shelf ->
            val choices = ShellChoices(Prefs(activity))
            choices.shelf = shelf
            choices.albumsGrid = true
        }
        val model = shellModel(activity)
        model.stack.clear()
        activity.setContent { DemoRoot(model, options) }
    }

    private fun shellModel(activity: ComponentActivity): ShellModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShellModel() as T
        }
        return ViewModelProvider(activity, factory)[ShellModel::class.java]
    }

    @Composable
    private fun DemoRoot(model: ShellModel, options: Options) {
        MusicTheme(dark = options.dark ?: isSystemInDarkTheme(), dynamic = options.dynamic) {
            val loaded by loaded.collectAsStateWithLifecycle()
            if (!loaded) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                return@MusicTheme
            }
            val state by PlayerConnection.state.collectAsStateWithLifecycle()
            var queueOpen by remember { mutableStateOf(false) }
            PlayerSheetHost {
                LibraryHome(onLogOut = {}, startTab = options.screen.tab)
                Driver(model, options, openQueue = { queueOpen = true })
            }
            if (queueOpen && state.count > 0) QueueSheet(onDismiss = { queueOpen = false })
        }
    }

    /** Opens the page asked for, starts the queue and opens Now Playing or the queue over it. */
    @Composable
    private fun Driver(model: ShellModel, options: Options, openQueue: () -> Unit) {
        val sheet = LocalPlayerSheet.current
        val state by PlayerConnection.state.collectAsStateWithLifecycle()
        LaunchedEffect(options) {
            val page = runCatching { DemoLibrary.pageFor(options) }.getOrNull() ?: return@LaunchedEffect
            if (model.stack.lastOrNull() != page) {
                model.stack.clear()
                model.stack += page
            }
        }
        LaunchedEffect(state.connected) {
            if (!state.connected || playedFor == options) return@LaunchedEffect
            playedFor = options
            runCatching { DemoLibrary.startQueue(options.playing) }.onFailure { Logger.w(TAG, "the demo queue did not start", it) }
        }
        val hasQueue = state.count > 0
        LaunchedEffect(hasQueue) {
            if (!hasQueue) return@LaunchedEffect
            if (options.screen == Screen.NowPlaying || options.screen == Screen.Queue) {
                // The sheet is composed once the queue is there; give it a moment to lay out.
                delay(600)
                sheet?.expand()
                if (options.screen == Screen.Queue) {
                    delay(900)
                    openQueue()
                }
            }
        }
    }

    /** What a demo launch shows. */
    data class Options(
        val screen: Screen,
        val dark: Boolean?,
        val dynamic: Boolean,
        val playing: Boolean,
        val album: String,
    ) {
        companion object {
            fun from(intent: Intent?): Options = Options(
                screen = Screen.of(intent?.getStringExtra(EXTRA_SCREEN)),
                dark = intent?.takeIf { it.hasExtra(EXTRA_DARK) }?.getBooleanExtra(EXTRA_DARK, false),
                dynamic = intent?.getBooleanExtra(EXTRA_DYNAMIC, false) ?: false,
                playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true,
                album = intent?.getStringExtra(EXTRA_ALBUM)?.trim()?.takeIf { it.isNotEmpty() } ?: DemoCatalog.PAGE_ALBUM,
            )
        }
    }

    /** The screens a demo launch can open, by their `demo_screen` name. */
    enum class Screen(val key: String, val tab: MainTab, val shelf: LibraryShelf? = null) {
        Home("home", MainTab.Home),
        Chats("chats", MainTab.Chats),
        Songs("songs", MainTab.Library, LibraryShelf.Songs),
        Albums("albums", MainTab.Library, LibraryShelf.Albums),
        Artists("artists", MainTab.Library, LibraryShelf.Artists),
        Playlists("playlists", MainTab.Library, LibraryShelf.Playlists),
        Favourites("favourites", MainTab.Library, LibraryShelf.Favourites),
        Album("album", MainTab.Library, LibraryShelf.Albums),
        Artist("artist", MainTab.Library, LibraryShelf.Artists),
        Playlist("playlist", MainTab.Library, LibraryShelf.Playlists),
        Settings("settings", MainTab.Home),
        NowPlaying("nowplaying", MainTab.Home),
        Queue("queue", MainTab.Home),
        ;

        companion object {
            fun of(name: String?): Screen {
                val key = name?.trim()?.lowercase()?.filter { it.isLetter() }.orEmpty()
                return entries.firstOrNull { it.key == key } ?: Home
            }
        }
    }
}
