package com.tmplayer.music.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The index's tables. Songs, albums and artists are materialised: each is rebuilt only for the
 * keys a write touched, so a read at 100,000 tracks is an index range scan, never a GROUP BY over
 * every track. Every list has an index that matches its ORDER BY exactly, so keyset paging stays as
 * fast on the last page as on the first.
 */
internal object IndexSchema {

    /** Raised whenever a table or an index changes; [MIGRATIONS] carries an older file forward. */
    const val VERSION = 2

    /**
     * Steps from one version to the next, keyed by the version they start from. Each step commits
     * its own work and sets `user_version` to the next version in its last transaction, so a step
     * cut short (the app killed part way) leaves the old version and runs again from the start on
     * the next open; every step is safe to run twice. The index is a cache of what Telegram holds,
     * so a step may also drop a table and let the scanner refill it, but never the user's tables
     * (see [createUserData]).
     */
    val MIGRATIONS: Map<Int, (SQLiteConnection) -> Unit> = mapOf(
        0 to { db ->
            inTransaction(db) {
                createV1(db)
                setVersion(db, 1)
            }
        },
        1 to ::splitArtistsV2,
    )

    /** Tracks the version 2 step reads per transaction, so 100,000 tracks are 50 short commits. */
    internal const val ARTIST_BATCH = 2_000

    private fun inTransaction(db: SQLiteConnection, block: () -> Unit) {
        db.execSQL("BEGIN IMMEDIATE")
        try {
            block()
            db.execSQL("COMMIT")
        } catch (e: Throwable) {
            runCatching { db.execSQL("ROLLBACK") }
            throw e
        }
    }

    private fun setVersion(db: SQLiteConnection, version: Int) {
        db.execSQL("PRAGMA user_version = $version")
    }

    /** The first schema; internal so a test can build a version 1 file and migrate it. */
    internal fun createV1(db: SQLiteConnection) {
        db.execSQL(
            """
            CREATE TABLE chats(
                chat_id INTEGER PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                picked INTEGER NOT NULL DEFAULT 1,
                added_at INTEGER NOT NULL DEFAULT 0,
                audio_from INTEGER NOT NULL DEFAULT 0,
                audio_newest INTEGER NOT NULL DEFAULT 0,
                audio_done INTEGER NOT NULL DEFAULT 0,
                documents_from INTEGER NOT NULL DEFAULT 0,
                documents_newest INTEGER NOT NULL DEFAULT 0,
                documents_done INTEGER NOT NULL DEFAULT 0,
                last_scan_at INTEGER NOT NULL DEFAULT 0,
                track_count INTEGER NOT NULL DEFAULT 0
            )
            """,
        )
        db.execSQL(
            """
            CREATE TABLE tracks(
                chat_id INTEGER NOT NULL,
                message_id INTEGER NOT NULL,
                file_id INTEGER NOT NULL DEFAULT 0,
                unique_id TEXT NOT NULL DEFAULT '',
                file_name TEXT NOT NULL DEFAULT '',
                mime TEXT NOT NULL DEFAULT '',
                size_bytes INTEGER NOT NULL DEFAULT 0,
                date_sec INTEGER NOT NULL DEFAULT 0,
                title TEXT NOT NULL DEFAULT '',
                artist TEXT NOT NULL DEFAULT '',
                album TEXT NOT NULL DEFAULT '',
                album_artist TEXT NOT NULL DEFAULT '',
                year INTEGER NOT NULL DEFAULT 0,
                track_number INTEGER NOT NULL DEFAULT 0,
                disc_number INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                format TEXT NOT NULL DEFAULT '',
                bitrate_kbps INTEGER NOT NULL DEFAULT 0,
                sample_rate INTEGER NOT NULL DEFAULT 0,
                bit_depth INTEGER NOT NULL DEFAULT 0,
                tag_state TEXT NOT NULL DEFAULT '',
                thumb_file_id INTEGER NOT NULL DEFAULT 0,
                cover_key TEXT NOT NULL DEFAULT '',
                group_key TEXT NOT NULL DEFAULT '',
                variant TEXT NOT NULL DEFAULT '',
                song_key TEXT NOT NULL DEFAULT '',
                album_key TEXT NOT NULL DEFAULT '',
                artist_key TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(chat_id, message_id)
            )
            """,
        )
        db.execSQL("CREATE INDEX tracks_group ON tracks(group_key)")
        db.execSQL("CREATE INDEX tracks_tags ON tracks(tag_state, date_sec)")
        db.execSQL("CREATE INDEX tracks_song ON tracks(song_key)")
        db.execSQL("CREATE INDEX tracks_album ON tracks(album_key, song_key)")
        db.execSQL("CREATE INDEX tracks_artist ON tracks(artist_key, song_key, album_key)")
        db.execSQL(
            """
            CREATE TABLE songs(
                song_key TEXT PRIMARY KEY,
                base_key TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                default_chat_id INTEGER NOT NULL,
                default_message_id INTEGER NOT NULL,
                version_count INTEGER NOT NULL,
                best_format TEXT NOT NULL,
                thumb_file_id INTEGER NOT NULL,
                cover_key TEXT NOT NULL,
                variant TEXT NOT NULL,
                added_sec INTEGER NOT NULL,
                title_sort TEXT NOT NULL,
                artist_sort TEXT NOT NULL,
                album_sort TEXT NOT NULL,
                added_neg INTEGER NOT NULL,
                album_key TEXT NOT NULL DEFAULT '',
                artist_key TEXT NOT NULL DEFAULT '',
                search TEXT NOT NULL DEFAULT ''
            ) WITHOUT ROWID
            """,
        )
        // Search reads this narrow index instead of the wide rows: it holds the folded text and,
        // as the table has no rowid, the song key, so a LIKE over every song never touches the table.
        db.execSQL("CREATE INDEX songs_search ON songs(search)")
        db.execSQL("CREATE INDEX songs_base ON songs(base_key)")
        db.execSQL("CREATE INDEX songs_title ON songs(title_sort, song_key)")
        db.execSQL("CREATE INDEX songs_artist ON songs(artist_sort, song_key)")
        db.execSQL("CREATE INDEX songs_album ON songs(album_sort, song_key)")
        db.execSQL("CREATE INDEX songs_added ON songs(added_neg, song_key)")
        db.execSQL(
            """
            CREATE TABLE albums(
                album_key TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                year INTEGER NOT NULL,
                song_count INTEGER NOT NULL,
                thumb_file_id INTEGER NOT NULL,
                cover_key TEXT NOT NULL,
                title_sort TEXT NOT NULL,
                search TEXT NOT NULL DEFAULT ''
            ) WITHOUT ROWID
            """,
        )
        db.execSQL("CREATE INDEX albums_title ON albums(title_sort, album_key)")
        db.execSQL("CREATE INDEX albums_search ON albums(search)")
        db.execSQL(
            """
            CREATE TABLE artists(
                artist_key TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                song_count INTEGER NOT NULL,
                album_count INTEGER NOT NULL,
                name_sort TEXT NOT NULL
            ) WITHOUT ROWID
            """,
        )
        db.execSQL("CREATE INDEX artists_name ON artists(name_sort, artist_key)")
        db.execSQL(
            """
            CREATE TABLE lyrics_files(
                chat_id INTEGER NOT NULL,
                message_id INTEGER NOT NULL,
                file_id INTEGER NOT NULL,
                base_name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                PRIMARY KEY(chat_id, message_id)
            )
            """,
        )
        db.execSQL("CREATE INDEX lyrics_name ON lyrics_files(chat_id, base_name)")
        createUserData(db)
    }

    /**
     * Version 2: artists split on credits ([ArtistCredits]). Adds `track_artists`, one row per artist
     * a track's credit names, fills it from the tracks already stored, then rebuilds `artists` from
     * it. Only cache tables change: favourites, plays, playlists and the saved queue key by file plus
     * song key, and neither a file nor a song key changes here. Tracks are read in key order in
     * batches of [ARTIST_BATCH], each in its own transaction, with the split done in memory and no
     * query per track; the artists are rebuilt with one grouped read in the last transaction, which
     * also sets the version.
     */
    private fun splitArtistsV2(db: SQLiteConnection) {
        inTransaction(db) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS track_artists(
                    chat_id INTEGER NOT NULL,
                    message_id INTEGER NOT NULL,
                    position INTEGER NOT NULL,
                    artist_key TEXT NOT NULL,
                    name TEXT NOT NULL,
                    song_key TEXT NOT NULL DEFAULT '',
                    album_key TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(chat_id, message_id, position)
                ) WITHOUT ROWID
                """,
            )
            // song_key and album_key copy the track's, so an artist's songs and albums are counted
            // from this table's index alone. The index is built once after the rows are in (see
            // below), which is far cheaper than keeping it up to date through 100,000 inserts.
            db.execSQL("DROP INDEX IF EXISTS track_artists_artist")
            // Artist pages read track_artists now; tracks.artist_key keeps only the first artist.
            db.execSQL("DROP INDEX IF EXISTS tracks_artist")
        }
        val read = db.prepare(
            "SELECT chat_id, message_id, artist, artist_key, song_key, album_key FROM tracks " +
                "WHERE (chat_id, message_id) > (?, ?) ORDER BY chat_id, message_id LIMIT $ARTIST_BATCH",
        )
        val clear = db.prepare(
            "DELETE FROM track_artists WHERE (chat_id, message_id) > (?, ?) AND (chat_id, message_id) <= (?, ?)",
        )
        val insert = db.prepare(
            "INSERT OR REPLACE INTO track_artists(chat_id, message_id, position, artist_key, name, song_key, album_key) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?)",
        )
        val rekey = db.prepare("UPDATE tracks SET artist_key = ? WHERE chat_id = ? AND message_id = ?")
        // Tracks come in chat and message order, so the first name seen for a key is the spelling of
        // the lowest file, the one [LibraryIndex] shows too. Bounded by the number of artists.
        val firstSpelling = HashMap<String, String>()
        try {
            var afterChat = Long.MIN_VALUE
            var afterMessage = Long.MIN_VALUE
            while (true) {
                val batch = ArrayList<StoredCredit>(ARTIST_BATCH)
                inTransaction(db) {
                    read.reset()
                    read.bindLong(1, afterChat)
                    read.bindLong(2, afterMessage)
                    while (read.step()) {
                        batch += StoredCredit(
                            read.getLong(0), read.getLong(1),
                            if (read.isNull(2)) "" else read.getText(2),
                            if (read.isNull(3)) "" else read.getText(3),
                            if (read.isNull(4)) "" else read.getText(4),
                            if (read.isNull(5)) "" else read.getText(5),
                        )
                    }
                    read.reset()
                    val last = batch.lastOrNull() ?: return@inTransaction
                    clear.reset()
                    clear.bindLong(1, afterChat)
                    clear.bindLong(2, afterMessage)
                    clear.bindLong(3, last.chatId)
                    clear.bindLong(4, last.messageId)
                    clear.step()
                    clear.reset()
                    for (row in batch) {
                        val artists = ArtistCredits.split(row.credit)
                        artists.forEachIndexed { position, artist ->
                            insert.reset()
                            insert.bindLong(1, row.chatId)
                            insert.bindLong(2, row.messageId)
                            insert.bindLong(3, position.toLong())
                            insert.bindText(4, artist.key)
                            insert.bindText(5, artist.name)
                            firstSpelling.putIfAbsent(artist.key, artist.name)
                            insert.bindText(6, row.songKey)
                            insert.bindText(7, row.albumKey)
                            insert.step()
                        }
                        val primary = artists.firstOrNull()?.key.orEmpty()
                        if (primary != row.oldKey) {
                            rekey.reset()
                            rekey.bindText(1, primary)
                            rekey.bindLong(2, row.chatId)
                            rekey.bindLong(3, row.messageId)
                            rekey.step()
                        }
                    }
                    insert.reset()
                    rekey.reset()
                    afterChat = last.chatId
                    afterMessage = last.messageId
                }
                if (batch.size < ARTIST_BATCH) break
            }
        } finally {
            runCatching { read.close() }
            runCatching { clear.close() }
            runCatching { insert.close() }
            runCatching { rekey.close() }
        }
        inTransaction(db) {
            db.execSQL(
                "UPDATE songs SET artist_key = COALESCE((SELECT t.artist_key FROM tracks t " +
                    "WHERE t.chat_id = songs.default_chat_id AND t.message_id = songs.default_message_id), artist_key)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS track_artists_artist ON track_artists(artist_key, song_key, album_key)")
            db.execSQL("DELETE FROM artists")
            val totals = ArrayList<ArtistTotal>()
            db.prepare(ARTIST_TOTALS_ALL).use { s ->
                while (s.step()) {
                    val key = s.getText(0)
                    totals += ArtistTotal(key = key, name = firstSpelling[key] ?: key, songs = s.getLong(1), albums = s.getLong(2))
                }
            }
            db.prepare(
                "INSERT OR REPLACE INTO artists(artist_key, name, song_count, album_count, name_sort) VALUES(?, ?, ?, ?, ?)",
            ).use { s ->
                for (total in totals) {
                    if (total.songs <= 0) continue
                    s.reset()
                    s.bindText(1, total.key)
                    s.bindText(2, total.name)
                    s.bindLong(3, total.songs)
                    s.bindLong(4, total.albums)
                    s.bindText(5, sortOrLast(total.name))
                    s.step()
                }
            }
            setVersion(db, 2)
        }
    }

    private class StoredCredit(
        val chatId: Long,
        val messageId: Long,
        val credit: String,
        val oldKey: String,
        val songKey: String,
        val albumKey: String,
    )

    private class ArtistTotal(val key: String, val name: String, val songs: Long, val albums: Long)

    /**
     * Every artist with its songs and its albums, in one pass over the `track_artists_artist` index.
     * [LibraryIndex] counts one artist by the same rules.
     */
    private const val ARTIST_TOTALS_ALL =
        "SELECT artist_key, COUNT(DISTINCT song_key), COUNT(DISTINCT NULLIF(album_key, '')) " +
            "FROM track_artists GROUP BY artist_key"

    /**
     * What the user keeps about songs: favourites, plays and playlists. A song's key can change when
     * a new version arrives, so each row stores the file it was made from plus the song key and base
     * key of that moment, and is resolved to the song that now holds the file, or failing that to
     * the song with the same base key. A row whose song cannot be found is kept and hidden, so adding
     * its chat back brings it back. Unlike the tables above, these are not a cache of Telegram and
     * cannot be scanned again, so a later schema step must carry them over, never drop them.
     */
    private fun createUserData(db: SQLiteConnection) {
        db.execSQL(
            """
            CREATE TABLE favourites(
                chat_id INTEGER NOT NULL,
                message_id INTEGER NOT NULL,
                song_key TEXT NOT NULL,
                base_key TEXT NOT NULL,
                added_sec INTEGER NOT NULL,
                PRIMARY KEY(chat_id, message_id)
            )
            """,
        )
        db.execSQL("CREATE INDEX favourites_base ON favourites(base_key)")
        db.execSQL("CREATE INDEX favourites_added ON favourites(added_sec)")
        db.execSQL(
            """
            CREATE TABLE plays(
                play_id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id INTEGER NOT NULL,
                message_id INTEGER NOT NULL,
                song_key TEXT NOT NULL,
                base_key TEXT NOT NULL,
                at_sec INTEGER NOT NULL
            )
            """,
        )
        db.execSQL("CREATE INDEX plays_track ON plays(chat_id, message_id)")
        db.execSQL(
            """
            CREATE TABLE playlists(
                playlist_id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_sec INTEGER NOT NULL,
                updated_sec INTEGER NOT NULL
            )
            """,
        )
        db.execSQL("CREATE INDEX playlists_updated ON playlists(updated_sec, playlist_id)")
        // Positions are dense from 0 but not unique in the index, so a move can shift a range with
        // one UPDATE without tripping over a row that has not moved yet.
        db.execSQL(
            """
            CREATE TABLE playlist_items(
                item_id INTEGER PRIMARY KEY AUTOINCREMENT,
                playlist_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                chat_id INTEGER NOT NULL,
                message_id INTEGER NOT NULL,
                song_key TEXT NOT NULL,
                base_key TEXT NOT NULL
            )
            """,
        )
        db.execSQL("CREATE INDEX playlist_items_order ON playlist_items(playlist_id, position)")
        db.execSQL("CREATE INDEX playlist_items_track ON playlist_items(chat_id, message_id)")
        db.execSQL("CREATE INDEX playlist_items_base ON playlist_items(base_key)")
        db.execSQL(
            """
            CREATE TABLE recent_searches(
                folded TEXT PRIMARY KEY,
                query TEXT NOT NULL,
                seq INTEGER NOT NULL
            ) WITHOUT ROWID
            """,
        )
    }
}
