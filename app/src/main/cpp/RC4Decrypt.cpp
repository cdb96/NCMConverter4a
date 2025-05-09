#include <jni.h>
#include <cstdint>
#include <vector>
#include <arm_neon.h>

uint8_t keyStreamBytes[256];
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_cdb96_ncmconverter4a_RC4Jni_ksa(JNIEnv* env, jclass, jbyteArray key) {
    jsize keyLength = env->GetArrayLength(key);
    auto* keyBytes = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(key, nullptr));

    uint8_t sBox[256];
    for (int i = 0; i < 256; ++i) {
        sBox[i] = static_cast<uint8_t>(i);
    }

    int j = 0;
    for (int i = 0; i < 256; ++i) {
        j = (j + sBox[i] + keyBytes[i % keyLength]) & 0xFF;
        std::swap(sBox[i], sBox[j]);
    }

    for (int k = 1; k < 256; ++k) {
        keyStreamBytes[k - 1] = sBox[ (sBox[k] + sBox [ ( sBox[k] + k ) & 0xff ] ) & 0xff ];
    }
    keyStreamBytes[255] = sBox[ (sBox[0] + sBox [ ( sBox[0] + 0 ) & 0xff ] ) & 0xff ];

    jbyteArray result = env->NewByteArray(256);
    env->SetByteArrayRegion(result, 0, 256, reinterpret_cast<const jbyte*>(sBox));
    env->ReleaseByteArrayElements(key, reinterpret_cast<jbyte*>(keyBytes), 0);

    return result;
}
extern "C"
JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_RC4Jni_prgaDecrypt(JNIEnv* env, jclass, jbyteArray sBox, jbyteArray cipherData) {
    auto* cipherDataBytes = reinterpret_cast<uint8_t*>( env->GetByteArrayElements(cipherData, nullptr) );
    auto* sBoxBytes = reinterpret_cast<uint8_t*>( env->GetByteArrayElements(sBox, nullptr) );
    jsize cipherDataLength = env ->GetArrayLength(cipherData);

    int i = 0;
    for (;i + 64 <= cipherDataLength; i += 64){
        int k = i & 0xff;
        uint8x16x4_t cipherDataBytesChunk = vld1q_u8_x4(cipherDataBytes + i);
        uint8x16x4_t key = vld1q_u8_x4(keyStreamBytes + k);
        cipherDataBytesChunk.val[0] = veorq_u8(cipherDataBytesChunk.val[0], key.val[0]);
        cipherDataBytesChunk.val[1] = veorq_u8(cipherDataBytesChunk.val[1], key.val[1]);
        cipherDataBytesChunk.val[2] = veorq_u8(cipherDataBytesChunk.val[2], key.val[2]);
        cipherDataBytesChunk.val[3] = veorq_u8(cipherDataBytesChunk.val[3], key.val[3]);
        vst1q_u8_x4(cipherDataBytes + i, cipherDataBytesChunk);
    }

    for (; i < cipherDataLength; i++) {
        int j = i & 0xff;
        cipherDataBytes[i] ^= keyStreamBytes[j];
    }

    env->ReleaseByteArrayElements(sBox, reinterpret_cast<jbyte*>(sBoxBytes), 0);
    env->ReleaseByteArrayElements(cipherData, reinterpret_cast<jbyte*>(cipherDataBytes), 0);
}