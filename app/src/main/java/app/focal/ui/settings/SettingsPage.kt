package app.focal.ui.settings

import android.text.format.Formatter
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.focal.R
import app.focal.ui.components.FeedbackHostState
import app.focal.ui.components.FeedbackPill
import app.focal.ui.components.FocalBottomSheet
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.FocalIconButton
import app.focal.ui.components.FocalPrimaryPill
import app.focal.ui.components.FocalTextButton
import app.focal.ui.components.Glyph
import app.focal.ui.components.NineBars
import app.focal.ui.components.PageArea
import app.focal.ui.components.PaperTopFade
import app.focal.ui.components.PressPlate
import app.focal.ui.components.WaveDress
import app.focal.ui.components.WaveMode
import app.focal.ui.components.focusRing
import app.focal.ui.components.markBox
import app.focal.ui.components.pageInsets
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.LocalWindowLayout
import app.focal.ui.design.chromeAlign
import app.focal.ui.design.tabular
import app.focal.ui.state.SettingsUiState
import app.focal.ui.state.ThemeMode

/** What the settings page does. */
@Stable
class SettingsActions(
    val setTheme: (ThemeMode) -> Unit = {},
    val setParagraphTimes: (Boolean) -> Unit = {},
    /** Export all settled notes as Markdown ZIP through Android SAF. */
    val exportNotes: () -> Unit = {},
    /** Back up all settled notes (with optional audio) through Android SAF. */
    val backupNotes: (includeAudio: Boolean) -> Unit = {},
    /** Restore notes from an offline backup ZIP through Android SAF. */
    val restoreBackup: () -> Unit = {},
    /** "Remove all audio", once confirmed in its sheet. */
    val removeAllAudio: () -> Unit = {},
    /** "Delete all notes", once confirmed in its sheet. */
    val deleteAllNotes: () -> Unit = {},
    /** ← and Esc: the strip closes with the swipe's motion; in `SettingsActivity` the activity finishes. */
    val back: () -> Unit = {},
    val sourceCode: () -> Unit = {},
    val licenses: () -> Unit = {},
)

/** Settings page geometry (spec 5.10, 4.1.4, 4.18). */
object SettingsMetrics {
    /** The first group label sits this far under the top bar. */
    val contentTop = FocalGrid.x8
    /** Group labels: 24 dp above (none above the first), 4 dp below. */
    val groupAbove = FocalGrid.x24
    val groupBelow = FocalGrid.x4
    /** The theme control sits this far under its label: it has no row padding of its own. */
    val controlTop = FocalGrid.x8
    val rowMinHeight = FocalGrid.x56
    val rowPadding = FocalGrid.x12
    val trailingGap = FocalGrid.x16
    /** A row's trailing value never takes more than this, so its title keeps room at large font scales. */
    val valueMaxWidth = 160.dp
    /** The switch: a 52 x 32 pill with a thumb 4 dp inside it. */
    val switchWidth = 52.dp
    val switchHeight = FocalGrid.x32
    val switchInset = FocalGrid.x4
    /** The footer: the static mark 48 dp under the last row, the statement 12 dp under it, at most this wide. */
    val footerGap = FocalGrid.x48
    val footerMark = FocalGrid.x32
    val statementGap = FocalGrid.x12
    val statementWidth = 264.dp
    val plateInset = FocalGrid.x8
    /** The bar's title starts after the ← (x 72). */
    val titleStart = 72.dp
}

private val RowPlate = PressPlate(FocalShapes.block, insetX = SettingsMetrics.plateInset)

/**
 * The settings page (spec 5.10): the bar with ← "Back" and the page's name beside it, then four groups under small
 * labels (Theme, Notes, Data, About). Every row does something or leads somewhere. Paper, a top fade, no dock.
 * [headingFocus] receives focus when the page was opened by the accessibility action or a key.
 */
@Composable
fun SettingsPage(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    headingFocus: FocusRequester = remember { FocusRequester() },
    scroll: ScrollState = rememberScrollState(),
    feedback: FeedbackHostState = remember { FeedbackHostState() },
) {
    val context = LocalContext.current
    val pane = stringResource(R.string.settings_pane)
    var confirmingRemoveAudio by rememberSaveable { mutableStateOf(false) }
    var confirmingDeleteAll by rememberSaveable { mutableStateOf(false) }
    var choosingBackup by rememberSaveable { mutableStateOf(false) }
    // Withdraw sheets automatically if the underlying counts dropped to zero elsewhere.
    if (confirmingRemoveAudio && state.usageKnown && state.notesWithAudio == 0) SideEffect { confirmingRemoveAudio = false }
    if (confirmingDeleteAll && state.usageKnown && state.noteCount == 0) SideEffect { confirmingDeleteAll = false }
    if (choosingBackup && state.usageKnown && state.noteCount == 0) SideEffect { choosingBackup = false }
    val audio = Formatter.formatShortFileSize(context, state.audioBytes)
    val hasNotes = !state.usageKnown || state.noteCount > 0
    val hasAudio = !state.usageKnown || state.notesWithAudio > 0
    val canActOnNotes = state.usageKnown && state.noteCount > 0 && !state.busy
    val canActOnAudio = state.usageKnown && state.notesWithAudio > 0 && !state.busy

    Box(modifier.fillMaxSize().semantics { paneTitle = pane }) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = LocalWindowLayout.current.topBar)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .windowInsetsPadding(pageInsets(PageArea.Window))
                .padding(bottom = FocalLayout.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The content column: at most 600 dp between the gutters, centred (spec 2.5, 5.14).
            Column(Modifier.widthIn(max = FocalLayout.contentMaxWidth + FocalLayout.gutter * 2).fillMaxWidth().padding(top = SettingsMetrics.contentTop)) {
                GroupLabel(stringResource(R.string.settings_theme), first = true)
                ThemeControl(
                    state.theme, actions.setTheme,
                    Modifier.padding(horizontal = FocalLayout.gutter).padding(top = SettingsMetrics.controlTop - SettingsMetrics.groupBelow),
                )
                GroupLabel(stringResource(R.string.settings_notes))
                SwitchRow(stringResource(R.string.paragraph_times), state.paragraphTimes, actions.setParagraphTimes)
                GroupLabel(stringResource(R.string.settings_data))
                SettingsRow(
                    stringResource(R.string.data_export_notes),
                    value = stringResource(R.string.data_export_format),
                    onClick = { if (canActOnNotes) actions.exportNotes() },
                    enabled = hasNotes && !state.busy,
                )
                SettingsRow(
                    stringResource(R.string.data_backup),
                    onClick = { if (canActOnNotes) choosingBackup = true },
                    enabled = hasNotes && !state.busy,
                )
                SettingsRow(
                    stringResource(R.string.data_restore),
                    onClick = { if (!state.busy) actions.restoreBackup() },
                    enabled = !state.busy,
                )
                SettingsRow(
                    stringResource(R.string.storage_remove_all),
                    value = when {
                        !state.usageKnown -> ""
                        state.notesWithAudio > 0 -> audio
                        else -> stringResource(R.string.storage_none)
                    },
                    onClick = { if (canActOnAudio) confirmingRemoveAudio = true },
                    enabled = hasAudio && !state.busy,
                )
                SettingsRow(
                    stringResource(R.string.data_delete_all),
                    value = when {
                        !state.usageKnown -> ""
                        state.noteCount > 0 -> pluralStringResource(R.plurals.data_note_count, state.noteCount, state.noteCount)
                        else -> stringResource(R.string.data_none)
                    },
                    onClick = { if (canActOnNotes) confirmingDeleteAll = true },
                    enabled = hasNotes && !state.busy,
                )
                GroupLabel(stringResource(R.string.settings_about))
                SettingsRow(
                    stringResource(R.string.source_code), glyph = FocalIcon.OpenExternal, onClick = actions.sourceCode,
                    spoken = stringResource(R.string.a11y_source_code),
                )
                SettingsRow(stringResource(R.string.licenses), glyph = FocalIcon.ChevronRight, onClick = actions.licenses)
                SettingsRow(stringResource(R.string.settings_version), value = state.versionName)
                Footer(Modifier.align(Alignment.CenterHorizontally))
            }
        }
        PaperTopFade(LocalWindowLayout.current.topBar, Modifier.fillMaxSize())
        TopBar(stringResource(R.string.settings_title), actions.back, headingFocus)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = FocalLayout.contentMaxWidth + FocalLayout.gutter * 2)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = FocalLayout.gutter, vertical = FocalGrid.x16),
        ) {
            FeedbackPill(feedback)
        }
        BackupSheet(
            visible = choosingBackup && state.noteCount > 0,
            hasAudio = state.notesWithAudio > 0,
            audioSize = audio,
            onConfirm = { includeAudio ->
                choosingBackup = false
                actions.backupNotes(includeAudio)
            },
            onDismiss = { choosingBackup = false },
        )
        RemoveAudioSheet(
            visible = confirmingRemoveAudio && state.notesWithAudio > 0, notes = state.notesWithAudio, size = audio,
            onConfirm = {
                confirmingRemoveAudio = false
                actions.removeAllAudio()
            },
            onDismiss = { confirmingRemoveAudio = false },
        )
        DeleteAllNotesSheet(
            visible = confirmingDeleteAll && state.noteCount > 0,
            notes = state.noteCount,
            onConfirm = {
                confirmingDeleteAll = false
                actions.deleteAllNotes()
            },
            onDismiss = { confirmingDeleteAll = false },
        )
    }
}

/** Backup options sheet: text + metadata by default, with an optional audio toggle when kept audio exists. */
@Composable
private fun BackupSheet(
    visible: Boolean,
    hasAudio: Boolean,
    audioSize: String,
    onConfirm: (includeAudio: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FocalTheme.colors
    var includeAudio by rememberSaveable { mutableStateOf(false) }
    val shown = remember { mutableStateOf(hasAudio to audioSize) }
    if (visible) shown.value = hasAudio to audioSize
    val (canIncludeAudio, size) = shown.value
    FocalBottomSheet(visible = visible, onDismiss = onDismiss) {
        Text(
            stringResource(R.string.data_backup_title), style = FocalType.headline, color = colors.ink,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = FocalGrid.x24).semantics { heading() },
        )
        Text(
            stringResource(R.string.data_backup_body), style = FocalType.secondary, color = colors.recede,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = FocalGrid.x8),
        )
        if (canIncludeAudio) {
            Spacer(Modifier.height(FocalGrid.x12))
            SwitchRow(
                title = stringResource(R.string.data_backup_include_audio, size),
                checked = includeAudio,
                onChange = { includeAudio = it },
                horizontalPadding = 0.dp,
            )
        }
        FocalPrimaryPill(
            stringResource(R.string.data_backup_confirm),
            onClick = { onConfirm(canIncludeAudio && includeAudio) },
            modifier = Modifier.fillMaxWidth().padding(top = FocalGrid.x20),
        )
        FocalTextButton(stringResource(R.string.action_cancel), onClick = onDismiss, modifier = Modifier.padding(top = FocalGrid.x8))
    }
}

/**
 * "Remove all audio" asks before it acts: what goes (the audio of N notes, and how much that frees), what stays
 * (every note's text), and that it is final. The count and the size are kept while the sheet leaves, after the audio
 * has gone.
 */
@Composable
private fun RemoveAudioSheet(visible: Boolean, notes: Int, size: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = FocalTheme.colors
    val shown = remember { mutableStateOf(notes to size) }
    if (visible) shown.value = notes to size
    val (count, freed) = shown.value
    FocalBottomSheet(visible = visible, onDismiss = onDismiss) {
        Text(
            pluralStringResource(R.plurals.storage_remove_title, count, count), style = FocalType.headline, color = colors.ink,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = FocalGrid.x24).semantics { heading() },
        )
        Text(
            stringResource(R.string.storage_remove_body, freed), style = FocalType.secondary, color = colors.recede,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = FocalGrid.x8),
        )
        FocalPrimaryPill(stringResource(R.string.storage_remove_confirm), onClick = onConfirm, modifier = Modifier.fillMaxWidth().padding(top = FocalGrid.x24))
        FocalTextButton(stringResource(R.string.action_cancel), onClick = onDismiss, modifier = Modifier.padding(top = FocalGrid.x8))
    }
}

/** "Delete all notes" asks clearly before permanently deleting every settled note and its saved audio. */
@Composable
private fun DeleteAllNotesSheet(visible: Boolean, notes: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = FocalTheme.colors
    val shown = remember { mutableStateOf(notes) }
    if (visible) shown.value = notes
    val count = shown.value
    FocalBottomSheet(visible = visible, onDismiss = onDismiss) {
        Text(
            pluralStringResource(R.plurals.data_delete_all_title, count, count), style = FocalType.headline, color = colors.ink,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = FocalGrid.x24).semantics { heading() },
        )
        Text(
            stringResource(R.string.data_delete_all_body), style = FocalType.secondary, color = colors.recede,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = FocalGrid.x8),
        )
        FocalPrimaryPill(stringResource(R.string.data_delete_all_confirm), onClick = onConfirm, modifier = Modifier.fillMaxWidth().padding(top = FocalGrid.x24))
        FocalTextButton(stringResource(R.string.action_cancel), onClick = onDismiss, modifier = Modifier.padding(top = FocalGrid.x8))
    }
}

/**
 * The bar of the settings and licences pages (spec 4.1.4): ← "Back" at x 12-60 and the page's name beside it, on the
 * arrow's own line, where it stays as the page scrolls. Beside a hinge that splits the window it keeps to the page's
 * side, after the hinge ([app.focal.ui.design.WindowLayout.windowStart]). [headingFocus] can put focus on the name.
 */
@Composable
fun TopBar(title: String, onBack: () -> Unit, headingFocus: FocusRequester? = null) {
    val colors = FocalTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = LocalWindowLayout.current.windowStart)
            .height(LocalWindowLayout.current.topBar),
        contentAlignment = Alignment.CenterStart,
    ) {
        FocalIconButton(FocalIcon.Back, stringResource(R.string.cd_back), onBack, Modifier.padding(start = FocalLayout.iconEdge))
        Text(
            title, style = FocalType.headline, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = SettingsMetrics.titleStart, end = FocalLayout.gutter)
                .semantics { heading() }
                .then(if (headingFocus != null) Modifier.focusRequester(headingFocus) else Modifier)
                .focusable(),
        )
    }
}

/** A group label (spec 4.18): `section` `muted`, 24 dp above (none above the first), 4 dp below. */
@Composable
private fun GroupLabel(text: String, first: Boolean = false) {
    Text(
        text, style = FocalType.section, color = FocalTheme.colors.recede,
        modifier = Modifier
            .padding(horizontal = FocalLayout.gutter)
            .padding(top = if (first) 0.dp else SettingsMetrics.groupAbove, bottom = SettingsMetrics.groupBelow)
            .semantics { heading() },
    )
}

/**
 * A settings row (spec 4.18): one line, at least 56 dp tall, 24 dp side padding: its name in `field`, then its value
 * (`muted`, tabular) or the glyph of where it leads. Pressable rows get the plate at x 8-404; one that has nothing to
 * act on now ([enabled] false) is `faint` and takes no press.
 */
@Composable
private fun SettingsRow(
    title: String,
    value: String? = null,
    glyph: FocalIcon? = null,
    onClick: (() -> Unit)? = null,
    spoken: String? = null,
    enabled: Boolean = true,
) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsMetrics.rowMinHeight)
            .then(
                if (onClick == null) Modifier.semantics(mergeDescendants = true) { }
                else Modifier.focusRing(FocalShapes.block)
                    .clickable(source, indication = RowPlate, enabled = enabled, role = Role.Button, onClick = onClick)
                    .then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken; role = Role.Button } else Modifier),
            )
            .padding(horizontal = FocalLayout.gutter, vertical = SettingsMetrics.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = FocalType.field, color = if (enabled) colors.ink else colors.faint, textAlign = chromeAlign(), modifier = Modifier.weight(1f))
        if (value != null) {
            Spacer(Modifier.width(SettingsMetrics.trailingGap))
            Text(
                value, style = FocalType.field.tabular(), color = if (enabled) colors.recede else colors.faint,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = SettingsMetrics.valueMaxWidth),
            )
        }
        if (glyph != null) {
            Spacer(Modifier.width(SettingsMetrics.trailingGap))
            Glyph(glyph, colors.recede)
        }
    }
}

/**
 * A row that turns something on or off: its name and a switch; the whole row toggles it. The switch is the dock's
 * shapes at a smaller size: a pill track (`s2` off, `inv` on) and a round thumb that crosses it on `move`.
 */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = FocalLayout.gutter,
) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    val on = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) { on.animateTo(if (checked) 1f else 0f, FocalMotion.move()) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsMetrics.rowMinHeight)
            .focusRing(FocalShapes.block)
            .toggleable(checked, source, indication = RowPlate, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = horizontalPadding, vertical = SettingsMetrics.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = FocalType.field, color = colors.ink, textAlign = chromeAlign(), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(SettingsMetrics.trailingGap))
        Spacer(
            Modifier.size(SettingsMetrics.switchWidth, SettingsMetrics.switchHeight).drawBehind {
                val t = on.value
                val radius = size.height / 2
                drawRoundRect(lerp(colors.s2, colors.inv, t), cornerRadius = CornerRadius(radius))
                val inset = SettingsMetrics.switchInset.toPx()
                val thumb = radius - inset
                val travel = size.width - 2 * radius
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - radius - travel * t else radius + travel * t
                drawCircle(lerp(colors.paper, colors.onInv, t), thumb, Offset(x, radius))
            },
        )
    }
}

/**
 * The footer (spec 5.10): the static mark, 32 dp, and under it what Focal is rather than what it can be set to: it
 * works offline, in `caption` `muted`, centred.
 */
@Composable
private fun Footer(modifier: Modifier = Modifier) {
    val colors = FocalTheme.colors
    Column(
        modifier.padding(top = SettingsMetrics.footerGap).padding(horizontal = FocalLayout.gutter).semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NineBars(WaveMode.Static, WaveDress.paper(colors), Modifier.markBox(SettingsMetrics.footerMark))
        Spacer(Modifier.height(SettingsMetrics.statementGap))
        Text(
            stringResource(R.string.works_offline), style = FocalType.caption, color = colors.recede, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = SettingsMetrics.statementWidth),
        )
    }
}
