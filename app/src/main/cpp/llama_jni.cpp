#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include <algorithm>
#include "llama.h"

#define TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define JNI_FN(name) Java_com_example_aichatclient_LlamaBridge_##name

struct Engine {
    llama_model*       model = nullptr;
    llama_context*     ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
};

static bool g_backend_ready = false;
static std::atomic<bool> g_stop(false);

// Length of the longest prefix of s that does not end in a half-finished UTF-8 character
static size_t complete_utf8_len(const std::string& s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4 && (((unsigned char)s[i - 1]) & 0xC0) == 0x80) { i--; back++; }
    if (i == 0) return n;
    unsigned char lead = (unsigned char)s[i - 1];
    int need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    int have = back + 1;
    if (have < need) return i - 1;
    return n;
}

static void emit(JNIEnv* env, jobject cb, jmethodID mid, const std::string& bytes) {
    jbyteArray arr = env->NewByteArray((jsize)bytes.size());
    env->SetByteArrayRegion(arr, 0, (jsize)bytes.size(), (const jbyte*)bytes.data());
    env->CallVoidMethod(cb, mid, arr);
    env->DeleteLocalRef(arr);
}

extern "C" JNIEXPORT jlong JNICALL
JNI_FN(nativeLoad)(JNIEnv* env, jobject, jstring jpath, jint nCtx, jint nThreads) {
    if (!g_backend_ready) { llama_backend_init(); g_backend_ready = true; }

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("Failed to load model"); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = nCtx;
    cp.n_batch         = 512;
    cp.n_threads       = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("Failed to create context"); llama_model_free(model); return 0; }

    Engine* e = new Engine();
    e->model = model;
    e->ctx   = ctx;
    e->vocab = llama_model_get_vocab(model);
    LOGI("Model loaded");
    return (jlong)e;
}

// Returns number of generated tokens, or: -1 not loaded, -2 prompt too long,
// -3 tokenize failed, -4 decode failed
extern "C" JNIEXPORT jint JNICALL
JNI_FN(nativeGenerate)(JNIEnv* env, jobject, jlong ptr, jbyteArray jprompt,
                       jint maxTokens, jfloat temperature, jobject callback) {
    Engine* e = (Engine*)ptr;
    if (!e) return -1;
    g_stop = false;

    jclass cls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cls, "onToken", "([B)V");
    if (!onToken) return -1;

    jsize plen = env->GetArrayLength(jprompt);
    std::string prompt((size_t)plen, '\0');
    if (plen > 0) env->GetByteArrayRegion(jprompt, 0, plen, (jbyte*)&prompt[0]);

    // Start fresh each call (KV-cache reuse can be added later)
    llama_memory_clear(llama_get_memory(e->ctx), true);

    int n = -llama_tokenize(e->vocab, prompt.c_str(), (int)prompt.size(),
                            nullptr, 0, true, true);
    if (n <= 0) return -3;
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(e->vocab, prompt.c_str(), (int)prompt.size(),
                       tokens.data(), (int)tokens.size(), true, true) < 0) return -3;

    int nCtx = (int)llama_n_ctx(e->ctx);
    if (n >= nCtx - 16) return -2;
    maxTokens = std::min((int)maxTokens, nCtx - n);

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Process the prompt in chunks
    const int nb = 512;
    for (int i = 0; i < n; i += nb) {
        if (g_stop) { llama_sampler_free(smpl); return 0; }
        int c = std::min(nb, n - i);
        llama_batch b = llama_batch_get_one(tokens.data() + i, c);
        if (llama_decode(e->ctx, b) != 0) { llama_sampler_free(smpl); return -4; }
    }

    // Generate, streaming each piece back to Java
    std::string pending;
    int generated = 0;
    int rc = 0;
    for (int i = 0; i < maxTokens && !g_stop; i++) {
        llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, t)) break;

        char buf[256];
        int len = llama_token_to_piece(e->vocab, t, buf, sizeof(buf), 0, true);
        if (len > 0) {
            pending.append(buf, len);
            size_t ok = complete_utf8_len(pending);
            if (ok > 0) {
                emit(env, callback, onToken, pending.substr(0, ok));
                pending.erase(0, ok);
            }
        }
        generated++;

        llama_batch b = llama_batch_get_one(&t, 1);
        if (llama_decode(e->ctx, b) != 0) { rc = -4; break; }
    }
    llama_sampler_free(smpl);
    return rc < 0 ? rc : generated;
}

extern "C" JNIEXPORT void JNICALL
JNI_FN(nativeStop)(JNIEnv*, jobject) {
g_stop = true;
}

extern "C" JNIEXPORT void JNICALL
JNI_FN(nativeFree)(JNIEnv*, jobject, jlong ptr) {
Engine* e = (Engine*)ptr;
if (!e) return;
llama_free(e->ctx);
llama_model_free(e->model);
delete e;
}