package com.egrmeister.mealsip.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.egrmeister.mealsip.MainActivity
import com.egrmeister.mealsip.R
import com.egrmeister.mealsip.domain.reminders.AlarmScheduler
import com.egrmeister.mealsip.domain.reminders.NotificationAccess
import com.egrmeister.mealsip.domain.reminders.ReminderCallback
import com.egrmeister.mealsip.domain.reminders.ReminderNotifier
import java.time.LocalDate

/**
 * Inexact one-shot alarms via [AlarmManager.set]. No exact-alarm permission, no foreground service,
 * no wake lock. One pending alarm per period: the PendingIntent is unique per period through its data URI.
 */
class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    override fun schedule(triggerAtMillis: Long, callback: ReminderCallback) {
        val intent = baseIntent(callback.periodId)
            .putExtra(EXTRA_PERIOD_ID, callback.periodId)
            .putExtra(EXTRA_GENERATION, callback.generation)
            .putExtra(EXTRA_DATE, callback.targetDate.toString())
            .putExtra(EXTRA_MINUTE, callback.targetMinute)
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager?.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        Log.d(TAG, "Scheduled period ${callback.periodId} for ${callback.targetDate} minute ${callback.targetMinute}")
    }

    override fun cancel(periodId: Long) {
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            baseIntent(periodId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager?.cancel(pi)
        pi.cancel()
    }

    private fun baseIntent(periodId: Long) = Intent(context, ReminderReceiver::class.java)
        .setAction(ACTION_REMIND)
        .setData(Uri.parse("mealsip://reminder/$periodId"))

    companion object {
        private const val TAG = "MealSipReminders"
        const val ACTION_REMIND = "com.egrmeister.mealsip.action.REMIND"
        private const val EXTRA_PERIOD_ID = "period_id"
        private const val EXTRA_GENERATION = "generation"
        private const val EXTRA_DATE = "target_date"
        private const val EXTRA_MINUTE = "target_minute"

        fun callbackFrom(intent: Intent): ReminderCallback? {
            val periodId = intent.getLongExtra(EXTRA_PERIOD_ID, -1L)
            val generation = intent.getIntExtra(EXTRA_GENERATION, -1)
            val minute = intent.getIntExtra(EXTRA_MINUTE, -1)
            val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (periodId < 0 || generation < 0 || minute < 0 || date == null) return null
            return ReminderCallback(periodId, generation, date, minute)
        }
    }
}

object Notifications {
    const val CHANNEL_ID = "period_reminders"

    /** Single ID: a new reminder replaces the previous one, so nothing accumulates. */
    const val REMINDER_NOTIFICATION_ID = 3001
    const val EXTRA_OPEN_PERIOD = "com.egrmeister.mealsip.OPEN_PERIOD"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun access(context: Context): NotificationAccess {
        val compat = NotificationManagerCompat.from(context)
        val runtimeRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val granted = !runtimeRequired || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        val channel = compat.getNotificationChannel(CHANNEL_ID)
        // A missing channel is recreated before posting; a channel the user turned off stays off.
        val channelEnabled = channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
        return NotificationAccess(
            runtimePermissionRequired = runtimeRequired,
            permissionGranted = granted,
            appNotificationsEnabled = compat.areNotificationsEnabled(),
            channelEnabled = channelEnabled,
        )
    }

    @SuppressLint("MissingPermission") // Checked explicitly right before notify().
    fun post(context: Context, periodId: Long, periodName: String): Boolean {
        createChannel(context)
        if (!access(context).canDeliver) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_PERIOD, periodId)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = context.getString(R.string.reminder_body, periodName)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(REMINDER_NOTIFICATION_ID, notification)
        return true
    }

    fun cancelVisible(context: Context) {
        NotificationManagerCompat.from(context).cancel(REMINDER_NOTIFICATION_ID)
    }
}

class AndroidReminderNotifier(private val context: Context) : ReminderNotifier {
    override fun canDeliver(): Boolean = Notifications.access(context).canDeliver
    override fun post(periodId: Long, periodName: String) {
        Notifications.post(context, periodId, periodName)
    }
    override fun cancelVisible() = Notifications.cancelVisible(context)
}
