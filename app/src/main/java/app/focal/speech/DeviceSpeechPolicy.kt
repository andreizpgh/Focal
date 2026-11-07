package app.focal.speech

import android.content.Context
import android.os.Build
import android.os.PowerManager
import app.focal.core.DecodePolicy
import java.io.File

/** The device facts behind the recognizer's thread count, read when a recognizer is created. */
internal object DeviceSpeechPolicy {
    /** Cores outside the slowest cluster, from each core's maximum frequency in sysfs. */
    val performanceCores: Int by lazy {
        val available = Runtime.getRuntime().availableProcessors()
        val frequencies = (0 until available).map { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        DecodePolicy.performanceCores(frequencies, available)
    }

    /** True under a thermal status of moderate or worse (API 29+). */
    fun warm(context: Context): Boolean = Build.VERSION.SDK_INT >= 29 &&
        context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

    fun powerSave(context: Context): Boolean = context.getSystemService(PowerManager::class.java).isPowerSaveMode

    /** min(4, performance cores); 2 under power-save or thermal pressure right now. */
    fun recognizerThreads(context: Context): Int =
        DecodePolicy.threads(performanceCores, powerSave(context), warm(context))
}
