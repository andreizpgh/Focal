package app.focal.ui.note

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focal.R
import app.focal.ui.components.FadeThrough
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.FocalIconButton
import app.focal.ui.components.FocalMenu
import app.focal.ui.components.FocalMenuDivider
import app.focal.ui.components.FocalMenuItem
import app.focal.ui.components.Glyph
import app.focal.ui.components.GlyphSize
import app.focal.ui.components.focusRing
import app.focal.ui.components.pressScale
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.FocalWeight
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.LocalWindowLayout
import app.focal.ui.design.tabular

/** Top bar geometry (spec 4.1.2, 4.1.3). */
object NoteBarMetrics {
    /** The collapsed title starts after ← (x 72) and ends 8 dp before ⋮. */
    val titleStart = FocalGrid.x12
    val titleEnd = FocalGrid.x8
    val doneHeight = FocalGrid.x40
    val donePadding = FocalGrid.x20
    /** ⋮ ends 4 dp before Done (x 256-304, Done 308-388). */
    val doneGap = FocalGrid.x4
    /** Done's end sits on the gutter, 12 dp further in than an icon button's target. */
    val doneEnd = FocalLayout.gutter - FocalLayout.iconEdge
    /** From this font scale Redo moves into Edit's ⋮ (spec 8.4). */
    const val REDO_IN_MENU_FONT = 1.8f
}

/** What the note menu offers in this state (spec 4.15: items that do not apply are hidden). */
internal data class NoteMenuItems(
    /** "Edit": the way into Edit without a pointer (a keyboard cannot tap a word). */
    val edit: Boolean = false,
    val markAllReviewed: Boolean = false,
    val find: Boolean,
    val share: Boolean,
    val removeAudio: Boolean,
    val delete: Boolean = true,
    /** "Continue recording": the dock's ● as a menu item, which a short window's rail has no room for. */
    val append: Boolean = false,
)

internal class NoteMenuActions(
    val edit: () -> Unit = {},
    val markAllReviewed: () -> Unit = {},
    val find: () -> Unit,
    val rename: () -> Unit,
    val share: () -> Unit,
    val append: () -> Unit = {},
    val removeAudio: () -> Unit,
    val delete: () -> Unit,
    val audioSize: suspend () -> String?,
)

/**
 * The note top bar (spec 4.1.2): ← "Back" and ⋮ "More options" with the note menu. There is no Edit button: the text
 * is edited where it is tapped. Once the content title has scrolled under the bar a one-line copy of it fades in
 * between ← and ⋮. [chrome] is the items' alpha (they fade out under Find).
 */
@Composable
internal fun NoteTopBar(
    title: String,
    titleMuted: Boolean,
    collapsed: Boolean,
    menuItems: NoteMenuItems,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    menu: NoteMenuActions,
    onBack: () -> Unit,
    chrome: () -> Float,
    modifier: Modifier = Modifier,
    reviewCount: Int = 0,
    onReview: () -> Unit = {},
) {
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val titleAlpha = remember { Animatable(if (collapsed) 1f else 0f) }
    LaunchedEffect(collapsed, reduced) {
        val target = if (collapsed) 1f else 0f
        if (reduced) titleAlpha.snapTo(target) else titleAlpha.animateTo(target, FocalMotion.fade())
    }
    BarRow(modifier.graphicsLayer { alpha = chrome() }) {
        FocalIconButton(FocalIcon.Back, stringResource(R.string.cd_back), onBack)
        Text(
            title, style = FocalType.rowTitle, color = if (titleMuted) colors.recede else colors.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = NoteBarMetrics.titleStart, end = NoteBarMetrics.titleEnd)
                .graphicsLayer { alpha = titleAlpha.value }
                .clearAndSetSemantics { },
        )
        ReviewTopPill(count = reviewCount, onClick = onReview)
        Box {
            FocalIconButton(FocalIcon.More, stringResource(R.string.cd_more), { onMenu(true) })
            FocalMenu(expanded = menuOpen, onDismiss = { onMenu(false) }) {
                NoteMenu(menuItems, menu) { onMenu(false) }
            }
        }
    }
}

/** Quiet top-bar pill ("Review · N") that fades out cleanly once all unclear words are resolved. */
@Composable
private fun ReviewTopPill(count: Int, onClick: () -> Unit) {
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val visible = count > 0
    var shownCount by remember { mutableIntStateOf(count) }
    if (visible) shownCount = count
    val alpha = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible, reduced) {
        val target = if (visible) 1f else 0f
        if (reduced) alpha.snapTo(target) else alpha.animateTo(target, if (visible) FocalMotion.fade() else FocalMotion.fadeFast())
    }
    val gone by remember { derivedStateOf { alpha.value <= 0f } }
    if (!visible && gone) return
    val source = remember { MutableInteractionSource() }
    val label = stringResource(R.string.review_pill, shownCount)
    Box(
        Modifier
            .graphicsLayer { this.alpha = alpha.value }
            .heightIn(min = FocalLayout.target)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!visible) disabled()
            }
            .pressScale(source)
            .focusRing(FocalShapes.pill)
            .clickable(source, indication = null, enabled = visible, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .background(colors.s1, FocalShapes.pill)
                .border(1.dp, colors.stageIdleBorder, FocalShapes.pill)
                .padding(horizontal = FocalGrid.x12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).background(colors.recede, FocalShapes.pill))
            Spacer(Modifier.width(FocalGrid.x8))
            Text(
                label,
                style = FocalType.action.copy(fontWeight = FocalWeight.Medium).tabular(),
                color = colors.ink,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** The note menu (spec 4.15): Edit, Mark all as reviewed, Continue recording, Find and replace, Rename, Share, —, Remove audio · size, Delete. */
@Composable
private fun NoteMenu(items: NoteMenuItems, actions: NoteMenuActions, dismiss: () -> Unit) {
    fun run(action: () -> Unit): () -> Unit = {
        dismiss()
        action()
    }
    if (items.edit) FocalMenuItem(stringResource(R.string.menu_edit), run(actions.edit))
    if (items.markAllReviewed) FocalMenuItem(stringResource(R.string.review_mark_all), run(actions.markAllReviewed))
    if (items.append) FocalMenuItem(stringResource(R.string.action_append), run(actions.append))
    if (items.find) FocalMenuItem(stringResource(R.string.menu_find), run(actions.find))
    FocalMenuItem(stringResource(R.string.menu_rename), run(actions.rename))
    if (items.share) FocalMenuItem(stringResource(R.string.menu_share), run(actions.share))
    if (items.removeAudio) {
        FocalMenuDivider()
        val size = remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { size.value = actions.audioSize() }
        FocalMenuItem(stringResource(R.string.menu_remove_audio), run(actions.removeAudio), trailing = size.value)
    }
    if (items.delete) FocalMenuItem(stringResource(R.string.menu_delete), run(actions.delete))
}

/** What the edit bar shows and does. */
internal class EditBarActions(
    val undo: () -> Unit,
    val redo: () -> Unit,
    val done: () -> Unit,
    val restore: () -> Unit,
    val find: () -> Unit,
    /** ▶: plays the audio of the selected words (or of the words around the caret); ‖ while it plays. */
    val listen: () -> Unit = {},
    val reviewPrevious: () -> Unit = {},
    val reviewNext: () -> Unit = {},
    val markAllReviewed: () -> Unit = {},
)

/**
 * The edit bar (spec 4.1.3): ↶ ↷ (`faint` with nothing to undo or redo), optional compact ‹ 1 of N › stepper while
 * stepping through unclear words, ▶ "Listen" (when not shown in [ReviewStrip]), ⋮ with "Mark all as reviewed",
 * "Restore original" and "Find and replace", and the Done pill. A failed save writes "Not saved" and Done reads "Retry".
 */
@Composable
internal fun EditBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canRestore: Boolean,
    failed: Boolean,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    actions: EditBarActions,
    modifier: Modifier = Modifier,
    canListen: Boolean = false,
    listening: Boolean = false,
    reviewPosition: Int = 0,
    reviewTotal: Int = 0,
    canMarkAllReviewed: Boolean = false,
) {
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val redoInMenu = LocalDensity.current.fontScale >= NoteBarMetrics.REDO_IN_MENU_FONT || reviewTotal > 0
    val notSaved = remember { Animatable(if (failed) 1f else 0f) }
    LaunchedEffect(failed, reduced) {
        val target = if (failed) 1f else 0f
        if (reduced) notSaved.snapTo(target) else notSaved.animateTo(target, FocalMotion.fade())
    }
    BarRow(modifier) {
        FocalIconButton(FocalIcon.Undo, stringResource(R.string.cd_undo), actions.undo, enabled = canUndo)
        if (!redoInMenu) FocalIconButton(FocalIcon.Redo, stringResource(R.string.cd_redo), actions.redo, enabled = canRedo)
        if (reviewTotal > 0) {
            Spacer(Modifier.width(FocalGrid.x4))
            ReviewStepper(
                position = reviewPosition,
                total = reviewTotal,
                onPrevious = actions.reviewPrevious,
                onNext = actions.reviewNext,
            )
        }
        Spacer(Modifier.weight(1f))
        // Laid out only while it shows: at rest its width belongs to the buttons (a narrow phone has none to spare).
        if (failed || notSaved.value > 0f) Text(
            stringResource(R.string.not_saved), style = FocalType.meta, color = colors.recede, maxLines = 1,
            // Read only while it is shown, and announced when it appears.
            modifier = Modifier
                .graphicsLayer { alpha = notSaved.value }
                .then(if (failed) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier.clearAndSetSemantics { }),
        )
        // "Not saved" takes ▶'s room while it shows: the save matters more than the listen.
        if (canListen && !failed) {
            FocalIconButton(
                if (listening) FocalIcon.Pause else FocalIcon.Play,
                stringResource(if (listening) R.string.cd_listen_stop else R.string.cd_listen), actions.listen,
            )
        }
        Box {
            FocalIconButton(FocalIcon.More, stringResource(R.string.cd_more), { onMenu(true) })
            FocalMenu(expanded = menuOpen, onDismiss = { onMenu(false) }) {
                fun run(action: () -> Unit): () -> Unit = {
                    onMenu(false)
                    action()
                }
                if (canMarkAllReviewed) FocalMenuItem(stringResource(R.string.review_mark_all), run(actions.markAllReviewed))
                if (redoInMenu) FocalMenuItem(stringResource(R.string.cd_redo), run(actions.redo), enabled = canRedo)
                FocalMenuItem(stringResource(R.string.menu_restore), run(actions.restore), enabled = canRestore)
                FocalMenuItem(stringResource(R.string.menu_find), run(actions.find))
            }
        }
        Spacer(Modifier.width(NoteBarMetrics.doneGap))
        DonePill(if (failed) stringResource(R.string.action_retry) else stringResource(R.string.action_done), actions.done)
        Spacer(Modifier.width(NoteBarMetrics.doneEnd))
    }
}

/** Crisp non-wrapping ‹ 1 of N › stepper inside [EditBar] while walking through unclear words. */
@Composable
private fun ReviewStepper(position: Int, total: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
    val colors = FocalTheme.colors
    val canStep = total > 1
    val label = if (position > 0) stringResource(R.string.review_of, position, total) else stringResource(R.string.review_left, total)
    Box(Modifier.heightIn(min = FocalLayout.target), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .padding(vertical = 6.dp)
                .background(colors.s1, FocalShapes.pill)
                .border(1.dp, colors.stageIdleBorder, FocalShapes.pill),
        )
        Row(
            Modifier.padding(horizontal = FocalGrid.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperButton(
                description = stringResource(R.string.cd_review_prev),
                enabled = canStep,
                flipped = true,
                onClick = onPrevious,
            )
            Text(
                label,
                style = FocalType.caption.copy(fontWeight = FocalWeight.Medium).tabular(),
                color = colors.ink,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = FocalGrid.x4),
            )
            StepperButton(
                description = stringResource(R.string.cd_review_next),
                enabled = canStep,
                flipped = false,
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun StepperButton(description: String, enabled: Boolean, flipped: Boolean, onClick: () -> Unit) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .widthIn(min = 32.dp)
            .heightIn(min = FocalLayout.target)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (enabled) this.onClick { onClick(); true } else disabled()
            }
            .pressScale(source)
            .focusRing(FocalShapes.pill)
            .clickable(source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(
            FocalIcon.ChevronRight,
            tint = if (enabled) colors.ink else colors.faint,
            size = GlyphSize.small,
            modifier = if (flipped) Modifier.graphicsLayer { scaleX = -1f } else Modifier,
        )
    }
}

/** Done (spec 4.1.3): a 40 dp `inv` pill in a 48 dp target, `label` in `onInv`, 20 dp padding. */
@Composable
private fun DonePill(label: String, onClick: () -> Unit) {
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
            Modifier
                .heightIn(min = NoteBarMetrics.doneHeight)
                .background(colors.inv, FocalShapes.pill)
                .padding(horizontal = NoteBarMetrics.donePadding),
            contentAlignment = Alignment.Center,
        ) {
            FadeThrough(label) { text -> Text(text, style = FocalType.label, color = colors.onInv, maxLines = 1) }
        }
    }
}

/** A 64 dp bar under the status bar (48 in a short window); icon buttons sit 12 dp from the edges (spec 2.5, 5.14). */
@Composable
private fun BarRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(LocalWindowLayout.current.topBar)
            .padding(horizontal = FocalLayout.iconEdge),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
