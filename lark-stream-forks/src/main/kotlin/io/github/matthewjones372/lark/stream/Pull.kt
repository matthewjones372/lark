package io.github.matthewjones372.lark.stream

import arrow.core.Either
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage

/**
 * One element at a time, on the thread that asks. `null` is the end, which no element can be, because
 * every element is `Any`. A stage only runs when the one after it asks, so a `take` stops its source.
 */
internal fun interface Pull {
    fun next(): Any?
}

/** The operators a pull runs: every one that needs no second thread and no clock. */
internal fun Node.pulls(): Boolean =
    when (this) {
        is Node.Elements, is Node.Single, Node.Empty, is Node.Fail, is Node.FromStage,
        is Node.Map, is Node.MapOrFail, is Node.Filter, is Node.FilterNot, is Node.Take, is Node.Drop,
        is Node.TakeWhile, is Node.DropWhile, is Node.Grouped, is Node.Scan, is Node.StatefulMap,
        is Node.MapConcat, is Node.Either, is Node.Absolve, is Node.CatchAll, is Node.MapError,
        is Node.OrFailIfEmpty, is Node.Concat, is Node.Prepend, is Node.ZipWith, is Node.Fused,
        -> true

        is Node.FlatMap -> breadth == null

        is Node.Native, is Node.Stage, Node.Hole, is Node.Tick, is Node.GroupedWithin, is Node.Sliding,
        is Node.MapAsync, is Node.Conflate, is Node.MapPar, is Node.Merge, is Node.Interleave,
        is Node.RestartOnDefect,
        -> false
    }

/** The pull a node describes. `start` has refused every node [pulls] says no to before this is reached. */
internal fun Node.pull(): Pull =
    when (this) {
        is Node.Elements -> elements.iterator().let { items -> Pull { items.takeIf { it.hasNext() }?.next() } }

        is Node.Single -> once { element }

        Node.Empty -> Pull { null }

        is Node.Fail -> Pull { throw DeclaredFailure(error) }

        is Node.FromStage -> once { awaited(this) }

        is Node.Map -> map()

        is Node.MapOrFail -> mapOrFail()

        is Node.Filter -> kept("filter", at) { a -> predicate(a) }

        is Node.FilterNot -> kept("filterNot", at) { a -> !predicate(a) }

        is Node.Take -> take()

        is Node.Drop -> drop()

        is Node.TakeWhile -> takeWhile()

        is Node.DropWhile -> dropWhile()

        is Node.Grouped -> grouped()

        is Node.Scan -> scan()

        is Node.StatefulMap -> statefulMap()

        is Node.MapConcat -> mapConcat()

        is Node.Either -> either()

        is Node.Absolve -> absolve()

        is Node.CatchAll -> catchAll()

        is Node.MapError -> mapError()

        is Node.OrFailIfEmpty -> orFailIfEmpty()

        is Node.FlatMap -> flatMapConcat()

        is Node.Concat -> upstream.then(next)

        is Node.Prepend -> first.then(upstream)

        is Node.ZipWith -> zipWith()

        is Node.Fused -> fusedLoop()

        is Node.Native, is Node.Stage, Node.Hole, is Node.Tick, is Node.GroupedWithin, is Node.Sliding,
        is Node.MapAsync, is Node.Conflate, is Node.MapPar, is Node.Merge, is Node.Interleave,
        is Node.RestartOnDefect,
        -> error("$operator reached the Forks runner, which start refuses it before")
    }

private fun once(element: () -> Any): Pull {
    var given = false
    return Pull {
        if (given) {
            null
        } else {
            given = true
            element()
        }
    }
}

/** A stage's value on the pulling thread, with what failed it rather than the wrapper the JDK adds. */
@Suppress("UNCHECKED_CAST")
private fun awaited(from: Node.FromStage): Any =
    try {
        (from.stage as CompletionStage<Any>).checked(from.onNull).toCompletableFuture().join()
    } catch (wrapped: CompletionException) {
        throw wrapped.cause ?: wrapped
    }

private fun Node.Map.map(): Pull {
    val body = guarded("map", at, f)
    val up = upstream.pull()
    return Pull { up.next()?.let(body) }
}

private fun Node.MapOrFail.mapOrFail(): Pull {
    val scope = Failing<Any?>()
    val body = guarded("mapOrFail", at) { a: Any -> scope.f(a) }
    val up = upstream.pull()
    return Pull { up.next()?.let(body) }
}

private fun Node.Unary.kept(operator: String, at: String, test: (Any) -> Boolean): Pull {
    val guard = guarded(operator, at, test)
    val up = upstream.pull()
    return Pull {
        var a = up.next()
        while (a != null && !guard(a)) a = up.next()
        a
    }
}

private fun Node.Take.take(): Pull {
    val up = upstream.pull()
    var left = n
    return Pull {
        if (left <= 0) {
            null
        } else {
            left--
            up.next()
        }
    }
}

private fun Node.Drop.drop(): Pull {
    val up = upstream.pull()
    var skip = n
    return Pull {
        while (skip > 0) {
            skip--
            if (up.next() == null) return@Pull null
        }
        up.next()
    }
}

private fun Node.TakeWhile.takeWhile(): Pull {
    val test = guarded("takeWhile", at, predicate)
    val up = upstream.pull()
    var open = true
    return Pull {
        val a = if (open) up.next() else null
        if (a != null && !test(a)) open = false
        a.takeIf { open }
    }
}

private fun Node.DropWhile.dropWhile(): Pull {
    val test = guarded("dropWhile", at, predicate)
    val up = upstream.pull()
    var dropping = true
    return Pull {
        var a = up.next()
        while (dropping && a != null && test(a)) a = up.next()
        dropping = false
        a
    }
}

private fun Node.Grouped.grouped(): Pull {
    val up = upstream.pull()
    return Pull {
        buildList {
            while (size < n) add(up.next() ?: break)
        }.ifEmpty { null }
    }
}

private fun Node.Scan.scan(): Pull {
    val carry = guarded("scan", at, f)
    val up = upstream.pull()
    var carried: Any? = null
    return Pull {
        val last = carried
        if (last == null) {
            zero.also { carried = it }
        } else {
            up.next()?.let { a -> carry(last, a).also { carried = it } }
        }
    }
}

private fun Node.StatefulMap.statefulMap(): Pull {
    val step = guarded("statefulMap", at, f)
    val up = upstream.pull()
    var state = create()
    var ended = false
    return Pull {
        if (ended) return@Pull null
        val a = up.next()
        if (a == null) {
            ended = true
            onComplete(state)
        } else {
            val (next, out) = step(state, a)
            state = next
            out
        }
    }
}

private fun Node.MapConcat.mapConcat(): Pull {
    val body = guarded("mapConcat", at, f)
    val up = upstream.pull()
    var current: Iterator<Any> = emptyList<Any>().iterator()
    return Pull {
        while (!current.hasNext()) current = body(up.next() ?: return@Pull null).iterator()
        current.next()
    }
}

/** The declared failure as a last `Left`: a pull that failed is not pulled again, so the stream ends there. */
private fun Node.Either.either(): Pull {
    val up = upstream.pull()
    var ended = false
    return Pull {
        if (ended) return@Pull null
        try {
            up.next()?.let { a -> Either.Right(a) }
        } catch (failure: DeclaredFailure) {
            ended = true
            Either.Left(failure.declared<Any?>())
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Node.Absolve.absolve(): Pull {
    val decided = guarded("absolve", at) { either: Any ->
        (either as Either<Any?, Any>).fold({ left -> throw DeclaredFailure(left) }, { right -> right })
    }
    val up = upstream.pull()
    return Pull { up.next()?.let(decided) }
}

/** One recovery, as Pekko's `recoverWithRetries(1)`: a failure of the stream that took over ends the run. */
private fun Node.CatchAll.catchAll(): Pull {
    var current = upstream.pull()
    var recovered = false
    return Pull {
        try {
            current.next()
        } catch (failure: DeclaredFailure) {
            if (recovered) throw failure
            recovered = true
            current = f(failure.declared()).fused().pull()
            current.next()
        }
    }
}

// A declared failure carries a value and no stack, so the one replaced is not lost: its value is mapped.
@Suppress("SwallowedException")
private fun Node.MapError.mapError(): Pull {
    val mapped = guardedError("mapError", at, f)
    val up = upstream.pull()
    return Pull {
        try {
            up.next()
        } catch (failure: DeclaredFailure) {
            throw DeclaredFailure(mapped(failure.declared()))
        }
    }
}

private fun Node.OrFailIfEmpty.orFailIfEmpty(): Pull {
    val up = upstream.pull()
    var emitted = false
    return Pull {
        val a = up.next()
        if (a == null && !emitted) throw DeclaredFailure(error)
        emitted = true
        a
    }
}

private fun Node.FlatMap.flatMapConcat(): Pull {
    val build = guarded("flatMapConcat", at, f)
    val up = upstream.pull()
    var inner: Pull? = null
    return Pull {
        var element = inner?.next()
        while (element == null) {
            val started = build(up.next() ?: return@Pull null).node.fused().pull()
            inner = started
            element = started.next()
        }
        element
    }
}

/** This node's elements, then [next]'s, which is not pulled from, or started, until this one has ended. */
private fun Node.then(next: Node): Pull {
    val first = pull()
    var second: Pull? = null
    return Pull {
        val switched = second
        if (switched != null) return@Pull switched.next()
        first.next() ?: next.pull().also { second = it }.next()
    }
}

private fun Node.ZipWith.zipWith(): Pull {
    val combine = guarded("zipWith", at, f)
    val left = upstream.pull()
    val right = other.pull()
    return Pull {
        val a = left.next() ?: return@Pull null
        val b = right.next() ?: return@Pull null
        combine(a, b)
    }
}

/** A fused run as one pull: the steps are calls in a loop, and an element a filter drops pulls the next. */
private fun Node.Fused.fusedLoop(): Pull {
    val bodies = steps.map { it.body() }.toTypedArray()
    val up = upstream.pull()
    return Pull {
        var value: Any? = null
        while (value == null) value = bodies.through(up.next() ?: return@Pull null)
        value
    }
}
