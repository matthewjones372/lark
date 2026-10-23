package io.github.matthewjones372.lark

import arrow.core.raise.Raise
import io.github.matthewjones372.lark.fail.Fail
import io.github.matthewjones372.lark.fail.NonEmptyList
import io.github.matthewjones372.lark.fail.toNonEmptyListOrNull
import java.util.concurrent.Executor
import arrow.core.NonEmptyList as ArrowNonEmptyList
import arrow.core.toNonEmptyListOrNull as toArrowNonEmptyListOrNull

/**
 * Runs both branches on forks of their own and keeps every failure: a fail does not end a sibling, so
 * both branches run to completion and their errors answer together, in branch order.
 */
fun <E, A, B, C> Fail<NonEmptyList<E>>.parZipOrAccumulate(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    transform: (A, B) -> C,
): C = parZipOrAccumulate(VirtualThreads, fa, fb, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C> Fail<NonEmptyList<E>>.parZipOrAccumulate(
    on: Executor,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    transform: (A, B) -> C,
): C {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    failAll(flight.settleEvery())
    return transform(a.await(), b.await())
}

/** The same two branches, their failures folded with [combine] into the error this scope declares. */
fun <E, A, B, C> Fail<E>.parZipOrAccumulate(
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    transform: (A, B) -> C,
): C = parZipOrAccumulate(VirtualThreads, combine, fa, fb, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C> Fail<E>.parZipOrAccumulate(
    on: Executor,
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    transform: (A, B) -> C,
): C {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    failCombined(flight.settleEvery(), combine)
    return transform(a.await(), b.await())
}

/** Three branches, each on a fork of its own. */
fun <E, A, B, C, D> Fail<NonEmptyList<E>>.parZipOrAccumulate(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    transform: (A, B, C) -> D,
): D = parZipOrAccumulate(VirtualThreads, fa, fb, fc, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D> Fail<NonEmptyList<E>>.parZipOrAccumulate(
    on: Executor,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    transform: (A, B, C) -> D,
): D {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    failAll(flight.settleEvery())
    return transform(a.await(), b.await(), c.await())
}

/** Three branches, their failures folded with [combine]. */
fun <E, A, B, C, D> Fail<E>.parZipOrAccumulate(
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    transform: (A, B, C) -> D,
): D = parZipOrAccumulate(VirtualThreads, combine, fa, fb, fc, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D> Fail<E>.parZipOrAccumulate(
    on: Executor,
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    transform: (A, B, C) -> D,
): D {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    failCombined(flight.settleEvery(), combine)
    return transform(a.await(), b.await(), c.await())
}

/** Four branches, each on a fork of its own. */
fun <E, A, B, C, D, F> Fail<NonEmptyList<E>>.parZipOrAccumulate(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    transform: (A, B, C, D) -> F,
): F = parZipOrAccumulate(VirtualThreads, fa, fb, fc, fd, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F> Fail<NonEmptyList<E>>.parZipOrAccumulate(
    on: Executor,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    transform: (A, B, C, D) -> F,
): F {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    failAll(flight.settleEvery())
    return transform(a.await(), b.await(), c.await(), d.await())
}

/** Four branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F> Fail<E>.parZipOrAccumulate(
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    transform: (A, B, C, D) -> F,
): F = parZipOrAccumulate(VirtualThreads, combine, fa, fb, fc, fd, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F> Fail<E>.parZipOrAccumulate(
    on: Executor,
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    transform: (A, B, C, D) -> F,
): F {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    failCombined(flight.settleEvery(), combine)
    return transform(a.await(), b.await(), c.await(), d.await())
}

/** Five branches, each on a fork of its own. */
fun <E, A, B, C, D, F, G> Fail<NonEmptyList<E>>.parZipOrAccumulate(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    transform: (A, B, C, D, F) -> G,
): G = parZipOrAccumulate(VirtualThreads, fa, fb, fc, fd, fe, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G> Fail<NonEmptyList<E>>.parZipOrAccumulate(
    on: Executor,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    transform: (A, B, C, D, F) -> G,
): G {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    failAll(flight.settleEvery())
    return transform(a.await(), b.await(), c.await(), d.await(), e.await())
}

/** Five branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F, G> Fail<E>.parZipOrAccumulate(
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    transform: (A, B, C, D, F) -> G,
): G = parZipOrAccumulate(VirtualThreads, combine, fa, fb, fc, fd, fe, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G> Fail<E>.parZipOrAccumulate(
    on: Executor,
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    transform: (A, B, C, D, F) -> G,
): G {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    failCombined(flight.settleEvery(), combine)
    return transform(a.await(), b.await(), c.await(), d.await(), e.await())
}

/** Six branches, each on a fork of its own. */
fun <E, A, B, C, D, F, G, H> Raise<ArrowNonEmptyList<E>>.parZipOrAccumulate(
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    transform: (A, B, C, D, F, G) -> H,
): H = parZipOrAccumulate(VirtualThreads, fa, fb, fc, fd, fe, ff, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G, H> Raise<ArrowNonEmptyList<E>>.parZipOrAccumulate(
    on: Executor,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    transform: (A, B, C, D, F, G) -> H,
): H {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    val f = flight.fork(ff)
    raiseAll(flight.settleEvery())
    return transform(a.await(), b.await(), c.await(), d.await(), e.await(), f.await())
}

/** Six branches, their raises folded with [combine]. */
fun <E, A, B, C, D, F, G, H> Raise<E>.parZipOrAccumulate(
    combine: (E, E) -> E,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    transform: (A, B, C, D, F, G) -> H,
): H = parZipOrAccumulate(VirtualThreads, combine, fa, fb, fc, fd, fe, ff, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G, H> Raise<E>.parZipOrAccumulate(
    on: Executor,
    combine: (E, E) -> E,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    transform: (A, B, C, D, F, G) -> H,
): H {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    val f = flight.fork(ff)
    raiseCombined(flight.settleEvery(), combine)
    return transform(a.await(), b.await(), c.await(), d.await(), e.await(), f.await())
}

/** Seven branches, each on a fork of its own. */
fun <E, A, B, C, D, F, G, H, I> Raise<ArrowNonEmptyList<E>>.parZipOrAccumulate(
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    transform: (A, B, C, D, F, G, H) -> I,
): I = parZipOrAccumulate(VirtualThreads, fa, fb, fc, fd, fe, ff, fg, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G, H, I> Raise<ArrowNonEmptyList<E>>.parZipOrAccumulate(
    on: Executor,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    transform: (A, B, C, D, F, G, H) -> I,
): I {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    val f = flight.fork(ff)
    val g = flight.fork(fg)
    raiseAll(flight.settleEvery())
    return transform(a.await(), b.await(), c.await(), d.await(), e.await(), f.await(), g.await())
}

/** Seven branches, their raises folded with [combine]. */
fun <E, A, B, C, D, F, G, H, I> Raise<E>.parZipOrAccumulate(
    combine: (E, E) -> E,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    transform: (A, B, C, D, F, G, H) -> I,
): I = parZipOrAccumulate(VirtualThreads, combine, fa, fb, fc, fd, fe, ff, fg, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G, H, I> Raise<E>.parZipOrAccumulate(
    on: Executor,
    combine: (E, E) -> E,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    transform: (A, B, C, D, F, G, H) -> I,
): I {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    val f = flight.fork(ff)
    val g = flight.fork(fg)
    raiseCombined(flight.settleEvery(), combine)
    return transform(a.await(), b.await(), c.await(), d.await(), e.await(), f.await(), g.await())
}

/** Eight branches, each on a fork of its own. */
fun <E, A, B, C, D, F, G, H, I, J> Raise<ArrowNonEmptyList<E>>.parZipOrAccumulate(
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    fh: Raise<E>.() -> I,
    transform: (A, B, C, D, F, G, H, I) -> J,
): J = parZipOrAccumulate(VirtualThreads, fa, fb, fc, fd, fe, ff, fg, fh, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G, H, I, J> Raise<ArrowNonEmptyList<E>>.parZipOrAccumulate(
    on: Executor,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    fh: Raise<E>.() -> I,
    transform: (A, B, C, D, F, G, H, I) -> J,
): J {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    val f = flight.fork(ff)
    val g = flight.fork(fg)
    val h = flight.fork(fh)
    raiseAll(flight.settleEvery())
    return transform(a.await(), b.await(), c.await(), d.await(), e.await(), f.await(), g.await(), h.await())
}

/** Eight branches, their raises folded with [combine]. */
fun <E, A, B, C, D, F, G, H, I, J> Raise<E>.parZipOrAccumulate(
    combine: (E, E) -> E,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    fh: Raise<E>.() -> I,
    transform: (A, B, C, D, F, G, H, I) -> J,
): J = parZipOrAccumulate(VirtualThreads, combine, fa, fb, fc, fd, fe, ff, fg, fh, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G, H, I, J> Raise<E>.parZipOrAccumulate(
    on: Executor,
    combine: (E, E) -> E,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    fh: Raise<E>.() -> I,
    transform: (A, B, C, D, F, G, H, I) -> J,
): J {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    val f = flight.fork(ff)
    val g = flight.fork(fg)
    val h = flight.fork(fh)
    raiseCombined(flight.settleEvery(), combine)
    return transform(a.await(), b.await(), c.await(), d.await(), e.await(), f.await(), g.await(), h.await())
}

/** Nine branches, the last arity `arrow-fx-coroutines` has. */
fun <E, A, B, C, D, F, G, H, I, J, K> Raise<ArrowNonEmptyList<E>>.parZipOrAccumulate(
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    fh: Raise<E>.() -> I,
    fi: Raise<E>.() -> J,
    transform: (A, B, C, D, F, G, H, I, J) -> K,
): K = parZipOrAccumulate(VirtualThreads, fa, fb, fc, fd, fe, ff, fg, fh, fi, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G, H, I, J, K> Raise<ArrowNonEmptyList<E>>.parZipOrAccumulate(
    on: Executor,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    fh: Raise<E>.() -> I,
    fi: Raise<E>.() -> J,
    transform: (A, B, C, D, F, G, H, I, J) -> K,
): K {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    val f = flight.fork(ff)
    val g = flight.fork(fg)
    val h = flight.fork(fh)
    val i = flight.fork(fi)
    raiseAll(flight.settleEvery())
    return transform(a.await(), b.await(), c.await(), d.await(), e.await(), f.await(), g.await(), h.await(), i.await())
}

/** Nine branches, their raises folded with [combine]. */
fun <E, A, B, C, D, F, G, H, I, J, K> Raise<E>.parZipOrAccumulate(
    combine: (E, E) -> E,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    fh: Raise<E>.() -> I,
    fi: Raise<E>.() -> J,
    transform: (A, B, C, D, F, G, H, I, J) -> K,
): K = parZipOrAccumulate(VirtualThreads, combine, fa, fb, fc, fd, fe, ff, fg, fh, fi, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G, H, I, J, K> Raise<E>.parZipOrAccumulate(
    on: Executor,
    combine: (E, E) -> E,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    fh: Raise<E>.() -> I,
    fi: Raise<E>.() -> J,
    transform: (A, B, C, D, F, G, H, I, J) -> K,
): K {
    val flight = Flight<E>(Uncollected, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    val f = flight.fork(ff)
    val g = flight.fork(fg)
    val h = flight.fork(fh)
    val i = flight.fork(fi)
    raiseCombined(flight.settleEvery(), combine)
    return transform(a.await(), b.await(), c.await(), d.await(), e.await(), f.await(), g.await(), h.await(), i.await())
}

/** Runs [transform] over every element on a fork of its own, keeping the failure of each element that failed. */
fun <E, A, B> Fail<NonEmptyList<E>>.parMapOrAccumulate(
    iterable: Iterable<A>,
    transform: Fail<E>.(A) -> B,
): List<B> = parMapOrAccumulate(VirtualThreads, iterable, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B> Fail<NonEmptyList<E>>.parMapOrAccumulate(
    on: Executor,
    iterable: Iterable<A>,
    transform: Fail<E>.(A) -> B,
): List<B> {
    val flight = Flight<E>(Uncollected, on)
    val forks = iterable.map { element -> flight.fork { transform(element) } }
    failAll(flight.settleEvery())
    return forks.map { it.await() }
}

/** The same map, its failures folded with [combine] into the error this scope declares. */
fun <E, A, B> Fail<E>.parMapOrAccumulate(
    combine: (E, E) -> E,
    iterable: Iterable<A>,
    transform: Fail<E>.(A) -> B,
): List<B> = parMapOrAccumulate(VirtualThreads, combine, iterable, transform)

/** The same, with every fork run on [on]. */
fun <E, A, B> Fail<E>.parMapOrAccumulate(
    on: Executor,
    combine: (E, E) -> E,
    iterable: Iterable<A>,
    transform: Fail<E>.(A) -> B,
): List<B> {
    val flight = Flight<E>(Uncollected, on)
    val forks = iterable.map { element -> flight.fork { transform(element) } }
    failCombined(flight.settleEvery(), combine)
    return forks.map { it.await() }
}

/** Raises the branches' errors together, or returns so the caller can read their values. */
private fun <E> Raise<ArrowNonEmptyList<E>>.raiseAll(errors: List<E>) {
    errors.toArrowNonEmptyListOrNull()?.let { raise(it) }
}

/** The same, for a scope that declares one error and was handed the fold that makes it. */
private fun <E> Raise<E>.raiseCombined(errors: List<E>, combine: (E, E) -> E) {
    errors.reduceOrNull(combine)?.let { raise(it) }
}

/** Fails with the branches' errors together, or returns so the caller can read their values. */
private fun <E> Fail<NonEmptyList<E>>.failAll(errors: List<E>) {
    errors.toNonEmptyListOrNull()?.let { fail(it) }
}

/** The same, for a scope that declares one error and was handed the fold that makes it. */
private fun <E> Fail<E>.failCombined(errors: List<E>, combine: (E, E) -> E) {
    errors.reduceOrNull(combine)?.let { fail(it) }
}
