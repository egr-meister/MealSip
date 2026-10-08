package com.egrmeister.mealsip.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.egrmeister.mealsip.domain.goals.GoalLookup
import com.egrmeister.mealsip.domain.goals.GoalStart
import com.egrmeister.mealsip.domain.model.Outcome

val GOAL_EXAMPLES = listOf(1_500, 2_000, 2_500)

/** Editable goal examples (not personalized recommendations). */
@Composable
fun GoalExamples(onPick: (Int) -> Unit) {
    Column {
        Text(
            "Examples — not personalized recommendations:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GOAL_EXAMPLES.forEach { ml ->
                SuggestionChip(onClick = { onPick(ml) }, label = { Text(Fmt.ml(ml)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}

/**
 * Goal editor with "Apply today" / "Apply tomorrow". Earlier dates are never rewritten.
 * [showStart] is false where only today makes sense (no saved goal yet).
 */
@Composable
fun GoalDialog(
    initialGoal: Int?,
    onDismiss: () -> Unit,
    onSave: (goalMl: Int, start: GoalStart) -> Unit,
    showStart: Boolean = true,
) {
    var text by rememberSaveable { mutableStateOf(initialGoal?.toString() ?: "") }
    var startToday by rememberSaveable { mutableStateOf(true) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                if (showStart) {
                    Column(Modifier.selectableGroup()) {
                        StartOption("Apply today", startToday) { startToday = true }
                        StartOption("Apply tomorrow", !startToday) { startToday = false }
                    }
                    Text(
                        "Earlier days keep the goal that was saved for them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val parsed = GoalLookup.parseGoal(text)) {
                    is Outcome.Ok -> onSave(parsed.value, if (startToday || !showStart) GoalStart.TODAY else GoalStart.TOMORROW)
                    is Outcome.Invalid -> error = parsed.message
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StartOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
