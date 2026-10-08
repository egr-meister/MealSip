package com.egrmeister.mealsip

import com.egrmeister.mealsip.domain.history.BoardBuilder
import com.egrmeister.mealsip.domain.logging.ActionGuard
import com.egrmeister.mealsip.domain.logging.EntryDraft
import com.egrmeister.mealsip.domain.logging.EntryValidation
import com.egrmeister.mealsip.domain.logging.UndoAction
import com.egrmeister.mealsip.domain.logging.UndoCenter
import com.egrmeister.mealsip.domain.logging.UndoExecutor
import com.egrmeister.mealsip.domain.model.Outcome
import com.egrmeister.mealsip.domain.model.WaterEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class LoggingTest {

    @Test
    fun quickAddRecordsInChosenPeriodAtCurrentTime() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 21, 30, 15))
        val dinner = env.periodId("Dinner")
        val r = blocking { env.log.quickAdd(dinner, 250) }
        val entry = (r as Outcome.Ok).value
        assertEquals(dinner, entry.periodId)
        assertEquals(250, entry.amountMl)
        assertEquals(env.today(), entry.localDate)
        assertEquals(LocalTime.of(21, 30, 15), entry.localTime)
        assertEquals("Europe/Minsk", entry.zoneId)
        assertEquals(env.time.now().toEpochMilli(), entry.timestamp)
        // Period assignment is explicit: 21:30 is NOT re-assigned to "Evening".
        assertEquals(0, env.store.entriesOnSync(env.today()).count { it.periodId == env.periodId("Evening") })
    }

    @Test
    fun oneEntryDoesNotMarkAnotherPeriod() {
        val env = Env()
        blocking { env.log.quickAdd(env.periodId("Breakfast"), 500) }
        val board = blocking { boardFor(env) }
        val breakfast = board.blocks.first { it.item.name == "Breakfast" }
        val lunch = board.blocks.first { it.item.name == "Lunch" }
        assertEquals(500L, breakfast.volumeMl)
        assertEquals(1, breakfast.count)
        assertEquals(0L, lunch.volumeMl)
        assertEquals(0, lunch.count)
    }

    @Test
    fun actionGuardBlocksDuplicateActivationButAllowsDeliberateRepeat() {
        var now = 1_000L
        val guard = ActionGuard(clockMs = { now }, windowMs = 700)
        assertTrue(guard.tryAcquire("quick:1:250"))
        // Same activation delivered twice while still in flight.
        assertFalse(guard.tryAcquire("quick:1:250"))
        guard.release("quick:1:250")
        now += 100
        // A bounce right after completion is still the same activation.
        assertFalse(guard.tryAcquire("quick:1:250"))
        // A different button is independent.
        assertTrue(guard.tryAcquire("quick:1:500"))
        now += 1_000
        // A deliberate second tap later records a separate entry.
        assertTrue(guard.tryAcquire("quick:1:250"))
    }

    @Test
    fun separateIntentionalEntriesAreKept() {
        val env = Env()
        val lunch = env.periodId("Lunch")
        blocking { env.log.quickAdd(lunch, 250) }
        env.time.advanceMinutes(1)
        blocking { env.log.quickAdd(lunch, 250) }
        assertEquals(2, env.store.entriesOnSync(env.today()).size)
    }

    @Test
    fun amountValidation() {
        assertTrue(EntryValidation.parseAmount("1") is Outcome.Ok)
        assertTrue(EntryValidation.parseAmount("5000") is Outcome.Ok)
        assertTrue(EntryValidation.parseAmount("0") is Outcome.Invalid)
        assertTrue(EntryValidation.parseAmount("5001") is Outcome.Invalid)
        assertTrue(EntryValidation.parseAmount("12.5") is Outcome.Invalid)
        assertTrue(EntryValidation.parseAmount("") is Outcome.Invalid)
        assertTrue(EntryValidation.parseAmount("99999999999") is Outcome.Invalid)
        assertEquals(250, (EntryValidation.parseAmount(" 0250 ") as Outcome.Ok).value)
    }

    @Test
    fun manualEntryRejectsFutureDateAndTime() {
        val env = Env(LocalDateTime.of(2026, 3, 10, 9, 0))
        val lunch = env.periodId("Lunch")
        val futureDate = blocking { env.log.addManual(EntryDraft("200", lunch, env.today().plusDays(1), LocalTime.of(8, 0))) }
        assertTrue(futureDate is Outcome.Invalid)
        val futureTime = blocking { env.log.addManual(EntryDraft("200", lunch, env.today(), LocalTime.of(9, 1))) }
        assertTrue(futureTime is Outcome.Invalid)
        val ok = blocking { env.log.addManual(EntryDraft("200", lunch, env.today(), LocalTime.of(9, 0))) }
        assertTrue(ok is Outcome.Ok)
        val past = blocking { env.log.addManual(EntryDraft("300", lunch, env.today().minusDays(3), LocalTime.of(23, 59))) }
        assertTrue(past is Outcome.Ok)
    }

    @Test
    fun manualEntryRequiresAPeriod() {
        val env = Env()
        val r = blocking { env.log.addManual(EntryDraft("200", null, env.today(), LocalTime.of(8, 0))) }
        assertTrue(r is Outcome.Invalid)
    }

    @Test
    fun undoAddRemovesEntryExactlyOnce() {
        val env = Env()
        val center = UndoCenter()
        val executor = UndoExecutor(env.log)
        val entry = (blocking { env.log.quickAdd(env.periodId("Breakfast"), 150) } as Outcome.Ok).value
        val action = UndoAction.Added(center.nextToken(), "added", entry.id)
        center.offer(action)
        val first = center.consume(action.token)
        assertNotNull(first)
        assertTrue(blocking { executor.perform(first!!) })
        assertNull(center.consume(action.token))
        assertTrue(env.store.entriesOnSync(env.today()).isEmpty())
    }

    @Test
    fun deleteAndUndoRestoresTheExactEntryOnce() {
        val env = Env()
        val center = UndoCenter()
        val executor = UndoExecutor(env.log)
        val original = (blocking { env.log.quickAdd(env.periodId("Lunch"), 333) } as Outcome.Ok).value
        val removed: WaterEntry = blocking { env.log.delete(original.id) }!!
        assertTrue(env.store.entriesOnSync(env.today()).isEmpty())
        val action = UndoAction.Deleted(center.nextToken(), "deleted", removed)
        center.offer(action)
        assertTrue(blocking { executor.perform(center.consume(action.token)!!) })
        assertEquals(listOf(original), env.store.entriesOnSync(env.today()))
        // A second Undo for the same token does nothing, and restoring again is refused.
        assertNull(center.consume(action.token))
        assertFalse(blocking { env.log.restore(removed) })
        assertEquals(1, env.store.entriesOnSync(env.today()).size)
    }

    private suspend fun boardFor(env: Env) = BoardBuilder.build(
        env.today(), env.today(), env.store.entriesOn(env.today()), env.store.periods(),
        env.store.snapshot(env.today()), env.store.configVersions(), env.store.goals(),
    )
}
