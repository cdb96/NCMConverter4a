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

#if defined(__aarch64__)
void genMask(int startPos) {
    for (int pos = 0; pos < PRE_COMPUTED_TABLE_SIZE * 16; pos += 16 * 64) {
        int i = startPos + pos;
        i >>= 4;
        //int med8 = PRE_COMPUTED_TABLE[i % PRE_COMPUTED_TABLE_SIZE];
        uint8x16x4_t tableDataChunk = vld1q_u8_x4(
                PRE_COMPUTED_TABLE + (i % PRE_COMPUTED_TABLE_SIZE));
        i >>= 8;
        while (i >= 0x11) {
            uint8x16_t xorData0 = vdupq_n_u8(PRE_COMPUTED_TABLE[i % 4352]);
            tableDataChunk.val[0] = veorq_u8(tableDataChunk.val[0], xorData0);
            tableDataChunk.val[1] = veorq_u8(tableDataChunk.val[1], xorData0);
            tableDataChunk.val[2] = veorq_u8(tableDataChunk.val[2], xorData0);
            tableDataChunk.val[3] = veorq_u8(tableDataChunk.val[3], xorData0);
            i >>= 8;
        }
        int storePos = pos >> 4;
        vst1q_u8_x4(maskBytes + storePos, tableDataChunk);
    }
}
#elif defined(__x86_64__)
void genMask(int startPos) {
    for (int pos = 0; pos < PRE_COMPUTED_TABLE_SIZE * 16; pos += 16 * 16) {
        int i = startPos + pos;
        i >>= 4;
        uint8x16_t tableDataChunk = vld1q_u8(
                PRE_COMPUTED_TABLE + (i % PRE_COMPUTED_TABLE_SIZE));
        i >>= 8;
        while (i >= 0x11) {
            uint8x16_t xorData0 = vdupq_n_u8(PRE_COMPUTED_TABLE[i % 4352]);
            tableDataChunk = veorq_u8(tableDataChunk,xorData0);
            i >>= 8;
        }
        int storePos = pos >> 4;
        vst1q_u8(maskBytes + storePos, tableDataChunk);
    }
}
#endif

extern "C"
JNIEXPORT int JNICALL
Java_com_cdb96_ncmconverter4a_JNIUtil_KGMDecrypt_decrypt(JNIEnv *env, jobject , jobject cipher_data_bytes, jint offset, jint bytes_read) {
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
Java_com_cdb96_ncmconverter4a_JNIUtil_KGMDecrypt_init(JNIEnv *env, jclass clazz, jbyteArray own_key_bytes) {
    env->GetByteArrayRegion(own_key_bytes, 0, 17, reinterpret_cast<jbyte*>(ownKeyBytes));
    for (int i = 1; i < 16; i++) {
        memcpy(ownKeyBytes + i * 17, ownKeyBytes, 17);
    }
    memcpy(maskBytes, PRE_COMPUTED_TABLE, PRE_COMPUTED_TABLE_SIZE);
}