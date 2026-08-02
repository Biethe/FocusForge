// FocusForge JNI layer over llama.cpp.
//
// DESIGN CONSTRAINT (architect, 2026-08-02 amendment 4): every knob the Phase 6
// governor will want to turn — thread count, CPU affinity, n_ctx — is a RUNTIME
// ARGUMENT here, never a compile-time constant. The governor is going to drive this
// interface to benchmark each CPU cluster and then re-tune mid-session.
//
// The model is held open across generations. The first version of this file loaded,
// generated and freed in one call, which made the reported "TTFT" include mapping a
// 386 MB file off flash — 3149 ms on the A20e, of which the overwhelming majority was
// disk. A coach that reloaded the model for every message would also be unusable, and
// a 60-second self-benchmark that reloaded per configuration could not fit in 60
// seconds. So: load once, generate many, and time the two separately.

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

constexpr char SEP = '\x1f';

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

/** An open model plus its context. Owned by Kotlin through an opaque handle. */
struct Session {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
    int  n_ctx   = 0;
    int  threads = 0;
    long load_ms = -1;
    long rss_after_load = -1;

    /**
     * The prompt tokens currently held in the KV cache.
     *
     * Time to first token on the A20e is almost entirely prompt processing: measured across
     * three runs, TTFT tracks prompt length at ~61 ms per token with an intercept near zero
     * (prefill runs at roughly 16 tok/s). Every coaching prompt begins with the same
     * instruction and differs only in the numbers, so re-processing the shared opening every
     * time is pure waste — and it is the difference between meeting the 3000 ms contract and
     * missing it.
     */
    std::vector<llama_token> cached;
};

std::string pack(std::initializer_list<std::string> fields) {
    std::string out;
    bool first = true;
    for (const auto& f : fields) {
        if (!first) out += SEP;
        out += f;
        first = false;
    }
    return out;
}

/**
 * Formats a prompt with the model's own chat template.
 *
 * Not cosmetic. SmolLM2-360M-**Instruct** is trained on ChatML, so a bare sentence looks
 * like the middle of a document with no assistant turn to complete — and the first token
 * it predicts is end-of-generation. That produced a "no tokens generated" failure on the
 * A20e on 2026-08-02 while every layer underneath was working correctly.
 */
std::string apply_template(llama_model* model, const char* prompt, bool* used) {
    *used = false;
    const char* tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr) return prompt;

    llama_chat_message msg{"user", prompt};
    std::vector<char> buf(std::strlen(prompt) + 2048);
    int n = llama_chat_apply_template(tmpl, &msg, 1, /*add_assistant=*/true,
                                      buf.data(), (int32_t) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
    }
    if (n <= 0) return prompt;
    *used = true;
    return std::string(buf.data(), n);
}

}  // namespace

extern "C" {

/**
 * Opens a model. Returns: ok | error | handle | loadMs | rssAfterLoad
 *
 * `threads` and `nCtx` are per-session because llama.cpp fixes them at context creation:
 * the governor changing thread placement means opening a new session, which is why load
 * time is measured and reported rather than assumed to be free.
 */
JNIEXPORT jstring JNICALL
Java_com_focusforge_LlamaBridge_nativeLoad(JNIEnv* env, jobject /*thiz*/,
                                           jstring j_path, jint threads, jint n_ctx) {
    ensure_backend();
    const char* path = env->GetStringUTFChars(j_path, nullptr);

    const long t0 = now_ms();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;                     // CPU only: no usable GPU backend here
    mparams.load_mode    = LLAMA_LOAD_MODE_MMAP;  // amendment 2: mmap, not a full read

    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(j_path, path);

    if (!model) {
        return env->NewStringUTF(
            pack({"0", "llama_model_load_from_file returned null (bad path or corrupt GGUF?)",
                  "0", "-1", "-1"}).c_str());
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) n_ctx;
    cparams.n_batch         = (uint32_t) n_ctx;
    cparams.n_threads       = threads;
    cparams.n_threads_batch = threads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        return env->NewStringUTF(
            pack({"0", "llama_init_from_model returned null", "0", "-1", "-1"}).c_str());
    }

    Session* s = new Session();
    s->model   = model;
    s->ctx     = ctx;
    s->vocab   = llama_model_get_vocab(model);
    s->n_ctx   = n_ctx;
    s->threads = threads;
    s->load_ms = now_ms() - t0;
    s->rss_after_load = rss_bytes();

    LOGI("model loaded in %ld ms, RSS = %ld bytes", s->load_ms, s->rss_after_load);
    return env->NewStringUTF(
        pack({"1", "", std::to_string((jlong) (intptr_t) s),
              std::to_string(s->load_ms), std::to_string(s->rss_after_load)}).c_str());
}

/**
 * Generates from an already-open session. Returns:
 *   ok | error | text | ttftMs | decodeMs | tokens | rssAfterGen
 *   | promptTokens | firstTokenId | firstWasEog | usedChatTemplate
 *
 * TTFT here is what the performance contract means by TTFT: the model is resident, so it
 * is prompt processing plus one token, with no disk in the path.
 */
JNIEXPORT jstring JNICALL
Java_com_focusforge_LlamaBridge_nativeGenerate(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                               jstring j_prompt, jint max_tokens) {
    Session* s = (Session*) (intptr_t) handle;
    if (s == nullptr || s->ctx == nullptr) {
        return env->NewStringUTF(
            pack({"0", "no open model session", "", "-1", "-1", "0", "-1",
                  "0", "-1", "0", "0", "0"}).c_str());
    }

    const char* prompt = env->GetStringUTFChars(j_prompt, nullptr);
    bool used_template = false;
    const std::string formatted = apply_template(s->model, prompt, &used_template);
    env->ReleaseStringUTFChars(j_prompt, prompt);

    const long t_start = now_ms();

    const int n_prompt = -llama_tokenize(s->vocab, formatted.c_str(),
                                         (int32_t) formatted.size(), nullptr, 0, true, true);
    if (n_prompt <= 0 || n_prompt >= s->n_ctx) {
        return env->NewStringUTF(
            pack({"0",
                  "prompt is " + std::to_string(n_prompt) + " tokens against n_ctx " +
                      std::to_string(s->n_ctx),
                  "", "-1", "-1", "0", std::to_string(rss_bytes()),
                  std::to_string(n_prompt), "-1", "0", used_template ? "1" : "0", "0"}).c_str());
    }
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(s->vocab, formatted.c_str(), (int32_t) formatted.size(),
                       tokens.data(), (int32_t) tokens.size(), true, true) < 0) {
        return env->NewStringUTF(
            pack({"0", "tokenization failed", "", "-1", "-1", "0", std::to_string(rss_bytes()),
                  std::to_string(n_prompt), "-1", "0", used_template ? "1" : "0", "0"}).c_str());
    }

    // Reuse whatever the cache already holds.
    //
    // Compare the new prompt against the tokens still in the KV cache and keep the longest
    // common prefix, dropping everything after it. This is done by comparing token ids rather
    // than by assuming a fixed instruction block, so it keeps working if the prompt is
    // reworded, translated, or restructured — no structural promise to break later.
    //
    // At least one token must always be decoded: llama_decode with an empty batch is an
    // error, and the model needs a position to predict from.
    size_t reuse = 0;
    while (reuse < s->cached.size() && reuse < tokens.size() &&
           s->cached[reuse] == tokens[reuse]) {
        reuse++;
    }
    if (reuse >= tokens.size()) reuse = tokens.size() - 1;

    llama_memory_t mem = llama_get_memory(s->ctx);
    llama_memory_seq_rm(mem, 0, (llama_pos) reuse, -1);
    s->cached.assign(tokens.begin(), tokens.end());

    // Positions continue from what is left in the cache, so only the tail is decoded.
    llama_token* tail = tokens.data() + reuse;
    const int32_t n_tail = (int32_t) (tokens.size() - reuse);

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Greedy: a deterministic sampler makes two runs comparable, which is what both the
    // smoke test and the governor's throughput probe need. The coach uses its own chain.
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    llama_batch batch = llama_batch_get_one(tail, n_tail);

    std::string out;
    std::string error;
    long ttft_ms = -1;
    long t_first = 0;
    int generated = 0;
    int first_id = -1;
    bool first_was_eog = false;

    // llama_batch_get_one BORROWS this pointer — it must outlive the decode that consumes it.
    llama_token next_token = 0;

    for (int pos = (int) reuse; pos + batch.n_tokens < s->n_ctx && generated < max_tokens; ) {
        const int rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            error = "llama_decode failed with code " + std::to_string(rc);
            break;
        }
        pos += batch.n_tokens;

        const llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (ttft_ms < 0) {
            t_first = now_ms();
            ttft_ms = t_first - t_start;
            first_id = id;
            first_was_eog = llama_vocab_is_eog(s->vocab, id);
        }
        if (llama_vocab_is_eog(s->vocab, id)) break;

        char buf[256];
        const int n = llama_token_to_piece(s->vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, n);
        generated++;

        next_token = id;
        batch = llama_batch_get_one(&next_token, 1);
    }

    // Decode time excludes prompt processing, so tok/s describes generation speed rather
    // than being diluted by however long the prompt happened to be.
    const long decode_ms = (ttft_ms < 0) ? -1 : (now_ms() - t_first);
    const long rss_after = rss_bytes();

    llama_sampler_free(smpl);

    const bool ok = error.empty() && generated > 0;
    if (!ok && error.empty()) {
        if (first_was_eog) {
            error = "the model's FIRST token was end-of-generation (id " +
                    std::to_string(first_id) + "). The pipeline ran correctly; the model " +
                    "had nothing to say to this prompt. Chat template " +
                    std::string(used_template ? "WAS" : "was NOT") + " applied.";
        } else {
            error = "no tokens generated (prompt " + std::to_string(n_prompt) +
                    " tokens, first id " + std::to_string(first_id) + ")";
        }
    }

    return env->NewStringUTF(
        pack({ok ? "1" : "0", error, out, std::to_string(ttft_ms), std::to_string(decode_ms),
              std::to_string(generated), std::to_string(rss_after),
              std::to_string(n_prompt), std::to_string(first_id),
              first_was_eog ? "1" : "0", used_template ? "1" : "0",
              std::to_string(reuse)}).c_str());
}

JNIEXPORT void JNICALL
Java_com_focusforge_LlamaBridge_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    Session* s = (Session*) (intptr_t) handle;
    if (s == nullptr) return;
    if (s->ctx)   llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
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
