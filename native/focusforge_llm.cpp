// FocusForge JNI layer over llama.cpp.
//
// DESIGN CONSTRAINT (architect, 2026-08-02 amendment 4): every knob the Phase 6
// governor will want to turn — thread count, CPU affinity, n_ctx — is a RUNTIME
// ARGUMENT here, never a compile-time constant. The governor is going to drive this
// interface to benchmark each CPU cluster and then re-tune mid-session; anything
// baked in at build time would have to be torn out again a week from now.
//
// Everything is measured, nothing is estimated: TTFT is the wall clock from the
// start of the call to the first sampled token, and RSS is read from
// /proc/self/statm at the moments the architect asked for (after load, after first
// generation).

#include <jni.h>
#include <android/log.h>

#include <unistd.h>   // sysconf, for the page size behind the RSS reading

#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "FocusForgeLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Resident set size in bytes, straight from the kernel. The 700 MB budget in
// CLAUDE.md §2 is written against VmRSS, so that is what we report — not Java heap,
// which would miss the mmapped model entirely.
long rss_bytes() {
    FILE* f = std::fopen("/proc/self/statm", "r");
    if (!f) return -1;
    long size = 0, resident = 0;
    if (std::fscanf(f, "%ld %ld", &size, &resident) != 2) {
        std::fclose(f);
        return -1;
    }
    std::fclose(f);
    return resident * sysconf(_SC_PAGESIZE);
}

long now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

bool g_backend_ready = false;

void ensure_backend() {
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }
}

// One generation run, start to finish. Deliberately stateless: load, generate,
// free. The smoke test needs to prove the whole path works on this silicon, and a
// cached model would hide a load-time failure behind a warm run.
struct RunResult {
    bool ok = false;
    std::string text;
    std::string error;
    long ttft_ms = -1;
    long total_ms = -1;
    int tokens = 0;
    long rss_after_load = -1;
    long rss_after_gen = -1;
};

RunResult run_generation(const char* model_path, const char* prompt, int n_threads,
                         int n_ctx, int max_tokens) {
    RunResult r;
    ensure_backend();

    const long t_start = now_ms();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;                       // CPU only: no usable GPU backend here
    mparams.load_mode    = LLAMA_LOAD_MODE_MMAP;    // amendment 2: mmap, not a full read

    llama_model* model = llama_model_load_from_file(model_path, mparams);
    if (!model) {
        r.error = "llama_model_load_from_file returned null (bad path or corrupt GGUF?)";
        return r;
    }
    r.rss_after_load = rss_bytes();
    LOGI("model loaded, RSS = %ld bytes", r.rss_after_load);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) n_ctx;
    cparams.n_batch         = (uint32_t) n_ctx;
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        r.error = "llama_init_from_model returned null";
        return r;
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    // Tokenize. Ask for the size first rather than guessing a buffer.
    const int n_prompt = -llama_tokenize(vocab, prompt, (int32_t) std::strlen(prompt),
                                         nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt, (int32_t) std::strlen(prompt), tokens.data(),
                       (int32_t) tokens.size(), true, true) < 0) {
        llama_free(ctx);
        llama_model_free(model);
        r.error = "tokenization failed";
        return r;
    }

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Greedy: the smoke test asks "does this silicon execute the kernels", and a
    // deterministic sampler makes two runs comparable. The coach will use a real
    // sampler chain later.
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());

    std::string out;
    long ttft = -1;
    int generated = 0;

    // llama_batch_get_one BORROWS this pointer — it must outlive the decode call that
    // consumes it, so it lives out here rather than inside the loop body.
    llama_token next_token = 0;

    for (int pos = 0; pos + batch.n_tokens < n_ctx && generated < max_tokens; ) {
        if (llama_decode(ctx, batch) != 0) {
            r.error = "llama_decode failed";
            break;
        }
        pos += batch.n_tokens;

        llama_token id = llama_sampler_sample(smpl, ctx, -1);
        if (ttft < 0) {
            ttft = now_ms() - t_start;   // first token out of the model
        }
        if (llama_vocab_is_eog(vocab, id)) break;

        char buf[256];
        const int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, n);
        generated++;

        next_token = id;
        batch = llama_batch_get_one(&next_token, 1);
    }

    r.rss_after_gen = rss_bytes();
    r.total_ms = now_ms() - t_start;
    r.ttft_ms = ttft;
    r.tokens = generated;
    r.text = out;
    r.ok = r.error.empty() && generated > 0;
    if (r.ok == false && r.error.empty()) r.error = "no tokens generated";

    llama_sampler_free(smpl);
    llama_free(ctx);
    llama_model_free(model);
    return r;
}

}  // namespace

extern "C" {

// Returns a single '\x1f'-separated record so the smoke test needs no JSON parser:
//   ok | error | text | ttftMs | totalMs | tokens | rssAfterLoad | rssAfterGen
JNIEXPORT jstring JNICALL
Java_com_focusforge_LlamaBridge_nativeGenerate(JNIEnv* env, jobject /*thiz*/,
                                               jstring j_model_path, jstring j_prompt,
                                               jint n_threads, jint n_ctx, jint max_tokens) {
    const char* model_path = env->GetStringUTFChars(j_model_path, nullptr);
    const char* prompt     = env->GetStringUTFChars(j_prompt, nullptr);

    RunResult r = run_generation(model_path, prompt, n_threads, n_ctx, max_tokens);

    env->ReleaseStringUTFChars(j_model_path, model_path);
    env->ReleaseStringUTFChars(j_prompt, prompt);

    char sep = '\x1f';
    std::string packed;
    packed += (r.ok ? "1" : "0");            packed += sep;
    packed += r.error;                       packed += sep;
    packed += r.text;                        packed += sep;
    packed += std::to_string(r.ttft_ms);     packed += sep;
    packed += std::to_string(r.total_ms);    packed += sep;
    packed += std::to_string(r.tokens);      packed += sep;
    packed += std::to_string(r.rss_after_load); packed += sep;
    packed += std::to_string(r.rss_after_gen);

    return env->NewStringUTF(packed.c_str());
}

// The build-time guarantee, readable at runtime. If this ever reports a feature the
// A20e lacks, the APK is mis-built and we want to see it on screen rather than as a
// SIGILL with no explanation.
JNIEXPORT jstring JNICALL
Java_com_focusforge_LlamaBridge_nativeBuildInfo(JNIEnv* env, jobject /*thiz*/) {
    std::string s = "compiled for: ";
#if defined(__ARM_ARCH)
    s += "ARM v" + std::to_string(__ARM_ARCH);
#else
    s += "unknown arch";
#endif
    s += "  features:";
#if defined(__ARM_FEATURE_DOTPROD)
    s += " dotprod(!!)";
#endif
#if defined(__ARM_FEATURE_MATMUL_INT8)
    s += " i8mm(!!)";
#endif
#if defined(__ARM_FEATURE_SVE)
    s += " sve(!!)";
#endif
#if defined(__ARM_NEON)
    s += " neon";
#endif
    if (s.find("(!!)") != std::string::npos) {
        s += "  <-- ARMV8.0 BASELINE VIOLATED, this build will SIGILL on the A20e";
    } else {
        s += " (baseline armv8-a, as required)";
    }
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_focusforge_LlamaBridge_nativeRssBytes(JNIEnv* /*env*/, jobject /*thiz*/) {
    return (jlong) rss_bytes();
}

}  // extern "C"
