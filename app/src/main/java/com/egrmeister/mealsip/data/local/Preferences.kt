package com.egrmeister.mealsip.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.egrmeister.mealsip.domain.logging.EntryValidation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Quick amounts and lightweight UI state (DataStore). Records and periods live in Room. */
data class AppPrefs(
    val quickAmounts: List<Int> = DEFAULT_QUICK_AMOUNTS,
    val setupDone: Boolean = false,
    val notificationPromptShown: Boolean = false,
) {
    companion object {
        val DEFAULT_QUICK_AMOUNTS = listOf(150, 250, 500)
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mealsip_prefs")

class PreferencesStore(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val QUICK = listOf(intPreferencesKey("quick_1"), intPreferencesKey("quick_2"), intPreferencesKey("quick_3"))
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val PROMPTED = booleanPreferencesKey("notification_prompt_shown")
    }

    val prefs: Flow<AppPrefs> = store.data.map { p ->
        AppPrefs(
            quickAmounts = Keys.QUICK.mapIndexed { i, key ->
                p[key]?.takeIf { EntryValidation.validAmount(it) } ?: AppPrefs.DEFAULT_QUICK_AMOUNTS[i]
            },
            setupDone = p[Keys.SETUP_DONE] ?: false,
            notificationPromptShown = p[Keys.PROMPTED] ?: false,
        )
    }

    suspend fun current(): AppPrefs = prefs.first()

    /** Saves three quick amounts; each must be 1–5,000 whole mL. */
    suspend fun setQuickAmounts(amounts: List<Int>): Boolean {
        if (amounts.size != Keys.QUICK.size || !amounts.all { EntryValidation.validAmount(it) }) return false
        store.edit { p -> Keys.QUICK.forEachIndexed { i, key -> p[key] = amounts[i] } }
        return true
    }

    suspend fun markSetupDone() {
        store.edit { it[Keys.SETUP_DONE] = true }
    }

    suspend fun markNotificationPromptShown() {
        store.edit { it[Keys.PROMPTED] = true }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }
}
