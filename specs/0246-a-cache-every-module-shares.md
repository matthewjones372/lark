# 0246 — A cache every module shares

## Problem

`KeyCache` (0235) is the only real cache in Surefold, and it is private to `surefold-tenancy-crypto`. It loads each
key once however many threads ask, serves a stale key while the KMS is down, and evicts the least recently used
owner. Spec 0240's flags provider would write the same again, and a service that caches a price list or a tenant's
settings writes it a third time. The alternative is Caffeine, which a `NoOtherDependenciesTest` refuses, and which
knows nothing of Surefold's clock, `Either`, tenants, scopes or invalidation.

Each copy gets the hard parts slightly differently:
- whether two callers both load;
- whether a failure is remembered, and for how long;
- whether a stale value outlives the outage;
- whether another process's change is seen before the age runs out;
- whether a test can drive any of it without sleeping.

A service that runs many instances wants a second layer too: on a miss in memory, ask a store every instance shares
before going to the source, so ten instances that restart together ask the source once each per key, not ten times.

## Not doing

- No source of its own. `surefold-cache` depends on `surefold` alone: no database, no stream, no cluster, no
  serializer. A shared layer, and whatever says a value changed, is an adapter in another module.
- No Postgres layer. Postgres says a value changed; Redis is the shared layer, since it holds bytes with an expiry
  and that is all a layer needs.
- No write-through. The source is written as it is today, and the cache is told to drop a key.
- No size by weight. The bound is a count of entries.

## Shape

The lookup is given once, when the cache is made, as ZIO Cache does it: a cache is a function with a memory, not a
map a caller fills. Memory is a list of layers, nearest first. The default is one, in this process.

```kotlin
// One layer, in memory: what almost every caller wants.
val prices: Cache<String, PriceError, Price> = Cache(
    ttl = { result -> if (result is Either.Right) 30.seconds else 2.seconds },  // a failure is remembered too
    stale = 10.minutes,                     // while the lookup fails, the last good value answers this long
    retry = Schedule.exponential<PriceError>(100.milliseconds) and Schedule.recurs(2),  // within one lookup
    refreshing = Schedule.spaced(25.seconds),   // ahead of expiry, for keys read since the last refresh
    layers = listOf(InMemory(capacity = 10_000)),
) { sku -> loadPrice(sku) }                  // (String) -> Either<PriceError, Price>

prices.get("sku-1")                          // Either<PriceError, Price>: one lookup per key, however many ask
prices.refresh("sku-1")                      // look up again in the background; the old value answers until then
prices.invalidate(Invalidation.Key("sku-1"))
prices.invalidate(Invalidation.All)          // "something may have been missed": drop everything
prices.stats()                               // per layer: hits, misses, loads, failures, stale, evictions, size

// Two layers: memory, then Redis, which every instance shares, then the source (surefold-cache-redis).
val redis: Layer<String, Price> = RedisLayer(connection, prefix = "prices", codec = priceCodec)
val prices2 = Cache(ttl = { 30.seconds }, layers = listOf(InMemory(10_000), redis)) { sku -> loadPrice(sku) }
RedisInvalidations.listen(client, "prices", prices2) { message -> message }  // pub/sub; close it to stop

val settings = TenantCache(ttl = { 1.minutes }) { key: String -> load(key) }   // surefold-cache-tenancy
```

```kotlin
/** One layer of memory: nearest first. In memory is this module's; anything shared is an adapter's. */
interface Layer<K : Any, V : Any> {
    fun read(key: K): Stored<V>?             // the value, when it was stored, and when it expires
    fun write(key: K, stored: Stored<V>)
    fun remove(key: K)
    fun clear()
}
```

- **Reads go down, values come back up.** `get` asks each layer in turn. A hit in a far layer is written into every
  nearer one, keeping its original expiry, so a value is never younger in memory than it was in the shared store.
  A miss in every layer runs the lookup once in this process, and writes the result into every layer.
- **Failures stay near.** A `Left` is kept only in the in-memory layer, so one instance's outage is not shared with
  the rest. A shared layer holds only `Right`s.
- **One lookup per key, per process,** through 0247's `SingleFlight`. Callers for a key that is loading wait on that
  one lookup, on virtual threads, and are answered its result. A caller who stops waiting does not cancel it for
  the others. Across processes, the shared layer is what saves the source.
- **Failures have their own life.** `ttl` reads the result, so a `Left` can be kept for two seconds and a `Right`
  for thirty. A storm of requests against a source that is down asks it once per two seconds, not once each.
- **Schedules are Surefold's own `Schedule`**, the one `PostgresNotifications` reconnects on:
  - `retry` reruns a failing lookup inside one `get`, on the bound clock, before its `Left` is kept. Every waiter
    shares the retries; none is added per caller.
  - `refreshing` drives refresh-ahead. After each lookup the schedule is stepped with its result, and its delay is
    when the key is looked up again in the background. A key nobody read since the last refresh is let expire
    instead, so the cache does not keep refreshing keys no one wants. A schedule that stops ends that key's
    refreshes until it is looked up again.
  - Both run in the cache's scope, so a `TestClock` drives them and closing the cache stops them.
- **Stale on failure.** When a lookup for an expired entry fails and the last `Right` is younger than `stale`, that
  value answers. `stats().stale` counts it.
- **A layer that fails is skipped.** A shared store that cannot be reached is a miss, counted, never an error to the
  caller. The source still answers.
- **`refresh`** looks up in the cache's own scope and writes the result to every layer only if it is a `Right`. The
  cache is a resource: closing it, or the app graph that opened it, stops every refresh in flight.
- **`InMemory`** admits past `capacity` on a frequency sketch (TinyLFU, as Caffeine does), so a scan of keys read
  once cannot flush the keys read all day. Within that, the least recently read entry goes. Its `onEvict` sees each
  entry that goes; `KeyCache` zeroes its keys there.
- **Time** is the clock bound where the cache was made. A `TestClock` moves every age in every layer, so no test
  sleeps.
- **`TenantCache`**, in `surefold-cache-tenancy` so `surefold-cache` stays on core alone, keys every entry, in every
  layer, by `TenantKey(currentTenant(), key)`, and looks up as the key's tenant. With no tenant bound, `get` answers
  `NoTenant` rather than sharing an entry between tenants.
- **`RedisLayer`** in `surefold-cache-redis` stores each `Right` as the codec's bytes under `prefix:key`, with
  Redis's own expiry set from `Stored.expiresAt`, so Redis drops it when memory would. `RedisInvalidations` is the
  same module's invalidation adapter, over Redis pub/sub, for services with Redis and no Postgres.
- **Invalidation is a port.** The cache takes `Invalidation<K>`, `Key(k)` or `All`, from whatever knows a value
  changed, and removes it from every layer, far first, so a nearer layer is not refilled from a stale far one.
  Adapters turn a source's events into invalidations: `surefold-cache-postgres` maps a notice's payload to `Key`
  and `Notice.Gap` to `All`. A cluster topic (0082), Kafka, or a webhook would each be a few lines of their own.

## Why this shape

Making the lookup part of the cache means every caller gets the same rules for a failure, a stale value and a
refresh, and a test checks them once on a `TestClock`. Layers keep that one set of rules whatever sits underneath:
memory alone today, memory over a shared store later, with no change at the call site. Invalidation as a stream of
`Key` and `All` keeps the core free of every source.

Against Caffeine, this keeps its admission policy and stats and adds what it lacks:
- typed failures with their own lifetime;
- stale-on-failure;
- layers, with a shared store under memory;
- tenant keys;
- structured refresh that a closed graph stops;
- a deterministic clock;
- invalidation from any source.

**Alternatives:**
- Caffeine behind a port. Recommended against: a second dependency in every module that caches, and its executor
  and ticker are not Surefold's.
- A layer that is itself a `Cache` (caches nested). Recommended against: two sets of single-flight and ttl rules
  that can disagree. Layers are plain storage; the rules live once, in `Cache`.

## Stack

- [x] **`spec-0246-cache`** ([#886](https://github.com/matthewjones372/surefold/pull/886)): `surefold-cache` in core, with `Cache`, `get`, `ttl` by result, `stale`, `invalidate`
      and `stats`, over `InMemory` without a bound yet.
      Done when: on a `TestClock`:
      - twenty threads asking for one missing key run its lookup once;
      - a `Left` with a 2-second ttl is looked up again after 2 seconds and not before;
      - a failing lookup answers the stale value until `stale` runs out, and the error after.
- [x] **`spec-0246-admission`** ([#889](https://github.com/matthewjones372/surefold/pull/889)): `InMemory`'s `capacity`, the frequency sketch, `onEvict`.
      Done when:
      - a key read a hundred times survives a scan of ten thousand keys read once;
      - `onEvict` sees every entry that goes.
- [x] **`spec-0246-layers`** ([#890](https://github.com/matthewjones372/surefold/pull/890)): the `Layer` port, reads down and writes up, failures kept near, a failing layer
      skipped.
      Done when, over an in-memory fake of a shared layer:
      - a hit in the far layer fills the near one with its original expiry;
      - a `Left` never reaches the far layer;
      - a far layer that throws is a miss and the lookup still answers;
      - `invalidate` removes from the far layer before the near.
- [x] **`spec-0246-schedules`** ([#891](https://github.com/matthewjones372/surefold/pull/891)): `retry`, `refreshing`, `refresh`, and closing the cache.
      Done when, on a `TestClock`:
      - a lookup failing twice then succeeding, under `recurs(2)`, answers the value, with one lookup in flight;
      - with `refreshing = spaced(25.seconds)`, a key read within each window is looked up at 25 and 50 seconds;
      - a key not read since its last refresh is not refreshed again;
      - during a refresh, `get` answers the old value, and a failed refresh keeps it;
      - closing the cache stops a refresh that is blocked in its lookup.
- [x] **`spec-0246-tenant`** ([#895](https://github.com/matthewjones372/surefold/pull/895)): `TenantCache`, in `surefold-cache-tenancy`.
      Done when:
      - two tenants reading one key each look up their own value, in every layer;
      - with no tenant bound, `get` answers `NoTenant`.
- [x] **`spec-0246-postgres`** ([#892](https://github.com/matthewjones372/surefold/pull/892)): `surefold-cache-postgres`, with `invalidations`, the first adapter.
      Done when: on Postgres, a `NOTIFY` from another connection drops its key before its ttl, and a `Gap` drops
      every entry.
- [x] **`spec-0246-redis`** ([#902](https://github.com/matthewjones372/surefold/pull/902)): `surefold-cache-redis`, with `RedisLayer`, a `Codec`, and `RedisInvalidations`.
      Done when, on Redis:
      - a value one cache stored is read by a second cache with an empty memory, without its lookup running;
      - Redis expires it at its `expiresAt`;
      - a published invalidation drops it from both caches.
- [x] **`spec-0246-keys`** ([#903](https://github.com/matthewjones372/surefold/pull/903)): `KeyCache`'s one unwrap per key on `SingleFlight`, keeping its own rules: a loader per
      call, the clock bound at each call, stale only while the KMS is unreachable, and its bound and eviction by owner.
      `Cache` is not bent to fit those four.
      Done when: `KeyCacheSuite` passes unchanged, and `KeyCache` holds no lock map of its own.

## Acceptance

```bash
./gradlew :surefold-cache:build :surefold-cache-postgres:build :surefold-cache-redis:build \
    :surefold-tenancy-crypto:build
```

## Open questions

1. **TinyLFU admission, or plain LRU?** Recommended: TinyLFU. It is what makes Caffeine's hit rate, it is about a
   hundred lines (a count-min sketch with periodic halving), and it is tested by the scan case above.
2. **Should a caller who stops waiting cancel the lookup?** Recommended: no, as ZIO Cache does. Others may be
   waiting on it, and its result is worth keeping. Only closing the cache stops a lookup.
3. **Which Redis client?** Recommended: Lettuce. It is thread-safe on one connection, has pub/sub, and is the one
   Spring and Micronaut ship. The dependency is `surefold-cache-redis`'s alone.
4. **How does a shared layer turn values into bytes?** Recommended: a `Codec<V>` the adapter takes, not one this
   module chooses, so `surefold-cache` stays free of any serializer.
5. **One lookup per key across instances** (a Redis lock while one instance looks up)? Recommended: not now. The
   shared layer already turns N instances' lookups into one each after the first, and a lock is a second thing to
   get wrong under failover.
6. **Cluster invalidation over 0082's topic, in this spec?** Recommended: deferred. It is another adapter onto the
   same `Invalidation`, and no service has asked for it yet.
7. **Ship the Postgres invalidation adapter here?** Recommended: yes, as its own module, since 0240's flags need it
   first. A service that never uses Postgres never sees it.
