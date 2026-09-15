// KGM core shared by the Android and Desktop builds.
//
// This is a port of the previous Android NEON implementation; the byte-for-byte
// behaviour (including the mask-table cursor arithmetic) is kept on purpose so
// that both platforms keep producing identical output.
//
// The SIMD body follows the historical KGMDecrypt.cpp: genMask() expands the
// pre-computed table, and the main loop keeps the old two-path med8/msk8
// structure rather than the algebraically merged form. ARM compiles it through
// <arm_neon.h>, x86 through NEON_2_SSE (see SimdCompat.h).
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
constexpr int kMaskV2Period = 272;          // 17 * 16
constexpr int kMaskBytePeriod = 4352;       // PRE_COMPUTED_TABLE_SIZE

thread_local std::array<std::uint8_t, kMaskBytePeriod> maskBytes{};
// The 17-byte file key repeated 16 times. Kept unmerged on purpose: the
// historical SIMD loop XORs MASK_V2_PRE_DEF in separately, and restoring that
// structure is what makes the migration comparable to the old implementation.
thread_local std::array<std::uint8_t, 16 * 17> ownKeyBytes{};

#if NCM_HAS_SIMD
const uint8x16_t andVec = vdupq_n_u8(0x0f);
#endif

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

// Advances the per-byte cursors just before a byte is consumed. The historical
// loop did this inline; it is factored out here so the SIMD body and the scalar
// paths cannot drift apart. The order matches the old code exactly.
inline void normalizeCountersBeforeByte(int i, int j, int& genMaskCounter, int& maskV2Counter,
                                        int& keyBytesIndexCounter) {
    if (genMaskCounter == kGenMaskPeriod) {
        genMask(i);
        genMaskCounter = 0;
    }

    if (maskV2Counter == kMaskV2Period) {
        maskV2Counter = 0;
    }

    if (j > 0 && (i & 15) == 0) {
        ++keyBytesIndexCounter;
        if (keyBytesIndexCounter == kMaskBytePeriod) {
            keyBytesIndexCounter = 0;
        }
    }
}

// Scalar equivalent of one SIMD lane:
//   int med8 = ownKeyBytes[i % 17] ^ cipherDataBytes[j];
//   med8 ^= (med8 & 0xf) << 4;
//   int msk8 = maskBytes[keyBytesIndexCounter] ^ MASK_V2_PRE_DEF[MaskV2Counter];
//   msk8 ^= (msk8 & 0xf) << 4;
//   cipherDataBytes[j] = (med8 ^ msk8);
// `ownKeyBytes[maskV2Counter]` stands in for `ownKeyBytes[i % 17]` because
// kMaskV2Period is a multiple of 17, so both indices agree for every byte.
inline void decryptScalarByte(std::uint8_t* data, int j, int maskV2Counter,
                              int keyBytesIndexCounter) {
    int med8 = ownKeyBytes[static_cast<std::size_t>(maskV2Counter)] ^ data[j];
    med8 ^= (med8 & 0x0F) << 4;

    int msk8 = maskBytes[static_cast<std::size_t>(keyBytesIndexCounter)] ^
               MASK_V2_PRE_DEF[static_cast<std::size_t>(maskV2Counter)];
    msk8 ^= (msk8 & 0x0F) << 4;

    data[j] = static_cast<std::uint8_t>(med8 ^ msk8);
}

}  // namespace

void kgmInit(const std::uint8_t* key, int keyLength) {
    if (key == nullptr || keyLength < 17) return;

    // Keep the old layout exactly: repeat the original 17-byte key 16 times.
    std::memcpy(ownKeyBytes.data(), key, 17);
    for (int i = 1; i < 16; ++i) {
        std::memcpy(ownKeyBytes.data() + i * 17, ownKeyBytes.data(), 17);
    }

    // The first 69632-byte period uses the pre-computed table directly.
    std::memcpy(maskBytes.data(), PRE_COMPUTED_TABLE, kMaskBytePeriod);
}

int kgmDecrypt(std::uint8_t* data, int offset, int bytesRead) {
    if (data == nullptr || bytesRead <= 0) return offset;

    int i = offset;
    int j = 0;

    int genMaskCounter = offset % kGenMaskPeriod;
    int maskV2Counter = offset % kMaskV2Period;
    int keyBytesIndexCounter = (offset >> 4) % kMaskBytePeriod;

    // A chunk that starts exactly on a period boundary must refresh the mask;
    // genMask(0) is a no-op because the first 17 table bytes are zero, so the
    // offset == 0 case keeps the table copied by kgmInit().
    if (genMaskCounter == 0) {
        genMask(i);
    }

#if NCM_HAS_SIMD
    // ---------------------------------------------------------------------
    // Scalar prefix
    // ---------------------------------------------------------------------
    // The historical SIMD loop assumed a 16-byte aligned stream position: one
    // SIMD block spans exactly one maskBytes entry, and maskV2Counter has to
    // stay a multiple of 16 so `vld1q_u8(ownKeyBytes + maskV2Counter)` cannot
    // read past the 272-byte cycle. Preserve the SIMD body, but consume an
    // unaligned prefix explicitly so the public C API stays correct for
    // arbitrary offsets.
    while (j < bytesRead && (i & 15) != 0) {
        normalizeCountersBeforeByte(i, j, genMaskCounter, maskV2Counter,
                                    keyBytesIndexCounter);

        decryptScalarByte(data, j, maskV2Counter, keyBytesIndexCounter);

        ++genMaskCounter;
        ++maskV2Counter;
        ++i;
        ++j;
    }

    // ---------------------------------------------------------------------
    // 16-byte SIMD main loop
    // ---------------------------------------------------------------------
    // Runs on the 16-byte aligned stream position the prefix just established.
    for (; j + 16 <= bytesRead; i += 16, j += 16) {
        normalizeCountersBeforeByte(i, j, genMaskCounter, maskV2Counter,
                                    keyBytesIndexCounter);

        uint8x16_t cipherDataBytesChunk = vld1q_u8(data + j);

        // ---- med8 --------------------------------------------------------
        uint8x16_t med8DataChunkOriginal = vld1q_u8(ownKeyBytes.data() + maskV2Counter);
        uint8x16_t med8DataChunkTemp;

        med8DataChunkOriginal = veorq_u8(med8DataChunkOriginal, cipherDataBytesChunk);
        med8DataChunkTemp = vandq_u8(med8DataChunkOriginal, andVec);
        med8DataChunkTemp = vshlq_n_u8(med8DataChunkTemp, 4);
        med8DataChunkOriginal = veorq_u8(med8DataChunkOriginal, med8DataChunkTemp);

        // ---- msk8 --------------------------------------------------------
        uint8x16_t msk8DataChunkOriginal =
            vld1q_dup_u8(maskBytes.data() + keyBytesIndexCounter);
        uint8x16_t msk8DataChunkTemp;

        const uint8x16_t maskV2Data = vld1q_u8(MASK_V2_PRE_DEF + maskV2Counter);

        msk8DataChunkOriginal = veorq_u8(msk8DataChunkOriginal, maskV2Data);
        msk8DataChunkTemp = vandq_u8(msk8DataChunkOriginal, andVec);
        msk8DataChunkTemp = vshlq_n_u8(msk8DataChunkTemp, 4);
        msk8DataChunkOriginal = veorq_u8(msk8DataChunkOriginal, msk8DataChunkTemp);

        // ---- output ------------------------------------------------------
        cipherDataBytesChunk = veorq_u8(msk8DataChunkOriginal, med8DataChunkOriginal);
        vst1q_u8(data + j, cipherDataBytesChunk);

        genMaskCounter += 16;
        maskV2Counter += 16;
    }
#endif

    // ---------------------------------------------------------------------
    // Scalar tail (and the whole implementation when NCM_HAS_SIMD == 0)
    // ---------------------------------------------------------------------
    while (j < bytesRead) {
        normalizeCountersBeforeByte(i, j, genMaskCounter, maskV2Counter,
                                    keyBytesIndexCounter);

        decryptScalarByte(data, j, maskV2Counter, keyBytesIndexCounter);

        ++genMaskCounter;
        ++maskV2Counter;
        ++i;
        ++j;
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
