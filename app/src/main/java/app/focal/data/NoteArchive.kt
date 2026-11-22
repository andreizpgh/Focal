package app.focal.data

import app.focal.core.Transcript
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Offline export (human-readable Markdown ZIP) and full backup/restore (JSON + optional PCM audio ZIP).
 * Works purely through Android SAF streams — no network or storage permissions.
 */
object NoteArchive {
    private const val BACKUP_VERSION = 1
    private const val BACKUP_MANIFEST = "backup.json"
    private const val AUDIO_PREFIX = "audio/"
    private const val AUDIO_SUFFIX = ".pcm"
    private const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024
    private const val MAX_AUDIO_ENTRY_BYTES = 1024L * 1024 * 1024
    private const val BUFFER_BYTES = 65_536
    private val FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm", Locale.US)
    private val HUMAN_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
    private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")

    /** Outcome of restoring an offline backup archive. */
    data class ImportSummary(val imported: Int, val skipped: Int)

    /**
     * Exports every settled note in [store] as individual Markdown files inside one ZIP stream.
     * Returns the number of exported notes.
     */
    fun exportMarkdownZip(store: NoteStore, out: OutputStream, zone: ZoneId = ZoneId.systemDefault()): Int {
        val notes = store.library().filter { it.state !in NoteState.ACTIVE }
        val usedNames = HashSet<String>()
        var exported = 0
        ZipOutputStream(out).use { zip ->
            for (note in notes) {
                val paragraphs = store.paragraphs(note.id).filter { it.visible && it.text.isNotBlank() }
                val instant = Instant.ofEpochMilli(note.created).atZone(zone)
                val title = note.title.ifBlank { "Note at ${TIME_ONLY.format(instant)}" }
                val baseName = "${FILE_DATE.format(instant)}_${slugify(title)}"
                var fileName = "$baseName.md"
                var suffix = 2
                while (!usedNames.add(fileName.lowercase(Locale.ROOT))) {
                    fileName = "${baseName}-${suffix++}.md"
                }
                val content = buildString {
                    append("# ").append(title).append("\n\n")
                    append("Date: ").append(HUMAN_DATE.format(instant)).append('\n')
                    append("Duration: ").append(Transcript.time(note.duration)).append("\n\n")
                    paragraphs.forEachIndexed { index, p ->
                        if (index > 0) append("\n\n")
                        append(p.text)
                    }
                    append('\n')
                }
                zip.putNextEntry(ZipEntry(fileName))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                exported++
            }
        }
        return exported
    }

    /**
     * Exports every settled note in [store] as a restorable backup ZIP (`backup.json` + optional `audio/<id>.pcm`).
     * Streams audio in 64 KB chunks so memory usage stays flat regardless of library size.
     */
    fun exportBackupZip(store: NoteStore, out: OutputStream, includeAudio: Boolean): Int {
        val notes = store.library().filter { it.state !in NoteState.ACTIVE }
        val notesJson = JSONArray()
        val audioEntries = ArrayList<Pair<String, File>>()
        for (note in notes) {
            val paragraphs = store.paragraphs(note.id).filter { it.visible }
            val audioFile = if (includeAudio && note.audioPresent) store.audioFile(note.id).takeIf { it.isFile } else null
            if (audioFile != null) audioEntries += note.id to audioFile
            val pArray = JSONArray()
            for (p in paragraphs) {
                pArray.put(JSONObject().apply {
                    put("ord", p.ord)
                    put("startMs", p.startMs)
                    put("endMs", p.endMs)
                    put("text", p.text)
                    if (p.sourceText != null) put("sourceText", p.sourceText)
                    if (p.wordsJson != null) put("wordsJson", p.wordsJson)
                    put("edited", p.edited)
                })
            }
            notesJson.put(JSONObject().apply {
                put("id", note.id)
                put("title", note.title)
                put("titleIsCustom", note.titleIsCustom)
                put("created", note.created)
                put("language", note.language)
                put("duration", note.duration)
                put("speechEngine", note.speechEngine)
                put("audioIncluded", audioFile != null)
                put("paragraphs", pArray)
            })
        }
        val manifest = JSONObject().apply {
            put("version", BACKUP_VERSION)
            put("app", "Focal")
            put("exportedAt", System.currentTimeMillis())
            put("includeAudio", includeAudio)
            put("notes", notesJson)
        }
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(BACKUP_MANIFEST))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            val buffer = ByteArray(BUFFER_BYTES)
            for ((noteId, file) in audioEntries) {
                zip.putNextEntry(ZipEntry("$AUDIO_PREFIX$noteId$AUDIO_SUFFIX"))
                file.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
        return notes.size
    }

    /**
     * Restores notes from a Focal backup ZIP non-destructively (skips any note ID already present in [store]).
     * Validates entry names against path traversal and cleans up temporary staging files in all cases.
     */
    fun importBackupZip(store: NoteStore, input: InputStream, tempRoot: File): ImportSummary {
        val stageDir = File(tempRoot, "focal-restore-${System.nanoTime()}").apply { mkdirs() }
        try {
            var manifestBytes: ByteArray? = null
            val stagedAudio = HashMap<String, File>()
            val buffer = ByteArray(BUFFER_BYTES)
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || name.startsWith("/") || name.contains("..")) {
                        zip.closeEntry()
                        continue
                    }
                    when {
                        name == BACKUP_MANIFEST -> {
                            val out = ByteArrayOutputStream()
                            var total = 0
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_MANIFEST_BYTES) throw IOException("Backup manifest is too large.")
                                out.write(buffer, 0, read)
                            }
                            manifestBytes = out.toByteArray()
                        }
                        name.startsWith(AUDIO_PREFIX) && name.endsWith(AUDIO_SUFFIX) -> {
                            val noteId = name.removePrefix(AUDIO_PREFIX).removeSuffix(AUDIO_SUFFIX)
                            if (SAFE_ID.matches(noteId)) {
                                val tempAudio = File(stageDir, "$noteId$AUDIO_SUFFIX")
                                var total = 0L
                                FileOutputStream(tempAudio).use { out ->
                                    while (true) {
                                        val read = zip.read(buffer)
                                        if (read < 0) break
                                        total += read
                                        if (total > MAX_AUDIO_ENTRY_BYTES) throw IOException("Backup audio entry is too large.")
                                        out.write(buffer, 0, read)
                                    }
                                }
                                stagedAudio[noteId] = tempAudio
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val raw = manifestBytes ?: throw IOException("Not a valid Focal backup.")
            val root = JSONObject(raw.toString(Charsets.UTF_8))
            val version = root.optInt("version", -1)
            if (version != BACKUP_VERSION) throw IOException("Unsupported backup version.")
            val notesArray = root.optJSONArray("notes") ?: throw IOException("Missing notes list.")
            var imported = 0
            var skipped = 0
            for (i in 0 until notesArray.length()) {
                val obj = notesArray.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                if (!SAFE_ID.matches(id)) continue
                if (store.noteExists(id)) {
                    skipped++
                    continue
                }
                val pArray = obj.optJSONArray("paragraphs") ?: JSONArray()
                val paragraphs = ArrayList<NoteStore.RestoredParagraph>(pArray.length())
                for (j in 0 until pArray.length()) {
                    val pObj = pArray.optJSONObject(j) ?: continue
                    paragraphs += NoteStore.RestoredParagraph(
                        ord = pObj.optInt("ord", j),
                        startMs = pObj.optLong("startMs", 0L),
                        endMs = pObj.optLong("endMs", 0L),
                        text = pObj.optString("text", ""),
                        sourceText = if (pObj.has("sourceText") && !pObj.isNull("sourceText")) pObj.getString("sourceText") else null,
                        wordsJson = if (pObj.has("wordsJson") && !pObj.isNull("wordsJson")) pObj.getString("wordsJson") else null,
                        edited = pObj.optBoolean("edited", false),
                    )
                }
                val wantAudio = obj.optBoolean("audioIncluded", false)
                val staged = if (wantAudio) stagedAudio[id] else null
                var hasAudio = false
                if (staged != null && staged.isFile) {
                    val dest = store.audioFile(id)
                    val destTmp = File(store.audioDir, "$id.restore.tmp")
                    try {
                        staged.inputStream().use { src ->
                            FileOutputStream(destTmp).use { dst ->
                                while (true) {
                                    val read = src.read(buffer)
                                    if (read < 0) break
                                    dst.write(buffer, 0, read)
                                }
                                dst.fd.sync()
                            }
                        }
                        if (!destTmp.renameTo(dest)) {
                            destTmp.copyTo(dest, overwrite = true)
                            destTmp.delete()
                        }
                        hasAudio = true
                    } catch (_: Exception) {
                        destTmp.delete()
                        hasAudio = false
                    }
                }
                val inserted = store.importRestoredNote(
                    id = id,
                    title = obj.optString("title", ""),
                    titleIsCustom = obj.optBoolean("titleIsCustom", false),
                    created = obj.optLong("created", System.currentTimeMillis()),
                    language = obj.optString("language", AUTOMATIC_LANGUAGE),
                    durationMs = obj.optLong("duration", 0L),
                    speechEngine = obj.optString("speechEngine", ULTRA_ENGINE),
                    audioPresent = hasAudio,
                    paragraphs = paragraphs,
                )
                if (inserted) {
                    imported++
                } else {
                    if (hasAudio) runCatching { store.audioFile(id).delete() }
                    skipped++
                }
            }
            return ImportSummary(imported = imported, skipped = skipped)
        } finally {
            stageDir.deleteRecursively()
        }
    }

    private fun slugify(title: String): String {
        val sb = StringBuilder(48)
        var dash = false
        for (ch in title.trim()) {
            when {
                ch.isLetterOrDigit() -> {
                    sb.append(ch.lowercaseChar())
                    dash = false
                }
                !dash && sb.isNotEmpty() -> {
                    sb.append('-')
                    dash = true
                }
            }
            if (sb.length >= 44) break
        }
        val clean = sb.toString().trimEnd('-')
        return clean.ifEmpty { "note" }
    }
}
