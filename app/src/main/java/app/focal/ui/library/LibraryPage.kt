package app.focal.ui.library

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.util.lerp
import app.focal.R
import app.focal.ui.components.BottomFade
import app.focal.ui.components.DayLabel
import app.focal.ui.components.DockActions
import app.focal.ui.components.DockState
import app.focal.ui.components.LibraryTopFade
import app.focal.ui.components.PageArea
import app.focal.ui.components.StickyLabelOverlay
import app.focal.ui.components.WaveMeter
import app.focal.ui.components.inert
import app.focal.ui.components.pageInsets
import app.focal.ui.components.rememberRowPlacement
import app.focal.ui.components.rememberStickyLabels
import app.focal.ui.components.stickyLabelSource
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.Stage
import app.focal.ui.design.columnInset
import app.focal.ui.design.smoothstep
import app.focal.ui.scene.ExpandDrag
import app.focal.ui.shell.Destination
import app.focal.ui.shell.DestinationBottom
import app.focal.ui.shell.LocalShell
import app.focal.ui.shell.foldLabel
import app.focal.ui.state.NoteRow
import app.focal.ui.state.SearchRow
import app.focal.ui.state.SearchUiState
import java.time.LocalDate
import kotlinx.coroutines.launch

/** What the library page does; the route provides it from the view models (the gallery from fakes). */
@Stable
class LibraryActions(
    /** A row opens its note (T16). */
    val open: (NoteRow) -> Unit = {},
    /** The stage opens its note (T16′). */
    val openStage: (NoteRow) -> Unit = open,
    /** A finger landed on a row or the stage: its note is read ahead of the tap (by note id). */
    val prepare: (String) -> Unit = {},
    val openResult: (SearchRow) -> Unit = {},
    /** Tap or Enter on the live stage: the scene expands (T6′). */
    val expand: () -> Unit = {},
    /** The stage's Copy: copies the note and returns whether the clipboard took it. */
    val copy: suspend (NoteRow) -> Boolean = { false },
    /** A row's "Copy text" (an accessibility action since rows have no menu). */
    val copyText: (NoteRow) -> Unit = {},
    val share: (NoteRow) -> Unit = {},
    val rename: (NoteRow, String) -> Unit = { _, _ -> },
    val removeAudio: (NoteRow) -> Unit = {},
    val delete: (NoteRow) -> Unit = {},
    /** "Delete N": the selected notes go to the trash together, with one Undo. */
    val deleteAll: (List<NoteRow>) -> Unit = { rows -> rows.forEach(delete) },
    /** ⋮ "Remove audio" over a selection: these notes keep their text and lose their audio, with one Undo. */
    val removeAudioAll: (List<NoteRow>) -> Unit = { rows -> rows.forEach(removeAudio) },
    /** What the audio of these notes takes together ("42 MB"), for that menu item. */
    val audioSize: suspend (List<NoteRow>) -> String? = { null },
    val query: (String) -> Unit = {},
    val record: () -> Unit = {},
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
    val finish: () -> Unit = {},
    val openSettings: () -> Unit = {},
)

/** The bounds of a field being renamed, so a tap anywhere else commits it. */
private class RenameBounds { var rect: androidx.compose.ui.geometry.Rect? = null }

/**
 * The library (spec 5.1): the stage, then the notes by day under small labels that pin only once the list scrolls,
 * the dock, and search riding the keyboard. The empty library shows the instrument at rest instead. Everything is
 * drawn from [model] and [search], immutable state; [meter] is the live wave's level, read in the draw phase.
 *
 * A long press on a note (a row, or the stage holding one) selects it; while notes are selected ([selection]) a tap
 * adds or removes one, the dock is ✕ | ⋮ | "Delete N" (⋮ "Remove audio" keeps the notes as text), and Back or ✕ ends
 * it. A note that is being transcribed cannot be deleted, so it cannot be selected.
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
    /** The live recording's whole seconds, read only by the live stage's timer (null: the model's own). */
    liveSeconds: (() -> Long)? = null,
    rename: LibraryRenameState = rememberLibraryRenameState(),
    searchList: LazyListState = rememberLazyListState(),
    selection: LibrarySelectionState = rememberLibrarySelectionState(),
) {
    val shell = LocalShell.current
    val bottom = shell.bottom
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var renaming by rename::noteId
    val renameBounds = remember { RenameBounds() }
    val imeVisible = WindowInsets.isImeVisible
    val density = LocalDensity.current

    SyncQuery(query, actions.query)
    val closeSearch: () -> Unit = {
        focusManager.clearFocus()
        scope.launch { searchState.close { query.clearText() } }
    }
    // The results take the page once there is a query; until then the library stays under the open field. It also
    // stays until the first results are there: giving the page up on the first letter, a moment before anything
    // could take its place, left it blank in between.
    val querying = searchState.open && search.query.isNotBlank()
    val answered = querying && search.resultsQuery.isNotBlank()
    LaunchedEffect(answered) { searchState.showResults(answered) }
    // Not under a page (a note, the licences): its Back comes first. This page stays composed beneath it.
    val covered = shell.libraryCovered
    BackHandler(enabled = searchState.open && !covered, onBack = closeSearch)
    BackHandler(enabled = renaming != null && !covered) { renaming = null }
    // The notes that can be selected now, by id: a selected note that left (deleted elsewhere, or now recording or
    // being transcribed) leaves the selection with it.
    val selectable = remember(model) {
        buildMap {
            (model.stage as? StageUi.Note)?.row?.let { put(it.id, it) }
            model.days.forEach { day -> day.rows.forEach { put(it.id, it) } }
        }.filterValues { it.phase !in LibraryModels.BUSY }
    }
    // Only once the notes are known: a selection restored with the page must not be emptied by a library still loading.
    LaunchedEffect(selectable, model.loaded) { if (model.loaded) selection.retain(selectable.keys) }
    val selecting = selection.active
    BackHandler(enabled = selecting && !covered) { selection.clear() }
    // Settings may open only from the library at rest (spec 4.23.3), and on screen.
    val armed = !covered && !searchState.open && renaming == null && !imeVisible && !selecting
    SideEffect { shell.settings.armed = armed }

    if (model.loaded) {
        val currentSelectable by rememberUpdatedState(selectable)
        val dockActions = remember(actions, searchState, selection) {
            DockActions(
                search = { scope.launch { searchState.open() } },
                record = actions.record, pause = actions.pause, resume = actions.resume, finish = actions.finish,
                openSettings = actions.openSettings,
                closeSelection = { selection.clear() },
                deleteSelected = {
                    val rows = selection.ids.mapNotNull { currentSelectable[it] }
                    selection.clear()
                    if (rows.isNotEmpty()) actions.deleteAll(rows)
                },
                removeSelectedAudio = {
                    val rows = selection.ids.mapNotNull { currentSelectable[it] }.filter { it.canRemoveAudio }
                    selection.clear()
                    if (rows.isNotEmpty()) actions.removeAudioAll(rows)
                },
                selectedAudioSize = { actions.audioSize(selection.ids.mapNotNull { currentSelectable[it] }.filter { it.canRemoveAudio }) },
            )
        }
        // ⋮ is there only while a selected note has audio that can go.
        val selectedAudio = selecting && selection.ids.any { selectable[it]?.canRemoveAudio == true }
        DestinationBottom(
            Destination.Library, if (selecting && model.live == null) DockState.Selecting(selection.count, selectedAudio) else model.dock, dockActions,
            rows = if (searchState.fieldShown) { { SearchField(searchState, query, onClose = closeSearch) } } else null,
            dockHidden = searchState.dockHidden || renaming != null,
        )
    }

    // A data class from the resolved words: equal across recompositions, so the row and stage actions built on it
    // below stay the same objects and the list's rows can skip.
    val menuLabels = MenuLabels(
        copyText = stringResource(R.string.menu_copy_text), share = stringResource(R.string.menu_share),
        rename = stringResource(R.string.menu_rename), removeAudio = stringResource(R.string.menu_remove_audio),
        delete = stringResource(R.string.menu_delete),
    )
    val menuActions = remember(actions) {
        NoteMenuActions(
            copyText = actions.copyText, share = actions.share, rename = { renaming = it.id },
            removeAudio = actions.removeAudio, delete = actions.delete,
        )
    }
    // A long press selects; a tap opens, or toggles while notes are selected. A note that cannot be deleted now (it is
    // being transcribed) is not selectable: a long press on it does nothing, a tap still opens it. Nor does a selection
    // begin where its dock (✕ | "Delete N") cannot stand: while a recording runs (the dock is the recording's), or
    // beside an open note in two panes (the dock is the note's). It would be a mode with no way to act in it.
    val canBegin by rememberUpdatedState(model.live == null && shell.current == Destination.Library)
    val select: (NoteRow) -> Unit = { row ->
        if (row.phase !in LibraryModels.BUSY && renaming == null && (selection.active || canBegin)) selection.toggle(row.id)
    }
    val rowActions = remember(actions, menuLabels, selection) {
        RowActions(
            open = { row -> if (selection.active) select(row) else actions.open(row) },
            select = select,
            menuActions = { row -> menuItems(row).map { item -> CustomAccessibilityAction(menuLabels.of(item)) { menuActions.run(item, row); true } } },
            prepare = actions.prepare,
        )
    }
    val stageActions = remember(actions, rowActions, selection) {
        StageActions(
            open = { row -> if (selection.active) select(row) else actions.openStage(row) },
            expand = actions.expand, copy = actions.copy, openSettings = actions.openSettings,
            select = select,
            menuActions = rowActions.menuActions,
            prepare = actions.prepare,
        )
    }
    // The same lambda across recompositions, so the list it is handed to can skip.
    val renameField: @Composable (NoteRow, onStage: Boolean) -> Unit = remember(actions, rename, renameBounds) { { row, onStage ->
        val colors = FocalTheme.colors
        // On the material the paper's `select` and ink handles would vanish: stageInk handles on a light veil.
        val stageSelection = remember(colors.stageInk) { TextSelectionColors(handleColor = colors.stageInk, backgroundColor = Stage.selection) }
        val selection = if (onStage) stageSelection else LocalTextSelectionColors.current
        CompositionLocalProvider(LocalTextSelectionColors provides selection) {
            RenameField(
                initial = row.title,
                // Kept with the rename's state, above the list (a row's own saved state stays with its place).
                draft = rename.draft,
                style = if (onStage) FocalType.headline else FocalType.rowTitle,
                color = if (onStage) colors.stageInk else colors.ink,
                caret = if (onStage) colors.stageInk else colors.ink,
                onCommit = { text ->
                    rename.noteId = null
                    if (text != row.title) actions.rename(row, text)
                },
                modifier = Modifier.onGloballyPositioned { renameBounds.rect = it.boundsInWindow() },
            )
        }
    } }

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
            )
            // A note's page folding back waits until this page is laid out again (one pane composes it anew).
            .onPlaced { shell.pages.library = it }
            // Beside the landscape rail, in the list pane of two panes, inside the side insets (spec 5.14).
            .windowInsetsPadding(pageInsets(PageArea.Library)),
    ) {
        // The library layer fades out under the results (T17). Under open search it is neither touchable nor read: a
        // tap on it, while it still shows under an empty field, closes search (a tap outside the field).
        val behindSearch = searchState.open
        Box(
            Modifier
                .fillMaxSize()
                // The library dissolves as the results come up from the field. They overlap for a moment: a
                // fade-through here left the whole page blank between a dark stage and the results, a flash at
                // the first letter typed.
                .graphicsLayer { alpha = 1 - smoothstep(0f, LIBRARY_GONE, searchState.content.value) }
                .inert(behindSearch)
                .then(if (behindSearch) Modifier.tapsOnly { if (!querying) closeSearch() } else Modifier),
        ) {
            if (model.loaded) LibraryContent(
                model, dates, today, meter, listState, stageActions, rowActions, selection,
                renaming = renaming, renameField = renameField, imePadded = renaming != null,
                openSettings = actions.openSettings, stageFocus = stageFocus, liveSeconds = liveSeconds,
                // The library itself is on screen: no page, settings, search or scene over it.
                onScreen = {
                    !shell.libraryCovered && !searchState.open && shell.settings.progress.value <= 0f && shell.live.expansion.value <= 0f
                },
            )
        }
        // Read through derived state: the page composes when the results come or go, not on every frame of their fade.
        val resultsShown by remember(searchState) { derivedStateOf { searchState.open || searchState.content.value > 0f } }
        if (resultsShown) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val shown = smoothstep(RESULTS_FROM, 1f, searchState.content.value)
                    alpha = shown
                    translationY = (1 - shown) * RESULTS_RISE.toPx()
                },
            ) {
                SearchResults(search, bottom, dates, today, onOpen = { result ->
                    focusManager.clearFocus()
                    actions.openResult(result)
                }, list = searchList)
                LibraryTopFade({ Float.POSITIVE_INFINITY }, Modifier.fillMaxSize())
            }
        }
        BottomFade(
            bottom, Modifier.fillMaxSize(), keyboard = searchState.open,
            // Over the library it is whole; over the results it waits for the first row to scroll under it.
            alpha = { lerp(1f, searchFadeAlpha(searchList, density), smoothstep(HALF, 1f, searchState.content.value)) },
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
    selection: LibrarySelectionState,
    renaming: String?,
    renameField: @Composable (NoteRow, Boolean) -> Unit,
    imePadded: Boolean,
    openSettings: () -> Unit,
    stageFocus: FocusRequester?,
    liveSeconds: (() -> Long)?,
    onScreen: () -> Boolean,
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
    // Whether each is still on screen, read through derived state: not every frame of their crossfade.
    val emptyShown by remember { derivedStateOf { emptyAlpha.value > 0f } }
    val listShown by remember { derivedStateOf { listAlpha.value > 0f } }
    if (empty || emptyShown) {
        EmptyLibrary(openSettings, Modifier.fillMaxSize().graphicsLayer { alpha = emptyAlpha.value })
    }
    if (!empty || listShown) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = listAlpha.value }) {
            LibraryList(
                model, dates, today, meter, listState, stageActions, rowActions, selection, renaming, renameField, imePadded, stageFocus,
                liveSeconds, onScreen,
            )
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
    selection: LibrarySelectionState,
    renaming: String?,
    renameField: @Composable (NoteRow, Boolean) -> Unit,
    imePadded: Boolean,
    stageFocus: FocusRequester?,
    liveSeconds: (() -> Long)?,
    onScreen: () -> Boolean,
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
    // Dragging a stage that holds a note down opens the note the same way (the card's edge follows the finger).
    val stagePull = remember(shell.pages) { StagePull(shell.pages) }
    val stageRow = (model.stage as? StageUi.Note)?.row
    stagePull.travel = { windowHeight - (live.stageBounds?.bottom ?: 0f) }
    stagePull.density = density.density
    stagePull.note = stageRow?.id
    stagePull.open = { stageRow?.let(stageActions.open) }
    stagePull.drop = { page -> shell.dropPulled(page) }
    // Not while notes are selected or a title is renamed (a drag then scrolls, as anywhere), nor beside an open note.
    val canPull by rememberUpdatedState(stageRow != null && !selection.active && renaming == null && !shell.twoPane && shell.current == Destination.Library)
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
    val texts = remember(model.days, dates, today) { model.days.associate { dayKey(it.epochDay) to dates.dayLabel(it.epochDay, today) } }
    val stageShown = { onScreen() && listState.layoutInfo.visibleItemsInfo.any { it.key == STAGE_KEY } }
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The stage, the labels and the rows keep the content column: at most 600 dp, centred (spec 2.5, 4.22.1).
        val side = FocalLayout.columnInset(maxWidth) - FocalLayout.gutter
        // While a page covers the library, the bottom column is that page's: the hidden list keeps the reserve and the
        // fade it had, instead of measuring and drawing again with every frame of the page's own bottom motion.
        val held = remember(shell) { HeldWhileCovered { shell.libraryCovered } }
        val padding = remember(top, side, shell.bottom, density, held) { held.padding(shell.bottom.contentPadding(top, density, side)) }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // Nothing of the list is drawn under the solid paper of its fades (the status edge above, the bottom
                // fade below), where it is covered anyway: behind the recording scene the pages are scaled down, so
                // the window's edges land inside a pixel that the paper covers only partly, and the glyphs under it
                // would show there as a dotted line.
                .drawWithContent {
                    val solidBottom = held.fadeEnd { shell.bottom.fadeEnd(this) }
                    clipRect(top = status.getTop(this).toFloat(), bottom = if (solidBottom.isNaN()) size.height else solidBottom) {
                        this@drawWithContent.drawContent()
                    }
                }
                .then(if (imePadded) Modifier.imePadding() else Modifier)
                .nestedScroll(expandDrag)
                .nestedScroll(stagePull)
                .pointerInput(expandDrag, stagePull) {
                    // Every stream starts disarmed; the stage arms the ones that start on it (it sees them after).
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        expandDrag.arm(false)
                        stagePull.arm(false)
                    }
                },
            contentPadding = padding,
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
                            .onGloballyPositioned { coordinates -> live.stageBounds = live.unscaled(coordinates) }
                            // The morph draws the stage while it is up (spec 6.4); under the crossfade it stays.
                            .graphicsLayer { alpha = if (live.spatial && live.expansion.value > 0f) 0f else 1f }
                            .then(
                                if (!isLive) Modifier.pointerInput(stagePull) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                                        // Not while the settings swipe still settles: the library is still moving sideways.
                                        stagePull.arm(atTop && canPull && shell.settings.progress.value <= 0f)
                                        while (stagePull.deciding) {
                                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            val travel = change.position - down.position
                                            stagePull.track(travel.x, travel.y, viewConfiguration.touchSlop)
                                        }
                                    }
                                } else Modifier.pointerInput(expandDrag) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                                        expandDrag.arm(atTop && live.expansion.value <= 0f)
                                        // The stream's direction, from its touch-down, at its first move past the slop
                                        // (seen on the Initial pass, before the list starts to scroll).
                                        while (expandDrag.deciding) {
                                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            val travel = change.position - down.position
                                            expandDrag.track(travel.x, travel.y, viewConfiguration.touchSlop)
                                        }
                                    }
                                },
                            ),
                        title = if (row != null && renaming == row.id) { { renameField(row, true) } } else null,
                        shown = stageShown,
                        liveSeconds = liveSeconds,
                        selected = row != null && selection.contains(row.id),
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
                            .foldLabel(shell.pages, key)
                            .animateItem(fadeInSpec = FocalMotion.fade(), placementSpec = placement, fadeOutSpec = FocalMotion.fadeFast()),
                    )
                }
                itemsIndexed(day.rows, key = { _, row -> row.id }, contentType = { _, _ -> ROW_TYPE }) { at, row ->
                    NoteRowItem(
                        row, dates, rowActions,
                        modifier = Modifier.animateItem(fadeInSpec = FocalMotion.fade(), placementSpec = placement, fadeOutSpec = FocalMotion.fadeFast()),
                        title = if (renaming == row.id) { { renameField(row, false) } } else null,
                        capturing = model.live != null,
                        selected = selection.contains(row.id),
                        // Within a day the rows touch; a day's label parts it from the next.
                        joinsAbove = day.rows.getOrNull(at - 1)?.let { selection.contains(it.id) } == true,
                        joinsBelow = day.rows.getOrNull(at + 1)?.let { selection.contains(it.id) } == true,
                    )
                }
            }
        }
        LibraryTopFade(stageBottom, Modifier.fillMaxSize())
        StickyLabelOverlay(labels, { key -> texts[key].orEmpty() }, Modifier)
    }
}

private const val HALF = 0.5f
/** The library is gone at this share of the change to the results, which start to arrive at [RESULTS_FROM]. */
private const val LIBRARY_GONE = 0.7f
private const val RESULTS_FROM = 0.3f
/** The results rise this far as they arrive, from the field they answer. */
private val RESULTS_RISE = FocalGrid.x12
private const val STAGE_KEY = "stage"
private const val DAY_TYPE = "day"
private const val ROW_TYPE = "row"
private fun dayKey(epochDay: Long) = "day:$epochDay"

/** The first label sits 32 dp under the stage (spec 4.12.1). */
private fun firstLabelGap(afterStage: Boolean) = if (afterStage) FocalGrid.x32 else 0.dp
/** 36 dp from a preview to the next label, less the row's 12 dp hit area below it. */
private val SECTION_GAP = FocalGrid.x36 - RowMetrics.hitPad
/** 16 dp from a label to the next title, less the row's 12 dp hit area above it. */
private val LABEL_TO_TITLE = FocalGrid.x16 - RowMetrics.hitPad

/**
 * A layer that takes every touch before what is under it, so nothing there can be pressed or scrolled; a touch that
 * lifts where it came down (within the slop) is a tap ([onTap]).
 */
private fun Modifier.tapsOnly(onTap: () -> Unit): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            down.consume()
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (!change.pressed) {
                    if (!moved) tap()
                    break
                }
            }
        }
    }
}

/**
 * Which note's title is renamed in place (the stage's or a row's), if any, and what its field holds so far; kept
 * across recreation.
 */
@Stable
class LibraryRenameState internal constructor(noteId: String?, draft: TextFieldValue? = null) {
    private var renamed by mutableStateOf(noteId)

    /** A rename of another note (or none) starts again from the title. */
    var noteId: String?
        get() = renamed
        set(value) {
            if (value != renamed) draft.value = null
            renamed = value
        }

    /** The rename's text and selection so far; null until it is first edited (the title, all selected). */
    val draft: MutableState<TextFieldValue?> = mutableStateOf(draft)

    companion object {
        val Saver: Saver<LibraryRenameState, Any> = listSaver(
            save = { state ->
                val draft = state.draft.value
                listOf(state.noteId, draft?.text, draft?.selection?.start, draft?.selection?.end)
            },
            restore = { saved ->
                val text = saved[1] as String?
                LibraryRenameState(saved[0] as String?, text?.let { TextFieldValue(it, TextRange(saved[2] as Int, saved[3] as Int)) })
            },
        )
    }
}

/** The notes selected in the library (spec 5.1.7), by id, in the order they were selected; kept across recreation. */
@Stable
class LibrarySelectionState internal constructor(ids: List<String> = emptyList()) {
    private val selected = mutableStateListOf<String>().apply { addAll(ids) }

    val ids: List<String> get() = selected.toList()
    val count: Int get() = selected.size
    /** Notes are selected: taps toggle, the dock is ✕ | "Delete N". */
    val active: Boolean get() = selected.isNotEmpty()

    fun contains(id: String): Boolean = id in selected

    /** Adds [id] to the selection, or takes it out; taking out the last one ends the selection. */
    fun toggle(id: String) {
        if (!selected.remove(id)) selected += id
    }

    fun clear() = selected.clear()

    /** Drops the selected notes that are not among [existing] any more. */
    fun retain(existing: Set<String>) {
        selected.removeAll { it !in existing }
    }

    companion object {
        val Saver: Saver<LibrarySelectionState, Any> = listSaver(save = { it.ids }, restore = { LibrarySelectionState(it) })
    }
}

@Composable
fun rememberLibrarySelectionState(ids: List<String> = emptyList()): LibrarySelectionState =
    rememberSaveable(saver = LibrarySelectionState.Saver) { LibrarySelectionState(ids) }

@Composable
fun rememberLibraryRenameState(noteId: String? = null): LibraryRenameState =
    rememberSaveable(saver = LibraryRenameState.Saver) { LibraryRenameState(noteId) }

@Immutable
private data class MenuLabels(val copyText: String, val share: String, val rename: String, val removeAudio: String, val delete: String) {
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
) {
    fun run(item: MenuItem, row: NoteRow) = when (item) {
        MenuItem.CopyText -> copyText(row)
        MenuItem.Share -> share(row)
        MenuItem.Rename -> rename(row)
        MenuItem.RemoveAudio -> removeAudio(row)
        MenuItem.Delete -> delete(row)
    }
}

/** API 32 and below show no system clipboard preview, so a row's Copy text confirms in the pill (5.1.6). */
val confirmsCopyInApp: Boolean get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2

/**
 * What the library's list reads of the shared bottom column, held at its last value while a page covers the library
 * ([covered]): the column then moves for that page (its player, Find, the keyboard), and the hidden list would measure
 * or draw again on every frame of it. Uncovered, it reads the column again.
 */
private class HeldWhileCovered(private val covered: () -> Boolean) {
    private var bottom = Dp.Unspecified
    private var fade = Float.NaN
    private var faded = false

    fun padding(live: PaddingValues): PaddingValues = object : PaddingValues {
        override fun calculateTopPadding(): Dp = live.calculateTopPadding()
        override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = live.calculateLeftPadding(layoutDirection)
        override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = live.calculateRightPadding(layoutDirection)
        override fun calculateBottomPadding(): Dp {
            if (covered() && bottom.isSpecified) return bottom
            return live.calculateBottomPadding().also { bottom = it }
        }
    }

    fun fadeEnd(live: () -> Float): Float {
        if (covered() && faded) return fade
        return live().also {
            fade = it
            faded = true
        }
    }
}

