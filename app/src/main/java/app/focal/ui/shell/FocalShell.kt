package app.focal.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import app.focal.ui.components.BottomColumn
import app.focal.ui.components.BottomColumnState
import app.focal.ui.components.Dock
import app.focal.ui.components.DockActions
import app.focal.ui.components.DockState
import app.focal.ui.components.rememberBottomColumnState
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.smoothstep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Commands from outside the composition: the activity's keyboard shortcuts. */
enum class ShellCommand { OpenSettings }

/**
 * The app's navigation and root-level surfaces (spec 5): the back stack (Library, Note, Licenses), the settings strip
 * of the library, the recording overlay, the bottom column (dock + feedback pill, drawn above both the pages and the
 * overlay) and the system bars' appearance.
 */
@Stable
class ShellState internal constructor(
    val backStack: SnapshotStateList<Destination>,
    val settings: SettingsStripState,
    val live: LiveSurfaceState,
    val bottom: BottomColumnState,
    val bars: SystemBarsState,
    val pages: PageTransitions,
) {
    val current: Destination get() = backStack.last()

    /** Opens [destination] on top; [motion] says how (spec 6.1): a fade-through unless it grows out of the library. */
    fun open(destination: Destination, motion: PageMotion = PageMotion.FadeThrough) {
        if (backStack.lastOrNull() == destination) return
        pages.push(destination, motion)
        backStack.add(destination)
    }

    /** Back from the top page: it folds into where it came from if that is on screen, else it fades through. */
    fun back() {
        if (backStack.size <= 1) return
        pages.pop(backStack.last())
        backStack.removeAt(backStack.lastIndex)
    }

    /** What each destination asked the bottom column to show; only the current destination's is drawn. */
    internal val bottomContent = mutableStateMapOf<Destination, BottomContent>()

    /** The scene's dock (Pause | Finish in the scene's dress), shown over any page while the scene is up. */
    internal var sceneBottom by mutableStateOf<BottomContent?>(null)
}

val LocalShell = staticCompositionLocalOf<ShellState> { error("No ShellState: wrap the screen in FocalShell.") }

/** [capturing] says whether a recording is live, so an open scene survives recreation only while one runs. */
@Composable
fun rememberShellState(capturing: () -> Boolean = { false }): ShellState {
    val backStack = rememberSaveable(saver = Destination.BackStackSaver) { SnapshotStateList<Destination>().apply { add(Destination.Library) } }
    val settings = rememberSettingsStripState()
    val live = rememberLiveSurfaceState(capturing)
    val bottom = rememberBottomColumnState()
    return remember(backStack, settings, live, bottom) { ShellState(backStack, settings, live, bottom, SystemBarsState(), PageTransitions()) }
}

/** The bottom column's contents for one destination. */
@Immutable
internal class BottomContent(
    val dock: DockState,
    val actions: DockActions,
    val strip: (@Composable () -> Unit)?,
    val rows: (@Composable ColumnScope.() -> Unit)?,
    val dockHidden: Boolean,
)

/**
 * A destination tells the root's bottom column what to show while it is the current page: the dock's state and
 * actions, the player [strip], keyboard-riding [rows]. The dock hides while rows are open, and whenever
 * [dockHidden] says so (an inline rename's keyboard); a closing row can ask for the dock back before it leaves, so
 * the dock fades in under it without a dip.
 */
@Composable
fun DestinationBottom(
    destination: Destination,
    dock: DockState,
    actions: DockActions,
    strip: (@Composable () -> Unit)? = null,
    rows: (@Composable ColumnScope.() -> Unit)? = null,
    dockHidden: Boolean = rows != null,
) {
    val shell = LocalShell.current
    val content = BottomContent(dock, actions, strip, rows, dockHidden)
    SideEffect { shell.bottomContent[destination] = content }
}

/**
 * While the scene is up, the root dock carries its controls (spec 4.3: Pause | Finish, dressed by the material's
 * edge), whatever page is under it. The scene registers them here while it is composed.
 */
@Composable
fun SceneBottom(dock: DockState, actions: DockActions) {
    val shell = LocalShell.current
    val content = BottomContent(dock, actions, strip = null, rows = null, dockHidden = false)
    SideEffect { shell.sceneBottom = content }
    DisposableEffect(shell) { onDispose { shell.sceneBottom = null } }
}

/**
 * The root: Navigation 3's [NavDisplay] for the pages, the recording overlay above them, and the bottom column above
 * both, so the dock never moves while the scene grows behind it. [destination] draws a page; [scene] draws the
 * recording overlay while the expansion is above 0. Behind the morph the pages recede (scale 1 - 0.06 p about the
 * window's centre, a black dim of 0.30 p, spec 6.4); behind the open scene they are inert.
 */
@Composable
fun FocalShell(
    shell: ShellState,
    destination: @Composable (Destination) -> Unit,
    modifier: Modifier = Modifier,
    scene: @Composable () -> Unit = {},
) {
    val colors = FocalTheme.colors
    CompositionLocalProvider(LocalShell provides shell) {
        val live = shell.live
        val behindScene by remember(live) { derivedStateOf { live.open } }
        Box(modifier.fillMaxSize().background(colors.paper)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (behindScene) Modifier.clearAndSetSemantics { }.focusProperties { canFocus = false } else Modifier)
                    // The dim covers the whole window, the paper the scaled pages leave at its edges included.
                    .drawWithContent {
                        drawContent()
                        val p = live.expansion.value
                        if (live.spatial && live.landingNote == null && p > 0f) drawRect(Color.Black, alpha = PAGES_DIM * p)
                    }
                    .background(colors.paper)
                    .graphicsLayer {
                        val scale = live.pagesScale()
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                // Every change is a bare swap; each page draws its own part from the upper page's progress, and the
                // container transforms draw above both (PageTransitions).
                NavDisplay(
                    backStack = shell.backStack,
                    onBack = { shell.back() },
                    transitionSpec = { pageSwap() },
                    popTransitionSpec = { pageSwap() },
                    predictivePopTransitionSpec = { edge ->
                        shell.pages.beginPredictive(shell.current, edge)
                        pageSwap()
                    },
                    entryProvider = { key -> NavEntry(key) { PageFrame(key, shell.pages) { destination(key) } } },
                )
                PageTransformOverlay(shell.pages, shell.live)
            }
            LiveSurfaceHost(shell.live, scene)
            ShellBottom(shell)
        }
    }
    // Pages that left the back stack drop what they registered.
    LaunchedEffect(shell.backStack.toList()) { shell.bottomContent.keys.retainAll(shell.backStack.toSet()) }
}

/** The last bottom content shown: a page that is arriving registers its own a frame later. */
private class ShownBottom { var content: BottomContent? = null }

@Composable
private fun ShellBottom(shell: ShellState) {
    val current = shell.current
    val shown = remember { ShownBottom() }
    // The scene's controls while it is up and not settling into the note's page (T7).
    val scene = shell.sceneBottom?.takeIf { shell.live.visible && shell.live.landingNote == null }
    val content = scene ?: shell.bottomContent[current]?.also { shown.content = it } ?: shown.content?.takeIf { current.hasDock } ?: return
    val onLibrary = current == Destination.Library
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduced = LocalReducedMotion.current
    // With settings fully open the library's dock and feedback pill are off screen: inert, like the page.
    val behindSettings by remember(shell) { derivedStateOf { shell.settings.progress.value >= 1f } }
    BottomColumn(
        state = shell.bottom,
        modifier = Modifier
            .fillMaxSize()
            .then(if (onLibrary && behindSettings) Modifier.clearAndSetSemantics { }.focusProperties { canFocus = false } else Modifier)
            .graphicsLayer {
                // The library's dock and feedback pill move with the library page (spec 4.23.2).
                if (onLibrary) {
                    val s = shell.settings.progress.value
                    if (reduced) alpha = 1 - smoothstep(0f, HALF, s)
                    else translationX = (if (rtl) 1 else -1) * s * settingsTravel(size.width, this)
                }
            },
        dock = { Dock(content.dock, content.actions, materialEdge = shell.live.materialEdge) },
        strip = content.strip,
        rows = content.rows,
        dockHidden = content.dockHidden,
    )
}

private const val HALF = 0.5f
/** The pages behind the morph darken (spec 2.8.2 `libraryDim`). */
private const val PAGES_DIM = 0.30f

/** Runs the activity's keyboard shortcuts: Ctrl+, opens settings from the library while the scene is folded. */
@Composable
fun ShellCommands(shell: ShellState, commands: Flow<ShellCommand>) {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    LaunchedEffect(commands) {
        commands.collect { command ->
            when (command) {
                ShellCommand.OpenSettings -> if (shell.current == Destination.Library && !shell.live.visible && shell.settings.armed) {
                    scope.launch { shell.settings.open(reduced) }
                }
            }
        }
    }
}
