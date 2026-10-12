#pragma once
#include <atomic>
#include "llama.h"

// Shared between llama_jni.cpp and vision_jni.cpp
struct Engine {
    llama_model*       model = nullptr;
    llama_context*     ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
};

extern std::atomic<bool> g_stop;   // defined in llama_jni.cpp
