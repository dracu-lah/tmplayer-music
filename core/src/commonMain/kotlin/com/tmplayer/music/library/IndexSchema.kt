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
    const val VERSION = 1

    /**
     * Steps from one version to the next, keyed by the version they start from. The index is a
     * cache of what Telegram holds, so a step may also drop a table and let the scanner refill it.
     */
    val MIGRATIONS: Map<Int, (SQLiteConnection) -> Unit> = mapOf(
        0 to ::createV1,
    )

    private fun createV1(db: SQLiteConnection) {
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
                added_neg INTEGER NOT NULL
            ) WITHOUT ROWID
            """,
        )
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
                title_sort TEXT NOT NULL
            ) WITHOUT ROWID
            """,
        )
        db.execSQL("CREATE INDEX albums_title ON albums(title_sort, album_key)")
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
    }
}
