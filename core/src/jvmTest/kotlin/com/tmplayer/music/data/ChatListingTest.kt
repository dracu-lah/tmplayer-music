package com.tmplayer.music.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The picker's chat list: the archive listed after the main list, folders, TDLib's own copy shown
 * first, pages loaded within a budget, flood waits, failures, and chats moving between the lists.
 */
class ChatListingTest {

    /**
     * An account as TDLib holds it: [main] and [archive] are ids in order; [localMain] and [localArchive]
     * are how many of each TDLib has on its disk before anything is loaded. Each load brings [page] more.
     */
    private class FakeAccount(
        val main: List<Long>,
        val archive: List<Long> = emptyList(),
        var localMain: Int = 0,
        var localArchive: Int = 0,
        val page: Int = 100,
        val folders: Map<Int, List<Long>> = emptyMap(),
        val secret: Set<Long> = emptySet(),
        val saved: Long? = null,
    ) : ChatListCalls {
        val loads = ArrayList<ChatListRef>()
        val chatReads = HashMap<Long, Int>()
        val scripted = ArrayDeque<ChatLoad>()
        var failArchiveIds = false
        override val moves = MutableSharedFlow<ChatMove>(extraBufferCapacity = 8)

        override suspend fun load(list: ChatListRef, limit: Int): ChatLoad {
            loads += list
            scripted.removeFirstOrNull()?.let { return it }
            return when (list) {
                ChatListRef.Main -> if (localMain >= main.size) ChatLoad.AllLoaded else {
                    localMain = minOf(main.size, localMain + minOf(limit, page))
                    ChatLoad.Loaded
                }
                ChatListRef.Archive -> if (localArchive >= archive.size) ChatLoad.AllLoaded else {
                    localArchive = minOf(archive.size, localArchive + minOf(limit, page))
                    ChatLoad.Loaded
                }
                is ChatListRef.Folder -> ChatLoad.Failed("folders are not loaded here")
            }
        }

        override suspend fun ids(list: ChatListRef, limit: Int): List<Long>? = when (list) {
            ChatListRef.Main -> main.take(localMain).take(limit)
            ChatListRef.Archive -> if (failArchiveIds) null else archive.take(localArchive).take(limit)
            is ChatListRef.Folder -> folders[list.id]?.filter { it in main.take(localMain) || it in archive.take(localArchive) }
        }

        override suspend fun chat(chatId: Long): ChatFacts? {
            chatReads[chatId] = (chatReads[chatId] ?: 0) + 1
            if (chatId != saved && chatId !in main && chatId !in archive) return null
            return ChatFacts(chatId, "Chat $chatId", photoFileId = 0, miniThumbnail = null, archived = chatId in archive, secret = chatId in secret)
        }

        override suspend fun savedMessagesId(): Long? = saved
    }

    private fun directory(account: FakeAccount, folders: List<ChatFolder> = emptyList(), waits: MutableList<Int> = ArrayList()) =
        ChatDirectory(
            client = { error("not used") },
            folders = { folders },
            callsOf = { account },
            wait = { waits += it },
        )

    @Test
    fun theArchiveIsListedAfterTheMainList() = runTest {
        val account = FakeAccount(main = listOf(1, 2, 3), archive = listOf(10, 11), saved = 99)
        val done = directory(account).listing().last()
        assertEquals(listOf(99L, 1, 2, 3, 10, 11), done.chats.map { it.chatId })
        assertEquals(listOf(10L, 11), done.archived.map { it.chatId })
        assertTrue(done.chats.first().savedMessages)
        assertFalse(done.chats.first().archived)
        assertFalse(done.loading)
        assertNull(done.failure)
        assertTrue(ChatListRef.Archive in account.loads)
    }

    @Test
    fun anEmptyArchiveListsTheMainListAlone() = runTest {
        val account = FakeAccount(main = listOf(1, 2))
        val done = directory(account).listing().last()
        assertEquals(listOf(1L, 2), done.chats.map { it.chatId })
        assertTrue(done.archived.isEmpty())
    }

    @Test
    fun tdlibsOwnCopyShowsFirstBeforeAnythingIsLoaded() = runTest {
        val account = FakeAccount(main = (1L..500L).toList(), archive = listOf(900), localMain = 120, localArchive = 1)
        val first = directory(account).listing().first()
        assertEquals(121, first.chats.size)
        assertTrue(first.loading)
        assertTrue(account.loads.isEmpty())
    }

    @Test
    fun aLargeAccountIsLoadedInPagesWithEachChatReadOnce() = runTest {
        val main = (1L..3_000L).toList()
        val archive = (10_001L..10_400L).toList()
        val account = FakeAccount(main = main, archive = archive)
        val steps = directory(account).listing().toList()
        val done = steps.last()
        assertEquals(3_400, done.chats.size)
        assertEquals(main + archive, done.chats.map { it.chatId })
        // 30 pages of the main list and 4 of the archive (each list then answers 404), each shown as it came.
        assertEquals(31, account.loads.count { it == ChatListRef.Main })
        assertEquals(5, account.loads.count { it == ChatListRef.Archive })
        assertEquals(1 + 34 + 1, steps.size)
        assertTrue(account.chatReads.values.all { it == 1 })
    }

    @Test
    fun theLoadStopsAtItsBudgetAndTheArchiveStillShows() = runTest {
        val account = FakeAccount(main = (1L..20_000L).toList(), archive = listOf(50_000))
        val done = directory(account).listing().last()
        assertEquals(ChatDirectory.MAX_CHATS, done.chats.count { !it.archived })
        assertEquals(listOf(50_000L), done.archived.map { it.chatId })
        assertTrue(account.loads.size <= ChatDirectory.MAX_PAGES + 2)
        assertFalse(done.loading)
    }

    @Test
    fun aShortFloodWaitIsWaitedOutAndTheLoadGoesOn() = runTest {
        val account = FakeAccount(main = (1L..150L).toList(), archive = listOf(900))
        account.scripted += ChatLoad.FloodWait(5)
        val waits = ArrayList<Int>()
        val done = directory(account, waits = waits).listing().last()
        assertEquals(listOf(5), waits)
        assertEquals(151, done.chats.size)
        assertNull(done.failure)
    }

    @Test
    fun aLongFloodWaitStopsTheLoadAndSaysSoKeepingWhatIsShown() = runTest {
        val account = FakeAccount(main = (1L..300L).toList(), archive = listOf(900), localMain = 100)
        account.scripted += ChatLoad.FloodWait(3_600)
        val waits = ArrayList<Int>()
        val done = directory(account, waits = waits).listing().last()
        assertTrue(waits.isEmpty())
        assertNotNull(done.failure)
        // The main list stopped where it was; the archive, a separate list, was still loaded.
        assertEquals(100, done.chats.count { !it.archived })
        assertEquals(listOf(900L), done.archived.map { it.chatId })
    }

    @Test
    fun aFailingArchiveDoesNotTakeTheMainListDown() = runTest {
        val account = FakeAccount(main = listOf(1, 2), archive = listOf(10)).apply { failArchiveIds = true }
        val done = directory(account).listing().last()
        assertEquals(listOf(1L, 2), done.chats.map { it.chatId })
    }

    @Test
    fun secretChatsAreLeftOut() = runTest {
        val account = FakeAccount(main = listOf(1, 2, 3), secret = setOf(2))
        assertEquals(listOf(1L, 3), directory(account).listing().last().chats.map { it.chatId })
    }

    @Test
    fun foldersListTheirChatsAndEmptyFoldersAreLeftOut() = runTest {
        val account = FakeAccount(
            main = listOf(1, 2, 3),
            archive = listOf(10),
            folders = mapOf(7 to listOf(2, 10), 8 to emptyList()),
        )
        val done = directory(account, folders = listOf(ChatFolder(7, "Music"), ChatFolder(8, "Work"))).listing().last()
        assertEquals(listOf(ChatFolder(7, "Music")), done.folders)
        assertEquals(listOf(2L, 10), done.inFolder(7).map { it.chatId })
        assertTrue(done.inFolder(8).isEmpty())
        assertTrue(done.inFolder(99).isEmpty())
    }

    @Test
    fun aChatArchivedWhileOpenMovesToTheArchive() {
        val listing = ChatListing(
            chats = listOf(entry(99, saved = true), entry(1), entry(2), entry(10, archived = true)),
            loading = false,
        )
        val moved = listing.moved(entry(2, archived = true))
        assertEquals(listOf(99L, 1, 2, 10), moved.chats.map { it.chatId })
        assertEquals(listOf(2L, 10), moved.archived.map { it.chatId })
        // And back: first after Saved Messages.
        val back = moved.moved(entry(10, archived = false))
        assertEquals(listOf(99L, 10, 1, 2), back.chats.map { it.chatId })
        assertEquals(listOf(2L), back.archived.map { it.chatId })
        // The same move twice changes nothing.
        assertEquals(back, back.moved(entry(10, archived = false)))
        // A chat not listed yet is added to its list.
        assertEquals(listOf(99L, 10, 1, 5, 2), back.moved(entry(5, archived = true)).chats.map { it.chatId })
    }

    @Test
    fun movesFromTelegramBecomeEntries() = runTest {
        val account = FakeAccount(main = listOf(1, 2), archive = listOf(10), saved = 99)
        val dir = directory(account)
        val got = ArrayList<ChatEntry>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            dir.moves().collect { got += it }
        }
        account.moves.emit(ChatMove(2, archived = true))
        account.moves.emit(ChatMove(77, archived = true)) // a chat TDLib cannot answer for is skipped
        account.moves.emit(ChatMove(99, archived = true)) // Saved Messages never shows as archived
        testScheduler.runCurrent()
        job.cancel()
        assertEquals(listOf(2L to true, 99L to false), got.map { it.chatId to it.archived })
    }

    private fun entry(id: Long, archived: Boolean = false, saved: Boolean = false) =
        ChatEntry(chatId = id, title = "Chat $id", savedMessages = saved, archived = archived, photoFileId = 0)
}
