package app.focal.ui.note

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** How the find rows stand in a window (spec 4.9.2, 5.8, 5.14): FindArrangement. */
class FindArrangementTest {
    private val count = 76.dp // "No matches" at 1.0x

    @Test fun portraitAndTabletsKeepTheRowsStacked() {
        assertEquals(FindArrangement.Stacked, FindArrangement.of(rail = false, room = 364.dp, count = count))
        assertEquals(FindArrangement.Stacked, FindArrangement.of(rail = false, room = 600.dp, count = count))
    }

    @Test fun aWideLandscapePhoneSetsTheRowsSideBySide() {
        // 915 x 412 dp: two phone columns and the gap between them, centred.
        assertEquals(FindArrangement.Paired(736.dp), FindArrangement.of(rail = true, room = 771.dp, count = count))
        // Just wide enough for two 180 dp fields.
        assertEquals(FindArrangement.Paired(656.dp), FindArrangement.of(rail = true, room = 656.dp, count = count))
    }

    @Test fun aNarrowLandscapePhoneMakesOneRow() {
        // 780 x 360 dp beside the rail and a cutout: the pair would leave 170 dp fields.
        assertEquals(FindArrangement.Single(612.dp), FindArrangement.of(rail = true, room = 612.dp, count = count))
        // At least two 96 dp fields, the count, two 136 dp controls and four gaps.
        val least = 96.dp * 2 + count + 136.dp * 2 + 8.dp * 4
        assertEquals(FindArrangement.Single(least), FindArrangement.of(rail = true, room = least, count = count))
    }

    @Test fun tooNarrowForOneRowFallsBackToTheStackedRows() {
        val least = 96.dp * 2 + count + 136.dp * 2 + 8.dp * 4
        assertEquals(FindArrangement.Stacked, FindArrangement.of(rail = true, room = least - 1.dp, count = count))
        // A large font widens the count until one row no longer fits.
        assertEquals(FindArrangement.Stacked, FindArrangement.of(rail = true, room = 612.dp, count = 160.dp))
    }
}
