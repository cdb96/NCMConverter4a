package com.cdb96.ncmconverter4a.converter.kgg

import com.cdb96.ncmconverter4a.util.LengthUtils.readIntBE
import com.cdb96.ncmconverter4a.util.LengthUtils.writeIntBE

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
        var v0 = readIntBE(src, 0).toUInt()
        var v1 = readIntBE(src, 4).toUInt()

        val k0 = readIntBE(key, 0).toUInt()
        val k1 = readIntBE(key, 4).toUInt()
        val k2 = readIntBE(key, 8).toUInt()
        val k3 = readIntBE(key, 12).toUInt()

        val delta = DELTA
        var sum = delta * (rounds / 2).toUInt()

        for (i in 0 until rounds / 2) {
            v1 -= ((v0 shl 4) + k2) xor (v0 + sum) xor ((v0 shr 5) + k3)
            v0 -= ((v1 shl 4) + k0) xor (v1 + sum) xor ((v1 shr 5) + k1)
            sum -= delta
        }

        writeIntBE(dst, 0, v0.toInt())
        writeIntBE(dst, 4, v1.toInt())
    }
}
