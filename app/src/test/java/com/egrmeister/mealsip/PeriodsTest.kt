package com.egrmeister.mealsip

import com.egrmeister.mealsip.domain.history.BoardBuilder
import com.egrmeister.mealsip.domain.history.DayBoard
import com.egrmeister.mealsip.domain.logging.EntryDraft
import com.egrmeister.mealsip.domain.model.Outcome
import com.egrmeister.mealsip.domain.model.PeriodIcons
import com.egrmeister.mealsip.domain.periods.LayoutCodec
import com.egrmeister.mealsip.domain.model.LayoutItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class PeriodsTest {

    private fun board(env: Env, date: LocalDate): DayBoard = blocking {
        BoardBuilder.build(
            date, env.today(), env.store.entriesOn(date), env.store.periods(),
            env.store.snapshot(date), env.store.configVersions(), env.store.goals(),
        )
    }

    @Test
    fun starterPeriodsHaveExampleTimesAndRemindersOff() {
        val env = Env()
        val periods = env.store.periodRows.values.sortedBy { it.displayOrder }
        assertEquals(listOf("Breakfast", "Lunch", "Dinner", "Evening"), periods.map { it.name })
        assertEquals(listOf(480, 780, 1140, 1260), periods.map { it.reminderMinute })
        assertTrue(periods.none { it.reminderEnabled })
        assertEquals(
            listOf(PeriodIcons.SUNRISE, PeriodIcons.SUN, PeriodIcons.SUNSET, PeriodIcons.MOON),
            periods.map { it.iconKey },
        )
    }

    @Test
    fun nameValidation() {
        val env = Env()
        assertTrue(blocking { env.periods.create("   ", PeriodIcons.CUP) } is Outcome.Invalid)
        assertTrue(blocking { env.periods.create("lunch", PeriodIcons.CUP) } is Outcome.Invalid) // duplicate, ignoring case
        assertTrue(blocking { env.periods.create("x".repeat(25), PeriodIcons.CUP) } is Outcome.Invalid)
        val ok = blocking { env.periods.create("  Late night  ", PeriodIcons.STAR) }
        assertTrue(ok is Outcome.Ok)
        assertEquals("Late night", env.store.periodRows[(ok as Outcome.Ok).value]!!.name)
    }

    @Test
    fun oneToEightActivePeriods() {
        val env = Env()
        (1..4).forEach { assertTrue(blocking { env.periods.create("Extra $it", PeriodIcons.CUP) } is Outcome.Ok) }
        assertTrue(blocking { env.periods.create("Ninth", PeriodIcons.CUP) } is Outcome.Invalid)
        val ids = env.store.periodRows.values.map { it.id }
        ids.dropLast(1).forEach { assertTrue(blocking { env.periods.archive(it) } is Outcome.Ok) }
        assertTrue(blocking { env.periods.archive(ids.last()) } is Outcome.Invalid)
        assertEquals(1, env.store.periodRows.values.count { it.isActive })
    }

    @Test
    fun renameTodayKeepsEarlierSnapshots() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 9, 0))
        val breakfast = env.periodId("Breakfast")
        blocking { env.log.quickAdd(breakfast, 300) }
        val day1 = env.today()

        env.time.setLocal(LocalDateTime.of(2026, 3, 11, 9, 0))
        blocking { env.periods.update(breakfast, "Morning", PeriodIcons.SUNRISE) }
        blocking { env.periods.move(breakfast, +1) }

        // Earlier date keeps its saved label and order.
        val past = board(env, day1)
        assertEquals("Breakfast", past.blocks.first().item.name)
        assertEquals(300L, past.blocks.first().volumeMl)
        // Today shows the new name and order, same stable ID.
        val today = board(env, env.today())
        assertEquals(listOf("Lunch", "Morning", "Dinner", "Evening"), today.blocks.map { it.item.name })
        assertEquals(breakfast, today.blocks[1].item.periodId)
    }

    @Test
    fun renameUpdatesTodaysExistingSnapshot() {
        val env = Env()
        val lunch = env.periodId("Lunch")
        blocking { env.log.quickAdd(lunch, 200) }
        blocking { env.periods.update(lunch, "Midday", PeriodIcons.SUN) }
        val snap = blocking { env.store.snapshot(env.today()) }
        assertEquals("Midday", snap.first { it.periodId == lunch }.name)
    }

    @Test
    fun datesWithoutRecordsUseTheConfigurationEffectiveThen() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 9, 0))
        val day1 = env.today()
        val dinner = env.periodId("Dinner")
        env.time.setLocal(LocalDateTime.of(2026, 3, 12, 9, 0))
        blocking { env.periods.update(dinner, "Supper", PeriodIcons.SUNSET) }
        val earlier = board(env, day1.plusDays(1)) // 11 March: no records, configuration from 10 March
        assertTrue(earlier.blocks.any { it.item.name == "Dinner" })
        assertTrue(board(env, env.today()).blocks.any { it.item.name == "Supper" })
        // Logging there freezes that configuration into the day's snapshot.
        blocking { env.log.addManual(EntryDraft("100", dinner, day1.plusDays(1), LocalTime.of(19, 0))) }
        val snap = blocking { env.store.snapshot(day1.plusDays(1)) }
        assertEquals("Dinner", snap.first { it.periodId == dinner }.name)
    }

    @Test
    fun archivingKeepsEntriesVisibleAndBlocksNewOnes() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 9, 0))
        val evening = env.periodId("Evening")
        blocking { env.log.addManual(EntryDraft("400", evening, env.today().minusDays(1), LocalTime.of(21, 0))) }
        blocking { env.log.quickAdd(evening, 250) }
        assertTrue(blocking { env.periods.archive(evening) } is Outcome.Ok)

        val today = board(env, env.today())
        assertFalse(today.blocks.any { it.item.periodId == evening })
        assertEquals(1, today.archivedBlocks.size)
        assertEquals(250L, today.archivedBlocks.single().volumeMl)
        assertEquals(250L, today.progress.totalMl)

        val yesterday = board(env, env.today().minusDays(1))
        assertEquals(400L, yesterday.blocks.first { it.item.periodId == evening }.volumeMl)

        assertTrue(blocking { env.log.quickAdd(evening, 100) } is Outcome.Invalid)
        assertTrue(
            blocking { env.log.addManual(EntryDraft("100", evening, env.today().minusDays(1), LocalTime.of(20, 0))) } is Outcome.Invalid,
        )
        assertEquals(2, env.store.entryRows.size)
        val archived = env.store.periodRows[evening]!!
        assertFalse(archived.reminderEnabled)
    }

    @Test
    fun reorderUsesMoveUpAndDown() {
        val env = Env()
        val evening = env.periodId("Evening")
        blocking { env.periods.move(evening, -1) }
        blocking { env.periods.move(evening, -1) }
        assertEquals(listOf("Breakfast", "Evening", "Lunch", "Dinner"), board(env, env.today()).blocks.map { it.item.name })
        assertTrue(blocking { env.periods.move(env.periodId("Breakfast"), -1) } is Outcome.Invalid)
    }

    @Test
    fun enabledRemindersNeedDistinctTimes() {
        val env = Env()
        assertTrue(blocking { env.periods.setReminder(env.periodId("Lunch"), true, 600) } is Outcome.Ok)
        assertTrue(blocking { env.periods.setReminder(env.periodId("Dinner"), true, 600) } is Outcome.Invalid)
        assertTrue(blocking { env.periods.setReminder(env.periodId("Dinner"), false, 600) } is Outcome.Ok)
        assertTrue(blocking { env.periods.setReminder(env.periodId("Dinner"), true, 24 * 60) } is Outcome.Invalid)
    }

    @Test
    fun layoutCodecRoundTripsAnyName() {
        val items = listOf(LayoutItem(3, "Tea; time\nnew", PeriodIcons.CUP, 2, false), LayoutItem(9, "Ночь", PeriodIcons.MOON, 0, true))
        assertEquals(items, LayoutCodec.decode(LayoutCodec.encode(items)))
    }
}
