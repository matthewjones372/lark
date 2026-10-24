package io.github.matthewjones372.lark.assert

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Asserts this does not equal [unexpected], and returns it. */
infix fun <A> A.shouldNotBe(unexpected: A): A {
    if (this == unexpected) mismatch(expected = null, actual = this, expectedText = "anything but $unexpected")
    return this
}

/** Asserts this is null. */
fun <A> A?.shouldBeNull() {
    if (this != null) mismatch(expected = null, actual = this)
}

/** Asserts this is not null, and returns it as its non-null type. */
@OptIn(ExperimentalContracts::class)
fun <A : Any> A?.shouldNotBeNull(): A {
    contract { returns() implies (this@shouldNotBeNull != null) }
    return this ?: mismatch(expected = null, actual = null, expectedText = "a value, not null")
}

/** Asserts this is a [T], and returns it as one. */
@OptIn(ExperimentalContracts::class)
inline fun <reified T> Any?.shouldBeInstanceOf(): T {
    contract { returns() implies (this@shouldBeInstanceOf is T) }
    if (this is T) return this
    mismatch(expected = null, actual = typed(this), expectedText = "an instance of ${T::class.qualifiedName}")
}

/** Asserts this is the very object [expected] is, not only an equal one, and returns it. */
infix fun <A> A.shouldBeSameInstanceAs(expected: A): A {
    if (this !== expected) mismatch(expected = null, actual = identified(this), expectedText = identified(expected))
    return this
}

/** Asserts this is not the very object [unexpected] is, though it may equal it, and returns it. */
infix fun <A> A.shouldNotBeSameInstanceAs(unexpected: A): A {
    if (this === unexpected) {
        mismatch(expected = null, actual = identified(this), expectedText = "anything but ${identified(unexpected)}")
    }
    return this
}

/** A value with its type, for a check about its type. */
@PublishedApi
internal fun typed(value: Any?): String = value?.let { "$it of ${it::class.qualifiedName}" } ?: "null"

/** A value with its identity, for a check that two are or are not the same object. */
private fun identified(value: Any?): String =
    value?.let { "$it @${Integer.toHexString(System.identityHashCode(it))}" } ?: "null"
