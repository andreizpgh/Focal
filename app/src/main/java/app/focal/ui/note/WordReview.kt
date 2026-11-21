package app.focal.ui.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.window.Dialog
import app.focal.core.TextWords
import app.focal.R
import app.focal.data.fitsPassageDraftBudget
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.FocalIconButton
import app.focal.ui.components.FocalPrimaryPill
import app.focal.ui.components.FocalTextButton
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.state.ParagraphUi

@Composable
internal fun WordReview(
    paragraph: ParagraphUi,
    span: ReviewSpan,
    previous: String?,
    nearby: List<String>,
    overlapReadings: suspend (Long, Long) -> List<String>,
    listening: Boolean,
    listen: (() -> Unit)?,
    next: (() -> Unit)?,
    split: (() -> Unit)?,
    join: (() -> Unit)?,
    saving: Boolean,
    error: String?,
    dismiss: () -> Unit,
    save: (String) -> Unit,
) {
    val start = span.punctuationStart(paragraph.text)
    val end = span.punctuationEnd(paragraph.text)
    val original = paragraph.text.substring(start, end)
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(original, TextRange(span.start - start, span.end - start)))
    }
    var tooLong by remember { mutableStateOf(false) }
    fun update(value: TextFieldValue) {
        if (saving) return
        tooLong = value.text != field.text && !fitsPassageDraftBudget(value.text)
        if (!tooLong) field = value
    }
    val punctuation = field.text.takeLastWhile { it in REVIEW_MARKS }
    val stem = field.text.dropLast(punctuation.length)
    var readings by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(span) {
        val selected = paragraph.words.filter { it.end > span.start && it.start < span.end }
        if (selected.isEmpty() || selected.any { it.startMs == null || it.endMs == null }) return@LaunchedEffect
        readings = try {
            overlapReadings(selected.first().startMs!!, selected.last().endMs!!).distinctBy(::spellingKey).filterNot {
                spellingKey(it) == spellingKey(span.text(paragraph))
            }
        } catch (_: android.database.SQLException) {
            emptyList()
        }
    }
    val colors = FocalTheme.colors
    val context = remember(paragraph.text, span) {
        val before = paragraph.text.substring(maxOf(0, start - 64), start)
        val after = paragraph.text.substring(end, minOf(paragraph.text.length, end + 64))
        buildAnnotatedString {
            append(if (start > before.length) "…" + before.substringAfter(' ', before) else before)
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(original) }
            append(if (end + after.length < paragraph.text.length) after.substringBeforeLast(' ', after) + "…" else after)
        }
    }
    Dialog(onDismissRequest = { if (!saving) dismiss() }) {
        Surface(shape = FocalShapes.block, color = colors.paper) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(FocalGrid.x20),
                verticalArrangement = Arrangement.spacedBy(FocalGrid.x8),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.review_selection), Modifier.weight(1f), style = FocalType.rowTitle, color = colors.ink)
                    if (listen != null) FocalIconButton(
                        if (listening) FocalIcon.Pause else FocalIcon.Play,
                        stringResource(if (listening) R.string.cd_listen_stop else R.string.cd_listen), listen,
                    )
                }
                Text(context, style = FocalType.secondary, color = colors.muted, maxLines = 4)
                OutlinedTextField(
                    value = field, onValueChange = ::update, enabled = !saving,
                    modifier = Modifier.fillMaxWidth(), textStyle = FocalType.bodyWords,
                    label = { Text(stringResource(R.string.review_wording)) }, singleLine = true,
                )
                FlowRow {
                    for (mark in listOf(",", ".", if (TextWords.endsInGreek(stem)) ";" else "?", "!")) FocalTextButton(mark, {
                        update(TextFieldValue(if (punctuation == mark) stem else stem + mark, TextRange(stem.length)))
                    })
                }
                fun choose(value: String) {
                    val opening = stem.takeWhile { it in "¿¡" }
                    update(TextFieldValue(opening + value + punctuation, TextRange(opening.length, opening.length + value.length)))
                }
                val used = listOfNotNull(previous)
                Suggestions(R.string.review_previous_wording, used, ::choose)
                val seen = used.mapTo(mutableSetOf(), ::spellingKey)
                Suggestions(R.string.review_other_reading, readings.filter { seen.add(spellingKey(it)) }, ::choose)
                Suggestions(R.string.review_from_note, nearby.filter { seen.add(spellingKey(it)) }, ::choose)
                if (split != null && original == field.text && !saving) {
                    FocalTextButton(stringResource(R.string.review_split), split)
                }
                if (join != null && original == field.text && !saving) {
                    FocalTextButton(stringResource(R.string.review_join), join)
                }
                val message = if (tooLong) stringResource(R.string.error_replace_too_large) else error
                if (message != null) Text(message, style = FocalType.secondary, color = colors.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FocalGrid.x4)) {
                    FocalPrimaryPill(
                        stringResource(R.string.review_apply),
                        { save(paragraph.text.replaceRange(start, end, field.text.trim())) },
                        enabled = !saving && !tooLong && field.text.isNotBlank() && original != field.text.trim(),
                    )
                    if (next != null && original == field.text && !saving) {
                        FocalTextButton(stringResource(R.string.review_next), next)
                    }
                    FocalTextButton(stringResource(if (original == field.text) R.string.action_done else R.string.action_cancel), { if (!saving) dismiss() })
                }
            }
        }
    }
}

@Composable
private fun Suggestions(label: Int, values: List<String>, choose: (String) -> Unit) {
    if (values.isEmpty()) return
    Text(stringResource(label), style = FocalType.meta, color = FocalTheme.colors.muted)
    FlowRow { values.forEach { value -> FocalTextButton(value, { choose(value) }) } }
}
