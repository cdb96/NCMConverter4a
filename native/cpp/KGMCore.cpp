// KGM core shared by the Android and Desktop builds.
//
// Uses the merged byte transform shared by both platform builds.
// The file key and fixed mask are combined during initialization. Since
// T(x) = x ^ (x << 4) is linear over XOR for bytes, each SIMD block needs only
// one transform. ARM uses NEON; x86 uses NEON_2_SSE via SimdCompat.h.
//
// The main loop requires a 16-byte aligned `offset` (see NativeApi.h), which is
// what the historical code assumed as well; `length` may be arbitrary, and the
// final partial block is handled byte by byte.
#include "NativeApi.h"
#include "SimdCompat.h"
#include "ContextMemory.h"

#include <array>
#include <cstdint>
#include <cstring>

#include "KgmTables.h"

struct NcmKgmContext {
    std::array<std::uint8_t, 4352> maskBytes{};
    std::array<std::uint8_t, 272> fileKeyBytes{};
};

namespace ncm {
namespace {

constexpr int kMaskBlock = 16;
constexpr int kGenMaskPeriod = 69632;       // 17 * 16 * 16 * 16
constexpr int kMaskV2Period = 272;          // 17 * 16
constexpr int kMaskBytePeriod = 4352;       // PRE_COMPUTED_TABLE_SIZE

// Expands the pre-computed byte table into 16-byte masks for one 69632 byte
// period starting at absolute stream position `startPos`.
void genMask(NcmKgmContext& context, int startPos) {
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
            vst1q_u8(context.maskBytes.data() + storePos + k * 16, chunk[k]);
        }
    }
}

// Advances the per-byte cursors just before a byte is consumed. The historical
// loop did this inline; it is factored out here so the SIMD body and the scalar
// paths cannot drift apart. The order matches the old code exactly.
inline void normalizeCountersBeforeByte(NcmKgmContext& context, int i, int j,
                                        int& genMaskCounter, int& maskV2Counter,
                                        int& keyBytesIndexCounter) {
    if (genMaskCounter == kGenMaskPeriod) {
        genMask(context, i);
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

// Scalar equivalent of the merged SIMD transform; conversion to uint8_t
// discards the high bits of the shift, matching the SIMD byte lanes.
inline void decryptScalarByte(const NcmKgmContext& context, std::uint8_t* data,
                              int j, int maskV2Counter,
                              int keyBytesIndexCounter) {
    const int combined = context.fileKeyBytes[static_cast<std::size_t>(maskV2Counter)] ^
                         data[j] ^ context.maskBytes[static_cast<std::size_t>(keyBytesIndexCounter)];
    data[j] = static_cast<std::uint8_t>(combined ^ (combined << 4));
}

}  // namespace

void kgmInit(NcmKgmContext& context, const std::uint8_t* key, int keyLength) {
    if (key == nullptr || keyLength < 17) return;

    // Repeat the key, then fold the fixed mask into the per-file table.
    std::memcpy(context.fileKeyBytes.data(), key, 17);
    for (int i = 1; i < 16; ++i) {
        std::memcpy(context.fileKeyBytes.data() + i * 17, context.fileKeyBytes.data(), 17);
    }

    for (int i = 0; i < kMaskV2Period; ++i) {
        context.fileKeyBytes[static_cast<std::size_t>(i)] ^= MASK_V2_PRE_DEF[i];
    }

    // The first 69632-byte period uses the pre-computed table directly.
    std::memcpy(context.maskBytes.data(), PRE_COMPUTED_TABLE, kMaskBytePeriod);
}

int kgmDecrypt(NcmKgmContext& context, std::uint8_t* data, int offset, int bytesRead) {
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
        genMask(context, i);
    }

    // ---------------------------------------------------------------------
    // 16-byte SIMD main loop
    // ---------------------------------------------------------------------
    // `offset` is a multiple of 16 by contract (see NativeApi.h), so every
    // iteration starts on a 16-byte block boundary: one SIMD block spans exactly
    // one maskBytes entry, and maskV2Counter stays a multiple of 16, so
    // `vld1q_u8(fileKeyBytes + maskV2Counter)` cannot read past the 272-byte cycle.
    for (; j + 16 <= bytesRead; i += 16, j += 16) {
        normalizeCountersBeforeByte(context, i, j, genMaskCounter, maskV2Counter,
                                    keyBytesIndexCounter);

        const uint8x16_t cipher = vld1q_u8(data + j);
        const uint8x16_t fileKey = vld1q_u8(context.fileKeyBytes.data() + maskV2Counter);
        const uint8x16_t mask = vld1q_dup_u8(context.maskBytes.data() + keyBytesIndexCounter);
        const uint8x16_t combined = veorq_u8(veorq_u8(fileKey, cipher), mask);
        const uint8x16_t result = veorq_u8(combined, vshlq_n_u8(combined, 4));
        vst1q_u8(data + j, result);

        genMaskCounter += 16;
        maskV2Counter += 16;
    }
    // ---------------------------------------------------------------------
    // Scalar tail for the last bytes of the chunk
    // ---------------------------------------------------------------------
    // The tail must keep calling normalizeCountersBeforeByte(): none of its three
    // checks is SIMD bookkeeping that the tail could skip.
    //   * The SIMD loop advances genMaskCounter/maskV2Counter by 16, so it can
    //     stop with genMaskCounter == 69632 or maskV2Counter == 272 exactly, and
    //     both then fall due on the first tail byte: offset=69616 length=17 puts
    //     the 69632 boundary there, offset=256 length=17 leaves maskV2Counter at
    //     272.
    //   * The first tail byte is 16-byte aligned, so it has to advance
    //     keyBytesIndexCounter like every other 16-byte block start.
    // Skipping it reads past the 272-byte fileKeyBytes/MASK_V2_PRE_DEF cycle and
    // decodes the tail with a stale mask block.
    while (j < bytesRead) {
        normalizeCountersBeforeByte(context, i, j, genMaskCounter, maskV2Counter,
                                    keyBytesIndexCounter);

        decryptScalarByte(context, data, j, maskV2Counter, keyBytesIndexCounter);

        ++genMaskCounter;
        ++maskV2Counter;
        ++i;
        ++j;
    }

    return i;
}

}  // namespace ncm

NcmKgmContext* ncm_kgm_create(const uint8_t* key, int key_len) {
    if (key == nullptr || key_len < 17) return nullptr;
    auto* context = ncm::createContext<NcmKgmContext>();
    if (context != nullptr) ncm::kgmInit(*context, key, 17);
    return context;
}

int ncm_kgm_decrypt(NcmKgmContext* context, uint8_t* data, int offset, int length) {
    return context == nullptr ? offset : ncm::kgmDecrypt(*context, data, offset, length);
}

void ncm_kgm_destroy(NcmKgmContext* context) {
    ncm::destroyContext(context);
}
