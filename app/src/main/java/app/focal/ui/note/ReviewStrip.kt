package app.focal.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.focal.R
import app.focal.ui.components.FadeThrough
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.Glyph
import app.focal.ui.components.GlyphSize
import app.focal.ui.components.focusRing
import app.focal.ui.components.pressScale
import app.focal.ui.components.scrollEdgeFade
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.FocalWeight

/** The primary right-hand action of [ReviewStrip]. */
internal enum class ReviewPrimary {
    /** Confirm the current doubtful word as spoken and clear its underline. */
    Keep,
    /** Save edits and jump straight to the next unclear word in the queue. */
    Next,
    /** Save edits and finish reviewing. */
    Apply,
}

/** Companion strip geometry: one quiet 48 dp row riding directly above the keyboard. */
internal object ReviewStripMetrics {
    val height = FocalGrid.x48
    val pillHeight = FocalGrid.x40
    val horizontalPadding = 14.dp
    val iconGap = FocalGrid.x4
    val itemGap = FocalGrid.x8
}

/**
 * Single-row review companion riding above the keyboard via [app.focal.ui.components.BottomColumn]:
 * left side holds Listen plus one-tap suggestions from earlier edits or nearby spellings in the note;
 * right side pins a single primary action (Keep, Next, or Apply).
 */
@Composable
internal fun ReviewStrip(
    canListen: Boolean,
    listening: Boolean,
    onListen: () -> Unit,
    suggestions: List<String>,
    onPickSuggestion: (String) -> Unit,
    primary: ReviewPrimary?,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = ReviewStripMetrics.height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .scrollEdgeFade(scroll)
                .horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(ReviewStripMetrics.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canListen) {
                ListenPill(listening = listening, onClick = onListen)
            }
            suggestions.forEach { wording ->
                SuggestionPill(text = wording, onClick = { onPickSuggestion(wording) })
            }
        }
        if (primary != null) {
            Spacer(Modifier.width(ReviewStripMetrics.itemGap))
            PrimaryReviewPill(primary = primary, onClick = onPrimary)
        }
    }
}

@Composable
private fun ListenPill(listening: Boolean, onClick: () -> Unit) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    val label = stringResource(R.string.review_listen)
    val description = stringResource(if (listening) R.string.cd_listen_stop else R.string.cd_listen)
    Box(
        Modifier
            .heightIn(min = FocalLayout.target)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                this.onClick { onClick(); true }
            }
            .pressScale(source)
            .focusRing(FocalShapes.pill)
            .clickable(source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = ReviewStripMetrics.pillHeight)
                .background(if (listening) colors.s2 else colors.s1, FocalShapes.pill)
                .border(1.dp, colors.stageIdleBorder, FocalShapes.pill)
                .padding(horizontal = ReviewStripMetrics.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(if (listening) FocalIcon.Pause else FocalIcon.Play, colors.ink, size = GlyphSize.small)
            Spacer(Modifier.width(ReviewStripMetrics.iconGap))
            Text(
                label,
                style = FocalType.action.copy(fontWeight = FocalWeight.Medium),
                color = colors.ink,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SuggestionPill(text: String, onClick: () -> Unit) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = FocalLayout.target)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .pressScale(source)
            .focusRing(FocalShapes.pill)
            .clickable(source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = ReviewStripMetrics.pillHeight)
                .background(colors.s1, FocalShapes.pill)
                .border(1.dp, colors.stageIdleBorder, FocalShapes.pill)
                .padding(horizontal = ReviewStripMetrics.horizontalPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = FocalType.action.copy(fontWeight = FocalWeight.Medium),
                color = colors.ink,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun PrimaryReviewPill(primary: ReviewPrimary, onClick: () -> Unit) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = FocalLayout.target)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .pressScale(source)
            .focusRing(FocalShapes.pill)
            .clickable(source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = ReviewStripMetrics.pillHeight)
                .background(colors.inv, FocalShapes.pill)
                .padding(horizontal = FocalGrid.x16),
            contentAlignment = Alignment.Center,
        ) {
            FadeThrough(primary) { mode ->
                val text = when (mode) {
                    ReviewPrimary.Keep -> stringResource(R.string.review_keep)
                    ReviewPrimary.Next -> stringResource(R.string.review_next_step)
                    ReviewPrimary.Apply -> stringResource(R.string.review_apply)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mode != ReviewPrimary.Next) {
                        Glyph(FocalIcon.Check, colors.onInv, size = GlyphSize.small)
                        Spacer(Modifier.width(ReviewStripMetrics.iconGap))
                    }
                    Text(text, style = FocalType.label, color = colors.onInv, maxLines = 1, softWrap = false)
                    if (mode == ReviewPrimary.Next) {
                        Spacer(Modifier.width(ReviewStripMetrics.iconGap))
                        Glyph(FocalIcon.ChevronRight, colors.onInv, size = GlyphSize.small)
                    }
                }
            }
        }
    }
}
