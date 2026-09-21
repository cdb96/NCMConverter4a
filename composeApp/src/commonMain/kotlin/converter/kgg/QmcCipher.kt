package com.cdb96.ncmconverter4a.converter.kgg

import com.cdb96.ncmconverter4a.platform.Logger
import kotlin.math.abs

//ported from Unlock Music Project
object QmcCipher {

    private val log = Logger("KGG")

    /** Key stream segment of the RC4 variant; each segment restarts from the boxed state. */
    const val RC4_SEGMENT_SIZE = 5120

    /**
     * Read buffer for streaming decryption. A multiple of [RC4_SEGMENT_SIZE]
     * keeps every buffer boundary on a segment boundary, so no call has to
     * replay the key stream up to the middle of a segment before it can emit
     * its first byte.
     */
    const val STREAM_BUFFER_SIZE = 64 * RC4_SEGMENT_SIZE

    /** Mask ciphers reduce the stream offset modulo this period once it exceeds it. */
    private const val MASK_PERIOD = 0x7FFF

    fun createCipher(key: ByteArray): QmcStreamCipher {
        return if (key.size > 300) {
            log.i("RC4Cipher")
            QmcRC4Cipher(key)
        } else if (key.isNotEmpty()) {
            log.i("MapCipher")
            QmcMapCipher(key)
        } else {
            log.i("StaticCipher")
            QmcStaticCipher()
        }
    }

    interface QmcStreamCipher {
        /** Decrypts `data[0, length)` in place; [offset] is its absolute position in the stream. */
        fun decrypt(data: ByteArray, offset: Long = 0L, length: Int = data.size)
    }

    private fun checkRange(data: ByteArray, offset: Long, length: Int) {
        require(offset >= 0 && length in 0..data.size && length.toLong() <= Long.MAX_VALUE - offset) {
            "invalid QMC stream range"
        }
    }

    /**
     * RC4Cipher 是使用特定密钥的 RC4 实例
     */
    private class QmcRC4Cipher(
        private val key: ByteArray,
    ): QmcStreamCipher {
        private val box: ByteArray
        private val n: Int = key.size
        private var hash: UInt = 1u

        // The boxed state as unsigned ints, so the segment loop never has to
        // mask sign-extended bytes.
        private val boxValues: IntArray

        init {
            // The segment loop reduces sums of a state byte (< 256) and an index
            // (< n) by one conditional subtraction, which needs n > 255.
            require(n > 255) { "QMC RC4 key is too short: $n" }
            box = ByteArray(n)

            for (i in 0 until n) {
                box[i] = i.toByte()
            }

            var j = 0
            for (i in 0 until n) {
                j = (j + (box[i].toInt() and 0xFF) + (key[i % n].toInt() and 0xFF)) % n
                val temp = box[i]
                box[i] = box[j]
                box[j] = temp
            }
            boxValues = IntArray(n) { box[it].toInt() and 0xFF }

            getHashBase()
        }

        private fun getHashBase() {
            hash = 1u
            for (i in 0 until n) {
                val v = (key[i].toInt() and 0xFF).toUInt()
                if (v == 0u) {
                    continue
                }
                val nextHash = hash * v
                if (nextHash == 0u || nextHash <= hash) {
                    break
                }
                hash = nextHash
            }
        }

        override fun decrypt(data: ByteArray, offset: Long, length: Int) {
            checkRange(data, offset, length)
            var currentOffset = offset
            var processed = 0
            var toProcess = length

            // 处理第一段
            if (currentOffset < RC4_FIRST_SEGMENT_SIZE) {
                val blockSize = minOf(toProcess, (RC4_FIRST_SEGMENT_SIZE - currentOffset).toInt())
                encFirstSegment(data, 0, blockSize, currentOffset)
                currentOffset += blockSize
                processed += blockSize
                toProcess -= blockSize
            }
            if (toProcess == 0) return

            // One scratch copy of the key state per call instead of one per segment.
            val state = IntArray(n)
            while (toProcess > 0) {
                val segmentRemaining = RC4_SEGMENT_SIZE - (currentOffset % RC4_SEGMENT_SIZE).toInt()
                val blockSize = minOf(toProcess, segmentRemaining)
                encASegment(data, processed, blockSize, currentOffset, state)
                currentOffset += blockSize
                processed += blockSize
                toProcess -= blockSize
            }
        }

        private fun encFirstSegment(buf: ByteArray, start: Int, length: Int, offset: Long) {
            for (i in 0 until length) {
                buf[start + i] = (buf[start + i].toInt() xor key[getSegmentSkip(offset + i.toLong())].toInt()).toByte()
            }
        }

        /**
         * Applies one segment of the key stream to `buf[start, start + length)`.
         * The stream restarts from [boxValues] for every segment, discards
         * `offset % segment + segmentSkip` bytes, then XORs the payload. All
         * modulo-n reductions of the original are replaced by conditional
         * subtractions: both operands are always below n or below 256.
         */
        private fun encASegment(buf: ByteArray, start: Int, length: Int, offset: Long, state: IntArray) {
            boxValues.copyInto(state)
            val n = n
            var j = 0
            var k = 0

            val skipLen = (offset % RC4_SEGMENT_SIZE.toLong()).toInt() +
                getSegmentSkip(offset / RC4_SEGMENT_SIZE.toLong())
            repeat(skipLen) {
                if (++j == n) j = 0
                val atJ = state[j]
                k += atJ
                if (k >= n) k -= n
                state[j] = state[k]
                state[k] = atJ
            }

            for (i in start until start + length) {
                if (++j == n) j = 0
                val atJ = state[j]
                k += atJ
                if (k >= n) k -= n
                val atK = state[k]
                state[j] = atK
                state[k] = atJ
                var index = atK + atJ
                if (index >= n) index -= n
                buf[i] = (buf[i].toInt() xor state[index]).toByte()
            }
        }

        private fun getSegmentSkip(id: Long): Int {
            val seed = key[(id % n.toLong()).toInt()].toInt() and 0xFF
            val idx = (hash.toDouble() / ((id + 1L) * seed).toDouble() * 100.0).toLong()
            return abs(idx % n.toLong()).toInt()
        }

        companion object {
            const val RC4_FIRST_SEGMENT_SIZE = 128
        }
    }

    /**
     * XORs a mask table over `data[0, length)`. Up to offset 0x7FFF the table
     * is indexed by the offset itself; beyond that the original ciphers reduce
     * the offset modulo 0x7FFF, which is tracked here by a wrapping counter
     * instead of a division per byte.
     */
    private fun applyMaskTable(masks: ByteArray, data: ByteArray, offset: Long, length: Int) {
        val direct = (MASK_PERIOD + 1 - offset).coerceIn(0L, length.toLong()).toInt()
        for (i in 0 until direct) {
            data[i] = (data[i].toInt() xor masks[(offset + i).toInt()].toInt()).toByte()
        }
        if (direct == length) return

        var reduced = ((offset + direct) % MASK_PERIOD).toInt()
        for (i in direct until length) {
            data[i] = (data[i].toInt() xor masks[reduced].toInt()).toByte()
            if (++reduced == MASK_PERIOD) reduced = 0
        }
    }

    private class QmcMapCipher(private val key: ByteArray) : QmcStreamCipher {
        private val size = key.size

        // The mask depends only on the reduced offset, so it is tabulated once.
        private val masks = ByteArray(MASK_PERIOD + 1) { maskAt(it.toLong()) }

        private fun maskAt(reducedOffset: Long): Byte {
            val idx = ((reducedOffset * reducedOffset + 71214L) % size.toLong()).toInt()
            return rotate(key[idx], (idx and 0x7).toByte())
        }

        private fun rotate(value: Byte, bits: Byte): Byte {
            val rotate = ((bits + 4) % 8)
            val left = (value.toInt() and 0xFF) shl rotate
            val right = (value.toInt() and 0xFF) ushr rotate
            return ((left or right) and 0xFF).toByte()
        }

        override fun decrypt(data: ByteArray, offset: Long, length: Int) {
            checkRange(data, offset, length)
            applyMaskTable(masks, data, offset, length)
        }
    }

    private class QmcStaticCipher : QmcStreamCipher {
        private val staticCipherBox = byteArrayOf(
            0x77.toByte(), 0x48, 0x32, 0x73, 0xDE.toByte(), 0xF2.toByte(), 0xC0.toByte(), 0xC8.toByte(), //0x00
            0x95.toByte(), 0xEC.toByte(), 0x30, 0xB2.toByte(), 0x51, 0xC3.toByte(), 0xE1.toByte(), 0xA0.toByte(), //0x08
            0x9E.toByte(), 0xE6.toByte(), 0x9D.toByte(), 0xCF.toByte(), 0xFA.toByte(), 0x7F, 0x14, 0xD1.toByte(), //0x10
            0xCE.toByte(), 0xB8.toByte(), 0xDC.toByte(), 0xC3.toByte(), 0x4A, 0x67, 0x93.toByte(), 0xD6.toByte(), //0x18
            0x28, 0xC2.toByte(), 0x91.toByte(), 0x70, 0xCA.toByte(), 0x8D.toByte(), 0xA2.toByte(), 0xA4.toByte(), //0x20
            0xF0.toByte(), 0x08, 0x61, 0x90.toByte(), 0x7E, 0x6F, 0xA2.toByte(), 0xE0.toByte(), //0x28
            0xEB.toByte(), 0xAE.toByte(), 0x3E, 0xB6.toByte(), 0x67, 0xC7.toByte(), 0x92.toByte(), 0xF4.toByte(), //0x30
            0x91.toByte(), 0xB5.toByte(), 0xF6.toByte(), 0x6C, 0x5E, 0x84.toByte(), 0x40, 0xF7.toByte(), //0x38
            0xF3.toByte(), 0x1B, 0x02, 0x7F, 0xD5.toByte(), 0xAB.toByte(), 0x41, 0x89.toByte(), //0x40
            0x28, 0xF4.toByte(), 0x25, 0xCC.toByte(), 0x52, 0x11, 0xAD.toByte(), 0x43, //0x48
            0x68, 0xA6.toByte(), 0x41, 0x8B.toByte(), 0x84.toByte(), 0xB5.toByte(), 0xFF.toByte(), 0x2C, //0x50
            0x92.toByte(), 0x4A, 0x26, 0xD8.toByte(), 0x47, 0x6A, 0x7C, 0x95.toByte(), //0x58
            0x61, 0xCC.toByte(), 0xE6.toByte(), 0xCB.toByte(), 0xBB.toByte(), 0x3F, 0x47, 0x58, //0x60
            0x89.toByte(), 0x75, 0xC3.toByte(), 0x75, 0xA1.toByte(), 0xD9.toByte(), 0xAF.toByte(), 0xCC.toByte(), //0x68
            0x08, 0x73, 0x17, 0xDC.toByte(), 0xAA.toByte(), 0x9A.toByte(), 0xA2.toByte(), 0x16, //0x70
            0x41, 0xD8.toByte(), 0xA2.toByte(), 0x06, 0xC6.toByte(), 0x8B.toByte(), 0xFC.toByte(), 0x66, //0x78
            0x34, 0x9F.toByte(), 0xCF.toByte(), 0x18, 0x23, 0xA0.toByte(), 0x0A, 0x74, //0x80
            0xE7.toByte(), 0x2B, 0x27, 0x70, 0x92.toByte(), 0xE9.toByte(), 0xAF.toByte(), 0x37, //0x88
            0xE6.toByte(), 0x8C.toByte(), 0xA7.toByte(), 0xBC.toByte(), 0x62, 0x65, 0x9C.toByte(), 0xC2.toByte(), //0x90
            0x08, 0xC9.toByte(), 0x88.toByte(), 0xB3.toByte(), 0xF3.toByte(), 0x43, 0xAC.toByte(), 0x74, //0x98
            0x2C, 0x0F, 0xD4.toByte(), 0xAF.toByte(), 0xA1.toByte(), 0xC3.toByte(), 0x01, 0x64, //0xA0
            0x95.toByte(), 0x4E, 0x48, 0x9F.toByte(), 0xF4.toByte(), 0x35, 0x78, 0x95.toByte(), //0xA8
            0x7A, 0x39, 0xD6.toByte(), 0x6A, 0xA0.toByte(), 0x6D, 0x40, 0xE8.toByte(), //0xB0
            0x4F, 0xA8.toByte(), 0xEF.toByte(), 0x11, 0x1D, 0xF3.toByte(), 0x1B, 0x3F, //0xB8
            0x3F, 0x07, 0xDD.toByte(), 0x6F, 0x5B, 0x19, 0x30, 0x19, //0xC0
            0xFB.toByte(), 0xEF.toByte(), 0x0E, 0x37, 0xF0.toByte(), 0x0E, 0xCD.toByte(), 0x16, //0xC8
            0x49, 0xFE.toByte(), 0x53, 0x47, 0x13, 0x1A, 0xBD.toByte(), 0xA4.toByte(), //0xD0
            0xF1.toByte(), 0x40, 0x19, 0x60, 0x0E, 0xED.toByte(), 0x68, 0x09, //0xD8
            0x06, 0x5F, 0x4D, 0xCF.toByte(), 0x3D, 0x1A, 0xFE.toByte(), 0x20, //0xE0
            0x77, 0xE4.toByte(), 0xD9.toByte(), 0xDA.toByte(), 0xF9.toByte(), 0xA4.toByte(), 0x2B, 0x76, //0xE8
            0x1C, 0x71, 0xDB.toByte(), 0x00, 0xBC.toByte(), 0xFD.toByte(), 0x0C, 0x6C, //0xF0
            0xA5.toByte(), 0x47, 0xF7.toByte(), 0xF6.toByte(), 0x00, 0x79, 0x4A, 0x11, //0xF8
        )

        // The mask depends only on the reduced offset, so it is tabulated once.
        private val masks = ByteArray(MASK_PERIOD + 1) { maskAt(it.toLong()) }

        private fun maskAt(reducedOffset: Long): Byte =
            staticCipherBox[((reducedOffset * reducedOffset + 27L) and 0xFFL).toInt()]

        override fun decrypt(data: ByteArray, offset: Long, length: Int) {
            checkRange(data, offset, length)
            applyMaskTable(masks, data, offset, length)
        }
    }
}
