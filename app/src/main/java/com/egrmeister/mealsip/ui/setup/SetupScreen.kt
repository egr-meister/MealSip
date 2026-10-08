package com.egrmeister.mealsip.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.egrmeister.mealsip.AppContainer
import com.egrmeister.mealsip.domain.goals.GoalLookup
import com.egrmeister.mealsip.domain.goals.GoalStart
import com.egrmeister.mealsip.domain.model.Outcome
import com.egrmeister.mealsip.domain.model.StarterPeriods
import com.egrmeister.mealsip.domain.time.TimeMath
import com.egrmeister.mealsip.ui.components.GoalExamples
import com.egrmeister.mealsip.ui.components.PeriodBadge
import kotlinx.coroutines.launch

/** First launch: starter periods are already in place; the user may choose a goal or skip it. */
@Composable
fun SetupRoute(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }

    fun finish(goal: Int?) {
        if (busy) return
        busy = true
        scope.launch {
            container.periods.seedIfEmpty()
            if (goal != null) container.goals.setGoal(goal, GoalStart.TODAY)
            container.preferences.markSetupDone()
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Welcome to MealSip", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                "Record water in named periods of your day. The periods are just labels — you can rename, reorder or " +
                    "add them later. All reminders start off.",
                style = MaterialTheme.typography.bodyMedium,
            )
            StarterPeriods.ALL.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PeriodBadge(p.iconKey, size = 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(p.name, modifier = Modifier.weight(1f))
                    Text("Example reminder ${TimeMath.formatMinute(p.reminderMinute)} (off)", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Daily goal (optional)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(6); error = null },
                label = { Text("Goal (mL)") },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: "100–10,000 mL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            GoalExamples(onPick = { text = it.toString(); error = null })
            Button(
                onClick = {
                    when (val r = GoalLookup.parseGoal(text)) {
                        is Outcome.Ok -> finish(r.value)
                        is Outcome.Invalid -> error = r.message
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Save goal and start") }
            TextButton(
                onClick = { finish(null) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Skip — log without a goal") }
        }
    }
}
