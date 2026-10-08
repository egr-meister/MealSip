package com.egrmeister.mealsip.data.repository

import androidx.room.withTransaction
import com.egrmeister.mealsip.data.local.AppDatabase
import com.egrmeister.mealsip.data.local.DayPeriodSnapshotEntity
import com.egrmeister.mealsip.data.local.GoalChangeEntity
import com.egrmeister.mealsip.data.local.PeriodConfigurationVersionEntity
import com.egrmeister.mealsip.data.local.PeriodEntity
import com.egrmeister.mealsip.data.local.WaterEntryEntity
import com.egrmeister.mealsip.domain.model.ConfigVersion
import com.egrmeister.mealsip.domain.model.GoalChange
import com.egrmeister.mealsip.domain.model.LayoutItem
import com.egrmeister.mealsip.domain.model.MealSipStore
import com.egrmeister.mealsip.domain.model.Period
import com.egrmeister.mealsip.domain.model.PeriodIcons
import com.egrmeister.mealsip.domain.model.WaterEntry
import com.egrmeister.mealsip.domain.periods.LayoutCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime

/** Room-backed [MealSipStore]. Room runs suspend DAO calls off the main thread. */
class RoomStore(private val db: AppDatabase) : MealSipStore {

    override suspend fun <R> inTransaction(block: suspend () -> R): R = db.withTransaction { block() }

    override suspend fun periods(): List<Period> = db.periods().all().map { it.toDomain() }
    override suspend fun period(id: Long): Period? = db.periods().byId(id)?.toDomain()
    override suspend fun insertPeriod(period: Period): Long = db.periods().insert(period.toEntity())
    override suspend fun updatePeriod(period: Period) = db.periods().update(period.toEntity())

    override suspend fun configVersions(): List<ConfigVersion> = db.configVersions().all().map { it.toDomain() }

    override suspend fun upsertConfigVersion(effectiveDate: LocalDate, items: List<LayoutItem>) {
        val dao = db.configVersions()
        val data = LayoutCodec.encode(items)
        val existing = dao.byDate(effectiveDate.toString())
        if (existing == null) {
            dao.insert(PeriodConfigurationVersionEntity(effectiveLocalDate = effectiveDate.toString(), configurationData = data))
        } else if (existing.configurationData != data) {
            dao.update(existing.copy(configurationData = data))
        }
    }

    override suspend fun snapshot(date: LocalDate): List<LayoutItem> =
        db.snapshots().forDate(date.toString()).map { it.toDomain() }

    override suspend fun replaceSnapshot(date: LocalDate, items: List<LayoutItem>) {
        val key = date.toString()
        db.snapshots().deleteForDate(key)
        db.snapshots().insertAll(items.map { it.toSnapshot(key) })
    }

    override suspend fun insertEntry(entry: WaterEntry): Long = db.entries().insert(entry.toEntity())
    override suspend fun updateEntry(entry: WaterEntry): Int = db.entries().update(entry.toEntity())
    override suspend fun deleteEntry(id: Long): Int = db.entries().deleteById(id)
    override suspend fun entry(id: Long): WaterEntry? = db.entries().byId(id)?.toDomain()
    override suspend fun entriesOn(date: LocalDate): List<WaterEntry> =
        db.entries().onDate(date.toString()).map { it.toDomain() }

    override suspend fun goals(): List<GoalChange> = db.goals().all().map { it.toDomain() }
    override suspend fun upsertGoal(goal: GoalChange) = db.goals().upsert(goal.toEntity())
    override suspend fun deleteGoalsAfter(date: LocalDate) = db.goals().deleteAfter(date.toString())

    override suspend fun clearAll() {
        db.withTransaction {
            db.entries().deleteAll()
            db.snapshots().deleteAll()
            db.configVersions().deleteAll()
            db.goals().deleteAll()
            db.periods().deleteAll()
        }
    }

    // Observation for the UI (Room emits again after every committed change).
    fun observePeriods(): Flow<List<Period>> = db.periods().observeAll().map { list -> list.map { it.toDomain() } }
    fun observeVersions(): Flow<List<ConfigVersion>> = db.configVersions().observeAll().map { list -> list.map { it.toDomain() } }
    fun observeGoals(): Flow<List<GoalChange>> = db.goals().observeAll().map { list -> list.map { it.toDomain() } }
    fun observeEntriesOn(date: LocalDate): Flow<List<WaterEntry>> =
        db.entries().observeOnDate(date.toString()).map { list -> list.map { it.toDomain() } }
    fun observeEntriesBetween(from: LocalDate, to: LocalDate): Flow<List<WaterEntry>> =
        db.entries().observeBetween(from.toString(), to.toString()).map { list -> list.map { it.toDomain() } }
    fun observeSnapshot(date: LocalDate): Flow<List<LayoutItem>> =
        db.snapshots().observeForDate(date.toString()).map { list -> list.map { it.toDomain() } }
    fun observeSnapshotsBetween(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, List<LayoutItem>>> =
        db.snapshots().observeBetween(from.toString(), to.toString()).map { list ->
            list.groupBy({ LocalDate.parse(it.localDate) }, { it.toDomain() })
        }
}

private fun PeriodEntity.toDomain() = Period(
    id = id,
    name = name,
    iconKey = PeriodIcons.normalize(iconKey),
    displayOrder = displayOrder,
    archivedAt = archivedAt,
    reminderEnabled = reminderEnabled,
    reminderMinute = reminderMinute,
    reminderGeneration = reminderGeneration,
)

private fun Period.toEntity() = PeriodEntity(
    id = id,
    name = name,
    iconKey = iconKey,
    displayOrder = displayOrder,
    archivedAt = archivedAt,
    reminderEnabled = reminderEnabled,
    reminderMinute = reminderMinute,
    reminderGeneration = reminderGeneration,
)

private fun PeriodConfigurationVersionEntity.toDomain() =
    ConfigVersion(id, LocalDate.parse(effectiveLocalDate), LayoutCodec.decode(configurationData))

private fun DayPeriodSnapshotEntity.toDomain() =
    LayoutItem(periodId, nameSnapshot, PeriodIcons.normalize(iconKeySnapshot), displayOrder, archivedState)

private fun LayoutItem.toSnapshot(date: String) =
    DayPeriodSnapshotEntity(date, periodId, name, iconKey, displayOrder, archived)

private fun WaterEntryEntity.toDomain() = WaterEntry(
    id = id,
    amountMl = amountMl,
    periodId = periodId,
    timestamp = timestamp,
    localDate = LocalDate.parse(loggedLocalDate),
    localTime = LocalTime.parse(loggedLocalTime),
    zoneId = zoneId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun WaterEntry.toEntity() = WaterEntryEntity(
    id = id,
    amountMl = amountMl,
    periodId = periodId,
    timestamp = timestamp,
    loggedLocalDate = localDate.toString(),
    loggedLocalTime = localTime.toString(),
    zoneId = zoneId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun GoalChangeEntity.toDomain() = GoalChange(LocalDate.parse(effectiveLocalDate), goalMl, createdAt)
private fun GoalChange.toEntity() = GoalChangeEntity(effectiveDate.toString(), goalMl, createdAt)
