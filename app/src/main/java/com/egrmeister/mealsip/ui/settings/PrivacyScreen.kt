package com.egrmeister.mealsip.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.egrmeister.mealsip.ui.theme.PlannerColors

private val sections = listOf(
    "What MealSip stores" to
        "Water entries (amount, the period you chose, date, time and time zone), your period names, icons, order and " +
        "reminder times, effective-dated daily goals, quick amounts and a few display preferences.",
    "Where it is stored" to
        "Only in MealSip's private app storage on this device. There is no account, no server and no cloud " +
        "synchronization. The app does not request internet access.",
    "Backup and transfer" to
        "Android cloud backup and device-to-device transfer are disabled for MealSip, so records are not copied off " +
        "this device. Uninstalling the app or using Clear all local data removes them.",
    "Notifications" to
        "Reminders are optional, off by default and generated on this device. They never record water for you and do " +
        "not imply that a meal happened. MealSip asks for notification permission only when you first enable a reminder.",
    "What MealSip does not do" to
        "No ads, analytics, tracking, payments, location, sensors or Health Connect. MealSip does not track food, " +
        "calories, diets or weight and gives no medical advice. Period names are only labels for organizing your records.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PlannerColors.Cream),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            sections.forEach { (title, body) ->
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
