package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.NCMConverter
import com.cdb96.ncmconverter4a.converter.NcmFileInfo
import com.cdb96.ncmconverter4a.io.BinaryOutput
import com.cdb96.ncmconverter4a.io.InputStreamBinaryInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NcmFlacMetadataTest {
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
