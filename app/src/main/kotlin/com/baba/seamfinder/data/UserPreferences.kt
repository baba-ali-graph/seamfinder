package com.baba.seamfinder.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

enum class AppsLayout {
    LIST,
    GRID,
}

data class BubblePosition(val x: Int, val y: Int)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

class UserPreferences(private val context: Context) {
    val appsLayout: Flow<AppsLayout> = context.dataStore.data.map { prefs ->
        when (prefs[KEY_APPS_LAYOUT]) {
            AppsLayout.GRID.name -> AppsLayout.GRID
            else -> AppsLayout.LIST
        }
    }

    val dockEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DOCK_ENABLED] == true
    }

    suspend fun setAppsLayout(layout: AppsLayout) {
        context.dataStore.edit { prefs ->
            prefs[KEY_APPS_LAYOUT] = layout.name
        }
    }

    suspend fun setDockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DOCK_ENABLED] = enabled
        }
    }

    suspend fun setBubblePosition(x: Int, y: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BUBBLE_X] = x
            prefs[KEY_BUBBLE_Y] = y
        }
    }

    /** Blocking helpers for Service / BootReceiver startup paths. */
    fun isDockEnabledBlocking(): Boolean = runBlocking {
        context.dataStore.data.first()[KEY_DOCK_ENABLED] == true
    }

    fun bubblePositionBlocking(defaultY: Int = 600): BubblePosition = runBlocking {
        val prefs = context.dataStore.data.first()
        BubblePosition(
            x = prefs[KEY_BUBBLE_X] ?: 0,
            y = prefs[KEY_BUBBLE_Y] ?: defaultY,
        )
    }

    companion object {
        private val KEY_APPS_LAYOUT = stringPreferencesKey("apps_layout")
        private val KEY_DOCK_ENABLED = booleanPreferencesKey("dock_enabled")
        private val KEY_BUBBLE_X = intPreferencesKey("bubble_x")
        private val KEY_BUBBLE_Y = intPreferencesKey("bubble_y")
    }
}
