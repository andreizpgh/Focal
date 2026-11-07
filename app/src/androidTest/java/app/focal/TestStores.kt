package app.focal

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import app.focal.core.AsrToken
import app.focal.core.EndKind
import app.focal.core.WindowPlan
import app.focal.data.NoteStore
import app.focal.data.RawAsr
import app.focal.data.ULTRA_ENGINE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileOutputStream

/** A context whose database and files live in [root], so a test can build and reopen its own focal.db. */
internal class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
    override fun getDatabasePath(name: String) = File(root, name)
    override fun getFilesDir() = File(root, "files")
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
        openOrCreateDatabase(name, mode, factory, null)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                                      errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
        val flags = SQLiteDatabase.CREATE_IF_NECESSARY or
            if (mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0
        return SQLiteDatabase.openDatabase(getDatabasePath(name).absolutePath, factory, flags, errorHandler)
    }
}

/** Synthetic recognizer output and window plans for store tests. */
internal object Synthetic {
    /** One token per word: (text, start s, duration s) relative to the decoded audio. */
    fun tokens(vararg words: Triple<String, Double, Double>, logProb: Double = -0.01): List<AsrToken> =
        words.map { (text, start, duration) -> AsrToken(" $text", start, duration, logProb) }

    /** Words spoken one after another from [startSec], each [step] seconds long. */
    fun speech(text: String, startSec: Double = 0.5, step: Double = 0.3): List<AsrToken> =
        text.split(' ').mapIndexed { i, word -> AsrToken(" $word", startSec + i * step, step * 0.8, -0.01) }

    fun plan(index: Int, start: Long, end: Long, kind: EndKind, previous: EndKind? = null, speechMs: Long = 1_000,
             peak: Int = 10_000): WindowPlan {
        val audioStart = when (previous) { EndKind.HARD -> start - 500; EndKind.PAUSE -> maxOf(0, start - 250); else -> start }
        val keepLo = if (previous == EndKind.HARD) start else null
        val (audioEnd, keepHi) = when (kind) {
            EndKind.HARD -> end + 500 to end
            EndKind.PAUSE -> end + 1_000 to end
            else -> end to null
        }
        return WindowPlan(index, start, end, audioStart, audioEnd, keepLo, keepHi, kind, audioEnd, speechMs, peak)
    }

    fun savedAudio(store: NoteStore, noteId: String, ms: Long, value: (Int) -> Byte = { (it % 97).toByte() }): ByteArray {
        val bytes = ByteArray((ms * 32).toInt(), value)
        FileOutputStream(store.audioFile(noteId)).use { it.write(bytes); it.fd.sync() }
        store.duration(noteId, ms)
        return bytes
    }

    /** Persists, starts and commits one window. */
    fun commit(store: NoteStore, noteId: String, plan: WindowPlan, tokens: List<AsrToken>, text: String = tokens.joinToString("") { it.text }.trim()) {
        val window = store.addWindow(noteId, plan)
        assertNotNull(store.beginDecode(window.id))
        assertTrue(store.commitDecoded(window.id, ULTRA_ENGINE, RawAsr(text, tokens)))
    }

    fun assertSearchIndexIntact(store: NoteStore) {
        val db = store.writableDatabase
        db.execSQL("INSERT INTO paragraph_fts(paragraph_fts) VALUES('integrity-check')")
        db.execSQL("INSERT INTO title_fts(title_fts) VALUES('integrity-check')")
        db.rawQuery("PRAGMA foreign_key_check", null).use { assertEquals("foreign keys", 0, it.count) }
    }
}

/** A paragraph value for UI tests that render without a store. */
internal fun testParagraph(id: Long, noteId: String, startMs: Long, endMs: Long, text: String, hidden: Boolean = false) =
    app.focal.data.Paragraph(id, noteId, id.toInt(), startMs, endMs, text, text, null, edited = false, hidden = hidden,
        open = false, revision = 0)
