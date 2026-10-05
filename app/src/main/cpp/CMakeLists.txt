cmake_minimum_required(VERSION 3.22.1)
project("llama_jni" C CXX)

set(CMAKE_CXX_STANDARD 17)

# Don't build llama.cpp extras we don't need
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

# Enable ARM optimizations for the A14's Cortex-A78/A55 cores
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)

add_subdirectory(llama.cpp)

add_library(llama_jni SHARED llama_jni.cpp)
target_link_libraries(llama_jni llama android log)