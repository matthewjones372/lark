# 0224 — Files on any S3

## Problem

A booking service keeps tickets as PDFs, venue images and uploaded ID scans. Each service writes its own wrapper
around the AWS SDK: its own key layout, its own handling of `NoSuchKeyException` thrown from four places, no
deadline, a test that needs real AWS or nothing. Moving from AWS to Cloudflare R2, Hetzner or Backblaze to save money
means finding every place that assumed AWS's endpoint, path style or error codes.

## Not doing

- No image processing, virus scanning or CDN: storage only.
- No HTTP: a presigned URL is handed to a client by the service's own endpoint, on Pelican.
- No async API: every call blocks on a virtual thread, as the rest of Surefold does.
- No spans of its own: the service puts OpenTelemetry's AWS SDK instrumentation on the `S3Client` it builds.

## Shape

```kotlin
val files: Files = S3Files.of(
    FilesConfig(endpoint = URI("https://<account>.r2.cloudflarestorage.com"), region = "auto", bucket = "tickets",
        accessKey = key, secretKey = Secret(secret), pathStyle = true, maxSize = 50.megabytes),
    policy = policy("files") { deadline(10.seconds); retry(Schedule.exponential<Throwable>(200.milliseconds).take(3)) },
)

val key = TenantPrefix(tenantId).key("tickets/$bookingId.pdf")      // "tenants/<id>/tickets/<id>.pdf", validated
val stored: Either<FileError, FileMeta> = files.put(key, stream, size = bytes, contentType = "application/pdf")
files.get(key).map { it.use { content -> content.stream.copyTo(out) } }
files.presignPut(key, expiresIn = 10.minutes, contentType = "image/jpeg", size = 2_000_000)   // a URL for the browser
```

- **Operations:** `put`, `get` (streaming, `AutoCloseable`), `head`, `delete`, `list(prefix)` (paged), `copy`,
  `presignPut`, `presignGet`, and `multipart(key)` returning an upload with `part`, `complete` and `abort`.
- **Typed failures**, never an SDK exception: `FileError.NotFound`, `AccessDenied`, `TooLarge`, `Unavailable` (a
  network failure, a 5xx, a throttle, the policy's deadline or breaker) and `InvalidKey`.
- **Keys** are a value class checked on construction: non-empty, at most 1,024 bytes, no leading `/`, no `..` segment,
  no control characters. `TenantPrefix` namespaces by tenant.
- **Every call** runs under the store's `Policy`, by default a 10-second deadline and three retries of `Unavailable`.
- **Metrics:** `surefold.files.duration.seconds` by operation and outcome, `surefold.files.bytes` by direction.
- **A fake:** `InMemoryFiles` implements `Files`, with the same failures, for unit tests.
- **A test kit:** `surefold-files-test` starts MinIO in Testcontainers and answers a `Files` on a fresh bucket.

## Why this shape

One interface, two implementations, and a contract suite that runs against both, so the fake cannot drift from S3.
The SDK runs on its URL-connection client, as `surefold-cluster-aws` does, so no Netty or Apache client comes with
it. **Alternative:** MinIO's own Java client; recommended against, since it is S3 everywhere but AWS itself.

## Stack

- [x] **`spec-0224-api`** ([#787](https://github.com/matthewjones372/surefold/pull/787)) — the module, `Files`, `FileKey`, `TenantPrefix`, `FileError`, `InMemoryFiles`.
      Done when: the contract suite passes against the fake, and a key with `..` is refused.
- [x] **`spec-0224-s3`** ([#788](https://github.com/matthewjones372/surefold/pull/788)) — `S3Files` for put, get, head, delete, list and copy; `surefold-files-test` with MinIO.
      Done when: the contract suite passes against MinIO, and a stopped MinIO answers `Unavailable` within the deadline.
- [x] **`spec-0224-presign`** ([#789](https://github.com/matthewjones372/surefold/pull/789)) — presigned PUT and GET with expiry, content type and size, and multipart upload.
      Done when: a presigned PUT of the wrong size is refused by MinIO, and a 12 MB upload in three parts reads back.
- [x] **`spec-0224-docs`** ([#790](https://github.com/matthewjones372/surefold/pull/790)) — `docs/files.md`, the module tables, metrics.
      Done when: the guide's example is held to a compiled source, as the jobs guide is.

## Acceptance

```bash
./gradlew :surefold-files:build :surefold-files-test:build
```

## Open questions

Taken as recommended (agent, 2026-10-27, on the maintainer's instruction to keep going overnight; revisit in review).

1. **`Either` or `Fail`?** Recommended: `Either<FileError, A>` from each call, since a call is usually bound inside a
   handler's own `Fail` scope, and `Either` crosses threads where a `Fail` scope does not.
2. **Size limits on a presigned PUT:** S3 cannot sign a range for a PUT. Recommended: sign the exact
   `Content-Length`, which the caller states and which must be within `maxSize`.
3. **The SDK's own retries:** Recommended: off, so the store's `Policy` is the one place retries are decided.
