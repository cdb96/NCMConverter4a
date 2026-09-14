import com.cdb96.ncmconverter4a.jni.KGMDecrypt;
import com.cdb96.ncmconverter4a.jni.RC4Decrypt;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Standalone JVM check for the unified native core. Mirrors the desktop test
 * suite (golden vector + concurrency + chunk boundaries) and adds a byte level
 * KGM reference so it can run without Gradle.
 */
public final class NativeCoreCheck {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.loadLibrary("ncmc4a");

        rc4GoldenVector();
        rc4MultiLengthKeys();
        rc4ChunkBoundaries();
        kgmMatchesReference();
        kgmChunkBoundaries();
        concurrentKeysDoNotOverwriteEachOther();

        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- RC4

    private static void rc4GoldenVector() {
        byte[] key = {1, 2, 3, 4, 5, 6, 7};
        byte[] actual = new byte[32];
        for (int i = 0; i < actual.length; i++) actual[i] = (byte) i;
        byte[] expected = {
            (byte) 0x6D, (byte) 0x84, (byte) 0xE4, (byte) 0x70, (byte) 0x92, (byte) 0x1D,
            (byte) 0xEE, (byte) 0x82, (byte) 0xA1, (byte) 0x5E, (byte) 0x4D, (byte) 0x6C,
            (byte) 0xB4, (byte) 0xD0, (byte) 0x0E, (byte) 0x5A, (byte) 0x0B, (byte) 0x00,
            (byte) 0xC2, (byte) 0xC0, (byte) 0xF1, (byte) 0xFE, (byte) 0x2D, (byte) 0xE9,
            (byte) 0xB4, (byte) 0x98, (byte) 0xA1, (byte) 0x8F, (byte) 0x87, (byte) 0x42,
            (byte) 0x9F, (byte) 0x82
        };
        RC4Decrypt.ksa(key);
        RC4Decrypt.prgaDecryptByteArray(actual, actual.length);
        check("rc4 golden vector", expected, actual);
    }

    private static void rc4MultiLengthKeys() {
        int[] keyLengths = {1, 7, 16, 17, 32, 255, 256, 257};
        for (int keyLength : keyLengths) {
            byte[] key = new byte[keyLength];
            for (int i = 0; i < keyLength; i++) key[i] = (byte) (i * 31 + keyLength);
            byte[] input = new byte[1024];
            for (int i = 0; i < input.length; i++) input[i] = (byte) (i * 7 + 1);

            byte[] expected = input.clone();
            referenceRc4(key, expected);
            byte[] actual = input.clone();
            RC4Decrypt.ksa(key);
            RC4Decrypt.prgaDecryptByteArray(actual, actual.length);
            check("rc4 key length " + keyLength, expected, actual);
        }
    }

    private static void rc4ChunkBoundaries() {
        byte[] key = new byte[17];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 13 + 5);
        byte[] input = new byte[4096 + 137];
        for (int i = 0; i < input.length; i++) input[i] = (byte) (i * 11 + 3);

        byte[] expected = input.clone();
        referenceRc4(key, expected);

        // 256-byte aligned chunks, last chunk short: the documented contract.
        byte[] chunked = input.clone();
        RC4Decrypt.ksa(key);
        int offset = 0;
        while (offset < chunked.length) {
            int length = Math.min(256, chunked.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(chunked, offset, chunk, 0, length);
            RC4Decrypt.prgaDecryptByteArray(chunk, length);
            System.arraycopy(chunk, 0, chunked, offset, length);
            offset += length;
        }
        check("rc4 256-byte chunks", expected, chunked);
    }

    private static void referenceRc4(byte[] key, byte[] data) {
        byte[] sBox = new byte[256];
        for (int i = 0; i < 256; i++) sBox[i] = (byte) i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + sBox[i] + key[i % key.length]) & 0xFF;
            byte temp = sBox[i];
            sBox[i] = sBox[j];
            sBox[j] = temp;
        }
        byte[] stream = new byte[256];
        for (int k = 1; k < 256; k++) {
            stream[k - 1] = sBox[(sBox[k] + sBox[(sBox[k] + k) & 0xFF]) & 0xFF];
        }
        stream[255] = sBox[(sBox[0] + sBox[(sBox[0] + 0) & 0xFF]) & 0xFF];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (data[i] ^ stream[i & 0xFF]);
        }
    }

    // ---------------------------------------------------------------- KGM

    private static void kgmMatchesReference() {
        int[] keySeeds = {0, 1, 200, 255};
        int[] lengths = {1, 15, 16, 17, 255, 256, 257, 272, 273, 4096, 69632 + 33, 139264 + 7};

        for (int keySeed : keySeeds) {
            byte[] key = new byte[17];
            for (int i = 0; i < 17; i++) key[i] = (byte) (keySeed + i * 13);
            for (int length : lengths) {
                byte[] input = new byte[length];
                for (int i = 0; i < length; i++) input[i] = (byte) (i * 29 + length);

                KGMDecrypt.init(key);
                byte[] actual = input.clone();
                KGMDecrypt.decrypt(actual, 0, actual.length);

                byte[] expected = input.clone();
                new ReferenceKgm(key).decrypt(expected, 0, expected.length);

                check("kgm reference key0=" + key[0] + " length=" + length, expected, actual);
            }
        }
    }

    private static void kgmChunkBoundaries() {
        byte[] key = new byte[17];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 53 + 7);
        int total = 69632 * 2 + 4096 + 37;

        byte[] input = new byte[total];
        for (int i = 0; i < total; i++) input[i] = (byte) (i * 17 + 5);

        // 16-byte aligned chunk sizes: the contract used by the callers.
        int[][] alignedSizes = {{256}, {272}, {4096}, {256, 4096, 272, 16, 65536}, {69632}};
        for (int[] sizes : alignedSizes) {
            byte[] chunked = input.clone();
            KGMDecrypt.init(key);
            int offset = 0;
            int index = 0;
            while (offset < chunked.length) {
                int length = Math.min(sizes[index % sizes.length], chunked.length - offset);
                length -= length % 16;                      // keep the chunk boundary aligned
                if (length == 0) length = Math.min(16, chunked.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(chunked, offset, chunk, 0, length);
                offset = KGMDecrypt.decrypt(chunk, offset, length);
                System.arraycopy(chunk, 0, chunked, offset - length, length);
                index++;
            }

            // Reference with the identical chunking.
            byte[] expected = input.clone();
            ReferenceKgm reference = new ReferenceKgm(key);
            int refOffset = 0;
            index = 0;
            while (refOffset < expected.length) {
                int length = Math.min(sizes[index % sizes.length], expected.length - refOffset);
                length -= length % 16;
                if (length == 0) length = Math.min(16, expected.length - refOffset);
                byte[] chunk = new byte[length];
                System.arraycopy(expected, refOffset, chunk, 0, length);
                reference.decrypt(chunk, refOffset, length);
                System.arraycopy(chunk, 0, expected, refOffset, length);
                refOffset += length;
                index++;
            }

            if (offset != chunked.length) {
                fail("kgm chunked offset mismatch: " + offset + " vs " + chunked.length);
            }
            check("kgm chunked (16-aligned) " + java.util.Arrays.toString(sizes), expected, chunked);
        }

        // Known pre-existing quirk, intentionally preserved: the cursor arithmetic
        // uses the chunk-local index, so a chunk shorter than 16 bytes decrypts
        // differently from the same bytes read with a larger chunk. Every caller
        // uses >=4096 byte chunks, so this only documents the boundary.
        System.out.println("[info] sub-16-byte chunks are chunk-boundary sensitive (pre-existing, preserved)");
    }

    private static void concurrentKeysDoNotOverwriteEachOther() throws Exception {
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                final int id = worker;
                jobs.add(() -> {
                    byte[] rc4Key = new byte[17];
                    for (int i = 0; i < rc4Key.length; i++) rc4Key[i] = (byte) ((id + 1) * 13 + i * 7);
                    byte[] input = new byte[513];
                    for (int i = 0; i < input.length; i++) input[i] = (byte) ((id * 19 + i * 3) & 0xFF);
                    byte[] expectedRc4 = input.clone();
                    referenceRc4(rc4Key, expectedRc4);

                    byte[] kgmKey = new byte[17];
                    for (int i = 0; i < kgmKey.length; i++) kgmKey[i] = (byte) (id * 41 + i);
                    byte[] kgmInput = new byte[8192 + 19];
                    for (int i = 0; i < kgmInput.length; i++) kgmInput[i] = (byte) (i * 23 + id);
                    byte[] expectedKgm = kgmInput.clone();
                    new ReferenceKgm(kgmKey).decrypt(expectedKgm, 0, expectedKgm.length);

                    for (int round = 0; round < 100; round++) {
                        byte[] actualRc4 = input.clone();
                        RC4Decrypt.ksa(rc4Key);
                        RC4Decrypt.prgaDecryptByteArray(actualRc4, actualRc4.length);
                        if (!java.util.Arrays.equals(expectedRc4, actualRc4)) {
                            fail("rc4 concurrency worker " + id + " round " + round);
                            return null;
                        }

                        byte[] actualKgm = kgmInput.clone();
                        KGMDecrypt.init(kgmKey);
                        KGMDecrypt.decrypt(actualKgm, 0, actualKgm.length);
                        if (!java.util.Arrays.equals(expectedKgm, actualKgm)) {
                            fail("kgm concurrency worker " + id + " round " + round);
                            return null;
                        }
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = executor.invokeAll(jobs);
            for (Future<Void> future : futures) future.get();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        System.out.println("[ok] concurrent thread-local state");
    }

    /** Byte level port of the pre-existing C++ NEON implementation. */
    static final class ReferenceKgm {
        private final byte[] maskBytes = new byte[4352];
        private final byte[] fileKeyBytes = new byte[16 * 17];

        ReferenceKgm(byte[] key) {
            for (int i = 0; i < 17; i++) fileKeyBytes[i] = key[i];
            for (int i = 1; i < 16; i++) {
                System.arraycopy(fileKeyBytes, 0, fileKeyBytes, i * 17, 17);
            }
            System.arraycopy(TABLES, 0, maskBytes, 0, 4352);
            for (int i = 0; i < 272; i++) {
                fileKeyBytes[i] = (byte) (fileKeyBytes[i] ^ TABLES[4352 + i]);
            }
        }

        void decrypt(byte[] data, int offset, int length) {
            int i = offset;
            int j = 0;
            int genMaskCounter = offset % 69632;
            int fileKeyCounter = offset % 272;
            int maskBytesIndexCounter = (offset >> 4) % 4352;
            if (genMaskCounter == 0) genMask(i);
            while (j < length) {
                if (fileKeyCounter == 272) fileKeyCounter = 0;
                if (genMaskCounter == 69632) {
                    genMask(i);
                    genMaskCounter = 0;
                }
                if (j > 0 && (i & 15) == 0) {
                    maskBytesIndexCounter++;
                    if (maskBytesIndexCounter == 4352) maskBytesIndexCounter = 0;
                }
                int combined = (fileKeyBytes[fileKeyCounter] ^ data[j] ^ maskBytes[maskBytesIndexCounter]) & 0xFF;
                data[j] = (byte) (combined ^ (combined << 4));
                genMaskCounter++;
                fileKeyCounter++;
                i++;
                j++;
            }
        }

        private void genMask(int startPos) {
            for (int pos = 0; pos < 4352 * 16; pos += 16 * 16 * 16) {
                int i = startPos + pos;
                i >>= 4;
                int chunkPreTablePos = i % 4352;
                byte[][] chunk = new byte[16][16];
                for (int k = 0; k < 16; k++) {
                    System.arraycopy(TABLES, chunkPreTablePos + k * 16, chunk[k], 0, 16);
                }
                i >>= 8;
                do {
                    byte xorData = TABLES[i % 4352];
                    for (int k = 0; k < 16; k++) {
                        for (int b = 0; b < 16; b++) chunk[k][b] = (byte) (chunk[k][b] ^ xorData);
                    }
                    i >>= 8;
                } while (i >= 0x11);
                int storePos = pos >> 4;
                for (int k = 0; k < 16; k++) {
                    System.arraycopy(chunk[k], 0, maskBytes, storePos + k * 16, 16);
                }
            }
        }

        private static final byte[] TABLES = loadTables();
    }


    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] loadTables() {
        // Raw bytes generated from native/KgmTables.h; keeping the reference
        // self-contained means the check does not depend on the native library.
        return java.util.Base64.getDecoder().decode(
            "AAAAAAAAAAAAAAAAAAAAAAABIQFhASEB4QEhAWEBIQHSIwICQkICAsLCAgJCQgIC09MCA2NDYwPjw+MDY0NjA5S0lGUEBAQEhISE"
            + "hAQEBASVlZWVBAUlBeWFpYXlBSUF1raWttYnBgbGxoaGxsYGBtfXl5fX1wYH58fnh+fH5wcYOBh4GDgY6QgICAgICAgIGRkZGRkZ"
            + "GRkICSkJaQkpCdo6GjpaOho62isKCkpKCgrb2xsbW1sbG9vbCgtrS2sLnLycfBw8HHycvJxtDAwMDJ2dnZ0dHR0dnZ2dnQwNLQ3e"
            + "vp6+3j4ePt6+nr7eLw4O39+fn9/fHx/f35+f398ODwAgAGAAIADgACAAYAAgAPEBAQEBAQEBAQEBAQEBAQEBIyICIkIiAiLCIgIi"
            + "QiICItMiAwNDQwMDw8MDA0NDAwO0tGVkBCQEZISkhGQEJARklbWVZAUFBQWFhYWFBQUFBba2trYnJgYmxqaGpsYmBibXt5e31yYH"
            + "B8fHh4fHxwcHODh4eDg46egIKAhoCCgI6Bk5GXkZORnoCQkJCQkJCQk6Ojo6Ojo6OisqCipKKgoq2zsbO1s7GzvbKgsLS0sLC7y8"
            + "fHw8PHx8vLxtbAwsDGydvZ19HT0dfZ29nWwNDQ0Nvr6+vj4+Pj6+vr6+Ly4OLt+/n7/fPx8/37+fv98uDw8AAEBAAADAwAAAQEAA"
            + "ANHQIQEhQSEBIcEhASFBIQEh0AICAgICAgICAgICAgICAgICAyMDYwMjA+MDIwNjAyMDlGVERAQERESEhEREBARERJWVREUlBSVF"
            + "pYWlRSUFJUWWtpZnBgYGBoaGhoYGBgYGl5eXlwYHJwfnh6eH5wcnBxh4WHgY6cjICAhISAgIyMgZGVlZGRnIySkJKUkpCSnJGjoa"
            + "eho6GusKCgoKCgoKChsbGxsbGxsbCgsrC2sLKwucfFx8HHxcfJxtTEwMDExMnZ1dXR0dXV2dnUxNLQ0tTZ6+nn4ePh5+nr6ebw4O"
            + "Dg6fn5+fHx8fH5+fn58ODy8PAGBAYADgwOAAYEBgAPHQ0AEBQUEBAcHBAQFBQQEB0NECIgJiAiIC4gIiAmICIgLzAwMDAwMDAwMD"
            + "AwMDAwMDA2VkRGQEZERkhGREZARkRGSVZEVFBQVFRYWFRUUFBUVFtrZnZgYmBmaGpoZmBiYGZpe3l2YHBwcHh4eHhwcHBwd4eHh4"
            + "6ejI6AhoSGgI6MjoGXlZeRnoyckJCUlJCQnJyTo6eno6OuvqCioKagoqCuobOxt7Gzsb6gsLCwsLCwsLfHx8fHx8fHxtbExsDGxM"
            + "bJ19XX0dfV19nWxNTQ0NTU2+vn5+Pj5+fr6+b24OLg5un7+ffx8/H3+fv59uDw8PDwAAAACAgICAAAAAAJGQsJBhASEBYYGhgWEB"
            + "IQFhkLGxQkICAkJCgoJCQgICQkKTkmNDYwNjQ2ODY0NjA2NDY5IEBAQEBAQEBAQEBAQEBAQEBAUlBWUFJQXlBSUFZQUlBdYnBgZG"
            + "RgYGxsYGBkZGBgbX1wYHZ0dnB+fH5wdnR2cHGDgY6YiIiIgICAgIiIiIiBkZGRmIiamJaQkpCWmJqYlaOho6WquKikpKCgpKSoqK"
            + "W1sbG1tbiotrS2sLa0trixw8HHwcPBztDAwMDAwMDAwdHR0dHR0dHQwNLQ1tDS0N3j4ePl4+Hj7eLw4OTk4ODt/fHx9fXx8f398O"
            + "D29Pbw8AIADggKCA4AAgAPGQkJCQAQEBAYGBgYEBAQEBkJGxkUIiAiJCooKiQiICIkKzkpJDQwMDQ0ODg0NDAwNDQ5KTBCQEZAQk"
            + "BOQEJARkBCQE9QUFBQUFBQUFBQUFBQUFBQUnJgYmRiYGJsYmBiZGJgYm1yYHB0dHBwfHxwcHR0cHBzg46eiIqIjoCCgI6IioiOgZ"
            + "ORnoiYmJiQkJCQmJiYmJOjo6OquqiqpKKgoqSqqKqls7GztbqouLS0sLC0tLi4s8PHx8PDzt7AwsDGwMLAzsHT0dfR09HewNDQ0N"
            + "DQ0NDT4+Pj4+Pj4+Ly4OLk4uDi7fPx8/Xz8fP98uDw9PTw8PAADAwICAwMAAANHQsJCw0CEBIcGhgaHBIQEh0LGxsbECAgICgoKC"
            + "ggICAgKTkrKSYwMjA2ODo4NjAyMDY5KzswQEREQEBMTEBARERAQE1dQlBSVFJQUlxSUFJUUlBSXUBgYGBgYGBgYGBgYGBgYGBgYH"
            + "JwdnBycH5wcnB2cHJwcY6cjIiIjIyAgIyMiIiMjIGRnIyamJqckpCSnJqYmpyRo6GuuKioqKCgoKCoqKioobGxsbiouri2sLKwtr"
            + "i6uLHHxcfBztzMwMDExMDAzMzB0dXV0dHczNLQ0tTS0NLc0ePh5+Hj4e7w4ODg4ODg4OHx8fHx8fHx8ODy8Pbw8vDwDgwOCA4MDg"
            + "APHQ0JCQ0NABAcHBgYHBwQEB0NGxkbHRAiIC4oKiguICIgLzkpKSkgMDAwODg4ODAwMDA5KTs5MEZERkBOTE5ARkRGQE9dTUBQVF"
            + "RQUFxcUFBUVFBQXU1QYmBmYGJgbmBiYGZgYmBvcHBwcHBwcHBwcHBwcHBwcH6ejI6IjoyOgI6MjoiOjI6BnoycmJicnJCQnJyYmJ"
            + "yck6OuvqiqqK6goqCuqKqorqGzsb6ouLi4sLCwsLi4uLi3x8fHzt7MzsDGxMbAzszOwdfV19HezNzQ0NTU0NDc3NPj5+fj4+7+4O"
            + "Lg5uDi4O7h8/H38fPx/uDw8PDw8PDw8AAAAAAAAAABEQMBBwEDAQ4QEhAWEBIQHhEDExcXExMcLCAgJCQgICwsITEnJSchLjw+MD"
            + "Y0NjA+PD4xJzc3NzhISEhAQEBASEhISEFRQ0FOWFpYXlBSUF5YWlheUUNTXGxoaGxsYGBsbGhobGxhcW58fnh+fH5wfnx+eH58fn"
            + "FggICAgICAgICAgICAgICAgICSkJaQkpCekJKQlpCSkJ2isKCkpKCgrKygoKSkoKCtvbCgtrS2sL68vrC2tLawucvJxtDAwMDIyM"
            + "jIwMDAwMnZ2dnQwNLQ3tja2N7Q0tDd6+nr7eLw4Ozs6Ojs7ODg7f35+f398OD+/P74/vz+8PACAAYAAgAPEQEBAQEBAQEAEBAQEB"
            + "AQEBEBExEXERMRHCIgIiQiICIsIzEhJSUhISw8MDA0NDAwPDwxITc1NzE4SkhGQEJARkhKSEdRQUFBSFhYWFBQUFBYWFhYUUFTUV"
            + "xqaGpsYmBibGpoamxjcWFsfHh4fHxwcHx8eHh8fHFhcIKAhoCCgI6AgoCGgIKAj5CQkJCQkJCQkJCQkJCQkJCSsqCipKKgoqyioK"
            + "KkoqCirbKgsLS0sLC8vLCwtLSwsLvLxtbAwsDGyMrIxsDCwMbJ29nWwNDQ0NjY2NjQ0NDQ2+vr6+Ly4OLs6ujq7OLg4u37+fv98u"
            + "Dw/Pz4+Pz88PDwAAQEAAANHQMBAwUDAQMNAhASFBIQEh0DExMTExMTExAgICAgICAgITEjISchIyEuMDIwNjAyMD4xIzM3NzMzOE"
            + "hEREBARERISEVVQ0FDRUpYWlRSUFJUWlhaVUNTU1NYaGhoYGBgYGhoaGhhcWNhbnh6eH5wcnB+eHp4fnFjc3CAhISAgIyMgICEhI"
            + "CAjZ2CkJKUkpCSnJKQkpSSkJKdgKCgoKCgoKCgoKCgoKCgoKCgsrC2sLKwvrCysLawsrC5xtTEwMDExMjIxMTAwMTEydnUxNLQ0t"
            + "Ta2NrU0tDS1Nnr6ebw4ODg6Ojo6ODg4ODp+fn58ODy8P74+vj+8PLw8AYEBgAPHQ0BAQUFAQENDQAQFBQQEB0NExETFRMREx0QIi"
            + "AmICIgLzEhISEhISEhIDAwMDAwMDAxITMxNzEzMThGREZARkRGSEdVRUFBRUVIWFRUUFBUVFhYVUVTUVNVWGpoZmBiYGZoamhncW"
            + "FhYWh4eHhwcHBweHh4eHFhc3FwhoSGgI6MjoCGhIaAj52NgJCUlJCQnJyQkJSUkJCdjZCioKagoqCuoKKgpqCioK+wsLCwsLCwsL"
            + "CwsLCwsLCwttbExsDGxMbIxsTGwMbExsnWxNTQ0NTU2NjU1NDQ1NTb6+b24OLg5ujq6Obg4uDm6fv59uDw8PD4+Pj48PDw8PAAAA"
            + "AJGQsJBwEDAQcJCwkGEBIQFhkLGxcXExMXFxsbFCQgICQkKTknJSchJyUnKSY0NjA2NDY5Jzc3Nzc3NzcwQEBAQEBAQEFRQ0FHQU"
            + "NBTlBSUFZQUlBeUUNTV1dTU1xsYGBkZGBgbGxhcWdlZ2FufH5wdnR2cH58fnFnd3d3cICAgIiIiIiAgICAiZmLiYaQkpCWmJqYlp"
            + "CSkJaZi5uUpKCgpKSoqKSkoKCkpKm5prS2sLa0tri2tLawtrS2uaDAwMDAwMDAwMDAwMDAwMDAwNLQ1tDS0N7Q0tDW0NLQ3eLw4O"
            + "Tk4ODs7ODg5OTg4O398OD29Pbw/vz+8Pb09vDwAgAPGQkJCQEBAQEJCQkJABAQEBkJGxkXERMRFxkbGRQiICIkKzkpJSUhISUlKS"
            + "kkNDAwNDQ5KTc1NzE3NTc5MEJARkBCQE9RQUFBQUFBQUBQUFBQUFBQUUFTUVdRU1FcYmBiZGJgYmxjcWFlZWFhbHxwcHR0cHB8fH"
            + "Fhd3V3cXCCgI6IioiOgIKAj5mJiYmAkJCQmJiYmJCQkJCZiZuZlKKgoqSqqKqkoqCipKu5qaS0sLC0tLi4tLSwsLS0uamwwsDGwM"
            + "LAzsDCwMbAwsDP0NDQ0NDQ0NDQ0NDQ0NDQ0NLy4OLk4uDi7OLg4uTi4OLt8uDw9PTw8Pz88PD09PDw8AANHQsJCw0DAQMNCwkLDQ"
            + "IQEh0LGxsbExMTExsbGxsQICAgKTkrKSchIyEnKSspJjAyMDY5Kzs3NzMzNzc7OzBARERAQE1dQ0FDRUNBQ01CUFJUUlBSXUNTU1"
            + "NTU1NTUGBgYGBgYGBhcWNhZ2FjYW5wcnB2cHJwfnFjc3d3c3NwgIyMiIiMjICAjZ2LiYuNgpCSnJqYmpySkJKdi5ubm5CgoKCoqK"
            + "iooKCgoKm5q6mmsLKwtri6uLawsrC2uau7sMDExMDAzMzAwMTEwMDN3cLQ0tTS0NLc0tDS1NLQ0t3A4ODg4ODg4ODg4ODg4ODg4O"
            + "Dy8Pbw8vD+8PLw9vDy8PAPHQ0JCQ0NAQENDQkJDQ0AEB0NGxkbHRMREx0bGRsdECIgLzkpKSkhISEhKSkpKSAwMDA5KTs5NzEzMT"
            + "c5OzkwRkRGQE9dTUFBRUVBQU1NQFBUVFBQXU1TUVNVU1FTXVBiYGZgYmBvcWFhYWFhYWFgcHBwcHBwcHFhc3F3cXNxcI6MjoiOjI"
            + "6Aj52NiYmNjYCQnJyYmJyckJCdjZuZm52QoqCuqKqorqCioK+5qampoLCwsLi4uLiwsLCwuam7ubDGxMbAzszOwMbExsDP3c3A0N"
            + "TU0NDc3NDQ1NTQ0N3N0OLg5uDi4O7g4uDm4OLg7/Dw8PDw8PDw8PDw8PDw8PDwABIQFhASEB4QEhAWEBIQHwASAgYGAgIODgICBg"
            + "YCAg09MCA2NDYwPjw+MDY0NjA/LS8gNiYmJi4uLi4mJiYmKVlZWVBAUlBeWFpYXlBSUF9JS0lPQFJCTk5KSk5OQkJNfXl5fX1wYH"
            + "58fnh+fH5wf21vaW9tb2B+bm5ubm5ubmGRkZGRkZGRkICSkJaQkpCfgYOBh4GDgY+AkoKGhoKCjb2xsbW1sbG9vbCgtrS2sL+tr6"
            + "Gnpaehr62voLampqap2dnZ0dHR0dnZ2dnQwNLQ38nLyc/Bw8HPycvJz8DSws39+fn9/fHx/f35+f398OD/7e/p7+3v4e/t7+nv7e"
            + "/g+41T2y6a94jIMzcVF2oM03Lz41jam+mLfnjCLOWmHfaGmJ/qW23ql3/Mi9veVtPlo272lOvuHpZhzz2QK28hKbRNBvuTWJtkZt"
            + "c4IGacHt14XCMN+iYr55LWJiPQ1+vkiJIwKg5NV1UTICU/0WOiE7Fg/Dsruz4ro6PRPs9gFFhKVwD5NJDGTNMdXMTAcBngAaI5C/"
            + "iB47q6Y+xHNHEH47XrzjAIT/CdTgiQ9bWHBP+2XYXFMb08jGv++YsFBPD+rlg1iMKCyEZ83QnkfbJ1DK9GNj6Jd/G0sMwsEhTMxY"
            + "9ZRSo/PT4Gj0ACPzXgp7k92rErIT6ITXp58PMkxVHQQ2UtwD8/lOQuk9Ye98trOTUA=="
        );
    }

    // ---------------------------------------------------------------- util

    private static void check(String label, byte[] expected, byte[] actual) {
        if (java.util.Arrays.equals(expected, actual)) {
            System.out.println("[ok] " + label);
        } else {
            int at = -1;
            int limit = Math.min(expected.length, actual.length);
            for (int i = 0; i < limit; i++) {
                if (expected[i] != actual[i]) { at = i; break; }
            }
            fail(label + ": mismatch at " + at + " (expected[at]=" + (at < 0 ? "n/a" :
                (expected[at] & 0xFF)) + " actual[at]=" + (at < 0 ? "n/a" : (actual[at] & 0xFF)) + ")");
        }
    }

    private static void fail(String message) {
        failures++;
        System.out.println("[FAIL] " + message);
    }
}
