package io.github.matthewjones372.lark.structured

import arrow.core.raise.Raise
import io.github.matthewjones372.lark.Flock
import java.util.concurrent.Callable
import java.util.concurrent.StructuredTaskScope
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs every branch as a subtask of one scope named [name] and answers with their values in order. The
 * first branch to fail cancels the rest from the scope's `Joiner`, and its failure is the answer.
 */
fun <E, A> Raise<E>.parAll(name: String, vararg branches: Flock<E>.() -> A): List<A> {
    val (failure, subtasks) = runBranches(name, FirstFailure(), branches)
    failure?.let { surface(it) }
    return subtasks.map { subtask ->
        when (val outcome = subtask.get()) {
            is Returned -> outcome.value
            is Failure -> surface(outcome)
        }
    }
}

/**
 * Runs every branch as a subtask of one scope named [name] and answers with the first to finish, whatever
 * it finished with. The `Joiner` cancels the rest the moment it has an answer.
 */
fun <E, A> Raise<E>.firstOf(name: String, vararg branches: Flock<E>.() -> A): A {
    require(branches.isNotEmpty()) { "firstOf needs a branch to answer" }
    val (first, _) = runBranches(name, FirstToFinish(), branches)
    return when (val outcome = checkNotNull(first) { "a joined race has a finisher" }) {
        is Returned -> outcome.value
        is Failure -> surface(outcome)
    }
}

private fun <E, A, R> runBranches(
    name: String,
    joiner: StructuredTaskScope.Joiner<Outcome<E, A>, R, RuntimeException>,
    branches: Array<out Flock<E>.() -> A>,
): Pair<R, List<StructuredTaskScope.Subtask<Outcome<E, A>>>> {
    val threads = Thread.ofVirtual().name("$name/", 1).factory()
    return StructuredTaskScope.open(joiner) { it.withName(name).withThreadFactory(threads) }.use { tasks ->
        val subtasks = branches.mapIndexed { i, branch -> tasks.fork(Callable { capture("$name/${i + 1}", branch) }) }
        tasks.join() to subtasks
    }
}

/** Every subtask succeeds as far as the JDK knows: a branch's failure is a value, read here. */
private class FirstFailure<E, A> : StructuredTaskScope.Joiner<Outcome<E, A>, Failure<E>?, RuntimeException> {

    private val first = AtomicReference<Failure<E>?>()

    override fun onComplete(subtask: StructuredTaskScope.Subtask<Outcome<E, A>>): Boolean {
        val failure = subtask.get() as? Failure<E> ?: return false
        first.compareAndSet(null, failure)
        return true
    }

    override fun result(): Failure<E>? = first.get()

    override fun timeout(): Failure<E>? = first.get()
}

private class FirstToFinish<E, A> : StructuredTaskScope.Joiner<Outcome<E, A>, Outcome<E, A>?, RuntimeException> {

    private val first = AtomicReference<Outcome<E, A>?>()

    override fun onComplete(subtask: StructuredTaskScope.Subtask<Outcome<E, A>>): Boolean {
        first.compareAndSet(null, subtask.get())
        return true
    }

    override fun result(): Outcome<E, A>? = first.get()

    override fun timeout(): Outcome<E, A>? = first.get()
}
