package app.focal.ui.library

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.lerp
import app.focal.R
import app.focal.ui.components.BottomColumnState
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.Glyph
import app.focal.ui.components.rememberRowPlacement
import app.focal.ui.components.KeyboardRowMetrics
import app.focal.ui.components.focusRing
import app.focal.ui.components.scrollEdgeFade
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.columnInset
import app.focal.ui.design.smoothstep
import app.focal.ui.state.SearchRow
import app.focal.ui.state.SearchUiState
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Search geometry (spec 4.9.1, 5.2). */
object SearchMetrics {
    /** The field's leading ← and trailing × are 56 dp targets; the text runs between them. */
    val target = FocalGrid.x56
    /** The last result ends this far above the field; the count head and the "no results" line sit 16 above. */
    val resultsGap = FocalGrid.x24
    val headGap = FocalGrid.x16
}

/**
 * Library search (spec 5.2, T17). Opening it grows the dock's search circle into the keyboard-riding field; the
 * library stays where it is until there is something to look for, and gives way to the results once a query is typed
 * (an empty page with one field on it said nothing, and flashed white on the way in and out). Closing reverses it, and
 * the dock fades back in under the shrunk field before the field leaves, so nothing dips. [grow] is the field's width
 * (0 = the 56 dp circle, 1 = the column); [content] crossfades the library (out) and the results (in), and follows
 * [showResults]. A restored search starts as it was left: with its results, or over the library if it had no query.
 */
@Stable
class LibrarySearchState internal constructor(open: Boolean, results: Boolean = open) {
    var open by mutableStateOf(open)
        private set
    /** The field is in the bottom column (it stays while it shrinks back into the circle). */
    var fieldShown by mutableStateOf(open)
        private set
    var dockHidden by mutableStateOf(open)
        private set
    /** Set by [open]: the field takes focus (and the keyboard opens) once it is composed. A restored search does not. */
    internal var focusPending by mutableStateOf(false)
    val grow = Animatable(if (open) 1f else 0f)
    val content = Animatable(if (open && results) 1f else 0f)
    val focus = FocusRequester()

    suspend fun open() = coroutineScope {
        if (open) return@coroutineScope
        open = true
        fieldShown = true
        dockHidden = true
        focusPending = true
        grow.animateTo(1f, FocalMotion.moveUnit())
    }

    /** The results take the page ([show]: search is open with a query), or give it back to the library. */
    suspend fun showResults(show: Boolean) {
        content.animateTo(if (show && open) 1f else 0f, FocalMotion.fadeSlow())
    }

    /** Closes back into the circle; [clear] then empties the query. */
    suspend fun close(clear: () -> Unit) = coroutineScope {
        if (!open) return@coroutineScope
        open = false
        launch { content.animateTo(0f, FocalMotion.fade()) }
        grow.animateTo(0f, FocalMotion.moveUnit())
        dockHidden = false
        // The dock fades in on `fade` over the circle-sized field; the field leaves once it is covered.
        Animatable(0f).animateTo(1f, FocalMotion.fade())
        // Opened again meanwhile: the field and the query are the new search's.
        if (open) return@coroutineScope
        fieldShown = false
        clear()
    }

    companion object {
        val Saver: Saver<LibrarySearchState, Any> = listSaver(
            save = { listOf(it.open, it.content.targetValue > 0f) },
            restore = { LibrarySearchState(it[0], it[1]) },
        )
    }
}

/** [open]: start with search open (a restored state, or a screenshot); the field then waits in the dock slot. */
@Composable
fun rememberLibrarySearchState(open: Boolean = false): LibrarySearchState =
    rememberSaveable(saver = LibrarySearchState.Saver) { LibrarySearchState(open) }

/**
 * The search field (spec 4.9.1): `s1`, 56 dp, the column's width once grown. ← "Close search" sits where the
 * search glyph was (x 24-80) and crossfades from it; the query in `field`, "Search notes" as the placeholder; ×
 * "Clear" only while there is a query. The IME's Search hides the keyboard and keeps the results.
 */
@Composable
fun SearchField(search: LibrarySearchState, query: TextFieldState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FocalTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val circle = with(LocalDensity.current) { KeyboardRowMetrics.searchField.roundToPx() }
    val hint = stringResource(R.string.search_hint)
    val scroll = rememberScrollState()
    LaunchedEffect(search.focusPending) {
        if (!search.focusPending) return@LaunchedEffect
        search.focusPending = false
        search.focus.requestFocus()
    }
    Box(
        modifier
            .layout { measurable, constraints ->
                val width = lerp(circle.toFloat(), constraints.maxWidth.toFloat(), search.grow.value).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, circle))
                layout(constraints.maxWidth, circle) { placeable.place(0, 0) }
            }
            .background(colors.s1, FocalShapes.pill),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val back = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(SearchMetrics.target)
                    .focusRing(FocalShapes.pill)
                    .clickable(back, indication = null, role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(FocalIcon.Search, colors.ink, Modifier.graphicsLayer { alpha = 1 - smoothstep(0f, HALF, search.grow.value) })
                Glyph(
                    FocalIcon.Back, colors.ink, contentDescription = stringResource(R.string.cd_search_close),
                    modifier = Modifier.graphicsLayer { alpha = smoothstep(HALF, 1f, search.grow.value) },
                )
            }
            BasicTextField(
                state = query,
                textStyle = FocalType.field.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.ink),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                onKeyboardAction = KeyboardActionHandler { keyboard?.hide() },
                scrollState = scroll,
                modifier = Modifier
                    .weight(1f)
                    .scrollEdgeFade(scroll)
                    .focusRequester(search.focus)
                    .semantics { contentDescription = hint }
                    .graphicsLayer { alpha = smoothstep(HALF, 1f, search.grow.value) },
                decorator = TextFieldDecorator { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.text.isEmpty()) Text(hint, style = FocalType.field, color = colors.recede, maxLines = 1)
                        field()
                    }
                },
            )
            // It leaves with the field's text as the field shrinks back into the circle.
            ClearButton(query, shown = { smoothstep(HALF, 1f, search.grow.value) })
        }
    }
}

private const val HALF = 0.5f

/** × "Clear", shown only while there is a query (`fadeFast`). */
@Composable
private fun ClearButton(query: TextFieldState, shown: () -> Float = { 1f }) {
    val colors = FocalTheme.colors
    val visible = query.text.isNotEmpty()
    val alpha = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) { alpha.animateTo(if (visible) 1f else 0f, FocalMotion.fadeFast()) }
    val source = remember { MutableInteractionSource() }
    val description = stringResource(R.string.cd_search_clear)
    Box(
        Modifier
            .size(SearchMetrics.target)
            .graphicsLayer { this.alpha = alpha.value * shown() }
            .then(
                if (visible) Modifier.focusRing(FocalShapes.pill)
                    .clickable(source, indication = null, role = Role.Button) { query.clearText() }
                    .clearAndSetSemantics { contentDescription = description; role = Role.Button }
                else Modifier.clearAndSetSemantics { },
            ),
        contentAlignment = Alignment.Center,
    ) { Glyph(FocalIcon.Close, colors.ink) }
}

/** Keeps the view model's query in step with the field (the view model debounces it). */
@Composable
fun SyncQuery(query: TextFieldState, onQuery: (String) -> Unit) {
    LaunchedEffect(query) { snapshotFlow { query.text.toString() }.collect(onQuery) }
}

/**
 * The results (spec 5.2): a stack that grows upward from the field (`reverseLayout`, the most recent match nearest
 * the field), its last row 24 dp above the field and the count head 16 dp above the topmost title. An empty query
 * leaves the paper empty; no match leaves one line 16 dp above the field. The stack's bottom follows the field as it
 * rides the keyboard, read in the layout phase. The list runs down to the field's top, so a row scrolled toward the
 * field passes under the whole bottom fade (and [searchFadeAlpha]) instead of being cut 12 dp above it.
 */
@Composable
fun SearchResults(
    search: SearchUiState,
    bottom: BottomColumnState,
    dates: LibraryDates,
    today: LocalDate,
    onOpen: (SearchRow) -> Unit,
    modifier: Modifier = Modifier,
    list: LazyListState = rememberLazyListState(),
) {
    val colors = FocalTheme.colors
    val status = WindowInsets.statusBars
    val results = remember(search.results) { search.results.sortedByDescending { it.row.created } }
    // A new query starts at the field.
    LaunchedEffect(search.resultsQuery) { list.scrollToItem(0) }
    BoxWithConstraints(
        modifier.fillMaxSize().layout { measurable, constraints ->
            val height = bottom.fieldTop(constraints.maxHeight).coerceIn(0, constraints.maxHeight)
            val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
        },
    ) {
        // The results keep the content column (at most 600 dp, centred) over the field below them.
        val side = FocalLayout.columnInset(maxWidth) - FocalLayout.gutter
        val shows = when {
            search.resultsQuery.isBlank() -> ResultsShow.Nothing
            results.isEmpty() && !search.searching -> ResultsShow.None
            else -> ResultsShow.List
        }
        // The list and the "no notes" line take each other's place on `fade`, never in one frame.
        Crossfade(shows, Modifier.fillMaxSize(), animationSpec = FocalMotion.fade(), label = "results") { shown ->
            when (shown) {
                ResultsShow.Nothing -> Unit
                ResultsShow.None -> Box(Modifier.fillMaxSize()) {
                    Text(
                        stringResource(R.string.search_none, search.resultsQuery), style = FocalType.secondary, color = colors.recede,
                        modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = side + FocalLayout.gutter)
                            .padding(bottom = SearchMetrics.headGap),
                    )
                }
                ResultsShow.List -> {
                    // Rows glide only while the results change, never while the keyboard resizes the list (see
                    // rememberRowPlacement): glided then, the rows on screen trailed the field and overlapped the next.
                    val placement = rememberRowPlacement(remember(results) { results.map { it.row.id } })
                    LazyColumn(
                        state = list,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = side, end = side,
                            top = with(LocalDensity.current) { status.getTop(this).toDp() } + FocalLayout.topFade,
                            // The last row's hit area reaches 12 dp below its text: its text ends 24 dp above the field.
                            bottom = SearchMetrics.resultsGap - RowMetrics.hitPad,
                        ),
                    ) {
                        items(results, key = { it.row.id }, contentType = { "result" }) { result ->
                            SearchResultItem(
                                result, dates, today, onOpen,
                                Modifier.animateItem(fadeInSpec = FocalMotion.fade(), placementSpec = placement, fadeOutSpec = FocalMotion.fadeFast()),
                            )
                        }
                        item(key = "count", contentType = "head") {
                            Text(
                                pluralStringResource(R.plurals.search_count, results.size, results.size), style = FocalType.section,
                                color = colors.recede,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = FocalLayout.gutter)
                                    .padding(bottom = SearchMetrics.headGap - RowMetrics.hitPad).semantics { heading() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What the results page shows: nothing yet, "No notes found", or the matches. */
private enum class ResultsShow { Nothing, None, List }

/**
 * The bottom fade's alpha above the results (spec 2.6, 5.2): at rest every result clears it, so it stays off and the
 * nearest result's last line keeps its full contrast; it arrives over the first 16 dp that the stack scrolls toward
 * the field (as the library's pin band arrives, G1). Read it in the draw phase.
 */
fun searchFadeAlpha(list: LazyListState, density: Density): Float {
    if (list.firstVisibleItemIndex > 0) return 1f
    return (list.firstVisibleItemScrollOffset / with(density) { FocalLayout.topFade.toPx() }).coerceIn(0f, 1f)
}

/** The search field's top in the window: the topmost bottom element (the field, or the feedback pill above it). */
internal fun BottomColumnState.fieldTop(windowHeight: Int): Int {
    val value = top.value
    return if (value.isNaN()) windowHeight else value.roundToInt()
}
