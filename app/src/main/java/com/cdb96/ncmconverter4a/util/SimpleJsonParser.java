package com.cdb96.ncmconverter4a.util;

import java.util.ArrayList;

public class SimpleJsonParser {
    //这里写的太烂了,但是我也懒得改了
    public static ArrayList<String> parse(String metaData) {
        ArrayList<String> musicInfo = new ArrayList<>();
        for (int i = 0,j; i < metaData.length() - 1; i++) {
            if (metaData.charAt(i) == '\"') {
                j = i + 1;
                while (j < metaData.length() - 1 && metaData.charAt(j) != '\"') {
                    j++;
                }
                musicInfo.add(metaData.substring(i + 1, j));
                i = j + 2;
                j = i;
                if (metaData.charAt(i) == '[' || metaData.charAt(i) == '{') {
                    int leftBracketCount = 1;
                    int rightBracketCount = 0;
                    j++;
                    while (j < metaData.length() - 1 && leftBracketCount != rightBracketCount) {
                        if ( metaData.charAt(j) == '[' || metaData.charAt(j) == '{') {
                            leftBracketCount++;
                        } else if (metaData.charAt(j) == ']' || metaData.charAt(j) == '}') {
                            rightBracketCount++;
                        }
                        j++;
                    }
                    musicInfo.add(metaData.substring(i,j));
                } else {
                    while (j < metaData.length() - 1 && metaData.charAt(j) != ',') {
                        j++;
                    }
                    if (metaData.charAt(i) == '\"'){
                        musicInfo.add(metaData.substring(i + 1,j - 1));
                    } else {
                        musicInfo.add(metaData.substring(i,j));
                    }
                }
                i = j;
            }
        }
        return musicInfo;
    }
}
