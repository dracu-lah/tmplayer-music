// Adapted from TMPlayer (GPL-3.0), app/src/main/java/com/tmplayer/data/UpdatedReceiver.kt.
package com.tmplayer.music.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tmplayer.music.BuildConfig
import com.tmplayer.music.MainActivity
import com.tmplayer.music.R
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs in the new version once Android has replaced the old one (`MY_PACKAGE_REPLACED`, which
 * Android sends to the app's own package only).
 *
 * An update installed in a session on Android 12 and later closes the app with no "Open" button to
 * bring it back, and Android 10 and later do not let an app start an activity from the background,
 * so the way back is a notification, "Updated to TMPlayer Music 0.2.0", whose tap opens it. After
 * an install the user started, the activity is also asked for directly, where Android allows it.
 * On Android 6 to 11 the system installer's own screen has Open, so nothing is posted. Either way
 * the downloaded APK is deleted here, and the app says "Updated to" once on the next launch.
 */
class UpdatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        scope.launch {
            try {
                runCatching { AfterUpdate.afterReplace(context.applicationContext) }
                    .onFailure { Logger.w("UpdatedReceiver", "after the update", it) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = JobFailures.scope("UpdatedReceiver", Dispatchers.IO)
    }
}

/** What happens after the app has updated itself. */
object AfterUpdate {

    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 7301

    /** The broadcast after the replace: clean up, and bring the app back after a session install. */
    fun afterReplace(context: Context) {
        deleteDownloads(context)
        if (!Updates.enabled) return
        val stored = UpdateSettings(context).installing()
        if (!UpdateRestart.notifyAfterReplace(stored, BuildConfig.VERSION_NAME)) return
        val pending = UpdateRestart.Pending.decode(stored) ?: return
        if (pending.foreground) {
            runCatching {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        runCatching { postUpdated(context, BuildConfig.VERSION_NAME) }.onFailure { Logger.w("AfterUpdate", "no notification", it) }
    }

    /**
     * The first look after a launch: the version to say "Updated to" for, once, or null. Forgets
     * the record when it is done with or stale, and takes the notification down.
     */
    fun onLaunch(context: Context): String? {
        if (!Updates.enabled) return null
        val settings = UpdateSettings(context)
        val launch = UpdateRestart.onLaunch(settings.installing(), BuildConfig.VERSION_NAME, System.currentTimeMillis())
        if (launch.clear) settings.setInstalling("")
        if (launch.updatedTo != null) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
            deleteDownloads(context)
        }
        return launch.updatedTo
    }

    private fun postUpdated(context: Context, version: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, L.updateChannel, NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(L.updateUpdatedTo(version))
            .setContentText(L.updateTapToOpen)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** The APK is installed, or was never going to be: no second copy of the app in the cache. */
    private fun deleteDownloads(context: Context) {
        runCatching { File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() } }
    }
}
