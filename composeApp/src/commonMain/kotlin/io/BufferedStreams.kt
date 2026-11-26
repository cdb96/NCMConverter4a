package com.cdb96.ncmconverter4a.io

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Coalesces small writes (such as ID3/FLAC headers, metadata frames, and small
 * audio payloads) into [buf] while forwarding chunk-sized writes directly to [out]
 * without an extra copy. Does not close [out] or own [buf] so callers can pool [buf].
 */
internal class PassThroughBufferedOutputStream(
    private val out: OutputStream,
    private val buf: ByteArray,
) : OutputStream() {
    private var count = 0

    override fun write(b: Int) {
        if (count >= buf.size) {
            flushBuffer()
        }
        buf[count++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off > b.size - len) {
            throw IndexOutOfBoundsException("invalid write range: off=$off len=$len size=${b.size}")
        }
        if (len == 0) return

        if (len >= buf.size) {
            flushBuffer()
            out.write(b, off, len)
            return
        }
        if (len > buf.size - count) {
            flushBuffer()
        }
        b.copyInto(buf, destinationOffset = count, startIndex = off, endIndex = off + len)
        count += len
    }

    override fun flush() {
        flushBuffer()
        out.flush()
    }

    fun flushBuffer() {
        if (count > 0) {
            out.write(buf, 0, count)
            count = 0
        }
    }
}

/**
 * Prefetching input wrapper that supports arbitrary small [peek] inspections
 * (replacing BufferedInputStream mark/reset), forwards large reads directly to
 * [raw], and can also wrap an already-read in-memory small-file buffer via [fromBuffer].
 */
internal class PrefetchInputStream private constructor(
    private val raw: InputStream?,
    private val buf: ByteArray,
    initialLimit: Int,
    initialEof: Boolean,
) : InputStream() {
    constructor(raw: InputStream, buf: ByteArray) : this(raw, buf, 0, false)

    companion object {
        fun fromBuffer(buf: ByteArray, length: Int): PrefetchInputStream =
            PrefetchInputStream(raw = null, buf = buf, initialLimit = length, initialEof = true)
    }

    private var pos = 0
    private var limit = initialLimit
    private var eof = initialEof

    fun peek(dest: ByteArray, length: Int = dest.size): Int {
        require(length in 0..minOf(dest.size, buf.size)) {
            "invalid peek length: $length (dest=${dest.size}, buf=${buf.size})"
        }
        if (length == 0) return 0

        val unread = limit - pos
        if (unread < length && !eof && raw != null) {
            if (pos > 0 && buf.size - pos < length) {
                if (unread > 0) {
                    buf.copyInto(buf, destinationOffset = 0, startIndex = pos, endIndex = limit)
                }
                pos = 0
                limit = unread
            }
            while (limit - pos < length && !eof) {
                val read = raw.read(buf, limit, buf.size - limit)
                if (read < 0) {
                    eof = true
                } else if (read == 0) {
                    throw BinaryInputException("input returned zero bytes")
                } else {
                    limit += read
                }
            }
        }

        val available = minOf(length, limit - pos)
        if (available > 0) {
            buf.copyInto(dest, destinationOffset = 0, startIndex = pos, endIndex = pos + available)
        }
        return available
    }

    override fun read(): Int {
        if (pos >= limit) {
            if (!refill()) return -1
        }
        return buf[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off > b.size - len) {
            throw IndexOutOfBoundsException("invalid read range: off=$off len=$len size=${b.size}")
        }
        if (len == 0) return 0

        if (pos < limit) {
            val copied = minOf(len, limit - pos)
            buf.copyInto(b, destinationOffset = off, startIndex = pos, endIndex = pos + copied)
            pos += copied
            return copied
        }
        if (eof || raw == null) return -1

        if (len >= buf.size) {
            val read = raw.read(b, off, len)
            if (read < 0) eof = true
            return read
        }

        if (!refill()) return -1
        val copied = minOf(len, limit - pos)
        buf.copyInto(b, destinationOffset = off, startIndex = pos, endIndex = pos + copied)
        pos += copied
        return copied
    }

    fun skipFully(bytes: Long) {
        require(bytes >= 0) { "cannot skip a negative number of bytes: $bytes" }
        if (bytes == 0L) return

        var remaining = bytes
        val buffered = limit - pos
        if (buffered > 0) {
            val consumed = minOf(remaining, buffered.toLong()).toInt()
            pos += consumed
            remaining -= consumed
        }

        while (remaining > 0) {
            if (raw == null) {
                throw BinaryInputException("unexpected end of input while skipping $bytes bytes")
            }
            val skipped = raw.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val wanted = minOf(remaining, buf.size.toLong()).toInt()
                val read = raw.read(buf, 0, wanted)
                when {
                    read < 0 -> throw BinaryInputException(
                        "unexpected end of input while skipping $bytes bytes"
                    )
                    read == 0 -> throw BinaryInputException("input returned zero bytes")
                    else -> remaining -= read
                }
            }
        }
    }

    override fun close() {
        raw?.close()
    }

    private fun refill(): Boolean {
        if (eof || raw == null) return false
        pos = 0
        limit = 0
        val read = raw.read(buf, 0, buf.size)
        if (read < 0) {
            eof = true
            return false
        }
        if (read == 0) {
            throw BinaryInputException("input returned zero bytes")
        }
        limit = read
        return true
    }
}

internal class WorkerBuffers(
    audioBufferSize: Int,
    inputPrefetchSize: Int = 16 * 1024,
    outputScratchSize: Int = audioBufferSize,
) {
    val audioBuffer = ByteArray(audioBufferSize)
    val inputPrefetch = ByteArray(inputPrefetchSize)
    val smallInputBuffer = ByteArray(audioBufferSize)
    val outputScratch = ByteArray(outputScratchSize)
    val headerPeek = ByteArray(24)
}

internal class ConversionBufferPool(private val audioBufferSize: Int) {
    private val pool = ConcurrentLinkedQueue<WorkerBuffers>()

    fun acquire(): WorkerBuffers = pool.poll() ?: WorkerBuffers(audioBufferSize)

    fun release(buffers: WorkerBuffers) {
        pool.offer(buffers)
    }
}
