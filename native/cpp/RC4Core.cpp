// v3.4 RC4 algorithm with thread-local key state and pure NEON SIMD.
#include <jni.h>
#include <cstdint>
#include <numeric>
#include <utility>

#include "SimdCompat.h"

namespace {

thread_local uint8_t keyStreamBytes[256]{};
thread_local uint8x16_t keys[16]{};

void throwIllegalArg(JNIEnv* env, const char* msg) {
    if (jclass cls = env->FindClass("java/lang/IllegalArgumentException"); cls != nullptr) {
        env->ThrowNew(cls, msg);
    }
}

inline void xor64(uint8_t* p, const uint8x16_t* k) noexcept {
    vst1q_u8(p,      veorq_u8(vld1q_u8(p),      k[0]));
    vst1q_u8(p + 16, veorq_u8(vld1q_u8(p + 16), k[1]));
    vst1q_u8(p + 32, veorq_u8(vld1q_u8(p + 32), k[2]));
    vst1q_u8(p + 48, veorq_u8(vld1q_u8(p + 48), k[3]));
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_RC4Decrypt_init(JNIEnv* env, jclass, jbyteArray key) {
    const jsize keyLength = key == nullptr ? 0 : env->GetArrayLength(key);
    if (keyLength == 0) {
        throwIllegalArg(env, "RC4 key must not be null or empty");
        return;
    }
    auto* keyBytes = reinterpret_cast<uint8_t*>(env->GetPrimitiveArrayCritical(key, nullptr));
    if (keyBytes == nullptr) return;

    uint8_t sBox[256];
    std::iota(sBox, sBox + 256, 0);
    for (int i = 0, j = 0, ki = 0; i < 256; ++i) {
        j = (j + sBox[i] + keyBytes[ki]) & 0xFF;
        std::swap(sBox[i], sBox[j]);
        if (++ki == keyLength) ki = 0;
    }
    env->ReleasePrimitiveArrayCritical(key, keyBytes, JNI_ABORT);

    for (int k = 0; k < 256; ++k) {
        const int idx = (k + 1) & 0xFF;
        keyStreamBytes[k] = sBox[(sBox[idx] + sBox[(sBox[idx] + idx) & 0xFF]) & 0xFF];
    }
    for (int i = 0; i < 16; ++i) {
        keys[i] = vld1q_u8(keyStreamBytes + i * 16);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_RC4Decrypt_decrypt(JNIEnv* env, jclass,
                                                     jbyteArray cipherData, jint bytesRead) {
    if (cipherData == nullptr || bytesRead < 0 || bytesRead > env->GetArrayLength(cipherData)) {
        throwIllegalArg(env, "invalid RC4 byte array range");
        return;
    }
    if (bytesRead == 0) return;
    auto* data = reinterpret_cast<uint8_t*>(env->GetPrimitiveArrayCritical(cipherData, nullptr));
    if (data == nullptr) return;

    uint8x16_t k[16];
    for (int v = 0; v < 16; ++v) k[v] = keys[v];

    int i = 0;
    for (; i + 256 <= bytesRead; i += 256) {
        xor64(data + i,       k);
        xor64(data + i + 64,  k + 4);
        xor64(data + i + 128, k + 8);
        xor64(data + i + 192, k + 12);
    }
    for (int v = 0; i + 16 <= bytesRead; i += 16, ++v) {
        vst1q_u8(data + i, veorq_u8(vld1q_u8(data + i), k[v]));
    }
    for (; i < bytesRead; ++i) {
        data[i] ^= keyStreamBytes[i & 0xFF];
    }
    env->ReleasePrimitiveArrayCritical(cipherData, data, 0);
}
