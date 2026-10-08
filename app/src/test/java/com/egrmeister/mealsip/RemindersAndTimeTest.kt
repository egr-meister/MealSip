package com.egrmeister.mealsip

import com.egrmeister.mealsip.domain.goals.GoalStart
import com.egrmeister.mealsip.domain.logging.EntryDraft
import com.egrmeister.mealsip.domain.model.Outcome
import com.egrmeister.mealsip.domain.reminders.NotificationAccess
import com.egrmeister.mealsip.domain.reminders.ReminderCallback
import com.egrmeister.mealsip.domain.reminders.ReminderDecision
import com.egrmeister.mealsip.domain.reminders.ReminderPolicy
import com.egrmeister.mealsip.domain.time.LocalResolution
import com.egrmeister.mealsip.domain.time.TimeMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.egrmeister.mealsip.domain.time.TodayProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RemindersAndTimeTest {

    private fun enable(env: Env, name: String, minute: Int): Long {
        val id = env.periodId(name)
        assertTrue(blocking { env.periods.setReminder(id, true, minute) } is Outcome.Ok)
        blocking { env.reminders.rescheduleAll() }
        return id
    }

    private fun fire(env: Env, periodId: Long): ReminderDecision {
        val cb = env.alarms.pending[periodId]!!.second
        env.time.instant = java.time.Instant.ofEpochMilli(env.alarms.pending[periodId]!!.first)
        return blocking { env.reminders.handle(cb) }
    }

    @Test
    fun schedulesOnePendingAlarmPerEnabledPeriod() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 9, 0))
        val lunch = enable(env, "Lunch", 13 * 60)
        val dinner = enable(env, "Dinner", 19 * 60)
        assertEquals(setOf(lunch, dinner), env.alarms.pending.keys)
        val (at, cb) = env.alarms.pending[lunch]!!
        assertEquals(LocalDate.of(2026, 3, 10), cb.targetDate)
        assertEquals(ZonedDateTime.of(2026, 3, 10, 13, 0, 0, 0, env.time.zoneId).toInstant().toEpochMilli(), at)
        blocking { env.reminders.rescheduleAll() }
        assertEquals(2, env.alarms.pending.size)
    }

    @Test
    fun postsWithThePeriodNameAndReschedulesForTomorrow() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 7, 0))
        val breakfast = enable(env, "Breakfast", 8 * 60)
        assertEquals(ReminderDecision.POST, fire(env, breakfast))
        assertEquals(listOf(breakfast to "Breakfast"), env.notifier.posted)
        assertEquals(LocalDate.of(2026, 3, 11), env.alarms.pending[breakfast]!!.second.targetDate)
        // Nothing was logged automatically.
        assertTrue(env.store.entryRows.isEmpty())
    }

    @Test
    fun suppressedAfterWaterIsRecordedInThatPeriod() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 7, 0))
        val breakfast = enable(env, "Breakfast", 8 * 60)
        blocking { env.log.quickAdd(breakfast, 150) }
        assertEquals(ReminderDecision.SKIP_ALREADY_RECORDED, fire(env, breakfast))
        assertTrue(env.notifier.posted.isEmpty())
        assertTrue(env.alarms.pending.containsKey(breakfast)) // still planned for tomorrow
    }

    @Test
    fun anotherPeriodsEntryDoesNotSuppress() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 7, 0))
        val breakfast = enable(env, "Breakfast", 8 * 60)
        blocking { env.log.quickAdd(env.periodId("Lunch"), 150) }
        assertEquals(ReminderDecision.POST, fire(env, breakfast))
    }

    @Test
    fun suppressedWhenGoalReached() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 12, 0))
        blocking { env.goals.setGoal(500, GoalStart.TODAY) }
        val dinner = enable(env, "Dinner", 19 * 60)
        blocking { env.log.quickAdd(env.periodId("Lunch"), 500) }
        assertEquals(ReminderDecision.SKIP_GOAL_REACHED, fire(env, dinner))
    }

    @Test
    fun staleCallbacksAreDropped() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 7, 0))
        val lunch = enable(env, "Lunch", 13 * 60)
        val old = env.alarms.pending[lunch]!!.second
        // Time changed afterwards: the old callback no longer matches the configuration.
        blocking { env.periods.setReminder(lunch, true, 14 * 60) }
        env.time.setLocal(LocalDateTime.of(2026, 3, 10, 13, 0))
        assertEquals(ReminderDecision.SKIP_STALE_CONFIGURATION, blocking { env.reminders.handle(old) })
        assertTrue(env.notifier.posted.isEmpty())
        val next = env.alarms.pending[lunch]!!.second
        assertEquals(14 * 60, next.targetMinute)
        assertEquals(LocalDate.of(2026, 3, 10), next.targetDate)
    }

    @Test
    fun disabledAndArchivedPeriodsDoNotPost() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 7, 0))
        val lunch = enable(env, "Lunch", 13 * 60)
        val cb = env.alarms.pending[lunch]!!.second
        blocking { env.periods.archive(lunch) }
        env.time.setLocal(LocalDateTime.of(2026, 3, 10, 13, 0))
        assertEquals(ReminderDecision.SKIP_ARCHIVED, blocking { env.reminders.handle(cb) })
        assertFalse(env.alarms.pending.containsKey(lunch))
    }

    @Test
    fun permissionDenialSkipsButLoggingStillWorks() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 7, 0))
        env.notifier.deliverable = false
        val breakfast = enable(env, "Breakfast", 8 * 60)
        assertEquals(ReminderDecision.SKIP_NOT_DELIVERABLE, fire(env, breakfast))
        assertTrue(blocking { env.log.quickAdd(breakfast, 250) } is Outcome.Ok)
        val access = NotificationAccess(runtimePermissionRequired = true, permissionGranted = false, appNotificationsEnabled = true, channelEnabled = true)
        assertFalse(access.canDeliver)
        assertFalse(access.copy(permissionGranted = true, channelEnabled = false).canDeliver)
        assertTrue(access.copy(runtimePermissionRequired = false).canDeliver)
    }

    @Test
    fun lateAndOtherDayCallbacksAreSkippedWithoutBacklog() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 7, 0))
        val breakfast = enable(env, "Breakfast", 8 * 60)
        val cb = env.alarms.pending[breakfast]!!.second
        env.time.setLocal(LocalDateTime.of(2026, 3, 10, 9, 1)) // 61 minutes late
        assertEquals(ReminderDecision.SKIP_TOO_LATE, blocking { env.reminders.handle(cb) })
        env.time.setLocal(LocalDateTime.of(2026, 3, 11, 0, 30)) // the next day
        assertEquals(ReminderDecision.SKIP_OTHER_DAY, blocking { env.reminders.handle(cb) })
        assertTrue(env.notifier.posted.isEmpty())
        // Exactly one pending occurrence, for today's 08:00.
        assertEquals(LocalDate.of(2026, 3, 11), env.alarms.pending[breakfast]!!.second.targetDate)
        assertEquals(1, env.alarms.pending.size)
    }

    @Test
    fun sixtyMinutesLateStillPosts() {
        val period = com.egrmeister.mealsip.domain.model.Period(1, "Lunch", "sun", 0, null, true, 780, 3)
        val cb = ReminderCallback(1, 3, LocalDate.of(2026, 3, 10), 780)
        val now = ZonedDateTime.of(2026, 3, 10, 14, 0, 0, 0, ZoneId.of("UTC"))
        assertEquals(ReminderDecision.POST, ReminderPolicy.decide(cb, period, now, true, 0, 0, null))
        assertEquals(ReminderDecision.SKIP_TOO_LATE, ReminderPolicy.decide(cb, period, now.plusMinutes(1), true, 0, 0, null))
    }

    @Test
    fun timeZoneChangeKeepsRecordedLocalDatesAndMovesFutureReminders() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 23, 30), ZoneId.of("Europe/Minsk"))
        val evening = env.periodId("Evening")
        val e = (blocking { env.log.quickAdd(evening, 200) } as Outcome.Ok).value
        val dinner = enable(env, "Dinner", 19 * 60)
        // Travel west: in London it is still 20:30 on 10 March, but the entry keeps its recorded values.
        env.time.zoneId = ZoneId.of("Europe/London")
        blocking { env.reminders.rescheduleAll() }
        val stored = env.store.entryRows[e.id]!!
        assertEquals(LocalDate.of(2026, 3, 10), stored.localDate)
        assertEquals(LocalTime.of(23, 30), stored.localTime)
        assertEquals("Europe/Minsk", stored.zoneId)
        val (at, cb) = env.alarms.pending[dinner]!!
        assertEquals(LocalDate.of(2026, 3, 11), cb.targetDate)
        assertEquals(ZonedDateTime.of(2026, 3, 11, 19, 0, 0, 0, ZoneId.of("Europe/London")).toInstant().toEpochMilli(), at)
    }

    @Test
    fun daylightSavingGapIsRejectedAndOverlapIsConsistent() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(LocalResolution.Nonexistent, TimeMath.resolveLocal(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), berlin))
        val overlap = TimeMath.resolveLocal(LocalDate.of(2026, 10, 25), LocalTime.of(2, 30), berlin) as LocalResolution.Resolved
        assertTrue(overlap.ambiguous)
        assertEquals(7200, overlap.dateTime.offset.totalSeconds) // earlier instant (summer offset)

        val env = Env(LocalDateTime.of(2026, 3, 29, 12, 0), berlin)
        val r = blocking { env.log.addManual(EntryDraft("100", env.periodId("Breakfast"), env.today(), LocalTime.of(2, 30))) }
        assertTrue(r is Outcome.Invalid)
    }

    @Test
    fun midnightRefreshesTodayWithoutTouchingRecords() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 23, 59))
        blocking { env.log.quickAdd(env.periodId("Evening"), 300) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val provider = TodayProvider(env.time, scope)
            assertEquals(LocalDate.of(2026, 3, 10), provider.current())
            env.time.advanceMinutes(2)
            provider.refresh()
            assertEquals(LocalDate.of(2026, 3, 11), provider.current())
            assertEquals(1, env.store.entriesOnSync(LocalDate.of(2026, 3, 10)).size)
            assertTrue(env.store.entriesOnSync(LocalDate.of(2026, 3, 11)).isEmpty())
            assertEquals(60_000L, TimeMath.millisUntilNextMidnight(env.time.now().minusSeconds(120), env.time.zoneId))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun nextOccurrenceIsStrictlyAfter() {
        val z = ZoneId.of("UTC")
        val at8 = ZonedDateTime.of(2026, 3, 10, 8, 0, 0, 0, z)
        assertEquals(at8.plusDays(1), ReminderPolicy.nextOccurrence(at8, 480))
        assertEquals(at8, ReminderPolicy.nextOccurrence(at8.minusMinutes(1), 480))
    }
}
