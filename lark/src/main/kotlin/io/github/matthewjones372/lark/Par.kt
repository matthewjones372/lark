package io.github.matthewjones372.lark

/**
 * Runs both branches on forks of their own and combines their values; the first branch to fail interrupts
 * the other, and its raise or throw surfaces here.
 */
fun <E, A, B, C> Flock<E>.parZip(fa: Flock<E>.() -> A, fb: Flock<E>.() -> B, f: (A, B) -> C): C {
    val flight = Flight(this)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    flight.settle()
    return f(a.await(), b.await())
}

/** Three branches, each on a fork of its own. */
fun <E, A, B, C, D> Flock<E>.parZip(
    fa: Flock<E>.() -> A,
    fb: Flock<E>.() -> B,
    fc: Flock<E>.() -> C,
    f: (A, B, C) -> D,
): D {
    val flight = Flight(this)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    flight.settle()
    return f(a.await(), b.await(), c.await())
}

/** Four branches, each on a fork of its own. */
fun <E, A, B, C, D, F> Flock<E>.parZip(
    fa: Flock<E>.() -> A,
    fb: Flock<E>.() -> B,
    fc: Flock<E>.() -> C,
    fd: Flock<E>.() -> D,
    f: (A, B, C, D) -> F,
): F {
    val flight = Flight(this)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    val d = flight.fork(fd)
    flight.settle()
    return f(a.await(), b.await(), c.await(), d.await())
}

/** Runs [f] over every element on a fork of its own, and answers in the iterable's order. */
fun <E, A, B> Flock<E>.parMap(iterable: Iterable<A>, f: Flock<E>.(A) -> B): List<B> {
    val flight = Flight(this)
    val forks = iterable.map { element -> flight.fork { f(element) } }
    flight.settle()
    return forks.map { it.await() }
}
