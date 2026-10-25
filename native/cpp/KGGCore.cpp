// KGG/QMC audio ciphers, ported from the Unlock Music based Kotlin implementation.
#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <utility>

#include "SimdCompat.h"

namespace {

constexpr std::uint32_t segmentSize = 5120;
constexpr std::uint32_t firstSegmentSize = 128;
constexpr std::uint32_t maskPeriod = 0x7FFF;

// Private, transient format shared only by prepare/decrypt in this library.
// It is copied with memcpy, so pinned byte arrays need no struct alignment.
// keySize == 0: a 32768-byte mask table follows the header.
// keySize > 300: key, 128 first-byte masks, then n-1+5120 RC4 bytes follow it.
struct Header {
    std::uint32_t keySize;
    std::uint32_t hash;
};

constexpr std::uint8_t staticBox[256] = {
    0x77, 0x48, 0x32, 0x73, 0xDE, 0xF2, 0xC0, 0xC8,
    0x95, 0xEC, 0x30, 0xB2, 0x51, 0xC3, 0xE1, 0xA0,
    0x9E, 0xE6, 0x9D, 0xCF, 0xFA, 0x7F, 0x14, 0xD1,
    0xCE, 0xB8, 0xDC, 0xC3, 0x4A, 0x67, 0x93, 0xD6,
    0x28, 0xC2, 0x91, 0x70, 0xCA, 0x8D, 0xA2, 0xA4,
    0xF0, 0x08, 0x61, 0x90, 0x7E, 0x6F, 0xA2, 0xE0,
    0xEB, 0xAE, 0x3E, 0xB6, 0x67, 0xC7, 0x92, 0xF4,
    0x91, 0xB5, 0xF6, 0x6C, 0x5E, 0x84, 0x40, 0xF7,
    0xF3, 0x1B, 0x02, 0x7F, 0xD5, 0xAB, 0x41, 0x89,
    0x28, 0xF4, 0x25, 0xCC, 0x52, 0x11, 0xAD, 0x43,
    0x68, 0xA6, 0x41, 0x8B, 0x84, 0xB5, 0xFF, 0x2C,
    0x92, 0x4A, 0x26, 0xD8, 0x47, 0x6A, 0x7C, 0x95,
    0x61, 0xCC, 0xE6, 0xCB, 0xBB, 0x3F, 0x47, 0x58,
    0x89, 0x75, 0xC3, 0x75, 0xA1, 0xD9, 0xAF, 0xCC,
    0x08, 0x73, 0x17, 0xDC, 0xAA, 0x9A, 0xA2, 0x16,
    0x41, 0xD8, 0xA2, 0x06, 0xC6, 0x8B, 0xFC, 0x66,
    0x34, 0x9F, 0xCF, 0x18, 0x23, 0xA0, 0x0A, 0x74,
    0xE7, 0x2B, 0x27, 0x70, 0x92, 0xE9, 0xAF, 0x37,
    0xE6, 0x8C, 0xA7, 0xBC, 0x62, 0x65, 0x9C, 0xC2,
    0x08, 0xC9, 0x88, 0xB3, 0xF3, 0x43, 0xAC, 0x74,
    0x2C, 0x0F, 0xD4, 0xAF, 0xA1, 0xC3, 0x01, 0x64,
    0x95, 0x4E, 0x48, 0x9F, 0xF4, 0x35, 0x78, 0x95,
    0x7A, 0x39, 0xD6, 0x6A, 0xA0, 0x6D, 0x40, 0xE8,
    0x4F, 0xA8, 0xEF, 0x11, 0x1D, 0xF3, 0x1B, 0x3F,
    0x3F, 0x07, 0xDD, 0x6F, 0x5B, 0x19, 0x30, 0x19,
    0xFB, 0xEF, 0x0E, 0x37, 0xF0, 0x0E, 0xCD, 0x16,
    0x49, 0xFE, 0x53, 0x47, 0x13, 0x1A, 0xBD, 0xA4,
    0xF1, 0x40, 0x19, 0x60, 0x0E, 0xED, 0x68, 0x09,
    0x06, 0x5F, 0x4D, 0xCF, 0x3D, 0x1A, 0xFE, 0x20,
    0x77, 0xE4, 0xD9, 0xDA, 0xF9, 0xA4, 0x2B, 0x76,
    0x1C, 0x71, 0xDB, 0x00, 0xBC, 0xFD, 0x0C, 0x6C,
    0xA5, 0x47, 0xF7, 0xF6, 0x00, 0x79, 0x4A, 0x11,
};

void throwJava(JNIEnv* env, const char* type, const char* message) {
    const auto exception = env->FindClass(type);
    if (exception != nullptr) env->ThrowNew(exception, message);
}

std::uint64_t stateSize(std::uint32_t n) {
    return sizeof(Header) + (n == 0 ? maskPeriod + 1ULL
        : 2ULL * n + firstSegmentSize + segmentSize - 1);
}

std::uint32_t segmentSkip(const Header& header, const std::uint8_t* key, std::uint64_t id) {
    const auto seed = key[id % header.keySize];
    // Kotlin Double.toLong saturates +Infinity to Long.MAX_VALUE. Converting
    // infinity with a C++ cast is undefined; explicitly preserve zero seeds.
    const auto index = seed == 0 ? std::numeric_limits<std::int64_t>::max()
        : static_cast<std::int64_t>(static_cast<double>(header.hash) /
            static_cast<double>((id + 1) * seed) * 100.0);
    return static_cast<std::uint32_t>(index % header.keySize);
}

// Unaligned vector loads support any stream/chunk position. Only the last
// partial 16-byte block uses scalar XOR; no mask division occurs per byte.
void xorStream(std::uint8_t* data, const std::uint8_t* masks, std::uint32_t length) {
    while (length >= 64) {
        const auto a = veorq_u8(vld1q_u8(data), vld1q_u8(masks));
        const auto b = veorq_u8(vld1q_u8(data + 16), vld1q_u8(masks + 16));
        const auto c = veorq_u8(vld1q_u8(data + 32), vld1q_u8(masks + 32));
        const auto d = veorq_u8(vld1q_u8(data + 48), vld1q_u8(masks + 48));
        vst1q_u8(data, a);
        vst1q_u8(data + 16, b);
        vst1q_u8(data + 32, c);
        vst1q_u8(data + 48, d);
        data += 64;
        masks += 64;
        length -= 64;
    }
    while (length >= 16) {
        vst1q_u8(data, veorq_u8(vld1q_u8(data), vld1q_u8(masks)));
        data += 16;
        masks += 16;
        length -= 16;
    }
    for (std::uint32_t i = 0; i < length; ++i) data[i] ^= masks[i];
}

void decrypt(const Header& header, const std::uint8_t* state,
             std::uint8_t* data, std::uint64_t offset, std::uint32_t length) {
    if (header.keySize == 0) {
        // Offset 0x7FFF is indexed directly, whereas subsequent offsets wrap
        // modulo 0x7FFF. This exceptional first period is part of the format.
        if (offset <= maskPeriod) {
            const auto count = std::min(length, static_cast<std::uint32_t>(maskPeriod + 1 - offset));
            xorStream(data, state + offset, count);
            data += count;
            offset += count;
            length -= count;
        }
        auto reduced = static_cast<std::uint32_t>(offset % maskPeriod);
        while (length > 0) {
            const auto count = std::min(length, maskPeriod - reduced);
            xorStream(data, state + reduced, count);
            data += count;
            length -= count;
            reduced = 0;
        }
        return;
    }

    const auto* firstMasks = state + header.keySize;
    const auto* stream = firstMasks + firstSegmentSize;
    if (offset < firstSegmentSize) {
        const auto count = std::min(length, static_cast<std::uint32_t>(firstSegmentSize - offset));
        xorStream(data, firstMasks + offset, count);
        data += count;
        offset += count;
        length -= count;
    }
    while (length > 0) {
        const auto within = static_cast<std::uint32_t>(offset % segmentSize);
        const auto count = std::min(length, segmentSize - within);
        xorStream(data, stream + within + segmentSkip(header, state, offset / segmentSize), count);
        data += count;
        offset += count;
        length -= count;
    }
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGGDecrypt_prepare(JNIEnv* env, jclass, jbyteArray keyArray) {
    if (keyArray == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "KGG key must not be null");
        return nullptr;
    }
    const auto n = static_cast<std::uint32_t>(env->GetArrayLength(keyArray));
    Header header{n > 300 ? n : 0, 1};
    const auto size = stateSize(header.keySize);
    if (size > static_cast<std::uint64_t>(std::numeric_limits<jsize>::max())) {
        throwJava(env, "java/lang/IllegalArgumentException", "KGG key is too long");
        return nullptr;
    }
    std::unique_ptr<std::uint8_t[]> bytes(new (std::nothrow) std::uint8_t[size]);
    std::unique_ptr<std::uint8_t[]> box(n > 300 ? new (std::nothrow) std::uint8_t[n] : nullptr);
    if (!bytes || (n > 300 && !box)) {
        throwJava(env, "java/lang/OutOfMemoryError", "KGG key schedule allocation failed");
        return nullptr;
    }
    auto* payload = bytes.get() + sizeof(Header);
    if (n > 300) {
        env->GetByteArrayRegion(keyArray, 0, n, reinterpret_cast<jbyte*>(payload));
        for (std::uint32_t i = 0; i < n; ++i) {
            const auto value = payload[i];
            if (value == 0) continue;
            const std::uint32_t next = header.hash * value;
            if (next <= header.hash) break;
            header.hash = next;
        }
        for (std::uint32_t i = 0; i < n; ++i) box[i] = static_cast<std::uint8_t>(i);
        std::uint32_t j = 0;
        for (std::uint32_t i = 0; i < n; ++i) {
            j = (j + box[i] + payload[i]) % n;
            std::swap(box[i], box[j]);
        }
        auto* firstMasks = payload + n;
        for (std::uint32_t i = 0; i < firstSegmentSize; ++i) {
            firstMasks[i] = payload[segmentSkip(header, payload, i)];
        }
        auto* stream = firstMasks + firstSegmentSize;
        j = 0;
        std::uint32_t k = 0;
        for (std::uint32_t i = 0; i < n - 1 + segmentSize; ++i) {
            if (++j == n) j = 0;
            k += box[j];
            if (k >= n) k -= n;
            std::swap(box[j], box[k]);
            std::uint32_t index = box[j] + box[k];
            if (index >= n) index -= n;
            stream[i] = box[index];
        }
    } else {
        std::uint8_t key[300]{};
        if (n > 0) env->GetByteArrayRegion(keyArray, 0, n, reinterpret_cast<jbyte*>(key));
        for (std::uint32_t i = 0; i <= maskPeriod; ++i) {
            if (n == 0) {
                payload[i] = staticBox[(i * i + 27) & 0xFF];
            } else {
                const auto index = (i * i + 71214) % n;
                const auto rotate = ((index & 7) + 4) % 8;
                // Preserve the format's shift/OR operation (both shifts use
                // rotate); replacing it with a conventional rotation differs.
                payload[i] = static_cast<std::uint8_t>((key[index] << rotate) | (key[index] >> rotate));
            }
        }
    }
    std::memcpy(bytes.get(), &header, sizeof(header));
    const auto result = env->NewByteArray(static_cast<jsize>(size));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(bytes.get()));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cdb96_ncmconverter4a_jni_KGGDecrypt_decrypt(JNIEnv* env, jclass,
    jbyteArray stateArray, jbyteArray dataArray, jlong offset, jint length) {
    if (stateArray == nullptr || dataArray == nullptr || offset < 0 || length < 0 ||
        length > env->GetArrayLength(dataArray) ||
        offset > std::numeric_limits<jlong>::max() - length ||
        env->GetArrayLength(stateArray) < static_cast<jsize>(sizeof(Header)) ||
        env->IsSameObject(stateArray, dataArray)) {
        throwJava(env, "java/lang/IllegalArgumentException", "invalid KGG stream range or state");
        return;
    }
    Header header{};
    env->GetByteArrayRegion(stateArray, 0, sizeof(header), reinterpret_cast<jbyte*>(&header));
    if ((header.keySize != 0 && header.keySize <= 300) ||
        stateSize(header.keySize) != static_cast<std::uint64_t>(env->GetArrayLength(stateArray))) {
        throwJava(env, "java/lang/IllegalArgumentException", "invalid KGG cipher state");
        return;
    }
    if (length == 0) return;
    const auto* state = static_cast<const std::uint8_t*>(env->GetPrimitiveArrayCritical(stateArray, nullptr));
    if (state == nullptr) return;
    auto* data = static_cast<std::uint8_t*>(env->GetPrimitiveArrayCritical(dataArray, nullptr));
    if (data != nullptr) {
        decrypt(header, state + sizeof(header), data, static_cast<std::uint64_t>(offset), length);
        env->ReleasePrimitiveArrayCritical(dataArray, data, 0);
    }
    env->ReleasePrimitiveArrayCritical(stateArray, const_cast<std::uint8_t*>(state), JNI_ABORT);
}
