#include <jni.h>
#include <cstdint>
#include <vector>
#include "KGMData.h"

#if defined(__ARM_NEON__) || defined(__aarch64__)
    #include <arm_neon.h>
#else
    #include "NEON_2_SSE.h"
#endif

const uint8x16_t andVec = vdupq_n_u8(0x0f);

thread_local uint8_t maskBytes[PRE_COMPUTED_TABLE_SIZE];
thread_local uint8_t ownKeyBytes[16 * 17];

void genMask(int startPos) {
    uint8x16_t chunk[16];
    for (int pos = 0; pos < PRE_COMPUTED_TABLE_SIZE * 16; pos += 16 * 16 * 16) {
        int i = startPos + pos;
        i >>= 4;
        int chunkPreTablePos = i % PRE_COMPUTED_TABLE_SIZE;
        for(int k = 0; k < 16; ++k) {
            chunk[k] = vld1q_u8(PRE_COMPUTED_TABLE + chunkPreTablePos + k * 16);
        }
        i >>= 8;
        //startPos > 69632才能这么用
        do {
            uint8x16_t xorData = vld1q_dup_u8(PRE_COMPUTED_TABLE + (i % PRE_COMPUTED_TABLE_SIZE));
            for (auto &k: chunk) {
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

extern "C"
JNIEXPORT int JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGMDecrypt_decrypt(JNIEnv *env, jclass clazz, jobject cipher_data_bytes, jint offset, jint bytes_read) {
    auto *cipherDataBytes = reinterpret_cast<uint8_t *>( env->GetDirectBufferAddress(cipher_data_bytes));
    int i = offset;
    int j = 0;
    int genMaskCounter = offset % 69632;
    int MaskV2Counter = offset % 272;
    int keyBytesIndexCounter = (offset >> 4) % 4352;

    for (; j + 16 <= bytes_read; i += 16 , j += 16) {
        //简化取模运算
        if (genMaskCounter == 69632) {
            genMask(i);
            genMaskCounter = 0;
        }
        if (MaskV2Counter == 272) {
            MaskV2Counter = 0;
        }
        if (j > 0 && (i & 15) == 0) {
            keyBytesIndexCounter++;
            if (keyBytesIndexCounter == 4352) {
                keyBytesIndexCounter = 0;
            }
        }
        uint8x16_t cipherDataBytesChunk = vld1q_u8(cipherDataBytes + j);

        uint8x16_t med8DataChunkOriginal = vld1q_u8(ownKeyBytes + MaskV2Counter);
        uint8x16_t med8DataChunkTemp;
        med8DataChunkOriginal = veorq_u8(med8DataChunkOriginal,cipherDataBytesChunk);
        med8DataChunkTemp = vandq_u8(med8DataChunkOriginal, andVec);
        med8DataChunkTemp = vshlq_n_u8(med8DataChunkTemp, 4);
        med8DataChunkOriginal = veorq_u8(med8DataChunkOriginal, med8DataChunkTemp);
        //int med8 = ownKeyBytes[i % 17] ^ cipherDataBytes[j];
        //med8 ^= (med8 & 0xf) << 4;

        uint8x16_t msk8DataChunkOriginal = vld1q_dup_u8(maskBytes + keyBytesIndexCounter);
        uint8x16_t msk8DataChunkTemp;
        uint8x16_t maskV2Data = vld1q_u8(MASK_V2_PRE_DEF + MaskV2Counter);
        msk8DataChunkOriginal = veorq_u8(msk8DataChunkOriginal,maskV2Data);
        msk8DataChunkTemp = vandq_u8(msk8DataChunkOriginal, andVec);
        msk8DataChunkTemp = vshlq_n_u8(msk8DataChunkTemp, 4);
        msk8DataChunkOriginal = veorq_u8(msk8DataChunkOriginal, msk8DataChunkTemp);
        //int msk8 = maskBytes[keyBytesIndexCounter] ^ MASK_V2_PRE_DEF[MaskV2Counter];
        //msk8 ^= (msk8 & 0xf) << 4;
        //cipherDataBytes[j] = (med8 ^ msk8);

        cipherDataBytesChunk = veorq_u8(msk8DataChunkOriginal,med8DataChunkOriginal);
        vst1q_u8(cipherDataBytes + j,cipherDataBytesChunk);

        genMaskCounter += 16;
        MaskV2Counter += 16;
    }
    for (; j < bytes_read; ++i, ++j) {
        int med8 = ownKeyBytes[i % 17] ^ cipherDataBytes[j];
        med8 ^= (med8 & 0xf) << 4;

        if (genMaskCounter == 69632) {
            genMask(i);
            genMaskCounter = 0;
        }
        if (MaskV2Counter == 272) {
            MaskV2Counter = 0;
        }
        if (j > 0 && (i & 15) == 0) {
            keyBytesIndexCounter++;
            if (keyBytesIndexCounter == 4352) {
                keyBytesIndexCounter = 0;
            }
        }

        int msk8 = maskBytes[keyBytesIndexCounter] ^ MASK_V2_PRE_DEF[MaskV2Counter];
        msk8 ^= (msk8 & 0xf) << 4;
        cipherDataBytes[j] = (med8 ^ msk8);

        genMaskCounter++;
        MaskV2Counter++;
    }

    return i;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGMDecrypt_init(JNIEnv *env, jclass clazz, jbyteArray own_key_bytes) {
    env->GetByteArrayRegion(own_key_bytes, 0, 17, reinterpret_cast<jbyte*>(ownKeyBytes));
    for (int i = 1; i < 16; i++) {
        memcpy(ownKeyBytes + i * 17, ownKeyBytes, 17);
    }
    memcpy(maskBytes, PRE_COMPUTED_TABLE, PRE_COMPUTED_TABLE_SIZE);
}