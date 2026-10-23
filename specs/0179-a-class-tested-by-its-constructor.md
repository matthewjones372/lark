# 0179 — A class tested by its constructor

## Problem

Lark's docs teach the graph as the way to test one class. `docs/app.md#testing`
and the cookbook's "Test one thing, not everything" both show:

```kotlin
testApp(app.subgraph<RegisterUser>().overriding(single<UserRepo> { InMemoryUsers() })) { register: RegisterUser ->
    register.register("ada") shouldBe Registered("ada")
}
```

`RegisterUser` takes one `UserRepo`. The test reaches through the whole
application's wiring to build what `RegisterUser(InMemoryUsers())` builds in
one call. A wiring change can then break a test that says nothing about wiring,
and the constructor, which is the component's real seam, never appears.

Petshop copied the pattern: `PostgresOutboxSpec` builds `Petshop.module` to
reach an `Outbox` over a `DataSource`, and `FeedSpec` does the same for `Feed`.

## Not doing

- **Any change to `testApp`, `subgraph` or `overriding`.** They stay, for
  the tests they suit.
- **A lint or compiler check.** Docs only.
- **Petshop.** Its tests move once this ships (list at the end).

## Shape

The docs give one rule: **a test of one class calls its constructor; a test
whose claim spans wiring uses the graph.**

```kotlin
// One class, its collaborators as arguments.
RegisterUser(InMemoryUsers()).register("ada") shouldBe Registered("ada")

// A claim about the wiring: the real nodes, one faked, released however the block ends.
testApp(app.subgraph<Checkout>().overriding(single<Payments> { DecliningPayments() })) { checkout: Checkout ->
    checkout.order(basket) shouldBe Declined
}
```

The graph is for claims that only hold with the nodes together: start and
release order, readiness, a use case across an actor, a pool and a migration,
a fake replacing one node among real ones.

- `docs/app.md#testing` opens with the rule, then the graph example.
- The cookbook recipe becomes "Test the wiring, not one class", with both
  fences compiled by `CookbookTest`.
- `docs/testing.md`'s table row reads "several nodes together, one faked";
  a new row reads "one class" → its constructor.

## Why this shape

The constructor is the narrowest seam a class has, and a test through it
breaks only when the class does. `subgraph` earns its place where the claim
is about nodes together, which is exactly where hand-wiring would repeat
production's assembly and drift from it. The alternative, keeping the graph
as the single way in for consistency, is what produced tests coupled to
wiring they do not test.

## Stack

- [ ] **`spec-0179-docs`**: the rule and both examples in `docs/app.md`, the
      cookbook and `docs/testing.md`.
      Done when: `CookbookTest` and `ReadmeExampleTest` compile the new
      fences, and no doc shows `subgraph` around a single-dependency class.

## Acceptance

```bash
./gradlew :lark-app:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. Is a `PetShop` over its actor, database and outbox "one class" or
   "wiring"? **Wiring**: its claim needs the actor and the database together,
   so petshop's adoption specs stay on the graph.
2. Should a class whose constructor is `internal` get a test-only factory,
   or should its test stay on the graph? **Neither**: the test sits in the
   same module and calls the `internal` constructor.

Petshop follow-up: `PostgresOutboxSpec`, `FeedSpec` and `EventBusContract`
construct their component; `EndToEnd`, `Restart`, `Readiness`, `Scrape` and the
`PetShop` specs stay on the graph.
