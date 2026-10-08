package com.egrmeister.mealsip.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.egrmeister.mealsip.AppContainer
import com.egrmeister.mealsip.ui.components.ConfirmDialog
import com.egrmeister.mealsip.ui.components.Fmt
import com.egrmeister.mealsip.ui.components.PeriodBadge
import com.egrmeister.mealsip.ui.components.containerViewModel
import com.egrmeister.mealsip.ui.theme.PlannerColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditorRoute(container: AppContainer, onClose: () -> Unit) {
    val vm = containerViewModel { EntryEditorViewModel(container, it) }
    val s by vm.state.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(s.finished) { if (s.finished) onClose() }

    val requestClose: () -> Unit = {
        if (s.dirty && !s.finished) {
            confirmDiscard = true
        } else {
            onClose()
        }
    }
    // Predictive-back compatible: only intercepts while there are unsaved changes.
    BackHandler(enabled = s.dirty && !s.finished) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (s.isNew) "Add water" else "Edit entry") },
                navigationIcon = {
                    IconButton(onClick = requestClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                },
                actions = {
                    if (!s.isNew && !s.missing) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete entry")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PlannerColors.Cream),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (s.missing) {
                Text("This entry no longer exists.")
                Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                return@Column
            }
            if (!s.loaded) return@Column
            OutlinedTextField(
                value = s.amountText,
                onValueChange = vm::setAmount,
                label = { Text("Amount (mL)") },
                supportingText = { Text("Whole milliliters, 1–5,000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Date: ${Fmt.shortDate(s.date)}")
                }
                OutlinedButton(onClick = { showTime = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Time: ${Fmt.clock(s.time)}")
                }
            }
            Text("Period", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            s.periodHint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
            if (s.periods.isEmpty()) {
                Text("No periods are available on this date.", style = MaterialTheme.typography.bodyMedium)
            }
            Column(Modifier.selectableGroup()) {
                s.periods.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .selectable(
                                selected = s.periodId == item.periodId,
                                onClick = { vm.setPeriod(item.periodId) },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.periodId == item.periodId, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        PeriodBadge(item.iconKey, size = 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(item.name + if (item.archived) " (archived)" else "")
                    }
                }
            }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = requestClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                Button(onClick = vm::save, enabled = !s.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
            }
        }
    }

    if (showDate) {
        val today = s.today
        val selectable = remember(today) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)

                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            }
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = s.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = selectable,
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        val d: LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        if (!d.isAfter(today)) vm.setDate(d)
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTime) {
        val timeState = rememberTimePickerState(initialHour = s.time.hour, initialMinute = s.time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("Time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    vm.setTime(LocalTime.of(timeState.hour, timeState.minute))
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            text = "Your unsaved changes to this entry will be lost.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            onConfirm = { confirmDiscard = false; onClose() },
            onDismiss = { confirmDiscard = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this entry?",
            text = "You can undo this right after deleting.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; vm.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
