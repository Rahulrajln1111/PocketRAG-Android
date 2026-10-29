cmake_minimum_required(VERSION 3.22)
project(pdf_llama_bridge VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Use ccache if available (dramatically speeds up repeated builds)
find_program(CCACHE_FOUND ccache)
if(CCACHE_FOUND)
    set(CMAKE_C_COMPILER_LAUNCHER ccache)
    set(CMAKE_CXX_COMPILER_LAUNCHER ccache)
    message(STATUS "ccache enabled for faster C++ rebuilds")
endif()

# ─── Fetch llama.cpp ─────────────────────────────────────────────────────────
include(FetchContent)

FetchContent_Declare(
    llama.cpp
    GIT_REPOSITORY  https://github.com/ggerganov/llama.cpp.git
    GIT_TAG         b4872        # Latest stable as of early 2026
    GIT_SHALLOW     TRUE
    GIT_PROGRESS    TRUE
)

# Suppress llama.cpp features we don't need on mobile
set(BUILD_SHARED_LIBS             OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS             OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER            OFF CACHE BOOL "" FORCE)
set(LLAMA_STATIC                  ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE                   OFF CACHE BOOL "" FORCE)  # Don't optimise for host CPU
set(GGML_CPU                      ON  CACHE BOOL "" FORCE)
set(GGML_CUDA                     OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN                   OFF CACHE BOOL "" FORCE)
set(GGML_METAL                    OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP                   OFF CACHE BOOL "" FORCE)  # Android NDK doesn't always have OMP
set(GGML_BLAS                     OFF CACHE BOOL "" FORCE)

# On 64-bit ARM devices enable NEON — CMake toolchain sets ANDROID_ARM_NEON=TRUE
# For arm64-v8a enable ARM optimisations
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_ARM                    ON  CACHE BOOL "" FORCE)
endif()

# NDK 25 doesn't support float16 NEON intrinsics on 32-bit ARM (armeabi-v7a)
# Disable llamafile which uses them
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(LLAMA_LLAMAFILE             OFF CACHE BOOL "" FORCE)
endif()

FetchContent_MakeAvailable(llama.cpp)

# ─── Our JNI bridge library ──────────────────────────────────────────────────
add_library(llama_bridge SHARED llama_bridge.cpp)

target_include_directories(llama_bridge PRIVATE
    ${llama.cpp_SOURCE_DIR}
    ${llama.cpp_SOURCE_DIR}/include
    ${llama.cpp_SOURCE_DIR}/ggml/include
    ${llama.cpp_SOURCE_DIR}/common
)

target_link_libraries(llama_bridge
    PRIVATE
        llama
        log                             # Android logging
)

# Export the model data-dir path so the bridge knows where the GGUF file lives
target_compile_definitions(llama_bridge PRIVATE
    MODEL_DIR="/data/data/com.pdfapk/files/models"
)
