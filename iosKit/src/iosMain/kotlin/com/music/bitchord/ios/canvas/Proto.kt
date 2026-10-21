package com.music.bitchord.ios.canvas

/**
 * The sliver of the protobuf wire format the canvaz-cache fallback needs:
 * length-delimited fields out, and tags, strings and skipping in. Android
 * leans on protobuf-java's coded streams for this; iOS has no such library to
 * hand, and two message shapes of a few string fields each don't justify one.
 *
 * Reads throw [IllegalStateException] on truncated or malformed input, the
 * way the coded streams throw on theirs — the callers already treat any
 * throw as "nothing in here".
 */
internal object Proto {

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_START_GROUP = 3
    private const val WIRE_END_GROUP = 4
    private const val WIRE_FIXED32 = 5

    class Writer {
        private val out = ArrayList<Byte>()

        /** Field [field] as length-delimited [value]: a string's UTF-8 or an embedded message. */
        fun bytes(field: Int, value: ByteArray) {
            varint(((field shl 3) or WIRE_LENGTH_DELIMITED).toLong())
            varint(value.size.toLong())
            value.forEach(out::add)
        }

        private fun varint(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.add(v.toByte())
                    return
                }
                out.add(((v and 0x7F) or 0x80).toByte())
                v = v ushr 7
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    class Reader(private val bytes: ByteArray) {
        private var pos = 0

        val isAtEnd: Boolean get() = pos >= bytes.size

        /** The next field's tag (field number shifted left 3, or'd with the wire type), or 0 at the end. */
        fun readTag(): Int = if (isAtEnd) 0 else readVarint().toInt()

        fun readBytes(): ByteArray {
            val length = readVarint()
            check(length >= 0 && length <= bytes.size - pos) { "truncated length-delimited field" }
            val start = pos
            pos += length.toInt()
            return bytes.copyOfRange(start, pos)
        }

        fun readString(): String = readBytes().decodeToString()

        /** Steps over the field whose [tag] was just read, whatever its wire type. */
        fun skipField(tag: Int) {
            when (tag and 0x7) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> advance(8)
                WIRE_LENGTH_DELIMITED -> readBytes()
                WIRE_FIXED32 -> advance(4)
                WIRE_START_GROUP -> {
                    // Groups are long deprecated, but skipping one is cheap and
                    // means an old-style field can't derail the fields after it.
                    val field = tag ushr 3
                    while (true) {
                        check(!isAtEnd) { "unterminated group" }
                        val inner = readTag()
                        if (inner and 0x7 == WIRE_END_GROUP) {
                            check(inner ushr 3 == field) { "mismatched end group" }
                            return
                        }
                        skipField(inner)
                    }
                }
                else -> error("unexpected wire type ${tag and 0x7}")
            }
        }

        private fun advance(count: Int) {
            check(count <= bytes.size - pos) { "truncated fixed-width field" }
            pos += count
        }

        private fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                check(!isAtEnd) { "truncated varint" }
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            error("malformed varint")
        }
    }
}
