package io.github.matthewjones372.surefold

import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.Fail
import io.github.matthewjones372.surefold.fail.NonEmptyList
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.fail.getOrElse
import io.github.matthewjones372.surefold.fail.toNonEmptyListOrNull

/**
 * Runs both branches in order on the calling thread and keeps every failure: a fail ends only its own
 * branch, so both run and their errors answer together, in branch order. A throw is not kept; it ends
 * the call where it happens.
 */
fun <E, A, B, C> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    transform: (A, B) -> C,
): C {
    val a = attempt(fa)
    val b = attempt(fb)
    failAll(a, b)
    return transform(a.settled(), b.settled())
}

/** The same two branches, their failures folded with [combine] into the error this scope declares. */
fun <E, A, B, C> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    transform: (A, B) -> C,
): C = combined(combine) { zipOrAccumulate(fa, fb, transform) }

/** Three branches, in order. */
fun <E, A, B, C, D> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    transform: (A, B, C) -> D,
): D {
    val a = attempt(fa)
    val b = attempt(fb)
    val c = attempt(fc)
    failAll(a, b, c)
    return transform(a.settled(), b.settled(), c.settled())
}

/** Three branches, their failures folded with [combine]. */
fun <E, A, B, C, D> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    transform: (A, B, C) -> D,
): D = combined(combine) { zipOrAccumulate(fa, fb, fc, transform) }

/** Four branches, in order. */
fun <E, A, B, C, D, F> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    transform: (A, B, C, D) -> F,
): F {
    val a = attempt(fa)
    val b = attempt(fb)
    val c = attempt(fc)
    val d = attempt(fd)
    failAll(a, b, c, d)
    return transform(a.settled(), b.settled(), c.settled(), d.settled())
}

/** Four branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    transform: (A, B, C, D) -> F,
): F = combined(combine) { zipOrAccumulate(fa, fb, fc, fd, transform) }

/** Five branches, in order. */
fun <E, A, B, C, D, F, G> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    transform: (A, B, C, D, F) -> G,
): G {
    val a = attempt(fa)
    val b = attempt(fb)
    val c = attempt(fc)
    val d = attempt(fd)
    val e = attempt(fe)
    failAll(a, b, c, d, e)
    return transform(a.settled(), b.settled(), c.settled(), d.settled(), e.settled())
}

/** Five branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F, G> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    transform: (A, B, C, D, F) -> G,
): G = combined(combine) { zipOrAccumulate(fa, fb, fc, fd, fe, transform) }

/** Six branches, in order. */
fun <E, A, B, C, D, F, G, H> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    ff: Fail<E>.() -> G,
    transform: (A, B, C, D, F, G) -> H,
): H {
    val a = attempt(fa)
    val b = attempt(fb)
    val c = attempt(fc)
    val d = attempt(fd)
    val e = attempt(fe)
    val f = attempt(ff)
    failAll(a, b, c, d, e, f)
    return transform(
        a.settled(), b.settled(), c.settled(), d.settled(), e.settled(), f.settled(),
    )
}

/** Six branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F, G, H> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    ff: Fail<E>.() -> G,
    transform: (A, B, C, D, F, G) -> H,
): H = combined(combine) { zipOrAccumulate(fa, fb, fc, fd, fe, ff, transform) }

/** Seven branches, in order. */
fun <E, A, B, C, D, F, G, H, I> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    ff: Fail<E>.() -> G,
    fg: Fail<E>.() -> H,
    transform: (A, B, C, D, F, G, H) -> I,
): I {
    val a = attempt(fa)
    val b = attempt(fb)
    val c = attempt(fc)
    val d = attempt(fd)
    val e = attempt(fe)
    val f = attempt(ff)
    val g = attempt(fg)
    failAll(a, b, c, d, e, f, g)
    return transform(
        a.settled(), b.settled(), c.settled(), d.settled(), e.settled(), f.settled(), g.settled(),
    )
}

/** Seven branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F, G, H, I> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    ff: Fail<E>.() -> G,
    fg: Fail<E>.() -> H,
    transform: (A, B, C, D, F, G, H) -> I,
): I = combined(combine) { zipOrAccumulate(fa, fb, fc, fd, fe, ff, fg, transform) }

/** Eight branches, in order. */
fun <E, A, B, C, D, F, G, H, I, J> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    ff: Fail<E>.() -> G,
    fg: Fail<E>.() -> H,
    fh: Fail<E>.() -> I,
    transform: (A, B, C, D, F, G, H, I) -> J,
): J {
    val a = attempt(fa)
    val b = attempt(fb)
    val c = attempt(fc)
    val d = attempt(fd)
    val e = attempt(fe)
    val f = attempt(ff)
    val g = attempt(fg)
    val h = attempt(fh)
    failAll(a, b, c, d, e, f, g, h)
    return transform(
        a.settled(), b.settled(), c.settled(), d.settled(), e.settled(), f.settled(), g.settled(), h.settled(),
    )
}

/** Eight branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F, G, H, I, J> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    ff: Fail<E>.() -> G,
    fg: Fail<E>.() -> H,
    fh: Fail<E>.() -> I,
    transform: (A, B, C, D, F, G, H, I) -> J,
): J = combined(combine) { zipOrAccumulate(fa, fb, fc, fd, fe, ff, fg, fh, transform) }

/** Nine branches, in order. */
fun <E, A, B, C, D, F, G, H, I, J, K> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    ff: Fail<E>.() -> G,
    fg: Fail<E>.() -> H,
    fh: Fail<E>.() -> I,
    fi: Fail<E>.() -> J,
    transform: (A, B, C, D, F, G, H, I, J) -> K,
): K {
    val a = attempt(fa)
    val b = attempt(fb)
    val c = attempt(fc)
    val d = attempt(fd)
    val e = attempt(fe)
    val f = attempt(ff)
    val g = attempt(fg)
    val h = attempt(fh)
    val i = attempt(fi)
    failAll(a, b, c, d, e, f, g, h, i)
    return transform(
        a.settled(), b.settled(), c.settled(), d.settled(), e.settled(),
        f.settled(), g.settled(), h.settled(), i.settled(),
    )
}

/** Nine branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F, G, H, I, J, K> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    fd: Fail<E>.() -> D,
    fe: Fail<E>.() -> F,
    ff: Fail<E>.() -> G,
    fg: Fail<E>.() -> H,
    fh: Fail<E>.() -> I,
    fi: Fail<E>.() -> J,
    transform: (A, B, C, D, F, G, H, I, J) -> K,
): K = combined(combine) { zipOrAccumulate(fa, fb, fc, fd, fe, ff, fg, fh, fi, transform) }

/**
 * Runs [transform] on each element in order on the calling thread and keeps every failure: a fail ends only
 * its own element, so every element runs and their errors answer together, in the input's order.
 */
fun <E, A, B> Fail<NonEmptyList<E>>.mapOrAccumulate(iterable: Iterable<A>, transform: Fail<E>.(A) -> B): List<B> {
    val results = iterable.map { element -> attempt<E, B> { transform(element) } }
    failAll(results)
    return results.map { it.settled() }
}

/** The same map, its failures folded with [combine] into the error this scope declares. */
fun <E, A, B> Fail<E>.mapOrAccumulate(
    combine: (E, E) -> E,
    iterable: Iterable<A>,
    transform: Fail<E>.(A) -> B,
): List<B> = combined(combine) { mapOrAccumulate(iterable, transform) }

/** Fails with every branch's error together, or returns so the caller can read their values. */
private fun <E> Fail<NonEmptyList<E>>.failAll(vararg branches: Either<E, *>) = failAll(branches.asList())

/** The same, for branches already in a list. */
private fun <E> Fail<NonEmptyList<E>>.failAll(branches: List<Either<E, *>>) {
    // A null error is still an error, so Left is matched rather than leftOrNull.
    branches.mapNotNull { it as? Either.Left }.map { it.value }.toNonEmptyListOrNull()?.let { fail(it) }
}

/** The value of a branch that [failAll] has already seen succeed. */
private fun <A> Either<*, A>.settled(): A = (this as Either.Right<A>).value

/** Runs [block] collecting into a list, then fails this scope with that list folded by [combine]. */
private fun <E, A> Fail<E>.combined(combine: (E, E) -> E, block: Fail<NonEmptyList<E>>.() -> A): A =
    attempt(block).getOrElse { fail(it.reduce(combine)) }
