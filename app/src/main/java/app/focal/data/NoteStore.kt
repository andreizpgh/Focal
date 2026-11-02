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
import java.io.RandomAccessFile
import java.util.UUID

data class Note(val id: String, val title: String, val created: Long, val language: String,
                val state: String, val duration: Long, val error: String, val preview: String = "", val reviewCount: Int = 0)
data class Chunk(val id: String, val noteId: String, val start: Long, val end: Long,
                 val overlap: Boolean, val state: String)
data class Passage(val id: Long = 0, val noteId: String, val chunkId: String, val start: Long,
                   val end: Long, val text: String, val uncertain: Boolean, val reviewed: Boolean = false)

class NoteStore(context: Context) : SQLiteOpenHelper(context, "focal.db", null, 1) {
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
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Missing database migration")
    private fun changed() { changes.update { it + 1 } }
    fun create(language: String): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("notes", null, ContentValues().apply {
            put("id", id); put("title", "Untitled note"); put("created", System.currentTimeMillis())
            put("language", language); put("state", "RECORDING")
        }); changed(); return id
    }
    fun notes(): List<Note> = readableDatabase.rawQuery("""SELECT n.*,
        (SELECT text FROM passages WHERE note_id=n.id ORDER BY start,id LIMIT 1) preview,
        (SELECT count(*) FROM passages WHERE note_id=n.id AND uncertain=1 AND reviewed=0) reviews
        FROM notes n ORDER BY created DESC""", null).use { c -> buildList {
        while (c.moveToNext()) add(note(c).copy(preview = c.string("preview"), reviewCount = c.int("reviews")))
    } }
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
        val size = (chunk.end - chunk.start) * 32
        check(size in 2..(30 * 16000 * 2)) { "Invalid saved audio range." }
        return ByteArray(size.toInt()).also { bytes ->
            RandomAccessFile(audioFile(chunk.noteId), "r").use { file -> file.seek(chunk.start * 32); file.readFully(bytes) }
        }
    }
    fun beginChunk(note: String, start: Long, overlap: Boolean): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("chunks", null, ContentValues().apply {
            put("id", id); put("note_id", note); put("start", start); put("end", start); put("overlap", if (overlap) 1 else 0); put("state", "WRITING")
        }); return id
    }
    fun finishChunk(id: String, end: Long) {
        writableDatabase.update("chunks", ContentValues().apply { put("end", end); put("state", "PENDING") }, "id=?", arrayOf(id)); changed()
    }
    fun nextChunk(note: String): Chunk? = chunks(note).firstOrNull { it.state == "PENDING" }
    fun chunks(note: String): List<Chunk> = readableDatabase.rawQuery("SELECT * FROM chunks WHERE note_id=? ORDER BY start,rowid", arrayOf(note)).use { c -> buildList {
        while (c.moveToNext()) add(Chunk(c.string("id"), c.string("note_id"), c.long("start"), c.long("end"), c.int("overlap") == 1, c.string("state")))
    } }
    fun passages(note: String): List<Passage> = readableDatabase.rawQuery("SELECT * FROM passages WHERE note_id=? ORDER BY start,id", arrayOf(note)).use { c -> buildList {
        while (c.moveToNext()) add(Passage(c.long("id"), c.string("note_id"), c.string("chunk_id"), c.long("start"), c.long("end"), c.string("text"), c.int("uncertain") == 1, c.int("reviewed") == 1))
    } }
    fun complete(chunk: Chunk, results: List<Passage>) {
        val db = writableDatabase
        db.transaction {
            val done = rawQuery("SELECT state FROM chunks WHERE id=?", arrayOf(chunk.id)).use { it.moveToFirst() && it.getString(0) == "DONE" }
            if (done) return@transaction
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
        writableDatabase.update("passages", ContentValues().apply { put("text", text.trim()); put("reviewed", 1) }, "id=?", arrayOf(passage.id.toString())); changed()
    }
    fun recover() {
        val db = writableDatabase
        db.execSQL("UPDATE notes SET state='INTERRUPTED',error='Recording was interrupted. Saved audio can still be transcribed.' WHERE state IN ('RECORDING','PAUSED','PROCESSING')")
        db.rawQuery("SELECT * FROM chunks WHERE state='WRITING'", null).use { c ->
            while (c.moveToNext()) {
                val id = c.string("id"); val file = audioFile(c.string("note_id"))
                if (file.length() - c.long("start") * 32 >= 640) finishChunk(id, file.length() / 32)
                else db.delete("chunks", "id=?", arrayOf(id))
            }
        }
        notes().filter { it.state == "INTERRUPTED" }.forEach { duration(it.id, audioFile(it.id).length() / 32) }
        changed()
    }
    fun delete(id: String) {
        writableDatabase.delete("notes", "id=?", arrayOf(id))
        audioFile(id).delete(); changed()
    }
    fun removeAudio(id: String) { audioFile(id).delete(); changed() }
    fun audioBytes(id: String) = audioFile(id).length()
    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name)) ?: ""
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
}
