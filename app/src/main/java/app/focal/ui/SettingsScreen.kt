package app.focal.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.focal.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun SettingsScreen(
    theme: String, setTheme: (String) -> Unit, modelBundled: Boolean, openModel: () -> Unit, notify: (String) -> Unit,
) {
    var help by rememberSaveable { mutableStateOf(false) }
    var licenses by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Column {
                SettingsRow("Speech model", if (modelBundled) "Ultra · Automatic language" else "Missing from this build", click = openModel)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        listOf("light" to "Light", "dark" to "Dark", "system" to "System").forEach { (id, name) ->
                            FilterChip(colors = focalChipColors(), shape = RoundedCornerShape(50), selected = theme == id,
                                onClick = { setTheme(id) }, label = { Text(name) })
                        }
                    }
                }
            }
        }
        Column {
            SettingsSectionLabel("Help & about")
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface) {
                Column {
                    SettingsRow("Using Focal", click = { help = true })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsRow("Source code", click = {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/andreizpgh/Focal".toUri())) }
                        catch (_: ActivityNotFoundException) { notify("No app is available to open this link.") }
                        catch (_: SecurityException) { notify("This device cannot open the link.") }
                    })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsRow("Licenses", click = { licenses = true })
                }
            }
            Row(
                Modifier.padding(top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BrandMark(32)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (help) AlertDialog(
        onDismissRequest = { help = false }, title = { Text("Using Focal") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                HelpTopic("Recording", "Ultra detects the language automatically. Record, then finish to review and copy the text. If transcription cannot finish, the original audio stays available for retry.")
                HelpTopic("In the background", "Recording can continue with the screen off or in another app. Force-stopping Focal or restarting your phone ends it. Some phones restrict background apps.")
                HelpTopic("Reviewing words", "Some uncertain or missing ranges are marked for review; the dots are not a grammar check or a confidence score. Tap any passage to listen and edit. Done confirms it; Next hint confirms it and moves on. Your original audio stays unchanged.")
                HelpTopic("Keeping recordings", "Audio and text are stored on this device. Uninstalling Focal deletes them. Copy or share text you need before uninstalling.")
            }
        },
        confirmButton = { TextButton(onClick = { help = false }) { Text("Done") } },
    )
    if (licenses) LicensesDialog { licenses = false }
}

@Composable private fun SettingsSectionLabel(label: String) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
}

@Composable private fun SettingsRow(label: String, value: String? = null, enabled: Boolean = true, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = click)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        if (enabled) Glyph(Symbol.Chevron, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        else CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
    }
}

@Composable private fun HelpTopic(title: String, text: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 7.dp))
    }
}

private val licenseParagraphBreak = Regex("\\r?\\n(?:[ \\t]*\\r?\\n)+")

@Composable private fun LicensesDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    var paragraphs by remember { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        try {
            paragraphs = withContext(Dispatchers.IO) {
                listOf("licenses.txt", "ultra-licenses.txt").flatMap { name ->
                    context.assets.open(name).bufferedReader().use { reader ->
                        licenseParagraphBreak.split(reader.readText()).filter { it.isNotBlank() }
                    }
                }
            }
        }
        catch (e: Exception) { if (e is CancellationException) throw e; failed = true }
    }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("Open-source licenses") },
        text = {
            when {
                paragraphs != null -> SelectionContainer {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(paragraphs.orEmpty()) { paragraph ->
                            Text(paragraph, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                failed -> Column {
                    Text("Couldn't open the licenses.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { attempt++ }, contentPadding = PaddingValues(0.dp)) { Text("Try again") }
                }
                else -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } },
    )
}
