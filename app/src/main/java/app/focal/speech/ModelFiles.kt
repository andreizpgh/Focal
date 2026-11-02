package app.focal.speech

import android.content.Context
import java.io.File
import java.io.InputStream

class ModelFiles(private val context: Context) {
    val model = File(context.filesDir, NAME)
    fun ready() = model.isFile && model.length() > 10_000_000
    fun installBundled() {
        if (ready()) return
        if (context.assets.list("")?.contains(NAME) == true) context.assets.open(NAME).use(::install)
    }
    fun install(input: InputStream) {
        val partial = File(context.filesDir, "$NAME.partial")
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    total += count; require(total <= 200_000_000) { "Choose a multilingual Whisper base model (under 200 MB)." }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            require(partial.length() > 10_000_000) { "This file is not a complete speech model." }
            val handle = NativeWhisper.open(partial.absolutePath)
            NativeWhisper.close(handle)
            require(partial.renameTo(model)) { "Could not save the model. Check free storage." }
        } finally { partial.delete() }
    }
    companion object { const val NAME = "ggml-base-q5_1.bin" }
}
