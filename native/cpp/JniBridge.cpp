// JNI bridge shared by the Android and the Desktop build.
//
// The exported symbol names match the Kotlin `actual object` declarations
// (RC4Decrypt / KGMDecrypt in com.cdb96.ncmconverter4a.jni), so both platforms
// can load the same library. All algorithm code lives in RC4Core.cpp and
// KGMCore.cpp behind the C ABI in NativeApi.h; this file only converts
// arguments and reports errors.
#include <jni.h>

#include <cstdint>

#include "NativeApi.h"

namespace {

void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

void throwOutOfMemory(JNIEnv* env) {
    jclass exceptionClass = env->FindClass("java/lang/OutOfMemoryError");
    if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, "native decrypt context allocation failed");
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
    jbyte* bytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(source, nullptr));
    if (bytes == nullptr) return;
    copy(reinterpret_cast<std::uint8_t*>(bytes), static_cast<int>(length));
    env->ReleasePrimitiveArrayCritical(source, bytes, JNI_ABORT);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_cdb96_ncmconverter4a_jni_RC4Decrypt_create(JNIEnv* env, jclass, jbyteArray key) {
    NcmRc4Context* context = nullptr;
    withByteArray(
        env, key, 1,
        "RC4 key must not be null or empty",
        "RC4 key must not be null or empty",
        [&](std::uint8_t* bytes, int length) { context = ncm_rc4_create(bytes, length); });
    if (context == nullptr && !env->ExceptionCheck()) throwOutOfMemory(env);
    return reinterpret_cast<jlong>(context);
}

JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_RC4Decrypt_decrypt(JNIEnv* env, jclass, jlong handle,
                                                      jbyteArray cipherData, jint bytesRead) {
    if (handle == 0) {
        throwIllegalArgument(env, "RC4 context must not be zero");
        return;
    }
    if (cipherData == nullptr) {
        throwIllegalArgument(env, "RC4 data must not be null");
        return;
    }
    const jsize dataLength = env->GetArrayLength(cipherData);
    if (bytesRead < 0 || bytesRead > dataLength) {
        throwIllegalArgument(env, "bytesRead is outside the byte array bounds");
        return;
    }
    jbyte* bytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(cipherData, nullptr));
    if (bytes == nullptr) return;
    ncm_rc4_decrypt(reinterpret_cast<NcmRc4Context*>(handle),
                    reinterpret_cast<std::uint8_t*>(bytes), static_cast<int>(bytesRead));
    env->ReleasePrimitiveArrayCritical(cipherData, bytes, 0);
}

JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_RC4Decrypt_destroy(JNIEnv*, jclass, jlong handle) {
    ncm_rc4_destroy(reinterpret_cast<NcmRc4Context*>(handle));
}

JNIEXPORT jlong JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGMDecrypt_create(JNIEnv* env, jclass, jbyteArray fileKeyBytes) {
    NcmKgmContext* context = nullptr;
    withByteArray(
        env, fileKeyBytes, 17,
        "KGM key must contain at least 17 bytes",
        "KGM key must contain at least 17 bytes",
        [&](std::uint8_t* bytes, int /*length*/) { context = ncm_kgm_create(bytes, 17); });
    if (context == nullptr && !env->ExceptionCheck()) throwOutOfMemory(env);
    return reinterpret_cast<jlong>(context);
}

JNIEXPORT jint JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGMDecrypt_decrypt(JNIEnv* env, jclass, jlong handle,
                                                     jbyteArray cipherData, jint offset, jint bytesRead) {
    if (handle == 0) {
        throwIllegalArgument(env, "KGM context must not be zero");
        return offset;
    }
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
    jbyte* bytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(cipherData, nullptr));
    if (bytes == nullptr) return offset;
    const int nextOffset = ncm_kgm_decrypt(reinterpret_cast<NcmKgmContext*>(handle),
                                          reinterpret_cast<std::uint8_t*>(bytes),
                                          static_cast<int>(offset), static_cast<int>(bytesRead));
    env->ReleasePrimitiveArrayCritical(cipherData, bytes, 0);
    return nextOffset;
}

JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGMDecrypt_destroy(JNIEnv*, jclass, jlong handle) {
    ncm_kgm_destroy(reinterpret_cast<NcmKgmContext*>(handle));
}

}  // extern "C"
