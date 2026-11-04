package app.focal.speech

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Validates the on-disk legacy ggml structure before native code reads untrusted files. */
internal object BaseModelValidator {
    private data class Tensor(val dimensions: List<Int>, val kind: Kind)
    private enum class Kind { FLOAT, CONV, WEIGHT }
    private data class Type(val block: Int, val bytes: Int)
    private val types = mapOf(0 to Type(1, 4), 1 to Type(1, 2), 2 to Type(32, 18),
        3 to Type(32, 20), 6 to Type(32, 22), 7 to Type(32, 24), 8 to Type(32, 34))
    private val weightTypes = mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 3, 7 to 8, 8 to 6, 9 to 7)
    private const val MESSAGE = "Choose a complete multilingual Whisper base model in a supported format."

    fun validate(file: File, checkCancelled: () -> Unit = {}) {
        try {
            RandomAccessFile(file, "r").use { f ->
                fun integer(): Int = Integer.reverseBytes(f.readInt())
                fun skip(bytes: Long) {
                    require(bytes >= 0 && bytes <= f.length() - f.filePointer) { MESSAGE }
                    f.seek(f.filePointer + bytes)
                }
                require(integer() == 0x67676d6c) { MESSAGE }
                val header = List(10) { integer() }
                require(header == listOf(51865, 1500, 512, 8, 6, 448, 512, 8, 6, 80)) { MESSAGE }
                val ftype = integer()
                require(ftype >= 0 && ftype / 1000 <= 2) { MESSAGE }
                val weightType = weightTypes[ftype % 1000] ?: throw IllegalArgumentException(MESSAGE)
                require(integer() == 80 && integer() == 201) { MESSAGE }
                skip(80L * 201 * 4)
                val vocab = integer()
                require(vocab in 50257..51865) { MESSAGE }
                // Token metadata is small and sequential. Bound the read to the
                // maximum legal vocabulary and resume tensor parsing at its end.
                checkCancelled()
                val vocabularyStart = f.filePointer
                val vocabularyLimit = minOf((f.length() - vocabularyStart).coerceAtLeast(0),
                    2_000_000L + 4L * vocab).toInt()
                val vocabulary = ByteArray(vocabularyLimit)
                f.readFully(vocabulary)
                val tokens = ByteBuffer.wrap(vocabulary).order(ByteOrder.LITTLE_ENDIAN)
                var vocabularyBytes = 0L
                repeat(vocab) {
                    if (it > 0 && it % 1024 == 0) checkCancelled()
                    if (tokens.remaining() < Int.SIZE_BYTES) throw EOFException()
                    val length = tokens.int
                    require(length in 0..512) { MESSAGE }
                    vocabularyBytes += length
                    require(vocabularyBytes <= 2_000_000) { MESSAGE }
                    require(length <= tokens.remaining()) { MESSAGE }
                    tokens.position(tokens.position() + length)
                }
                f.seek(vocabularyStart + tokens.position())
                val remaining = tensors().toMutableMap()
                while (f.filePointer < f.length()) {
                    checkCancelled()
                    val dimensions = integer()
                    val length = integer()
                    val type = integer()
                    require(dimensions in 1..3 && length in 1..128) { MESSAGE }
                    val shape = List(dimensions) { integer() }
                    val nameBytes = ByteArray(length); f.readFully(nameBytes)
                    require(nameBytes.all { it.toInt() in 32..126 }) { MESSAGE }
                    val name = nameBytes.toString(Charsets.US_ASCII)
                    val expected = remaining.remove(name) ?: throw IllegalArgumentException(MESSAGE)
                    require(shape == expected.dimensions) { MESSAGE }
                    val expectedType = when (expected.kind) {
                        Kind.FLOAT -> 0
                        Kind.CONV -> if (weightType == 0) 0 else 1
                        Kind.WEIGHT -> weightType
                    }
                    require(type == expectedType) { MESSAGE }
                    val typeInfo = types[type] ?: throw IllegalArgumentException(MESSAGE)
                    require(shape.first() % typeInfo.block == 0) { MESSAGE }
                    val elements = shape.fold(1L) { acc, n -> acc * n }
                    skip(elements / typeInfo.block * typeInfo.bytes)
                }
                require(remaining.isEmpty()) { MESSAGE }
            }
        } catch (e: java.io.EOFException) {
            throw IllegalArgumentException(MESSAGE, e)
        }
    }

    private fun tensors(): Map<String, Tensor> = buildMap {
        fun add(name: String, kind: Kind, vararg dimensions: Int) { put(name, Tensor(dimensions.toList(), kind)) }
        add("encoder.positional_embedding", Kind.FLOAT, 512, 1500)
        add("encoder.conv1.weight", Kind.CONV, 3, 80, 512)
        add("encoder.conv1.bias", Kind.FLOAT, 1, 512)
        add("encoder.conv2.weight", Kind.CONV, 3, 512, 512)
        add("encoder.conv2.bias", Kind.FLOAT, 1, 512)
        listOf("encoder.ln_post", "decoder.ln").forEach { prefix ->
            add("$prefix.weight", Kind.FLOAT, 512); add("$prefix.bias", Kind.FLOAT, 512)
        }
        add("decoder.positional_embedding", Kind.FLOAT, 512, 448)
        add("decoder.token_embedding.weight", Kind.WEIGHT, 512, 51865)
        for (part in listOf("encoder", "decoder")) for (layer in 0 until 6) {
            val prefix = "$part.blocks.$layer"
            for (name in listOf("mlp_ln", "attn_ln") + if (part == "decoder") listOf("cross_attn_ln") else emptyList()) {
                add("$prefix.$name.weight", Kind.FLOAT, 512); add("$prefix.$name.bias", Kind.FLOAT, 512)
            }
            add("$prefix.mlp.0.weight", Kind.WEIGHT, 512, 2048)
            add("$prefix.mlp.0.bias", Kind.FLOAT, 2048)
            add("$prefix.mlp.2.weight", Kind.WEIGHT, 2048, 512)
            add("$prefix.mlp.2.bias", Kind.FLOAT, 512)
            for (attention in listOf("attn") + if (part == "decoder") listOf("cross_attn") else emptyList()) {
                for (name in listOf("query", "key", "value", "out")) {
                    add("$prefix.$attention.$name.weight", Kind.WEIGHT, 512, 512)
                    if (name != "key") add("$prefix.$attention.$name.bias", Kind.FLOAT, 512)
                }
            }
        }
    }
}
