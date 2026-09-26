// RC4 core shared by the Android and Desktop builds.
//
// The NCM keystream is a 256 byte cycle XORed over the payload. The only
// requirement on callers is that every chunk except the last one is a multiple
// of 256 bytes, which is why decryptAt() can restart the cycle at byte 0 for
// each chunk. The last chunk is shorter than 256 bytes, so it stays aligned by
// construction (see NativeApi.h).
//
// The SIMD body is intentionally kept very close to the historical Android
// RC4Decrypt.cpp: the key schedule loads the 256 byte keystream into 16 vector
// registers once, and decryption then runs 16 loads -> 16 XORs -> 16 stores per
// 256 byte cycle. ARM uses native NEON, x86 uses NEON_2_SSE (see SimdCompat.h).
#include "NativeApi.h"
#include "SimdCompat.h"

#include <array>
#include <cstdint>
#include <numeric>
#include <utility>

namespace ncm {
namespace {

thread_local std::array<std::uint8_t, 256> keyStreamBytes{};

// The whole keystream, held as 16 x 128-bit chunks (16 * 16 = 256 bytes).
thread_local uint8x16_t keys[16];

void applyKeyStream(std::uint8_t* data, int bytesRead) {
    int i = 0;

    // Keep the old implementation structure almost unchanged:
    // 16 loads -> 16 XORs -> 16 stores = one complete 256-byte RC4 period.
    uint8x16_t chunk[16];

    for (; i + 256 <= bytesRead; i += 256) {
        for (int k = 0; k < 16; ++k) {
            const int offset = i + k * 16;
            chunk[k] = vld1q_u8(data + offset);
        }

        for (int k = 0; k < 16; ++k) {
            chunk[k] = veorq_u8(chunk[k], keys[k]);
        }

        for (int k = 0; k < 16; ++k) {
            const int offset = i + k * 16;
            vst1q_u8(data + offset, chunk[k]);
        }
    }

    // Scalar tail for the remainder of the final, incomplete 256-byte cycle.
    // `i` is a multiple of 256 here, so the cycle index is simply `i & 0xFF`.
    for (; i < bytesRead; ++i) {
        const int j = i & 0xFF;
        data[i] ^= keyStreamBytes[static_cast<std::size_t>(j)];
    }
}

}  // namespace

void rc4Init(const std::uint8_t* key, int keyLength) {
    if (key == nullptr || keyLength <= 0) return;

    std::array<std::uint8_t, 256> sBox{};
    std::iota(sBox.begin(), sBox.end(), std::uint8_t{0});

    int j = 0;
    for (int i = 0; i < 256; ++i) {
        j = (j + sBox[static_cast<std::size_t>(i)] + key[i % keyLength]) & 0xFF;
        std::swap(sBox[static_cast<std::size_t>(i)], sBox[static_cast<std::size_t>(j)]);
    }

    // Preserve the corrected historical indexing that avoids the old
    // out-of-bounds load around the end of the 256-byte period.
    for (int k = 1; k < 256; ++k) {
        keyStreamBytes[static_cast<std::size_t>(k - 1)] =
            sBox[(sBox[static_cast<std::size_t>(k)] +
                  sBox[(sBox[static_cast<std::size_t>(k)] + k) & 0xFF]) & 0xFF];
    }
    keyStreamBytes[255] = sBox[(sBox[0] + sBox[(sBox[0] + 0) & 0xFF]) & 0xFF];

    // Directly inherited from the old RC4Decrypt.cpp: the keystream is loaded
    // into the vector registers once, right after the key schedule.
    for (int i = 0; i < 16; ++i) {
        const uint8x16_t keyChunk = vld1q_u8(keyStreamBytes.data() + i * 16);
        keys[i] = keyChunk;
    }
}

void rc4DecryptAt(std::uint8_t* data, int length) {
    if (data == nullptr || length <= 0) return;
    applyKeyStream(data, length);
}

}  // namespace ncm

void ncm_rc4_init(const uint8_t* key, int key_len) {
    ncm::rc4Init(key, key_len);
}

void ncm_rc4_decrypt(uint8_t* data, int length) {
    ncm::rc4DecryptAt(data, length);
}
