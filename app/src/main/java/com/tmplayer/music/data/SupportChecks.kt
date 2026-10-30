package com.tmplayer.music.data

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.tmplayer.music.MainActivity
import com.tmplayer.music.R
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.About
import com.tmplayer.music.platform.Logger

/**
 * The sponsor reminder while the app is closed, as TMPlayer's `BackgroundChecks` does it: one
 * platform job (JobScheduler, no library), once a day, scheduled only while the "Sponsor
 * reminders" switch is on and "Don't show again" has not been pressed, and cancelled as soon as
 * either changes. While idle nothing of ours runs: the system wakes the job, it reads a few values
 * and ends. It never asks for the notification permission itself (the switch does, when turned
 * on), and a notification that would not be seen, would land over the app or over music playing,
 * stays unsent without counting as asked.
 */
class SupportChecks : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        runCatching { checkNudge(applicationContext) }.onFailure { Logger.w(TAG, "support check failed", it) }
        return false
    }

    // Periodic either way; the next window comes round by itself.
    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val TAG = "SupportChecks"
        const val JOB_SUPPORT = 4402
        const val CHANNEL = "support"
        const val NOTIFY_SUPPORT = 0x300002
        private const val EVERY_MS = 24L * 60 * 60 * 1000

        /** The launch extra of a press on the notification: the library opens the Buy Me a Coffee window. */
        const val EXTRA_OPEN = "com.tmplayer.music.extra.OPEN"
        const val OPEN_SUPPORT = "support"

        fun store(context: Context) = SupportStore(Prefs(context))

        /** Makes the job match the settings: at start, and whenever the switch or "Don't show again" changes. */
        fun schedule(context: Context) {
            runCatching {
                val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
                val store = store(context)
                val wanted = SupportReminder.enabled && store.remindersOn && !store.counters().finished
                val have = scheduler.allPendingJobs.any { it.id == JOB_SUPPORT }
                when {
                    wanted && !have -> {
                        val job = JobInfo.Builder(JOB_SUPPORT, ComponentName(context, SupportChecks::class.java))
                            .setPeriodic(EVERY_MS)
                            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setRequiresBatteryNotLow(true) }
                            .setPersisted(true)
                            .build()
                        scheduler.schedule(job)
                    }
                    !wanted && have -> scheduler.cancel(JOB_SUPPORT)
                }
            }.onFailure { Logger.w(TAG, "scheduling the support check failed", it) }
        }

        /** Whether a notification would be seen: allowed by the system and, on Android 13+, by the user. */
        fun canNotify(context: Context): Boolean {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * The notification, if due and it would be seen: first a star on its own, then "Don't forget
         * to sponsor", with Buy me a coffee, Star on GitHub (until pressed) and Not now. Pressing it
         * opens the window. Never over the app, and never while music plays.
         */
        fun checkNudge(context: Context) {
            if (!SupportReminder.enabled || Library.isForeground || Library.isPlaying || !canNotify(context)) return
            val store = store(context)
            val rung = SupportReminder.claimNudgeRung(store, System.currentTimeMillis()) ?: return
            val counters = store.counters()
            ensureChannel(context)
            val star = rung == SupportReminder.NudgeRung.Star
            val title = if (star) L.supportNotifyStarTitle else L.supportNotifySponsorTitle
            val body = if (star) L.supportNotifyStarBody else L.supportNotifySponsorBody
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN, OPEN_SUPPORT)
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_support)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(
                    PendingIntent.getActivity(context, NOTIFY_SUPPORT, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
                .setAutoCancel(true)
            if (!star) builder.addAction(R.drawable.ic_stat_support, L.supportBuyCoffee, NoticeActions.link(context, NoticeActions.LINK_COFFEE))
            if (SupportReminder.offersStar(counters, rung)) {
                builder.addAction(R.drawable.ic_stat_support, L.supportStarGithub, NoticeActions.link(context, NoticeActions.LINK_STAR))
            }
            builder.addAction(0, L.supportNotifyNotNow, NoticeActions.dismiss(context))
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFY_SUPPORT, builder.build()) }
                .onFailure { Logger.w(TAG, "posting the support notification failed", it) }
        }

        private fun ensureChannel(context: Context) {
            // Android 7 and older have no channels: the notification carries its own priority there.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(CHANNEL, L.supportChannel, NotificationManager.IMPORTANCE_LOW).apply {
                description = L.supportChannelDetail
                setShowBadge(false)
            }
            runCatching { context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel) }
        }
    }
}

/**
 * The notification's buttons: Buy me a coffee and Star on GitHub open the page in the browser
 * through [NoticeLinkActivity] (since Android 12 a notification may only open an activity
 * directly, never from a receiver), and Not now ([NoticeDismissReceiver]) only takes the
 * notification down, which already counted as asked when it went out.
 */
object NoticeActions {
    const val EXTRA_LINK = "com.tmplayer.music.extra.NOTICE_LINK"
    const val LINK_COFFEE = "coffee"
    const val LINK_STAR = "star"

    fun link(context: Context, which: String): PendingIntent {
        val intent = Intent(context, NoticeLinkActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
            .putExtra(EXTRA_LINK, which)
        return PendingIntent.getActivity(
            context,
            SupportChecks.NOTIFY_SUPPORT + which.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun dismiss(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        SupportChecks.NOTIFY_SUPPORT,
        Intent(context, NoticeDismissReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(SupportChecks.NOTIFY_SUPPORT) }
    }
}

/** Not now: the notification goes, and nothing else changes. */
class NoticeDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NoticeActions.cancel(context)
    }
}

/**
 * Opens Buy Me a Coffee or the GitHub page in the browser and closes at once, with no window of
 * its own. Star on GitHub is remembered, so no star is asked for again.
 */
class NoticeLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val which = intent?.getStringExtra(NoticeActions.EXTRA_LINK)
        NoticeActions.cancel(this)
        val url = when (which) {
            NoticeActions.LINK_STAR -> About.SOURCE
            NoticeActions.LINK_COFFEE -> About.COFFEE
            else -> null
        }
        if (which == NoticeActions.LINK_STAR) runCatching { SupportChecks.store(applicationContext).markStarred() }
        if (url != null) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        finish()
    }
}
