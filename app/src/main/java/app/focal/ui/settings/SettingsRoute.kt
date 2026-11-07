package app.focal.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.focal.R
import app.focal.ui.state.SettingsViewModel

/**
 * The settings page from its view model: the theme applies at once, "Source code" opens the repository through
 * `ACTION_VIEW` (no web view, no INTERNET permission), "Open-source licenses" opens the licences page. [visible] says
 * whether any of the page is on screen (it stays composed beside the library); the storage use is re-read only then.
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
    val actions = remember(settings, url) {
        SettingsActions(
            setTheme = settings::setTheme,
            back = { back() },
            sourceCode = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } catch (_: ActivityNotFoundException) { }
            },
            licenses = { licenses() },
        )
    }
    SettingsPage(state, actions, modifier, headingFocus, scroll)
}
