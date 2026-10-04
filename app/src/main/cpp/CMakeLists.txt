cmake_minimum_required(VERSION 3.22.1)
project(noir_llama_bridge LANGUAGES C CXX)

include(FetchContent)

# Pin the public llama.cpp release. v0.5.0 includes the hunyuan-dense
# architecture used by the official Hy-MT2 Q4_K_M GGUF. The model's
# 1.25-bit AngelSlim/STQ GGUF is deliberately not used here: its kernel is
# still an upstream PR and is not part of this stock runtime.
FetchContent_Declare(
    llama_cpp
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/v0.5.0
    URL_HASH SHA256=fef9ed754f4e031fb5c663c29260feda4ebc241abb68d64a81c0f1df5f1748e2
)

# Keep the embedded runtime CPU-only and small: the GGUF model is downloaded
# separately at first use and is never packaged in the APK.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build llama.cpp static libraries" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "No CLI/common layer in Android app" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "No llama.cpp tests in Android app" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "No llama.cpp examples in Android app" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "No llama.cpp tools in Android app" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "No llama.cpp server in Android app" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "No llama.cpp app in Android app" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "No native network stack" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "No subprocesses on Android" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "Use portable Android CPU baseline" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Avoid OpenMP runtime dependency" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "Link CPU backend statically" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "No llamafile backend" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "Use runtime-safe portable CPU kernels" FORCE)

FetchContent_MakeAvailable(llama_cpp)

add_library(noir_llama SHARED NoirLlamaNative.cpp)
target_compile_features(noir_llama PRIVATE cxx_std_17)
target_link_libraries(noir_llama PRIVATE llama android log)
