#include <jni.h>
#include <cstdint>
#include <vector>
#include <numeric>
#include <utility>

#if defined(__ARM_NEON__) || defined(__aarch64__)
    #include <arm_neon.h>
#else
    #include "NEON_2_SSE.h"
#endif

thread_local uint8_t keyStreamBytes[256];
thread_local uint8x16_t keys[16];
extern "C"
JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_JNIUtil_RC4Decrypt_ksa(JNIEnv* env, jclass, jbyteArray key) {
    jsize keyLength = env->GetArrayLength(key);
    auto* keyBytes = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(key, nullptr));

    uint8_t sBox[256];
    std::iota(sBox, sBox + 256, 0);

    int j = 0;
    for (int i = 0; i < 256; ++i) {
        j = (j + sBox[i] + keyBytes[i % keyLength]) & 0xFF;
        std::swap(sBox[i], sBox[j]);
    }
    env->ReleaseByteArrayElements(key, reinterpret_cast<jbyte*>(keyBytes), 0);

    for (int k = 1; k < 256; ++k) {
        keyStreamBytes[k - 1] = sBox[ (sBox[k] + sBox [ ( sBox[k] + k ) & 0xff ] ) & 0xff ];
    }
    keyStreamBytes[255] = sBox[ (sBox[0] + sBox [ ( sBox[0] + 0 ) & 0xff ] ) & 0xff ];
    for(int i = 0; i < 16; i++) {
        uint8x16_t keyChunk = vld1q_u8(keyStreamBytes + i * 16);
        keys[i] = keyChunk;
    }
}
void decryptData(uint8_t* data, int bytesRead) {
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
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_JNIUtil_RC4Decrypt_prgaDecryptByteBuffer(JNIEnv* env, jclass, jobject cipherData, jint bytesRead) {
    // 处理 ByteBuffer 的实现
    auto* cipherDataBytes = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(cipherData));
    decryptData(cipherDataBytes, bytesRead);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_JNIUtil_RC4Decrypt_prgaDecryptByteArray(JNIEnv* env, jclass, jbyteArray cipherData, jint bytesRead) {
    // 处理 ByteArray 的实现
    jbyte* cipherDataBytes = env->GetByteArrayElements(cipherData, nullptr);
    decryptData(reinterpret_cast<uint8_t*>(cipherDataBytes), bytesRead);
    env->ReleaseByteArrayElements(cipherData, cipherDataBytes, 0);
}