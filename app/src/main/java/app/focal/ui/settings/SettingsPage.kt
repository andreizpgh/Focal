package app.focal.ui.settings

import android.text.format.Formatter
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.unit.dp
import app.focal.R
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.FocalIconButton
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
    /** A group's note ends this far under the row it explains (the row's own 12 dp padding less this overlap). */
    val noteLift = FocalGrid.x8
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
 * The settings page (spec 5.10): the bar with ← "Back" and the page's name beside it, then three groups under small
 * labels. Each row is one line: what it is, and its value or where it leads. What needs a sentence is a note under
 * its group, in the smallest type, never a second heading inside a row; the statement that Focal works offline is not
 * a setting, so it closes the page under the mark instead of sitting in a list. Paper, a top fade, no dock.
 * [headingFocus] receives focus when the page was opened by the accessibility action or a key.
 */
@Composable
fun SettingsPage(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    headingFocus: FocusRequester = remember { FocusRequester() },
    scroll: ScrollState = rememberScrollState(),
) {
    val context = LocalContext.current
    val pane = stringResource(R.string.settings_pane)
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
                GroupLabel(stringResource(R.string.settings_storage))
                SettingsRow(stringResource(R.string.storage_audio), value = Formatter.formatShortFileSize(context, state.audioBytes))
                GroupNote(pluralStringResource(R.plurals.storage_desc, state.notesWithAudio, state.notesWithAudio))
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

/** What a group's row needs a sentence for: `caption` `muted`, close under the row it explains. */
@Composable
private fun GroupNote(text: String) {
    Text(
        text, style = FocalType.caption, color = FocalTheme.colors.recede, textAlign = chromeAlign(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = FocalLayout.gutter).offset(y = -SettingsMetrics.noteLift),
    )
}

/**
 * A settings row (spec 4.18): one line, at least 56 dp tall, 24 dp side padding: its name in `field`, then its value
 * (`muted`, tabular) or the glyph of where it leads. Pressable rows get the plate at x 8-404.
 */
@Composable
private fun SettingsRow(
    title: String,
    value: String? = null,
    glyph: FocalIcon? = null,
    onClick: (() -> Unit)? = null,
    spoken: String? = null,
) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsMetrics.rowMinHeight)
            .then(
                if (onClick == null) Modifier.semantics(mergeDescendants = true) { }
                else Modifier.focusRing(FocalShapes.block).clickable(source, indication = RowPlate, role = Role.Button, onClick = onClick)
                    .then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken; role = Role.Button } else Modifier),
            )
            .padding(horizontal = FocalLayout.gutter, vertical = SettingsMetrics.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = FocalType.field, color = colors.ink, textAlign = chromeAlign(), modifier = Modifier.weight(1f))
        if (value != null) {
            Spacer(Modifier.width(SettingsMetrics.trailingGap))
            Text(
                value, style = FocalType.field.tabular(), color = colors.recede,
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
