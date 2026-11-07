package app.focal.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.focal.R
import app.focal.ui.components.PressPlate
import app.focal.ui.components.focusRing
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.FocalWeight
import app.focal.ui.design.alignWith
import app.focal.ui.shell.LocalShell
import app.focal.ui.shell.pageOrigin
import app.focal.ui.shell.rememberFocusReport
import app.focal.ui.shell.rememberRowOrigin
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteRow
import app.focal.ui.state.SearchRow
import java.time.LocalDate
import kotlin.math.roundToInt

/** Row geometry (spec 4.12). */
object RowMetrics {
    /** The hit area reaches this far above the title and below the preview; rows are twice this apart. */
    val hitPad = FocalGrid.x12
    /** The press plate spans x 8-404: this far inside the full-width hit area. */
    val plateInset = FocalGrid.x8
    val durationGap = FocalGrid.x16
    val previewGap = FocalGrid.x4
    val trackGap = FocalGrid.x8
    val track = 2.dp
    const val TITLE_LINES = 2
    const val PREVIEW_LINES = 2
    /** From this font scale the duration wraps under the title. */
    const val WRAP_FONT = 1.5f
    /** The run-in and the text are separated by an en space (U+2002). */
    const val RUN_IN_GAP = " "
    const val PERCENT = 100
}

private val RowPlate = PressPlate(FocalShapes.block, insetX = RowMetrics.plateInset)

/** Where a long-press landed, for the menu at the touch point. */
private class TouchPoint { var at = Offset.Zero }

/** What a row can do; the page provides it. */
@Stable
class RowActions(
    val open: (NoteRow) -> Unit,
    val menu: (NoteRow, IntOffset) -> Unit,
    /** The row menu's items as TalkBack custom actions (spec 4.12.2: TalkBack gets the same actions). */
    val menuActions: (NoteRow) -> List<CustomAccessibilityAction>,
)

/**
 * A ready row (spec 4.12.2), or a transcribing or waiting one (4.12.4): the title with the duration flush right
 * like a page number, and a preview opening with a run-in. The hit area is the full width, 12 dp above the title
 * and below the preview; the press plate spans x 8-404. Long-press opens the menu at the touch point. [capturing]: a
 * recording holds the microphone, so the row's page will open with ▶ unavailable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteRowItem(
    row: NoteRow,
    dates: LibraryDates,
    actions: RowActions,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit = {},
    title: (@Composable () -> Unit)? = null,
    capturing: Boolean = false,
) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    val touch = remember { TouchPoint() }
    val runIn = when (row.phase) {
        NotePhase.TRANSCRIBING -> stringResource(R.string.row_transcribing)
        NotePhase.WAITING -> stringResource(R.string.row_waiting)
        NotePhase.PREPARING_MODEL -> stringResource(R.string.row_preparing)
        else -> dates.time(row.created)
    }
    val trailing = if (row.phase == NotePhase.TRANSCRIBING) {
        stringResource(R.string.row_percent, ((row.progress ?: 0f) * RowMetrics.PERCENT).toInt())
    } else durationText(row.durationMs)
    val titleText = noteTitle(row, dates)
    val menuActions = actions.menuActions(row)
    val pages = LocalShell.current.pages
    val origin = rememberRowOrigin(row.id, RowMetrics.TITLE_LINES, titleText, titleMuted = row.title.isBlank(), dock = pageDock(row, capturing))
    Box(
        modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull()?.takeIf { it.pressed && !it.previousPressed }?.let { touch.at = it.position }
                    }
                }
            }
            // While the title is renamed the row's menu is out of reach for touch, and so for TalkBack.
            .semantics { customActions = if (title == null) menuActions else emptyList() }
            .focusRing(FocalShapes.block)
            .combinedClickable(
                interactionSource = source, indication = RowPlate, enabled = title == null,
                onLongClick = { actions.menu(row, IntOffset(touch.at.x.roundToInt(), touch.at.y.roundToInt())) },
                onClick = { actions.open(row) },
            )
            .padding(horizontal = FocalLayout.gutter, vertical = RowMetrics.hitPad)
            // The row's content box is what a page grows out of (T16).
            .pageOrigin(origin),
    ) {
        Column {
            TitleLine(
                title = title ?: {
                    // Balanced, so a title never ends on a lone word; hidden while a copy of it flies to the page.
                    Text(
                        titleText, style = RowTitleStyle, color = if (row.title.isBlank()) colors.recede else colors.ink,
                        maxLines = RowMetrics.TITLE_LINES, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pageOrigin(origin, title = true)
                            .graphicsLayer { alpha = if (pages.flying(row.id)) 0f else 1f },
                    )
                },
                trailing = trailing,
                trailingInk = row.phase == NotePhase.TRANSCRIBING,
                titleText = titleText,
            )
            Spacer(Modifier.height(RowMetrics.previewGap))
            Preview(runIn, previewAfterTitle(row))
            if (row.phase == NotePhase.TRANSCRIBING) {
                Spacer(Modifier.height(RowMetrics.trackGap))
                RowTrack(row.progress ?: 0f)
            }
        }
        menu()
    }
}

private val RowTitleStyle = FocalType.rowTitle.copy(lineBreak = LineBreak.Heading)

/**
 * The title and, flush right on its first baseline, the duration (it wraps under the title from 1.5×). The title fills
 * its slot ([title] sets it at full width), so a derived title aligns in the note's own direction over the preview
 * under it; a duration wrapped under it keeps to the same edge ([titleText]'s).
 */
@Composable
private fun TitleLine(title: @Composable () -> Unit, trailing: String, trailingInk: Boolean, titleText: String) {
    val colors = FocalTheme.colors
    val wraps = LocalDensity.current.fontScale >= RowMetrics.WRAP_FONT
    val duration: @Composable (Modifier, TextAlign?) -> Unit = { modifier, align ->
        Text(trailing, style = FocalType.meta, color = if (trailingInk) colors.ink else colors.recede, maxLines = 1, textAlign = align, modifier = modifier)
    }
    if (wraps) {
        Column {
            title()
            duration(Modifier.fillMaxWidth(), alignWith(titleText))
        }
    } else {
        Row {
            Box(Modifier.weight(1f).alignByBaseline()) { title() }
            Spacer(Modifier.width(RowMetrics.durationGap))
            duration(Modifier.alignByBaseline(), null)
        }
    }
}

/** "16:05" (or the working state) in 500 ink, an en space, then the preview, cut on a whole word (G4). */
@Composable
private fun Preview(runIn: String, text: String, hits: List<IntRange> = emptyList()) {
    val colors = FocalTheme.colors
    val annotated = remember(runIn, text, hits, colors.ink) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = colors.ink, fontWeight = FocalWeight.Medium, fontFeatureSettings = TNUM)) { append(runIn) }
            append(RowMetrics.RUN_IN_GAP)
            val start = length
            append(text)
            // Every hit in 600 ink inside the muted snippet (spec 5.2); snippets are static, so the weight may change.
            for (hit in hits) {
                if (hit.first >= 0 && hit.last < text.length) {
                    addStyle(SpanStyle(color = colors.ink, fontWeight = FocalWeight.Semibold), start + hit.first, start + hit.last + 1)
                }
            }
        }
    }
    CutText(
        annotated, FocalType.secondaryWords, colors.recede, RowMetrics.PREVIEW_LINES,
        Modifier.fillMaxWidth(), protectedEnd = runIn.length + RowMetrics.RUN_IN_GAP.length,
    )
}

private const val TNUM = "tnum"

/** The transcribing row's 2 dp determinate track (`lens`, `ink` fill, radius 1), moving with `move`. */
@Composable
private fun RowTrack(fraction: Float) {
    val colors = FocalTheme.colors
    val fill = remember { Animatable(fraction) }
    LaunchedEffect(fraction) { if (fraction > fill.value) fill.animateTo(fraction.coerceIn(0f, 1f), FocalMotion.move()) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Spacer(
        Modifier.fillMaxWidth().height(RowMetrics.track).drawBehind {
            val corner = CornerRadius(size.height / 2)
            drawRoundRect(colors.lens, cornerRadius = corner)
            val filled = size.width * fill.value
            drawRoundRect(colors.ink, topLeft = Offset(if (rtl) size.width - filled else 0f, 0f), size = Size(filled, size.height), cornerRadius = corner)
        },
    )
}

/** "42 s", "24 min", "1 h 12 min" (spec 7.1 `dur_*`). */
@Composable
fun durationText(durationMs: Long): String {
    val parts = Lengths.parts(durationMs)
    val first = parts.first()
    return when {
        parts.size == 2 -> stringResource(R.string.dur_h_min, first.value.toInt(), parts[1].value.toInt())
        first.unit == LengthUnit.Hours -> stringResource(R.string.dur_h, first.value.toInt())
        first.unit == LengthUnit.Minutes -> stringResource(R.string.dur_min, first.value.toInt())
        else -> stringResource(R.string.dur_s, first.value.toInt())
    }
}

/**
 * A search result (spec 5.2): the row's anatomy with the day as its run-in ("Today", "Mon", "3 Nov") and the snippet
 * around the first hit, the hit in 600 ink.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchResultItem(result: SearchRow, dates: LibraryDates, today: LocalDate, onOpen: (SearchRow) -> Unit, modifier: Modifier = Modifier) {
    val colors = FocalTheme.colors
    val row = result.row
    val title = noteTitle(row, dates)
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxWidth()
            .focusRing(FocalShapes.block)
            .combinedClickable(interactionSource = source, indication = RowPlate, onClick = { onOpen(result) })
            .padding(horizontal = FocalLayout.gutter, vertical = RowMetrics.hitPad),
    ) {
        Column {
            TitleLine(
                title = {
                    Text(
                        title, style = FocalType.rowTitle, color = if (row.title.isBlank()) colors.recede else colors.ink,
                        maxLines = RowMetrics.TITLE_LINES, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                    )
                },
                trailing = durationText(row.durationMs),
                trailingInk = false,
                titleText = title,
            )
            Spacer(Modifier.height(RowMetrics.previewGap))
            Preview(dates.searchDay(row.created, today), result.snippet, result.snippetHits)
        }
    }
}

/**
 * A title renamed in place (spec 5.1.6): a [BasicTextField] in the title's own style with all text selected, the
 * caret in [caret]. IME Done, Enter or leaving the field commits; [onCancel] (Back) is the caller's. An empty value
 * restores the derived title (the store's rule for a blank title), which [placeholder] can show while it is empty.
 */
@Composable
fun RenameField(
    initial: String,
    style: TextStyle,
    color: Color,
    caret: Color,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: (@Composable () -> Unit)? = null,
    /**
     * Where a caller that keeps the field's text itself holds it (null until the first change: the title, all
     * selected); the field's own saved state is then left alone.
     */
    draft: MutableState<TextFieldValue?>? = null,
) {
    val saved = rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    // Read where it is used, so a commit right after a change has the change.
    fun value(): TextFieldValue = if (draft == null) saved.value else draft.value ?: TextFieldValue(initial, selection = TextRange(0, initial.length))
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    // Keys typed here stay text: the two-pane library hands Space and the Ctrl shortcuts to an open note otherwise.
    val typing = rememberFocusReport()
    fun commit() {
        if (committed) return
        committed = true
        onCommit(value().text.trim())
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value(),
        onValueChange = {
            val next = it.copy(text = it.text.replace('\n', ' '))
            if (draft == null) saved.value = next else draft.value = next
        },
        textStyle = style.copy(color = color),
        cursorBrush = SolidColor(caret),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { state ->
                if (focused && !state.isFocused) commit()
                focused = state.isFocused
                typing(state.isFocused)
            }
            .onPreviewKeyEvent { event -> if (event.key == Key.Enter) { commit(); true } else false },
        decorationBox = { field ->
            Box {
                // While it is empty the title it falls back to shows faintly (spec 5.5).
                if (value().text.isEmpty()) placeholder?.invoke()
                field()
            }
        },
    )
}
