cmake_minimum_required(VERSION 3.22.1)

project("omnidocs")

# Force -O2 optimization for llama.cpp even when the APK is built as debug.
# AGP sets CMAKE_BUILD_TYPE=Debug which causes -O0 (no optimization), making
# inference ~15x slower. We override the flags directly to ensure speed.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -DNDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -DNDEBUG")

# Disable features that don't apply to Android builds
set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_AVX OFF CACHE BOOL "" FORCE)
set(LLAMA_AVX2 OFF CACHE BOOL "" FORCE)
set(LLAMA_FMA OFF CACHE BOOL "" FORCE)
set(LLAMA_F16C OFF CACHE BOOL "" FORCE)
set(LLAMA_CLBLAST OFF CACHE BOOL "" FORCE)
set(LLAMA_CUBLAS OFF CACHE BOOL "" FORCE)
set(LLAMA_HIPBLAS OFF CACHE BOOL "" FORCE)
set(LLAMA_VULKAN OFF CACHE BOOL "" FORCE)
set(LLAMA_METAL OFF CACHE BOOL "" FORCE)
set(LLAMA_SYCL OFF CACHE BOOL "" FORCE)
set(LLAMA_KOMPUTE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# --- ARM optimization flags (critical for performance!) ---
# Target armv8.6-a with dotprod + i8mm for Snapdragon 8 Gen 2 / modern ARM CPUs.
# This enables hardware-accelerated int8 matrix multiply (i8mm) and NEON dot
# product instructions — without these, Q4_K quantized matmul falls back to
# scalar code and is ~100x slower.
#
# GGML_CPU_ALL_VARIANTS is NOT used because it requires GGML_BACKEND_DL
# (dynamic backend loading) which doesn't work with our JNI static linking.
set(GGML_CPU_ARM_ARCH "armv8.6-a+dotprod+i8mm" CACHE STRING "" FORCE)
set(GGML_OPENMP ON CACHE BOOL "" FORCE)

# Add llama.cpp as a subdirectory
add_subdirectory(llama.cpp)

# Create our JNI wrapper library
add_library(llama-android SHARED
    llama_jni.cpp
)

# Find required libraries
find_library(log-lib log)

# Link against llama.cpp libraries
target_link_libraries(llama-android
    llama
    ggml
    ggml-base
    ggml-cpu
    ${log-lib}
)

# Include llama.cpp headers
target_include_directories(llama-android PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

# ============================================================================
# Paddle OCR JNI Bridge
# ============================================================================

# Set C++ standard for Paddle
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Paddle Lite libraries path
# Actual extracted structure: PaddleLite/java/libs/arm64-v8a/ and PaddleLite/cxx/include/
# CMAKE_SOURCE_DIR is app/src/main/cpp/, so we need ../../../ to reach app/
set(PADDLE_LITE_DIR "${CMAKE_SOURCE_DIR}/../../../PaddleLite")
set(PADDLE_LITE_LIB_DIR "${PADDLE_LITE_DIR}/java/libs/arm64-v8a")
set(PADDLE_LITE_INCLUDE_DIR "${PADDLE_LITE_DIR}/cxx/include")

# Include Paddle Lite headers
include_directories(${PADDLE_LITE_INCLUDE_DIR})

# Add JNI bridge source
add_library(paddle_ocr_jni SHARED paddle_ocr_jni.cpp)

# Find required libraries
find_library(log-lib log)
find_library(android-lib android)

# Link against Paddle Lite static library (symbol table in .so causes linker issues)
set(PADDLE_LITE_CXX_LIB "${PADDLE_LITE_DIR}/cxx/libs/arm64-v8a/libpaddle_api_light_bundled.a")

# Find OpenMP (needed by Paddle Lite)
find_package(OpenMP REQUIRED)

target_link_libraries(
    paddle_ocr_jni
    ${PADDLE_LITE_CXX_LIB}
    OpenMP::OpenMP_CXX
    ${log-lib}
    ${android-lib}
)
