package com.tmplayer.music.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A chat the user chose to scan, as the chat picker hands it over. */
data class ChatPick(val chatId: Long, val title: String)

/**
 * How far one pass of a chat's scan got. Telegram pages newest first, so [fromMessageId] is where
 * the next page starts (0 is the newest end), [done] says the oldest end was reached, and
 * [newestMessageId] is the newest message the pass has seen, where a later top-up stops.
 */
data class ScanCursor(val fromMessageId: Long = 0, val done: Boolean = false, val newestMessageId: Long = 0)

/**
 * How far the scan of one chat got, so a restart resumes instead of reading the chat again
 * Audio messages and documents are two searches with their own cursors:
 * sharing one would let a top-up of one skip what is new in the other.
 */
data class ScanState(
    val audio: ScanCursor = ScanCursor(),
    val documents: ScanCursor = ScanCursor(),
    /** When this chat was last scanned, Unix seconds. */
    val lastScanAt: Long = 0,
)

/** One chosen chat with its scan progress and how many audio files it holds in the index. */
data class ChatRow(
    val chatId: Long,
    val title: String,
    val picked: Boolean,
    /** When the user added the chat, Unix seconds. */
    val addedAt: Long,
    val scan: ScanState,
    val trackCount: Int,
)

/** A `.lrc` or `.txt` file beside the audio in a chat, kept by its name for the lyrics view. */
data class LyricsFile(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    /** The file name without its extension; matched without regard to case. */
    val baseName: String,
    val sizeBytes: Long,
)

/**
 * Where the last page of Songs ended: the row's sort key and its song key, which together are
 * unique, so the next page starts right after it however deep it is. Build one with [of].
 */
data class SongCursor(val key: String, val groupKey: String) {
    companion object {
        /** The cursor just after [song] in the [sort] order. */
        fun of(song: Song, sort: SongSort): SongCursor = SongCursor(
            key = when (sort) {
                SongSort.Title -> titleOrder(song.title)
                SongSort.Artist -> artistOrder(song.artist, song.title)
                SongSort.Album -> albumOrder(song.album, song.title)
                SongSort.Added -> (-song.addedSec).toString()
            },
            groupKey = song.groupKey,
        )
    }
}

/**
 * The library on disk: every audio file of the chosen chats, folded into songs, albums and
 * artists, so the app shows its first rows from here within 3 s of starting and never waits for a
 * scan. Sized for 100,000 tracks over 3,000 chats with 20,000 in one.
 *
 * Writes recompute only the songs, albums and artists they touch. Reads are keyset paged and
 * capped, so a list never loads every row. One SQLite connection is shared and guarded by a lock;
 * every call runs on [Dispatchers.IO]. A file that cannot be read is moved aside and the index
 * starts empty, because everything in it can be scanned again from Telegram.
 */
class LibraryIndex private constructor(
    private val db: SQLiteConnection,
    private val pick: (List<Track>) -> Track?,
    private val clusters: (List<Long>, Boolean) -> List<Int>,
) : AutoCloseable {

    private val lock = ReentrantLock()
    private val statements = HashMap<String, SQLiteStatement>()

    /**
     * What the lists leave out: [ClipRules] bits (short clips, voice style files), read at query
     * time, so a Settings switch changes the lists without touching a row. 0 shows everything.
     * Favourites and playlists are the user's own picks and always show what they hold.
     */
    @Volatile
    var hiddenMask: Int = 0

    /** " AND (hide & mask) = 0" for the songs aliased [alias] (blank for none), or "" when nothing is hidden. */
    private fun visible(alias: String = ""): String {
        val mask = hiddenMask and (ClipRules.SHORT or ClipRules.VOICE)
        return if (mask == 0) "" else " AND (${alias}hide & $mask) = 0"
    }
    private var closed = false

    /** Moves on with every write, so a cached read knows it may be stale. Guarded by [lock]. */
    private var revision = 0L

    /** The last [letterIndex] per sort with the [revision] it was read at; at most one per sort. */
    private val letterCache = HashMap<SongSort, Pair<Long, List<Pair<Char, Int>>>>()

    // Chats

    /**
     * Makes [chats] the set of chosen chats. New ones are added, known ones renamed, and a chat
     * that is no longer in the list has its tracks purged at once (leaving a chat must not leave
     * its songs behind), while its row stays as not picked so it can be offered again.
     */
    suspend fun setPicked(chats: List<ChatPick>) = io {
        val now = System.currentTimeMillis() / 1000
        val wanted = chats.associateBy { it.chatId }
        tx {
            val before = query("SELECT chat_id FROM chats WHERE picked = 1 LIMIT $MAX_ROWS") { it.getLong(0) }
            for (chat in wanted.values) {
                exec(
                    """
                    INSERT INTO chats(chat_id, title, picked, added_at) VALUES(?, ?, 1, ?)
                    ON CONFLICT(chat_id) DO UPDATE SET title = excluded.title,
                        added_at = CASE WHEN picked = 1 THEN added_at ELSE excluded.added_at END,
                        picked = 1
                    """,
                    chat.chatId, chat.title, now,
                )
            }
            for (chatId in before) {
                if (chatId !in wanted) purgeChat(chatId, keepRow = true)
            }
        }
    }

    /** The chosen chats with their scan state and track counts, oldest added first. */
    suspend fun pickedChats(): List<ChatRow> = io {
        query("SELECT $CHAT_COLUMNS FROM chats WHERE picked = 1 ORDER BY added_at, chat_id LIMIT $MAX_ROWS", map = ::readChat)
    }

    /**
     * Forgets a chat: its tracks and lyrics files go, and every song, album and artist they were in
     * is rebuilt in the same transaction. A song with a version in another chat stays, with one
     * version fewer and a new default if the default was here.
     */
    suspend fun removeChat(chatId: Long) = io {
        tx { purgeChat(chatId, keepRow = false) }
    }

    /** One chosen chat with its scan state and track count, or null when it is not chosen. */
    suspend fun pickedChat(chatId: Long): ChatRow? = io {
        query("SELECT $CHAT_COLUMNS FROM chats WHERE chat_id = ? AND picked = 1 LIMIT 1", chatId, map = ::readChat).firstOrNull()
    }

    /** How far the scan of [chatId] got, or null for a chat the index does not know. */
    suspend fun scanState(chatId: Long): ScanState? = io {
        query("SELECT $CHAT_COLUMNS FROM chats WHERE chat_id = ? LIMIT 1", chatId, map = ::readChat)
            .firstOrNull()?.scan
    }

    /** Records how far the scan of [chatId] got; a chat that is not chosen is left alone. */
    suspend fun saveScanState(chatId: Long, state: ScanState) = io {
        exec(
            """
            UPDATE chats SET audio_from = ?, audio_newest = ?, audio_done = ?, documents_from = ?,
                documents_newest = ?, documents_done = ?, last_scan_at = ? WHERE chat_id = ? AND picked = 1
            """,
            state.audio.fromMessageId, state.audio.newestMessageId, state.audio.done,
            state.documents.fromMessageId, state.documents.newestMessageId, state.documents.done,
            state.lastScanAt, chatId,
        )
    }

    // Tracks

    /**
     * Adds or replaces [tracks] (the scanner sends up to 500 at a time) in one transaction, then
     * rebuilds only the songs, albums and artists they touch. A track from a chat that is not
     * chosen is dropped: a scan that was still running when its chat was removed must not bring
     * the chat's songs back. Returns how many were stored.
     */
    suspend fun upsert(tracks: List<Track>): Int = io {
        if (tracks.isEmpty()) return@io 0
        tx { upsertRows(tracks) }
    }

    /** The body of [upsert]; the caller holds the lock and the transaction. */
    private fun upsertRows(tracks: List<Track>): Int {
        val touched = Touched()
        var stored = 0
        run {
            val pickedCache = HashMap<Long, Boolean>()
            for (track in tracks.associateBy { it.id }.values) {
                val picked = pickedCache.getOrPut(track.chatId) {
                    query("SELECT picked FROM chats WHERE chat_id = ? LIMIT 1", track.chatId) { it.getLong(0) == 1L }
                        .firstOrNull() == true
                }
                if (!picked) continue
                if (track.tagState == TagState.TelegramOnly && keepsReadTags(track)) continue
                val existed = touchOld(track.id, touched)
                val base = indexBaseKey(track)
                val album = indexAlbumKey(track)
                val artists = ArtistCredits.split(track.artist)
                exec(
                    "INSERT OR REPLACE INTO tracks($TRACK_COLUMNS, album_key, artist_key) " +
                        "VALUES(${List(TRACK_COLUMN_COUNT + 2) { "?" }.joinToString()})",
                    *trackValues(track, base), album, artists.firstOrNull()?.key.orEmpty(),
                )
                // A file seen for the first time has no artist rows yet, so a first scan skips this.
                if (existed) exec("DELETE FROM track_artists WHERE chat_id = ? AND message_id = ?", track.chatId, track.messageId)
                artists.forEachIndexed { position, artist ->
                    exec(
                        // The song key is filled in by rebuildBase below, as it is for the track.
                        "INSERT OR REPLACE INTO track_artists(chat_id, message_id, position, artist_key, name, song_key, album_key) " +
                            "VALUES(?, ?, ?, ?, ?, '', ?)",
                        track.chatId, track.messageId, position, artist.key, artist.name, album,
                    )
                    touched.artists += artist.key
                }
                touched.bases += base
                touched.albums += album
                touched.chats += track.chatId
                stored++
            }
            rebuild(touched)
        }
        return stored
    }

    /**
     * True when the stored row for [track]'s message already has its tags read (or given up on) for
     * the same file, so Telegram's fields seen again (a scan, a chat page) must not replace them.
     */
    private fun keepsReadTags(track: Track): Boolean = query(
        "SELECT tag_state, unique_id FROM tracks WHERE chat_id = ? AND message_id = ? LIMIT 1",
        track.chatId, track.messageId,
    ) { text(it, 0) to text(it, 1) }.firstOrNull()?.let { (state, unique) ->
        state != TagState.TelegramOnly.name && unique == track.uniqueId
    } == true

    /**
     * Adds those of [tracks] the index does not hold yet (a chat page read them from Telegram before
     * the scan got there), leaving the stored ones alone. Files of chats that are not chosen are
     * dropped, as in [upsert]. Returns how many were added.
     */
    suspend fun upsertMissing(tracks: List<Track>): Int = io {
        if (tracks.isEmpty()) return@io 0
        val fresh = tracks.distinctBy { it.id }.filter { t ->
            count("SELECT COUNT(*) FROM tracks WHERE chat_id = ? AND message_id = ?", t.chatId, t.messageId) == 0
        }
        if (fresh.isEmpty()) 0 else tx { upsertRows(fresh) }
    }

    /**
     * One page of [chatId]'s files in message order, after message [after] (null for the first
     * page): newest first, or oldest first when [oldestFirst]. Read through the primary key, so a
     * page deep in a chat of 20,000 costs what the first does.
     */
    suspend fun chatTracks(chatId: Long, oldestFirst: Boolean, after: Long?, limit: Int): List<Track> = io {
        val n = pageSize(limit)
        val (op, dir) = if (oldestFirst) ">" to "ASC" else "<" to "DESC"
        if (after == null) {
            query("SELECT $TRACK_COLUMNS FROM tracks WHERE chat_id = ? ORDER BY message_id $dir LIMIT ?", chatId, n, map = ::readTrack)
        } else {
            query(
                "SELECT $TRACK_COLUMNS FROM tracks WHERE chat_id = ? AND message_id $op ? ORDER BY message_id $dir LIMIT ?",
                chatId, after, n, map = ::readTrack,
            )
        }
    }

    /** The files of [chatId] among [messageIds] the index holds, by message id; at most [MAX_PAGE] asked. */
    suspend fun chatTracksByIds(chatId: Long, messageIds: Collection<Long>): Map<Long, Track> = io {
        val out = HashMap<Long, Track>()
        for (part in messageIds.distinct().take(MAX_PAGE).chunked(ID_CHUNK)) {
            query(
                "SELECT $TRACK_COLUMNS FROM tracks WHERE chat_id = ? AND message_id IN (${part.joinToString { "?" }})",
                chatId, *part.toTypedArray(), map = ::readTrack,
            ).forEach { out[it.messageId] = it }
        }
        out
    }

    /**
     * Up to [limit] files waiting for their tags whose duration is not known (audio sent as a
     * document, most lossless), newest post first: the tag reader reads these before the rest.
     */
    suspend fun pendingDocuments(limit: Int): List<Track> = io {
        query(
            "SELECT $TRACK_COLUMNS FROM tracks WHERE tag_state = ? AND duration_ms = 0 ORDER BY date_sec DESC LIMIT ?",
            TagState.TelegramOnly.name, pageSize(limit), map = ::readTrack,
        )
    }

    /** Removes the given files (deleted messages) and rebuilds what they were part of. */
    suspend fun removeTracks(ids: List<TrackId>) = io {
        if (ids.isEmpty()) return@io
        val touched = Touched()
        tx {
            for (id in ids.toSet()) {
                touchOld(id, touched)
                exec("DELETE FROM tracks WHERE chat_id = ? AND message_id = ?", id.chatId, id.messageId)
                exec("DELETE FROM track_artists WHERE chat_id = ? AND message_id = ?", id.chatId, id.messageId)
                exec("DELETE FROM lyrics_files WHERE chat_id = ? AND message_id = ?", id.chatId, id.messageId)
                exec("DELETE FROM resume_points WHERE chat_id = ? AND message_id = ?", id.chatId, id.messageId)
                touched.chats += id.chatId
            }
            rebuild(touched)
        }
    }

    /** Adds or replaces lyrics files found by the document scan; files of unchosen chats are dropped. */
    suspend fun upsertLyricsFiles(files: List<LyricsFile>) = io {
        if (files.isEmpty()) return@io
        tx {
            for (file in files) {
                val picked = query("SELECT picked FROM chats WHERE chat_id = ? LIMIT 1", file.chatId) { it.getLong(0) == 1L }
                    .firstOrNull() == true
                if (!picked) continue
                exec(
                    "INSERT OR REPLACE INTO lyrics_files(chat_id, message_id, file_id, base_name, size_bytes) VALUES(?, ?, ?, ?, ?)",
                    file.chatId, file.messageId, file.fileId, lyricsName(file.baseName), file.sizeBytes,
                )
            }
        }
    }

    /** The newest lyrics file in [chatId] named like an audio file there, or null when none is. */
    suspend fun lyricsFor(chatId: Long, baseName: String): LyricsFile? = io {
        query(
            "SELECT chat_id, message_id, file_id, base_name, size_bytes FROM lyrics_files " +
                "WHERE chat_id = ? AND base_name = ? ORDER BY message_id DESC LIMIT 1",
            chatId, lyricsName(baseName),
        ) { LyricsFile(it.getLong(0), it.getLong(1), it.getLong(2).toInt(), text(it, 3), it.getLong(4)) }
            .firstOrNull()
    }

    // Reading

    /**
     * One page of Songs in [sort] order, starting after [after] (null for the first page). The
     * page is found through the index that matches the order, so the last page costs what the
     * first does.
     */
    suspend fun songs(sort: SongSort, after: SongCursor?, limit: Int): List<Song> = io {
        val column = sortColumn(sort)
        val n = pageSize(limit)
        if (after == null) {
            query("SELECT $SONG_COLUMNS FROM songs WHERE 1 = 1${visible()} ORDER BY $column, song_key LIMIT ?", n, map = ::readSong)
        } else {
            val key: Any = if (sort == SongSort.Added) after.key.toLongOrNull() ?: Long.MIN_VALUE else after.key
            query(
                "SELECT $SONG_COLUMNS FROM songs WHERE ($column, song_key) > (?, ?)${visible()} ORDER BY $column, song_key LIMIT ?",
                key, after.groupKey, n, map = ::readSong,
            )
        }
    }

    /** One song by its key, or null when it is gone. */
    suspend fun song(songKey: String): Song? = io {
        query("SELECT $SONG_COLUMNS FROM songs WHERE song_key = ? LIMIT 1", songKey, map = ::readSong).firstOrNull()
    }

    /** How many songs the library has, every set of versions counted once. */
    suspend fun songCount(): Int = io { count("SELECT COUNT(*) FROM songs WHERE 1 = 1${visible()}") }

    /** How many audio files the library has, so "5,820 songs (7,311 files)" adds up. */
    suspend fun fileCount(): Int = io { count("SELECT COUNT(*) FROM tracks") }

    /** Every file of one song, the default first, then oldest post first. */
    suspend fun versions(songKey: String): List<Track> = io {
        val default = query(
            "SELECT default_chat_id, default_message_id FROM songs WHERE song_key = ? LIMIT 1",
            songKey,
        ) { TrackId(it.getLong(0), it.getLong(1)) }.firstOrNull()
        query(
            "SELECT $TRACK_COLUMNS FROM tracks WHERE song_key = ? ORDER BY date_sec, chat_id, message_id LIMIT $MAX_VERSIONS",
            songKey, map = ::readTrack,
        ).sortedByDescending { it.id == default }
    }

    /** One page of albums by title, starting after [after]. */
    suspend fun albums(after: Album?, limit: Int): List<Album> = io {
        val n = pageSize(limit)
        val count = albumVisibleCount()
        if (count != null) {
            val keyset = if (after == null) "" else "(a.title_sort, a.album_key) > (?, ?) AND "
            val args: List<Any?> = if (after == null) listOf(n) else listOf(titleOrder(after.title), after.key, n)
            return@io query(
                "SELECT a.album_key, a.title, a.artist, a.year, $count, a.thumb_file_id, a.cover_key FROM albums a " +
                    "WHERE $keyset$count > 0 ORDER BY a.title_sort, a.album_key LIMIT ?",
                *args.toTypedArray(), map = ::readAlbum,
            )
        }
        if (after == null) {
            query("SELECT $ALBUM_COLUMNS FROM albums ORDER BY title_sort, album_key LIMIT ?", n, map = ::readAlbum)
        } else {
            query(
                "SELECT $ALBUM_COLUMNS FROM albums WHERE (title_sort, album_key) > (?, ?) ORDER BY title_sort, album_key LIMIT ?",
                titleOrder(after.title), after.key, n, map = ::readAlbum,
            )
        }
    }

    /** The songs of one album in disc and track order; a compilation track is in each of its albums. */
    suspend fun albumSongs(albumKey: String, limit: Int = MAX_LIST): List<Song> = io {
        query(
            """
            SELECT ${SONG_COLUMNS.split(", ").joinToString { "s.$it" }} FROM songs s JOIN (
                SELECT song_key, MIN(disc_number * 100000 + track_number) AS position FROM tracks
                WHERE album_key = ? GROUP BY song_key
            ) t ON s.song_key = t.song_key WHERE 1 = 1${visible("s.")} ORDER BY t.position, s.title_sort, s.song_key LIMIT ?
            """,
            albumKey, listSize(limit), map = ::readSong,
        )
    }

    /** One page of artists by name, starting after [after]. Files with no artist are in no artist. */
    suspend fun artists(after: Artist?, limit: Int): List<Artist> = io {
        val n = pageSize(limit)
        val counts = artistVisibleCounts()
        if (counts != null) {
            val keyset = if (after == null) "" else "(r.name_sort, r.artist_key) > (?, ?) AND "
            val args: List<Any?> = if (after == null) listOf(n) else listOf(sortOrLast(after.name), after.key, n)
            return@io query(
                "SELECT r.artist_key, r.name, ${counts.first}, ${counts.second} FROM artists r " +
                    "WHERE $keyset${counts.first} > 0 ORDER BY r.name_sort, r.artist_key LIMIT ?",
                *args.toTypedArray(), map = ::readArtist,
            )
        }
        if (after == null) {
            query("SELECT $ARTIST_COLUMNS FROM artists ORDER BY name_sort, artist_key LIMIT ?", n, map = ::readArtist)
        } else {
            query(
                "SELECT $ARTIST_COLUMNS FROM artists WHERE (name_sort, artist_key) > (?, ?) ORDER BY name_sort, artist_key LIMIT ?",
                sortOrLast(after.name), after.key, n, map = ::readArtist,
            )
        }
    }

    /** The songs of one artist by title: every song with a version whose credit names the artist. */
    suspend fun artistSongs(artistKey: String, limit: Int = MAX_LIST): List<Song> = io {
        query(
            "SELECT $SONG_COLUMNS FROM songs WHERE song_key IN (SELECT song_key FROM track_artists WHERE artist_key = ?)${visible()} " +
                "ORDER BY title_sort, song_key LIMIT ?",
            artistKey, listSize(limit), map = ::readSong,
        )
    }

    /** The albums one artist has tracks on, oldest first. */
    suspend fun artistAlbums(artistKey: String, limit: Int = MAX_LIST): List<Album> = io {
        query(
            "SELECT $ALBUM_COLUMNS FROM albums WHERE album_key IN " +
                "(SELECT album_key FROM track_artists WHERE artist_key = ? AND album_key <> '') " +
                "ORDER BY year, title_sort, album_key LIMIT ?",
            artistKey, listSize(limit), map = ::readAlbum,
        )
    }

    /**
     * The artists [song]'s credit names, in the order written, for "Go to artist": one for a song
     * by one artist, several for "A, B feat. C", none when the file names nobody. Read from the
     * song's default version as the index has it now.
     */
    suspend fun songArtists(song: Song): List<Artist> = io {
        val file = currentDefault(song)
        query(
            "SELECT ${ARTIST_COLUMNS.split(", ").joinToString { "r.$it" }} FROM track_artists a " +
                "JOIN artists r ON r.artist_key = a.artist_key WHERE a.chat_id = ? AND a.message_id = ? " +
                "ORDER BY a.position LIMIT ${ArtistCredits.MAX_ARTISTS}",
            file.chatId, file.messageId, map = ::readArtist,
        )
    }

    /** One page of the songs that have a version in [chatId], by title (the "From: one chat" filter). */
    suspend fun chatSongs(chatId: Long, after: SongCursor?, limit: Int): List<Song> = io {
        val n = pageSize(limit)
        val inChat = "song_key IN (SELECT song_key FROM tracks WHERE chat_id = ?)${visible()}"
        if (after == null) {
            query("SELECT $SONG_COLUMNS FROM songs WHERE $inChat ORDER BY title_sort, song_key LIMIT ?", chatId, n, map = ::readSong)
        } else {
            query(
                "SELECT $SONG_COLUMNS FROM songs WHERE $inChat AND (title_sort, song_key) > (?, ?) " +
                    "ORDER BY title_sort, song_key LIMIT ?",
                chatId, after.key, after.groupKey, n, map = ::readSong,
            )
        }
    }

    /** The song [id]'s file is a version of now, or null when the file is not in the index. */
    suspend fun songOf(id: TrackId): Song? = io {
        query(
            "SELECT ${prefixed("s")} FROM tracks t JOIN songs s ON s.song_key = t.song_key " +
                "WHERE t.chat_id = ? AND t.message_id = ? LIMIT 1",
            id.chatId, id.messageId, map = ::readSong,
        ).firstOrNull()
    }

    /** One file by its message, or null when it is not in the index. */
    suspend fun track(id: TrackId): Track? = io {
        query("SELECT $TRACK_COLUMNS FROM tracks WHERE chat_id = ? AND message_id = ? LIMIT 1", id.chatId, id.messageId, map = ::readTrack)
            .firstOrNull()
    }

    /** Up to [limit] files whose tags have not been read yet, newest post first. */
    suspend fun pendingTags(limit: Int): List<Track> = io {
        query(
            "SELECT $TRACK_COLUMNS FROM tracks WHERE tag_state = ? ORDER BY date_sec DESC LIMIT ?",
            TagState.TelegramOnly.name, pageSize(limit), map = ::readTrack,
        )
    }

    /**
     * What the tag reader found in [id]'s file. The file's own tags win over Telegram's fields
     * where they say something; what they leave blank keeps what the track had. The title and
     * artist can move the track to another song, so the group key is computed again and the
     * songs, albums and artists it touches are rebuilt. Null [tags] marks the file failed, so it is
     * not read again, and changes nothing else.
     */
    suspend fun applyTags(id: TrackId, tags: Tags?, coverKey: String) = applyTags(listOf(TagResult(id, tags, coverKey)))

    /**
     * [applyTags] for a batch, in one transaction with one rebuild of what the batch touched, so a
     * batch of the tag reader costs one write instead of one per file.
     */
    suspend fun applyTags(results: List<TagResult>) = io {
        if (results.isEmpty()) return@io
        tx {
            val updated = ArrayList<Track>(results.size)
            for (result in results.associateBy { it.id }.values) {
                val track = query(
                    "SELECT $TRACK_COLUMNS FROM tracks WHERE chat_id = ? AND message_id = ? LIMIT 1",
                    result.id.chatId, result.id.messageId, map = ::readTrack,
                ).firstOrNull() ?: continue
                val tags = result.tags
                if (tags == null) {
                    exec(
                        "UPDATE tracks SET tag_state = ? WHERE chat_id = ? AND message_id = ?",
                        TagState.Failed.name, result.id.chatId, result.id.messageId,
                    )
                } else {
                    updated += withTags(track, tags, result.coverKey)
                }
            }
            upsertRows(updated)
        }
    }

    /**
     * Gives rows stored by an older version the names a new scan would give them (2026-10-30): titles
     * written with underscores, a leading hashtag mark or a clear track number ([TitleFallback.tidyTitle]),
     * and artists that only say "unknown" ([TitleFallback.tidyArtist]). Only rows that may need it are
     * read (one pass over the tracks table), and only the ones that change are written, through the
     * same rebuild as a scan, so favourites and playlists follow their songs. Run when the index opens;
     * a second run finds nothing to change. Returns how many files changed.
     */
    suspend fun tidyStoredNames(): Int = io { tidyStoredNamesLocked() }

    private fun tidyStoredNamesLocked(): Int {
        val placeholders = TitleFallback.PLACEHOLDER_ARTISTS.toList()
        val marks = placeholders.joinToString { "?" }
        val candidates = query(
            "SELECT $TRACK_COLUMNS FROM tracks WHERE instr(title, '_') > 0 OR instr(title, '  ') > 0 OR " +
                "substr(title, 1, 1) IN ('#', '(') OR title GLOB '[0-9]*' OR instr(artist, '_') > 0 OR " +
                "substr(artist, 1, 1) = '#' OR lower(trim(artist)) IN ($marks) OR lower(trim(album_artist)) IN ($marks) " +
                "LIMIT $MAX_ROWS",
            *placeholders.toTypedArray(), *placeholders.toTypedArray(), map = ::readTrack,
        )
        val changed = candidates.mapNotNull { track ->
            val title = TitleFallback.tidyTitle(track.title).ifEmpty { track.title }
            val artist = TitleFallback.tidyArtist(track.artist)
            val albumArtist = TitleFallback.tidyArtist(track.albumArtist)
            if (title == track.title && artist == track.artist && albumArtist == track.albumArtist) return@mapNotNull null
            track.copy(
                title = title,
                artist = artist,
                albumArtist = albumArtist,
                groupKey = GroupKey.of(title, artist, track.durationMs),
                variant = GroupKey.variantOf(title),
            )
        }
        changed.chunked(TIDY_BATCH).forEach { batch -> tx { upsertRows(batch) } }
        return changed.size
    }

    /** How many files wait for their tags, have them, and could not be read. */
    suspend fun tagCounts(): TagCounts = io {
        var pending = 0
        var read = 0
        var failed = 0
        query("SELECT tag_state, COUNT(*) FROM tracks GROUP BY tag_state") { text(it, 0) to it.getLong(1).toInt() }
            .forEach { (state, n) ->
                when (state) {
                    TagState.TelegramOnly.name -> pending += n
                    TagState.Read.name -> read += n
                    else -> failed += n
                }
            }
        TagCounts(pending = pending, read = read, failed = failed)
    }

    /** Puts every file whose tags could not be read back in the queue; returns how many. */
    suspend fun retryFailedTags(): Int = io {
        val n = count("SELECT COUNT(*) FROM tracks WHERE tag_state = ?", TagState.Failed.name)
        if (n > 0) exec("UPDATE tracks SET tag_state = ? WHERE tag_state = ?", TagState.TelegramOnly.name, TagState.Failed.name)
        n
    }

    /** The files among [ids] still waiting for their tags, for reading what is on screen first. */
    suspend fun pendingAmong(ids: Collection<TrackId>): List<Track> = io {
        ids.take(MAX_PAGE).mapNotNull { id ->
            query(
                "SELECT $TRACK_COLUMNS FROM tracks WHERE chat_id = ? AND message_id = ? AND tag_state = ? LIMIT 1",
                id.chatId, id.messageId, TagState.TelegramOnly.name, map = ::readTrack,
            ).firstOrNull()
        }
    }

    private fun withTags(track: Track, tags: Tags, coverKey: String): Track {
        val (artist, title) = TitleFallback.display(tags.title, tags.artist, track.title, track.artist, track.fileName)
        val duration = tags.durationMs.takeIf { it > 0 } ?: track.durationMs
        return track.copy(
            title = title,
            artist = artist,
            album = tags.album.ifBlank { track.album },
            albumArtist = tags.albumArtist.ifBlank { track.albumArtist },
            year = tags.year.takeIf { it > 0 } ?: track.year,
            trackNumber = tags.trackNumber.takeIf { it > 0 } ?: track.trackNumber,
            discNumber = tags.discNumber.takeIf { it > 0 } ?: track.discNumber,
            durationMs = duration,
            format = tags.format.takeIf { it != AudioFormat.OTHER } ?: track.format,
            bitrateKbps = tags.bitrateKbps.takeIf { it > 0 } ?: track.bitrateKbps,
            sampleRate = tags.sampleRate.takeIf { it > 0 } ?: track.sampleRate,
            bitDepth = tags.bitDepth.takeIf { it > 0 } ?: track.bitDepth,
            channels = tags.channels.takeIf { it > 0 } ?: track.channels,
            tagState = TagState.Read,
            coverKey = coverKey.ifBlank { track.coverKey },
            groupKey = GroupKey.of(title, artist, duration),
            variant = GroupKey.variantOf(title),
        )
    }

    /**
     * The A to Z bar for [sort]: each first letter with the position of its first row. Digits and
     * symbols share '#', non-Latin letters stand for themselves, and rows with no name (sorted last)
     * fall under '#' only when no row before them did. Empty for [SongSort.Added], which has no
     * letters.
     */
    suspend fun letterIndex(sort: SongSort): List<Pair<Char, Int>> = io {
        if (sort == SongSort.Added) return@io emptyList()
        // The GROUP BY reads every song (25 to 30 ms at 100,000), so it runs again only after a write.
        letterCache[sort]?.takeIf { it.first == revision }?.let { return@io it.second }
        val column = sortColumn(sort)
        val groups = query("SELECT substr($column, 1, 1), COUNT(*) FROM songs WHERE 1 = 1${visible()} GROUP BY 1 ORDER BY 1 LIMIT $MAX_ROWS") {
            text(it, 0) to it.getLong(1)
        }
        Letters.index(groups).also { letterCache[sort] = revision to it }
    }

    /**
     * Up to [limit] songs in [sort] order starting at row [offset] (0 is the first), for a jump to a
     * letter far down the list: the row at the offset is found by walking the sort index alone, then
     * the page is read from there by key, so a jump to row 90,000 reads one page, not 90,000 rows.
     */
    suspend fun songsAt(sort: SongSort, offset: Int, limit: Int): List<Song> = io {
        val column = sortColumn(sort)
        val n = pageSize(limit)
        if (offset <= 0) {
            return@io query("SELECT $SONG_COLUMNS FROM songs ORDER BY $column, song_key LIMIT ?", n, map = ::readSong)
        }
        val start = query(
            "SELECT $column, song_key FROM songs ORDER BY $column, song_key LIMIT 1 OFFSET ?",
            offset,
        ) { (if (sort == SongSort.Added) it.getLong(0) else text(it, 0)) to text(it, 1) }.firstOrNull() ?: return@io emptyList()
        query(
            "SELECT $SONG_COLUMNS FROM songs WHERE ($column, song_key) >= (?, ?) ORDER BY $column, song_key LIMIT ?",
            start.first, start.second, n, map = ::readSong,
        )
    }

    /**
     * Up to [limit] songs just before [before] in [sort] order, nearest last (so the page reads in list
     * order), for scrolling up from a list that starts part way down.
     */
    suspend fun songsBefore(sort: SongSort, before: SongCursor, limit: Int): List<Song> = io {
        val column = sortColumn(sort)
        val key: Any = if (sort == SongSort.Added) before.key.toLongOrNull() ?: Long.MAX_VALUE else before.key
        query(
            "SELECT $SONG_COLUMNS FROM songs WHERE ($column, song_key) < (?, ?) ORDER BY $column DESC, song_key DESC LIMIT ?",
            key, before.groupKey, pageSize(limit), map = ::readSong,
        ).reversed()
    }

    // Going from a song to its album and artist

    /** One album by its key, for "Go to album"; null when the key is blank or the album has no songs left. */
    suspend fun album(key: String): Album? = io {
        if (key.isEmpty()) return@io null
        query("SELECT $ALBUM_COLUMNS FROM albums WHERE album_key = ? LIMIT 1", key, map = ::readAlbum).firstOrNull()
    }

    /**
     * One file behind a generated cover key ([CoverArt.keyOf]): a track of the album for an album key,
     * a version of the song for a song key. The online cover lookup reads its title, artist and album.
     * Null for a playlist key, a blank key, or a key with nothing left behind it.
     */
    suspend fun trackForCover(artKey: String): Track? = io {
        val album = CoverArt.albumKeyOf(artKey)
        val base = CoverArt.songBaseOf(artKey)
        when {
            !album.isNullOrEmpty() ->
                query("SELECT $TRACK_COLUMNS FROM tracks WHERE album_key = ? LIMIT 1", album, map = ::readTrack).firstOrNull()
            !base.isNullOrEmpty() ->
                query("SELECT $TRACK_COLUMNS FROM tracks WHERE group_key = ? LIMIT 1", base, map = ::readTrack).firstOrNull()
            else -> null
        }
    }

    /** A track of album [key] that has Telegram's cover thumbnail, so the tile can ask for it by message. */
    suspend fun albumThumbTrack(key: String): TrackId? = io {
        if (key.isEmpty()) return@io null
        query(
            "SELECT chat_id, message_id FROM tracks WHERE album_key = ? AND thumb_file_id <> 0 LIMIT 1",
            key,
        ) { TrackId(it.getLong(0), it.getLong(1)) }.firstOrNull()
    }

    /** One artist by key, for "Go to artist"; null when the key is blank or the artist has no songs left. */
    suspend fun artist(key: String): Artist? = io {
        if (key.isEmpty()) return@io null
        query("SELECT $ARTIST_COLUMNS FROM artists WHERE artist_key = ? LIMIT 1", key, map = ::readArtist).firstOrNull()
    }

    // Favourites

    /**
     * Marks [song] as a favourite or not. Turning it on stores the song's current default file, so
     * the heart stays on whichever versions come and go; turning it off removes every favourite
     * that now resolves to this song, including one made before two songs became one. Returns
     * false only when the song cannot be stored (a blank key, or [MAX_FAVOURITES] reached).
     */
    suspend fun setFavourite(song: Song, on: Boolean): Boolean = io {
        if (song.groupKey.isEmpty()) return@io false
        tx {
            val rows = refsOf("favourites", "r.rowid", song.groupKey) { it.getLong(0) }
            if (!on) {
                for (row in rows) exec("DELETE FROM favourites WHERE rowid = ?", row)
                return@tx true
            }
            if (rows.isNotEmpty()) return@tx true
            if (count("SELECT COUNT(*) FROM favourites") >= MAX_FAVOURITES) return@tx false
            val target = currentDefault(song)
            exec(
                "INSERT OR REPLACE INTO favourites(chat_id, message_id, song_key, base_key, added_sec) VALUES(?, ?, ?, ?, ?)",
                target.chatId, target.messageId, song.groupKey, baseOfSongKey(song.groupKey), nowSec(),
            )
            true
        }
    }

    /** The current keys of every favourite song that can still be played, for the heart on each row. */
    suspend fun favouriteKeys(): Set<String> = io {
        query(
            "SELECT DISTINCT s.song_key FROM favourites f JOIN songs s ON s.song_key = ${resolved("f")} LIMIT $MAX_FAVOURITES",
        ) { text(it, 0) }.toHashSet()
    }

    /** Favourite songs, the one made a favourite last first; songs whose files are all gone are left out. */
    suspend fun favourites(limit: Int): List<Song> = io {
        query(
            "SELECT ${prefixed("s")}, MAX(f.added_sec) AS at, MAX(f.rowid) AS seq FROM favourites f " +
                "JOIN songs s ON s.song_key = ${resolved("f")} GROUP BY s.song_key ORDER BY at DESC, seq DESC LIMIT ?",
            listSize(limit), map = ::readSong,
        )
    }

    // Plays

    /**
     * Records that [id] was played at [atSec], for Recently played. The file's current song key is
     * stored when the index knows the file, [songKey] otherwise; a file of a chat that is not chosen
     * is not recorded, since leaving a chat clears its history. Only the newest [MAX_PLAYS] plays
     * are kept.
     */
    suspend fun recordPlay(id: TrackId, songKey: String, atSec: Long) = io {
        tx {
            val current = query(
                "SELECT song_key FROM tracks WHERE chat_id = ? AND message_id = ? LIMIT 1",
                id.chatId, id.messageId,
            ) { text(it, 0) }.firstOrNull()?.takeIf { it.isNotEmpty() }
            if (current == null) {
                val picked = query("SELECT picked FROM chats WHERE chat_id = ? LIMIT 1", id.chatId) { it.getLong(0) == 1L }
                    .firstOrNull() == true
                if (!picked || songKey.isEmpty()) return@tx
            }
            val key = current ?: songKey
            exec(
                "INSERT INTO plays(chat_id, message_id, song_key, base_key, at_sec) VALUES(?, ?, ?, ?, ?)",
                id.chatId, id.messageId, key, baseOfSongKey(key), atSec,
            )
            exec(
                "DELETE FROM plays WHERE play_id <= (SELECT play_id FROM plays ORDER BY play_id DESC LIMIT 1 OFFSET $MAX_PLAYS)",
            )
        }
    }

    /** Songs played lately, each once, the most recently played first. */
    suspend fun recentlyPlayed(limit: Int): List<Song> = io {
        query(
            "SELECT ${prefixed("s")}, MAX(p.at_sec) AS at, MAX(p.play_id) AS seq FROM plays p " +
                "JOIN songs s ON s.song_key = ${resolved("p")} WHERE 1 = 1${visible("s.")} GROUP BY s.song_key ORDER BY at DESC, seq DESC LIMIT ?",
            listSize(limit), map = ::readSong,
        )
    }

    /** Songs by the date their newest version was posted, newest first: the Recently added row. */
    suspend fun recentlyAdded(limit: Int): List<Song> = songs(SongSort.Added, null, limit)

    // Resume points and Speed dial

    /**
     * Keeps where [id] was left, for Speed dial. A position not worth coming back to
     * ([SpeedDial.worthKeeping]: the first seconds, or the last stretch) clears what was kept for the
     * file instead. A file the index does not hold (its chat was left, its message deleted) is not
     * kept. Only the newest [SpeedDial.MAX_KEPT] rows stay.
     */
    suspend fun saveResume(id: TrackId, songKey: String, positionMs: Long, durationMs: Long, atMs: Long) = io {
        tx {
            val known = query("SELECT 1 FROM tracks WHERE chat_id = ? AND message_id = ? LIMIT 1", id.chatId, id.messageId) { 1 }
                .isNotEmpty()
            if (!known) return@tx
            if (!SpeedDial.worthKeeping(positionMs, durationMs)) {
                exec("DELETE FROM resume_points WHERE chat_id = ? AND message_id = ?", id.chatId, id.messageId)
                return@tx
            }
            exec(
                "INSERT OR REPLACE INTO resume_points(chat_id, message_id, song_key, position_ms, duration_ms, at_ms, hidden) " +
                    "VALUES(?, ?, ?, ?, ?, ?, 0)",
                id.chatId, id.messageId, songKey, positionMs, durationMs.coerceAtLeast(0), atMs,
            )
            trimResumePoints()
        }
    }

    /** Forgets where [id] was left: it ended, or its file is gone. A hidden card stays hidden. */
    suspend fun clearResume(id: TrackId) = io {
        exec("DELETE FROM resume_points WHERE chat_id = ? AND message_id = ? AND hidden = 0", id.chatId, id.messageId)
    }

    /** Forgets everything kept for [id], hidden or not: for a file that can no longer be played. */
    suspend fun forgetResume(id: TrackId) = io {
        exec("DELETE FROM resume_points WHERE chat_id = ? AND message_id = ?", id.chatId, id.messageId)
    }

    /**
     * Takes [id]'s card off Speed dial at [atMs], position and all. Playing the song again after
     * that brings it back, so no confirmation is needed.
     */
    suspend fun hideFromDial(id: TrackId, songKey: String, atMs: Long) = io {
        tx {
            exec(
                "INSERT OR REPLACE INTO resume_points(chat_id, message_id, song_key, position_ms, duration_ms, at_ms, hidden) " +
                    "VALUES(?, ?, ?, 0, 0, ?, 1)",
                id.chatId, id.messageId, songKey, atMs,
            )
            trimResumePoints()
        }
    }

    private fun trimResumePoints() {
        exec(
            "DELETE FROM resume_points WHERE at_ms <= " +
                "(SELECT at_ms FROM resume_points ORDER BY at_ms DESC LIMIT 1 OFFSET ${SpeedDial.MAX_KEPT})",
        )
    }

    /**
     * The kept positions and the hidden cards whose files the index still holds, newest first, at
     * most [limit] rows; each keyed by the song its file is in now ([DialItem.key]).
     */
    suspend fun resumePoints(limit: Int = SpeedDial.MAX_KEPT): Pair<List<DialItem>, List<DialHidden>> = io {
        val items = ArrayList<DialItem>()
        val hidden = ArrayList<DialHidden>()
        query(
            "SELECT r.chat_id, r.message_id, r.song_key, r.position_ms, r.duration_ms, r.at_ms, r.hidden, t.song_key, " +
                "t.title, t.file_name, t.artist, t.cover_key, t.thumb_file_id, t.duration_ms, t.album_key FROM resume_points r " +
                "JOIN tracks t ON t.chat_id = r.chat_id AND t.message_id = r.message_id ORDER BY r.at_ms DESC LIMIT ?",
            limit.coerceIn(1, SpeedDial.MAX_KEPT),
        ) { s ->
            val id = TrackId(s.getLong(0), s.getLong(1))
            val key = text(s, 7).ifEmpty { DialItem.fileKey(id) }
            if (s.getLong(6) != 0L) {
                hidden += DialHidden(key, s.getLong(5))
            } else {
                items += DialItem(
                    key = key,
                    track = id,
                    songKey = text(s, 2),
                    title = text(s, 8).ifBlank { text(s, 9) },
                    artist = text(s, 10),
                    coverKey = text(s, 11),
                    thumbFileId = s.getLong(12).toInt(),
                    positionMs = s.getLong(3).coerceAtLeast(0),
                    durationMs = s.getLong(4).takeIf { it > 0 } ?: s.getLong(13).coerceAtLeast(0),
                    atMs = s.getLong(5),
                    coverArt = CoverArt.keyOf(text(s, 14), text(s, 7).ifEmpty { "${KEY_SEPARATOR}m${id.chatId}:${id.messageId}" }),
                )
            }
        }
        items to hidden
    }

    /** The latest play of each song, as Speed dial cards that start from the top, newest first. */
    suspend fun recentPlays(limit: Int): List<DialItem> = io {
        query(
            "SELECT ${prefixed("s")}, MAX(p.at_sec) AS at, MAX(p.play_id) AS seq FROM plays p " +
                "JOIN songs s ON s.song_key = ${resolved("p")} WHERE 1 = 1${visible("s.")} GROUP BY s.song_key ORDER BY at DESC, seq DESC LIMIT ?",
            listSize(limit),
        ) { s ->
            val song = readSong(s)
            DialItem(
                key = song.groupKey,
                track = song.defaultTrack,
                songKey = song.groupKey,
                title = song.title,
                artist = song.artist,
                coverKey = song.coverKey,
                thumbFileId = song.thumbFileId,
                durationMs = song.durationMs,
                atMs = s.getLong(SONG_COLUMN_COUNT) * 1000,
                coverArt = CoverArt.keyOf(song.albumKey, song.groupKey),
            )
        }
    }

    // Playlists

    /** Every playlist, the one changed last first, each with the songs it can still play counted. */
    suspend fun playlists(): List<Playlist> = io {
        val rows = query(
            "SELECT playlist_id, name, updated_sec FROM playlists ORDER BY updated_sec DESC, playlist_id DESC LIMIT $MAX_PLAYLISTS",
        ) { Triple(it.getLong(0), text(it, 1), it.getLong(2)) }
        rows.map { (id, name, updated) ->
            val songs = count(
                "SELECT COUNT(DISTINCT s.song_key) FROM playlist_items i JOIN songs s ON s.song_key = ${resolved("i")} " +
                    "WHERE i.playlist_id = ?",
                id,
            )
            val cover = query(
                "SELECT s.thumb_file_id, s.cover_key FROM playlist_items i JOIN songs s ON s.song_key = ${resolved("i")} " +
                    "WHERE i.playlist_id = ? ORDER BY i.position, i.item_id LIMIT 1",
                id,
            ) { it.getLong(0).toInt() to text(it, 1) }.firstOrNull()
            Playlist(id, name, songs, cover?.first ?: 0, cover?.second.orEmpty(), updated)
        }
    }

    /**
     * Creates an empty playlist and returns its id. The name is trimmed, a blank one becomes
     * "Playlist" and a long one is cut to 200 characters. Returns -1 when [MAX_PLAYLISTS] exist.
     */
    suspend fun createPlaylist(name: String): Long = io {
        tx {
            if (count("SELECT COUNT(*) FROM playlists") >= MAX_PLAYLISTS) return@tx -1L
            val now = nowSec()
            exec("INSERT INTO playlists(name, created_sec, updated_sec) VALUES(?, ?, ?)", playlistName(name), now, now)
            query("SELECT last_insert_rowid()") { it.getLong(0) }.firstOrNull() ?: -1L
        }
    }

    /** Renames a playlist, with the same rules as [createPlaylist]; an unknown id is ignored. */
    suspend fun renamePlaylist(id: Long, name: String) = io {
        exec("UPDATE playlists SET name = ?, updated_sec = ? WHERE playlist_id = ?", playlistName(name), nowSec(), id)
    }

    /** Deletes a playlist and its entries; the songs themselves stay in the library. */
    suspend fun deletePlaylist(id: Long) = io {
        tx {
            exec("DELETE FROM playlist_items WHERE playlist_id = ?", id)
            exec("DELETE FROM playlists WHERE playlist_id = ?", id)
        }
    }

    /**
     * Appends [songs] to a playlist in order, skipping any it already holds (by the song it now
     * resolves to) and repeats within [songs]. Each entry stores the song's current default file.
     * Stops at [MAX_PLAYLIST_ITEMS] entries. Returns how many were added; 0 for an unknown id.
     */
    suspend fun addToPlaylist(id: Long, songs: List<Song>): Int = io {
        tx {
            if (count("SELECT COUNT(*) FROM playlists WHERE playlist_id = ?", id) == 0) return@tx 0
            val entries = playlistEntries(id)
            val have = entries.mapNotNullTo(HashSet()) { it.songKey }
            var total = entries.size
            var added = 0
            for (song in songs) {
                if (total >= MAX_PLAYLIST_ITEMS) break
                val key = song.groupKey
                if (key.isEmpty() || !have.add(key)) continue
                val target = currentDefault(song)
                exec(
                    "INSERT INTO playlist_items(playlist_id, position, chat_id, message_id, song_key, base_key) VALUES(?, ?, ?, ?, ?, ?)",
                    id, total, target.chatId, target.messageId, key, baseOfSongKey(key),
                )
                total++
                added++
            }
            if (added > 0) touchPlaylist(id)
            added
        }
    }

    /**
     * Removes the song at [position] of [playlistSongs] (positions count only the songs shown,
     * not hidden entries). Any hidden entry for the same song goes too, so it cannot reappear.
     * An out of range position is ignored.
     */
    suspend fun removeFromPlaylist(id: Long, position: Int) = io {
        tx {
            val all = playlistEntries(id)
            val target = visibleEntries(all).getOrNull(position) ?: return@tx
            val gone = all.filter { it.songKey == target.songKey }.toSet()
            for (entry in gone) exec("DELETE FROM playlist_items WHERE item_id = ?", entry.itemId)
            renumber(all.filter { it !in gone })
            touchPlaylist(id)
        }
    }

    /**
     * Moves the song at [from] to [to], both positions in [playlistSongs]. Hidden entries keep
     * their place relative to their neighbours. Out of range positions are ignored.
     */
    suspend fun movePlaylistItem(id: Long, from: Int, to: Int) = io {
        if (from == to) return@io
        tx {
            val all = playlistEntries(id)
            val visible = visibleEntries(all)
            val moving = visible.getOrNull(from) ?: return@tx
            val anchor = visible.getOrNull(to) ?: return@tx
            val rest = all.filter { it.itemId != moving.itemId }.toMutableList()
            val anchorAt = rest.indexOfFirst { it.itemId == anchor.itemId }
            if (anchorAt < 0) return@tx
            rest.add(if (from < to) anchorAt + 1 else anchorAt, moving)
            renumber(rest)
            touchPlaylist(id)
        }
    }

    /** The songs of a playlist in order; entries whose song cannot be found are left out, not deleted. */
    suspend fun playlistSongs(id: Long): List<Song> = io {
        query(
            "SELECT ${prefixed("s")} FROM playlist_items i JOIN songs s ON s.song_key = ${resolved("i")} " +
                "WHERE i.playlist_id = ? ORDER BY i.position, i.item_id LIMIT $MAX_PLAYLIST_ITEMS",
            id, map = ::readSong,
        ).distinctBy { it.groupKey }
    }

    /** The playlists that hold [song] now, for the ticks in the Add to playlist sheet. */
    suspend fun playlistsContaining(song: Song): Set<Long> = io {
        if (song.groupKey.isEmpty()) return@io emptySet()
        refsOf("playlist_items", "r.playlist_id", song.groupKey) { it.getLong(0) }.toHashSet()
    }

    // Search

    /**
     * Finds songs (by title, artist or album), albums (by title or artist) and artists (by name)
     * holding every word of [query], in any order, without regard to case or accents. Each list
     * puts a name that starts with the whole query first, then one with a word starting with the
     * first query word, then the rest, each by name. A blank query or one over 200 characters finds
     * nothing. Each list holds at most [limit] rows.
     */
    suspend fun search(query: String, limit: Int = 30): SearchResults = io {
        val words = searchWords(query)
        if (words.isEmpty()) return@io SearchResults.EMPTY
        val n = limit.coerceIn(1, MAX_SEARCH)
        val contains: List<Any?> = words.map { "%" + likeEscape(it) + "%" }
        val startsWithQuery = likeEscape(words.joinToString(" ")) + "%"
        val wordStartsWithFirst = "% " + likeEscape(words.firstOrNull().orEmpty()) + "%"
        val args: Array<Any?> = (contains + listOf(startsWithQuery, wordStartsWithFirst, n)).toTypedArray()

        fun where(column: String) = words.joinToString(" AND ") { "$column LIKE ? ESCAPE '\\'" }
        fun rank(column: String) =
            "CASE WHEN $column LIKE ? ESCAPE '\\' THEN 0 WHEN (' ' || $column) LIKE ? ESCAPE '\\' THEN 1 ELSE 2 END"

        // Songs are found through the narrow search index first, then read whole by key.
        val songKeys = this.query(
            "SELECT song_key FROM songs WHERE ${where("search")}${visible()} ORDER BY ${rank("search")}, search, song_key LIMIT ?",
            *args,
        ) { text(it, 0) }
        val songs = songKeys.mapNotNull { key ->
            this.query("SELECT $SONG_COLUMNS FROM songs WHERE song_key = ? LIMIT 1", key, map = ::readSong).firstOrNull()
        }
        val albumCount = albumVisibleCount()
        val albums = this.query(
            "SELECT a.album_key, a.title, a.artist, a.year, ${albumCount ?: "a.song_count"}, a.thumb_file_id, a.cover_key " +
                "FROM albums a WHERE ${where("a.search")}${albumCount?.let { " AND $it > 0" }.orEmpty()} " +
                "ORDER BY ${rank("a.search")}, a.search, a.album_key LIMIT ?",
            *args, map = ::readAlbum,
        )
        val artistCounts = artistVisibleCounts()
        val artists = this.query(
            "SELECT r.artist_key, r.name, ${artistCounts?.first ?: "r.song_count"}, ${artistCounts?.second ?: "r.album_count"} " +
                "FROM artists r WHERE ${where("r.name_sort")}${artistCounts?.let { " AND ${it.first} > 0" }.orEmpty()} " +
                "ORDER BY ${rank("r.name_sort")}, r.name_sort, r.artist_key LIMIT ?",
            *args, map = ::readArtist,
        )
        SearchResults(songs, albums, artists)
    }

    /** The last searches the user ran, newest first, at most [MAX_RECENT_SEARCHES]. */
    suspend fun recentSearches(): List<String> = io {
        query("SELECT query FROM recent_searches ORDER BY seq DESC LIMIT $MAX_RECENT_SEARCHES") { text(it, 0) }
    }

    /**
     * Remembers a search the user ran. It is trimmed; a blank one or one over 200 characters is
     * ignored; the same words in another case replace the older entry and move to the top.
     */
    suspend fun addRecentSearch(query: String) = io {
        val key = recentSearchKey(query)
        if (key.isEmpty() || key.length > MAX_QUERY_CHARS) return@io
        val shown = query.trim().replace(SPACES, " ")
        tx {
            val seq = (this.query("SELECT MAX(seq) FROM recent_searches") { it.getLong(0) }.firstOrNull() ?: 0L) + 1
            exec("INSERT OR REPLACE INTO recent_searches(folded, query, seq) VALUES(?, ?, ?)", key, shown, seq)
            exec(
                "DELETE FROM recent_searches WHERE seq <= " +
                    "(SELECT seq FROM recent_searches ORDER BY seq DESC LIMIT 1 OFFSET $MAX_RECENT_SEARCHES)",
            )
        }
    }

    /** Forgets every recent search. */
    suspend fun clearRecentSearches() = io { exec("DELETE FROM recent_searches") }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            for (statement in statements.values) runCatching { statement.close() }
            statements.clear()
            runCatching { db.close() }
        }
    }

    /**
     * The songs an album (aliased a) still shows under [hiddenMask], as SQL, or null when nothing is
     * hidden and the stored count holds. Counted through `tracks_album`, a page of albums at a time.
     */
    private fun albumVisibleCount(): String? {
        val filter = visible("s.").ifEmpty { return null }
        return "(SELECT COUNT(DISTINCT t.song_key) FROM tracks t JOIN songs s ON s.song_key = t.song_key " +
            "WHERE t.album_key = a.album_key$filter)"
    }

    /** The songs and albums an artist (aliased r) still shows under [hiddenMask], or null when nothing is hidden. */
    private fun artistVisibleCounts(): Pair<String, String>? {
        val filter = visible("s.").ifEmpty { return null }
        val from = "FROM track_artists x JOIN songs s ON s.song_key = x.song_key WHERE x.artist_key = r.artist_key$filter"
        return "(SELECT COUNT(DISTINCT x.song_key) $from)" to "(SELECT COUNT(DISTINCT NULLIF(x.album_key, '')) $from)"
    }

    // Rebuilding what a write touched

    /** The keys a write touched, gathered first and rebuilt once each at the end. */
    private class Touched {
        val bases = HashSet<String>()
        val albums = HashSet<String>()
        val artists = HashSet<String>()
        val chats = HashSet<Long>()
    }

    /** Adds the keys the stored row for [id] had, so whatever it leaves behind is rebuilt too. */
    private fun touchOld(id: TrackId, touched: Touched): Boolean {
        val (base, album) = query(
            "SELECT group_key, album_key FROM tracks WHERE chat_id = ? AND message_id = ? LIMIT 1",
            id.chatId, id.messageId,
        ) { text(it, 0) to text(it, 1) }.firstOrNull() ?: return false
        touched.bases += base
        touched.albums += album
        touched.artists += artistKeysOf(id.chatId, id.messageId)
        return true
    }

    /** The keys of the artists stored for one file. */
    private fun artistKeysOf(chatId: Long, messageId: Long): List<String> = query(
        "SELECT artist_key FROM track_artists WHERE chat_id = ? AND message_id = ? LIMIT ${ArtistCredits.MAX_ARTISTS}",
        chatId, messageId,
    ) { text(it, 0) }

    private fun rebuild(touched: Touched) {
        for (base in touched.bases) rebuildBase(base, touched)
        for (album in touched.albums) rebuildAlbum(album)
        for (artist in touched.artists) rebuildArtist(artist)
        for (chatId in touched.chats) {
            exec("UPDATE chats SET track_count = (SELECT COUNT(*) FROM tracks WHERE chat_id = ?) WHERE chat_id = ?", chatId, chatId)
        }
    }

    /**
     * Rebuilds the songs of one base key: its files are split into recordings by length, each
     * recording is one song keyed base plus cluster, and a file whose song changed marks its album
     * and artist for a rebuild too.
     */
    private fun rebuildBase(base: String, touched: Touched) {
        val members = query(
            "SELECT $TRACK_COLUMNS, song_key, album_key FROM tracks WHERE group_key = ? LIMIT $MAX_GROUP",
            base,
        ) { Member(readTrack(it), text(it, TRACK_COLUMN_COUNT), text(it, TRACK_COLUMN_COUNT + 1)) }
        exec("DELETE FROM songs WHERE base_key = ?", base)
        if (members.isEmpty()) return
        val artistKnown = base.substringBefore(KEY_SEPARATOR).isNotBlank()
        val assigned = try {
            clusters(members.map { it.track.durationMs }, artistKnown)
        } catch (e: Exception) {
            Logger.w(TAG, "clusters failed for a group, keeping it whole", e)
            emptyList()
        }
        val byCluster = members.indices.groupBy { assigned.getOrNull(it) ?: 0 }
        for ((cluster, indices) in byCluster) {
            val songKey = base + KEY_SEPARATOR + "d" + cluster
            val group = indices.map { members[it] }
            for (member in group) {
                if (member.songKey == songKey) continue
                exec(
                    "UPDATE tracks SET song_key = ? WHERE chat_id = ? AND message_id = ?",
                    songKey, member.track.chatId, member.track.messageId,
                )
                exec(
                    "UPDATE track_artists SET song_key = ? WHERE chat_id = ? AND message_id = ?",
                    songKey, member.track.chatId, member.track.messageId,
                )
                // A file that moved from one song to another carries what the user kept about it
                // along, so a later fallback by base key looks for the song it is in now.
                if (member.songKey.isNotEmpty()) {
                    for (table in REF_TABLES) {
                        exec(
                            "UPDATE $table SET song_key = ?, base_key = ? WHERE chat_id = ? AND message_id = ?",
                            songKey, base, member.track.chatId, member.track.messageId,
                        )
                    }
                }
                touched.albums += member.albumKey
                touched.artists += artistKeysOf(member.track.chatId, member.track.messageId)
            }
            writeSong(base, songKey, group.map { it.track })
        }
    }

    private fun writeSong(base: String, songKey: String, versions: List<Track>) {
        val chosen = try {
            pick(versions)
        } catch (e: Exception) {
            Logger.w(TAG, "pick failed for a song, using the default rule", e)
            null
        }
        val default = versions.firstOrNull { it.id == chosen?.id } ?: defaultPick(versions) ?: return
        val title = default.title.ifBlank { default.fileName }
        val versionCount = versions.mapTo(HashSet()) { it.uniqueId.ifBlank { "${it.chatId}:${it.messageId}" } }.size
        val best = versions.minByOrNull { formatRank(it.format) }?.format ?: default.format
        val thumb = default.thumbFileId.takeIf { it != 0 } ?: versions.firstOrNull { it.thumbFileId != 0 }?.thumbFileId ?: 0
        val cover = default.coverKey.ifBlank { versions.firstOrNull { it.coverKey.isNotBlank() }?.coverKey.orEmpty() }
        val duration = default.durationMs.takeIf { it > 0 } ?: versions.firstOrNull { it.durationMs > 0 }?.durationMs ?: 0
        val added = versions.maxOf { it.dateSec }
        val albumKey = indexAlbumKey(default).ifEmpty {
            versions.firstNotNullOfOrNull { v -> indexAlbumKey(v).takeIf { it.isNotEmpty() } }.orEmpty()
        }
        exec(
            "INSERT OR REPLACE INTO songs($SONG_COLUMNS, base_key, title_sort, artist_sort, album_sort, added_neg, search, hide) " +
                "VALUES(${List(22) { "?" }.joinToString()})",
            songKey, title, default.artist, default.album, duration, default.chatId, default.messageId,
            versionCount, best.name, thumb, cover, default.variant, added, albumKey, indexArtistKey(default),
            base, titleOrder(title), artistOrder(default.artist, title), albumOrder(default.album, title), -added,
            searchText(title, default.artist, default.album),
            // Judged with the song's duration, so a document whose length a sibling knows is judged too.
            ClipRules.bits(default.copy(durationMs = duration)),
        )
    }

    private fun rebuildAlbum(key: String) {
        if (key.isEmpty()) return
        val songs = count("SELECT COUNT(DISTINCT song_key) FROM tracks WHERE album_key = ?", key)
        if (songs == 0) {
            exec("DELETE FROM albums WHERE album_key = ?", key)
            return
        }
        // The album is shown with the fields of a file that has a cover, so its tile is not blank.
        val album = query(
            "SELECT album, album_artist, artist, year, thumb_file_id, cover_key FROM tracks WHERE album_key = ? " +
                "ORDER BY (thumb_file_id = 0 AND cover_key = ''), chat_id, message_id LIMIT 1",
            key,
        ) {
            Album(
                key = key,
                title = text(it, 0),
                artist = text(it, 1).ifBlank { text(it, 2) },
                year = it.getLong(3).toInt(),
                songCount = songs,
                thumbFileId = it.getLong(4).toInt(),
                coverKey = text(it, 5),
            )
        }.firstOrNull() ?: return
        exec(
            "INSERT OR REPLACE INTO albums(album_key, title, artist, year, song_count, thumb_file_id, cover_key, title_sort, search) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
            key, album.title, album.artist, album.year, songs, album.thumbFileId, album.coverKey, titleOrder(album.title),
            searchText(album.title, album.artist),
        )
    }

    /**
     * Counts one split artist again from `track_artists`: songs and albums with a file whose credit
     * names it, shown with the first spelling stored (lowest chat and message id), as the version 2
     * migration does for every artist at once.
     */
    private fun rebuildArtist(key: String) {
        if (key.isEmpty()) return
        // One statement per artist: the scan touches hundreds of artists per batch.
        val (songs, albums, name) = query(
            "SELECT COUNT(DISTINCT song_key), COUNT(DISTINCT NULLIF(album_key, '')), " +
                "(SELECT name FROM track_artists WHERE artist_key = ?1 ORDER BY chat_id, message_id LIMIT 1) " +
                "FROM track_artists WHERE artist_key = ?1",
            key,
        ) { Triple(it.getLong(0).toInt(), it.getLong(1).toInt(), text(it, 2)) }.firstOrNull() ?: Triple(0, 0, "")
        if (songs == 0 || name.isEmpty()) {
            exec("DELETE FROM artists WHERE artist_key = ?", key)
            return
        }
        exec(
            "INSERT OR REPLACE INTO artists(artist_key, name, song_count, album_count, name_sort) VALUES(?, ?, ?, ?, ?)",
            key, name, songs, albums, sortOrLast(name),
        )
    }

    /** Deletes a chat's files and lyrics and rebuilds what they were in; the caller holds the transaction. */
    private fun purgeChat(chatId: Long, keepRow: Boolean) {
        val touched = Touched()
        query("SELECT group_key, album_key FROM tracks WHERE chat_id = ? LIMIT $MAX_ROWS", chatId) {
            text(it, 0) to text(it, 1)
        }.forEach { (base, album) ->
            touched.bases += base
            touched.albums += album
        }
        query("SELECT DISTINCT artist_key FROM track_artists WHERE chat_id = ? LIMIT $MAX_ROWS", chatId) { text(it, 0) }
            .forEach { touched.artists += it }
        exec("DELETE FROM tracks WHERE chat_id = ?", chatId)
        exec("DELETE FROM track_artists WHERE chat_id = ?", chatId)
        exec("DELETE FROM lyrics_files WHERE chat_id = ?", chatId)
        // Listening history of a chat the user left goes with it; favourites and playlist entries
        // stay and fall back to another version, or hide until the chat is added again.
        exec("DELETE FROM plays WHERE chat_id = ?", chatId)
        exec("DELETE FROM resume_points WHERE chat_id = ?", chatId)
        rebuild(touched)
        if (keepRow) {
            exec(
                """
                UPDATE chats SET picked = 0, audio_from = 0, audio_newest = 0, audio_done = 0, documents_from = 0,
                    documents_newest = 0, documents_done = 0, last_scan_at = 0, track_count = 0 WHERE chat_id = ?
                """,
                chatId,
            )
        } else {
            exec("DELETE FROM chats WHERE chat_id = ?", chatId)
        }
    }

    private class Member(val track: Track, val songKey: String, val albumKey: String)

    // What the user keeps, resolved to songs

    /** One stored playlist entry with the song it resolves to now, null when it is hidden. */
    private data class Entry(val itemId: Long, val position: Int, val songKey: String?)

    /** Every entry of a playlist in order, hidden ones included. */
    private fun playlistEntries(id: Long): List<Entry> = query(
        "SELECT i.item_id, i.position, s.song_key FROM playlist_items i LEFT JOIN songs s ON s.song_key = ${resolved("i")} " +
            "WHERE i.playlist_id = ? ORDER BY i.position, i.item_id LIMIT $MAX_PLAYLIST_ITEMS",
        id,
    ) { Entry(it.getLong(0), it.getLong(1).toInt(), if (it.isNull(2)) null else it.getText(2)) }

    /** The entries [playlistSongs] shows: resolvable, and only the first of two that became one song. */
    private fun visibleEntries(all: List<Entry>): List<Entry> {
        val seen = HashSet<String>()
        return all.filter { entry -> entry.songKey?.let { seen.add(it) } == true }
    }

    /** Writes positions 0 to n-1 in the order of [entries], touching only rows that moved. */
    private fun renumber(entries: List<Entry>) {
        entries.forEachIndexed { i, entry ->
            if (entry.position != i) exec("UPDATE playlist_items SET position = ? WHERE item_id = ?", i, entry.itemId)
        }
    }

    private fun touchPlaylist(id: Long) {
        exec("UPDATE playlists SET updated_sec = ? WHERE playlist_id = ?", nowSec(), id)
    }

    /**
     * The file to store for [song]: its default as the index has it now, or the one the caller
     * holds when the song is no longer there.
     */
    private fun currentDefault(song: Song): TrackId = query(
        "SELECT default_chat_id, default_message_id FROM songs WHERE song_key = ? LIMIT 1",
        song.groupKey,
    ) { TrackId(it.getLong(0), it.getLong(1)) }.firstOrNull() ?: song.defaultTrack

    /**
     * The rows of [table] (favourites, plays or playlist items, aliased r) that resolve to
     * [songKey] now. Only two kinds of row can: one whose file is a version of the song, and one
     * whose stored base key is the song's, so both are found through an index instead of a scan.
     */
    private fun <T> refsOf(table: String, column: String, songKey: String, map: (SQLiteStatement) -> T): List<T> = query(
        """
        SELECT $column FROM $table r WHERE r.base_key = ? AND ${resolved("r")} = ?
        UNION
        SELECT $column FROM tracks t JOIN $table r ON r.chat_id = t.chat_id AND r.message_id = t.message_id
        WHERE t.song_key = ? AND ${resolved("r")} = ?
        LIMIT $MAX_ROWS
        """,
        baseOfSongKey(songKey), songKey, songKey, songKey, map = map,
    )

    // SQLite plumbing

    /** Runs [block] on the IO pool holding the connection's lock, so calls never overlap. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            check(!closed) { "the library index is closed" }
            block()
        }
    }

    private inline fun <T> tx(block: () -> T): T {
        db.execSQL("BEGIN IMMEDIATE")
        try {
            val result = block()
            db.execSQL("COMMIT")
            return result
        } catch (e: Throwable) {
            runCatching { db.execSQL("ROLLBACK") }
            throw e
        }
    }

    /** A prepared statement for [sql], kept for reuse, reset and with its bindings cleared. */
    private fun statement(sql: String, args: Array<out Any?>): SQLiteStatement {
        val statement = statements.getOrPut(sql) { db.prepare(sql) }
        statement.reset()
        statement.clearBindings()
        args.forEachIndexed { i, value ->
            val at = i + 1
            when (value) {
                null -> statement.bindNull(at)
                is String -> statement.bindText(at, value)
                is Long -> statement.bindLong(at, value)
                is Int -> statement.bindLong(at, value.toLong())
                is Boolean -> statement.bindLong(at, if (value) 1 else 0)
                is Double -> statement.bindDouble(at, value)
                else -> statement.bindText(at, value.toString())
            }
        }
        return statement
    }

    private fun exec(sql: String, vararg args: Any?) {
        revision++
        val statement = statement(sql, args)
        try {
            statement.step()
        } finally {
            statement.reset()
        }
    }

    private fun <T> query(sql: String, vararg args: Any?, map: (SQLiteStatement) -> T): List<T> {
        val statement = statement(sql, args)
        try {
            val out = ArrayList<T>()
            while (statement.step()) out += map(statement)
            return out
        } finally {
            statement.reset()
        }
    }

    private fun count(sql: String, vararg args: Any?): Int =
        query(sql, *args) { it.getLong(0).toInt() }.firstOrNull() ?: 0

    companion object {
        private const val TAG = "LibraryIndex"

        /** Rows any one query may return; far above a real account, there only so nothing is unbounded. */
        private const val MAX_ROWS = 1_000_000

        /** Files one base key may hold; a key shared by more is a tagging accident, not a song. */
        private const val MAX_GROUP = 20_000

        /** Favourites kept; far above a real collection, there so the heart set stays bounded. */
        const val MAX_FAVOURITES = 20_000

        /** Plays kept for Recently played; older ones are trimmed as new ones come. */
        const val MAX_PLAYS = 5_000

        /** Playlists one library may hold, so the Playlists tab is one bounded read. */
        const val MAX_PLAYLISTS = 1_000

        /** Entries one playlist may hold. */
        const val MAX_PLAYLIST_ITEMS = 10_000

        /** Recent searches kept. */
        const val MAX_RECENT_SEARCHES = 10

        private const val MAX_SEARCH = 200
        private val SPACES = Regex("\\s+")

        /** The tables whose rows point at a file and a song key, kept current when a file changes song. */
        private val REF_TABLES = listOf("favourites", "plays", "playlist_items")

        private const val MAX_VERSIONS = 500

        /** Files [tidyStoredNames] rewrites per transaction. */
        private const val TIDY_BATCH = 500
        private const val MAX_PAGE = 1_000
        private const val ID_CHUNK = 200
        private const val MAX_LIST = 5_000

        private const val TRACK_COLUMNS =
            "chat_id, message_id, file_id, unique_id, file_name, mime, size_bytes, date_sec, title, artist, album, " +
                "album_artist, year, track_number, disc_number, duration_ms, format, bitrate_kbps, sample_rate, " +
                "bit_depth, tag_state, thumb_file_id, cover_key, group_key, variant, channels"
        private const val TRACK_COLUMN_COUNT = 26
        private const val SONG_COLUMNS =
            "song_key, title, artist, album, duration_ms, default_chat_id, default_message_id, version_count, " +
                "best_format, thumb_file_id, cover_key, variant, added_sec, album_key, artist_key"
        private const val SONG_COLUMN_COUNT = 15
        private const val ALBUM_COLUMNS = "album_key, title, artist, year, song_count, thumb_file_id, cover_key"
        private const val ARTIST_COLUMNS = "artist_key, name, song_count, album_count"
        private const val CHAT_COLUMNS =
            "chat_id, title, picked, added_at, audio_from, audio_newest, audio_done, documents_from, " +
                "documents_newest, documents_done, last_scan_at, track_count"

        /**
         * Opens the index at [path] (":memory:" for a throwaway one), creating or migrating it.
         * A file that is not a database, is damaged or comes from a newer app is renamed to
         * `<path>.broken` and a fresh index is created, so a bad file never stops the app. Call it
         * off the main thread: it touches the disk.
         */
        fun open(
            path: String,
            pick: (List<Track>) -> Track? = { TrackVersions.pick(it) },
            clusters: (List<Long>, Boolean) -> List<Int> = GroupKey::clusters,
        ): LibraryIndex {
            val db = try {
                connect(path)
            } catch (e: Exception) {
                Logger.w(TAG, "the library index could not be read, starting a fresh one", e)
                moveAside(path)
                try {
                    connect(path)
                } catch (again: Exception) {
                    Logger.w(TAG, "a fresh library index could not be created either, keeping it in memory", again)
                    connect(":memory:")
                }
            }
            val index = LibraryIndex(db, pick, clusters)
            // Older rows get today's names; a failure here only leaves them as they were.
            runCatching { index.lock.withLock { index.tidyStoredNamesLocked() } }
                .onSuccess { if (it > 0) Logger.i(TAG, "tidied the names of $it stored files") }
                .onFailure { Logger.w(TAG, "tidying stored names failed", it) }
            return index
        }

        private fun connect(path: String): SQLiteConnection {
            val db = BundledSQLiteDriver().open(path)
            try {
                db.execSQL("PRAGMA journal_mode = WAL")
                db.execSQL("PRAGMA synchronous = NORMAL")
                db.execSQL("PRAGMA foreign_keys = OFF")
                val version = db.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0).toInt() else 0 }
                check(version <= IndexSchema.VERSION) { "index schema $version is newer than ${IndexSchema.VERSION}" }
                // Each step commits its own work and moves user_version on by one (see MIGRATIONS).
                for (from in version until IndexSchema.VERSION) {
                    val step = IndexSchema.MIGRATIONS[from] ?: error("no migration from index schema $from")
                    step(db)
                }
                // Reads one row of each table, so a file whose header is fine but whose tables are
                // not fails here, at open, instead of on the first screen.
                for (table in listOf(
                        "chats", "tracks", "track_artists", "songs", "albums", "artists", "lyrics_files",
                        "favourites", "plays", "playlists", "playlist_items", "recent_searches", "resume_points",
                    )) {
                    db.prepare("SELECT * FROM $table LIMIT 1").use { it.step() }
                }
                return db
            } catch (e: Throwable) {
                runCatching { db.close() }
                throw e
            }
        }

        private fun moveAside(path: String) {
            if (path == ":memory:" || path.isEmpty()) return
            runCatching {
                val file = File(path)
                val broken = File("$path.broken")
                broken.delete()
                if (file.exists() && !file.renameTo(broken)) file.delete()
                File("$path-wal").delete()
                File("$path-shm").delete()
            }.onFailure { Logger.w(TAG, "could not move the broken library index aside", it) }
        }

        /**
         * SQL for the song a stored row (aliased [alias], with chat_id, message_id, song_key and
         * base_key) resolves to: the song its file is in now, else the song with its base key,
         * preferring its own cluster, else NULL, which hides the row.
         */
        private fun resolved(alias: String): String =
            "COALESCE((SELECT NULLIF(t.song_key, '') FROM tracks t WHERE t.chat_id = $alias.chat_id AND " +
                "t.message_id = $alias.message_id), " +
                "(SELECT b.song_key FROM songs b WHERE b.song_key = $alias.song_key AND b.base_key = $alias.base_key), " +
                "(SELECT b.song_key FROM songs b WHERE b.base_key = $alias.base_key ORDER BY b.song_key LIMIT 1))"

        /** [SONG_COLUMNS] each prefixed by a table alias, for joins. */
        private fun prefixed(alias: String): String = SONG_COLUMNS.split(", ").joinToString { "$alias.$it" }

        private fun nowSec(): Long = System.currentTimeMillis() / 1000

        private fun sortColumn(sort: SongSort): String = when (sort) {
            SongSort.Title -> "title_sort"
            SongSort.Artist -> "artist_sort"
            SongSort.Album -> "album_sort"
            SongSort.Added -> "added_neg"
        }

        private fun pageSize(limit: Int): Int = limit.coerceIn(1, MAX_PAGE)

        private fun listSize(limit: Int): Int = limit.coerceIn(1, MAX_LIST)

        private fun lyricsName(name: String): String = name.trim().lowercase(Locale.ROOT)

        /** Text from a column, empty when the file holds NULL there. */
        private fun text(s: SQLiteStatement, i: Int): String = if (s.isNull(i)) "" else s.getText(i)

        private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback

        private fun trackValues(t: Track, base: String): Array<Any?> = arrayOf(
            t.chatId, t.messageId, t.fileId, t.uniqueId, t.fileName, t.mime, t.sizeBytes, t.dateSec, t.title,
            t.artist, t.album, t.albumArtist, t.year, t.trackNumber, t.discNumber, t.durationMs, t.format.name,
            t.bitrateKbps, t.sampleRate, t.bitDepth, t.tagState.name, t.thumbFileId, t.coverKey, base, t.variant,
            t.channels,
        )

        private fun readTrack(s: SQLiteStatement) = Track(
            chatId = s.getLong(0),
            messageId = s.getLong(1),
            fileId = s.getLong(2).toInt(),
            uniqueId = text(s, 3),
            fileName = text(s, 4),
            mime = text(s, 5),
            sizeBytes = s.getLong(6),
            dateSec = s.getLong(7),
            title = text(s, 8),
            artist = text(s, 9),
            album = text(s, 10),
            albumArtist = text(s, 11),
            year = s.getLong(12).toInt(),
            trackNumber = s.getLong(13).toInt(),
            discNumber = s.getLong(14).toInt(),
            durationMs = s.getLong(15),
            format = enumOr(text(s, 16), AudioFormat.OTHER),
            bitrateKbps = s.getLong(17).toInt(),
            sampleRate = s.getLong(18).toInt(),
            bitDepth = s.getLong(19).toInt(),
            tagState = enumOr(text(s, 20), TagState.TelegramOnly),
            thumbFileId = s.getLong(21).toInt(),
            coverKey = text(s, 22),
            groupKey = text(s, 23),
            variant = text(s, 24),
            channels = s.getLong(25).toInt(),
        )

        private fun readSong(s: SQLiteStatement) = Song(
            groupKey = text(s, 0),
            title = text(s, 1),
            artist = text(s, 2),
            album = text(s, 3),
            durationMs = s.getLong(4),
            defaultTrack = TrackId(s.getLong(5), s.getLong(6)),
            versionCount = s.getLong(7).toInt(),
            bestFormat = enumOr(text(s, 8), AudioFormat.OTHER),
            thumbFileId = s.getLong(9).toInt(),
            coverKey = text(s, 10),
            variant = text(s, 11),
            addedSec = s.getLong(12),
            albumKey = text(s, 13),
            artistKey = text(s, 14),
        )

        private fun readAlbum(s: SQLiteStatement) = Album(
            key = text(s, 0),
            title = text(s, 1),
            artist = text(s, 2),
            year = s.getLong(3).toInt(),
            songCount = s.getLong(4).toInt(),
            thumbFileId = s.getLong(5).toInt(),
            coverKey = text(s, 6),
        )

        private fun readArtist(s: SQLiteStatement) = Artist(
            key = text(s, 0),
            name = text(s, 1),
            songCount = s.getLong(2).toInt(),
            albumCount = s.getLong(3).toInt(),
        )

        private fun readChat(s: SQLiteStatement) = ChatRow(
            chatId = s.getLong(0),
            title = text(s, 1),
            picked = s.getLong(2) == 1L,
            addedAt = s.getLong(3),
            scan = ScanState(
                audio = ScanCursor(s.getLong(4), s.getLong(6) == 1L, s.getLong(5)),
                documents = ScanCursor(s.getLong(7), s.getLong(9) == 1L, s.getLong(8)),
                lastScanAt = s.getLong(10),
            ),
            trackCount = s.getLong(11).toInt(),
        )
    }
}
