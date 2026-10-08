package com.egrmeister.mealsip.domain.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * A named daily period (Breakfast, Lunch, …). Periods are organizational labels only:
 * they never imply that a meal happened and never cover an automatic time range.
 */
data class Period(
    val id: Long,
    val name: String,
    val iconKey: String,
    val displayOrder: Int,
    /** Epoch millis when the period was archived, or null while active. */
    val archivedAt: Long? = null,
    val reminderEnabled: Boolean = false,
    /** Local minute of day (0..1439) for the optional reminder. */
    val reminderMinute: Int? = null,
    /** Incremented on every reminder change so stale alarm callbacks can be recognised. */
    val reminderGeneration: Int = 0,
) {
    val isActive: Boolean get() = archivedAt == null
}

/** One period as it appears on a specific day's board (a snapshot or a configuration item). */
data class LayoutItem(
    val periodId: Long,
    val name: String,
    val iconKey: String,
    val displayOrder: Int,
    val archived: Boolean = false,
)

/** A configuration version: the active period layout effective from [effectiveDate]. */
data class ConfigVersion(
    val id: Long,
    val effectiveDate: LocalDate,
    val items: List<LayoutItem>,
)

/**
 * A water record. [timestamp] is the absolute instant; [localDate]/[localTime]/[zoneId] keep the
 * local values recorded at logging time so historical grouping never shifts after a zone change.
 */
data class WaterEntry(
    val id: Long = 0,
    val amountMl: Int,
    val periodId: Long,
    val timestamp: Long,
    val localDate: LocalDate,
    val localTime: LocalTime,
    val zoneId: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** An effective-dated daily goal. A goal applies from [effectiveDate] until the next change. */
data class GoalChange(
    val effectiveDate: LocalDate,
    val goalMl: Int,
    val createdAt: Long,
)

/** Result of a validated domain operation. */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Invalid(val message: String) : Outcome<Nothing>
}

object Limits {
    const val MIN_AMOUNT_ML = 1
    const val MAX_AMOUNT_ML = 5_000
    const val MIN_GOAL_ML = 100
    const val MAX_GOAL_ML = 10_000
    const val MIN_ACTIVE_PERIODS = 1
    const val MAX_ACTIVE_PERIODS = 8
    const val MAX_NAME_LENGTH = 24
}
