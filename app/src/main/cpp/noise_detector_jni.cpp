#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <atomic>
#include <cfenv>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace {
constexpr int kSampleRate = 16000;
constexpr int kWindow = 512;
constexpr int kMaxSamples = 30 * kSampleRate;
struct Detector {
    explicit Detector(whisper_vad_context *value) : context(value) {}
    ~Detector() { whisper_vad_free(context); }
    whisper_vad_context *context;
    std::mutex work;
    std::atomic<bool> closed{false};
#if defined(FOCAL_NOISE_DIAGNOSTIC)
    std::atomic<bool> abortNextGraph{false};
#endif
};
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Detector>> registry;
jlong nextHandle = 1;

void fail(JNIEnv *env, const char *kind, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(kind);
    if (cls) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
}
void stateError(JNIEnv *env, const char *message) { fail(env, "java/lang/IllegalStateException", message); }
void argumentError(JNIEnv *env, const char *message) { fail(env, "java/lang/IllegalArgumentException", message); }
std::shared_ptr<Detector> lookup(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto found = registry.find(handle);
    return found == registry.end() ? nullptr : found->second;
}

struct CancellationCheck {
    JNIEnv *env;
    jobject callback;
    jmethodID invoke;
    bool check() const {
        jobject ignored = env->CallObjectMethod(callback, invoke);
        if (ignored) env->DeleteLocalRef(ignored);
        return !env->ExceptionCheck();
    }
};

#if defined(FOCAL_NOISE_DIAGNOSTIC)
struct InjectedGraphAbort {
    std::atomic<int> calls{0};
    static bool check(void *data) {
        // The first two checks are the API preflight and first window. The
        // CPU backend performs the third check after a graph node has run.
        return ++static_cast<InjectedGraphAbort *>(data)->calls >= 3;
    }
};
#endif

bool roundPcm16(double value, int16_t &result) {
    if (!std::isfinite(value) || value < -32769.0 || value > 32768.0) return false;
    const double lower = std::floor(value), fraction = value - lower;
    int rounded = static_cast<int>(lower);
    if (fraction > .5 || (fraction == .5 && rounded % 2 != 0)) ++rounded;
    if (rounded == 32768 && value <= 32768.0) rounded = 32767;
    if (rounded < -32768 || rounded > 32767) return false;
    result = static_cast<int16_t>(rounded);
    return true;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_focal_speech_NoiseDetector_openNative(JNIEnv *env, jobject, jstring path) {
    if (!path) { argumentError(env, "Missing speech presence model."); return 0; }
    const char *name = env->GetStringUTFChars(path, nullptr);
    if (!name) return 0;
    try {
        auto params = whisper_vad_default_context_params();
        params.use_gpu = false;
        params.n_threads = 1;
        std::unique_ptr<whisper_vad_context, decltype(&whisper_vad_free)> context(
            whisper_vad_init_from_file_with_params(name, params), whisper_vad_free);
        env->ReleaseStringUTFChars(path, name);
        name = nullptr;
        if (!context) { stateError(env, "Could not open the speech presence model."); return 0; }
        auto detector = std::make_shared<Detector>(context.get());
        context.release();
        std::lock_guard<std::mutex> lock(registryMutex);
        if (nextHandle == std::numeric_limits<jlong>::max()) {
            stateError(env, "No speech presence handles are available."); return 0;
        }
        const jlong handle = nextHandle++;
        registry.emplace(handle, std::move(detector));
        return handle;
    } catch (...) {
        if (name) env->ReleaseStringUTFChars(path, name);
        stateError(env, "Could not open the speech presence model.");
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_focal_speech_NoiseDetector_detectNative(JNIEnv *env, jobject, jlong handle,
                                                jfloatArray input, jobject callback) {
    try {
        const auto detector = lookup(handle);
        if (!detector || detector->closed.load()) { stateError(env, "Speech presence detector is closed."); return JNI_FALSE; }
        std::unique_lock<std::mutex> lock(detector->work, std::try_to_lock);
        if (!lock.owns_lock()) { stateError(env, "Speech presence detector is busy."); return JNI_FALSE; }
        if (!input || !callback) { argumentError(env, "Missing speech presence input."); return JNI_FALSE; }
        const int count = env->GetArrayLength(input);
        if (count <= 0 || count > kMaxSamples) { argumentError(env, "Invalid speech presence input length."); return JNI_FALSE; }
        if (std::fegetround() != FE_TONEAREST) { stateError(env, "Unexpected audio rounding mode."); return JNI_FALSE; }
        jclass callbackClass = env->GetObjectClass(callback);
        if (!callbackClass) return JNI_FALSE;
        jmethodID invoke = env->GetMethodID(callbackClass, "invoke", "()Ljava/lang/Object;");
        env->DeleteLocalRef(callbackClass);
        if (!invoke) return JNI_FALSE;
        const CancellationCheck cancellation{env, callback, invoke};
        std::vector<float> source(count), conditioned(count);
        env->GetFloatArrayRegion(input, 0, count, source.data());
        if (env->ExceptionCheck()) return JNI_FALSE;
        double envelope = 0.0;
        for (int start = 0; start < count; start += kWindow) {
            if (!cancellation.check()) return JNI_FALSE;
            if (detector->closed.load()) { stateError(env, "Speech presence detector is closed."); return JNI_FALSE; }
            const int length = std::min(kWindow, count - start);
            double squares = 0.0;
            for (int i = 0; i < length; ++i) {
                const double sample = source[start + i];
                if (!std::isfinite(sample) || sample < -1.0 || sample > 1.0) {
                    argumentError(env, "Speech presence samples must be finite normalized PCM."); return JNI_FALSE;
                }
                squares += sample * sample;
            }
            const double rms = std::sqrt(squares / length);
            const double release = std::exp(-static_cast<double>(length) / kSampleRate / 0.5);
            envelope = std::max(rms, envelope * release);
            const double gain = std::min(30.0, std::max(1.0, .03 / std::max(envelope, 1e-12)));
            for (int i = 0; i < length; ++i) {
                int16_t value = 0;
                if (!roundPcm16(static_cast<double>(source[start + i]) * gain * 32768.0, value)) {
                    argumentError(env, "Speech presence conditioning would clip."); return JNI_FALSE;
                }
                conditioned[start + i] = static_cast<float>(value) / 32768.f;
            }
        }
        if (!cancellation.check()) return JNI_FALSE;
        // The combined overlay returns false and clears probabilities on graph failure.
        // Normal builds install no callback: Kotlin checks cancellation around the bounded call.
#if defined(FOCAL_NOISE_DIAGNOSTIC)
        InjectedGraphAbort injected;
        const bool inject = detector->abortNextGraph.exchange(false);
        const bool succeeded = whisper_vad_detect_speech_with_abort(detector->context, conditioned.data(), count,
            inject ? &InjectedGraphAbort::check : nullptr, inject ? &injected : nullptr);
        if (inject) {
            if (succeeded || injected.calls.load() != 3 || whisper_vad_n_probs(detector->context) != 0) {
                stateError(env, "Graph abort diagnostic failed."); return JNI_FALSE;
            }
            stateError(env, "Injected graph abort confirmed."); return JNI_FALSE;
        }
#else
        const bool succeeded = whisper_vad_detect_speech_with_abort(detector->context, conditioned.data(), count, nullptr, nullptr);
#endif
        if (!succeeded) {
            stateError(env, "Speech presence analysis failed."); return JNI_FALSE;
        }
        if (!cancellation.check()) return JNI_FALSE;
        if (detector->closed.load()) { stateError(env, "Speech presence detector is closed."); return JNI_FALSE; }
        const int windows = (count + kWindow - 1) / kWindow;
        const int probabilitiesCount = whisper_vad_n_probs(detector->context);
        const float *probabilities = whisper_vad_probs(detector->context);
        if (probabilitiesCount != windows || !probabilities) { stateError(env, "Incomplete speech presence analysis."); return JNI_FALSE; }
        for (int i = 0; i < probabilitiesCount; ++i) {
            if (!std::isfinite(probabilities[i]) || probabilities[i] < 0.f || probabilities[i] > 1.f) {
                stateError(env, "Invalid speech presence probabilities."); return JNI_FALSE;
            }
        }
        auto params = whisper_vad_default_params();
        params.threshold = .5f;
        params.min_speech_duration_ms = 250;
        params.min_silence_duration_ms = 600;
        params.max_speech_duration_s = 15.f;
        params.speech_pad_ms = 500;
        params.samples_overlap = 0.f;
        std::unique_ptr<whisper_vad_segments, decltype(&whisper_vad_free_segments)> segments(
            whisper_vad_segments_from_probs(detector->context, params), whisper_vad_free_segments);
        if (!segments) { stateError(env, "Speech presence segmentation failed."); return JNI_FALSE; }
        const int segmentCount = whisper_vad_segments_n_segments(segments.get());
        if (segmentCount < 0 || segmentCount > windows) { stateError(env, "Invalid speech presence segments."); return JNI_FALSE; }
        bool present = false;
        int previousEnd = 0;
        for (int i = 0; i < segmentCount; ++i) {
            const float first = whisper_vad_segments_get_segment_t0(segments.get(), i);
            const float last = whisper_vad_segments_get_segment_t1(segments.get(), i);
            if (!std::isfinite(first) || !std::isfinite(last) || std::floor(first) != first ||
                std::floor(last) != last || first < previousEnd || last <= first ||
                last > std::ceil(windows * kWindow / 160.0)) {
                stateError(env, "Invalid speech presence range."); return JNI_FALSE;
            }
            previousEnd = static_cast<int>(last);
            const int start = std::min(static_cast<int>(first) * 160, count);
            const int end = std::min(static_cast<int>(last) * 160, count);
            present |= end > start;
        }
        if (!cancellation.check()) return JNI_FALSE;
        return present ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        stateError(env, "Speech presence analysis failed.");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_focal_speech_NoiseDetector_closeNative(JNIEnv *, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto found = registry.find(handle);
    if (found != registry.end()) {
        found->second->closed.store(true);
        registry.erase(found);
    }
}

#if defined(FOCAL_NOISE_DIAGNOSTIC)
extern "C" JNIEXPORT void JNICALL
Java_app_focal_speech_NoiseDetectorFaultProbe_arm(JNIEnv *env, jobject, jlong handle) {
    const auto detector = lookup(handle);
    if (!detector || detector->closed.load()) { stateError(env, "Speech presence detector is closed."); return; }
    std::unique_lock<std::mutex> lock(detector->work, std::try_to_lock);
    if (!lock.owns_lock()) { stateError(env, "Speech presence detector is busy."); return; }
    detector->abortNextGraph.store(true);
}
#endif
