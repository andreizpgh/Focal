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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.Grid

/** What the rationale sheet offers (spec 5.11). */
enum class RationaleMode { Allow, OpenSettings }

/**
 * Record's microphone permission (spec 5.11): the first tap shows the system dialog directly; after a denial the
 * rationale sheet explains and asks again; once Android stops asking, the sheet's pill opens the app's details,
 * and coming back with the permission granted starts the recording at once. On grant, capture starts with no
 * second tap.
 */
@Stable
class RecordFlow internal constructor() {
    var sheet by mutableStateOf<RationaleMode?>(null)
        internal set
    internal var start: () -> Unit = {}
    internal var allow: () -> Unit = {}
    internal var openDetails: () -> Unit = {}

    /** Record: start now, or ask for the microphone first. */
    fun record() = start()

    fun dismiss() { sheet = null }
}

@Composable
fun rememberRecordFlow(record: () -> Unit): RecordFlow {
    val context = LocalContext.current
    val flow = remember { RecordFlow() }
    val onRecord by rememberUpdatedState(record)
    var fromSettings by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onRecord()
        else if (context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) {
            // Android will not ask again: the sheet's pill opens the app's details instead.
            flow.sheet = RationaleMode.OpenSettings
        }
    }
    flow.start = {
        val activity = context.findActivity()
        when {
            context.hasMicrophone() -> onRecord()
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true -> flow.sheet = RationaleMode.Allow
            else -> launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Back from the app's details with the permission granted: record at once.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && fromSettings) {
                fromSettings = false
                if (context.hasMicrophone()) {
                    flow.sheet = null
                    onRecord()
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    flow.allow = { launcher.launch(Manifest.permission.RECORD_AUDIO) }
    flow.openDetails = {
        fromSettings = true
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        } catch (_: ActivityNotFoundException) {
            fromSettings = false
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
            modifier = Modifier.padding(top = Grid.x16),
        )
        Text(
            stringResource(R.string.perm_body), style = FocalType.secondary, color = colors.recede, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Grid.x8),
        )
        FocalPrimaryPill(
            stringResource(if (shown == RationaleMode.Allow) R.string.perm_allow else R.string.perm_settings),
            onClick = {
                flow.dismiss()
                if (shown == RationaleMode.Allow) flow.allow() else flow.openDetails()
            },
            modifier = Modifier.fillMaxWidth().padding(top = Grid.x32),
            leading = if (shown == RationaleMode.Allow) { tint -> RecordDot(tint) } else null,
        )
        FocalTextButton(stringResource(R.string.perm_not_now), onClick = flow::dismiss, modifier = Modifier.padding(top = Grid.x8))
    }
}

/** The sheet's static mark (40 dp) sits 36 dp from its top (spec 4.19), under the grab handle (12 dp down, 4 dp tall). */
private val SheetMark = Grid.x40
private val SheetTop = Grid.x36 - SheetMetrics.handleTop - SheetMetrics.handleHeight

private fun Context.hasMicrophone(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
