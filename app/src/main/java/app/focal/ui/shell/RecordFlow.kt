package app.focal.ui.shell

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.focal.R
import app.focal.ui.components.FocalBottomSheet
import app.focal.ui.components.FocalPrimaryPill
import app.focal.ui.components.FocalTextButton
import app.focal.ui.components.NineBars
import app.focal.ui.components.RecordDot
import app.focal.ui.components.SheetMetrics
import app.focal.ui.components.WaveDress
import app.focal.ui.components.WaveMode
import app.focal.ui.components.markBox
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType

/** What the rationale sheet offers (spec 5.11). */
enum class RationaleMode { Allow, OpenSettings }

/**
 * Activity-scoped in-memory continuation and rationale sheet state across configuration changes and
 * permission/settings dialogs. Kept strictly out of SavedStateHandle so task removal or process death never
 * resumes into microphone capture without a fresh user tap.
 */
internal class RecordFlowState {
    data class Target(val appendTo: String? = null, val fromMs: Long = 0L)

    var sheet by mutableStateOf<RationaleMode?>(null)
        private set

    private var target: Target? = null
    private var awaitingPermission: Boolean = false
    private var awaitingSettings: Boolean = false

    fun armPermission(appendTo: String?, fromMs: Long) {
        sheet = null
        target = Target(appendTo, fromMs)
        awaitingPermission = true
        awaitingSettings = false
    }

    fun holdRationale(mode: RationaleMode, appendTo: String?, fromMs: Long) {
        sheet = mode
        target = Target(appendTo, fromMs)
        awaitingPermission = false
        awaitingSettings = false
    }

    fun armPermissionFromHold() {
        sheet = null
        target = target ?: Target()
        awaitingPermission = true
        awaitingSettings = false
    }

    fun consumePermission(): Target? {
        if (!awaitingPermission) return null
        awaitingPermission = false
        return target
    }

    fun armSettingsFromHold() {
        sheet = null
        target = target ?: Target()
        awaitingPermission = false
        awaitingSettings = true
    }

    fun consumeSettings(): Target? {
        if (!awaitingSettings) return null
        awaitingSettings = false
        val current = target
        target = null
        return current
    }

    fun clear() {
        sheet = null
        target = null
        awaitingPermission = false
        awaitingSettings = false
    }
}

internal class RecordFlowViewModel : ViewModel() {
    val state = RecordFlowState()
}

/**
 * Record's microphone permission (spec 5.11): the first tap shows the system dialog directly; after a denial the
 * rationale sheet explains and asks again; once Android stops asking, the sheet's pill opens the app's details,
 * and coming back with the permission granted starts the recording at once. On grant, capture starts with no
 * second tap.
 */
@Stable
class RecordFlow internal constructor(private val state: RecordFlowState = RecordFlowState()) {
    var sheet: RationaleMode?
        get() = state.sheet
        internal set(value) {
            if (value == null) state.clear() else state.holdRationale(value, null, 0L)
        }
    internal var start: (String?, Long) -> Unit = { _, _ -> }
    internal var allow: () -> Unit = {}
    internal var openDetails: () -> Unit = {}

    /**
     * Record: start now, or ask for the microphone first. With [appendTo], the recording continues that note, whose
     * length so far is [fromMs].
     */
    fun record(appendTo: String? = null, fromMs: Long = 0) = start(appendTo, fromMs)

    fun dismiss() {
        state.clear()
    }
}

@Composable
fun rememberRecordFlow(record: (appendTo: String?, fromMs: Long) -> Unit): RecordFlow {
    val context = LocalContext.current
    val owner = LocalViewModelStoreOwner.current
    val state = if (owner != null) viewModel<RecordFlowViewModel>(viewModelStoreOwner = owner).state else remember { RecordFlowState() }
    val flow = remember(state) { RecordFlow(state) }
    val onRecord by rememberUpdatedState(record)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val armed = state.consumePermission()
        if (granted) {
            state.clear()
            if (armed != null) onRecord(armed.appendTo, armed.fromMs)
        } else if (armed != null && context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) {
            // Android will not ask again: the sheet's pill opens the app's details instead.
            state.holdRationale(RationaleMode.OpenSettings, armed.appendTo, armed.fromMs)
        } else {
            state.clear()
        }
    }
    // Back from the app's details in this Activity with the permission granted: record at once.
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val armed = state.consumeSettings()
        if (armed != null && context.hasMicrophone()) {
            state.clear()
            onRecord(armed.appendTo, armed.fromMs)
        }
    }
    flow.start = { appendTo, fromMs ->
        val activity = context.findActivity()
        when {
            context.hasMicrophone() -> {
                state.clear()
                onRecord(appendTo, fromMs)
            }
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true -> {
                state.holdRationale(RationaleMode.Allow, appendTo, fromMs)
            }
            else -> {
                state.armPermission(appendTo, fromMs)
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    flow.allow = {
        state.armPermissionFromHold()
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    flow.openDetails = {
        state.armSettingsFromHold()
        try {
            settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        } catch (_: ActivityNotFoundException) {
            state.clear()
        }
    }
    return flow
}

/**
 * The rationale sheet (spec 4.19, 5.11): the static mark, "Focal needs the microphone", why, and [Allow microphone]
 * (or [Open settings] once Android stops asking) with "Not now" under it.
 */
@Composable
fun RecordFlowSheet(flow: RecordFlow) {
    val colors = FocalTheme.colors
    val mode = flow.sheet
    var shown by remember { mutableStateOf(RationaleMode.Allow) }
    if (mode != null) shown = mode
    FocalBottomSheet(visible = mode != null, onDismiss = flow::dismiss) {
        Spacer(Modifier.height(SheetTop))
        NineBars(WaveMode.Static, WaveDress.paper(colors), Modifier.markBox(SheetMark))
        Text(
            stringResource(R.string.perm_title), style = FocalType.headline, color = colors.ink, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = FocalGrid.x16),
        )
        Text(
            stringResource(R.string.perm_body), style = FocalType.secondary, color = colors.recede, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = FocalGrid.x8),
        )
        FocalPrimaryPill(
            stringResource(if (shown == RationaleMode.Allow) R.string.perm_allow else R.string.perm_settings),
            onClick = {
                if (shown == RationaleMode.Allow) flow.allow() else flow.openDetails()
            },
            modifier = Modifier.fillMaxWidth().padding(top = FocalGrid.x32),
            leading = if (shown == RationaleMode.Allow) { _ -> RecordDot(FocalTheme.colors.recordDot) } else null,
        )
        FocalTextButton(stringResource(R.string.perm_not_now), onClick = flow::dismiss, modifier = Modifier.padding(top = FocalGrid.x8))
    }
}

/** The sheet's static mark (40 dp) sits 36 dp from its top (spec 4.19), under the grab handle (12 dp down, 4 dp tall). */
private val SheetMark = FocalGrid.x40
private val SheetTop = FocalGrid.x36 - SheetMetrics.handleTop - SheetMetrics.handleHeight

private fun Context.hasMicrophone(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
