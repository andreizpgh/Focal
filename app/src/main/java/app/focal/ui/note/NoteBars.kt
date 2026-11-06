package app.focal.ui.note

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.focal.R
import app.focal.ui.components.FadeThrough
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.FocalIconButton
import app.focal.ui.components.FocalMenu
import app.focal.ui.components.FocalMenuDivider
import app.focal.ui.components.FocalMenuItem
import app.focal.ui.components.focusRing
import app.focal.ui.components.pressScale
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.Grid
import app.focal.ui.design.LocalReducedMotion

/** Top bar geometry (spec 4.1.2, 4.1.3). */
object NoteBarMetrics {
    /** The collapsed title starts after ← (x 72) and ends 8 dp before ✎. */
    val titleStart = Grid.x12
    val titleEnd = Grid.x8
    val doneHeight = Grid.x40
    val donePadding = Grid.x20
    /** ⋮ ends 4 dp before Done (x 256-304, Done 308-388). */
    val doneGap = Grid.x4
    /** Done's end sits on the gutter, 12 dp further in than an icon button's target. */
    val doneEnd = FocalLayout.gutter - FocalLayout.iconEdge
    /** From this font scale Redo moves into Edit's ⋮ (spec 8.4). */
    const val REDO_IN_MENU_FONT = 1.8f
}

/** What the note menu offers in this state (spec 4.15: items that do not apply are hidden). */
internal data class NoteMenuItems(val find: Boolean, val share: Boolean, val removeAudio: Boolean)

internal class NoteMenuActions(
    val find: () -> Unit,
    val rename: () -> Unit,
    val share: () -> Unit,
    val removeAudio: () -> Unit,
    val delete: () -> Unit,
    val audioSize: suspend () -> String?,
)

/**
 * The note top bar (spec 4.1.2): ← "Back", ✎ "Edit" (only once the note is editable; it fades in at Ready), ⋮ "More
 * options" with the note menu. Once the content title has scrolled under the bar a one-line copy of it fades in
 * between ← and ✎. [chrome] is the items' alpha (they fade out under Find).
 */
@Composable
internal fun NoteTopBar(
    title: String,
    titleMuted: Boolean,
    collapsed: Boolean,
    editable: Boolean,
    menuItems: NoteMenuItems,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    menu: NoteMenuActions,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    chrome: () -> Float,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val titleAlpha = remember { Animatable(if (collapsed) 1f else 0f) }
    LaunchedEffect(collapsed, reduced) {
        val target = if (collapsed) 1f else 0f
        if (reduced) titleAlpha.snapTo(target) else titleAlpha.animateTo(target, FocalMotion.fade())
    }
    val pencil = remember { Animatable(if (editable) 1f else 0f) }
    LaunchedEffect(editable, reduced) {
        val target = if (editable) 1f else 0f
        if (reduced) pencil.snapTo(target) else pencil.animateTo(target, FocalMotion.fade())
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
        if (editable || pencil.value > 0f) {
            FocalIconButton(
                FocalIcon.Edit, stringResource(R.string.cd_edit), onEdit,
                modifier = Modifier.graphicsLayer { alpha = pencil.value }, enabled = editable,
            )
        }
        Box {
            FocalIconButton(FocalIcon.More, stringResource(R.string.cd_more), { onMenu(true) })
            FocalMenu(expanded = menuOpen, onDismiss = { onMenu(false) }) {
                NoteMenu(menuItems, menu) { onMenu(false) }
            }
        }
    }
}

/** The note menu (spec 4.15): Find and replace, Rename, Share, —, Remove audio · size, Delete. */
@Composable
private fun NoteMenu(items: NoteMenuItems, actions: NoteMenuActions, dismiss: () -> Unit) {
    fun run(action: () -> Unit): () -> Unit = {
        dismiss()
        action()
    }
    if (items.find) FocalMenuItem(stringResource(R.string.menu_find), run(actions.find))
    FocalMenuItem(stringResource(R.string.menu_rename), run(actions.rename))
    if (items.share) FocalMenuItem(stringResource(R.string.menu_share), run(actions.share))
    if (items.removeAudio) {
        FocalMenuDivider()
        val size = remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { size.value = actions.audioSize() }
        FocalMenuItem(stringResource(R.string.menu_remove_audio), run(actions.removeAudio), trailing = size.value)
    }
    FocalMenuItem(stringResource(R.string.menu_delete), run(actions.delete))
}

/** What the edit bar shows and does. */
internal class EditBarActions(
    val undo: () -> Unit,
    val redo: () -> Unit,
    val done: () -> Unit,
    val restore: () -> Unit,
    val find: () -> Unit,
)

/**
 * The edit bar (spec 4.1.3): ↶ ↷ (`faint` with nothing to undo or redo), ⋮ with "Restore original" (enabled while
 * the caret's paragraph differs from its source) and "Find and replace", and the Done pill, the only filled shape on
 * screen while the dock is hidden. A failed save writes "Not saved" left of ⋮ and Done reads "Retry".
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
) {
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val redoInMenu = LocalDensity.current.fontScale >= NoteBarMetrics.REDO_IN_MENU_FONT
    val notSaved = remember { Animatable(if (failed) 1f else 0f) }
    LaunchedEffect(failed, reduced) {
        val target = if (failed) 1f else 0f
        if (reduced) notSaved.snapTo(target) else notSaved.animateTo(target, FocalMotion.fade())
    }
    BarRow(modifier) {
        FocalIconButton(FocalIcon.Undo, stringResource(R.string.cd_undo), actions.undo, enabled = canUndo)
        if (!redoInMenu) FocalIconButton(FocalIcon.Redo, stringResource(R.string.cd_redo), actions.redo, enabled = canRedo)
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.not_saved), style = FocalType.meta, color = colors.recede, maxLines = 1,
            // Read only while it is shown, and announced when it appears.
            modifier = Modifier
                .graphicsLayer { alpha = notSaved.value }
                .then(if (failed) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier.clearAndSetSemantics { }),
        )
        Box {
            FocalIconButton(FocalIcon.More, stringResource(R.string.cd_more), { onMenu(true) })
            FocalMenu(expanded = menuOpen, onDismiss = { onMenu(false) }) {
                fun run(action: () -> Unit): () -> Unit = {
                    onMenu(false)
                    action()
                }
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

/** A 64 dp bar under the status bar; icon buttons sit 12 dp from the edges (spec 2.5). */
@Composable
private fun BarRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(FocalLayout.topBar)
            .padding(horizontal = FocalLayout.iconEdge),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
