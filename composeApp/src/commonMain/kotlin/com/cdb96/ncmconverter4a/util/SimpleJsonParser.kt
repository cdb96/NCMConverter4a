package com.cdb96.ncmconverter4a.util

object SimpleJsonParser {
    fun parse(metaData: String): ArrayList<String> {
        val musicInfo = ArrayList<String>()
        var i = 0
        while (i < metaData.length - 1) {
            if (metaData[i] == '\"') {
                var j = i + 1
                while (j < metaData.length - 1 && metaData[j] != '\"') {
                    j++
                }
                musicInfo.add(metaData.substring(i + 1, j))
                i = j + 2
                j = i
                if (metaData[i] == '[' || metaData[i] == '{') {
                    var leftBracketCount = 1
                    var rightBracketCount = 0
                    j++
                    while (j < metaData.length - 1 && leftBracketCount != rightBracketCount) {
                        if (metaData[j] == '[' || metaData[j] == '{') {
                            leftBracketCount++
                        } else if (metaData[j] == ']' || metaData[j] == '}') {
                            rightBracketCount++
                        }
                        j++
                    }
                    musicInfo.add(metaData.substring(i, j))
                } else {
                    while (j < metaData.length - 1 && metaData[j] != ',') {
                        j++
                    }
                    if (metaData[i] == '\"') {
                        musicInfo.add(metaData.substring(i + 1, j - 1))
                    } else {
                        musicInfo.add(metaData.substring(i, j))
                    }
                }
                i = j
            }
            i++
        }
        return musicInfo
    }
}
