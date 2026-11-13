package com.tmplayer.music.library

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json

/**
 * The backup file: one JSON document holding what the user keeps (favourites, playlists in order,
 * play counts with the last time played, the chosen chats). Kept offline copies and the cache are
 * not in it, nor is any song, file or cover: they come back from Telegram.
 *
 * Every entry points at a song the way the index does (the file's chat and message plus the song key),
 * so a restore on a library that does not hold a song yet keeps the entry, hidden, and it attaches when
 * the chat is indexed. A file is read whole and checked before anything is applied: one wrong entry
 * makes the whole file bad, so a restore either does what the preview said or nothing.
 */
object Backup {

    /** Marks the file as ours. */
    const val FORMAT = "tmplayer-music-backup"

    /** What this build writes and the newest it reads. A file with a higher number is refused, never half read. */
    const val VERSION = 1

    /** The largest file that is read (bytes); the caller checks the size before decoding. */
    const val MAX_FILE_BYTES = 24 * 1024 * 1024

    /** Chats one backup may list. */
    const val MAX_CHATS = 5_000

    /** Entries in all playlists together; with the caps below this is what a very large account holds. */
    const val MAX_TOTAL_ITEMS = 120_000

    internal const val MAX_KEY = 4_096
    private const val MAX_TEXT = 400
    private const val MAX_DEPTH = 24
    private const val MAX_SEC = 4_000_000_000L

    /** One song as the user pointed at it: the file it was made from, the song's key then, and its name for the preview. */
    data class Ref(
        val chatId: Long,
        val messageId: Long,
        val songKey: String,
        val title: String = "",
        val artist: String = "",
    )

    data class Favourite(val ref: Ref, val addedSec: Long)

    data class PlaylistData(val name: String, val createdSec: Long, val updatedSec: Long, val items: List<Ref>)

    /** How often a song was played and when last. */
    data class PlayData(val ref: Ref, val count: Int, val lastSec: Long)

    data class ChatData(val chatId: Long, val title: String)

    /** The whole content of a backup. */
    data class Data(
        val createdSec: Long,
        val favourites: List<Favourite> = emptyList(),
        val playlists: List<PlaylistData> = emptyList(),
        val plays: List<PlayData> = emptyList(),
        val chats: List<ChatData> = emptyList(),
    ) {
        val isEmpty: Boolean get() = favourites.isEmpty() && playlists.isEmpty() && plays.isEmpty() && chats.isEmpty()
    }

    /**
     * What a restore did (or, for a preview, would do). [pending] counts entries whose song is not in the
     * library yet, kept by key until their chat is indexed; [dropped] counts entries left out because a
     * list was full; the [removed] counts are what Replace clears first.
     */
    data class Summary(
        val favouritesAdded: Int,
        val favouritesKnown: Int,
        val playlistsCreated: Int,
        val playlistsMerged: Int,
        val playlistItemsAdded: Int,
        val playedSongs: Int,
        val pending: Int,
        val dropped: Int,
        val newChats: List<ChatData>,
        val removedFavourites: Int,
        val removedPlaylists: Int,
        val removedPlays: Int,
    ) {
        /** True when applying it changes nothing in the library (chats aside). */
        val changesNothing: Boolean
            get() = favouritesAdded == 0 && playlistsCreated == 0 && playlistItemsAdded == 0 && playedSongs == 0 &&
                removedFavourites == 0 && removedPlaylists == 0 && removedPlays == 0
    }

    /** Why a file was refused, for the message and the logs. */
    enum class Problem {
        /** Not JSON at all, or cut short. */
        NotJson,

        /** JSON, but not one of ours. */
        NotBackup,

        /** One of ours, but an entry has the wrong shape or a value out of range. */
        Damaged,

        /** Far more entries than a library can hold. */
        TooLarge,
    }

    sealed interface Result {
        data class Ok(val data: Data) : Result

        /** Written by a newer app: [version] is its number. */
        data class Newer(val version: Long) : Result

        data class Bad(val problem: Problem, val detail: String = "") : Result
    }

    private class Invalid(val problem: Problem, val detail: String) : Exception(detail, null, false, false)

    private fun bad(detail: String): Nothing = throw Invalid(Problem.Damaged, detail)

    // Writing

    fun encode(data: Data): String {
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("createdSec", data.createdSec)
            put("favourites", buildJsonArray {
                for (f in data.favourites) add(buildJsonObject { putRef(f.ref); put("addedSec", f.addedSec) })
            })
            put("playlists", buildJsonArray {
                for (p in data.playlists) {
                    add(buildJsonObject {
                        put("name", p.name)
                        put("createdSec", p.createdSec)
                        put("updatedSec", p.updatedSec)
                        put("items", buildJsonArray { for (i in p.items) add(buildJsonObject { putRef(i) }) })
                    })
                }
            })
            put("plays", buildJsonArray {
                for (p in data.plays) add(buildJsonObject { putRef(p.ref); put("count", p.count); put("lastSec", p.lastSec) })
            })
            put("chats", buildJsonArray {
                for (c in data.chats) add(buildJsonObject { put("id", c.chatId); put("title", c.title) })
            })
        }
        return root.toString()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putRef(ref: Ref) {
        put("chat", ref.chatId)
        put("message", ref.messageId)
        put("key", ref.songKey)
        if (ref.title.isNotBlank()) put("title", TextFold.clip(ref.title, MAX_TEXT))
        if (ref.artist.isNotBlank()) put("artist", TextFold.clip(ref.artist, MAX_TEXT))
    }

    // Reading

    /**
     * Reads and checks a backup. Never throws and never returns part of a file: [Result.Ok] means every
     * entry is valid and within the caps.
     */
    fun decode(text: String?): Result {
        if (text.isNullOrBlank()) return Result.Bad(Problem.NotJson, "empty")
        if (text.length > MAX_FILE_BYTES) return Result.Bad(Problem.TooLarge, "file")
        if (!nestingOk(text)) return Result.Bad(Problem.NotJson, "nesting")
        val root = try {
            Json.parseToJsonElement(text.removePrefix("﻿"))
        } catch (e: Exception) {
            return Result.Bad(Problem.NotJson, e.javaClass.simpleName)
        } catch (e: StackOverflowError) {
            return Result.Bad(Problem.NotJson, "nesting")
        }
        return try {
            read(root)
        } catch (e: Invalid) {
            Result.Bad(e.problem, e.detail)
        } catch (e: Exception) {
            Result.Bad(Problem.Damaged, e.javaClass.simpleName)
        }
    }

    /** Whether brackets nest no deeper than [MAX_DEPTH] (outside strings), so the parser's recursion stays small. */
    private fun nestingOk(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> if (++depth > MAX_DEPTH) return false
                ']', '}' -> depth--
            }
        }
        return true
    }

    private fun read(root: JsonElement): Result {
        val obj = root as? JsonObject ?: return Result.Bad(Problem.NotBackup, "not an object")
        if (string(obj["format"]) != FORMAT) return Result.Bad(Problem.NotBackup, "format")
        val version = long(obj["version"]) ?: return Result.Bad(Problem.Damaged, "version")
        if (version > VERSION) return Result.Newer(version)
        if (version < 1) return Result.Bad(Problem.Damaged, "version")
        val created = (obj["createdSec"]?.let { long(it) ?: bad("createdSec") } ?: 0L).also { if (it !in 0..MAX_SEC) bad("createdSec") }

        val favourites = array(obj["favourites"], LibraryIndex.MAX_FAVOURITES, "favourites").mapIndexed { i, e ->
            val o = entry(e, "favourites[$i]")
            Favourite(ref(o, "favourites[$i]"), seconds(o["addedSec"], "favourites[$i].addedSec"))
        }
        var total = 0
        val playlists = array(obj["playlists"], LibraryIndex.MAX_PLAYLISTS, "playlists").mapIndexed { i, e ->
            val o = entry(e, "playlists[$i]")
            val items = array(o["items"], LibraryIndex.MAX_PLAYLIST_ITEMS, "playlists[$i].items").mapIndexed { j, item ->
                ref(entry(item, "playlists[$i].items[$j]"), "playlists[$i].items[$j]")
            }
            total += items.size
            if (total > MAX_TOTAL_ITEMS) throw Invalid(Problem.TooLarge, "items")
            PlaylistData(
                name = text(o["name"], "playlists[$i].name", MAX_TEXT),
                createdSec = seconds(o["createdSec"], "playlists[$i].createdSec"),
                updatedSec = seconds(o["updatedSec"], "playlists[$i].updatedSec"),
                items = items,
            )
        }
        var played = 0L
        val plays = array(obj["plays"], LibraryIndex.MAX_PLAYS, "plays").mapIndexed { i, e ->
            val o = entry(e, "plays[$i]")
            val count = long(o["count"]) ?: bad("plays[$i].count")
            if (count !in 1..LibraryIndex.MAX_PLAYS) bad("plays[$i].count")
            played += count
            if (played > LibraryIndex.MAX_PLAYS) throw Invalid(Problem.TooLarge, "plays")
            PlayData(ref(o, "plays[$i]"), count.toInt(), seconds(o["lastSec"], "plays[$i].lastSec"))
        }
        val chats = array(obj["chats"], MAX_CHATS, "chats").mapIndexed { i, e ->
            val o = entry(e, "chats[$i]")
            val id = long(o["id"]) ?: bad("chats[$i].id")
            if (id == 0L) bad("chats[$i].id")
            ChatData(id, text(o["title"], "chats[$i].title", MAX_TEXT, required = false))
        }
        return Result.Ok(Data(created, favourites, playlists, plays, chats))
    }

    private fun entry(element: JsonElement, where: String): JsonObject = element as? JsonObject ?: bad(where)

    /** A missing section is empty; one of the wrong type, or over [max] entries, refuses the file. */
    private fun array(element: JsonElement?, max: Int, where: String): List<JsonElement> {
        if (element == null) return emptyList()
        val array = element as? JsonArray ?: bad(where)
        if (array.size > max) throw Invalid(Problem.TooLarge, where)
        return array
    }

    private fun ref(o: JsonObject, where: String): Ref {
        val chat = long(o["chat"]) ?: bad("$where.chat")
        val message = long(o["message"]) ?: bad("$where.message")
        if (chat == 0L || message <= 0) bad("$where.id")
        val key = string(o["key"]) ?: bad("$where.key")
        if (key.isEmpty() || key.length > MAX_KEY) bad("$where.key")
        return Ref(
            chat, message, key,
            title = text(o["title"], "$where.title", MAX_TEXT, required = false),
            artist = text(o["artist"], "$where.artist", MAX_TEXT, required = false),
        )
    }

    private fun seconds(element: JsonElement?, where: String): Long {
        val value = long(element) ?: bad(where)
        if (value !in 0..MAX_SEC) bad(where)
        return value
    }

    private fun text(element: JsonElement?, where: String, max: Int, required: Boolean = true): String {
        if (element == null || element is JsonNull) return if (required) bad(where) else ""
        val value = string(element) ?: bad(where)
        return TextFold.clip(value, max)
    }

    private fun string(element: JsonElement?): String? {
        val p = element as? JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }

    private fun long(element: JsonElement?): Long? {
        val p = element as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return p.content.toLongOrNull()
    }
}
