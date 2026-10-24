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

## Flags of other types

A timeout, a cut-over time, a mode or a small structure is declared as the
type the code wants, and decoded once, beside the key and the default:

<!-- flags-types -->
```kotlin
import dev.openfeature.sdk.Value
import io.github.matthewjones372.lark.openfeature.Flag
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

enum class Checkout { Classic, Express }

data class Window(val from: Int, val to: Int)

object CheckoutFlags {
    val maxBasket = Flag.long("checkout.max-basket", default = 50L)
    val registryTimeout = Flag.duration("checkout.registry-timeout", default = 2.seconds)
    val newRulesFrom = Flag.instant("checkout.new-rules-from", default = Instant.MAX)
    val checkout = Flag.enum("checkout.mode", default = Checkout.Classic)
    val window = Flag.of("checkout.window", default = Window(0, 24)) { value: Value ->
        value.asStructure().let { Window(it.getValue("from").asInteger(), it.getValue("to").asInteger()) }
    }
}
```

| Factory | The provider holds | Decoded by |
|---|---|---|
| `Flag.long` | an integer | the SDK |
| `Flag.duration` | a string such as `"5s"`, `"1m 30s"` or `"PT5S"` | `Duration.parse` |
| `Flag.instant` | an ISO-8601 string | `Instant.parse` |
| `Flag.enum` | a string naming a constant exactly | `enumValueOf` |
| `Flag.of` | an object | the function you pass |

A value that will not decode answers the default, with `PARSE_ERROR` and the
reason in `details()`, and is warned about and counted like any other fall-back.

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
