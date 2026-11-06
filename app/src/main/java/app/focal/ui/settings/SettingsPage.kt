package app.focal.ui.settings

import android.text.format.Formatter
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.FocalWeight
import app.focal.ui.design.LocalWindowLayout
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
    /** The page title sits this far under the top bar (ref 112 under 104). */
    val titleTop = FocalGrid.x8
    /** Group heads: 32 dp above (24 under the page title), 8 dp below. */
    val groupAbove = FocalGrid.x32
    val groupAboveFirst = FocalGrid.x24
    val groupBelow = FocalGrid.x8
    val rowMinHeight = FocalGrid.x56
    val rowPadding = FocalGrid.x12
    val descriptionGap = FocalGrid.x2
    val trailingGap = FocalGrid.x16
    /** A row's trailing value never takes more than this, so its title keeps room at large font scales. */
    val valueMaxWidth = 160.dp
    /** The footer: the static mark 48 dp under the last row, the version 8 dp under it. */
    val footerGap = FocalGrid.x48
    val footerMark = FocalGrid.x32
    val versionGap = FocalGrid.x8
    val plateInset = FocalGrid.x8
    /** The collapsed title starts after the ← (x 72). */
    val collapsedTitleStart = 72.dp
}

private val RowPlate = PressPlate(FocalShapes.block, insetX = SettingsMetrics.plateInset)

/**
 * The settings page (spec 5.10): ← "Back", the "Settings" title (it collapses into the bar on scroll), Theme as a
 * segmented control, Storage, About, and the footer with the static mark and the version. Paper, a top fade, no
 * dock. [headingFocus] receives focus when the page was opened by the accessibility action or a key.
 */
@Composable
fun SettingsPage(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    headingFocus: FocusRequester = remember { FocusRequester() },
    scroll: ScrollState = rememberScrollState(),
) {
    val colors = FocalTheme.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val pane = stringResource(R.string.settings_pane)
    // The title's height: it has passed under the bar once the page scrolled by its top gap plus its height.
    val titleHeight = remember { floatArrayOf(Float.NaN) }
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
            Column(Modifier.widthIn(max = FocalLayout.contentMaxWidth + FocalLayout.gutter * 2).fillMaxWidth()) {
                Text(
                    stringResource(R.string.settings_title), style = FocalType.title, color = colors.ink,
                    modifier = Modifier
                        .padding(horizontal = FocalLayout.gutter)
                        .padding(top = SettingsMetrics.titleTop)
                        .semantics { heading() }
                        .focusRequester(headingFocus)
                        .focusable()
                        .onGloballyPositioned { titleHeight[0] = it.size.height.toFloat() },
                )
                GroupHead(stringResource(R.string.settings_theme), first = true)
                ThemeControl(state.theme, actions.setTheme, Modifier.padding(horizontal = FocalLayout.gutter))
                GroupHead(stringResource(R.string.settings_storage))
                SettingsRow(
                    title = stringResource(R.string.storage_audio),
                    description = pluralStringResource(R.plurals.storage_desc, state.notesWithAudio, state.notesWithAudio),
                    value = Formatter.formatShortFileSize(context, state.audioBytes),
                )
                GroupHead(stringResource(R.string.settings_about))
                SettingsRow(stringResource(R.string.works_offline), description = stringResource(R.string.works_offline_desc))
                SettingsRow(
                    stringResource(R.string.source_code), glyph = FocalIcon.OpenExternal, onClick = actions.sourceCode,
                    spoken = stringResource(R.string.a11y_source_code),
                )
                SettingsRow(stringResource(R.string.licenses), glyph = FocalIcon.ChevronRight, onClick = actions.licenses)
                Footer(state.versionName, Modifier.align(Alignment.CenterHorizontally))
            }
        }
        PaperTopFade(LocalWindowLayout.current.topBar, Modifier.fillMaxSize())
        TopBar(stringResource(R.string.settings_title), actions.back, collapsed = {
            val height = titleHeight[0]
            !height.isNaN() && scroll.value > height + with(density) { SettingsMetrics.titleTop.toPx() }
        })
    }
}

/**
 * The bar of the settings and licences pages (spec 4.1.4): ← "Back" at x 12-60 only; the page title collapses into
 * it (`rowTitle`, fading on `fade`) once the content title has scrolled under the bar.
 */
@Composable
fun TopBar(title: String, onBack: () -> Unit, collapsed: () -> Boolean) {
    val colors = FocalTheme.colors
    val shown by remember { derivedStateOf(collapsed) }
    val alpha = remember { Animatable(if (shown) 1f else 0f) }
    LaunchedEffect(shown) { alpha.animateTo(if (shown) 1f else 0f, FocalMotion.fade()) }
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(LocalWindowLayout.current.topBar),
        contentAlignment = Alignment.CenterStart,
    ) {
        FocalIconButton(FocalIcon.Back, stringResource(R.string.cd_back), onBack, Modifier.padding(start = FocalLayout.iconEdge))
        Text(
            title, style = FocalType.rowTitle, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = SettingsMetrics.collapsedTitleStart, end = FocalLayout.gutter)
                .graphicsLayer { this.alpha = alpha.value }
                .clearAndSetSemantics { },
        )
    }
}

/** A group head (spec 4.18): `section` `muted`, 32 dp above (24 under the page title), 8 dp below. */
@Composable
private fun GroupHead(text: String, first: Boolean = false) {
    Text(
        text, style = FocalType.section, color = FocalTheme.colors.recede,
        modifier = Modifier
            .padding(horizontal = FocalLayout.gutter)
            .padding(top = if (first) SettingsMetrics.groupAboveFirst else SettingsMetrics.groupAbove, bottom = SettingsMetrics.groupBelow)
            .semantics { heading() },
    )
}

/**
 * A settings row (spec 4.18): at least 56 dp, 12 dp vertical and 24 dp horizontal padding; the title in `field`
 * 500, an optional description 2 dp below, an optional trailing value or glyph. Pressable rows get the plate at
 * x 8-404.
 */
@Composable
private fun SettingsRow(
    title: String,
    description: String? = null,
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SettingsMetrics.descriptionGap)) {
            Text(title, style = FocalType.field.copy(fontWeight = FocalWeight.Medium), color = colors.ink)
            if (description != null) Text(description, style = FocalType.secondary, color = colors.recede)
        }
        if (value != null) {
            Spacer(Modifier.width(SettingsMetrics.trailingGap))
            Text(
                value, style = FocalType.field.copy(fontWeight = FocalWeight.Medium).tabular(), color = colors.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = SettingsMetrics.valueMaxWidth),
            )
        }
        if (glyph != null) {
            Spacer(Modifier.width(SettingsMetrics.trailingGap))
            Glyph(glyph, colors.ink)
        }
    }
}

/** The footer (spec 5.10): the static mark, 32 dp, and "Focal 1.0.0" in `caption` `muted`. */
@Composable
private fun Footer(version: String, modifier: Modifier = Modifier) {
    val colors = FocalTheme.colors
    Column(modifier.padding(top = SettingsMetrics.footerGap), horizontalAlignment = Alignment.CenterHorizontally) {
        NineBars(WaveMode.Static, WaveDress.paper(colors), Modifier.markBox(SettingsMetrics.footerMark))
        Spacer(Modifier.height(SettingsMetrics.versionGap))
        Text(stringResource(R.string.version, version), style = FocalType.caption, color = colors.recede)
    }
}
