package app.focal

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class ParakeetProbeTest {
    @Test fun offlineRecognizerRunsPublicSpeechAndNoiseControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "parakeet-probe")
        val receipts = File(root, "receipts")
        check(receipts.mkdirs() || receipts.isDirectory) { "Cannot create the private probe receipt directory." }
        val runId = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val receiptFile = AtomicFile(File(receipts, "$runId.json"))
        val cases = JSONArray()
        val receipt = JSONObject()
            .put("schema", 1)
            .put("run_id", runId)
            .put("status", "RUNNING")
            .put("model_repository", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8")
            .put("model_revision", MODEL_REVISION)
            .put("aar_sha256", AAR_SHA256)
            .put("device", deviceFacts(context))
            .put("cases", cases)
        var phase = "model_verification"
        fun enter(next: String) {
            phase = next
            receipt.put("phase", next)
            writeReceipt(receiptFile, receipt)
        }
        enter(phase)
        try {
            val directory = File(root.canonicalFile, "model")
            val files = verifiedFiles(directory)
            receipt.put("model_files", JSONArray(MODELS.map {
                JSONObject().put("name", it.name).put("bytes", it.bytes).put("sha256", it.sha256)
            }))

            enter("runtime_verification")
            val version = VersionInfo.version
            val revision = VersionInfo.gitSha1
            val ort = VersionInfo.onnxruntimeVersion
            receipt.put("runtime", JSONObject().put("sherpa_onnx", version).put("git_sha1", revision).put("onnxruntime", ort))
            check(version == "2.0.1" && revision.length >= 8 && SHERPA_REVISION.startsWith(revision) && ort == "1.28.2") {
                "The loaded Sherpa or ONNX Runtime identity does not match the probe."
            }
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80, dither = 0f),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = files.getValue("encoder.int8.onnx").absolutePath,
                        decoder = files.getValue("decoder.int8.onnx").absolutePath,
                        joiner = files.getValue("joiner.int8.onnx").absolutePath,
                    ),
                    tokens = files.getValue("tokens.txt").absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "nemo_transducer",
                    debug = false,
                ),
                decodingMethod = "greedy_search",
            )
            receipt.put("config", JSONObject()
                .put("sample_rate_hz", config.featConfig.sampleRate)
                .put("requested_feature_dim", config.featConfig.featureDim)
                .put("dither", config.featConfig.dither.toDouble())
                .put("provider", config.modelConfig.provider)
                .put("threads", config.modelConfig.numThreads)
                .put("model_type", config.modelConfig.modelType)
                .put("decoding_method", config.decodingMethod)
                .put("debug_logging", config.modelConfig.debug)
                .put("language_hint", JSONObject.NULL))
            receipt.put("memory_before_load", memorySnapshot())
            enter("model_load")
            val loadStart = SystemClock.elapsedRealtimeNanos()
            val recognizer = OfflineRecognizer(assetManager = null, config = config)
            try {
                receipt.put("load_ns", SystemClock.elapsedRealtimeNanos() - loadStart)
                receipt.put("memory_after_load", memorySnapshot())
                writeReceipt(receiptFile, receipt)
                enter("public_speech")
                val wav = instrumentation.context.assets.open("jfk.wav").use { it.readBytes() }
                check(wav.size == 352_044 && sha256(wav) == JFK_SHA256) { "The bundled public WAV fixture changed." }
                val speech = decode(recognizer, "jfk", wav.copyOfRange(44, wav.size))
                    .put("wav_sha256", JFK_SHA256)
                val words = Regex("[A-Za-z]+").findAll(speech.getString("text").lowercase(Locale.ROOT))
                    .map { it.value }.toList()
                val speechPassed = words.size >= 8 && "ask" in words && "country" in words
                cases.put(speech.put("passed", speechPassed))
                writeReceipt(receiptFile, receipt)

                enter("exact_zero")
                val zero = decode(recognizer, "exact_zero_30s", ByteArray(CONTROL_FRAMES * 2))
                val zeroPassed = zero.getString("text").isBlank()
                cases.put(zero.put("passed", zeroPassed))
                writeReceipt(receiptFile, receipt)

                enter("varying_noise")
                val noise = decode(recognizer, "varying_noise_30s", varyingNoise())
                    .put("generator", "xorshift32")
                    .put("seed", NOISE_SEED)
                    .put("amplitude_pcm16", NOISE_AMPLITUDE)
                val noisePassed = noise.getString("text").isBlank()
                cases.put(noise.put("passed", noisePassed))
                writeReceipt(receiptFile, receipt)

                phase = "case_assertions"
                check(speechPassed) { "The public speech fixture did not produce substantive English." }
                check(zeroPassed) { "The exact-zero control produced nonblank text." }
                check(noisePassed) { "The varying-noise control produced nonblank text." }
            } finally {
                // Sherpa's API has no cancellation. Release only after the synchronous call returns.
                recognizer.release()
            }
            receipt.put("memory_after_release", memorySnapshot())
            receipt.put("status", "PASS").put("phase", "complete")
        } catch (failure: Throwable) {
            receipt.put("status", "FAIL").put("phase", phase)
                .put("error_type", failure.javaClass.name)
                .put("error_message", failure.message ?: "")
            // Keep raw diagnostics and recognizer output out of instrumentation logs.
            throw AssertionError("Parakeet probe failed during $phase. Private receipt: $runId.json")
        } finally {
            writeReceipt(receiptFile, receipt)
        }
    }

    private fun decode(recognizer: OfflineRecognizer, id: String, pcm: ByteArray): JSONObject {
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(input.remaining()) { input.get() / 32768f }
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, RATE)
            val started = SystemClock.elapsedRealtimeNanos()
            recognizer.decode(stream)
            val decodeNs = SystemClock.elapsedRealtimeNanos() - started
            val result = recognizer.getResult(stream)
            return JSONObject()
                .put("id", id)
                .put("frames", samples.size)
                .put("audio_seconds", samples.size.toDouble() / RATE)
                .put("pcm16_sha256", sha256(pcm))
                .put("decode_ns", decodeNs)
                .put("memory_after_decode", memorySnapshot())
                .put("text", result.text)
                .put("reported_language", result.lang)
                .put("tokens", JSONArray(result.tokens.toList()))
                .put("timestamps_seconds", floatArrayJson(result.timestamps))
                .put("token_durations_seconds", floatArrayJson(result.durations))
        } finally {
            stream.release()
        }
    }

    private fun verifiedFiles(directory: File): Map<String, File> {
        check(directory.isDirectory && directory.canonicalFile == directory) { "Missing private Parakeet model directory." }
        return MODELS.associate { model ->
            val file = File(directory, model.name)
            check(file.isFile && file.canonicalFile == file && file.length() == model.bytes) {
                "Missing or wrong-sized model file: ${model.name}"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(hex(digest.digest()) == model.sha256) { "Model checksum mismatch: ${model.name}" }
            model.name to file
        }
    }

    private fun varyingNoise(): ByteArray {
        val pcm = ByteBuffer.allocate(CONTROL_FRAMES * 2).order(ByteOrder.LITTLE_ENDIAN)
        var state = NOISE_SEED
        repeat(CONTROL_FRAMES) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val sample = Integer.remainderUnsigned(state, 2 * NOISE_AMPLITUDE + 1) - NOISE_AMPLITUDE
            pcm.putShort(sample.toShort())
        }
        return pcm.array()
    }

    private fun deviceFacts(context: Context): JSONObject {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val power = context.getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("api", Build.VERSION.SDK_INT)
            .put("android_version", Build.VERSION.RELEASE)
            .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("process_64bit", Process.is64Bit())
            .put("logical_processors", Runtime.getRuntime().availableProcessors())
            .put("total_ram_bytes", memory.totalMem)
            .put("power_save_mode", power.isPowerSaveMode)
            .put("thermal_status_at_start",
                if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else JSONObject.NULL)
    }

    private fun memorySnapshot(): JSONObject {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val stats = runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.mapNotNull { MEMORY_LINE.matchEntire(it) }
                    .associate { it.groupValues[1] to it.groupValues[2].toLong() }
            }
        }.getOrDefault(emptyMap())
        return JSONObject()
            .put("total_pss_kib", memory.totalPss)
            .put("rss_kib", stats["VmRSS"] ?: JSONObject.NULL)
            .put("process_lifetime_peak_rss_kib", stats["VmHWM"] ?: JSONObject.NULL)
    }

    private fun writeReceipt(file: AtomicFile, receipt: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(receipt.toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun floatArrayJson(values: FloatArray) =
        JSONArray(values.map { if (it.isFinite()) it.toDouble() else it.toString() })

    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private data class ModelFile(val name: String, val bytes: Long, val sha256: String)

    private companion object {
        const val RATE = 16_000
        const val CONTROL_FRAMES = 30 * RATE
        const val NOISE_SEED = 0xF0CA1
        const val NOISE_AMPLITUDE = 128
        const val MODEL_REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
        const val SHERPA_REVISION = "af2d8d81dbfa61c4cea4c568da78f3982edca005"
        const val AAR_SHA256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
        const val JFK_SHA256 = "d7d4e74b8a333ed02186008bc109a1b1a19d16da668bd56e785d80d69a16a72f"
        val MEMORY_LINE = Regex("(VmRSS|VmHWM):\\s+(\\d+) kB")
        val MODELS = listOf(
            ModelFile("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
            ModelFile("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
            ModelFile("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
            ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        )
    }
}
