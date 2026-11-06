package app.focal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.recording.ServiceEvent
import app.focal.recording.StartFailure
import app.focal.ui.FocalApp
import app.focal.ui.design.FocalColors
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalSettle
import app.focal.ui.shell.NightMode
import app.focal.ui.shell.ShellCommand
import app.focal.ui.state.EntryRequest
import app.focal.ui.state.EntryRequests
import app.focal.ui.state.ThemeMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import app.focal.ui.shell.FocalApp as RebuiltApp

class MainActivity : ComponentActivity() {
    private val openedNote = MutableStateFlow<Pair<String?, Long>>(null to 0)
    private val shellCommands = MutableSharedFlow<ShellCommand>(extraBufferCapacity = 1)

    /** API 26-30: the theme setting reaches values-night before any resource is read (no flash on a cold start). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val preferences = (newBase.applicationContext as FocalApplication).preferences
        NightMode.legacyOverride(ThemeMode.of(preferences.settings.value.theme))?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // The splash leaves on the first frame with `fadeFast` (spec 5.13).
        splash.setOnExitAnimationListener { exit ->
            exit.view.animate().alpha(0f)
                .setDuration(FocalSettle.FADE_FAST_MS)
                .setInterpolator(FocalMotion.springInterpolator(FocalMotion.EFFECTS_DAMPING, FocalMotion.FADE_FAST_STIFFNESS, FocalSettle.FADE_FAST_MS))
                .withEndAction { exit.remove() }
                .start()
        }
        val preferences = (application as FocalApplication).preferences
        // Before the first frame; later changes follow the setting (the flow's first value is this one).
        applyTheme(ThemeMode.of(preferences.settings.value.theme))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                preferences.settings.map { ThemeMode.of(it.theme) }.distinctUntilChanged().drop(1).collect(::applyTheme)
            }
        }
        // The saved Compose destination already reflects any previously handled
        // notification. Replaying the launch intent would override it on rotation.
        if (savedInstanceState == null) handle(intent)
        setContent {
            if (BuildConfig.NEW_UI) RebuiltApp(preferences, shellCommands)
            else { val model: FocalViewModel = viewModel(); FocalApp(model, openedNote) }
        }
    }

    /**
     * Edge-to-edge with transparent bars whose icons follow the app's setting (not the system's), the system's own
     * night mode for the next splash, and the window background for gaps before Compose draws.
     */
    private fun applyTheme(mode: ThemeMode) {
        val dark = NightMode.isDark(mode)
        NightMode.applyToSystem(this, mode)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        window.setBackgroundDrawable((if (dark) FocalColors.Dark else FocalColors.Light).paper.toArgb().toDrawable())
    }

    /** Ctrl+, opens Settings with no focused node needed (spec 4.23.6). */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (BuildConfig.NEW_UI && keyCode == KeyEvent.KEYCODE_COMMA && event.isCtrlPressed) {
            shellCommands.tryEmit(ShellCommand.OpenSettings)
            return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    /** Esc is Back wherever the screen has nothing of its own to close (spec 8.5). */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (BuildConfig.NEW_UI && keyCode == KeyEvent.KEYCODE_ESCAPE && !event.isCanceled) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Lists Ctrl+, in the system's keyboard-shortcuts helper (Meta+/). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        if (!BuildConfig.NEW_UI) return
        data += KeyboardShortcutGroup(
            getString(R.string.kbd_group),
            listOf(KeyboardShortcutInfo(getString(R.string.kbd_open_settings), KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON)),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // Only the non-exported ShortcutActivity asks for this, in memory: no intent another app can send to
        // this exported activity starts the microphone.
        if (ShortcutRecord.consume()) recordFromShortcut()
        // Transcription that waits (process death, Android's background limit) continues only from here.
        (application as FocalApplication).resumeWaitingTranscription(this)
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            // The shortcut's request itself travels in memory (ShortcutRecord), handled in onResume.
            ACTION_FROM_SHORTCUT -> Unit
            ACTION_SHOW_RECORDING -> {
                val id = intent.getStringExtra(RecordingService.NOTE)
                EntryRequests.post(EntryRequest.ShowRecording(id))
                openFromIntent(intent)
            }
            else -> {
                intent.getStringExtra(RecordingService.NOTE)?.let { EntryRequests.post(EntryRequest.OpenNote(it)) }
                openFromIntent(intent)
            }
        }
    }

    /**
     * "New recording": an existing recording is shown, never a second one started. With the microphone
     * permission the service starts here, from the visible activity; without it the UI asks first.
     */
    private fun recordFromShortcut() {
        val live = RecordingState.live.value
        if (live.capturing) {
            EntryRequests.post(EntryRequest.ShowRecording(live.noteId))
            openedNote.value = live.noteId to (openedNote.value.second + 1)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            EntryRequests.post(EntryRequest.Record)
            return
        }
        try {
            RecordingService.command(this, RecordingService.START)
            EntryRequests.post(EntryRequest.ShowRecording(null))
        } catch (error: Exception) {
            RecordingState.events.tryEmit(ServiceEvent.StartFailed(StartFailure.OTHER, error.message ?: "Could not start recording."))
        }
    }

    private fun openFromIntent(intent: Intent) {
        openedNote.value = intent.getStringExtra(RecordingService.NOTE) to (openedNote.value.second + 1)
    }

    companion object {
        /** API 26-28 keep a translucent scrim behind three-button navigation (spec 9); 29+ draw none. */
        private val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

        /** Sent by [ShortcutActivity] ("New recording", res/xml/shortcuts.xml); carries no request itself. */
        const val ACTION_FROM_SHORTCUT = "app.focal.action.FROM_SHORTCUT"
        /** The recording notification: show the recording screen. */
        const val ACTION_SHOW_RECORDING = "app.focal.action.SHOW_RECORDING"
    }
}
