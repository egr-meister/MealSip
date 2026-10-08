package com.egrmeister.mealsip.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.egrmeister.mealsip.AppContainer
import com.egrmeister.mealsip.domain.history.BoardBuilder
import com.egrmeister.mealsip.domain.history.DaySummary
import com.egrmeister.mealsip.ui.components.Fmt
import com.egrmeister.mealsip.ui.components.PeriodBadge
import com.egrmeister.mealsip.ui.components.containerViewModel
import com.egrmeister.mealsip.ui.theme.PlannerColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class HistoryState(
    val end: LocalDate,
    val start: LocalDate,
    val today: LocalDate,
    val days: List<DaySummary>,
) {
    val canGoLater: Boolean get() = end.isBefore(today)
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val c: AppContainer, private val saved: SavedStateHandle) : ViewModel() {
    /** Null end means "the seven days ending today" (follows midnight). */
    private val endOverride = MutableStateFlow(saved.get<String>(KEY_END)?.let { runCatching { LocalDate.parse(it) }.getOrNull() })

    val state: StateFlow<HistoryState?> = combine(endOverride, c.todayProvider.today) { override, today ->
        val end = override?.takeIf { !it.isAfter(today) } ?: today
        end to today
    }.flatMapLatest { (end, today) ->
        val start = end.minusDays(6)
        combine(
            c.store.observeEntriesBetween(start, end),
            c.store.observeSnapshotsBetween(start, end),
            c.store.observePeriods(),
            c.store.observeVersions(),
            c.store.observeGoals(),
        ) { entries, snapshots, periods, versions, goals ->
            val byDate = entries.groupBy { it.localDate }
            val days = BoardBuilder.range(end).map { date ->
                val board = BoardBuilder.build(
                    date, today, byDate[date].orEmpty(), periods, snapshots[date].orEmpty(), versions, goals,
                )
                BoardBuilder.summary(board)
            }
            HistoryState(end, start, today, days)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun earlier() {
        val current = state.value?.end ?: c.time.today()
        setEnd(current.minusDays(7))
    }

    fun later() {
        val s = state.value ?: return
        val next = s.end.plusDays(7)
        setEnd(if (next.isBefore(s.today)) next else null)
    }

    private fun setEnd(date: LocalDate?) {
        endOverride.value = date
        saved[KEY_END] = date?.toString()
    }

    private companion object {
        const val KEY_END = "history_end"
    }
}

@Composable
fun HistoryRoute(container: AppContainer, onOpenDay: (LocalDate) -> Unit) {
    val vm = containerViewModel { HistoryViewModel(container, it) }
    val state by vm.state.collectAsStateWithLifecycle()
    HistoryScreen(state, onEarlier = vm::earlier, onLater = vm::later, onOpenDay = onOpenDay)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryState?,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PlannerColors.Cream),
            )
        },
    ) { padding ->
        val s = state ?: return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onEarlier, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                        Text("Earlier")
                    }
                    Text(
                        "${Fmt.shortDate(s.start)} – ${Fmt.shortDate(s.end)}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    TextButton(onClick = onLater, enabled = s.canGoLater, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Later")
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
            items(s.days, key = { it.date.toString() }) { day ->
                DayCard(day, isToday = day.date == s.today, onClick = { onOpenDay(day.date) })
            }
        }
    }
}

@Composable
private fun DayCard(day: DaySummary, isToday: Boolean, onClick: () -> Unit) {
    Surface(
        color = PlannerColors.Paper,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, PlannerColors.Rule),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open day", role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Fmt.shortDate(day.date) + if (isToday) " · Today" else "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (day.hasRecords) {
                    Text(Fmt.ml(day.totalMl), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            if (!day.hasRecords) {
                Text("No records", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                day.goalMl?.let { Text("Goal ${Fmt.ml(it)}", style = MaterialTheme.typography.bodySmall) }
                return@Column
            }
            Text(
                listOfNotNull(
                    Fmt.count(day.entryCount),
                    day.goalMl?.let { "goal ${Fmt.ml(it)} (${day.totalMl * 100 / it}%)" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            day.breakdown.filter { it.count > 0 || !it.archived }.forEach { share ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PeriodBadge(share.iconKey, size = 24.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        share.name + if (share.archived) " (archived)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (share.count > 0) "${Fmt.ml(share.volumeMl)} · ${share.count}" else "No entries",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
