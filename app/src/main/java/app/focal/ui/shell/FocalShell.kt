package app.focal.ui.shell

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import app.focal.ui.components.BottomColumn
import app.focal.ui.components.BottomColumnState
import app.focal.ui.components.Dock
import app.focal.ui.components.DockActions
import app.focal.ui.components.DockState
import app.focal.ui.components.rememberBottomColumnState
import app.focal.ui.design.FocalMotion
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
) {
    val current: Destination get() = backStack.last()

    fun open(destination: Destination) {
        if (backStack.lastOrNull() != destination) backStack.add(destination)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** What each destination asked the bottom column to show; only the current destination's is drawn. */
    internal val bottomContent = mutableStateMapOf<Destination, BottomContent>()
}

val LocalShell = staticCompositionLocalOf<ShellState> { error("No ShellState: wrap the screen in FocalShell.") }

@Composable
fun rememberShellState(): ShellState {
    val backStack = rememberSaveable(saver = Destination.BackStackSaver) { SnapshotStateList<Destination>().apply { add(Destination.Library) } }
    val settings = rememberSettingsStripState()
    val live = rememberLiveSurfaceState()
    val bottom = rememberBottomColumnState()
    return remember(backStack, settings, live, bottom) { ShellState(backStack, settings, live, bottom, SystemBarsState()) }
}

/** The bottom column's contents for one destination. */
@Immutable
internal class BottomContent(
    val dock: DockState,
    val actions: DockActions,
    val strip: (@Composable () -> Unit)?,
    val rows: (@Composable ColumnScope.() -> Unit)?,
)

/**
 * A destination tells the root's bottom column what to show while it is the current page: the dock's state and
 * actions, the player [strip], keyboard-riding [rows] (the dock hides while they are open).
 */
@Composable
fun DestinationBottom(
    destination: Destination,
    dock: DockState,
    actions: DockActions,
    strip: (@Composable () -> Unit)? = null,
    rows: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val shell = LocalShell.current
    val content = BottomContent(dock, actions, strip, rows)
    SideEffect { shell.bottomContent[destination] = content }
}

/**
 * The root: Navigation 3's [NavDisplay] for the pages, the recording overlay above them, and the bottom column above
 * both, so the dock never moves while the scene grows behind it. [destination] draws a page; [scene] draws the
 * recording overlay while the expansion is above 0.
 */
@Composable
fun FocalShell(
    shell: ShellState,
    destination: @Composable (Destination) -> Unit,
    modifier: Modifier = Modifier,
    scene: @Composable () -> Unit = {},
) {
    val colors = FocalTheme.colors
    val backInset = with(LocalDensity.current) { PREDICTIVE_SHIFT_INSET.roundToPx() }
    CompositionLocalProvider(LocalShell provides shell) {
        Box(modifier.fillMaxSize().background(colors.paper)) {
            NavDisplay(
                backStack = shell.backStack,
                onBack = { shell.back() },
                transitionSpec = { pageSwap() },
                popTransitionSpec = { pageSwap() },
                predictivePopTransitionSpec = { edge -> predictivePagePop(edge, backInset) },
                entryProvider = { key -> NavEntry(key) { destination(key) } },
            )
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
    val content = shell.bottomContent[current]?.also { shown.content = it } ?: shown.content ?: return
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
    )
}

/** Pages swap by fading through (`fade` in, `fadeFast` out) until each transition gets its container transform. */
private fun pageSwap(): ContentTransform = fadeIn(FocalMotion.fade()) togetherWith fadeOut(FocalMotion.fadeFast())

/**
 * Predictive back on a page (spec 2.8.5): it scales to 0.9 and shifts W / 20 - 8 dp toward the gesture, eased by
 * [BackEase]. The container transforms back into the stage or a row come with those screens.
 */
private fun predictivePagePop(edge: Int, insetPx: Int): ContentTransform {
    val spec = tween<Float>(durationMillis = PREDICTIVE_DURATION_MS, easing = BackEase)
    val direction = if (edge == NavigationEvent.EDGE_RIGHT) -1 else 1
    return fadeIn(spec) togetherWith (
        scaleOut(spec, targetScale = PREDICTIVE_SCALE) +
            slideOutHorizontally(tween(PREDICTIVE_DURATION_MS, easing = BackEase)) { width ->
                direction * (width / PREDICTIVE_SHIFT_DIVISOR - insetPx)
            }
        )
}

private const val HALF = 0.5f
private const val PREDICTIVE_SCALE = 0.9f
/** The seekable transition's length; the gesture's progress drives it, so this only shapes the commit. */
private const val PREDICTIVE_DURATION_MS = 300
private const val PREDICTIVE_SHIFT_DIVISOR = 20
private val PREDICTIVE_SHIFT_INSET = 8.dp

/** Runs the activity's keyboard shortcuts: Ctrl+, opens settings from the library while the scene is folded. */
@Composable
fun ShellCommands(shell: ShellState, commands: Flow<ShellCommand>) {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    LaunchedEffect(commands) {
        commands.collect { command ->
            when (command) {
                ShellCommand.OpenSettings -> if (shell.current == Destination.Library && !shell.live.visible) {
                    scope.launch { shell.settings.open(reduced) }
                }
            }
        }
    }
}
