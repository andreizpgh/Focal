package app.focal.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.focal.ui.library.LibraryModel
import app.focal.ui.scene.SceneUi
import app.focal.ui.shell.Destination
import app.focal.ui.shell.FocalShell
import app.focal.ui.shell.rememberShellState
import app.focal.ui.state.NoteUiState

/**
 * The window sizes of spec 5.14, each rendered on a display of its own size: a phone in landscape (the dock in the
 * end rail, the two-column scene, the keyboard rows over a landscape keyboard), a tablet held upright (one pane, the
 * column centred and capped at 600 dp) and a wide window (the library and a note side by side).
 */
object AdaptiveShots {
    private val Landscape = DpSize(915.dp, 412.dp)
    private val Tablet = DpSize(800.dp, 1280.dp)
    private val Wide = DpSize(1280.dp, 800.dp)

    val landscape: List<Shot> = buildList {
        addAll(sized("land-library", Landscape) { Library(idleModel()) })
        add(sized("land-empty", Landscape, dark = false) { Library(LibraryModel(loaded = true)) })
        addAll(sized("land-recording", Landscape) { Library(liveModel(paused = false)) })
        addAll(sized("land-scene", Landscape) { SceneFrame(1f, live()) })
        add(sized("land-scene-start", Landscape, dark = false) { SceneFrame(1f, start()) })
        add(sized("land-scene-paused", Landscape, dark = false) { SceneFrame(1f, paused()) })
        add(sized("land-expand-50", Landscape, dark = false) { SceneFrame(0.5f, live()) })
        addAll(sized("land-note", Landscape) { Note(design()) })
        add(sized("land-playing", Landscape, dark = false) { Note(design(), playing = true) })
        addAll(sized("land-edit", Landscape) { Note(design(edited = true), edit = true, scroll = 2 to 0, states = PageStates(focus = true)) })
        add(sized("land-find", Landscape, dark = false) { Note(design(), find = "screen" to "page", nextMatch = true) })
        // The rows ride a landscape keyboard (the query takes focus, so the keyboard opens).
        add(sized("land-find-keyboard", Landscape, dark = false) { Note(design(), find = "screen" to "page", states = PageStates(focus = true)) })
        add(sized("land-search", Landscape, dark = false) { Library(idleModel(), search = searchResults()) })
        add(sized("land-undo", Landscape, dark = false) { Library(idleModel(), feedback = true) })
        add(sized("land-settings", Landscape, dark = false) { Strip(1f) })
    }

    val tablet: List<Shot> = buildList {
        addAll(sized("tablet-library", Tablet) { Library(idleModel()) })
        addAll(sized("tablet-note", Tablet) { Note(design()) })
        add(sized("tablet-scene", Tablet, dark = false) { SceneFrame(1f, live()) })
    }

    val wide: List<Shot> = buildList {
        addAll(sized("wide-library", Wide) { TwoPanes(note = null) })
        addAll(sized("wide-note", Wide) { TwoPanes(note = design()) })
        add(sized("wide-scene", Wide, dark = false) { TwoPanes(note = null, scene = live()) })
        add(sized("wide-settings", Wide, dark = false) { TwoPanes(note = design(), settings = true) })
    }
}

private fun sized(name: String, size: DpSize, content: @Composable () -> Unit): List<Shot> = listOf(
    sized(name, size, dark = false, content),
    sized(name, size, dark = true, content),
)

private fun sized(name: String, size: DpSize, dark: Boolean, content: @Composable () -> Unit): Shot =
    Shot("$name-${if (dark) "dark" else "light"}", dark = dark, width = size.width, height = size.height, content = content)

/**
 * The library and, when [note] is given, that note open beside it; with [scene], the recording scene open over both;
 * with [settings], the settings page slid in over the whole window.
 */
@Composable
private fun TwoPanes(note: NoteUiState?, scene: SceneUi? = null, settings: Boolean = false) {
    val shell = rememberShellState()
    val destination = remember(note?.noteId) { note?.let { Destination.Note(it.noteId) } }
    LaunchedEffect(shell) {
        if (destination != null) shell.open(destination)
        if (scene != null) shell.live.expansion.snapTo(1f)
        if (settings) shell.settings.track(1f)
    }
    val model = if (scene != null) libraryOf(scene) else idleModel()
    FocalShell(
        shell,
        destination = { current ->
            if (current is Destination.Note && note != null) {
                NoteDestination(
                    note, current, playing = false, edit = false, find = null, nextMatch = false, replaced = false,
                    captureElsewhere = false, scroll = null, landing = false,
                )
            } else LibraryDestination(model)
        },
        scene = { if (scene != null) SceneLayer(shell, scene) },
    )
}
