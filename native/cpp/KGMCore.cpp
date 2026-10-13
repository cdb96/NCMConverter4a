// v3.4 KGM algorithm with per-conversion mask and file key.
#include <jni.h>
#include <cstdint>
#include <cstring>

#include "SimdCompat.h"
#include "ContextHandles.h"
#include "KgmTables.h"

using ncm::MASK_V2_PRE_DEF;
using ncm::PRE_COMPUTED_TABLE;
using ncm::PRE_COMPUTED_TABLE_SIZE;

struct NcmKgmContext {
    uint8_t maskBytes[PRE_COMPUTED_TABLE_SIZE]{};
    uint8_t fileKeyBytes[16 * 17]{};
};

namespace {

thread_local ncm::ContextHandles<NcmKgmContext> contexts;

void genMask(NcmKgmContext& context, int startPos) {
    auto& maskBytes = context.maskBytes;
    uint8x16_t chunk[16];
    for (int pos = 0; pos < PRE_COMPUTED_TABLE_SIZE * 16; pos += 16 * 16 * 16) {
        int i = startPos + pos;
        i >>= 4;
        int chunkPreTablePos = i % PRE_COMPUTED_TABLE_SIZE;
        for (int k = 0; k < 16; ++k) {
            chunk[k] = vld1q_u8(PRE_COMPUTED_TABLE + chunkPreTablePos + k * 16);
        }
        i >>= 8;
        do {
            uint8x16_t xorData = vld1q_dup_u8(PRE_COMPUTED_TABLE + (i % PRE_COMPUTED_TABLE_SIZE));
            for (auto& k : chunk) {
                k = veorq_u8(k, xorData);
            }
            i >>= 8;
        } while (i >= 0x11);
        int storePos = pos >> 4;
        for (int k = 0; k < 16; ++k) {
            vst1q_u8(maskBytes + storePos + k * 16, chunk[k]);
        }
    }
}

}  // namespace

// offset must be a multiple of 16; only the final chunk may end unaligned.
extern "C" JNIEXPORT jint JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGMDecrypt_decrypt(JNIEnv* env, jclass, jint handle,
                                                  jbyteArray cipher_data_bytes, jint offset, jint bytes_read) {
    const auto context = contexts.get(handle);
    if (context == nullptr || cipher_data_bytes == nullptr || offset < 0 || (offset & 15) != 0 ||
        bytes_read < 0 || bytes_read > env->GetArrayLength(cipher_data_bytes)) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
        if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, "invalid KGM context or byte array range");
        return offset;
    }
    if (bytes_read == 0) return offset;
    auto* cipherDataBytes = reinterpret_cast<uint8_t*>(env->GetPrimitiveArrayCritical(cipher_data_bytes, nullptr));
    if (cipherDataBytes == nullptr) return offset;
    auto& maskBytes = context->maskBytes;
    const auto& fileKeyBytes = context->fileKeyBytes;

    int i = offset;
    int j = 0;
    int genMaskCounter = offset % 69632;
    int fileKeyCounter = offset % 272;
    int maskBytesIndexCounter = (offset >> 4) % 4352;
    // Refresh the mask when a new buffer starts on a 69632-byte boundary.
    if (genMaskCounter == 0) {
        genMask(*context, i);
    }
    for (; j + 16 <= bytes_read; i += 16, j += 16) {
        // Avoid repeated modulo operations.
        if (genMaskCounter == 69632) {
            genMask(*context, i);
            genMaskCounter = 0;
        }
        if (fileKeyCounter == 272) {
            fileKeyCounter = 0;
        }
        if (j > 0 && (i & 15) == 0) {
            maskBytesIndexCounter++;
            if (maskBytesIndexCounter == 4352) {
                maskBytesIndexCounter = 0;
            }
        }
        uint8x16_t vCipher = vld1q_u8(cipherDataBytes + j);
        uint8x16_t vMed8 = vld1q_u8(fileKeyBytes + fileKeyCounter);
        uint8x16_t vMsk8 = vld1q_dup_u8(maskBytes + maskBytesIndexCounter);
        vMed8 = veorq_u8(vMed8, vCipher);
        vMed8 = veorq_u8(vMed8, vMsk8);
        vMsk8 = vshlq_n_u8(vMed8, 4);
        vMsk8 = veorq_u8(vMed8, vMsk8);
        vst1q_u8(cipherDataBytes + j, vMsk8);

        genMaskCounter += 16;
        fileKeyCounter += 16;
    }
    // The tail also needs to handle period boundaries and the mask index.
    for (; j < bytes_read; ++i, ++j) {
        if (genMaskCounter == 69632) {
            genMask(*context, i);
            genMaskCounter = 0;
        }
        if (fileKeyCounter == 272) {
            fileKeyCounter = 0;
        }
        if (j > 0 && (i & 15) == 0) {
            maskBytesIndexCounter++;
            if (maskBytesIndexCounter == 4352) {
                maskBytesIndexCounter = 0;
            }
        }

        int combined = fileKeyBytes[fileKeyCounter] ^ cipherDataBytes[j] ^ maskBytes[maskBytesIndexCounter];
        cipherDataBytes[j] = combined ^ (combined << 4);

        genMaskCounter++;
        fileKeyCounter++;
    }

    env->ReleasePrimitiveArrayCritical(cipher_data_bytes, cipherDataBytes, 0);
    return i;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGMDecrypt_create(JNIEnv* env, jclass, jbyteArray file_key_bytes) {
    if (file_key_bytes == nullptr || env->GetArrayLength(file_key_bytes) < 17) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
        if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, "KGM key must contain at least 17 bytes");
        return 0;
    }
    auto* context = ncm::createContext<NcmKgmContext>(env);
    if (context == nullptr) return 0;
    auto& maskBytes = context->maskBytes;
    auto& fileKeyBytes = context->fileKeyBytes;

    env->GetByteArrayRegion(file_key_bytes, 0, 17, reinterpret_cast<jbyte*>(fileKeyBytes));
    for (int i = 1; i < 16; i++) {
        std::memcpy(fileKeyBytes + i * 17, fileKeyBytes, 17);
    }
    std::memcpy(maskBytes, PRE_COMPUTED_TABLE, PRE_COMPUTED_TABLE_SIZE);
    for (int i = 0; i < 16 * 17; i++) {
        fileKeyBytes[i] ^= MASK_V2_PRE_DEF[i];
    }
    return contexts.add(env, context);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGMDecrypt_destroy(JNIEnv*, jclass, jint handle) {
    contexts.remove(handle);
}
