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
    JNIEnv * env, jobject, jlong h, jstring file, jobjectArray roles, jobjectArray contents, jobject sink, jfloat temperature, jfloat topP, jint maxNew, jint threads, jint topK, jfloat minP, jfloat repeatPenalty, jint repeatLastN, jlong seed, jint contextSize) {
    auto * e = engine(h);
    try {
        if (maxNew < 16 || maxNew > 4096 || maxNew >= contextSize || contextSize < 512 || contextSize > 16384 || topK < 0 || topK > 1000 || !(minP >= 0 && minP <= 1) || !(repeatPenalty >= 0.5f && repeatPenalty <= 2) || repeatLastN < 0 || repeatLastN > 8192 || seed < -1 || seed > 4294967294LL || threads < 1 || threads > 8 ||
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
        if (n <= 0 || n > contextSize - maxNew) throw std::runtime_error("Conversation trop longue pour le contexte et la réponse réservée. Créez une nouvelle discussion.");
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
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(repeatLastN, repeatPenalty, 0.0f, 0.0f));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(topK));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_min_p(minP, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
        for (int offset = 0; offset < n; offset += 256) {
            if (e->stopped) return;
            const int result = llama_decode(ctx.get(), llama_batch_get_one(tokens.data() + offset, std::min(256, n - offset)));
            if (e->stopped) return;
            if (result != 0) throw std::runtime_error("Erreur pendant la lecture du contexte.");
        }
        for (auto token : tokens) llama_sampler_accept(sampler.get(), token);
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


extern "C" JNIEXPORT jstring JNICALL Java_ca_heretic_inference_LlamaEngine_inspect(JNIEnv * env, jobject, jstring file) {
    try {
        const char * chars = env->GetStringUTFChars(file, nullptr);
        std::string path(chars); env->ReleaseStringUTFChars(file, chars);
        auto params = llama_model_default_params(); params.vocab_only = true; params.n_gpu_layers = 0;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(path.c_str(), params), llama_model_free);
        if (!model) throw std::runtime_error("Métadonnées incompatibles avec le moteur ou fichier illisible.");
        char desc[512]; llama_model_desc(model.get(), desc, sizeof(desc));
        std::string result = std::string(desc) + "\nContexte d’entraînement : " + std::to_string(llama_model_n_ctx_train(model.get())) +
            " tokens\nMétadonnées reconnues par llama.cpp b5046. La compatibilité complète et la mémoire seront vérifiées à la génération.";
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception & error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what()); return nullptr;
    }
}
