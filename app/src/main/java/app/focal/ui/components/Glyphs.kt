package app.focal.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.focal.R
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalShapes

/**
 * The icon set (spec 2.7): Material Symbols Rounded, FILL 0 (play and pause filled), wght 400, opsz 24, as vector
 * drawables. Directional glyphs are auto-mirrored in RTL. Focal draws no settings glyph anywhere.
 */
enum class FocalIcon(@param:DrawableRes val res: Int) {
    Back(R.drawable.ic_arrow_back),
    ArrowDown(R.drawable.ic_arrow_downward),
    ArrowUp(R.drawable.ic_arrow_upward),
    Check(R.drawable.ic_check),
    ChevronRight(R.drawable.ic_chevron_right),
    Close(R.drawable.ic_close),
    Copy(R.drawable.ic_content_copy),
    Edit(R.drawable.ic_edit),
    Down(R.drawable.ic_keyboard_arrow_down),
    Up(R.drawable.ic_keyboard_arrow_up),
    More(R.drawable.ic_more_vert),
    OpenExternal(R.drawable.ic_open_in_new),
    Pause(R.drawable.ic_pause),
    Play(R.drawable.ic_play_arrow),
    Redo(R.drawable.ic_redo),
    Search(R.drawable.ic_search),
    ReplaceWith(R.drawable.ic_subdirectory_arrow_right),
    Undo(R.drawable.ic_undo),
}

/** A glyph in [tint] (24 dp unless [size] says otherwise). Decorative unless [contentDescription] is given. */
@Composable
fun Glyph(icon: FocalIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = FocalLayout.glyph, contentDescription: String? = null) {
    Image(
        painter = painterResource(icon.res),
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/** Custom glyph sizes (spec 2.7). */
object GlyphSize {
    /** Glyphs inside 56 dp pills, 8 dp before the label. */
    val inPill = 20.dp
    /** The feedback action's undo glyph, 6 dp before its label; the stage's copy glyph. */
    val small = 18.dp
    /** The record dot in a pill. */
    val recordDot = 10.dp
    /** The finish square and its corner radius. */
    val finishSquare = 12.dp
    val finishRadius = 3.dp
}

/** ● The microphone: Record and Resume. */
@Composable
fun RecordDot(color: Color, modifier: Modifier = Modifier, size: Dp = GlyphSize.recordDot) {
    Box(modifier.size(size).background(color, FocalShapes.pill))
}

private val finishShape = RoundedCornerShape(GlyphSize.finishRadius)

/** ■ Finish. */
@Composable
fun FinishSquare(color: Color, modifier: Modifier = Modifier, size: Dp = GlyphSize.finishSquare) {
    Box(modifier.size(size).background(color, if (size == GlyphSize.finishSquare) finishShape else RoundedCornerShape(GlyphSize.finishRadius * (size / GlyphSize.finishSquare))))
}
