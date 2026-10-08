package com.egrmeister.mealsip.ui.today

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.egrmeister.mealsip.AppContainer
import com.egrmeister.mealsip.domain.goals.GoalStart
import com.egrmeister.mealsip.domain.history.BoardBuilder
import com.egrmeister.mealsip.domain.history.DayBoard
import com.egrmeister.mealsip.domain.logging.UndoAction
import com.egrmeister.mealsip.domain.model.Outcome
import com.egrmeister.mealsip.domain.model.WaterEntry
import com.egrmeister.mealsip.ui.components.Fmt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DayBoardState(
    val board: DayBoard,
    val quickAmounts: List<Int>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DayBoardViewModel(
    private val c: AppContainer,
    fixedDate: LocalDate?,
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val dateFlow: Flow<LocalDate> = if (fixedDate != null) flowOf(fixedDate) else c.todayProvider.today

    val state: StateFlow<DayBoardState?> = combine(dateFlow, c.todayProvider.today) { d, t -> d to t }
        .flatMapLatest { (date, today) ->
            combine(
                c.store.observeEntriesOn(date),
                c.store.observePeriods(),
                c.store.observeSnapshot(date),
                c.store.observeVersions(),
                c.store.observeGoals(),
            ) { entries, periods, snapshot, versions, goals ->
                BoardBuilder.build(date, today, entries, periods, snapshot, versions, goals)
            }
        }
        .combine(c.preferences.prefs) { board, prefs -> DayBoardState(board, prefs.quickAmounts) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _expanded = MutableStateFlow(saved.get<LongArray>(KEY_EXPANDED)?.toSet() ?: emptySet())
    val expanded: StateFlow<Set<Long>> = _expanded.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun toggle(periodId: Long) {
        setExpanded(if (periodId in _expanded.value) _expanded.value - periodId else _expanded.value + periodId)
    }

    fun expand(periodId: Long) {
        setExpanded(_expanded.value + periodId)
    }

    private fun setExpanded(value: Set<Long>) {
        _expanded.value = value
        saved[KEY_EXPANDED] = value.toLongArray()
    }

    /** Quick amount; one activation is processed once (see ActionGuard). */
    fun quickAdd(periodId: Long, periodName: String, amountMl: Int) {
        val key = "quick:$periodId:$amountMl"
        if (!c.actionGuard.tryAcquire(key)) return
        viewModelScope.launch {
            try {
                when (val r = c.log.quickAdd(periodId, amountMl)) {
                    is Outcome.Ok -> c.undo.offer(
                        UndoAction.Added(c.undo.nextToken(), "${Fmt.ml(amountMl)} recorded in $periodName", r.value.id),
                    )
                    is Outcome.Invalid -> _message.value = r.message
                }
            } finally {
                c.actionGuard.release(key)
            }
        }
    }

    fun delete(entry: WaterEntry) {
        val key = "delete:${entry.id}"
        if (!c.actionGuard.tryAcquire(key)) return
        viewModelScope.launch {
            try {
                val removed = c.log.delete(entry.id)
                if (removed != null) {
                    c.undo.offer(UndoAction.Deleted(c.undo.nextToken(), "Entry deleted (${Fmt.ml(removed.amountMl)})", removed))
                }
            } finally {
                c.actionGuard.release(key)
            }
        }
    }

    fun setGoal(goalMl: Int, start: GoalStart) {
        viewModelScope.launch {
            val r = c.goals.setGoal(goalMl, start)
            if (r is Outcome.Invalid) _message.value = r.message
            c.reminders.rescheduleAll()
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val KEY_EXPANDED = "expanded_periods"
    }
}
