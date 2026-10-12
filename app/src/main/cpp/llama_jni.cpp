#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>
#include "llama.h"
#include "llama.cpp/tools/mtmd/mtmd.h"
#include "llama.cpp/tools/mtmd/mtmd-helper.h"

#define TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define JNI_FN(name) Java_com_example_aichatclient_LlamaBridge_##name

struct Engine {
    llama_model*       model = nullptr;
    llama_context*     ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
    mtmd_context*      mtmd  = nullptr;     // image input (needs an mmproj file), optional
};

static bool g_backend_ready = false;
static std::atomic<bool> g_stop(false);

// Newer llama.cpp versions have a text_len field in mtmd_input_text, older ones don't.
template <typename T>
static auto set_text_len(T& t, size_t n, int) -> decltype((void)(t.text_len = n), void()) { t.text_len = n; }
template <typename T>
static void set_text_len(T&, size_t, long) {}

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

static llama_sampler* make_sampler(float temperature) {
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return smpl;
}

static std::string bytes_to_string(JNIEnv* env, jbyteArray arr) {
    jsize len = env->GetArrayLength(arr);
    std::string s((size_t)len, '\0');
    if (len > 0) env->GetByteArrayRegion(arr, 0, len, (jbyte*)&s[0]);
    return s;
}

// ------------------------------------------------------------------ load / free

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

extern "C" JNIEXPORT void JNICALL
JNI_FN(nativeFree)(JNIEnv*, jobject, jlong ptr) {
    Engine* e = (Engine*)ptr;
    if (!e) return;
    if (e->mtmd) mtmd_free(e->mtmd);
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

extern "C" JNIEXPORT void JNICALL
JNI_FN(nativeStop)(JNIEnv*, jobject) {
    g_stop = true;
}

// ------------------------------------------------------------------ text generation

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

    std::string prompt = bytes_to_string(env, jprompt);

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

    llama_sampler* smpl = make_sampler(temperature);

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

// ------------------------------------------------------------------ image input (vision models)

extern "C" JNIEXPORT jstring JNICALL
JNI_FN(nativeVisionMarker)(JNIEnv* env, jobject) {
    return env->NewStringUTF(mtmd_default_marker());
}

// Loads the vision projector (mmproj .gguf) that belongs to the already loaded text model.
extern "C" JNIEXPORT jboolean JNICALL
JNI_FN(nativeLoadVision)(JNIEnv* env, jobject, jlong ptr, jstring jpath, jint nThreads) {
    Engine* e = (Engine*)ptr;
    if (!e) return JNI_FALSE;
    if (e->mtmd) { mtmd_free(e->mtmd); e->mtmd = nullptr; }

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    mtmd_context_params mp = mtmd_context_params_default();
    mp.use_gpu = false;                 // CPU only on this phone
    mp.n_threads = nThreads;
    mp.print_timings = false;
    e->mtmd = mtmd_init_from_file(path, e->model, mp);
    env->ReleaseStringUTFChars(jpath, path);

    if (!e->mtmd) { LOGE("Failed to load vision projector"); return JNI_FALSE; }
    if (!mtmd_support_vision(e->mtmd)) {
        LOGE("This projector does not support images");
        mtmd_free(e->mtmd);
        e->mtmd = nullptr;
        return JNI_FALSE;
    }
    LOGI("Vision projector loaded");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
JNI_FN(nativeFreeVision)(JNIEnv*, jobject, jlong ptr) {
    Engine* e = (Engine*)ptr;
    if (e && e->mtmd) { mtmd_free(e->mtmd); e->mtmd = nullptr; }
}

// One image (RGB, 3 bytes per pixel) + a prompt containing the media marker.
// Returns number of generated tokens, or: -1 no vision, -2 too long, -4 decode failed,
// -5 marker/image count mismatch, -6 image could not be processed, -7 image encoding failed
extern "C" JNIEXPORT jint JNICALL
JNI_FN(nativeGenerateImage)(JNIEnv* env, jobject, jlong ptr, jbyteArray jprompt, jbyteArray jrgb,
                            jint width, jint height, jint maxTokens, jfloat temperature,
                            jobject callback) {
    Engine* e = (Engine*)ptr;
    if (!e || !e->mtmd) return -1;
    g_stop = false;

    jclass cls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cls, "onToken", "([B)V");
    if (!onToken) return -1;

    std::string prompt = bytes_to_string(env, jprompt);

    size_t need = (size_t)width * (size_t)height * 3;
    if (width <= 0 || height <= 0 || (size_t)env->GetArrayLength(jrgb) < need) return -6;
    std::vector<unsigned char> rgb(need);
    env->GetByteArrayRegion(jrgb, 0, (jsize)need, (jbyte*)rgb.data());

    mtmd_bitmap* bitmap = mtmd_bitmap_init((uint32_t)width, (uint32_t)height, rgb.data());
    if (!bitmap) return -6;

    llama_memory_clear(llama_get_memory(e->ctx), true);

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    mtmd_input_text txt;
    std::memset(&txt, 0, sizeof(txt));
    txt.text = prompt.c_str();
    set_text_len(txt, prompt.size(), 0);
    txt.add_special = true;
    txt.parse_special = true;

    const mtmd_bitmap* bitmaps[1] = { bitmap };
    int32_t tr = mtmd_tokenize(e->mtmd, chunks, &txt, bitmaps, 1);
    if (tr != 0) {
        LOGE("mtmd_tokenize failed: %d", (int)tr);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        return tr == 1 ? -5 : -6;
    }

    int nCtx = (int)llama_n_ctx(e->ctx);
    int nTok = (int)mtmd_helper_get_n_tokens(chunks);
    if (nTok >= nCtx - 16) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        return -2;
    }
    maxTokens = std::min((int)maxTokens, nCtx - nTok);

    // Reads the image through the vision encoder and decodes text + image tokens (the slow part)
    llama_pos nPast = 0;
    int32_t er = mtmd_helper_eval_chunks(e->mtmd, e->ctx, chunks, 0, 0, 512, true, &nPast);
    if (er != 0) {
        LOGE("mtmd_helper_eval_chunks failed: %d", (int)er);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        return -7;
    }

    llama_sampler* smpl = make_sampler(temperature);
    llama_batch batch = llama_batch_init(1, 0, 1);
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

        // explicit position: mtmd tracks n_past itself (needed for M-RoPE models like Qwen-VL)
        batch.n_tokens     = 1;
        batch.token[0]     = t;
        batch.pos[0]       = nPast;
        batch.n_seq_id[0]  = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0]    = true;
        if (llama_decode(e->ctx, batch) != 0) { rc = -4; break; }
        nPast++;
    }

    llama_batch_free(batch);
    llama_sampler_free(smpl);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);
    return rc < 0 ? rc : generated;
}