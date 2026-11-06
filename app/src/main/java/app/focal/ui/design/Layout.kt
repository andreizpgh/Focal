package app.focal.ui.design

import androidx.compose.ui.unit.dp

/**
 * The 4 dp grid (spec 2.5). These are the only spacing steps; anything else is a derived optical offset that the
 * spec names, defined beside the component that uses it.
 */
object Grid {
    val x2 = 2.dp
    val x4 = 4.dp
    val x8 = 8.dp
    val x12 = 12.dp
    val x16 = 16.dp
    val x20 = 20.dp
    val x24 = 24.dp
    val x32 = 32.dp
    val x36 = 36.dp
    val x40 = 40.dp
    val x48 = 48.dp
    val x56 = 56.dp
    val x64 = 64.dp
}

/** Layout constants shared by every screen (spec 2.5). Positions are built from insets, never from ref coordinates. */
object FocalLayout {
    /** Text, rows and every bottom element start and end this far from the window edges. */
    val gutter = Grid.x24
    /** Icon buttons sit this far from the screen edge, so a 24 dp glyph's edge lands on the gutter. */
    val iconEdge = Grid.x12
    /** The content column and the bottom column are capped at this width and centred on wider windows. */
    val contentMaxWidth = 600.dp
    /** The minimum touch target everywhere (words are the one exception). */
    val target = Grid.x48
    val glyph = Grid.x24
    /** The note, settings and licences top bar, below the status bar. */
    val topBar = Grid.x64
    /** Stacked bottom elements sit this far apart; the lowest field rides this far above the keyboard. */
    val bottomGap = Grid.x8
    /** The dock's bottom edge sits this far above the navigation inset. */
    val dockInset = Grid.x16
    /** Scrolling content reserves the bottom column's height plus this, so the last line clears the fade. */
    val contentReserve = Grid.x64
    /** Paper fades replace bars and dividers (spec 2.6). */
    val topFade = Grid.x16
    val bottomFade = Grid.x64
    /** Above search results and find rows the bottom fade is shorter. */
    val keyboardFade = Grid.x48
}
