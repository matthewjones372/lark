# 0109 — Metrics a service can read back

## Problem

Lark's `Metrics` is write-only: `counter`, `gauge` and `histogram` hand a measurement to a backend, and nothing asks
the backend what it now holds. A service that shows its own numbers (the bank's live ops page shows asks a second,
ask p99, command rates and its shards and entities) has to reach past Lark to the backend's registry: the bank reads
Micrometer's `registry.find(...)` for its own `bank.*` meters and for Lark's own `lark.sharding.*` gauges. The call
sites that write through Lark then read through Micrometer, and a test binding `capturingMetrics` cannot see what the
page would show.

## Not doing

- **Aggregation, rates or quantiles.** A reading is what the backend holds now; a rate is two readings apart, and a
  quantile over an interval is a caller's arithmetic on two histograms' buckets. Lark does not keep history.
- **Gauges read on a callback.** A gauge is still set. A value that is expensive to read is read on a timer by the
  service and set, which is one line of `Stream.tick`, not a new instrument.
- **A query language.** A reading is found by exact name, every tag set of it returned.

## Shape

```kotlin
/** What one instrument holds now, under its name and one set of its tags. */
sealed interface Reading {
    val name: String
    val tags: Map<String, String>

    data class Total(override val name: String, override val tags: Map<String, String>, val total: Double) : Reading
    data class Value(override val name: String, override val tags: Map<String, String>, val value: Double) : Reading
    data class Distribution(
        override val name: String,
        override val tags: Map<String, String>,
        val count: Long,
        val sum: Double,
        /** How many recorded values were at or below each bound, where the backend keeps buckets; else empty. */
        val buckets: Map<Double, Double>,
    ) : Reading
}

interface Metrics {
    // counter, gauge and histogram, as today
    /** Every instrument named [name], one reading per set of tags; none where the backend keeps nothing. */
    fun read(name: String): List<Reading> = emptyList()
}

fun readings(name: String): List<Reading> = metrics.get().read(name)
```

```kotlin
val asks = readings("bank.ask.duration").filterIsInstance<Reading.Distribution>()
val applied = readings("bank.account.commands").filterIsInstance<Reading.Total>().filter { it.tags["outcome"] == "applied" }
```

- **`MicrometerMetrics.read`** finds every meter of the name in its registry: a counter is a `Total`, a gauge a
  `Value`, a distribution summary a `Distribution` with its histogram's buckets, where a meter filter gave it some.
- **`CapturedMetrics.read`** answers what it captured, a histogram as a `Distribution` of its values with no buckets.
- **`NoMetrics.read`** answers nothing, as it records nothing.

## Why this shape

Readings by name, with every tag set, cover what a page or a probe asks ("the p99 of this", "these by outcome")
without Lark holding anything it does not already hand to the backend: the backend is the store, as it is for
writing. A default method on `Metrics` keeps every adapter written before this compiling, answering nothing. The
alternative, Lark keeping its own copy of every instrument, would double what a busy node holds, to answer a
question the backend already can.

## Depends on

Nothing.

## Stack

- [x] **`spec-0109-readings`** — `Reading`, `Metrics.read` and `readings`, in `lark`, `CapturedMetrics` and
      `lark-micrometer`.
      Done when: a counter, a gauge and a bucketed histogram written through Lark read back through Lark, from
      Micrometer and from `capturingMetrics` alike, each tag set its own reading.

## Acceptance

```bash
./gradlew build
```

## Settled

1. **Rates and quantiles in Lark?** No: a reading is now; the caller subtracts.
2. **Callback gauges?** No: a gauge is set, on a timer where reading its value costs something.
