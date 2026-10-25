# Shared native core

One C++ implementation of the RC4, KGM and KGG decryption used by **both** the Android
app and the Desktop app. `androidApp` and `composeApp` compile the same three
runtime sources; only the way the library is loaded differs.

`cpp/` contains the runtime C++ code.
Tests, Windows title-bar code, and the external SIMD header are kept alongside it.

| File | Role |
| --- | --- |
| `cpp/SimdCompat.h` | Picks the SIMD backend: `<arm_neon.h>` on ARM, `NEON_2_SSE` on x86 |
| `cpp/RC4Core.cpp` | RC4 key schedule, keystream XOR and JNI entry points |
| `cpp/KGMCore.cpp` | KGM mask expansion, stream transform and JNI entry points |
| `cpp/KGGCore.cpp` | KGG/QMC RC4 key schedule, map/static masks, SIMD XOR and JNI entry points |
| `cpp/KgmTables.h` | Generated lookup tables (formerly `KGMData.h`) |
| `third_party/NEON_2_SSE/` | Intel ARM_NEON_2_x86_SSE submodule (x86 compatibility layer) |
| `tests/SelfTest.cpp` | JNI entry-point checks against golden vectors and byte-level references, no JVM needed |
| `tests/NativeLibraryLoadCheck.java` | Windows DLL load check without MinGW on `PATH` |
| `windows/NativeCaption.cpp` | Windows native title-bar support |

## SIMD

The algorithms are written in NEON intrinsics and `SimdCompat.h` routes them to a
backend per architecture — so Android and Desktop keep compiling the same three
`.cpp` files:

```text
RC4Core.cpp / KGMCore.cpp / KGGCore.cpp
          │
     SimdCompat.h
    ┌─────┴─────┐
 ARM/AArch64  x86/x86_64
    │             │
arm_neon.h   NEON_2_SSE.h
```

There is no scalar build variant to keep in sync: the intrinsics *are* the
implementation on both backends, with a scalar tail only for the final partial
block (RC4 restarts its 256-byte cycle per call, so callers pass 256-byte aligned
chunks; KGM requires a 16-byte aligned `offset`. The converters satisfy these
contracts by filling 256 KiB buffers). KGG supports arbitrary byte offsets and
chunk sizes: its SIMD XOR stops at each 128-byte first-segment, 5120-byte RC4
segment or 32767-byte mask-period boundary, then continues with the next mask.
There is also no
hand-written SSE/AVX2 implementation and no runtime CPUID dispatch, so a build for
an architecture that has neither backend fails at compile time instead of silently
producing a second code path.

Consumers:

- Android: `androidApp` builds `native/CMakeLists.txt` into `libncmc4a.so` and
  `System.loadLibrary("ncmc4a")` resolves it from the APK.
- Desktop: `:composeApp:nativeBuild` builds the library and bundles it as
  the `ncmc4a/<os>-<arch>/ncmc4a.<ext>` resource; `jni/NativeLibrary.kt` extracts
  it at runtime, so the Jar, the installed distribution and `desktopTest` all work.

RC4 and KGM keep key state in `thread_local` arrays, as in v3.4. Each file calls
`init` before decrypting on the same worker, with no coroutine suspension or
interleaved initialization until that file finishes. No context handles,
allocation, registry, or locks are needed. The Windows MSVC Release DLL links
the Visual C++ runtime statically and uses link-time optimization.

Release, RelWithDebInfo (used by Android release builds), and MinSizeRel compile
the core with `-O3` on Clang/GCC or `/O2` on MSVC. The self-test uses the same
optimization level. Debug builds retain their normal debug flags. Desktop Native
Image executables use `-O3` with PGO independently; that build does not recompile
this library.

KGG instead keeps an immutable key schedule in a private JVM byte array per
cipher. C++ prepares the RC4 stream or map/static mask table once, then pins the
state and output arrays for each SIMD XOR call. No native heap survives the call,
and ciphers can move between workers, run concurrently and decrypt out of order.
The Kotlin `QmcCipher` facade contains no decryption algorithm. Android release
and Desktop ProGuard rules preserve its JNI bridge; Native Image smoke checks
and headless conversion checks exercise the new entry points as well.

## Verify

The self-test calls the production JNI entry points with in-memory byte arrays.
It needs a C++ compiler and JDK headers, but does not start or link a JVM:

```bash
cmake -S native -B native/build -DNCM_BUILD_JNI=OFF -DNCM_JNI_INCLUDE_DIR="$JAVA_HOME/include"
cmake --build native/build
./native/build/ncm_core_selftest
```

It must print `ALL CHECKS PASSED`. On Windows a multi-config generator puts the
binary in `native/build/Release/ncm_core_selftest.exe`.

The checks compare the SIMD implementation against an independent byte-level
reference for RC4 and KGM, covering the 16, 272, 4352 and 69632 byte period
boundaries, 16-byte aligned KGM offsets and chunk sizes, and a final partial
chunk.

KGG golden vectors from the original byte-level algorithms cover all three
modes, the first 128 bytes, RC4 segment and mask period crossings, zero key bytes,
and offsets near `Long.MAX_VALUE`. Each vector is also decrypted with arbitrary
1/15/16/17/63/64/65-byte chunks and checked for writes beyond the given length.

Desktop unit tests, including the JNI golden vectors and chunk boundary checks:

```bash
./gradlew :composeApp:desktopTest
```

The QMC tests compare the production JNI implementation with the original
Kotlin byte-level references and check streaming, random access, concurrent
instances, shared-cipher reads, key snapshots and invalid JNI ranges/states.
