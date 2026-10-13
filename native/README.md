# Shared native core

One C++ implementation of the RC4 and KGM decryption used by **both** the Android
app and the Desktop app. `androidApp` and `composeApp` compile the same two
runtime sources; only the way the library is loaded differs.

`cpp/` contains the runtime C++ code.
Tests, Windows title-bar code, and the external SIMD header are kept alongside it.

| File | Role |
| --- | --- |
| `cpp/SimdCompat.h` | Picks the SIMD backend: `<arm_neon.h>` on ARM, `NEON_2_SSE` on x86 |
| `cpp/ContextMemory.h` | Per-conversion context allocation and release |
| `cpp/ContextHandles.h` | Thread-safe integer handles and native context lifetime |
| `cpp/RC4Core.cpp` | RC4 key schedule, keystream XOR and JNI entry points |
| `cpp/KGMCore.cpp` | KGM mask expansion, stream transform and JNI entry points |
| `cpp/KgmTables.h` | Generated lookup tables (formerly `KGMData.h`) |
| `third_party/NEON_2_SSE/` | Intel ARM_NEON_2_x86_SSE submodule (x86 compatibility layer) |
| `tests/SelfTest.cpp` | JNI entry-point checks against golden vectors and byte-level references, no JVM needed |
| `tests/NativeLibraryLoadCheck.java` | Windows DLL load check without MinGW on `PATH` |
| `windows/NativeCaption.cpp` | Windows native title-bar support |

## SIMD

The algorithms are written in NEON intrinsics and `SimdCompat.h` routes them to a
backend per architecture — so Android and Desktop keep compiling the same two
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

There is no scalar build variant to keep in sync: the intrinsics *are* the
implementation on both backends, with a scalar tail only for the final partial
block (RC4 restarts its 256-byte cycle per call, so callers pass 256-byte aligned
chunks; KGM requires a 16-byte aligned `offset`. The converters satisfy these
contracts by filling 256 KiB buffers). There is also no
hand-written SSE/AVX2 implementation and no runtime CPUID dispatch, so a build for
an architecture that has neither backend fails at compile time instead of silently
producing a second code path.

Consumers:

- Android: `androidApp` builds `native/CMakeLists.txt` into `libncmc4a.so` and
  `System.loadLibrary("ncmc4a")` resolves it from the APK.
- Desktop: `:composeApp:nativeBuild` builds the library and bundles it as
  the `ncmc4a/<os>-<arch>/ncmc4a.<ext>` resource; `jni/NativeLibrary.kt` extracts
  it at runtime, so the Jar, the installed distribution and `desktopTest` all work.

Each conversion creates its own RC4 or KGM context, passes that handle on each
decrypt call, and destroys it in `finally`. Native key state does not depend on
which Java worker thread runs a call. Both APIs use Kotlin `Int` / JNI `jint`
IDs backed by a native registry, so pointer width stays internal to C++. The
registry keeps contexts alive during active calls and rejects stale handles.
The Windows MSVC Release DLL links the
Visual C++ runtime statically and uses link-time optimization.

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

Desktop unit tests, including the JNI golden vectors and chunk boundary checks:

```bash
./gradlew :composeApp:desktopTest
```
