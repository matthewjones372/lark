# Feature flags

`lark-openfeature` reads [OpenFeature](https://openfeature.dev) flags on
virtual threads. Who a flag is evaluated for is bound once, where a request
comes in, and every flag read below it answers for that caller: in a `parZip`
branch, in a fork, and in an actor's handler. A flag is declared once, with its
key, its type and its default, and read with no client passed in.

```kotlin
dependencies {
    // brings lark, the OpenFeature SDK and the slf4j-api it logs through
    implementation("io.github.matthewjones372:lark-openfeature:0.10.1")
}
```

Call `OpenFeatureAPI.getInstance().useLark()` once, in `main`, before setting a
provider. It keeps the SDK's evaluation context where forks and messages can
see it, and adds the hook that counts evaluations.

In a `lark-app` graph, `lark-app-openfeature`'s `openFeature { provider }` does
this for you. The provider is a node keyed by `Client`, set before any node
that takes one is built. A provider whose init throws refuses the start, the
graph reports not ready while the provider is in error or stale, and release
shuts the SDK down. `Flag.value()` reads the same provider. One graph per JVM
can hold it, and a second is refused.

## Declaring and reading a flag

<!-- flags-declare -->
```kotlin
import dev.openfeature.sdk.FlagEvaluationDetails
import io.github.matthewjones372.lark.openfeature.Flag
import io.github.matthewjones372.lark.openfeature.evaluatedFor
import io.github.matthewjones372.lark.parZip

object AdoptionFlags {
    val registryCheck = Flag.boolean("adoption.registry-check", default = true)
    val perAdopterLimit = Flag.int("adoption.per-customer-limit", default = 3)
}

fun limits(customer: String, tier: String): Pair<Boolean, Int> =
    evaluatedFor(customer, "tier" to tier) {
        // Both branches answer for this customer: the context crosses the fork.
        parZip({ AdoptionFlags.registryCheck.value() }, { AdoptionFlags.perAdopterLimit.value() }) { check, limit ->
            check to limit
        }
    }

fun why(customer: String): FlagEvaluationDetails<Int> =
    evaluatedFor(customer) { AdoptionFlags.perAdopterLimit.details() }
```

- `Flag.boolean`, `Flag.string`, `Flag.int` and `Flag.double` each declare a
  flag. They are separate factories, so a default of `3` or `3.0` cannot pick
  the type silently.
- `value()` answers the flag, or its default when it cannot be evaluated: a
  flag never fails a request. `details()` adds the variant, the reason and,
  when it fell back, the error code.
- `evaluatedFor(targetingKey, attributes…) { }` binds who the flag is for. A
  nested call adds its attributes to the ones around it.
- A message sent to an actor carries the targeting key and every string,
  boolean, int, long and double attribute, each arriving as the type it was.
  Structures, lists and instants stay behind.

## What gets counted

Every evaluation is counted under `openfeature.evaluations`, tagged `flag`,
`variant` and `reason`. One that fell back to its default also writes a
warning naming the flag and the error code, and is counted under
`openfeature.errors`, tagged `flag` and `code`. Both go to whatever logger and
metrics the service bound, the same as [Lark's own](observability.md).

## Testing

`featureClient` is the client a flag is read through. A test binds one with a
provider of its own, and the forks it opens read the same one:

<!-- flags-test -->
```kotlin
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.providers.memory.InMemoryProvider
import io.github.matthewjones372.lark.openfeature.Flag
import io.github.matthewjones372.lark.openfeature.featureClient
import io.github.matthewjones372.lark.openfeature.useLark
import dev.openfeature.sdk.providers.memory.Flag as MemoryFlag

val perAdopterLimit = Flag.int("adoption.per-customer-limit", default = 3)

fun aTest(): Int {
    val api = OpenFeatureAPI.getInstance().apply { useLark() }
    val five = MemoryFlag.builder<Int>().variant("on", 5).defaultVariant("on").build()
    api.setProviderAndWait("a-test", InMemoryProvider(mapOf("adoption.per-customer-limit" to five)))

    return featureClient.locally(api.getClient("a-test")) { perAdopterLimit.value() }
}
```
