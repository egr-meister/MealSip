package com.egrmeister.mealsip.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.egrmeister.mealsip.AppContainer
import com.egrmeister.mealsip.domain.model.Period
import com.egrmeister.mealsip.domain.reminders.NotificationAccess
import com.egrmeister.mealsip.domain.reminders.ReminderPolicy
import com.egrmeister.mealsip.domain.time.TimeMath
import com.egrmeister.mealsip.ui.components.ConfirmDialog
import com.egrmeister.mealsip.ui.components.Fmt
import com.egrmeister.mealsip.ui.components.GoalDialog
import com.egrmeister.mealsip.ui.components.PeriodBadge
import com.egrmeister.mealsip.ui.components.containerViewModel
import com.egrmeister.mealsip.ui.theme.PlannerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(container: AppContainer, onManagePeriods: () -> Unit, onPrivacy: () -> Unit) {
    val vm = containerViewModel { SettingsViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var goalDialog by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var timeFor by rememberSaveable { mutableStateOf<Long?>(null) }
    // Reminder waiting for the one-time permission answer.
    var pendingEnable by rememberSaveable { mutableStateOf<Long?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAccess() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshAccess()
        pendingEnable?.let { id ->
            val p = state?.activePeriods?.firstOrNull { it.id == id }
            if (p != null) vm.setReminder(id, true, p.reminderMinute ?: 8 * 60)
        }
        pendingEnable = null
    }

    fun enableReminder(p: Period) {
        val needsPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            state?.promptShown == false
        if (needsPrompt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Asked once, only when the user first enables a reminder. Never re-prompted.
            vm.markPromptShown()
            pendingEnable = p.id
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.setReminder(p.id, true, p.reminderMinute ?: 8 * 60)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PlannerColors.Cream),
            )
        },
    ) { padding ->
        val s = state ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Daily goal") {
                Text(s.todayGoal?.let { "Today: ${Fmt.ml(it)}" } ?: "No daily goal set. Logging works without one.")
                s.pendingGoal?.let {
                    Text("From ${Fmt.shortDate(it.effectiveDate)}: ${Fmt.ml(it.goalMl)}", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = { goalDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (s.todayGoal == null) "Set a daily goal" else "Change daily goal")
                }
            }

            Section("Periods") {
                Text(
                    s.activePeriods.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onManagePeriods, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Manage periods")
                }
            }

            Section("Period reminders") {
                ReminderStatus(access, anyEnabled = s.activePeriods.any { it.reminderEnabled }, promptShown = s.promptShown, context = context)
                s.activePeriods.forEach { p ->
                    ReminderRow(
                        period = p,
                        onToggle = { on -> if (on) enableReminder(p) else vm.setReminder(p.id, false, p.reminderMinute) },
                        onPickTime = { timeFor = p.id },
                    )
                }
                Text(
                    "Reminders are optional and never record water for you. Android may delay delivery. " +
                        "MealSip skips a reminder delayed by more than ${ReminderPolicy.MAX_DELAY_MINUTES} minutes, " +
                        "when the period already has a water entry today, or when your selected goal is reached. " +
                        "If the app is force-stopped, reminders stay off until MealSip is opened again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Quick water amounts") {
                QuickAmountsEditor(s.quickAmounts, onSave = vm::saveQuickAmounts)
            }

            Section("Privacy") {
                Text("All records stay on this device. No account, no network access.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onPrivacy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Privacy information") }
            }

            Section("Data") {
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Clear all local data", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (goalDialog) {
        GoalDialog(
            initialGoal = state?.todayGoal,
            onDismiss = { goalDialog = false },
            onSave = { ml, start -> vm.setGoal(ml, start); goalDialog = false },
            showStart = state?.todayGoal != null,
        )
    }

    timeFor?.let { id ->
        val p = state?.activePeriods?.firstOrNull { it.id == id }
        if (p != null) {
            val minute = p.reminderMinute ?: 8 * 60
            val timeState = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = true)
            AlertDialog(
                onDismissRequest = { timeFor = null },
                title = { Text("${p.name} reminder") },
                text = { TimePicker(state = timeState) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.setReminder(p.id, p.reminderEnabled, timeState.hour * 60 + timeState.minute)
                        timeFor = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { timeFor = null }) { Text("Cancel") } },
            )
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Clear all local data?",
            text = "This deletes every water record, goal, custom period and preference on this device, cancels reminders " +
                "and restores the starter periods with reminders off. This cannot be undone.",
            confirmLabel = "Clear all data",
            onConfirm = { confirmReset = false; vm.clearAllData() },
            onDismiss = { confirmReset = false },
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
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        color = PlannerColors.Paper,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, PlannerColors.Rule),
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            HorizontalDivider(color = PlannerColors.Rule)
            content()
        }
    }
}

@Composable
private fun ReminderStatus(access: NotificationAccess, anyEnabled: Boolean, promptShown: Boolean, context: Context) {
    if (access.canDeliver) return
    if (!anyEnabled && !promptShown) return
    Surface(color = PlannerColors.Peach, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    access.runtimePermissionRequired && !access.permissionGranted -> "Notifications are not allowed for MealSip."
                    !access.appNotificationsEnabled -> "Notifications are turned off for MealSip."
                    else -> "The reminder channel is turned off."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Reminders cannot appear. Logging water still works normally.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { openNotificationSettings(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Open notification settings (optional)")
            }
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun ReminderRow(period: Period, onToggle: (Boolean) -> Unit, onPickTime: () -> Unit) {
    val minute = period.reminderMinute
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        PeriodBadge(period.iconKey, size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(period.name, modifier = Modifier.weight(1f))
        TextButton(
            onClick = onPickTime,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Change ${period.name} reminder time, currently ${minute?.let { TimeMath.formatMinute(it) } ?: "not set"}" },
        ) {
            Text(minute?.let { TimeMath.formatMinute(it) } ?: "Set time")
        }
        Switch(
            checked = period.reminderEnabled,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { contentDescription = "${period.name} reminder" },
        )
    }
}

@Composable
private fun QuickAmountsEditor(current: List<Int>, onSave: (List<String>) -> Unit) {
    var a by rememberSaveable(current) { mutableStateOf(current.getOrNull(0)?.toString() ?: "") }
    var b by rememberSaveable(current) { mutableStateOf(current.getOrNull(1)?.toString() ?: "") }
    var c by rememberSaveable(current) { mutableStateOf(current.getOrNull(2)?.toString() ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmountField("First", a, Modifier.weight(1f)) { a = it }
            AmountField("Second", b, Modifier.weight(1f)) { b = it }
            AmountField("Third", c, Modifier.weight(1f)) { c = it }
        }
        Text("Each amount: 1–5,000 mL.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { onSave(listOf(a, b, c)) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Save quick amounts")
        }
    }
}

@Composable
private fun AmountField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(5)) },
        label = { Text("$label (mL)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
