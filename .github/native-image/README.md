# Native Image portability checks

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
- The headless conversion workload used for PGO training.

The Linux build script checks the final tar archive in the same way under Xvfb.
Linux still needs the host's X11/OpenGL/font libraries and fonts; removing JDK
environment variables does not simulate a completely clean operating system.

When clearing Windows environment overrides, use the PowerShell environment
provider to remove them. Passing `$null` to `Environment.SetEnvironmentVariable`
can leave an empty value under PowerShell 7/.NET 9+, which differs from Windows
PowerShell 5.1. Remove the variable before starting Skiko: its environment
override takes precedence over the system property even when empty.

Keep PGO training headless. Desktop tracing and archive checks are separate from
the instrumented conversion workload and must not be removed to speed up PGO.

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
