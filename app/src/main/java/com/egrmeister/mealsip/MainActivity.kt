package com.egrmeister.mealsip

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.egrmeister.mealsip.reminders.Notifications
import com.egrmeister.mealsip.ui.MealSipRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Period to expand on Today after a reminder tap (consumed by the UI). */
    private val openPeriodRequests = MutableStateFlow<OpenPeriodRequest?>(null)
    private var requestCounter = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        val container = (application as MealSipApp).container
        setContent {
            MealSipRoot(container = container, openPeriodRequests = openPeriodRequests)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Foreground return: refresh Today (midnight may have passed) and re-plan reminders.
        val container = (application as MealSipApp).container
        container.todayProvider.refresh()
        lifecycleScope.launch { container.reminders.rescheduleAll() }
    }

    private fun handleIntent(intent: Intent?) {
        val periodId = intent?.getLongExtra(Notifications.EXTRA_OPEN_PERIOD, -1L) ?: -1L
        if (periodId >= 0) {
            requestCounter += 1
            openPeriodRequests.value = OpenPeriodRequest(periodId, requestCounter)
            intent?.removeExtra(Notifications.EXTRA_OPEN_PERIOD)
        }
    }
}

data class OpenPeriodRequest(val periodId: Long, val serial: Long)
