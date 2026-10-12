// v3.4 RC4 algorithm with per-conversion key state.
#include <jni.h>
#include <cstdint>
#include <numeric>
#include <utility>

#include "SimdCompat.h"
#include "ContextMemory.h"

struct NcmRc4Context {
    uint8_t keyStreamBytes[256]{};
    uint8x16_t keys[16]{};
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_cdb96_ncmconverter4a_jni_RC4Decrypt_create(JNIEnv* env, jclass, jbyteArray key) {
    jsize keyLength = key == nullptr ? 0 : env->GetArrayLength(key);
    if (keyLength == 0) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
        if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, "RC4 key must not be null or empty");
        return 0;
    }
    auto* context = ncm::createContext<NcmRc4Context>(env);
    if (context == nullptr) return 0;
    auto* keyBytes = reinterpret_cast<uint8_t*>(env->GetPrimitiveArrayCritical(key, nullptr));
    if (keyBytes == nullptr) {
        ncm::destroyContext(context);
        return 0;
    }
    auto& keyStreamBytes = context->keyStreamBytes;
    auto& keys = context->keys;

    uint8_t sBox[256];
    std::iota(sBox, sBox + 256, 0);

    int j = 0;
    for (int i = 0; i < 256; ++i) {
        j = (j + sBox[i] + keyBytes[i % keyLength]) & 0xFF;
        std::swap(sBox[i], sBox[j]);
    }
    env->ReleasePrimitiveArrayCritical(key, keyBytes, JNI_ABORT);

    for (int k = 1; k < 256; ++k) {
        keyStreamBytes[k - 1] = sBox[(sBox[k] + sBox[(sBox[k] + k) & 0xff]) & 0xff];
    }
    keyStreamBytes[255] = sBox[(sBox[0] + sBox[(sBox[0] + 0) & 0xff]) & 0xff];
    for (int i = 0; i < 16; i++) {
        uint8x16_t keyChunk = vld1q_u8(keyStreamBytes + i * 16);
        keys[i] = keyChunk;
    }
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_RC4Decrypt_decrypt(JNIEnv* env, jclass, jlong handle,
                                                  jbyteArray cipherData, jint bytesRead) {
    if (handle == 0 || cipherData == nullptr || bytesRead < 0 || bytesRead > env->GetArrayLength(cipherData)) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
        if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, "invalid RC4 context or byte array range");
        return;
    }
    if (bytesRead == 0) return;
    auto* context = reinterpret_cast<NcmRc4Context*>(handle);
    auto* data = reinterpret_cast<uint8_t*>(env->GetPrimitiveArrayCritical(cipherData, nullptr));
    if (data == nullptr) return;
    const auto& keyStreamBytes = context->keyStreamBytes;
    const auto& keys = context->keys;

    int i = 0;
    uint8x16_t chunk[16];
    for (; i + 256 <= bytesRead; i += 256) {
        for (int k = 0; k < 16; ++k) {
            int offset = i + k * 16;
            chunk[k] = vld1q_u8(data + offset);
        }
        for (int k = 0; k < 16; ++k) {
            chunk[k] = veorq_u8(chunk[k], keys[k]);
        }
        for (int k = 0; k < 16; ++k) {
            int offset = i + k * 16;
            vst1q_u8(data + offset, chunk[k]);
        }
    }
    for (; i < bytesRead; i++) {
        int j = i & 0xff;
        data[i] ^= keyStreamBytes[j];
    }
    env->ReleasePrimitiveArrayCritical(cipherData, data, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_RC4Decrypt_destroy(JNIEnv*, jclass, jlong handle) {
    ncm::destroyContext(reinterpret_cast<NcmRc4Context*>(handle));
}
