package com.cdb96.ncmconverter4a.io

/**
 * Small platform-neutral binary input abstraction used by the streaming
 * converters.  Platform source sets adapt java.io.InputStream to this API.
 */
interface BinaryInput {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

interface BinaryOutput {
    fun write(buffer: ByteArray, offset: Int, length: Int)
}

class BinaryInputException(message: String) : IllegalArgumentException(message)

fun BinaryInput.readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
    require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
        "invalid read range: offset=$offset length=$length bufferSize=${buffer.size}"
    }

    var position = offset
    val end = offset + length
    while (position < end) {
        val bytesRead = read(buffer, position, end - position)
        when {
            bytesRead < 0 -> throw BinaryInputException(
                "unexpected end of input while reading $length bytes"
            )
            bytesRead == 0 -> throw BinaryInputException("input returned zero bytes")
            bytesRead > end - position -> throw BinaryInputException(
                "input returned too many bytes: $bytesRead"
            )
            else -> position += bytesRead
        }
    }
}

/** Reads up to [buffer.size] bytes, tolerating short reads from the source. */
fun BinaryInput.readAtMost(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int {
    require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
        "invalid read range: offset=$offset length=$length bufferSize=${buffer.size}"
    }

    var total = 0
    while (total < length) {
        val bytesRead = read(buffer, offset + total, length - total)
        when {
            bytesRead < 0 -> break
            bytesRead == 0 -> throw BinaryInputException("input returned zero bytes")
            bytesRead > length - total -> throw BinaryInputException(
                "input returned too many bytes: $bytesRead"
            )
            else -> total += bytesRead
        }
    }
    return total
}

fun BinaryInput.skipFully(bytes: Long) {
    require(bytes >= 0) { "cannot skip a negative number of bytes: $bytes" }
    if (bytes == 0L) return

    val scratch = ByteArray(8192)
    var remaining = bytes
    while (remaining > 0) {
        val wanted = minOf(remaining, scratch.size.toLong()).toInt()
        val bytesRead = read(scratch, 0, wanted)
        when {
            bytesRead < 0 -> throw BinaryInputException(
                "unexpected end of input while skipping $bytes bytes"
            )
            bytesRead == 0 -> throw BinaryInputException("input returned zero bytes")
            bytesRead > wanted -> throw BinaryInputException(
                "input returned too many bytes: $bytesRead"
            )
            else -> remaining -= bytesRead
        }
    }
}

fun BinaryOutput.write(bytes: ByteArray) = write(bytes, 0, bytes.size)
