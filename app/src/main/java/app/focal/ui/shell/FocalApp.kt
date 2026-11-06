package app.focal.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.focal.data.Preferences
import app.focal.ui.components.DockActions
import app.focal.ui.components.DockState
import app.focal.ui.design.FocalTheme
import app.focal.ui.library.LibraryRoute
import app.focal.ui.settings.LicensesPage
import app.focal.ui.state.LibraryViewModel
import app.focal.ui.state.RecordingViewModel
import app.focal.ui.state.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * The new interface's entry: the theme from the app's setting (read synchronously, so the first frame already has
 * the right colours), the system bars, the shell and its destinations, Record's permission flow and the app-wide
 * effects. The note and recording screens are filled in by their own stages.
 */
@Composable
fun FocalApp(preferences: Preferences, commands: Flow<ShellCommand>) {
    val settings by preferences.settings.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val systemDark = remember(configuration) { NightMode.systemDark() }
    val dark = NightMode.isDark(ThemeMode.of(settings.theme), systemDark)
    FocalTheme(dark) {
        val shell = rememberShellState()
        val library: LibraryViewModel = viewModel()
        val recording: RecordingViewModel = viewModel()
        val recordFlow = rememberRecordFlow(recording::record)
        SystemBarsEffect(shell.bars, dark)
        ShellCommands(shell, commands)
        AppEffects(shell, library, recording, recordFlow::record)
        FocalShell(shell, destination = { destination ->
            when (destination) {
                Destination.Library -> LibraryRoute(recordFlow::record, sheetOpen = recordFlow.sheet != null)
                is Destination.Note -> NoteDestination(destination)
                Destination.Licenses -> LicensesPage(onBack = shell::back)
            }
        })
        RecordFlowSheet(recordFlow)
    }
}

/** A note page. Its screen arrives with the note stage. */
@Composable
private fun NoteDestination(destination: Destination.Note) {
    DestinationBottom(destination, DockState.LibraryEmpty, remember { DockActions() })
    Box(Modifier.fillMaxSize())
}
