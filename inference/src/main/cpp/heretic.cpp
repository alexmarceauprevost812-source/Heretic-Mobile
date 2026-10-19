#include <jni.h>
#include "llama.h"
#include <atomic>
#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

struct Engine {
    std::atomic<bool> stopped{false};
    llama_model * model = nullptr;
    std::string path;
    ~Engine() { if (model) llama_model_free(model); }
};
static Engine * engine(jlong handle) { return reinterpret_cast<Engine *>(handle); }
static bool aborted(void * data) { return static_cast<Engine *>(data)->stopped.load(); }
static bool loading(float, void * data) { return !aborted(data); }
static std::string utf8(JNIEnv * env, jbyteArray bytes) {
    const auto length = env->GetArrayLength(bytes);
    std::string result(length, '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
extern "C" JNIEXPORT jlong JNICALL Java_ca_heretic_inference_LlamaEngine_create(JNIEnv *, jobject) {
    llama_backend_init();
    return reinterpret_cast<jlong>(new Engine());
}
extern "C" JNIEXPORT void JNICALL Java_ca_heretic_inference_LlamaEngine_reset(JNIEnv *, jobject, jlong h) { engine(h)->stopped = false; }
extern "C" JNIEXPORT void JNICALL Java_ca_heretic_inference_LlamaEngine_abort(JNIEnv *, jobject, jlong h) { engine(h)->stopped = true; }
extern "C" JNIEXPORT void JNICALL Java_ca_heretic_inference_LlamaEngine_destroy(JNIEnv *, jobject, jlong h) { delete engine(h); }
extern "C" JNIEXPORT void JNICALL Java_ca_heretic_inference_LlamaEngine_run(
    JNIEnv * env, jobject, jlong h, jstring file, jobjectArray roles, jobjectArray contents, jobject sink, jfloat temperature, jfloat topP, jint maxNew, jint threads) {
    auto * e = engine(h);
    try {
        if (maxNew < 16 || maxNew > 1024 || threads < 1 || threads > 8 ||
            !(temperature >= 0 && temperature <= 2) || !(topP >= 0.05f && topP <= 1))
            throw std::runtime_error("Réglages de génération invalides.");
        if (e->stopped) return;
        const char * chars = env->GetStringUTFChars(file, nullptr);
        std::string path(chars);
        env->ReleaseStringUTFChars(file, chars);
        if (!e->model || path != e->path) {
            if (e->model) { llama_model_free(e->model); e->model = nullptr; }
            auto params = llama_model_default_params();
            params.n_gpu_layers = 0;
            params.progress_callback = loading;
            params.progress_callback_user_data = e;
            e->model = llama_model_load_from_file(path.c_str(), params);
            if (e->stopped) return;
            if (!e->model) throw std::runtime_error("Modèle incompatible ou mémoire insuffisante. Essayez un GGUF plus petit.");
            e->path = path;
        }
        const auto count = env->GetArrayLength(roles);
        std::vector<std::string> roleStrings, texts;
        roleStrings.reserve(count); texts.reserve(count);
        for (int i = 0; i < count; ++i) {
            auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
            const char * value = env->GetStringUTFChars(role, nullptr);
            roleStrings.emplace_back(value);
            env->ReleaseStringUTFChars(role, value);
            env->DeleteLocalRef(role);
            auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
            texts.push_back(utf8(env, bytes));
            env->DeleteLocalRef(bytes);
        }
        std::vector<llama_chat_message> messages;
        for (int i = 0; i < count; ++i) messages.push_back({roleStrings[i].c_str(), texts[i].c_str()});
        const char * tmpl = llama_model_chat_template(e->model, nullptr);
        if (!tmpl) tmpl = "chatml";
        int size = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, nullptr, 0);
        if (size <= 0) throw std::runtime_error("Le format de conversation de ce modèle n’est pas pris en charge.");
        std::vector<char> formatted(size + 1);
        size = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, formatted.data(), formatted.size());
        if (size <= 0 || size >= static_cast<int>(formatted.size())) throw std::runtime_error("Format de conversation invalide.");
        const auto * vocab = llama_model_get_vocab(e->model);
        int n = -llama_tokenize(vocab, formatted.data(), size, nullptr, 0, true, true);
        constexpr int contextSize = 2048;
        if (n <= 0 || n > contextSize - maxNew) throw std::runtime_error("Conversation trop longue (contexte 2 048 tokens). Créez une nouvelle discussion.");
        std::vector<llama_token> tokens(n);
        if (llama_tokenize(vocab, formatted.data(), size, tokens.data(), n, true, true) < 0) throw std::runtime_error("Échec de tokenisation.");
        auto params = llama_context_default_params();
        params.n_ctx = contextSize; params.n_batch = 256; params.n_ubatch = 128;
        params.n_threads = threads; params.n_threads_batch = threads;
        params.abort_callback = aborted; params.abort_callback_data = e;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(e->model, params), llama_free);
        if (!ctx) throw std::runtime_error("Mémoire insuffisante pour le contexte.");
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        for (int offset = 0; offset < n; offset += 256) {
            if (e->stopped) return;
            const int result = llama_decode(ctx.get(), llama_batch_get_one(tokens.data() + offset, std::min(256, n - offset)));
            if (e->stopped) return;
            if (result != 0) throw std::runtime_error("Erreur pendant la lecture du contexte.");
        }
        auto sinkClass = env->GetObjectClass(sink);
        auto callback = env->GetMethodID(sinkClass, "onBytes", "([B)V");
        env->DeleteLocalRef(sinkClass);
        if (!callback || env->ExceptionCheck()) return;
        for (int i = 0; i < maxNew && !e->stopped; ++i) {
            llama_token token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            std::vector<char> piece(256);
            int length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (length < 0) { piece.resize(-length); length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (length < 0) throw std::runtime_error("Décodage du texte impossible.");
            auto bytes = env->NewByteArray(length);
            env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(piece.data()));
            env->CallVoidMethod(sink, callback, bytes);
            env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck() || e->stopped) return;
            int result = llama_decode(ctx.get(), llama_batch_get_one(&token, 1));
            if (e->stopped) return;
            if (result != 0) throw std::runtime_error("Erreur pendant la génération.");
        }
    } catch (const std::exception & error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
    }
}

extern "C" JNIEXPORT void JNICALL Java_ca_heretic_inference_LlamaEngine_quantize(
    JNIEnv * env, jobject, jstring source, jstring destination, jint format) {
    try {
        const char * src = env->GetStringUTFChars(source, nullptr);
        std::string input(src); env->ReleaseStringUTFChars(source, src);
        const char * dst = env->GetStringUTFChars(destination, nullptr);
        std::string output(dst); env->ReleaseStringUTFChars(destination, dst);
        if (input == output) throw std::runtime_error("La conversion doit créer un nouveau fichier.");
        auto params = llama_model_quantize_default_params();
        params.nthread = 4;
        params.allow_requantize = true;
        switch (format) {
            case 4: params.ftype = LLAMA_FTYPE_MOSTLY_Q4_K_M; break;
            case 5: params.ftype = LLAMA_FTYPE_MOSTLY_Q5_K_M; break;
            case 8: params.ftype = LLAMA_FTYPE_MOSTLY_Q8_0; break;
            default: throw std::runtime_error("Format de conversion inconnu.");
        }
        if (llama_model_quantize(input.c_str(), output.c_str(), &params) != 0)
            throw std::runtime_error("Conversion impossible : modèle incompatible, mémoire ou espace insuffisants.");
    } catch (const std::exception & error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
    }
}
