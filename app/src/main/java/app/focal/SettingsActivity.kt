package app.focal

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.focal.ui.design.FocalTheme
import app.focal.ui.settings.LicensesPage
import app.focal.ui.settings.SettingsRoute
import app.focal.ui.shell.FocalWindow
import app.focal.ui.shell.NightMode
import app.focal.ui.shell.SystemBarsEffect
import app.focal.ui.shell.SystemBarsState
import app.focal.ui.state.ThemeMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Focal's settings from outside the app (spec 4.23.6): Android shows a gear in App info for an activity that
 * handles `APPLICATION_PREFERENCES`. It hosts the same settings page (and the licences) on paper, opens at its
 * content, and ← or Back finishes it, back to App info (its own empty task affinity keeps it off the library's task).
 */
class SettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val preferences = (newBase.applicationContext as FocalApplication).preferences
        NightMode.legacyOverride(ThemeMode.of(preferences.settings.value.theme))?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = (application as FocalApplication).preferences
        FocalWindow.apply(this, ThemeMode.of(preferences.settings.value.theme))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                preferences.settings.map { ThemeMode.of(it.theme) }.distinctUntilChanged().drop(1).collect { FocalWindow.apply(this@SettingsActivity, it) }
            }
        }
        setContent {
            val settings by preferences.settings.collectAsStateWithLifecycle()
            val configuration = LocalConfiguration.current
            val systemDark = remember(configuration) { NightMode.systemDark() }
            val dark = NightMode.isDark(ThemeMode.of(settings.theme), systemDark)
            FocalTheme(dark) {
                SystemBarsEffect(remember { SystemBarsState() }, dark)
                var licenses by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = licenses) { licenses = false }
                Box(Modifier.fillMaxSize().background(FocalTheme.colors.paper)) {
                    if (licenses) LicensesPage(onBack = { licenses = false })
                    else SettingsRoute(onBack = ::finish, onLicenses = { licenses = true })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening settings by any path ends the peeks (spec 4.23.5).
        (application as FocalApplication).preferences.settingsOpened = true
    }
}
