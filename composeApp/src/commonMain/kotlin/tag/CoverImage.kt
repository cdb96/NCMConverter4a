package com.cdb96.ncmconverter4a.tag

import com.cdb96.ncmconverter4a.converter.startsWith

/** Cover art helpers shared by the ID3 and FLAC writers. */
internal object CoverImage {
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /**
     * NCM covers are JPEG or PNG. Anything else keeps the historical JPEG
     * label, which players treat as a hint and sniff past anyway.
     */
    fun mimeType(coverData: ByteArray): String =
        if (coverData.startsWith(PNG_SIGNATURE)) "image/png" else "image/jpeg"
}
