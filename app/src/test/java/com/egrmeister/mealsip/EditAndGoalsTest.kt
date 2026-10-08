package com.egrmeister.mealsip

import com.egrmeister.mealsip.domain.goals.GoalLookup
import com.egrmeister.mealsip.domain.goals.GoalProgress
import com.egrmeister.mealsip.domain.goals.GoalStart
import com.egrmeister.mealsip.domain.history.BoardBuilder
import com.egrmeister.mealsip.domain.logging.EntryDraft
import com.egrmeister.mealsip.domain.model.Outcome
import com.egrmeister.mealsip.domain.model.PeriodIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class EditAndGoalsTest {

    private fun summary(env: Env, date: LocalDate) = blocking {
        BoardBuilder.summary(
            BoardBuilder.build(
                date, env.today(), env.store.entriesOn(date), env.store.periods(),
                env.store.snapshot(date), env.store.configVersions(), env.store.goals(),
            ),
        )
    }

    @Test
    fun editAmountAndPeriodUpdatesTotals() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 14, 0))
        val lunch = env.periodId("Lunch")
        val dinner = env.periodId("Dinner")
        val e = (blocking { env.log.quickAdd(lunch, 250) } as Outcome.Ok).value
        val r = blocking { env.log.edit(e.id, EntryDraft("400", dinner, e.localDate, e.localTime)) }
        assertTrue(r is Outcome.Ok)
        val s = summary(env, env.today())
        assertEquals(400L, s.totalMl)
        assertEquals(0L, s.breakdown.first { it.name == "Lunch" }.volumeMl)
        assertEquals(400L, s.breakdown.first { it.name == "Dinner" }.volumeMl)
        // Unchanged date and minute keep the exact original timestamp.
        assertEquals(e.timestamp, env.store.entryRows[e.id]!!.timestamp)
    }

    @Test
    fun movingToAnotherDateRequiresAPeriodAvailableThere() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 9, 0))
        val breakfast = env.periodId("Breakfast")
        val e = (blocking { env.log.quickAdd(breakfast, 300) } as Outcome.Ok).value
        val yesterday = env.today().minusDays(1)

        // A period created today did not exist on yesterday's layout.
        val brunch = (blocking { env.periods.create("Brunch", PeriodIcons.CUP) } as Outcome.Ok).value
        val wrong = blocking { env.log.edit(e.id, EntryDraft("300", brunch, yesterday, LocalTime.of(10, 0))) }
        assertTrue(wrong is Outcome.Invalid)
        val noPeriod = blocking { env.log.edit(e.id, EntryDraft("300", null, yesterday, LocalTime.of(10, 0))) }
        assertTrue(noPeriod is Outcome.Invalid)

        val ok = blocking { env.log.edit(e.id, EntryDraft("300", breakfast, yesterday, LocalTime.of(10, 0))) }
        assertTrue(ok is Outcome.Ok)
        assertEquals(0L, summary(env, env.today()).totalMl)
        assertEquals(300L, summary(env, yesterday).totalMl)
        assertEquals(yesterday, env.store.entryRows[e.id]!!.localDate)
        assertTrue(blocking { env.store.snapshot(yesterday) }.any { it.periodId == breakfast })
        assertFalse(blocking { env.store.snapshot(yesterday) }.any { it.periodId == brunch })
    }

    @Test
    fun editKeepsAnArchivedPeriodOnTheSameDateButNotWhenMoving() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 22, 0))
        val evening = env.periodId("Evening")
        val e = (blocking { env.log.quickAdd(evening, 200) } as Outcome.Ok).value
        blocking { env.periods.archive(evening) }
        val sameDay = blocking { env.log.edit(e.id, EntryDraft("250", evening, e.localDate, LocalTime.of(21, 0))) }
        assertTrue(sameDay is Outcome.Ok)
        val moved = blocking { env.log.edit(e.id, EntryDraft("250", evening, e.localDate.minusDays(1), LocalTime.of(21, 0))) }
        assertTrue(moved is Outcome.Invalid)
    }

    @Test
    fun goalPercentIncludesArchivedAndIsNotCapped() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 22, 0))
        blocking { env.goals.setGoal(1000, GoalStart.TODAY) }
        val evening = env.periodId("Evening")
        blocking { env.log.quickAdd(evening, 900) }
        blocking { env.periods.archive(evening) }
        blocking { env.log.quickAdd(env.periodId("Dinner"), 600) }
        val p = GoalProgress(summary(env, env.today()).totalMl, blocking { env.goals.goalOn(env.today()) })
        assertEquals(1500L, p.totalMl)
        assertEquals(150L, p.percent)
        assertTrue(p.reached)
        assertEquals(1f, p.lineFraction)
    }

    @Test
    fun noGoalMeansNoPercent() {
        val p = GoalProgress(700, null)
        assertNull(p.percent)
        assertFalse(p.reached)
        assertEquals(0f, p.lineFraction)
    }

    @Test
    fun goalChangesAreEffectiveDatedAndNeverRewriteEarlierDates() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 9, 0))
        val d10 = env.today()
        assertTrue(blocking { env.goals.setGoal(50, GoalStart.TODAY) } is Outcome.Invalid)
        assertTrue(blocking { env.goals.setGoal(10_001, GoalStart.TODAY) } is Outcome.Invalid)
        blocking { env.goals.setGoal(2000, GoalStart.TODAY) }
        env.time.setLocal(LocalDateTime.of(2026, 3, 12, 9, 0))
        blocking { env.goals.setGoal(2500, GoalStart.TOMORROW) }
        val goals = env.store.goalRows.values.toList()
        assertNull(GoalLookup.goalOn(d10.minusDays(1), goals)) // before any goal: volume only
        assertEquals(2000, GoalLookup.goalOn(d10, goals))
        assertEquals(2000, GoalLookup.goalOn(env.today(), goals))
        assertEquals(2500, GoalLookup.goalOn(env.today().plusDays(1), goals))
        assertEquals(2500, GoalLookup.pending(env.today(), goals)?.goalMl)
        // "Apply today" replaces the pending change for tomorrow.
        blocking { env.goals.setGoal(1800, GoalStart.TODAY) }
        val after = env.store.goalRows.values.toList()
        assertEquals(1800, GoalLookup.goalOn(env.today().plusDays(1), after))
        assertEquals(2000, GoalLookup.goalOn(d10, after))
    }

    @Test
    fun historySummaryShowsNoRecordsWithoutInventingIntake() {
        val env = Env()
        val s = summary(env, env.today().minusDays(2))
        assertFalse(s.hasRecords)
        assertEquals(0, s.entryCount)
        assertEquals(7, BoardBuilder.range(env.today()).size)
        assertEquals(env.today().minusDays(6), BoardBuilder.range(env.today()).last())
    }
}
