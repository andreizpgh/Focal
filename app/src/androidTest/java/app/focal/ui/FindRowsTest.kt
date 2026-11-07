package app.focal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.data.TextMatch
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.LocalWindowLayout
import app.focal.ui.design.WindowLayout
import app.focal.ui.design.tabular
import app.focal.ui.note.FindActions
import app.focal.ui.note.FindRows
import app.focal.ui.note.FindState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The find rows (spec 4.9.2, 5.8, 5.14) in a short window too narrow for the pair: one row. */
@RunWith(AndroidJUnit4::class)
class FindRowsTest {
    @get:Rule val rule = createComposeRule()

    /** A count wider than "No matches" (its reserve) is shown whole, never cut at its space. */
    @Test fun aCountWiderThanNoMatchesStaysWholeInOneRow() {
        val find = FindState()
        // A smaller phone in landscape: 780 x 360 dp, the rows beside the rail inside the gutters. The row takes that
        // width even where the test's display is narrower.
        val window = WindowLayout(780.dp, 360.dp)
        var whole = 0
        rule.setContent {
            FocalTheme(dark = false) {
                // The count's own width on one line, in its style.
                val measurer = rememberTextMeasurer()
                val style = FocalType.meta.tabular()
                whole = remember(measurer, style) { measurer.measure("100 of $MATCHES", style, maxLines = 1, softWrap = false).size.width }
                CompositionLocalProvider(LocalWindowLayout provides window) {
                    Column(Modifier.width(window.width - WindowLayout.railWidth - FocalLayout.gutter * 2)) {
                        FindRows(find, FindActions(close = {}, next = find::next, previous = find::previous, replaceAll = {}))
                    }
                }
            }
        }
        rule.runOnIdle {
            find.open(fromEdit = false)
            find.query.setTextAndPlaceCursorAtEnd("a")
            find.show("a", List(MATCHES) { TextMatch(1, it * 2, it * 2 + 1) }) { true }
            repeat(99) { find.next() }
        }
        // Cut at its space, the count would be as narrow as its box ("No matches") and show "100 of" alone.
        val count = rule.onNodeWithText("100 of $MATCHES").fetchSemanticsNode()
        assertTrue("The count is ${count.size.width} px of its $whole", whole > 0 && count.size.width >= whole)
    }

    private companion object {
        const val MATCHES = 1000
    }
}
