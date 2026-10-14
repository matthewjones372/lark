package io.github.matthewjones372.lark

import arrow.core.raise.Raise
import java.util.concurrent.Executor

/**
 * Runs both branches on forks of their own and combines their values; the first branch to fail interrupts
 * the other, and its raise or throw surfaces here.
 */
fun <E, A, B, C> Raise<E>.parZip(fa: Raise<E>.() -> A, fb: Raise<E>.() -> B, f: (A, B) -> C): C =
    parZip(VirtualThreads, fa, fb, f)

/** The same, with every fork run on [on]. */
fun <E, A, B, C> Raise<E>.parZip(
    on: Executor,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    f: (A, B) -> C,
): C {
    val flight = Flight(this, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    flight.settleAll()
    return f(a.await(), b.await())
}

/** Three branches, each on a fork of its own. */
fun <E, A, B, C, D> Raise<E>.parZip(
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    f: (A, B, C) -> D,
): D = parZip(VirtualThreads, fa, fb, fc, f)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D> Raise<E>.parZip(
    on: Executor,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    f: (A, B, C) -> D,
): D {
    val flight = Flight(this, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    flight.settleAll()
    return f(a.await(), b.await(), c.await())
}

/** Four branches, each on a fork of its own. */
fun <E, A, B, C, D, F> Raise<E>.parZip(
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    f: (A, B, C, D) -> F,
): F = parZip(VirtualThreads, fa, fb, fc, fd, f)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F> Raise<E>.parZip(
    on: Executor,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    f: (A, B, C, D) -> F,
): F {
    val flight = Flight(this, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    flight.settleAll()
    return f(a.await(), b.await(), c.await(), d.await())
}

/** Five branches, each on a fork of its own. */
fun <E, A, B, C, D, F, G> Raise<E>.parZip(
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    f: (A, B, C, D, F) -> G,
): G = parZip(VirtualThreads, fa, fb, fc, fd, fe, f)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G> Raise<E>.parZip(
    on: Executor,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    f: (A, B, C, D, F) -> G,
): G {
    val flight = Flight(this, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    flight.settleAll()
    return f(a.await(), b.await(), c.await(), d.await(), e.await())
}

/** Six branches, each on a fork of its own. */
fun <E, A, B, C, D, F, G, H> Raise<E>.parZip(
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    f: (A, B, C, D, F, G) -> H,
): H = parZip(VirtualThreads, fa, fb, fc, fd, fe, ff, f)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G, H> Raise<E>.parZip(
    on: Executor,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    f: (A, B, C, D, F, G) -> H,
): H {
    val flight = Flight(this, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    val g = flight.fork(ff)
    flight.settleAll()
    return f(a.await(), b.await(), c.await(), d.await(), e.await(), g.await())
}

/** Seven branches, each on a fork of its own. */
fun <E, A, B, C, D, F, G, H, I> Raise<E>.parZip(
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    f: (A, B, C, D, F, G, H) -> I,
): I = parZip(VirtualThreads, fa, fb, fc, fd, fe, ff, fg, f)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G, H, I> Raise<E>.parZip(
    on: Executor,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    f: (A, B, C, D, F, G, H) -> I,
): I {
    val flight = Flight(this, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    val g = flight.fork(ff)
    val h = flight.fork(fg)
    flight.settleAll()
    return f(a.await(), b.await(), c.await(), d.await(), e.await(), g.await(), h.await())
}

/** Eight branches, each on a fork of its own. */
fun <E, A, B, C, D, F, G, H, I, J> Raise<E>.parZip(
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    fh: Raise<E>.() -> I,
    f: (A, B, C, D, F, G, H, I) -> J,
): J = parZip(VirtualThreads, fa, fb, fc, fd, fe, ff, fg, fh, f)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G, H, I, J> Raise<E>.parZip(
    on: Executor,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    fh: Raise<E>.() -> I,
    f: (A, B, C, D, F, G, H, I) -> J,
): J {
    val flight = Flight(this, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    val g = flight.fork(ff)
    val h = flight.fork(fg)
    val i = flight.fork(fh)
    flight.settleAll()
    return f(a.await(), b.await(), c.await(), d.await(), e.await(), g.await(), h.await(), i.await())
}

/** Nine branches, the last arity `arrow-fx-coroutines` has. */
fun <E, A, B, C, D, F, G, H, I, J, K> Raise<E>.parZip(
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    fd: Raise<E>.() -> D,
    fe: Raise<E>.() -> F,
    ff: Raise<E>.() -> G,
    fg: Raise<E>.() -> H,
    fh: Raise<E>.() -> I,
    fi: Raise<E>.() -> J,
    f: (A, B, C, D, F, G, H, I, J) -> K,
): K = parZip(VirtualThreads, fa, fb, fc, fd, fe, ff, fg, fh, fi, f)

/** The same, with every fork run on [on]. */
fun <E, A, B, C, D, F, G, H, I, J, K> Raise<E>.parZip(
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
    f: (A, B, C, D, F, G, H, I, J) -> K,
): K {
    val flight = Flight(this, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    val e = flight.fork(fe)
    val g = flight.fork(ff)
    val h = flight.fork(fg)
    val i = flight.fork(fh)
    val j = flight.fork(fi)
    flight.settleAll()
    return f(a.await(), b.await(), c.await(), d.await(), e.await(), g.await(), h.await(), i.await(), j.await())
}

/** Runs [f] over every element on a fork of its own, and answers in the iterable's order. */
fun <E, A, B> Raise<E>.parMap(iterable: Iterable<A>, f: Raise<E>.(A) -> B): List<B> =
    parMap(VirtualThreads, iterable, f)

/** The same, with every fork run on [on]. */
fun <E, A, B> Raise<E>.parMap(
    on: Executor,
    iterable: Iterable<A>,
    f: Raise<E>.(A) -> B,
): List<B> {
    val flight = Flight(this, on)
    val forks = iterable.map { element -> flight.fork { f(element) } }
    flight.settleAll()
    return forks.map { it.await() }
}

// The same combinators for code outside any `Raise`, with plain branches: the arrow-fx-coroutines
// signatures minus the `suspend`. Each forks under an owner that cannot be asked to raise.

/** Runs both branches on forks of their own and combines their values; the first to throw interrupts the other. */
fun <A, B, C> parZip(fa: () -> A, fb: () -> B, f: (A, B) -> C): C = parZip(VirtualThreads, fa, fb, f)

/** The same, with every fork run on [on]. */
fun <A, B, C> parZip(on: Executor, fa: () -> A, fb: () -> B, f: (A, B) -> C): C =
    Unraisable.parZip(on, { fa() }, { fb() }, f)

/** Three branches, each on a fork of its own. */
fun <A, B, C, D> parZip(fa: () -> A, fb: () -> B, fc: () -> C, f: (A, B, C) -> D): D =
    parZip(VirtualThreads, fa, fb, fc, f)

/** The same, with every fork run on [on]. */
fun <A, B, C, D> parZip(
    on: Executor,
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
    f: (A, B, C) -> D,
): D = Unraisable.parZip(on, { fa() }, { fb() }, { fc() }, f)

/** Four branches, each on a fork of its own. */
fun <A, B, C, D, F> parZip(fa: () -> A, fb: () -> B, fc: () -> C, fd: () -> D, f: (A, B, C, D) -> F): F =
    parZip(VirtualThreads, fa, fb, fc, fd, f)

/** The same, with every fork run on [on]. */
fun <A, B, C, D, F> parZip(
    on: Executor,
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
    fd: () -> D,
    f: (A, B, C, D) -> F,
): F = Unraisable.parZip(on, { fa() }, { fb() }, { fc() }, { fd() }, f)

/** Five branches, each on a fork of its own. */
fun <A, B, C, D, F, G> parZip(
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
    fd: () -> D,
    fe: () -> F,
    f: (A, B, C, D, F) -> G,
): G = parZip(VirtualThreads, fa, fb, fc, fd, fe, f)

/** The same, with every fork run on [on]. */
fun <A, B, C, D, F, G> parZip(
    on: Executor,
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
    fd: () -> D,
    fe: () -> F,
    f: (A, B, C, D, F) -> G,
): G = Unraisable.parZip(on, { fa() }, { fb() }, { fc() }, { fd() }, { fe() }, f)

/** Six branches, each on a fork of its own. */
fun <A, B, C, D, F, G, H> parZip(
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
    fd: () -> D,
    fe: () -> F,
    ff: () -> G,
    f: (A, B, C, D, F, G) -> H,
): H = parZip(VirtualThreads, fa, fb, fc, fd, fe, ff, f)

/** The same, with every fork run on [on]. */
fun <A, B, C, D, F, G, H> parZip(
    on: Executor,
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
    fd: () -> D,
    fe: () -> F,
    ff: () -> G,
    f: (A, B, C, D, F, G) -> H,
): H = Unraisable.parZip(on, { fa() }, { fb() }, { fc() }, { fd() }, { fe() }, { ff() }, f)

/** Seven branches, each on a fork of its own. */
fun <A, B, C, D, F, G, H, I> parZip(
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
    fd: () -> D,
    fe: () -> F,
    ff: () -> G,
    fg: () -> H,
    f: (A, B, C, D, F, G, H) -> I,
): I = parZip(VirtualThreads, fa, fb, fc, fd, fe, ff, fg, f)

/** The same, with every fork run on [on]. */
fun <A, B, C, D, F, G, H, I> parZip(
    on: Executor,
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
    fd: () -> D,
    fe: () -> F,
    ff: () -> G,
    fg: () -> H,
    f: (A, B, C, D, F, G, H) -> I,
): I = Unraisable.parZip(on, { fa() }, { fb() }, { fc() }, { fd() }, { fe() }, { ff() }, { fg() }, f)

/** Eight branches, each on a fork of its own. */
fun <A, B, C, D, F, G, H, I, J> parZip(
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
    fd: () -> D,
    fe: () -> F,
    ff: () -> G,
    fg: () -> H,
    fh: () -> I,
    f: (A, B, C, D, F, G, H, I) -> J,
): J = parZip(VirtualThreads, fa, fb, fc, fd, fe, ff, fg, fh, f)

/** The same, with every fork run on [on]. */
fun <A, B, C, D, F, G, H, I, J> parZip(
    on: Executor,
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
    fd: () -> D,
    fe: () -> F,
    ff: () -> G,
    fg: () -> H,
    fh: () -> I,
    f: (A, B, C, D, F, G, H, I) -> J,
): J = Unraisable.parZip(on, { fa() }, { fb() }, { fc() }, { fd() }, { fe() }, { ff() }, { fg() }, { fh() }, f)

/** Nine branches, the last arity `arrow-fx-coroutines` has. */
fun <A, B, C, D, F, G, H, I, J, K> parZip(
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
    fd: () -> D,
    fe: () -> F,
    ff: () -> G,
    fg: () -> H,
    fh: () -> I,
    fi: () -> J,
    f: (A, B, C, D, F, G, H, I, J) -> K,
): K = parZip(VirtualThreads, fa, fb, fc, fd, fe, ff, fg, fh, fi, f)

/** The same, with every fork run on [on]. */
fun <A, B, C, D, F, G, H, I, J, K> parZip(
    on: Executor,
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
    fd: () -> D,
    fe: () -> F,
    ff: () -> G,
    fg: () -> H,
    fh: () -> I,
    fi: () -> J,
    f: (A, B, C, D, F, G, H, I, J) -> K,
): K =
    Unraisable.parZip(on, { fa() }, { fb() }, { fc() }, { fd() }, { fe() }, { ff() }, { fg() }, { fh() }, { fi() }, f)

/** Runs [f] over every element on a fork of its own, and answers in the iterable's order. */
fun <A, B> parMap(iterable: Iterable<A>, f: (A) -> B): List<B> = parMap(VirtualThreads, iterable, f)

/** The same, with every fork run on [on]. */
fun <A, B> parMap(on: Executor, iterable: Iterable<A>, f: (A) -> B): List<B> =
    Unraisable.parMap(on, iterable) { f(it) }
