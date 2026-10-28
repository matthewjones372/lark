package io.github.matthewjones372.surefold.stream

import io.github.matthewjones372.surefold.ByteChunk
import java.io.InputStream
import java.io.OutputStream

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
