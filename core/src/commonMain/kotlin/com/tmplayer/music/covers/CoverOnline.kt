package com.tmplayer.music.covers

import com.tmplayer.music.data.Library
import com.tmplayer.music.data.NetState
import com.tmplayer.music.data.Updates
import com.tmplayer.music.lyrics.LyricsOnline
import com.tmplayer.music.platform.Logger
import java.io.File

/**
 * The app's one cover client, started by the app with a folder in its cache directory. Until then (and
 * always in the debug demo, which never starts it) it is off: no lookup, no request.
 *
 * Its switch is the lyrics one, "Look up lyrics and covers online" ([LyricsOnline.choices]), on by
 * default. Its network rule is the tag reader's: Wi-Fi, or mobile data when the user said yes to it
 * ([com.tmplayer.music.data.LibraryChoices.mobileAllowed]).
 */
object CoverOnline {
    @Volatile
    var lookup: CoverLookup? = null
        private set

    /** Replaces the HTTP client, for tests; null for the platform's own. */
    @Volatile
    var httpOverride: CoverHttp? = null

    /** True once [start] ran: rows only look for an online cover after it. */
    val started: Boolean get() = lookup != null

    /** Whether lookups are on now (the user's switch). */
    val on: Boolean get() = started && runCatching { LyricsOnline.choices.online.value }.getOrDefault(false)

    fun start(dir: File) {
        if (lookup != null) return
        val http = CoverHttp { url, headers ->
            (httpOverride ?: UrlConnectionCoverHttp(CoverLookup.TIMEOUT_MS.toInt())).get(url, headers)
        }
        lookup = CoverLookup(
            http = http,
            cache = CoverDiskCache(dir),
            enabled = { runCatching { LyricsOnline.choices.online.value }.getOrDefault(false) },
            mayUseNetwork = {
                when (Library.network.value) {
                    NetState.Unmetered -> true
                    NetState.Metered -> Library.choices.mobileAllowed.value
                    NetState.Offline -> false
                }
            },
            userAgent = { "TMPlayerMusic/${runCatching { Updates.installedVersion }.getOrDefault("0")} (Android)" },
        )
    }

    /** The kept file for the generated cover key [artKey], from the device only; null when none or off. */
    fun cached(artKey: String): File? = runCatching { lookup?.cached(artKey) }.getOrNull()

    /** Where the cover of [artKey] would be kept, for a memory cache key; null before [start]. */
    fun pathOf(artKey: String): String? = lookup?.pathOf(artKey)

    /**
     * The cover of [artKey] from the device or, when allowed, the gateway. Resolves the key to one of
     * the library's tracks for its title, artist and album; a playlist key, or a key with no track, asks
     * nothing. Null when there is no cover to show. Cancelling the caller drops a queued request.
     */
    suspend fun find(artKey: String): File? {
        val lookup = lookup ?: return null
        if (!on) return null
        lookup.cached(artKey)?.let { return it }
        val track = try {
            Library.index().trackForCover(artKey)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG, "no track for a cover key", e)
            null
        } ?: return null
        return (lookup.lookup(CoverQuery.of(track, artKey)) as? CoverResult.Found)?.file
    }

    /** Forgets every kept cover (Clear saved covers, log out). */
    fun clear() {
        runCatching { lookup?.clear() }
    }

    fun sizeBytes(): Long = runCatching { lookup?.sizeBytes() ?: 0L }.getOrDefault(0L)

    private const val TAG = "CoverOnline"
}
