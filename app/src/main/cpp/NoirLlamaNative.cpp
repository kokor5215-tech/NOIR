#include <jni.h>
#include <android/log.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <exception>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>

namespace {
constexpr int32_t kPromptBatch = 256;

std::once_flag gBackendOnce;

struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    std::atomic<bool> cancelled{false};
};

void throwJava(JNIEnv * env, const char * className, const std::string & message) {
    jclass type = env->FindClass(className);
    if (type != nullptr) {
        env->ThrowNew(type, message.c_str());
        env->DeleteLocalRef(type);
    }
}

std::string fromJString(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

jbyteArray toJByteArray(JNIEnv * env, const std::string & text) {
    if (text.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throwJava(env, "java/lang/IllegalStateException", "Local translation response is too large");
        return nullptr;
    }
    const auto size = static_cast<jsize>(text.size());
    jbyteArray out = env->NewByteArray(size);
    if (out == nullptr) return nullptr;
    if (size > 0) {
        env->SetByteArrayRegion(
            out,
            0,
            size,
            reinterpret_cast<const jbyte *>(text.data())
        );
    }
    return out;
}

std::string tokenToUtf8(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(128);
    int32_t count = llama_token_to_piece(vocab, token, buffer.data(),
                                         static_cast<int32_t>(buffer.size()), 0, false);
    if (count < 0) {
        buffer.resize(static_cast<size_t>(-count));
        count = llama_token_to_piece(vocab, token, buffer.data(),
                                     static_cast<int32_t>(buffer.size()), 0, false);
    }
    if (count <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(count));
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_lagradost_cloudstream3_utils_NoirLlamaBridge_nativeLoadModel(
    JNIEnv * env,
    jobject /* thiz */,
    jstring modelPath,
    jint contextSize,
    jint requestedThreads
) try {
    const std::string path = fromJString(env, modelPath);
    if (path.empty()) {
        throwJava(env, "java/lang/IllegalArgumentException", "Model path is empty");
        return 0;
    }

    std::call_once(gBackendOnce, [] { llama_backend_init(); });

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0; // CPU-only; no network/GPU backend is required.
    llama_model * model = llama_model_load_from_file(path.c_str(), modelParams);
    if (model == nullptr) {
        throwJava(env, "java/lang/IllegalStateException", "Hy-MT2 GGUF could not be loaded by this runtime");
        return 0;
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = static_cast<uint32_t>(
        std::clamp(contextSize, 1024, 8192)
    );
    contextParams.n_batch = 256;
    contextParams.n_ubatch = 64;
    contextParams.n_threads = std::clamp(requestedThreads, 1, 6);
    contextParams.n_threads_batch = contextParams.n_threads;
    contextParams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    contextParams.no_perf = true;

    llama_context * context = llama_init_from_model(model, contextParams);
    if (context == nullptr) {
        llama_model_free(model);
        throwJava(env, "java/lang/IllegalStateException", "Not enough memory to create the local translation context");
        return 0;
    }

    auto * engine = new (std::nothrow) Engine();
    if (engine == nullptr) {
        llama_free(context);
        llama_model_free(model);
        throwJava(env, "java/lang/OutOfMemoryError", "Could not allocate the local translation runtime");
        return 0;
    }
    engine->model = model;
    engine->context = context;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(engine));
} catch (const std::bad_alloc &) {
    throwJava(env, "java/lang/OutOfMemoryError", "Not enough memory to load Hy-MT2");
    return 0;
} catch (const std::exception & error) {
    throwJava(env, "java/lang/IllegalStateException", error.what());
    return 0;
} catch (...) {
    throwJava(env, "java/lang/IllegalStateException", "Unknown error while loading Hy-MT2");
    return 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lagradost_cloudstream3_utils_NoirLlamaBridge_nativeGenerate(
    JNIEnv * env,
    jobject /* thiz */,
    jlong handle,
    jstring promptText,
    jint requestedMaxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jfloat repeatPenalty
) try {
    auto * engine = reinterpret_cast<Engine *>(static_cast<intptr_t>(handle));
    if (engine == nullptr || engine->model == nullptr || engine->context == nullptr) {
        throwJava(env, "java/lang/IllegalStateException", "Local translation runtime is not loaded");
        return nullptr;
    }

    const std::string prompt = fromJString(env, promptText);
    if (prompt.empty()) {
        throwJava(env, "java/lang/IllegalArgumentException", "Translation prompt is empty");
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(engine->context), true);

    // Hy-MT2's published chat_template.jinja for one user message is:
    // <｜hy_begin▁of▁sentence｜><｜hy_User｜>{content}<｜hy_Assistant｜>
    // Keep the exact official token spelling and let llama.cpp tokenize the
    // special tokens from the GGUF vocabulary (parse_special=true).
    const std::string formatted =
        "<｜hy_begin▁of▁sentence｜><｜hy_User｜>" + prompt + "<｜hy_Assistant｜>";

    const llama_vocab * vocab = llama_model_get_vocab(engine->model);
    if (vocab == nullptr) {
        throwJava(env, "java/lang/IllegalStateException", "Hy-MT2 vocabulary is missing");
        return nullptr;
    }

    const int32_t tokenCount = -llama_tokenize(
        vocab,
        formatted.c_str(),
        static_cast<int32_t>(formatted.size()),
        nullptr,
        0,
        false, // The Hy-MT2 chat template already contains its BOS token.
        true
    );
    if (tokenCount <= 0) {
        throwJava(env, "java/lang/IllegalStateException", "Could not tokenize the local translation prompt");
        return nullptr;
    }

    std::vector<llama_token> promptTokens(static_cast<size_t>(tokenCount));
    const int32_t actualTokenCount = llama_tokenize(
        vocab,
        formatted.c_str(),
        static_cast<int32_t>(formatted.size()),
        promptTokens.data(),
        tokenCount,
        false, // The Hy-MT2 chat template already contains its BOS token.
        true
    );
    if (actualTokenCount <= 0) {
        throwJava(env, "java/lang/IllegalStateException", "Could not tokenize the local translation prompt");
        return nullptr;
    }
    promptTokens.resize(static_cast<size_t>(actualTokenCount));

    const uint32_t contextLength = llama_n_ctx(engine->context);
    if (promptTokens.size() >= contextLength) {
        throwJava(env, "java/lang/IllegalArgumentException", "Translation block exceeds the model context window");
        return nullptr;
    }

    for (size_t offset = 0; offset < promptTokens.size(); offset += kPromptBatch) {
        if (engine->cancelled.load(std::memory_order_relaxed)) return toJByteArray(env, "");
        const auto count = static_cast<int32_t>(std::min(
            static_cast<size_t>(kPromptBatch), promptTokens.size() - offset
        ));
        llama_batch batch = llama_batch_get_one(promptTokens.data() + offset, count);
        if (llama_decode(engine->context, batch) != 0) {
            throwJava(env, "java/lang/IllegalStateException", "Hy-MT2 failed while reading the translation block");
            return nullptr;
        }
    }

    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    samplerParams.no_perf = true;
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_chain_init(samplerParams), &llama_sampler_free
    );
    if (sampler == nullptr) {
        throwJava(env, "java/lang/IllegalStateException", "Could not initialize the translation sampler");
        return nullptr;
    }

    const int32_t vocabSize = llama_vocab_n_tokens(vocab);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(
        vocabSize,
        64,
        std::max(1.0f, repeatPenalty),
        0.0f,
        0.0f
    ));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(std::clamp(topK, 1, 100)));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(
        std::clamp(topP, 0.05f, 1.0f),
        1
    ));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(
        std::clamp(temperature, 0.0f, 1.5f)
    ));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    const int32_t maxTokens = std::clamp(requestedMaxTokens, 1, 1400);
    const int32_t available = static_cast<int32_t>(contextLength - promptTokens.size());
    const int32_t budget = std::min(maxTokens, available);
    std::string output;
    output.reserve(static_cast<size_t>(budget) * 6);

    for (int32_t i = 0; i < budget; ++i) {
        if (engine->cancelled.load(std::memory_order_relaxed)) break;
        const llama_token token = llama_sampler_sample(sampler.get(), engine->context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        output += tokenToUtf8(vocab, token);

        if (i + 1 >= budget || engine->cancelled.load(std::memory_order_relaxed)) break;
        llama_batch next = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
        if (llama_decode(engine->context, next) != 0) {
            throwJava(env, "java/lang/IllegalStateException", "Hy-MT2 failed while generating a translation");
            return nullptr;
        }
    }

    return toJByteArray(env, output);
} catch (const std::bad_alloc &) {
    throwJava(env, "java/lang/OutOfMemoryError", "Not enough memory during local translation");
    return nullptr;
} catch (const std::exception & error) {
    throwJava(env, "java/lang/IllegalStateException", error.what());
    return nullptr;
} catch (...) {
    throwJava(env, "java/lang/IllegalStateException", "Unknown error during local translation");
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lagradost_cloudstream3_utils_NoirLlamaBridge_nativeResetCancel(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle
) {
    auto * engine = reinterpret_cast<Engine *>(static_cast<intptr_t>(handle));
    if (engine != nullptr) engine->cancelled.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lagradost_cloudstream3_utils_NoirLlamaBridge_nativeCancel(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle
) {
    auto * engine = reinterpret_cast<Engine *>(static_cast<intptr_t>(handle));
    if (engine != nullptr) engine->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lagradost_cloudstream3_utils_NoirLlamaBridge_nativeFree(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle
) {
    auto * engine = reinterpret_cast<Engine *>(static_cast<intptr_t>(handle));
    if (engine == nullptr) return;
    if (engine->context != nullptr) llama_free(engine->context);
    if (engine->model != nullptr) llama_model_free(engine->model);
    delete engine;
}
