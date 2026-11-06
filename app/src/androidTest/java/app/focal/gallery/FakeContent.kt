package app.focal.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.FocalWeight
import app.focal.ui.design.Grid
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

/** A library row as the design draws it (spec 4.12.2), for context around the components. */
@Composable
fun FakeRow(note: FakeNote, modifier: Modifier = Modifier) {
    val colors = FocalTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = Grid.x24), verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(note.title, style = FocalType.rowTitle, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Grid.x16))
            Text(note.duration, style = FocalType.meta, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.ink, fontWeight = FocalWeight.Medium, fontFeatureSettings = "tnum")) { append(note.time) }
                append(" ")
                append(note.preview)
            },
            style = FocalType.secondary, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun FakeCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = FocalType.caption.tabular(), color = FocalTheme.colors.muted, modifier = modifier)
}
