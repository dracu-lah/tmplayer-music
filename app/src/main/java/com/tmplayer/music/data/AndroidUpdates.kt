// Adapted from TMPlayer (GPL-3.0), app/src/main/java/com/tmplayer/data/AndroidUpdates.kt, with
// the update settings in their own SharedPreferences file instead of TMPlayer's SettingsStore.
package com.tmplayer.music.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.net.toUri
import com.tmplayer.music.BuildConfig
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.ui.common.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The update settings and records, in a file of their own ("updates"): the Settings switches, the
 * throttle, the skipped and snoozed versions, the install under way and Android's refusal of a
 * silent install. Reads are cheap SharedPreferences reads; a value of the wrong type reads as the
 * default.
 */
class UpdateSettings(context: Context) : UpdatePrefs {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private fun bool(key: String, default: Boolean) = runCatching { prefs.getBoolean(key, default) }.getOrDefault(default)
    private fun long(key: String) = runCatching { prefs.getLong(key, 0L) }.getOrDefault(0L)
    private fun string(key: String) = runCatching { prefs.getString(key, null) }.getOrNull().orEmpty()

    override suspend fun checkOnOpen(): Boolean = checkOnOpenNow()
    fun checkOnOpenNow(): Boolean = bool(CHECK, true)
    fun setCheckOnOpen(on: Boolean) = prefs.edit().putBoolean(CHECK, on).apply()

    override suspend fun testVersions(): Boolean = testVersionsNow()
    fun testVersionsNow(): Boolean = bool(TEST_VERSIONS, false)
    fun setTestVersions(on: Boolean) = prefs.edit().putBoolean(TEST_VERSIONS, on).apply()

    override suspend fun lastCheck(): Long = long(LAST_CHECK)
    override suspend fun setLastCheck(at: Long) = prefs.edit().putLong(LAST_CHECK, at).apply()
    override suspend fun skippedVersion(): String = string(SKIPPED)
    override suspend fun setSkippedVersion(version: String) = prefs.edit().putString(SKIPPED, version).apply()
    override suspend fun snoozedUntil(): Long = long(SNOOZED)
    override suspend fun setSnoozedUntil(at: Long) = prefs.edit().putLong(SNOOZED, at).apply()
    override suspend fun popupShownFor(): String = string(POPUP_SHOWN)
    override suspend fun setPopupShownFor(version: String) = prefs.edit().putString(POPUP_SHOWN, version).apply()

    /** The install handed to Android ([UpdateRestart.Pending]), "" for none. */
    fun installing(): String = string(INSTALLING)

    /** Written with commit: the install may end this process straight after. */
    fun setInstalling(record: String) {
        prefs.edit().putString(INSTALLING, record).commit()
    }

    /** The Android release (API level) on which a silent install was refused, 0 for none. */
    fun silentRefused(): Long = long(SILENT_REFUSED)
    fun setSilentRefused(api: Long) = prefs.edit().putLong(SILENT_REFUSED, api).apply()

    private companion object {
        const val CHECK = "check_on_open"
        const val TEST_VERSIONS = "test_versions"
        const val LAST_CHECK = "last_check"
        const val SKIPPED = "skipped"
        const val SNOOZED = "snoozed_until"
        const val POPUP_SHOWN = "popup_shown_for"
        const val INSTALLING = "installing"
        const val SILENT_REFUSED = "silent_refused_api"
    }
}

/** Tells [Updates] what this build is: called once from `MusicApp.onCreate`. */
fun Updates.configureForAndroid(context: Context) {
    val app = context.applicationContext
    enabled = BuildConfig.UPDATE_CHECK
    configure(
        installedVersion = BuildConfig.VERSION_NAME,
        online = { Library.network.value != NetState.Offline },
        metered = { Library.network.value == NetState.Metered },
        testVersions = { UpdateSettings(app).testVersionsNow() },
        formatBytes = { Format.size(it).ifEmpty { "0 B" } },
    )
}

/** The update schedule over this app's settings. Holds no state of its own. */
fun updateScheduler(context: Context): UpdateScheduler = UpdateScheduler(UpdateSettings(context))

/** Where the APK is downloaded: the cache, so Android may reclaim a forgotten one. */
fun updatesDir(context: Context): File = File(context.cacheDir, "updates")

/**
 * Starts and stops the download and install that Update now begins. It runs on a scope of its own
 * so closing the popup does not cancel it; Cancel does ([cancel]). One at a time.
 */
object UpdateRunner {
    private val scope = JobFailures.scope("Updates", Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Fetches the APK (resuming a part left by an earlier try) and installs it ([SelfInstaller]):
     * on Android 12 and later without Android's question where it allows that, else with it. The
     * space check is [Updates.download]'s, before a byte is fetched.
     */
    fun start(context: Context, release: Release) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        _running.value = true
        job = scope.launch {
            try {
                val session = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                val file = Updates.download(release, updatesDir(app), installing = session) ?: return@launch
                when (val result = SelfInstaller.install(app, file, release, foreground = true)) {
                    SelfInstaller.Result.Installed -> Unit
                    SelfInstaller.Result.HandedOver -> Updates.handedOver(release, file)
                    SelfInstaller.Result.Cancelled, SelfInstaller.Result.NeedsUser -> Updates.backToOffer()
                    is SelfInstaller.Result.Failed -> Updates.installFailed(result.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("UpdateRunner", "update failed", e)
                Updates.installFailed(com.tmplayer.music.i18n.L.updateDidNotFinish)
            } finally {
                _running.value = false
            }
        }
    }

    /** Cancel while downloading: the part stays for the next Update now. */
    fun cancel() {
        Updates.cancelDownload()
    }
}

/** Whether the device will let this app hand an APK to the installer at all. */
@Suppress("UnusedReceiverParameter")
fun Updates.canInstall(context: Context): Boolean =
    // Before Android 8 there is no per-app permission, only the one "unknown sources" switch, and
    // the system's installer asks for that itself when the APK is handed over.
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

/** The system screen where "allow apps from this source" is turned on. */
@Suppress("UnusedReceiverParameter")
fun Updates.unknownSourcesIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData("package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
