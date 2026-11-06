package app.focal.ui.library

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.focal.R
import app.focal.ui.components.BottomFade
import app.focal.ui.components.DayLabel
import app.focal.ui.components.DockActions
import app.focal.ui.components.FocalMenu
import app.focal.ui.components.FocalMenuDivider
import app.focal.ui.components.FocalMenuItem
import app.focal.ui.components.LibraryTopFade
import app.focal.ui.components.MenuAnchor
import app.focal.ui.components.StickyLabelOverlay
import app.focal.ui.components.WaveMeter
import app.focal.ui.components.rememberRowPlacement
import app.focal.ui.components.rememberStickyLabels
import app.focal.ui.components.stickyLabelSource
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.Grid
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.Stage
import app.focal.ui.scene.ExpandDrag
import app.focal.ui.shell.Destination
import app.focal.ui.shell.DestinationBottom
import app.focal.ui.shell.LocalShell
import app.focal.ui.state.NoteRow
import app.focal.ui.state.SearchRow
import app.focal.ui.state.SearchUiState
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the library page does; the route provides it from the view models (the gallery from fakes). */
@Stable
class LibraryActions(
    /** A row opens its note (T16). */
    val open: (NoteRow) -> Unit = {},
    /** The stage opens its note (T16′). */
    val openStage: (NoteRow) -> Unit = open,
    val openResult: (SearchRow) -> Unit = {},
    /** Tap or Enter on the live stage: the scene expands (T6′). */
    val expand: () -> Unit = {},
    /** The stage's Copy: copies the note and returns whether the clipboard took it. */
    val copy: suspend (NoteRow) -> Boolean = { false },
    /** The row menu's "Copy text". */
    val copyText: (NoteRow) -> Unit = {},
    val share: (NoteRow) -> Unit = {},
    val rename: (NoteRow, String) -> Unit = { _, _ -> },
    val removeAudio: (NoteRow) -> Unit = {},
    val delete: (NoteRow) -> Unit = {},
    /** The size the note's audio takes, for "Remove audio · 9 MB"; null while unknown. */
    val audioSize: suspend (NoteRow) -> String? = { null },
    val query: (String) -> Unit = {},
    val record: () -> Unit = {},
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
    val finish: () -> Unit = {},
    val openSettings: () -> Unit = {},
)

/** The note a row menu is open for, and where it was pressed (inside the row or the stage). */
private data class MenuTarget(val noteId: String, val at: IntOffset)

/** The bounds of a field being renamed, so a tap anywhere else commits it. */
private class RenameBounds { var rect: androidx.compose.ui.geometry.Rect? = null }

/**
 * The library (spec 5.1): the stage, then the notes by day under small labels that pin only once the list scrolls,
 * the dock, and search riding the keyboard. The empty library shows the instrument at rest instead. Everything is
 * drawn from [model] and [search], immutable state; [meter] is the live wave's level, read in the draw phase.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryPage(
    model: LibraryModel,
    search: SearchUiState,
    meter: WaveMeter,
    dates: LibraryDates,
    today: LocalDate,
    actions: LibraryActions,
    listState: LazyListState = rememberLazyListState(),
    searchState: LibrarySearchState = rememberLibrarySearchState(),
    query: TextFieldState = rememberTextFieldState(search.query),
    stageFocus: FocusRequester? = null,
    /** A note whose title starts out renamed in place (the stage's or a row's). */
    initialRename: String? = null,
) {
    val shell = LocalShell.current
    val bottom = shell.bottom
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var renaming by rememberSaveable { mutableStateOf(initialRename) }
    val renameBounds = remember { RenameBounds() }
    val imeVisible = WindowInsets.isImeVisible
    val searchList = rememberLazyListState()
    val density = LocalDensity.current

    SyncQuery(query, actions.query)
    val closeSearch: () -> Unit = {
        focusManager.clearFocus()
        scope.launch { searchState.close { query.clearText() } }
    }
    BackHandler(enabled = searchState.open, onBack = closeSearch)
    BackHandler(enabled = renaming != null) { renaming = null }
    // A menu closes when the list scrolls (spec 4.15).
    LaunchedEffect(listState) { snapshotFlow { listState.isScrollInProgress }.collect { if (it) menu = null } }
    // Settings may open only from the library at rest (spec 4.23.3).
    val armed = !searchState.open && menu == null && renaming == null && !imeVisible
    SideEffect { shell.settings.armed = armed }

    if (model.loaded) {
        val dockActions = remember(actions, searchState) {
            DockActions(
                search = { scope.launch { searchState.open() } },
                record = actions.record, pause = actions.pause, resume = actions.resume, finish = actions.finish,
                openSettings = actions.openSettings,
            )
        }
        DestinationBottom(
            Destination.Library, model.dock, dockActions,
            rows = if (searchState.fieldShown) { { SearchField(searchState, query, onClose = closeSearch) } } else null,
            dockHidden = searchState.dockHidden || renaming != null,
        )
    }

    val menuLabels = MenuLabels(
        copyText = stringResource(R.string.menu_copy_text), share = stringResource(R.string.menu_share),
        rename = stringResource(R.string.menu_rename), removeAudio = stringResource(R.string.menu_remove_audio),
        delete = stringResource(R.string.menu_delete),
    )
    val menuActions = remember(actions) {
        NoteMenuActions(
            copyText = actions.copyText, share = actions.share, rename = { renaming = it.id },
            removeAudio = actions.removeAudio, delete = actions.delete, audioSize = actions.audioSize,
        )
    }
    val rowActions = remember(actions, menuLabels) {
        RowActions(
            open = actions.open,
            menu = { row, at -> menu = MenuTarget(row.id, at) },
            menuActions = { row -> menuItems(row).map { item -> CustomAccessibilityAction(menuLabels.of(item)) { menuActions.run(item, row); true } } },
        )
    }
    val stageActions = remember(actions, rowActions) {
        StageActions(
            open = actions.openStage, expand = actions.expand, copy = actions.copy, openSettings = actions.openSettings,
            menu = { row, at -> menu = MenuTarget(row.id, at) },
            menuActions = rowActions.menuActions,
        )
    }
    val noteMenu: @Composable (NoteRow) -> Unit = { row ->
        val target = menu?.takeIf { it.noteId == row.id }
        NoteMenu(row, expanded = target != null, at = target?.at ?: IntOffset.Zero, labels = menuLabels, actions = menuActions) { menu = null }
    }
    val renameField: @Composable (NoteRow, onStage: Boolean) -> Unit = { row, onStage ->
        val colors = FocalTheme.colors
        // On the material the paper's `select` and ink handles would vanish: stageInk handles on a light veil.
        val stageSelection = remember(colors.stageInk) { TextSelectionColors(handleColor = colors.stageInk, backgroundColor = Stage.selection) }
        val selection = if (onStage) stageSelection else LocalTextSelectionColors.current
        CompositionLocalProvider(LocalTextSelectionColors provides selection) {
            RenameField(
                initial = row.title,
                style = if (onStage) FocalType.headline else FocalType.rowTitle,
                color = if (onStage) colors.stageInk else colors.ink,
                caret = if (onStage) colors.stageInk else colors.ink,
                onCommit = { text ->
                    renaming = null
                    if (text != row.title) actions.rename(row, text)
                },
                modifier = Modifier.onGloballyPositioned { renameBounds.rect = it.boundsInWindow() },
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(
                // While a title is renamed, a tap anywhere else commits it (spec 5.1.6) and does nothing else.
                if (renaming == null) Modifier else Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                            val inside = renameBounds.rect?.contains(down.position) == true
                            if (!inside) {
                                event.changes.forEach { it.consume() }
                                focusManager.clearFocus()
                            }
                        }
                    }
                },
            ),
    ) {
        // The library layer fades out under search (T17) and is then neither touchable nor read.
        val behindSearch = searchState.open
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1 - searchState.content.value }
                .then(if (behindSearch) Modifier.clearAndSetSemantics { }.focusProperties { canFocus = false }.blockTouches() else Modifier),
        ) {
            if (model.loaded) LibraryContent(
                model, dates, today, meter, listState, stageActions, rowActions, noteMenu,
                renaming = renaming, renameField = renameField, imePadded = renaming != null,
                reserve = bottom.contentReserve(LocalDensity.current), openSettings = actions.openSettings, stageFocus = stageFocus,
            )
        }
        if (searchState.open || searchState.content.value > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = searchState.content.value }) {
                SearchResults(search, bottom, dates, today, onOpen = { result ->
                    focusManager.clearFocus()
                    actions.openResult(result)
                }, list = searchList)
                LibraryTopFade({ Float.POSITIVE_INFINITY }, Modifier.fillMaxSize())
            }
        }
        BottomFade(
            bottom, Modifier.fillMaxSize(), keyboard = searchState.open,
            alpha = { if (searchState.open) searchFadeAlpha(searchList, density) else 1f },
        )
    }
}

/** The page's list or the empty composition, crossfading when the first note arrives or the last one leaves. */
@Composable
private fun LibraryContent(
    model: LibraryModel,
    dates: LibraryDates,
    today: LocalDate,
    meter: WaveMeter,
    listState: LazyListState,
    stageActions: StageActions,
    rowActions: RowActions,
    noteMenu: @Composable (NoteRow) -> Unit,
    renaming: String?,
    renameField: @Composable (NoteRow, Boolean) -> Unit,
    imePadded: Boolean,
    reserve: androidx.compose.ui.unit.Dp,
    openSettings: () -> Unit,
    stageFocus: FocusRequester?,
) {
    val empty = model.empty
    // Deleting the last note: the stage leaves on `fadeFast`, the composition arrives on `fade` (4.22.5).
    val emptyAlpha = remember { Animatable(if (empty) 1f else 0f) }
    val listAlpha = remember { Animatable(if (empty) 0f else 1f) }
    LaunchedEffect(empty) {
        if (empty) {
            listAlpha.animateTo(0f, FocalMotion.fadeFast())
            emptyAlpha.animateTo(1f, FocalMotion.fade())
        } else {
            emptyAlpha.animateTo(0f, FocalMotion.fadeFast())
            listAlpha.animateTo(1f, FocalMotion.fade())
        }
    }
    if (empty || emptyAlpha.value > 0f) {
        EmptyLibrary(openSettings, Modifier.fillMaxSize().graphicsLayer { alpha = emptyAlpha.value })
    }
    if (!empty || listAlpha.value > 0f) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = listAlpha.value }) {
            LibraryList(model, dates, today, meter, listState, stageActions, rowActions, noteMenu, renaming, renameField, imePadded, reserve, stageFocus)
        }
    }
}

/**
 * The stage and the days of notes (spec 5.1.1, 4.12): the first day label sits 32 dp under the stage, then 16 dp to
 * the first title; 36 dp separate a section's last preview from the next label. Labels pin at status + 12 only once
 * they reach that line (drawn by the overlay above the top fade).
 */
@Composable
private fun LibraryList(
    model: LibraryModel,
    dates: LibraryDates,
    today: LocalDate,
    meter: WaveMeter,
    listState: LazyListState,
    stageActions: StageActions,
    rowActions: RowActions,
    noteMenu: @Composable (NoteRow) -> Unit,
    renaming: String?,
    renameField: @Composable (NoteRow, Boolean) -> Unit,
    imePadded: Boolean,
    reserve: androidx.compose.ui.unit.Dp,
    stageFocus: FocusRequester?,
) {
    val shell = LocalShell.current
    val live = shell.live
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    // Dragging the live stage down grows it into the scene (T28): the list's nested-scroll parent takes the
    // downward overscroll at its top while a stream that started on the stage is armed.
    val expandDrag = remember(live) { ExpandDrag(live, scope) }
    expandDrag.travel = { windowHeight - (live.stageBounds?.bottom ?: 0f) }
    expandDrag.density = density.density
    expandDrag.reduced = reduced || !live.spatial
    // Record and a fold bring the stage into view; focus returns to it after a fold (spec 5.3.1, T5′, 8.6).
    DisposableEffect(live, listState, stageFocus) {
        live.revealStage = { animated -> if (animated) listState.animateScrollToItem(0) else listState.scrollToItem(0) }
        live.stageFocus = stageFocus
        onDispose {
            live.revealStage = null
            live.stageFocus = null
        }
    }
    val sections = remember(model.days) {
        buildMap<Any, Any> {
            for (day in model.days) {
                val key = dayKey(day.epochDay)
                put(key, key)
                day.rows.forEach { put(it.id, key) }
            }
        }
    }
    val labels = rememberStickyLabels(listState) { key -> sections[key] }
    val texts = model.days.associate { dayKey(it.epochDay) to dates.dayLabel(it.epochDay, today) }
    val stageBottom = {
        val info = listState.layoutInfo
        info.visibleItemsInfo.firstOrNull { it.key == STAGE_KEY }?.let { (it.offset + it.size - info.viewportStartOffset).toFloat() } ?: Float.NaN
    }
    val status = WindowInsets.statusBars
    val top = with(density) { status.getTop(this).toDp() } + FocalLayout.topFade
    val isLive = model.stage is StageUi.Live
    val placement = rememberRowPlacement(
        remember(model.stage?.key, model.days) {
            buildList {
                if (model.stage != null) add(STAGE_KEY)
                model.days.forEach { day ->
                    add(dayKey(day.epochDay))
                    day.rows.forEach { add(it.id) }
                }
            }
        },
    )
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(if (imePadded) Modifier.imePadding() else Modifier)
                .nestedScroll(expandDrag)
                .pointerInput(expandDrag) {
                    // Every stream starts disarmed; the stage arms the ones that start on it (it sees them after).
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        expandDrag.arm(false)
                    }
                },
            contentPadding = PaddingValues(top = top, bottom = reserve),
        ) {
            model.stage?.let { stage ->
                item(key = STAGE_KEY, contentType = STAGE_KEY) {
                    val row = (stage as? StageUi.Note)?.row
                    DisposableEffect(live) { onDispose { live.stageBounds = null } }
                    StageCard(
                        stage, dates, today, meter, stageActions,
                        modifier = Modifier
                            .padding(horizontal = FocalLayout.gutter)
                            .then(if (stageFocus != null) Modifier.focusRequester(stageFocus) else Modifier)
                            .onGloballyPositioned { coordinates ->
                                val root = coordinates.findRootCoordinates().size
                                val laidOut = Rect(coordinates.positionInWindow(), coordinates.size.toSize())
                                live.stageBounds = live.unscaled(laidOut, Offset(root.width / 2f, root.height / 2f))
                            }
                            // The morph draws the stage while it is up (spec 6.4); under the crossfade it stays.
                            .graphicsLayer { alpha = if (live.spatial && live.expansion.value > 0f) 0f else 1f }
                            .then(
                                if (!isLive) Modifier else Modifier.pointerInput(expandDrag) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                                        expandDrag.arm(atTop && live.expansion.value <= 0f)
                                    }
                                },
                            ),
                        menu = { if (row != null) noteMenu(row) },
                        title = if (row != null && renaming == row.id) { { renameField(row, true) } } else null,
                    )
                }
            }
            model.days.forEachIndexed { index, day ->
                val key = dayKey(day.epochDay)
                item(key = key, contentType = DAY_TYPE) {
                    DayLabel(
                        texts[key].orEmpty(),
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FocalLayout.gutter)
                            .padding(top = if (index == 0) firstLabelGap(model.stage != null) else SECTION_GAP, bottom = LABEL_TO_TITLE)
                            .stickyLabelSource(labels, key)
                            .animateItem(fadeInSpec = FocalMotion.fade(), placementSpec = placement, fadeOutSpec = FocalMotion.fadeFast()),
                    )
                }
                items(day.rows, key = { it.id }, contentType = { ROW_TYPE }) { row ->
                    NoteRowItem(
                        row, dates, rowActions,
                        modifier = Modifier.animateItem(fadeInSpec = FocalMotion.fade(), placementSpec = placement, fadeOutSpec = FocalMotion.fadeFast()),
                        menu = { noteMenu(row) },
                        title = if (renaming == row.id) { { renameField(row, false) } } else null,
                    )
                }
            }
        }
        LibraryTopFade(stageBottom, Modifier.fillMaxSize())
        StickyLabelOverlay(labels, { key -> texts[key].orEmpty() }, Modifier.fillMaxSize())
    }
}

private const val STAGE_KEY = "stage"
private const val DAY_TYPE = "day"
private const val ROW_TYPE = "row"
private fun dayKey(epochDay: Long) = "day:$epochDay"

/** The first label sits 32 dp under the stage (spec 4.12.1). */
private fun firstLabelGap(afterStage: Boolean) = if (afterStage) Grid.x32 else 0.dp
/** 36 dp from a preview to the next label, less the row's 12 dp hit area below it. */
private val SECTION_GAP = Grid.x36 - RowMetrics.hitPad
/** 16 dp from a label to the next title, less the row's 12 dp hit area above it. */
private val LABEL_TO_TITLE = Grid.x16 - RowMetrics.hitPad

/** A layer that takes every touch, so nothing under a faded layer can be pressed. */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

@Stable
private class MenuLabels(val copyText: String, val share: String, val rename: String, val removeAudio: String, val delete: String) {
    fun of(item: MenuItem) = when (item) {
        MenuItem.CopyText -> copyText
        MenuItem.Share -> share
        MenuItem.Rename -> rename
        MenuItem.RemoveAudio -> removeAudio
        MenuItem.Delete -> delete
    }
}

@Stable
private class NoteMenuActions(
    val copyText: (NoteRow) -> Unit,
    val share: (NoteRow) -> Unit,
    val rename: (NoteRow) -> Unit,
    val removeAudio: (NoteRow) -> Unit,
    val delete: (NoteRow) -> Unit,
    val audioSize: suspend (NoteRow) -> String?,
) {
    fun run(item: MenuItem, row: NoteRow) = when (item) {
        MenuItem.CopyText -> copyText(row)
        MenuItem.Share -> share(row)
        MenuItem.Rename -> rename(row)
        MenuItem.RemoveAudio -> removeAudio(row)
        MenuItem.Delete -> delete(row)
    }
}

/** The row menu at the touch point (spec 4.15), with the divider before Remove audio and its size. */
@Composable
private fun NoteMenu(row: NoteRow, expanded: Boolean, at: IntOffset, labels: MenuLabels, actions: NoteMenuActions, onDismiss: () -> Unit) {
    var size by remember(row.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(expanded, row.id) { if (expanded && row.canRemoveAudio) size = actions.audioSize(row) }
    FocalMenu(expanded, onDismiss, MenuAnchor.At(at)) {
        for (item in menuItems(row)) {
            if (item == MenuItem.RemoveAudio) FocalMenuDivider()
            FocalMenuItem(
                labels.of(item),
                onClick = {
                    onDismiss()
                    actions.run(item, row)
                },
                trailing = if (item == MenuItem.RemoveAudio) size else null,
            )
        }
    }
}

/** API 32 and below show no system clipboard preview, so the row menu's Copy text confirms in the pill (5.1.6). */
val confirmsCopyInApp: Boolean get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
