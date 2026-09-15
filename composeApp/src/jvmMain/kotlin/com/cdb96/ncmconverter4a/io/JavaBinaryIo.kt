package com.cdb96.ncmconverter4a.io

import com.cdb96.ncmconverter4a.converter.EncryptedFormat
import com.cdb96.ncmconverter4a.converter.detectEncryptedFormat
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream

/** Detects the encrypted format without consuming the stream header. */
fun BufferedInputStream.detectEncryptedFormat(): EncryptedFormat {
    mark(32)
    val header = ByteArray(16)
    val bytesRead = readChunk(header)
    reset()
    return detectEncryptedFormat(header.copyOf(bytesRead.coerceAtLeast(0)))
}

class InputStreamBinaryInput(private val input: InputStream) : BinaryInput {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        input.read(buffer, offset, length)
}

class OutputStreamBinaryOutput(private val output: OutputStream) : BinaryOutput {
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        output.write(buffer, offset, length)
    }
}

/** Fills a chunk unless EOF is reached, so each non-final chunk is stable. */
fun InputStream.readChunk(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val bytesRead = read(buffer, total, buffer.size - total)
        when {
            bytesRead < 0 -> break
            bytesRead == 0 -> {
                val one = read()
                if (one < 0) break
                buffer[total++] = one.toByte()
            }
            bytesRead > buffer.size - total ->
                throw IllegalArgumentException("input returned too many bytes: $bytesRead")
            else -> total += bytesRead
        }
    }
    return if (total == 0) -1 else total
}

fun InputStream.readFully(buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) {
        val bytesRead = read(buffer, offset, buffer.size - offset)
        when {
            bytesRead < 0 -> throw IllegalArgumentException(
                "unexpected end of input while reading ${buffer.size} bytes"
            )
            bytesRead == 0 -> {
                val one = read()
                if (one < 0) throw IllegalArgumentException("unexpected end of input")
                buffer[offset++] = one.toByte()
            }
            bytesRead > buffer.size - offset ->
                throw IllegalArgumentException("input returned too many bytes: $bytesRead")
            else -> offset += bytesRead
        }
    }
}

fun InputStream.skipFully(bytes: Long) {
    require(bytes >= 0) { "cannot skip a negative number of bytes: $bytes" }
    val scratch = ByteArray(8192)
    var remaining = bytes
    while (remaining > 0) {
        val bytesRead = read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
        when {
            bytesRead < 0 -> throw IllegalArgumentException("unexpected end of input while skipping")
            bytesRead == 0 -> {
                val one = read()
                if (one < 0) throw IllegalArgumentException("unexpected end of input while skipping")
                remaining--
            }
            else -> remaining -= bytesRead
        }
    }
}
