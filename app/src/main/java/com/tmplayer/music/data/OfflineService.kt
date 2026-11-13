package com.tmplayer.music.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tmplayer.music.MainActivity
import com.tmplayer.music.R
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and shows progress while Keep offline fetches tracks (a foreground
 * service of the data sync type). The queue itself runs in [OfflineDownloads]; this only holds the
 * process up, and ends the moment nothing is being fetched: waiting for Wi-Fi, paused or done, so
 * nothing runs, and nothing is held, when nothing downloads.
 *
 * Android 6 to 15: the notification channel is made from Android 8, the service type is passed from
 * Android 10 (and declared in the manifest, with its permission, for Android 14), and Android 15's
 * time limit on data sync services pauses the queue instead of letting the system kill the app.
 * From Android 12 a service cannot be started from the background: that refusal is caught and said
 * in Settings ([OfflineHost.refused]); the queue goes on in the process while it lives.
 */
class OfflineService : Service() {

    private val scope = JobFailures.scope("OfflineService", Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android allows a few seconds from startForegroundService to this call: the notification first.
        val shown = goToForeground(notification(OfflineDownloads.state.value))
        when (intent?.action) {
            ACTION_PAUSE -> OfflineDownloads.pause()
            ACTION_RESUME -> OfflineDownloads.resume()
            ACTION_STOP -> OfflineDownloads.cancelAll()
        }
        if (!shown) {
            // The system would not take it: nothing holds the process, so say so and let go.
            OfflineHost.refused.value = true
            stopSelf()
            return START_NOT_STICKY
        }
        OfflineHost.refused.value = false
        holdWake()
        if (watching == null) {
            watching = scope.launch {
                OfflineDownloads.state.collect { state ->
                    if (!state.working) {
                        stopForegroundCompat()
                        stopSelf()
                    } else {
                        notifier().notify(NOTIFICATION_ID, notification(state))
                        // At most one update a second: a fetch reports far more often than that.
                        delay(UPDATE_EVERY_MS)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 ends a data sync service after six hours: the queue is paused, not lost. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Logger.i(TAG, "Android's time limit on this service: pausing the queue")
        OfflineDownloads.pause()
        runCatching { Toast.makeText(applicationContext, L.offlinePausedByAndroid, Toast.LENGTH_LONG).show() }
        stopSelf()
    }

    override fun onDestroy() {
        watching?.cancel()
        watching = null
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        // The index is written now: the process may go with the service.
        OfflineDownloads.flush()
        super.onDestroy()
    }

    private fun holdWake() {
        if (wake != null) return
        wake = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tmplayermusic:offline")
                .apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LIMIT_MS)
                }
        }.getOrNull()
    }

    private fun goToForeground(notification: Notification): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }.onFailure { Logger.w(TAG, "could not go to the foreground", it) }.isSuccess

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        }
    }

    private fun notifier(): NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notification(state: KeepState): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(OfflineNotice.title(state))
            .setContentText(OfflineNotice.text(state))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, L.offlinePause, action(ACTION_PAUSE, 1))
            .addAction(0, L.offlineStopAll, action(ACTION_STOP, 2))
        OfflineNotice.fraction(state)?.let { builder.setProgress(1000, (it * 1000).toInt(), false) }
        return builder.build()
    }

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
        this,
        code,
        Intent(this, OfflineService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "OfflineService"
        private const val CHANNEL = "offline"
        private const val NOTIFICATION_ID = 4107
        private const val UPDATE_EVERY_MS = 1_000L

        /** A fetch that never ends cannot hold the CPU forever: the lock lapses after a day and the next start takes it again. */
        private const val WAKE_LIMIT_MS = 24L * 60 * 60 * 1000
        const val ACTION_PAUSE = "com.tmplayer.music.offline.PAUSE"
        const val ACTION_RESUME = "com.tmplayer.music.offline.RESUME"
        const val ACTION_STOP = "com.tmplayer.music.offline.STOP"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, L.offlineChannelName, NotificationManager.IMPORTANCE_LOW).apply {
                    description = L.offlineChannelDescription
                    setShowBadge(false)
                },
            )
        }
    }
}

/** Starts [OfflineService] for a fetch, and remembers when Android would not let it. */
object OfflineHost {

    /** True when the system refused the service (a start from the background, Android 12 and later). */
    val refused = MutableStateFlow(false)

    fun start(context: Context) {
        val app = context.applicationContext
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, OfflineService::class.java))
        }.onFailure {
            Logger.w("OfflineHost", "could not start the offline service", it)
            refused.value = true
        }
    }
}

/** What the notification says, from the queue's state; pure, so it is tested without a device. */
object OfflineNotice {
    fun title(state: KeepState): String = state.running.firstOrNull()?.title?.takeIf { it.isNotBlank() } ?: L.offlineNotifTitle

    fun text(state: KeepState): String = L.offlineNotifText(state.done.coerceAtMost(state.total), state.total)

    /** How far the track being fetched has come, 0 to 1, or null when its size is not known. */
    fun fraction(state: KeepState): Float? {
        val one = state.running.firstOrNull() ?: return null
        if (one.total <= 0) return null
        return (one.downloaded.toFloat() / one.total).coerceIn(0f, 1f)
    }
}
