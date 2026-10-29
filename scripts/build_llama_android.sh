#!/usr/bin/env bash
#
# build_llama_android.sh
#
# Cross-compiles llama.cpp for Android (arm64-v8a, armeabi-v7a, x86_64)
# and copies the resulting libraries into app/src/main/jniLibs/<abi>/.
#
# Prerequisites:
#   - Android NDK installed (set via ANDROID_NDK_HOME or detected from $HOME/Android/Sdk)
#   - cmake, ninja, git
#
# Usage:  ./scripts/build_llama_android.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LLAMA_DIR="$PROJECT_DIR/build/llama.cpp"
JNILIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs"

# ── Config ───────────────────────────────────────────────────────────────────
LLAMA_TAG="b4872"          # llama.cpp tag to build
LLAMA_REPO="https://github.com/ggerganov/llama.cpp.git"

# Detect NDK
NDK_PATH="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK_PATH" ]; then
    SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
    if [ -d "$SDK_ROOT/ndk" ]; then
        NDK_PATH=$(ls -d "$SDK_ROOT/ndk/"* 2>/dev/null | sort -V | tail -1)
    fi
fi
if [ -z "$NDK_PATH" ] || [ ! -d "$NDK_PATH" ]; then
    echo "❌ Android NDK not found. Set ANDROID_NDK_HOME or install via SDK Manager."
    exit 1
fi
echo "📦 NDK: $NDK_PATH"

# ── Clone / Fetch llama.cpp ──────────────────────────────────────────────────
if [ -d "$LLAMA_DIR" ]; then
    echo "🔄 Updating llama.cpp in $LLAMA_DIR"
    cd "$LLAMA_DIR"
    git fetch --tags
    git checkout "$LLAMA_TAG" 2>/dev/null || true
else
    echo "📥 Cloning llama.cpp ($LLAMA_TAG)…"
    mkdir -p "$(dirname "$LLAMA_DIR")"
    git clone --depth 1 --branch "$LLAMA_TAG" "$LLAMA_REPO" "$LLAMA_DIR"
fi

# ── Build for each ABI ───────────────────────────────────────────────────────
build_abi() {
    local abi="$1"
    local api_level="$2"
    local toolchain_file="$NDK_PATH/build/cmake/android.toolchain.cmake"

    local build_dir="$LLAMA_DIR/build/android/$abi"
    local install_dir="$LLAMA_DIR/build/install/$abi"

    echo ""
    echo "═══════════════════════════════════════════════════"
    echo "  Building for $abi  (API $api_level)"
    echo "═══════════════════════════════════════════════════"

    cmake -S "$LLAMA_DIR" -B "$build_dir" \
        -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$toolchain_file" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$api_level" \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF \
        -DLLAMA_BUILD_SERVER=OFF \
        -DLLAMA_STATIC=ON \
        -DGGML_NATIVE=OFF \
        -DGGML_CPU=ON \
        -DGGML_CUDA=OFF \
        -DGGML_VULKAN=OFF \
        -DGGML_METAL=OFF \
        -DGGML_OPENMP=OFF \
        -DGGML_BLAS=OFF

    cmake --build "$build_dir" --config Release -j "$(nproc)"

    # The output .a files are in $build_dir/src/
    echo "📁 Installing to $JNILIBS_DIR/$abi/"
    mkdir -p "$JNILIBS_DIR/$abi"
    # Copy static .a files
    find "$build_dir" -name "*.a" -exec cp {} "$JNILIBS_DIR/$abi/" \;
}

# API 29 = Android 10 (our minSdk)
API_LEVEL=29

build_abi "arm64-v8a"   $API_LEVEL
build_abi "armeabi-v7a" $API_LEVEL
build_abi "x86_64"      $API_LEVEL

echo ""
echo "✅ Done! Native libraries placed in: $JNILIBS_DIR"
echo ""
echo "The CMake build in Android Studio will now find libllama.a and link it."
echo "Open the project in Android Studio and build normally."
