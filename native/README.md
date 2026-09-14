# Shared native core

One C++ implementation of the RC4 and KGM decryption used by **both** the Android
app and the Desktop app. `nativeLib` (Android) and `composeApp` (Desktop) compile
the same three sources; only the way the library is loaded differs.

| File | Role |
| --- | --- |
| `NativeApi.h` | C ABI, free of JNI types, so the core is testable without a JVM |
| `RC4Core.cpp` | RC4 key schedule + keystream XOR |
| `KGMCore.cpp` | KGM mask expansion and stream transform |
| `KgmTables.h` | Generated lookup tables (formerly `KGMData.h`) |
| `JniBridge.cpp` | JNI glue for `com.cdb96.ncmconverter4a.jni.{RC4Decrypt,KGMDecrypt}` |
| `selftest/SelfTest.cpp` | Golden vector + byte-level reference comparison, no JVM needed |
| `check/NativeCoreCheck.java` | JVM-level checks (JNI symbol names, boundaries, concurrency) |

Consumers:

- Android: `nativeLib/src/main/cpp/CMakeLists.txt` builds `libncmc4a.so` and
  `System.loadLibrary("ncmc4a")` resolves it from the APK.
- Desktop: `:composeApp:nativeTestLibrary` builds the library and bundles it as
  the `ncmc4a/<os>-<arch>/ncmc4a.<ext>` resource; `jni/NativeLibrary.kt` extracts
  it at runtime, so the Jar, the installed distribution and `desktopTest` all work.

## Verify

Self-test (needs only a C++ compiler):

```bash
g++ -std=c++17 -O2 -Inative -o /tmp/ncm_core_selftest \
    native/selftest/SelfTest.cpp native/RC4Core.cpp native/KGMCore.cpp
/tmp/ncm_core_selftest
```

or through cmake:

```bash
cmake -S native -B native/build -DNCM_BUILD_JNI=OFF
cmake --build native/build
./native/build/selftest/ncm_core_selftest
```

JVM-level check (needs a JDK as well):

```bash
native/check.sh
```

Desktop unit tests, including the parity check against the retired Vector API
implementation:

```bash
./gradlew :composeApp:desktopTest
```
