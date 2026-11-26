// KGM decryption using GF(2) linear decomposition, 4096-byte high-order mask
// absorption, and pure NEON intrinsics (compiled via NEON_2_SSE on x86_64).
#include <jni.h>
#include <algorithm>
#include <array>
#include <cstdint>

#include "SimdCompat.h"
#include "KgmTables.h"

using ncm::MASK_V2_PRE_DEF;
using ncm::PRE_COMPUTED_TABLE;
using ncm::PRE_COMPUTED_TABLE_SIZE;

namespace {

constexpr int KEY_PERIOD = 16 * 17;                             // 272 bytes (17 x 16B vectors)
constexpr int PAGE_SIZE = 4096;                                 // 256 x 16B blocks
constexpr int SUPER_BLOCK_SIZE = PRE_COMPUTED_TABLE_SIZE * 16;  // 69632 = 17 * 4096 = 256 * 272

constexpr std::uint8_t transformByte(std::uint8_t x) noexcept {
    return static_cast<std::uint8_t>(x ^ (x << 4));
}

constexpr std::array<std::uint8_t, PRE_COMPUTED_TABLE_SIZE> makeTransformedTable() noexcept {
    std::array<std::uint8_t, PRE_COMPUTED_TABLE_SIZE> out{};
    for (int i = 0; i < PRE_COMPUTED_TABLE_SIZE; ++i) {
        out[static_cast<std::size_t>(i)] = transformByte(PRE_COMPUTED_TABLE[i]);
    }
    return out;
}

inline constexpr auto F_PRE_COMPUTED_TABLE = makeTransformedTable();

thread_local std::uint8_t fFileKeyBytes[KEY_PERIOD]{};
thread_local int activeSuperBlock = 0;

void throwIllegalArg(JNIEnv* env, const char* msg) {
    if (jclass cls = env->FindClass("java/lang/IllegalArgumentException"); cls != nullptr) {
        env->ThrowNew(cls, msg);
    }
}

// Constant high-order transformed mask f(C(q)) for 4096-byte page q.
inline std::uint8_t computeHighMask(int q) noexcept {
    std::uint8_t acc = 0;
    int t = q;
    do {
        acc ^= F_PRE_COMPUTED_TABLE[static_cast<std::size_t>(t % PRE_COMPUTED_TABLE_SIZE)];
        t >>= 8;
    } while (t >= 0x11);
    return acc;
}

inline void decrypt16(std::uint8_t* ptr, uint8x16_t key, uint8x16_t mask) noexcept {
    const uint8x16_t c = vld1q_u8(ptr);
    const uint8x16_t t = veorq_u8(c, vshlq_n_u8(c, 4));
    vst1q_u8(ptr, veorq_u8(t, veorq_u8(key, mask)));
}

template <int Base>
inline void decrypt64(std::uint8_t* ptr, const uint8x16_t* key, uint8x8_t mask8) noexcept {
    const uint8x16_t c0 = vld1q_u8(ptr),      c1 = vld1q_u8(ptr + 16);
    const uint8x16_t c2 = vld1q_u8(ptr + 32), c3 = vld1q_u8(ptr + 48);
    const uint8x16_t km0 = veorq_u8(key[0], vdupq_lane_u8(mask8, Base + 0));
    const uint8x16_t km1 = veorq_u8(key[1], vdupq_lane_u8(mask8, Base + 1));
    const uint8x16_t km2 = veorq_u8(key[2], vdupq_lane_u8(mask8, Base + 2));
    const uint8x16_t km3 = veorq_u8(key[3], vdupq_lane_u8(mask8, Base + 3));
    vst1q_u8(ptr,      veorq_u8(veorq_u8(c0, vshlq_n_u8(c0, 4)), km0));
    vst1q_u8(ptr + 16, veorq_u8(veorq_u8(c1, vshlq_n_u8(c1, 4)), km1));
    vst1q_u8(ptr + 32, veorq_u8(veorq_u8(c2, vshlq_n_u8(c2, 4)), km2));
    vst1q_u8(ptr + 48, veorq_u8(veorq_u8(c3, vshlq_n_u8(c3, 4)), km3));
}

}  // namespace

// offset must be a multiple of 16; only the final chunk may end unaligned.
extern "C" JNIEXPORT jint JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGMDecrypt_decrypt(JNIEnv* env, jclass,
                                                     jbyteArray cipher_data_bytes, jint offset, jint bytes_read) {
    if (cipher_data_bytes == nullptr || offset < 0 || (offset & 15) != 0 ||
        bytes_read < 0 || bytes_read > env->GetArrayLength(cipher_data_bytes)) {
        throwIllegalArg(env, "invalid KGM byte array range");
        return offset;
    }
    if (bytes_read == 0) return offset;
    auto* data = reinterpret_cast<std::uint8_t*>(env->GetPrimitiveArrayCritical(cipher_data_bytes, nullptr));
    if (data == nullptr) return offset;

    uint8x16_t baseKey[17];
    for (int k = 0; k < 17; ++k) baseKey[k] = vld1q_u8(fFileKeyBytes + k * 16);

    int i = offset;
    int j = 0;
    const std::uint8_t* const fTable = F_PRE_COMPUTED_TABLE.data();

    while (j + 16 <= bytes_read) {
        const int counter = i % SUPER_BLOCK_SIZE;
        if (counter == 0) activeSuperBlock = i / SUPER_BLOCK_SIZE;

        const int pageBlocks = std::min((bytes_read - j) >> 4, (PAGE_SIZE - (counter & (PAGE_SIZE - 1))) >> 4);
        const uint8x16_t vFc = vdupq_n_u8(computeHighMask(activeSuperBlock * 17 + (counter >> 12)));

        uint8x16_t winKey[17];
        for (int k = 0; k < 17; ++k) winKey[k] = veorq_u8(baseKey[k], vFc);

        const std::uint8_t* maskPtr = fTable + (counter >> 4);
        std::uint8_t* p = data + j;
        int keyIdx = (i % KEY_PERIOD) >> 4;
        int rem = pageBlocks;

        while (rem > 0 && keyIdx != 0) {
            decrypt16(p, winKey[keyIdx], vld1q_dup_u8(maskPtr++));
            p += 16;
            --rem;
            if (++keyIdx == 17) keyIdx = 0;
        }
        while (rem >= 17) {
            const uint8x16_t vMask16 = vld1q_u8(maskPtr);
            const uint8x8_t mLo = vget_low_u8(vMask16);
            const uint8x8_t mHi = vget_high_u8(vMask16);
            decrypt64<0>(p,       winKey,      mLo);
            decrypt64<4>(p + 64,  winKey + 4,  mLo);
            decrypt64<0>(p + 128, winKey + 8,  mHi);
            decrypt64<4>(p + 192, winKey + 12, mHi);
            decrypt16(p + 256, winKey[16], vld1q_dup_u8(maskPtr + 16));
            p += KEY_PERIOD;
            maskPtr += 17;
            rem -= 17;
        }
        for (int k = 0; k < rem; ++k, p += 16) {
            decrypt16(p, winKey[k], vld1q_dup_u8(maskPtr++));
        }

        const int advanced = pageBlocks << 4;
        i += advanced;
        j += advanced;
    }

    if (j < bytes_read) {
        const int counter = i % SUPER_BLOCK_SIZE;
        if (counter == 0) activeSuperBlock = i / SUPER_BLOCK_SIZE;
        const std::uint8_t fMask = static_cast<std::uint8_t>(
            fTable[counter >> 4] ^ computeHighMask(activeSuperBlock * 17 + (counter >> 12)));
        for (; j < bytes_read; ++i, ++j) {
            data[j] = static_cast<std::uint8_t>(transformByte(data[j]) ^ fFileKeyBytes[i % KEY_PERIOD] ^ fMask);
        }
    }

    env->ReleasePrimitiveArrayCritical(cipher_data_bytes, data, 0);
    return i;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGMDecrypt_init(JNIEnv* env, jclass, jbyteArray file_key_bytes) {
    if (file_key_bytes == nullptr || env->GetArrayLength(file_key_bytes) < 17) {
        throwIllegalArg(env, "KGM key must contain at least 17 bytes");
        return;
    }
    std::uint8_t rawKey[17];
    env->GetByteArrayRegion(file_key_bytes, 0, 17, reinterpret_cast<jbyte*>(rawKey));
    for (int idx = 0; idx < KEY_PERIOD; ++idx) {
        fFileKeyBytes[idx] = transformByte(static_cast<std::uint8_t>(rawKey[idx % 17] ^ MASK_V2_PRE_DEF[idx]));
    }
    activeSuperBlock = 0;
}
