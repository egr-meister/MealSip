package com.egrmeister.mealsip.domain.periods

import com.egrmeister.mealsip.domain.model.ConfigVersion
import com.egrmeister.mealsip.domain.model.LayoutItem
import com.egrmeister.mealsip.domain.model.Period
import com.egrmeister.mealsip.domain.model.PeriodIcons
import com.egrmeister.mealsip.domain.model.WaterEntry
import java.time.LocalDate
import java.util.Base64

/** Text encoding of a configuration version's layout (no reflection, no extra libraries). */
object LayoutCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(items: List<LayoutItem>): String = items.joinToString("\n") { item ->
        listOf(
            item.periodId.toString(),
            item.displayOrder.toString(),
            item.iconKey,
            if (item.archived) "1" else "0",
            encoder.encodeToString(item.name.toByteArray(Charsets.UTF_8)),
        ).joinToString(";")
    }

    fun decode(data: String): List<LayoutItem> = data.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split(';')
            if (parts.size != 5) return@mapNotNull null
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            val order = parts[1].toIntOrNull() ?: return@mapNotNull null
            val name = runCatching { String(decoder.decode(parts[4]), Charsets.UTF_8) }.getOrNull()
                ?: return@mapNotNull null
            LayoutItem(id, name, PeriodIcons.normalize(parts[2]), order, parts[3] == "1")
        }
        .toList()
}

/**
 * Pure rules for which periods appear on a given date's board.
 *
 *  * Today: the current active periods in their current order, plus archived periods that already
 *    hold entries today (shown in an archived section).
 *  * Earlier dates: the saved day snapshot. Without a snapshot (no records yet), the configuration
 *    version effective on that date.
 */
object LayoutResolver {

    fun activeItems(periods: List<Period>): List<LayoutItem> = periods
        .filter { it.isActive }
        .sortedWith(compareBy({ it.displayOrder }, { it.id }))
        .map { it.toItem(archived = false) }

    fun todayLayout(periods: List<Period>, todayEntries: List<WaterEntry>): List<LayoutItem> {
        val usedIds = todayEntries.map { it.periodId }.toSet()
        val archivedWithEntries = periods
            .filter { !it.isActive && it.id in usedIds }
            .sortedWith(compareBy({ it.displayOrder }, { it.id }))
            .map { it.toItem(archived = true) }
        return activeItems(periods) + archivedWithEntries
    }

    /** The configuration effective on [date]: the latest version on or before it, else the earliest. */
    fun configEffectiveOn(date: LocalDate, versions: List<ConfigVersion>, periods: List<Period>): List<LayoutItem> {
        if (versions.isEmpty()) return activeItems(periods)
        val sorted = versions.sortedBy { it.effectiveDate }
        val version = sorted.lastOrNull { !it.effectiveDate.isAfter(date) } ?: sorted.first()
        return version.items.sortedWith(compareBy({ it.displayOrder }, { it.periodId }))
    }

    fun layoutFor(
        date: LocalDate,
        today: LocalDate,
        periods: List<Period>,
        snapshot: List<LayoutItem>,
        versions: List<ConfigVersion>,
        entriesOnDate: List<WaterEntry>,
    ): List<LayoutItem> {
        val base = when {
            date == today -> todayLayout(periods, entriesOnDate)
            snapshot.isNotEmpty() -> snapshot.sortedWith(compareBy({ it.archived }, { it.displayOrder }, { it.periodId }))
            else -> configEffectiveOn(date, versions, periods)
        }
        // Defensive: an entry whose period is missing from the layout is still shown (never hidden or moved).
        val known = base.map { it.periodId }.toSet()
        val orphans = entriesOnDate.map { it.periodId }.distinct().filter { it !in known }.map { id ->
            val p = periods.firstOrNull { it.id == id }
            LayoutItem(id, p?.name ?: "Removed period", p?.iconKey ?: PeriodIcons.CLOCK, p?.displayOrder ?: Int.MAX_VALUE, archived = true)
        }
        return base + orphans
    }

    /**
     * Periods of a date's layout that may receive NEW or MOVED entries. Periods archived in the
     * layout or archived now are excluded, except [keepPeriodId] (an entry's existing period when
     * its date is unchanged).
     */
    fun selectable(layout: List<LayoutItem>, periods: List<Period>, keepPeriodId: Long? = null): List<LayoutItem> {
        val activeIds = periods.filter { it.isActive }.map { it.id }.toSet()
        return layout.filter { (!it.archived && it.periodId in activeIds) || it.periodId == keepPeriodId }
    }

    private fun Period.toItem(archived: Boolean) = LayoutItem(id, name, iconKey, displayOrder, archived)
}
