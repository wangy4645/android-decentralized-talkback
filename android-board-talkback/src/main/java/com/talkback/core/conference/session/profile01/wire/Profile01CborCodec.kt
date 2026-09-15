package com.talkback.core.conference.session.profile01.wire

/**
 * Strict deterministic CBOR subset for Profile 01 Q4 wire facts.
 *
 * Decoder rejects non-canonical encodings; encoder produces canonical bytes only.
 */
object Profile01CborCodec {
    class CborException(message: String) : IllegalArgumentException(message)

    fun encode(value: CborValue): ByteArray = value.encode()

    fun decodeStrict(data: ByteArray): CborValue {
        val (value, offset) = decodeItem(data, 0)
        if (offset != data.size) throw CborException("trailing bytes")
        if (!value.encode().contentEquals(data)) throw CborException("non-canonical re-encoding")
        return value
    }

    fun decodeItem(data: ByteArray, offset: Int): Pair<CborValue, Int> {
        if (offset >= data.size) throw CborException("truncated item")
        val initial = data[offset].toInt() and 0xFF
        var cursor = offset + 1
        val major = initial shr 5
        val ai = initial and 0x1F
        when (major) {
            7 -> {
                if (ai == 22) return CborValue.Null to cursor
                throw CborException("unsupported simple/float value")
            }
            6 -> throw CborException("tags forbidden")
        }
        val (argument, nextCursor) = readArgument(data, cursor, ai)
        cursor = nextCursor
        return when (major) {
            0 -> CborValue.Unsigned(argument) to cursor
            1 -> throw CborException("negative integers forbidden")
            2, 3 -> {
                val length = argument.toInt()
                val end = cursor + length
                if (end > data.size) throw CborException("truncated string")
                val raw = data.copyOfRange(cursor, end)
                cursor = end
                if (major == 2) {
                    CborValue.ByteString(raw) to cursor
                } else {
                    CborValue.Text(raw.decodeToString()) to cursor
                }
            }
            4 -> {
                val items = ArrayList<CborValue>(argument.toInt())
                repeat(argument.toInt()) {
                    val (item, next) = decodeItem(data, cursor)
                    items += item
                    cursor = next
                }
                CborValue.CborArray(items) to cursor
            }
            5 -> {
                val entries = ArrayList<Pair<CborValue, CborValue>>(argument.toInt())
                var previousKeyBytes: ByteArray? = null
                repeat(argument.toInt()) {
                    val (key, keyNext) = decodeItem(data, cursor)
                    val keyBytes = key.encode()
                    previousKeyBytes?.let { prior ->
                        if (compareLex(keyBytes, prior) <= 0) {
                            throw CborException("map keys not deterministic or duplicate")
                        }
                    }
                    previousKeyBytes = keyBytes
                    cursor = keyNext
                    val (value, valueNext) = decodeItem(data, cursor)
                    cursor = valueNext
                    if (entries.any { it.first == key }) throw CborException("duplicate map key")
                    entries += key to value
                }
                CborValue.CborMap(entries) to cursor
            }
            else -> throw CborException("unsupported major type")
        }
    }

    private fun readArgument(data: ByteArray, offset: Int, ai: Int): Pair<Long, Int> {
        if (ai < 24) return ai.toLong() to offset
        val size =
            when (ai) {
                24 -> 1
                25 -> 2
                26 -> 4
                27 -> 8
                else -> throw CborException("indefinite/reserved additional information")
            }
        if (offset + size > data.size) throw CborException("truncated argument")
        var value = 0L
        for (i in 0 until size) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        val minimum =
            when (ai) {
                24 -> 24L
                25 -> 0x100L
                26 -> 0x10000L
                27 -> 0x100000000L
                else -> 0L
            }
        if (value < minimum) throw CborException("non-shortest argument")
        return value to offset + size
    }

    private fun compareLex(left: ByteArray, right: ByteArray): Int {
        val min = minOf(left.size, right.size)
        for (i in 0 until min) {
            val delta = (left[i].toInt() and 0xFF) - (right[i].toInt() and 0xFF)
            if (delta != 0) return delta
        }
        return left.size - right.size
    }

    private fun head(major: Int, value: Long): ByteArray {
        if (value < 0) throw CborException("negative value")
        return when {
            value < 24 -> byteArrayOf(((major shl 5) or value.toInt()).toByte())
            value <= 0xFF -> byteArrayOf(((major shl 5) or 24).toByte(), value.toByte())
            value <= 0xFFFF -> {
                byteArrayOf(
                    ((major shl 5) or 25).toByte(),
                    (value shr 8).toByte(),
                    value.toByte(),
                )
            }
            value <= 0xFFFFFFFFL -> {
                byteArrayOf(
                    ((major shl 5) or 26).toByte(),
                    (value shr 24).toByte(),
                    (value shr 16).toByte(),
                    (value shr 8).toByte(),
                    value.toByte(),
                )
            }
            else -> {
                byteArrayOf(
                    ((major shl 5) or 27).toByte(),
                    (value shr 56).toByte(),
                    (value shr 48).toByte(),
                    (value shr 40).toByte(),
                    (value shr 32).toByte(),
                    (value shr 24).toByte(),
                    (value shr 16).toByte(),
                    (value shr 8).toByte(),
                    value.toByte(),
                )
            }
        }
    }

    sealed class CborValue {
        abstract fun encode(): ByteArray

        data object Null : CborValue() {
            override fun encode(): ByteArray = byteArrayOf(0xF6.toByte())
        }

        data class Unsigned(val value: Long) : CborValue() {
            override fun encode(): ByteArray {
                require(value >= 0) { "negative value" }
                return head(0, value)
            }
        }

        data class ByteString(val bytes: ByteArray) : CborValue() {
            override fun encode(): ByteArray = head(2, bytes.size.toLong()) + bytes
        }

        data class Text(val value: String) : CborValue() {
            override fun encode(): ByteArray {
                val encoded = value.encodeToByteArray()
                return head(3, encoded.size.toLong()) + encoded
            }
        }

        data class CborArray(val items: List<CborValue>) : CborValue() {
            override fun encode(): ByteArray =
                head(4, items.size.toLong()) + items.fold(byteArrayOf()) { acc, item -> acc + item.encode() }
        }

        data class CborMap(val entries: List<Pair<CborValue, CborValue>>) : CborValue() {
            override fun encode(): ByteArray {
                val sorted =
                    entries
                        .map { entry -> entry.first.encode() to entry }
                        .sortedWith { left, right -> compareLex(left.first, right.first) }
                        .map { it.second }
                return head(5, sorted.size.toLong()) +
                    sorted.fold(byteArrayOf()) { acc, (key, value) ->
                        acc + key.encode() + value.encode()
                    }
            }
        }

        fun asUnsigned(): Long? = (this as? Unsigned)?.value
        fun asText(): String? = (this as? Text)?.value
        fun asByteString(): ByteArray? = (this as? ByteString)?.bytes?.copyOf()
        fun asArray(): List<CborValue>? = (this as? CborArray)?.items
        fun asMap(): List<Pair<CborValue, CborValue>>? = (this as? CborMap)?.entries

        fun intKeyMap(): kotlin.collections.Map<Int, CborValue>? {
            val map = asMap() ?: return null
            val out = LinkedHashMap<Int, CborValue>(map.size)
            for ((key, value) in map) {
                val intKey = key.asUnsigned()?.toInt() ?: return null
                out[intKey] = value
            }
            return out
        }
    }
}
