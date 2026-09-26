#!/usr/bin/env bash
# Builds the shared native core and runs the JVM-level checks against it.
#
#   native/tests/check.sh
#
# Needs a C++ compiler, a JDK (for jni.h) and javac/java. On Windows run it from
# Git Bash (JAVA_HOME may point at the JDK).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="$root/cpp"
test_dir="$root/tests"
build="$root/build/check"
classes="$build/classes"
mkdir -p "$classes" "$build/bin"

jdk="${JAVA_HOME:-}"
if [ -z "$jdk" ]; then
    jdk="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
if [ ! -f "$jdk/include/jni.h" ]; then
    echo "jni.h not found under $jdk/include" >&2
    exit 1
fi

cxx="${CXX:-g++}"
os="$(uname -s)"
case "$os" in
    MINGW*|MSYS*|CYGWIN*) lib="ncmc4a.dll" ;;
    Darwin)               lib="libncmc4a.dylib" ;;
    *)                    lib="libncmc4a.so" ;;
esac

echo "== compiling $lib =="
"$cxx" -std=c++17 -O2 -shared -fPIC \
    -I"$source_dir" -I"$jdk/include" -I"$jdk/include/win32" -I"$jdk/include/darwin" -I"$jdk/include/linux" \
    -o "$build/bin/$lib" \
    "$source_dir/RC4Core.cpp" "$source_dir/KGMCore.cpp" "$source_dir/JniBridge.cpp"

echo "== compiling checks =="
javac -d "$classes" -sourcepath "$test_dir/java" \
    "$test_dir/NativeCoreCheck.java" \
    "$test_dir/java/com/cdb96/ncmconverter4a/jni/RC4Decrypt.java" \
    "$test_dir/java/com/cdb96/ncmconverter4a/jni/KGMDecrypt.java"

echo "== running checks =="
java "-Djava.library.path=$build/bin" -cp "$classes" NativeCoreCheck
