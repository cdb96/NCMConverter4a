#!/usr/bin/env bash
# Run under Xvfb (or an existing X11 display) with Oracle GraalVM 25 on PATH.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
: "${JAVA_HOME:?JAVA_HOME must point at GraalVM 25}"
if [[ "$(uname -s)" != Linux ]]; then
    echo 'This Native Image build requires Linux.' >&2
    exit 1
fi
if [[ -z "${DISPLAY:-}" ]]; then
    echo 'An X11 display is required; run this script with xvfb-run -a.' >&2
    exit 1
fi

shopt -s nullglob
jars=("$root"/composeApp/build/compose/jars/*-release.jar)
if [[ ${#jars[@]} != 1 ]]; then
    echo 'Expected exactly one release UberJar; build packageReleaseUberJarForCurrentOS in a clean checkout.' >&2
    exit 1
fi
jar="${jars[0]}"
build="$root/build/native-image/linux"
metadata="$build/metadata"
classes="$build/trace-classes"
appdata="$build/trace-appdata"
mkdir -p "$metadata" "$classes" "$appdata/NCMConverter4a" "$build/pgo"

# Never read or overwrite the user's desktop settings during metadata tracing.
export APPDATA="$appdata"
# Mesa's software GL driver supports JVM metadata tracing under Xvfb.
export LIBGL_ALWAYS_SOFTWARE=true
# Skiko rejects llvmpipe. Mesa's softpipe still exercises the OpenGL backend.
export GALLIUM_DRIVER=softpipe
unset SKIKO_RENDER_API
if command -v glxinfo >/dev/null 2>&1; then glxinfo -B; fi
"$JAVA_HOME/bin/javac" -cp "$jar" -d "$classes" \
    .github/native-image/NativeImageMain.java .github/native-image/PgoTraining.java
classpath="$classes:$jar"

for gpu in true false; do
    printf 'gpuRendering=%s\n' "$gpu" > "$appdata/NCMConverter4a/rendering.properties"
    agent_mode=config-output-dir
    if [[ "$gpu" == false ]]; then agent_mode=config-merge-dir; fi
    timeout 90s "$JAVA_HOME/bin/java" \
        "-agentlib:native-image-agent=$agent_mode=$metadata,config-write-period-secs=5,config-write-initial-delay-secs=1" \
        --enable-native-access=ALL-UNNAMED -cp "$classpath" NativeImageMain --trace-desktop \
        2>&1 | tee "$build/trace-$gpu.log"
done
test -s "$metadata/reachability-metadata.json"

cd "$build"
args=(--no-fallback --enable-native-access=ALL-UNNAMED -march=compatibility
    "-H:ConfigurationFileDirectories=$metadata,$root/.github/native-image/metadata,$root/.github/native-image/metadata-linux"
    -cp "$classpath")
"$JAVA_HOME/bin/native-image" "${args[@]}" --pgo-instrument NativeImageMain NCMConverter4a-instrumented

# AWT still uses shared libraries. Match its java.home-relative support layout.
if [[ ! -f libjawt.so ]]; then cp -p "$JAVA_HOME/lib/libjawt.so" libjawt.so; fi
mkdir -p lib
cp -p libjawt.so lib/libjawt.so
for file in "$JAVA_HOME"/lib/fontconfig*; do
    if [[ -f "$file" ]]; then cp -p "$file" lib/; fi
done

# Train the conversion code once; this does not open windows or initialize AWT.
if [[ -e default.iprof ]]; then
    echo 'Unexpected PGO profile already exists; use a clean build directory.' >&2
    exit 1
fi
timeout 90s ./NCMConverter4a-instrumented --pgo-train 2>&1 | tee pgo-train.log
test -s default.iprof
profile="$build/pgo/conversion.iprof"
mv default.iprof "$profile"
profile_option="--pgo=$profile"
"$JAVA_HOME/bin/native-image" "${args[@]}" "$profile_option" NativeImageMain NCMConverter4a

# Stage an allowlisted runtime. It contains no JDK, classpath,
# build metadata, instrumented binary or profiles.
runtime="$(mktemp -d "$build/runtime.XXXXXX")"
mkdir -p "$runtime/NCMConverter4a/lib"
cp -p NCMConverter4a "$runtime/NCMConverter4a/"
cp -a ./*.so "$runtime/NCMConverter4a/"
cp -a lib/. "$runtime/NCMConverter4a/lib/"
cd "$runtime/NCMConverter4a"
timeout 90s ./NCMConverter4a --native-smoke
archive="$root/build/native-image/NCMConverter4a-linux-native-image.tar.gz"
tar -czf "$archive" -C "$runtime" NCMConverter4a
ls -lh "$archive"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf 'Linux Native Image archive: %s bytes\n' "$(stat -c %s "$archive")" >> "$GITHUB_STEP_SUMMARY"
fi
