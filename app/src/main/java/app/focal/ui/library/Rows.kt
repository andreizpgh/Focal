package app.focal.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.semantics.selected
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.focal.R
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.Glyph
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
import app.focal.ui.design.rememberFocalHaptics
import app.focal.ui.shell.LocalShell
import app.focal.ui.shell.pageOrigin
import app.focal.ui.shell.rememberFocusReport
import app.focal.ui.shell.rememberRowOrigin
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteRow
import app.focal.ui.state.SearchRow
import java.time.LocalDate

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
    /** The check of a selected row, where its length was: the size of a line of `meta`. */
    val check = FocalGrid.x20
    const val TITLE_LINES = 2
    const val PREVIEW_LINES = 2
    /** From this font scale the duration wraps under the title. */
    const val WRAP_FONT = 1.5f
    const val PERCENT = 100
}

private val RowPlate = PressPlate(FocalShapes.block, insetX = RowMetrics.plateInset)

/** What a row can do; the page provides it. */
@Stable
class RowActions(
    /** A tap: the row opens its note, or, while notes are selected, joins or leaves the selection. */
    val open: (NoteRow) -> Unit,
    /** A long press: the row is selected (and selection begins). */
    val select: (NoteRow) -> Unit,
    /** The note's actions as TalkBack custom actions (spec 4.12.2): Copy text, Share, Rename, Remove audio, Delete. */
    val menuActions: (NoteRow) -> List<CustomAccessibilityAction>,
    /** A finger landed on the row: its note may be read ahead of a tap (by note id). */
    val prepare: (String) -> Unit = {},
)

/**
 * A ready row (spec 4.12.2), or a transcribing or waiting one (4.12.4): the title with the note's length flush right
 * like a page number, and under it the text that follows the title, two lines. A row carries one piece of metadata, in
 * one place: its length, or what is happening to it while it is not ready ("42%", "Waiting", "Preparing"); the day
 * is the label above it and the time of day is on its page. The hit area is the full width, 12 dp above the title and
 * below the preview; the press plate spans x 8-404. A long press selects the row; a [selected] row keeps its plate
 * (`s1`, joined with the plates of selected rows next to it) and shows a check where its length was, and is said to
 * be selected. [capturing]: a recording holds the
 * microphone, so the row's page will open with ▶ unavailable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteRowItem(
    row: NoteRow,
    dates: LibraryDates,
    actions: RowActions,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    capturing: Boolean = false,
    selected: Boolean = false,
    /** The rows right above and below are selected too: the plates join into one block, square where they meet. */
    joinsAbove: Boolean = false,
    joinsBelow: Boolean = false,
) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    val haptics = rememberFocalHaptics()
    // The plate of a selected row: it arrives and leaves on `fadeFast`, read in the draw phase.
    val plate = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) { plate.animateTo(if (selected) 1f else 0f, FocalMotion.fadeFast()) }
    val trailing = when (row.phase) {
        NotePhase.TRANSCRIBING -> stringResource(R.string.row_percent, ((row.progress ?: 0f) * RowMetrics.PERCENT).toInt())
        NotePhase.WAITING -> stringResource(R.string.row_waiting)
        NotePhase.PREPARING_MODEL -> stringResource(R.string.row_preparing)
        else -> durationText(row.durationMs)
    }
    val working = row.phase == NotePhase.TRANSCRIBING || row.phase == NotePhase.WAITING || row.phase == NotePhase.PREPARING_MODEL
    val titleText = noteTitle(row, dates)
    val menuActions = actions.menuActions(row)
    val pages = LocalShell.current.pages
    val origin = rememberRowOrigin(row.id, RowMetrics.TITLE_LINES, titleText, titleMuted = row.title.isBlank(), dock = pageDock(row, capturing))
    val prepare by rememberUpdatedState(actions.prepare)
    val platePath = remember { Path() }
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val a = plate.value
                if (a <= 0f) return@drawBehind
                val inset = RowMetrics.plateInset.toPx()
                val round = CornerRadius(FocalShapes.blockRadius.toPx())
                // Rows that are selected next to each other are one block: a stack of rounded plates, each with its
                // own corners, read as separate buttons.
                val top = if (joinsAbove && selected) CornerRadius.Zero else round
                val bottom = if (joinsBelow && selected) CornerRadius.Zero else round
                val plateRect = RoundRect(Rect(inset, 0f, size.width - inset, size.height), top, top, bottom, bottom)
                platePath.reset()
                platePath.addRoundRect(plateRect)
                drawPath(platePath, colors.s1, alpha = a)
            }
            // Its note is read as the finger lands, so the page a tap opens has it in its first frame.
            .pointerInput(row.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    prepare(row.id)
                }
            }
            // While the title is renamed the row's actions are out of reach for touch, and so for TalkBack.
            .semantics {
                customActions = if (title == null) menuActions else emptyList()
                // Said only of a selected row: set on every row, each would be read as "not selected".
                if (selected) this.selected = true
            }
            .focusRing(FocalShapes.block)
            .combinedClickable(
                interactionSource = source, indication = RowPlate, enabled = title == null,
                onLongClick = {
                    haptics.selected()
                    actions.select(row)
                },
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
                trailingInk = working,
                titleText = titleText,
                checked = { plate.value },
            )
            Preview(previewAfterTitle(row))
            if (row.phase == NotePhase.TRANSCRIBING) {
                Spacer(Modifier.height(RowMetrics.trackGap))
                RowTrack(row.progress ?: 0f)
            }
        }
    }
}

private val RowTitleStyle = FocalType.rowTitle.copy(lineBreak = LineBreak.Heading)

/**
 * The title and, flush right on its first baseline, the duration (it wraps under the title from 1.5×). The title fills
 * its slot ([title] sets it at full width), so a derived title aligns in the note's own direction over the preview
 * under it; a duration wrapped under it keeps to the same edge ([titleText]'s).
 */
@Composable
private fun TitleLine(
    title: @Composable () -> Unit,
    trailing: String,
    trailingInk: Boolean,
    titleText: String,
    /** How far the row is selected (0..1, read in the draw phase): a check fades in over the trailing text's end. */
    checked: () -> Float = { 0f },
) {
    val colors = FocalTheme.colors
    val wraps = LocalDensity.current.fontScale >= RowMetrics.WRAP_FONT
    val duration: @Composable (Modifier, TextAlign?) -> Unit = { modifier, align ->
        Box(modifier, contentAlignment = if (align == TextAlign.Left) Alignment.CenterStart else Alignment.CenterEnd) {
            Text(
                trailing, style = FocalType.meta, color = if (trailingInk) colors.ink else colors.recede, maxLines = 1, textAlign = align,
                modifier = Modifier.graphicsLayer { alpha = 1 - checked() },
            )
            Glyph(FocalIcon.Check, colors.ink, Modifier.graphicsLayer { alpha = checked() }, size = RowMetrics.check)
        }
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

/**
 * The preview: the text after the title in `secondary` `muted`, two lines, cut on a whole word (G4), 4 dp under the
 * title. A note whose title holds all of its text has none, and its row is the title line alone.
 */
@Composable
private fun Preview(text: String, hits: List<IntRange> = emptyList()) {
    if (text.isBlank()) return
    val colors = FocalTheme.colors
    val annotated = remember(text, hits, colors.ink) {
        buildAnnotatedString {
            append(text)
            // Every hit in 600 ink inside the muted snippet (spec 5.2); snippets are static, so the weight may change.
            for (hit in hits) {
                if (hit.first >= 0 && hit.last < text.length) {
                    addStyle(SpanStyle(color = colors.ink, fontWeight = FocalWeight.Semibold), hit.first, hit.last + 1)
                }
            }
        }
    }
    Spacer(Modifier.height(RowMetrics.previewGap))
    CutText(annotated, FocalType.secondaryWords, colors.recede, RowMetrics.PREVIEW_LINES, Modifier.fillMaxWidth())
}

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
 * A search result (spec 5.2): the row's anatomy with the day in place of the length ("Today", "Mon", "3 Nov": results
 * span days and carry no day labels) and the snippet around the first hit, the hit in 600 ink.
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
                        title, style = RowTitleStyle, color = if (row.title.isBlank()) colors.recede else colors.ink,
                        maxLines = RowMetrics.TITLE_LINES, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                    )
                },
                trailing = dates.searchDay(row.created, today),
                trailingInk = false,
                titleText = title,
            )
            Preview(result.snippet, result.snippetHits)
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
