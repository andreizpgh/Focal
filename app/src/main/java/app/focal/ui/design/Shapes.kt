package app.focal.ui.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** The five radii of the product (spec 2.4). No other radius literals. */
object FocalShapes {
    /** Every button and circle, fields, the feedback pill, segmented track, jump pill, the bars and the playhead. */
    val pill = CircleShape
    /** The stage, bottom-sheet tops, the morph's corners and page corners in predictive back and the settings swipe. */
    val sheetRadius = 28.dp
    val sheet = RoundedCornerShape(sheetRadius)
    val sheetTop = RoundedCornerShape(topStart = sheetRadius, topEnd = sheetRadius)
    /** Dropdown menus. */
    val menu = RoundedCornerShape(16.dp)
    /** The edit sheet; row and settings press plates. */
    val blockRadius = 12.dp
    val block = RoundedCornerShape(blockRadius)
    /** Inline highlights: the playback lens, find matches, the search-arrival highlight. */
    val markRadius = 8.dp
    val mark = RoundedCornerShape(markRadius)

    val material = Shapes(
        extraSmall = mark, small = mark, medium = block, large = menu, extraLarge = sheet,
    )
}
