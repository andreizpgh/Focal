package app.focal

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.security.SecureRandom

/**
 * Target of the "New recording" launcher shortcut and home-screen widget. Not exported: only Focal or its own
 * immutable PendingIntents/launcher shortcuts can launch it. The request travels in memory via [ShortcutRecord]
 * bound to a random single-use nonce carried on the intent, so no external caller spoofing [MainActivity] can trigger
 * microphone capture.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nonce = ShortcutRecord.request()
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_FROM_SHORTCUT)
                .putExtra(MainActivity.EXTRA_SHORTCUT_NONCE, nonce)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
        finish()
    }
}

/** A Record request handed to [MainActivity] in memory, bound to a single-use non-zero nonce. */
internal object ShortcutRecord {
    /** A request older than this (the activity never came up) is dropped rather than recording later. */
    internal const val VALID_MS = 10_000L
    private val random = SecureRandom()
    @Volatile private var requestedAt = 0L
    private var pendingNonce = 0L

    fun request(): Long = requestAt(SystemClock.elapsedRealtime())

    @Synchronized internal fun requestAt(nowMs: Long): Long {
        var nonce: Long
        do {
            nonce = random.nextLong()
        } while (nonce == 0L)
        pendingNonce = nonce
        requestedAt = nowMs
        return nonce
    }

    /** True once for a matching [nonce] requested within the last few seconds. */
    fun consume(nonce: Long): Boolean = consumeAt(nonce, SystemClock.elapsedRealtime())

    @Synchronized internal fun consumeAt(nonce: Long, nowMs: Long): Boolean {
        if (nonce == 0L) return false
        val at = requestedAt
        val expected = pendingNonce
        if (at == 0L || expected == 0L) return false
        val elapsed = nowMs - at
        if (elapsed < 0L || elapsed >= VALID_MS) {
            requestedAt = 0L
            pendingNonce = 0L
            return false
        }
        if (nonce != expected) return false
        requestedAt = 0L
        pendingNonce = 0L
        return true
    }

    @Synchronized internal fun clearForTests() {
        requestedAt = 0L
        pendingNonce = 0L
    }
}
