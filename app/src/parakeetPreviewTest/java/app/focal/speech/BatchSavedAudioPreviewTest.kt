package app.focal.speech

import android.os.SystemClock
import android.util.AtomicFile
import app.focal.data.MAX_PASSAGE_EDIT_BYTES
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Private DEV batch. Build in the opt-in preview test source set only. */
class BatchSavedAudioPreviewTest {
    @Test fun evaluateSavedAudioPreviewBatch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = requireNotNull(InstrumentationRegistry.getArguments().getString("runId"))
        require(Regex("[a-z0-9-]{1,40}").matches(runId)) { "Invalid batch ID." }
        val root = File(instrumentation.targetContext.filesDir, "parakeet-batch/$runId").canonicalFile
        val inputDir = File(root, "inputs").canonicalFile
        val outputDir = File(root, "results")
        require(inputDir.parentFile == root && outputDir.mkdirs()) { "Batch input missing or output already exists." }
        val manifestBytes = File(root, "manifest.json").readBytes()
        val manifestHash = requireNotNull(InstrumentationRegistry.getArguments().getString("manifestSha256"))
        require(Regex("[0-9a-f]{64}").matches(manifestHash) && sha256(manifestBytes) == manifestHash) {
            "Manifest checksum mismatch."
        }
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        val cases = manifest.getJSONArray("cases")
        require(cases.length() == 18) { "Unexpected case count." }
        val modelDir = File(instrumentation.targetContext.filesDir, "parakeet-probe/model")
        val progress = AtomicFile(File(outputDir, "progress.json"))
        val status = JSONObject().put("status", "RUNNING").put("completed", 0)
        save(progress, status)
        try {
            val seen = HashSet<String>()
            var totalLoads = 0
            var totalDecodes = 0
            for (index in 0 until cases.length()) {
                val item = cases.getJSONObject(index)
                val id = item.getString("id")
                require(Regex("[a-z0-9_-]{1,100}").matches(id) && seen.add(id)) { "Invalid case ID." }
                status.put("case", id).put("phase", "validating")
                save(progress, status)
                require(item.getLong("frames") in 1L..480_000L) { "Invalid case length." }
                val file = File(inputDir, "$id.pcm").canonicalFile
                require(file.parentFile == inputDir && file.isFile && file.length() == item.getLong("frames") * 2) {
                    "Missing or invalid case file."
                }
                require(sha256(file.readBytes()) == item.getString("pcm_sha256")) { "Case checksum mismatch." }
                val recordFile = AtomicFile(File(outputDir, "$id.json"))
                val windows = JSONArray()
                val positions = JSONArray()
                val record = JSONObject().put("id", id).put("status", "RUNNING")
                    .put("frames", item.getLong("frames")).put("pcm_sha256", item.getString("pcm_sha256"))
                    .put("windows", windows).put("progress", positions).put("loads", 0)
                status.put("case", id).put("phase", "processing")
                save(progress, status)
                save(recordFile, record)
                val started = SystemClock.elapsedRealtimeNanos()
                try {
                    val segments = SavedAudioPreview.process(file, decoderFactory = { checkCancelled ->
                        require(id != "control-zero" && id != "control-dc") { "Constant control opened decoder." }
                        require(++totalLoads <= 16) { "Decoder load budget exceeded." }
                        record.put("loads", record.getInt("loads") + 1)
                        save(recordFile, record)
                        val engine = PreviewEngineFactory.open(modelDir, 2, checkCancelled)
                        object : SavedAudioPreview.Decoder {
                            override fun decode(samples: FloatArray): String {
                                require(++totalDecodes <= 23) { "Decode budget exceeded." }
                                val hash = MessageDigest.getInstance("SHA-256")
                                val block = ByteBuffer.allocate(8192).order(ByteOrder.BIG_ENDIAN)
                                for (sample in samples) {
                                    if (block.remaining() < 4) { hash.update(block.array(), 0, block.position()); block.clear() }
                                    block.putFloat(sample)
                                }
                                hash.update(block.array(), 0, block.position())
                                val began = SystemClock.elapsedRealtimeNanos()
                                val text = engine.decode(samples)
                                require(text.length <= MAX_PASSAGE_EDIT_BYTES) { "Decoder output too long." }
                                windows.put(JSONObject().put("frames", samples.size)
                                    .put("float32be_sha256", hex(hash.digest())).put("text", text)
                                    .put("decode_ns", SystemClock.elapsedRealtimeNanos() - began))
                                save(recordFile, record)
                                return text
                            }
                            override fun close() = engine.close()
                        }
                    }, onProgress = { covered, total ->
                        positions.put(JSONArray().put(covered).put(total))
                        status.put("covered_frames", covered).put("total_frames", total)
                        save(progress, status)
                    })
                    val output = JSONArray()
                    segments.forEach { output.put(JSONObject().put("start_ms", it.start).put("end_ms", it.end)
                        .put("uncertain", it.uncertain).put("text", it.text)) }
                    val text = segments.map { it.text }.filter(String::isNotBlank).joinToString("\n\n")
                    record.put("segments", output).put("text", text)
                        .put("empty_cues", segments.count { it.text.isBlank() })
                        .put("processing_elapsed_ns", SystemClock.elapsedRealtimeNanos() - started)
                        .put("memory_after_process", memorySnapshot()).put("status", "PASS")
                    save(recordFile, record)
                    status.put("completed", index + 1)
                    save(progress, status)
                } catch (error: Exception) {
                    record.put("status", "FAIL").put("error_type", error.javaClass.name)
                        .put("memory_after_process", memorySnapshot())
                    save(recordFile, record)
                    status.put("status", "FAIL").put("phase", "processing")
                    save(progress, status)
                    throw AssertionError("Private batch failed on case $index; inspect the private receipt.", error)
                }
            }
            status.put("status", "PASS").put("phase", "complete")
            save(progress, status)
        } catch (error: Exception) {
            status.put("status", "FAIL").put("error_type", error.javaClass.name)
            throw error
        } finally {
            save(progress, status)
        }
    }

    private fun save(file: AtomicFile, value: JSONObject) {
        val out = file.startWrite()
        try {
            out.write(value.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (error: Exception) {
            file.failWrite(out)
            throw error
        }
    }

    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun memorySnapshot(): JSONObject {
        val result = JSONObject().put("rss_kib", JSONObject.NULL).put("process_lifetime_peak_rss_kib", JSONObject.NULL)
        try {
            File("/proc/self/status").useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split(Regex("\\s+"))
                    val value = parts.getOrNull(1)?.toLongOrNull() ?: return@forEach
                    if (parts[0] == "VmRSS:") result.put("rss_kib", value)
                    if (parts[0] == "VmHWM:") result.put("process_lifetime_peak_rss_kib", value)
                }
            }
        } catch (_: Exception) {
            // The receipt keeps missing measurements as null.
        }
        return result
    }
}
