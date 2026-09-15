# Shared native core

One C++ implementation of the RC4 and KGM decryption used by **both** the Android
app and the Desktop app. `nativeLib` (Android) and `composeApp` (Desktop) compile
the same three sources; only the way the library is loaded differs.

| File | Role |
| --- | --- |
| `NativeApi.h` | C ABI, free of JNI types, so the core is testable without a JVM |
| `SimdCompat.h` | Picks the SIMD backend: `<arm_neon.h>` on ARM, `NEON_2_SSE` on x86 |
| `RC4Core.cpp` | RC4 key schedule + keystream XOR |
| `KGMCore.cpp` | KGM mask expansion and stream transform |
| `KgmTables.h` | Generated lookup tables (formerly `KGMData.h`) |
| `third_party/NEON_2_SSE/` | Intel ARM_NEON_2_x86_SSE submodule (x86 compatibility layer) |
| `JniBridge.cpp` | JNI glue for `com.cdb96.ncmconverter4a.jni.{RC4Decrypt,KGMDecrypt}` |
| `selftest/SelfTest.cpp` | Golden vector + byte-level reference comparison, no JVM needed |
| `check/NativeCoreCheck.java` | JVM-level checks (JNI symbol names, boundaries, concurrency) |

## SIMD

The algorithms are written once, in NEON intrinsics, and `SimdCompat.h` routes them
to a backend per architecture — so Android and Desktop keep compiling the same two
`.cpp` files:

```text
RC4Core.cpp / KGMCore.cpp
          │
     SimdCompat.h
    ┌─────┴─────┐
 ARM/AArch64  x86/x86_64
    │             │
arm_neon.h   NEON_2_SSE.h
```

`NCM_ENABLE_SIMD` (default `ON`) selects the backend. `OFF` keeps the scalar
implementations, which exist so that both builds can be compared byte for byte;
there is no hand-written SSE/AVX2 implementation and no runtime CPUID dispatch.

Consumers:

- Android: `nativeLib/src/main/cpp/CMakeLists.txt` builds `libncmc4a.so` and
  `System.loadLibrary("ncmc4a")` resolves it from the APK.
- Desktop: `:composeApp:nativeTestLibrary` builds the library and bundles it as
  the `ncmc4a/<os>-<arch>/ncmc4a.<ext>` resource; `jni/NativeLibrary.kt` extracts
  it at runtime, so the Jar, the installed distribution and `desktopTest` all work.

## Verify

The self-test needs only a C++ compiler. Run it in **both** builds: the SIMD and the
scalar backend must agree byte for byte.

```bash
# SIMD backend (NEON on ARM, NEON_2_SSE on x86)
cmake -S native -B native/build-simd -DNCM_BUILD_JNI=OFF -DNCM_ENABLE_SIMD=ON
cmake --build native/build-simd
./native/build-simd/ncm_core_selftest

# Scalar backend
cmake -S native -B native/build-scalar -DNCM_BUILD_JNI=OFF -DNCM_ENABLE_SIMD=OFF
cmake --build native/build-scalar
./native/build-scalar/ncm_core_selftest
```

Both must print `ALL CHECKS PASSED`. On Windows a multi-config generator puts the
binary in `native/build-simd/Release/ncm_core_selftest.exe`.

JVM-level check (needs a JDK as well):

```bash
native/check.sh
```

Desktop unit tests, including the parity check against the retired Vector API
implementation:

```bash
./gradlew :composeApp:desktopTest
```
