package io.github.matthewjones372.lark.app.kotlinx

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.typesafe.Reading
import io.github.matthewjones372.lark.app.typesafe.config
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/**
 * The section at [path] as a node, read from [A]'s own properties: each is a path named after it, a
 * nested class a nested section. Every fault is named at once, as the block form names them.
 */
inline fun <reified A : Any> config(path: String): Module = config(path) { decoded(serializer<A>()) }

@PublishedApi
internal fun <A> Reading.decoded(deserializer: DeserializationStrategy<A>): A =
    deserializer.deserialize(SectionDecoder(this))

private const val DURATION = "kotlin.time.Duration"

/**
 * Each property read through [reading]'s own leaves, so a fault is recorded and the read carries on.
 *
 * Every property without a default is handed out, present or not: absent, it is the leaf that records
 * the fault, where kotlinx would stop at the first with a `MissingFieldException`.
 */
@OptIn(ExperimentalSerializationApi::class)
private class SectionDecoder(private val reading: Reading) : AbstractDecoder() {

    override val serializersModule: SerializersModule = EmptySerializersModule()

    private var next = 0
    private var key = ""

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (next < descriptor.elementsCount) {
            val index = next++
            key = descriptor.getElementName(index)
            if (!descriptor.isElementOptional(index) || reading.config.hasPath(key)) return index
        }
        return CompositeDecoder.DECODE_DONE
    }

    override fun decodeNotNullMark(): Boolean = reading.config.hasPath(key)

    override fun decodeString(): String = reading.string(key)

    override fun decodeInt(): Int = reading.int(key)

    override fun decodeLong(): Long = reading.long(key)

    override fun decodeBoolean(): Boolean = reading.boolean(key)

    // The casts hold because each branch is chosen by the descriptor of the very type it returns.
    @Suppress("UNCHECKED_CAST")
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        val descriptor = deserializer.descriptor
        return when {
            // kotlinx's own serializer reads ISO-8601; HOCON says `30s`.
            descriptor.serialName == DURATION -> reading.duration(key) as T

            descriptor.kind == StructureKind.LIST && descriptor.getElementDescriptor(0).kind == PrimitiveKind.STRING ->
                reading.strings(key) as T

            descriptor.kind == StructureKind.CLASS -> reading.section(key) { decoded(deserializer) }

            else -> super.decodeSerializableValue(deserializer)
        }
    }
}
