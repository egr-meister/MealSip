package com.egrmeister.mealsip.domain.history

import com.egrmeister.mealsip.domain.goals.GoalLookup
import com.egrmeister.mealsip.domain.goals.GoalProgress
import com.egrmeister.mealsip.domain.model.ConfigVersion
import com.egrmeister.mealsip.domain.model.GoalChange
import com.egrmeister.mealsip.domain.model.LayoutItem
import com.egrmeister.mealsip.domain.model.Period
import com.egrmeister.mealsip.domain.model.WaterEntry
import com.egrmeister.mealsip.domain.periods.LayoutResolver
import java.time.LocalDate

/** One period block on a day board. */
data class PeriodBlock(
    val item: LayoutItem,
    val entries: List<WaterEntry>,
    /** Reminder minute of the current period configuration (shown for today only). */
    val reminderMinute: Int?,
    /** Whether new entries may be added here (active, not archived). */
    val canLog: Boolean,
) {
    /** Totals are derived from entries using Long. */
    val volumeMl: Long get() = entries.sumOf { it.amountMl.toLong() }
    val count: Int get() = entries.size
}

data class DayBoard(
    val date: LocalDate,
    val isToday: Boolean,
    val progress: GoalProgress,
    val blocks: List<PeriodBlock>,
    val archivedBlocks: List<PeriodBlock>,
) {
    val entryCount: Int get() = (blocks + archivedBlocks).sumOf { it.count }
}

data class PeriodShare(val name: String, val iconKey: String, val volumeMl: Long, val count: Int, val archived: Boolean)

data class DaySummary(
    val date: LocalDate,
    val totalMl: Long,
    val goalMl: Int?,
    val entryCount: Int,
    val breakdown: List<PeriodShare>,
) {
    val hasRecords: Boolean get() = entryCount > 0
}

object BoardBuilder {

    fun build(
        date: LocalDate,
        today: LocalDate,
        entries: List<WaterEntry>,
        periods: List<Period>,
        snapshot: List<LayoutItem>,
        versions: List<ConfigVersion>,
        goals: List<GoalChange>,
    ): DayBoard {
        val dayEntries = entries.filter { it.localDate == date }
            .sortedWith(compareBy({ it.localTime }, { it.timestamp }, { it.id }))
        val layout = LayoutResolver.layoutFor(date, today, periods, snapshot, versions, dayEntries)
        val selectable = LayoutResolver.selectable(layout, periods).map { it.periodId }.toSet()
        val byPeriod = dayEntries.groupBy { it.periodId }
        val isToday = date == today
        val blocks = layout.map { item ->
            val current = periods.firstOrNull { it.id == item.periodId }
            PeriodBlock(
                item = item,
                entries = byPeriod[item.periodId].orEmpty(),
                reminderMinute = if (isToday && current != null && current.isActive && current.reminderEnabled) current.reminderMinute else null,
                canLog = item.periodId in selectable,
            )
        }
        val (archived, main) = blocks.partition { it.item.archived }
        val total = dayEntries.sumOf { it.amountMl.toLong() }
        return DayBoard(
            date = date,
            isToday = isToday,
            progress = GoalProgress(total, GoalLookup.goalOn(date, goals)),
            blocks = main,
            // Archived periods appear only when they still hold entries on this date.
            archivedBlocks = archived.filter { it.count > 0 },
        )
    }

    fun summary(board: DayBoard): DaySummary = DaySummary(
        date = board.date,
        totalMl = board.progress.totalMl,
        goalMl = board.progress.goalMl,
        entryCount = board.entryCount,
        breakdown = (board.blocks + board.archivedBlocks).map {
            PeriodShare(it.item.name, it.item.iconKey, it.volumeMl, it.count, it.item.archived)
        },
    )

    /** Seven consecutive dates ending at [end] (newest first). */
    fun range(end: LocalDate, days: Int = 7): List<LocalDate> = (0 until days).map { end.minusDays(it.toLong()) }
}
