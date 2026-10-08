package com.egrmeister.mealsip.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        PeriodEntity::class,
        PeriodConfigurationVersionEntity::class,
        DayPeriodSnapshotEntity::class,
        WaterEntryEntity::class,
        GoalChangeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun periods(): PeriodDao
    abstract fun configVersions(): ConfigVersionDao
    abstract fun snapshots(): SnapshotDao
    abstract fun entries(): WaterEntryDao
    abstract fun goals(): GoalDao

    companion object {
        const val NAME = "mealsip.db"

        /**
         * Explicit migrations only. For a schema change: bump [version], add `Migration(n, n + 1)` here
         * and commit the newly exported schema from app/schemas. No destructive fallback is configured,
         * so a missing migration fails loudly instead of wiping user records.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
