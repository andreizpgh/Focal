package app.focal.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.window.layout.DisplayFeature
import app.focal.ui.components.BottomArea
import app.focal.ui.components.BottomColumn
import app.focal.ui.components.BottomColumnState
import app.focal.ui.components.Dock
import app.focal.ui.components.DockActions
import app.focal.ui.components.DockState
import app.focal.ui.components.inert
import app.focal.ui.components.rememberBottomColumnState
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalSettle
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.LocalWindowLayout
import app.focal.ui.design.TriggerClock
import app.focal.ui.design.WindowLayout
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
    /** The dock's swaps count from the tap that changed what it holds (a page change, Record), see Dock. */
    internal val dockClock: TriggerClock,
) {
    val current: Destination get() = backStack.last()

    init {
        // The scene morphs out of the library's stage and back into it; over any other page it has no origin on screen.
        live.overLibrary = { current == Destination.Library }
    }

    /**
     * Whether the library and a note sit side by side (spec 5.14, width >= 840 dp); the shell sets it from the window.
     * A note then opens in the detail pane in place of the one there, fading through (nothing grows out of the list
     * that stays on screen beside it).
     */
    var twoPane by mutableStateOf(false)
        internal set

    /** Opens [destination] on top; [motion] says how (spec 6.1): a fade-through unless it grows out of the library. */
    fun open(destination: Destination, motion: PageMotion = PageMotion.FadeThrough) {
        val top = backStack.lastOrNull()
        if (top == destination) return
        // The note's page is already on top (it was opened at a search hit, and its recording went on from it): Finish
        // lands on that page, never on a second one for the same note.
        if (destination is Destination.Note && destination.hit == null && (top as? Destination.Note)?.id == destination.id) return
        dockClock.start()
        if (twoPane && destination is Destination.Note) {
            // T7 stays a cut under the scene; everything else fades through in the detail pane.
            val inPane = if (motion == PageMotion.Cut) motion else PageMotion.FadeThrough
            val top = backStack.last()
            if (top is Destination.Note) {
                pages.replace(top, destination, inPane)
                backStack[backStack.lastIndex] = destination
            } else {
                pages.push(destination, inPane)
                backStack.add(destination)
            }
            return
        }
        pages.push(destination, motion)
        backStack.add(destination)
    }

    /** Back from the top page: it folds into where it came from if that is on screen, else it fades through. */
    fun back() {
        if (backStack.size <= 1) return
        dockClock.start()
        pages.pop(backStack.last())
        backStack.removeAt(backStack.lastIndex)
    }

    /**
     * Record (T1′, T1′e): the scene grows out of the stage or the resting instrument while the dock's Search | Record
     * fade through to Pause | Finish from the tap.
     */
    suspend fun record(fromEmpty: Boolean, reducedMotion: Boolean = false) {
        dockClock.start()
        live.record(fromEmpty, reducedMotion)
    }

    /**
     * A note's ● "Continue recording": the scene comes in over the note's page, and the dock's Play | ● | Copy fade
     * through to Pause | Finish from the tap.
     */
    suspend fun recordMore(reducedMotion: Boolean = false) {
        dockClock.start()
        live.appear(reducedMotion)
    }

    /** What each destination asked the bottom column to show; only the current destination's is drawn. */
    internal val bottomContent = mutableStateMapOf<Destination, BottomContent>()

    /** The scene's dock (Pause | Finish in the scene's dress), shown over any page while the scene is up. */
    internal var sceneBottom by mutableStateOf<BottomContent?>(null)
}

/**
 * Where the window's bottom elements stand (spec 5.14), each read in the layout phase: [dock], the bottom column now
 * (its page, its pane, carried to the window's middle under the scene); [landing], the dock once Finish has settled
 * the scene into it (T7: the note's page or pane); [scene], the page area under the open scene (beside the landscape
 * rail; the whole window over two panes).
 */
@Immutable
class DockAreas(val dock: BottomArea, val landing: BottomArea, val scene: BottomArea)

val LocalDockAreas = staticCompositionLocalOf { DockAreas(BottomArea.Window, BottomArea.Window, BottomArea.Window) }

val LocalShell = staticCompositionLocalOf<ShellState> { error("No ShellState: wrap the screen in FocalShell.") }

/** [capturing] says whether a recording is live, so an open scene survives recreation only while one runs. */
@Composable
fun rememberShellState(capturing: () -> Boolean = { false }): ShellState {
    val backStack = rememberSaveable(saver = Destination.BackStackSaver) { SnapshotStateList<Destination>().apply { add(Destination.Library) } }
    val settings = rememberSettingsStripState()
    val live = rememberLiveSurfaceState(capturing)
    val bottom = rememberBottomColumnState()
    // Page changes run their clock in the composition's scope, on its frame clock.
    val scope = rememberCoroutineScope()
    return remember(backStack, settings, live, bottom) {
        ShellState(backStack, settings, live, bottom, SystemBarsState(), PageTransitions(scope), TriggerClock(scope, FocalSettle.FADE_FAST_MS))
    }
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
 *
 * The window's size picks the layout (spec 5.14, [WindowLayout]): a short window puts the dock in a rail beside the
 * pages; from 840 dp wide the library stays in its pane at the start and the back stack's note shows beside it, split
 * at a separating vertical fold or hinge among the window's [displayFeatures] (androidx.window) when there is one.
 */
@Composable
fun FocalShell(
    shell: ShellState,
    destination: @Composable (Destination) -> Unit,
    modifier: Modifier = Modifier,
    displayFeatures: List<DisplayFeature> = emptyList(),
    scene: @Composable () -> Unit = {},
) {
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    SideEffect {
        shell.pages.reduced = reduced
        shell.pages.density = density
        shell.live.reducedMotion = reduced
    }
    // One holder for every page's saved state, so a page keeps it when the layout changes around it (a rotation
    // between one pane and two).
    val pageStates = rememberSaveableStateHolder()
    BoxWithConstraints(modifier.fillMaxSize().background(colors.paper)) {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val hinge = remember(displayFeatures, maxWidth, density, rtl) { separatingHinge(displayFeatures, maxWidth, density, rtl) }
        val layout = WindowLayout(maxWidth, maxHeight, hinge)
        SideEffect { shell.twoPane = layout.twoPane }
        CompositionLocalProvider(LocalShell provides shell, LocalWindowLayout provides layout, LocalDockAreas provides rememberDockAreas(shell, layout)) {
            Shell(shell, layout, pageStates, destination, scene)
        }
    }
    // Pages that left the back stack drop what they registered.
    LaunchedEffect(shell.backStack.toList()) { shell.bottomContent.keys.retainAll(shell.backStack.toSet()) }
}

@Composable
private fun Shell(
    shell: ShellState,
    layout: WindowLayout,
    pageStates: SaveableStateHolder,
    destination: @Composable (Destination) -> Unit,
    scene: @Composable () -> Unit,
) {
    val colors = FocalTheme.colors
    val live = shell.live
    val behindScene by remember(live) { derivedStateOf { live.open } }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .inert(behindScene)
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
            val twoPane = layout.twoPane
            // Every change is a bare swap; each page draws its own part from the upper page's progress, and the
            // container transforms draw above both (PageTransitions). In two panes the display holds the detail
            // pane (and the licences, over the whole window) above the library's pane; it is composed first, so
            // the library's own Back (settings, search) comes before it, except under the licences, which cover
            // the library's pane and whose Back the library leaves to the display.
            NavDisplay(
                backStack = shell.backStack,
                modifier = if (twoPane) Modifier.zIndex(DETAIL_Z) else Modifier,
                onBack = { shell.back() },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(pageStates)),
                transitionSpec = { pageSwap() },
                popTransitionSpec = { pageSwap() },
                predictivePopTransitionSpec = { edge ->
                    shell.pages.beginPredictive(shell.current, edge)
                    pageSwap()
                },
                // One entry content for both layouts: a rememberSaveable is found again only at the place in the
                // tree that saved it, so a page keeps its state across a turn between one pane and two only if it
                // sits at the same place in both.
                entryProvider = { key ->
                    NavEntry(key, contentKey = if (twoPane) DetailKey.of(key) else key.toString()) {
                        PageEntry(key, shell, twoPane, colors.paper, destination)
                    }
                },
            )
            if (twoPane) pageStates.SaveableStateProvider(Destination.Library.toString()) { ListPane(shell, destination) }
            else PageTransformOverlay(shell.pages, shell.live, shell.bottom)
        }
        LiveSurfaceHost(shell.live, scene)
        ShellBottom(shell, layout)
    }
}

/**
 * The saved-state keys of the two-pane display's entries: the library's entry is the empty detail pane there (its
 * page is in the list pane, under the key it has in one pane), the others keep the keys they have in one pane.
 */
private object DetailKey {
    fun of(destination: Destination): Any = if (destination == Destination.Library) "detail:none" else destination.toString()
}

/**
 * One entry of the display, the same composable in both layouts (see the display's entry provider). In one pane the
 * page as it is. In two: a note in the detail pane, moving with the settings strip as the rest of the window does
 * (spec 4.23.7: the whole window slides) and rounding its seam-side corners with the library's; the licences on paper
 * over the whole window; nothing for the library itself, whose page is in its own pane.
 */
@Composable
private fun PageEntry(key: Destination, shell: ShellState, twoPane: Boolean, paper: Color, destination: @Composable (Destination) -> Unit) {
    if (twoPane && key == Destination.Library) return
    val strip = shell.settings
    val behindSettings by remember(strip) { derivedStateOf { strip.progress.value >= 1f } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduced = LocalReducedMotion.current
    // After the list pane, or after the hinge that ends it.
    val detailStart = LocalWindowLayout.current.detailStart
    val frame = when {
        !twoPane -> Modifier
        key is Destination.Note -> Modifier
            .fillMaxSize()
            .padding(start = detailStart)
            .inert(behindSettings)
            .graphicsLayer {
                val s = strip.progress.value
                if (reduced) alpha = 1 - smoothstep(0f, HALF, s) else {
                    // The window's travel: this pane's right edge is the window's, as the library layer's is.
                    val window = size.width + detailStart.toPx()
                    translationX = (if (rtl) 1 else -1) * s * settingsTravel(window, this)
                    clip = s > 0f && s < 1f
                    shape = if (clip) SeamSideShape(FocalShapes.sheetRadius, seamAtEnd = true) else RectangleShape
                }
            }
        else -> Modifier.fillMaxSize().background(paper)
    }
    Box(frame) { PageFrame(key, shell.pages) { destination(key) } }
}

/**
 * The library's pane (two panes): the Library destination at the window's size, so the settings strip slides the
 * whole window (spec 4.23.7); its page keeps to the list pane. While the licences fade through over the window, the
 * settings page under them leaves as the page below a fade-through does (two typesettings never overlap).
 *
 * With a note open beside it, the note's shortcuts (spec 8.5: Space, Ctrl+F, Ctrl+C, Ctrl+Z) go to the note even while
 * focus is in this pane: after a touch the first hardware key leaves touch mode and puts focus on this pane's first
 * control by itself, so Space would otherwise press a row or a button instead of playing the note. While the note is
 * read, Space is its own even with nothing to play; a key the note declines (Space in Edit or Find, no Edit to undo)
 * stays with the focused control, and Enter always does. A focused control keeps the keys it answers itself
 * ([FocusedKeys]): a text field here (a rename) every key, the live stage Space (it expands), a stage holding a note
 * Ctrl+C (it copies its own note). Search's field sits in the bottom column, outside this pane, so its keys never pass
 * here.
 */
@Composable
private fun ListPane(shell: ShellState, destination: @Composable (Destination) -> Unit) {
    val pages = shell.pages
    val pageKeys = LocalPageKeys.current
    val focused = remember { FocusedKeys() }
    val shortcuts = remember(pageKeys) { ShortcutKeys(focused.leaving(pageKeys::dispatch)) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val change = pages.change
                if (change != null && change.document == Destination.Licenses) alpha = pages.look(Destination.Library).alpha
            }
            .shortcutKeys(shortcuts) { shell.current is Destination.Note },
    ) { CompositionLocalProvider(LocalFocusedKeys provides focused) { destination(Destination.Library) } }
}

/**
 * Where the bottom column stands (spec 2.5, 5.14), read in the layout phase: the page area inside the side insets and
 * beside the landscape rail; in two panes, the list pane while the detail pane is empty and the detail pane while a
 * note is open (spec 5.14: the dock lives in the detail pane), crossing between them on `move` with the page change.
 * While the scene grows over two panes the dock is carried to the window's middle with p, under the scene's column,
 * and back with the fold; Finish's settle carries it to the note's pane, where the scene lands (T7).
 */
@Composable
private fun rememberDockAreas(shell: ShellState, layout: WindowLayout): DockAreas {
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val live = shell.live
    val reduced = LocalReducedMotion.current
    // 0 = the list pane, 1 = the detail pane.
    val toDetail = remember(shell) { Animatable(0f) }
    LaunchedEffect(shell, layout.twoPane, reduced) {
        var first = true
        snapshotFlow { shell.current is Destination.Note || live.landingNote != null }.collect { detail ->
            val target = if (detail) 1f else 0f
            if (first || !layout.twoPane || reduced) toDetail.snapTo(target) else toDetail.animateTo(target, FocalMotion.move())
            first = false
        }
    }
    return remember(shell, layout, sides, rtl) {
        fun page(width: Int, density: Density): ClosedFloatingPointRange<Float> = with(density) {
            val left = sides.getLeft(density, LayoutDirection.Ltr).toFloat()
            val right = width - sides.getRight(density, LayoutDirection.Ltr).toFloat()
            val rail = if (layout.rail) WindowLayout.railWidth.toPx() else 0f
            if (rtl) (left + rail)..right else left..(right - rail)
        }
        // The list pane from the window's start edge (412 dp, or up to a hinge); the detail pane the rest after it.
        fun pane(width: Int, density: Density, detail: Float): ClosedFloatingPointRange<Float> =
            layout.paneSpan(width.toFloat(), page(width, density), detail, rtl, density)
        // What the scene takes over two panes: the whole page area, or the detail pane beside a hinge that splits it,
        // so its column and Pause | Finish never lie under the hinge.
        fun whole(width: Int, density: Density): ClosedFloatingPointRange<Float> =
            if (layout.split != null) pane(width, density, detail = 1f) else page(width, density)
        val landing = BottomArea { width, density -> pane(width, density, detail = 1f) }
        val scene = BottomArea { width, density -> whole(width, density) }
        val dock = BottomArea { width, density ->
            if (!layout.twoPane) return@BottomArea page(width, density)
            val window = whole(width, density)
            val p = when {
                live.landingNote != null -> 1 - live.landing.value
                live.spatial -> live.expansion.value
                else -> if (live.expansion.value >= HALF) 1f else 0f
            }
            val home = pane(width, density, detail = toDetail.value)
            lerp(home.start, window.start, p)..lerp(home.endInclusive, window.endInclusive, p)
        }
        DockAreas(dock = dock, landing = landing, scene = scene)
    }
}

/** The last bottom content shown: a page that is arriving registers its own a frame later. */
private class ShownBottom { var content: BottomContent? = null }

@Composable
private fun ShellBottom(shell: ShellState, layout: WindowLayout) {
    val current = shell.current
    val shown = remember { ShownBottom() }
    val live = shell.live
    // The scene's controls while it is up and not settling into the note's page (T7). Until the scene has registered
    // them (it is composed in the frame p leaves 0), the dock already holds Pause | Finish, so its swap starts with
    // the tap. A page that opens out of the stage or a row shows the dock it will register (its origin knows the
    // note), for the same reason; actions arrive with the page, a frame or two later.
    val scene = if (live.visible && live.landingNote == null) shell.sceneBottom ?: sceneDock(shell) else null
    val content = scene
        ?: shell.bottomContent[current]?.also { shown.content = it }
        ?: shell.pages.arrivingDock(current)?.let { BottomContent(it, NoActions, strip = null, rows = null, dockHidden = false) }
        ?: shown.content?.takeIf { current.hasDock }
        ?: return
    // The library's dock moves with its page; in two panes the whole window slides, the note's dock with it.
    val onLibrary = current == Destination.Library || layout.twoPane && current is Destination.Note
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduced = LocalReducedMotion.current
    // With settings fully open the library's dock and feedback pill are off screen: inert, like the page.
    val behindSettings by remember(shell) { derivedStateOf { shell.settings.progress.value >= 1f } }
    BottomColumn(
        state = shell.bottom,
        modifier = Modifier
            .fillMaxSize()
            .inert(onLibrary && behindSettings)
            .graphicsLayer {
                // The library's dock and feedback pill move with the library page (spec 4.23.2).
                if (onLibrary) {
                    val s = shell.settings.progress.value
                    if (reduced) alpha = 1 - smoothstep(0f, HALF, s)
                    else translationX = (if (rtl) 1 else -1) * s * settingsTravel(size.width, this)
                }
            },
        dock = {
            Dock(
                content.dock, content.actions, material = shell.live.material, clock = shell.dockClock, hidden = content.dockHidden,
                requests = shell.bottom.dock, rail = layout.rail,
            )
        },
        strip = content.strip,
        rows = content.rows,
        dockHidden = content.dockHidden,
        area = LocalDockAreas.current.dock,
        rail = layout.rail,
    )
}

/** The scene's dock before the scene has registered its own: a live recording's, paused as the library's says. */
private fun sceneDock(shell: ShellState): BottomContent {
    val paused = (shell.bottomContent[Destination.Library]?.dock as? DockState.Live)?.paused ?: false
    return if (paused) PausedSceneDock else LiveSceneDock
}

/** A dock shown ahead of its page or scene: it does nothing until their own actions arrive. */
private val NoActions = DockActions()
private val LiveSceneDock = BottomContent(DockState.Live(paused = false), NoActions, strip = null, rows = null, dockHidden = false)
private val PausedSceneDock = BottomContent(DockState.Live(paused = true), NoActions, strip = null, rows = null, dockHidden = false)

private const val HALF = 0.5f
/** In two panes the display (the detail pane, the licences) draws above the library's pane. */
private const val DETAIL_Z = 1f
/** The pages behind the morph darken (spec 2.8.2 `libraryDim`). */
private const val PAGES_DIM = 0.30f

/**
 * Runs the activity's keyboard shortcuts: Ctrl+, opens settings from the library while the scene is folded and the
 * library is at rest (4.23.6). In two panes a note open beside the library leaves it on screen, so Ctrl+, opens
 * settings there too, as the swipe does (the whole window slides).
 */
@Composable
fun ShellCommands(shell: ShellState, commands: Flow<ShellCommand>) {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    LaunchedEffect(commands) {
        commands.collect { command ->
            when (command) {
                ShellCommand.OpenSettings -> {
                    val onLibrary = shell.current == Destination.Library || shell.twoPane && shell.current is Destination.Note
                    if (onLibrary && !shell.live.visible && shell.settings.armed) scope.launch { shell.settings.open(reduced) }
                }
            }
        }
    }
}
