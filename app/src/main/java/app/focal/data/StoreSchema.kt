package app.focal.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.focal.core.TextWords
import app.focal.core.Title
import java.io.File

/**
 * Schema 6 of focal.db and the upgrades from every earlier schema (6 has schema 5's tables: only automatic
 * titles were derived again).
 *
 * - `notes`: one recording. `seq` is the note's search document id; `legacy` marks notes transcribed
 *   before schema 5; `deleted_at` is set while a deletion can still be undone.
 * - `windows`: segmentation windows with their audio, own and keep ranges, state and immutable raw ASR
 *   (text plus token JSON with times and log-probabilities).
 * - `paragraphs`: the editable body. `source_text` and `words` keep what the recognizer produced.
 * - `gaps`: ranges of migrated notes that no window covers yet. `breaks`: Pause positions (samples).
 * - `paragraph_fts` / `title_fts`: FTS4 external-content indexes over folded paragraph text and titles.
 *   Every paragraph and note row has exactly one index entry, which must be deleted before the row's
 *   indexed text changes and inserted again afterwards, in the same transaction.
 * - `pending_audio_deletions`: the journal that makes audio file deletion retryable.
 */
internal object StoreSchema {
    const val VERSION = 6
    const val UNTITLED_V4 = "Untitled note"
    private const val SECONDS_BYTES = 32L

    /** Upgrades run with foreign keys off (NoteStore enables them in onOpen), so tables can be rebuilt. */
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,
            language TEXT NOT NULL,state TEXT NOT NULL,duration INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '',
            automatic_title_chunk TEXT,speech_engine TEXT NOT NULL DEFAULT 'whisper')""")
        createDeletionJournal(db)
        addNoteColumns(db)
        createTables(db)
    }

    fun upgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int, audioDir: File) {
        check(oldVersion in 1 until VERSION && newVersion == VERSION) { "Missing database migration" }
        if (oldVersion == 5) {
            retitle(db)
            return
        }
        if (oldVersion == 1) createDeletionJournal(db)
        if (oldVersion < 3) {
            // Older text may already include manual edits. Its original provenance is unknown.
            db.execSQL("ALTER TABLE passages ADD COLUMN original_transcript_text TEXT")
            db.execSQL("ALTER TABLE passages ADD COLUMN excluded INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN automatic_title_chunk TEXT")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE notes ADD COLUMN speech_engine TEXT NOT NULL DEFAULT 'whisper'")
            db.execSQL("ALTER TABLE chunks ADD COLUMN next_start INTEGER")
        }
        upgradeToFive(db, audioDir)
    }

    /**
     * Schema 5 to 6: nothing in the tables changes. The titles schema 5 derived are derived again, because the rule for
     * a first sentence longer than the limit changed (whole words to a clause end, no ellipsis). Renamed notes keep
     * theirs, and so do notes from before schema 5 ([upgradeToFive] kept the titles they came with), so a database
     * ends with the same titles whether it passed through schema 5 or not.
     */
    private fun retitle(db: SQLiteDatabase) {
        val derived = db.rawQuery("SELECT id,title FROM notes WHERE title_is_custom=0 AND legacy=0", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        var changed = false
        for ((id, current) in derived) {
            val title = Title.of(titleWords(db, id))
            if (title == current) continue
            db.update("notes", ContentValues().apply { put("title", title) }, "id=?", arrayOf(id))
            changed = true
        }
        if (changed) db.execSQL("INSERT INTO title_fts(title_fts) VALUES('rebuild')")
    }

    private fun createDeletionJournal(db: SQLiteDatabase) {
        // No foreign key: the intent must survive deleting the corresponding note.
        db.execSQL("CREATE TABLE pending_audio_deletions(note_id TEXT PRIMARY KEY)")
    }

    private fun addNoteColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN seq INTEGER")
        db.execSQL("ALTER TABLE notes ADD COLUMN title_is_custom INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE notes ADD COLUMN legacy INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE notes ADD COLUMN audio_present INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE notes ADD COLUMN pause_reason TEXT")
        db.execSQL("ALTER TABLE notes ADD COLUMN end_reason TEXT")
        db.execSQL("ALTER TABLE notes ADD COLUMN deleted_at INTEGER")
    }

    private fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE UNIQUE INDEX notes_seq ON notes(seq)")
        db.execSQL("CREATE INDEX notes_created ON notes(created)")
        db.execSQL("""CREATE TABLE windows(id INTEGER PRIMARY KEY AUTOINCREMENT,
            note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,idx INTEGER NOT NULL,
            own_start INTEGER NOT NULL,own_end INTEGER NOT NULL,audio_start INTEGER NOT NULL,audio_end INTEGER NOT NULL,
            keep_lo INTEGER,keep_hi INTEGER,end_kind TEXT,decide_at INTEGER NOT NULL,
            speech_ms INTEGER NOT NULL DEFAULT 0,peak INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL,skip TEXT,
            hidden INTEGER NOT NULL DEFAULT 0,engine TEXT,attempts INTEGER NOT NULL DEFAULT 0,
            raw_text TEXT,tokens TEXT,error TEXT,UNIQUE(note_id,idx))""")
        db.execSQL("CREATE INDEX windows_state ON windows(note_id,state)")
        db.execSQL("""CREATE TABLE gaps(note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
            start INTEGER NOT NULL,end INTEGER NOT NULL,PRIMARY KEY(note_id,start))""")
        db.execSQL("""CREATE TABLE breaks(note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
            sample INTEGER NOT NULL,PRIMARY KEY(note_id,sample))""")
        db.execSQL("""CREATE TABLE paragraphs(id INTEGER PRIMARY KEY AUTOINCREMENT,
            note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,ord INTEGER NOT NULL,
            start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,text TEXT NOT NULL,source_text TEXT,words TEXT,
            edited INTEGER NOT NULL DEFAULT 0,hidden INTEGER NOT NULL DEFAULT 0,open INTEGER NOT NULL DEFAULT 0,
            revision INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX paragraphs_note ON paragraphs(note_id,ord)")
        val fold = { column: String -> SearchText.SQL_FOLD_PREFIX + column + SearchText.SQL_FOLD_SUFFIX }
        db.execSQL("""CREATE VIEW paragraph_search AS SELECT id AS rowid,
            CASE WHEN hidden=0 THEN ${fold("text")} ELSE '' END AS body FROM paragraphs""")
        db.execSQL("CREATE VIEW title_search AS SELECT seq AS rowid,${fold("title")} AS body FROM notes")
        val tokenizer = tokenizer(db)
        db.execSQL("CREATE VIRTUAL TABLE paragraph_fts USING fts4(content=\"paragraph_search\",body,$tokenizer)")
        db.execSQL("CREATE VIRTUAL TABLE title_fts USING fts4(content=\"title_search\",body,$tokenizer)")
    }

    /** remove_diacritics=2 needs SQLite 3.27 (Android 11); older devices get =1, which differs only rarely. */
    private fun tokenizer(db: SQLiteDatabase): String {
        val version = db.rawQuery("SELECT sqlite_version()", null).use { it.moveToFirst(); it.getString(0) }
        val (major, minor) = version.split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0)
        val level = if (major > 3 || (major == 3 && minor >= 27)) 2 else 1
        return "tokenize=unicode61 \"remove_diacritics=$level\""
    }

    /**
     * Schema 4 to 5. Passages become paragraphs 1:1 with their ids and current text; their original
     * transcript becomes the source text (NULL stays unknown); background-sound exclusions become hidden
     * paragraphs; nothing gets word times. Completed chunks become migrated windows. For notes that were
     * not finished, every saved range that no completed chunk covers becomes a gap for Ultra to transcribe.
     */
    private fun upgradeToFive(db: SQLiteDatabase, audioDir: File) {
        addNoteColumns(db)
        db.execSQL("""UPDATE notes SET seq=rowid,legacy=1,
            title_is_custom=CASE WHEN automatic_title_chunk IS NULL AND title!='$UNTITLED_V4' THEN 1 ELSE 0 END,
            title=CASE WHEN automatic_title_chunk IS NULL AND title='$UNTITLED_V4' THEN '' ELSE title END""")
        db.execSQL("""UPDATE notes SET audio_present=0
            WHERE id IN (SELECT note_id FROM pending_audio_deletions)""")
        createTables(db)
        db.execSQL("""INSERT INTO paragraphs(id,note_id,ord,start_ms,end_ms,text,source_text,words,edited,hidden,open,revision)
            SELECT id,note_id,0,start,end,text,original_transcript_text,NULL,
            CASE WHEN original_transcript_text IS NULL THEN reviewed!=0 WHEN text=original_transcript_text THEN 0 ELSE 1 END,
            excluded!=0,0,0 FROM passages WHERE note_id IN (SELECT id FROM notes)""")
        db.compileStatement("UPDATE paragraphs SET ord=? WHERE id=?").use { setOrd ->
            db.rawQuery("SELECT id,note_id FROM passages WHERE note_id IN (SELECT id FROM notes) ORDER BY note_id,start,id", null).use { c ->
                var note: String? = null
                var ord = 0L
                while (c.moveToNext()) {
                    if (c.getString(1) != note) { note = c.getString(1); ord = 0 }
                    setOrd.bindLong(1, ord++); setOrd.bindLong(2, c.getLong(0)); setOrd.executeUpdateDelete()
                }
            }
        }
        data class LegacyNote(val id: String, val state: String, val engine: String, val audioPresent: Boolean)
        val notes = db.rawQuery("SELECT id,state,speech_engine,audio_present FROM notes", null).use { c ->
            buildList { while (c.moveToNext()) add(LegacyNote(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1)) }
        }
        for (note in notes) {
            // Before schema 5, Remove audio deleted the file and then its journal row, so only the missing
            // file tells that the audio of such a note is gone.
            val present = note.audioPresent && File(audioDir, "${note.id}.pcm").isFile
            if (!present && note.audioPresent) db.execSQL("UPDATE notes SET audio_present=0 WHERE id=?", arrayOf<Any>(note.id))
            migrateWindows(db, note.id, note.state, note.engine, present, audioDir)
        }
        db.execSQL("DROP TABLE passages")
        db.execSQL("DROP TABLE chunks")
        // Notes saved before any text arrived get an automatic title from their text, if they have any.
        db.rawQuery("SELECT id FROM notes WHERE title='' AND title_is_custom=0", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.forEach { id ->
            val title = Title.of(titleWords(db, id))
            if (title.isNotEmpty()) db.update("notes", ContentValues().apply { put("title", title) }, "id=?", arrayOf(id))
        }
        db.execSQL("INSERT INTO paragraph_fts(paragraph_fts) VALUES('rebuild')")
        db.execSQL("INSERT INTO title_fts(title_fts) VALUES('rebuild')")
    }

    private fun migrateWindows(db: SQLiteDatabase, id: String, state: String, engine: String, audioPresent: Boolean,
                               audioDir: File) {
        data class Chunk(val start: Long, val end: Long, val state: String)
        val chunks = db.rawQuery("SELECT start,end,state FROM chunks WHERE note_id=? ORDER BY start,rowid", arrayOf(id)).use { c ->
            buildList { while (c.moveToNext()) add(Chunk(c.getLong(0), c.getLong(1), c.getString(2))) }
        }
        var index = 0
        for (chunk in chunks) {
            if (chunk.state != "DONE" || chunk.end <= chunk.start) continue
            db.insertOrThrow("windows", null, ContentValues().apply {
                put("note_id", id); put("idx", index++)
                put("own_start", chunk.start); put("own_end", chunk.end)
                put("audio_start", chunk.start); put("audio_end", chunk.end)
                putNull("end_kind"); put("decide_at", chunk.end)
                put("state", WindowState.DONE.name); put("engine", engine)
            })
        }
        val unfinished = state != NoteState.READY || chunks.any { it.state != "DONE" }
        if (!unfinished) return
        val file = File(audioDir, "$id.pcm")
        val savedMs = if (audioPresent && file.isFile) file.length() / SECONDS_BYTES else 0L
        val covered = chunks.filter { it.state == "DONE" }.map { Coverage.Range(it.start, it.end) }
        val gaps = Coverage.uncovered(covered, savedMs)
        gaps.forEach { gap ->
            db.insertOrThrow("gaps", null, ContentValues().apply {
                put("note_id", id); put("start", gap.startMs); put("end", gap.endMs)
            })
        }
        db.update("notes", ContentValues().apply {
            if (savedMs > 0) put("duration", savedMs)
            if (gaps.isEmpty()) {
                // Nothing is left to transcribe: the note is complete as saved.
                put("state", NoteState.READY)
            } else {
                put("state", NoteState.INTERRUPTED)
                put("error", "Recording was interrupted. Saved audio can still be transcribed.")
            }
        }, "id=?", arrayOf(id))
    }

    /** The leading words of a note's visible text, enough for [Title.of]. */
    fun titleWords(db: SQLiteDatabase, noteId: String): List<String> =
        db.rawQuery("""SELECT substr(text,1,600) FROM paragraphs WHERE note_id=? AND hidden=0 AND trim(text)!=''
            ORDER BY ord LIMIT 3""", arrayOf(noteId)).use { c ->
            buildList { while (c.moveToNext()) addAll(TextWords.split(c.getString(0))) }
        }
}
