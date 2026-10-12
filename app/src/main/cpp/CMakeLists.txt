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

# Image input: also build libmtmd (llama.cpp's multimodal library)
set(LLAMA_BUILD_MTMD ON  CACHE BOOL "" FORCE)
set(MTMD_VIDEO       OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

# Older llama.cpp versions don't know LLAMA_BUILD_MTMD: build tools/mtmd directly in that case
if (NOT TARGET mtmd)
    add_subdirectory(llama.cpp/tools/mtmd ${CMAKE_CURRENT_BINARY_DIR}/mtmd_build)
endif()

add_library(llama_jni SHARED llama_jni.cpp)

# Make sure the compiler (and the editor) can find mtmd.h and mtmd-helper.h
set(MTMD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/tools/mtmd)
if (NOT EXISTS ${MTMD_DIR}/mtmd.h)
    message(FATAL_ERROR "mtmd.h not found in ${MTMD_DIR}. Your llama.cpp copy is too old or incomplete. "
                        "Update it with: git submodule update --init --remote")
endif()
target_include_directories(llama_jni PRIVATE ${MTMD_DIR})

target_link_libraries(llama_jni llama mtmd android log)
