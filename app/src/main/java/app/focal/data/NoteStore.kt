package app.focal.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.VisibleForTesting
import androidx.core.database.sqlite.transaction
import app.focal.core.AsrToken
import app.focal.core.EndKind
import app.focal.core.FormatConfig
import app.focal.core.Title
import app.focal.core.Transcript
import app.focal.core.TranscriptAssembler
import app.focal.core.WindowGate
import app.focal.core.WindowMerge
import app.focal.core.WindowPlan
import app.focal.core.Word
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID

/**
 * Notes, their transcription windows and their editable paragraphs (schema: [StoreSchema]).
 *
 * The speech worker persists each window before decoding it ([addWindow], [beginDecode]) and commits the
 * result together with the paragraphs it changes ([commitDecoded]); only a note that is not READY is
 * changed this way. The user edits only READY notes, and every edit checks the paragraph's revision.
 * [changes] counts committed changes that the library shows; duration ticks of a live recording are not
 * among them.
 */
class NoteStore(context: Context) : SQLiteOpenHelper(context, "focal.db", null, StoreSchema.VERSION) {
    private companion object {
        const val BYTES_PER_MS = 32L
        const val SAMPLES_PER_MS = 16L
        const val INLINE_TEXT_BYTES = 256 * 1024
        const val TEXT_BLOCK_CHARS = 100_000
        const val MAX_LEGACY_PASSAGE_BYTES = 16L * 1024 * 1024
        const val MAX_TITLE_CHARS = 120
        const val PREVIEW_CHARS = 300
        const val SEARCH_TEXT_CHARS = 20_000
        /** A window whose decoding failed or was killed this many times is marked FAILED. */
        const val MAX_DECODE_ATTEMPTS = 2
        const val INTERRUPTED_MESSAGE = "Recording was interrupted. Saved audio can still be transcribed."
        const val NOTE_COLUMNS = "n.id,n.title,n.created,n.language,n.state,n.duration,n.error,n.speech_engine," +
            "n.title_is_custom,n.legacy,n.audio_present,n.pause_reason,n.end_reason," +
            "(SELECT substr(text,1,$PREVIEW_CHARS) FROM paragraphs p WHERE p.note_id=n.id AND p.hidden=0 " +
            "AND trim(p.text)!='' ORDER BY p.ord LIMIT 1) AS preview," +
            "(SELECT count(*) FROM windows w WHERE w.note_id=n.id AND w.state='FAILED') AS failed"
        const val PARAGRAPH_COLUMNS = "id,note_id,ord,start_ms,end_ms,edited,hidden,open,revision,words," +
            "CASE WHEN length(CAST(text AS BLOB))<=$INLINE_TEXT_BYTES THEN text END AS inline_text," +
            "length(CAST(text AS BLOB)) AS text_bytes," +
            "CASE WHEN source_text IS NULL THEN 0 WHEN source_text=text THEN 1 " +
            "WHEN length(CAST(source_text AS BLOB))<=$INLINE_TEXT_BYTES THEN 2 ELSE 3 END AS source_kind," +
            "CASE WHEN source_text!=text AND length(CAST(source_text AS BLOB))<=$INLINE_TEXT_BYTES " +
            "THEN source_text END AS inline_source"
        const val WINDOW_COLUMNS = "id,note_id,idx,own_start,own_end,audio_start,audio_end,keep_lo,keep_hi," +
            "end_kind,decide_at,speech_ms,peak,state,skip,hidden,engine,attempts,raw_text,error"
    }

    val changes = MutableStateFlow(0L)
    val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
    private val format = FormatConfig()

    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) = StoreSchema.create(db)
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        StoreSchema.upgrade(db, oldVersion, newVersion, audioDir)
    // Upgrades rebuild tables, so foreign keys (and their cascades) are enabled only once the schema is current.
    override fun onOpen(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    private fun changed() { changes.update { it + 1 } }

    // ---------------------------------------------------------------- notes

    /** A new Ultra recording. Its title stays empty until the transcript has words. */
    fun create(): String = insertNote(NoteState.RECORDING, legacy = false, created = System.currentTimeMillis())

    private fun insertNote(state: String, legacy: Boolean, created: Long, language: String = AUTOMATIC_LANGUAGE,
                           engine: String = ULTRA_ENGINE): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.transaction {
            execSQL("""INSERT INTO notes(id,title,created,language,state,speech_engine,legacy,seq)
                VALUES(?,'',?,?,?,?,?,(SELECT COALESCE(MAX(seq),0)+1 FROM notes))""",
                arrayOf<Any>(id, created, language, state, engine, if (legacy) 1 else 0))
            indexTitle(noteSeq(this, id)!!)
        }
        changed()
        return id
    }

    /** A note that is not in the trash, or null. */
    fun note(id: String): Note? = readableDatabase.rawQuery(
        "SELECT $NOTE_COLUMNS FROM notes n WHERE n.id=? AND n.deleted_at IS NULL", arrayOf(id),
    ).use { if (it.moveToFirst()) note(it) else null }

    /** Every note outside the trash, newest first, with a preview of its first paragraph. */
    fun library(): List<Note> = readableDatabase.rawQuery(
        "SELECT $NOTE_COLUMNS FROM notes n WHERE n.deleted_at IS NULL ORDER BY n.created DESC,n.seq DESC", null,
    ).use { c -> buildList { while (c.moveToNext()) add(note(c)) } }

    private fun note(c: Cursor) = Note(
        id = c.string("id"), title = c.string("title"), created = c.long("created"), language = c.string("language"),
        state = c.string("state"), duration = c.long("duration"), error = c.string("error"),
        preview = c.string("preview"), speechEngine = c.string("speech_engine"),
        titleIsCustom = c.int("title_is_custom") == 1, legacy = c.int("legacy") == 1,
        audioPresent = c.int("audio_present") == 1, pauseReason = c.nullableString("pause_reason"),
        endReason = c.nullableString("end_reason"), failedWindows = c.int("failed"),
    )

    /**
     * Notes whose title or a visible paragraph contains every word of [query] (the last one as a prefix),
     * in library order. Matching ignores case, Latin diacritics and ё/е.
     */
    fun search(query: String): List<SearchResult> {
        val terms = SearchText.terms(query)
        val match = SearchText.matchExpression(terms) ?: return emptyList()
        val db = readableDatabase
        data class Hit(val paragraphId: Long, val text: String)
        val paragraphHits = HashMap<String, Hit>()
        db.rawQuery("""SELECT p.note_id,p.id,MIN(p.ord),substr(p.text,1,$SEARCH_TEXT_CHARS) FROM paragraphs p
            WHERE p.hidden=0 AND p.id IN (SELECT docid FROM paragraph_fts WHERE paragraph_fts MATCH ?)
            GROUP BY p.note_id""", arrayOf(match)).use { c ->
            while (c.moveToNext()) paragraphHits[c.getString(0)] = Hit(c.getLong(1), c.getString(3))
        }
        val titleHits = db.rawQuery(
            "SELECT id FROM notes WHERE seq IN (SELECT docid FROM title_fts WHERE title_fts MATCH ?)", arrayOf(match),
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        return library().mapNotNull { note ->
            val hit = paragraphHits[note.id]
            when {
                hit != null -> {
                    val range = SearchText.firstHit(hit.text, terms)
                    val (snippet, at) = SearchText.snippet(hit.text, range)
                    SearchResult(note, hit.paragraphId, range?.first ?: 0, range?.let { it.last + 1 } ?: 0, snippet,
                        if (range == null) -1 else at)
                }
                note.id in titleHits -> SearchResult(note, null, -1, -1, note.preview, -1)
                else -> null
            }
        }
    }

    fun state(id: String, state: String, error: String = "") {
        writableDatabase.update("notes", ContentValues().apply {
            put("state", state); put("error", error)
            if (state != NoteState.PAUSED) putNull("pause_reason")
        }, "id=?", arrayOf(id))
        changed()
    }

    /** The recording was paused automatically ([CaptureReason]); the user resumes it. */
    fun pauseAutomatically(id: String, reason: String) {
        writableDatabase.update("notes", ContentValues().apply {
            put("state", NoteState.PAUSED); put("pause_reason", reason)
        }, "id=?", arrayOf(id))
        changed()
    }

    /** Why capture ended other than by Finish ([CaptureReason]); null clears it. */
    fun endReason(id: String, reason: String?) {
        writableDatabase.update("notes", ContentValues().apply { put("end_reason", reason) }, "id=?", arrayOf(id))
        changed()
    }

    /** The durable duration of a live recording. Not a library change: the live row has its own clock. */
    fun duration(id: String, ms: Long) {
        writableDatabase.update("notes", ContentValues().apply { put("duration", ms) }, "id=?", arrayOf(id))
    }

    /** A blank title returns the note to its automatic title. */
    fun rename(id: String, title: String) {
        val trimmed = Transcript.truncateUtf16(title.trim(), MAX_TITLE_CHARS)
        writableDatabase.transaction {
            val seq = noteSeq(this, id) ?: return@transaction
            unindexTitle(seq)
            if (trimmed.isBlank()) {
                update("notes", ContentValues().apply {
                    put("title_is_custom", 0); put("title", Title.of(StoreSchema.titleWords(this@transaction, id)))
                }, "id=?", arrayOf(id))
            } else {
                update("notes", ContentValues().apply { put("title_is_custom", 1); put("title", trimmed) }, "id=?", arrayOf(id))
            }
            indexTitle(seq)
        }
        changed()
    }

    fun audioFile(noteId: String) = File(audioDir, "$noteId.pcm")
    fun audioBytes(id: String) = audioFile(id).length()

    /** PCM samples of [startMs, endMs), clamped to the saved file. Windows are at most a few tens of seconds. */
    fun readAudio(noteId: String, startMs: Long, endMs: Long): ShortArray {
        require(startMs >= 0 && endMs >= startMs && endMs - startMs <= 120_000) { "Invalid saved audio range." }
        RandomAccessFile(audioFile(noteId), "r").use { file ->
            val from = minOf(startMs * BYTES_PER_MS, file.length() and 1L.inv())
            val to = minOf(endMs * BYTES_PER_MS, file.length() and 1L.inv())
            val bytes = ByteArray((to - from).toInt())
            file.seek(from)
            file.readFully(bytes)
            val samples = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            return samples
        }
    }

    /** True when the saved audio has no samples or every sample is the same: a recording to discard. */
    fun audioIsEmpty(noteId: String): Boolean {
        val file = audioFile(noteId)
        if (!file.isFile || file.length() < 2) return true
        file.inputStream().buffered(64 * 1024).use { input ->
            val block = ByteArray(64 * 1024)
            var first: Int? = null
            var carry = -1
            while (true) {
                val count = input.read(block)
                if (count < 0) return true
                var i = 0
                if (carry >= 0) {
                    val sample = (block[0].toInt() shl 8) or carry
                    if (first == null) first = sample.toShort().toInt() else if (sample.toShort().toInt() != first) return false
                    carry = -1; i = 1
                }
                while (i + 1 < count) {
                    val sample = ((block[i + 1].toInt() shl 8) or (block[i].toInt() and 0xff)).toShort().toInt()
                    if (first == null) first = sample else if (sample != first) return false
                    i += 2
                }
                if (i < count) carry = block[i].toInt() and 0xff
            }
        }
    }

    // ---------------------------------------------------------------- windows

    /**
     * Persists a planned window (the segmenter checkpoint) before it is decoded: PENDING, or SILENT when
     * [WindowGate] skips it. A window of a migrated note must start at one of its [gaps], which shrinks.
     */
    fun addWindow(noteId: String, plan: WindowPlan, skip: WindowGate.Skip? = null): StoredWindow {
        require(plan.ownEndMs > plan.ownStartMs && plan.audioStartMs <= plan.ownStartMs && plan.audioEndMs >= plan.ownEndMs) {
            "Invalid window plan."
        }
        val id = writableDatabase.transaction {
            val legacy = rawQuery("SELECT legacy,state FROM notes WHERE id=?", arrayOf(noteId)).use {
                check(it.moveToFirst()) { "This note is no longer available." }
                check(it.getString(1) != NoteState.READY) { "A ready note takes no new windows." }
                it.getInt(0) == 1
            }
            check(plan.index == nextWindowIndex(this, noteId)) { "Windows must be saved in order." }
            if (legacy) {
                val gapEnd = rawQuery("SELECT end FROM gaps WHERE note_id=? AND start=?",
                    arrayOf(noteId, plan.ownStartMs.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }
                checkNotNull(gapEnd) { "A migrated note takes windows only where no text exists." }
                check(plan.ownEndMs <= gapEnd) { "The window reaches past the untranscribed range." }
                delete("gaps", "note_id=? AND start=?", arrayOf(noteId, plan.ownStartMs.toString()))
                if (plan.ownEndMs < gapEnd) insertOrThrow("gaps", null, ContentValues().apply {
                    put("note_id", noteId); put("start", plan.ownEndMs); put("end", gapEnd)
                })
            } else {
                val last = lastWindow(this, noteId)
                check(last?.endKind != EndKind.FINAL) { "The recording is already complete." }
                check(plan.ownStartMs == (last?.ownEndMs ?: 0L)) { "Windows must cover the recording without gaps." }
            }
            insertOrThrow("windows", null, ContentValues().apply {
                put("note_id", noteId); put("idx", plan.index)
                put("own_start", plan.ownStartMs); put("own_end", plan.ownEndMs)
                put("audio_start", plan.audioStartMs); put("audio_end", plan.audioEndMs)
                put("keep_lo", plan.keepLoMs); put("keep_hi", plan.keepHiMs)
                put("end_kind", plan.endKind.name); put("decide_at", plan.decideAtMs)
                put("speech_ms", plan.speechMs); put("peak", plan.peak)
                put("state", if (skip == null) WindowState.PENDING.name else WindowState.SILENT.name)
                put("skip", skip?.name)
            })
        }
        changed()
        return checkNotNull(window(id))
    }

    /**
     * Counts a decoding attempt before the recognizer runs, so that a window that keeps killing the process
     * cannot loop: a window that already used [MAX_DECODE_ATTEMPTS] is marked FAILED and null is returned.
     */
    fun beginDecode(windowId: Long): StoredWindow? {
        var failed = false
        val window = writableDatabase.transaction {
            val window = window(this, windowId)?.takeIf { it.state == WindowState.PENDING } ?: return@transaction null
            if (window.attempts >= MAX_DECODE_ATTEMPTS) {
                fail(this, window, "This part could not be transcribed.")
                failed = true
                return@transaction null
            }
            execSQL("UPDATE windows SET attempts=attempts+1 WHERE id=?", arrayOf<Any>(windowId))
            window.copy(attempts = window.attempts + 1)
        }
        if (failed) changed()
        return window
    }

    /** A decode that was cancelled cleanly (not a failure) does not count as an attempt. */
    fun cancelDecode(windowId: Long) {
        writableDatabase.execSQL("UPDATE windows SET attempts=max(0,attempts-1) WHERE id=? AND state='PENDING'",
            arrayOf<Any>(windowId))
    }

    fun failWindow(windowId: Long, error: String) {
        val failed = writableDatabase.transaction {
            val window = window(this, windowId)?.takeIf { it.state == WindowState.PENDING } ?: return@transaction false
            fail(this, window, error)
            true
        }
        if (failed) changed()
    }

    private fun fail(db: SQLiteDatabase, window: StoredWindow, error: String) {
        db.update("windows", ContentValues().apply { put("state", WindowState.FAILED.name); put("error", error) },
            "id=?", arrayOf(window.id.toString()))
        // Text after a failed window starts a new paragraph, so a later retry can be placed between them.
        db.execSQL("UPDATE paragraphs SET open=0 WHERE note_id=? AND open=1 AND start_ms<?",
            arrayOf<Any>(window.noteId, window.ownStartMs))
    }

    /**
     * Stores a window's recognizer result and, unless the hallucination guard hides it, merges its words
     * into the paragraphs at its place in the note: the open paragraph before it grows and new paragraphs
     * follow. All in one transaction. Returns false when the window is gone or was already committed.
     */
    fun commitDecoded(windowId: Long, engine: String, result: RawAsr): Boolean {
        val committed = writableDatabase.transaction {
            val window = window(this, windowId)?.takeIf { it.state == WindowState.PENDING } ?: return@transaction false
            val state = rawQuery("SELECT state FROM notes WHERE id=?", arrayOf(window.noteId)).use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: return@transaction false
            check(state != NoteState.READY) { "Transcription never changes a ready note." }
            val plan = window.plan()
            val decoded = plan.toDecodedWindow(result.tokens)
            val nextOrd = rawQuery("SELECT MIN(ord) FROM paragraphs WHERE note_id=? AND start_ms>=?",
                arrayOf(window.noteId, plan.ownStartMs.toString())).use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else null }
            val previous = paragraphsIn(this, if (nextOrd == null) "note_id=?" else "note_id=? AND ord<?",
                listOfNotNull(window.noteId, nextOrd?.toString()), descending = true, limit = 1).firstOrNull()
            val open = previous?.takeIf { it.open && !it.edited && !it.hidden && it.wordsJson != null }
            val openWords = open?.sourceWords().orEmpty()
            val kept = WindowMerge.keptWords(decoded, plan.index, openWords.lastOrNull(), format.dedupeMs, format.minWordEndMs)
            val hidden = WindowGate.hidesResult(plan, kept.size)
            update("windows", ContentValues().apply {
                put("state", WindowState.DONE.name); put("engine", engine); put("raw_text", result.text)
                put("tokens", TranscriptJson.encodeTokens(result.tokens)); put("hidden", if (hidden) 1 else 0)
                putNull("error")
            }, "id=?", arrayOf(windowId.toString()))
            if (!hidden && kept.isNotEmpty()) {
                val update = TranscriptAssembler(format, openWords).append(decoded, plan.index)
                materialise(this, window.noteId, open, nextOrd ?: previous?.let { it.ord + 1 } ?: 0, update.paragraphs)
                refreshTitle(this, window.noteId)
            }
            true
        }
        if (committed) changed()
        return committed
    }

    /** Writes assembler paragraphs: the first replaces [open] (if any), the rest are inserted; the last stays open. */
    private fun materialise(db: SQLiteDatabase, noteId: String, open: Paragraph?, insertOrd: Int, paragraphs: List<List<Word>>) {
        if (paragraphs.isEmpty()) return
        var fresh = paragraphs
        var ord = insertOrd
        if (open != null) {
            writeWords(db, open.id, paragraphs[0], isOpen = paragraphs.size == 1)
            fresh = paragraphs.drop(1)
            ord = open.ord + 1
        }
        if (fresh.isEmpty()) return
        db.execSQL("UPDATE paragraphs SET ord=ord+? WHERE note_id=? AND ord>=?", arrayOf<Any>(fresh.size, noteId, ord))
        fresh.forEachIndexed { i, words ->
            val text = words.joinToString(" ") { it.text }
            val id = db.insertOrThrow("paragraphs", null, ContentValues().apply {
                put("note_id", noteId); put("ord", ord + i)
                put("start_ms", words.first().startMs); put("end_ms", words.last().endMs)
                put("text", text); put("source_text", text); put("words", TranscriptJson.encodeWords(words))
                put("open", if (i == fresh.lastIndex) 1 else 0)
            })
            db.indexParagraph(id)
        }
    }

    private fun writeWords(db: SQLiteDatabase, id: Long, words: List<Word>, isOpen: Boolean) {
        val text = words.joinToString(" ") { it.text }
        db.unindexParagraph(id)
        db.update("paragraphs", ContentValues().apply {
            put("text", text); put("source_text", text); put("words", TranscriptJson.encodeWords(words))
            put("start_ms", words.first().startMs); put("end_ms", words.last().endMs)
            put("open", if (isOpen) 1 else 0); put("edited", 0)
        }, "id=?", arrayOf(id.toString()))
        db.execSQL("UPDATE paragraphs SET revision=revision+1 WHERE id=?", arrayOf<Any>(id))
        db.indexParagraph(id)
    }

    /** Failed windows of a note become PENDING again (with fresh attempts); the note waits for transcription. */
    fun retryFailed(noteId: String): Int {
        val count = writableDatabase.transaction {
            val note = note(noteId) ?: return@transaction 0
            if (!note.audioPresent || note.state in NoteState.ACTIVE) return@transaction 0
            val count = update("windows", ContentValues().apply {
                put("state", WindowState.PENDING.name); put("attempts", 0); putNull("error")
            }, "note_id=? AND state='FAILED'", arrayOf(noteId))
            if (count > 0) update("notes", ContentValues().apply {
                put("state", NoteState.INTERRUPTED); put("error", "")
            }, "id=?", arrayOf(noteId))
            count
        }
        if (count > 0) changed()
        return count
    }

    fun window(id: Long): StoredWindow? = window(readableDatabase, id)
    private fun window(db: SQLiteDatabase, id: Long): StoredWindow? =
        db.rawQuery("SELECT $WINDOW_COLUMNS FROM windows WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) storedWindow(it) else null
        }

    /** All windows of a note by index (without their tokens). */
    fun windows(noteId: String): List<StoredWindow> = readableDatabase.rawQuery(
        "SELECT $WINDOW_COLUMNS FROM windows WHERE note_id=? ORDER BY idx", arrayOf(noteId),
    ).use { c -> buildList { while (c.moveToNext()) add(storedWindow(c)) } }

    /** Persisted windows still to decode, in index order. */
    fun pendingWindows(noteId: String): List<StoredWindow> = readableDatabase.rawQuery(
        "SELECT $WINDOW_COLUMNS FROM windows WHERE note_id=? AND state='PENDING' ORDER BY idx", arrayOf(noteId),
    ).use { c -> buildList { while (c.moveToNext()) add(storedWindow(c)) } }

    /** The window with the highest index: for a note recorded with schema 5, the segmenter checkpoint. */
    fun lastWindow(noteId: String): StoredWindow? = lastWindow(readableDatabase, noteId)
    private fun lastWindow(db: SQLiteDatabase, noteId: String): StoredWindow? = db.rawQuery(
        "SELECT $WINDOW_COLUMNS FROM windows WHERE note_id=? ORDER BY idx DESC LIMIT 1", arrayOf(noteId),
    ).use { if (it.moveToFirst()) storedWindow(it) else null }

    /** A planned window ending exactly at [endMs], to continue a partly transcribed gap after it. */
    fun windowEndingAt(noteId: String, endMs: Long): StoredWindow? = readableDatabase.rawQuery(
        "SELECT $WINDOW_COLUMNS FROM windows WHERE note_id=? AND own_end=? AND end_kind IS NOT NULL ORDER BY idx DESC LIMIT 1",
        arrayOf(noteId, endMs.toString()),
    ).use { if (it.moveToFirst()) storedWindow(it) else null }

    fun nextWindowIndex(noteId: String): Int = nextWindowIndex(readableDatabase, noteId)
    private fun nextWindowIndex(db: SQLiteDatabase, noteId: String): Int =
        db.rawQuery("SELECT COALESCE(MAX(idx)+1,0) FROM windows WHERE note_id=?", arrayOf(noteId)).use {
            it.moveToFirst(); it.getInt(0)
        }

    /** The stored recognizer tokens of a decoded window. */
    fun windowTokens(windowId: Long): List<AsrToken>? = readableDatabase.rawQuery(
        "SELECT tokens FROM windows WHERE id=?", arrayOf(windowId.toString()),
    ).use { if (it.moveToFirst() && !it.isNull(0)) TranscriptJson.decodeTokens(it.getString(0)) else null }

    private fun storedWindow(c: Cursor) = StoredWindow(
        id = c.long("id"), noteId = c.string("note_id"), index = c.int("idx"),
        ownStartMs = c.long("own_start"), ownEndMs = c.long("own_end"),
        audioStartMs = c.long("audio_start"), audioEndMs = c.long("audio_end"),
        keepLoMs = c.nullableLong("keep_lo"), keepHiMs = c.nullableLong("keep_hi"),
        endKind = c.nullableString("end_kind")?.let(EndKind::valueOf), decideAtMs = c.long("decide_at"),
        speechMs = c.long("speech_ms"), peak = c.int("peak"), state = WindowState.valueOf(c.string("state")),
        skip = c.nullableString("skip"), hidden = c.int("hidden") == 1, engine = c.nullableString("engine"),
        attempts = c.int("attempts"), rawText = c.nullableString("raw_text"), error = c.nullableString("error"),
    )

    /** Untranscribed ranges of a migrated note, in time order. */
    fun gaps(noteId: String): List<Gap> = readableDatabase.rawQuery(
        "SELECT start,end FROM gaps WHERE note_id=? ORDER BY start", arrayOf(noteId),
    ).use { c -> buildList { while (c.moveToNext()) add(Gap(noteId, c.getLong(0), c.getLong(1))) } }

    /** Records a Pause at PCM sample [sample]; segmentation after process death replays it as a break. */
    fun addBreak(noteId: String, sample: Long) {
        writableDatabase.insertWithOnConflict("breaks", null, ContentValues().apply {
            put("note_id", noteId); put("sample", sample)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun breaks(noteId: String): List<Long> = readableDatabase.rawQuery(
        "SELECT sample FROM breaks WHERE note_id=? ORDER BY sample", arrayOf(noteId),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    fun progress(noteId: String): TranscriptionProgress {
        val db = readableDatabase
        val (duration, legacy) = db.rawQuery("SELECT duration,legacy FROM notes WHERE id=?", arrayOf(noteId)).use {
            if (it.moveToFirst()) it.getLong(0) to (it.getInt(1) == 1) else return TranscriptionProgress(0, 0, 0, 0, 0)
        }
        fun pair(sql: String) = db.rawQuery(sql, arrayOf(noteId)).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }
        val (pending, pendingMs) = pair("SELECT count(*),COALESCE(SUM(own_end-own_start),0) FROM windows WHERE note_id=? AND state='PENDING'")
        val (gaps, gapMs) = pair("SELECT count(*),COALESCE(SUM(end-start),0) FROM gaps WHERE note_id=?")
        val (failed, coveredEnd) = pair("SELECT (SELECT count(*) FROM windows WHERE note_id=?1 AND state='FAILED')," +
            "(SELECT COALESCE(MAX(own_end),0) FROM windows WHERE note_id=?1 AND end_kind IS NOT NULL)")
        val tail = if (legacy) 0L else (duration - coveredEnd).coerceAtLeast(0)
        return TranscriptionProgress(duration, pendingMs + gapMs + tail, pending, gaps, failed)
    }

    /** Windows and migrated ranges still waiting for transcription. */
    fun pendingCount(noteId: String): Int = progress(noteId).let { it.pendingWindows + it.gaps }

    /** Saved audio that no committed window has transcribed yet. */
    fun hasUntranscribedAudio(note: Note): Boolean = progress(note.id).let { it.remainingMs > 0 || it.pendingWindows > 0 }

    /**
     * Completes a transcription: all paragraphs close and the note becomes READY. Refused (false) while a
     * window is pending, a migrated range is untranscribed or saved audio lies beyond the last window.
     */
    fun markReady(noteId: String): Boolean {
        val ready = writableDatabase.transaction {
            val progress = progress(noteId)
            if (progress.pendingWindows > 0 || progress.gaps > 0 || progress.remainingMs > 0) return@transaction false
            execSQL("UPDATE paragraphs SET open=0 WHERE note_id=? AND open=1", arrayOf<Any>(noteId))
            update("notes", ContentValues().apply {
                put("state", NoteState.READY); put("error", ""); putNull("pause_reason")
            }, "id=? AND deleted_at IS NULL", arrayOf(noteId)) == 1
        }
        if (ready) changed()
        return ready
    }

    // ---------------------------------------------------------------- paragraphs

    /** Every paragraph of a note in order, hidden ones included ([Paragraph.visible] filters them). */
    fun paragraphs(noteId: String): List<Paragraph> = paragraphsIn(readableDatabase, "note_id=?", listOf(noteId))

    fun paragraph(id: Long): Paragraph? = paragraphsIn(readableDatabase, "id=?", listOf(id.toString())).firstOrNull()

    private fun paragraphsIn(db: SQLiteDatabase, where: String, args: List<String>, descending: Boolean = false,
                             limit: Int? = null): List<Paragraph> {
        val order = if (descending) "ord DESC,id DESC" else "ord,id"
        return db.rawQuery("SELECT $PARAGRAPH_COLUMNS FROM paragraphs WHERE $where ORDER BY $order" +
            (limit?.let { " LIMIT $it" } ?: ""), args.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.long("id")
                    if (c.long("text_bytes") > MAX_LEGACY_PASSAGE_BYTES)
                        throw PassageSizeException("This saved paragraph is too large to open. Its text is still saved.")
                    val text = c.nullableString("inline_text") ?: longText(db, id, "text")
                    val source = when (c.int("source_kind")) {
                        0 -> null
                        1 -> text
                        2 -> c.nullableString("inline_source")
                        else -> longText(db, id, "source_text")
                    }
                    add(Paragraph(id, c.string("note_id"), c.int("ord"), c.long("start_ms"), c.long("end_ms"), text, source,
                        c.nullableString("words"), c.int("edited") == 1, c.int("hidden") == 1, c.int("open") == 1,
                        c.long("revision")))
                }
            }
        }
    }

    /** Text too large for one cursor window is read in blocks of characters. */
    private fun longText(db: SQLiteDatabase, id: Long, column: String): String {
        val length = db.rawQuery("SELECT length($column) FROM paragraphs WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        val out = StringBuilder(length.toInt())
        var position = 1L
        while (position <= length) {
            db.rawQuery("SELECT substr($column,?,$TEXT_BLOCK_CHARS) FROM paragraphs WHERE id=?",
                arrayOf(position.toString(), id.toString())).use { if (it.moveToFirst()) out.append(it.getString(0)) }
            position += TEXT_BLOCK_CHARS
        }
        return out.toString()
    }

    /** The note body for Copy and Share. */
    fun transcript(noteId: String): String = transcriptText(paragraphs(noteId))

    private fun editable(db: SQLiteDatabase, paragraph: Paragraph?, revision: Long): EditResult? = when {
        paragraph == null -> EditResult.Missing
        noteState(db, paragraph.noteId) != NoteState.READY -> EditResult.NotEditable
        paragraph.revision != revision -> EditResult.Conflict
        else -> null
    }

    private fun noteState(db: SQLiteDatabase, noteId: String): String? =
        db.rawQuery("SELECT state FROM notes WHERE id=? AND deleted_at IS NULL", arrayOf(noteId)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private inline fun edit(crossinline block: SQLiteDatabase.() -> EditResult): EditResult {
        val result = writableDatabase.transaction { block() }
        if (result is EditResult.Saved) changed()
        return result
    }

    /** Replaces a paragraph's text; [revision] is the revision the editor started from. */
    fun editParagraph(id: Long, revision: Long, text: String): EditResult {
        if (!fitsPassageDraftBudget(text)) return EditResult.TooLarge
        return edit {
            val paragraph = paragraphsIn(this, "id=?", listOf(id.toString())).firstOrNull()
            editable(this, paragraph, revision)?.let { return@edit it }
            paragraph!!
            val value = text.trim()
            if (value == paragraph.text && !paragraph.hidden) return@edit EditResult.Saved(listOf(paragraph))
            writeText(this, paragraph.id, value, edited = paragraph.sourceText == null || value != paragraph.sourceText)
            refreshTitle(this, paragraph.noteId)
            EditResult.Saved(paragraphsIn(this, "id=?", listOf(id.toString())))
        }
    }

    /** Puts back the recognizer's text of an edited paragraph. */
    fun restoreOriginal(id: Long, revision: Long): EditResult = edit {
        val paragraph = paragraphsIn(this, "id=?", listOf(id.toString())).firstOrNull()
        editable(this, paragraph, revision)?.let { return@edit it }
        val source = paragraph!!.sourceText?.takeIf { it.isNotEmpty() } ?: return@edit EditResult.NoOriginal
        writeText(this, paragraph.id, source, edited = false)
        refreshTitle(this, paragraph.noteId)
        EditResult.Saved(paragraphsIn(this, "id=?", listOf(id.toString())))
    }

    /** Shows a paragraph that the retired background-sound review had excluded. */
    fun unhideParagraph(id: Long): Boolean {
        val shown = writableDatabase.transaction {
            val paragraph = paragraphsIn(this, "id=?", listOf(id.toString())).firstOrNull()
            if (paragraph == null || !paragraph.hidden) return@transaction false
            unindexParagraph(id)
            execSQL("UPDATE paragraphs SET hidden=0,revision=revision+1 WHERE id=?", arrayOf<Any>(id))
            indexParagraph(id)
            refreshTitle(this, paragraph.noteId)
            true
        }
        if (shown) changed()
        return shown
    }

    private fun writeText(db: SQLiteDatabase, id: Long, text: String, edited: Boolean) {
        db.unindexParagraph(id)
        db.execSQL("UPDATE paragraphs SET text=?,edited=?,hidden=0,revision=revision+1 WHERE id=?",
            arrayOf<Any>(text, if (edited) 1 else 0, id))
        db.indexParagraph(id)
    }

    /** Splits a paragraph at char [offset] of its text (Enter in the editor). */
    fun splitParagraph(id: Long, revision: Long, offset: Int): EditResult = edit {
        val paragraph = paragraphsIn(this, "id=?", listOf(id.toString())).firstOrNull()
        editable(this, paragraph, revision)?.let { return@edit it }
        paragraph!!
        require(offset in 0..paragraph.text.length) { "The split position is outside the paragraph." }
        val (first, second) = ParagraphEdits.split(paragraph.content(), offset)
        writeContent(this, paragraph.id, first)
        execSQL("UPDATE paragraphs SET ord=ord+1 WHERE note_id=? AND ord>?", arrayOf<Any>(paragraph.noteId, paragraph.ord))
        val added = insertOrThrow("paragraphs", null, ContentValues().apply {
            put("note_id", paragraph.noteId); put("ord", paragraph.ord + 1)
            put("start_ms", second.startMs); put("end_ms", second.endMs); put("text", second.text)
            put("source_text", second.sourceText); put("words", second.words?.let(TranscriptJson::encodeWords))
            put("edited", if (second.edited) 1 else 0); put("hidden", if (paragraph.hidden) 1 else 0)
        })
        indexParagraph(added)
        refreshTitle(this, paragraph.noteId)
        EditResult.Saved(paragraphsIn(this, "id IN (?,?)", listOf(id.toString(), added.toString())))
    }

    /**
     * Joins a paragraph with the next visible one (Backspace at the start of [secondId]). [EditResult.CannotMerge]
     * when that would drop a known recognizer original; both paragraphs then stay as they are.
     */
    fun mergeParagraphs(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long): EditResult = edit {
        val first = paragraphsIn(this, "id=?", listOf(firstId.toString())).firstOrNull()
        val second = paragraphsIn(this, "id=?", listOf(secondId.toString())).firstOrNull()
        editable(this, first, firstRevision)?.let { return@edit it }
        editable(this, second, secondRevision)?.let { return@edit it }
        first!!; second!!
        if (first.noteId != second.noteId || first.ord >= second.ord) return@edit EditResult.Conflict
        val between = rawQuery("""SELECT count(*) FROM paragraphs WHERE note_id=? AND ord>? AND ord<?
            AND hidden=0 AND trim(text)!=''""", arrayOf(first.noteId, first.ord.toString(), second.ord.toString())).use {
            it.moveToFirst(); it.getInt(0)
        }
        if (between > 0) return@edit EditResult.Conflict
        val merged = ParagraphEdits.merge(first.content(), second.content()) ?: return@edit EditResult.CannotMerge
        if (!fitsPassageDraftBudget(merged.text)) return@edit EditResult.TooLarge
        unindexParagraph(second.id)
        delete("paragraphs", "id=?", arrayOf(second.id.toString()))
        writeContent(this, first.id, merged, hidden = first.hidden && second.hidden)
        refreshTitle(this, first.noteId)
        EditResult.Saved(paragraphsIn(this, "id=?", listOf(firstId.toString())))
    }

    private fun Paragraph.content() = ParagraphContent(text, sourceText, sourceWords(), startMs, endMs, edited)

    private fun writeContent(db: SQLiteDatabase, id: Long, content: ParagraphContent, hidden: Boolean? = null) {
        db.unindexParagraph(id)
        db.update("paragraphs", ContentValues().apply {
            put("text", content.text); put("source_text", content.sourceText)
            put("words", content.words?.let(TranscriptJson::encodeWords))
            put("start_ms", content.startMs); put("end_ms", content.endMs); put("edited", if (content.edited) 1 else 0)
            hidden?.let { put("hidden", if (it) 1 else 0) }
        }, "id=?", arrayOf(id.toString()))
        db.execSQL("UPDATE paragraphs SET revision=revision+1 WHERE id=?", arrayOf<Any>(id))
        db.indexParagraph(id)
    }

    /** Find in note: every occurrence of [query] in the visible paragraphs, in reading order. */
    fun findInNote(noteId: String, query: String): List<TextMatch> {
        if (query.isEmpty()) return emptyList()
        return paragraphs(noteId).filter { it.visible }.flatMap { paragraph ->
            SearchText.occurrences(paragraph.text, query).map { TextMatch(paragraph.id, it.first, it.last + 1) }
        }
    }

    /** Replaces every occurrence of [query] in the visible paragraphs of a ready note, all or nothing. */
    fun replaceAll(noteId: String, query: String, replacement: String): ReplaceResult {
        if (query.isEmpty()) return ReplaceResult(0, null, EditResult.Saved(emptyList()))
        var count = 0
        var undo: ReplaceUndo? = null
        val outcome = edit {
            if (noteState(this, noteId) != NoteState.READY) return@edit EditResult.NotEditable
            val changes = paragraphsIn(this, "note_id=?", listOf(noteId)).filter { it.visible }.mapNotNull { paragraph ->
                val ranges = SearchText.occurrences(paragraph.text, query)
                if (ranges.isEmpty()) return@mapNotNull null
                count += ranges.size
                Triple(paragraph, SearchText.replace(paragraph.text, ranges, replacement), ranges.size)
            }
            if (changes.any { !fitsPassageDraftBudget(it.second) }) { count = 0; return@edit EditResult.TooLarge }
            val written = changes.filter { (paragraph, text) -> text != paragraph.text }
            written.forEach { (paragraph, text) ->
                writeText(this, paragraph.id, text, edited = paragraph.sourceText == null || text != paragraph.sourceText)
            }
            if (written.isNotEmpty()) refreshTitle(this, noteId)
            val saved = if (written.isEmpty()) emptyList() else paragraphsIn(this,
                "id IN (${written.joinToString(",") { "?" }})", written.map { it.first.id.toString() })
            undo = ReplaceUndo(noteId, saved.map { after ->
                val before = written.first { it.first.id == after.id }.first
                ReplaceUndo.Entry(after.id, before.text, before.edited, after.revision)
            }).takeIf { it.entries.isNotEmpty() }
            EditResult.Saved(saved)
        }
        return ReplaceResult(if (outcome is EditResult.Saved) count else 0, undo, outcome)
    }

    /** Undoes a Replace all, unless one of its paragraphs changed since. */
    fun undoReplace(undo: ReplaceUndo): EditResult = edit {
        if (noteState(this, undo.noteId) != NoteState.READY) return@edit EditResult.NotEditable
        val current = undo.entries.map { entry ->
            paragraphsIn(this, "id=?", listOf(entry.paragraphId.toString())).firstOrNull() ?: return@edit EditResult.Missing
        }
        if (current.zip(undo.entries).any { (paragraph, entry) -> paragraph.revision != entry.revision })
            return@edit EditResult.Conflict
        undo.entries.forEach { writeText(this, it.paragraphId, it.text, it.edited) }
        refreshTitle(this, undo.noteId)
        EditResult.Saved(undo.entries.flatMap { paragraphsIn(this, "id=?", listOf(it.paragraphId.toString())) })
    }

    /** An automatic title follows the first sentence of the current text. */
    private fun refreshTitle(db: SQLiteDatabase, noteId: String) {
        val (seq, current) = db.rawQuery("SELECT seq,title FROM notes WHERE id=? AND title_is_custom=0", arrayOf(noteId)).use {
            if (it.moveToFirst()) it.getLong(0) to it.getString(1) else return
        }
        val title = Title.of(StoreSchema.titleWords(db, noteId))
        if (title == current) return
        db.unindexTitle(seq)
        db.update("notes", ContentValues().apply { put("title", title) }, "id=?", arrayOf(noteId))
        db.indexTitle(seq)
    }

    // ---------------------------------------------------------------- search index

    private fun noteSeq(db: SQLiteDatabase, id: String): Long? =
        db.rawQuery("SELECT seq FROM notes WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun SQLiteDatabase.indexParagraph(id: Long) = execSQL(
        "INSERT INTO paragraph_fts(docid,body) SELECT rowid,body FROM paragraph_search WHERE rowid=?", arrayOf<Any>(id))
    private fun SQLiteDatabase.unindexParagraph(id: Long) =
        execSQL("DELETE FROM paragraph_fts WHERE docid=?", arrayOf<Any>(id))
    private fun SQLiteDatabase.indexTitle(seq: Long) = execSQL(
        "INSERT INTO title_fts(docid,body) SELECT rowid,body FROM title_search WHERE rowid=?", arrayOf<Any>(seq))
    private fun SQLiteDatabase.unindexTitle(seq: Long) =
        execSQL("DELETE FROM title_fts WHERE docid=?", arrayOf<Any>(seq))

    // ---------------------------------------------------------------- deletion and recovery

    /** Moves a note to the trash so the deletion can be undone; [purgeDeleted] deletes it for good. */
    fun deleteWithUndo(id: String): Boolean {
        val deleted = writableDatabase.update("notes", ContentValues().apply { put("deleted_at", System.currentTimeMillis()) },
            "id=? AND deleted_at IS NULL AND state NOT IN ('${NoteState.ACTIVE.joinToString("','")}')", arrayOf(id)) == 1
        if (deleted) changed()
        return deleted
    }

    fun undoDelete(id: String): Boolean {
        val restored = writableDatabase.update("notes", ContentValues().apply { putNull("deleted_at") },
            "id=? AND deleted_at IS NOT NULL", arrayOf(id)) == 1
        if (restored) changed()
        return restored
    }

    /**
     * Permanently deletes a trashed note, or every trashed note. A note restored by [undoDelete] meanwhile is
     * kept: the purge checks the trash inside its own transaction, so an undo either wins or reports false.
     */
    fun purgeDeleted(id: String? = null) = trashed(id).forEach { delete(it, onlyTrashed = true) }

    private fun trashed(id: String?): List<String> = readableDatabase.rawQuery(
        "SELECT id FROM notes WHERE deleted_at IS NOT NULL" + if (id == null) "" else " AND id=?",
        id?.let { arrayOf(it) },
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Permanently deletes a note, its text and its audio now. */
    fun delete(id: String) { delete(id, onlyTrashed = false) }

    private fun delete(id: String, onlyTrashed: Boolean) {
        val deleted = writableDatabase.transaction {
            if (onlyTrashed && rawQuery("SELECT 1 FROM notes WHERE id=? AND deleted_at IS NOT NULL", arrayOf(id)).use { !it.moveToFirst() })
                return@transaction false
            insertWithOnConflict("pending_audio_deletions", null, ContentValues().apply { put("note_id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
            val paragraphIds = rawQuery("SELECT id FROM paragraphs WHERE note_id=?", arrayOf(id)).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }
            paragraphIds.forEach { unindexParagraph(it) }
            noteSeq(this, id)?.let { unindexTitle(it) }
            delete("notes", "id=?", arrayOf(id))
            true
        }
        if (deleted) try { deletePendingAudio(id) } finally { changed() }
    }

    /**
     * Keeps the text and deletes the audio. Only for a ready note with nothing left to transcribe and no
     * failed part: Retry needs the audio of a failed part, which would otherwise be lost for good.
     */
    fun removeAudio(id: String) {
        writableDatabase.transaction {
            val note = checkNotNull(note(id)) { "This note is no longer available." }
            val progress = progress(id)
            check(note.state == NoteState.READY && progress.pendingWindows == 0 && progress.gaps == 0 && progress.remainingMs == 0L) {
                "Finish transcription before removing its source audio."
            }
            check(progress.failedWindows == 0) { "Part of this note couldn't be transcribed. Retry it before removing the audio." }
            insertWithOnConflict("pending_audio_deletions", null, ContentValues().apply { put("note_id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
            update("notes", ContentValues().apply { put("audio_present", 0) }, "id=?", arrayOf(id))
        }
        try { deletePendingAudio(id) } finally { changed() }
    }

    private fun deletePendingAudio(id: String) {
        try { Files.deleteIfExists(audioFile(id).toPath()) }
        catch (e: IOException) { throw IOException("Could not remove saved audio. Focal will retry when it next opens.", e) }
        writableDatabase.delete("pending_audio_deletions", "note_id=?", arrayOf(id))
    }

    /**
     * After process death (all notes) or a failed capture (one note): a recording or transcription that was
     * running waits for transcription, with its duration taken from the synced audio file. At startup the
     * trash is emptied and interrupted audio deletions are retried.
     */
    fun recover(noteId: String? = null) {
        val db = writableDatabase
        val interrupted = db.transaction {
            val scope = if (noteId == null) "" else " AND id=?"
            val args = noteId?.let { arrayOf(it) }
            val active = rawQuery("""SELECT id,state FROM notes WHERE state IN ('${NoteState.ACTIVE.joinToString("','")}')
                AND deleted_at IS NULL$scope""", args).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
            }
            active.forEach { (id, state) ->
                update("notes", ContentValues().apply {
                    put("state", NoteState.INTERRUPTED); put("error", INTERRUPTED_MESSAGE); putNull("pause_reason")
                }, "id=?", arrayOf(id))
                if (noteId == null && state != NoteState.PROCESSING)
                    execSQL("UPDATE notes SET end_reason=? WHERE id=? AND end_reason IS NULL",
                        arrayOf<Any>(CaptureReason.PROCESS_ENDED, id))
            }
            // The synced file is the durable duration of every recording that may still need transcription.
            val waiting = rawQuery("SELECT id FROM notes WHERE state IN ('${NoteState.INTERRUPTED}','${NoteState.ERROR}') " +
                "AND audio_present=1 AND deleted_at IS NULL$scope", args).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            waiting.forEach { id ->
                val file = audioFile(id)
                if (!file.isFile) return@forEach
                RandomAccessFile(file, "rw").use { it.fd.sync() }
                update("notes", ContentValues().apply { put("duration", file.length() / BYTES_PER_MS) }, "id=?", arrayOf(id))
            }
            active.map { it.first }
        }
        // A recording the process lost before its first second was synced holds nothing: discard it, as
        // Finish would have.
        if (noteId == null) interrupted.forEach { id -> try { discardIfEmpty(id) } catch (_: IOException) { } }
        // A deletion that was not undone before the process ended is final. Its rows go now; a failed
        // file deletion stays in the journal and is retried below and at the next start.
        if (noteId == null) trashed(null).forEach { id -> try { delete(id, onlyTrashed = true) } catch (_: IOException) { } }
        // Filesystem and SQLite cannot share a transaction. A committed intent makes
        // deletion retryable after process death or a transient filesystem failure.
        val deletions = db.rawQuery("SELECT note_id FROM pending_audio_deletions", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        deletions.forEach { try { deletePendingAudio(it) } catch (_: IOException) { /* Retry next launch. */ } }
        changed()
    }

    /**
     * Deletes a recording that holds nothing: no saved bytes or exactly constant samples, and no text.
     * Notes from older versions are never discarded. True when it was deleted.
     */
    fun discardIfEmpty(id: String): Boolean {
        val note = note(id) ?: return false
        if (note.legacy || paragraphs(id).isNotEmpty() || !audioIsEmpty(id)) return false
        delete(id)
        return true
    }

    /** Notes waiting for transcription, oldest first: the drain resumes them from a foreground start. */
    fun waitingNotes(): List<String> = readableDatabase.rawQuery(
        "SELECT id FROM notes WHERE state='${NoteState.INTERRUPTED}' AND deleted_at IS NULL AND audio_present=1 ORDER BY created",
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /**
     * A note with the given paragraphs, for UI and service tests. Paragraphs get no word times unless
     * [timedWords] is set, in which case each word gets an even share of its paragraph. [hidden] lists the
     * indices of paragraphs that a legacy background-sound review excluded.
     */
    @VisibleForTesting
    internal fun insertNoteForTests(paragraphs: List<String>, durationMs: Long = 1_000L * paragraphs.size.coerceAtLeast(1),
                                    title: String? = null, state: String = NoteState.READY, legacy: Boolean = false,
                                    created: Long = System.currentTimeMillis(), timedWords: Boolean = false,
                                    hidden: Set<Int> = emptySet()): String {
        val id = insertNote(state, legacy, created, language = if (legacy) "en" else AUTOMATIC_LANGUAGE,
            engine = if (legacy) LEGACY_WHISPER_ENGINE else ULTRA_ENGINE)
        writableDatabase.transaction {
            val span = durationMs / paragraphs.size.coerceAtLeast(1)
            paragraphs.forEachIndexed { i, text ->
                val start = i * span
                val tokens = text.split(' ').filter { it.isNotEmpty() }
                val words = if (!timedWords || tokens.isEmpty()) null else tokens.mapIndexed { k, word ->
                    val step = span / tokens.size
                    Word(word, start + k * step, start + (k + 1) * step, null, false, i)
                }
                val added = insertOrThrow("paragraphs", null, ContentValues().apply {
                    put("note_id", id); put("ord", i); put("start_ms", start); put("end_ms", start + span)
                    put("text", text); put("source_text", if (legacy) null else text)
                    put("words", words?.let(TranscriptJson::encodeWords)); put("hidden", if (i in hidden) 1 else 0)
                })
                indexParagraph(added)
            }
            update("notes", ContentValues().apply { put("duration", durationMs) }, "id=?", arrayOf(id))
            if (title != null) {
                val seq = noteSeq(this, id)!!
                unindexTitle(seq)
                update("notes", ContentValues().apply { put("title", title); put("title_is_custom", 1) }, "id=?", arrayOf(id))
                indexTitle(seq)
            } else refreshTitle(this, id)
        }
        changed()
        return id
    }

    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name)) ?: ""
    private fun Cursor.nullableString(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.nullableLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
}
