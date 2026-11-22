package app.focal.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.focal.R
import app.focal.ui.components.FeedbackHostState
import app.focal.ui.state.SettingsViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The settings page from its view model: the theme applies at once, export/backup/restore use Android SAF with zero
 * storage permissions, "Source code" opens the repository through `ACTION_VIEW` (no INTERNET permission), and
 * "Open-source licenses" opens the licences page.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    modifier: Modifier = Modifier,
    headingFocus: FocusRequester = remember { FocusRequester() },
    visible: () -> Boolean = { true },
    scroll: ScrollState = rememberScrollState(),
) {
    val settings: SettingsViewModel = viewModel()
    val shown by rememberUpdatedState(visible)
    LaunchedEffect(settings) { snapshotFlow { shown() }.collect(settings::setVisible) }
    val state by settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val url = stringResource(R.string.source_code_url)
    val back by rememberUpdatedState(onBack)
    val licenses by rememberUpdatedState(onLicenses)
    val feedback = remember { FeedbackHostState() }
    LaunchedEffect(settings) { settings.events.collect { message -> feedback.show(message) } }

    var pendingIncludeAudio by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) settings.exportMarkdown(uri)
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) settings.exportBackup(uri, includeAudio = pendingIncludeAudio)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) settings.restoreBackup(uri)
    }

    val actions = remember(settings, url) {
        SettingsActions(
            setTheme = settings::setTheme,
            setParagraphTimes = settings::setParagraphTimes,
            exportNotes = {
                val stamp = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                runCatching { exportLauncher.launch("focal-notes-$stamp.zip") }
            },
            backupNotes = { includeAudio ->
                pendingIncludeAudio = includeAudio
                val stamp = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                runCatching { backupLauncher.launch("focal-backup-$stamp.zip") }
            },
            restoreBackup = {
                runCatching { restoreLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            },
            removeAllAudio = settings::removeAllAudio,
            deleteAllNotes = settings::deleteAllNotes,
            back = { back() },
            sourceCode = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } catch (_: ActivityNotFoundException) { }
            },
            licenses = { licenses() },
        )
    }
    SettingsPage(state, actions, modifier, headingFocus, scroll, feedback)
}
