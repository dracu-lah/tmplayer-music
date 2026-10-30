// Adapted from TMPlayer (GPL-3.0), core/src/jvmTest/kotlin/com/tmplayer/data/UpdateFeedTest.kt.
package com.tmplayer.music.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** GitHub's answers, the version order and the one asset the app installs, away from the network. */
class UpdateFeedTest {

    @After
    fun reset() {
        Updates.fetch = { UpdateFeed.latest("test") }
        Updates.configure(installedVersion = "0")
        Updates.dismiss()
        Updates.skip()
    }

    private fun ghRelease(tag: String, prerelease: Boolean = false, draft: Boolean = false, state: String = "uploaded") = """
        {"tag_name":"$tag","prerelease":$prerelease,"draft":$draft,
         "html_url":"https://github.com/dracu-lah/tmplayer-music/releases/tag/$tag",
         "body":"- One change\n\n| File | SHA-256 |",
         "assets":[{"name":"TMPlayerMusic-${tag.removePrefix("v")}.apk","size":9,"state":"$state",
           "digest":"sha256:${"e".repeat(64)}",
           "browser_download_url":"https://github.com/dracu-lah/tmplayer-music/releases/download/$tag/TMPlayerMusic-${tag.removePrefix("v")}.apk"}]}
    """.trimIndent()

    @Test
    fun `reads the GitHub answer`() {
        val json = """
            {"html_url":"https://github.com/dracu-lah/tmplayer-music/releases/tag/v0.2.0",
             "tag_name": "v0.2.0", "body": "- Faster start\n- Updates",
             "assets":[
               {"name":"TMPlayerMusic-0.2.0.apk","size":30000000,"digest":"sha256:${"D".repeat(64)}",
                "browser_download_url":"https://github.com/d/r/releases/download/v0.2.0/TMPlayerMusic-0.2.0.apk"},
               {"name":"TMPlayerMusic-0.2.0-arm64-v8a.apk","size":5,
                "browser_download_url":"https://github.com/d/r/releases/download/v0.2.0/TMPlayerMusic-0.2.0-arm64-v8a.apk"},
               {"name":"mapping-0.2.0.txt","browser_download_url":"https://github.com/d/r/releases/download/v0.2.0/mapping-0.2.0.txt"}
             ]}
        """.trimIndent()
        val release = UpdateFeed.parseGitHub(json)!!
        assertEquals("0.2.0", release.version)
        assertEquals("https://github.com/dracu-lah/tmplayer-music/releases/tag/v0.2.0", release.pageUrl)
        assertEquals(setOf(UpdateFeed.ANDROID_UNIVERSAL), release.assets.keys)
        val apk = UpdateFeed.androidAsset(release)!!
        assertEquals("TMPlayerMusic-0.2.0.apk", apk.name)
        assertEquals(30000000L, apk.size)
        assertEquals("d".repeat(64), apk.sha256)
        assertEquals(listOf("Faster start", "Updates"), release.changes)
        assertNull(UpdateFeed.parseGitHub("{}"))
        assertNull(UpdateFeed.parseGitHub("<html>"))
    }

    @Test
    fun `a digest that is not one is no digest`() {
        val release = UpdateFeed.parseGitHub(ghRelease("v0.2.0").replace("e".repeat(64), "zz"))!!
        assertNull(UpdateFeed.androidAsset(release)?.sha256)
    }

    @Test
    fun `only the universal apk the release workflow names is offered`() {
        mapOf(
            "TMPlayerMusic-0.2.0.apk" to UpdateFeed.ANDROID_UNIVERSAL,
            "TMPlayerMusic-0.2.0-beta.3.apk" to UpdateFeed.ANDROID_UNIVERSAL,
            "tmplayermusic-1.0.0.APK" to UpdateFeed.ANDROID_UNIVERSAL,
            "TMPlayerMusic-0.2.0-arm64-v8a.apk" to null,
            "TMPlayerMusic-0.2.0-armeabi-v7a.apk" to null,
            "TMPlayerMusic-0.2.0-x86_64.apk" to null,
            "TMPlayer-1.28.0-universal.apk" to null,
            "mapping-0.2.0.txt" to null,
            "" to null,
        ).forEach { (name, key) -> assertEquals(name, key, UpdateFeed.keyFor(name)) }
    }

    @Test
    fun `rate limiting and other answers are named`() {
        try {
            UpdateFeed.latest("t") { _, _ -> UpdateFeed.Answer(403, "") }
            fail("expected a failure")
        } catch (e: java.io.IOException) {
            assertEquals("GitHub has had too many requests from this connection. Try again in an hour.", e.message)
        }
        try {
            UpdateFeed.latest("t") { _, _ -> throw java.io.IOException("offline") }
            fail("expected a failure")
        } catch (e: java.io.IOException) {
            assertEquals("Could not reach GitHub. Try again in a moment.", e.message)
        }
        try {
            UpdateFeed.latest("t") { _, _ -> UpdateFeed.Answer(502, "") }
            fail("expected a failure")
        } catch (e: java.io.IOException) {
            assertEquals("GitHub answered 502. Try again in a moment.", e.message)
        }
    }

    @Test
    fun `GitHub is asked with a name and its media type`() {
        val release = UpdateFeed.latest("TMPlayerMusic/0.1.0") { url, headers ->
            assertEquals(UpdateFeed.GITHUB_LATEST, url)
            assertEquals("TMPlayerMusic/0.1.0", headers["User-Agent"])
            assertEquals("application/vnd.github+json", headers["Accept"])
            UpdateFeed.Answer(200, ghRelease("v0.2.0"))
        }
        assertEquals("0.2.0", release.version)
    }

    // ---- order ------------------------------------------------------------------------------

    @Test
    fun `versions compare as numbers with pre-releases below their release`() {
        assertTrue(UpdateFeed.isNewer("0.5.1", "0.5.0"))
        assertFalse(UpdateFeed.isNewer("0.5.0", "0.5.0"))
        assertFalse(UpdateFeed.isNewer("0.4.9", "0.5.0"))
        assertTrue(UpdateFeed.isNewer("0.10.0", "0.9.0"))
        assertFalse(UpdateFeed.isNewer("0.9.0", "0.10.0"))
        assertTrue(UpdateFeed.isNewer("2.0.10", "2.0.9"))
        assertTrue(UpdateFeed.isNewer("v2.1.0", "2.0.0"))
        assertTrue(UpdateFeed.isNewer("2.0.0", "2.0.0-alpha.1"))
        assertTrue(UpdateFeed.isNewer("2.0.0-beta.1", "2.0.0-alpha.2"))
        assertTrue(UpdateFeed.isNewer("2.0.0-alpha.10", "2.0.0-alpha.9"))
        assertFalse(UpdateFeed.isNewer("2.0.0-alpha.1", "2.0.0"))
        assertFalse(UpdateFeed.isNewer("1.17.0", "2.0.0-alpha.1"))
        assertTrue(UpdateFeed.isNewer("1.0", "0.9.9"))
        assertFalse(UpdateFeed.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun `test versions order by number and below their release`() {
        val order = listOf("0.1.0", "0.1.1-beta.1", "0.1.1-beta.2", "0.1.1-beta.9", "0.1.1-beta.10", "0.1.1", "0.1.2-beta.1")
        for ((older, newer) in order.zipWithNext()) {
            assertTrue("$newer after $older", UpdateFeed.compare(newer, older) > 0)
            assertTrue("$older before $newer", UpdateFeed.compare(older, newer) < 0)
        }
        assertEquals(0, UpdateFeed.compare("v0.1.1-beta.3", "0.1.1-beta.3"))
    }

    @Test
    fun `the version code follows the release workflow`() {
        assertEquals(10099, ReleaseVersion.androidCode("0.1.0"))
        assertEquals(10001, ReleaseVersion.androidCode("v0.1.0-beta.1"))
        assertEquals(1020399, ReleaseVersion.androidCode("1.2.3"))
        assertNull(ReleaseVersion.androidCode("1.100.0"))
        assertTrue(ReleaseVersion.isTest("0.2.0-beta.4"))
        assertFalse(ReleaseVersion.isTest("0.2.0"))
        assertNull(ReleaseVersion.parse("0.2"))
    }

    // ---- test versions (GitHub pre-releases) -------------------------------------------------

    private val list = "[" + listOf(
        ghRelease("v0.2.0-beta.10", prerelease = true),
        ghRelease("v0.2.0-beta.11", prerelease = true, draft = true),
        ghRelease("v0.2.0-beta.9", prerelease = true),
        ghRelease("v0.1.0"),
    ).joinToString(",") + "]"

    @Test
    fun `the release list gives its newest published test version`() {
        val newest = UpdateFeed.parseGitHubList(list)!!
        // beta.10 over beta.9, though text order says otherwise; the draft is passed over.
        assertEquals("0.2.0-beta.10", newest.version)
        assertEquals(listOf("One change"), newest.changes)
        assertNull(UpdateFeed.parseGitHubList("[]"))
        assertNull(UpdateFeed.parseGitHubList("{}"))
    }

    @Test
    fun `a release whose files are still uploading is not offered`() {
        val uploading = "[" + ghRelease("v0.2.0-beta.12", prerelease = true, state = "starter") + "," +
            ghRelease("v0.2.0-beta.10", prerelease = true) + "]"
        assertEquals("0.2.0-beta.10", UpdateFeed.parseGitHubList(uploading)!!.version)
    }

    @Test
    fun `the newer of the release and the test version is chosen, a tie going to the release`() {
        val stable = Release("0.1.0", "s")
        val beta = Release("0.2.0-beta.1", "b")
        assertEquals(beta, UpdateFeed.choose(stable, beta))
        assertEquals(stable, UpdateFeed.choose(stable, null))
        assertEquals(beta, UpdateFeed.choose(null, beta))
        assertNull(UpdateFeed.choose(null, null))
        val final = Release("0.2.0", "f")
        assertEquals(final, UpdateFeed.choose(final, beta))
        assertEquals(final, UpdateFeed.choose(final, Release("0.2.0", "github")))
    }

    @Test
    fun `without test versions only the latest release is asked`() {
        val asked = mutableListOf<String>()
        UpdateFeed.newest("t", testVersions = false) { url, _ ->
            asked += url
            UpdateFeed.Answer(200, ghRelease("v0.1.0"))
        }
        assertEquals(listOf(UpdateFeed.GITHUB_LATEST), asked)
    }

    @Test
    fun `with test versions the release list is asked too and the newer wins`() {
        val asked = mutableListOf<String>()
        val release = UpdateFeed.newest("t", testVersions = true) { url, _ ->
            asked += url
            if (url == UpdateFeed.GITHUB_LATEST) UpdateFeed.Answer(200, ghRelease("v0.1.0")) else UpdateFeed.Answer(200, list)
        }
        assertEquals("0.2.0-beta.10", release.version)
        assertEquals(listOf(UpdateFeed.GITHUB_LATEST, UpdateFeed.GITHUB_RELEASES), asked)
    }

    @Test
    fun `a release list that cannot be read costs only the test versions`() {
        val release = UpdateFeed.newest("t", testVersions = true) { url, _ ->
            if (url == UpdateFeed.GITHUB_LATEST) UpdateFeed.Answer(200, ghRelease("v0.1.0")) else UpdateFeed.Answer(403, "")
        }
        assertEquals("0.1.0", release.version)
        // No full release yet (GitHub's latest is a 404): the list still answers.
        val fromList = UpdateFeed.newest("t", testVersions = true) { url, _ ->
            if (url == UpdateFeed.GITHUB_LATEST) UpdateFeed.Answer(404, "") else UpdateFeed.Answer(200, list)
        }
        assertEquals("0.2.0-beta.10", fromList.version)
    }

    // ---- the state the popup reads ------------------------------------------------------------

    @Test
    fun `the updater asks for test versions only when the setting is on`() = runBlocking {
        val wanted = mutableListOf<Boolean>()
        var on = false
        Updates.configure(installedVersion = "0.1.0", testVersions = { on })
        Updates.fetch = { tests ->
            wanted += tests
            if (tests) UpdateFeed.parseGitHubList(list)!! else UpdateFeed.parseGitHub(ghRelease("v0.1.0"))!!
        }
        Updates.check(quiet = true)
        assertEquals(UpdateState.Idle, Updates.state.value)

        on = true
        Updates.testVersionsSwitched(on = true, skipped = "")
        assertEquals("0.2.0-beta.10", (Updates.state.value as UpdateState.Available).release.version)

        // Off takes the test version back at once, and the stable answer has nothing newer.
        on = false
        Updates.testVersionsSwitched(on = false, skipped = "")
        assertEquals(UpdateState.Idle, Updates.state.value)
        assertEquals(listOf(false, true, false), wanted)
    }

    @Test
    fun `a test version installed is updated by its release whatever the setting`() = runBlocking {
        Updates.configure(installedVersion = "0.2.0-beta.10")
        Updates.fetch = { UpdateFeed.parseGitHub(ghRelease("v0.2.0"))!! }
        Updates.check(quiet = true)
        assertEquals("0.2.0", (Updates.state.value as UpdateState.Available).release.version)
        Updates.skip()
        Updates.fetch = { UpdateFeed.parseGitHub(ghRelease("v0.1.0"))!! }
        Updates.check(quiet = true)
        assertEquals(UpdateState.Idle, Updates.state.value)
    }

    @Test
    fun `a newer release without the apk is no news`() = runBlocking {
        Updates.configure(installedVersion = "0.1.0")
        Updates.fetch = { Release("0.2.0", "page") }
        assertTrue(Updates.check(quiet = true))
        assertEquals(UpdateState.Idle, Updates.state.value)
    }

    @Test
    fun `a skipped version is passed over quietly and re-offered when asked`() = runBlocking {
        Updates.configure(installedVersion = "0.1.0")
        Updates.fetch = { UpdateFeed.parseGitHub(ghRelease("v0.2.0"))!! }
        Updates.check(quiet = true, skipped = "0.2.0")
        assertEquals(UpdateState.Idle, Updates.state.value)
        Updates.check(quiet = false, skipped = "0.2.0")
        assertEquals(true, (Updates.state.value as UpdateState.Available).skipped)
    }

    @Test
    fun `a quiet failure keeps what was already known`() = runBlocking {
        Updates.configure(installedVersion = "0.1.0")
        Updates.fetch = { UpdateFeed.parseGitHub(ghRelease("v0.2.0"))!! }
        Updates.check(quiet = true)
        Updates.fetch = { throw java.io.IOException("offline") }
        assertFalse(Updates.check(quiet = true))
        assertTrue(Updates.state.value is UpdateState.Available)
        assertFalse(Updates.check(quiet = false))
        assertEquals("Could not reach GitHub. Try again in a moment.", (Updates.state.value as UpdateState.Failed).message)
        // Dismissed, the release is still on offer.
        Updates.dismiss()
        assertTrue(Updates.state.value is UpdateState.Available)
    }

    @Test
    fun `offline is said without asking`() = runBlocking {
        var asked = 0
        Updates.configure(installedVersion = "0.1.0", online = { false })
        Updates.fetch = { asked++; Release("0.2.0", "p") }
        assertFalse(Updates.check(quiet = false))
        assertEquals("Connect to the internet to check for updates.", (Updates.state.value as UpdateState.Failed).message)
        assertEquals(0, asked)
    }

    @Test
    fun `a build with updates switched off never asks and never downloads`() = runBlocking {
        Updates.configure(installedVersion = "0.1.0")
        var asked = 0
        Updates.fetch = { asked++; UpdateFeed.parseGitHub(ghRelease("v0.2.0"))!! }
        Updates.enabled = false
        try {
            assertFalse(Updates.check(quiet = true))
            assertFalse(Updates.check(quiet = false))
            assertEquals(0, asked)
            assertEquals(UpdateState.Idle, Updates.state.value)
            val dir = kotlin.io.path.createTempDirectory("updates").toFile()
            assertNull(Updates.download(UpdateFeed.parseGitHub(ghRelease("v0.2.0"))!!, dir))
            assertEquals(UpdateState.Idle, Updates.state.value)
            assertTrue(dir.listFiles().isNullOrEmpty())
            dir.deleteRecursively()
        } finally {
            Updates.enabled = true
        }
        assertTrue(Updates.check(quiet = true))
        assertEquals(1, asked)
    }

    @Test
    fun `the GitHub page gives its opening list and not the table under it`() {
        val body = "- First change\n- Second: with detail\n\n\n| File | SHA-256 |\n| --- | --- |\n- not this\n"
        assertEquals(listOf("First change", "Second: with detail"), UpdateFeed.leadingList(body))
    }
}
