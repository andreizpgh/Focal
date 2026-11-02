package app.focal.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import app.focal.core.Transcript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID

data class Note(val id: String, val title: String, val created: Long, val language: String,
                val state: String, val duration: Long, val error: String, val preview: String = "", val reviewCount: Int = 0)
data class Chunk(val id: String, val noteId: String, val start: Long, val end: Long,
                 val overlap: Boolean, val state: String)
data class Passage(val id: Long = 0, val noteId: String, val chunkId: String, val start: Long,
                   val end: Long, val text: String, val uncertain: Boolean, val reviewed: Boolean = false)

class NoteStore(context: Context) : SQLiteOpenHelper(context, "focal.db", null, 2) {
    private companion object {
        const val BYTES_PER_MS = 32L
        const val MAX_CHUNK_MS = 30_000L
        const val RECOVERY_OVERLAP_MS = 500L
    }
    val changes = MutableStateFlow(0L)
    val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,
            language TEXT NOT NULL,state TEXT NOT NULL,duration INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '')""")
        db.execSQL("""CREATE TABLE chunks(id TEXT PRIMARY KEY,note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
            start INTEGER NOT NULL,end INTEGER NOT NULL,overlap INTEGER NOT NULL,state TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE passages(id INTEGER PRIMARY KEY AUTOINCREMENT,note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
            chunk_id TEXT NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,start INTEGER NOT NULL,end INTEGER NOT NULL,
            text TEXT NOT NULL,uncertain INTEGER NOT NULL,reviewed INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX chunks_queue ON chunks(note_id,state,start)")
        db.execSQL("CREATE INDEX passages_note ON passages(note_id,start,id)")
        createDeletionJournal(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Missing database migration" }
        createDeletionJournal(db)
    }
    private fun createDeletionJournal(db: SQLiteDatabase) {
        // No foreign key: the intent must survive deleting the corresponding note.
        db.execSQL("CREATE TABLE pending_audio_deletions(note_id TEXT PRIMARY KEY)")
    }
    private fun changed() { changes.update { it + 1 } }
    fun create(language: String): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("notes", null, ContentValues().apply {
            put("id", id); put("title", "Untitled note"); put("created", System.currentTimeMillis())
            put("language", language); put("state", "RECORDING")
        }); changed(); return id
    }
    fun notes(query: String = ""): List<Note> {
        val notes = readableDatabase.rawQuery("""SELECT n.*,
        (SELECT text FROM passages WHERE note_id=n.id ORDER BY start,id LIMIT 1) preview,
        (SELECT count(*) FROM passages WHERE note_id=n.id AND uncertain=1 AND reviewed=0) reviews
        FROM notes n ORDER BY created DESC""", null).use { c -> buildList {
        while (c.moveToNext()) add(note(c).copy(preview = c.string("preview"), reviewCount = c.int("reviews")))
        } }
        val term = query.trim()
        if (term.isEmpty()) return notes
        val matches = notes.filter { it.title.contains(term, ignoreCase = true) }.mapTo(mutableSetOf()) { it.id }
        // SQLite's built-in LOWER/LIKE only folds ASCII. Keep matching Unicode-aware,
        // reading one bounded passage at a time rather than loading every transcript.
        readableDatabase.rawQuery("SELECT note_id,text FROM passages", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if (id !in matches && cursor.getString(1).contains(term, ignoreCase = true)) matches += id
            }
        }
        return notes.filter { it.id in matches }
    }
    fun note(id: String): Note? = readableDatabase.rawQuery("SELECT * FROM notes WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) note(it) else null
    }
    private fun note(c: Cursor) = Note(c.string("id"), c.string("title"), c.long("created"), c.string("language"),
        c.string("state"), c.long("duration"), c.string("error"))
    fun state(id: String, state: String, error: String = "") {
        writableDatabase.update("notes", ContentValues().apply { put("state", state); put("error", error) }, "id=?", arrayOf(id)); changed()
    }
    fun duration(id: String, ms: Long) {
        writableDatabase.update("notes", ContentValues().apply { put("duration", ms) }, "id=?", arrayOf(id)); changed()
    }
    fun rename(id: String, title: String) {
        writableDatabase.update("notes", ContentValues().apply { put("title", title.trim().take(120).ifBlank { "Untitled note" }) }, "id=?", arrayOf(id)); changed()
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
    fun beginChunk(note: String, start: Long, overlap: Boolean): String {
        require(start >= 0 && start <= Long.MAX_VALUE / BYTES_PER_MS) { "Invalid saved audio range." }
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("chunks", null, ContentValues().apply {
            put("id", id); put("note_id", note); put("start", start); put("end", start); put("overlap", if (overlap) 1 else 0); put("state", "WRITING")
        }); return id
    }
    fun finishChunk(id: String, end: Long) {
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
    ).use { c -> if (c.moveToFirst()) Chunk(c.string("id"), c.string("note_id"), c.long("start"), c.long("end"), c.int("overlap") == 1, c.string("state")) else null }
    fun previousChunkEnd(chunk: Chunk): Long? = readableDatabase.rawQuery(
        "SELECT end FROM chunks WHERE note_id=? AND state='DONE' AND start<? ORDER BY start DESC,rowid DESC LIMIT 1",
        arrayOf(chunk.noteId, chunk.start.toString())
    ).use { if (it.moveToFirst()) it.getLong(0) else null }
    fun pendingCount(note: String): Int = readableDatabase.rawQuery(
        "SELECT count(*) FROM chunks WHERE note_id=? AND state!='DONE'", arrayOf(note)
    ).use { it.moveToFirst(); it.getInt(0) }
    fun recentPassages(note: String, before: Long): List<Passage> = readableDatabase.rawQuery(
        "SELECT * FROM passages WHERE note_id=? AND start<? ORDER BY start DESC,id DESC LIMIT 6", arrayOf(note, before.toString())
    ).use { c -> buildList {
        while (c.moveToNext()) add(Passage(c.long("id"), c.string("note_id"), c.string("chunk_id"), c.long("start"), c.long("end"), c.string("text"), c.int("uncertain") == 1, c.int("reviewed") == 1))
    }.asReversed() }
    fun chunks(note: String): List<Chunk> = readableDatabase.rawQuery("SELECT * FROM chunks WHERE note_id=? ORDER BY start,rowid", arrayOf(note)).use { c -> buildList {
        while (c.moveToNext()) add(Chunk(c.string("id"), c.string("note_id"), c.long("start"), c.long("end"), c.int("overlap") == 1, c.string("state")))
    } }
    fun passages(note: String): List<Passage> = readableDatabase.rawQuery("SELECT * FROM passages WHERE note_id=? ORDER BY start,id", arrayOf(note)).use { c -> buildList {
        while (c.moveToNext()) add(Passage(c.long("id"), c.string("note_id"), c.string("chunk_id"), c.long("start"), c.long("end"), c.string("text"), c.int("uncertain") == 1, c.int("reviewed") == 1))
    } }
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
            }) }
            db.update("chunks", ContentValues().apply { put("state", "DONE") }, "id=?", arrayOf(chunk.id))
            if (note(chunk.noteId)?.title == "Untitled note") {
                val title = results.joinToString(" ") { it.text }
                if (title.isNotBlank()) db.update("notes", ContentValues().apply { put("title", Transcript.title(title)) }, "id=?", arrayOf(chunk.noteId))
            }
        }
        changed()
    }
    fun edit(passage: Passage, text: String) {
        val updated = writableDatabase.update("passages", ContentValues().apply { put("text", text.trim()); put("reviewed", 1) },
            "id=? AND note_id=? AND chunk_id=?", arrayOf(passage.id.toString(), passage.noteId, passage.chunkId))
        check(updated == 1) { "This passage is no longer available. Your draft has not been saved." }
        changed()
    }
    fun recover(noteId: String? = null, tailFromMs: Long? = null) {
        require(tailFromMs == null || (noteId != null && tailFromMs >= 0)) { "A recovery tail needs a note and a valid source offset." }
        val db = writableDatabase
        db.transaction {
            val scope = if (noteId == null) "" else " AND n.id=?"
            val args = noteId?.let { arrayOf(it) }
            // A queued Begin may never have reached SQLite before process death.
            // Only interrupted/error captures need conservative tail reconciliation;
            // READY notes keep their intentional silence-gate gaps unchanged.
            val candidates = rawQuery("""SELECT n.id FROM notes n
                WHERE (n.state IN ('RECORDING','PAUSED','PROCESSING','INTERRUPTED','ERROR')${if (tailFromMs == null) "" else " OR n.id=?"})
                AND NOT EXISTS(SELECT 1 FROM pending_audio_deletions d WHERE d.note_id=n.id)$scope""",
                if (tailFromMs == null) args else arrayOf(noteId!!, noteId)).use { c ->
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
                if (saved.start < 0 || end - saved.start < 20) {
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
                val lastEnd = rawQuery("SELECT COALESCE(MAX(end),0) FROM chunks WHERE note_id=? AND state!='WRITING'", arrayOf(id)).use {
                    it.moveToFirst(); it.getLong(0)
                }
                val start = (if (id == noteId) tailFromMs else null)?.coerceAtMost(end) ?: lastEnd
                val missing = uncoveredRanges(this, id, start, end).filter { it.end - it.start >= 20 }
                if (missing.isNotEmpty()) {
                    syncOriginal(id)
                    missing.forEachIndexed { index, range ->
                        // A precise rejected Begin can include the preceding hard-cut
                        // overlap. A stale watermark must never replay covered chunks.
                        val keepPrefix = index == 0 && tailFromMs != null && range.precedingStart != null &&
                            start > range.precedingStart && range.start - start in 1..RECOVERY_OVERLAP_MS
                        val from = if (keepPrefix) start else range.start
                        publishRecoveredRange(this, id, from, range.end, overlap = keepPrefix)
                    }
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
    private data class MissingRange(val start: Long, val end: Long, val precedingStart: Long?)
    private fun uncoveredRanges(db: SQLiteDatabase, note: String, start: Long, end: Long): List<MissingRange> {
        if (start >= end) return emptyList()
        val missing = mutableListOf<MissingRange>()
        var through = start
        var precedingStart: Long? = null
        db.rawQuery("SELECT start,end FROM chunks WHERE note_id=? AND state IN ('DONE','PENDING') AND end>? AND start<? ORDER BY start,end",
            arrayOf(note, start.toString(), end.toString())).use { c ->
            while (c.moveToNext()) {
                val coveredStart = c.getLong(0)
                val coveredEnd = c.getLong(1)
                if (coveredStart > through) missing += MissingRange(through, coveredStart, precedingStart)
                if (coveredEnd > through) { through = coveredEnd; precedingStart = coveredStart }
                if (through >= end) break
            }
        }
        if (through < end) missing += MissingRange(through, end, precedingStart)
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
            check(pendingCount(id) == 0) { "Finish transcription before removing its source audio." }
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
    private fun chunkFromCursor(c: Cursor) = Chunk(c.string("id"), c.string("note_id"), c.long("start"), c.long("end"), c.int("overlap") == 1, c.string("state"))
    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name)) ?: ""
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
}
