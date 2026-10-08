package com.egrmeister.mealsip.domain.model

import java.time.LocalDate

/**
 * Persistence boundary for the domain layer. The Room implementation lives in the data package;
 * tests use an in-memory fake. Every multi-step write is wrapped in [inTransaction].
 */
interface MealSipStore {
    suspend fun <R> inTransaction(block: suspend () -> R): R

    // Periods
    suspend fun periods(): List<Period>
    suspend fun period(id: Long): Period?
    suspend fun insertPeriod(period: Period): Long
    suspend fun updatePeriod(period: Period)

    // Configuration versions (one per effective date)
    suspend fun configVersions(): List<ConfigVersion>
    suspend fun upsertConfigVersion(effectiveDate: LocalDate, items: List<LayoutItem>)

    // Day snapshots
    suspend fun snapshot(date: LocalDate): List<LayoutItem>
    suspend fun replaceSnapshot(date: LocalDate, items: List<LayoutItem>)

    // Entries
    suspend fun insertEntry(entry: WaterEntry): Long
    suspend fun updateEntry(entry: WaterEntry): Int
    suspend fun deleteEntry(id: Long): Int
    suspend fun entry(id: Long): WaterEntry?
    suspend fun entriesOn(date: LocalDate): List<WaterEntry>

    // Goals
    suspend fun goals(): List<GoalChange>
    suspend fun upsertGoal(goal: GoalChange)
    suspend fun deleteGoalsAfter(date: LocalDate)

    /** Deletes every record in one transaction. */
    suspend fun clearAll()
}
