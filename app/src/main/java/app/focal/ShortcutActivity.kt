package app.focal

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock

/**
 * Target of the "New recording" launcher shortcut. It is not exported: the launcher starts shortcuts with
 * Focal's own identity, but no other app can start it, so no other app can make Focal record. The request
 * reaches [MainActivity] through process memory ([ShortcutRecord]), never through an intent that another app
 * could send to the exported activity.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShortcutRecord.request()
        startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_FROM_SHORTCUT)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

/** A Record request from the launcher shortcut, handed to [MainActivity] in memory and used at most once. */
internal object ShortcutRecord {
    /** A request older than this (the activity never came up) is dropped rather than recording later. */
    private const val VALID_MS = 10_000L
    @Volatile private var requestedAt = 0L

    fun request() { requestedAt = SystemClock.elapsedRealtime() }

    /** True once for a request made within the last few seconds. */
    @Synchronized fun consume(): Boolean {
        val at = requestedAt
        requestedAt = 0L
        return at != 0L && SystemClock.elapsedRealtime() - at < VALID_MS
    }
}
