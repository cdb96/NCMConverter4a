// KGM core shared by the Android and Desktop builds.
//
// This is a direct port of the previous Android NEON implementation; the
// byte-for-byte behaviour (including the mask-table cursor arithmetic) is kept
// on purpose so that both platforms keep producing identical output.
#include "NativeApi.h"
#include "SimdCompat.h"

#include <array>
#include <cstdint>
#include <cstring>

#include "KgmTables.h"

namespace ncm {
namespace {

constexpr int kMaskBlock = 16;
constexpr int kGenMaskPeriod = 69632;       // 17 * 16 * 16 * 16
constexpr int kFileKeyPeriod = 272;         // 17 * 16
constexpr int kMaskBytePeriod = 4352;       // PRE_COMPUTED_TABLE_SIZE

thread_local std::array<std::uint8_t, kMaskBytePeriod> maskBytes{};
thread_local std::array<std::uint8_t, 16 * 17> fileKeyBytes{};

// Expands the pre-computed byte table into 16-byte masks for one 69632 byte
// period starting at absolute stream position `startPos`.
void genMask(int startPos) {
#if NCM_HAS_SIMD
    // Intentionally almost a direct copy of the historical SIMD genMask():
    // 17 iterations, each expanding 256 pre-computed bytes into 16 mask blocks
    // of 16 bytes, then XORing a single byte into all of them.
    uint8x16_t chunk[16];

    for (int pos = 0; pos < kMaskBytePeriod * kMaskBlock; pos += 16 * 16 * 16) {
        int i = startPos + pos;
        i >>= 4;
        const int chunkPreTablePos = i % kMaskBytePeriod;

        for (int k = 0; k < 16; ++k) {
            chunk[k] = vld1q_u8(PRE_COMPUTED_TABLE + chunkPreTablePos + k * 16);
        }

        i >>= 8;
        // Valid once startPos >= 69632; smaller positions are handled by the
        // caller sending a zeroed cursor.
        do {
            const uint8x16_t xorData =
                vld1q_dup_u8(PRE_COMPUTED_TABLE + (i % kMaskBytePeriod));
            for (uint8x16_t& value : chunk) {
                value = veorq_u8(value, xorData);
            }
            i >>= 8;
        } while (i >= 0x11);

        const int storePos = pos >> 4;
        for (int k = 0; k < 16; ++k) {
            vst1q_u8(maskBytes.data() + storePos + k * 16, chunk[k]);
        }
    }
#else
    for (int pos = 0; pos < kMaskBytePeriod * kMaskBlock; pos += 16 * 16 * 16) {
        int i = startPos + pos;
        i >>= 4;
        const int chunkPreTablePos = i % kMaskBytePeriod;

        std::array<std::uint8_t, kMaskBlock * kMaskBlock> chunk{};
        for (int k = 0; k < kMaskBlock; ++k) {
            std::memcpy(chunk.data() + k * kMaskBlock,
                        PRE_COMPUTED_TABLE + chunkPreTablePos + k * kMaskBlock,
                        kMaskBlock);
        }

        i >>= 8;
        // Valid once startPos >= 69632; smaller positions are handled by the
        // caller sending a zeroed cursor.
        do {
            const std::uint8_t xorByte =
                PRE_COMPUTED_TABLE[static_cast<std::size_t>(i) % kMaskBytePeriod];
            for (std::uint8_t& value : chunk) {
                value ^= xorByte;
            }
            i >>= 8;
        } while (i >= 0x11);

        const int storePos = pos >> 4;
        for (int k = 0; k < kMaskBlock; ++k) {
            std::memcpy(maskBytes.data() + storePos + k * kMaskBlock,
                        chunk.data() + k * kMaskBlock,
                        kMaskBlock);
        }
    }
#endif
}

std::uint8_t decryptByte(std::uint8_t cipher) {
    const std::uint8_t combined = cipher;
    return static_cast<std::uint8_t>(combined ^ (combined << 4));
}

}  // namespace

void kgmInit(const std::uint8_t* key, int keyLength) {
    if (key == nullptr || keyLength < 17) return;

    std::memcpy(fileKeyBytes.data(), key, 17);
    for (int i = 1; i < 16; i++) {
        std::memcpy(fileKeyBytes.data() + i * 17, fileKeyBytes.data(), 17);
    }
    std::memcpy(maskBytes.data(), PRE_COMPUTED_TABLE, kMaskBytePeriod);
    for (int i = 0; i < 272; i++) {
        fileKeyBytes[static_cast<std::size_t>(i)] ^= MASK_V2_PRE_DEF[static_cast<std::size_t>(i)];
    }
}

int kgmDecrypt(std::uint8_t* data, int offset, int length) {
    if (data == nullptr || length <= 0) return offset;

    int i = offset;
    int j = 0;
    int genMaskCounter = offset % kGenMaskPeriod;
    int fileKeyCounter = offset % kFileKeyPeriod;
    int maskByteIndexCounter = (offset >> 4) % kMaskBytePeriod;

    // Make sure a chunk that starts exactly on a period boundary has its mask.
    if (genMaskCounter == 0) {
        genMask(i);
    }

    while (j < length) {
        if (fileKeyCounter == kFileKeyPeriod) {
            fileKeyCounter = 0;
        }

        if (genMaskCounter == kGenMaskPeriod) {
            genMask(i);
            genMaskCounter = 0;
        }
        if (j > 0 && (i & 15) == 0) {
            if (++maskByteIndexCounter == kMaskBytePeriod) {
                maskByteIndexCounter = 0;
            }
        }

        const int available = length - j;
        if (available >= kMaskBlock && fileKeyCounter + kMaskBlock <= kFileKeyPeriod) {
            for (int k = 0; k < kMaskBlock; ++k) {
                const std::size_t index = static_cast<std::size_t>(j + k);
                const std::uint8_t fileKey =
                    fileKeyBytes[static_cast<std::size_t>(fileKeyCounter + k)];
                const std::uint8_t mask =
                    maskBytes[static_cast<std::size_t>(maskByteIndexCounter)];
                const std::uint8_t value = decryptByte(
                    static_cast<std::uint8_t>(fileKey ^ data[index] ^ mask));
                data[index] = value;
            }
            genMaskCounter += kMaskBlock;
            fileKeyCounter += kMaskBlock;
            i += kMaskBlock;
            j += kMaskBlock;
            continue;
        }

        const std::uint8_t fileKey = fileKeyBytes[static_cast<std::size_t>(fileKeyCounter)];
        const std::uint8_t mask = maskBytes[static_cast<std::size_t>(maskByteIndexCounter)];
        data[static_cast<std::size_t>(j)] =
            decryptByte(static_cast<std::uint8_t>(fileKey ^ data[static_cast<std::size_t>(j)] ^ mask));

        genMaskCounter++;
        fileKeyCounter++;
        i++;
        j++;
    }

    return i;
}

}  // namespace ncm

void ncm_kgm_init(const uint8_t* key, int key_len) {
    ncm::kgmInit(key, key_len);
}

int ncm_kgm_decrypt(uint8_t* data, int offset, int length) {
    return ncm::kgmDecrypt(data, offset, length);
}
