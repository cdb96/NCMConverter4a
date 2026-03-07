package com.cdb96.ncmconverter4a.converter.kgg

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Ported from Go
/*
 * Copyright 2015 The Go Authors. All rights reserved.
 *
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */
class TeaCipher(key: ByteArray, private val rounds: Int = 64) {

    companion object {
        private const val DELTA = 0x9e3779b9u
    }

    private val key = ByteArray(16)

    init {
        require(key.size == 16) { "tea: incorrect key size" }
        require(rounds % 2 == 0) { "tea: odd number of rounds specified" }
        key.copyInto(this.key)
    }

    fun decrypt(dst: ByteArray, src: ByteArray) {
        val buffer = ByteBuffer.wrap(src).order(ByteOrder.BIG_ENDIAN)
        var v0 = buffer.int.toUInt()
        var v1 = buffer.int.toUInt()

        val keyBuffer = ByteBuffer.wrap(key).order(ByteOrder.BIG_ENDIAN)
        val k0 = keyBuffer.int.toUInt()
        val k1 = keyBuffer.int.toUInt()
        val k2 = keyBuffer.int.toUInt()
        val k3 = keyBuffer.int.toUInt()

        val delta = DELTA
        var sum = delta * (rounds / 2).toUInt()

        for (i in 0 until rounds / 2) {
            v1 -= ((v0 shl 4) + k2) xor (v0 + sum) xor ((v0 shr 5) + k3)
            v0 -= ((v1 shl 4) + k0) xor (v1 + sum) xor ((v1 shr 5) + k1)
            sum -= delta
        }

        val outBuffer = ByteBuffer.wrap(dst).order(ByteOrder.BIG_ENDIAN)
        outBuffer.putInt(v0.toInt())
        outBuffer.putInt(v1.toInt())
    }
}