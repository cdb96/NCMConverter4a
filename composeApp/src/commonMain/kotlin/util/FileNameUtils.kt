package com.cdb96.ncmconverter4a.util

object FileNameUtils {
    private const val MAX_BASE_NAME_BYTES = 180
    private val RESERVED_NAME = Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")

    fun normalizeExtension(extension: String): String =
        extension.removePrefix(".").lowercase().also {
            require(it.matches(Regex("[a-z0-9]+"))) { "非法输出格式: $extension" }
        }

    fun outputFileName(name: String, extension: String, sequence: Int = 0): String {
        val suffix = if (sequence == 0) "" else " ($sequence)"
        return "${sanitizeFileName(name)}$suffix.${normalizeExtension(extension)}"
    }

    /** Makes a generated basename safe on Windows, macOS and Linux. */
    fun sanitizeFileName(name: String): String {
        var sanitized = buildString(name.length) {
            for (character in name) {
                when {
                    character.code < 0x20 -> append('_')
                    character in "<>:\"/\\|?*" -> append('_')
                    else -> append(character)
                }
            }
        }.trimEnd(' ', '.')

        if (sanitized.isEmpty()) return "Unknown"
        if (RESERVED_NAME.matches(sanitized)) sanitized = "_$sanitized"

        sanitized = truncateUtf8(sanitized, MAX_BASE_NAME_BYTES).trimEnd(' ', '.')
        return sanitized.ifEmpty { "Unknown" }
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        var byteCount = 0
        var index = 0
        val result = StringBuilder(value.length)
        while (index < value.length) {
            val codePointLength = if (
                value[index].isHighSurrogate() &&
                index + 1 < value.length &&
                value[index + 1].isLowSurrogate()
            ) 2 else 1
            val codePoint = value.substring(index, index + codePointLength)
            val codePointBytes = codePoint.encodeToByteArray().size
            if (byteCount + codePointBytes > maxBytes) break
            result.append(codePoint)
            byteCount += codePointBytes
            index += codePointLength
        }
        return result.toString()
    }

    fun removeLastExtension(name: String): String {
        val lastDot = name.lastIndexOf('.')
        return if (lastDot > 0) name.substring(0, lastDot) else name
    }
}
