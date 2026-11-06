package app.focal.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.style.TextAlign
import app.focal.R
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.Glyph
import app.focal.ui.components.KeyboardRow
import app.focal.ui.components.KeyboardRowMetrics
import app.focal.ui.components.PressPlate
import app.focal.ui.components.focusRing
import app.focal.ui.components.pressScale
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.Grid
import app.focal.ui.design.tabular

/** Find row geometry (spec 4.9.2): fields 24-244, controls 252-388, 48 dp rows. */
object FindMetrics {
    /** ← and ↳ sit in a 48 dp box at the field's start, so the text starts at x 72 in both rows. */
    val lead = Grid.x48
    /** The count ends 16 dp inside the field, at least 8 dp after the query. */
    val countEnd = Grid.x16
    val countGap = Grid.x8
    val replacePadding = Grid.x8
    /** From this font scale the count stacks under the query, inside a field that grows. */
    const val STACK_FONT = 1.5f
    val stackPadding = Grid.x8
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
 * hides the keyboard and the rows drop to the dock's place.
 */
@Composable
internal fun ColumnScope.FindRows(find: FindState, actions: FindActions) {
    val count = find.matches.size
    KeyboardRow(
        field = { FindField(find, actions) },
        control = { NavPill(enabled = count > 0, actions) },
        modifier = Modifier.fillMaxWidth(),
    )
    KeyboardRow(
        field = { ReplaceField(find.replacement) },
        control = { ReplaceAll(count = count, enabled = find.query.text.isNotEmpty() && count > 0, onClick = actions.replaceAll) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RowScope.FindField(find: FindState, actions: FindActions) {
    val colors = FocalTheme.colors
    val hint = stringResource(R.string.find_hint)
    val count = when {
        find.matchedQuery.isEmpty() || find.query.text.isEmpty() -> null
        find.matches.isEmpty() -> stringResource(R.string.find_none)
        else -> stringResource(R.string.find_count, find.current + 1, find.matches.size)
    }
    Row(
        Modifier.weight(1f).heightIn(min = KeyboardRowMetrics.row).background(colors.s1, FocalShapes.pill),
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
        val query: @Composable (Modifier) -> Unit = { modifier ->
            BasicTextField(
                state = find.query,
                textStyle = FocalType.field.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.ink),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                onKeyboardAction = KeyboardActionHandler { actions.next() },
                modifier = modifier
                    .focusRequester(find.queryFocus)
                    .semantics { contentDescription = hint },
                decorator = TextFieldDecorator { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (find.query.text.isEmpty()) Text(hint, style = FocalType.field, color = colors.recede, maxLines = 1)
                        field()
                    }
                },
            )
        }
        val counter: @Composable () -> Unit = {
            if (count != null) Text(
                count, style = FocalType.meta, color = colors.recede, maxLines = 1,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        // At large font scales the count goes under the query, so the query keeps the field's width (spec 8.4).
        if (LocalDensity.current.fontScale >= FindMetrics.STACK_FONT) {
            Column(Modifier.weight(1f).padding(vertical = FindMetrics.stackPadding)) {
                query(Modifier.fillMaxWidth())
                counter()
            }
        } else {
            query(Modifier.weight(1f))
            if (count != null) Spacer(Modifier.width(FindMetrics.countGap))
            counter()
        }
        Spacer(Modifier.width(FindMetrics.countEnd))
    }
}

@Composable
private fun RowScope.ReplaceField(state: TextFieldState) {
    val colors = FocalTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val hint = stringResource(R.string.replace_hint)
    Row(
        Modifier.weight(1f).heightIn(min = KeyboardRowMetrics.row).background(colors.s1, FocalShapes.pill),
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
            modifier = Modifier.weight(1f).padding(end = FindMetrics.countEnd).semantics { contentDescription = hint },
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
        Modifier.fillMaxWidth().heightIn(min = KeyboardRowMetrics.row).background(colors.s1, FocalShapes.pill),
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
private val LeadPlate = PressPlate(FocalShapes.pill, insetX = Grid.x4, insetY = Grid.x4)
