package app.focal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.ui.design.FocalMotion
import app.focal.ui.shell.FocalApp
import app.focal.ui.shell.FocalWindow
import app.focal.ui.shell.NightMode
import app.focal.ui.shell.PageKey
import app.focal.ui.shell.PageKeys
import app.focal.ui.shell.ShellCommand
import app.focal.ui.state.EntryRequest
import app.focal.ui.state.EntryRequests
import app.focal.ui.state.LibraryViewModel
import app.focal.ui.state.RecordingViewModel
import app.focal.ui.state.ThemeMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val shellCommands = MutableSharedFlow<ShellCommand>(extraBufferCapacity = 1)
    /** The note page's shortcuts, heard here when no focused node took them (spec 8.5). */
    private val pageKeys = PageKeys()
    /** The same view model the interface uses: a start from here is STARTING there too (the tick, spec 5.13). */
    private val recording: RecordingViewModel by viewModels()
    /** Shared with Compose so SQLite loading starts in onCreate and the splash reveals the real first frame. */
    private val library: LibraryViewModel by viewModels()

    /** API 26-30: the theme setting reaches values-night before any resource is read (no flash on a cold start). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val preferences = (newBase.applicationContext as FocalApplication).preferences
        NightMode.legacyOverride(ThemeMode.of(preferences.settings.value.theme))?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val createdAt = SystemClock.uptimeMillis()
        // Touch library eagerly so SQLite loads in parallel with Compose setup; hold splash briefly until ready.
        library
        splash.setKeepOnScreenCondition {
            !library.state.value.loaded && SystemClock.uptimeMillis() - createdAt < SPLASH_MAX_HOLD_MS
        }
        // Quiet paper-only reveal once the first real library frame is ready: no logo flash, no artificial hold.
        splash.setOnExitAnimationListener { exit ->
            val animScale = runCatching {
                Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
            if (animScale <= 0f) {
                exit.remove()
                return@setOnExitAnimationListener
            }
            val duration = SPLASH_EXIT_MS
            val interpolator = FocalMotion.springInterpolator(FocalMotion.EFFECTS_DAMPING, FocalMotion.FADE_FAST_STIFFNESS, duration)
            exit.view.animate()
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(interpolator)
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
        setContent { FocalApp(preferences, shellCommands, pageKeys) }
    }

    private fun applyTheme(mode: ThemeMode) = FocalWindow.apply(this, mode)

    /**
     * Ctrl+, opens Settings with no focused node needed (spec 4.23.6); Ctrl+F, Ctrl+C, Ctrl+Z and Ctrl+Shift+Z go to
     * the page on top (8.5), which takes them only where they apply.
     */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            val key = when (keyCode) {
                KeyEvent.KEYCODE_COMMA -> {
                    shellCommands.tryEmit(ShellCommand.OpenSettings)
                    return true
                }
                KeyEvent.KEYCODE_F -> PageKey.Find
                KeyEvent.KEYCODE_C -> PageKey.Copy
                KeyEvent.KEYCODE_Z -> if (event.isShiftPressed) PageKey.Redo else PageKey.Undo
                else -> null
            }
            if (key != null && pageKeys.dispatch(key)) return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    /** Space plays or pauses the note being read when no focused control took it (spec 8.5). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SPACE && event.hasNoModifiers() && event.repeatCount == 0 && pageKeys.dispatch(PageKey.PlayPause)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Esc is Back wherever the screen has nothing of its own to close (spec 8.5). */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && !event.isCanceled) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Lists the shortcuts in the system's keyboard-shortcuts helper (Meta+/). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        fun shortcut(label: Int, keyCode: Int, modifiers: Int) = KeyboardShortcutInfo(getString(label), keyCode, modifiers)
        data += KeyboardShortcutGroup(
            getString(R.string.kbd_group),
            listOf(
                shortcut(R.string.kbd_open_settings, KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON),
                shortcut(R.string.kbd_play, KeyEvent.KEYCODE_SPACE, 0),
                shortcut(R.string.kbd_find, KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON),
                shortcut(R.string.kbd_copy, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON),
                shortcut(R.string.kbd_undo, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON),
                shortcut(R.string.kbd_redo, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
            ),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
        // Quick Settings tile taps while MainActivity is already resumed behind the shade may deliver onNewIntent directly.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            consumeShortcut(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // Only our internal non-exported ShortcutActivity or RecordTileService can pair this extra with an in-memory nonce.
        consumeShortcut(intent)
        // Transcription that waits (process death, Android's background limit) continues only from here.
        (application as FocalApplication).resumeWaitingTranscription(this)
    }

    private fun consumeShortcut(target: Intent?) {
        val source = target ?: return
        if (source.action != ACTION_FROM_SHORTCUT) return
        val nonce = source.getLongExtra(EXTRA_SHORTCUT_NONCE, 0L)
        source.removeExtra(EXTRA_SHORTCUT_NONCE)
        if (ShortcutRecord.consume(nonce)) recordFromShortcut()
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            // The shortcut's request itself travels in memory (ShortcutRecord), matched by nonce in onResume/onNewIntent.
            ACTION_FROM_SHORTCUT -> Unit
            ACTION_SHOW_RECORDING -> EntryRequests.post(EntryRequest.ShowRecording(intent.getStringExtra(RecordingService.NOTE)))
            else -> intent.getStringExtra(RecordingService.NOTE)?.let { EntryRequests.post(EntryRequest.OpenNote(it)) }
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
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            EntryRequests.post(EntryRequest.Record)
            return
        }
        // Through the interface's own Record: the start is STARTING until the service confirms it, so the tick and
        // the notification permission follow exactly as for Record in the app. A failure arrives as StartFailed.
        recording.record()
        if (recording.starting) EntryRequests.post(EntryRequest.ShowRecording(null))
    }

    companion object {
        /** Sent by [ShortcutActivity] and [app.focal.recording.RecordTileService]; bound to [EXTRA_SHORTCUT_NONCE]. */
        const val ACTION_FROM_SHORTCUT = "app.focal.action.FROM_SHORTCUT"
        const val EXTRA_SHORTCUT_NONCE = "app.focal.extra.SHORTCUT_NONCE"
        /** The recording notification: show the recording screen. */
        const val ACTION_SHOW_RECORDING = "app.focal.action.SHOW_RECORDING"
        /** Hard ceiling for holding the system splash while SQLite emits the first library frame. */
        private const val SPLASH_MAX_HOLD_MS = 250L
        private const val SPLASH_EXIT_MS = 120L
    }
}
