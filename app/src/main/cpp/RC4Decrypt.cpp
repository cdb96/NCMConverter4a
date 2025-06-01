#include <jni.h>
#include <cstdint>
#include <vector>
#include <numeric>

#if defined(__ARM_NEON__) || defined(__aarch64__)
    #include <arm_neon.h>
#else
    #include "NEON_2_SSE.h"
#endif

thread_local uint8_t keyStreamBytes[256];
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
}
void decryptData(uint8_t* data, int bytesRead) {
    int i = 0;
    #if defined(__aarch64__)
        for (; i + 64 <= bytesRead; i += 64) {
            int k = i & 0xff;
            uint8x16x4_t dataChunk = vld1q_u8_x4(data + i);
            uint8x16x4_t key = vld1q_u8_x4(keyStreamBytes + k);
            dataChunk.val[0] = veorq_u8(dataChunk.val[0], key.val[0]);
            dataChunk.val[1] = veorq_u8(dataChunk.val[1], key.val[1]);
            dataChunk.val[2] = veorq_u8(dataChunk.val[2], key.val[2]);
            dataChunk.val[3] = veorq_u8(dataChunk.val[3], key.val[3]);
            vst1q_u8_x4(data + i, dataChunk);
        }
    #elif defined(__x86_64__)
        for (; i + 16 <= bytesRead; i += 16) {
            int k = i & 0xff;
            uint8x16_t dataChunk = vld1q_u8(data + i);
            uint8x16_t key = vld1q_u8(keyStreamBytes + k);
            dataChunk = veorq_u8(dataChunk, key);
            vst1q_u8(data + i, dataChunk);
        }
    #endif
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