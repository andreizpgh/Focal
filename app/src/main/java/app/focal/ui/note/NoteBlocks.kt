package app.focal.ui.note

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.focal.R
import app.focal.ui.components.NineBars
import app.focal.ui.components.PressPlate
import app.focal.ui.components.WaveDress
import app.focal.ui.components.WaveMode
import app.focal.ui.components.focusRing
import app.focal.ui.components.markBox
import app.focal.ui.components.pressScale
import app.focal.ui.design.FocalClock
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.FocalType
import app.focal.ui.design.FocalWeight
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.alignWith
import app.focal.ui.library.RenameField
import app.focal.ui.shell.LocalPageReveal
import app.focal.ui.shell.pageTitle
import app.focal.ui.shell.rememberPageTitle
import app.focal.ui.state.GapUi
import kotlin.math.roundToInt

/** The note's head and inline blocks (spec 4.13, 4.14, 4.16, 5.4, 5.5). */
object NoteBlockMetrics {
    /** Title to meta, meta to notices, notices to their pill. */
    val headGap = FocalGrid.x8
    /** The body starts 24 dp under the head; the list puts [ParagraphMetrics.gap] between items, the head the rest. */
    val bodyGap = FocalGrid.x24
    val noticePill = FocalGrid.x40
    val noticePillPadding = FocalGrid.x16
    const val NOTICE_LINES = 3
    const val TITLE_LINES = 3
    /** From this font scale the title may take a fourth line (spec 8.4). */
    const val TITLE_LINES_LARGE = 4
    const val LARGE_FONT = 1.5f
    val gapRow = FocalGrid.x48
    val gapMark = FocalGrid.x16
    const val GAP_MARK_ALPHA = 0.4f
    val gapMarkGap = FocalGrid.x12
    val writingHead = FocalGrid.x40
    /** The provenance line: 16 dp under the text, 20 dp tall; its "Restore original" keeps a 48 dp target. */
    val provenanceGap = FocalGrid.x16
    val provenanceLine = FocalGrid.x20
}

/** A notice under the meta (spec 4.16): its first sentence in ink (or none, [quiet]), the rest `muted`. */
@Immutable
internal data class Notice(val text: String, val quiet: Boolean = false, val action: String? = null, val onAction: (() -> Unit)? = null)

/** What the head shows. */
@Immutable
internal data class HeadUi(
    val title: String,
    /** The fallback "Note at 14:32" (muted), or the title being derived. */
    val titleFallback: Boolean,
    /** The derived title, shown faintly while a rename is emptied. */
    val derivedTitle: String,
    val meta: String,
    val notices: List<Notice>,
)

/**
 * The page's head (spec 5.5): the title (`title`, at most 3 lines; tapping it renames in place), the meta line 8 dp
 * under it and any notices 8 dp under that. The title recedes with the body ([titleInks]); [renaming] swaps it for a
 * field in the same style. [entrance] is T7's develop: the title at +120 ms and the meta at +200 ms (`fade`).
 */
@Composable
internal fun NoteHead(
    noteId: String,
    head: HeadUi,
    titleInks: Boolean,
    renaming: Boolean,
    canRename: Boolean,
    onRename: () -> Unit,
    onCommitRename: (String) -> Unit,
    onTitleBounds: (top: Float, bottom: Float) -> Unit,
    entrance: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val large = LocalDensity.current.fontScale >= NoteBlockMetrics.LARGE_FONT
    val receded = remember { Animatable(if (titleInks) 0f else 1f) }
    LaunchedEffect(titleInks, reduced) {
        val target = if (titleInks) 0f else 1f
        if (reduced) receded.snapTo(target) else receded.animateTo(target, FocalMotion.develop())
    }
    val reveal = LocalPageReveal.current
    // T7's develop, counted from Finish: the title from +120 and the meta from +200 (`fade`).
    val titleShown = { if (entrance) FocalClock.fade(reveal.develop() - FocalTiming.SETTLE_TITLE_DELAY_MS) else 1f }
    val metaShown = { if (entrance) FocalClock.fade(reveal.develop() - FocalTiming.SETTLE_META_DELAY_MS) else 1f }
    val flight = rememberPageTitle(
        noteId, head.title, head.titleFallback, if (large) NoteBlockMetrics.TITLE_LINES_LARGE else NoteBlockMetrics.TITLE_LINES,
    )
    Column(modifier.fillMaxWidth().padding(horizontal = FocalLayout.gutter).padding(bottom = NoteBlockMetrics.bodyGap - ParagraphMetrics.gap)) {
        // While the page opens out of the stage or a row (or folds back), a copy of the title flies in its place.
        val titleModifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                val top = it.positionInWindow().y
                onTitleBounds(top, top + it.size.height)
            }
            .pageTitle(flight)
            .graphicsLayer { alpha = titleShown() * reveal.title() }
        if (renaming) {
            RenameField(
                initial = if (head.titleFallback) "" else head.title,
                style = TitleStyle,
                color = colors.ink,
                caret = colors.ink,
                onCommit = onCommitRename,
                modifier = titleModifier,
                placeholder = { Text(head.derivedTitle, style = TitleStyle, color = colors.faint, maxLines = NoteBlockMetrics.TITLE_LINES) },
            )
        } else {
            val source = remember { MutableInteractionSource() }
            val renameLabel = stringResource(R.string.menu_rename)
            // The fallback title is muted; any title recedes with the body, in the draw phase.
            val base = if (head.titleFallback) colors.recede else colors.ink
            BasicText(
                head.title,
                style = TitleStyle,
                color = { lerp(base, colors.recede, receded.value) },
                maxLines = if (large) NoteBlockMetrics.TITLE_LINES_LARGE else NoteBlockMetrics.TITLE_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier
                    .semantics { heading() }
                    .clickable(source, indication = null, enabled = canRename, onClickLabel = renameLabel, onClick = onRename),
            )
        }
        Spacer(Modifier.height(NoteBlockMetrics.headGap))
        // The meta lines up with the title over it: in a mirrored layout a title derived from the note keeps the
        // note's own edge (spec 8.5), and the meta keeps it too rather than parting to the other side.
        Text(
            head.meta, style = FocalType.meta, color = colors.recede, maxLines = 2, textAlign = alignWith(head.title),
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = metaShown() },
        )
        head.notices.forEach { notice ->
            Spacer(Modifier.height(NoteBlockMetrics.headGap))
            NoticeLine(notice)
        }
    }
}

/** The note title wraps in balanced lines, as a heading (the renders' "Notes for Thursday’s / design review"). */
private val TitleStyle = FocalType.title.copy(lineBreak = LineBreak.Heading)

/** A notice line (spec 4.16) and its small pill ("Retry", "Try again"). */
@Composable
private fun NoticeLine(notice: Notice) {
    val colors = FocalTheme.colors
    val text = remember(notice.text, notice.quiet, colors.ink, colors.recede) {
        buildAnnotatedString {
            val split = notice.text.indexOf(". ").takeIf { it >= 0 }?.plus(1) ?: notice.text.length
            withStyle(SpanStyle(color = if (notice.quiet) colors.recede else colors.ink)) { append(notice.text.substring(0, split)) }
            withStyle(SpanStyle(color = colors.recede)) { append(notice.text.substring(split)) }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(text, style = FocalType.secondary, maxLines = NoteBlockMetrics.NOTICE_LINES, overflow = TextOverflow.Ellipsis)
        val action = notice.action
        val onAction = notice.onAction
        if (action != null && onAction != null) {
            Spacer(Modifier.height(NoteBlockMetrics.headGap - (FocalLayout.target - NoteBlockMetrics.noticePill) / 2))
            SmallPill(action, onAction)
        }
    }
}

/** The notice's small pill: `s1`, 40 dp visual in a 48 dp target, `action` label, 16 dp padding. */
@Composable
private fun SmallPill(label: String, onClick: () -> Unit) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = FocalLayout.target)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                this.onClick { onClick(); true }
            }
            .pressScale(source)
            .focusRing(FocalShapes.pill)
            .clickable(source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.heightIn(min = NoteBlockMetrics.noticePill).background(colors.s1, FocalShapes.pill)
                .padding(horizontal = NoteBlockMetrics.noticePillPadding),
            contentAlignment = Alignment.Center,
        ) { Text(label, style = FocalType.action, color = colors.ink) }
    }
}

/**
 * A part that could not be transcribed (spec 4.13): the 16 dp static mark at 40 %, "0:42 not transcribed" and a
 * "Play" text button that plays exactly that range (hidden once the audio is removed).
 */
@Composable
internal fun GapRow(gap: GapUi, playable: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FocalTheme.colors
    val label = stringResource(R.string.gap_not_transcribed, NoteTimes.clock(gap.endMs - gap.startMs))
    Row(
        modifier.fillMaxWidth().heightIn(min = NoteBlockMetrics.gapRow).padding(start = FocalLayout.gutter, end = FocalLayout.gutter - GapPlayInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NineBars(
            WaveMode.Static, WaveDress.paper(colors),
            Modifier.markBox(NoteBlockMetrics.gapMark).graphicsLayer { alpha = NoteBlockMetrics.GAP_MARK_ALPHA },
        )
        Spacer(Modifier.width(NoteBlockMetrics.gapMarkGap))
        Text(label, style = FocalType.meta, color = colors.recede, modifier = Modifier.weight(1f))
        if (playable) {
            val source = remember { MutableInteractionSource() }
            val play = stringResource(R.string.action_play)
            Box(
                Modifier
                    .heightIn(min = FocalLayout.target)
                    .focusRing(FocalShapes.pill)
                    .clickable(source, indication = GapPlate, role = Role.Button, onClick = onPlay)
                    .padding(horizontal = GapPlayInset),
                contentAlignment = Alignment.Center,
            ) { Text(play, style = FocalType.label, color = colors.ink) }
        }
    }
}

/** The text button's 12 dp padding: its label's end lands on the gutter. */
private val GapPlayInset = FocalGrid.x12
private val GapPlate = PressPlate(FocalShapes.pill)

/** The writing head (spec 5.4): the 40 dp wave in paper dress, sweeping while the tail transcribes, still while it waits. */
@Composable
internal fun WritingHead(working: Boolean, modifier: Modifier = Modifier) {
    val colors = FocalTheme.colors
    Box(modifier.fillMaxWidth().padding(horizontal = FocalLayout.gutter)) {
        NineBars(WaveMode.Working(paused = !working), WaveDress.paper(colors), Modifier.markBox(NoteBlockMetrics.writingHead))
    }
}

/**
 * The provenance line under the edit sheet (spec 4.14): "Edited · Restore original", 16 dp under the text; the item
 * grows by 36 dp on `move` while it shows and collapses again after a restore. "Restore original" keeps a 48 dp target
 * that reaches into the gap below the line.
 */
@Composable
internal fun ProvenanceLine(visible: Boolean, onRestore: () -> Unit) {
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val open = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible, reduced) {
        val target = if (visible) 1f else 0f
        if (reduced) open.snapTo(target) else open.animateTo(target, FocalMotion.move())
    }
    if (!visible && open.value <= 0f) return
    val restore = stringResource(R.string.menu_restore)
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val full = (NoteBlockMetrics.provenanceGap + NoteBlockMetrics.provenanceLine).roundToPx()
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                layout(placeable.width, (full * open.value).roundToInt()) {
                    placeable.placeWithLayer(0, NoteBlockMetrics.provenanceGap.roundToPx() - (placeable.height - NoteBlockMetrics.provenanceLine.roundToPx()) / 2) {
                        alpha = open.value
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(stringResource(R.string.provenance_edited), style = FocalType.meta, color = colors.recede)
        Text(PROVENANCE_DOT, style = FocalType.meta, color = colors.recede)
        Box(
            Modifier
                .heightIn(min = FocalLayout.target)
                .semantics { role = Role.Button }
                .focusRing(FocalShapes.pill)
                .clickable(source, indication = PressPlate(FocalShapes.pill), enabled = visible, onClickLabel = restore, onClick = onRestore),
            contentAlignment = Alignment.Center,
        ) { Text(restore, style = FocalType.meta.copy(fontWeight = FocalWeight.Semibold), color = colors.ink) }
    }
}

private const val PROVENANCE_DOT = " · "

/** A ready note with sound and no words (spec 5.9): one `body` line in `muted`. */
@Composable
internal fun NoSpeechLine(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.no_speech), style = FocalType.body, color = FocalTheme.colors.recede,
        modifier = modifier.fillMaxWidth().padding(horizontal = FocalLayout.gutter),
    )
}
