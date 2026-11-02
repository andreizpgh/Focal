package app.focal.core

/** Resource limits shared by live and recovered transcription. */
object DecodePolicy {
    fun threads(available: Int, powerSave: Boolean, warm: Boolean): Int = when {
        powerSave -> 1
        warm -> minOf(available, 2).coerceAtLeast(1)
        else -> (available - 1).coerceIn(1, 4)
    }

    // Cool down under actual pressure, never add a fixed delay to every result.
    fun cooldownMs(decodeMs: Long, powerSave: Boolean, warm: Boolean): Long =
        if (powerSave || warm) (decodeMs / 4).coerceIn(150, 3000) else 0
}
