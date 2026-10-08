package com.egrmeister.mealsip.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.egrmeister.mealsip.AppContainer
import com.egrmeister.mealsip.domain.goals.GoalLookup
import com.egrmeister.mealsip.domain.goals.GoalStart
import com.egrmeister.mealsip.domain.logging.EntryValidation
import com.egrmeister.mealsip.domain.model.GoalChange
import com.egrmeister.mealsip.domain.model.Outcome
import com.egrmeister.mealsip.domain.model.Period
import com.egrmeister.mealsip.domain.reminders.NotificationAccess
import com.egrmeister.mealsip.reminders.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsState(
    val todayGoal: Int?,
    val pendingGoal: GoalChange?,
    val activePeriods: List<Period>,
    val quickAmounts: List<Int>,
    val promptShown: Boolean,
)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {

    val state: StateFlow<SettingsState?> = combine(
        c.store.observeGoals(),
        c.store.observePeriods(),
        c.preferences.prefs,
        c.todayProvider.today,
    ) { goals, periods, prefs, today ->
        SettingsState(
            todayGoal = GoalLookup.goalOn(today, goals),
            pendingGoal = GoalLookup.pending(today, goals),
            activePeriods = periods.filter { it.isActive }.sortedWith(compareBy({ it.displayOrder }, { it.id })),
            quickAmounts = prefs.quickAmounts,
            promptShown = prefs.notificationPromptShown,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _access = MutableStateFlow(Notifications.access(c.context))
    val access: StateFlow<NotificationAccess> = _access.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refreshAccess() {
        _access.value = Notifications.access(c.context)
    }

    fun setGoal(goalMl: Int, start: GoalStart) {
        viewModelScope.launch {
            when (val r = c.goals.setGoal(goalMl, start)) {
                is Outcome.Ok -> _message.value = if (start == GoalStart.TODAY) "Goal saved from today." else "Goal saved from tomorrow."
                is Outcome.Invalid -> _message.value = r.message
            }
        }
    }

    fun setReminder(periodId: Long, enabled: Boolean, minute: Int?) {
        viewModelScope.launch {
            when (val r = c.periods.setReminder(periodId, enabled, minute)) {
                is Outcome.Ok -> c.reminders.rescheduleAll()
                is Outcome.Invalid -> _message.value = r.message
            }
        }
    }

    fun markPromptShown() {
        viewModelScope.launch { c.preferences.markNotificationPromptShown() }
    }

    fun saveQuickAmounts(texts: List<String>) {
        viewModelScope.launch {
            val parsed = texts.map { EntryValidation.parseAmount(it) }
            val invalid = parsed.firstOrNull { it is Outcome.Invalid } as Outcome.Invalid?
            if (invalid != null) {
                _message.value = invalid.message
                return@launch
            }
            val values = parsed.map { (it as Outcome.Ok).value }
            _message.value = if (c.preferences.setQuickAmounts(values)) "Quick amounts saved." else "Enter between 1 and 5,000 mL."
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            c.clearAllData()
            _message.value = null
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
