#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <iomanip>
#include <limits>
#include <locale>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct Engine {
    explicit Engine(whisper_context *context) : ctx(context) {}
    ~Engine() { whisper_free(ctx); }
    whisper_context *ctx;
    std::atomic<bool> cancelled{false};
    std::mutex decoding;
};

// IDs never expose native pointers. An active decode retains shared ownership
// after close removes the ID; cancel can safely race either operation.
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Engine>> engines;
jlong nextHandle = 1;

void raise(JNIEnv *env, const char *kind, const char *message) {
    if (env->ExceptionCheck()) return;
    const auto type = env->FindClass(kind);
    if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}
void fail(JNIEnv *env, const char *message) { raise(env, "java/lang/IllegalStateException", message); }
void invalid(JNIEnv *env, const char *message) { raise(env, "java/lang/IllegalArgumentException", message); }
void cancelled(JNIEnv *env) {
    raise(env, "java/util/concurrent/CancellationException", "Transcription cancelled. Saved audio is available to retry.");
}
void nativeFailure(JNIEnv *env) { fail(env, "The speech engine failed. Your saved audio is available to retry."); }

std::shared_ptr<Engine> findEngine(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto found = engines.find(handle);
    return found == engines.end() ? nullptr : found->second;
}

// JNI's GetStringUTFChars uses modified UTF-8, whereas Whisper expects UTF-8.
// This also preserves supplementary characters in a manually corrected prompt.
bool utf8(JNIEnv *env, jstring value, std::string &out) {
    const auto length = env->GetStringLength(value);
    const auto *chars = env->GetStringChars(value, nullptr);
    if (!chars) return false;
    try {
        for (jsize i = 0; i < length; ++i) {
            unsigned int code = chars[i];
            if (code >= 0xd800 && code <= 0xdbff && i + 1 < length && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
                code = 0x10000 + ((code - 0xd800) << 10) + (chars[++i] - 0xdc00);
            } else if (code >= 0xd800 && code <= 0xdfff) code = 0xfffd;
            if (code < 0x80) out += static_cast<char>(code);
            else if (code < 0x800) {
                out += static_cast<char>(0xc0 | (code >> 6));
                out += static_cast<char>(0x80 | (code & 0x3f));
            } else if (code < 0x10000) {
                out += static_cast<char>(0xe0 | (code >> 12));
                out += static_cast<char>(0x80 | ((code >> 6) & 0x3f));
                out += static_cast<char>(0x80 | (code & 0x3f));
            } else {
                out += static_cast<char>(0xf0 | (code >> 18));
                out += static_cast<char>(0x80 | ((code >> 12) & 0x3f));
                out += static_cast<char>(0x80 | ((code >> 6) & 0x3f));
                out += static_cast<char>(0x80 | (code & 0x3f));
            }
        }
    } catch (...) {
        env->ReleaseStringChars(value, chars);
        throw;
    }
    env->ReleaseStringChars(value, chars);
    return true;
}

bool supportedLanguage(const std::string &language) {
    static const std::vector<std::string> supported{"en", "ru", "de", "fr", "es", "it", "pt"};
    return std::find(supported.begin(), supported.end(), language) != supported.end();
}

std::string quoted(const char *value) {
    std::ostringstream out; out << '"';
    for (const unsigned char c : std::string(value ? value : "")) {
        if (c == '"' || c == '\\') out << '\\' << c;
        else if (c < 32) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << int(c) << std::dec;
        else out << c;
    }
    out << '"'; return out.str();
}

jbyteArray decode(JNIEnv *env, jlong handle, jfloatArray samples, jstring language, jint threads, jstring context) {
    const auto engine = findEngine(handle);
    if (!engine) { fail(env, "Speech engine is not open."); return nullptr; }
    std::unique_lock<std::mutex> lock(engine->decoding, std::try_to_lock);
    if (!lock.owns_lock()) { fail(env, "Speech engine is already transcribing."); return nullptr; }
    if (engine->cancelled.load()) { cancelled(env); return nullptr; }
    if (!samples || !language || !context) { invalid(env, "Speech input is missing."); return nullptr; }
    const auto count = env->GetArrayLength(samples);
    if (count <= 0 || count > 30 * 16000) { invalid(env, "Invalid audio chunk length."); return nullptr; }
    if (env->GetStringLength(context) > 240) { invalid(env, "Speech context is too long."); return nullptr; }
    std::string lang, prompt;
    if (!utf8(env, language, lang) || !utf8(env, context, prompt)) return nullptr;
    if (!supportedLanguage(lang) || whisper_lang_id(lang.c_str()) < 0) {
        invalid(env, "Choose one of the seven supported speech languages."); return nullptr;
    }
    if (prompt.find('\0') != std::string::npos) { invalid(env, "Speech context contains invalid text."); return nullptr; }
    std::vector<float> audio(std::max(count, 16000), 0.f);
    env->GetFloatArrayRegion(samples, 0, count, audio.data());
    if (env->ExceptionCheck()) return nullptr;
    if (std::any_of(audio.begin(), audio.begin() + count, [](float sample) { return !std::isfinite(sample) || sample < -1.f || sample > 1.f; })) {
        invalid(env, "Audio samples must be finite normalized PCM."); return nullptr;
    }
    auto p = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    p.language = lang.c_str();
    p.n_threads = std::clamp(int(threads), 1, 4);
    p.translate = false; p.detect_language = false; p.no_context = true;
    p.initial_prompt = prompt.empty() ? nullptr : prompt.c_str(); p.n_max_text_ctx = 64;
    p.print_progress = false; p.print_realtime = false; p.print_timestamps = false; p.print_special = false;
    p.token_timestamps = false;
    p.suppress_blank = true; p.suppress_nst = true;
    p.temperature = 0.f; p.temperature_inc = 0.f; p.greedy.best_of = 1;
    p.beam_search.beam_size = 3;
    p.abort_callback = [](void *data) { return static_cast<Engine *>(data)->cancelled.load(); };
    p.abort_callback_user_data = engine.get();
    const int result = whisper_full(engine->ctx, p, audio.data(), static_cast<int>(audio.size()));
    // Aborted backends can leave partial segments. Never make their chunk DONE.
    if (engine->cancelled.load()) { cancelled(env); return nullptr; }
    if (result != 0) { fail(env, "Transcription stopped. Your saved audio is available to retry."); return nullptr; }
    std::ostringstream json; json.imbue(std::locale::classic()); json << '[';
    const auto segments = whisper_full_n_segments(engine->ctx);
    for (int i = 0; i < segments; i++) {
        if (i) json << ',';
        const float noSpeech = whisper_full_get_segment_no_speech_prob(engine->ctx, i);
        json << "{\"start\":" << whisper_full_get_segment_t0(engine->ctx, i) * 10
             << ",\"end\":" << whisper_full_get_segment_t1(engine->ctx, i) * 10
             << ",\"text\":" << quoted(whisper_full_get_segment_text(engine->ctx, i))
             << ",\"noSpeech\":" << (std::isfinite(noSpeech) && noSpeech >= 0.f && noSpeech <= 1.f ? noSpeech : 1.f)
             << ",\"probabilities\":[";
        bool first = true;
        for (int t = 0; t < whisper_full_n_tokens(engine->ctx, i); t++) {
            const auto token = whisper_full_get_token_data(engine->ctx, i, t);
            if (token.id >= whisper_token_eot(engine->ctx)) continue;
            if (!first) json << ',';
            first = false; json << (std::isfinite(token.p) && token.p >= 0.f && token.p <= 1.f ? token.p : 0.f);
        }
        json << "]}";
    }
    if (engine->cancelled.load()) { cancelled(env); return nullptr; }
    json << ']'; const auto text = json.str();
    auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return env->ExceptionCheck() ? nullptr : bytes;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_focal_speech_NativeWhisper_open(JNIEnv *env, jobject, jstring path) {
    try {
        if (!path) { invalid(env, "The speech model path is missing."); return 0; }
        std::string file;
        if (!utf8(env, path, file)) return 0;
        if (file.empty() || file.find('\0') != std::string::npos) { invalid(env, "The speech model path is invalid."); return 0; }
        auto params = whisper_context_default_params(); params.use_gpu = false;
        std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(whisper_init_from_file_with_params(file.c_str(), params), whisper_free);
        if (!ctx) { fail(env, "The speech model could not be opened. Import a complete multilingual base model."); return 0; }
        if (!whisper_is_multilingual(ctx.get()) || whisper_model_n_audio_layer(ctx.get()) != 6) {
            fail(env, "Choose the multilingual Whisper base model."); return 0;
        }
        const auto engine = std::make_shared<Engine>(ctx.get()); ctx.release();
        std::lock_guard<std::mutex> lock(registryMutex);
        if (nextHandle == std::numeric_limits<jlong>::max()) { fail(env, "No speech engine handles are available."); return 0; }
        const auto handle = nextHandle++;
        engines.emplace(handle, engine);
        return handle;
    } catch (const std::bad_alloc &) { fail(env, "Not enough memory to open the speech model. Your saved audio is available to retry."); }
      catch (...) { nativeFailure(env); }
    return 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_focal_speech_NativeWhisper_decode(JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring language, jint threads, jstring context) {
    try { return decode(env, handle, samples, language, threads, context); }
    catch (const std::bad_alloc &) { fail(env, "Not enough memory to transcribe. Saved audio is available to retry."); }
    catch (...) { nativeFailure(env); }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_app_focal_speech_NativeWhisper_cancel(JNIEnv *env, jobject, jlong handle) {
    try { if (const auto engine = findEngine(handle)) engine->cancelled.store(true); }
    catch (...) { nativeFailure(env); }
}

extern "C" JNIEXPORT void JNICALL
Java_app_focal_speech_NativeWhisper_close(JNIEnv *env, jobject, jlong handle) {
    try {
        std::shared_ptr<Engine> engine;
        {
            std::lock_guard<std::mutex> lock(registryMutex);
            const auto found = engines.find(handle);
            if (found == engines.end()) return;
            engine = found->second; engines.erase(found);
        }
        engine->cancelled.store(true);
    } catch (...) { nativeFailure(env); }
}
