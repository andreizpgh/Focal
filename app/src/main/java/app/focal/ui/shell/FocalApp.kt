package app.focal.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.focal.data.Preferences
import app.focal.recording.AudioLevel
import app.focal.recording.RecordingState
import app.focal.ui.components.LiveMeter
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.library.LibraryRoute
import app.focal.ui.library.rememberLibraryPageStates
import app.focal.ui.note.NoteRoute
import app.focal.ui.scene.SceneEffects
import app.focal.ui.scene.SceneRoute
import app.focal.ui.settings.LicensesPage
import app.focal.ui.state.LibraryViewModel
import app.focal.ui.state.RecordingViewModel
import app.focal.ui.state.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The new interface's entry: the theme from the app's setting (read synchronously, so the first frame already has
 * the right colours), the system bars, the shell and its destinations, the recording surface, Record's permission flow
 * and the app-wide effects.
 */
@Composable
fun FocalApp(preferences: Preferences, commands: Flow<ShellCommand>, keys: PageKeys = PageKeys()) {
    CompositionLocalProvider(LocalPageKeys provides keys) { FocalAppContent(preferences, commands) }
}

@Composable
private fun FocalAppContent(preferences: Preferences, commands: Flow<ShellCommand>) {
    val settings by preferences.settings.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val systemDark = remember(configuration) { NightMode.systemDark() }
    val dark = NightMode.isDark(ThemeMode.of(settings.theme), systemDark)
    FocalTheme(dark) {
        val shell = rememberShellState(capturing = { RecordingState.live.value.capturing })
        val library: LibraryViewModel = viewModel()
        val recording: RecordingViewModel = viewModel()
        val scope = rememberCoroutineScope()
        val reduced = LocalReducedMotion.current
        // One meter for the stage's wave and the scene's: they hand over at p = 0 drawing the same levels.
        val meter = remember { LiveMeter { AudioLevel.current } }
        // Record (spec 5.3.1): capture starts at once; the scene grows out of the stage (T1′) or, in the empty
        // library, out of the resting instrument (T1′e), in parallel. Only after the microphone is granted.
        // A note's own ● continues that note (spec 5.5): the same capture, into the note on screen; the scene comes in
        // over its page, where there is no stage to grow out of.
        val recordFlow = rememberRecordFlow { appendTo, fromMs ->
            val running = recording.state.value.live
            recording.record(appendTo, fromMs)
            // Only a start that was accepted opens the scene: a refusal says why on the page it was asked from.
            if (recording.starting && !running && !shell.live.visible) scope.launch {
                if (appendTo == null) shell.record(fromEmpty = library.state.value.empty, reduced) else shell.recordMore(reduced)
            }
        }
        // The library's saved state lives here, above the layout: one pane and two compose the library at different
        // places in the tree, and a rememberSaveable is found again only where it was saved.
        val libraryStates = rememberLibraryPageStates(query = { library.search.value.query })
        SystemBarsEffect(shell.bars, dark)
        ShellCommands(shell, commands)
        AppEffects(
            shell, library, recording,
            // "Try again" repeats what failed: Resume while a recording waits for the microphone (a held recording says
            // the microphone is busy too), else the same Record, so a note that was to continue is not started as a new one.
            retry = {
                if (recording.state.value.live) recording.resume()
                else recording.lastRequest.let { (note, fromMs) -> recordFlow.record(note, fromMs) }
            },
        ) { recordFlow.record() }
        SceneEffects(shell, recording, scope)
        FocalShell(
            shell,
            destination = { destination ->
                when (destination) {
                    Destination.Library -> LibraryRoute({ recordFlow.record() }, sheetOpen = recordFlow.sheet != null, meter = meter, states = libraryStates)
                    is Destination.Note -> NoteRoute(
                        destination, scope, recordMore = { fromMs -> recordFlow.record(destination.id, fromMs) },
                        // The page develops under the settling scene (T7) or out of the live stage (T7b).
                        landing = remember(destination) { shell.live.landingNote == destination.id || shell.pages.develops(destination) },
                    )
                    Destination.Licenses -> LicensesPage(onBack = shell::back)
                }
            },
            // A separating vertical fold or hinge splits two panes at itself (spec 5.14).
            displayFeatures = rememberDisplayFeatures(),
            scene = { SceneRoute(shell, recording, library, meter, scope) },
        )
        RecordFlowSheet(recordFlow)
    }
}
