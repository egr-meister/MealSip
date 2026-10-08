package com.egrmeister.mealsip.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.egrmeister.mealsip.AppContainer
import com.egrmeister.mealsip.domain.logging.EntryDraft
import com.egrmeister.mealsip.domain.logging.UndoAction
import com.egrmeister.mealsip.domain.model.LayoutItem
import com.egrmeister.mealsip.domain.model.Outcome
import com.egrmeister.mealsip.ui.components.Fmt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class EditorState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val missing: Boolean = false,
    val amountText: String = "",
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.NOON,
    val periodId: Long? = null,
    val periods: List<LayoutItem> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val periodHint: String? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val finished: Boolean = false,
)

/**
 * Manual entry and editing. The draft lives in [SavedStateHandle], so it survives rotation and
 * process recreation; unsaved changes are confirmed before discarding (see the screen).
 */
class EntryEditorViewModel(private val c: AppContainer, private val saved: SavedStateHandle) : ViewModel() {

    private val entryId: Long = saved.get<Long>("entryId") ?: -1L
    private val isNew = entryId < 0

    private val _state = MutableStateFlow(EditorState(isNew = isNew, today = c.time.today()))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val today = c.time.today()
        if (saved.get<Boolean>(K_LOADED) != true) {
            if (isNew) {
                val argDate = saved.get<String>("date")?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?.takeIf { !it.isAfter(today) } ?: today
                val time = if (argDate == today) {
                    c.time.now().atZone(c.time.zone()).toLocalTime().truncatedTo(ChronoUnit.MINUTES)
                } else {
                    LocalTime.NOON
                }
                val argPeriod = saved.get<Long>("periodId")?.takeIf { it >= 0 }
                writeOriginal("", argDate, time, argPeriod)
                writeDraft("", argDate, time, argPeriod)
            } else {
                val entry = c.store.entry(entryId)
                if (entry == null) {
                    _state.value = _state.value.copy(loaded = true, missing = true)
                    return
                }
                val t = entry.localTime.truncatedTo(ChronoUnit.MINUTES)
                writeOriginal(entry.amountMl.toString(), entry.localDate, t, entry.periodId)
                writeDraft(entry.amountMl.toString(), entry.localDate, t, entry.periodId)
            }
            saved[K_LOADED] = true
        }
        refresh()
    }

    private fun writeOriginal(amount: String, date: LocalDate, time: LocalTime, periodId: Long?) {
        saved[K_O_AMOUNT] = amount
        saved[K_O_DATE] = date.toString()
        saved[K_O_TIME] = time.toString()
        saved[K_O_PERIOD] = periodId ?: -1L
    }

    private fun writeDraft(amount: String, date: LocalDate, time: LocalTime, periodId: Long?) {
        saved[K_AMOUNT] = amount
        saved[K_DATE] = date.toString()
        saved[K_TIME] = time.toString()
        saved[K_PERIOD] = periodId ?: -1L
    }

    private val draftAmount get() = saved.get<String>(K_AMOUNT) ?: ""
    private val draftDate get() = saved.get<String>(K_DATE)?.let { LocalDate.parse(it) } ?: c.time.today()
    private val draftTime get() = saved.get<String>(K_TIME)?.let { LocalTime.parse(it) } ?: LocalTime.NOON
    private val draftPeriod get() = saved.get<Long>(K_PERIOD)?.takeIf { it >= 0 }
    private val originalDate get() = saved.get<String>(K_O_DATE)?.let { LocalDate.parse(it) }
    private val originalPeriod get() = saved.get<Long>(K_O_PERIOD)?.takeIf { it >= 0 }

    private val dirty: Boolean
        get() = draftAmount != (saved.get<String>(K_O_AMOUNT) ?: "") ||
            saved.get<String>(K_DATE) != saved.get<String>(K_O_DATE) ||
            saved.get<String>(K_TIME) != saved.get<String>(K_O_TIME) ||
            saved.get<Long>(K_PERIOD) != saved.get<Long>(K_O_PERIOD)

    /** Recomputes the selectable periods for the draft date. */
    private suspend fun refresh() {
        val date = draftDate
        val keep = if (!isNew && date == originalDate) originalPeriod else null
        val periods = c.periods.selectableFor(date, keep)
        var periodId = draftPeriod
        var hint: String? = null
        if (periodId != null && periods.none { it.periodId == periodId }) {
            periodId = null
            saved[K_PERIOD] = -1L
            hint = "Choose a period available on ${Fmt.shortDate(date)}."
        }
        if (!isNew && date != originalDate && periodId == null) {
            hint = "This entry moves to another date. Choose its period on ${Fmt.shortDate(date)}."
        }
        _state.value = _state.value.copy(
            loaded = true,
            amountText = draftAmount,
            date = date,
            time = draftTime,
            periodId = periodId,
            periods = periods,
            today = c.time.today(),
            periodHint = hint,
            dirty = dirty,
        )
    }

    fun setAmount(text: String) {
        saved[K_AMOUNT] = text.filter { it.isDigit() }.take(5)
        _state.value = _state.value.copy(amountText = draftAmount, error = null, dirty = dirty)
    }

    fun setPeriod(id: Long) {
        saved[K_PERIOD] = id
        _state.value = _state.value.copy(periodId = id, periodHint = null, error = null, dirty = dirty)
    }

    fun setTime(time: LocalTime) {
        saved[K_TIME] = time.truncatedTo(ChronoUnit.MINUTES).toString()
        _state.value = _state.value.copy(time = draftTime, error = null, dirty = dirty)
    }

    fun setDate(date: LocalDate) {
        val previous = draftDate
        if (date == previous) return
        saved[K_DATE] = date.toString()
        // Moving an existing entry to another date always requires an explicit destination period.
        if (!isNew && date != originalDate) saved[K_PERIOD] = -1L
        if (!isNew && date == originalDate) saved[K_PERIOD] = originalPeriod ?: -1L
        _state.value = _state.value.copy(error = null)
        viewModelScope.launch { refresh() }
    }

    fun save() {
        val s = _state.value
        if (s.saving || !s.loaded || s.missing) return
        _state.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            val draft = EntryDraft(draftAmount, draftPeriod, draftDate, draftTime)
            val result = if (isNew) c.log.addManual(draft) else c.log.edit(entryId, draft)
            when (result) {
                is Outcome.Ok -> {
                    if (isNew) {
                        val name = _state.value.periods.firstOrNull { it.periodId == result.value.periodId }?.name ?: "the period"
                        c.undo.offer(
                            UndoAction.Added(c.undo.nextToken(), "${Fmt.ml(result.value.amountMl)} recorded in $name", result.value.id),
                        )
                    }
                    c.reminders.rescheduleAll()
                    _state.value = _state.value.copy(saving = false, finished = true)
                }
                is Outcome.Invalid -> _state.value = _state.value.copy(saving = false, error = result.message)
            }
        }
    }

    fun delete() {
        if (isNew || _state.value.saving) return
        _state.value = _state.value.copy(saving = true)
        viewModelScope.launch {
            val removed = c.log.delete(entryId)
            if (removed != null) {
                c.undo.offer(UndoAction.Deleted(c.undo.nextToken(), "Entry deleted (${Fmt.ml(removed.amountMl)})", removed))
            }
            _state.value = _state.value.copy(saving = false, finished = true)
        }
    }

    private companion object {
        const val K_LOADED = "draft_loaded"
        const val K_AMOUNT = "draft_amount"
        const val K_DATE = "draft_date"
        const val K_TIME = "draft_time"
        const val K_PERIOD = "draft_period"
        const val K_O_AMOUNT = "orig_amount"
        const val K_O_DATE = "orig_date"
        const val K_O_TIME = "orig_time"
        const val K_O_PERIOD = "orig_period"
    }
}
