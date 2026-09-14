// JNI bridge shared by the Android and the Desktop build.
//
// The exported symbol names match the Kotlin `actual object` declarations
// (RC4Decrypt / KGMDecrypt in com.cdb96.ncmconverter4a.jni), so both platforms
// can load the same library. All algorithm code lives in RC4Core.cpp and
// KGMCore.cpp behind the C ABI in NativeApi.h; this file only converts
// arguments and reports errors.
#include <jni.h>

#include <cstdint>
#include <cstring>

#include "NativeApi.h"

namespace {

void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

/** Copies a non-empty jbyteArray into a local buffer and runs `copy`. */
template <typename Fn>
void withByteArray(JNIEnv* env, jbyteArray source, int minimumLength, const char* nullMessage,
                   const char* shortMessage, Fn copy) {
    if (source == nullptr) {
        throwIllegalArgument(env, nullMessage);
        return;
    }
    const jsize length = env->GetArrayLength(source);
    if (length < minimumLength) {
        throwIllegalArgument(env, shortMessage);
        return;
    }
    jbyte* bytes = env->GetByteArrayElements(source, nullptr);
    if (bytes == nullptr) return;
    copy(reinterpret_cast<std::uint8_t*>(bytes), static_cast<int>(length));
    env->ReleaseByteArrayElements(source, bytes, 0);
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_RC4Decrypt_ksa(JNIEnv* env, jclass, jbyteArray key) {
    withByteArray(
        env, key, 1,
        "RC4 key must not be null or empty",
        "RC4 key must not be null or empty",
        [](std::uint8_t* bytes, int length) { ncm_rc4_init(bytes, length); });
}

JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_RC4Decrypt_prgaDecryptByteArray(JNIEnv* env, jclass,
                                                                 jbyteArray cipherData,
                                                                 jint bytesRead) {
    if (cipherData == nullptr) {
        throwIllegalArgument(env, "RC4 data must not be null");
        return;
    }
    const jsize dataLength = env->GetArrayLength(cipherData);
    if (bytesRead < 0 || bytesRead > dataLength) {
        throwIllegalArgument(env, "bytesRead is outside the byte array bounds");
        return;
    }
    jbyte* bytes = env->GetByteArrayElements(cipherData, nullptr);
    if (bytes == nullptr) return;
    ncm_rc4_decrypt(reinterpret_cast<std::uint8_t*>(bytes), static_cast<int>(bytesRead));
    env->ReleaseByteArrayElements(cipherData, bytes, 0);
}

JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGMDecrypt_init(JNIEnv* env, jclass, jbyteArray fileKeyBytes) {
    withByteArray(
        env, fileKeyBytes, 17,
        "KGM key must contain at least 17 bytes",
        "KGM key must contain at least 17 bytes",
        [](std::uint8_t* bytes, int /*length*/) { ncm_kgm_init(bytes, 17); });
}

JNIEXPORT jint JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGMDecrypt_decrypt(JNIEnv* env, jclass, jbyteArray cipherData,
                                                     jint offset, jint bytesRead) {
    if (cipherData == nullptr) {
        throwIllegalArgument(env, "KGM data must not be null");
        return offset;
    }
    const jsize dataLength = env->GetArrayLength(cipherData);
    // `offset` is the absolute position in the KGM stream; the array holds only
    // the current chunk, so it is only validated against zero.
    if (offset < 0 || bytesRead < 0 || bytesRead > dataLength) {
        throwIllegalArgument(env, "invalid KGM byte array range");
        return offset;
    }
    jbyte* bytes = env->GetByteArrayElements(cipherData, nullptr);
    if (bytes == nullptr) return offset;
    const int nextOffset = ncm_kgm_decrypt(reinterpret_cast<std::uint8_t*>(bytes),
                                          static_cast<int>(offset), static_cast<int>(bytesRead));
    env->ReleaseByteArrayElements(cipherData, bytes, 0);
    return nextOffset;
}

}  // extern "C"
