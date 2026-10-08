package com.egrmeister.mealsip.ui.periods

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.egrmeister.mealsip.AppContainer
import com.egrmeister.mealsip.domain.model.Limits
import com.egrmeister.mealsip.domain.model.Outcome
import com.egrmeister.mealsip.domain.model.Period
import com.egrmeister.mealsip.domain.model.PeriodIcons
import com.egrmeister.mealsip.domain.periods.PeriodValidation
import com.egrmeister.mealsip.ui.components.ConfirmDialog
import com.egrmeister.mealsip.ui.components.PeriodBadge
import com.egrmeister.mealsip.ui.components.containerViewModel
import com.egrmeister.mealsip.ui.theme.PlannerColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PeriodsViewModel(private val c: AppContainer) : ViewModel() {
    val periods: StateFlow<List<Period>?> = c.store.observePeriods()
        .map { list -> list.sortedWith(compareBy({ !it.isActive }, { it.displayOrder }, { it.id })) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Invokes [onDone] only after a successful save so the dialog can close. */
    fun save(id: Long?, name: String, iconKey: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val r = if (id == null) c.periods.create(name, iconKey) else c.periods.update(id, name, iconKey)
            when (r) {
                is Outcome.Ok -> onDone()
                is Outcome.Invalid -> _message.value = r.message
            }
        }
    }

    fun move(id: Long, delta: Int) {
        viewModelScope.launch {
            val r = c.periods.move(id, delta)
            if (r is Outcome.Invalid) _message.value = r.message
        }
    }

    fun archive(id: Long) {
        viewModelScope.launch {
            when (val r = c.periods.archive(id)) {
                is Outcome.Ok -> c.reminders.rescheduleAll() // cancels the archived period's alarm
                is Outcome.Invalid -> _message.value = r.message
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodsRoute(container: AppContainer, onBack: () -> Unit) {
    val vm = containerViewModel { PeriodsViewModel(container) }
    val periods by vm.periods.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    // Editor dialog: -1 = closed, 0 = new period, otherwise the period ID.
    var editing by rememberSaveable { mutableStateOf(-1L) }
    var archiving by rememberSaveable { mutableStateOf(-1L) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Periods") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PlannerColors.Cream),
            )
        },
    ) { padding ->
        val all = periods ?: return@Scaffold
        val active = all.filter { it.isActive }
        val archived = all.filter { !it.isActive }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Changes to names, icons and order apply from today. Earlier days keep the labels they were saved with. " +
                    "Water always stays in the period you chose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            active.forEachIndexed { index, p ->
                ActivePeriodRow(
                    period = p,
                    canMoveUp = index > 0,
                    canMoveDown = index < active.lastIndex,
                    canArchive = active.size > Limits.MIN_ACTIVE_PERIODS,
                    onUp = { vm.move(p.id, -1) },
                    onDown = { vm.move(p.id, +1) },
                    onEdit = { editing = p.id },
                    onArchive = { archiving = p.id },
                )
            }
            Button(
                onClick = { editing = 0L },
                enabled = active.size < Limits.MAX_ACTIVE_PERIODS,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Add period") }
            if (active.size >= Limits.MAX_ACTIVE_PERIODS) {
                Text("You can have up to ${Limits.MAX_ACTIVE_PERIODS} active periods.", style = MaterialTheme.typography.bodySmall)
            }
            if (archived.isNotEmpty()) {
                Text("Archived", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(
                    "Archived periods keep their past entries and appear in History. They can't receive new water entries.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                archived.forEach { p ->
                    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        PeriodBadge(p.iconKey, size = 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(p.name)
                    }
                }
            }
        }
    }

    if (editing >= 0) {
        val existing = periods?.firstOrNull { it.id == editing }
        PeriodEditorDialog(
            initialName = existing?.name ?: "",
            initialIcon = existing?.iconKey ?: PeriodIcons.CUP,
            isNew = editing == 0L,
            others = periods.orEmpty(),
            excludeId = existing?.id,
            onDismiss = { editing = -1L },
            onSave = { name, icon -> vm.save(existing?.id, name, icon) { editing = -1L } },
        )
    }
    if (archiving >= 0) {
        val p = periods?.firstOrNull { it.id == archiving }
        ConfirmDialog(
            title = "Archive ${p?.name ?: "period"}?",
            text = "It will no longer be offered for new water entries and its reminder will be turned off. " +
                "Existing entries stay where they are and remain visible in History.",
            confirmLabel = "Archive",
            onConfirm = { vm.archive(archiving); archiving = -1L },
            onDismiss = { archiving = -1L },
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            confirmButton = { TextButton(onClick = vm::clearMessage) { Text("OK") } },
            text = { Text(it) },
        )
    }
}

@Composable
private fun ActivePeriodRow(
    period: Period,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canArchive: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    Surface(
        color = PlannerColors.Paper,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, PlannerColors.Rule),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PeriodBadge(period.iconKey, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(period.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onUp, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${period.name} up")
                }
                IconButton(onClick = onDown, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${period.name} down")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit ${period.name}")
                }
            }
            TextButton(onClick = onArchive, enabled = canArchive, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (canArchive) "Archive" else "Archive (at least one active period is required)")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodEditorDialog(
    initialName: String,
    initialIcon: String,
    isNew: Boolean,
    others: List<Period>,
    excludeId: Long?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var icon by rememberSaveable { mutableStateOf(initialIcon) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New period" else "Edit period") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40); error = null },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "${name.trim().length}/${Limits.MAX_NAME_LENGTH}") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PeriodIcons.ALL.forEach { key ->
                        val selected = key == icon
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (selected) PlannerColors.PaleBlue else PlannerColors.Paper,
                            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) PlannerColors.Ink else PlannerColors.Rule),
                            modifier = Modifier
                                .size(52.dp)
                                .selectable(selected = selected, onClick = { icon = key }, role = Role.RadioButton)
                                .semantics { contentDescription = PeriodIcons.label(key) },
                        ) {
                            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                PeriodBadge(key, size = 36.dp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = PeriodValidation.nameError(name, others, excludeId)
                if (problem != null) {
                    error = problem
                } else {
                    onSave(name, icon)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
