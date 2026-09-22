package com.cdb96.ncmconverter4a.util

object SimpleJsonParser {
    fun parse(metaData: String): ArrayList<String> {
        val musicInfo = ArrayList<String>()
        var i = 0
        while (i < metaData.length) {
            if (metaData[i] != '"') {
                i++
                continue
            }
            val keyEnd = metaData.indexOf('"', i + 1)
            if (keyEnd < 0) break
            val key = metaData.substring(i + 1, keyEnd)
            i = keyEnd + 1
            while (i < metaData.length && metaData[i].isWhitespace()) i++
            if (i >= metaData.length || metaData[i] != ':') break
            i++

            val valueStart = i
            var depth = 0
            var inString = false
            while (i < metaData.length) {
                val char = metaData[i]
                if (inString && char == '\\') {
                    // 跳过转义字符，保留原文，不做 JSON 转义解码。
                    i += minOf(2, metaData.length - i)
                    continue
                }
                if (char == '"') {
                    inString = !inString
                } else if (!inString) {
                    if (depth == 0 && (char == ',' || char == '}')) break
                    when (char) {
                        '[', '{' -> depth++
                        ']', '}' -> depth--
                    }
                }
                i++
            }
            if (inString || depth != 0) break
            val value = metaData.substring(valueStart, i).trim()
            if (value.isEmpty()) break
            musicInfo.add(key)
            musicInfo.add(value.removeSurrounding("\""))
        }
        return musicInfo
    }
}
