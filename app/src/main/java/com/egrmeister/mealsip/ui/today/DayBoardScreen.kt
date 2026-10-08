package com.egrmeister.mealsip.ui.today

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.egrmeister.mealsip.AppContainer
import com.egrmeister.mealsip.OpenPeriodRequest
import com.egrmeister.mealsip.domain.history.DayBoard
import com.egrmeister.mealsip.domain.history.PeriodBlock
import com.egrmeister.mealsip.domain.model.WaterEntry
import com.egrmeister.mealsip.domain.time.TimeMath
import com.egrmeister.mealsip.ui.components.Fmt
import com.egrmeister.mealsip.ui.components.GoalDialog
import com.egrmeister.mealsip.ui.components.PeriodBadge
import com.egrmeister.mealsip.ui.components.containerViewModel
import com.egrmeister.mealsip.ui.theme.PlannerColors
import com.egrmeister.mealsip.ui.theme.accentFor
import java.time.LocalDate

@Composable
fun DayBoardRoute(
    container: AppContainer,
    fixedDate: LocalDate?,
    expandRequest: OpenPeriodRequest?,
    onAddManual: (LocalDate, Long?) -> Unit,
    onEditEntry: (Long) -> Unit,
    onBack: (() -> Unit)?,
) {
    val vm = containerViewModel(key = "board-${fixedDate ?: "today"}") { DayBoardViewModel(container, fixedDate, it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val expanded by vm.expanded.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var goalDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(expandRequest?.serial) {
        expandRequest?.let { vm.expand(it.periodId) }
    }

    DayBoardScreen(
        state = state,
        expanded = expanded,
        isDetail = onBack != null,
        onBack = onBack,
        onToggle = vm::toggle,
        onQuick = vm::quickAdd,
        onAddManual = onAddManual,
        onEditEntry = onEditEntry,
        onDelete = vm::delete,
        onSetGoal = { goalDialog = true },
    )

    if (goalDialog) {
        GoalDialog(
            initialGoal = state?.board?.progress?.goalMl,
            onDismiss = { goalDialog = false },
            onSave = { ml, start -> vm.setGoal(ml, start); goalDialog = false },
            showStart = state?.board?.progress?.goalMl != null,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayBoardScreen(
    state: DayBoardState?,
    expanded: Set<Long>,
    isDetail: Boolean,
    onBack: (() -> Unit)?,
    onToggle: (Long) -> Unit,
    onQuick: (Long, String, Int) -> Unit,
    onAddManual: (LocalDate, Long?) -> Unit,
    onEditEntry: (Long) -> Unit,
    onDelete: (WaterEntry) -> Unit,
    onSetGoal: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state == null -> ""
                            state.board.isToday -> "Today"
                            else -> Fmt.shortDate(state.board.date)
                        },
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to History")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PlannerColors.Cream),
            )
        },
    ) { padding ->
        val s = state ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BoardHeader(s.board, onSetGoal)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val twoColumns = maxWidth >= 600.dp
                val blocks = s.board.blocks
                val render: @Composable (PeriodBlock) -> Unit = { block ->
                    PeriodBlockCard(
                        block = block,
                        expanded = block.item.periodId in expanded,
                        quickAmounts = s.quickAmounts,
                        onToggle = { onToggle(block.item.periodId) },
                        onQuick = { amount -> onQuick(block.item.periodId, block.item.name, amount) },
                        onOther = { onAddManual(s.board.date, block.item.periodId) },
                        onEdit = onEditEntry,
                        onDelete = onDelete,
                    )
                }
                if (twoColumns) {
                    // Column-major: the left column holds the first half, so reading order is preserved.
                    val split = (blocks.size + 1) / 2
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            blocks.take(split).forEach { render(it) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            blocks.drop(split).forEach { render(it) }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { blocks.forEach { render(it) } }
                }
            }
            if (s.board.archivedBlocks.isNotEmpty()) {
                Text(
                    "Archived periods",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() }.padding(top = 8.dp),
                )
                s.board.archivedBlocks.forEach { block ->
                    PeriodBlockCard(
                        block = block,
                        expanded = block.item.periodId in expanded,
                        quickAmounts = emptyList(),
                        onToggle = { onToggle(block.item.periodId) },
                        onQuick = {},
                        onOther = {},
                        onEdit = onEditEntry,
                        onDelete = onDelete,
                    )
                }
            }
            if (isDetail && s.board.blocks.any { it.canLog }) {
                OutlinedButton(
                    onClick = { onAddManual(s.board.date, null) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add a water entry for this date")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardHeader(board: DayBoard, onSetGoal: () -> Unit) {
    val progress = board.progress
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Fmt.longDate(board.date), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Fmt.ml(progress.totalMl), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("recorded", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val goal = progress.goalMl
        if (goal != null) {
            Text(
                "Goal ${Fmt.ml(goal)} · ${progress.percent}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { progress.lineFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .semantics { contentDescription = "Goal progress ${progress.percent} percent" },
                color = PlannerColors.Ink,
                trackColor = PlannerColors.Rule,
                drawStopIndicator = {},
            )
            if (progress.reached) {
                Text("Your selected goal is reached.", style = MaterialTheme.typography.bodyMedium)
            }
            if (board.isToday) {
                TextButton(onClick = onSetGoal, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change daily goal") }
            }
        } else if (board.isToday) {
            TextButton(onClick = onSetGoal, modifier = Modifier.heightIn(min = 48.dp)) { Text("Set a daily goal") }
        } else {
            Text(
                "No goal was saved for this date.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = PlannerColors.Rule)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodBlockCard(
    block: PeriodBlock,
    expanded: Boolean,
    quickAmounts: List<Int>,
    onToggle: () -> Unit,
    onQuick: (Int) -> Unit,
    onOther: () -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (WaterEntry) -> Unit,
) {
    val accent = accentFor(block.item.iconKey)
    val status = if (block.count > 0) "Recorded ${Fmt.ml(block.volumeMl)} · ${Fmt.count(block.count)}" else "No water entries."
    Surface(
        color = PlannerColors.Paper,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, PlannerColors.Rule),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(accent.deep))
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PeriodBadge(block.item.iconKey)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            block.item.name + if (block.item.archived) " (archived)" else "",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        block.reminderMinute?.let {
                            Text(
                                "Reminder ${TimeMath.formatMinute(it)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(
                        onClick = onToggle,
                        modifier = Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
                    ) {
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "Hide ${block.item.name} details" else "Show ${block.item.name} details",
                        )
                    }
                }
                if (block.canLog && !expanded) {
                    OutlinedButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add Water")
                    }
                }
                if (expanded) {
                    if (block.canLog) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            quickAmounts.forEach { amount ->
                                FilledTonalButton(
                                    onClick = { onQuick(amount) },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics { contentDescription = "Add ${Fmt.ml(amount)} to ${block.item.name}" },
                                ) { Text("+${Fmt.ml(amount)}") }
                            }
                            TextButton(onClick = onOther, modifier = Modifier.heightIn(min = 48.dp)) { Text("Other amount…") }
                        }
                    }
                    if (block.entries.isNotEmpty()) {
                        HorizontalDivider(color = PlannerColors.Rule)
                        block.entries.forEach { entry -> EntryRow(entry, block.item.name, onEdit, onDelete) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: WaterEntry, periodName: String, onEdit: (Long) -> Unit, onDelete: (WaterEntry) -> Unit) {
    val label = "${Fmt.ml(entry.amountMl)} at ${Fmt.clock(entry.localTime)}"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Fmt.clock(entry.localTime), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
        Text(Fmt.ml(entry.amountMl), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { onEdit(entry.id) }) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit $label in $periodName")
        }
        IconButton(onClick = { onDelete(entry) }) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete $label in $periodName")
        }
    }
}
