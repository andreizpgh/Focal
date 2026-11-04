package app.focal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal enum class SessionAction { Rename, Share, RemoveAudio, Delete }

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SessionActionsSheet(
    title: String,
    loading: Boolean,
    loaded: Boolean,
    shareReason: String?,
    audioReason: String?,
    deleteReason: String?,
    onDismiss: () -> Unit,
    onAction: (SessionAction) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val currentAction by rememberUpdatedState(onAction)
    var closing by remember { mutableStateOf(false) }
    fun choose(action: SessionAction) {
        if (closing) return
        closing = true
        scope.launch {
            try {
                sheetState.hide()
                if (!sheetState.isVisible) currentAction(action)
            } finally {
                closing = false
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        DialogSystemBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() })
                if (loading) FocusProcessingIndicator(contentDescription = "Loading session options")
            }
            SessionActionRow("Rename", Symbol.Edit, loaded && !closing) { choose(SessionAction.Rename) }
            SessionActionRow("Share text", Symbol.Share, loaded && shareReason == null && !closing, shareReason) { choose(SessionAction.Share) }
            SessionActionRow("Remove audio", Symbol.Audio, loaded && audioReason == null && !closing, audioReason) { choose(SessionAction.RemoveAudio) }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SessionActionRow("Delete session", Symbol.Trash, loaded && deleteReason == null && !closing, deleteReason, destructive = true) {
                choose(SessionAction.Delete)
            }
        }
    }
}

@Composable private fun SessionActionRow(
    label: String, symbol: Symbol, enabled: Boolean, reason: String? = null,
    destructive: Boolean = false, onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val color = if (destructive && enabled) MaterialTheme.colorScheme.error else LocalContentColor.current
                Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
                if (reason != null) Text(reason, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = {
            val color = if (destructive && enabled) MaterialTheme.colorScheme.error else LocalContentColor.current
            Glyph(symbol, Modifier.size(19.dp), color)
        },
        enabled = enabled, onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
    )
}
