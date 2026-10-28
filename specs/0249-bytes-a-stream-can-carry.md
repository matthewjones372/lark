# 0249 — Bytes a stream can carry

## Problem

Bytes never travel through a Surefold stream. Every module that moves them drops to `java.io` and does the fiddly
parts by hand:

- **`surefold-files`.** `Files.get` hands back an `InputStream`, and `put` and `MultipartUpload.part` take one. To
  import a large CSV from S3, a service either reads the whole object into memory or writes its own loop.
- **`surefold-actor-remote`.** `Wire.kt` writes a 4-byte length before each frame, refuses one over `MAX_FRAME`, and
  reads frames back with `DataInputStream.readInt`, all by hand.
- **The bank example.** `Sse.kt` turns each frame into bytes and writes it to an `OutputStream` itself.

`Stream<E, Byte>` is not the answer: it boxes every byte. ZStream solves this with `Chunk[Byte]`, and it ships the
operators people otherwise get wrong: UTF-8 decoding when a character is split across two reads, lines that cross a
read boundary, and length-prefixed frames.

## Not doing

- **A generic `Chunk<A>`.** `List<A>` already does that job. Only bytes need their own type.
- **`Ints`, `Longs` or other primitive blocks.** Not until something needs one.
- **Moving streams to chunk-at-a-time internals.** Elements still move one at a time. A `ByteChunk` is one element.
- **`surefold-files` and `actor-remote` on the new API.** Each is its own spec once this lands.
- **Reading files from disk or opening sockets.** `read` takes any `InputStream` the caller opens.
- **A `java.nio` module.** Reads park a virtual thread, so selectors and async channels buy nothing here, and a
  `Path` is one `Files.newInputStream` away.

## Shape

```kotlin
// A block of bytes nobody can change. Slicing shares the array; from and toByteArray copy.
ByteChunk.of(0x01, 0x02)                     // bytes listed one by one, as Stream.of
ByteChunk.from(array)                        // a copy of a ByteArray
ByteChunk.from(array, from = 4, until = 12)  // a copy of part of one
ByteChunk.from(buffer)                       // a copy of a ByteBuffer's remaining bytes; its position is untouched
ByteChunk.from("héllo")                      // UTF-8
ByteChunk.empty()
val bytes: ByteChunk = ByteChunk.from("héllo")
bytes.size; bytes[0]; bytes.slice(0, 2); bytes + other; bytes.decodeToString(); bytes.writeTo(out)
bytes.toByteArray(); bytes.asReadOnlyBuffer()   // a copy; a read-only ByteBuffer over the same bytes, no copy

// In and out of an InputStream and an OutputStream.
Stream.read({ socket.getInputStream() }, chunkSize = 64 * 1024)   // Stream<Nothing, ByteChunk>
    .runInto(out)                                                  // Run<Nothing, Long>: the bytes written

// Text.
Stream.read({ files.get(key).getOrThrow().stream })
    .decodeUtf8()                                    // Stream<Nothing, String>, safe on a character split by a read
    .lines()                                         // one String per line, \n or \r\n, the last one unended too
    .drop(1)
    .map(::parseOrder)
    .runCollect()

lines.encodeUtf8()                                   // Stream<E, ByteChunk>

// Frames: a 4-byte big-endian length, then the frame, as Wire.kt writes them today.
messages.map(codec::encode).framed().runInto(socket.getOutputStream())
Stream.read({ socket.getInputStream() }).unframed(maxSize = 16 shl 20)   // Stream<Nothing, ByteChunk>, one per frame
```

- **`read`** opens the stream once per run, through `Stream.blocking`, and closes it however the run ends. A
  `stop()` closes it to wake a blocked `read`, since `InputStream.read` ignores interrupts. An `IOException` is a
  defect, as in any other blocking source.
- **`runInto`** writes each block as it comes and does not close `out`: the caller opened it.
- **`unframed`** fails the run with a defect when a length is over `maxSize`, or when the stream ends partway
  through a frame.

## Why this shape

Every operator is built from what already exists: `read` from `Stream.blocking`, `runInto` from `runFold`, and the
text and frame operators from `statefulMap` and `mapConcat`. So all backends get them with no backend changes, and
no new `Node`. `ByteChunk` is a final class over a `ByteArray` with an offset and length, so `slice` and `unframed` do
not copy.

**Alternative:** carry `ByteArray` and say "do not change it" in the docs. Recommended against: a `ByteArray` can
always be changed, it has no structural equality, and slicing it means copying.

## Stack

- [x] **`spec-0249-byte-chunk`** ([#893](https://github.com/matthewjones372/surefold/pull/893)): `ByteChunk` in `surefold`.
      Done when: every `from` and `toByteArray` copy, so changing the array or buffer afterwards leaves the chunk
      unchanged; `slice` copies nothing; `asReadOnlyBuffer` cannot write; two chunks with equal contents are equal
      and hash the same.
- [ ] **`spec-0249-read-write`**: `Stream.read` and `runInto` in `surefold-stream`.
      Done when: a 1 MiB input comes out the same in every backend; the `InputStream` is closed on success, failure
      and `stop()`; a `stop()` wakes a `read` that is blocked.
- [ ] **`spec-0249-text`**: `decodeUtf8`, `encodeUtf8`, `lines`.
      Done when: a text with 1-, 2-, 3- and 4-byte characters, split at every byte offset, decodes to the same
      `String`; `lines` gives the same lines however the input is split.
- [ ] **`spec-0249-frames`**: `framed` and `unframed`.
      Done when: frames survive a round trip at every split; a length over `maxSize` and an end partway through a
      frame are defects.

## Acceptance

```bash
./gradlew :surefold:build :surefold-stream:build :surefold-stream-forks:build :surefold-stream-pekko:build
```

## Open questions

1. **Does `ByteChunk` live in `surefold` or `surefold-stream`?** Answered: `surefold`, so `surefold-files` and
   `actor-remote` can take it without depending on streams.
2. **What is it called?** Answered: `ByteChunk`. `Bytes` clashes with Guava's `Bytes` and the AWS SDK's `SdkBytes`
   (already in `surefold-files`), and `ByteString` with Pekko's, okio's and kotlinx-io's. `ByteChunk` is ZIO's name
   for the same thing and is free on the JVM.
3. **What does `decodeUtf8` do with malformed input?** Answered: replace it with U+FFFD, as Kotlin's own
   `decodeToString` does. A strict `decodeUtf8(strict = true)` that fails the run can follow.
4. **Is an `IOException` from `read` a defect, or a typed error?** Answered: a defect, as in every other blocking
   source. `catchAll` already turns one into a value where a caller wants that.
