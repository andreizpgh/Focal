package app.focal.gallery

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import app.focal.ui.components.MenuMetrics
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.tabular

/** Synthetic notes (the design renders' sample content); never user data. */
data class FakeNote(val day: String, val title: String, val duration: String, val time: String, val preview: String)

val FakeNotes = listOf(
    FakeNote("Today", "Notes for Thursday’s design review", "12 min", "16:05",
        "Start with the recording screen, because that’s the moment people decide whether to trust the app at all."),
    FakeNote("Today", "Звонок с подрядчиком по ремонту", "8 min", "13:20",
        "Плитку привезут в понедельник, но затирку надо выбрать до пятницы, иначе мастер уйдёт на другой объект."),
    FakeNote("Today", "Pick up the prescription before six", "42 s", "09:48",
        "And ask whether the generic one is the same dose, because the box looked different last time."),
    FakeNote("Yesterday", "Мысли после пробежки", "4 min", "19:12",
        "Темп держал ровно до седьмого километра, а потом подъём у моста всё испортил."),
    FakeNote("Yesterday", "Chapter four, the lighthouse scene", "27 min", "22:40",
        "She doesn’t climb the stairs at first. She counts them from the bottom, out loud, the way her father did."),
    FakeNote("Yesterday", "Список на выходные", "2 min", "10:05",
        "Забрать куртку из химчистки, купить лампочки в коридор и наконец позвонить бабушке."),
    FakeNote("Monday", "Interview with the ferry captain, part one", "1 h 12 min", "16:40",
        "He started on the night crossings in 1998, when the boat still carried the mail and the trip took four hours."),
    FakeNote("Monday", "Gift ideas for Lena", "1 min", "08:15",
        "The ceramic class she mentioned, or the good headphones, but not another book this year."),
)

val FakeLive = listOf(
    "Короче, по бюджету так. Печать каталога забирает почти половину, и это ещё до доставки.",
    "Я бы урезал тираж до трёхсот экземпляров, а для всех остальных сделал электронную версию.",
)

val FakeScene = listOf(
    "So the plan for Saturday is simple. We meet at the trailhead at eight, not half past, because the car park is full by nine.",
    "Anna brings the stove and the big pot. I’ll carry the tent and the first-aid kit, and Mark can take the water filter if someone lends him a bigger pack.",
    "If the forecast still says rain after lunch, we turn back at the lake instead of going over the ridge.",
)

@Composable
fun FakeCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = FocalType.caption.tabular(), color = FocalTheme.colors.muted, modifier = modifier)
}

/**
 * Places a menu drawn inline (popups are separate windows the capture cannot see) as MenuPosition does on a phone:
 * its top-start corner at a long-press ([x], [y]), clamped 16 dp from the frame's edges, or its top-end corner at an
 * overflow button's end when [endAligned], bounded by the frame only.
 */
internal fun Modifier.menuAt(x: Dp, y: Dp, endAligned: Boolean = false): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints())
    val edge = if (endAligned) 0 else MenuMetrics.edge.roundToPx()
    val start = if (endAligned) x.roundToPx() - placeable.width else x.roundToPx()
    val left = start.coerceIn(edge, (constraints.maxWidth - edge - placeable.width).coerceAtLeast(edge))
    val top = y.roundToPx().coerceIn(edge, (constraints.maxHeight - edge - placeable.height).coerceAtLeast(edge))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(left, top) }
}

/** The gallery's frame clock steps 16 ms a frame. */
internal const val FRAME_MS = 16L

/**
 * Waits [ms] of frame time in whole frames and returns inside a frame. `delay` resumes between frames, a frame early
 * or late from run to run, so a change it starts would land in a different frame each time; counted in frames, every
 * shot's change starts in the same frame and its captures are pixel-stable.
 */
internal suspend fun waitFrames(ms: Long) {
    repeat((ms / FRAME_MS).toInt()) { withFrameMillis { } }
}

/** A capture [t] ms after a change started at [at] ms, on the nearest frame (captures land on whole frames). */
internal fun after(at: Long, t: Long): Long = at + t - FRAME_MS / 2
