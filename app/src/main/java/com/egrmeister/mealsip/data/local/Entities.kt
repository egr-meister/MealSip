package com.egrmeister.mealsip.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Dates are stored as ISO-8601 text (yyyy-MM-dd, sortable); local times as HH:mm:ss. */
@Entity(tableName = "periods")
data class PeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconKey: String,
    val displayOrder: Int,
    val archivedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val reminderEnabled: Boolean = false,
    val reminderMinute: Int? = null,
    @ColumnInfo(defaultValue = "0") val reminderGeneration: Int = 0,
)

@Entity(
    tableName = "period_configuration_versions",
    indices = [Index(value = ["effectiveLocalDate"], unique = true)],
)
data class PeriodConfigurationVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val effectiveLocalDate: String,
    val configurationData: String,
)

@Entity(
    tableName = "day_period_snapshots",
    primaryKeys = ["localDate", "periodId"],
)
data class DayPeriodSnapshotEntity(
    val localDate: String,
    val periodId: Long,
    val nameSnapshot: String,
    val iconKeySnapshot: String,
    val displayOrder: Int,
    val archivedState: Boolean,
)

@Entity(
    tableName = "water_entries",
    indices = [Index(value = ["loggedLocalDate"]), Index(value = ["periodId"])],
)
data class WaterEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMl: Int,
    val periodId: Long,
    val timestamp: Long,
    val loggedLocalDate: String,
    val loggedLocalTime: String,
    val zoneId: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "goal_changes")
data class GoalChangeEntity(
    @PrimaryKey val effectiveLocalDate: String,
    val goalMl: Int,
    val createdAt: Long,
)
