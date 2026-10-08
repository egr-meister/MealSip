package com.egrmeister.mealsip

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.egrmeister.mealsip.data.local.AppDatabase
import com.egrmeister.mealsip.data.local.PreferencesStore
import com.egrmeister.mealsip.data.repository.RoomStore
import com.egrmeister.mealsip.domain.goals.GoalService
import com.egrmeister.mealsip.domain.logging.ActionGuard
import com.egrmeister.mealsip.domain.logging.UndoCenter
import com.egrmeister.mealsip.domain.logging.UndoExecutor
import com.egrmeister.mealsip.domain.logging.WaterLogService
import com.egrmeister.mealsip.domain.periods.PeriodService
import com.egrmeister.mealsip.domain.reminders.ReminderCoordinator
import com.egrmeister.mealsip.domain.time.SystemTimeSource
import com.egrmeister.mealsip.domain.time.TimeSource
import com.egrmeister.mealsip.domain.time.TodayProvider
import com.egrmeister.mealsip.reminders.AndroidAlarmScheduler
import com.egrmeister.mealsip.reminders.AndroidReminderNotifier
import com.egrmeister.mealsip.reminders.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency injection: one container per process. */
class AppContainer(appContext: Context, val time: TimeSource = SystemTimeSource) {
    val context: Context = appContext.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database = AppDatabase.create(context)
    val store = RoomStore(database)
    val preferences = PreferencesStore(context)
    val todayProvider = TodayProvider(time, appScope)
    val periods = PeriodService(store, time)
    val log = WaterLogService(store, periods, time)
    val goals = GoalService(store, time)
    private val notifier = AndroidReminderNotifier(context)
    val reminders = ReminderCoordinator(store, AndroidAlarmScheduler(context), notifier, time)
    val actionGuard = ActionGuard(clockMs = { SystemClock.elapsedRealtime() })
    val undo = UndoCenter()
    val undoExecutor = UndoExecutor(log)

    /**
     * Clear All Data: cancels alarms and visible notifications, deletes records (one Room transaction)
     * and preferences, then restores the starter periods with reminders disabled.
     */
    suspend fun clearAllData() {
        val existing = store.periods()
        val alarms = AndroidAlarmScheduler(context)
        existing.forEach { alarms.cancel(it.id) }
        notifier.cancelVisible()
        undo.clear()
        store.clearAll()
        preferences.clear()
        periods.seedIfEmpty()
        reminders.rescheduleAll()
    }
}

class MealSipApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannel(this)
        container.appScope.launch {
            container.periods.seedIfEmpty()
            container.reminders.rescheduleAll()
        }
    }
}
