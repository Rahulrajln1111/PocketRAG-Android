#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <sstream>
#include <mutex>
#include <cstring>
#include <cinttypes>

#include "llama.h"

#define LOG_TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// -------------------------------------------------------------------
//  Global state
// -------------------------------------------------------------------
static llama_model         *g_model   = nullptr;
static llama_context       *g_ctx     = nullptr;
static llama_sampler       *g_sampler = nullptr;
static const llama_vocab   *g_vocab   = nullptr;
static std::mutex           g_mutex;

static constexpr int32_t MAX_CONTEXT = 8192;
static constexpr int32_t MAX_TOKENS  = 2048;

// -------------------------------------------------------------------
//  Build ChatML prompt for Qwen 2.5
// -------------------------------------------------------------------
static std::string build_chat_prompt(const std::string &system,
                                      const std::string &user) {
    std::ostringstream oss;
    oss << "<|im_start|>system\n" << system << "<|im_end|>\n"
        << "<|im_start|>user\n" << user << "<|im_end|>\n"
        << "<|im_start|>assistant\n";
    return oss.str();
}

// -------------------------------------------------------------------
//  Tokenize via the new API (llama_vocab*)
// -------------------------------------------------------------------
static std::vector<llama_token> tokenize(const std::string &text,
                                          bool add_bos,
                                          bool special) {
    if (!g_vocab) return {};

    int n_tokens = (int)text.size() + (add_bos ? 1 : 0) + 1;
    std::vector<llama_token> result(n_tokens);

    n_tokens = llama_tokenize(g_vocab,
                              text.data(),
                              (int32_t)text.size(),
                              result.data(),
                              (int32_t)result.size(),
                              add_bos,
                              special);

    if (n_tokens < 0) {
        result.resize(-n_tokens);
        n_tokens = llama_tokenize(g_vocab,
                                  text.data(),
                                  (int32_t)text.size(),
                                  result.data(),
                                  (int32_t)result.size(),
                                  add_bos,
                                  special);
    }

    result.resize(n_tokens);
    return result;
}

// -------------------------------------------------------------------
//  JNI: nativeLoadModel
// -------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pdfapk_llm_LlmEngine_nativeLoadModel(JNIEnv *env,
                                                jobject /* thiz */,
                                                jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model from: %s", path);

    std::lock_guard<std::mutex> lock(g_mutex);

    // Free previous
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);     g_model   = nullptr; }
    g_vocab = nullptr;
    llama_backend_free();
    llama_backend_init();

    // Model params — CPU only for broad Android compatibility
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    // Get vocab from model (new API)
    g_vocab = llama_model_get_vocab(g_model);
    if (!g_vocab) {
        LOGE("Failed to get vocab");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    // Context params
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx    = MAX_CONTEXT;
    ctx_params.n_batch  = 512;
    ctx_params.n_ubatch = 512;
    ctx_params.no_perf  = true;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
        return JNI_FALSE;
    }

    // Greedy sampler chain (deterministic, good for summarisation / translation)
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_greedy());

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

// -------------------------------------------------------------------
//  JNI: nativeIsModelLoaded
// -------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pdfapk_llm_LlmEngine_nativeIsModelLoaded(JNIEnv *, jobject) {
    return g_model != nullptr ? JNI_TRUE : JNI_FALSE;
}

// -------------------------------------------------------------------
//  JNI: nativeUnloadModel
// -------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_pdfapk_llm_LlmEngine_nativeUnloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);     g_model   = nullptr; }
    g_vocab = nullptr;
    llama_backend_free();
    LOGI("Model unloaded");
}

// -------------------------------------------------------------------
//  JNI: nativeGenerate — streaming via Java callback
// -------------------------------------------------------------------
//  Callback: void onToken(String partialText, boolean isComplete)
//
extern "C" JNIEXPORT void JNICALL
Java_com_pdfapk_llm_LlmEngine_nativeGenerate(JNIEnv *env,
                                              jobject /* thiz */,
                                              jstring system_prompt,
                                              jstring user_prompt,
                                              jobject callback) {
    const char *sys = env->GetStringUTFChars(system_prompt, nullptr);
    const char *usr = env->GetStringUTFChars(user_prompt, nullptr);

    std::string prompt = build_chat_prompt(sys, usr);
    env->ReleaseStringUTFChars(system_prompt, sys);
    env->ReleaseStringUTFChars(user_prompt, usr);

    // Locate the callback method
    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_cls, "onToken",
                                           "(Ljava/lang/String;Z)V");
    if (!on_token) {
        LOGE("Callback method onToken not found");
        return;
    }

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx || !g_sampler) {
        jstring err = env->NewStringUTF("Model not loaded");
        env->CallVoidMethod(callback, on_token, err, JNI_TRUE);
        return;
    }

    // Tokenise the prompt
    std::vector<llama_token> tokens = tokenize(prompt, true, true);
    if (tokens.empty()) {
        LOGE("Tokenisation failed");
        return;
    }

    // Eval the prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("llama_decode failed for prompt");
        return;
    }

    // Generate tokens
    std::string result;
    int32_t n_tokens = 0;

    while (n_tokens < MAX_TOKENS) {
        // Apply the sampler chain to get the next token
        llama_token id = llama_sampler_sample(g_sampler, g_ctx, -1);

        // End of generation?
        if (llama_vocab_is_eog(g_vocab, id)) {
            break;
        }

        // Convert token to string
        std::vector<char> buf(16);
        int32_t len = llama_token_to_piece(g_vocab, id, buf.data(), (int32_t)buf.size(), 0, false);
        if (len < 0) {
            buf.resize(-len);
            len = llama_token_to_piece(g_vocab, id, buf.data(), (int32_t)buf.size(), 0, false);
        }
        if (len <= 0) {
            // Skip control / unknown tokens
            llama_token batch_tokens[] = { id };
            llama_batch next = llama_batch_get_one(batch_tokens, 1);
            llama_decode(g_ctx, next);
            n_tokens++;
            continue;
        }

        std::string piece(buf.data(), len);
        result += piece;

        // Push partial result to Java callback (cumulative string)
        jstring jpartial = env->NewStringUTF(result.c_str());
        env->CallVoidMethod(callback, on_token, jpartial, JNI_FALSE);
        env->DeleteLocalRef(jpartial);

        // Decode next
        llama_token batch_tokens[] = { id };
        llama_batch next = llama_batch_get_one(batch_tokens, 1);
        if (llama_decode(g_ctx, next) != 0) {
            LOGE("llama_decode failed");
            break;
        }
        n_tokens++;
    }

    // Signal completion with final full string
    jstring jfinal = env->NewStringUTF(result.c_str());
    env->CallVoidMethod(callback, on_token, jfinal, JNI_TRUE);
    env->DeleteLocalRef(jfinal);
}

// -------------------------------------------------------------------
//  JNI: nativeGetModelInfo
// -------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_pdfapk_llm_LlmEngine_nativeGetModelInfo(JNIEnv *env, jobject) {
    if (!g_model) {
        return env->NewStringUTF("No model loaded");
    }
    uint64_t n_params = llama_model_n_params(g_model);
    int32_t  n_ctx    = g_ctx ? llama_n_ctx(g_ctx) : 0;
    int32_t  n_vocab  = g_vocab ? llama_vocab_n_tokens(g_vocab) : 0;

    char buf[128];
    snprintf(buf, sizeof(buf),
             "Params: %" PRIu64 " | Context: %d | Vocab: %d",
             n_params, n_ctx, n_vocab);
    return env->NewStringUTF(buf);
}

// -------------------------------------------------------------------
//  JNI: nativeResetContext
// -------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_pdfapk_llm_LlmEngine_nativeResetContext(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) {
        llama_kv_cache_seq_rm(g_ctx, -1, -1, -1);
        LOGI("KV cache cleared");
    }
}
