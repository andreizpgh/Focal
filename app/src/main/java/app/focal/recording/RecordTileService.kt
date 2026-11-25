package app.focal.recording

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.focal.MainActivity
import app.focal.R
import app.focal.ShortcutRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Quick Settings tile ("Record"): queries live capture state in [onStartListening] so process crashes or restarts
 * never leave a stale "Recording" label in SystemUI, and updates while the shade stays open.
 */
class RecordTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile(RecordingState.live.value)
        listenJob?.cancel()
        listenJob = scope.launch {
            RecordingState.live
                .map { it.capturing to it.paused }
                .distinctUntilChanged()
                .drop(1)
                .collect { updateTile(RecordingState.live.value) }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateTile(session: LiveSession) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        if (session.capturing) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(if (session.paused) R.string.n_public_paused else R.string.n_recording)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.action_record)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(R.string.app_name)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::launchRecorder) else launchRecorder()
    }

    private fun launchRecorder() {
        val nonce = ShortcutRecord.request()
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_FROM_SHORTCUT)
            .putExtra(MainActivity.EXTRA_SHORTCUT_NONCE, nonce)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this,
                TILE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            launchLegacy(intent)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    private companion object {
        const val TILE_REQUEST_CODE = 302
    }
}
