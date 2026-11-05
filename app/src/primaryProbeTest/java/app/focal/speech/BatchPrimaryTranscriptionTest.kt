package app.focal.speech

import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.BuildConfig
import app.focal.FocalApplication
import app.focal.MainActivity
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.recording.SessionOperations
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Explicit private-corpus probe. Excluded from ordinary test and release builds. */
class BatchPrimaryTranscriptionTest {
    @Test fun evaluateDurablePrimaryPipeline() {
        check(BuildConfig.FOCAL_ULTRA_BUNDLED)
        val app = ApplicationProvider.getApplicationContext<FocalApplication>()
        runBlocking { app.storageReady.await() }
        val args = InstrumentationRegistry.getArguments()
        val runId = requireNotNull(args.getString("runId"))
        require(Regex("[a-z0-9-]{1,40}").matches(runId))
        val root = File(app.filesDir, "primary-batch/$runId").canonicalFile
        val inputs = File(root, "inputs").canonicalFile
        val outputs = File(root, "results")
        check(inputs.parentFile == root && outputs.mkdirs())
        val manifestFile = File(root, "manifest.json")
        require(manifestFile.length() in 1L..65_536L)
        check(sha(manifestFile) == args.getString("manifestSha256"))
        val cases = JSONObject(manifestFile.readText()).getJSONArray("cases")
        require(cases.length() in 1..64 && cases.length() == args.getString("caseCount")?.toInt())
        val seen = HashSet<String>()
        val status = JSONObject().put("status", "RUNNING").put("completed", 0)
        val progress = File(outputs, "progress.json")
        save(progress, status)
        ActivityScenario.launch(MainActivity::class.java).use {
            for (index in 0 until cases.length()) {
                val item = cases.getJSONObject(index)
                val id = item.getString("id")
                require(Regex("[a-z0-9_-]{1,100}").matches(id) && seen.add(id))
                val source = File(inputs, "$id.pcm").canonicalFile
                val frames = item.getLong("frames")
                require(frames in 16L..(600L * 16_000))
                require(frames % 16L == 0L) { "The paired corpus must end on a full millisecond." }
                check(source.parentFile == inputs && source.length() == frames * 2 && sha(source) == item.getString("pcm_sha256"))
                check(RecordingState.live.value.noteId == null)
                val note = app.store.create("auto", "ultra-v1")
                val record = JSONObject().put("id", id).put("status", "RUNNING")
                    .put("frames", frames).put("pcm_sha256", sha(source)).put("private_fixture_note_id", note)
                val result = File(outputs, "$id.json")
                status.put("case", id)
                save(progress, status)
                try {
                    source.inputStream().use { input -> app.store.audioFile(note).outputStream().use { output -> input.copyTo(output) } }
                    app.store.state(note, "INTERRUPTED", "Private primary-pipeline fixture")
                    val started = SystemClock.elapsedRealtime()
                    RecordingService.command(app, RecordingService.RECOVER, note)
                    val deadline = started + 240_000
                    var began = false
                    while (true) {
                        val current = checkNotNull(app.store.note(note))
                        if (RecordingState.live.value.noteId == note) began = true
                        val idle = RecordingState.live.value.noteId == null &&
                            !SessionOperations.matchesSession(capture = false, noteId = note)
                        if (current.state == "READY" && idle) break
                        check(!(began && idle && current.state == "INTERRUPTED")) { current.error }
                        check(SystemClock.elapsedRealtime() < deadline) { "Primary case exceeded its time budget." }
                        SystemClock.sleep(100)
                    }
                    check(sha(app.store.audioFile(note)) == item.getString("pcm_sha256"))
                    val chunks = app.store.chunks(note)
                    check(chunks.isNotEmpty() && chunks.all { it.state == "DONE" })
                    check(chunks.first().start == 0L && chunks.last().end == frames / 16)
                    chunks.zipWithNext().forEach { (a, b) -> check(b.start == a.nextStart && b.start <= a.end) }
                    val windows = JSONArray()
                    chunks.forEach { chunk ->
                        windows.put(JSONObject().put("start_ms", chunk.start).put("end_ms", chunk.end)
                            .put("next_start_ms", chunk.nextStart).put("overlap", chunk.overlap)
                            .put("pcm_sha256", hex(MessageDigest.getInstance("SHA-256").digest(app.store.readChunk(chunk)))))
                    }
                    val passages = app.store.passages(note)
                    val segments = JSONArray()
                    passages.forEach { p -> segments.put(JSONObject().put("start_ms", p.start).put("end_ms", p.end)
                        .put("text", p.text).put("uncertain", p.uncertain)) }
                    record.put("status", "PASS").put("windows", windows).put("segments", segments)
                        .put("text", passages.filter { !it.excluded && it.text.isNotBlank() }.joinToString("\n\n") { p -> p.text })
                        .put("empty_cues", passages.count { p -> p.text.isBlank() })
                        .put("processing_elapsed_ms", SystemClock.elapsedRealtime() - started)
                        .put("speech_engine", app.store.note(note)!!.speechEngine)
                    save(result, record)
                    status.put("completed", index + 1)
                    save(progress, status)
                } catch (error: Exception) {
                    record.put("status", "FAIL").put("error_type", error.javaClass.name)
                    save(result, record)
                    status.put("status", "FAIL")
                    save(progress, status)
                    throw error
                } finally {
                    // Never remove an original while its worker could still be reading it.
                    if (record.optString("status") == "PASS" && RecordingState.live.value.noteId != note &&
                        !SessionOperations.matchesSession(capture = false, noteId = note)) app.store.delete(note)
                }
            }
        }
        status.put("status", "PASS")
        save(progress, status)
    }

    private fun save(path: File, value: JSONObject) {
        val file = AtomicFile(path)
        val output = file.startWrite()
        try { output.write(value.toString().toByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
