package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.carlink.BuildConfig

/**
 * Tabs shown in the Settings screen's navigation rail.
 *
 * @property title User-facing tab label. NOTE: hardcoded English; not localized.
 * @property icon Material icon rendered alongside [title] in the rail.
 *
 * Filtering contract: [visible] is the authoritative list of entries the UI
 * should render; callers must iterate [visible] (not [entries]) so build-flavor
 * gating is honored.
 *
 * Cross-file contract: adding/removing an entry requires updates at
 * SettingsScreen.kt:263-267 (exhaustive `when`), :102 (default `selectedTab`),
 * and :172 (tab rendering loop). Declaration order below is load-bearing.
 */
enum class SettingsTab(
    val title: String,
    val icon: ImageVector,
) {
    // Order determines tab display order in the navigation rail.
    PHONES("Phones", Icons.Default.PhoneAndroid),
    CONTROL("Control", Icons.Default.Settings),
    LOGS("Logs", Icons.AutoMirrored.Filled.Article),
    ;

    companion object {
        /**
         * Entries filtered by current build flavor.
         *
         * Recomputed on every access (called per recomposition at
         * SettingsScreen.kt:172); intentional and cheap — three-entry filter
         * over a compile-time-constant condition, results are not cached.
         *
         * Caveat: no invariant pins `selectedTab in visible`. If a caller ends
         * up with a `selectedTab` absent from `visible` (e.g. LOGS while hidden),
         * the rail will show no selection while the content pane still renders
         * that tab.
         */
        val visible: List<SettingsTab>
            get() =
                entries.filter { tab ->
                    when (tab) {
                        // SUSPICIOUS: inverted predicate — hides LOGS in DEBUG,
                        // shows it in RELEASE. Reads backwards for a developer-aid
                        // tab; load-bearing, preserve as-is but re-verify product
                        // intent before touching.
                        LOGS -> !BuildConfig.DEBUG
                        else -> true
                    }
                }
    }
}
