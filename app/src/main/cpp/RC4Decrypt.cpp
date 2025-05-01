#include <jni.h>
#include <cstdint>
#include <vector>

constexpr size_t S_BOX_SIZE = 256;
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

    for (int i = 1,j = 1; i < cipherDataLength; i++) {
        j = i & 0xff;
        cipherDataBytes[i-1] ^= sBoxBytes[ (sBoxBytes[j] + sBoxBytes [ ( sBoxBytes[j] + j ) & 0xff ] ) & 0xff ];
    }

    auto decryptedResult = env->NewByteArray(cipherDataLength);
    env ->SetByteArrayRegion( decryptedResult,0,cipherDataLength,reinterpret_cast<const jbyte*>(cipherDataBytes) );

    env->ReleaseByteArrayElements(sBox, reinterpret_cast<jbyte*>(sBoxBytes), 0);
    env->ReleaseByteArrayElements(cipherData, reinterpret_cast<jbyte*>(cipherDataBytes), 0);

    return decryptedResult;
}