package app.focal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.focal.data.Note
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RecordingDatesSheet(
    days: Map<LocalDate, List<Note>>, current: LocalDate?,
    dismiss: () -> Unit, select: (LocalDate?) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val currentSelect by rememberUpdatedState(select)
    var closing by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
    ) {
        DialogSystemBars()
        Text("Choose day", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).semantics { heading() })
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        ) {
            items(listOf<LocalDate?>(null) + days.keys, key = { it?.toString() ?: "all" }) { day ->
                Surface(shape = RoundedCornerShape(14.dp),
                    color = if (day == current) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background,
                ) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .selectable(day == current, enabled = !closing, role = Role.RadioButton, onClick = {
                            closing = true
                            scope.launch {
                                try { sheet.hide(); if (!sheet.isVisible) currentSelect(day) }
                                finally { closing = false }
                            }
                        }).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(day?.let { dayLabel(it, LocalDate.now()) } ?: "All dates", style = MaterialTheme.typography.titleMedium)
                            val count = if (day == null) days.values.sumOf { it.size } else days.getValue(day).size
                            Text("$count ${if (count == 1) "recording" else "recordings"}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (day == current) Glyph(Symbol.Check, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
