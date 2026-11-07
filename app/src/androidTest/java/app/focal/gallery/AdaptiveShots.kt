package app.focal.gallery

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature
import app.focal.ui.library.LibraryModel
import app.focal.ui.scene.SceneUi
import app.focal.ui.shell.Destination
import app.focal.ui.shell.FocalShell
import app.focal.ui.shell.rememberShellState
import app.focal.ui.state.NoteUiState

/**
 * The window sizes of spec 5.14, each rendered on a display of its own size: a phone in landscape (the dock in the
 * end rail, the two-column scene, the keyboard rows over a landscape keyboard), a tablet held upright (one pane, the
 * column centred and capped at 600 dp) and a wide window (the library and a note side by side, also split at a hinge).
 */
object AdaptiveShots {
    private val Landscape = DpSize(915.dp, 412.dp)
    /** A smaller phone in landscape (360 x 780 dp upright): too narrow for the find rows side by side. */
    private val NarrowLandscape = DpSize(780.dp, 360.dp)
    private val Tablet = DpSize(800.dp, 1280.dp)
    private val Wide = DpSize(1280.dp, 800.dp)
    /** A simulated hinge's width, across the wide window's middle. */
    private val HingeWidth = 28.dp

    val landscape: List<Shot> = buildList {
        addAll(sized("land-library", Landscape) { Library(idleModel()) })
        add(sized("land-empty", Landscape, dark = false) { Library(LibraryModel(loaded = true)) })
        addAll(sized("land-recording", Landscape) { Library(liveModel(paused = false)) })
        // Paused, the rail's circle still widens into a labelled Resume (R1).
        addAll(sized("land-recording-paused", Landscape) { Library(liveModel(paused = true)) })
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
        // Narrower, the rows make one row: the find field, the replace field, the count, ∧ ∨ and Replace all.
        addAll(sized("land-find-narrow", NarrowLandscape) { Note(design(), find = "screen" to "page", nextMatch = true) })
        add(sized("land-find-narrow-keyboard", NarrowLandscape, dark = false) {
            Note(design(), find = "screen" to "page", states = PageStates(focus = true))
        })
        add(sized("land-search", Landscape, dark = false) { Library(idleModel(), search = searchResults()) })
        add(sized("land-undo", Landscape, dark = false) { Library(idleModel(), feedback = true) })
        add(sized("land-settings", Landscape, dark = false) { Strip(1f) })
        // Mirrored: the rail on the left, the live stage's row from the card's right edge.
        add(sized("land-library-rtl", Landscape, dark = false) { Rtl { Library(idleModel()) } })
        add(sized("land-recording-paused-rtl", Landscape, dark = false) { Rtl { Library(liveModel(paused = true)) } })
        add(sized("land-scene-paused-rtl", Landscape, dark = false) { Rtl { SceneFrame(1f, paused()) } })
    }

    val tablet: List<Shot> = buildList {
        addAll(sized("tablet-library", Tablet) { Library(idleModel()) })
        addAll(sized("tablet-note", Tablet) { Note(design()) })
        add(sized("tablet-scene", Tablet, dark = false) { SceneFrame(1f, live()) })
        add(sized("tablet-scene-first-text", Tablet, dark = false) { SceneFrame(1f, firstText()) })
    }

    val wide: List<Shot> = buildList {
        addAll(sized("wide-library", Wide) { TwoPanes(note = null) })
        addAll(sized("wide-note", Wide) { TwoPanes(note = design()) })
        add(sized("wide-scene", Wide, dark = false) { TwoPanes(note = null, scene = live()) })
        add(sized("wide-settings", Wide, dark = false) { TwoPanes(note = design(), settings = true) })
        // Two screens joined by a hinge (a simulated androidx.window fold, drawn black where the hinge hides the
        // display): the library's pane ends at it and the note starts after it, its dock with it.
        addAll(sized("wide-hinge-note", Wide) { TwoPanes(note = design(), hinge = HingeWidth) })
        add(sized("wide-hinge-library", Wide, dark = false) { TwoPanes(note = null, hinge = HingeWidth) })
        add(sized("wide-hinge-note-rtl", Wide, dark = false) { Rtl { TwoPanes(note = design(), hinge = HingeWidth) } })
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
 * with [settings], the settings page slid in over the whole window; with [hinge], a vertical hinge that wide across
 * the window's middle, reported as androidx.window would (window-testing's FoldingFeature) and drawn black.
 */
@Composable
private fun TwoPanes(note: NoteUiState?, scene: SceneUi? = null, settings: Boolean = false, hinge: Dp? = null) {
    if (hinge == null) {
        TwoPanesShell(note, scene, settings, emptyList())
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val features = remember(maxWidth, maxHeight, hinge, density) {
            with(density) {
                val window = Rect(0, 0, maxWidth.roundToPx(), maxHeight.roundToPx())
                listOf(FoldingFeature(window, size = hinge.roundToPx(), state = FoldingFeature.State.FLAT, orientation = FoldingFeature.Orientation.VERTICAL))
            }
        }
        TwoPanesShell(note, scene, settings, features)
        Box(Modifier.absoluteOffset(x = (maxWidth - hinge) / 2).width(hinge).fillMaxHeight().background(Color.Black))
    }
}

@Composable
private fun TwoPanesShell(note: NoteUiState?, scene: SceneUi?, settings: Boolean, features: List<DisplayFeature>) {
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
        displayFeatures = features,
        scene = { if (scene != null) SceneLayer(shell, scene) },
    )
}
