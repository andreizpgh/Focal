package app.focal.ui.components

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.focal.R
import app.focal.recording.StorageReserve
import app.focal.ui.state.WaitingReason

/**
 * What a waiting note says about why it is not being transcribed (spec 5.4). The note's notice ([page]) adds that the
 * audio is kept where the spec's sentence does not already promise more; the stage's last line, which has two lines at
 * most, and its spoken description keep the one sentence.
 */
@Composable
internal fun waitingText(reason: WaitingReason, page: Boolean): String = when (reason) {
    WaitingReason.Memory -> stringResource(R.string.model_failed)
    WaitingReason.Failures -> stringResource(if (page) R.string.engine_stopped else R.string.stage_engine_stopped)
    is WaitingReason.Storage -> {
        val amount = Formatter.formatShortFileSize(LocalContext.current, reason.shortBytes.coerceAtLeast(StorageReserve.STEP_BYTES))
        stringResource(if (page) R.string.model_storage else R.string.stage_model_storage, amount)
    }
    WaitingReason.Install -> stringResource(if (page) R.string.model_install_failed else R.string.stage_model_install_failed)
}
