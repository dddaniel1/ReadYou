package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.flowArticleListViewMode
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalFlowArticleListViewMode =
    compositionLocalOf<FlowArticleListViewModePreference> {
        FlowArticleListViewModePreference.default
    }

sealed class FlowArticleListViewModePreference(val value: Int) : Preference() {
    object List : FlowArticleListViewModePreference(0)
    object Gallery : FlowArticleListViewModePreference(1)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(
                DataStoreKey.flowArticleListViewMode,
                value
            )
        }
    }

    companion object {

        val default = List
        val values = listOf(List, Gallery)

        fun fromPreferences(preferences: Preferences) =
            when (
                preferences[
                    DataStoreKey.keys[flowArticleListViewMode]?.key as Preferences.Key<Int>
                ]
            ) {
                0 -> List
                1 -> Gallery
                else -> default
            }
    }
}
