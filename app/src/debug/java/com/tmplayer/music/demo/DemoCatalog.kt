package com.tmplayer.music.demo

import com.tmplayer.music.library.AudioFormat
import com.tmplayer.music.library.ChatPick
import com.tmplayer.music.library.TagState
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId

/**
 * The demo library's content, all of it invented: artists, albums, titles and chats. Nothing here
 * names a real artist, album or song (AGENTS.md "Writing"), and every number is fixed, so two runs
 * draw the same screens. Debug builds only.
 */
internal object DemoCatalog {

    /** One invented album: its cover is drawn from [style] and [palette] ([DemoCovers]). */
    class Album(
        val title: String,
        val artist: String,
        val year: Int,
        val chat: Long,
        val format: Quality,
        val style: Int,
        val palette: Int,
        /** Title and length in seconds, in track order. */
        val tracks: List<Pair<String, Int>>,
    ) {
        val coverKey: String get() = "demo_cover_${title.lowercase().filter { it.isLetterOrDigit() }}"
    }

    /** How a chat's files are encoded, for the format badges. */
    class Quality(val format: AudioFormat, val ext: String, val mime: String, val kbps: Int, val rate: Int, val bits: Int)

    val FLAC_HI = Quality(AudioFormat.FLAC, "flac", "audio/flac", 2_400, 96_000, 24)
    val FLAC = Quality(AudioFormat.FLAC, "flac", "audio/flac", 980, 44_100, 16)
    val MP3 = Quality(AudioFormat.MP3, "mp3", "audio/mpeg", 320, 44_100, 0)
    val M4A = Quality(AudioFormat.M4A, "m4a", "audio/mp4", 256, 44_100, 0)
    val OPUS = Quality(AudioFormat.OPUS, "opus", "audio/ogg", 160, 48_000, 0)

    const val ROAD_TRIP = -1_009_000_000_001L
    const val STUDY = -1_009_000_000_002L
    const val LIVE = -1_009_000_000_003L
    const val BEDROOM = -1_009_000_000_004L
    const val OLD_FAVOURITES = -1_009_000_000_005L

    val chats = listOf(
        ChatPick(ROAD_TRIP, "Road trip mixes"),
        ChatPick(STUDY, "Study loops"),
        ChatPick(LIVE, "Live sessions"),
        ChatPick(BEDROOM, "Bedroom demos"),
        ChatPick(OLD_FAVOURITES, "Old favourites"),
    )

    val albums = listOf(
        Album(
            "Northern Glass", "Halden Reach", 2021, OLD_FAVOURITES, MP3, style = 0, palette = 0,
            tracks = listOf(
                "Frost on the Signal" to 224, "Northern Glass" to 251, "Harbour Lights Fade" to 238, "Thin Ice Radio" to 199,
                "Cold Front Lullaby" to 283, "White Pines" to 207, "Aurora Static" to 262, "Last Ferry North" to 318,
            ),
        ),
        Album(
            "Low Tide Hours", "Halden Reach", 2023, OLD_FAVOURITES, FLAC, style = 1, palette = 1,
            tracks = listOf(
                "Low Tide Hours" to 246, "Saltwater Clocks" to 213, "Driftline" to 189, "Grey Gull Morning" to 271,
                "Undertow Letters" to 233, "Pier at Dusk" to 297, "Moon over Shale" to 255,
            ),
        ),
        Album(
            "Paper Satellites", "Coral Atlas", 2019, ROAD_TRIP, M4A, style = 2, palette = 2,
            tracks = listOf(
                "Paper Satellites" to 217, "Orbit of Small Things" to 242, "Tin Can Telescope" to 196, "Signal Bloom" to 229,
                "Weightless in June" to 264, "Cardboard Rockets" to 183, "Coming Home Slow" to 301,
            ),
        ),
        Album(
            "Slow Orbit", "Coral Atlas", 2022, LIVE, FLAC_HI, style = 3, palette = 3,
            tracks = listOf(
                "Slow Orbit" to 276, "Perigee" to 204, "Velvet Vacuum" to 248, "Starlit Cul de Sac" to 231,
                "Gravity Postcards" to 219, "Quiet Apogee" to 334,
            ),
        ),
        Album(
            "Quiet Engines", "Ostra Lane", 2020, STUDY, FLAC, style = 4, palette = 4,
            tracks = listOf(
                "Quiet Engines" to 258, "Copper Wire Hymn" to 236, "Night Shift Drift" to 289, "Idle Turbines" to 211,
                "Factory Rain" to 244, "Motorway Psalm" to 268, "Pilot Light" to 197,
            ),
        ),
        Album(
            "Summer Static", "Kite Harbour", 2024, ROAD_TRIP, MP3, style = 5, palette = 5,
            tracks = listOf(
                "Summer Static" to 203, "Sunburnt Cassette" to 226, "Coastline Radio" to 241, "Open Windows" to 188,
                "Lemon Haze" to 215, "Boardwalk Echoes" to 252, "Long Way Round" to 287,
            ),
        ),
        Album(
            "Lanterns", "Kite Harbour", 2018, ROAD_TRIP, M4A, style = 6, palette = 6,
            tracks = listOf(
                "Paper Lanterns" to 232, "Fireflies in Jars" to 209, "Late Train Home" to 265, "Lantern Street" to 221,
                "Small Hours" to 247, "Glow Worm Waltz" to 194,
            ),
        ),
        Album(
            "Amber Rooms", "Sable Orchard", 2021, BEDROOM, OPUS, style = 1, palette = 7,
            tracks = listOf(
                "Amber Rooms" to 239, "Dust in the Sunbeam" to 218, "Velvet Armchair" to 256, "Honey Light" to 201,
                "Wallpaper Flowers" to 274, "Attic Window" to 230, "Slow Kettle" to 186,
            ),
        ),
        Album(
            "Distant Weather", "Pale Meridian", 2017, STUDY, FLAC, style = 2, palette = 8,
            tracks = listOf(
                "Distant Weather" to 293, "Barometer" to 227, "Isobar Lines" to 249, "Rain Shadow" to 262,
                "Pressure Drop West" to 214, "Fog Bank" to 305,
            ),
        ),
        Album(
            "Field Notes", "Juniper Hollow", 2022, STUDY, FLAC_HI, style = 0, palette = 9,
            tracks = listOf(
                "Field Notes" to 198, "Moss and Pencil" to 223, "Birch Sketches" to 245, "Creek Index" to 211,
                "Fern Margin" to 236, "Pollen Count" to 192, "Hollow Ground" to 281,
            ),
        ),
        Album(
            "Morning Lines", "Wren Calloway", 2023, BEDROOM, MP3, style = 3, palette = 10,
            tracks = listOf(
                "Morning Lines" to 207, "Coffee Ring Notebook" to 194, "Window Seat" to 228, "Blue Hour Walk" to 252,
                "Soft Alarm" to 183, "Kitchen Radio" to 216,
            ),
        ),
        Album(
            "Night Ferry", "Pale Meridian", 2020, LIVE, FLAC, style = 4, palette = 11,
            tracks = listOf(
                "Night Ferry" to 312, "Deck Lights" to 238, "Crossing at Two" to 269, "Starboard Song" to 224,
                "Harbour Bell" to 247, "Arrival Grey" to 291,
            ),
        ),
        Album(
            "Harbour Hall", "Kite Harbour", 2025, LIVE, FLAC_HI, style = 6, palette = 12,
            tracks = listOf(
                "Summer Static (Live)" to 236, "Coastline Radio (Live)" to 268, "Lemon Haze (Live)" to 244,
                "Long Way Round (Live)" to 341,
            ),
        ),
    )

    /** Songs with a second copy in Old favourites (MP3 beside the chat's own), folded as versions. */
    val copies = listOf("Paper Satellites", "Amber Rooms", "Field Notes", "Night Ferry", "Slow Orbit")

    /** What plays when the demo opens: an album, the track in it and where in that track. */
    const val NOW_ALBUM = "Summer Static"
    const val NOW_TRACK = 1
    const val NOW_POSITION_MS = 83_000L

    /** The album page shown by `demo_screen album`, unless `demo_album` names another. */
    const val PAGE_ALBUM = "Northern Glass"

    val favourites = listOf(
        "Northern Glass", "Sunburnt Cassette", "Paper Satellites", "Honey Light", "Field Notes",
        "Night Ferry", "Quiet Engines", "Window Seat", "Slow Orbit", "Lantern Street",
    )

    /** Playlist name and its songs by title. */
    val playlists = listOf(
        "Night drive" to listOf(
            "Night Ferry", "Motorway Psalm", "Late Train Home", "Coastline Radio", "Aurora Static", "Crossing at Two",
            "Night Shift Drift", "Deck Lights", "Long Way Round", "Starlit Cul de Sac",
        ),
        "Deep focus" to listOf(
            "Field Notes", "Quiet Engines", "Isobar Lines", "Moss and Pencil", "Idle Turbines", "Barometer",
            "Birch Sketches", "Fog Bank", "Creek Index", "Copper Wire Hymn", "Rain Shadow", "Pilot Light",
        ),
        "Sunday morning" to listOf(
            "Morning Lines", "Honey Light", "Coffee Ring Notebook", "Open Windows", "Slow Kettle", "Window Seat",
            "Dust in the Sunbeam", "Kitchen Radio",
        ),
    )

    /** Played lately, newest first, for Recently played and Speed dial. */
    val played = listOf(
        "Slow Orbit", "Honey Light", "Field Notes", "Northern Glass", "Late Train Home", "Distant Weather",
        "Morning Lines", "Quiet Engines", "Paper Satellites", "Saltwater Clocks", "Deck Lights", "Pollen Count",
    )

    /** Left part way: title and the share played, for the cards with a thin bar on Speed dial. */
    val leftPartWay = listOf("Slow Orbit" to 0.62, "Field Notes" to 0.35, "Distant Weather" to 0.48, "Late Train Home" to 0.21)

    /**
     * Every file of the demo, one row each: the albums in their chats, then the second copies. Dates
     * run back from [nowSec], newest album first in each chat, so Recently added has an order.
     */
    fun tracks(nowSec: Long): List<Track> {
        val out = ArrayList<Track>()
        var file = 1_000
        val nextMessage = HashMap<Long, Long>()
        fun add(album: Album, number: Int, title: String, seconds: Int, chat: Long, quality: Quality, ageDays: Int) {
            val message = (nextMessage[chat] ?: 1_000L) + 1
            nextMessage[chat] = message
            file++
            val ms = seconds * 1_000L
            val bytes = when {
                quality.bits > 0 -> seconds * quality.rate.toLong() * quality.bits / 8 * 2 * 6 / 10
                else -> seconds * quality.kbps * 1_000L / 8
            }
            out += Track(
                chatId = chat,
                messageId = message * 1_048_576L,
                fileId = file,
                uniqueId = "demo$file",
                fileName = "%02d %s.%s".format(number, title, quality.ext),
                mime = quality.mime,
                sizeBytes = bytes,
                dateSec = nowSec - ageDays * 86_400L - number * 600L,
                title = title,
                artist = album.artist,
                album = album.title,
                albumArtist = album.artist,
                year = album.year,
                trackNumber = number,
                durationMs = ms,
                format = quality.format,
                bitrateKbps = quality.kbps,
                sampleRate = quality.rate,
                bitDepth = quality.bits,
                tagState = TagState.Read,
                coverKey = album.coverKey,
                channels = 2,
            )
        }
        albums.forEachIndexed { a, album ->
            album.tracks.forEachIndexed { i, (title, seconds) ->
                add(album, i + 1, title, seconds, album.chat, album.format, ageDays = (albums.size - a) * 9 + (2025 - album.year))
            }
        }
        for (title in copies) {
            val album = albums.firstOrNull { it.tracks.any { t -> t.first == title } } ?: continue
            val at = album.tracks.indexOfFirst { it.first == title }
            add(album, at + 1, title, album.tracks[at].second, OLD_FAVOURITES, MP3, ageDays = 140)
        }
        return out
    }

    private val lengths: Map<TrackId, Long> by lazy { tracks(0).associate { it.id to it.durationMs } }

    /** The invented length of a demo file, for the generated audio; null for one it does not know. */
    fun durationOf(id: TrackId): Long? = lengths[id]
}
