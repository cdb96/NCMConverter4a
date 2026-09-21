package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.NCMConverter
import com.cdb96.ncmconverter4a.converter.NcmFileInfo
import com.cdb96.ncmconverter4a.io.BinaryInput
import com.cdb96.ncmconverter4a.io.BinaryOutput
import com.cdb96.ncmconverter4a.io.InputStreamBinaryInput
import com.cdb96.ncmconverter4a.io.OutputStreamBinaryOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NcmFlacMetadataTest {
    @Test
    fun rawStreamingWritesMultipleDecryptedChunksWithoutLosingThePrefix() {
        val key = byteArrayOf(3, 1, 4, 1, 5)
        val plainPayload = ByteArray(1027) { (it * 29 + 7).toByte() }
        val output = ByteArrayOutputStream()

        NCMConverter.writeAudio(
            input = InputStreamBinaryInput(ByteArrayInputStream(rc4(plainPayload, key))),
            output = object : BinaryOutput {
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    output.write(buffer, offset, length)
                }
            },
            info = NcmFileInfo(key, byteArrayOf(), "Title", "Album", "Artist", "mp3"),
            rawWriteMode = true,
            bufferSize = 256,
        )

        assertContentEquals(plainPayload, output.toByteArray())
    }

    @Test
    fun addsLastPictureWhenVorbisCommentWasTheLastMetadataBlock() {
        val audioTail = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val plainPayload = flacPayload(audioTail)
        val key = byteArrayOf(1, 2, 3, 4, 5)
        val encryptedPayload = rc4(plainPayload, key)
        val output = ByteArrayOutputStream()

        NCMConverter.writeAudio(
            input = InputStreamBinaryInput(ByteArrayInputStream(encryptedPayload)),
            output = object : BinaryOutput {
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    output.write(buffer, offset, length)
                }
            },
            info = NcmFileInfo(key, byteArrayOf(9, 8, 7), "Title", "Album", "Artist", "flac"),
            rawWriteMode = false,
            bufferSize = 256,
        )

        val result = output.toByteArray()
        assertContentEquals("fLaC".encodeToByteArray(), result.copyOfRange(0, 4))

        var position = 4
        val headers = ArrayList<ByteArray>()
        repeat(3) {
            val header = result.copyOfRange(position, position + 4)
            headers += header
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            position += 4 + length
        }

        assertEquals(0, headers[0][0].toInt() and 0x80)
        assertEquals(4, headers[1][0].toInt() and 0x7F)
        assertEquals(0, headers[1][0].toInt() and 0x80)
        assertEquals(0x86, headers[2][0].toInt() and 0xFF)
        assertTrue(result.copyOfRange(position, result.size).contentEquals(audioTail))
    }

    @Test
    fun preservesVendorAndOtherBlocksAcrossDecryptBufferBoundaries() {
        val vendor = ByteArray(1031) { ('A'.code + it % 26).toByte() }
        val oldComments = "TITLE=${"旧标题".repeat(100)}".encodeToByteArray()
        val oldVorbis = ByteBuffer.allocate(12 + vendor.size + oldComments.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(vendor.size).put(vendor).putInt(1).putInt(oldComments.size).put(oldComments).array()
        val application = ByteArray(777) { (it * 7).toByte() }
        val padding = ByteArray(4097)
        val audio = ByteArray(1029) { (it * 17 + 3).toByte() }
        val payload = "fLaC".encodeToByteArray() +
            flacBlock(0, false, ByteArray(34)) +
            flacBlock(2, false, application) +
            flacBlock(4, false, oldVorbis) +
            flacBlock(1, true, padding) + audio

        val (blocks, actualAudio) = parseFlac(convertFlac(payload))

        assertEquals(listOf(0, 2, 4, 6, 0x81), blocks.map { it.first })
        assertContentEquals(application, blocks[1].second)
        assertContentEquals(padding, blocks.last().second)
        assertContentEquals(audio, actualAudio)
        val vorbis = ByteBuffer.wrap(blocks[2].second).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(vendor.size, vorbis.int)
        assertContentEquals(vendor, ByteArray(vendor.size).also { vorbis.get(it) })
        assertEquals(3, vorbis.int)
        val comments = List(3) {
            ByteArray(vorbis.int).also { vorbis.get(it) }.decodeToString()
        }
        assertEquals(listOf("ARTIST=Artist", "TITLE=Title", "ALBUM=Album"), comments)
        assertEquals(0, vorbis.remaining())
        val picture = ByteBuffer.wrap(blocks[3].second)
        assertEquals(3, picture.int)
        val mime = ByteArray(picture.int).also { picture.get(it) }.decodeToString()
        assertEquals("image/jpeg", mime)
        repeat(5) { assertEquals(0, picture.int) } // description length and dimensions
        assertEquals(3, picture.int)
        assertContentEquals(byteArrayOf(9, 8, 7), ByteArray(3).also { picture.get(it) })
        assertEquals(0, picture.remaining())
    }

    @Test
    fun leavesMetadataWithoutVorbisUnchanged() {
        val payload = "fLaC".encodeToByteArray() +
            flacBlock(0, false, ByteArray(34)) +
            flacBlock(2, true, ByteArray(777) { it.toByte() }) + byteArrayOf(1, 2, 3)

        assertContentEquals(payload, convertFlac(payload))
    }

    @Test
    fun preservesAdditionalVorbisBlockWhenItIsLast() {
        val secondVorbis = ByteArray(8)
        val payload = "fLaC".encodeToByteArray() +
            flacBlock(0, false, ByteArray(34)) +
            flacBlock(4, false, ByteArray(8)) +
            flacBlock(4, true, secondVorbis) + byteArrayOf(1, 2, 3)

        val (blocks, audio) = parseFlac(convertFlac(payload))

        assertEquals(listOf(0, 4, 6, 0x84), blocks.map { it.first })
        assertContentEquals(secondVorbis, blocks.last().second)
        assertContentEquals(byteArrayOf(1, 2, 3), audio)
    }

    @Test
    fun rejectsTruncatedOrOversizedMetadataIncludingSkippedComments() {
        val invalidBlocks = listOf(
            byteArrayOf(0x80.toByte(), 0, 0, 2, 1), // truncated body
            byteArrayOf(0, 0, 0, 0), // no last block
            byteArrayOf(0x84.toByte(), 0, 0, 3, 0, 0, 0), // no vendor length
            byteArrayOf(0x84.toByte(), 0, 0, 8, 5, 0, 0, 0, 0, 0, 0, 0), // vendor too long
            byteArrayOf(0x84.toByte(), 0, 0, 8, -1, -1, -1, -1, 0, 0, 0, 0),
            byteArrayOf(0x84.toByte(), 0, 0, 8, 4, 0, 0, 0, 1, 2), // truncated vendor
            byteArrayOf(0x84.toByte(), 0, 0, 8, 0, 0, 0, 0, 0, 0, 0), // truncated comments
            byteArrayOf(0x81.toByte(), -1, -1, -1) // total metadata exceeds the limit
        )
        for (block in invalidBlocks) {
            assertFailsWith<IllegalArgumentException> {
                convertFlac("fLaC".encodeToByteArray() + block)
            }
        }
    }

    @Test
    fun streamsLargeMetadataInBoundedChunksWithoutChangingBytes() {
        val paddingSize = 8 * 1024 * 1024
        val audioSize = 257
        val prefix = "fLaC".encodeToByteArray() + flacBlock(0, false, ByteArray(34)) +
            byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 0)
        val total = prefix.size + paddingSize + audioSize
        val key = byteArrayOf(1, 2, 3, 4, 5)
        val keyStream = rc4(ByteArray(256), key)
        val input = object : BinaryInput {
            var position = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(buffer.size <= NCMConverter.AUDIO_BUFFER_SIZE)
                if (position == total) return -1
                val count = minOf(length, total - position, 65521)
                repeat(count) { index ->
                    val plain = if (position < prefix.size) prefix[position] else 0
                    buffer[offset + index] = (plain.toInt() xor keyStream[position % 256].toInt()).toByte()
                    position++
                }
                return count
            }
        }
        val actualCrc = CRC32()
        var written = 0L
        NCMConverter.writeAudio(
            input,
            object : BinaryOutput {
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    assertTrue(buffer.size <= NCMConverter.AUDIO_BUFFER_SIZE)
                    actualCrc.update(buffer, offset, length)
                    written += length
                }
            },
            NcmFileInfo(key, byteArrayOf(), "Title", "Album", "Artist", "flac"),
            rawWriteMode = false
        )

        val expectedCrc = CRC32().apply { update(prefix) }
        val zeros = ByteArray(8192)
        var remaining = paddingSize + audioSize
        while (remaining > 0) {
            val length = minOf(remaining, zeros.size)
            expectedCrc.update(zeros, 0, length)
            remaining -= length
        }
        assertEquals(total.toLong(), written)
        assertEquals(expectedCrc.value, actualCrc.value)
    }

    private fun convertFlac(payload: ByteArray): ByteArray {
        val key = byteArrayOf(1, 2, 3, 4, 5)
        val input = object : ByteArrayInputStream(rc4(payload, key)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 17))
        }
        val output = ByteArrayOutputStream()
        NCMConverter.writeAudio(
            InputStreamBinaryInput(input),
            OutputStreamBinaryOutput(output),
            NcmFileInfo(key, byteArrayOf(9, 8, 7), "Title", "Album", "Artist", "flac"),
            rawWriteMode = false,
            bufferSize = 256
        )
        return output.toByteArray()
    }

    private fun parseFlac(bytes: ByteArray): Pair<List<Pair<Int, ByteArray>>, ByteArray> {
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        var position = 4
        do {
            val type = bytes[position].toInt() and 0xFF
            val length = ByteBuffer.wrap(bytes, position, 4).int and 0xFFFFFF
            blocks += type to bytes.copyOfRange(position + 4, position + 4 + length)
            position += 4 + length
        } while (type and 0x80 == 0)
        return blocks to bytes.copyOfRange(position, bytes.size)
    }

    private fun flacPayload(audioTail: ByteArray): ByteArray {
        val streamInfo = ByteArray(34)
        val vorbis = ByteArray(8) // empty vendor and empty comment list
        return ByteArrayOutputStream().apply {
            write("fLaC".encodeToByteArray())
            write(flacBlock(type = 0, isLast = false, body = streamInfo))
            write(flacBlock(type = 4, isLast = true, body = vorbis))
            write(audioTail)
        }.toByteArray()
    }

    private fun flacBlock(type: Int, isLast: Boolean, body: ByteArray): ByteArray {
        val header = byteArrayOf(
            (type or if (isLast) 0x80 else 0).toByte(),
            (body.size ushr 16).toByte(),
            (body.size ushr 8).toByte(),
            body.size.toByte(),
        )
        return header + body
    }

    private fun rc4(input: ByteArray, key: ByteArray): ByteArray {
        val state = ByteArray(256) { it.toByte() }
        var j = 0
        for (i in state.indices) {
            j = (j + state[i].toInt() + key[i % key.size].toInt()) and 0xFF
            state[i] = state[j].also { state[j] = state[i] }
        }

        val keyStream = ByteArray(256)
        for (k in 1 until 256) {
            val index = (state[k].toInt() + state[(state[k].toInt() + k) and 0xFF].toInt()) and 0xFF
            keyStream[k - 1] = state[index]
        }
        keyStream[255] = state[(state[0].toInt() + state[state[0].toInt() and 0xFF].toInt()) and 0xFF]

        val output = input.copyOf()
        var i = 0
        for (index in output.indices) {
            output[index] = (output[index].toInt() xor keyStream[i].toInt()).toByte()
            i = (i + 1) and 0xFF
        }
        return output
    }
}
