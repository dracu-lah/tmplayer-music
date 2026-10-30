// Adapted from TMPlayer (GPL-3.0), core/src/commonMain/kotlin/com/tmplayer/data/UpdateFeed.kt:
// GitHub's API is the only source (no site feed yet), the JSON is read with kotlinx.serialization,
// and a release carries one APK, the universal one.
package com.tmplayer.music.data

import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URI

/** A failure the user can be told about in words, rather than as "something went wrong". */
internal class UpdateFailure(val text: String) : java.io.IOException(text)

/** One downloadable file of a release. [sha256] is null when the source did not say. */
data class ReleaseAsset(
    val url: String,
    val sha256: String? = null,
    val size: Long = 0,
) {
    val name: String get() = url.substringAfterLast('/')
}

/**
 * The newest TMPlayer Music release, as GitHub describes it.
 *
 * [assets] is keyed by [UpdateFeed.keyFor]: "android-universal" for the one APK a release
 * carries (`TMPlayerMusic-<version>.apk`). Anything else on the release page is left out.
 */
data class Release(
    val version: String,
    val pageUrl: String,
    /** The leading "- " list of the release page, one change per item. */
    val changes: List<String> = emptyList(),
    val assets: Map<String, ReleaseAsset> = emptyMap(),
)

/**
 * Where a new version is learned of, and how two versions are put in order.
 *
 * GitHub's API allows an anonymous caller sixty requests an hour per address, which is why the
 * check is throttled ([UpdateScheduler]). `releases/latest` passes over pre-releases by
 * definition; with "Get test versions" on, the release list is read too, pre-releases included
 * ([newest]).
 */
object UpdateFeed {

    const val REPO = "dracu-lah/tmplayer-music"
    const val GITHUB_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /** The newest releases, pre-releases included (and drafts, which only the owner is shown). */
    const val GITHUB_RELEASES = "https://api.github.com/repos/$REPO/releases?per_page=10"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    /** The key of the one APK a release carries. */
    const val ANDROID_UNIVERSAL = "android-universal"

    /** An answer bigger than this is not a release list, and is not read. */
    internal const val MAX_ANSWER_CHARS = 4 * 1024 * 1024

    /** What an HTTP GET answered: the status and, for a 2xx, the body. */
    data class Answer(val code: Int, val body: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** The newest full release. Throws [UpdateFailure] with a sentence for the user. */
    fun latest(userAgent: String, get: (url: String, headers: Map<String, String>) -> Answer = ::httpGet): Release =
        parseGitHub(askGitHub(GITHUB_LATEST, mapOf("User-Agent" to userAgent), get)) ?: throw UpdateFailure(L.updateNoVersion)

    /**
     * The release to offer: [latest], and with [testVersions] on also the newest of the release
     * list, whichever of the two is newer ([choose]). A list that cannot be read costs only the test
     * versions, and only when neither answered is the stable source's failure thrown.
     */
    fun newest(
        userAgent: String,
        testVersions: Boolean,
        get: (url: String, headers: Map<String, String>) -> Answer = ::httpGet,
    ): Release {
        if (!testVersions) return latest(userAgent, get)
        val stable = runCatching { latest(userAgent, get) }
        val test = runCatching {
            parseGitHubList(askGitHub(GITHUB_RELEASES, mapOf("User-Agent" to userAgent), get))
        }
        test.exceptionOrNull()?.let { Logger.w(TAG, "Test versions unavailable: ${it.message}") }
        return choose(stable.getOrNull(), test.getOrNull())
            ?: throw (stable.exceptionOrNull() ?: UpdateFailure(L.updateNoVersion))
    }

    /** The newer of the stable answer and the newest test version; a tie goes to the stable one. */
    fun choose(stable: Release?, test: Release?): Release? = when {
        stable == null -> test
        test == null -> stable
        compare(test.version, stable.version) > 0 -> test
        else -> stable
    }

    private fun askGitHub(
        url: String,
        headers: Map<String, String>,
        get: (url: String, headers: Map<String, String>) -> Answer,
    ): String {
        // GitHub asks every caller to name itself and answers 403 to some that do not.
        val answer = runCatching {
            get(url, headers + ("Accept" to "application/vnd.github+json"))
        }.getOrElse { throw UpdateFailure(L.updateUnreachable) }
        if (answer.code !in 200..299) {
            throw UpdateFailure(
                if (answer.code == 403 || answer.code == 429) {
                    L.updateRateLimited
                } else {
                    L.updateGithubAnswered(answer.code.toString())
                },
            )
        }
        return answer.body
    }

    /** The API's `releases/latest` answer, or null when it is not one. */
    fun parseGitHub(text: String): Release? = (parse(text) as? JsonObject)?.let(::parseGitHub)

    /**
     * The API's release list, pre-releases included: the newest by version of those that are
     * published and carry the APK. Drafts are left out, and so is a file still being uploaded.
     */
    fun parseGitHubList(text: String): Release? {
        val list = parse(text) as? JsonArray ?: return null
        var best: Release? = null
        for (item in list) {
            val obj = item as? JsonObject ?: continue
            if (obj.bool("draft") == true) continue
            val release = parseGitHub(obj) ?: continue
            if (release.assets.isEmpty()) continue
            if (best == null || compare(release.version, best.version) > 0) best = release
        }
        return best
    }

    private fun parse(text: String): JsonElement? {
        if (text.length > MAX_ANSWER_CHARS) return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    private fun parseGitHub(root: JsonObject): Release? = runCatching {
        val tag = root.str("tag_name").orEmpty().trim().take(MAX_FIELD)
        val version = tag.removePrefix("v")
        if (version.isBlank() || ReleaseVersion.parse(version) == null) return null
        val assets = buildMap {
            val list = root["assets"] as? JsonArray ?: return@buildMap
            for (item in list) {
                val asset = item as? JsonObject ?: continue
                val url = asset.str("browser_download_url")?.takeIf { it.startsWith("https://") } ?: continue
                // "starter" while it is still being uploaded; older answers leave the field out.
                if ((asset.str("state") ?: "uploaded") != "uploaded") continue
                val name = asset.str("name")?.ifBlank { null } ?: url.substringAfterLast('/')
                val key = keyFor(name) ?: continue
                if (containsKey(key)) continue
                put(
                    key,
                    ReleaseAsset(
                        url = url,
                        sha256 = asset.str("digest")?.removePrefix("sha256:")?.lowercase()
                            ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } },
                        size = asset.long("size")?.coerceAtLeast(0) ?: 0,
                    ),
                )
            }
        }
        Release(
            version = version,
            pageUrl = root.str("html_url")?.takeIf { it.startsWith("https://github.com/") } ?: "$RELEASES_PAGE/tag/$tag",
            changes = leadingList(root.str("body").orEmpty().take(MAX_BODY)),
            assets = assets,
        )
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    /**
     * The "- " list a release page opens with, one item per change. Stops at the first other line
     * after it, so a table or links under it are left out.
     */
    fun leadingList(markdown: String): List<String> {
        val items = mutableListOf<String>()
        for (raw in markdown.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("- ") || line.startsWith("* ") ->
                    line.drop(2).trim().takeIf { it.isNotEmpty() }?.let { items += it.take(MAX_POINT) }
                line.isEmpty() -> Unit
                items.isNotEmpty() -> break
            }
            if (items.size >= MAX_POINTS) break
        }
        return items
    }

    /**
     * The asset key a release file name stands for. Only the universal APK the release workflow
     * makes, `TMPlayerMusic-<version>.apk`; a per-architecture APK (should one ever be added) and
     * every other file are not offered.
     */
    fun keyFor(fileName: String): String? {
        val name = fileName.lowercase()
        if (!name.startsWith("tmplayermusic-") || !name.endsWith(".apk")) return null
        if (ABIS.any { name.endsWith("-$it.apk") }) return null
        return ANDROID_UNIVERSAL
    }

    private val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /** The APK this app installs from [release]: the universal one. */
    fun androidAsset(release: Release): ReleaseAsset? = release.assets[ANDROID_UNIVERSAL]

    fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    /**
     * Semantic version order with pre-releases: 2.0.0 is newer than 2.0.0-beta.1, which is newer
     * than 2.0.0-alpha.2. A leading "v" and build metadata are ignored, and a missing piece counts
     * as zero, so 1.0 and 1.0.0 are the same. Text order would put 2.0.10 before 2.0.9.
     */
    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val c = coreA.getOrElse(i) { 0 }.compareTo(coreB.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        // A release outranks any of its own pre-releases.
        if (preA.isEmpty() || preB.isEmpty()) return preB.size.coerceAtMost(1) - preA.size.coerceAtMost(1)
        for (i in 0 until maxOf(preA.size, preB.size)) {
            val x = preA.getOrNull(i) ?: return -1
            val y = preB.getOrNull(i) ?: return 1
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            val c = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> -1
                ny != null -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c
        }
        return 0
    }

    private fun split(version: String): Pair<List<Int>, List<String>> {
        val clean = version.trim().take(MAX_FIELD).removePrefix("v").removePrefix("V").substringBefore('+')
        val core = clean.substringBefore('-')
        val pre = clean.substringAfter('-', "")
        return core.split('.').map { it.takeWhile(Char::isDigit).take(9).toIntOrNull() ?: 0 } to
            (if (pre.isEmpty()) emptyList() else pre.split('.'))
    }

    private fun httpGet(url: String, headers: Map<String, String>): Answer {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            headers.forEach(connection::setRequestProperty)
            // The status before the stream, or a 403 arrives as an empty IOException.
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.errorStream?.close()
                return Answer(code, "")
            }
            val body = connection.inputStream.bufferedReader().use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    out.append(buffer, 0, read)
                    // A runaway answer is cut off and then refused by [parse].
                    if (out.length > MAX_ANSWER_CHARS) break
                }
                out.toString()
            }
            return Answer(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private const val TAG = "UpdateFeed"
    private const val MAX_FIELD = 200
    private const val MAX_BODY = 64 * 1024
    private const val MAX_POINT = 500
    private const val MAX_POINTS = 30
    internal const val CONNECT_TIMEOUT_MS = 8_000
    internal const val READ_TIMEOUT_MS = 20_000
}
