package app.focal.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.VisibleForTesting
import androidx.core.database.sqlite.transaction
import app.focal.core.Transcript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID

internal const val MAX_PASSAGE_EDIT_BYTES = 128 * 1024
internal fun fitsPassageDraftBudget(text: String): Boolean =
    text.length <= MAX_PASSAGE_EDIT_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_PASSAGE_EDIT_BYTES

class PassageSizeException(message: String) : IllegalStateException(message)

/** Every new note is transcribed by Parakeet Ultra, which detects the language itself. */
const val ULTRA_ENGINE = "ultra-v1"
const val AUTOMATIC_LANGUAGE = "auto"
/**
 * Notes recorded before the single-engine rebuild. Their text, edits and audio stay as saved;
 * any range they left PENDING is now transcribed by Ultra through the same chunk queue.
 * The app never creates such notes; only migration and recovery tests do (createLegacy).
 */
internal const val LEGACY_WHISPER_ENGINE = "whisper"

data class Note(val id: String, val title: String, val created: Long, val language: String,
                val state: String, val duration: Long, val error: String, val preview: String = "", val reviewCount: Int = 0,
                val speechEngine: String = ULTRA_ENGINE)
internal val Note.mayNeedRecovery: Boolean get() = state == "INTERRUPTED" || state == "ERROR"

data class Chunk(val id: String, val noteId: String, val start: Long, val end: Long,
                 val overlap: Boolean, val state: String, val nextStart: Long? = null)
data class Passage(val id: Long = 0, val noteId: String, val chunkId: String, val start: Long,
                   val end: Long, val text: String, val uncertain: Boolean, val reviewed: Boolean = false,
                   val originalTranscriptText: String? = null, val excluded: Boolean = false)

internal fun transferableTranscript(passages: List<Passage>): String = passages.asSequence()
    .filter { !it.excluded && it.text.isNotBlank() }.joinToString("\n\n") { it.text }

class NoteStore(context: Context) : SQLiteOpenHelper(context, "focal.db", null, 4) {
    private companion object {
        const val BYTES_PER_MS = 32L
        const val MIN_RECOVERABLE_MS = 20L
        const val MAX_CHUNK_MS = 30_000L
        const val RECOVERY_OVERLAP_MS = 500L
        const val MAX_LEGACY_PASSAGE_BYTES = 16 * 1024 * 1024
        const val TEXT_BLOCK_BYTES = 64 * 1024
        const val PASSAGE_COLUMNS = "id,note_id,chunk_id,start,end,uncertain,reviewed,excluded," +
            "CASE WHEN length(CAST(text AS BLOB)) <= $MAX_PASSAGE_EDIT_BYTES THEN text END AS safe_text"
        const val ORIGINAL_COLUMNS = "length(CAST(original_transcript_text AS BLOB)) AS original_size," +
            "original_transcript_text=text AS original_matches_current," +
            "CASE WHEN original_transcript_text!=text AND length(CAST(original_transcript_text AS BLOB)) <= $MAX_PASSAGE_EDIT_BYTES " +
            "THEN original_transcript_text END AS safe_original_text"
    }
    val changes = MutableStateFlow(0L)
    val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,
            language TEXT NOT NULL,state TEXT NOT NULL,duration INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '',
            automatic_title_chunk TEXT,speech_engine TEXT NOT NULL DEFAULT 'whisper')""")
        db.execSQL("""CREATE TABLE chunks(id TEXT PRIMARY KEY,note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
            start INTEGER NOT NULL,end INTEGER NOT NULL,overlap INTEGER NOT NULL,state TEXT NOT NULL,next_start INTEGER)""")
        db.execSQL("""CREATE TABLE passages(id INTEGER PRIMARY KEY AUTOINCREMENT,note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
            chunk_id TEXT NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,start INTEGER NOT NULL,end INTEGER NOT NULL,
            text TEXT NOT NULL,uncertain INTEGER NOT NULL,reviewed INTEGER NOT NULL DEFAULT 0,
            original_transcript_text TEXT,excluded INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX chunks_queue ON chunks(note_id,state,start)")
        db.execSQL("CREATE INDEX passages_note ON passages(note_id,start,id)")
        createDeletionJournal(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..3 && newVersion in (oldVersion + 1)..4) { "Missing database migration" }
        if (oldVersion == 1) createDeletionJournal(db)
        if (oldVersion < 3 && newVersion >= 3) {
            // Older text may already include manual edits. Its original provenance is unknown.
            db.execSQL("ALTER TABLE passages ADD COLUMN original_transcript_text TEXT")
            db.execSQL("ALTER TABLE passages ADD COLUMN excluded INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN automatic_title_chunk TEXT")
        }
        if (oldVersion < 4 && newVersion >= 4) {
            // Existing notes retain their original decoder, including unfinished audio.
            db.execSQL("ALTER TABLE notes ADD COLUMN speech_engine TEXT NOT NULL DEFAULT 'whisper'")
            db.execSQL("ALTER TABLE chunks ADD COLUMN next_start INTEGER")
        }
    }
    private fun createDeletionJournal(db: SQLiteDatabase) {
        // No foreign key: the intent must survive deleting the corresponding note.
        db.execSQL("CREATE TABLE pending_audio_deletions(note_id TEXT PRIMARY KEY)")
    }
    private fun changed() { changes.update { it + 1 } }
    /** Every new note is an Ultra note; Ultra detects the language itself. */
    fun create(): String = insertNote(AUTOMATIC_LANGUAGE, ULTRA_ENGINE)
    /**
     * A note as Whisper-era builds saved it. Capture no longer publishes ranges for such a note,
     * so only tests that build legacy fixtures (with beginChunk/finishChunk) may create one.
     */
    @VisibleForTesting
    internal fun createLegacy(language: String): String = insertNote(language, LEGACY_WHISPER_ENGINE)
    private fun insertNote(language: String, speechEngine: String): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("notes", null, ContentValues().apply {
            put("id", id); put("title", "Untitled note"); put("created", System.currentTimeMillis())
            put("language", language); put("state", "RECORDING"); put("speech_engine", speechEngine)
        }); changed(); return id
    }
    fun notes(query: String = ""): List<Note> {
        val notes = readableDatabase.rawQuery("""SELECT n.*,
        (SELECT substr(text,1,512) FROM passages WHERE note_id=n.id AND excluded=0 ORDER BY start,id LIMIT 1) preview,
        (SELECT count(*) FROM passages WHERE note_id=n.id AND excluded=0 AND uncertain=1 AND reviewed=0) reviews
        FROM notes n ORDER BY created DESC""", null).use { c -> buildList {
        while (c.moveToNext()) add(note(c).copy(preview = c.string("preview"), reviewCount = c.int("reviews")))
        } }
        val term = query.trim()
        if (term.isEmpty()) return notes
        val matches = notes.filter { it.title.contains(term, ignoreCase = true) }.mapTo(mutableSetOf()) { it.id }
        // Read in bounded byte chunks: legacy passages can exceed a CursorWindow.
        // Search uses Unicode-aware Kotlin matching, including across chunk boundaries.
        readableDatabase.rawQuery("SELECT id,note_id,CASE WHEN length(CAST(text AS BLOB)) <= $MAX_PASSAGE_EDIT_BYTES THEN text END FROM passages WHERE excluded=0", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(1)
                if (id !in matches && (cursor.getString(2)?.contains(term, ignoreCase = true)
                        ?: passageContains(cursor.getLong(0), term))) matches += id
            }
        }
        return notes.filter { it.id in matches }
    }
    fun note(id: String): Note? = readableDatabase.rawQuery("SELECT * FROM notes WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) note(it) else null
    }
    private fun note(c: Cursor) = Note(c.string("id"), c.string("title"), c.long("created"), c.string("language"),
        c.string("state"), c.long("duration"), c.string("error"), speechEngine = c.string("speech_engine"))
    fun state(id: String, state: String, error: String = "") {
        writableDatabase.update("notes", ContentValues().apply { put("state", state); put("error", error) }, "id=?", arrayOf(id)); changed()
    }
    fun duration(id: String, ms: Long) {
        writableDatabase.update("notes", ContentValues().apply { put("duration", ms) }, "id=?", arrayOf(id)); changed()
    }
    fun rename(id: String, title: String) {
        writableDatabase.update("notes", ContentValues().apply {
            put("title", Transcript.truncateUtf16(title.trim(), 120).ifBlank { "Untitled note" })
            putNull("automatic_title_chunk")
        }, "id=?", arrayOf(id)); changed()
    }
    fun audioFile(noteId: String) = File(audioDir, "$noteId.pcm")
    fun readChunk(chunk: Chunk): ByteArray {
        check(chunk.start >= 0 && chunk.end > chunk.start && chunk.end - chunk.start <= MAX_CHUNK_MS) {
            "Invalid saved audio range."
        }
        val size = (chunk.end - chunk.start) * BYTES_PER_MS
        return RandomAccessFile(audioFile(chunk.noteId), "r").use { file ->
            check(chunk.end <= file.length() / BYTES_PER_MS) { "Saved audio is missing or damaged." }
            ByteArray(size.toInt()).also { bytes -> file.seek(chunk.start * BYTES_PER_MS); file.readFully(bytes) }
        }
    }
    // Whisper-era capture published gated ranges with beginChunk/finishChunk. Ultra capture no
    // longer does; tests use them to build the legacy notes that existing installs still hold.
    @VisibleForTesting
    internal fun beginChunk(note: String, start: Long, overlap: Boolean): String {
        require(start >= 0 && start <= Long.MAX_VALUE / BYTES_PER_MS) { "Invalid saved audio range." }
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("chunks", null, ContentValues().apply {
            put("id", id); put("note_id", note); put("start", start); put("end", start); put("overlap", if (overlap) 1 else 0); put("state", "WRITING")
        }); return id
    }
    @VisibleForTesting
    internal fun finishChunk(id: String, end: Long) {
        val db = writableDatabase
        var updated = false
        db.transaction {
            val chunk = rawQuery("SELECT * FROM chunks WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) chunkFromCursor(it) else null }
                ?: return@transaction
            // Repeated capture/recovery callbacks must never requeue committed corrections.
            if (chunk.state != "WRITING") return@transaction
            require(end > chunk.start && end - chunk.start <= MAX_CHUNK_MS) { "Invalid saved audio range." }
            update("chunks", ContentValues().apply { put("end", end); put("state", "PENDING") }, "id=? AND state='WRITING'", arrayOf(id))
            updated = true
        }
        if (updated) changed()
    }
    fun nextChunk(note: String): Chunk? = readableDatabase.rawQuery(
        "SELECT * FROM chunks WHERE note_id=? AND state='PENDING' ORDER BY start,rowid LIMIT 1", arrayOf(note)
    ).use { c -> if (c.moveToFirst()) chunkFromCursor(c) else null }

    internal fun lastChunk(note: String): Chunk? = readableDatabase.rawQuery(
        "SELECT * FROM chunks WHERE note_id=? ORDER BY end DESC,rowid DESC LIMIT 1", arrayOf(note)
    ).use { if (it.moveToFirst()) chunkFromCursor(it) else null }

    /** A window and its next cursor are published together, after the PCM watermark is synced. */
    internal fun enqueueUltraWindow(noteId: String, start: Long, end: Long, nextStart: Long): Chunk {
        require(start >= 0 && end > start && end - start <= MAX_CHUNK_MS && nextStart in (end - 500)..end && nextStart > start)
        val id = UUID.randomUUID().toString()
        var overlap = false
        writableDatabase.transaction {
            val note = checkNotNull(note(noteId))
            check(note.speechEngine == ULTRA_ENGINE && end <= note.duration) { "Audio is not durably saved yet." }
            val previous = lastChunk(noteId)
            check(start == (previous?.nextStart ?: 0L) && (previous == null || previous.state == "DONE")) {
                "Saved audio window is already queued."
            }
            check(previous == null || end > previous.end) { "Saved audio window must cover new audio." }
            overlap = previous != null && start < previous.end
            insertOrThrow("chunks", null, ContentValues().apply {
                put("id", id); put("note_id", noteId); put("start", start); put("end", end)
                put("overlap", if (overlap) 1 else 0); put("state", "PENDING"); put("next_start", nextStart)
            })
        }
        changed()
        return Chunk(id, noteId, start, end, overlap, "PENDING", nextStart)
    }
    fun previousChunkEnd(chunk: Chunk): Long? = readableDatabase.rawQuery(
        "SELECT end FROM chunks WHERE note_id=? AND state='DONE' AND start<? ORDER BY start DESC,rowid DESC LIMIT 1",
        arrayOf(chunk.noteId, chunk.start.toString())
    ).use { if (it.moveToFirst()) it.getLong(0) else null }
    fun pendingCount(note: String): Int = readableDatabase.rawQuery(
        "SELECT count(*) FROM chunks WHERE note_id=? AND state!='DONE'", arrayOf(note)
    ).use { it.moveToFirst(); it.getInt(0) }

    internal fun hasUnpublishedTail(note: Note): Boolean = note.mayNeedRecovery &&
        audioBytes(note.id) / BYTES_PER_MS - lastChunkEnd(readableDatabase, note.id) >=
        if (note.speechEngine == ULTRA_ENGINE) 1L else MIN_RECOVERABLE_MS

    private fun lastChunkEnd(db: SQLiteDatabase, id: String): Long =
        db.rawQuery("SELECT COALESCE(MAX(end),0) FROM chunks WHERE note_id=? AND state!='WRITING'", arrayOf(id)).use {
            it.moveToFirst(); it.getLong(0)
        }

    fun recentPassages(note: String, before: Long): List<Passage> = readPassages(
        "SELECT $PASSAGE_COLUMNS FROM passages WHERE note_id=? AND start<? AND excluded=0 ORDER BY start DESC,id DESC LIMIT 6",
        arrayOf(note, before.toString()),
    ).asReversed()
    fun chunks(note: String): List<Chunk> = readableDatabase.rawQuery("SELECT * FROM chunks WHERE note_id=? ORDER BY start,rowid", arrayOf(note)).use { c -> buildList {
        while (c.moveToNext()) add(chunkFromCursor(c))
    } }
    fun passages(note: String): List<Passage> = readPassages(
        "SELECT $PASSAGE_COLUMNS,$ORIGINAL_COLUMNS FROM passages WHERE note_id=? ORDER BY start,id", arrayOf(note),
        includeOriginal = true,
    )
    private fun readPassages(sql: String, args: Array<String>, includeOriginal: Boolean = false): List<Passage> = readableDatabase.rawQuery(sql, args).use { c -> buildList {
        while (c.moveToNext()) {
            val id = c.long("id")
            val text = c.getString(c.getColumnIndexOrThrow("safe_text")) ?: passageReader(id).use { it.readText() }
            val original = when {
                !includeOriginal || c.isNull(c.getColumnIndexOrThrow("original_size")) -> null
                c.int("original_matches_current") == 1 -> text
                else -> c.getString(c.getColumnIndexOrThrow("safe_original_text"))
                    ?: passageReader(id, original = true).use { it.readText() }
            }
            add(Passage(id, c.string("note_id"), c.string("chunk_id"), c.long("start"), c.long("end"), text,
                c.int("uncertain") == 1, c.int("reviewed") == 1, original, c.int("excluded") == 1))
        }
    } }
    // One SQL statement holds a consistent snapshot while individual rows fit safely
    // inside CursorWindow. The byte stream preserves UTF-8 across block boundaries.
    private fun passageReader(id: Long, limit: Boolean = true, original: Boolean = false): InputStreamReader {
        val column = if (original) "original_transcript_text" else "text"
        val cursor = readableDatabase.rawQuery("""WITH RECURSIVE offsets(pos,size) AS (
            SELECT 1,length(CAST($column AS BLOB)) FROM passages WHERE id=?
            UNION ALL SELECT pos+$TEXT_BLOCK_BYTES,size FROM offsets WHERE pos+$TEXT_BLOCK_BYTES<=size
        ) SELECT substr(CAST(p.$column AS BLOB),offsets.pos,$TEXT_BLOCK_BYTES), offsets.size
          FROM offsets JOIN passages p ON p.id=?""", arrayOf(id.toString(), id.toString()))
        val stream = object : InputStream() {
            var bytes = ByteArray(0)
            var position = 0
            var checked = false
            override fun read(): Int {
                if (position == bytes.size) {
                    if (!cursor.moveToNext()) return -1
                    if (!checked) {
                        checked = true
                        if (limit && cursor.getLong(1) > MAX_LEGACY_PASSAGE_BYTES)
                            throw PassageSizeException("This saved passage is too large to open. Its original text is still saved.")
                    }
                    bytes = cursor.getBlob(0) ?: ByteArray(0); position = 0
                    if (bytes.isEmpty()) return -1
                }
                return bytes[position++].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val first = read(); if (first < 0) return -1
                b[off] = first.toByte()
                val count = minOf(len - 1, bytes.size - position)
                System.arraycopy(bytes, position, b, off + 1, count); position += count
                return count + 1
            }
            override fun close() { cursor.close() }
        }
        return InputStreamReader(stream, Charsets.UTF_8)
    }
    private fun passageContains(id: Long, term: String): Boolean = passageReader(id, limit = false).use { reader ->
        val buffer = CharArray(8192)
        var carry = ""
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            val block = carry + String(buffer, 0, count)
            if (block.contains(term, ignoreCase = true)) return@use true
            carry = block.takeLast((term.length - 1).coerceAtLeast(0))
        }
        false
    }
    fun complete(chunk: Chunk, results: List<Passage>) {
        val db = writableDatabase
        db.transaction {
            val saved = rawQuery("SELECT * FROM chunks WHERE id=?", arrayOf(chunk.id)).use { if (it.moveToFirst()) chunkFromCursor(it) else null }
                ?: return@transaction // A note deleted while inference was in flight stays deleted.
            if (saved.state == "DONE") return@transaction
            check(saved.state == "PENDING") { "Audio has not finished saving." }
            require(saved.noteId == chunk.noteId && saved.start == chunk.start && saved.end == chunk.end) { "The source audio range changed." }
            results.forEach { p ->
                require(p.noteId == saved.noteId && p.chunkId == saved.id && p.start >= saved.start && p.end >= p.start && p.end <= saved.end) {
                    "A passage does not match its source audio."
                }
            }
            results.forEach { p -> db.insertOrThrow("passages", null, ContentValues().apply {
                put("note_id", p.noteId); put("chunk_id", p.chunkId); put("start", p.start); put("end", p.end)
                put("text", p.text); put("uncertain", if (p.uncertain) 1 else 0)
                put("original_transcript_text", p.text)
            }) }
            db.update("chunks", ContentValues().apply { put("state", "DONE") }, "id=?", arrayOf(chunk.id))
            if (note(chunk.noteId)?.title == "Untitled note") {
                val title = results.joinToString(" ") { it.text }
                if (title.isNotBlank()) db.update("notes", ContentValues().apply {
                    put("title", Transcript.title(title)); put("automatic_title_chunk", chunk.id)
                }, "id=? AND automatic_title_chunk IS NULL", arrayOf(chunk.noteId))
            }
        }
        changed()
    }
    fun edit(passage: Passage, text: String) {
        if (!fitsPassageDraftBudget(text))
            throw PassageSizeException("This passage is too long to save. Shorten it and try again; your draft is still open.")
        writableDatabase.transaction {
            val identity = arrayOf(passage.id.toString(), passage.noteId, passage.chunkId)
            val wasExcluded = rawQuery("SELECT excluded FROM passages WHERE id=? AND note_id=? AND chunk_id=?", identity).use {
                it.moveToFirst() && it.getInt(0) == 1
            }
            val updated = update("passages", ContentValues().apply {
                put("text", text.trim()); put("reviewed", 1); put("excluded", 0)
            }, "id=? AND note_id=? AND chunk_id=?", identity)
            check(updated == 1) { "This passage is no longer available. Your draft has not been saved." }
            if (wasExcluded) refreshAutomaticTitle(this, passage)
        }
        changed()
    }
    /** Notes checked by the retired background-sound feature can hold excluded passages; this brings one back. */
    fun restorePassage(passage: Passage): Boolean {
        val updated = writableDatabase.transaction {
            val changed = update("passages", ContentValues().apply {
                put("excluded", 0); put("reviewed", 1)
            }, "id=? AND note_id=? AND chunk_id=? AND excluded=1",
                arrayOf(passage.id.toString(), passage.noteId, passage.chunkId)) == 1
            if (changed) refreshAutomaticTitle(this, passage)
            changed
        }
        if (updated) changed()
        return updated
    }

    private fun refreshAutomaticTitle(db: SQLiteDatabase, passage: Passage) {
        val identity = arrayOf(passage.noteId, passage.chunkId)
        val titleSource = db.rawQuery("SELECT 1 FROM notes WHERE id=? AND automatic_title_chunk=?", identity).use { it.moveToFirst() }
        if (!titleSource) return
        val text = db.rawQuery(
            "SELECT substr(ltrim(text),1,512) FROM passages WHERE note_id=? AND chunk_id=? AND excluded=0 ORDER BY start,id",
            identity,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) }.joinToString(" ") }
        db.update("notes", ContentValues().apply { put("title", Transcript.title(text)) },
            "id=? AND automatic_title_chunk=?", identity)
    }

    fun recover(noteId: String? = null) {
        val db = writableDatabase
        db.transaction {
            val scope = if (noteId == null) "" else " AND n.id=?"
            val args = noteId?.let { arrayOf(it) }
            // Only interrupted/error captures need conservative tail reconciliation;
            // READY legacy notes keep their intentional silence-gate gaps unchanged.
            val candidates = rawQuery("""SELECT n.id FROM notes n
                WHERE n.state IN ('RECORDING','PAUSED','PROCESSING','INTERRUPTED','ERROR')
                AND NOT EXISTS(SELECT 1 FROM pending_audio_deletions d WHERE d.note_id=n.id)$scope""",
                args).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            val noteScope = if (noteId == null) "" else " AND id=?"
            execSQL("UPDATE notes SET state='INTERRUPTED',error='Recording was interrupted. Saved audio can still be transcribed.' WHERE state IN ('RECORDING','PAUSED','PROCESSING')$noteScope", args ?: emptyArray())
            val synced = mutableSetOf<String>()
            fun syncOriginal(id: String) {
                if (synced.add(id)) RandomAccessFile(audioFile(id), "rw").use { it.fd.sync() }
            }
            val writing = rawQuery("SELECT * FROM chunks WHERE state='WRITING'" + if (noteId == null) "" else " AND note_id=?", args).use { c ->
                buildList { while (c.moveToNext()) add(chunkFromCursor(c)) }
            }
            writing.forEach { saved ->
                val end = audioFile(saved.noteId).length() / BYTES_PER_MS
                if (saved.start < 0 || end - saved.start < MIN_RECOVERABLE_MS) {
                    delete("chunks", "id=?", arrayOf(saved.id))
                } else {
                    // Live recovery can follow a failed capture sync. It must succeed
                    // here before any recovered range becomes PENDING.
                    syncOriginal(saved.noteId)
                    publishRecoveredRange(this, saved.noteId, saved.start, end, saved.overlap, saved.id)
                }
            }
            candidates.forEach { id ->
                val file = audioFile(id)
                if (!file.isFile) return@forEach
                val end = file.length() / BYTES_PER_MS
                if (note(id)?.speechEngine == ULTRA_ENGINE) {
                    // The worker rebuilds its next pause-aware window from the saved cursor.
                    // Never replace committed text or pre-split its tail with Whisper framing.
                    syncOriginal(id)
                    update("notes", ContentValues().apply { put("duration", end) }, "id=?", arrayOf(id))
                    return@forEach
                }
                // Legacy Whisper notes: publish their unpublished tail as <=30 s PENDING ranges,
                // which the speech worker now drains with Ultra (it plans no new windows for them).
                // TODO(coverage-map migration, DB v5): replace this with a coverage map built from
                // all chunk states, so legacy notes resume exactly where coverage ends.
                val missing = uncoveredRanges(this, id, lastChunkEnd(this, id), end)
                    .filter { it.end - it.start >= MIN_RECOVERABLE_MS }
                if (missing.isNotEmpty()) {
                    syncOriginal(id)
                    missing.forEach { range -> publishRecoveredRange(this, id, range.start, range.end, overlap = false) }
                    update("notes", ContentValues().apply {
                        put("state", "INTERRUPTED")
                        put("error", "Recording was interrupted. Saved audio can still be transcribed.")
                    }, "id=?", arrayOf(id))
                }
                update("notes", ContentValues().apply { put("duration", end) }, "id=?", arrayOf(id))
            }
        }
        // Filesystem and SQLite cannot share a transaction. A committed intent makes
        // deletion retryable after process death or a transient filesystem failure.
        val deletions = db.rawQuery("SELECT note_id FROM pending_audio_deletions", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        deletions.forEach { try { deletePendingAudio(it) } catch (_: IOException) { /* Retry next launch. */ } }
        changed()
    }
    private data class MissingRange(val start: Long, val end: Long)
    private fun uncoveredRanges(db: SQLiteDatabase, note: String, start: Long, end: Long): List<MissingRange> {
        if (start >= end) return emptyList()
        val missing = mutableListOf<MissingRange>()
        var through = start
        db.rawQuery("SELECT start,end FROM chunks WHERE note_id=? AND state IN ('DONE','PENDING') AND end>? AND start<? ORDER BY start,end",
            arrayOf(note, start.toString(), end.toString())).use { c ->
            while (c.moveToNext()) {
                val coveredStart = c.getLong(0)
                val coveredEnd = c.getLong(1)
                if (coveredStart > through) missing += MissingRange(through, coveredStart)
                if (coveredEnd > through) through = coveredEnd
                if (through >= end) break
            }
        }
        if (through < end) missing += MissingRange(through, end)
        return missing
    }
    private fun publishRecoveredRange(db: SQLiteDatabase, note: String, from: Long, end: Long,
                                      overlap: Boolean, writingId: String? = null) {
        var start = from
        var first = true
        while (start < end) {
            val nextEnd = minOf(end, start + MAX_CHUNK_MS)
            if (first && writingId != null) {
                db.update("chunks", ContentValues().apply { put("end", nextEnd); put("state", "PENDING") }, "id=?", arrayOf(writingId))
            } else {
                db.insertOrThrow("chunks", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString()); put("note_id", note); put("start", start); put("end", nextEnd)
                    put("overlap", if (overlap || !first) 1 else 0); put("state", "PENDING")
                })
            }
            if (nextEnd == end) break
            start = nextEnd - RECOVERY_OVERLAP_MS
            first = false
        }
    }
    fun delete(id: String) {
        writableDatabase.transaction {
            insertWithOnConflict("pending_audio_deletions", null, ContentValues().apply { put("note_id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
            delete("notes", "id=?", arrayOf(id))
        }
        try { deletePendingAudio(id) } finally { changed() }
    }
    fun removeAudio(id: String) {
        writableDatabase.transaction {
            check(pendingCount(id) == 0 && note(id)?.let(::hasUnpublishedTail) != true) {
                "Finish transcription before removing its source audio."
            }
            insertWithOnConflict("pending_audio_deletions", null, ContentValues().apply { put("note_id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
        }
        try { deletePendingAudio(id) } finally { changed() }
    }
    private fun deletePendingAudio(id: String) {
        try { Files.deleteIfExists(audioFile(id).toPath()) }
        catch (e: IOException) { throw IOException("Could not remove saved audio. Focal will retry when it next opens.", e) }
        writableDatabase.delete("pending_audio_deletions", "note_id=?", arrayOf(id))
    }
    fun audioBytes(id: String) = audioFile(id).length()
    private fun chunkFromCursor(c: Cursor) = Chunk(c.string("id"), c.string("note_id"), c.long("start"), c.long("end"), c.int("overlap") == 1, c.string("state"),
        c.getColumnIndex("next_start").takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong))
    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name)) ?: ""
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
}
