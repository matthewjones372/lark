# Dipper

**A stream that names its failure.** A typed Kotlin view over Pekko Streams:
`Stream<E, A>` carries the failure type a pipeline can end with, the element
type can never be null, and running it answers an `Exit` that is `Done`,
`Failed(e)` or `Died(cause)` — never a dropped element and never a failed
future nobody read.

Every operator delegates to Pekko. `toSource()` and `Stream.from(source)` are
the way in and out, so nothing Pekko can do is out of reach.

```kotlin
val settled: CompletionStage<Exit<IngestError, Int>> =
    Stream.from(rows)                                                // Stream<Nothing, Row>
        .mapOrFail { row -> row.customer ?: fail(NoCustomer(row.id)) } // Stream<NoCustomer, Customer>
        .mapAsync(4) { customer -> ledger.settle(customer) }           // CompletionStage<Either<Declined, Receipt>>
        .divertLefts(to = declinedSink)                                // Stream<NoCustomer, Receipt>
        .runFold(0) { n, _ -> n + 1 }
        .run(system)
```

Nothing is built yet. `specs/` says what will be, and in what order; read
[AGENTS.md](AGENTS.md) before working on it.

## License

Apache 2.0 — see [LICENSE](LICENSE).
