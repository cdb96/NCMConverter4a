# Native Image portability checks

Windows and Linux build the main executable with `-O3` and a PGO profile trained
from the current conversion code. First build with `--pgo-instrument`, run the
headless NCM/KGM/KGG workload, then build the final executable with
`--pgo=<conversion.iprof> -O3`. Instrumentation controls its own optimization level;
do not pass `-O3` in the shared arguments used for the instrumented build.
Desktop metadata tracing and all runtime/conversion checks remain enabled.
The separately compiled SIMD decryption library favors speed: `-O3` on
Clang/GCC and `/O2` with Release LTO on MSVC (see `native/CMakeLists.txt`).

## Windows ICU data

The Windows Native Image job runs `TrimSkikoIcu.java` on a separate copy of the
release UberJar before metadata tracing and both Native Image builds. The
published JVM UberJar and dependency caches keep their original ICU data.
The filter is pinned to the audited Skiko 0.150.1 DLL and ICU 74 input/output
SHA-256 values and fails if any changes. It needs only JDK 25, without an
external ICU tool or a downloaded data package.

The filter removes collation, currency, language/region display names, unit and
time-zone data. It preserves every break rule/dictionary, root locale resource,
Unicode property/normalization/case table and converter. Retained item payloads
are copied verbatim. ICU falls from 10,468,208 to 5,758,880 bytes. Local Windows
O3 + PGO validation reduced the EXE by 4.50 MiB and the ZIP by 1.24 MiB;
72 multilingual layout/image comparisons and all packaged runtime checks passed.
CI repeats those comparisons using `SkikoIcuRenderCheck.java`, with separate
JVMs/caches for the original and trimmed JAR and verified extracted data.
This is a Skiko text-data policy, not a general-purpose ICU distribution.

`NativeImageMain` configures a private Windows cache under
`user.home/.ncmconverter4a/skiko/icu-<content-hash-prefix>` before loading Skiko.
An explicit `skiko.data.path` selects the parent of that content-version
directory. Skiko's own DLL checksum directory remains underneath it. This is
necessary because Skiko's default shared cache keys only on the unchanged DLL,
not the ICU payload. Headless PGO and JNI-only checks do not read or hash ICU.
Archive checks compare the extracted data against the embedded checksum and
verify the versioned cache; ordinary startup checks also verify the private
default cache without a property override. Updating Skiko requires reviewing
the filter and repeating Unicode rendering checks before changing the hashes.

## Runtime verification

The Windows build must embed `icudtl.dat`, `skiko-windows-x64.dll`, its
`.sha256` sidecar and the shared `ncmc4a` DLL. Linux must embed the Skiko `.so`,
its `.sha256` sidecar and the shared `ncmc4a` `.so`. Keep these in the platform
metadata even when the tracing agent also records them: an existing Skiko cache
can prevent the loader from opening its classpath resources during tracing.

Linux also needs explicit JNI access to `java.lang.Thread.yield()`. OpenJDK's
[`awtJNI_ThreadYield`](https://github.com/openjdk/jdk25u/blob/master/src/java.desktop/unix/native/libawt_xawt/awt/awt_util.c)
looks up both the class and static method from native code when the X11 event
poll timeout is zero. A trace may miss this timing-dependent branch. Failed
lookup returns before the AWT lock is reacquired, so a subsequent
`IllegalMonitorStateException` can be secondary to the missing JNI registration.

`NativeImageSmoke.verifyResources()` checks the embedded resources and Skiko
checksum even with a warm cache. Both tracing passes use separate user homes,
temporary directories and Skiko caches. Tracing finishes only after a Compose
frame has been rendered; a visible empty window or a timer expiring is not a
successful startup.

Windows CI runs `VerifyNativeImageWindows.ps1 -CheckRuntime` on the final ZIP.
It extracts to a new path containing Chinese characters and a space, clears Java
environment overrides, restricts PATH to Windows directories, and uses a new
profile and cache for each check:

- Embedded resources, Skiko loading and the decryption JNI golden vector.
- First startup without settings, including rendering and file drop mapping.
- First startup with software rendering selected.
- Software rendering after removing an empty inherited environment override.
- Music, database and directory pickers.
- The headless NCM/KGM/KGG conversion workload.

The Linux build script checks the final tar archive in the same way under Xvfb.
Linux still needs the host's X11/OpenGL/font libraries and fonts; removing JDK
environment variables does not simulate a completely clean operating system.

When clearing Windows environment overrides, use the PowerShell environment
provider to remove them. Passing `$null` to `Environment.SetEnvironmentVariable`
can leave an empty value under PowerShell 7/.NET 9+, which differs from Windows
PowerShell 5.1. Remove the variable before starting Skiko: its environment
override takes precedence over the system property even when empty.

Keep PGO training and conversion checks headless. `--pgo-train` runs the conversion
workload without AWT or Compose; it collects a profile only in the instrumented
build and also verifies the final executable. Desktop tracing and archive checks
are separate and must remain enabled.

`native-portability.yml` downloads an already-built Windows Native Image into a
separate [GitHub-hosted VM](https://docs.github.com/en/actions/concepts/runners/github-hosted-runners).
It does not build the application or restore build
caches. `VerifyNativeImageIsolation.ps1` first launches the ordinary entry point
with new settings, home and temporary directories, no Java in PATH, and Skiko's
default cache selection. It checks a responsive main window and graceful exit,
records loaded DLL paths and hashes, requires the bundled AWT/CRT libraries,
and rejects DLLs outside the archive, new profile or Windows directories. The
embedded ICU data must appear in the initially empty profile. It then runs the
six packaged runtime checks, including real Compose frames and file pickers.

This tests first launch without application state or developer runtime files.
The VM still has the runner image's preinstalled software and Windows drivers;
the DLL audit checks that this startup does not load development runtimes from
them. AppContainer is a separate security compatibility test: its restrictions
can deny Java's volume information query before a temporary file is created,
so an AppContainer failure alone does not establish a missing portable resource.
