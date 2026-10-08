package com.egrmeister.mealsip.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.egrmeister.mealsip.MealSipApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            withTimeoutOrNull(8_000) { block() }
        } finally {
            pending.finish()
        }
    }
}

/** Receives only our own explicit, immutable alarm PendingIntent (not exported). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAlarmScheduler.ACTION_REMIND) return
        val callback = AndroidAlarmScheduler.callbackFrom(intent) ?: return
        val container = (context.applicationContext as MealSipApp).container
        runAsync {
            val decision = container.reminders.handle(callback)
            Log.d("MealSipReminders", "Period ${callback.periodId}: $decision")
        }
    }
}

/** Reschedules after reboot, manual time / time-zone change and app update (protected broadcasts). */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val container = (context.applicationContext as MealSipApp).container
                container.todayProvider.refresh()
                runAsync { container.reminders.rescheduleAll() }
            }
        }
    }
}
