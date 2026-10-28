package io.github.matthewjones372.surefold.stream

import io.github.matthewjones372.surefold.ByteChunk
import java.io.InputStream
import java.io.OutputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

private const val DEFAULT_CHUNK_SIZE = 64 * 1024

/**
 * What [open] gives, in chunks of at most [chunkSize] bytes (spec 0249). It is opened once per run and closed
 * however the run ends. A stop closes it too, since `InputStream.read` ignores an interrupt; an `IOException` is a
 * defect, as from any blocking source.
 */
fun Stream.Companion.read(open: () -> InputStream, chunkSize: Int = DEFAULT_CHUNK_SIZE): Stream<Nothing, ByteChunk> {
    require(chunkSize > 0) { "a read takes at least one byte at a time, and chunkSize $chunkSize was asked for" }
    return Stream.blocking(
        open = open,
        next = { input -> input.nextChunk(chunkSize) },
        wake = InputStream::close,
        close = InputStream::close,
    )
}

private fun InputStream.nextChunk(chunkSize: Int): ByteChunk? {
    val buffer = ByteArray(chunkSize)
    val read = read(buffer)
    return if (read < 0) null else ByteChunk.from(buffer, 0, read)
}

/** Writes each chunk to [out] as it comes and answers how many bytes it wrote. [out] stays open: the caller owns it. */
fun <E> Stream<E, ByteChunk>.runInto(out: OutputStream): Run<E, Long> =
    runFold(0L) { written, chunk ->
        chunk.writeTo(out)
        written + chunk.size
    }

/**
 * Chunks read as UTF-8 text. A character split across two chunks is decoded once both have come, and input that
 * is not UTF-8, or a character the stream ends inside, decodes to U+FFFD, as `decodeToString` does.
 */
fun Pipe.Companion.decodeUtf8(): Pipe<Nothing, ByteChunk, String> =
    Pipe.statefulMap<ByteChunk, ByteChunk, String>(
        ByteChunk::empty,
        { held, chunk -> decoded(held + chunk, endOfInput = false) },
        { held -> decoded(held, endOfInput = true).second.ifEmpty { null } },
    ).filter { it.isNotEmpty() }

fun <E, In> Pipe<E, In, ByteChunk>.decodeUtf8(): Pipe<E, In, String> = via(Pipe.decodeUtf8())

fun <E> Stream<E, ByteChunk>.decodeUtf8(): Stream<E, String> = via(Pipe.decodeUtf8())

/** Each string as UTF-8, one chunk per string. */
fun Pipe.Companion.encodeUtf8(): Pipe<Nothing, String, ByteChunk> = Pipe.map(ByteChunk::from)

fun <E, In> Pipe<E, In, String>.encodeUtf8(): Pipe<E, In, ByteChunk> = via(Pipe.encodeUtf8())

fun <E> Stream<E, String>.encodeUtf8(): Stream<E, ByteChunk> = via(Pipe.encodeUtf8())

/**
 * Text as lines, ended by `\n` or `\r\n` however the text was split into strings. The last line needs no ending,
 * and text that ends in one has no empty line after it.
 */
fun Pipe.Companion.lines(): Pipe<Nothing, String, String> =
    Pipe.statefulMap<String, String, List<String>>(
        { "" },
        { partial, text -> (partial + text).split('\n').let { it.last() to it.dropLast(1).map(::unended) } },
        { partial -> partial.takeIf { it.isNotEmpty() }?.let(::listOf) },
    ).mapConcat { it }

fun <E, In> Pipe<E, In, String>.lines(): Pipe<E, In, String> = via(Pipe.lines())

fun <E> Stream<E, String>.lines(): Stream<E, String> = via(Pipe.lines())

private fun unended(line: String): String = line.removeSuffix("\r")

/**
 * [bytes] decoded as far as they go, and what is left: the start of a character the next chunk will finish. A
 * decoder holds no bytes between calls of its own, so one made for each call carries nothing over.
 */
private fun decoded(bytes: ByteChunk, endOfInput: Boolean): Pair<ByteChunk, String> {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val input = bytes.asReadOnlyBuffer()
    val output = CharBuffer.allocate((bytes.size * decoder.maxCharsPerByte()).toInt() + 1)
    decoder.decode(input, output, endOfInput)
    if (endOfInput) decoder.flush(output)
    return bytes.slice(input.position(), bytes.size) to output.flip().toString()
}
