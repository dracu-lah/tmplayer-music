package com.tmplayer.music.data

/**
 * The one tap first run (`plan/MVP-REVISION.md` decision h): "Start with Saved Messages" picks Saved
 * Messages and the few chats with the most audio, so the first song plays within seconds and more
 * chats can be added later. Plain logic over the counts the picker already has, so it is tested
 * without Telegram.
 */
object FirstRun {
    /** Chats the one tap picks at most, Saved Messages included. */
    const val MAX_CHATS = 5

    /** How many chats from the top of the list get their audio counted when the picker opens. */
    const val COUNT_AHEAD = 30

    /**
     * Saved Messages first when it has audio, then the other chats with the most audio messages (ties
     * keep Telegram's order), at most [max] in all. A chat whose count is unknown or zero is left out,
     * so nothing without audio is picked; archived chats are not picked by the one tap.
     */
    fun pick(chats: List<ChatEntry>, counts: Map<Long, Int>, max: Int = MAX_CHATS): List<ChatEntry> {
        if (max <= 0) return emptyList()
        val withAudio = chats.filter { (counts[it.chatId] ?: 0) > 0 }
        val saved = withAudio.firstOrNull { it.savedMessages }
        val others = withAudio
            .filter { !it.savedMessages && !it.archived }
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<ChatEntry>> { counts[it.value.chatId] ?: 0 }.thenBy { it.index })
            .map { it.value }
        return (listOfNotNull(saved) + others).distinctBy { it.chatId }.take(max)
    }

    /** The chats whose counts the picker asks for up front: Saved Messages and the top of the main list. */
    fun toCount(chats: List<ChatEntry>, ahead: Int = COUNT_AHEAD): List<ChatEntry> =
        (chats.filter { it.savedMessages } + chats.filter { !it.archived && !it.savedMessages }.take(ahead)).distinctBy { it.chatId }
}
