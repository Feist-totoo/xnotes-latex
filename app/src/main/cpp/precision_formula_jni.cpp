#include <jni.h>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct Request {
    std::atomic<bool> cancelled{false};
    std::chrono::steady_clock::time_point deadline;
};
std::mutex requests_mutex;
std::unordered_map<jlong, std::shared_ptr<Request>> requests;
std::once_flag initialized;
struct Engine {
    // Loading callbacks retain a valid request for the entire model lifetime.
    std::shared_ptr<Request> load_request;
    std::string key;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> ctx{nullptr, llama_free};
    std::unique_ptr<mtmd_context, decltype(&mtmd_free)> vision{nullptr, mtmd_free};
};
// Accessed only by Kotlin's single scheduled worker, including nativeUnload.
std::unique_ptr<Engine> cached_engine;
jlong last_timings[4]{};
using Clock = std::chrono::steady_clock;
jlong millis(Clock::time_point a, Clock::time_point b) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(b-a).count();
}
bool abort_request(void * data) {
    auto * r = static_cast<Request *>(data);
    return r->cancelled || std::chrono::steady_clock::now() >= r->deadline;
}
bool load_progress(float, void * data) { return !abort_request(data); }
void quiet_log(ggml_log_level, const char *, void *) {}
void require(bool success, const char * message) {
    if (!success) throw std::runtime_error(message);
}
std::string utf8(JNIEnv * env, jstring s) {
    const char * p = env->GetStringUTFChars(s, nullptr);
    if (!p) throw std::runtime_error("Cannot read model path");
    std::string result(p);
    env->ReleaseStringUTFChars(s, p);
    return result;
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_xnotes_platform_PrecisionFormulaRecognizer_nativeCreate(JNIEnv *, jobject, jlong id) {
    auto r = std::make_shared<Request>();
    r->deadline = std::chrono::steady_clock::now() + std::chrono::minutes(3);
    std::lock_guard<std::mutex> lock(requests_mutex);
    requests[id] = std::move(r);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xnotes_platform_PrecisionFormulaRecognizer_nativeCancel(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(requests_mutex);
    auto it = requests.find(id);
    if (it != requests.end()) it->second->cancelled = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xnotes_platform_PrecisionFormulaRecognizer_nativeRelease(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(requests_mutex);
    requests.erase(id);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xnotes_platform_PrecisionFormulaRecognizer_nativeRecognize(
        JNIEnv * env, jobject, jlong id, jstring model_path, jstring vision_path,
        jbyteArray rgb, jint width, jint height, jint threads) {
    try {
        std::shared_ptr<Request> request;
        {
            std::lock_guard<std::mutex> lock(requests_mutex);
            request = requests.at(id);
        }
        require(!abort_request(request.get()), "Recognition cancelled or timed out");
        require(width > 0 && height > 0 && width <= 4096 && height <= 4096 &&
                env->GetArrayLength(rgb) == width * height * 3, "Invalid formula image");
        std::call_once(initialized, [] {
            llama_log_set(quiet_log, nullptr);
            mtmd_helper_log_set(quiet_log, nullptr);
            llama_backend_init();
        });
        const auto started = Clock::now();
        const auto model_file = utf8(env, model_path), vision_file = utf8(env, vision_path);
        const auto key = model_file + "\n" + vision_file;
        const bool warm = cached_engine && cached_engine->key == key;
        if (!warm) {
            cached_engine.reset();
            auto candidate = std::make_unique<Engine>();
            candidate->load_request = request;
            candidate->key = key;
            auto mp = llama_model_default_params();
            mp.n_gpu_layers = 0;
            mp.use_mmap = true;
            mp.progress_callback = load_progress;
            mp.progress_callback_user_data = request.get();
            candidate->model.reset(llama_model_load_from_file(model_file.c_str(), mp));
            auto & model = candidate->model;
            require(bool(model), "Cannot load offline model (memory or model file)");
            auto cp = llama_context_default_params();
            cp.n_ctx = 4096;
            cp.n_batch = 512;
            cp.n_ubatch = 512;
            cp.n_threads = cp.n_threads_batch = threads;
            cp.abort_callback = abort_request;
            cp.abort_callback_data = request.get();
            candidate->ctx.reset(llama_init_from_model(model.get(), cp));
            auto & ctx = candidate->ctx;
            require(bool(ctx), "Cannot allocate offline recognition memory");
            auto vp = mtmd_context_params_default();
            vp.use_gpu = false;
            vp.n_threads = threads;
            vp.warmup = false;
            vp.print_timings = false;
            vp.progress_callback = load_progress;
            vp.progress_callback_user_data = request.get();
            candidate->vision.reset(mtmd_init_from_file(vision_file.c_str(), model.get(), vp));
            auto & vision = candidate->vision;
            require(bool(vision), "Cannot load offline vision model");
            cached_engine = std::move(candidate);
        }
        auto & model = cached_engine->model;
        auto & ctx = cached_engine->ctx;
        auto & vision = cached_engine->vision;
        llama_set_abort_callback(ctx.get(), abort_request, request.get());
        // Never carry an earlier expression or KV state into the next request.
        llama_memory_clear(llama_get_memory(ctx.get()), true);
        const auto loaded = Clock::now();
        require(!abort_request(request.get()), "Recognition cancelled or timed out");
        std::vector<unsigned char> pixels(width * height * 3);
        env->GetByteArrayRegion(rgb, 0, pixels.size(), reinterpret_cast<jbyte *>(pixels.data()));
        std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)> bitmap(
                mtmd_bitmap_init(width, height, pixels.data()), mtmd_bitmap_free);
        std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(
                mtmd_input_chunks_init(), mtmd_input_chunks_free);
        // Exact single-turn template from the pinned GLM-OCR model. No subject
        // instructions: keep the task prompt identical to the handwriting evaluation.
        std::string prompt = std::string("[gMASK]<sop><|user|>\n") +
                mtmd_default_marker() + "Formula Recognition:<|assistant|>";
        mtmd_input_text text{prompt.c_str(), false, true};
        const mtmd_bitmap * images[] = {bitmap.get()};
        require(mtmd_tokenize(vision.get(), chunks.get(), &text, images, 1) == 0,
                "Cannot tokenize formula image");
        require(mtmd_helper_get_n_tokens(chunks.get()) + 512 < 4096,
                "Selection too large; select a smaller formula");
        llama_pos past = 0;
        require(mtmd_helper_eval_chunks(vision.get(), ctx.get(), chunks.get(), 0, 0, 512, true, &past) == 0,
                "Formula encoding failed or was cancelled");
        require(!abort_request(request.get()), "Recognition cancelled or timed out");
        const auto encoded = Clock::now();
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
                llama_sampler_init_greedy(), llama_sampler_free);
        const auto * vocab = llama_model_get_vocab(model.get());
        std::string output;
        bool finished = false;
        auto batch = llama_batch_init(1, 0, 1);
        // batch owns raw allocations; wrap it so cancellation/errors also free it.
        auto batch_guard = std::unique_ptr<llama_batch, void(*)(llama_batch *)>(
                &batch, [](llama_batch * b) { llama_batch_free(*b); });
        for (int i = 0; i < 512; ++i) {
            require(!abort_request(request.get()), "Recognition cancelled or timed out");
            llama_token token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { finished = true; break; }
            std::vector<char> piece(128);
            int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (n < 0) {
                piece.resize(-n);
                n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            }
            require(n >= 0, "Invalid formula token");
            output.append(piece.data(), n);
            require(output.size() <= 16000, "Formula output too long");
            batch.n_tokens = 1;
            batch.token[0] = token;
            batch.pos[0] = past++;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0] = true;
            require(llama_decode(ctx.get(), batch) == 0, "Formula decoding failed or was cancelled");
        }
        require(finished && !output.empty(), "Incomplete formula; select a smaller expression");
        last_timings[0] = millis(started, loaded);
        last_timings[1] = millis(loaded, encoded);
        last_timings[2] = millis(encoded, Clock::now());
        last_timings[3] = warm ? 1 : 0;
        // An idle cached context must not retain a callback to this request.
        llama_set_abort_callback(ctx.get(), nullptr, nullptr);
        jbyteArray result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception & e) {
        cached_engine.reset();
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_xnotes_platform_PrecisionFormulaRecognizer_nativeUnload(JNIEnv *, jobject) {
    cached_engine.reset();
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_xnotes_platform_PrecisionFormulaRecognizer_nativeTimings(JNIEnv * env, jobject) {
    auto result = env->NewLongArray(4);
    if (result) env->SetLongArrayRegion(result, 0, 4, last_timings);
    return result;
}
