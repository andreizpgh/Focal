package app.focal.core

/** Resource limits shared by live and recovered transcription. */
object DecodePolicy {
    /** Upper bound on recognizer threads, also on phones with many performance cores. */
    const val MAX_THREADS = 4

    /** Thread count under power-save mode or a thermal status of moderate or worse. */
    const val CONSTRAINED_THREADS = 2

    /**
     * Recognizer threads, fixed when the recognizer is created: min(4, performance cores), and at most 2
     * under power-save mode or thermal pressure ([warm]) at that moment.
     */
    fun threads(performanceCores: Int, powerSave: Boolean, warm: Boolean): Int {
        val base = performanceCores.coerceIn(1, MAX_THREADS)
        return if (powerSave || warm) minOf(base, CONSTRAINED_THREADS) else base
    }

    /**
     * The cores outside the slowest cluster, from each core's maximum frequency (null when unreadable).
     * A phone whose cores all share one maximum frequency counts all of them. When any frequency is unknown,
     * half of the [available] cores are assumed to be performance cores.
     */
    fun performanceCores(maxFrequencies: List<Long?>, available: Int): Int {
        val known = maxFrequencies.filterNotNull().filter { it > 0 }
        if (known.isEmpty() || known.size != maxFrequencies.size) return (available / 2).coerceAtLeast(1)
        val slowest = known.min()
        val faster = known.count { it > slowest }
        return if (faster == 0) known.size else faster
    }

    // Cool down under actual pressure, never add a fixed delay to every result.
    fun cooldownMs(decodeMs: Long, powerSave: Boolean, warm: Boolean): Long =
        if (powerSave || warm) (decodeMs / 4).coerceIn(150, 3000) else 0
}
