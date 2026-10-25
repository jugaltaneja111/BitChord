package com.music.bitchord.download

/**
 * A growable byte buffer, the taggers' stand-in for `java.io.ByteArrayOutputStream` so they
 * build on every platform the downloads run on.
 */
internal class ByteSink(capacity: Int = 64) {
    private var buffer = ByteArray(capacity.coerceAtLeast(16))
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        var next = buffer.size * 2
        while (next < size + extra) next *= 2
        buffer = buffer.copyOf(next)
    }

    /** The low byte of [byte], as the stream's `write(int)`. */
    fun write(byte: Int) {
        ensure(1)
        buffer[size++] = byte.toByte()
    }

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        ensure(length)
        bytes.copyInto(buffer, size, offset, offset + length)
        size += length
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

/** A four-character code or other ASCII run as its bytes, one per character. */
internal fun String.latin1Bytes(): ByteArray = ByteArray(length) { this[it].code.toByte() }

/** [length] bytes from [offset] as characters, one per byte. */
internal fun latin1String(bytes: ByteArray, offset: Int, length: Int): String =
    buildString(length) { for (i in offset until offset + length) append((bytes[i].toInt() and 0xFF).toChar()) }
