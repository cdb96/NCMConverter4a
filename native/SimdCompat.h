// SIMD backend selection for the shared native core.
//
// This header only centralises the include logic that used to sit at the top of
// the historical Android RC4Decrypt.cpp / KGMDecrypt.cpp. It deliberately does
// not wrap the intrinsics in a new abstraction layer: the algorithms keep using
// the original NEON intrinsics, and the backend is picked by the preprocessor.
//
//   ARM / AArch64 -> <arm_neon.h>          native NEON
//   x86 / x86_64  -> NEON_2_SSE.h          Intel ARM_NEON_2_x86_SSE, SSE/SSSE3/SSE4
//
// That is why Android and Desktop can compile one and the same RC4Core.cpp /
// KGMCore.cpp without keeping separate NEON and SSE implementations.
//
// NCM_ENABLE_SIMD comes from native/CMakeLists.txt. Building with
// -DNCM_ENABLE_SIMD=OFF keeps the scalar paths, which exist purely so the
// self-test can prove SIMD and scalar builds agree byte for byte.
#ifndef NCM_SIMD_COMPAT_H
#define NCM_SIMD_COMPAT_H

#if defined(NCM_ENABLE_SIMD) && NCM_ENABLE_SIMD

    #if defined(__ARM_NEON__) || defined(__ARM_NEON) || defined(__aarch64__) || defined(_M_ARM64)

        #include <arm_neon.h>

        #define NCM_HAS_SIMD 1
        #define NCM_SIMD_NEON 1

    #elif defined(__x86_64__) || defined(__i386__) || defined(_M_X64) || defined(_M_IX86)

        // The historical project already compiled this code through NEON_2_SSE.
        // The compatibility/performance warnings are silenced here so the
        // Desktop build does not need Android-specific -Wno-deprecated flags.
        #ifndef NEON2SSE_DISABLE_PERFORMANCE_WARNING
        #define NEON2SSE_DISABLE_PERFORMANCE_WARNING
        #endif

        #include "third_party/NEON_2_SSE/NEON_2_SSE.h"

        #define NCM_HAS_SIMD 1
        #define NCM_SIMD_NEON2SSE 1

    #else

        // Unknown architecture: fall back to the scalar implementations.
        #define NCM_HAS_SIMD 0

    #endif

#else

    #define NCM_HAS_SIMD 0

#endif

#endif  // NCM_SIMD_COMPAT_H
