package com.egrmeister.mealsip.domain.logging

import com.egrmeister.mealsip.domain.model.Limits
import com.egrmeister.mealsip.domain.model.MealSipStore
import com.egrmeister.mealsip.domain.model.Outcome
import com.egrmeister.mealsip.domain.model.WaterEntry
import com.egrmeister.mealsip.domain.periods.PeriodService
import com.egrmeister.mealsip.domain.time.LocalResolution
import com.egrmeister.mealsip.domain.time.TimeMath
import com.egrmeister.mealsip.domain.time.TimeSource
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A manual entry or an edit, as typed by the user. */
data class EntryDraft(
    val amountText: String,
    val periodId: Long?,
    val date: LocalDate,
    val time: LocalTime,
)

object EntryValidation {
    /** Technical input limits (1–5,000 whole mL) — not consumption guidance. */
    fun parseAmount(text: String): Outcome<Int> {
        val t = text.trim()
        if (t.isEmpty()) return Outcome.Invalid("Enter an amount in mL.")
        if (!t.all { it in '0'..'9' }) return Outcome.Invalid("Use whole milliliters only.")
        val value = t.trimStart('0').ifEmpty { "0" }
        val ml = if (value.length > 5) null else value.toIntOrNull()
        if (ml == null || ml < Limits.MIN_AMOUNT_ML || ml > Limits.MAX_AMOUNT_ML) {
            return Outcome.Invalid("Enter between ${Limits.MIN_AMOUNT_ML} and 5,000 mL.")
        }
        return Outcome.Ok(ml)
    }

    fun validAmount(ml: Int): Boolean = ml in Limits.MIN_AMOUNT_ML..Limits.MAX_AMOUNT_ML
}

/**
 * Prevents one activation (a double tap, a repeated click event) from being processed twice,
 * while a deliberate second tap after [windowMs] records a separate entry.
 */
class ActionGuard(private val clockMs: () -> Long, private val windowMs: Long = 700) {
    private val inFlight = HashSet<String>()
    private val lastAccepted = HashMap<String, Long>()

    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clockMs()
        if (key in inFlight) return false
        val last = lastAccepted[key]
        if (last != null && now - last < windowMs) return false
        inFlight += key
        lastAccepted[key] = now
        return true
    }

    @Synchronized
    fun release(key: String) {
        inFlight -= key
    }
}

/**
 * Water logging, editing, deletion and undo. Every write runs in a transaction that also creates
 * the day's period snapshot when the date receives its first record.
 */
class WaterLogService(
    private val store: MealSipStore,
    private val periods: PeriodService,
    private val time: TimeSource,
) {
    /** Quick amount: records [amountMl] in [periodId] today at the current time. */
    suspend fun quickAdd(periodId: Long, amountMl: Int): Outcome<WaterEntry> = store.inTransaction {
        if (!EntryValidation.validAmount(amountMl)) return@inTransaction Outcome.Invalid("Enter between 1 and 5,000 mL.")
        val now = time.now()
        val zone = time.zone()
        val local = now.atZone(zone)
        val date = local.toLocalDate()
        if (periods.selectableFor(date).none { it.periodId == periodId }) {
            return@inTransaction Outcome.Invalid("This period is not available for logging.")
        }
        val stamp = now.toEpochMilli()
        val entry = WaterEntry(
            amountMl = amountMl,
            periodId = periodId,
            timestamp = stamp,
            localDate = date,
            localTime = local.toLocalTime().truncatedTo(ChronoUnit.SECONDS),
            zoneId = zone.id,
            createdAt = stamp,
            updatedAt = stamp,
        )
        periods.ensureSnapshot(date, periodId)
        val id = store.insertEntry(entry)
        Outcome.Ok(entry.copy(id = id))
    }

    suspend fun addManual(draft: EntryDraft): Outcome<WaterEntry> = store.inTransaction {
        val zone = time.zone()
        val checked = validateDraft(draft, zone, keepPeriodId = null)
        if (checked !is Outcome.Ok) return@inTransaction checked as Outcome.Invalid
        val (amount, periodId, stamp) = checked.value
        val nowMs = time.now().toEpochMilli()
        val entry = WaterEntry(
            amountMl = amount,
            periodId = periodId,
            timestamp = stamp,
            localDate = draft.date,
            localTime = draft.time.truncatedTo(ChronoUnit.MINUTES),
            zoneId = zone.id,
            createdAt = nowMs,
            updatedAt = nowMs,
        )
        periods.ensureSnapshot(draft.date, periodId)
        val id = store.insertEntry(entry)
        Outcome.Ok(entry.copy(id = id))
    }

    /**
     * Edits amount, period, date and time. Moving to another date requires an explicit period that is
     * available on the destination date; names are never matched. The entry's recorded zone is kept.
     */
    suspend fun edit(id: Long, draft: EntryDraft): Outcome<WaterEntry> = store.inTransaction {
        val original = store.entry(id) ?: return@inTransaction Outcome.Invalid("This entry no longer exists.")
        val zone = runCatching { ZoneId.of(original.zoneId) }.getOrDefault(time.zone())
        val keep = if (draft.date == original.localDate) original.periodId else null
        val checked = validateDraft(draft, zone, keepPeriodId = keep)
        if (checked !is Outcome.Ok) return@inTransaction checked as Outcome.Invalid
        val (amount, periodId, stamp) = checked.value
        val sameMoment = draft.date == original.localDate &&
            draft.time.truncatedTo(ChronoUnit.MINUTES) == original.localTime.truncatedTo(ChronoUnit.MINUTES)
        val finalEntry = original.copy(
            amountMl = amount,
            periodId = periodId,
            // Unchanged date and minute keep the exact original instant and seconds.
            timestamp = if (sameMoment) original.timestamp else stamp,
            localDate = draft.date,
            localTime = if (sameMoment) original.localTime else draft.time.truncatedTo(ChronoUnit.MINUTES),
            updatedAt = time.now().toEpochMilli(),
        )
        periods.ensureSnapshot(draft.date, periodId)
        if (store.updateEntry(finalEntry) == 0) return@inTransaction Outcome.Invalid("This entry no longer exists.")
        Outcome.Ok(finalEntry)
    }

    /** Deletes an entry and returns the exact removed record for Undo. */
    suspend fun delete(id: Long): WaterEntry? = store.inTransaction {
        val entry = store.entry(id) ?: return@inTransaction null
        if (store.deleteEntry(id) == 0) null else entry
    }

    /** Restores a deleted entry exactly (same ID and values). */
    suspend fun restore(entry: WaterEntry): Boolean = store.inTransaction {
        if (store.entry(entry.id) != null) return@inTransaction false
        if (store.period(entry.periodId) == null) return@inTransaction false
        periods.ensureSnapshot(entry.localDate, entry.periodId)
        store.insertEntry(entry)
        true
    }

    private data class Checked(val amount: Int, val periodId: Long, val timestamp: Long)

    private suspend fun validateDraft(draft: EntryDraft, zone: ZoneId, keepPeriodId: Long?): Outcome<Checked> {
        val amount = when (val a = EntryValidation.parseAmount(draft.amountText)) {
            is Outcome.Ok -> a.value
            is Outcome.Invalid -> return a
        }
        val now = time.now()
        val today = now.atZone(time.zone()).toLocalDate()
        if (draft.date.isAfter(today)) return Outcome.Invalid("Choose today or a past date.")
        val periodId = draft.periodId ?: return Outcome.Invalid("Choose a period.")
        if (periods.selectableFor(draft.date, keepPeriodId).none { it.periodId == periodId }) {
            return Outcome.Invalid("Choose a period available on this date.")
        }
        val resolved = when (val r = TimeMath.resolveLocal(draft.date, draft.time.truncatedTo(ChronoUnit.MINUTES), zone)) {
            is LocalResolution.Resolved -> r.dateTime
            LocalResolution.Nonexistent -> return Outcome.Invalid("This time does not exist on that date (clock change).")
        }
        val stamp = resolved.toInstant().toEpochMilli()
        if (stamp > now.toEpochMilli()) return Outcome.Invalid("Future times cannot be recorded.")
        return Outcome.Ok(Checked(amount, periodId, stamp))
    }
}
