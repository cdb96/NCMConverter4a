#include <jni.h>
#include <cstdint>
#include <vector>
#include <arm_neon.h>

uint8_t keyStreamBytes[257];
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

    for (int k = 0; k < 256; ++k) {
        keyStreamBytes[k] = sBox[ (sBox[k] + sBox [ ( sBox[k] + k ) & 0xff ] ) & 0xff ];
    }
    //下面的代码进行到i = 256 * n - 15的时候会出现数组越界，迫不得已只能这么搞了
    keyStreamBytes[256] = sBox[ (sBox[0] + sBox [ ( sBox[0] + 0 ) & 0xff ] ) & 0xff ];

    jbyteArray result = env->NewByteArray(256);
    env->SetByteArrayRegion(result, 0, 256, reinterpret_cast<const jbyte*>(sBox));
    env->ReleaseByteArrayElements(key, reinterpret_cast<jbyte*>(keyBytes), 0);

    return result;
}
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_cdb96_ncmconverter4a_RC4Jni_prgaDecrypt(JNIEnv* env, jclass, jbyteArray sBox, jbyteArray cipherData) {
    auto* cipherDataBytes = reinterpret_cast<uint8_t*>( env->GetByteArrayElements(cipherData, nullptr) );
    auto* sBoxBytes = reinterpret_cast<uint8_t*>( env->GetByteArrayElements(sBox, nullptr) );
    jsize cipherDataLength = env ->GetArrayLength(cipherData);

    int i = 1;
    for (;i + 16 <= cipherDataLength + 1; i += 16){
        uint8x16_t keyStreamChunk = vld1q_u8(keyStreamBytes + ( i & 0xff));
        uint8x16_t cipherDataBytesChunk = vld1q_u8(cipherDataBytes + i - 1);
        uint8x16_t decryptedChunk = veorq_u8(cipherDataBytesChunk,keyStreamChunk);
        vst1q_u8(cipherDataBytes + i - 1, decryptedChunk);
    }

    for (; i < cipherDataLength + 1; i++) {
        int j = i & 0xff;
        cipherDataBytes[i-1] ^= keyStreamBytes[j];
    }

    auto decryptedResult = env->NewByteArray(cipherDataLength);
    env ->SetByteArrayRegion( decryptedResult,0,cipherDataLength,reinterpret_cast<const jbyte*>(cipherDataBytes) );

    env->ReleaseByteArrayElements(sBox, reinterpret_cast<jbyte*>(sBoxBytes), 0);
    env->ReleaseByteArrayElements(cipherData, reinterpret_cast<jbyte*>(cipherDataBytes), 0);

    return decryptedResult;
}