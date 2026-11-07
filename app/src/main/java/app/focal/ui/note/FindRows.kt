package app.focal.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.LocalWindowLayout
import app.focal.ui.design.WindowLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import app.focal.R
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.Glyph
import app.focal.ui.components.KeyboardRow
import app.focal.ui.components.KeyboardRowMetrics
import app.focal.ui.components.PressPlate
import app.focal.ui.components.focusRing
import app.focal.ui.components.pressScale
import app.focal.ui.components.scrollEdgeFade
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.tabular
import kotlin.math.max

/** Find row geometry (spec 4.9.2): fields 24-244, controls 252-388, 48 dp rows. */
object FindMetrics {
    /** ← and ↳ sit in a 48 dp box at the field's start, so the text starts at x 72 in both rows. */
    val lead = FocalGrid.x48
    /** The count ends 16 dp inside the field, at least 8 dp after the query. */
    val countEnd = FocalGrid.x16
    val countGap = FocalGrid.x8
    val replacePadding = FocalGrid.x8
    /** Stacked under the query, the count and the query keep this much room above and below them. */
    val stackPadding = FocalGrid.x8
    /** The caret the query's end must still show (BasicTextField's 2 dp cursor, spec 4.9.1). */
    val caret = FocalGrid.x2
    /** The count stacks under the query only when, beside the widest count, less than this is left for the query. */
    val minQuery = FocalGrid.x48
    /** Side by side (a short window), each row is at most a phone's bottom column, and its field at least 180 dp. */
    val phoneColumn = 364.dp
    val minSideField = 180.dp
    /**
     * In one row (a short window too narrow for the pair), each field keeps at least its lead and one 48 dp target's
     * worth of text.
     */
    val minSingleField = 96.dp
}

/** How the find rows stand in the window (spec 4.9.2, 5.8, 5.14). */
internal sealed interface FindArrangement {
    /** The find row over the replace row: portrait, tablets, and a short window too narrow for one row. */
    data object Stacked : FindArrangement
    /** A short window: the two rows side by side, each at most a phone's bottom column, [width] in all. */
    data class Paired(val width: Dp) : FindArrangement
    /**
     * A short window too narrow for the pair: one row of [width], the find field, the replace field, the count, ∧ ∨
     * and Replace all, so a landscape keyboard still leaves the note's text above it.
     */
    data class Single(val width: Dp) : FindArrangement

    companion object {
        /**
         * The arrangement for [room] (the content's width beside the rail, inside the gutters and side insets) in a
         * short window ([rail]), with the count's widest form [count] wide.
         */
        fun of(rail: Boolean, room: Dp, count: Dp): FindArrangement {
            if (!rail) return Stacked
            val gap = KeyboardRowMetrics.columnGap
            val controls = KeyboardRowMetrics.controls
            val row = (room - gap) / 2
            if (row - gap - controls >= FindMetrics.minSideField) return Paired(minOf(room, FindMetrics.phoneColumn * 2 + gap))
            val field = (room - controls * 2 - count - gap * 4) / 2
            return if (field >= FindMetrics.minSingleField) Single(room) else Stacked
        }
    }
}

/** What the find rows do. */
internal class FindActions(
    val close: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val replaceAll: () -> Unit,
)

/**
 * The two find rows riding the keyboard (spec 4.9.2, 5.8): the find field with "2 of 4" and the ∧ ∨ pill, then the
 * replace field and "Replace all (N)". Search on the keyboard moves to the next match; Done in the replace field
 * hides the keyboard and the rows drop to the dock's place. When a cell grows (a stacked count, a wrapped "Replace
 * all" at large font sizes), both cells of its row take the row's height (spec 8.4). In a short window the rows stand
 * side by side, or, too narrow for that, make one row ([FindArrangement]).
 */
@Composable
internal fun ColumnScope.FindRows(find: FindState, actions: FindActions) {
    val count = find.matches.size
    val findRow = @Composable { modifier: Modifier ->
        KeyboardRow(
            field = { FindField(find, actions) },
            control = { NavPill(enabled = count > 0, actions) },
            modifier = modifier,
        )
    }
    val replaceRow = @Composable { modifier: Modifier ->
        KeyboardRow(
            field = { ReplaceField(find.replacement) },
            control = { ReplaceAll(count = count, enabled = find.query.text.isNotEmpty() && count > 0, onClick = actions.replaceAll) },
            modifier = modifier,
        )
    }
    val countReserve = countReserve()
    when (val arrangement = findArrangement(countReserve)) {
        // A short window: the two rows stand side by side, each as wide as a phone's bottom column, so a landscape
        // keyboard leaves room above them for the current match (spec 5.8, 5.14). Centred on the column, wider than it.
        is FindArrangement.Paired -> Row(
            Modifier.requiredWidth(arrangement.width), horizontalArrangement = Arrangement.spacedBy(KeyboardRowMetrics.columnGap),
        ) {
            findRow(Modifier.weight(1f))
            replaceRow(Modifier.weight(1f))
        }
        // Narrower: one row, the count moved out of the find field to stand before ∧ ∨, as wide as its widest form.
        is FindArrangement.Single -> Row(
            Modifier.requiredWidth(arrangement.width).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(KeyboardRowMetrics.columnGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FindField(find, actions, withCount = false)
            ReplaceField(find.replacement)
            Box(Modifier.width(countReserve).fillMaxHeight(), contentAlignment = Alignment.Center) { FindCount(find) }
            Row(Modifier.width(KeyboardRowMetrics.controls).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                NavPill(enabled = count > 0, actions)
            }
            Row(Modifier.width(KeyboardRowMetrics.controls).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                ReplaceAll(count = count, enabled = find.query.text.isNotEmpty() && count > 0, onClick = actions.replaceAll)
            }
        }
        FindArrangement.Stacked -> {
            findRow(Modifier.fillMaxWidth())
            replaceRow(Modifier.fillMaxWidth())
        }
    }
}

/**
 * The arrangement for this window: in a short window (the dock in the rail), the room is what the window has beside
 * the rail, inside the gutters and the side insets.
 */
@Composable
private fun findArrangement(count: Dp): FindArrangement {
    val layout = LocalWindowLayout.current
    if (!layout.rail) return FindArrangement.Stacked
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val sides = WindowInsets.safeDrawing
    val insets = with(density) { (sides.getLeft(this, direction) + sides.getRight(this, direction)).toDp() }
    return FindArrangement.of(rail = true, room = layout.width - WindowLayout.railWidth - FocalLayout.gutter * 2 - insets, count = count)
}

/** The width of the count's widest form ("No matches") at this font size: the row keeps it while the count changes. */
@Composable
private fun countReserve(): Dp {
    val measurer = rememberTextMeasurer()
    val none = stringResource(R.string.find_none)
    val style = FocalType.meta.tabular()
    val density = LocalDensity.current
    return remember(none, style, measurer, density) {
        with(density) { measurer.measure(none, style, maxLines = 1, softWrap = false).size.width.toDp() }
    }
}

/** "2 of 4" or "No matches" once the query has been matched; nothing before. Announced politely as it changes. */
@Composable
private fun FindCount(find: FindState) {
    val count = countText(find) ?: return
    Text(
        count, style = FocalType.meta.tabular(), color = FocalTheme.colors.recede, maxLines = 1,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun countText(find: FindState): String? = when {
    find.matchedQuery.isEmpty() || find.query.text.isEmpty() -> null
    find.matches.isEmpty() -> stringResource(R.string.find_none)
    else -> stringResource(R.string.find_count, find.current + 1, find.matches.size)
}

/** ← and the query; with [withCount], the count at the field's end (spec 4.9.2), else it stands outside (one row). */
@Composable
private fun RowScope.FindField(find: FindState, actions: FindActions, withCount: Boolean = true) {
    val colors = FocalTheme.colors
    val hint = stringResource(R.string.find_hint)
    val count = countText(find)
    // Whether the count stacks is decided by its widest form at this font size, never by what is typed: the row keeps
    // its height while the query and its count change (spec 4.9.2). A query longer than its room scrolls under its fade.
    val measurer = rememberTextMeasurer()
    val none = stringResource(R.string.find_none)
    val countStyle = FocalType.meta.tabular()
    val reserve = remember(none, countStyle, measurer, withCount) {
        if (withCount) measurer.measure(none, countStyle, maxLines = 1, softWrap = false).size else IntSize.Zero
    }
    val scroll = rememberScrollState()
    Row(
        Modifier.weight(1f).fillMaxHeight().heightIn(min = KeyboardRowMetrics.row).background(colors.s1, FocalShapes.pill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val back = remember { MutableInteractionSource() }
        val close = stringResource(R.string.cd_find_close)
        Box(
            Modifier
                .size(FindMetrics.lead)
                .focusRing(FocalShapes.pill)
                .clickable(back, indication = LeadPlate, role = Role.Button, onClick = actions.close)
                .semantics { contentDescription = close },
            contentAlignment = Alignment.Center,
        ) { Glyph(FocalIcon.Back, colors.ink) }
        QueryAndCount(
            reserve = reserve,
            query = {
                BasicTextField(
                    state = find.query,
                    textStyle = FocalType.field.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.ink),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    onKeyboardAction = KeyboardActionHandler { actions.next() },
                    scrollState = scroll,
                    modifier = Modifier
                        .scrollEdgeFade(scroll)
                        .focusRequester(find.queryFocus)
                        .semantics { contentDescription = hint },
                    decorator = TextFieldDecorator { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (find.query.text.isEmpty()) Text(hint, style = FocalType.field, color = colors.recede, maxLines = 1)
                            field()
                        }
                    },
                )
            },
            count = {
                if (withCount && count != null) Text(
                    count, style = countStyle, color = colors.recede, maxLines = 1,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(FindMetrics.countEnd))
    }
}

/**
 * The query and its count (spec 4.9.2, 8.4): side by side, the count pinned at the end, while the field leaves the
 * query at least [FindMetrics.minQuery] beside the count's widest form ([reserve], "No matches"); otherwise (large
 * font scales) the count's line goes under the query, kept even while there is no count, so the field never changes
 * height as the query is typed or its count changes.
 */
@Composable
private fun QueryAndCount(reserve: IntSize, query: @Composable () -> Unit, count: @Composable () -> Unit, modifier: Modifier) {
    val policy = remember(reserve) { QueryCountPolicy(reserve) }
    Layout(contents = listOf(query, count), modifier = modifier, measurePolicy = policy)
}

private class QueryCountPolicy(private val reserve: IntSize) : MultiContentMeasurePolicy {
    // Without a count (one row: it stands outside the field) the query never stacks.
    private fun Density.stacked(width: Int): Boolean = reserve != IntSize.Zero &&
        width - FindMetrics.caret.roundToPx() - FindMetrics.countGap.roundToPx() - reserve.width < FindMetrics.minQuery.roundToPx()

    private fun Density.inlineQueryWidth(width: Int, countWidth: Int?): Int =
        if (countWidth == null) width else (width - FindMetrics.countGap.roundToPx() - countWidth).coerceAtLeast(0)

    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val width = constraints.maxWidth
        val countPlaceable = measurables[1].firstOrNull()?.measure(Constraints(maxWidth = width))
        val pad = FindMetrics.stackPadding.roundToPx()
        if (stacked(width)) {
            val queryPlaceable = measurables[0].first().measure(Constraints.fixedWidth(width))
            val height = max(constraints.minHeight, pad + queryPlaceable.height + max(reserve.height, countPlaceable?.height ?: 0) + pad)
            return layout(width, height) {
                queryPlaceable.place(0, pad)
                countPlaceable?.place(0, pad + queryPlaceable.height)
            }
        }
        val queryPlaceable = measurables[0].first().measure(Constraints.fixedWidth(inlineQueryWidth(width, countPlaceable?.width)))
        val height = max(constraints.minHeight, max(queryPlaceable.height, countPlaceable?.height ?: 0))
        return layout(width, height) {
            queryPlaceable.place(0, (height - queryPlaceable.height) / 2)
            countPlaceable?.place(width - countPlaceable.width, (height - countPlaceable.height) / 2)
        }
    }

    // The rows give both cells one height (spec 8.4), which asks the field for its height at a width: the same rule.
    private fun IntrinsicMeasureScope.height(measurables: List<List<IntrinsicMeasurable>>, width: Int): Int {
        val query = measurables[0].first()
        val count = measurables[1].firstOrNull()
        val countWidth = count?.maxIntrinsicWidth(Constraints.Infinity)
        if (stacked(width)) {
            val countHeight = max(reserve.height, count?.minIntrinsicHeight(countWidth!!) ?: 0)
            return 2 * FindMetrics.stackPadding.roundToPx() + query.minIntrinsicHeight(width) + countHeight
        }
        return max(query.minIntrinsicHeight(inlineQueryWidth(width, countWidth)), count?.minIntrinsicHeight(countWidth!!) ?: 0)
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<List<IntrinsicMeasurable>>, width: Int): Int = height(measurables, width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<List<IntrinsicMeasurable>>, width: Int): Int = height(measurables, width)
}

@Composable
private fun RowScope.ReplaceField(state: TextFieldState) {
    val colors = FocalTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val hint = stringResource(R.string.replace_hint)
    val scroll = rememberScrollState()
    Row(
        Modifier.weight(1f).fillMaxHeight().heightIn(min = KeyboardRowMetrics.row).background(colors.s1, FocalShapes.pill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(FindMetrics.lead).semantics { hideFromAccessibility() }, contentAlignment = Alignment.Center) {
            Glyph(FocalIcon.ReplaceWith, colors.recede)
        }
        BasicTextField(
            state = state,
            textStyle = FocalType.field.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.ink),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = KeyboardActionHandler { keyboard?.hide() },
            scrollState = scroll,
            modifier = Modifier.weight(1f).padding(end = FindMetrics.countEnd).scrollEdgeFade(scroll).semantics { contentDescription = hint },
            decorator = TextFieldDecorator { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty()) Text(hint, style = FocalType.field, color = colors.recede, maxLines = 1)
                    field()
                }
            },
        )
    }
}

/** ∧ "Previous match" and ∨ "Next match" on one `s1` pill; both `faint` and disabled without matches. */
@Composable
private fun RowScope.NavPill(enabled: Boolean, actions: FindActions) {
    val colors = FocalTheme.colors
    Row(
        Modifier.fillMaxWidth().fillMaxHeight().heightIn(min = KeyboardRowMetrics.row).background(colors.s1, FocalShapes.pill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavButton(FocalIcon.Up, stringResource(R.string.cd_find_prev), enabled, actions.previous)
        NavButton(FocalIcon.Down, stringResource(R.string.cd_find_next), enabled, actions.next)
    }
}

@Composable
private fun RowScope.NavButton(icon: FocalIcon, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .heightIn(min = KeyboardRowMetrics.row)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (enabled) this.onClick { onClick(); true } else disabled()
            }
            .focusRing(FocalShapes.pill)
            .clickable(source, indication = LeadPlate, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, if (enabled) colors.ink else colors.faint) }
}

/** "Replace all (4)" on `inv`; disabled it is "Replace all" on `s1` in `faint` (spec 4.9.2, R2). */
@Composable
private fun RowScope.ReplaceAll(count: Int, enabled: Boolean, onClick: () -> Unit) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    val label = if (enabled) stringResource(R.string.replace_all_n, count) else stringResource(R.string.replace_all)
    val spoken = if (enabled) pluralStringResource(R.plurals.a11y_replace_all, count, count) else stringResource(R.string.replace_all)
    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .heightIn(min = KeyboardRowMetrics.row)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                if (enabled) this.onClick { onClick(); true } else disabled()
            }
            .pressScale(source)
            .focusRing(FocalShapes.pill)
            .background(if (enabled) colors.inv else colors.s1, FocalShapes.pill)
            .clickable(source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = FindMetrics.replacePadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = FocalType.action.tabular(), color = if (enabled) colors.onInv else colors.faint,
            textAlign = TextAlign.Center,
        )
    }
}

/** The 40 dp press circle of a control inside a 48 dp row target. */
private val LeadPlate = PressPlate(FocalShapes.pill, insetX = FocalGrid.x4, insetY = FocalGrid.x4)
