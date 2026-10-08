package com.egrmeister.mealsip.domain.reminders

import com.egrmeister.mealsip.domain.goals.GoalLookup
import com.egrmeister.mealsip.domain.model.MealSipStore
import com.egrmeister.mealsip.domain.model.Period
import com.egrmeister.mealsip.domain.time.TimeMath
import com.egrmeister.mealsip.domain.time.TimeSource
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

/** Data carried by one pending alarm. */
data class ReminderCallback(
    val periodId: Long,
    val generation: Int,
    val targetDate: LocalDate,
    val targetMinute: Int,
)

enum class ReminderDecision {
    POST,
    SKIP_PERIOD_MISSING,
    SKIP_ARCHIVED,
    SKIP_DISABLED,
    SKIP_STALE_CONFIGURATION,
    SKIP_OTHER_DAY,
    SKIP_TOO_LATE,
    SKIP_NOT_DELIVERABLE,
    SKIP_ALREADY_RECORDED,
    SKIP_GOAL_REACHED,
}

/** Notification availability snapshot (runtime permission, app switch, channel switch). */
data class NotificationAccess(
    val runtimePermissionRequired: Boolean,
    val permissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val channelEnabled: Boolean,
) {
    val canDeliver: Boolean
        get() = (!runtimePermissionRequired || permissionGranted) && appNotificationsEnabled && channelEnabled
}

/**
 * Pure reminder rules. Application delivery policy (not an Android guarantee): a reminder delayed
 * by more than [MAX_DELAY_MINUTES] beyond its intended time is skipped, and no backlog is delivered.
 */
object ReminderPolicy {
    const val MAX_DELAY_MINUTES = 60L

    /** Intended local time of a callback in the CURRENT zone (DST gaps shift forward). */
    fun intendedTime(cb: ReminderCallback, now: ZonedDateTime): ZonedDateTime =
        ZonedDateTime.of(cb.targetDate, TimeMath.timeOfMinute(cb.targetMinute), now.zone)

    fun decide(
        cb: ReminderCallback,
        period: Period?,
        now: ZonedDateTime,
        canDeliver: Boolean,
        periodEntriesToday: Int,
        dayTotalMl: Long,
        goalMl: Int?,
    ): ReminderDecision {
        if (period == null) return ReminderDecision.SKIP_PERIOD_MISSING
        if (!period.isActive) return ReminderDecision.SKIP_ARCHIVED
        if (!period.reminderEnabled || period.reminderMinute == null) return ReminderDecision.SKIP_DISABLED
        if (period.reminderGeneration != cb.generation || period.reminderMinute != cb.targetMinute) {
            return ReminderDecision.SKIP_STALE_CONFIGURATION
        }
        if (cb.targetDate != now.toLocalDate()) return ReminderDecision.SKIP_OTHER_DAY
        val late = Duration.between(intendedTime(cb, now), now)
        if (late.toMinutes() > MAX_DELAY_MINUTES) return ReminderDecision.SKIP_TOO_LATE
        if (!canDeliver) return ReminderDecision.SKIP_NOT_DELIVERABLE
        if (periodEntriesToday > 0) return ReminderDecision.SKIP_ALREADY_RECORDED
        if (goalMl != null && goalMl > 0 && dayTotalMl >= goalMl) return ReminderDecision.SKIP_GOAL_REACHED
        return ReminderDecision.POST
    }

    /** First occurrence of [minute] strictly after [after], in [after]'s zone. */
    fun nextOccurrence(after: ZonedDateTime, minute: Int): ZonedDateTime {
        val time = TimeMath.timeOfMinute(minute)
        val sameDay = ZonedDateTime.of(after.toLocalDate(), time, after.zone)
        return if (sameDay.isAfter(after)) sameDay else ZonedDateTime.of(after.toLocalDate().plusDays(1), time, after.zone)
    }
}

/** Platform alarm boundary (AlarmManager in the app, a fake in tests). */
interface AlarmScheduler {
    fun schedule(triggerAtMillis: Long, callback: ReminderCallback)
    fun cancel(periodId: Long)
}

/** Platform notification boundary. */
interface ReminderNotifier {
    fun canDeliver(): Boolean
    fun post(periodId: Long, periodName: String)
    fun cancelVisible()
}

/**
 * Keeps exactly one pending one-shot alarm per enabled period and decides, when an alarm fires,
 * whether a notification may be posted.
 */
class ReminderCoordinator(
    private val store: MealSipStore,
    private val alarms: AlarmScheduler,
    private val notifier: ReminderNotifier,
    private val time: TimeSource,
) {
    /** Cancels every period alarm, then schedules the next occurrence of each enabled one. */
    suspend fun rescheduleAll() {
        val periods = store.periods()
        periods.forEach { alarms.cancel(it.id) }
        val now = time.now().atZone(time.zone())
        periods.filter { it.isActive && it.reminderEnabled && it.reminderMinute != null }
            .forEach { scheduleAfter(it, now) }
    }

    suspend fun handle(cb: ReminderCallback): ReminderDecision {
        val now = time.now().atZone(time.zone())
        val period = store.period(cb.periodId)
        val dayEntries = store.entriesOn(now.toLocalDate())
        val decision = ReminderPolicy.decide(
            cb = cb,
            period = period,
            now = now,
            canDeliver = notifier.canDeliver(),
            periodEntriesToday = dayEntries.count { it.periodId == cb.periodId },
            dayTotalMl = dayEntries.sumOf { it.amountMl.toLong() },
            goalMl = GoalLookup.goalOn(now.toLocalDate(), store.goals()),
        )
        if (decision == ReminderDecision.POST && period != null) notifier.post(period.id, period.name)

        // Re-plan this period from its CURRENT configuration (stale callbacks never re-arm themselves).
        alarms.cancel(cb.periodId)
        if (period != null && period.isActive && period.reminderEnabled && period.reminderMinute != null) {
            val intended = ReminderPolicy.intendedTime(cb, now)
            scheduleAfter(period, if (intended.isAfter(now)) intended else now)
        }
        return decision
    }

    private fun scheduleAfter(period: Period, after: ZonedDateTime) {
        val minute = period.reminderMinute ?: return
        val next = ReminderPolicy.nextOccurrence(after, minute)
        alarms.schedule(
            next.toInstant().toEpochMilli(),
            ReminderCallback(period.id, period.reminderGeneration, next.toLocalDate(), minute),
        )
    }
}
