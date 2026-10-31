package com.tmplayer.music.data

import com.tmplayer.music.platform.Logger
import dev.g000sha256.tdl.TdlClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withTimeoutOrNull

/** One row of the chat picker. */
data class ChatEntry(
    val chatId: Long,
    val title: String,
    val savedMessages: Boolean,
    val archived: Boolean,
    /** The small avatar's file id, 0 when the chat has none. */
    val photoFileId: Int,
    /** Telegram's inline preview of the avatar, shown while the photo downloads; null when none. */
    val miniThumbnail: ByteArray? = null,
)

/**
 * What the picker shows: [chats] are Saved Messages first, then the main list in Telegram's order,
 * then the archive in its order; [members] are the chat ids of each folder in [folders]. [loading]
 * while the list is still being loaded from Telegram; [failure] when that stopped early (what is
 * already here stays shown).
 */
data class ChatListing(
    val chats: List<ChatEntry> = emptyList(),
    val folders: List<ChatFolder> = emptyList(),
    val members: Map<Int, Set<Long>> = emptyMap(),
    val loading: Boolean = true,
    val failure: String? = null,
) {
    val archived: List<ChatEntry> get() = chats.filter { it.archived }

    /** The chats of folder [folderId], in the list's order. */
    fun inFolder(folderId: Int): List<ChatEntry> {
        val ids = members[folderId] ?: return emptyList()
        return chats.filter { it.chatId in ids }
    }

    /**
     * The listing with [entry] moved to the list it is in now: first among the archive's chats when it
     * was archived, first after Saved Messages when it came back (Telegram puts a chat moved by hand on
     * top). A chat not listed yet is added the same way.
     */
    fun moved(entry: ChatEntry): ChatListing {
        val current = chats.firstOrNull { it.chatId == entry.chatId }
        if (current != null && current.archived == entry.archived) return this
        val rest = chats.filter { it.chatId != entry.chatId }
        val saved = rest.filter { it.savedMessages }
        val main = rest.filter { !it.savedMessages && !it.archived }
        val archive = rest.filter { !it.savedMessages && it.archived }
        val next = when {
            entry.savedMessages -> listOf(entry) + main + archive
            entry.archived -> saved + main + listOf(entry) + archive
            else -> saved + listOf(entry) + main + archive
        }
        return copy(chats = next)
    }
}

/**
 * The user's own chats, for the picker: Saved Messages first, then the main list in Telegram's
 * order, then the archive, with the user's folders. Only chats the user is in: there is no public
 * search here, by design.
 *
 * Large accounts are the case to design for (3,000 chats with folders and an archive). [listing]
 * shows TDLib's own copy of the lists first (on the disk after the first run, so it answers offline
 * and at once), then loads both lists from Telegram a page at a time within a budget ([MAX_CHATS],
 * [MAX_PAGES], a time per page; the archive has its own share), showing each page as it comes. A flood wait is waited out when
 * short, else the load stops there and says so; a failure stops it the same way, keeping what is
 * shown. Chats are read from TDLib in chunks, each only once. The folders are read last, from what is
 * loaded (loading a folder would read the whole account again). Each chat's audio count is asked for
 * only when its row is on screen ([count]), a few at a time, and remembered.
 */
class ChatDirectory(
    private val client: suspend () -> TdlClient,
    private val folders: () -> List<ChatFolder> = { emptyList() },
    private val callsOf: suspend () -> ChatListCalls = { TdChatListCalls(client()) },
    private val wait: suspend (seconds: Int) -> Unit = { delay(it * 1000L) },
    generation: () -> Long? = { 0L },
    private val counter: AudioCounter = AudioCounter(calls = { TdCountCalls(client()) }, generation = generation),
) {


    /**
     * The list as it fills: TDLib's copy first, then after each page loaded from Telegram, then with
     * the folders. Ends after the last; never throws for Telegram's answers.
     */
    fun listing(): Flow<ChatListing> = flow {
        val calls = callsOf()
        val known = HashMap<Long, ChatEntry?>()
        val saved = calls.savedMessagesId()

        emit(ChatListing(chats = entries(calls, saved, known), loading = true))
        var failure: String? = null
        var pages = 0
        for (list in listOf(ChatListRef.Main, ChatListRef.Archive)) {
            var waits = 0
            var listPages = 0
            // A main list that failed still leaves the archive to try: it is a separate list.
            while (pages < MAX_PAGES && listPages * LOAD_PAGE < MAX_CHATS) {
                val answer = withTimeoutOrNull(LOAD_TIMEOUT_MS) { calls.load(list, LOAD_PAGE) }
                    ?: ChatLoad.Failed("timeout")
                when (answer) {
                    ChatLoad.Loaded -> {
                        pages++
                        listPages++
                        emit(ChatListing(chats = entries(calls, saved, known), loading = true))
                    }
                    ChatLoad.AllLoaded -> break
                    is ChatLoad.FloodWait -> {
                        if (answer.seconds > MAX_WAIT_S || ++waits > MAX_WAITS) {
                            failure = "FLOOD_WAIT_${answer.seconds}"
                            break
                        }
                        // Waited out by this load alone; the rows shown stay usable meanwhile.
                        wait(answer.seconds)
                    }
                    is ChatLoad.Failed -> {
                        Logger.w(TAG, "loadChats($list) failed: ${answer.message}")
                        failure = answer.message
                        break
                    }
                }
            }
        }
        val chats = entries(calls, saved, known)
        val folderList = folders()
        val members = HashMap<Int, Set<Long>>()
        for (folder in folderList) {
            members[folder.id] = calls.ids(ChatListRef.Folder(folder.id), 2 * MAX_CHATS).orEmpty().toHashSet()
        }
        emit(
            ChatListing(
                chats = chats,
                folders = folderList.filter { members[it.id].orEmpty().isNotEmpty() },
                members = members,
                loading = false,
                failure = failure?.let { Failures.humanise(it) },
            ),
        )
    }

    /** Chats moving between the main list and the archive, as entries to give [ChatListing.moved]. */
    fun moves(): Flow<ChatEntry> = flow {
        val calls = callsOf()
        val saved = calls.savedMessagesId()
        calls.moves.collect { move ->
            val facts = calls.chat(move.chatId) ?: return@collect
            if (facts.secret) return@collect
            emit(entryOf(facts, saved).copy(archived = move.archived && move.chatId != saved))
        }
    }

    /** Every listable chat at once, Saved Messages first: what [listing] ends with. */
    suspend fun chats(): List<ChatEntry> = listing().last().chats

    /** The two lists' ids as TDLib holds them now, made into entries (each chat read once, in chunks). */
    private suspend fun entries(calls: ChatListCalls, saved: Long?, known: HashMap<Long, ChatEntry?>): List<ChatEntry> {
        val main = calls.ids(ChatListRef.Main, MAX_CHATS).orEmpty()
        // A failure here is not allowed to take the main list down with it.
        val archive = calls.ids(ChatListRef.Archive, MAX_CHATS).orEmpty()
        val archivedIds = archive.toHashSet()
        val mainIds = main.toHashSet()
        val ids = (listOfNotNull(saved) + main + archive).distinct()
        val fresh = ids.filter { it !in known }
        coroutineScope {
            for (chunk in fresh.chunked(CHUNK)) {
                chunk.map { id -> async { id to calls.chat(id) } }.awaitAll().forEach { (id, facts) ->
                    // A chat TDLib could not answer for is asked again next time.
                    if (facts != null) known[id] = if (facts.secret) null else entryOf(facts, saved)
                }
            }
        }
        val out = ArrayList<ChatEntry>(ids.size)
        for (id in ids) {
            val entry = known[id] ?: continue
            // The list it is in now wins over the chat's positions read a moment ago.
            val archived = id != saved && (id in archivedIds || (entry.archived && id !in mainIds))
            out += if (entry.archived == archived) entry else entry.copy(archived = archived)
        }
        return out
    }

    private fun entryOf(facts: ChatFacts, saved: Long?) = ChatEntry(
        chatId = facts.chatId,
        title = facts.title,
        savedMessages = facts.chatId == saved,
        archived = facts.archived && facts.chatId != saved,
        photoFileId = facts.photoFileId,
        miniThumbnail = facts.miniThumbnail,
    )

    /** The count already found for [chatId] in this session, without asking Telegram. */
    fun knownCount(chatId: Long): ChatCount.Known? = counter.cached(chatId)

    /** How much audio [chatId] holds ([AudioCounter]): [ChatCount.Unknown] when Telegram would not say, never 0. */
    suspend fun count(chatId: Long): ChatCount = counter.count(chatId)

    companion object {
        const val TAG = "ChatDirectory"

        /**
         * Chats listed at most from each list (the main list, the archive): the 3,000 chat account with
         * room to spare, and a main list past it never crowds the archive out.
         */
        const val MAX_CHATS = 4000

        /** `loadChats` pages: Telegram's own cap. */
        const val LOAD_PAGE = 100

        /** Pages loaded at most in one opening of the picker, both lists together. */
        const val MAX_PAGES = 2 * MAX_CHATS / LOAD_PAGE
        const val LOAD_TIMEOUT_MS = 15_000L

        /** Chats read from TDLib at once. */
        const val CHUNK = 32

        /** Flood waits a list's load waits out before it stops and says so. */
        const val MAX_WAITS = 3
        const val MAX_WAIT_S = 60
    }
}
