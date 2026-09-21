package com.cdb96.ncmconverter4a.util

/**
 * Small JSON object scanner for the fixed NCM metadata shape. It deliberately
 * keeps the historical alternating key/value return type, but never indexes
 * past the input when a damaged metadata block is supplied.
 */
object SimpleJsonParser {
    fun parse(metaData: String): ArrayList<String> {
        val values = ArrayList<String>()
        var position = 0

        while (position < metaData.length) {
            if (metaData[position] != '"') {
                position++
                continue
            }

            val keyEnd = findStringEnd(metaData, position)
            if (keyEnd < 0) break
            val key = decodeJsonString(metaData.substring(position + 1, keyEnd))
            position = keyEnd + 1
            while (position < metaData.length && metaData[position].isWhitespace()) position++
            if (position >= metaData.length || metaData[position] != ':') break
            position++
            while (position < metaData.length && metaData[position].isWhitespace()) position++
            if (position >= metaData.length) break

            val valueStart = position
            var valueEnd = position
            when (metaData[position]) {
                '"' -> {
                    valueEnd = findStringEnd(metaData, position)
                    if (valueEnd < 0) break
                }
                '[', '{' -> {
                    valueEnd = findCompositeEnd(metaData, position)
                    if (valueEnd < 0) break
                }
                else -> valueEnd = findScalarEnd(metaData, position)
            }

            val rawValue = metaData.substring(valueStart, valueEnd + 1).trim()
            val value = if (rawValue.length >= 2 && rawValue.first() == '"' && rawValue.last() == '"') {
                decodeJsonString(rawValue.substring(1, rawValue.length - 1))
            } else {
                rawValue
            }
            values += key
            values += value
            position = valueEnd + 1
        }
        return values
    }

    /** Index of the closing quote of the JSON string opening at [start], or -1. */
    internal fun findStringEnd(input: String, start: Int): Int {
        var escaped = false
        for (index in start + 1 until input.length) {
            val character = input[index]
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == '"') {
                return index
            }
        }
        return -1
    }

    private fun findCompositeEnd(input: String, start: Int): Int {
        val stack = ArrayList<Char>()
        var inString = false
        var escaped = false
        for (index in start until input.length) {
            val character = input[index]
            if (inString) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') inString = false
                continue
            }
            when (character) {
                '"' -> inString = true
                '[', '{' -> stack += character
                ']', '}' -> {
                    if (stack.isEmpty()) return -1
                    val expected = if (character == ']') '[' else '{'
                    if (stack.removeAt(stack.lastIndex) != expected) return -1
                    if (stack.isEmpty()) return index
                }
            }
        }
        return -1
    }

    private fun findScalarEnd(input: String, start: Int): Int {
        var index = start
        while (index + 1 < input.length && input[index + 1] != ',') {
            if (input[index + 1] == '}' || input[index + 1] == ']') break
            index++
        }
        return index
    }

    /** Resolves JSON escapes in the body of a quoted string (quotes excluded). */
    internal fun decodeJsonString(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character != '\\' || index + 1 >= value.length) {
                result.append(character)
                index++
                continue
            }
            val escaped = value[index + 1]
            result.append(
                when (escaped) {
                    '"' -> '"'
                    '\\' -> '\\'
                    '/' -> '/'
                    'b' -> '\b'
                    'f' -> '\u000C'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        if (index + 5 < value.length) {
                            val hex = value.substring(index + 2, index + 6)
                            hex.toIntOrNull(16)?.toChar() ?: escaped
                        } else {
                            escaped
                        }
                    }
                    else -> escaped
                }
            )
            index += if (escaped == 'u' && index + 5 < value.length &&
                value.substring(index + 2, index + 6).toIntOrNull(16) != null
            ) 6 else 2
        }
        return result.toString()
    }
}
