// Standalone self-test for the shared native core.
//
// It needs no JVM, so it can run anywhere a C++ compiler exists:
//   cmake -S native -B native/build -DNCM_BUILD_JNI=OFF
//   cmake --build native/build
//   ./native/build/ncm_core_selftest
//
// The KGM reference below is a straight copy of the pre-existing NEON/scalar
// implementation, so a pass means the rewritten core stayed byte-identical.
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "NativeApi.h"
#include "KgmTables.h"

namespace {

int failures = 0;

void report(const std::string& label, bool ok, const std::string& detail = "") {
    if (ok) {
        std::printf("[ok] %s\n", label.c_str());
    } else {
        failures++;
        std::printf("[FAIL] %s %s\n", label.c_str(), detail.c_str());
    }
}

std::vector<std::uint8_t> makeData(int length, int seed) {
    std::vector<std::uint8_t> data(static_cast<std::size_t>(length));
    for (int i = 0; i < length; i++) {
        data[static_cast<std::size_t>(i)] = static_cast<std::uint8_t>((i * seed + length) & 0xFF);
    }
    return data;
}

// ---------------------------------------------------------------- RC4 checks

void referenceRc4(const std::vector<std::uint8_t>& key, std::vector<std::uint8_t>& data) {
    std::uint8_t sBox[256];
    for (int i = 0; i < 256; i++) sBox[i] = static_cast<std::uint8_t>(i);

    int j = 0;
    const int keyLength = static_cast<int>(key.size());
    for (int i = 0; i < 256; i++) {
        j = (j + sBox[i] + key[static_cast<std::size_t>(i % keyLength)]) & 0xFF;
        const std::uint8_t temp = sBox[i];
        sBox[i] = sBox[j];
        sBox[j] = temp;
    }

    std::uint8_t stream[256];
    for (int k = 1; k < 256; k++) {
        stream[k - 1] = sBox[(sBox[k] + sBox[(sBox[k] + k) & 0xFF]) & 0xFF];
    }
    stream[255] = sBox[(sBox[0] + sBox[(sBox[0] + 0) & 0xFF]) & 0xFF];

    for (std::size_t i = 0; i < data.size(); i++) {
        data[i] ^= stream[i & 0xFF];
    }
}

void checkRc4GoldenVector() {
    const std::uint8_t key[] = {1, 2, 3, 4, 5, 6, 7};
    const std::uint8_t expected[] = {
        0x6D, 0x84, 0xE4, 0x70, 0x92, 0x1D, 0xEE, 0x82, 0xA1, 0x5E, 0x4D, 0x6C,
        0xB4, 0xD0, 0x0E, 0x5A, 0x0B, 0x00, 0xC2, 0xC0, 0xF1, 0xFE, 0x2D, 0xE9,
        0xB4, 0x98, 0xA1, 0x8F, 0x87, 0x42, 0x9F, 0x82};

    std::uint8_t data[32];
    for (int i = 0; i < 32; i++) data[i] = static_cast<std::uint8_t>(i);

    ncm_rc4_init(key, 7);
    ncm_rc4_decrypt(data, 32);

    report("rc4 golden vector", std::memcmp(data, expected, 32) == 0);
}

void checkRc4AgainstReference() {
    const int keyLengths[] = {1, 7, 16, 17, 32, 255, 256, 257};
    bool ok = true;
    for (int keyLength : keyLengths) {
        std::vector<std::uint8_t> key(static_cast<std::size_t>(keyLength));
        for (int i = 0; i < keyLength; i++) {
            key[static_cast<std::size_t>(i)] = static_cast<std::uint8_t>(i * 31 + keyLength);
        }
        std::vector<std::uint8_t> input = makeData(1024, 7);

        std::vector<std::uint8_t> expected = input;
        referenceRc4(key, expected);

        std::vector<std::uint8_t> actual = input;
        ncm_rc4_init(key.data(), keyLength);
        ncm_rc4_decrypt(actual.data(), static_cast<int>(actual.size()));

        if (expected != actual) {
            ok = false;
            std::printf("       mismatch for key length %d\n", keyLength);
        }
    }
    report("rc4 matches reference for key lengths 1..257", ok);
}

void checkRc4Chunking() {
    std::vector<std::uint8_t> key(17);
    for (int i = 0; i < 17; i++) key[static_cast<std::size_t>(i)] = static_cast<std::uint8_t>(i * 13 + 5);

    std::vector<std::uint8_t> input = makeData(4096 + 137, 11);
    std::vector<std::uint8_t> expected = input;
    referenceRc4(key, expected);

    std::vector<std::uint8_t> chunked = input;
    ncm_rc4_init(key.data(), 17);
    int offset = 0;
    while (offset < static_cast<int>(chunked.size())) {
        const int length = std::min(256, static_cast<int>(chunked.size()) - offset);
        ncm_rc4_decrypt(chunked.data() + offset, length);
        offset += length;
    }
    // The last chunk is shorter than 256 bytes, so the cycle stays aligned.
    report("rc4 256-byte chunking (documented contract)", expected == chunked);
}

/**
 * The SIMD keystream XOR consumes a whole 256-byte cycle per iteration and hands
 * the remainder to the scalar tail. These lengths sit on either side of that
 * boundary, so a wrong loop condition or a bad tail start would show up here.
 */
void checkRc4SimdBoundaries() {
    const int lengths[] = {
        1, 15, 16, 17, 255, 256, 257, 511, 512, 513, 4096 + 137
    };

    std::vector<std::uint8_t> key(17);
    for (int i = 0; i < 17; i++) {
        key[static_cast<std::size_t>(i)] = static_cast<std::uint8_t>(i * 13 + 7);
    }

    bool ok = true;

    for (int length : lengths) {
        const std::vector<std::uint8_t> input = makeData(length, 19);

        std::vector<std::uint8_t> expected = input;
        referenceRc4(key, expected);

        std::vector<std::uint8_t> actual = input;
        ncm_rc4_init(key.data(), static_cast<int>(key.size()));
        ncm_rc4_decrypt(actual.data(), length);

        if (actual != expected) {
            ok = false;
            std::printf("       RC4 SIMD boundary mismatch length=%d\n", length);
        }
    }

    report("rc4 SIMD 256-byte cycle boundaries", ok);
}

// ---------------------------------------------------------------- KGM checks

/** Byte-level copy of the pre-existing KGM implementation. */
class ReferenceKgm {
public:
    explicit ReferenceKgm(const std::uint8_t* key) {
        std::memcpy(fileKeyBytes_, key, 17);
        for (int i = 1; i < 16; i++) {
            std::memcpy(fileKeyBytes_ + i * 17, fileKeyBytes_, 17);
        }
        std::memcpy(maskBytes_, ncm::PRE_COMPUTED_TABLE, 4352);
        for (int i = 0; i < 272; i++) {
            fileKeyBytes_[i] ^= ncm::MASK_V2_PRE_DEF[i];
        }
    }

    void decrypt(std::uint8_t* data, int offset, int length) {
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
                if (++maskBytesIndexCounter == 4352) maskBytesIndexCounter = 0;
            }

            const int combined =
                (fileKeyBytes_[fileKeyCounter] ^ data[j] ^ maskBytes_[maskBytesIndexCounter]) & 0xFF;
            data[j] = static_cast<std::uint8_t>(combined ^ (combined << 4));

            genMaskCounter++;
            fileKeyCounter++;
            i++;
            j++;
        }
    }

private:
    void genMask(int startPos) {
        std::uint8_t chunk[16][16];
        for (int pos = 0; pos < 4352 * 16; pos += 16 * 16 * 16) {
            int i = startPos + pos;
            i >>= 4;
            const int chunkPreTablePos = i % 4352;
            for (int k = 0; k < 16; k++) {
                std::memcpy(chunk[k], ncm::PRE_COMPUTED_TABLE + chunkPreTablePos + k * 16, 16);
            }
            i >>= 8;
            do {
                const std::uint8_t xorByte = ncm::PRE_COMPUTED_TABLE[i % 4352];
                for (auto& row : chunk) {
                    for (std::uint8_t& value : row) value ^= xorByte;
                }
                i >>= 8;
            } while (i >= 0x11);

            const int storePos = pos >> 4;
            for (int k = 0; k < 16; k++) {
                std::memcpy(maskBytes_ + storePos + k * 16, chunk[k], 16);
            }
        }
    }

    std::uint8_t maskBytes_[4352]{};
    std::uint8_t fileKeyBytes_[16 * 17]{};
};

void checkKgmAgainstReference() {
    const int keySeeds[] = {0, 1, 200, 255};
    const int lengths[] = {1, 15, 16, 17, 255, 256, 257, 272, 273, 4096, 69632 + 33, 139264 + 7};
    bool ok = true;

    for (int keySeed : keySeeds) {
        std::uint8_t key[17];
        for (int i = 0; i < 17; i++) key[i] = static_cast<std::uint8_t>(keySeed + i * 13);

        for (int length : lengths) {
            std::vector<std::uint8_t> input = makeData(length, 29);

            std::vector<std::uint8_t> actual = input;
            ncm_kgm_init(key, 17);
            ncm_kgm_decrypt(actual.data(), 0, length);

            std::vector<std::uint8_t> expected = input;
            ReferenceKgm reference(key);
            reference.decrypt(expected.data(), 0, length);

            if (expected != actual) {
                ok = false;
                std::printf("       mismatch keySeed=%d length=%d\n", keySeed, length);
            }
        }
    }
    report("kgm matches reference for all lengths", ok);
}

void checkKgmChunking() {
    std::uint8_t key[17];
    for (int i = 0; i < 17; i++) key[i] = static_cast<std::uint8_t>(i * 53 + 7);

    const int total = 69632 * 2 + 4096 + 37;
    const std::vector<std::uint8_t> input = makeData(total, 17);

    // Chunk sizes used by the converters: 4096, 8192 and 16-aligned buffers.
    const int sizes[] = {256, 272, 4096, 16, 65536};
    const int sizeCount = static_cast<int>(sizeof(sizes) / sizeof(sizes[0]));

    std::vector<std::uint8_t> chunked = input;
    ncm_kgm_init(key, 17);
    int offset = 0;
    int index = 0;
    while (offset < static_cast<int>(chunked.size())) {
        int length = std::min(sizes[index % sizeCount], static_cast<int>(chunked.size()) - offset);
        length -= length % 16;  // keep chunk boundaries 16-byte aligned
        if (length == 0) length = std::min(16, static_cast<int>(chunked.size()) - offset);
        offset = ncm_kgm_decrypt(chunked.data() + offset, offset, length);
        index++;
    }

    // Same chunking through the reference for an apples-to-apples comparison.
    std::vector<std::uint8_t> expected = input;
    ReferenceKgm reference(key);
    int refOffset = 0;
    index = 0;
    while (refOffset < static_cast<int>(expected.size())) {
        int length = std::min(sizes[index % sizeCount], static_cast<int>(expected.size()) - refOffset);
        length -= length % 16;
        if (length == 0) length = std::min(16, static_cast<int>(expected.size()) - refOffset);
        reference.decrypt(expected.data() + refOffset, refOffset, length);
        refOffset += length;
        index++;
    }

    report("kgm 16-aligned chunking matches reference", expected == chunked);
}

/**
 * The historical SIMD loop required a 16-byte aligned stream position. The public
 * C ABI accepts any offset, so these cases walk across the 16-byte, 272-byte and
 * 69632-byte period boundaries with a deliberately unaligned start.
 */
void checkKgmUnalignedOffsets() {
    const int offsets[] = {
        1, 2, 15, 16, 17, 255, 271, 272, 273, 69631, 69632, 69633
    };
    const int lengths[] = {
        1, 15, 16, 17, 31, 32, 33, 255, 256, 257, 4096
    };

    std::uint8_t key[17];
    for (int i = 0; i < 17; i++) {
        key[i] = static_cast<std::uint8_t>(i * 29 + 3);
    }

    bool ok = true;

    for (int offset : offsets) {
        for (int length : lengths) {
            const std::vector<std::uint8_t> input = makeData(length, 37);

            std::vector<std::uint8_t> expected = input;
            ReferenceKgm reference(key);
            reference.decrypt(expected.data(), offset, length);

            std::vector<std::uint8_t> actual = input;
            ncm_kgm_init(key, 17);
            const int next = ncm_kgm_decrypt(actual.data(), offset, length);

            if (next != offset + length || actual != expected) {
                ok = false;
                std::printf("       KGM offset mismatch offset=%d length=%d\n", offset, length);
            }
        }
    }

    report("kgm matches reference for unaligned offsets", ok);
}

/**
 * Stronger cross-call check: the counters are re-derived from the absolute offset
 * on every call, so a chunked walk with unaligned chunk sizes must equal the same
 * walk through the reference. This is what exercises the scalar prefix meeting
 * the SIMD loop over and over within one 69632-byte period.
 */
void checkKgmUnalignedChunking() {
    std::uint8_t key[17];
    for (int i = 0; i < 17; i++) {
        key[i] = static_cast<std::uint8_t>(i * 71 + 19);
    }

    const int total = 69632 * 2 + 4096 + 37;
    const std::vector<std::uint8_t> input = makeData(total, 43);

    // Deliberately not multiples of 16, so every call starts unaligned and the
    // cumulative offset walks through all 16 possible alignments.
    const int sizes[] = {4093, 4099, 17, 1, 15, 16, 8191, 65537};
    const int sizeCount = static_cast<int>(sizeof(sizes) / sizeof(sizes[0]));

    std::vector<std::uint8_t> actual = input;
    ncm_kgm_init(key, 17);
    int position = 0;
    int index = 0;
    while (position < total) {
        const int length = std::min(sizes[index % sizeCount], total - position);
        position = ncm_kgm_decrypt(actual.data() + position, position, length);
        index++;
    }

    std::vector<std::uint8_t> expected = input;
    ReferenceKgm reference(key);
    int refPosition = 0;
    index = 0;
    while (refPosition < total) {
        const int length = std::min(sizes[index % sizeCount], total - refPosition);
        reference.decrypt(expected.data() + refPosition, refPosition, length);
        refPosition += length;
        index++;
    }

    report("kgm unaligned chunking matches reference",
           position == total && refPosition == total && expected == actual);
}

}  // namespace

int main() {
    checkRc4GoldenVector();
    checkRc4AgainstReference();
    checkRc4Chunking();
    checkRc4SimdBoundaries();
    checkKgmAgainstReference();
    checkKgmChunking();
    checkKgmUnalignedOffsets();
    checkKgmUnalignedChunking();

    if (failures == 0) {
        std::printf("ALL CHECKS PASSED\n");
        return 0;
    }
    std::printf("%d CHECK(S) FAILED\n", failures);
    return 1;
}
