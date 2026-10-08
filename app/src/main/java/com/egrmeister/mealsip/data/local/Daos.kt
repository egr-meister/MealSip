package com.egrmeister.mealsip.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PeriodDao {
    @Query("SELECT * FROM periods ORDER BY displayOrder, id")
    suspend fun all(): List<PeriodEntity>

    @Query("SELECT * FROM periods ORDER BY displayOrder, id")
    fun observeAll(): Flow<List<PeriodEntity>>

    @Query("SELECT * FROM periods WHERE id = :id")
    suspend fun byId(id: Long): PeriodEntity?

    @Insert
    suspend fun insert(entity: PeriodEntity): Long

    @Update
    suspend fun update(entity: PeriodEntity)

    @Query("DELETE FROM periods")
    suspend fun deleteAll()
}

@Dao
interface ConfigVersionDao {
    @Query("SELECT * FROM period_configuration_versions ORDER BY effectiveLocalDate")
    suspend fun all(): List<PeriodConfigurationVersionEntity>

    @Query("SELECT * FROM period_configuration_versions ORDER BY effectiveLocalDate")
    fun observeAll(): Flow<List<PeriodConfigurationVersionEntity>>

    @Query("SELECT * FROM period_configuration_versions WHERE effectiveLocalDate = :date")
    suspend fun byDate(date: String): PeriodConfigurationVersionEntity?

    @Insert
    suspend fun insert(entity: PeriodConfigurationVersionEntity): Long

    @Update
    suspend fun update(entity: PeriodConfigurationVersionEntity)

    @Query("DELETE FROM period_configuration_versions")
    suspend fun deleteAll()
}

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM day_period_snapshots WHERE localDate = :date ORDER BY displayOrder, periodId")
    suspend fun forDate(date: String): List<DayPeriodSnapshotEntity>

    @Query("SELECT * FROM day_period_snapshots WHERE localDate = :date ORDER BY displayOrder, periodId")
    fun observeForDate(date: String): Flow<List<DayPeriodSnapshotEntity>>

    @Query("SELECT * FROM day_period_snapshots WHERE localDate BETWEEN :from AND :to")
    fun observeBetween(from: String, to: String): Flow<List<DayPeriodSnapshotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DayPeriodSnapshotEntity>)

    @Query("DELETE FROM day_period_snapshots WHERE localDate = :date")
    suspend fun deleteForDate(date: String)

    @Query("DELETE FROM day_period_snapshots")
    suspend fun deleteAll()
}

@Dao
interface WaterEntryDao {
    @Query("SELECT * FROM water_entries WHERE loggedLocalDate = :date ORDER BY loggedLocalTime, timestamp, id")
    suspend fun onDate(date: String): List<WaterEntryEntity>

    @Query("SELECT * FROM water_entries WHERE loggedLocalDate = :date ORDER BY loggedLocalTime, timestamp, id")
    fun observeOnDate(date: String): Flow<List<WaterEntryEntity>>

    @Query("SELECT * FROM water_entries WHERE loggedLocalDate BETWEEN :from AND :to")
    fun observeBetween(from: String, to: String): Flow<List<WaterEntryEntity>>

    @Query("SELECT * FROM water_entries WHERE id = :id")
    suspend fun byId(id: Long): WaterEntryEntity?

    /** Explicit IDs are kept, so Undo can restore a deleted entry exactly. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: WaterEntryEntity): Long

    @Update
    suspend fun update(entity: WaterEntryEntity): Int

    @Query("DELETE FROM water_entries WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM water_entries")
    suspend fun deleteAll()
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goal_changes ORDER BY effectiveLocalDate")
    suspend fun all(): List<GoalChangeEntity>

    @Query("SELECT * FROM goal_changes ORDER BY effectiveLocalDate")
    fun observeAll(): Flow<List<GoalChangeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GoalChangeEntity)

    @Query("DELETE FROM goal_changes WHERE effectiveLocalDate > :date")
    suspend fun deleteAfter(date: String)

    @Query("DELETE FROM goal_changes")
    suspend fun deleteAll()
}
