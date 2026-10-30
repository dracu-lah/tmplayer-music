package com.tmplayer.music.data

import com.tmplayer.music.platform.Logger
import dev.g000sha256.tdl.TdlResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The signed-in user as the top bar's avatar shows them: the name, the first active username (empty
 * when there is none) and the file id of the small profile photo (0 when there is none). The file
 * id belongs to the session that read it ([generation]).
 */
data class AccountProfile(
    val name: String,
    val username: String,
    val photoFileId: Int,
    val generation: Long = 0,
)

/**
 * Who is signed in, read once per TDLib session with `getMe` when the avatar is first drawn. Nothing
 * polls: a new session (after a restart of the client) reads it again, Log out forgets it. Nothing
 * about the user leaves the device; the photo is TDLib's own small copy, downloaded like a chat photo.
 * A failure leaves the profile empty and the avatar shows initials (or a neutral tile).
 */
object Account {
    private const val TAG = "Account"
    private const val TIMEOUT_MS = 15_000L

    private val _profile = MutableStateFlow<AccountProfile?>(null)
    val profile: StateFlow<AccountProfile?> = _profile.asStateFlow()

    private val lock = Mutex()

    /** Set by the debug demo: an invented profile, and TDLib is never asked. */
    @Volatile
    private var fixed = false

    /** Shows [profile] in place of the real account, for the debug demo. */
    fun showFixed(profile: AccountProfile) {
        fixed = true
        _profile.value = profile
    }

    /** Reads the profile if this session has not yet. Safe to call from every composition. */
    suspend fun load() {
        if (fixed) return
        val generation = Td.currentGeneration() ?: return
        if (_profile.value?.generation == generation) return
        lock.withLock {
            if (fixed || _profile.value?.generation == generation) return
            try {
                val session = withTimeoutOrNull(TIMEOUT_MS) { Td.awaitAuthorizedSession() } ?: return
                val me = withTimeoutOrNull(TIMEOUT_MS) { session.client.getMe() }?.valueOrNull ?: return
                if (!session.isCurrent()) return
                _profile.value = AccountProfile(
                    name = AccountNames.display(me.firstName, me.lastName),
                    username = AccountNames.username(me.usernames?.activeUsernames),
                    photoFileId = me.profilePhoto?.small?.id ?: 0,
                    generation = session.generation,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "could not read the signed-in account", e)
            }
        }
    }

    /**
     * The local path of the profile photo, downloaded (small, TDLib caches it) if it is not yet;
     * null when there is none, the session changed or the download did not finish in time.
     */
    suspend fun photoPath(profile: AccountProfile): String? {
        if (fixed || profile.photoFileId == 0) return null
        return try {
            val session = withTimeoutOrNull(TIMEOUT_MS) { Td.awaitAuthorizedSession() } ?: return null
            if (session.generation != profile.generation) return null
            val result = withTimeoutOrNull(TIMEOUT_MS) { session.client.downloadFile(profile.photoFileId, 8, 0, 0, true) }
            val local = (result as? TdlResult.Success)?.result?.local ?: return null
            // Only a finished download: a half-written picture decodes as a strip over a blank square.
            local.path.takeIf { local.isDownloadingCompleted && it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "could not download the profile photo", e)
            null
        }
    }

    /** Forgets the profile (Log out). The next session reads it again. */
    fun clear() {
        if (fixed) return
        _profile.value = null
    }
}

/** How the avatar names the user: pure rules, tested on the JVM. */
object AccountNames {
    /** "First Last", either part alone, or empty when Telegram gave neither. */
    fun display(first: String?, last: String?): String =
        listOfNotNull(first?.trim(), last?.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    /** The first active username, without an at sign; empty when there is none. */
    fun username(active: Array<String>?): String =
        active?.firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("@").orEmpty()

    /**
     * Up to two letters for the avatar tile, by code point so an emoji or a non-Latin name is not cut
     * in half. Words that start with something other than a letter or digit are skipped; empty when
     * none is left (the tile then shows a person glyph instead).
     */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val letters = words.mapNotNull { word ->
            val cp = word.codePointAt(0)
            if (Character.isLetterOrDigit(cp)) String(Character.toChars(cp)).uppercase() else null
        }
        return when {
            letters.isEmpty() -> ""
            letters.size == 1 -> letters[0]
            else -> letters.first() + letters.last()
        }
    }
}
