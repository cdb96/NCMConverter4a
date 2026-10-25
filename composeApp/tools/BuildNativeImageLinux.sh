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
mkdir -p "$metadata" "$classes" "$build/pgo"

# Mesa's software GL driver supports JVM metadata tracing under Xvfb.
export LIBGL_ALWAYS_SOFTWARE=true
# Skiko rejects llvmpipe. Mesa's softpipe still exercises the OpenGL backend.
export GALLIUM_DRIVER=softpipe
unset SKIKO_RENDER_API
if command -v glxinfo >/dev/null 2>&1; then glxinfo -B; fi
"$JAVA_HOME/bin/javac" -cp "$jar" -d "$classes" \
    .github/native-image/NativeImageMain.java .github/native-image/NativeImageSmoke.java .github/native-image/PgoTraining.java
classpath="$classes:$jar"

for gpu in true false; do
    # Never read or overwrite the user's settings or reuse an extracted library.
    trace_profile="$(mktemp -d "$build/trace-$gpu.XXXXXX")"
    mkdir -p "$trace_profile/home" "$trace_profile/temp" "$trace_profile/skiko" "$trace_profile/appdata/NCMConverter4a"
    export APPDATA="$trace_profile/appdata"
    printf 'gpuRendering=%s\n' "$gpu" > "$APPDATA/NCMConverter4a/rendering.properties"
    agent_mode=config-output-dir
    if [[ "$gpu" == false ]]; then agent_mode=config-merge-dir; fi
    timeout 90s "$JAVA_HOME/bin/java" \
        "-agentlib:native-image-agent=$agent_mode=$metadata,config-write-period-secs=5,config-write-initial-delay-secs=1" \
        --enable-native-access=ALL-UNNAMED "-Duser.home=$trace_profile/home" \
        "-Djava.io.tmpdir=$trace_profile/temp" "-Dskiko.data.path=$trace_profile/skiko" \
        -cp "$classpath" NativeImageMain --trace-desktop \
        2>&1 | tee "$build/trace-$gpu.log"
done
test -s "$metadata/reachability-metadata.json"

cd "$build"
args=(--no-fallback --enable-native-access=ALL-UNNAMED -march=compatibility
    "-H:ConfigurationFileDirectories=$metadata,$root/.github/native-image/metadata,$root/.github/native-image/metadata-linux"
    -cp "$classpath")
# Instrumentation controls its optimization level; the final build uses -O3.
"$JAVA_HOME/bin/native-image" "${args[@]}" --pgo-instrument NativeImageMain NCMConverter4a-instrumented

# AWT still uses shared libraries. Match its java.home-relative support layout.
if [[ ! -f libjawt.so ]]; then cp -p "$JAVA_HOME/lib/libjawt.so" libjawt.so; fi
mkdir -p lib
cp -p libjawt.so lib/libjawt.so
for file in "$JAVA_HOME"/lib/fontconfig*; do
    if [[ -f "$file" ]]; then cp -p "$file" lib/; fi
done

# Train the current conversion code without opening windows or initializing AWT.
if [[ -e default.iprof ]]; then
    echo 'Unexpected PGO profile already exists; use a clean build directory.' >&2
    exit 1
fi
timeout 90s ./NCMConverter4a-instrumented --pgo-train 2>&1 | tee pgo-train.log
test -s default.iprof
profile="$build/pgo/conversion.iprof"
mv default.iprof "$profile"
profile_option="--pgo=$profile"
"$JAVA_HOME/bin/native-image" "${args[@]}" "$profile_option" -O3 NativeImageMain NCMConverter4a

# Stage an allowlisted runtime. It contains no JDK, classpath,
# build metadata, instrumented binary or profiles.
runtime="$(mktemp -d "$build/runtime.XXXXXX")"
mkdir -p "$runtime/NCMConverter4a/lib"
cp -p NCMConverter4a "$runtime/NCMConverter4a/"
cp -a ./*.so "$runtime/NCMConverter4a/"
cp -a lib/. "$runtime/NCMConverter4a/lib/"
cd "$runtime/NCMConverter4a"
archive="$root/build/native-image/NCMConverter4a-linux-native-image.tar.gz"
tar -czf "$archive" -C "$runtime" NCMConverter4a
# Check the archive itself, with no JDK, extracted libraries or checkout fallback.
validation="$(mktemp -d "$build/portable-smoke.XXXXXX")"
tar -xzf "$archive" -C "$validation"
cd "$validation/NCMConverter4a"
for check in runtime startup-default startup-software file-picker conversion; do
    profile_root="$validation/$check"
    mkdir -p "$profile_root/home" "$profile_root/temp" "$profile_root/skiko" "$profile_root/appdata"
    flag=--startup-smoke
    marker='Compose desktop startup smoke test passed'
    case "$check" in
        runtime) flag=--runtime-smoke; marker='Native Image runtime smoke test passed' ;;
        file-picker) flag=--file-picker-smoke; marker='File picker smoke test passed' ;;
        conversion) flag=--pgo-train; marker='PGO conversion training passed' ;;
        startup-software)
            mkdir -p "$profile_root/appdata/NCMConverter4a"
            printf 'gpuRendering=false\n' > "$profile_root/appdata/NCMConverter4a/rendering.properties"
            ;;
    esac
    env -u JAVA_HOME -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS -u LD_LIBRARY_PATH \
        PATH=/usr/bin:/bin APPDATA="$profile_root/appdata" \
        TMPDIR="$profile_root/temp" XDG_CACHE_HOME="$profile_root/home/.cache" \
        timeout 45s ./NCMConverter4a "-Duser.home=$profile_root/home" \
        "-Djava.io.tmpdir=$profile_root/temp" "-Dskiko.data.path=$profile_root/skiko" "$flag" \
        > "$profile_root/stdout.log" 2> "$profile_root/stderr.log" || {
            cat "$profile_root/stdout.log" "$profile_root/stderr.log"
            exit 1
        }
    cat "$profile_root/stdout.log" "$profile_root/stderr.log"
    grep -Fq "$marker" "$profile_root/stdout.log"
    if grep -Eq 'Exception|Error|FATAL' "$profile_root/stderr.log"; then exit 1; fi
    if [[ "$check" == startup-software ]]; then
        grep -Fq 'Skiko renderer: SOFTWARE' "$profile_root/stdout.log"
    fi
done
ls -lh "$archive"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf 'Linux Native Image archive: %s bytes\n' "$(stat -c %s "$archive")" >> "$GITHUB_STEP_SUMMARY"
fi
