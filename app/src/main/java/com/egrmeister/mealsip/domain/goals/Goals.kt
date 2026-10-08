package com.egrmeister.mealsip.domain.goals

import com.egrmeister.mealsip.domain.model.GoalChange
import com.egrmeister.mealsip.domain.model.Limits
import com.egrmeister.mealsip.domain.model.MealSipStore
import com.egrmeister.mealsip.domain.model.Outcome
import com.egrmeister.mealsip.domain.time.TimeSource
import java.time.LocalDate

enum class GoalStart { TODAY, TOMORROW }

/** Progress toward the user's selected goal. Percent is not capped; only the visual line is. */
data class GoalProgress(
    val totalMl: Long,
    val goalMl: Int?,
) {
    /** floor(total / goal × 100), or null when no goal is saved for the date. */
    val percent: Long? get() = goalMl?.takeIf { it > 0 }?.let { totalMl * 100L / it }
    val reached: Boolean get() = goalMl != null && goalMl > 0 && totalMl >= goalMl

    /** Fraction for the slim progress line, capped at 1. */
    val lineFraction: Float get() = goalMl?.takeIf { it > 0 }?.let { (totalMl.toDouble() / it).coerceIn(0.0, 1.0).toFloat() } ?: 0f
}

object GoalLookup {
    /** The goal effective on [date]: the latest change on or before it, or null (volume only). */
    fun goalOn(date: LocalDate, goals: List<GoalChange>): Int? =
        goals.filter { !it.effectiveDate.isAfter(date) }.maxByOrNull { it.effectiveDate }?.goalMl

    /** A change scheduled after [today] (e.g. "Apply tomorrow"), if any. */
    fun pending(today: LocalDate, goals: List<GoalChange>): GoalChange? =
        goals.filter { it.effectiveDate.isAfter(today) }.minByOrNull { it.effectiveDate }

    fun parseGoal(text: String): Outcome<Int> {
        val t = text.trim().replace(",", "")
        val v = if (t.isNotEmpty() && t.length <= 6 && t.all { it in '0'..'9' }) t.toIntOrNull() else null
        if (v == null || v < Limits.MIN_GOAL_ML || v > Limits.MAX_GOAL_ML) {
            return Outcome.Invalid("Enter a goal between 100 and 10,000 mL.")
        }
        return Outcome.Ok(v)
    }
}

/** Effective-dated goals: earlier dates are never rewritten. */
class GoalService(private val store: MealSipStore, private val time: TimeSource) {

    suspend fun setGoal(goalMl: Int, start: GoalStart): Outcome<LocalDate> {
        if (goalMl < Limits.MIN_GOAL_ML || goalMl > Limits.MAX_GOAL_ML) {
            return Outcome.Invalid("Enter a goal between 100 and 10,000 mL.")
        }
        val today = time.today()
        val effective = if (start == GoalStart.TODAY) today else today.plusDays(1)
        store.inTransaction {
            // A newer decision replaces any change scheduled later than its own start date.
            store.deleteGoalsAfter(effective)
            store.upsertGoal(GoalChange(effective, goalMl, time.now().toEpochMilli()))
        }
        return Outcome.Ok(effective)
    }

    suspend fun goalOn(date: LocalDate): Int? = GoalLookup.goalOn(date, store.goals())
}
