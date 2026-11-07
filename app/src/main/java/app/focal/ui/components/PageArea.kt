package app.focal.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.focal.ui.design.LocalWindowLayout
import app.focal.ui.design.WindowLayout

/** Which part of the window a page takes (spec 5.14). */
enum class PageArea {
    /** Settings and the licences: the whole window. */
    Window,

    /** The library: beside the landscape rail; in two panes, the list pane at the start. */
    Library,

    /** A note: beside the landscape rail; in two panes the shell places it in the detail pane. */
    Note,
}

/**
 * The side insets of a page's [area] (spec 5.14): the display cutout and a side navigation bar on either side, the
 * landscape rail on the end side of a page with a dock, and in two panes everything beside the library's pane (a
 * hinge and the detail pane after it). A page pads its content with them; the window's paper still runs under them.
 */
@Composable
fun pageInsets(area: PageArea): WindowInsets {
    val layout = LocalWindowLayout.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val start = if (rtl) WindowInsetsSides.Right else WindowInsetsSides.Left
    val end = if (rtl) WindowInsetsSides.Left else WindowInsetsSides.Right
    val sides = WindowInsets.safeDrawing
    val twoPane = layout.twoPane && area != PageArea.Window
    // In two panes each pane keeps the window's edge on its own side only.
    val edges = when {
        !twoPane -> sides.only(WindowInsetsSides.Horizontal)
        area == PageArea.Library -> sides.only(start)
        else -> sides.only(end)
    }
    val beside = when {
        area == PageArea.Window -> 0.dp
        area == PageArea.Library && layout.twoPane -> layout.width - layout.listPane
        layout.rail -> WindowLayout.railWidth
        else -> 0.dp
    }
    if (beside <= 0.dp) return edges
    return edges.add(if (rtl) WindowInsets(left = beside) else WindowInsets(right = beside))
}
