#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

struct Engine {
    whisper_context *ctx;
    std::atomic<bool> cancelled{false};
};
static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
static std::string quoted(const char *value) {
    std::ostringstream out; out << '"';
    for (const unsigned char c : std::string(value)) {
        if (c == '"' || c == '\\') out << '\\' << c;
        else if (c < 32) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << int(c) << std::dec;
        else out << c;
    }
    out << '"'; return out.str();
}
extern "C" JNIEXPORT jlong JNICALL
Java_app_focal_speech_NativeWhisper_open(JNIEnv *env, jobject, jstring path) {
    const char *file = env->GetStringUTFChars(path, nullptr);
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    auto *ctx = whisper_init_from_file_with_params(file, params);
    env->ReleaseStringUTFChars(path, file);
    if (!ctx) { fail(env, "The speech model could not be opened. Import a complete multilingual base model."); return 0; }
    if (!whisper_is_multilingual(ctx) || whisper_model_n_audio_layer(ctx) != 6) {
        whisper_free(ctx); fail(env, "Choose the multilingual Whisper base model."); return 0;
    }
    return reinterpret_cast<jlong>(new Engine{ctx});
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_focal_speech_NativeWhisper_decode(JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring language, jint threads) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (!engine) { fail(env, "Speech engine is not open."); return nullptr; }
    const auto count = env->GetArrayLength(samples);
    if (count <= 0 || count > 30 * 16000) { fail(env, "Invalid audio chunk length."); return nullptr; }
    std::vector<float> audio(std::max(count, 16000), 0.f);
    env->GetFloatArrayRegion(samples, 0, count, audio.data());
    const char *lang = env->GetStringUTFChars(language, nullptr);
    auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.language = lang;
    p.n_threads = std::clamp(int(threads), 1, 2);
    p.translate = false; p.no_context = true;
    p.print_progress = false; p.print_realtime = false; p.print_timestamps = false; p.print_special = false;
    p.token_timestamps = true; p.split_on_word = true; p.max_len = 180;
    p.suppress_blank = true; p.suppress_nst = true;
    p.temperature = 0.f; p.temperature_inc = 0.f; p.greedy.best_of = 1;
    p.abort_callback = [](void *data) { return static_cast<Engine *>(data)->cancelled.load(); };
    p.abort_callback_user_data = engine;
    const int result = whisper_full(engine->ctx, p, audio.data(), static_cast<int>(audio.size()));
    env->ReleaseStringUTFChars(language, lang);
    if (result != 0) { fail(env, "Transcription stopped. Your saved audio is available to retry."); return nullptr; }
    std::ostringstream json; json << '[';
    const auto segments = whisper_full_n_segments(engine->ctx);
    for (int i = 0; i < segments; i++) {
        if (i) json << ',';
        json << "{\"start\":" << whisper_full_get_segment_t0(engine->ctx, i) * 10
             << ",\"end\":" << whisper_full_get_segment_t1(engine->ctx, i) * 10
             << ",\"text\":" << quoted(whisper_full_get_segment_text(engine->ctx, i))
             << ",\"noSpeech\":" << whisper_full_get_segment_no_speech_prob(engine->ctx, i)
             << ",\"probabilities\":[";
        bool first = true;
        for (int t = 0; t < whisper_full_n_tokens(engine->ctx, i); t++) {
            const auto token = whisper_full_get_token_data(engine->ctx, i, t);
            if (token.id >= whisper_token_eot(engine->ctx)) continue;
            if (!first) json << ',';
            first = false; json << (std::isfinite(token.p) ? token.p : 0.f);
        }
        json << "]}";
    }
    json << ']'; const auto text = json.str();
    auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return bytes;
}
extern "C" JNIEXPORT void JNICALL
Java_app_focal_speech_NativeWhisper_cancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Engine *>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_app_focal_speech_NativeWhisper_close(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine) { whisper_free(engine->ctx); delete engine; }
}
