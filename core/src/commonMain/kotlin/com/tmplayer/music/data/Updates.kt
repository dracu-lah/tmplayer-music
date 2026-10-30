// Adapted from TMPlayer (GPL-3.0), core/src/commonMain/kotlin/com/tmplayer/data/Updates.kt: one
// universal APK, a download that resumes and can be cancelled, and a space check before it starts.
package com.tmplayer.music.data

import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Where the update machinery is, for the popup and the Settings rows. */
sealed interface UpdateState {
    /** Nothing has been asked yet, or the last answer was that this is the newest build. */
    data object Idle : UpdateState
    data object Checking : UpdateState

    /**
     * A newer release is out. [skipped] is a version the user said to skip, re-offered because
     * they asked Settings to check.
     */
    data class Available(val release: Release, val skipped: Boolean = false) : UpdateState

    /** Downloading, 0f to 1f. Null while the size is not known. */
    data class Downloading(val release: Release, val fraction: Float?) : UpdateState

    /** Downloaded and handed to the system installer's screen (Android 6 to 11). */
    data class Ready(val release: Release, val file: File) : UpdateState

    /** Downloaded, checked and in Android's installer session (Android 12 and later). */
    data class Installing(val release: Release) : UpdateState

    /** What went wrong, in words, and the release it went wrong for when there was one. */
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/** The release a state is about, whatever stage it is at. */
val UpdateState.release: Release?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Ready -> release
        is UpdateState.Installing -> release
        is UpdateState.Failed -> release
        else -> null
    }

/**
 * Checks for a newer TMPlayer Music and fetches it. Installing is Android's (`SelfInstaller` in
 * :app). [UpdateFeed] is the only source: no account, no analytics, and only when this app asks;
 * when it asks is [UpdateScheduler]'s business.
 *
 * Only the GitHub APK updates itself: a store build sets [enabled] false (BuildConfig.UPDATE_CHECK),
 * and then nothing is fetched or downloaded and the state stays [UpdateState.Idle].
 */
object Updates {

    /** The releases page without the scheme, as the dialogs print it. */
    const val RELEASES_PAGE = "github.com/${UpdateFeed.REPO}/releases"

    @Volatile
    var enabled: Boolean = true

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** This build's own version, as it is written on the releases page. */
    @Volatile
    var installedVersion: String = "0"
        private set

    @Volatile
    private var online: () -> Boolean = { true }

    @Volatile
    private var metered: () -> Boolean = { false }

    @Volatile
    private var userAgent: String = "TMPlayerMusic"

    @Volatile
    private var testVersions: suspend () -> Boolean = { false }

    /** How a byte count is written for the user ("48 MB"). */
    @Volatile
    var formatBytes: (Long) -> String = { "$it B" }
        private set

    /** How the release is fetched, given whether test versions are wanted; tests swap it. */
    @Volatile
    internal var fetch: (testVersions: Boolean) -> Release = { tests -> UpdateFeed.newest(userAgent, tests) }

    /** An open download: the status, the length the server gave (-1 for none) and the body. */
    internal class Opened(val code: Int, val length: Long, val body: InputStream?, val close: () -> Unit = {})

    /** How the APK is opened from [from] bytes on; tests swap it for bytes of their own. */
    @Volatile
    internal var open: (url: String, from: Long) -> Opened = ::httpOpen

    /** The running download, for [cancelDownload]. */
    @Volatile
    private var downloadJob: Job? = null

    /**
     * What this build is. [online] and [metered] are the platform's view of the connection,
     * [testVersions] the "Get test versions" setting, read at each check.
     */
    fun configure(
        installedVersion: String,
        online: () -> Boolean = { true },
        metered: () -> Boolean = { false },
        userAgent: String = "TMPlayerMusic/$installedVersion",
        testVersions: suspend () -> Boolean = { false },
        formatBytes: (Long) -> String = { "$it B" },
    ) {
        this.installedVersion = installedVersion
        this.online = online
        this.metered = metered
        this.userAgent = userAgent
        this.testVersions = testVersions
        this.formatBytes = formatBytes
    }

    /** Whether the connection in use is paid for by the byte, as far as the platform knows. */
    fun onMeteredNetwork(): Boolean = runCatching { metered() }.getOrDefault(false)

    /** The APK this app installs from [release], or null when the release has none. */
    fun apkFor(release: Release): ReleaseAsset? = UpdateFeed.androidAsset(release)

    /**
     * "Get test versions" was just switched. Off takes back a test version on offer; either way the
     * feed is asked again, quietly, so what is offered matches the switch now.
     */
    suspend fun testVersionsSwitched(on: Boolean, skipped: String) {
        if (!enabled) return
        val current = _state.value
        if (!on && current is UpdateState.Available && ReleaseVersion.isTest(current.release.version)) {
            _state.value = UpdateState.Idle
        }
        check(quiet = true, skipped = skipped)
    }

    /**
     * Asks the feed what the newest release is. Returns whether an answer came back, which is what
     * decides whether the check counts towards the throttle.
     *
     * [quiet] is the scheduled check: it says nothing on failure and passes over [skipped]. The
     * Settings button passes false, and a skipped release is offered again with a note.
     */
    suspend fun check(quiet: Boolean = false, skipped: String = ""): Boolean {
        if (!enabled) return false
        val before = _state.value
        // A download or a finished one is further along than anything a check could say.
        if (before.isInHand()) return false

        if (!runCatching { online() }.getOrDefault(true)) {
            if (!quiet) _state.value = UpdateState.Failed(L.updateOffline)
            return false
        }

        if (!quiet) _state.value = UpdateState.Checking
        val tests = try {
            testVersions()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        val attempt = withContext(Dispatchers.IO) { runCatching { fetch(tests) } }
        val release = attempt.getOrNull()
        attempt.exceptionOrNull()?.let { Logger.w(TAG, "Update check failed", it) }

        // Another check or a download may have moved on while this one waited on the network.
        if (_state.value.isInHand()) return release != null

        _state.value = when {
            release == null && quiet -> before.takeIf { it is UpdateState.Available } ?: UpdateState.Idle
            release == null -> UpdateState.Failed(
                (attempt.exceptionOrNull() as? UpdateFailure)?.text ?: L.updateUnreachable,
                before.release,
            )
            !UpdateFeed.isNewer(release.version, installedVersion) || apkFor(release) == null -> UpdateState.Idle
            release.version == skipped && quiet -> UpdateState.Idle
            else -> UpdateState.Available(release, skipped = release.version == skipped)
        }
        return release != null
    }

    /**
     * Free space an update of [apkBytes] needs: the download, Android's copy of it in the install
     * session, and room to spare. An unknown size asks for [UNKNOWN_SIZE_BYTES].
     */
    fun spaceNeeded(apkBytes: Long): Long {
        val apk = if (apkBytes > 0) apkBytes else UNKNOWN_SIZE_BYTES
        return apk * 2 + SPARE_BYTES
    }

    /** "The update needs 120 MB free and this device has 40 MB." */
    fun spaceWords(apkBytes: Long, free: Long): String =
        L.updateNeedsSpace(needed = formatBytes(spaceNeeded(apkBytes)), free = formatBytes(free.coerceAtLeast(0)))

    /** The partly downloaded file for [release] in [dir], kept for a resume. */
    fun partFile(dir: File, release: Release): File = File(dir, "${fileName(release)}.part")

    private fun fileName(release: Release): String =
        "TMPlayerMusic-${release.version.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.take(40)}.apk"

    /**
     * Fetches the APK into [dir], resuming a part left by an earlier try of the same version, and
     * checks its size and hash against what GitHub said. Once it is whole the state moves to
     * [UpdateState.Ready] (or, with [installing], to [UpdateState.Installing]) and the file is
     * returned. Null when it did not arrive, with the state already saying why.
     *
     * Before a byte is fetched, the free space of [dir] ([freeBytes]) has to hold [spaceNeeded].
     * Cancelled ([cancelDownload], or the caller's scope), the state goes back to offering the
     * release and the part stays for the next try. A part of another version is deleted.
     */
    suspend fun download(
        release: Release,
        dir: File,
        installing: Boolean = false,
        freeBytes: (File) -> Long = { runCatching { it.usableSpace }.getOrDefault(0L) },
    ): File? {
        if (!enabled) return null
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return null
        val asset = apkFor(release)
        if (asset == null) {
            _state.value = UpdateState.Failed(L.updateNoApk, release)
            return null
        }
        if (!runCatching { online() }.getOrDefault(true)) {
            _state.value = UpdateState.Failed(L.updateOffline, release)
            return null
        }
        val target = File(dir, fileName(release))
        val part = partFile(dir, release)
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                // One version at a time: anything else here is an older try, worthless now.
                dir.listFiles()?.forEach { if (it.name != part.name) it.delete() }
                val free = freeBytes(dir)
                val needed = spaceNeeded(asset.size) - part.length()
                if (free < needed) throw UpdateFailure(spaceWords(asset.size, free))
            }
        }
        prepared.exceptionOrNull()?.let {
            _state.value = UpdateState.Failed((it as? UpdateFailure)?.text ?: L.updateCouldNotSave, release)
            return null
        }

        downloadJob = currentCoroutineContext()[Job]
        _state.value = UpdateState.Downloading(release, null)
        val attempt = try {
            withContext(Dispatchers.IO) { runCatching { fetchInto(release, asset, part, target) } }
        } catch (e: CancellationException) {
            // The part stays: the next Update now goes on from where this stopped.
            _state.value = UpdateState.Available(release)
            throw e
        } finally {
            downloadJob = null
        }
        attempt.exceptionOrNull()?.let { error ->
            if (error is CancellationException) {
                _state.value = UpdateState.Available(release)
                throw error
            }
            Logger.w(TAG, "Update download failed", error)
            val full = error.message?.let { "ENOSPC" in it || "No space left" in it } == true
            // A full disk or a damaged file leaves nothing behind; a cut connection keeps its part.
            if (full || (error is UpdateFailure && error.text == L.updateDamaged)) part.delete()
            _state.value = UpdateState.Failed(
                when {
                    error is UpdateFailure -> error.text
                    full -> spaceWords(asset.size, freeBytes(dir))
                    else -> L.updateUnreachable
                },
                release,
            )
            return null
        }
        val file = attempt.getOrNull()
        if (file == null || file.length() == 0L) {
            _state.value = UpdateState.Failed(L.updateUnreachable, release)
            return null
        }
        _state.value = if (installing) UpdateState.Installing(release) else UpdateState.Ready(release, file)
        return file
    }

    private suspend fun fetchInto(release: Release, asset: ReleaseAsset, part: File, target: File): File {
        var from = part.length()
        // A part as long as the whole file, or longer, is checked rather than fetched again.
        if (asset.size in 1..from) from = if (from == asset.size) from else 0L.also { part.delete() }
        if (asset.size <= 0 || from < asset.size) {
            var opened = open(asset.url, from)
            if (opened.code == 416 && from > 0) {
                // The server will not go on from there: start again from nothing.
                opened.close()
                part.delete()
                from = 0
                opened = open(asset.url, 0)
            }
            try {
                val body = opened.body
                if (opened.code !in 200..299 || body == null) throw UpdateFailure(L.updateGithubAnswered(opened.code.toString()))
                // A 200 to a range request is the whole file again.
                val append = opened.code == 206 && from > 0
                if (!append) from = 0
                val total = when {
                    asset.size > 0 -> asset.size
                    opened.length > 0 -> from + opened.length
                    else -> -1L
                }
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = from
                    var shown = -1
                    body.use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0 && done > total) throw UpdateFailure(L.updateDamaged)
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != shown) {
                                    shown = percent
                                    _state.value = UpdateState.Downloading(release, done.toFloat() / total)
                                }
                            }
                        }
                    }
                }
            } finally {
                opened.close()
            }
        }
        if (asset.size > 0 && part.length() != asset.size) {
            // Short: the connection ended early. The part stays for a resume.
            if (part.length() < asset.size) throw UpdateFailure(L.updateUnreachable)
            part.delete()
            throw UpdateFailure(L.updateDamaged)
        }
        val expected = asset.sha256
        if (expected != null && !sha256(part).equals(expected, ignoreCase = true)) {
            part.delete()
            throw UpdateFailure(L.updateDamaged)
        }
        target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            throw UpdateFailure(L.updateCouldNotSave)
        }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun httpOpen(url: String, from: Long): Opened {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = UpdateFeed.CONNECT_TIMEOUT_MS
            readTimeout = UpdateFeed.READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }
        val code = connection.responseCode
        // Read from the header rather than contentLengthLong, which Android has only from 7.0.
        val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val body = if (code in 200..299) connection.inputStream else null
        return Opened(code, length, body) { runCatching { connection.disconnect() } }
    }

    /** Stops the running download; the part is kept for the next try. */
    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** For the platform's installer to report that it could not take the file. */
    fun installFailed(message: String) {
        _state.value = UpdateState.Failed(message, _state.value.release)
    }

    /** The downloaded [file] went to the system installer's own screen. */
    fun handedOver(release: Release, file: File) {
        _state.value = UpdateState.Ready(release, file)
    }

    /** Back to offering what was being fetched or installed: the user said no to Android's question. */
    fun backToOffer() {
        _state.value = _state.value.release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
    }

    /** For a platform that refuses the download before it starts. */
    fun refuse(message: String) {
        _state.value = UpdateState.Failed(message, _state.value.release)
    }

    /** Clears a finished outcome once the user has read it; a release on offer stays on offer. */
    fun dismiss() {
        when (val current = _state.value) {
            is UpdateState.Failed -> _state.value = current.release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            is UpdateState.Ready -> _state.value = UpdateState.Available(current.release)
            else -> Unit
        }
    }

    /** "Skip this version": the offer goes, for this version only. */
    fun skip() {
        if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.Installing) _state.value = UpdateState.Idle
    }

    /** A download, an APK handed over or an install under way: further along than a check. */
    private fun UpdateState.isInHand(): Boolean =
        this is UpdateState.Downloading || this is UpdateState.Ready || this is UpdateState.Installing

    const val UNKNOWN_SIZE_BYTES = 64L * 1024 * 1024
    private const val SPARE_BYTES = 32L * 1024 * 1024
    private const val TAG = "Updates"
}
