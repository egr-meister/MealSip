package com.egrmeister.mealsip

import com.egrmeister.mealsip.domain.goals.GoalService
import com.egrmeister.mealsip.domain.logging.WaterLogService
import com.egrmeister.mealsip.domain.model.ConfigVersion
import com.egrmeister.mealsip.domain.model.GoalChange
import com.egrmeister.mealsip.domain.model.LayoutItem
import com.egrmeister.mealsip.domain.model.MealSipStore
import com.egrmeister.mealsip.domain.model.Period
import com.egrmeister.mealsip.domain.model.WaterEntry
import com.egrmeister.mealsip.domain.periods.LayoutCodec
import com.egrmeister.mealsip.domain.periods.PeriodService
import com.egrmeister.mealsip.domain.reminders.AlarmScheduler
import com.egrmeister.mealsip.domain.reminders.ReminderCallback
import com.egrmeister.mealsip.domain.reminders.ReminderCoordinator
import com.egrmeister.mealsip.domain.reminders.ReminderNotifier
import com.egrmeister.mealsip.domain.time.TimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Controllable clock and zone. */
class FakeTime(var instant: Instant, var zoneId: ZoneId = ZoneId.of("Europe/Minsk")) : TimeSource {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId

    fun setLocal(dateTime: LocalDateTime) {
        instant = dateTime.atZone(zoneId).toInstant()
    }

    fun advanceMinutes(m: Long) {
        instant = instant.plusSeconds(m * 60)
    }
}

/**
 * In-memory store with Room-like semantics: auto IDs, explicit IDs kept, row counts, and
 * transactions that roll back on failure (a re-entrant lock models Room's nested transactions).
 */
class FakeStore : MealSipStore {
    private val mutex = Mutex()
    private var txDepth = 0
    private var nextPeriodId = 1L
    private var nextEntryId = 1L
    private var nextVersionId = 1L

    val periodRows = linkedMapOf<Long, Period>()
    val versionRows = linkedMapOf<LocalDate, ConfigVersion>()
    val snapshotRows = linkedMapOf<LocalDate, List<LayoutItem>>()
    val entryRows = linkedMapOf<Long, WaterEntry>()
    val goalRows = linkedMapOf<LocalDate, GoalChange>()

    override suspend fun <R> inTransaction(block: suspend () -> R): R {
        if (txDepth > 0) return block()
        return mutex.withLock {
            txDepth++
            val backup = copyState()
            try {
                block()
            } catch (t: Throwable) {
                restore(backup)
                throw t
            } finally {
                txDepth--
            }
        }
    }

    private data class State(
        val p: Map<Long, Period>, val v: Map<LocalDate, ConfigVersion>, val s: Map<LocalDate, List<LayoutItem>>,
        val e: Map<Long, WaterEntry>, val g: Map<LocalDate, GoalChange>,
    )

    private fun copyState() = State(periodRows.toMap(), versionRows.toMap(), snapshotRows.toMap(), entryRows.toMap(), goalRows.toMap())
    private fun restore(st: State) {
        periodRows.clear(); periodRows.putAll(st.p)
        versionRows.clear(); versionRows.putAll(st.v)
        snapshotRows.clear(); snapshotRows.putAll(st.s)
        entryRows.clear(); entryRows.putAll(st.e)
        goalRows.clear(); goalRows.putAll(st.g)
    }

    override suspend fun periods(): List<Period> = periodRows.values.sortedWith(compareBy({ it.displayOrder }, { it.id }))
    override suspend fun period(id: Long): Period? = periodRows[id]
    override suspend fun insertPeriod(period: Period): Long {
        val id = if (period.id > 0) period.id else nextPeriodId++
        periodRows[id] = period.copy(id = id)
        return id
    }
    override suspend fun updatePeriod(period: Period) {
        if (periodRows.containsKey(period.id)) periodRows[period.id] = period
    }

    override suspend fun configVersions(): List<ConfigVersion> = versionRows.values.sortedBy { it.effectiveDate }
    override suspend fun upsertConfigVersion(effectiveDate: LocalDate, items: List<LayoutItem>) {
        // Round-trip through the real codec, like the Room store.
        val decoded = LayoutCodec.decode(LayoutCodec.encode(items))
        val existing = versionRows[effectiveDate]
        versionRows[effectiveDate] = ConfigVersion(existing?.id ?: nextVersionId++, effectiveDate, decoded)
    }

    override suspend fun snapshot(date: LocalDate): List<LayoutItem> =
        snapshotRows[date].orEmpty().sortedWith(compareBy({ it.displayOrder }, { it.periodId }))
    override suspend fun replaceSnapshot(date: LocalDate, items: List<LayoutItem>) {
        snapshotRows[date] = items.toList()
    }

    override suspend fun insertEntry(entry: WaterEntry): Long {
        val id = if (entry.id > 0) entry.id else nextEntryId++
        check(!entryRows.containsKey(id)) { "UNIQUE constraint failed: water_entries.id" }
        if (id >= nextEntryId) nextEntryId = id + 1
        entryRows[id] = entry.copy(id = id)
        return id
    }
    override suspend fun updateEntry(entry: WaterEntry): Int =
        if (entryRows.containsKey(entry.id)) { entryRows[entry.id] = entry; 1 } else 0
    override suspend fun deleteEntry(id: Long): Int = if (entryRows.remove(id) != null) 1 else 0
    override suspend fun entry(id: Long): WaterEntry? = entryRows[id]
    override suspend fun entriesOn(date: LocalDate): List<WaterEntry> =
        entryRows.values.filter { it.localDate == date }.sortedWith(compareBy({ it.localTime }, { it.timestamp }, { it.id }))

    override suspend fun goals(): List<GoalChange> = goalRows.values.sortedBy { it.effectiveDate }
    override suspend fun upsertGoal(goal: GoalChange) {
        goalRows[goal.effectiveDate] = goal
    }
    override suspend fun deleteGoalsAfter(date: LocalDate) {
        goalRows.keys.filter { it.isAfter(date) }.forEach { goalRows.remove(it) }
    }

    override suspend fun clearAll() {
        inTransaction {
            periodRows.clear(); versionRows.clear(); snapshotRows.clear(); entryRows.clear(); goalRows.clear()
        }
    }

    fun entriesOnSync(date: LocalDate) = runBlocking { entriesOn(date) }
}

class FakeAlarms : AlarmScheduler {
    val pending = linkedMapOf<Long, Pair<Long, ReminderCallback>>()
    val cancelled = mutableListOf<Long>()
    override fun schedule(triggerAtMillis: Long, callback: ReminderCallback) {
        pending[callback.periodId] = triggerAtMillis to callback
    }
    override fun cancel(periodId: Long) {
        cancelled += periodId
        pending.remove(periodId)
    }
}

class FakeNotifier(var deliverable: Boolean = true) : ReminderNotifier {
    val posted = mutableListOf<Pair<Long, String>>()
    var cancelledVisible = 0
    override fun canDeliver(): Boolean = deliverable
    override fun post(periodId: Long, periodName: String) {
        posted += periodId to periodName
    }
    override fun cancelVisible() {
        cancelledVisible++
    }
}

/** Wires the real domain services over the fakes. */
class Env(start: LocalDateTime = LocalDateTime.of(2026, 3, 10, 9, 0), zone: ZoneId = ZoneId.of("Europe/Minsk")) {
    val time = FakeTime(start.atZone(zone).toInstant(), zone)
    val store = FakeStore()
    val periods = PeriodService(store, time)
    val log = WaterLogService(store, periods, time)
    val goals = GoalService(store, time)
    val alarms = FakeAlarms()
    val notifier = FakeNotifier()
    val reminders = ReminderCoordinator(store, alarms, notifier, time)

    init {
        runBlocking { periods.seedIfEmpty() }
    }

    fun today(): LocalDate = time.today()
    fun periodId(name: String): Long = store.periodRows.values.first { it.name == name }.id
}

fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }
