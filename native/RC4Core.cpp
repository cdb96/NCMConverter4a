// RC4 core shared by the Android and Desktop builds.
//
// The NCM keystream is a 256 byte cycle XORed over the payload. The only
// requirement on callers is that every chunk except the last one is a multiple
// of 256 bytes, which is why decryptAt() can restart the cycle at byte 0 for
// each chunk. The last chunk is shorter than 256 bytes, so it stays aligned by
// construction (see NativeApi.cpp).
#include "NativeApi.h"

#include <array>
#include <cstdint>
#include <numeric>

namespace ncm {
namespace {

thread_local std::array<std::uint8_t, 256> keyStreamBytes{};

void applyKeyStream(std::uint8_t* data, int length) {
    for (int i = 0; i < length; i++) {
        data[i] ^= keyStreamBytes[static_cast<std::size_t>(i) & 0xFFu];
    }
}

}  // namespace

void rc4Init(const std::uint8_t* key, int keyLength) {
    if (key == nullptr || keyLength <= 0) return;

    std::array<std::uint8_t, 256> sBox{};
    std::iota(sBox.begin(), sBox.end(), std::uint8_t{0});

    int j = 0;
    for (int i = 0; i < 256; ++i) {
        j = (j + sBox[static_cast<std::size_t>(i)] + key[i % keyLength]) & 0xFF;
        std::swap(sBox[static_cast<std::size_t>(i)], sBox[static_cast<std::size_t>(j)]);
    }

    for (int k = 1; k < 256; ++k) {
        keyStreamBytes[static_cast<std::size_t>(k - 1)] =
            sBox[(sBox[static_cast<std::size_t>(k)] +
                  sBox[(sBox[static_cast<std::size_t>(k)] + k) & 0xFF]) & 0xFF];
    }
    keyStreamBytes[255] = sBox[(sBox[0] + sBox[(sBox[0] + 0) & 0xFF]) & 0xFF];
}

void rc4DecryptAt(std::uint8_t* data, int length) {
    if (data == nullptr || length <= 0) return;
    applyKeyStream(data, length);
}

}  // namespace ncm

void ncm_rc4_init(const std::uint8_t* key, int key_len) {
    ncm::rc4Init(key, key_len);
}

void ncm_rc4_decrypt(uint8_t* data, int length) {
    ncm::rc4DecryptAt(data, length);
}
