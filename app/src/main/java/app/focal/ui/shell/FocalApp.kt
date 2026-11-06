package app.focal.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focal.data.Preferences
import app.focal.ui.components.DockActions
import app.focal.ui.components.DockState
import app.focal.ui.design.FocalTheme
import app.focal.ui.state.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * The new interface's entry: the theme from the app's setting (read synchronously, so the first frame already has
 * the right colours), the system bars, the shell and its destinations. The library, note, licences and recording
 * screens are filled in by their own stages; until then each destination is an empty page with its dock.
 */
@Composable
fun FocalApp(preferences: Preferences, commands: Flow<ShellCommand>) {
    val settings by preferences.settings.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val systemDark = remember(configuration) { NightMode.systemDark() }
    val dark = NightMode.isDark(ThemeMode.of(settings.theme), systemDark)
    FocalTheme(dark) {
        val shell = rememberShellState()
        SystemBarsEffect(shell.bars, dark)
        ShellCommands(shell, commands)
        FocalShell(shell, destination = { destination ->
            when (destination) {
                Destination.Library -> LibraryDestination(shell)
                is Destination.Note -> NoteDestination(destination)
                Destination.Licenses -> LicensesDestination()
            }
        })
    }
}

/** The library page and the settings page beside it. Their screens arrive with the library stage. */
@Composable
private fun LibraryDestination(shell: ShellState) {
    DestinationBottom(Destination.Library, DockState.LibraryEmpty, remember { DockActions() })
    SettingsStrip(shell.settings, library = { Box(Modifier.fillMaxSize()) }, settings = { Box(Modifier.fillMaxSize()) })
}

/** A note page. Its screen arrives with the note stage. */
@Composable
private fun NoteDestination(destination: Destination.Note) {
    DestinationBottom(destination, DockState.LibraryEmpty, remember { DockActions() })
    Box(Modifier.fillMaxSize())
}

/** The licences page. Its screen arrives with the settings work of the library stage. */
@Composable
private fun LicensesDestination() {
    Box(Modifier.fillMaxSize())
}
