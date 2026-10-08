package com.egrmeister.mealsip.domain.periods

import com.egrmeister.mealsip.domain.model.LayoutItem
import com.egrmeister.mealsip.domain.model.Limits
import com.egrmeister.mealsip.domain.model.MealSipStore
import com.egrmeister.mealsip.domain.model.Outcome
import com.egrmeister.mealsip.domain.model.Period
import com.egrmeister.mealsip.domain.model.PeriodIcons
import com.egrmeister.mealsip.domain.model.StarterPeriods
import com.egrmeister.mealsip.domain.time.TimeSource
import java.time.LocalDate

object PeriodValidation {
    fun normalizeName(raw: String): String = raw.trim()

    /** Returns an error message or null. Duplicates are checked case-insensitively among active periods. */
    fun nameError(raw: String, periods: List<Period>, excludeId: Long? = null): String? {
        val name = normalizeName(raw)
        if (name.isEmpty()) return "Enter a period name."
        if (name.length > Limits.MAX_NAME_LENGTH) return "Use at most ${Limits.MAX_NAME_LENGTH} characters."
        val clash = periods.any { it.isActive && it.id != excludeId && it.name.equals(name, ignoreCase = true) }
        return if (clash) "Another period already uses this name." else null
    }

    fun reminderError(minute: Int?, enabled: Boolean, periodId: Long, periods: List<Period>): String? {
        if (minute == null) return if (enabled) "Choose a reminder time." else null
        if (minute !in 0 until 24 * 60) return "Choose a valid time."
        if (!enabled) return null
        val clash = periods.any {
            it.isActive && it.id != periodId && it.reminderEnabled && it.reminderMinute == minute
        }
        return if (clash) "Another period already has a reminder at this time." else null
    }
}

/**
 * Period configuration and historical snapshots.
 *
 * Name, icon and order changes apply from today: today's board (and today's snapshot, if any) follows
 * the current configuration, while snapshots of earlier dates are never rewritten. A configuration
 * version keyed by date records the active layout so dates without records can be shown as they were.
 */
class PeriodService(
    private val store: MealSipStore,
    private val time: TimeSource,
) {
    suspend fun seedIfEmpty() {
        store.inTransaction {
            if (store.periods().isNotEmpty()) return@inTransaction
            StarterPeriods.ALL.forEachIndexed { index, starter ->
                store.insertPeriod(
                    Period(
                        id = 0,
                        name = starter.name,
                        iconKey = starter.iconKey,
                        displayOrder = index,
                        reminderEnabled = false,
                        reminderMinute = starter.reminderMinute,
                    ),
                )
            }
            // Baseline: dates before the first configuration change show the starter layout.
            store.upsertConfigVersion(BASELINE_DATE, LayoutResolver.activeItems(store.periods()))
            recordConfiguration()
        }
    }

    suspend fun create(rawName: String, iconKey: String): Outcome<Long> = store.inTransaction {
        val periods = store.periods()
        val active = periods.filter { it.isActive }
        if (active.size >= Limits.MAX_ACTIVE_PERIODS) {
            return@inTransaction Outcome.Invalid("You can have up to ${Limits.MAX_ACTIVE_PERIODS} active periods.")
        }
        PeriodValidation.nameError(rawName, periods)?.let { return@inTransaction Outcome.Invalid(it) }
        val order = (periods.maxOfOrNull { it.displayOrder } ?: -1) + 1
        val id = store.insertPeriod(
            Period(0, PeriodValidation.normalizeName(rawName), PeriodIcons.normalize(iconKey), order),
        )
        recordConfiguration()
        Outcome.Ok(id)
    }

    suspend fun update(id: Long, rawName: String, iconKey: String): Outcome<Unit> = store.inTransaction {
        val periods = store.periods()
        val period = periods.firstOrNull { it.id == id && it.isActive }
            ?: return@inTransaction Outcome.Invalid("This period is no longer active.")
        PeriodValidation.nameError(rawName, periods, excludeId = id)?.let { return@inTransaction Outcome.Invalid(it) }
        store.updatePeriod(period.copy(name = PeriodValidation.normalizeName(rawName), iconKey = PeriodIcons.normalize(iconKey)))
        recordConfiguration()
        Outcome.Ok(Unit)
    }

    /** Moves an active period one step up (-1) or down (+1) in the active order. */
    suspend fun move(id: Long, delta: Int): Outcome<Unit> = store.inTransaction {
        val active = store.periods().filter { it.isActive }.sortedWith(compareBy({ it.displayOrder }, { it.id }))
        val index = active.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in active.indices) return@inTransaction Outcome.Invalid("This period cannot move further.")
        val reordered = active.toMutableList()
        val moved = reordered.removeAt(index)
        reordered.add(target, moved)
        reordered.forEachIndexed { order, p -> if (p.displayOrder != order) store.updatePeriod(p.copy(displayOrder = order)) }
        recordConfiguration()
        Outcome.Ok(Unit)
    }

    /**
     * Archives a period: no new entries, reminder disabled (generation bumped so a pending callback
     * is stale), historical entries and snapshots kept. At least one active period must remain.
     */
    suspend fun archive(id: Long): Outcome<Unit> = store.inTransaction {
        val periods = store.periods()
        val period = periods.firstOrNull { it.id == id && it.isActive }
            ?: return@inTransaction Outcome.Invalid("This period is already archived.")
        if (periods.count { it.isActive } <= Limits.MIN_ACTIVE_PERIODS) {
            return@inTransaction Outcome.Invalid("At least one active period is required.")
        }
        store.updatePeriod(
            period.copy(
                archivedAt = time.now().toEpochMilli(),
                reminderEnabled = false,
                reminderGeneration = period.reminderGeneration + 1,
            ),
        )
        recordConfiguration()
        Outcome.Ok(Unit)
    }

    suspend fun setReminder(id: Long, enabled: Boolean, minute: Int?): Outcome<Unit> = store.inTransaction {
        val periods = store.periods()
        val period = periods.firstOrNull { it.id == id && it.isActive }
            ?: return@inTransaction Outcome.Invalid("This period is no longer active.")
        PeriodValidation.reminderError(minute, enabled, id, periods)?.let { return@inTransaction Outcome.Invalid(it) }
        store.updatePeriod(
            period.copy(
                reminderEnabled = enabled,
                reminderMinute = minute,
                reminderGeneration = period.reminderGeneration + 1,
            ),
        )
        Outcome.Ok(Unit)
    }

    /** Layout of any date, derived from stored data (see [LayoutResolver]). */
    suspend fun layoutFor(date: LocalDate): List<LayoutItem> {
        val today = time.today()
        return LayoutResolver.layoutFor(
            date = date,
            today = today,
            periods = store.periods(),
            snapshot = if (date == today) emptyList() else store.snapshot(date),
            versions = store.configVersions(),
            entriesOnDate = store.entriesOn(date),
        )
    }

    suspend fun selectableFor(date: LocalDate, keepPeriodId: Long? = null): List<LayoutItem> =
        LayoutResolver.selectable(layoutFor(date), store.periods(), keepPeriodId)

    /**
     * Ensures [date] has a stored snapshot (call inside a transaction before writing an entry there)
     * and that [periodId] is part of it.
     */
    suspend fun ensureSnapshot(date: LocalDate, periodId: Long) {
        val today = time.today()
        val existing = if (date == today) emptyList() else store.snapshot(date)
        var items = if (date == today) {
            LayoutResolver.todayLayout(store.periods(), store.entriesOn(date))
        } else if (existing.isNotEmpty()) {
            existing
        } else {
            LayoutResolver.configEffectiveOn(date, store.configVersions(), store.periods())
        }
        if (items.none { it.periodId == periodId }) {
            val p = store.period(periodId)
            if (p != null) items = items + LayoutItem(p.id, p.name, p.iconKey, p.displayOrder, archived = !p.isActive)
        }
        if (items != existing) store.replaceSnapshot(date, items)
    }

    companion object {
        /** Effective date of the starter configuration (applies to every date before the first change). */
        val BASELINE_DATE: LocalDate = LocalDate.of(1970, 1, 1)
    }

    /** Records today's configuration version and refreshes today's snapshot if one exists. */
    private suspend fun recordConfiguration() {
        val today = time.today()
        val periods = store.periods()
        store.upsertConfigVersion(today, LayoutResolver.activeItems(periods))
        if (store.snapshot(today).isNotEmpty()) {
            store.replaceSnapshot(today, LayoutResolver.todayLayout(periods, store.entriesOn(today)))
        }
    }
}
